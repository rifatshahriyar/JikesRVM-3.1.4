/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.plan.rcimmix;

import static org.mmtk.policy.rcimmix.RCImmixConstants.PAGES_IN_BLOCK;

import org.mmtk.plan.Phase;
import org.mmtk.plan.StopTheWorld;
import org.mmtk.plan.Trace;
import org.mmtk.policy.ExplicitLargeObjectSpace;
import org.mmtk.policy.Space;
import org.mmtk.policy.rcimmix.RCImmixSpace;
import org.mmtk.policy.rcimmix.RCImmixObjectHeader;
import org.mmtk.utility.Log;
import org.mmtk.utility.alloc.LinearScan;
import org.mmtk.utility.deque.SharedDeque;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.utility.options.CycleTriggerFraction;
import org.mmtk.utility.options.DefragTriggerFraction;
import org.mmtk.utility.options.Options;
import org.mmtk.utility.options.SurvivorCopyMultiplier;
import org.mmtk.utility.sanitychecker.SanityChecker;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Word;

/**
 * This class implements the global state of RCImmix collector.
 */
@Uninterruptible
public class RCImmix extends StopTheWorld {
  public static final short PROCESS_OLDROOTBUFFER  = Phase.createSimple("old-root");
  public static final short PROCESS_NEWROOTBUFFER  = Phase.createSimple("new-root");
  public static final short PROCESS_MODBUFFER      = Phase.createSimple("mods");
  public static final short PROCESS_DECBUFFER      = Phase.createSimple("decs");

  /** Is cycle collection enabled? */
  public static final boolean CC_ENABLED           = true;
  /** Force full cycle collection at each GC? */
  public static boolean CC_FORCE_FULL        = false;
  /** Use backup tracing for cycle collection (currently the only option) */
  public static final boolean CC_BACKUP_TRACE      = true;
  public static final boolean RC_SURVIVOR_COPY = true;

  public static boolean performCycleCollection;
  public static boolean performDefrag;
  public static int cycleTriggerThreshold;
  public static int defragTriggerThreshold;
  public static final short BT_CLOSURE_INIT        = Phase.createSimple("closure-bt-init");
  public static final short BT_CLOSURE             = Phase.createSimple("closure-bt");

  // CHECKSTYLE:OFF

  /**
   * Reference counting specific collection steps.
   */
  protected static final short refCountCollectionPhase = Phase.createComplex("release", null,
      Phase.scheduleGlobal     (PROCESS_OLDROOTBUFFER),
      Phase.scheduleCollector  (PROCESS_OLDROOTBUFFER),
      Phase.scheduleGlobal     (PROCESS_NEWROOTBUFFER),
      Phase.scheduleCollector  (PROCESS_NEWROOTBUFFER),
      Phase.scheduleMutator    (PROCESS_MODBUFFER),
      Phase.scheduleGlobal     (PROCESS_MODBUFFER),
      Phase.scheduleCollector  (PROCESS_MODBUFFER),
      Phase.scheduleMutator    (PROCESS_DECBUFFER),
      Phase.scheduleGlobal     (PROCESS_DECBUFFER),
      Phase.scheduleCollector  (PROCESS_DECBUFFER),
      Phase.scheduleGlobal     (BT_CLOSURE_INIT),
      Phase.scheduleCollector  (BT_CLOSURE_INIT), 
      Phase.scheduleGlobal     (BT_CLOSURE),
      Phase.scheduleCollector  (BT_CLOSURE));     
 
  /**
   * Perform the initial determination of liveness from the roots.
   */
  protected static final short rootClosurePhase = Phase.createComplex("initial-closure", null,
      Phase.scheduleMutator    (PREPARE),
      Phase.scheduleGlobal     (PREPARE),
      Phase.scheduleCollector  (PREPARE),
      Phase.scheduleComplex    (prepareStacks),
      Phase.scheduleCollector  (STACK_ROOTS),
      Phase.scheduleGlobal     (STACK_ROOTS),
      Phase.scheduleCollector  (ROOTS),
      Phase.scheduleGlobal     (ROOTS),
      Phase.scheduleGlobal     (CLOSURE),
      Phase.scheduleCollector  (CLOSURE));

  /**
   * This is the phase that is executed to perform a collection.
   */
  public short collection = Phase.createComplex("collection", null,
      Phase.scheduleComplex(initPhase),
      Phase.scheduleComplex(rootClosurePhase),
      Phase.scheduleComplex(refCountCollectionPhase),
      Phase.scheduleComplex(completeClosurePhase),
      Phase.scheduleComplex(finishPhase));
  
  // CHECKSTYLE:ON

  /*****************************************************************************
   *
   * Class fields
   */
  public static final RCImmixSpace rcSpace = new RCImmixSpace("rc", VMRequest.discontiguous());
  public static final ExplicitLargeObjectSpace rcloSpace = new ExplicitLargeObjectSpace("rclos", VMRequest.discontiguous());

  public static final int REF_COUNT = rcSpace.getDescriptor();
  public static final int REF_COUNT_LOS = rcloSpace.getDescriptor();

  public final SharedDeque modPool = new SharedDeque("mod", metaDataSpace, 1);
  public final SharedDeque decPool = new SharedDeque("dec", metaDataSpace, 1);
  public final SharedDeque newRootPool = new SharedDeque("newRoot", metaDataSpace, 1);
  public final SharedDeque newRootBackPool = new SharedDeque("newRootBack", metaDataSpace, 1);
  public final SharedDeque oldRootPool = new SharedDeque("oldRoot", metaDataSpace, 1);

  /*****************************************************************************
   *
   * Instance fields
   */
  public final Trace rootTrace;
  public final Trace backupTrace;
  private final RCImmixBTLargeSweeper loFreeSweeper;
  public int beginningPagesUsed = 0;
  public static double lineSurvivalRateExp = 1.0f;

  // MAX heuristic for proactive copying
  public static final int N = 4;
  public static double [] a = new double[N];

  /**
   * Constructor
   */
  public RCImmix() {
    Options.noReferenceTypes.setDefaultValue(true);
    Options.noFinalizer.setDefaultValue(true);
    Options.cycleTriggerFraction = new CycleTriggerFraction();
    Options.defragTriggerFraction = new DefragTriggerFraction();
    Options.survivorCopyMultiplier = new SurvivorCopyMultiplier();
    
    rootTrace = new Trace(metaDataSpace);
    backupTrace = new Trace(metaDataSpace);
    loFreeSweeper = new RCImmixBTLargeSweeper();
  }

  @Override
  @Interruptible
  public void processOptions() {
    super.processOptions();
    if (!Options.noReferenceTypes.getValue()) {
      VM.assertions.fail("Reference Types are not supported by RC");
    }
    if (!Options.noFinalizer.getValue()) {
      VM.assertions.fail("Finalizers are not supported by RC");
    }
    cycleTriggerThreshold = (int)(getTotalPages() * Options.cycleTriggerFraction.getValue());
    defragTriggerThreshold = (int)(getTotalPages() * Options.defragTriggerFraction.getValue());
  }

  /*****************************************************************************
   *
   * Collection
   */
  public static final boolean isRCObject(ObjectReference object) {
    return !object.isNull() && !Space.isInSpace(VM_SPACE, object);
  }

  @Override
  public boolean lastCollectionFullHeap() {
    return performCycleCollection;
  }

  @Override
  public void collectionPhase(short phaseId) {
    if (phaseId == SET_COLLECTION_KIND) {
      super.collectionPhase(phaseId);
      if (CC_ENABLED) {
        CC_FORCE_FULL = Options.fullHeapSystemGC.getValue();
        performCycleCollection |= (collectionAttempt > 1) || emergencyCollection || CC_FORCE_FULL;
        RCImmixObjectHeader.performCycleCollection = performCycleCollection;
        if (performCycleCollection && Options.verbose.getValue() > 0) Log.write(" [CC] ");
        if (performCycleCollection) {
          rcSpace.decideWhetherToDefrag(emergencyCollection, true, collectionAttempt, userTriggeredCollection, performDefrag);
          RCImmixObjectHeader.performSurvivorCopy = false;
        } else {
          RCImmixObjectHeader.performSurvivorCopy = true;
        }
      }
      rcSpace.maxCleanPagesForCopy = getCopyReserve();
      rcSpace.exhaustedCopySpace = false;
      return;
    }

    if (phaseId == PREPARE) {
      super.collectionPhase(phaseId);
      VM.finalizableProcessor.clear();
      VM.weakReferences.clear();
      VM.softReferences.clear();
      VM.phantomReferences.clear();
      rootTrace.prepare();
      rcSpace.prepare(true);
      if (CC_BACKUP_TRACE & performCycleCollection) {
        backupTrace.prepare();
      }
      return;
    }

    if (phaseId == CLOSURE) {
      rootTrace.prepare();
      return;
    }

    if (phaseId == PROCESS_OLDROOTBUFFER) {
      oldRootPool.prepare();
      return;
    }

    if (phaseId == PROCESS_NEWROOTBUFFER) {
      newRootPool.prepare();
      return;
    }

    if (phaseId == PROCESS_MODBUFFER) {
      modPool.prepare();
      return;
    }

    if (phaseId == PROCESS_DECBUFFER) {
      decPool.prepare();
      return;
    }

    if (phaseId == BT_CLOSURE_INIT) {
      if (CC_BACKUP_TRACE && performCycleCollection) {
        newRootBackPool.prepare();
      }
      return;
    }

    if (phaseId == BT_CLOSURE) {
      if (CC_BACKUP_TRACE && performCycleCollection) {
        backupTrace.prepare();
      }
      return;
    }

    if (phaseId == RELEASE) {
      rootTrace.release();
      if (CC_BACKUP_TRACE && performCycleCollection) {
        backupTrace.release();
        rcloSpace.sweep(loFreeSweeper);
        RCImmixObjectHeader.markValue = RCImmixObjectHeader.markValue.EQ(RCImmixObjectHeader.MARK_BIT_MASK)? Word.zero() : RCImmixObjectHeader.MARK_BIT_MASK;
        RCImmixObjectHeader.markAllocValue = RCImmixObjectHeader.markAllocValue.EQ(RCImmixObjectHeader.MARK_BIT_MASK)? Word.zero() : RCImmixObjectHeader.MARK_BIT_MASK;
      }
      rcSpace.release(true);
      int availablePages = getPagesAvail();
      beginningPagesUsed = rcSpace.reservedPages();

      // MAX heuristic for proactive copying
      if (!performCycleCollection) {
        for (int i = N-1; i >0; i--) {
          a[i] = a[i-1];
        }
        a[0] = (rcSpace.linesUsed - rcSpace.linesCleaned) / rcSpace.linesUsed;
      }

      lineSurvivalRateExp = max();
      if (lineSurvivalRateExp < 0.0f) lineSurvivalRateExp = 0;

      rcSpace.linesUsed = 0;
      rcSpace.linesCleaned = 0;

      performCycleCollection =  availablePages < cycleTriggerThreshold;
      performDefrag =  availablePages < defragTriggerThreshold;
      RCImmixObjectHeader.performSurvivorCopy = false;

      if (performCycleCollection) lineSurvivalRateExp = 0;

      return;
    }

    super.collectionPhase(phaseId);
  }

  /*****************************************************************************
   *
   * Accounting
   */

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPagesUsed() {
    return (rcSpace.reservedPages() + rcloSpace.reservedPages() + super.getPagesUsed());
  }

  /**
   * Return the number of pages reserved for collection.
   */
  @Override
  public int getCollectionReserve() {
    return super.getCollectionReserve() + rcSpace.defragHeadroomPages() + getCopyReserve();
  }

  /**
   * Perform a linear scan across all objects in the heap to check for leaks.
   */
  @Override
  public void sanityLinearScan(LinearScan scan) {
    //rcSpace.linearScan(scan);
  }

  @Override
  public int sanityExpectedRC(ObjectReference object, int sanityRootRC) {
    if (isRCObject(object)) {
      int fullRC = RCImmixObjectHeader.getRC(object);
      if (fullRC == 0) {
        return SanityChecker.DEAD;
      }
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(fullRC >= sanityRootRC);
      return fullRC - sanityRootRC;
    }
    return SanityChecker.ALIVE;
  }

  @Override
  public boolean willNeverMove(ObjectReference object) {
    if (Space.isInSpace(REF_COUNT, object)) {
      if (!VM.config.PINNING_BIT) return false;
      RCImmixObjectHeader.pinObject(object);
      return true;
    } else if (Space.isInSpace(REF_COUNT_LOS, object)) {
      return true;
    }
    return true;
  }

  /**
   * Register specialized methods.
   */
  @Override
  @Interruptible
  protected void registerSpecializedMethods() {
    super.registerSpecializedMethods();
  }

  @Override
  public void forceFullHeapCollection() {
    performCycleCollection = true;
    performDefrag = true;
  }

  @Inline
  public int getCopyReserve() {
    if (!RC_SURVIVOR_COPY) return 0;
    double copyReserve = lineSurvivalRateExp * (rcSpace.reservedPages() - beginningPagesUsed) * Options.survivorCopyMultiplier.getValue();
    int totalBlocks = (int)copyReserve/PAGES_IN_BLOCK;
    return totalBlocks * PAGES_IN_BLOCK;
  }

  @Inline
  public static double max() {
    double max = a[0];
    for (int i = 1; i < N;i++) {
      if (a[i] > max) max = a[i];
    }
    return max;
  }

  @Override
  public byte setBuildTimeGCByte(Address object, ObjectReference typeRef, int size) {
    byte status = 0;
    status |= RCImmixObjectHeader.UNLOGGED.toInt();
    return status;
  }
}


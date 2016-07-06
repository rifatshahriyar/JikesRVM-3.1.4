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


import org.mmtk.plan.Phase;
import org.mmtk.plan.Plan;
import org.mmtk.plan.StopTheWorldCollector;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.TransitiveClosure;
import org.mmtk.policy.Space;
import org.mmtk.policy.rcimmix.RCImmixCollectorLocal;
import org.mmtk.policy.rcimmix.RCImmixObjectHeader;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.alloc.RCImmixAllocator;
import org.mmtk.utility.deque.AddressDeque;
import org.mmtk.utility.deque.ObjectReferenceDeque;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Word;

/**
 * This class implements the collector context for RCImmix collector.
 */
@Uninterruptible
public class RCImmixCollector extends StopTheWorldCollector {

  /************************************************************************
   * Initialization
   */
  protected final AddressDeque newRootPointerBuffer;
  protected final AddressDeque newRootPointerBackBuffer;
  public TraceLocal backupTrace;
  private final RCImmixBTTraceLocal backTrace;
  private final RCImmixBTDefragTraceLocal defragTrace;
  private final ObjectReferenceDeque modBuffer;
  private final ObjectReferenceDeque oldRootBuffer;
  private final RCImmixDecBuffer decBuffer;
  public final RCImmixZero zero;
  protected RCImmixCollectorLocal rc;
  protected final RCImmixAllocator copy;
  protected final RCImmixAllocator young;
  private final RCImmixRootSetTraceLocal rootTrace;
  private final RCImmixModifiedProcessor modProcessor;

  /**
   * Constructor.
   */
  public RCImmixCollector() {
    newRootPointerBuffer = new AddressDeque("new-root", global().newRootPool);
    newRootPointerBackBuffer = new AddressDeque("new-root-back", global().newRootBackPool);
    oldRootBuffer = new ObjectReferenceDeque("old-root", global().oldRootPool);
    decBuffer = new RCImmixDecBuffer(global().decPool);
    modBuffer = new ObjectReferenceDeque("mod buf", global().modPool);
    backTrace = new RCImmixBTTraceLocal(global().backupTrace);
    defragTrace = new RCImmixBTDefragTraceLocal(global().backupTrace);
    zero = new RCImmixZero();
    rc = new RCImmixCollectorLocal(RCImmix.rcSpace);
    copy = new RCImmixAllocator(RCImmix.rcSpace, true, true);
    young = new RCImmixAllocator(RCImmix.rcSpace, true, false);
    rootTrace = new RCImmixRootSetTraceLocal(global().rootTrace, newRootPointerBuffer);
    modProcessor = new RCImmixModifiedProcessor(this);
  }

  /**
   * Get the modified processor to use.
   */
  protected final TransitiveClosure getModifiedProcessor() {
    return modProcessor;
  }

  /**
   * Get the root trace to use.
   */
  protected final TraceLocal getRootTrace() {
    return rootTrace;
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * {@inheritDoc}
   */
  @Override
  public void collect() {
    Phase.beginNewPhaseStack(Phase.scheduleComplex(global().collection));
  }

  @Override
  public void collectionPhase(short phaseId, boolean primary) {
    if (phaseId == RCImmix.PREPARE) {
      rc.prepare(true);
      getRootTrace().prepare();
      if (RCImmix.CC_BACKUP_TRACE && RCImmix.performCycleCollection) {
        backupTrace = RCImmix.rcSpace.inImmixDefragCollection() ? defragTrace : backTrace;
        backupTrace.prepare();
        copy.reset();
      } else {
        young.reset();
      }
      return;
    }

    if (phaseId == RCImmix.ROOTS) {
      VM.scanning.computeGlobalRoots(getCurrentTrace());
      VM.scanning.computeStaticRoots(getCurrentTrace());
      if (Plan.SCAN_BOOT_IMAGE && RCImmix.performCycleCollection) {
        VM.scanning.computeBootImageRoots(getCurrentTrace());
      }
      return;
    }

    if (phaseId == RCImmix.CLOSURE) {
      getRootTrace().completeTrace();
      newRootPointerBuffer.flushLocal();
      return;
    }

    if (phaseId == RCImmix.PROCESS_OLDROOTBUFFER) {
      ObjectReference current;
      while(!(current = oldRootBuffer.pop()).isNull()) {
        decBuffer.push(current);
      }
      return;
    }

    if (phaseId == RCImmix.PROCESS_NEWROOTBUFFER) {
      ObjectReference current;
      Address address;
      while(!newRootPointerBuffer.isEmpty()) {
        address = newRootPointerBuffer.pop();
        current = address.loadObjectReference();
        if (RCImmix.isRCObject(current)) {
          if (RCImmix.CC_BACKUP_TRACE && RCImmix.performCycleCollection) {
            if (RCImmixObjectHeader.incRC(current) == RCImmixObjectHeader.INC_NEW) {
              modBuffer.push(current);
            }
            newRootPointerBackBuffer.push(address);
          } else {
            if (RCImmix.RC_SURVIVOR_COPY) {
              survivorCopy(address, current, true);
            } else {
              if (RCImmixObjectHeader.incRC(current) == RCImmixObjectHeader.INC_NEW) {
                if (Space.isInSpace(RCImmix.REF_COUNT, current)) {
                  RCImmixObjectHeader.incLines(current);
                }
                modBuffer.push(current);
              }
              oldRootBuffer.push(current);
            }
          }
        }
      }
      modBuffer.flushLocal();
      if (RCImmix.CC_BACKUP_TRACE && RCImmix.performCycleCollection) {
        newRootPointerBackBuffer.flushLocal();
      } else {
        oldRootBuffer.flushLocal();
      }
      return;
    }

    if (phaseId == RCImmix.PROCESS_MODBUFFER) {
      ObjectReference current;
      while(!(current = modBuffer.pop()).isNull()) {
        RCImmixObjectHeader.makeUnlogged(current);
        VM.scanning.scanObject(getModifiedProcessor(), current);
      }
      return;
    }

    if (phaseId == RCImmix.PROCESS_DECBUFFER) {
      ObjectReference current;
      if (RCImmix.CC_BACKUP_TRACE && RCImmix.performCycleCollection) {
        while(!(current = decBuffer.pop()).isNull()) {
          if (RCImmixObjectHeader.isNew(current)) {
            if (Space.isInSpace(RCImmix.REF_COUNT_LOS, current)) {
              RCImmix.rcloSpace.free(current);
            } else if (Space.isInSpace(RCImmix.IMMORTAL, current)) {
              VM.scanning.scanObject(zero, current);
            }
          }
        }
        return;
      }
      while(!(current = decBuffer.pop()).isNull()) {
        if (RCImmixObjectHeader.isNew(current)) {
          if (Space.isInSpace(RCImmix.REF_COUNT_LOS, current)) {
            RCImmix.rcloSpace.free(current);
          } else if (Space.isInSpace(RCImmix.IMMORTAL, current)) {
            VM.scanning.scanObject(zero, current);
          }
        } else {
          if (RCImmixObjectHeader.decRC(current) == RCImmixObjectHeader.DEC_KILL) {
            decBuffer.processChildren(current);
            if (Space.isInSpace(RCImmix.REF_COUNT, current)) {
              RCImmixObjectHeader.decLines(current);
            } else if (Space.isInSpace(RCImmix.REF_COUNT_LOS, current)) {
              RCImmix.rcloSpace.free(current);
            } else if (Space.isInSpace(RCImmix.IMMORTAL, current)) {
              VM.scanning.scanObject(zero, current);
            }
          }
        }
      }
      return;
    }

    if (phaseId == RCImmix.BT_CLOSURE_INIT) {
      if (RCImmix.CC_BACKUP_TRACE && RCImmix.performCycleCollection) {
        ObjectReference current, newObject;
        Address address;
        while(!newRootPointerBackBuffer.isEmpty()) {
          address = newRootPointerBackBuffer.pop();
          current = address.loadObjectReference();
          if (RCImmix.isRCObject(current)) {
            newObject = backupTrace.traceObject(current);
            if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!newObject.isNull());
            address.store(newObject);
          }
        }
      }
      return;
    }

    if (phaseId == RCImmix.BT_CLOSURE) {
      if (RCImmix.CC_BACKUP_TRACE && RCImmix.performCycleCollection) {
        backupTrace.completeTrace();
      }
      return;
    }

    if (phaseId == RCImmix.RELEASE) {
      if (RCImmix.CC_BACKUP_TRACE && RCImmix.performCycleCollection) {
        backupTrace.release();
      }
      getRootTrace().release();
      rc.release(true);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(newRootPointerBuffer.isEmpty());
        VM.assertions._assert(modBuffer.isEmpty());
        VM.assertions._assert(decBuffer.isEmpty());
      }
      return;
    }

    super.collectionPhase(phaseId, primary);
  }

  /****************************************************************************
   *
   * Miscellaneous
   */

  /** @return The active global plan as an <code>RC</code> instance. */
  @Inline
  protected static RCImmix global() {
    return (RCImmix) VM.activePlan.global();
  }

  @Override
  public final TraceLocal getCurrentTrace() {
    return getRootTrace();
  }

  /** @return The current modBuffer instance. */
  @Inline
  public final ObjectReferenceDeque getModBuffer() {
    return modBuffer;
  }

   /****************************************************************************
   *
   * Collection-time allocation
   */

   /**
    * {@inheritDoc}
    */
   @Override
   @Inline
   public Address allocCopy(ObjectReference original, int bytes,
       int align, int offset, int allocator) {
     if (VM.VERIFY_ASSERTIONS) {
       VM.assertions._assert(bytes <= Plan.MAX_NON_LOS_COPY_BYTES);
       VM.assertions._assert(allocator == RCImmix.ALLOC_DEFAULT);
     }
     if (RCImmix.performCycleCollection && RCImmix.rcSpace.inImmixDefragCollection()) {
       return copy.alloc(bytes, align, offset);
     } else return young.alloc(bytes, align, offset);
   }


   @Override
   @Inline
   public void postCopy(ObjectReference object, ObjectReference typeRef,
       int bytes, int allocator) {
     if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(allocator == RCImmix.ALLOC_DEFAULT);
     if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(Space.isInSpace(RCImmix.REF_COUNT, object));
     if (RCImmix.performCycleCollection && RCImmix.rcSpace.inImmixDefragCollection()) {
       RCImmix.rcSpace.postCopy(object, bytes);

       if (VM.VERIFY_ASSERTIONS) {
         VM.assertions._assert(backupTrace.isLive(object));
         VM.assertions._assert(backupTrace.willNotMoveInCurrentCollection(object));
       }
     } else {
       RCImmix.rcSpace.postCopyYoungObject(object, bytes);
     }
   }

   @Inline
   public void survivorCopy(Address slot, ObjectReference object, boolean root) {
     if (Space.isInSpace(RCImmix.REF_COUNT, object)) {
       // Race to be the (potential) forwarder
       Word priorStatusWord = ForwardingWord.attemptToForward(object);
       if (ForwardingWord.stateIsForwardedOrBeingForwarded(priorStatusWord)) {
         // We lost the race; the object is either forwarded or being forwarded by another thread.
         ObjectReference rtn = ForwardingWord.spinAndGetForwardedObject(object, priorStatusWord);
         RCImmixObjectHeader.incRCOld(rtn);
         slot.store(rtn);
         if (root) oldRootBuffer.push(rtn);
       } else {
         byte priorState = (byte) (priorStatusWord.toInt() & 0xFF);
         // the object is unforwarded, either because this is the first thread to reach it, or because the object can't be forwarded
         if (!RCImmixObjectHeader.isHeaderNew(priorStatusWord)) {
           // the object has not been forwarded, but has the correct new state; unlock and return unmoved object
           RCImmixObjectHeader.returnToPriorState(object, priorState); // return to uncontested state
           RCImmixObjectHeader.incRCOld(object);
           if (root) oldRootBuffer.push(object);
         } else {
           if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(RCImmixObjectHeader.isNew(object));
           // we are the first to reach the object; forward it
           if (RCImmixObjectHeader.incRC(object) == RCImmixObjectHeader.INC_NEW) {
             // forward
             ObjectReference newObject;
             if (RCImmix.rcSpace.exhaustedCopySpace || RCImmixObjectHeader.isPinnedObject(object)) {
               RCImmixObjectHeader.clearStateYoungObject(object);
               newObject = object;
             } else {
               newObject = ForwardingWord.forwardObject(object, Plan.ALLOC_DEFAULT);
             }
             slot.store(newObject);
             RCImmixObjectHeader.incLines(newObject);
             modBuffer.push(newObject);
             if (root) oldRootBuffer.push(newObject);
           }
         }
       }
     } else {
       if (RCImmixObjectHeader.incRC(object) == RCImmixObjectHeader.INC_NEW) {
         modBuffer.push(object);
       }
       if (root) oldRootBuffer.push(object);
     }
   }
}

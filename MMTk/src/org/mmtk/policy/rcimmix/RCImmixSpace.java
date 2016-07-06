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
package org.mmtk.policy.rcimmix;

import static org.mmtk.policy.rcimmix.RCImmixConstants.*;
import static org.mmtk.utility.Constants.LOG_BYTES_IN_PAGE;

import org.mmtk.plan.TransitiveClosure;
import org.mmtk.policy.Space;
import org.mmtk.utility.heap.*;
import org.mmtk.utility.options.LineReuseRatio;
import org.mmtk.utility.options.Options;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.HeaderByte;
import org.mmtk.utility.Log;
import org.mmtk.vm.Lock;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * Each instance of this class corresponds to one immix <b>space</b>.
 * Each of the instance methods of this class may be called by any
 * thread (i.e. synchronization must be explicit in any instance or
 * class method).  This contrasts with the SquishLocal, where
 * instances correspond to *plan* instances and therefore to kernel
 * threads.  Thus unlike this class, synchronization is not necessary
 * in the instance methods of SquishLocal.
 *
 */
@Uninterruptible
public final class RCImmixSpace extends Space {

  /****************************************************************************
   *
   * Class variables
   */

  /**
   *
   */
  private static short reusableMarkStateThreshold = 0;

  /****************************************************************************
   *
   * Instance variables
   */

  /**
   *
   */
  private boolean inCollection;
  private int linesConsumed = 0;

  private Lock mutatorLock = VM.newLock(getName()+"mutator");
  private Lock gcLock = VM.newLock(getName()+"gc");

  private Address allocBlockCursor = Address.zero();
  private Address allocBlockSentinel = Address.zero();
  private boolean exhaustedReusableSpace = true;
  public boolean exhaustedCopySpace = false;
  public int maxCleanPagesForCopy = 0;
  public double linesUsed = 0;
  public double linesCleaned = 0;


  private final RCImmixChunkList chunkMap = new RCImmixChunkList();
  private final RCImmixDefrag rCImmixDefrag;

  /****************************************************************************
   *
   * Initialization
   */

  static {
    Options.lineReuseRatio = new LineReuseRatio();
    reusableMarkStateThreshold = (short) (Options.lineReuseRatio.getValue() * MAX_BLOCK_MARK_STATE);
  }

  /**
   * The caller specifies the region of virtual memory to be used for
   * this space.  If this region conflicts with an existing space,
   * then the constructor will fail.
   *
   * @param name The name of this space (used when printing error messages etc)
   * @param vmRequest The virtual memory request
   */
  public RCImmixSpace(String name, VMRequest vmRequest) {
    this(name, true, vmRequest);
  }

  /**
   * The caller specifies the region of virtual memory to be used for
   * this space.  If this region conflicts with an existing space,
   * then the constructor will fail.
   *
   * @param name The name of this space (used when printing error messages etc)
   * @param zeroed if true, allocations return zeroed memory
   * @param vmRequest The virtual memory request
   */
  public RCImmixSpace(String name, boolean zeroed, VMRequest vmRequest) {
    super(name, false, false, zeroed, vmRequest);
    if (vmRequest.isDiscontiguous())
      pr = new FreeListPageResource(this, RCImmixChunk.getRequiredMetaDataPages());
    else
      pr = new FreeListPageResource(this, start, extent, RCImmixChunk.getRequiredMetaDataPages());
    rCImmixDefrag = new RCImmixDefrag((FreeListPageResource) pr);
  }

  /****************************************************************************
   *
   * Global prepare and release
   */

  /**
   * Prepare for a new collection increment.
   */
  public void prepare(boolean majorGC) {
    chunkMap.reset();
    rCImmixDefrag.prepare(chunkMap, this);
    inCollection = true;

    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(VM.activePlan.collectorCount() <= MAX_COLLECTORS);
  }

  /**
   * A new collection increment has completed.  Release global resources.
   * @param majorGC TODO
   */
  public boolean release(boolean majorGC) {
    boolean didDefrag = rCImmixDefrag.inDefrag();
    chunkMap.reset();
    rCImmixDefrag.globalRelease();
    inCollection = false;

    /* set up reusable space */
    if (allocBlockCursor.isZero()) allocBlockCursor = chunkMap.getHeadChunk();
    allocBlockSentinel = allocBlockCursor;
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isRecycleAllocChunkAligned(allocBlockSentinel));
    exhaustedReusableSpace = allocBlockCursor.isZero();
    if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
      Log.write("gr[allocBlockCursor: "); Log.write(allocBlockCursor); Log.write(" allocBlockSentinel: "); Log.write(allocBlockSentinel); Log.writeln("]");
    }

    /* really just want this to happen once after options are booted, but no harm in re-doing it */
    reusableMarkStateThreshold = (short) (Options.lineReuseRatio.getValue() * MAX_BLOCK_MARK_STATE);
    RCImmixDefrag.defragReusableMarkStateThreshold = (short) (Options.defragLineReuseRatio.getValue() * MAX_BLOCK_MARK_STATE);

    linesConsumed = 0;
    return didDefrag;
  }

  /**
   * Determine the collection kind.
   *
   * @param emergencyCollection Is this collection an emergency (last did not yield enough)?
   * @param collectWholeHeap Is this a whole heap collection?
   * @param collectionAttempt Which attempt is this to collect?
   * @param userTriggeredCollection Was this collection requested by the user?
   */
  public void decideWhetherToDefrag(boolean emergencyCollection, boolean collectWholeHeap, int collectionAttempt, boolean userTriggeredCollection, boolean forceDefrag) {
    rCImmixDefrag.decideWhetherToDefrag(emergencyCollection, collectWholeHeap, collectionAttempt, userTriggeredCollection, exhaustedReusableSpace, forceDefrag);
  }

  /**
   * Return the amount of headroom required to allow defrag, so this can be included in a collection reserve.
   *
   * @return The number of pages.
   */
  public int defragHeadroomPages() {
    return rCImmixDefrag.getDefragHeadroomPages();
  }

 /****************************************************************************
  *
  * Collection state access methods
  */

  /**
   * Return {@code true} if this space is currently being collected.
   *
   * @return {@code true} if this space is currently being collected.
   */
  @Inline
  public boolean inImmixCollection() {
    return inCollection;
  }

  /**
   * Return {@code true} if this space is currently being defraged.
   *
   * @return {@code true} if this space is currently being defraged.
   */
  @Inline
  public boolean inImmixDefragCollection() {
    return inCollection && rCImmixDefrag.inDefrag();
  }

  /**
   * Return the number of pages allocated since the last collection
   *
   * @return The number of pages allocated since the last collection
   */
  public int getPagesAllocated() {
    return linesConsumed>>(LOG_BYTES_IN_PAGE-LOG_BYTES_IN_LINE);
  }

  /**
   * Return the reusable mark state threshold, which determines how
   * eagerly lines should be recycled (by default these values are
   * set so that all lines are recycled).
   *
   * @param forDefrag The query is the context of a defragmenting collection
   * @return The reusable mark state threshold
   */
  @Inline
  public static short getReusuableMarkStateThreshold(boolean forDefrag) {
    return forDefrag ? RCImmixDefrag.defragReusableMarkStateThreshold : reusableMarkStateThreshold;
  }

  /****************************************************************************
   *
   * Allocation
   */

  /**
   * Return a pointer to a set of new usable blocks, or null if none are available.
   * Use different block selection heuristics depending on whether the allocation
   * request is "hot" or "cold".
   *
   * @param hot True if the requesting context is for hot allocations (used for
   * allocations from high allocation volume sites).
   * @return The pointer into the alloc table containing usable blocks.
   */
  public Address getSpace(boolean hot, boolean copy, int lineUseCount) {
    Address rtn;
    if (copy)
      rCImmixDefrag.getBlock();

    if (!VM.activePlan.isMutator() && RCImmixObjectHeader.performSurvivorCopy) {
      if ((maxCleanPagesForCopy - PAGES_IN_BLOCK) >= 0) {
        maxCleanPagesForCopy-= PAGES_IN_BLOCK;
      } else {
        exhaustedCopySpace = true; // this flag is used to control survivor copy
      }
    }

    linesConsumed += lineUseCount;

    rtn = acquire(PAGES_IN_BLOCK);
    if (VM.activePlan.isMutator()) {
      linesUsed += LINES_IN_BLOCK;
    }


    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(RCImmixBlock.isAligned(rtn));
      VM.assertions._assert(!(copy && RCImmixBlock.isDefragSource(rtn)));
    }

    if (!rtn.isZero()) {
      RCImmixBlock.setBlockAsInUse(rtn);
      RCImmixChunk.updateHighWater(rtn);
      if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
        Log.write("gs["); Log.write(rtn); Log.write(" -> "); Log.write(rtn.plus(BYTES_IN_BLOCK-1)); Log.write(" copy: "); Log.write(copy); Log.writeln("]");
      }
    }

    return rtn;
  }

  @Override
  public void growSpace(Address start, Extent bytes, boolean newChunk) {
    super.growSpace(start, bytes, newChunk);
     if (newChunk) {
      Address chunk = chunkAlign(start.plus(bytes), true);
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(chunkAlign(start.plus(bytes), true).EQ(chunk));
      RCImmixChunk.clearMetaData(chunk);
      chunkMap.addNewChunkToMap(chunk);
    }
  }

  public Address acquireReusableBlocks() {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(isRecycleAllocChunkAligned(allocBlockCursor));
      VM.assertions._assert(isRecycleAllocChunkAligned(allocBlockSentinel));
    }
    Address rtn;

    lock();
    if (exhaustedReusableSpace)
      rtn = Address.zero();
    else {
      rtn = allocBlockCursor;
      Address lastAllocChunk = chunkAlign(allocBlockCursor, true);
      allocBlockCursor = allocBlockCursor.plus(BYTES_IN_RECYCLE_ALLOC_CHUNK);
      if (allocBlockCursor.GT(RCImmixChunk.getHighWater(lastAllocChunk)))
        allocBlockCursor = chunkMap.nextChunk(lastAllocChunk);
      if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
        Log.write("arb[ rtn: "); Log.write(rtn); Log.write(" allocBlockCursor: "); Log.write(allocBlockCursor); Log.write(" allocBlockSentinel: "); Log.write(allocBlockSentinel); Log.writeln("]");
      }

      if (allocBlockCursor.isZero() || allocBlockCursor.EQ(allocBlockSentinel)) {
        exhaustedReusableSpace = true;
        if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
          Log.writeln("[Reusable space exhausted]");
        }
      }
    }
    unlock();
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isRecycleAllocChunkAligned(rtn));
    return rtn;
  }

  /**
   * Release a block.  A block is free, so call the underlying page allocator
   * to release the associated storage.
   *
   * @param block The address of the block to be released
   */
  @Override
  @Inline
  public void release(Address block) {
    if (RCImmixBlock.getRCAddress(block).loadByte() == RCImmixObjectHeader.ONE) {
      RCImmixBlock.getRCAddress(block).store(RCImmixObjectHeader.ZERO);
    } else {
      linesCleaned += LINES_IN_BLOCK;
    }
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(RCImmixBlock.isAligned(block));
    RCImmixBlock.setBlockAsUnallocated(block);
    ((FreeListPageResource) pr).releasePages(block);
  }

  /**
   * {@inheritDoc}<p>
   *
   * This hook is called by the page level allocators whenever a
   * complete discontiguous chunk is released.
   */
  @Override
  public int releaseDiscontiguousChunks(Address chunk) {
    chunkMap.removeChunkFromMap(chunk);
    return super.releaseDiscontiguousChunks(chunk);
  }

  /****************************************************************************
  *
  * Header manipulation
  */

 /**
  * Perform any required post allocation initialization
  *
  * @param object the object ref to the storage to be initialized
  */
  @Inline
  public void postAlloc(ObjectReference object, int bytes) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(RCImmixObjectHeader.isNew(object));
  }

 /**
  * Perform any required post copy (i.e. in-GC allocation) initialization.
  * This is relevant (for example) when Squish is used as the mature space in
  * a copying GC.
  *
  * @param object the object ref to the storage to be initialized
  * @param majorGC Is this copy happening during a major gc?
  */
  @Inline
  public void postCopy(ObjectReference object, int bytes) {
    RCImmixObjectHeader.writeMarkState(object, bytes > BYTES_IN_LINE);
    if (!MARK_LINE_AT_SCAN_TIME) RCImmixObjectHeader.testAndMarkLines(object);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(RCImmixObjectHeader.isMarked(object));
    }
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!ForwardingWord.isForwardedOrBeingForwarded(object));
    if (VM.VERIFY_ASSERTIONS && HeaderByte.NEEDS_UNLOGGED_BIT) VM.assertions._assert(HeaderByte.isUnlogged(object));
  }

  /****************************************************************************
   *
   * Object tracing
   */

  @Inline
  public ObjectReference defragTraceObject(TransitiveClosure trace, ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(rCImmixDefrag.determined(true));
    traceObjectWithoutMoving(trace, object);
    return object;
  }

  @Inline
  public ObjectReference defragTraceObject(TransitiveClosure trace, ObjectReference object, int allocator) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(rCImmixDefrag.determined(true));
    if (isDefragSource(object)) {
      ObjectReference rtn = traceObjectWithOpportunisticCopy(trace, object, allocator);
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(!rtn.isNull());
        VM.assertions._assert(rCImmixDefrag.spaceExhausted() || !isDefragSource(rtn) || (RCImmixObjectHeader.isPinnedObject(rtn)));
      }
      return rtn;
    } else {
      traceObjectAndLineWithoutMoving(trace, object);
      return object;
    }
  }

  /**
   * Trace a reference to an object in the context of a non-moving collection.  This
   * call is optimized for the simpler non-moving case.
   *
   * @param trace The trace performing the transitive closure
   * @param object The object to be traced.
   * @return The object (there is no object forwarding in this
   * trace method, so we always return the same object: this could be a
   * void method but for compliance to a more general interface).
   */
  @Inline
  public ObjectReference fastTraceObjectAndLine(TransitiveClosure trace, ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(rCImmixDefrag.determined(false));
    traceObjectAndLineWithoutMoving(trace, object);
    return object;
  }

  @Inline
  public ObjectReference fastTraceObject(TransitiveClosure trace, ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(rCImmixDefrag.determined(false));
    traceObjectWithoutMoving(trace, object);
    return object;
  }

  /**
   * Trace a reference to an object.  This interface is not supported by immix, since
   * we require the allocator to be identified except for the special case of the fast
   * trace.
   *
   * @param trace The trace performing the transitive closure
   * @param object The object to be traced.
   * @return null and fail.
   */
  @Override
  public ObjectReference traceObject(TransitiveClosure trace, ObjectReference object) {
    VM.assertions.fail("unsupported interface");
    return null;
  }

  /**
   * Trace a reference to an object in the context of a non-moving collection.  This
   * call is optimized for the simpler non-moving case.
   *
   * @param trace The trace performing the transitive closure
   * @param object The object to be traced.
   */
  @Inline
  private void traceObjectAndLineWithoutMoving(TransitiveClosure trace, ObjectReference object) {
    if (RCImmixObjectHeader.testAndMark(object)) {
      if (!MARK_LINE_AT_SCAN_TIME) RCImmixObjectHeader.testAndMarkLines(object);
      trace.processNode(object);
      RCImmixObjectHeader.initRC(object);
    } else RCImmixObjectHeader.incRC(object);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!ForwardingWord.isForwardedOrBeingForwarded(object));
    if (VM.VERIFY_ASSERTIONS  && HeaderByte.NEEDS_UNLOGGED_BIT) VM.assertions._assert(HeaderByte.isUnlogged(object));
  }

  @Inline
  private void traceObjectWithoutMoving(TransitiveClosure trace, ObjectReference object) {
    if (RCImmixObjectHeader.testAndMark(object)) {
      trace.processNode(object);
      RCImmixObjectHeader.initRC(object);
    } else RCImmixObjectHeader.incRC(object);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!ForwardingWord.isForwardedOrBeingForwarded(object));
    if (VM.VERIFY_ASSERTIONS  && HeaderByte.NEEDS_UNLOGGED_BIT) VM.assertions._assert(HeaderByte.isUnlogged(object));
  }

  /**
   * Trace a reference to an object, forwarding the object if appropriate
   * If the object is not already marked, mark the object and enqueue it
   * for subsequent processing.
   *
   * @param trace The trace performing the transitive closure
   * @param object The object to be traced.
   * @param allocator The allocator to which any copying should be directed
   * @return Either the object or a forwarded object, if it was forwarded.
   */
  @Inline
  private ObjectReference traceObjectWithOpportunisticCopy(TransitiveClosure trace, ObjectReference object, int allocator) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert((rCImmixDefrag.determined(true) && isDefragSource(object)));
    // Race to be the (potential) forwarder
    Word priorStatusWord = ForwardingWord.attemptToForward(object);
    //Word priorHeaderWord = object.toAddress().loadWord(ObjectHeader.RC_HEADER_OFFSET);
    if (ForwardingWord.stateIsForwardedOrBeingForwarded(priorStatusWord)) {
      // We lost the race; the object is either forwarded or being forwarded by another thread.
      // Note that the concurrent attempt to forward the object may fail, so the object may remain in-place
      ObjectReference rtn = ForwardingWord.spinAndGetForwardedObject(object, priorStatusWord);
      if (VM.VERIFY_ASSERTIONS && rtn == object) VM.assertions._assert(RCImmixObjectHeader.isMarked(object) || rCImmixDefrag.spaceExhausted() || RCImmixObjectHeader.isPinnedObject(object));
      if (VM.VERIFY_ASSERTIONS && rtn != object) VM.assertions._assert(!isDefragSource(rtn));
      RCImmixObjectHeader.incRC(rtn);
      return rtn;
    } else {
      byte priorState = (byte) (priorStatusWord.toInt() & 0xFF);
      // the object is unforwarded, either because this is the first thread to reach it, or because the object can't be forwarded
      if (RCImmixObjectHeader.isMarked(priorState)) {
        // the object has not been forwarded, but has the correct mark state; unlock and return unmoved object
        // Note that in a sticky mark bits collector, the mark state does not change at each GC, so correct mark state does not imply another thread got there first
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(rCImmixDefrag.spaceExhausted() || RCImmixObjectHeader.isPinnedObject(object));
        RCImmixObjectHeader.returnToPriorState(object, priorState); // return to uncontested state
        RCImmixObjectHeader.incRC(object);
        return object;
      } else {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!RCImmixObjectHeader.isMarked(object));
        // we are the first to reach the object; either mark in place or forward it
        ObjectReference newObject;
        if (RCImmixObjectHeader.isPinnedObject(object) ||  rCImmixDefrag.spaceExhausted()) {
          // mark in place
          RCImmixObjectHeader.setMarkStateAndUnlock(object, priorState);
          newObject = object;
        } else {
          // forward
          if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!RCImmixObjectHeader.isPinnedObject(object));
          newObject = ForwardingWord.forwardObject(object, allocator);
        }
        if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
          Log.write("C["); Log.write(object); Log.write("/");
          Log.write(getName()); Log.write("] -> ");
          Log.write(newObject); Log.write("/");
          Log.write(Space.getSpaceForObject(newObject).getName());
          Log.writeln("]");
        }
        if (!MARK_LINE_AT_SCAN_TIME) RCImmixObjectHeader.testAndMarkLines(newObject);
        trace.processNode(newObject);
        RCImmixObjectHeader.initRC(newObject);
        if (VM.VERIFY_ASSERTIONS) {
          if (!((getSpaceForObject(newObject) != this) ||
                (newObject == object) ||
                (rCImmixDefrag.inDefrag() && willNotMoveThisGC(newObject))
               )) {
            Log.write("   object: "); Log.writeln(object);
            Log.write("newObject: "); Log.writeln(newObject);
            Log.write("    space: "); Log.writeln(getName());
            Space otherSpace = getSpaceForObject(newObject);
            Log.write(" space(o): "); Log.writeln(otherSpace == null ? "<NULL>" : otherSpace.getName());
            VM.assertions._assert(false);
          }
        }
        return newObject;
      }
    }
  }

  public int getNextUnavailableLine(Address baseLineAvailAddress, int line) {
    return RCImmixLine.getNextUnavailable(baseLineAvailAddress, line);
  }

  public int getNextAvailableLine(Address baseLineAvailAddress, int line) {
    return RCImmixLine.getNextAvailable(baseLineAvailAddress, line);
  }

  /****************************************************************************
  *
  * Establish available lines
  */

  /**
   * Establish the number of recyclable lines lines available for allocation
   * during defragmentation, populating the spillAvailHistogram, which buckets
   * available lines according to the number of holes on the block on which
   * the available lines reside.
   *
   * @param spillAvailHistogram A histogram of availability to be populated
   * @return The number of available recyclable lines
   */
  int getAvailableLines(int[] spillAvailHistogram) {
    int availableLines;
    if (allocBlockCursor.isZero() || exhaustedReusableSpace) {
      availableLines = 0;
    } else {
      if (allocBlockCursor.EQ(allocBlockSentinel)) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!exhaustedReusableSpace);
        allocBlockCursor = chunkMap.getHeadChunk();
        allocBlockSentinel = allocBlockCursor;
      }
      availableLines = getUsableLinesInRegion(allocBlockCursor, allocBlockSentinel, spillAvailHistogram);
    }
    return availableLines;
  }

  /**
   * Return the number of lines usable for allocation during defragmentation in the
   * address range specified by start and end.  Populate a histogram to indicate where
   * the usable lines reside as a function of block hole count.
   *
   * @param start  The start of the region to be checked for availability
   * @param end The end of the region to be checked for availability
   * @param spillAvailHistogram The histogram which will be populated
   * @return The number of usable lines
   */
  private int getUsableLinesInRegion(Address start, Address end, int[] spillAvailHistogram) {
    int usableLines = 0;
    Address blockCursor = RCImmixChunk.isAligned(start) ? start.plus(RCImmixChunk.FIRST_USABLE_BLOCK_INDEX<<LOG_BYTES_IN_BLOCK) : start;
    Address blockStateCursor = RCImmixBlock.getBlockMarkStateAddress(blockCursor);
    Address chunkCursor = RCImmixChunk.align(blockCursor);
    if (RCImmixChunk.getByteOffset(end) < RCImmixChunk.FIRST_USABLE_BLOCK_INDEX<<LOG_BYTES_IN_BLOCK)
      end = RCImmixChunk.align(end).plus(RCImmixChunk.FIRST_USABLE_BLOCK_INDEX<<LOG_BYTES_IN_BLOCK);

    for (int i = 0; i <= MAX_CONSV_SPILL_COUNT; i++) spillAvailHistogram[i] = 0;

    Address highwater = RCImmixChunk.getHighWater(chunkCursor);
    do {
      short markState = blockStateCursor.loadShort();
      if (markState != 0 && markState <= reusableMarkStateThreshold) {
        int usable = LINES_IN_BLOCK - markState;
        short bucket = RCImmixBlock.getConservativeSpillCount(blockCursor);
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(bucket >= 0 && bucket <= MAX_CONSV_SPILL_COUNT);
        spillAvailHistogram[bucket] += usable;
        usableLines += usable;
      }
      blockCursor = blockCursor.plus(BYTES_IN_BLOCK);
      if (blockCursor.GT(highwater)) {
        chunkCursor = chunkMap.nextChunk(chunkCursor);
        if (chunkCursor.isZero()) break;
        blockCursor = chunkCursor.plus(RCImmixChunk.FIRST_USABLE_BLOCK_INDEX<<LOG_BYTES_IN_BLOCK);
        blockStateCursor = RCImmixBlock.getBlockMarkStateAddress(blockCursor);
        highwater = RCImmixChunk.getHighWater(chunkCursor);
      } else
        blockStateCursor = blockStateCursor.plus(RCImmixBlock.BYTES_IN_BLOCK_STATE_ENTRY);
    } while (blockCursor.NE(end));

    return usableLines;
  }

  /****************************************************************************
   *
   * Object state
   */

  /**
   * Generic test of the liveness of an object
   *
   * @param object The object in question
   * @return {@code true} if this object is known to be live (i.e. it is marked)
   */
  @Override
  @Inline
  public boolean isLive(ObjectReference object) {
    if (rCImmixDefrag.inDefrag() && isDefragSource(object))
      return ForwardingWord.isForwardedOrBeingForwarded(object) || RCImmixObjectHeader.isMarked(object);
    else
      return RCImmixObjectHeader.isMarked(object);
  }

  /**
   * Test the liveness of an object during defragmentation
   *
   * @param object The object in question
   * @return {@code true} if this object is known to be live (i.e. it is marked)
   */
  @Inline
  public boolean fastIsLive(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!rCImmixDefrag.inDefrag());
    return RCImmixObjectHeader.isMarked(object);
  }

  @Inline
  public boolean willNotMoveThisGC(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(getSpaceForObject(object) == this && rCImmixDefrag.inDefrag());
    return RCImmixObjectHeader.isPinnedObject(object) || willNotMoveThisGC(VM.objectModel.refToAddress(object));
  }

  @Inline
  private boolean isDefragSource(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(getSpaceForObject(object) == this);
    return isDefragSource(VM.objectModel.refToAddress(object));
  }

  @Inline
  public boolean willNotMoveThisGC(Address address) {
    return !rCImmixDefrag.inDefrag() || rCImmixDefrag.spaceExhausted() || !isDefragSource(address);
  }

  @Inline
  public boolean isDefragSource(Address address) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(getSpaceForObject(address.toObjectReference()) == this);
    return RCImmixBlock.isDefragSource(address);
  }


  /****************************************************************************
   *
   * Locks
   */

  /**
   * Acquire the appropriate lock depending on whether the context is
   * GC or mutator.
   */
  private void lock() {
    if (inCollection)
      gcLock.acquire();
    else
      mutatorLock.acquire();
  }

   /**
    * Release the appropriate lock depending on whether the context is
    * GC or mutator.
    */
  private void unlock() {
    if (inCollection)
      gcLock.release();
    else
       mutatorLock.release();
  }


 /****************************************************************************
  *
  * Misc
  */

  /**
   *
   */
  public static boolean isRecycleAllocChunkAligned(Address ptr) {
    return ptr.toWord().and(RECYCLE_ALLOC_CHUNK_MASK).EQ(Word.zero());
  }

  @Inline
  public void postCopyYoungObject(ObjectReference object, int bytes) {
    RCImmixObjectHeader.writeStateYoungObject(object, bytes > BYTES_IN_LINE);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!ForwardingWord.isForwardedOrBeingForwarded(object));
    if (VM.VERIFY_ASSERTIONS && HeaderByte.NEEDS_UNLOGGED_BIT) VM.assertions._assert(HeaderByte.isUnlogged(object));
  }

  RCImmixChunkList getChunkMap() { return chunkMap; }
  RCImmixDefrag getDefrag() { return rCImmixDefrag; }
}

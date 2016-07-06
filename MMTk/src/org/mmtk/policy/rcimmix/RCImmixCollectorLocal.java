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


import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.Address;

/**
 * This class implements unsynchronized (local) elements of an
 * immix collector.  Marking is done using both a bit in
 * each header's object word, and a mark byte.  Sweeping is
 * performed lazily.<p>
 *
 */
@Uninterruptible
public final class RCImmixCollectorLocal {

  /****************************************************************************
   *
   * Class variables
   */


  /****************************************************************************
   *
   * Instance variables
   */
  private final RCImmixSpace rCImmixSpace;
  private final RCImmixChunkList chunkMap;
  private final RCImmixDefrag rCImmixDefrag;


  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   *
   * @param space The mark-sweep space to which this allocator
   * instances is bound.
   */
  public RCImmixCollectorLocal(RCImmixSpace space) {
    rCImmixSpace = space;
    chunkMap = rCImmixSpace.getChunkMap();
    rCImmixDefrag = rCImmixSpace.getDefrag();
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * Prepare for a collection. If paranoid, perform a sanity check.
   */
  public void prepare(boolean majorGC) {
    int ordinal = VM.activePlan.collector().parallelWorkerOrdinal();
    if (majorGC) {
      if (rCImmixSpace.inImmixDefragCollection()) {
        short threshold = RCImmixDefrag.defragSpillThreshold;
        resetLineMarksAndDefragStateTable(ordinal, threshold);
      }
    }
  }

  private void resetLineMarksAndDefragStateTable(int ordinal, final short threshold) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(rCImmixSpace.inImmixDefragCollection());
    int stride = VM.activePlan.collector().parallelWorkerCount();
    Address chunk = chunkMap.firstChunk(ordinal, stride);
    while (!chunk.isZero()) {
      RCImmixChunk.resetLineMarksAndDefragStateTable(chunk, threshold);
      chunk = chunkMap.nextChunk(chunk, ordinal, stride);
    }
  }


  /**
   * Finish up after a collection.
   *
   * We help sweeping all the blocks in parallel.
   */
  public void release(boolean majorGC) {
    sweepAllBlocks(majorGC);
  }

  private void sweepAllBlocks(boolean majorGC) {
    int stride = VM.activePlan.collector().parallelWorkerCount();
    int ordinal = VM.activePlan.collector().parallelWorkerOrdinal();
    int[] markSpillHisto = rCImmixDefrag.getAndZeroSpillMarkHistogram(ordinal);
    Address chunk = chunkMap.firstChunk(ordinal, stride);
    while (!chunk.isZero()) {
      RCImmixChunk.sweep(chunk, RCImmixChunk.getHighWater(chunk), rCImmixSpace, markSpillHisto);
      chunk = chunkMap.nextChunk(chunk, ordinal, stride);
    }
  }
}

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

import static org.mmtk.policy.Space.BYTES_IN_CHUNK;
import static org.mmtk.utility.Constants.LOG_BYTES_IN_ADDRESS;
import static org.mmtk.utility.Constants.LOG_BYTES_IN_INT;
import static org.mmtk.utility.Constants.LOG_BYTES_IN_PAGE;
import static org.mmtk.policy.rcimmix.RCImmixConstants.*;

import org.mmtk.utility.Conversions;
import org.mmtk.utility.heap.Mmapper;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Extent;

@Uninterruptible
public class RCImmixChunk {

  public static Address align(Address ptr) {
    return ptr.toWord().and(CHUNK_MASK.not()).toAddress();
  }

  static boolean isAligned(Address ptr) {
    return ptr.EQ(align(ptr));
  }

  static int getByteOffset(Address ptr) {
    return ptr.toWord().and(CHUNK_MASK).toInt();
  }

  /**
   * Return the number of pages of metadata required per chunk.
   */
  static int getRequiredMetaDataPages() {
    Extent bytes = Extent.fromIntZeroExtend(ROUNDED_METADATA_BYTES_PER_CHUNK);
    return Conversions.bytesToPagesUp(bytes);
  }

  static void sweep(Address chunk, Address end, RCImmixSpace space, int[] markHistogram) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isAligned(chunk));
    Address start = getFirstUsableBlock(chunk);
    Address cursor = RCImmixBlock.getBlockMarkStateAddress(start);
    for (int index = FIRST_USABLE_BLOCK_INDEX; index < BLOCKS_IN_CHUNK; index++) {
      Address block = chunk.plus(index<<LOG_BYTES_IN_BLOCK);
      if (block.GT(end)) break;
      final boolean defragSource = space.inImmixDefragCollection() && RCImmixBlock.isDefragSource(block);
      short marked = 0;
      if (RCImmixObjectHeader.performCycleCollection) {
        marked = RCImmixBlock.sweepOneBlockCycle(block, markHistogram);
      } else {
        marked = RCImmixBlock.sweepOneBlock(block, markHistogram);
      }
      if (marked == 0) {
        if (!RCImmixBlock.isUnusedState(cursor)) {
          space.release(block);
          if (defragSource) RCImmixDefrag.defragBytesFreed.inc(BYTES_IN_BLOCK);
        }
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(RCImmixBlock.isUnused(block));
      } else {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(marked > 0 && marked <= LINES_IN_BLOCK);
        RCImmixBlock.setState(cursor, marked);
        if (RCImmixBlock.getRCAddress(block).loadByte() == RCImmixObjectHeader.ZERO) {
          space.linesCleaned += (LINES_IN_BLOCK - marked);
        }
        RCImmixBlock.getRCAddress(block).store(RCImmixObjectHeader.ONE);
        if (defragSource) RCImmixDefrag.defragBytesNotFreed.inc(BYTES_IN_BLOCK);
      }
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(RCImmixBlock.isUnused(block) || (RCImmixBlock.getBlockMarkState(block) == marked && marked > 0 && marked <= MAX_BLOCK_MARK_STATE));
      cursor = cursor.plus(RCImmixBlock.BYTES_IN_BLOCK_STATE_ENTRY);
    }
  }


  static void clearMetaData(Address chunk) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(isAligned(chunk));
      VM.assertions._assert(Conversions.isPageAligned(chunk));
      VM.assertions._assert(Conversions.isPageAligned(ROUNDED_METADATA_BYTES_PER_CHUNK));
    }
    Mmapper.ensureMapped(chunk, ROUNDED_METADATA_PAGES_PER_CHUNK);
    VM.memory.zero(false, chunk, Extent.fromIntZeroExtend(ROUNDED_METADATA_BYTES_PER_CHUNK));
    if (VM.VERIFY_ASSERTIONS) checkMetaDataCleared(chunk, chunk);
  }

  private static void checkMetaDataCleared(Address chunk, Address value) {
    VM.assertions._assert(isAligned(chunk));
    Address block = RCImmixChunk.getHighWater(chunk);
    if (value.EQ(chunk)) {
      VM.assertions._assert(block.isZero());
      block = chunk.plus(RCImmixChunk.ROUNDED_METADATA_BYTES_PER_CHUNK);
    } else {
      block = block.plus(BYTES_IN_BLOCK); // start at first block after highwater
      VM.assertions._assert(RCImmixBlock.align(block).EQ(block));
    }
    while (block.LT(chunk.plus(BYTES_IN_CHUNK))) {
      VM.assertions._assert(RCImmixChunk.align(block).EQ(chunk));
      VM.assertions._assert(RCImmixBlock.isUnused(block));
      block = block.plus(BYTES_IN_BLOCK);
    }
  }

  static void updateHighWater(Address value) {
    Address chunk = align(value);
    if (getHighWater(chunk).LT(value)) {
      setHighWater(chunk, value);
    }
  }

  private static void setHighWater(Address chunk, Address value) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isAligned(chunk));
    chunk.plus(HIGHWATER_OFFSET).store(value);
  }

  public static Address getHighWater(Address chunk) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isAligned(chunk));
    return chunk.plus(HIGHWATER_OFFSET).loadAddress();
  }

  static void setMap(Address chunk, int value) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isAligned(chunk));
    chunk.plus(MAP_OFFSET).store(value);
  }

  static int getMap(Address chunk) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isAligned(chunk));
    int rtn = chunk.plus(MAP_OFFSET).loadInt();
    return (rtn < 0) ? -rtn : rtn;
  }

  static void resetLineMarksAndDefragStateTable(Address chunk, short threshold) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isAligned(chunk));
    Address markStateBase = RCImmixBlock.getBlockMarkStateAddress(chunk);
    Address defragStateBase = RCImmixBlock.getDefragStateAddress(chunk);
    for (int b = FIRST_USABLE_BLOCK_INDEX; b < BLOCKS_IN_CHUNK; b++) {
      RCImmixBlock.resetLineMarksAndDefragStateTable(threshold, markStateBase, defragStateBase, b);
    }
  }

  static Address getFirstUsableBlock(Address chunk) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isAligned(chunk));
    Address rtn = chunk.plus(ROUNDED_METADATA_BYTES_PER_CHUNK);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(rtn.EQ(RCImmixBlock.align(rtn)));
    return rtn;
  }

  private static final int LOG_BYTES_IN_HIGHWATER_ENTRY = LOG_BYTES_IN_ADDRESS;
  private static final int HIGHWATER_BYTES = 1<<LOG_BYTES_IN_HIGHWATER_ENTRY;
  private static final int LOG_BYTES_IN_MAP_ENTRY = LOG_BYTES_IN_INT;
  private static final int MAP_BYTES = 1<<LOG_BYTES_IN_MAP_ENTRY;

  /* byte offsets for each type of metadata */
  static final int LINE_RC_TABLE_OFFSET = 0;
  static final int BLOCK_STATE_TABLE_OFFSET = LINE_RC_TABLE_OFFSET + RCImmixLine.LINE_RC_TABLE_BYTES;
  static final int BLOCK_RC_TABLE_OFFSET = BLOCK_STATE_TABLE_OFFSET + RCImmixBlock.BLOCK_STATE_TABLE_BYTES;
  static final int BLOCK_DEFRAG_STATE_TABLE_OFFSET = BLOCK_RC_TABLE_OFFSET + RCImmixBlock.BLOCK_RC_TABLE_BYTES;
  static final int HIGHWATER_OFFSET = BLOCK_DEFRAG_STATE_TABLE_OFFSET + RCImmixBlock.BLOCK_DEFRAG_STATE_TABLE_BYTES;

  static final int MAP_OFFSET = HIGHWATER_OFFSET + HIGHWATER_BYTES;
  static final int METADATA_BYTES_PER_CHUNK = MAP_OFFSET + MAP_BYTES;

  /* FIXME we round the metadata up to block sizes just to ensure the underlying allocator gives us aligned requests */
  private static final int BLOCK_MASK = (1<<LOG_BYTES_IN_BLOCK) - 1;
  static final int ROUNDED_METADATA_BYTES_PER_CHUNK = (METADATA_BYTES_PER_CHUNK + BLOCK_MASK) & ~BLOCK_MASK;
  static final int ROUNDED_METADATA_PAGES_PER_CHUNK = ROUNDED_METADATA_BYTES_PER_CHUNK>>LOG_BYTES_IN_PAGE;
  public static final int FIRST_USABLE_BLOCK_INDEX = ROUNDED_METADATA_BYTES_PER_CHUNK>>LOG_BYTES_IN_BLOCK;

}

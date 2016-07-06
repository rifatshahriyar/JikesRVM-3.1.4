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
import static org.mmtk.utility.Constants.LOG_BYTES_IN_SHORT;

import org.mmtk.vm.VM;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;

/**
 * This class defines operations over block-granularity meta-data
 *
 */
@Uninterruptible
public class RCImmixBlock {

  public static Address align(final Address ptr) {
    return ptr.toWord().and(BLOCK_MASK.not()).toAddress();
  }

  public static boolean isAligned(final Address address) {
    return address.EQ(align(address));
  }

  private static int getChunkIndex(final Address block) {
    return block.toWord().and(CHUNK_MASK).rshl(LOG_BYTES_IN_BLOCK).toInt();
  }

  /***************************************************************************
   * Block marking
   */
  public static boolean isUnused(final Address address) {
    return getBlockMarkState(address) == UNALLOCATED_BLOCK_STATE;
  }

  static boolean isUnusedState(Address cursor) {
    return cursor.loadShort() == UNALLOCATED_BLOCK_STATE;
  }

  static short getMarkState(Address cursor) {
    return cursor.loadShort();
  }

  static void setState(Address cursor, short value) {
    cursor.store(value);
  }


  public static short getBlockMarkState(Address address) {
    return getBlockMarkStateAddress(address).loadShort();
  }

  static void setBlockAsInUse(Address address) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isUnused(address));
    setBlockState(address, UNMARKED_BLOCK_STATE);
  }

  public static void setBlockAsReused(Address address) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!isUnused(address));
    setBlockState(address, REUSED_BLOCK_STATE);
  }

  static void setBlockAsUnallocated(Address address) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!isUnused(address));
    getBlockMarkStateAddress(address).store(UNALLOCATED_BLOCK_STATE);
  }

  private static void setBlockState(Address address, short value) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(value != UNALLOCATED_BLOCK_STATE);
    getBlockMarkStateAddress(address).store(value);
  }

  static Address getBlockMarkStateAddress(Address address) {
    Address chunk = RCImmixChunk.align(address);
    int index = getChunkIndex(address);
    Address rtn = chunk.plus(RCImmixChunk.BLOCK_STATE_TABLE_OFFSET).plus(index<<LOG_BYTES_IN_BLOCK_STATE_ENTRY);
    if (VM.VERIFY_ASSERTIONS) {
      Address block = chunk.plus(index<<LOG_BYTES_IN_BLOCK);
      VM.assertions._assert(isAligned(block));
      boolean valid = rtn.GE(chunk.plus(RCImmixChunk.BLOCK_STATE_TABLE_OFFSET)) && rtn.LT(chunk.plus(RCImmixChunk.BLOCK_STATE_TABLE_OFFSET+BLOCK_STATE_TABLE_BYTES));
      VM.assertions._assert(valid);
    }
    return rtn;
  }

  /***************************************************************************
   * Sweeping
   */
  static short sweepOneBlock(Address block, int[] markHistogram) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isAligned(block));
    final boolean unused = isUnused(block);
    if (unused && !SANITY_CHECK_LINE_MARKS)
      return 0;
    short totalrc = 0;
    byte lastValue = 0;
    short spillCount = 0;
    Address address = RCImmixLine.getRCAddress(RCImmixLine.align(block));
    for (int index = 0; index < LINES_IN_BLOCK; index++) {
      byte value = address.loadByte();
      if (value >= RCImmixObjectHeader.LIVE_THRESHOLD) {
        totalrc++;
      } else if (lastValue >= RCImmixObjectHeader.LIVE_THRESHOLD) {
        spillCount++;
      }
      lastValue = value;
      address = address.plus(RCImmixLine.BYTES_IN_LINE_RC_ENTRY);
    }
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(totalrc <= LINES_IN_BLOCK);
      VM.assertions._assert(totalrc + spillCount <= LINES_IN_BLOCK);
      VM.assertions._assert(totalrc == 0 || !isUnused(block));
    }
    getDefragStateAddress(block).store(spillCount);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(totalrc >= spillCount);
    markHistogram[spillCount] += totalrc;
    totalrc = (short) (totalrc + spillCount);
    return totalrc;
  }

  static short sweepOneBlockCycle(Address block, int[] markHistogram) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isAligned(block));
    final boolean unused = isUnused(block);
    if (unused && !SANITY_CHECK_LINE_MARKS)
      return 0;
    short totalrc = 0;
    byte lastValue = 0;
    short spillCount = 0;
    Address address = RCImmixLine.getRCAddress(RCImmixLine.align(block));
    for (int index = 0; index < LINES_IN_BLOCK; index++) {
      byte value = address.loadByte();
      boolean isCurrentMarked = (value & RCImmixObjectHeader.LINE_MARK_BIT_MASK) == RCImmixObjectHeader.LINE_MARK_BIT_MASK;
      boolean isLastMarked = (lastValue & RCImmixObjectHeader.LINE_MARK_BIT_MASK) == RCImmixObjectHeader.LINE_MARK_BIT_MASK;
      if (isCurrentMarked) {
        totalrc++;
        address.store((byte) (value & ~RCImmixObjectHeader.LINE_MARK_BIT_MASK));
      } else if (isLastMarked) {
        spillCount++;
      }
      if (!isCurrentMarked) {
        value = RCImmixObjectHeader.ZERO;
        address.store(value);
      }
      lastValue = value;
      address = address.plus(RCImmixLine.BYTES_IN_LINE_RC_ENTRY);
    }
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(totalrc <= LINES_IN_BLOCK);
      VM.assertions._assert(totalrc + spillCount <= LINES_IN_BLOCK);
      VM.assertions._assert(totalrc == 0 || !isUnused(block));
    }
    getDefragStateAddress(block).store(spillCount);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(totalrc >= spillCount);
    markHistogram[spillCount] += totalrc;
    totalrc = (short) (totalrc + spillCount);
    return totalrc;
  }

  /****************************************************************************
   * Block defrag state
   */

  public static boolean isDefragSource(Address address) {
    return getDefragStateAddress(address).loadShort() == BLOCK_IS_DEFRAG_SOURCE;
  }

  static void clearConservativeSpillCount(Address address) {
    getDefragStateAddress(address).store((short) 0);
  }

  static short getConservativeSpillCount(Address address) {
    return getDefragStateAddress(address).loadShort();
  }

  static Address getDefragStateAddress(Address address) {
    Address chunk = RCImmixChunk.align(address);
    int index = getChunkIndex(address);
    Address rtn = chunk.plus(RCImmixChunk.BLOCK_DEFRAG_STATE_TABLE_OFFSET).plus(index<<LOG_BYTES_IN_BLOCK_DEFRAG_STATE_ENTRY);
    if (VM.VERIFY_ASSERTIONS) {
      Address block = chunk.plus(index<<LOG_BYTES_IN_BLOCK);
      VM.assertions._assert(isAligned(block));
      boolean valid = rtn.GE(chunk.plus(RCImmixChunk.BLOCK_DEFRAG_STATE_TABLE_OFFSET)) && rtn.LT(chunk.plus(RCImmixChunk.BLOCK_DEFRAG_STATE_TABLE_OFFSET+BLOCK_DEFRAG_STATE_TABLE_BYTES));
      VM.assertions._assert(valid);
    }
    return rtn;
  }

  static void resetLineMarksAndDefragStateTable(short threshold, Address markStateBase, Address defragStateBase,
      int block) {
    Offset csOffset = Offset.fromIntZeroExtend(block<<LOG_BYTES_IN_BLOCK_DEFRAG_STATE_ENTRY);
    short state = defragStateBase.loadShort(csOffset);
    short defragState = BLOCK_IS_NOT_DEFRAG_SOURCE;
    if (state >= threshold) defragState = BLOCK_IS_DEFRAG_SOURCE;
    defragStateBase.store(defragState, csOffset);
  }

  public static Address getRCAddress(Address address) {
    Address chunk = RCImmixChunk.align(address);
    int index = getChunkIndex(address);
    Address rtn = chunk.plus(RCImmixChunk.BLOCK_RC_TABLE_OFFSET).plus(index<<LOG_BYTES_IN_BLOCK_RC_ENTRY);
    if (VM.VERIFY_ASSERTIONS) {
      Address block = chunk.plus(index<<LOG_BYTES_IN_BLOCK);
      VM.assertions._assert(isAligned(block));
      boolean valid = rtn.GE(chunk.plus(RCImmixChunk.BLOCK_RC_TABLE_OFFSET)) && rtn.LT(chunk.plus(RCImmixChunk.BLOCK_RC_TABLE_OFFSET+BLOCK_RC_TABLE_BYTES));
      VM.assertions._assert(valid);
    }
    return rtn;
  }

  private static final short UNALLOCATED_BLOCK_STATE = 0;
  private static final short UNMARKED_BLOCK_STATE = (short) (MAX_BLOCK_MARK_STATE + 1);
  private static final short REUSED_BLOCK_STATE = (short) (MAX_BLOCK_MARK_STATE + 2);

  private static final short BLOCK_IS_NOT_DEFRAG_SOURCE = 0;
  private static final short BLOCK_IS_DEFRAG_SOURCE = 1;

  /* block states */
  static final int LOG_BYTES_IN_BLOCK_STATE_ENTRY = LOG_BYTES_IN_SHORT; // use a short for now
  static final int BYTES_IN_BLOCK_STATE_ENTRY = 1<<LOG_BYTES_IN_BLOCK_STATE_ENTRY;
  static final int BLOCK_STATE_TABLE_BYTES = BLOCKS_IN_CHUNK<<LOG_BYTES_IN_BLOCK_STATE_ENTRY;

  /* block rc states */
  public static final int LOG_BYTES_IN_BLOCK_RC_ENTRY = 0;
  static final int BYTES_IN_BLOCK_RC_ENTRY = 1<<LOG_BYTES_IN_BLOCK_RC_ENTRY;
  static final int BLOCK_RC_TABLE_BYTES = BLOCKS_IN_CHUNK<<LOG_BYTES_IN_BLOCK_RC_ENTRY;

  /* per-block defrag state */
  static final int LOG_BYTES_IN_BLOCK_DEFRAG_STATE_ENTRY = LOG_BYTES_IN_SHORT;
  static final int BYTES_IN_BLOCK_DEFRAG_STATE_ENTRY = 1<<LOG_BYTES_IN_BLOCK_DEFRAG_STATE_ENTRY;
  static final int BLOCK_DEFRAG_STATE_TABLE_BYTES = BLOCKS_IN_CHUNK<<LOG_BYTES_IN_BLOCK_DEFRAG_STATE_ENTRY;
}

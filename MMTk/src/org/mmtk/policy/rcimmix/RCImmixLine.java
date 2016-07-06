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

import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Extent;

@Uninterruptible
public class RCImmixLine {

  public static Address align(Address ptr) {
    return ptr.toWord().and(LINE_MASK.not()).toAddress();
  }

  public static boolean isAligned(Address address) {
    return address.EQ(align(address));
  }

  static int getChunkIndex(Address line) {
    return line.toWord().and(CHUNK_MASK).rshl(LOG_BYTES_IN_LINE).toInt();
  }

  @Inline
  public static int getNextUnavailable(Address blockAddress, int line) {
    Address lineAddress = blockAddress.plus(Extent.fromIntZeroExtend(line<<LOG_BYTES_IN_LINE));
    lineAddress = RCImmixLine.align(lineAddress);
    Address lineRCAddress = RCImmixLine.getRCAddress(lineAddress);
    while (line < LINES_IN_BLOCK) {
      byte value = lineRCAddress.loadByte();
      if (value >= RCImmixObjectHeader.LIVE_THRESHOLD) break;
      line++;
      lineRCAddress = lineRCAddress.plus(BYTES_IN_LINE_RC_ENTRY);
    }
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(line >= 0 && line <= LINES_IN_BLOCK);
    return line;
  }

  @Inline
  public static int getNextAvailable(Address blockAddress, int line) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(line >= 0 && line < LINES_IN_BLOCK);
    Address lineAddress = blockAddress.plus(Extent.fromIntZeroExtend(line<<LOG_BYTES_IN_LINE));
    lineAddress = RCImmixLine.align(lineAddress);
    Address lineRCAddress = RCImmixLine.getRCAddress(lineAddress);
    byte last = lineRCAddress.loadByte();
    byte thisline;
    line++;
    lineRCAddress = lineRCAddress.plus(BYTES_IN_LINE_RC_ENTRY);
    while (line < LINES_IN_BLOCK) {
      thisline = lineRCAddress.loadByte();
      if (thisline < RCImmixObjectHeader.LIVE_THRESHOLD && last < RCImmixObjectHeader.LIVE_THRESHOLD) break;
      last = thisline;
      line++;
      lineRCAddress = lineRCAddress.plus(BYTES_IN_LINE_RC_ENTRY);
    }
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(line >= 0 && line <= LINES_IN_BLOCK);
    return line;
  }

  private static Address getMetaAddressRC(Address address, final int tableOffset) {
    Address chunk = RCImmixChunk.align(address);
    int index = getChunkIndex(address);
    Address rtn = chunk.plus(tableOffset + (index<<LOG_BYTES_IN_LINE_RC_ENTRY));
    if (VM.VERIFY_ASSERTIONS) {
      Address line = chunk.plus(index<<LOG_BYTES_IN_LINE);
      VM.assertions._assert(isAligned(line));
      VM.assertions._assert(align(address).EQ(line));
      boolean valid = rtn.GE(chunk.plus(tableOffset)) && rtn.LT(chunk.plus(tableOffset + LINE_RC_TABLE_BYTES));
      VM.assertions._assert(valid);
    }
    return rtn;
  }

  public static Address getRCAddress(Address address) {
    return getMetaAddressRC(address, RCImmixChunk.LINE_RC_TABLE_OFFSET);
  }


  /* per-line rc bytes */
  public static final int LOG_BYTES_IN_LINE_RC_ENTRY = 0;
  static final int BYTES_IN_LINE_RC_ENTRY = 1<<LOG_BYTES_IN_LINE_RC_ENTRY;
  static final int LINE_RC_TABLE_BYTES = LINES_IN_CHUNK<<LOG_BYTES_IN_LINE_RC_ENTRY;
  static final int LOG_LINE_RC_BYTES_PER_BLOCK = LOG_LINES_IN_BLOCK+LOG_BYTES_IN_LINE_RC_ENTRY;
  public static final int LINE_RC_BYTES_PER_BLOCK = (1<<LOG_LINE_RC_BYTES_PER_BLOCK);

}

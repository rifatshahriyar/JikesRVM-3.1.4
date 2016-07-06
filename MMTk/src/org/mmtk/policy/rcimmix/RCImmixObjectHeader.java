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

import static org.mmtk.utility.Constants.BITS_IN_BYTE;
import static org.mmtk.utility.Constants.BITS_IN_ADDRESS;

import org.mmtk.utility.ForwardingWord;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public class RCImmixObjectHeader {

  public static boolean performCycleCollection = false;
  public static boolean performSurvivorCopy = false;
  public static final byte LINE_INCREMENT = 2;
  public static final byte LIVE_THRESHOLD = 2;
  public static final byte LINE_MARK_BIT_MASK = 1;
  public static final byte ZERO = 0;
  public static final byte ONE = 1;
  public static final byte LINE_INCREMENT_MARK = 3;

   /****************************************************************************
   * Object Logging (applies to *all* objects)
   */

  /* Mask bits to signify the start/finish of logging an object */
  public static final int      LOG_BIT  = 0;
  public static final Word     LOGGED = Word.zero();                            //...00000
  public static final Word     UNLOGGED   = Word.one();                         //...00001
  public static final Word BEING_LOGGED = Word.one().lsh(2).minus(Word.one());  //...00011
  public static final Word LOGGING_MASK = LOGGED.or(UNLOGGED).or(BEING_LOGGED); //...00011

  /**
   * Return true if <code>object</code> is yet to be logged (for
   * coalescing RC).
   *
   * @param object The object in question
   * @return <code>true</code> if <code>object</code> needs to be logged.
   */
  @Inline
  @Uninterruptible
  public static boolean logRequired(ObjectReference object) {
    Word value = VM.objectModel.readAvailableBitsWord(object);
    return value.and(LOGGING_MASK).EQ(UNLOGGED);
  }

  /**
   * Attempt to log <code>object</code> for coalescing RC. This is
   * used to handle a race to log the object, and returns
   * <code>true</code> if we are to log the object and
   * <code>false</code> if we lost the race to log the object.
   *
   * <p>If this method returns <code>true</code>, it leaves the object
   * in the <code>BEING_LOGGED</code> state.  It is the responsibility
   * of the caller to change the object to <code>LOGGED</code> once
   * the logging is complete.
   *
   * @see #makeLogged(ObjectReference)
   * @param object The object in question
   * @return <code>true</code> if the race to log
   * <code>object</code>was won.
   */
  @Inline
  @Uninterruptible
  public static boolean attemptToLog(ObjectReference object) {
    Word oldValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      if (oldValue.and(LOGGING_MASK).EQ(LOGGED)) {
        return false;
      }
    } while ((oldValue.and(LOGGING_MASK).EQ(BEING_LOGGED)) ||
             !VM.objectModel.attemptAvailableBits(object, oldValue, oldValue.or(BEING_LOGGED)));
    if (VM.VERIFY_ASSERTIONS) {
      Word value = VM.objectModel.readAvailableBitsWord(object);
      VM.assertions._assert(value.and(LOGGING_MASK).EQ(BEING_LOGGED));
    }
    return true;
  }


  /**
   * Signify completion of logging <code>object</code>.
   *
   * <code>object</code> is left in the <code>LOGGED</code> state.
   *
   * @see #attemptToLog(ObjectReference)
   * @param object The object whose state is to be changed.
   */
  @Inline
  @Uninterruptible
  public static void makeLogged(ObjectReference object) {
    Word value = VM.objectModel.readAvailableBitsWord(object);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(value.and(LOGGING_MASK).NE(LOGGED));
    VM.objectModel.writeAvailableBitsWord(object, value.and(LOGGING_MASK.not()));
  }

  /**
   * Change <code>object</code>'s state to <code>UNLOGGED</code>.
   *
   * @param object The object whose state is to be changed.
   */
  @Inline
  @Uninterruptible
  public static void makeUnlogged(ObjectReference object) {
    Word oldValue, newValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      newValue = oldValue.or(UNLOGGED);
      newValue = newValue.and(MARK_BIT_MASK.not()).or(markAllocValue);
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
  }

  /************************************************************************
   * RC header word
   */
  /* The bit used for newly allocated objects */
  public static final int NEW_BIT = LOG_BIT + 2;
  public static final Word NEW_BIT_MASK = Word.one().lsh(NEW_BIT);

  /* The mark bit used for backup tracing. */
  public static final int MARK_BIT = NEW_BIT + 1;
  public static final Word MARK_BIT_MASK = Word.one().lsh(MARK_BIT);

  public static final int STRADDLE_BIT = MARK_BIT + 1;
  public static final Word STRADDLE_BIT_MASK = Word.one().lsh(STRADDLE_BIT);

  public static final int PIN_BIT = VM.config.PINNING_BIT ? (STRADDLE_BIT + 1) : STRADDLE_BIT;
  public static final Word PIN_BIT_MASK = Word.one().lsh(PIN_BIT);

  /* Current not using any bits for cycle detection, etc */
  public static final int BITS_USED = PIN_BIT + 1;

  public static Word markValue = MARK_BIT_MASK;
  public static Word markAllocValue = Word.zero();

  /* Reference counting increments */
  public static final int INCREMENT_SHIFT = BITS_USED;
  public static final Word INCREMENT = Word.one().lsh(INCREMENT_SHIFT);
  public static final int AVAILABLE_BITS = BITS_IN_ADDRESS - BITS_USED;
  public static final Word OBJECT_LIVE_THRESHOLD = INCREMENT;

  public static final Word refSticky = Word.one().lsh(BITS_IN_BYTE - BITS_USED).minus(Word.one()).lsh(INCREMENT_SHIFT);
  public static final int refStickyValue = refSticky.rshl(INCREMENT_SHIFT).toInt();
  public static final Word WRITE_MASK = refSticky.not();
  public static final Word READ_MASK = refSticky;

  /* Return values from decRC */
  public static final int DEC_KILL = 0;
  public static final int DEC_ALIVE = 1;

  /* Return values from incRC */
  public static final int INC_OLD = 0;
  public static final int INC_NEW = 1;

  /**
   * Has this object been marked by the most recent backup trace.
   */
  @Inline
  public static boolean isMarked(ObjectReference object) {
    return isHeaderMarked(VM.objectModel.readAvailableBitsWord(object));
  }

  @Inline
  public static boolean isMarked(ObjectReference object, Word headerWord) {
    return isHeaderMarked(headerWord);
  }

  /**
   * Has this object been marked by the most recent backup trace.
   */
  @Inline
  public static void clearMarked(ObjectReference object) {
    Word oldValue, newValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isHeaderMarked(oldValue));
      newValue = oldValue.xor(MARK_BIT_MASK);
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!isHeaderMarked(newValue));
  }


  /**
   * Has this object been marked by the most recent backup trace.
   */
  @Inline
  private static boolean isHeaderMarked(Word header) {
    return header.and(MARK_BIT_MASK).EQ(markValue);
  }

  @Inline
  public static boolean isMarked(byte header) {
    return (header & MARK_BIT_MASK.toInt()) == markValue.toInt();
  }

  @Inline
  public static void setMarkStateAndUnlock(ObjectReference object, byte status) {
    byte oldValue = status;
    byte newValue = (byte) (oldValue & ~ForwardingWord.FORWARDING_MASK);
    newValue = (byte) (newValue | ForwardingWord.CLEAR_FORWARDING);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!isMarked(oldValue));
    newValue = (byte) (newValue ^ MARK_BIT_MASK.toInt());
    VM.objectModel.writeAvailableByte(object, newValue);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isMarked(newValue));
  }

  @Inline
  public static void returnToPriorState(ObjectReference object, byte status) {
    VM.objectModel.writeAvailableByte(object, status);
  }

  /**
   * Attempt to atomically mark this object. Return true if the mark was performed.
   */
  @Inline
  public static boolean testAndMark(ObjectReference object) {
    Word oldValue, newValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      if (isHeaderMarked(oldValue)) {
        return false;
      }
      newValue = oldValue.xor(MARK_BIT_MASK);
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isHeaderMarked(newValue));
    return true;
  }

  @Inline
  public static void writeMarkState(ObjectReference object, boolean straddling) {
    byte oldValue = VM.objectModel.readAvailableByte(object);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!isMarked(oldValue));
    byte newValue = (byte) (oldValue & ~ForwardingWord.FORWARDING_MASK);
    newValue = (byte) (newValue | ForwardingWord.CLEAR_FORWARDING);
    newValue = (byte) (newValue ^ MARK_BIT_MASK.toInt());
    if (straddling) newValue = (byte) (newValue | STRADDLE_BIT_MASK.toInt());
    VM.objectModel.writeAvailableByte(object, newValue);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isMarked(newValue));
  }

  /**
   * Has this object been marked by the most recent backup trace.
   */
  @Inline
  public static void markAsStraddling(ObjectReference object) {
    Word oldValue, newValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      newValue = oldValue.or(STRADDLE_BIT_MASK);
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
  }

  /**
   * Has this object been marked as new
   */
  @Inline
  private static boolean isHeaderStraddle(Word header) {
    return header.and(STRADDLE_BIT_MASK).EQ(STRADDLE_BIT_MASK);
  }

  /**
   * Has this object been marked as new
   */
  @Inline
  public static boolean isStraddlingObject(ObjectReference object) {
    return isHeaderStraddle(VM.objectModel.readAvailableBitsWord(object));
  }

  /**
   * Has this object been marked as new
   */
  @Inline
  public static boolean isHeaderNew(Word header) {
    return header.and(NEW_BIT_MASK).NE(NEW_BIT_MASK);
  }

  /**
   * Has this object been marked as new
   */
  @Inline
  public static boolean isNew(ObjectReference object) {
    return isHeaderNew(VM.objectModel.readAvailableBitsWord(object));
  }

  @Inline
  public static void clearNew(ObjectReference object) {
    Word oldValue, newValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      newValue = oldValue.or(NEW_BIT_MASK);
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
  }


  /**
   * Perform any required initialization of the GC portion of the header.
   *
   * @param object the object
   * @param initialInc start with a reference count of 1 (0 if false)
   */
  @Inline
  public static void initializeHeader(ObjectReference object, boolean initialInc, int bytes) {
    Word oldValue = VM.objectModel.readAvailableBitsWord(object);
    Word initialValue =  (initialInc) ? INCREMENT : Word.zero();
    VM.objectModel.writeAvailableBitsWord(object, oldValue.or(initialValue));
  }

  @Inline
  public static void initializeHeader(ObjectReference object) {
    Word oldValue = VM.objectModel.readAvailableBitsWord(object);
    VM.objectModel.writeAvailableBitsWord(object, oldValue.or(STRADDLE_BIT_MASK));
  }

  @Inline
  public static void initializeHeaderOther(ObjectReference object, boolean initialInc) {
    Word oldValue = VM.objectModel.readAvailableBitsWord(object);
    Word initialValue =  (initialInc) ? INCREMENT : Word.zero();
    VM.objectModel.writeAvailableBitsWord(object, oldValue.or(initialValue));
  }

  /**
   * Return true if given object is live
   *
   * @param object The object whose liveness is to be tested
   * @return True if the object is alive
   */
  @Inline
  @Uninterruptible
  public static boolean isLiveRC(ObjectReference object) {
    Word value = VM.objectModel.readAvailableBitsWord(object);
    if (isStuck(value)) return true;
    return value.and(READ_MASK).GE(OBJECT_LIVE_THRESHOLD);
  }

  /**
   * Return the reference count for the object.
   *
   * @param object The object whose liveness is to be tested
   * @return True if the object is alive
   */
  @Inline
  @Uninterruptible
  public static int getRC(ObjectReference object) {
    Word value = VM.objectModel.readAvailableBitsWord(object);
    if (isStuck(value)) return refStickyValue;
    return value.and(READ_MASK).rshl(INCREMENT_SHIFT).toInt();
  }

  /**
   * Increment the reference count of an object.
   *
   * @param object The object whose reference count is to be incremented.
   */
  @Inline
  public static int incRC(ObjectReference object) {
    Word oldValue, newValue;
    int rtn;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      if (isStuck(oldValue)) return INC_OLD;
      if (isHeaderNew(oldValue)) {
        newValue = oldValue.plus(INCREMENT);
        newValue = newValue.or(NEW_BIT_MASK);
        rtn = INC_NEW;
      } else {
        newValue = oldValue.plus(INCREMENT);
        rtn = INC_OLD;
      }
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
    return rtn;
  }

  /**
   * Decrement the reference count of an object.  Return either
   * <code>DEC_KILL</code> if the count went to zero,
   * <code>DEC_ALIVE</code> if the count did not go to zero.
   *
   * @param object The object whose RC is to be decremented.
   * @return <code>DEC_KILL</code> if the count went to zero,
   * <code>DEC_ALIVE</code> if the count did not go to zero.
   */
  @Inline
  @Uninterruptible
  public static int decRC(ObjectReference object) {
    Word oldValue, newValue;
    int rtn;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      if (isStuck(oldValue)) return DEC_ALIVE;
      newValue = oldValue.minus(INCREMENT);
      if (newValue.and(READ_MASK).LT(OBJECT_LIVE_THRESHOLD)) {
        rtn = DEC_KILL;
      } else {
        rtn = DEC_ALIVE;
      }
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
    return rtn;
  }

  /**
   * Initialize the reference count of an object.
   *
   * @param object The object whose reference count is to be initialized.
   */
  @Inline
  public static int initRC(ObjectReference object) {
    Word oldValue, newValue;
    int rtn;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      newValue = oldValue.and(WRITE_MASK).or(INCREMENT);
      if (isHeaderNew(oldValue)) {
        newValue=newValue.or(NEW_BIT_MASK);
        rtn = INC_NEW;
      } else {
        rtn = INC_OLD;
      }
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
    return rtn;
  }

  /**
   * Initialize the reference count of an object.
   *
   * @param object The object whose reference count is to be initialized.
   */
  @Inline
  public static int remainRC(ObjectReference object) {
    Word oldValue, newValue;
    int rtn;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      newValue = oldValue;
      if (isHeaderNew(oldValue)) {
        newValue=newValue.or(NEW_BIT_MASK);
        rtn = INC_NEW;
      } else {
        return INC_OLD;
      }
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
    return rtn;
  }

  @Inline
  private static boolean isStuck(Word value) {
    return value.and(refSticky).EQ(refSticky);
  }

  @Inline
  private static void incLineRC(Address address) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!RCImmixBlock.isUnused(RCImmixBlock.align(address)));
    address = RCImmixLine.align(address);
    Address line = RCImmixLine.getRCAddress(address);
    byte oldValue = line.loadByte();
    byte newValue = (byte)(oldValue + LINE_INCREMENT);
    line.store(newValue);
  }

  @Inline
  private static void incMultiLineRC(Address start, ObjectReference object) {
    Address endLine = RCImmixLine.align(VM.objectModel.getObjectEndAddress(object).minus(1));
    Address line = RCImmixLine.align(start.plus(RCImmixConstants.BYTES_IN_LINE));
    while (line.LT(endLine)) {
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(RCImmixBlock.align(start).EQ(RCImmixBlock.align(line)));
      incLineRC(line);
      line = line.plus(RCImmixConstants.BYTES_IN_LINE);
    }
  }

  @Inline
  private static void decLineRC(Address address) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!RCImmixBlock.isUnused(RCImmixBlock.align(address)));
    address = RCImmixLine.align(address);
    Address line = RCImmixLine.getRCAddress(address);
    byte oldValue = line.loadByte();
    byte newValue = (byte) (oldValue - LINE_INCREMENT);
    line.store(newValue);
  }

  @Inline
  private static void decMultiLineRC(Address start, ObjectReference object) {
    Address endLine = RCImmixLine.align(VM.objectModel.getObjectEndAddress(object).minus(1));
    Address line = RCImmixLine.align(start.plus(RCImmixConstants.BYTES_IN_LINE));
    while (line.LT(endLine)) {
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(RCImmixBlock.align(start).EQ(RCImmixBlock.align(line)));
      decLineRC(line);
      line = line.plus(RCImmixConstants.BYTES_IN_LINE);
    }
  }

  @Inline
  private static boolean testAndMarkLine(Address address) {
    address = RCImmixLine.align(address);
    Address line = RCImmixLine.getRCAddress(address);
    byte oldValue = line.loadByte();
    byte newValue;
    if ((oldValue & LINE_MARK_BIT_MASK) == LINE_MARK_BIT_MASK) {
      newValue = (byte) (oldValue + LINE_INCREMENT);
    } else {
      newValue = LINE_INCREMENT_MARK;
    }
    line.store(newValue);
    return true;
  }

  @Inline
  private static void testAndMarkMultiLine(Address start, ObjectReference object) {
    Address endLine = RCImmixLine.align(VM.objectModel.getObjectEndAddress(object).minus(1));
    Address line = RCImmixLine.align(start.plus(RCImmixConstants.BYTES_IN_LINE));
    while (line.LT(endLine)) {
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(RCImmixBlock.align(start).EQ(RCImmixBlock.align(line)));
      testAndMarkLine(line);
      line = line.plus(RCImmixConstants.BYTES_IN_LINE);
    }
  }

  @Inline
  public static void incLines(ObjectReference object) {
    Address address = VM.objectModel.objectStartRef(object);
    incLineRC(address);
    if (isStraddlingObject(object)) {
      incMultiLineRC(address, object);
    }
  }

  @Inline
  public static void decLines(ObjectReference object) {
    Address address = VM.objectModel.objectStartRef(object);
    decLineRC(address);
    if (isStraddlingObject(object)) {
      decMultiLineRC(address, object);
    }
  }

  @Inline
  public static void testAndMarkLines(ObjectReference object) {
    Address address = VM.objectModel.objectStartRef(object);
    testAndMarkLine(address);
    if (isStraddlingObject(object)) {
      testAndMarkMultiLine(address, object);
    }
  }

  @Inline
  public static void pinObject(ObjectReference object) {
    if (!VM.config.PINNING_BIT) return;
    Word oldValue, newValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      newValue = oldValue.or(PIN_BIT_MASK);
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
  }

  @Inline
  public static boolean isPinnedObject(ObjectReference object) {
    if (!VM.config.PINNING_BIT) return false;
    Word value = VM.objectModel.readAvailableBitsWord(object);
    return value.and(PIN_BIT_MASK).EQ(PIN_BIT_MASK);
  }

  @Inline
  public static void writeStateYoungObject(ObjectReference object, boolean straddling) {
    byte oldValue = VM.objectModel.readAvailableByte(object);
    byte newValue = (byte) (oldValue & ~ForwardingWord.FORWARDING_MASK);
    if (straddling) newValue = (byte) (newValue | STRADDLE_BIT_MASK.toInt());
    newValue = (byte) (newValue | NEW_BIT_MASK.toInt());
    VM.objectModel.writeAvailableByte(object, newValue);
  }

  @Inline
  public static void clearStateYoungObject(ObjectReference object) {
    byte oldValue = VM.objectModel.readAvailableByte(object);
    byte newValue = (byte) (oldValue & ~ForwardingWord.FORWARDING_MASK);
    VM.objectModel.writeAvailableByte(object, newValue);
  }

  @Inline
  public static void incRCOld(ObjectReference object) {
    Word oldValue, newValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      if (isStuck(oldValue)) return;
      newValue = oldValue.plus(INCREMENT);
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue, newValue));
  }
}

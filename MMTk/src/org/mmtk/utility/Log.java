/*
 * (C) Copyright IBM Corp 2001, 2002, 2003
 *
 * (C) Copyright Department of Computer Science,
 *     Australian National University. 2003
 */
//$Id$
package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Offset;
import com.ibm.JikesRVM.VM_PragmaLogicallyUninterruptible;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_Word;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;

/**
 * Error and trace logging.
 *
 * @author Derek Lieber
 * @author Andrew Gray
 * @version $Revision$
 * @date $Date$
 */ 
public class Log implements Constants, VM_Uninterruptible {

  /****************************************************************************
   *
   * Class variables
   */

  /**
   * characters in the write buffer for the caller's message.  This
   * does not inlcude characters reserved for the overflow message.
   */
  private static final int MESSAGE_BUFFER_SIZE = 1000;

  /** message added when the write buffer has overflown */
  private static final String OVERFLOW_MESSAGE =
    "... WARNING: Text truncated.";

  private static final char OVERFLOW_MESSAGE_FIRST_CHAR =
    OVERFLOW_MESSAGE.charAt(0);

  /** characters in the overflow message */
  private static final int OVERFLOW_SIZE = OVERFLOW_MESSAGE.length();

  /**
   * characters in buffer for building string representations of
   * longs.  A long is a signed 64-bit integer in the range -2^63 to
   * 2^63+1.  The number of digits in the decimal representation of
   * 2^63 is ceiling(log10(2^63)) == ceiling(63 * log10(2)) == 19.  An
   * extra character may be required for a minus sign (-).  So the
   * maximum number of characters is 20.
   */
  private static final int TEMP_BUFFER_SIZE = 20;

  /** string that prefixes numbers logged in hexadecimal */
  private static final String HEX_PREFIX = "0x";

  /**
   * log2 of number of bits represented by a single hexidemimal digit
   */
  private static final int LOG_BITS_IN_HEX_DIGIT = 2;

  /**
   * log2 of number of digits in the unsigned hexadecimal
   * representation of a byte
   */
  private static final int LOG_HEX_DIGITS_IN_BYTE =
    LOG_BITS_IN_BYTE - LOG_BITS_IN_HEX_DIGIT;

  /**
   * map of hexadecimal digit values to their character representations
   */
  private static final char [] hexDigitCharacter =
  { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e',
    'f' };

  /** new line character.  Emitted by writeln methods. */
  private static final char NEW_LINE_CHAR = '\n';

  /** log instance used at build time. */
  private static Log log = new Log();

  /****************************************************************************
   *
   * Instance variables
   */

  /** buffer to store written message until flushing */
  private char [] buffer =
    new char[MESSAGE_BUFFER_SIZE + OVERFLOW_SIZE];

  /** location of next character to be written */
  private int bufferIndex = 0;
  
  /** <code>true</code> if the buffer has overflown */
  private boolean overflow = false;

  /** <code>true</code> if a thread id will be prepended */
  private boolean threadIdFlag = false;

  /** buffer for building string representations of longs */
  private char [] tempBuffer = new char[TEMP_BUFFER_SIZE];

  /** constructor */
  Log() {
    for (int i = 0; i < OVERFLOW_SIZE; i++)
      VM_Interface.setArrayNoBarrier(buffer, MESSAGE_BUFFER_SIZE + i,
                                     OVERFLOW_MESSAGE.charAt(i));
  }
  
  /**
   * writes a boolean.  Either "true" or "false" is logged.
   *
   * @param b boolean value to be logged.
   */
  static void write(boolean b) {
    write(b ? "true" : "false");
  }

  /**
   * writes a character
   *
   * @param c character to be logged
   */
  static void write(char c) {
    add(c);
  }

  /**
   * writes a long, in decimal.  The value is not padded and no
   * thousands seperator is logged.  If the value is negative a
   * leading minus sign (-) is logged.
   *
   * @param l long value to be logged
   */
  static void write(long l) {
    boolean negative = l < 0;
    int nextDigit;
    char nextChar;
    int index = TEMP_BUFFER_SIZE - 1;
    char [] intBuffer = getIntBuffer();
    
    nextDigit = (int)(l % 10);
    nextChar = VM_Interface.getArrayNoBarrier(hexDigitCharacter,
                                              negative
                                              ? - nextDigit
                                              : nextDigit);
    VM_Interface.setArrayNoBarrier(intBuffer, index--, nextChar);
    l = l / 10;
    
    while (l != 0) {
      nextDigit = (int)(l % 10);
      nextChar = VM_Interface.getArrayNoBarrier(hexDigitCharacter,
                                                negative
                                                ? - nextDigit
                                                : nextDigit);
      VM_Interface.setArrayNoBarrier(intBuffer, index--, nextChar);
      l = l / 10;
    }
    
    if (negative)
      VM_Interface.setArrayNoBarrier(intBuffer, index--, '-');
    
    for (index++; index < TEMP_BUFFER_SIZE; index++)
      add(VM_Interface.getArrayNoBarrier(intBuffer, index));
  }

  /**
   * writes a <code>double</code>.  Two digits after the decimal point
   * are always logged.  The value is not padded and no thousands
   * seperator is used.  If the value is negative a leading
   * hyphen-minus (-) is logged.  The decimal point is a full stop
   * (.).
   *
   * @param d the double to be logged
   */
  static void write(double d) { write(d, 2); }

  /**
   * writes a <code>double</code>.  The number of digits after the
   * decimal point is determined by <code>postDecimalDigits</code>.
   * The value is not padded and not thousands seperator is used. If
   * the value is negative a leading hyphen-minus (-) is lgoged.  The
   * decimal point is a full stop (.) and is logged even if
   * <postDecimcalDigits</code> is zero. If <code>d</code> is greater
   * than the largest representable value of type <code>int</code>, it
   * is logged as that largest value.  Similarly, if it is less than
   * the smallest resentable value, it is logged as that value.
   *
   * @param d the double to be logged
   * @param postDecimaldigits the number of digits to be logged after
   * the decimal point.  If less than or equal to zero no digits are
   * logged, but the decimal point is.
   */
  static void write(double d, int postDecimalDigits) {
    if (VM_Interface.VerifyAssertions)
      VM_Interface._assert(d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE);
    boolean negative = (d < 0.0);
    d = (d < 0.0) ? (-d) : d;
    int ones = (int) d;
    int multiplier = 1;
    while (postDecimalDigits-- > 0)
      multiplier *= 10;
    int remainder = (int) (multiplier * (d - ones));
    if (negative) write('-');
    write(ones); 
    write('.');
    while (multiplier > 1) {
      multiplier /= 10;
      write(remainder / multiplier);
      remainder %= multiplier;
    }
  }

  /**
   * writes an array of characters
   *
   * @param c the array of characters to be logged
   */
  static void write(char [] c) {
    write(c, c.length);
  }

  /**
   * writes the start of an array of characters
   *
   * @param c the array of characters
   * @param len the number of characters to be logged, starting with
   * the first character
   */
  static void write(char [] c, int len) {
    if (VM_Interface.VerifyAssertions) VM_Interface._assert(len <= c.length);
    for (int i = 0; i < len; i++)
      add(VM_Interface.getArrayNoBarrier(c, i));
  }

  /**
   * writes an array of bytes.  The bytes are interpretted
   * as characters.
   *
   * @param b the array of bytes to be logged
   */
  static void write(byte [] b) {
    for (int i = 0; i < b.length; i++)
      add((char)VM_Interface.getArrayNoBarrier(b, i));
  }

  /**
   * writes a string
   *
   * @param s the string to be logged
   */
  static void write(String s) {
    add(s);
  }

  /**
   * writes a word, in hexadecimal.  It is zero-padded to the size of
   * an address.
   *
   * @param w the word to be logged
   */
  static void write(VM_Word w) {
    write(w.toAddress());
  }

  /**
   * writes an address, in hexademical.  It is zero-padded.
   *
   * @param a the address to be logged
   */
  static void write(VM_Address a) {
    writeHex(VM_Interface.addressToLong(a), BYTES_IN_ADDRESS);
  }

  /**
   * writes an offset, in hexademical.  It is zero-padded.
   *
   * @param o the offset to be logged
   */
  static void write(VM_Offset o) {
    writeHex(VM_Interface.offsetToLong(o), BYTES_IN_ADDRESS);
  }

  /**
   * write a new-line and flushes the buffer
   */
  static void writeln() {
    writelnWithFlush(true);
  }

  /**
   * writes a boolean and a new-line, then flushes the buffer.
   * @see #write(boolean)
   *
   * @param b boolean value to be logged.
   */
  static void writeln(boolean b) { writeln(b, true); }

  /**
   * writes a character and a new-line, then flushes the buffer.
   * @see #write(char)
   *
   * @param c character to be logged
   */
  static void writeln(char c)    { writeln(c, true); }

  /**
   * writes a long, in decimal, and a new-line, then flushes the buffer.
   * @see #write(long)
   *
   * @param l long value to be logged
   */
  static void writeln(long l)    { writeln(l, true); }

  /**
   * writes a <code>double</code> and a new-line, then flushes the buffer.
   * @see #write(double)
   *
   * @param d the double to be logged
   */
  static void writeln(double d)  { writeln(d, true); }

  /**
   * writes a <code>double</code> and a new-line, then flushes the buffer.
   * @see #write(double, int)
   *
   * @param d the double to be logged
   */
  static void writeln(double d, int postDecimalDigits) {
    writeln(d, postDecimalDigits, true); }

  /**
   * writes an array of characters and a new-line, then flushes the buffer.
   * @see #write(char [])
   *
   * @param c the array of characters to be logged
   */
  static void writeln(char [] ca) { writeln(ca, true); }

  /**
   * writes the start of an array of characters and a new-line, then
   * flushes the buffer.
   * @see #write(char [], int)
   *
   * @param c the array of characters
   * @param len the number of characters to be logged, starting with
   * the first character
   */
  static void writeln(char [] ca, int len) { writeln(ca, len, true); }

  /**
   * writes an array of bytes and a new-line, then
   * flushes the buffer.
   * @see #write(byte [])
   *
   * @param b the array of bytes to be logged
   */
  static void writeln(byte [] b) { writeln(b, true); }

  /**
   * writes a string and a new-line, then flushes the buffer.
   *
   * @param s the string to be logged
   */
  static void writeln(String s)  { writeln(s, true); }

  /**
   * writes a word, in hexadecimal, and a new-line, then flushes the buffer.
   * @see #write(VM_Word)
   *
   * @param w the word to be logged
   */
  static void writeln(VM_Word w) { writeln(w, true); }

  /**
   * writes an address, in hexademical, and a new-line, then flushes the buffer.
   * @see #write(VM_Address)
   *
   * @param a the address to be logged
   */
  static void writeln(VM_Address a) { writeln(a, true); }

  /**
   * writes an offset, in hexademical, and a new-line, then flushes the buffer.
   * @see #write(VM_Offset)
   *
   * @param o the offset to be logged
   */
  static void writeln(VM_Offset o) { writeln(o, true); }

  /**
   * writes a new-line without flushing the buffer
   */
  static void writelnNoFlush() {
    writelnWithFlush(false);
  }

  /**
   * writes a boolean and a new-line, then optionally flushes the buffer.
   * @see #write(boolean)
   *
   * @param b boolean value to be logged.
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(boolean b, boolean flush) {
    write(b);
    writelnWithFlush(flush);
  }

  /**
   * writes a character and a new-line, then optionally flushes the
   * buffer.
   * @see #write(char)
   *
   * @param c character to be logged
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(char c, boolean flush) {
    write(c);
    writelnWithFlush(flush);
  }

  /**
   * writes a long, in decimal, and a new-line, then optionally flushes
   * the buffer.
   * @see #write(long)
   *
   * @param l long value to be logged
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(long l, boolean flush) {
    write(l);
    writelnWithFlush(flush);
  }

  /**
   * writes a <code>double</code> and a new-line, then optionally flushes
   * the buffer.
   * @see #write(double)
   *
   * @param d the double to be logged
   * @param flush if <code>true</code> then flush the buffer
   */
  static void writeln(double d, boolean flush) {
    write(d);
    writelnWithFlush(flush);
  }

  /**
   * writes a <code>double</code> and a new-line, then optionally flushes
   * the buffer.
   * @see #write(double, int)
   *
   * @param d the double to be logged
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(double d, int postDecimalDigits, boolean flush) {
    write(d, postDecimalDigits);
    writelnWithFlush(flush);
  }


  /**
   * writes an array of characters and a new-line, then optionally
   * flushes the buffer.
   * @see #write(char [])
   *
   * @param c the array of characters to be logged
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(char [] ca, boolean flush) {
    write(ca);
    writelnWithFlush(flush);
  }

  /**
   * writes the start of an array of characters and a new-line, then
   * optionally flushes the buffer.
   * @see #write(char [], int)
   *
   * @param c the array of characters
   * @param len the number of characters to be logged, starting with
   * the first character
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(char [] ca, int len, boolean flush) {
    write(ca, len);
    writelnWithFlush(flush);
  }

  /**
   * writes an array of bytes and a new-line, then optionally flushes the
   * buffer.
   * @see #write(byte [])
   *
   * @param b the array of bytes to be logged
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(byte [] b, boolean flush) {
    write(b);
    writelnWithFlush(flush);
  }

  /**
   * writes a string and a new-line, then optionally flushes the buffer.
   *
   * @param s the string to be logged
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(String s, boolean flush) {
    write(s);
    writelnWithFlush(flush);
  }


  /**
   * writes a word, in hexadecimal, and a new-line, then optionally
   * flushes the buffer.
   * @see #write(VM_Word)
   *
   * @param w the word to be logged
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(VM_Word w, boolean flush) {
    write(w);
    writelnWithFlush(flush);
  }


  /**
   * writes an address, in hexademical, and a new-line, then optionally
   * flushes the buffer.
   * @see #write(VM_Address)
   *
   * @param a the address to be logged
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(VM_Address a, boolean flush) {
    write(a);
    writelnWithFlush(flush);
  }

  /**
   * writes an offset, in hexademical, and a new-line, then optionally
   * flushes the buffer.
   * @see #write(VM_Offset)
   *
   * @param o the offset to be logged
   * @param flush if <code>true</code> then flushes the buffer
   */
  static void writeln(VM_Offset o, boolean flush) {
    write(o);
    writelnWithFlush(flush);
  }

  /**
   * Log a thread identifier at the start of the next message flushed.
   */
  static void prependThreadId()
  {
    getLog().setThreadIdFlag();
  }

  /**
   * flushes the buffer.  The buffered effected of writes since the last
   * flush will be logged in one block without output from other
   * thread's logging interleaving.
   */
  static void flush() {
    getLog().flushBuffer();
  }

  /**
   * writes a new-line and optionally flushes the buffer
   *
   * @param flush if <code>true</code> the buffer is flushed
   */
  private static void writelnWithFlush(boolean flush) {
    add(NEW_LINE_CHAR);
    if (flush)
      flush();
  }

  /**
   * writes a <code>long</code> in hexadecimal
   *
   * @param l the long to be logged
   * @param bytes the number of bytes from the long to be logged.  If
   * less than 8 then the least significant bytes are logged and some
   * of the most significant bytes are ignored.
   */
  private static void writeHex(long l, int bytes) {
    int hexDigits = bytes * (1 << LOG_HEX_DIGITS_IN_BYTE);
    int nextDigit;
    char [] intBuffer = getIntBuffer();

    write(HEX_PREFIX);

    for (int digitNumber = hexDigits - 1; digitNumber >= 0; digitNumber--) {
      nextDigit = (int)(l >>> (digitNumber << LOG_BITS_IN_HEX_DIGIT)) & 0xf;
      char nextChar = VM_Interface.getArrayNoBarrier(hexDigitCharacter,
                                                     nextDigit);
      add(nextChar);
    }
  }

  /**
   * adds a character to the buffer
   *
   * @param c the character to add
   */
  private static void add(char c) {
    getLog().addToBuffer(c);
  }

  /**
   * adds a string to the buffer
   *
   * @param s the string to add
   */
  private static void add(String s) {
    getLog().addToBuffer(s);
  }

  private static Log getLog() {
    if (VM_Interface.runningVM())
      return VM_Interface.getPlan().getLog();
    else
      return log;
  }

  /**
   * adds a character to the buffer
   *
   * @param c the character to add
   */
  private void addToBuffer(char c) {
    if (bufferIndex < MESSAGE_BUFFER_SIZE)
      VM_Interface.setArrayNoBarrier(buffer, bufferIndex++, c);
    else
      overflow = true;
  }

  /**
   * adds a string to the buffer
   *
   * @param s the string to add
   */
  private void addToBuffer(String s) {
    if (bufferIndex < MESSAGE_BUFFER_SIZE) {
      bufferIndex += VM_Interface.copyStringToChars(s, buffer, bufferIndex,
                                                    MESSAGE_BUFFER_SIZE + 1);
      if (bufferIndex == MESSAGE_BUFFER_SIZE + 1) {
        overflow = true;
        VM_Interface.setArrayNoBarrier(buffer, MESSAGE_BUFFER_SIZE,
                                       OVERFLOW_MESSAGE_FIRST_CHAR); 
        bufferIndex--;
      }
    } else
      overflow = true;
  }

  /**
   * flushes the buffer
   */
  private void flushBuffer() {
    int totalMessageSize = overflow ? MESSAGE_BUFFER_SIZE + OVERFLOW_SIZE
      : bufferIndex;
    if (threadIdFlag)
      VM_Interface.sysWriteThreadId(buffer, totalMessageSize);
    else
      VM_Interface.sysWrite(buffer, totalMessageSize);
    threadIdFlag = false;
    overflow = false;
    bufferIndex = 0;
  }

  /**
   * sets the flag so that a thread identifier will be included before
   * the logged message
   */
  private void setThreadIdFlag() {
    threadIdFlag = true;
  }

  /**
   * gets the buffer for building string representations of integers.
   * There is one of these buffers for each Log instance.
   */
  private static char [] getIntBuffer() {
    return getLog().getTempBuffer();
  }

  /**
   * gets the buffer for building string representations of integers.
   */
  private char [] getTempBuffer() {
    return tempBuffer;
  }
}
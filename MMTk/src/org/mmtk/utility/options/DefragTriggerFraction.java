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
package org.mmtk.utility.options;

/**
 * Should a major GC be performed when a system GC is triggered?
 */
public final class DefragTriggerFraction extends org.vmutil.options.FloatOption {
  /**
   * Create the option.
   */
  public DefragTriggerFraction() {
    super(Options.set, "Defrag Trigger Fraction",
          "Should a major GC be performed when a system GC is triggered?",
          0.01f);
  }

  /**
   * Ensure the value is valid.
   */
  @Override
  protected void validate() {
    failIf((this.value < 0 || this.value > 1.0), "Ratio must be a float between 0 and 1");
  }
}

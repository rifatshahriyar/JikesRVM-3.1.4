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

import static org.mmtk.policy.rcimmix.RCImmixConstants.DEFAULT_SURVIVOR_COPY_MULTIPLIER;

public class SurvivorCopyMultiplier extends org.vmutil.options.FloatOption {
  /**
   * Create the option.
   */
  public SurvivorCopyMultiplier() {
    super(Options.set, "Survivor Copy Multiplier",
          "Allow the copy this fraction of the heap as headroom during survivor copy.",
          DEFAULT_SURVIVOR_COPY_MULTIPLIER);
  }

  /**
   * Ensure the value is valid.
   */
  @Override
  protected void validate() {
    failIf((this.value < 0 || this.value > 5.0), "Ratio must be a float between 0 and 1");
  }
}

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

import org.mmtk.utility.alloc.RCImmixAllocator;

import org.vmmagic.pragma.*;

/**
 *
 */
@Uninterruptible
public final class RCImmixMutatorLocal extends RCImmixAllocator {
  /**
   * Constructor
   *
   * @param space The mark-sweep space to which this allocator
   * instances is bound.
   * @param hot TODO
   */
  public RCImmixMutatorLocal(RCImmixSpace space, boolean hot) {
    super(space, hot, false);
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * Prepare for a collection. If paranoid, perform a sanity check.
   */
  public void prepare() {
    reset();
  }

  /**
   * Finish up after a collection.
   */
  public void release() {
    reset();
  }
}

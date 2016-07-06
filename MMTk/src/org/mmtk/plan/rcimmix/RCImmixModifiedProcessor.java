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
package org.mmtk.plan.rcimmix;

import org.mmtk.plan.TransitiveClosure;
import org.mmtk.policy.Space;
import org.mmtk.policy.rcimmix.RCImmixObjectHeader;
import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This class is the fundamental mechanism for performing a
 * transitive closure over an object graph.<p>
 *
 * @see org.mmtk.plan.TraceLocal
 */
@Uninterruptible
public final class RCImmixModifiedProcessor extends TransitiveClosure {

  private RCImmixCollector collector;

  public RCImmixModifiedProcessor(RCImmixCollector ctor) {
    this.collector = ctor;
  }

  @Override
  @Inline
  public void processEdge(ObjectReference source, Address slot) {
    ObjectReference object = slot.loadObjectReference();
    if (RCImmix.isRCObject(object)) {
      if (RCImmix.CC_BACKUP_TRACE && RCImmix.performCycleCollection) {
        if (RCImmixObjectHeader.remainRC(object) == RCImmixObjectHeader.INC_NEW) {
          collector.getModBuffer().push(object);
        }
      } else {
        if (RCImmix.RC_SURVIVOR_COPY) {
          collector.survivorCopy(slot, object, false);
        } else {
          if (RCImmixObjectHeader.incRC(object) == RCImmixObjectHeader.INC_NEW) {
            if (Space.isInSpace(RCImmix.REF_COUNT, object)) {
              RCImmixObjectHeader.incLines(object);
            }
            collector.getModBuffer().push(object);
          }
        }
      }
    }
  }
}

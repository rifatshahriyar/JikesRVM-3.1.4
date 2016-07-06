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

import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.Trace;
import org.mmtk.policy.rcimmix.RCImmixObjectHeader;
import org.mmtk.utility.deque.AddressDeque;
import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This class implements the thread-local core functionality for a transitive
 * closure over the heap graph.
 */
@Uninterruptible
public final class RCImmixRootSetTraceLocal extends TraceLocal {

  private final AddressDeque rootPointerBuffer;

  /**
   * Constructor
   */
  public RCImmixRootSetTraceLocal(Trace trace, AddressDeque rootPointerBuffer) {
    super(trace);
    this.rootPointerBuffer = rootPointerBuffer;
  }

  /****************************************************************************
   *
   * Externally visible Object processing and tracing
   */

  /**
   * Is the specified object reachable?
   *
   * @param object The object.
   * @return <code>true</code> if the object is reachable.
   */
  @Override
  public boolean isLive(ObjectReference object) {
    return RCImmix.isRCObject(object) && RCImmixObjectHeader.isLiveRC(object) || super.isLive(object);
  }

  /**
   * When we trace a non-root object we do nothing.
   *
   * @param object The object to be traced.
   * @return The new reference to the same object instance.
   */
  @Override
  @Inline
  public ObjectReference traceObject(ObjectReference object) {
    return object;
  }

  @Override
  @Inline
  public void processRootEdge(Address slot, boolean untraced) {
    if (!slot.isZero()) rootPointerBuffer.push(slot);
  }

  @Override
  @Inline
  public boolean willNotMoveInCurrentCollection(ObjectReference object) {
    return true;
  }
}

/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

/**
 * Class that supports scanning thread stacks for references during
 * collections. References are located using GCMapIterators and are
 * processed by invoking the processPtrField method of VM_Allocator.
 *
 * @author Stephen Smith
 */  
public class VM_ScanStack
  implements VM_Constants, VM_GCConstants, VM_Uninterruptible {

  // quietly validates each ref reported by map iterators
  static final boolean VALIDATE_STACK_REFS = true;

  // debugging options to produce printout during scanStack
  // MULTIPLE GC THREADS WILL PRODUCE SCRAMBLED OUTPUT so only
  // use these when running with PROCESSORS=1

  // includes in output a dump of the contents of each frame
  // forces DUMP_STACK_REFS & TRACE_STACKS on (ie. everything!!)
  static final boolean DUMP_STACK_FRAMES = false;

  // includes in output the refs reported by map iterators
  // forces TRACE_STACKS on 
  static final boolean DUMP_STACK_REFS = DUMP_STACK_FRAMES || false;

  // outputs names of methods as their frames are scanned
  static final boolean TRACE_STACKS = DUMP_STACK_REFS || false;

  /**
   * Scans a threads stack during collection to find object references.
   * Locates and updates references in stack frames using stack maps,
   * and references associated with JNI native frames.  Located references
   * are processed by calling VM_Allocator.processPtrField.
   * <p>
   * If relocate_code is true, moves code objects, and updates saved
   * link registers in the stack frames.
   *
   * @param t              VM_Thread for the thread whose stack is being scanned
   * @param top_frame      address of stack frame at which to begin the scan
   * @param relocate_code  if true, relocate code & update return addresses
   */
  static void 
  scanStack (VM_Thread t, int top_frame, boolean relocate_code)  {
    int       ip, fp, code, newip, newcode, delta, refaddr, prevFp;
    VM_GCMapIterator iterator;
    VM_GCMapIteratorGroup iteratorGroup;

    /*******
    // [6/9/99 DL] [Can this still happen? 4/1/01 SES]
    // Before scanning the thread's stack, copy forward any machine code that is
    // referenced by the thread's registers and adjust those registers to point
    // to the moved code. Note that "contextRegisters" never hold any machine code 
    // references, but "hardwareExceptionRegisters" might: namely in "lr" and "ip".
    //
    if (t.hardwareExceptionRegisters.inuse) {
      // live registers: fixup lr & ip
      int reg;
      reg = t.hardwareExceptionRegisters.lr;
      if (minFromRef <= reg && reg <= maxFromRef)
	t.hardwareExceptionRegisters.lr = gc_copyCode(reg);
      reg = t.hardwareExceptionRegisters.ip;
      if (minFromRef <= reg && reg <= maxFromRef)
	t.hardwareExceptionRegisters.ip = gc_copyCode(reg);
    }
    *******/
    
    // get gc thread local iterator group from our VM_CollectorThread object
    VM_CollectorThread collector = VM_Magic.threadAsCollectorThread(VM_Thread.getCurrentThread());
    iteratorGroup = collector.iteratorGroup;
    iteratorGroup.newStackWalk(t);
    
    if (TRACE_STACKS) {
      VM_Scheduler.trace("VM_ScanStack", "Thread id", t.getIndex());
      if ( relocate_code )
	VM.sysWrite("(relocate_code)\n");
      else
	VM.sysWrite("\n");
    }

    if ( top_frame != 0 ) {
      prevFp = top_frame;
      // start scan at caller of passed in fp
      ip = VM_Magic.getReturnAddress(top_frame);
      fp = VM_Magic.getCallerFramePointer(top_frame);
    }
    else {
      prevFp = 0;
      // start scan using fp & ip in threads saved context registers
      ip = t.contextRegisters.getInnermostInstructionAddress();
      fp = t.contextRegisters.getInnermostFramePointer();
    }

    if (TRACE_STACKS) {
      VM.sysWrite("  top_frame = "); VM.sysWriteHex(top_frame); VM.sysWrite("\n");
      VM.sysWrite("         ip = "); VM.sysWriteHex(ip); VM.sysWrite("\n");
      VM.sysWrite("         fp = "); VM.sysWriteHex(fp); VM.sysWrite("\n");
      VM.sysWrite("  registers.ip = "); VM.sysWriteHex(t.contextRegisters.ip); VM.sysWrite("\n");
    }
    
    if (DUMP_STACK_REFS) {
      if (t.jniEnv != null ) t.jniEnv.dumpJniRefsStack();
    }

    // It is possible to have a stack with only Native C frames, for a thread
    // that started in C, "attached" to the VM, made JNIFunction calls, and
    // has now returned back to C. In this case, scanStack should be called
    // with a "topJavaFrame" = 0. There may be references in the threads
    // JNIrefs side stack that need to be processed, below after the loop.

    if ( fp != STACKFRAME_SENTINAL_FP) {

    if (DUMP_STACK_REFS) {
      VM_Scheduler.dumpStack( ip, fp ); VM.sysWrite("\n");
    }

    // At start of loop:
    //   fp -> frame for method invocation being processed
    //   ip -> instruction pointer in the method (normally a call site)
    
    while (VM_Magic.getCallerFramePointer(fp) != STACKFRAME_SENTINAL_FP) {
      
      int compiledMethodId = VM_Magic.getCompiledMethodID(fp);

      // reflection and jni generate "invisible" transition frames with
      // method_id == 0.  reflections frames are skipped here. For PowerPC
      // JNI C to java transitions frames exist, but should be skipped at the
      // end of this loop during incrementing tothe "next" frame.
      //
      if (compiledMethodId == VM_Constants.INVISIBLE_METHOD_ID) {
	
	if (TRACE_STACKS) VM.sysWrite("\n--- METHOD --- <invisible method>\n");
	
	// skip "invisible" frame
	prevFp = fp;
	ip = VM_Magic.getReturnAddress(fp);
	fp = VM_Magic.getCallerFramePointer(fp);
	continue;
      }

      // following is for normal Java (and JNI Java to C transition) frames
      
      VM_CompiledMethod compiledMethod = VM_ClassLoader.getCompiledMethod(compiledMethodId);
      VM_Method         method = compiledMethod.getMethod();

      // initialize MapIterator for this frame
      int offset = ip - VM_Magic.objectAsAddress(compiledMethod.getInstructions());
      iterator = iteratorGroup.selectIterator(compiledMethod);
      iterator.setupIterator(compiledMethod, offset, fp);
      
      if (TRACE_STACKS) {
	VM_Scheduler.outputMutex.lock();
	VM.sysWrite("\n--- METHOD --- ");
	VM.sysWrite(method);
	VM.sysWrite(" at offset ");
	VM.sysWrite(offset,false);
	VM.sysWrite(".\n");
	VM_Scheduler.outputMutex.unlock();
      }
      if (DUMP_STACK_FRAMES) dumpStackFrame( fp, prevFp );

      if (DUMP_STACK_REFS) 
	VM.sysWrite("--- Refs Reported By GCMap Iterator ---\n");

      // scan the map for this frame and process each reference
      //
      for (refaddr = iterator.getNextReferenceAddress();  refaddr != 0;
	   refaddr = iterator.getNextReferenceAddress()) {

	if (VM.VerifyAssertions && VALIDATE_STACK_REFS) {
	  int ref = VM_Magic.getMemoryWord(refaddr);
	  if (!VM_GCUtil.validRef(ref)) {
	    VM.sysWrite("\nInvalid ref reported while scanning stack\n");
	    VM.sysWrite("--- METHOD --- ");
	    VM.sysWrite(method);
	    VM.sysWrite(" at offset ");
	    VM.sysWrite(offset,false);
	    VM.sysWrite(".\n");
	    VM.sysWrite(" fp = "); VM.sysWriteHex(fp);
	    VM.sysWrite(" ip = "); VM.sysWriteHex(ip); VM.sysWrite("\n");
	    // dump out bad ref
	    VM.sysWriteHex(refaddr); VM.sysWrite(":"); VM_GCUtil.dumpRef(ref);
	    // dump out contents of frame
	    dumpStackFrame( fp, prevFp );
	    // dump stack starting at current frame
	    VM.sysWrite("\nDumping stack starting at frame with bad ref:\n");
	    VM_Scheduler.dumpStack( ip, fp );
	    // start stact starting at top
	    int top_ip = t.contextRegisters.getInnermostInstructionAddress();
	    int top_fp = t.contextRegisters.getInnermostFramePointer();
	    VM_Scheduler.dumpStack( top_ip, top_fp );
	  }
	}
	if (DUMP_STACK_REFS) {
	  int ref = VM_Magic.getMemoryWord(refaddr);
	  VM.sysWriteHex(refaddr); VM.sysWrite(":"); VM_GCUtil.dumpRef(ref);
	}
	
	VM_Allocator.processPtrField( refaddr );
      }
      
      if (VM_Allocator.movesObjects && relocate_code) {
	// process the code object for the method, and if it is moved, relocate
	// the saved ip and any internal code pointers (JSR subroutine return
	// addresses. Note, the instructions field of the VM_Method object is
	// NOT updated at this time, so that other invocations of the same
	// method can use the original address to compute the relocation delta.
	//
	code = VM_Magic.objectAsAddress( compiledMethod.getInstructions() );
	newcode = VM_Allocator.processPtrValue( code );

	if (newcode != code) {
	  delta = newcode - code;  // amount to relocate return addresses
	  
	  // if the return address is in the BootImage ( in some OutOfLineMachineCode
	  // that called someone e.g. called native code ) do not relocate the return 
	  // address ... just the rest of the code that is in the normal heap.
	  //
	  if (! VM_GCUtil.addressInBootImage(ip)) {  // normal case
	    newip = ip + delta;
	    if (TRACE_STACKS) {
	      VM_Scheduler.traceHex("  moved code - ", "old ip", ip);
	      VM_Scheduler.traceHex("               ", "new ip", newip);
	    }
	  } else {
	    newip = ip;    // do not relocate
	    if (TRACE_STACKS)
	      VM_Scheduler.traceHex("  moved code - ", "ip in BootImage", ip);
	  }
	  
	  // relocate return address
	  if (prevFp != 0) {
	    // set return address:
	    //    intel - pushed on stack by call instruction
	    //    power - stored in frame header by prolog
	    VM_Magic.setReturnAddress(prevFp, newip );
	  }
	  else {
	    // top-most stack frame, ip saved in threads context regs
	    t.contextRegisters.ip = newip;
	  }
	
	  // scan for internal code pointers in the stack frame and relocate
	  iterator.reset();
	  for (int retaddr = iterator.getNextReturnAddressAddress();  retaddr != 0;
	       retaddr = iterator.getNextReturnAddressAddress()) {
	    // relocate internal code pointer
	    if (TRACE_STACKS)
	      VM_Scheduler.traceHex("  relocating return address", "at", retaddr);
	    VM_Magic.setMemoryWord( retaddr, VM_Magic.getMemoryWord(retaddr) + delta);
	  }
	}
      } //movesObjects

      // if at a JNIFunction method, it is preceeded by native frames that must be skipped
      //
      if (compiledMethod.getMethod().getDeclaringClass().isBridgeFromNative()) {
	// skip native frames, stopping at last native frame PRECEEDING the
	// Java To C transition frame
	fp = VM_Runtime.unwindNativeStackFrame(fp);

	if ( TRACE_STACKS ) VM.sysWrite("scanStack skipping native C frames\n");
      }       

      // set fp & ip for next frame
      prevFp = fp;
      ip = VM_Magic.getReturnAddress(fp);
      fp = VM_Magic.getCallerFramePointer(fp);
      
    } // end of while != sentinel

    } // end of if (fp != STACKFRAME_SENTINAL_FP)
    
    // if we are scanning the stack of a thread that entered the VM via a createJVM
    // or attachJVM then the "bottom" of the stack had native C frames instead of the 
    // usual java frames.  The JNIEnv for the thread may still contain jniRefs that
    // have been returned to the native C code, but have not been reported for GC.
    // calling getNextReferenceAddress without first calling setup... will report the
    // remaining jniRefs in the current "frame" of the jniRefs stack.  (this should
    // be the bottom frame)
    //
    //-#if RVM_FOR_AIX
    iterator = iteratorGroup.getJniIterator();
    refaddr =  iterator.getNextReferenceAddress();
    while( refaddr!=0 ) {
      VM_Allocator.processPtrField( refaddr );
      refaddr =  iterator.getNextReferenceAddress();
    }
    //-#else
    // exclude PPC FOR LINUX
    //-#endif
    
    if (TRACE_STACKS) VM.sysWrite("--- End Of Stack Scan ---\n");
    
  } //gc_scanStack
  

  // dump contents of a stack frame. attempts to interpret each
  // word a an object reference
  //
  static void
  dumpStackFrame( int fp, int prevFp ) {
    int start,end;
//-#if RVM_FOR_IA32
    if (prevFp==0) {
      start = fp - 20*WORDSIZE;
      VM.sysWrite("--- 20 words of stack frame with fp = ");
    }
    else {
      start = prevFp;    // start at callee fp
      VM.sysWrite("--- stack frame with fp = ");
    }
    VM.sysWriteHex(fp);
    VM.sysWrite(" ----\n");
    end = fp;            // end at fp
//-#endif
//-#if RVM_FOR_POWERPC
    VM.sysWrite("--- stack frame with fp = ");
    VM.sysWriteHex(fp);
    VM.sysWrite(" ----\n");
    start = fp;                         // start at fp
    end = VM_Magic.getMemoryWord(fp);   // stop at callers fp
//-#endif

    for ( int loc = start; loc <= end; loc+=WORDSIZE ) {
      VM.sysWrite(loc-start,false);
      VM.sysWrite(" ");
      VM.sysWriteHex(loc);
      VM.sysWrite(" ");
      int value = VM_Magic.getMemoryWord(loc);
      VM.sysWriteHex(value);
      VM.sysWrite(" ");
      if ( VM_GCUtil.referenceInVM(value) && (loc!=start) && (loc!=end) )
	VM_GCUtil.dumpRef(value);
      else
	VM.sysWrite("\n");
    }
    VM.sysWrite("\n");
  }

}   // VM_GCUtil
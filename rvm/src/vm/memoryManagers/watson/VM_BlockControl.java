/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

/**
 * Used by noncopying memory managers: There is 1 VM_BlockControl
 * for each GC_BLOCKSIZE (see VM_GCConstants.java in this directory)
 * bytes (aka one "chunk"
 * in the small object heap. The array of VM_BlockControls is 
 * allocated by VM_Allocator.boot() at RVM startup. baseAddr
 * is the address of the first byte of the chunk controlled by this
 * object.  It never changes, but is stored to avoid computing it 
 * repeatedly. slotsize is the size of each slot into which this 
 * chunk is partitioned during execution (see VM_SizeControl.java and
 * VM_GCConstants.java.) mark is used to record whether the associated
 * slot is live or not during GC. nextblock is used to chain together 
 * chunks allocated to the same slotsize.  live is set during GC to 
 * indicate that the chunk contains live object(s).  alloc_size is 
 * used to avoid computation during execution.
 *
 * @see VM_Allocator
 *
 * @author Dick Attanasio
 */

///TODO: remove Alloc1, Alloc2, and byte[] alloc.
//
// Size field = 44: must be changed if object size changes.
class VM_BlockControl 
	implements VM_Constants 
{
static final int Size = 44 + SCALAR_HEADER_SIZE;
int baseAddr;
int slotsize;		// slotsize
byte[] mark;
byte[] alloc;
int nextblock;
byte[] Alloc1;
byte[] Alloc2;
boolean live;
boolean sticky;
int alloc_size;	// allocated length of mark and alloc arrays
int allocCount; // RCGC number of allocated slots in the block
}
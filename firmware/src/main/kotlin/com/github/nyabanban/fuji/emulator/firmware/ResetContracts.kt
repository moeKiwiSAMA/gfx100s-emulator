package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FW-020: shared barrier producers/consumers and exact DAT L1 table span. No synchronization writes. */
object ResetContracts {
    const val PROFILE="kernel-page-table-experimental-v6"
    private fun word(bytes: ByteArray,pc: Int,base: Int)=ByteBuffer.wrap(bytes,pc-base,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    private fun imm16(w: Long)=(w and 0xfff) or ((w ushr 4) and 0xf000)
    private fun immediate(w: Long)=Integer.rotateRight((w and 255).toInt(),(((w ushr 8) and 15)*2).toInt()).toLong() and 0xffffffffL
    private fun pair(bytes: ByteArray,pc: Int,base: Int)=imm16(word(bytes,pc,base)) or (imm16(word(bytes,pc+4,base)) shl 16)
    fun table(kernel: ByteArray): Map<String,Any> {
        val expected=mapOf(0x58ae4 to 0xe30b3624L,0x58ae8 to 0xe3403005L,0x58aec to 0xe5935000L,0x58b20 to 0xe2453004L,0x58b24 to 0xe2852dffL,0x58b28 to 0xe282203cL,0x58b2c to 0xe3a01000L,0x58b30 to 0xe5a31004L,0x58b34 to 0xe1530002L,0x58b38 to 0x1afffffcL)
        expected.forEach {(pc,value)->require(word(kernel,pc,0x40000)==value) {"DAT L1 table pointer/zero loop differs"}}
        val pointer=pair(kernel,0x58ae4,0x40000);require(pointer in 0x40000..0x5b65c)
        val base=word(kernel,pointer.toInt(),0x40000)
        val stride=word(kernel,0x58b30,0x40000) and 0xfff
        val first=base-immediate(word(kernel,0x58b20,0x40000))+stride
        val last=base+immediate(word(kernel,0x58b24,0x40000))+immediate(word(kernel,0x58b28,0x40000))
        val words=(last-first)/stride+1;val size=words*stride
        require(base==0x120000L && first==base && stride==4L && last==0x123ffcL && words==4096L && size==16384L)
        return mapOf("address" to base,"size" to size,"permissions" to 3,"pointerAddress" to pointer,"firstAddress" to first,"lastAddress" to last,"stride" to stride,"words" to words,"zeroLoop" to 0x58b30,"checkedInstructions" to expected.keys.toList())
    }
    fun verify(image: FirmwareImage,regions: List<MemoryRegion>,stacks: SegmentsContracts.VerifiedPlan): SegmentsContracts.VerifiedPlan {
        require(image.metadata.id=="gfx100s-213")
        val t=table(image.decode("kernel"));val extra=MemoryRegion("kernel-l1-table",t.getValue("address") as Long,t.getValue("size") as Long,3)
        require(stacks.regions.none {r->extra.address<r.address+r.size && r.address<extra.address+extra.size})
        val all=(stacks.regions+extra).sortedBy {it.address};require(all==regions.sortedBy {it.address}) {"selected L1 table layout differs from DAT zero-loop extent"}
        return SegmentsContracts.VerifiedPlan(all,stacks.report+mapOf("profile" to PROFILE,"pageTable" to t,"regions" to all.map {mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)},
            "basis" to "DAT-verified v5 union plus only 00120000..00123FFF L1 table. RW-NX/zero initial contents remain HYP; no fault expansion/MMU translation or physical capacity claim"))
    }
    fun observe(boot: ByteArray,kernel: ByteArray): Map<String,Any> {
        val expected=mapOf(0x4e8 to 0xe30b33e4L,0x4ec to 0xe3403000L,0x4f0 to 0xe300222bL,0x4f4 to 0xe5832000L,0x4f8 to 0xe5832004L,0x4fc to 0xe5832008L,0x500 to 0xe583200cL,
            0x1044 to 0xe30b33e4L,0x1048 to 0xe3403000L,0x104c to 0xe3a02000L,0x1050 to 0xe5832008L)
        expected.forEach {(pc,value)->require(word(boot,pc,0)==value) {"DAT barrier producer/clearer differs"}}
        val more=mapOf(0x58afc to 0xe30b2628L,0x58b00 to 0xe3402005L,0x58b04 to 0xe5923000L,0x58b08 to 0xe3530002L,0x58b0c to 0x1afffffcL,0x58bd8 to 0xe30b3628L,0x58bdc to 0xe3403005L,0x58be0 to 0xe5836000L,0x58ee0 to 0xee010f10L)
        more.forEach {(pc,value)->require(word(kernel,pc,0x40000)==value) {"DAT kernel synchronization/MMU consumer differs"}}
        val barrier=pair(boot,0x4e8,0);require(pair(boot,0x1044,0)==barrier && pair(kernel,0x58bd8,0x40000)==pair(kernel,0x58afc,0x40000))
        val offsets=listOf(0x4f4,0x4f8,0x4fc,0x500).map {word(boot,it,0) and 0xfff};val clearOffset=word(boot,0x1050,0) and 0xfff
        return mapOf("matches" to true,"barrierAddress" to barrier,"barrierBusyValue" to imm16(word(boot,0x4f0,0)),"barrierOffsets" to offsets,"clearAddress" to barrier+clearOffset,"clearValue" to immediate(word(boot,0x104c,0)),
            "synchronizationAddress" to pair(kernel,0x58afc,0x40000),"synchronizationExpected" to immediate(word(kernel,0x58b08,0x40000)),"mmuEnableInstruction" to 0x58ee0,"bootInstructionAddresses" to expected.keys.toList(),"kernelInstructionAddresses" to more.keys.toList(),"pageTable" to table(kernel),
            "limitation" to "DAT producer/consumer and loop geometry only. Release thresholds/physical reset wiring and RW-NX/zero initial table contents remain HYP. Observers never write barrier/synchronization data; TTBR retention is not MMU execution")
    }
}

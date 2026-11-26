package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Selected DAT MMU control sequence and next strict A32 word-exclusive consumer. */
object MmuContracts {
    fun observe(kernel: ByteArray): Map<String,Any> {
        fun word(pc: Int)=ByteBuffer.wrap(kernel,pc-0x40000,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val expected=mapOf(0x58ec4 to 0xf57ff04fL,0x58ec8 to 0xee020f10L,0x58ecc to 0xee110f10L,0x58ed0 to 0xe3c00a01L,0x58ed4 to 0xe3c00004L,0x58ed8 to 0xe3c00002L,0x58edc to 0xe3800001L,0x58ee0 to 0xee010f10L,0x58ee4 to 0xe12fff1eL,0x57c6c to 0xe19ecf9fL,0x57c78 to 0xe18e4f91L,0x57c80 to 0x1afffff9L)
        expected.forEach {(pc,value)->require(word(pc)==value) {"DAT MMU/exclusive consumer differs"}}
        fun immediate(pc: Int)=Integer.rotateRight((word(pc) and 255).toInt(),(((word(pc) ushr 8) and 15)*2).toInt()).toLong() and 0xffffffffL
        return mapOf("matches" to true,"tableAddress" to ResetContracts.table(kernel).getValue("address"),"ttbrWrite" to 0x58ec8,"mmuEnable" to 0x58ee0,"afterEnable" to 0x58ee4,
            "sctlrClearedMask" to (immediate(0x58ed0) or immediate(0x58ed4) or immediate(0x58ed8)),"sctlrSetMask" to immediate(0x58edc),"exclusiveGuard" to 0x57c6c,
            "loadBaseRegister" to ((word(0x57c6c) ushr 16) and 15),"loadTargetRegister" to ((word(0x57c6c) ushr 12) and 15),"storeBaseRegister" to ((word(0x57c78) ushr 16) and 15),"storeStatusRegister" to ((word(0x57c78) ushr 12) and 15),"storeValueRegister" to (word(0x57c78) and 15),
            "checkedInstructions" to expected.keys.toList(),"limitation" to "Actual selected DAT control/operand fields only; native translation separately qualified. Word exclusive remains refused without a qualified logical monitor; no physical cache, task/ISR or boot/menu claim")
    }

    /** Independently selected target consumers. No CPU-model or physical core identity inference. */
    fun halfwords(kernel: ByteArray): Map<String,Any> {
        val expected=listOf(Triple(0x56804,0xe1fcaf9fL,0xe1ec3f92L),Triple(0x56934,0xe1fc9f9fL,0xe1ecaf93L),Triple(0x56b20,0xe1fc8f9fL,0xe1ec9f91L),
            Triple(0x56cf4,0xe1fcaf9fL,0xe1ec3f92L),Triple(0x56df4,0xe1fc8f9fL,0xe1ec9f93L),Triple(0x56f98,0xe1f7af9fL,0xe1e78f92L),
            Triple(0x570b4,0xe1f69f9fL,0xe1e6af93L),Triple(0x571e8,0xe1f7af9fL,0xe1e78f93L),Triple(0x57290,0xe1f47f9fL,0xe1e49f9cL),
            Triple(0x57368,0xe1f15f9fL,0xe1e16f93L),Triple(0x57448,0xe1f14f9fL,0xe1e16f93L),Triple(0x57594,0xe1fc8f9fL,0xe1ec9f93L),
            Triple(0x576f4,0xe1f1ef9fL,0xe1e17f93L),Triple(0x57810,0xe1fc8f9fL,0xe1ec9f93L))
        fun word(pc: Int)=ByteBuffer.wrap(kernel,pc-0x40000,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val rows=expected.map{(pc,load,store)->require(word(pc)==load&&word(pc+12)==store){"DAT halfword consumers differ"}
            require(load and 0x0ff00fffL==0x01f00f9fL&&store and 0x0ff00ff0L==0x01e00f90L)
            mapOf("loadPc" to pc,"storePc" to pc+12,"loadWord" to load,"storeWord" to store,"bytes" to 2,
                "baseRegister" to ((load ushr 16) and 15),"loadTargetRegister" to ((load ushr 12) and 15),
                "storeStatusRegister" to ((store ushr 12) and 15),"storeValueRegister" to (store and 15))}
        return mapOf("matches" to true,"pairCount" to rows.size,"consumers" to rows,"limitation" to "Selected DAT A32 halfword encodings and operands only; v2 logical reservation separately qualified, no R-profile or physical granule inference")
    }
}

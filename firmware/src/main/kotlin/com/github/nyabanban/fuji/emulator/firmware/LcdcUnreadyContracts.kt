package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** P268 complete control function and existing word helpers; only software transaction shape, never physical completion. */
object LcdcUnreadyContracts {
    private val control=longArrayOf(0xe92d4830L,0xe28db00cL,0xe3a04a0aL,0xe34f4ff7L,0xe3a03a09L,0xe34f3ffaL,0xe3500000L,0x11a04003L,0xe3a01000L,0xe1a00004L,0xebb7da68L,0xe1a00004L,0xebb7da78L,0xe3100001L,0x0afffffbL,0xe8bd8830L)
    private val store=longArrayOf(0xe52db004L,0xe28db000L,0xe5801000L,0xe28bd000L,0xe49db004L,0xe12fff1eL)
    private val load=longArrayOf(0xe52db004L,0xe28db000L,0xe5900000L,0xe28bd000L,0xe49db004L,0xe12fff1eL)
    fun observe(application:ByteArray,kernel:ByteArray):Map<String,Any> {
        fun check(bytes:ByteArray,base:Long,start:Long,expected:LongArray) {
            require(start>=base&&start-base+expected.size*4L<=bytes.size) {"LCD unready DAT range missing"}
            expected.forEachIndexed { i,value ->
                val actual=ByteBuffer.wrap(bytes,(start-base).toInt()+i*4,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL
                require(actual==value) {"LCD unready DAT word differs at "+(start+i*4L).toString(16)}
            }
        }
        check(application,0x1021000,0x12645c0,control)
        check(kernel,0x40000,0x5af90,store)
        check(kernel,0x40000,0x5afd8,load)
        return mapOf("evidenceClass" to "DAT / HYPOTHESIS","checkedWords" to 28,"controlFunction" to 0x12645c0L,"selectedNonzeroInstanceAddress" to 0xfffa9000L,"writePc" to 0x5af98L,"readPc" to 0x5afe0L,"testedMask" to 1,"scope" to "Complete SOURCE control function writes0 and polls bit0 until nonzero. Experimental retained0 remains unready; physical write effects/reset/readiness/IRQ and completion law UNKNOWN.")
    }
}

package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FW-015: actual dispatch literals/calls and producing instructions for optional write/SPI windows. */
object ClusterContracts {
    fun observe(loader: ByteArray,windows: List<DeviceWindow>): Map<String,Any> {
        val b=ByteBuffer.wrap(loader).order(ByteOrder.LITTLE_ENDIAN)
        fun word(at: Int)=b.getInt(at).toLong() and 0xffffffffL
        fun call(at: Int): Long {
            val w=word(at);require(w and 0xff000000L==0xeb000000L)
            val displacement=((w and 0xffffff).toInt() shl 8 shr 8)*4
            return (at+8L+displacement) and 0xffffffffL
        }
        require(word(0x398)==0xee100fb0L && word(0x39c)==0xe2000003L)
        val dispatch=List(4) { word(0x828+it*4) };require(dispatch==listOf(0x83cL,0x848L,0x854L,0x860L))
        val calls=List(4) { call(0x840+it*12) };require(calls==listOf(0x4d8L,0x86cL,0x103cL,0x10b4L))
        val worker=call(0x10f0);require(worker==0x2a38L)
        fun mov(at: Int): Long {
            val w=word(at);require(w and 0xfffff000L==0xe3a00000L)
            return Integer.rotateRight((w and 255).toInt(),((w ushr 8) and 15).toInt()*2).toLong() and 0xffffffffL
        }
        fun imm16(w: Long)=(w and 0xfff) or ((w ushr 4) and 0xf000)
        fun movt(at: Int,reg: Int): Long { val w=word(at);require(w and 0xfff0f000L==(0xe3400000L or (reg.toLong() shl 12)));return imm16(w) shl 16 }
        val boolean=mov(0x9168) or movt(0x916c,0);val serial=mov(0x5434) or movt(0x5438,0)
        require(word(0x5638)==0xe1a00600L && word(0x563c) and 0xfff0f000L==0xe3004000L)
        val baseEnable=imm16(word(0x563c)) or movt(0x5640,4);val spi1=baseEnable-4+4096
        val expected=mapOf("soc-system-control.boolean" to (boolean to 4L),"soc-system-control.serial-clock" to (serial to 4L),"soc-spi.spi1" to (spi1 to 0x28L))
        expected.forEach { (port,extent)->val w=windows.single { it.port==port };require(w.address==extent.first && w.size==extent.second) { "DAT producer differs from selected $port window" } }
        return mapOf("matches" to true,"mpidrInstructionAddress" to 0x398,"coreMask" to 3,"dispatchLiterals" to dispatch,"dispatchCalls" to calls,
            "workerCall" to 0x10f0,"workerTarget" to worker,"booleanAddress" to boolean,"serialClockAddress" to serial,"spiEnableBase0" to baseEnable,"spiStride" to 4096,"spiIndex" to 1,"spi1Base" to spi1,
            "limitation" to "Selected DAT producer/consumer facts only; four-core topology, simultaneous reset, scheduling and write-port effects remain unmeasured hypotheses")
    }
}

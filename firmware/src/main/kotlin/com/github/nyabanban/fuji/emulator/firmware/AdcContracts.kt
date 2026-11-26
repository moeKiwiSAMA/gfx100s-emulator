package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** FW-017: boot consumer fields/addresses, independently decoded output prefix and a conflicting SOURCE PC. */
object AdcContracts {
    fun observe(loader: ByteArray,windows: List<DeviceWindow>,section4: ByteArray,kernel: ByteArray): Map<String,Any> {
        val b=ByteBuffer.wrap(loader).order(ByteOrder.LITTLE_ENDIAN)
        fun word(at: Int)=b.getInt(at).toLong() and 0xffffffffL
        val expected=mapOf(0x99c4 to 0xe3510601L,0x99dc to 0xe7c11090L,0x99e0 to 0xe3c116ffL,0x99e4 to 0xe3c1180fL,
            0x9a68 to 0xe3500a01L,0x9a90 to 0xe7db1814L,0x9b68 to 0xe350000bL,0x9b88 to 0xe1a00100L,0x9b8c to 0xe2400abfL,0x9b90 to 0xe2400effL,
            0x9b98 to 0xe7eb0050L,0x9bf8 to 0xe3a01001L,0x9bfc to 0xe3a0000cL,0x9c00 to 0xe34f0ff4L,0x9c20 to 0xe3a00044L,0x9c24 to 0xe34f0ff4L,0x9c2c to 0xe7e80050L)
        expected.forEach { (at,w)->require(word(at)==w) { "DAT ADC producer/consumer differs at ${at.toString(16)}" } }
        fun immediate(w: Long)=Integer.rotateRight((w and 255).toInt(),((w ushr 8) and 15).toInt()*2).toLong() and 0xffffffffL
        fun imm16(w: Long)=(w and 0xfff) or ((w ushr 4) and 0xf000)
        val status=immediate(word(0x9bfc)) or (imm16(word(0x9c00)) shl 16)
        val base=status-(word(0x9bfc) and 255)
        val sampleBase=(-immediate(word(0x9b8c))-immediate(word(0x9b90))) and 0xffffffffL
        val stride=1L shl ((word(0x9b88) ushr 7) and 31).toInt()
        val channels=(word(0x9b68) and 255)+1
        val resultBits=((word(0x9b98) ushr 16) and 31)+1
        val maskBits=((word(0x9a90) ushr 16) and 31)-((word(0x9a90) ushr 7) and 31)+1
        val extra=immediate(word(0x9c20)) or (imm16(word(0x9c24)) shl 16)
        val size=extra-base+4
        val window=windows.single { it.port=="soc-adc" }
        require(window.address==base && window.size==size && sampleBase==base+0x10 && channels==12L && resultBits==maskBits) { "selected ADC layout differs from DAT" }
        val prefixSize=1187036 // Qualification observer extent from accepted stage-13 original output, never preloaded RAM.
        require(section4.size>=prefixSize)
        val prefix=mapOf("address" to 0x1021000,"size" to prefixSize,"sha256" to MessageDigest.getInstance("SHA-256").digest(section4.copyOfRange(0,prefixSize)).joinToString("") { "%02x".format(it) },"basis" to "Independent DAT section4 decode; bounded historical 32-page observer extent")
        val kernelAt=0x5ac50;val actual=ByteBuffer.wrap(kernel).order(ByteOrder.LITTLE_ENDIAN).getInt(kernelAt-0x40000).toLong() and 0xffffffffL
        val kb=ByteBuffer.wrap(kernel).order(ByteOrder.LITTLE_ENDIAN)
        fun kw(at: Int)=kb.getInt(at-0x40000).toLong() and 0xffffffffL
        mapOf(0x5ac60 to 0xe200301fL,0x5ac64 to 0xe1a002a0L,0x5ac68 to 0xe2800101L,0x5ac6c to 0xe2400defL,0x5ac78 to 0xe1a00100L).forEach { (at,w)->require(kw(at)==w) { "kernel comment crosscheck instruction differs" } }
        val bitmapBase=((immediate(kw(0x5ac68))-immediate(kw(0x5ac6c))) shl ((kw(0x5ac78) ushr 7) and 31).toInt()) and 0xffffffffL
        val distributor=windows.single { it.port=="soc-gic.distributor" }.address
        require(bitmapBase==distributor+0x100 && bitmapBase!=base) { "kernel reference range no longer matches selected GIC bitmap" }
        return mapOf("matches" to true,"base" to base,"size" to size,"statusAddress" to status,"statusW1c" to immediate(word(0x9bf8)),"channels" to channels,"sampleBase" to sampleBase,"sampleStride" to stride,
            "resultBits" to resultBits,"controlMaskBits" to maskBits,"controlMaskLimitExclusive" to immediate(word(0x9a68)),"dividerLimitExclusive" to immediate(word(0x99c4)),"extraAddress" to extra,"extraBits" to (((word(0x9c2c) ushr 16) and 31)+1),
            "checkedInstructionAddresses" to expected.keys.toList(),"section4Prefix" to prefix,"sourceCommentCrosscheck" to mapOf("address" to kernelAt,"expectedCompareWord" to 0xe3500c02L,"actualWord" to actual,"isolatedCompareMatches" to (actual==0xe3500c02L),"bitmapAddressForIndex0" to bitmapBase,"selectedGicDistributor" to distributor,"adcWindowMatchesBitmap" to (bitmapBase==base)),
            "limitation" to "Boot DAT consumer/address facts, not measured reset/voltage/timing/IRQ. SOURCE kernel absolute PC is crosschecked, not reused. +44 is static only on this original path")
    }
}

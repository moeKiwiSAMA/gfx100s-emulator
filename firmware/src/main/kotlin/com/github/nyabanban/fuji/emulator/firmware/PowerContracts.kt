package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FW-016: selected DAT fields and exact bounded producer/consumer instructions, not external status meanings. */
object PowerContracts {
    fun observe(loader: ByteArray,section4: ByteArray?=null): Map<String,Any> {
        val buffer=ByteBuffer.wrap(loader).order(ByteOrder.LITTLE_ENDIAN)
        fun word(at: Int)=buffer.getInt(at).toLong() and 0xffffffffL
        val record=List(7) { word(0xa460+it*4) }
        require(record==listOf(1L,0xc0005L,1L,1L,36L,0x1010000L,8L)) { "DAT serial controller record differs" }
        val expected=mapOf(0x1994 to 0xe2566001L,0x199c to 0xe1a07186L,0x19a8 to 0xe1a01739L,0x19ac to 0xe6ef1071L,
            0x1a84 to 0xe7de1c10L,0x1a88 to 0xe7df1f9fL,0x1a94 to 0xe7c71013L,0x1a98 to 0xe3a03004L,0x1a9c to 0xe30a0460L,
            0x1ab4 to 0xe0844003L,0x1abc to 0xe0844003L,0x1ac0 to 0xe1e04004L,0x1ac8 to 0xe6ef4074L,0x1acc to 0xe1530004L,
            0x1b8c to 0xe3a00000L,0x1b9c to 0xe15b32d3L,0x1ba0 to 0xe3530000L,0x1ba4 to 0xaaffffddL,
            0x1c48 to 0xe7de1c10L,0x1c4c to 0xe3811102L,0x1c50 to 0xe7d71413L,0x1c54 to 0xe0833423L,0x1c58 to 0xe0833c21L,0x1c5c to 0xe1e03003L,0x1c60 to 0xe7c71013L,0x1c64 to 0xe3a03004L)
        expected.forEach { (at,value)->require(word(at)==value) { "DAT serial producer/consumer instruction differs at ${at.toString(16)}" } }
        fun fieldWidth(at: Int): Int { val w=word(at);return ((w ushr 16) and 31).toInt()-((w ushr 7) and 31).toInt()+1 }
        val immediate=word(0x1c4c)
        val writeFlag=Integer.rotateRight((immediate and 255).toInt(),((immediate ushr 8) and 15).toInt()*2).toLong() and 0xffffffffL
        val checksumBits=fieldWidth(0x1a94)
        val prefix=section4?.let {
            // Observer extent from accepted stage-12 raw-zero output, never a guest write or full-image claim.
            val size=496324;require(it.size>=size)
            mapOf("address" to 0x1021000,"size" to size,"sha256" to java.security.MessageDigest.getInstance("SHA-256").digest(it.copyOfRange(0,size)).joinToString("") { b->"%02x".format(b) },
                "basis" to "DAT independent section4 decode prefix; bounded observer extent follows stage-12 raw-zero output")
        }
        return mapOf("matches" to true,"recordAddress" to 0xa460,"recordWords" to record,"spiIndex" to record[0],"csPort" to (record[1] ushr 16),"csPin" to (record[1] and 65535),"activeHigh" to (record[2]==1L),
            "frameBytes" to (word(0x1a98) and 255),"registerBits" to fieldWidth(0x1a84),"writeMask" to (writeFlag ushr 24),"dataBits" to fieldWidth(0x1c50),"checksumMask" to ((1 shl checksumBits)-1),
            "firstGuardRegister" to (word(0x1b8c) and 255),"guardSignedByteAddressOffset" to (((word(0x1b9c) ushr 4) and 0xf0) or (word(0x1b9c) and 15)),"guardDataBit" to (fieldWidth(0x1c50)-1),
            "checkedInstructionAddresses" to expected.keys.toList(),"section4Prefix" to (prefix ?: emptyMap<String,Any>()),"limitation" to "DAT MSB-first loop/bit fields/checksum and signed high-byte guard only; no chip identity, physical status/ready/battery meaning or measured input words")
    }
}

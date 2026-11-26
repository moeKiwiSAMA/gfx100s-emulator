package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exact selected-DAT byte-exclusive consumer; does not identify a physical lock or peripheral. */
object ByteExclusiveContracts {
    const val APP_BASE=0x1021000L
    const val LOAD_PC=0x12977a4L
    const val STORE_PC=0x12977b0L
    private val critical=linkedMapOf(
        0x1297770L to 0xe3a02000L,
        0x1297774L to 0xe3a04001L,
        0x1297778L to 0xe5d30008L,
        0x129777cL to 0xe3500000L,
        0x1297780L to 0xa000005L,
        0x1297784L to 0xe083300eL,
        0x1297788L to 0xe2822001L,
        0x129778cL to 0xe15c0002L,
        0x1297790L to 0x1afffff8L,
        0x1297794L to 0xe3e000feL,
        0x1297798L to 0xe8bd8870L,
        0x129779cL to 0xe2830008L,
        0x12977a0L to 0xf57ff05bL,
        0x12977a4L to 0xe1d05f9fL,
        0x12977a8L to 0xe3550000L,
        0x12977acL to 0x1a000002L,
        0x12977b0L to 0xe1c06f94L,
        0x12977b4L to 0xe3560000L,
        0x12977b8L to 0x1afffff9L,
        0x12977bcL to 0xf57ff05bL,
        0x12977c0L to 0x1affffefL,
        0x12977c4L to 0xe5813000L,
        0x12977c8L to 0xe3a00000L,
        0x12977ccL to 0xe8bd8870L
    )
    val wordAddresses:List<Long> get()=critical.keys.toList()
    fun observe(app:ByteArray):Map<String,Any> {
        fun word(pc:Long):Long {
            require(pc>=APP_BASE && pc%4==0L && pc-APP_BASE<=app.size-4L) { "byte-exclusive DAT consumer outside initialized section4" }
            return ByteBuffer.wrap(app,(pc-APP_BASE).toInt(),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        }
        critical.forEach { (pc,expected) -> require(word(pc)==expected) { "byte-exclusive DAT consumer differs at "+pc.toString(16) } }
        val load=word(LOAD_PC);val store=word(STORE_PC)
        require(load and 0x0ff00fffL==0x01d00f9fL && store and 0x0ff00ff0L==0x01c00f90L)
        val base=load ushr 16 and 15;require(base==store ushr 16 and 15)
        val branch=word(0x12977b8);val displacement=((branch.toInt() shl 8) shr 6).toLong()
        val retry=0x12977b8L+8+displacement;require(retry==LOAD_PC)
        val recordOffset=word(0x129779c) and 0xfff;require(recordOffset==8L)
        return linkedMapOf("matches" to true,"criticalWords" to critical.size,"wordAddresses" to wordAddresses,
            "loadPc" to LOAD_PC,"storePc" to STORE_PC,"loadWord" to load,"storeWord" to store,"bytes" to 1,
            "baseRegister" to base,"loadTargetRegister" to (load ushr 12 and 15),
            "storeStatusRegister" to (store ushr 12 and 15),"storeValueRegister" to (store and 15),
            "recordByteOffset" to recordOffset,"retryTarget" to retry,
            "basis" to "Selected section4 A32 byte-exclusive words and exact local consumer control flow; original03 observed load frontier. No physical lock identity, reservation granule or completed boot claim.")
    }
}

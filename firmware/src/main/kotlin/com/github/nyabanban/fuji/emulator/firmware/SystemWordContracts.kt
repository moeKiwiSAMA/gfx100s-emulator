package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selected GFX100S2.13 consumer, not a specification of the physical signal. */
object SystemWordContracts {
    const val PROFILE = "read-word-fff48004-experimental-v1"
    const val PORT = "soc-system-control.read-word-fff48004"
    const val ADDRESS = 0xfff48004L
    private const val APP_BASE = 0x1021000L
    private const val KERNEL_BASE = 0x40000L
    private val critical = linkedMapOf(
        0x120e0d4L to 0xe3080004L,
        0x120e0d8L to 0xe34f0ff4L,
        0x120e0dcL to 0xebb933bdL,
        0x120e0e0L to 0xe200501fL,
        0x120e0e4L to 0xe3a00e19L,
        0x120e0e8L to 0xeb023c7aL,
        0x120e0ecL to 0xe3a03000L,
        0x120e0f0L to 0xe54b3018L,
        0x120e0f4L to 0xe2400080L,
        0x120e0f8L to 0xe302358cL,
        0x120e0fcL to 0xe3403181L,
        0x120e100L to 0xe7d33005L,
        0x120e104L to 0xe0800003L,
        0x120e108L to 0xe54b0017L,
        0x120e10cL to 0xe3a0000cL,
        0x120e110L to 0xebff4c87L,
        0x120e114L to 0xe24b2018L,
        0x120e118L to 0xe1a01004L,
        0x120e11cL to 0xebff4bb2L,
        0x120e120L to 0xe3a03026L,
        0x120e124L to 0xe54b3018L,
        0x120e128L to 0xe302356cL,
        0x120e12cL to 0xe3403181L,
        0x120e130L to 0xe7d33005L,
        0x120e134L to 0xe54b3017L,
        0x120e138L to 0xe3a0000dL,
        0x120e13cL to 0xebff4c7cL,
        0x120e140L to 0xe24b2018L,
        0x120e144L to 0xe1a01004L,
        0x120e148L to 0xebff4ba7L,
        0x5afd8L to 0xe52db004L,
        0x5afdcL to 0xe28db000L,
        0x5afe0L to 0xe5900000L,
        0x5afe4L to 0xe28bd000L,
        0x5afe8L to 0xe49db004L,
        0x5afecL to 0xe12fff1eL
    )
    val wordAddresses: List<Long> get() = critical.keys.toList()
    val tableAddresses: List<Long> get() = listOf(0x181258cL, 0x181256cL)
    private val tableHashes = listOf("5a4b00b35ff6ec4c93e86da016716190c85a60dcbc83073c312e73bb1f03a7bf", "a985fcb3b49323a5f61ae867f4f358bda96d38aa52972426eed40c56e4a3f58c")
    fun observe(app: ByteArray, kernel: ByteArray): Map<String, Any> {
        fun word(pc: Long): Long {
            val bytes = if(pc >= APP_BASE) app else kernel
            val base = if(pc >= APP_BASE) APP_BASE else KERNEL_BASE
            require(pc % 4 == 0L && pc >= base && pc - base <= bytes.size - 4L) { "FFF48004 consumer outside selected initialized DAT section" }
            return ByteBuffer.wrap(bytes,(pc-base).toInt(),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        }
        critical.forEach { (pc, expected) -> require(word(pc) == expected) { "FFF48004 DAT consumer differs at " + pc.toString(16) } }
        fun movImmediate(pc: Long): Long { val w=word(pc); return ((w ushr 4) and 0xf000) or (w and 0xfff) }
        fun pair(pc: Long) = movImmediate(pc) or (movImmediate(pc+4) shl 16)
        fun branch(pc: Long): Long = pc+8+((word(pc).toInt() shl 8) shr 6).toLong()
        val address=pair(0x120e0d4); val helper=branch(0x120e0dc); val indexMask=word(0x120e0e0) and 0xfff
        require(address==ADDRESS && helper==0x5afd8L && indexMask==31L)
        require(pair(0x120e0f8)==tableAddresses[0] && pair(0x120e128)==tableAddresses[1])
        val tables=tableAddresses.mapIndexed { i, base ->
            require(base >= APP_BASE && base-APP_BASE <= app.size-32L)
            val bytes=app.copyOfRange((base-APP_BASE).toInt(),(base-APP_BASE+32).toInt())
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash==tableHashes[i]) { "FFF48004 selected-DAT index table differs" }
            mapOf("address" to base,"bytes" to 32,"elementBytes" to 1,"sha256" to hash,"values" to bytes.map{it.toInt() and 255})
        }
        require(branch(0x120e110)==0x11e1334L && branch(0x120e13c)==0x11e1334L && branch(0x120e11c)==0x11e0fecL && branch(0x120e148)==0x11e0fecL)
        return linkedMapOf("matches" to true,"criticalWords" to critical.size,"wordAddresses" to wordAddresses,
            "port" to PORT,"address" to address,"size" to 4,"helper" to helper,"loadPc" to 0x5afe0L,"returnPc" to 0x120e0e0L,
            "indexRegister" to 5,"indexMask" to indexMask,"indexCount" to 32,"tables" to tables,
            "outgoingIds" to listOf(12,13),"outgoingSecondCommandByte" to 0x26,
            "basis" to "DAT address producer, actual aligned word load, low-five-bit table consumer and downstream call words only; physical identity/value and called-device protocol UNKNOWN")
    }
}

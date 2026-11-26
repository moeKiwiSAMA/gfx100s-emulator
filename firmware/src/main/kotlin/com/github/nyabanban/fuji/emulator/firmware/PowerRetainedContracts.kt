package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GFX100S2.13 producer/formatter only; storage input is not an identified power transition. */
object PowerRetainedContracts {
    private const val APP_BASE=0x1021000L
    private val critical=linkedMapOf(
        0x120bfe8L to 0xe92d4bf0L,
        0x120bfecL to 0xe28db01cL,
        0x120bff0L to 0xe24dd008L,
        0x120bff4L to 0xe1a08000L,
        0x120bff8L to 0xe3a06000L,
        0x120bffcL to 0xe24b701cL,
        0x120c000L to 0xe5276004L,
        0x120c004L to 0xe7de6c10L,
        0x120c008L to 0xe3866102L,
        0x120c00cL to 0xe7d76411L,
        0x120c010L to 0xe0811421L,
        0x120c014L to 0xe0815c26L,
        0x120c018L to 0xe1e05005L,
        0x120c01cL to 0xe6ef5075L,
        0x120c020L to 0xe7c76015L,
        0x120c024L to 0xe3a04000L,
        0x120c028L to 0xe3e09032L,
        0x120c02cL to 0xea000009L,
        0x120c030L to 0xe1a00009L,
        0x120c034L to 0xe2844001L,
        0x120c038L to 0xe6ff4074L,
        0x120c03cL to 0xe2903000L,
        0x120c040L to 0x13a03001L,
        0x120c044L to 0xe3540002L,
        0x120c048L to 0x83a02000L,
        0x120c04cL to 0x92032001L,
        0x120c050L to 0xe3520000L,
        0x120c054L to 0xa000009L,
        0x120c058L to 0xe1a01007L,
        0x120c05cL to 0xe1a00006L,
        0x120c060L to 0xebfffc6aL,
        0x120c064L to 0xe3500000L,
        0x120c068L to 0x1afffff1L,
        0x120c06cL to 0xe55b3020L,
        0x120c070L to 0xe1530005L,
        0x120c074L to 0x1affffedL,
        0x120c078L to 0xe24bd01cL,
        0x120c07cL to 0xe8bd8bf0L,
        0x120c080L to 0xe3580000L,
        0x120c084L to 0x3a03000L,
        0x120c088L to 0xe3530000L,
        0x120c08cL to 0x13083f64L,
        0x120c090L to 0x13403187L,
        0x120c094L to 0x15932000L,
        0x120c098L to 0x12822001L,
        0x120c09cL to 0x15832000L,
        0x120c0a0L to 0xeafffff4L,
        0x120c0a4L to 0xe92d4800L,
        0x120c0a8L to 0xe28db004L,
        0x120c0acL to 0xebffffcdL,
        0x120c0b0L to 0xe8bd8800L,
        0x12ca1c8L to 0xe3a01c01L,
        0x12ca1ccL to 0xe3a00012L,
        0x12ca1d0L to 0xebfd07b3L,
        0x12ca770L to 0xe92d4800L,
        0x12ca774L to 0xe28db004L,
        0x12ca778L to 0xe3a01c01L,
        0x12ca77cL to 0xe3a00012L,
        0x12ca780L to 0xebfd0647L,
        0x12ca784L to 0xe8bd8800L
    )
    val wordAddresses:List<Long> get()=critical.keys.toList()
    fun observe(app:ByteArray):Map<String,Any> {
        fun word(pc:Long):Long { require(pc>=APP_BASE && pc%4==0L && pc-APP_BASE<=app.size-4L) { "power register18 DAT consumer outside initialized app" };return ByteBuffer.wrap(app,(pc-APP_BASE).toInt(),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL }
        critical.forEach{(pc,value)->require(word(pc)==value){"power register18 DAT consumer differs at "+pc.toString(16)}}
        fun immediate(pc:Long):Long { val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8)and 15).toInt()*2).toLong() and 0xffffffffL }
        fun branch(pc:Long)=pc+8+((word(pc).toInt() shl 8) shr 6).toLong()
        fun width(pc:Long):Int {val w=word(pc);return ((w ushr 16)and 31).toInt()-((w ushr 7)and 31).toInt()+1}
        val register=immediate(0x12ca77c);val value=immediate(0x12ca778);val wrapper=branch(0x12ca780);val formatter=branch(0x120c0ac)
        require(register==18L&&value==256L&&wrapper==0x120c0a4L&&formatter==0x120bfe8L&&branch(0x12ca1d0)==wrapper&&immediate(0x12ca1cc)==register&&immediate(0x12ca1c8)==value)
        val registerBits=width(0x120c004);val dataBits=width(0x120c00c);val checksumBits=width(0x120c020)
        val writeMask=immediate(0x120c008) ushr 24
        require(registerBits==7&&dataBits==16&&checksumBits==8&&writeMask==128L)
        val header=register or writeMask;val high=value ushr 8;val low=value and 255;val checksum=(header+high+low).inv() and 255
        val packet=listOf(header,high,low,checksum);val packed=packet.fold(0L){a,b->(a shl 8)or b}
        return linkedMapOf("matches" to true,"criticalWords" to critical.size,"wordAddresses" to wordAddresses,
            "register" to register,"value" to value,"wrapper" to wrapper,"formatter" to formatter,"transport" to branch(0x120c060),
            "registerBits" to registerBits,"dataBits" to dataBits,"checksumBits" to checksumBits,"writeMask" to writeMask,
            "requestBytes" to packet,"packedWord" to packed,"replyChecksumComparePc" to 0x120c070L,
            "candidateCallerReturnPcs" to listOf(0x12ca1d4L,0x12ca784L),
            "basis" to "DAT literal producers and checksum formatter/retry/compare; actual caller requires observed LR. Register retention/reset remain explicit HYPOTHESIS, physical rail/RTC/battery meaning and power-domain effects UNKNOWN")
    }
}

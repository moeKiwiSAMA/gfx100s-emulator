package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GFX100S2.13 selected-DAT consumers, not physical RTC reset/oscillator/settling evidence. */
object RtcContracts {
    const val FIRMWARE_ID="gfx100s-213"
    const val PROFILE="gfx100s-rtc-functional-experimental-v1"
    private const val BASE=0x1021000L
    private val critical=linkedMapOf(
        0x120d1a0L to 0xe92d4800L,
        0x120d1a4L to 0xe28db004L,
        0x120d1a8L to 0xe24dd008L,
        0x120d1acL to 0xe24b1008L,
        0x120d1b0L to 0xe3a00023L,
        0x120d1b4L to 0xebfffb29L,
        0x120d1b8L to 0xe24b1006L,
        0x120d1bcL to 0xe3a00024L,
        0x120d1c0L to 0xebfffb26L,
        0x120d1c4L to 0xe15b00b6L,
        0x120d1c8L to 0xe15b30b8L,
        0x120d1ccL to 0xe1830800L,
        0x120d1d0L to 0xe24bd004L,
        0x120d1d4L to 0xe8bd8800L,
        0x120d21cL to 0xe92d4830L,
        0x120d220L to 0xe28db00cL,
        0x120d224L to 0xe24dd008L,
        0x120d228L to 0xe1a05000L,
        0x120d22cL to 0xe24b400cL,
        0x120d230L to 0xe3a03000L,
        0x120d234L to 0xe16430b4L,
        0x120d238L to 0xe6ff1070L,
        0x120d23cL to 0xe3a0001dL,
        0x120d240L to 0xebfffb97L,
        0x120d244L to 0xe1a01825L,
        0x120d248L to 0xe3a0001eL,
        0x120d24cL to 0xebfffb94L,
        0x120d250L to 0xe1a01004L,
        0x120d254L to 0xe3a00027L,
        0x120d258L to 0xebfffb00L,
        0x120d25cL to 0xe55b300fL,
        0x120d260L to 0xe3833002L,
        0x120d264L to 0xe54b300fL,
        0x120d268L to 0xe55b3010L,
        0x120d26cL to 0xe3833002L,
        0x120d270L to 0xe54b3010L,
        0x120d274L to 0xe15b11b0L,
        0x120d278L to 0xe3a00027L,
        0x120d27cL to 0xebfffb88L,
        0x120d280L to 0xe24bd00cL,
        0x120d284L to 0xe8bd8830L,
        0x1211360L to 0xeb000441L,
        0x121246cL to 0xe92d4800L,
        0x1212470L to 0xe28db004L,
        0x1212474L to 0xe24dd008L,
        0x1212478L to 0xe24b1004L,
        0x121247cL to 0xe3a03000L,
        0x1212480L to 0xe16130b4L,
        0x1212484L to 0xe3a00027L,
        0x1212488L to 0xebffe674L,
        0x121248cL to 0xe55b3008L,
        0x1212490L to 0xe2033002L,
        0x1212494L to 0xe3530000L,
        0x1212498L to 0xe30936e0L,
        0x121249cL to 0xe3403187L,
        0x12124a0L to 0xe3a02001L,
        0x12124a4L to 0xe5c32000L,
        0x12124a8L to 0x13a00000L,
        0x12124acL to 0x3e0000aL,
        0x12124b0L to 0xe24bd004L,
        0x12124b4L to 0xe8bd8800L,
        0x12bb314L to 0xe30635ffL,
        0x12bb318L to 0xe3453feeL,
        0x12bb31cL to 0xe1510003L,
        0x12bb320L to 0x8a000041L,
        0x12bb358L to 0xe2822e7dL,
        0x12bb42cL to 0xe3003803L,
        0x12bb430L to 0xe1c030b0L,
        0x12bb434L to 0xe3a03001L,
        0x12bb438L to 0xe5c03002L,
        0x12bb43cL to 0xe5c03003L,
        0x12bb440L to 0xe3a03000L,
        0x12bb444L to 0xe5c03004L,
        0x12bb448L to 0xe5c03005L,
        0x12bb44cL to 0xe5c03006L,
        0x12bb450L to 0xe12fff1eL
    )
    val wordAddresses: List<Long> get()=critical.keys.toList()
    fun observe(app: ByteArray): Map<String,Any> {
        fun word(pc: Long): Long {require(pc>=BASE && pc%4==0L && pc-BASE<=app.size-4L) { "RTC DAT consumer outside initialized app" };return ByteBuffer.wrap(app,(pc-BASE).toInt(),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL}
        critical.forEach{(pc,value)->require(word(pc)==value) { "RTC DAT consumer differs at "+pc.toString(16) }}
        fun immediate(pc: Long)=Integer.rotateRight((word(pc) and 255).toInt(),((word(pc) ushr 8) and 15).toInt()*2).toLong() and 0xffffffffL
        fun half(pc: Long)=(word(pc) and 0xfff) or ((word(pc) ushr 4) and 0xf000)
        fun branch(pc: Long)=pc+8+((word(pc).toInt() shl 8) shr 6).toLong()
        val low=immediate(0x120d1b0);val high=immediate(0x120d1bc)
        val stageLow=immediate(0x120d23c);val stageHigh=immediate(0x120d248);val control=immediate(0x120d254)
        val valid=immediate(0x1212490);val loadMask=(immediate(0x120d260) shl 8) or immediate(0x120d26c)
        val bound=half(0x12bb314) or (half(0x12bb318) shl 16)
        val originYear=immediate(0x12bb358);val saturatedYear=half(0x12bb42c)
        require(listOf(stageLow,stageHigh,low,high,control)==listOf(29L,30L,35L,36L,39L) && valid==2L && loadMask==0x202L)
        require(branch(0x1211360)==0x121246cL && branch(0x1212488)==0x120be60L && branch(0x120d1b4)==0x120be60L && branch(0x120d1c0)==0x120be60L)
        require(listOf(0x120d240L,0x120d24cL,0x120d27cL).all{branch(it)==0x120c0a4L})
        require(bound==0x5fee65ffL && originYear==2000L && saturatedYear==2051L && branch(0x12bb320)==0x12bb42cL)
        return linkedMapOf("matches" to true,"criticalWords" to critical.size,"wordAddresses" to wordAddresses,
            "registers" to listOf(stageLow,stageHigh,low,high,control),"controlRegister" to control,"counterLow" to low,"counterHigh" to high,"stageLow" to stageLow,"stageHigh" to stageHigh,
            "validMask" to valid,"loadMask" to loadMask,"invalidResultU32" to (immediate(0x12124ac).inv() and 0xffffffffL),
            "originYear" to originYear,"maximumCalendarSeconds" to bound,"saturatedYear" to saturatedYear,"calendarOutputBytes" to 7,
            "basis" to "Selected-DAT literal/branch/bit and unsigned calendar-clamp producers. Native span tests and natural caller/transport qualify separately. Reset/timebase/running law, half-read latch, load completion time and physical RTC remain HYP/UNKNOWN")
    }
}

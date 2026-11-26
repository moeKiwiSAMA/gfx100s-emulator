package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Version-specific external register16 -> loader record -> kernel/app flags; no internal flag writes. */
object PowerBootFlagsContracts {
    private val bootWords=linkedMapOf(
        0x1af4L to 0xe3a03000L,
        0x1af8L to 0xe1a07003L,
        0x1bb8L to 0xe3a05010L,
        0x1bc4L to 0xe24b1020L,
        0x1bc8L to 0xe1a00005L,
        0x1bccL to 0xebffffa5L,
        0x1bf4L to 0xe55b3020L,
        0x1bf8L to 0xe7e02053L,
        0x1bfcL to 0xe7c07012L,
        0x1c00L to 0xe7e020d3L,
        0x1c04L to 0xe7c17092L,
        0x1c08L to 0xe7e02253L,
        0x1c0cL to 0xe7c47212L,
        0x1c10L to 0xe7e02153L,
        0x1c14L to 0xe7c27112L,
        0x1c18L to 0xe7e031d3L,
        0x1c1cL to 0xe7c37193L,
        0x1c20L to 0xe1a00007L,
        0x1c24L to 0xe24bd018L,
        0x1c28L to 0xe8bd89f0L,
        0x8a0L to 0xe300346cL,
        0x8a4L to 0xe3403001L,
        0x8a8L to 0xe6ff2070L,
        0x8acL to 0xe5832140L,
        0x8b0L to 0xe30b33d8L,
        0x8b4L to 0xe3403000L,
        0x8b8L to 0xe5933000L,
        0x8bcL to 0xe3530000L,
        0x8c0L to 0x1a000005L,
        0x8c4L to 0xe300246cL,
        0x8c8L to 0xe3402001L,
        0x8ccL to 0xe3c03010L,
        0x8d0L to 0xe1a03803L,
        0x8d4L to 0xe1a03823L,
        0x8d8L to 0xe5823140L
    )
    private val kernelWords=linkedMapOf(
        0x57950L to 0xe1a07000L,
        0x57954L to 0xe1a08001L,
        0x57a80L to 0xe1a00007L,
        0x57a84L to 0xe1a01008L,
        0x57a88L to 0xea0004c9L,
        0x58db4L to 0xe92d4800L,
        0x58db8L to 0xe28db004L,
        0x58dbcL to 0xe3043b20L,
        0x58dc0L to 0xe3403008L,
        0x58dc4L to 0xe5830000L,
        0x58dc8L to 0xe3510000L,
        0x58dccL to 0xa000003L,
        0x58dd0L to 0xe30025d4L,
        0x58dd4L to 0xe3040b24L,
        0x58dd8L to 0xe3400008L,
        0x58ddcL to 0xeb000231L,
        0x58de0L to 0xebffa461L,
        0x58de4L to 0xe8bd8800L
    )
    private val appWords=linkedMapOf(
        0x12c9d2cL to 0xe3043b24L,
        0x12c9d30L to 0xe3403008L,
        0x12c9d34L to 0xe5d34140L,
        0x12c9d38L to 0xe204301fL,
        0x12c9d3cL to 0xe3530008L,
        0x12c9d40L to 0x308381cL,
        0x12c9d44L to 0x340319fL,
        0x12c9d48L to 0x3a02001L,
        0x12c9d4cL to 0x5c32000L,
        0x12ca1d4L to 0xebfffecfL,
        0x12ca1d8L to 0xe3500000L,
        0x12ca1dcL to 0x1a000015L,
        0x12ca1e0L to 0xe308381cL,
        0x12ca1e4L to 0xe340319fL,
        0x12ca1e8L to 0xe5d33000L,
        0x12ca1ecL to 0xe3530001L,
        0x12ca1f0L to 0xa00000cL,
        0x12ca224L to 0xeafffffeL,
        0x12ca228L to 0xe308381eL,
        0x12ca22cL to 0xe340319fL,
        0x12ca230L to 0xe3a02001L,
        0x12ca234L to 0xe5c32000L
    )
    val wordAddresses:Map<String,List<Long>> get()=mapOf("boot" to bootWords.keys.toList(),"kernel" to kernelWords.keys.toList(),"section4" to appWords.keys.toList())
    fun observe(boot:ByteArray,kernel:ByteArray,app:ByteArray):Map<String,Any> {
        fun word(bytes:ByteArray,base:Long,pc:Long):Long {require(pc>=base&&pc%4==0L&&pc-base<=bytes.size-4L){"power boot flags DAT consumer outside initialized section"};return ByteBuffer.wrap(bytes,(pc-base).toInt(),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL}
        for((words,bytes,base)in listOf(Triple(bootWords,boot,0L),Triple(kernelWords,kernel,0x40000L),Triple(appWords,app,0x1021000L)))words.forEach{(pc,expected)->require(word(bytes,base,pc)==expected){"power boot flags DAT consumer differs at "+pc.toString(16)}}
        fun immediate16(w:Long)=(w and 0xfff)or((w ushr 4)and 0xf000)
        fun pair(words:Map<Long,Long>,pc:Long)=immediate16(words.getValue(pc))or(immediate16(words.getValue(pc+4))shl 16)
        var mask=0L
        val fields=listOf(0x1bf8L,0x1c00L,0x1c08L,0x1c10L,0x1c18L).map{pc->
            val extract=bootWords.getValue(pc);val insert=bootWords.getValue(pc+4)
            val sourceBit=(extract ushr 7)and 31;val width=((extract ushr 16)and 31)+1;val targetBit=(insert ushr 7)and 31
            require(width==1L&&sourceBit==targetBit&&((insert ushr 16)and 31)==targetBit)
            mask=mask or(1L shl sourceBit.toInt());mapOf("sourceBit" to sourceBit,"targetBit" to targetBit,"bits" to width)
        }
        val source=pair(bootWords,0x8a0);val target=pair(kernelWords,0x58dd4)
        val offset=bootWords.getValue(0x8ac)and 0xfff;val loadOffset=appWords.getValue(0x12c9d34)and 0xfff
        require(mask==31L&&source==0x1046cL&&target==0x84b24L&&pair(appWords,0x12c9d2c)==target&&offset==0x140L&&loadOffset==offset)
        val sourceRegister=bootWords.getValue(0x1bb8)and 255;val copyBytes=immediate16(kernelWords.getValue(0x58dd0));val selection=appWords.getValue(0x12c9d3c)and 255
        require(sourceRegister==16L&&copyBytes==0x5d4L&&selection==8L)
        return linkedMapOf("matches" to true,"criticalWords" to(bootWords.size+kernelWords.size+appWords.size),"wordAddresses" to wordAddresses,
            "sourceRegister" to sourceRegister,"sourceByte" to "low","extractedMask" to mask,"fields" to fields,
            "conditionalLoaderClearMask" to(bootWords.getValue(0x8cc)and 255),"loaderRecord" to source,"loaderFlag" to(source+offset),
            "kernelRecord" to target,"kernelFlag" to(target+loadOffset),"copyBytes" to copyBytes,"kernelInputPointerRegister" to 1,
            "appSelectionValue" to selection,"appSelectedFlagAddress" to pair(appWords,0x12c9d40),
            "basis" to "DAT register16 low-byte extraction, conditional bit4 clear, natural kernel input copy and app comparison with8. External word/profile is HYPOTHESIS; no internal flag/PC injection, hardware wake/power/USB meaning UNKNOWN")
    }
}

package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Current-DAT software-ID bytes, physical IRQ descriptor and mode1 consumer; physical channel6->63 wiring remains explicit HYP. */
object Timer6Contracts {
    const val TABLE=0x15a76f0L
    const val DESCRIPTORS=0x15a89acL
    private val application=linkedMapOf(
        0x11d8ea4L to 0xe51b0010L,0x11d8ebcL to 0xe5d42008L,0x11d8ec0L to 0xe5941004L,0x11d8ec4L to 0xebba070dL,
        0x11d8ed8L to 0x051b2010L,0x11d8eecL to 0x030736f8L,0x11d8ef0L to 0x0340315aL,0x11d8ef4L to 0x07935102L,
        0x11d8efcL to 0xe51b4010L,0x11d8f08L to 0xe30736f0L,0x11d8f0cL to 0xe340315aL,0x11d8f10L to 0xe7d30004L,0x11d8f14L to 0xeb003fb2L,
        0x11e8c44L to 0xe594100cL,0x11e8c48L to 0xe1a00005L,0x11e8c4cL to 0xebb9c81fL,
        0x11e8c50L to 0xe5941004L,0x11e8c54L to 0xe1a00005L,0x11e8c58L to 0xebb9c7c2L,
        0x11e8c5cL to 0xe5d41008L,0x11e8c60L to 0xe1a00005L,0x11e8c64L to 0xebb9c7d6L,
        0x11e8c8cL to 0xe1a00005L,0x11e8c90L to 0xebb9c7eeL,
        0x11e8decL to 0xe3500098L,0x11e8df0L to 0x930839acL,0x11e8df4L to 0x9340315aL,0x11e8df8L to 0x90830200L,0x11e8be8L to 0xe5905000L,
        0x11d8c0cL to 0xe3a00006L,0x11d8c10L to 0xebffffe8L,0x11d8c14L to 0xe8bd8800L)
    private val kernel=linkedMapOf(0x5a940L to 0x0005a998L,0x5a998L to 0xe3043240L,0x5a99cL to 0xe340300fL,0x5a9a0L to 0xe3a00c09L,0x5a9a4L to 0xe340003dL,0x5a9a8L to 0xe1510000L,0x5a9acL to 0x8afffff5L)
    val applicationAddresses get()=application.keys.toList()
    val kernelAddresses get()=kernel.keys.toList()
    private fun word(bytes: ByteArray,base: Long,address: Long): Long {require(address>=base&&address%4==0L&&address-base<=bytes.size-4L){"timer6 DAT word outside initialized section"};return ByteBuffer.wrap(bytes,(address-base).toInt(),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL}
    private fun imm(w: Long)=Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong()and 0xffffffffL
    private fun half(w: Long)=(w and 0xfff)or(w ushr 4 and 0xf000)
    private fun branch(pc: Long,w: Long)=pc+8+(((w and 0xffffff).toInt()shl 8 shr 8).toLong()shl 2)
    fun observe(image: FirmwareImage): Map<String,Any> {require(image.metadata.id=="gfx100s-213");return observe(image.decode("kernel"),image.decode("section4"),image.metadata.sections.single{it.name=="section4"}.address)+mapOf("inputSha256" to image.metadata.sha256)}
    fun observe(k: ByteArray,a: ByteArray,appBase: Long=TimerContracts.SECTION4_BASE): Map<String,Any> {
        val inherited=TimerContracts.observe(k,a,appBase)
        kernel.forEach{(pc,w)->require(word(k,0x40000,pc)==w){"timer6 DAT mode1 word differs at "+pc.toString(16)}}
        application.forEach{(pc,w)->require(word(a,appBase,pc)==w){"timer6 DAT application consumer differs at "+pc.toString(16)}}
        val table=half(word(a,appBase,0x11d8f08))or(half(word(a,appBase,0x11d8f0c))shl 16);require(table==TABLE&&table>=appBase&&table-appBase<=a.size-8L)
        val ids=(0..7).map{a[(table-appBase+it).toInt()].toInt()and 255};require(ids==(32..39).toList()){"timer6 DAT software ID byte table differs"}
        val descriptors=half(word(a,appBase,0x11e8df0))or(half(word(a,appBase,0x11e8df4))shl 16)
        val shift=(word(a,appBase,0x11e8df8)ushr 7 and 31).toInt();require(descriptors==DESCRIPTORS&&shift==4)
        val irqs=ids.map{word(a,appBase,descriptors+(it.toLong()shl shift))};require(irqs==(57L..64L).toList()){"timer6 DAT physical IRQ descriptor differs"}
        val callback=word(a,appBase,table+8+6*4);require(callback==0x11d8c04L){"timer6 DAT callback pointer differs"}
        val divisor=half(word(k,0x40000,0x5a998))or(half(word(k,0x40000,0x5a99c))shl 16)
        val maxArg=imm(word(k,0x40000,0x5a9a0))or(half(word(k,0x40000,0x5a9a4))shl 16)
        @Suppress("UNCHECKED_CAST")val conversion=inherited["countConversion"]as Map<String,Any>
        val frequency=(conversion.getValue("nominalConstant")as Number).toLong();val count=TimerContracts.ceilingCount(frequency,2500,divisor)
        require(divisor==1_000_000L&&maxArg==4_000_000L&&count==135000L)
        require(branch(0x11d8ec4,application.getValue(0x11d8ec4))==0x5ab00L&&branch(0x11d8f14,application.getValue(0x11d8f14))==0x11e8de4L)
        return mapOf("schema" to 1,"evidenceClass" to "DAT / HYPOTHESIS","softwareIdByteTable" to mapOf("address" to table,"entryBytes" to 1,"values" to ids,"channel6Id" to ids[6]),
            "interruptDescriptors" to mapOf("address" to descriptors,"entryBytes" to(1 shl shift),"lineFieldBytes" to 4,"physicalLines" to irqs,"channel6Line" to irqs[6],"channel6Descriptor" to(descriptors+(ids[6].toLong()shl shift))),
            "callbackTable" to mapOf("address" to table+8,"entryBytes" to 4,"channel6Pointer" to callback,"scope" to "separate function-pointer array, never IRQ numbers"),
            "criticalWords" to(kernel.size+application.size),"checkedKernelWords" to kernelAddresses,"checkedApplicationWords" to applicationAddresses,
            "mode1Conversion" to mapOf("mode" to 1,"divisor" to divisor,"maximumArgument" to maxArg,"nominalConstant" to frequency,"observedArgumentExample" to 2500,"computedExampleCount" to count,"computedExampleReload" to count-1,"loadPhysical" to 0xfff3c340L,"reloadPhysical" to 0xfff3c344L),
            "routeCandidate" to mapOf("channel" to 6,"line" to irqs[6],"evidenceClass" to "HYPOTHESIS","physicalWiringMeasured" to false),"limitation" to "Selected-DAT software ID byte lookup, descriptor word0, GIC helper consumers and software count arithmetic; real physical wiring, pulse/clock/reset and firmware menu remain unmeasured. Neighboring callback pointers are different data with different width")
    }
}

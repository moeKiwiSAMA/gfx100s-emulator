package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selected-DAT consumers; opaque values and two virtual delays remain explicit hypotheses. */
object BaseParameterContracts {
    const val PROFILE="configuration-base-parameters-experimental-v1"
    const val APP_BASE=0x1021000L
    const val APP_SHIFT=0x11f2f40L
    val bases=listOf(0xff701000L,0xff702000,0xff703000,0xff704000,0xff707000,0xff708000,0xff70c000,0xff70a000,0xff70b000,0xff706000,0xff709000,0xff705000,0xfffae000)
    val groupIndices=listOf(0,7,9,10,11,12,13,14,15,16,17,18,19)
    // Bounded original instruction words, captured by the independent JVM discovery runner.
    private val bootWords: Map<Long,Long> = linkedMapOf(
        0x4c6cL to 0xe92d4810L,0x4c70L to 0xe28db008L,0x4c74L to 0xe24dd00cL,0x4c78L to 0xe3a03000L,
        0x4c7cL to 0xe50b3010L,0x4c80L to 0xe28040f0L,0x4c84L to 0xe1a00004L,0x4c88L to 0xeb000192L,
        0x4c8cL to 0xe50b0010L,0x4c90L to 0xe51b3010L,0x4c94L to 0xe3130001L,0x4c98L to 0x1afffff9L,
        0x4c9cL to 0xe24bd008L,0x4ca0L to 0xe8bd8810L,0x4cf8L to 0xe92d4810L,0x4cfcL to 0xe28db008L,
        0x4d00L to 0xe24dd00cL,0x4d04L to 0xe3a03000L,0x4d08L to 0xe50b3010L,0x4d0cL to 0xe2804080L,
        0x4d10L to 0xe1a00004L,0x4d14L to 0xeb00015dL,0x4d18L to 0xe1a00004L,0x4d1cL to 0xeb00016dL,
        0x4d20L to 0xe50b0010L,0x4d24L to 0xe51b3010L,0x4d28L to 0xe3130007L,0x4d2cL to 0x1afffff9L,
        0x4d30L to 0xe24bd008L,0x4d34L to 0xe8bd8810L,0x4f24L to 0xe350000cL,0x4f28L to 0x8a000011L,
        0x4f2cL to 0xe92d4830L,0x4f30L to 0xe28db00cL,0x4f34L to 0xe1a05002L,0x4f38L to 0xe30a3d50L,
        0x4f3cL to 0xe3403000L,0x4f40L to 0xe7934100L,0x4f44L to 0xe1a00004L,0x4f48L to 0xeb0000d0L,
        0x4f4cL to 0xe1a01005L,0x4f50L to 0xe2840008L,0x4f54L to 0xeb0000cdL,0x4f58L to 0xe3a01003L,
        0x4f5cL to 0xe1a00004L,0x4f60L to 0xebffff64L,0x4f64L to 0xe1a00004L,0x4f68L to 0xebffff3fL,
        0x4f6cL to 0xe3a00000L,0x4f70L to 0xe8bd8830L,0x4f74L to 0xe3e0001dL,0x4f78L to 0xe12fff1eL,
        0x4f7cL to 0xe350000cL,0x4f80L to 0x8a000011L,0x4f84L to 0xe3520000L,0x4f88L to 0x13510000L,
        0x4f8cL to 0x0a000010L,0x4f90L to 0xe92d48f0L,0x4f94L to 0xe28db014L,0x4f98L to 0xe1a04002L,
        0x4f9cL to 0xe1a05001L,0x4fa0L to 0xe30a3d50L,0x4fa4L to 0xe3403000L,0x4fa8L to 0xe7936100L,
        0x4facL to 0xe1a00006L,0x4fb0L to 0xeb0000c8L,0x4fb4L to 0xe5850000L,0x4fb8L to 0xe2860008L,
        0x4fbcL to 0xeb0000c5L,0x4fc0L to 0xe5840000L,0x4fc4L to 0xe3a00000L,0x4fc8L to 0xe8bd88f0L,
        0x4fccL to 0xe3e0001dL,0x4fd0L to 0xe12fff1eL,0x4fd4L to 0xe3e0001dL,0x4fd8L to 0xe12fff1eL)
    val instructionAddresses get()=bootWords.keys.toList()
    private val calls=mapOf(0x4c88L to 0x52d8L,0x4d14L to 0x5290L,0x4d1cL to 0x52d8L,0x4f48L to 0x5290L,
        0x4f54L to 0x5290L,0x4f60L to 0x4cf8L,0x4f68L to 0x4c6cL,0x4fb0L to 0x52d8L,0x4fbcL to 0x52d8L)
    private val pointerWords=setOf(0x4f38L,0x4f3cL,0x4fa0L,0x4fa4L)
    private fun word(bytes: ByteArray,base: Long,address: Long): Long {
        require(address and 3L==0L&&address>=base&&address-base<=bytes.size-4L) { "base-parameter DAT word outside initialized section" }
        return ByteBuffer.wrap(bytes,(address-base).toInt(),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL
    }
    private fun pointer(bytes: ByteArray,base: Long,pc: Long): Long {
        val a=word(bytes,base,pc);val b=word(bytes,base,pc+4)
        require(a and 0xfff0f000L==0xe3003000L && b and 0xfff0f000L==0xe3403000L) { "base-parameter pointer opcode differs" }
        fun imm(w: Long)=(w and 0xfff)or((w ushr 4)and 0xf000)
        return imm(a)or(imm(b)shl 16)
    }
    private fun call(bytes: ByteArray,base: Long,pc: Long): Long {
        val w=word(bytes,base,pc);require(w and 0xff000000L==0xeb000000L)
        return pc+8+(((w and 0xffffff).toInt()shl 8 shr 8).toLong()shl 2)
    }
    private fun table(bytes: ByteArray,base: Long,address: Long): Map<String,Any> {
        val values=bases.indices.map {word(bytes,base,address+it*4)}
        require(values==bases) { "base-parameter DAT table differs" }
        val data=bytes.copyOfRange((address-base).toInt(),(address-base+52).toInt())
        return mapOf("address" to address,"values" to values,"sha256" to digest(data),"kind" to "data-table-not-execution")
    }
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun sections(boot: ByteArray,kernel: ByteArray,app: ByteArray,appBase: Long): Map<String,Any> {
        require(appBase==APP_BASE) { "base-parameter application base differs" }
        bootWords.forEach {(pc,w)->require(word(boot,0,pc)==w) { "base-parameter boot consumer differs at "+pc.toString(16) }}
        bootWords.forEach {(pc,w)->
            val current=pc+APP_SHIFT
            when {
                pc in pointerWords -> Unit
                pc in calls -> {
                    val target=calls.getValue(pc)
                    val expected=when(target){0x5290L->0x5af90L;0x52d8L->0x5afd8L;else->target+APP_SHIFT}
                    require(call(app,appBase,current)==expected) { "base-parameter application call differs" }
                }
                else->require(word(app,appBase,current)==w) { "base-parameter application consumer differs at "+current.toString(16) }
            }
        }
        require(pointer(boot,0,0x4f38)==0xad50L && pointer(boot,0,0x4fa0)==0xad50L)
        require(pointer(app,appBase,0x11f7e78)==0x15ac950L && pointer(app,appBase,0x11f7ee0)==0x15ac950L)
        require(word(kernel,0x40000,0x5af98)==0xe5801000L && word(kernel,0x40000,0x5afe0)==0xe5900000L) { "base-parameter kernel word access differs" }
        return mapOf("profile" to PROFILE,"bootTable" to table(boot,0,0xad50),"applicationTable" to table(app,appBase,0x15ac950),
            "groupIndices" to groupIndices,"parameterOffsets" to listOf(0,8),"requestOffset" to 0x80,"requestCommand" to 3,"requestPollMask" to 7,
            "statusOffset" to 0xf0,"statusPollMask" to 1,"maximumIndex" to 12,"valueTransfer" to "unmasked A32 word; physical reserved bits remain unknown",
            "criticalBootWords" to bootWords.size,"criticalApplicationWords" to bootWords.size,"criticalKernelWords" to 2,
            "bootSetter" to 0x4f24,"applicationSetter" to 0x11f7e64,"applicationRequest" to 0x11f7c38,"applicationStatus" to 0x11f7bac,
            "limitation" to "DAT software table/word transfers/two polls only. No physical timing, readiness, clock/reset/IRQ or undocumented bit semantics")
    }
    fun windows(devices: List<DeviceWindow>): List<Map<String,Any>> = bases.indices.map {i->
        val group=groupIndices[i]
        require(devices.singleOrNull {it.port=="soc-configuration.group$group"}?.let {it.id=="soc-configuration"&&it.address==bases[i]&&it.size==0x84L&&!it.optional}==true) { "base-parameter parent metadata differs" }
        val port="soc-configuration.base-status$i"
        require(devices.singleOrNull {it.port==port}?.let {it.id=="soc-configuration"&&it.address==bases[i]+0xf0&&it.size==4L&&it.optional}==true) { "base-parameter status metadata differs" }
        mapOf("index" to i,"group" to group,"base" to bases[i],"statusAddress" to bases[i]+0xf0,"statusPort" to port)
    }
    fun observe(image: FirmwareImage,devices: List<DeviceWindow>?=null): Map<String,Any> {
        val result=sections(image.decode("boot"),image.decode("kernel"),image.decode("section4"),image.metadata.sections.single {it.name=="section4"}.address)
        return if(devices==null)result else result+mapOf("windows" to windows(devices))
    }
}

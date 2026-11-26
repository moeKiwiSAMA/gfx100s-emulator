package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** JVM-DAT-019: DAT tables and concrete software accesses, with physical effects left unknown. */
object SelectionContracts {
    const val PROFILE = "selection-special-retention-experimental-v1"
    val masks = listOf(0x1000000L,0x40000,0x20000,0x10000,0xc00,0x300,0xc0,0x30,4,2,1)
    val encodings = listOf(0L,0x1000000,0,0x40000,0,0x20000,0,0x10000,0,0x400,0x800,0,0x100,0x200,0,0x40,0x80,0,0x10,0x20,0,4,0,2,0,1)
    val eligible = listOf(listOf(0,1),listOf(2,3),listOf(4,5),listOf(6,7),listOf(8,9,10),listOf(11,12,13),listOf(14,15,16),listOf(17,18,19),listOf(20,21),listOf(22,23),listOf(24,25))
    private fun word(data: ByteArray, base: Long, address: Long): Long {
        require(address and 3L == 0L && address >= base && address-base <= data.size-4L) { "selection DAT word outside initialized section" }
        return ByteBuffer.wrap(data,(address-base).toInt(),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun pair(data: ByteArray, base: Long, pc: Long, rd: Int): Long {
        val a=word(data,base,pc);val b=word(data,base,pc+4)
        require(a and 0xfff0f000L == (0xe3000000L or (rd.toLong() shl 12)) && b and 0xfff0f000L == (0xe3400000L or (rd.toLong() shl 12))) { "selection DAT MOVW/MOVT producer differs" }
        fun imm(w: Long)=(w and 0xfff) or ((w ushr 4) and 0xf000)
        return imm(a) or (imm(b) shl 16)
    }
    private fun branch(data: ByteArray, base: Long, pc: Long): Long {
        val w=word(data,base,pc);require(w and 0xff000000L == 0xeb000000L)
        return pc+8+(((w and 0xffffff).toInt() shl 8 shr 8).toLong() shl 2)
    }
    fun tables(data: ByteArray, base: Long, eligibility: Long, encoding: Long, mask: Long): Map<String,Any> {
        val actualMasks=masks.indices.map {word(data,base,mask+it*4)}
        val actualEncodings=encodings.indices.map {word(data,base,encoding+it*4)}
        require(actualMasks==masks && actualEncodings==encodings) { "selection DAT field tables differ" }
        require(eligibility>=base && eligibility-base<=data.size-286L)
        val matrix=data.copyOfRange((eligibility-base).toInt(),(eligibility-base+286).toInt())
        require(matrix.all {it==0.toByte() || it==1.toByte()}) { "selection eligibility is not Boolean" }
        val choices=(0 until 11).map {field -> (0 until 26).filter {matrix[field*26+it]==1.toByte()} }
        require(choices==eligible && choices.flatten().sorted()==encodings.indices.toList()) { "selection DAT field eligibility differs" }
        val combined=actualMasks.fold(0L){acc,m->require(acc and m==0L);acc or m}
        require(combined==0x01070ff7L && choices.indices.all {f->choices[f].all {actualEncodings[it] and actualMasks[f].inv()==0L}})
        return mapOf("eligibilityAddress" to eligibility,"encodingAddress" to encoding,"maskAddress" to mask,"masks" to actualMasks,
            "encodings" to actualEncodings,"eligible" to choices,"combinedMask" to combined,"eligibilityBytes" to matrix.size,"eligibilitySha256" to hash(matrix))
    }
    fun boot(boot: ByteArray): Map<String,Any> {
        val expected=linkedMapOf(0x4de0L to 0xe350000aL,0x4de4L to 0x93510019L,0x4df4L to 0xe3a0301aL,0x4df8L to 0xe0232093L,0x4dfcL to 0xe7d33001L,
            0x4e00L to 0xe3530000L,0x4e18L to 0xe3a06c86L,0x4e1cL to 0xe34f6ff4L,0x4e20L to 0xe1a00006L,
            0x4e30L to 0xe7931104L,0x4e34L to 0xe1c01001L,0x4e40L to 0xe7933105L,0x4e44L to 0xe1811003L,0x4e48L to 0xe1a00006L,
            0x4fdcL to 0xe3500035L,0x4ff0L to 0xe5933008L,0x5008L to 0xe593300cL,0x502cL to 0xe5930004L,0x5038L to 0xe3700001L,
            0x50e8L to 0xe5936004L,0x50f8L to 0x06ef1070L,0x50fcL to 0x13801c01L,0x5114L to 0xe3700001L,0x5298L to 0xe5801000L,0x52e0L to 0xe5900000L)
        expected.forEach {(pc,value)->require(word(boot,0,pc)==value){"selection DAT consumer differs at "+pc.toString(16)}}
        val matrix=pair(boot,0,0x4dec,2);val mask=pair(boot,0,0x4e28,3);val encoding=pair(boot,0,0x4e38,3)
        require(matrix==0xa8d0L && mask==0xadecL && encoding==0xad84L)
        val descriptor=pair(boot,0,0x4fe4,3);require(descriptor==0xa9f0L)
        require(branch(boot,0,0x4e24)==0x52d8L && branch(boot,0,0x4e4c)==0x5290L && branch(boot,0,0x5030)==0x5290L && branch(boot,0,0x50f0)==0x52d8L && branch(boot,0,0x5104)==0x5290L)
        val special=listOf(52 to listOf(0xffffffffL,0xfff4860c,2,255),53 to listOf(0xffffffffL,0xfff48604,0,16)).map {(index,expectedRow)->
            val address=descriptor+index*16;val row=(0..3).map {word(boot,0,address+it*4)};require(row==expectedRow){"special descriptor row differs"}
            mapOf("index" to index,"descriptor" to address,"sentinel" to row[0],"register" to row[1],"minimumLowByte" to row[2],"maximumLowByte" to row[3],"enableBit" to 8)
        }
        return mapOf("tables" to tables(boot,0,matrix,encoding,mask),"specialDomains" to special,"checkedWords" to expected.map{mapOf("address" to it.key,"word" to it.value)},
            "writeAccessorInstruction" to 0x5298,"readAccessorInstruction" to 0x52e0,"tableSha256" to hash(boot.copyOfRange(0xa8d0,0xae18)))
    }
    fun observe(image: FirmwareImage): Map<String,Any> = observe(image.decode("boot"),image.decode("kernel"),image.decode("section4"),image.metadata.sections.single{it.name=="section4"}.address) + mapOf("inputIdentityVerified" to true,"inputSha256" to image.metadata.sha256)
    fun observe(boot: ByteArray, kernel: ByteArray, app: ByteArray, appBase: Long): Map<String,Any> {
        val bootReport=this.boot(boot);require(appBase==TimerContracts.SECTION4_BASE)
        val mask=pair(app,appBase,0x11f7d68,3);val encoding=pair(app,appBase,0x11f7d78,3)
        require(mask==0x15ac9ecL && encoding==0x15ac984L)
        require(word(app,appBase,0x11f7d58)==0xe3a06c86L && word(app,appBase,0x11f7d5c)==0xe34f6ff4L && word(app,appBase,0x11f7d60)==0xe1a00006L)
        require(branch(app,appBase,0x11f7d64)==0x5afd8L && branch(app,appBase,0x11f7d8c)==0x5af90L)
        require(word(app,appBase,0x11f7d70)==0xe7931104L && word(app,appBase,0x11f7d74)==0xe1c01001L && word(app,appBase,0x11f7d80)==0xe7933105L && word(app,appBase,0x11f7d84)==0xe1811003L)
        require(word(kernel,0x40000,0x5afe0)==0xe5900000L)
        val appTables=tables(app,appBase,0x15ac4d0,encoding,mask)
        return mapOf("matches" to true,"inputIdentityVerified" to false,"boot" to bootReport,"applicationTables" to appTables,
            "applicationReadCallsite" to 0x11f7d64,"kernelReadInstruction" to 0x5afe0,"applicationWriteCallsite" to 0x11f7d8c,
            "windows" to listOf(mapOf("port" to "soc-selection.selection","address" to 0xfff48600L,"size" to 4),mapOf("port" to "soc-selection.special52","address" to 0xfff4860cL,"size" to 4),mapOf("port" to "soc-selection.special53","address" to 0xfff48604L,"size" to 4)),
            "limitation" to "DAT software domains and accesses. Explicit initial values and passive retention remain HYP; physical identity/wiring/reset/clock/ready/IRQ effects unknown")
    }
}

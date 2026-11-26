package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** FW-019: selected DAT SPI producer and banked stack consumers. No destination preload or fault-driven mapping. */
object KernelContracts {
    const val PROFILE="kernel-stacks-experimental-v5"
    private val modes=listOf("irq" to 0x57988,"abt" to 0x579a8,"und" to 0x579c8,"sys" to 0x579e8,"svc" to 0x57a08)
    private fun word(bytes: ByteArray,address: Int,base: Int=0x40000): Long {
        require(address>=base && address-base<=bytes.size-4) { "DAT stack pointer outside initialized kernel" }
        return ByteBuffer.wrap(bytes,address-base,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }
    data class Stack(val mode: String,val consumer: Int,val baseLiteral: Int,val sizeLiteral: Int,val sizePointer: Int,val address: Long,val perCoreSize: Long) {
        fun region()=MemoryRegion("stack-$mode",address,4*perCoreSize,3)
        fun report()=mapOf("mode" to mode,"consumer" to consumer,"baseLiteral" to baseLiteral,"sizeLiteral" to sizeLiteral,"sizePointer" to sizePointer,"address" to address,"perCoreSize" to perCoreSize,"cores" to 4,"size" to 4*perCoreSize,"permissions" to 3)
    }
    fun stacks(kernel: ByteArray): List<Stack> {
        for((pc,expected) in mapOf(0x57950 to 0xe1a07000L,0x57974 to 0xee100fb0L,0x57978 to 0xe2100003L,0x5797c to 0xe2800001L,0x57a2c to 0xebffc5b0L))
            require(word(kernel,pc)==expected) { "DAT kernel entry/core-index consumer differs" }
        val result=modes.map { (mode,pc)->
            val a=word(kernel,pc);val b=word(kernel,pc+4)
            require(a and 0xfffff000L==0xe59f1000L && b and 0xfffff000L==0xe59f2000L) { "DAT stack literal consumers differ" }
            require(listOf(8,12,16,20).map {word(kernel,pc+it)}==listOf(0xe5922000L,0xe0020092L,0xe0811002L,0xe1a0d001L)) { "DAT per-core stack arithmetic differs" }
            val baseLiteral=pc+8+(a and 0xfff).toInt();val sizeLiteral=pc+12+(b and 0xfff).toInt()
            val start=word(kernel,baseLiteral);val pointer=word(kernel,sizeLiteral);require(pointer<=Int.MAX_VALUE)
            val size=word(kernel,pointer.toInt());require(size==4096L && start%4096==0L && start in 0x88000..0x9c000)
            Stack(mode,pc,baseLiteral,sizeLiteral,pointer.toInt(),start,size)
        }
        require(result.map {it.address}==listOf(0x8c000L,0x90000L,0x94000L,0x88000L,0x9c000L)) { "DAT bounded stack geometry differs" }
        return result
    }
    fun verify(image: FirmwareImage,regions: List<MemoryRegion>,segments: SegmentsContracts.VerifiedPlan): SegmentsContracts.VerifiedPlan {
        require(image.metadata.id=="gfx100s-213" && image.metadata.sections.single {it.name=="kernel"}.address==0x40000L)
        val stacks=stacks(image.decode("kernel"));val extra=stacks.map {it.region()}
        extra.forEach {a->require(segments.regions.none {b->a.address<b.address+b.size && b.address<a.address+a.size}) { "DAT stack overlaps segment region" }}
        val all=(segments.regions+extra).sortedBy {it.address}
        all.zipWithNext().forEach { (a,b)->require(a.address+a.size<=b.address) }
        require(all==regions.sortedBy {it.address}) { "selected stack RAM differs from DAT consumers" }
        return SegmentsContracts.VerifiedPlan(all,segments.report+mapOf("profile" to PROFILE,"stacks" to stacks.map {it.report()},
            "regions" to all.map {mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)},
            "basis" to "DAT-derived v4 union plus five modes, four 4096-byte downward stacks each. Four-core allocation/zero initial bytes/RW-NX permissions are HYPOTHESIS; holes stay unmapped, no physical DRAM/reset claim"))
    }
    fun observe(boot: ByteArray,kernel: ByteArray,windows: List<DeviceWindow>,section4: ByteArray): Map<String,Any> {
        val declaredBase=word(kernel,0x40004);val declaredSize=word(kernel,0x40008);val entry=word(kernel,0x4000c)
        require(word(kernel,0x40000)==0L && word(kernel,0x4001c)==0xaaaaffffL && declaredBase==0x40000L && declaredSize==kernel.size.toLong() && entry==0x57950L) { "DAT initialized kernel header/entry differs" }
        val expected=mapOf(0x5978 to 0xe30f3f32L,0x597c to 0xe340300fL,0x5980 to 0xe0803003L,0x5984 to 0xe1a04603L,0x5998 to 0x03801040L)
        expected.forEach { (pc,value)->require(word(boot,pc,0)==value) { "DAT indexed SPI address/control producer differs" } }
        fun imm(w: Long)=(w and 0xfff) or ((w ushr 4) and 0xf000)
        val shift=((word(boot,0x5984,0) ushr 7) and 31).toInt()
        val base=(imm(word(boot,0x5978,0)) or (imm(word(boot,0x597c,0)) shl 16)) shl shift
        val selected=(0..6).map { index->windows.single {it.port=="soc-spi.spi$index"}.also {
            require(it.address==base+(index.toLong() shl shift) && it.size==0x28L && it.optional==(index!=1)) { "SPI layout/activation differs from DAT producer/SOURCE extent" }
        } }
        val chunks=(0 until section4.size step 1048576).map { at->
            val size=minOf(1048576,section4.size-at)
            mapOf("address" to 0x1021000+at,"size" to size,"sha256" to hash(section4.copyOfRange(at,at+size)))
        }
        return mapOf("matches" to true,"spiBase0" to base,"spiStride" to (1L shl shift),"spiControlBit" to (word(boot,0x5998,0) and 255),
            "spiInstructionAddresses" to expected.keys.toList(),"spiWindows" to selected.map {mapOf("port" to it.port,"address" to it.address,"size" to it.size,"optional" to it.optional)},
            "kernelEntry" to entry,"kernelHeader" to mapOf("base" to declaredBase,"initializedSize" to declaredSize,"entry" to entry),"coreIndexMask" to (word(kernel,0x57978) and 255),"coreIndexAdd" to (word(kernel,0x5797c) and 255),"stacks" to stacks(kernel).map {it.report()},
            "section4" to mapOf("address" to 0x1021000,"size" to section4.size,"sha256" to hash(section4),"chunks" to chunks),
            "limitation" to "DAT consumers/bytes only. SPI0..6 catalog extent, virtual resets/clocks, four-core stack allocation/RW-NX/zero contents are SOURCE/HYP. Hashes observe only; no kernel task/MMU/ISR/menu claim")
    }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
}

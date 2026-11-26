package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** FW-012: computes descriptor/control fields from selected bytes, not explanatory constants. */
object SoftwareContracts {
    fun observe(image: FirmwareImage, loader: ByteArray, regions: List<MemoryRegion>): Map<String, Any> {
        val code = ByteBuffer.wrap(loader).order(ByteOrder.LITTLE_ENDIAN)
        fun word(at: Int) = code.getInt(at).toLong() and 0xffffffffL
        fun immediate(w: Long) = Integer.rotateRight((w and 255).toInt(),((w ushr 8) and 15).toInt() * 2).toLong() and 0xffffffffL
        fun mov(at: Int, reg: Int): Long { val w = word(at); require(w and 0xfffff000L == (0xe3a00000L or (reg.toLong() shl 12))); return immediate(w) }
        fun orr(at: Int, dest: Int, source: Int): Long { val w = word(at); require(w and 0xfffff000L == (0xe3800000L or (source.toLong() shl 16) or (dest.toLong() shl 12))); return immediate(w) }
        fun bitfield(at: Int, dest: Int, source: Int): Pair<Int, Long> {
            val w = word(at); require(w and 0xffe00070L == 0xe7c00010L && ((w ushr 12) and 15) == dest.toLong() && w and 15 == source.toLong())
            val lsb = ((w ushr 7) and 31).toInt(); val width = ((w ushr 16) and 31).toInt() - lsb + 1; require(width in 1..32)
            return lsb to (((1L shl width) - 1) shl lsb)
        }
        var control = mov(0x3cd8,4)
        for (at in listOf(0x3d34,0x3d38)) control = control and bitfield(at,4,15).second.inv()
        control = control or orr(0x3d3c,4,4)
        val insert = bitfield(0x3d44,4,3); control = (control and insert.second.inv()) or ((mov(0x3d40,3) shl insert.first) and insert.second)
        for (at in listOf(0x3d48,0x3d4c,0x3d50)) control = control and bitfield(at,4,15).second.inv()
        control = control or orr(0x3d54,0,4)
        val cleared = bitfield(0x3e68,1,15); val startBit = orr(0x3a78,1,0)
        val request = mov(0x4098,3); val width = mov(0x40a0,3); val burst = mov(0x40a8,3)
        val sourceFixed = mov(0x4094,4) == 0L; val destinationIncrement = mov(0x40b4,3) == 1L
        require(control == 0x01100001L && cleared.first == 28 && cleared.second == 0x10000000L && startBit == cleared.second && request == 44L && width == 3L && burst == 16L && sourceFixed && destinationIncrement)
        fun imm16(w: Long) = ((w ushr 4) and 0xf000) or (w and 0xfff)
        val lo = word(0x40bc); val hi = word(0x40c0); require(lo and 0xfff0f000L == 0xe3003000L && hi and 0xfff0f000L == 0xe3403000L)
        val fixedSource = imm16(lo) or (imm16(hi) shl 16); require(fixedSource == 0xc2a0L)
        val raw = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN); image.inputPort().read(0x140274,raw); repeat(1024) { raw.put(it,raw.get(it).toInt().inv().toByte()) }
        val records = listOf(0x40,0xd0).map { at ->
            val words = List(7) { raw.getInt(at + it * 4).toLong() and 0xffffffffL }
            mapOf("id" to words[0],"initializedAddress" to words[1],"initializedSize" to words[2],"bssAddress" to words[3],"bssSize" to words[4],"flashOffset" to words[5],"flashAllocation" to words[6])
        }
        val kernel = records[0]; val section = image.metadata.sections.single { it.name == "kernel" }
        require(kernel.getValue("id") == 0L && records[1].getValue("id") == 3L && section.address == kernel.getValue("initializedAddress") && section.size.toLong() == kernel.getValue("initializedSize"))
        require(kernel.getValue("bssAddress") == section.address + section.size && kernel.getValue("bssSize") == 0x29ec0L && kernel.getValue("flashOffset") == 0x60000L && kernel.getValue("flashAllocation") == 0x20000L)
        val end = maxOf(kernel.getValue("bssAddress") + kernel.getValue("bssSize"),section.address + kernel.getValue("flashAllocation"))
        val base = section.address and -4096L; val roundedEnd = (end + 4095) and -4096L
        val region = regions.find { it.id == "kernel" }; require(region == null || (region.address == base && region.size == roundedEnd - base && region.permissions == 7)) { "kernel RAM profile differs from DAT union" }
        val allocation = ByteBuffer.allocate(kernel.getValue("flashAllocation").toInt()); image.inputPort().read(0x274 + kernel.getValue("flashOffset"),allocation)
        repeat(allocation.capacity()) { allocation.put(it,allocation.get(it).toInt().inv().toByte()) }
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val geometry = (width shl 16) or ((burst - 1) shl 8)
        return mapOf("matches" to true,"request" to request,"preparedControl" to control,"startBit" to startBit,"submittedControl" to (control or startBit),"sourceWidthBytes" to (1 shl width.toInt()),
            "burstBeats" to burst,"sourceGeometry" to (geometry or 4),"destinationGeometry" to geometry,"fixedSource" to fixedSource,"records" to records,
            "kernelMapped" to (region != null),"kernelRegionAddress" to base,"kernelRegionSize" to roundedEnd - base,"allocationSha256" to hash(allocation.array()),
            "initializedSha256" to hash(image.decode("kernel")),"bssSha256" to hash(ByteArray(kernel.getValue("bssSize").toInt())),"zeroSourceSha256" to hash(ByteArray(8)))
    }
}

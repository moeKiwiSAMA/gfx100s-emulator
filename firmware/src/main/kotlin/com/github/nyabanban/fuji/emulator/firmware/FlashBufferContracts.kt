package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Selected-DAT software block/buffer geometry; no physical flash or DRAM geometry claim. */
object FlashBufferContracts {
    const val PROFILE = "kernel-flash-buffer-experimental-v10"
    private const val APP_BASE = 0x1021000L
    private val critical = linkedMapOf(
        0x129d8d0L to 0xe3033cf0L,
        0x129d8d4L to 0xe340318cL,
        0x129d8d8L to 0xe3a00902L,
        0x129d8dcL to 0xe34004e0L,
        0x129d8e0L to 0xe5830000L,
        0x129d8e4L to 0xe3a0180fL,
        0x129d8e8L to 0xeb000489L,
        0x129eb14L to 0xe92d4ff8L,
        0x129eb18L to 0xe28db024L,
        0x129eb1cL to 0xe1a07000L,
        0x129eb20L to 0xe1a09001L,
        0x129eb24L to 0xe7f08051L,
        0x129eb28L to 0xe30736e8L,
        0x129eb2cL to 0xe3403174L,
        0x129eb30L to 0xe59350a8L,
        0x129eb34L to 0xe59360acL,
        0x129eb38L to 0xe1b0a8a1L,
        0x129eb3cL to 0xa000027L,
        0x129eb40L to 0xe15a0005L,
        0x129eb44L to 0x2a000009L,
        0x129eb48L to 0xe1a02000L,
        0x129eb4cL to 0xe1a0100aL,
        0x129eb50L to 0xe1a00006L,
        0x129eb54L to 0xebfffc48L,
        0x129eb58L to 0xe1a04000L,
        0x129dc7cL to 0xe92d4800L,
        0x129dc80L to 0xe28db004L,
        0x129dc84L to 0xe1a03002L,
        0x129dc88L to 0xe1a02881L,
        0x129dc8cL to 0xe3a01000L,
        0x129dc90L to 0xebffffdeL,
        0x129dc94L to 0xe8bd8800L,
        0x129dc10L to 0xe30bc200L,
        0x129dc14L to 0xe340c181L,
        0x129dc18L to 0xe5dcc000L,
        0x129dc1cL to 0xe35c0001L,
        0x129dc20L to 0x1a000011L,
        0x129dc24L to 0xe300c17eL,
        0x129dc28L to 0xe150000cL,
        0x129dc2cL to 0x9a000010L,
        0x129dc30L to 0xe92d48f0L,
        0x129dc34L to 0xe28db014L,
        0x129dc38L to 0xe1a05003L,
        0x129dc3cL to 0xe1a06002L,
        0x129dc40L to 0xe1a07001L,
        0x129dc44L to 0xe1a04000L,
        0x129dc48L to 0xeb006d89L,
        0x129dc4cL to 0xe0870884L,
        0x129dc50L to 0xebfd03c0L,
        0x129dc54L to 0xe3a03001L,
        0x129dc58L to 0xe1a02006L,
        0x129dc5cL to 0xe1a01000L,
        0x129dc60L to 0xe1a00005L,
        0x129dc64L to 0xebfd0368L,
        0x11deb58L to 0xe52db004L,
        0x11deb5cL to 0xe28db000L,
        0x11deb60L to 0xe280020fL,
        0x11deb64L to 0xe28bd000L,
        0x11deb68L to 0xe49db004L,
        0x11deb6cL to 0xe12fff1eL,
        0x1747790L to 66L,
        0x1747794L to 424L,
        0x174779cL to 0L,
        0x17477a0L to 490L,
        0x129d25cL to 0xe92d4830L,
        0x129d260L to 0xe28db00cL,
        0x129d264L to 0xe1a04000L,
        0x129d268L to 0xe2403872L,
        0x129d26cL to 0xe3530b86L,
        0x129d270L to 0x3a000006L,
        0x129d274L to 0xe2402876L,
        0x129d278L to 0xe30f3fbfL,
        0x129d27cL to 0xe3403002L,
        0x129d280L to 0xe1520003L,
        0x129d284L to 0x8a000002L,
        0x129d288L to 0xeb00c1b4L,
        0x129d28cL to 0xea000000L,
        0x129d290L to 0xeb00c1b2L,
        0x129d294L to 0xe1a00004L,
        0x129d298L to 0xe8bd8830L,
        0x129d648L to 0xe92d4830L,
        0x129d64cL to 0xe28db00cL,
        0x129d650L to 0xe3033cf0L,
        0x129d654L to 0xe340318cL,
        0x129d658L to 0xe5934000L,
        0x129d65cL to 0xebfffefeL,
        0x129d660L to 0xe0840000L,
        0x129d664L to 0xe8bd8830L,
        0x129d668L to 0xe92d4800L,
        0x129d66cL to 0xe28db004L,
        0x129d670L to 0xe3a0080fL,
        0x129d674L to 0xebfffff3L,
        0x129d678L to 0xe3a02863L,
        0x129d67cL to 0xe3a01000L,
        0x129d680L to 0xeb0072cbL,
        0x129d684L to 0xe3a0087bL,
        0x129d688L to 0xebffffeeL,
        0x129d68cL to 0xe3a02805L,
        0x129d690L to 0xe3a01000L,
        0x129d694L to 0xeb0072c6L,
        0x129d8ecL to 0xe3a00876L,
        0x129d8f0L to 0xebffff54L,
        0x129d8f4L to 0xe30f2fc0L,
        0x129d8f8L to 0xe3402002L,
        0x129d8fcL to 0xe3a01876L,
        0x129d900L to 0xeb0003faL,
        0x12ba1b4L to 0xe1803002L,
        0x12ba1b8L to 0xe3130003L,
        0x12ba1bcL to 0x0a000006L,
        0x12ba1c0L to 0xe3520000L,
        0x12ba1c4L to 0x012fff1eL,
        0x12ba1c8L to 0xe0802002L,
        0x12ba1ccL to 0xe4c01001L,
        0x12ba1d0L to 0xe1520000L,
        0x12ba1d4L to 0x1afffffcL,
        0x12ba1d8L to 0xe12fff1eL,
        0x12ba1dcL to 0xe92d4800L,
        0x12ba1e0L to 0xe28db004L,
        0x12ba1e4L to 0xe1a03801L,
        0x12ba1e8L to 0xe1833401L,
        0x12ba1ecL to 0xe1833001L,
        0x12ba1f0L to 0xe1a02122L,
        0x12ba1f4L to 0xe1831c01L,
        0x12ba1f8L to 0xebffffe3L,
        0x12ba1fcL to 0xe8bd8800L
    )
    val wordAddresses: List<Long> get() = critical.keys.toList()
    private fun word(app: ByteArray, pc: Long): Long {
        require(pc % 4 == 0L && pc >= APP_BASE && pc - APP_BASE <= app.size - 4L) { "flash buffer DAT word outside initialized section4" }
        return ByteBuffer.wrap(app, (pc - APP_BASE).toInt(), 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
    }
    private fun immediate(w: Long) = Integer.rotateRight((w and 255).toInt(), ((w ushr 8 and 15) * 2).toInt()).toLong() and 0xffff_ffffL
    private fun half(w: Long) = (w and 0xfff) or (w ushr 4 and 0xf000)
    private fun pair(app: ByteArray, pc: Long) = half(word(app, pc)) or (half(word(app, pc + 4)) shl 16)
    fun observe(app: ByteArray): Map<String, Any> {
        critical.forEach { (pc, expected) -> require(word(app, pc) == expected) { "flash buffer DAT consumer differs at " + pc.toString(16) } }
        val base = immediate(word(app, 0x129d8d8)) or (half(word(app, 0x129d8dc)) shl 16)
        val firstReadBytes = immediate(word(app, 0x129d8e4))
        val firstClearOffset = immediate(word(app, 0x129d670)); val firstClearBytes = immediate(word(app, 0x129d678))
        val lastClearOffset = immediate(word(app, 0x129d684)); val lastClearBytes = immediate(word(app, 0x129d68c))
        val secondaryOffset = immediate(word(app, 0x129d8ec)); val secondaryBytes = pair(app, 0x129d8f4)
        val bytes = Math.addExact(lastClearOffset, lastClearBytes)
        val shift = (word(app, 0x129dc88) ushr 7 and 31).toInt()
        val blockBytes = 1L shl shift
        val start = word(app, 0x1747794); val count = word(app, 0x1747790)
        val secondStart = word(app, 0x17477a0); val secondCount = word(app, 0x174779c)
        val flashBase = immediate(word(app, 0x11deb60))
        val whole = firstReadBytes ushr shift; val remainder = firstReadBytes and (blockBytes - 1)
        val sourceOffset = Math.multiplyExact(start, blockBytes)
        val source = Math.addExact(flashBase, sourceOffset)
        val regionBytes = Math.multiplyExact(count, blockBytes)
        val firstBytes = Math.multiplyExact(whole, blockBytes)
        val end = Math.addExact(base, bytes)
        require(base == 0x04e08000L && firstReadBytes == 0xf0000L && bytes == 0x800000L && end == 0x05608000L && shift == 17 && blockBytes == 0x20000L)
        require(firstClearOffset == firstReadBytes && firstClearBytes == 0x630000L && firstClearOffset + firstClearBytes == 0x720000L)
        require(lastClearOffset == 0x7b0000L && lastClearBytes == 0x50000L && secondaryOffset == 0x760000L && secondaryBytes == 0x2ffc0L && secondaryOffset + secondaryBytes <= bytes)
        require(start == 424L && count == 66L && secondStart == 490L && secondCount == 0L)
        require(sourceOffset == 0x3500000L && source == 0xf3500000L && flashBase == 0xf0000000L)
        require(whole == 7L && remainder == 0x10000L && firstBytes == 0xe0000L && regionBytes == 0x840000L)
        require(pair(app, 0x129d8d0) == 0x18c3cf0L && half(word(app, 0x129dc24)) == 0x17eL)
        return mapOf("profile" to PROFILE, "base" to base, "byteSize" to bytes, "endExclusive" to end,
            "firstReadBytes" to firstReadBytes, "firstClearOffset" to firstClearOffset, "firstClearBytes" to firstClearBytes,
            "lastClearOffset" to lastClearOffset, "lastClearBytes" to lastClearBytes, "secondaryReadOffset" to secondaryOffset, "secondaryReadBytes" to secondaryBytes,
            "pointerAddress" to pair(app, 0x129d8d0), "blockShift" to shift, "logicalBlockBytes" to blockBytes,
            "tableStartBlock" to start, "tableBlocks" to count, "secondTableStartBlock" to secondStart, "secondTableBlocks" to secondCount,
            "flashApertureBase" to flashBase, "flashOffset" to sourceOffset, "firstSourceAddress" to source,
            "declaredTableBytes" to regionBytes, "wholeBlocks" to whole, "firstTransferBytes" to firstBytes, "remainderBytes" to remainder,
            "firstDestinationEndExclusive" to base + firstBytes, "rejectedBlockMaximum" to half(word(app, 0x129dc24)),
            "criticalInstructions" to critical.size - 4, "criticalDataWords" to 4, "checkedAddresses" to wordAddresses,
            "basis" to "DAT base/global pointer, unchanged offset helper, first F0000 read, secondary760000/2FFC0 and explicit zero-fill ranges F0000+630000 / 7B0000+50000 ending800000; block table and add-F0000000 source separate. No file contents, physical geometry, RAM permission/reset or original-completion claim")
    }
    fun regions(inherited: List<MemoryRegion>, geometry: Map<String, Any>): List<MemoryRegion> {
        require(geometry["profile"] == PROFILE && geometry["base"] == 0x04e08000L && geometry["byteSize"] == 0x800000L)
        val added = MemoryRegion("flash-read-buffer", 0x04e08000, 0x800000, 3)
        require(inherited.none { it.id == added.id || it.address < added.address + added.size && added.address < it.address + it.size }) { "flash buffer overlaps inherited RAM" }
        return (inherited + added).sortedBy { it.address }
    }
    /** No mappings are installed here; selecting this candidate requires a separate explicit profile and native qualification. */
    fun verify(image: FirmwareImage, selected: List<MemoryRegion>, v9: SegmentsContracts.VerifiedPlan): SegmentsContracts.VerifiedPlan {
        require(image.metadata.id == "gfx100s-213" && v9.report["profile"] == L2Contracts.PROFILE &&
            v9.regions == image.metadata.memoryProfiles.getValue(L2Contracts.PROFILE).sortedBy { it.address }) { "flash buffer requires verified selected v9" }
        val geometry = observe(image.decode("section4"))
        val all = regions(v9.regions, geometry)
        require(all == selected.sortedBy { it.address }) { "selected flash buffer differs from DAT or inherited RAM permissions" }
        return SegmentsContracts.VerifiedPlan(all, v9.report + mapOf("profile" to PROFILE, "flashBuffer" to geometry,
            "regions" to all.map { mapOf("id" to it.id, "address" to it.address, "size" to it.size, "permissions" to it.permissions) },
            "basis" to "Verified v9 plus exactly DAT-produced 04E08000..05607FFF. RW/NX and zero backing remain HYP; other holes retained. This verification alone does not activate a profile or prove original DMA completion"))
    }
}

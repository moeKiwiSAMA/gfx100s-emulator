package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** DAT software buffer, not measured DRAM capacity or a confirmed peripheral. */
object AuxiliaryTaskBufferContracts {
    const val PROFILE = "kernel-auxiliary-task-buffer-experimental-v12"
    private const val APP_BASE = 0x1021000L
    private const val AUX_BASE = 0xe20400L
    private data class Span(val start: Long, val end: Long, val sha256: String)
    private val spans = listOf(
        Span(0x12b22ecL, 0x12b2360L, "806d396038ee4e77e26e3cbb581a9e4c3e99638d2077bd862bacfc14f2f93f0f"),
        Span(0x12ba18cL, 0x12ba200L, "b17fbe2eeb1525866af33c6b3955bc8d360f07784404c5dc7e4e8561b257b427"),
        Span(0xe32aa4L, 0xe32b60L, "e70f0a42e7bc5c566e00c7406b470d692ddaf43b55364d2fe1cddaa9873d309c"),
        Span(0xe32c8cL, 0xe32cb8L, "91213e3fd1aad504278931e09da866eb2e3f97e4f4aea4dd85079c410b65004d")
    )
    val wordAddresses: List<Long> get() = listOf(AUX_BASE) + spans.flatMap { (it.start until it.end step 4).toList() }
    private fun hash(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun auxiliaryImage(image: FirmwareImage): ByteArray {
        require(image.metadata.id == "gfx100s-213") { "auxiliary task buffer requires selected GFX100S 2.13" }
        val metadata = ByteArray(1024)
        image.inputPort().read(0x140274, ByteBuffer.wrap(metadata))
        metadata.indices.forEach { metadata[it] = metadata[it].toInt().inv().toByte() }
        val r = SegmentsContracts.derive(metadata).records.single { it.id == 3L }
        require(r.initializedAddress == AUX_BASE && r.initializedSize == 149888L && r.flashOffset == 0x160000L) { "auxiliary task DAT record differs" }
        val bytes = ByteArray(r.initializedSize.toInt())
        image.inputPort().read(0x274 + r.flashOffset, ByteBuffer.wrap(bytes))
        bytes.indices.forEach { bytes[it] = bytes[it].toInt().inv().toByte() }
        require(hash(bytes) == "87b32511a0da3024888e6bbc37116f22aaa3b530e36de5c13aadc95081a992ec") { "auxiliary task initialized image differs" }
        return bytes
    }
    fun observe(app: ByteArray, auxiliary: ByteArray): Map<String, Any> {
        fun slice(start: Long, end: Long): ByteArray {
            val base = if (start < APP_BASE) AUX_BASE else APP_BASE
            val image = if (base == AUX_BASE) auxiliary else app
            require(start >= base && end > start && end - base <= image.size) { "auxiliary task DAT consumer outside initialized image" }
            return image.copyOfRange((start - base).toInt(), (end - base).toInt())
        }
        fun word(pc: Long) = ByteBuffer.wrap(slice(pc, pc + 4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        fun immediate(w: Long) = Integer.rotateRight((w and 255).toInt(), ((w ushr 8 and 15) * 2).toInt()).toLong() and 0xffffffffL
        fun half(w: Long) = (w and 0xfff) or (w ushr 4 and 0xf000)
        spans.forEach { require(hash(slice(it.start, it.end)) == it.sha256) { "auxiliary task DAT consumer differs at " + it.start.toString(16) } }
        require(word(AUX_BASE) == 0xe32aa4L) { "auxiliary task entry pointer differs" }
        val base = immediate(word(0x12b2328)) or (half(word(0x12b232c)) shl 16)
        val size = immediate(word(0x12b2334))
        val argument = half(word(0x12b22ec)) or (half(word(0x12b22f0)) shl 16)
        val baseGlobal = half(word(0xe32b20)) or (half(word(0xe32b24)) shl 16)
        val sizeGlobal = half(word(0xe32b30)) or (half(word(0xe32b34)) shl 16)
        val shift = word(0x12ba1f0) ushr 7 and 31
        val step = word(0x12ba19c) and 0xfff
        require(base == 0x0589f000L && size == 0x31000L && argument == 0x19d7018L && baseGlobal == 0xe7d554L && sizeGlobal == 0xe7d560L && shift == 2L && step == 4L && (size ushr shift.toInt()) * step == size)
        return linkedMapOf("profile" to PROFILE, "base" to base, "byteSize" to size, "endExclusive" to Math.addExact(base, size), "argumentAddress" to argument,
            "baseFieldOffset" to (word(0xe32b28) and 0xfff), "sizeFieldOffset" to (word(0xe32b38) and 0xfff), "baseGlobal" to baseGlobal, "sizeGlobal" to sizeGlobal,
            "fillByte" to immediate(word(0xe32cac)), "wordCount" to (size ushr shift.toInt()), "wordBytes" to step, "countShift" to shift, "criticalWords" to wordAddresses.size,
            "basis" to "DAT producer12B2328..2338 stores base/byte length to struct; ID3 entry copies fields to globals; E32CB4 calls unmodified fill. Exact range only; RW/NX/zero backing HYP, physical purpose/capacity UNKNOWN.")
    }
    fun regions(inherited: List<MemoryRegion>, geometry: Map<String, Any>): List<MemoryRegion> {
        require(geometry["profile"] == PROFILE && geometry["base"] == 0x0589f000L && geometry["byteSize"] == 0x31000L)
        val added = MemoryRegion("auxiliary-task-buffer", 0x0589f000, 0x31000, 3)
        require(inherited.none { it.id == added.id || it.address < added.address + added.size && added.address < it.address + it.size }) { "auxiliary task buffer overlaps inherited RAM" }
        return (inherited + added).sortedBy { it.address }
    }
    fun verify(image: FirmwareImage, selected: List<MemoryRegion>, v11: SegmentsContracts.VerifiedPlan): SegmentsContracts.VerifiedPlan {
        require(image.metadata.id == "gfx100s-213" && v11.report["profile"] == AuxBufferContracts.PROFILE && v11.regions == image.metadata.memoryProfiles.getValue(AuxBufferContracts.PROFILE).sortedBy { it.address }) { "auxiliary task buffer requires verified selected v11" }
        val geometry = observe(image.decode("section4"), auxiliaryImage(image))
        val plan = regions(v11.regions, geometry)
        require(selected.sortedBy { it.address } == plan) { "selected auxiliary task buffer differs from DAT or inherited RAM permissions" }
        return SegmentsContracts.VerifiedPlan(plan, v11.report + mapOf("profile" to PROFILE, "auxiliaryTaskBuffer" to geometry,
            "regions" to plan.map { mapOf("id" to it.id, "address" to it.address, "size" to it.size, "permissions" to it.permissions) },
            "basis" to "Verified v11 plus exact DAT software buffer0589F000..058CFFFF. Other holes unchanged; no peripheral response, guest-state patch or auto-mapping."))
    }
}

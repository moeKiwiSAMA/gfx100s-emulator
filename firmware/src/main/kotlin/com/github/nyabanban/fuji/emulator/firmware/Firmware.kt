package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.sha256
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.logging.Logger

data class Section(val name: String, val address: Long, val size: Int, val offset: Int, val encoding: String, val sha256: String)
data class DeviceWindow(val id: String, val address: Long, val size: Long, val port: String, val optional: Boolean = false)
data class BootRomSpec(val address: Long, val size: Long, val sha256: String)
class FirmwareMetadata(private val data: Map<String,Any>) {
    companion object {
        const val BOOT_ROM_EXPERIMENT = "boot-rom-experiment"
        const val BOOT_ROM_ADDRESS = 0xfff00000L
        const val BOOT_ROM_SIZE = 0x8000L
        const val BOOT_ROM_SHA256 = "2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4"
    }
    constructor(path: Path): this(Formats.read(path))
    val id = data.getValue("id") as String
    val size = number(data.getValue("size"))
    val sha256 = data.getValue("sha256") as String
    val entry = number(data.getValue("entry"))
    val cpsr = number(data.getValue("cpsr")).toInt()
    val loaderMemoryBytes = number(data.getValue("loaderMemoryBytes"))
    val bootMode = data.getValue("bootMode") as String
    val bootRom: BootRomSpec? = if ("bootRom" !in data) null else {
        val row = Formats.map(data.getValue("bootRom"))
        require(row.keys == setOf("address", "size", "sha256")) { "invalid bootRom metadata" }
        BootRomSpec(number(row.getValue("address")), number(row.getValue("size")), row.getValue("sha256") as String)
    }
    val memoryProfiles: Map<String, List<com.github.nyabanban.fuji.emulator.api.MemoryRegion>> = if ("memoryProfiles" !in data) mapOf(
        "loader-low128-experimental-v1" to listOf(com.github.nyabanban.fuji.emulator.api.MemoryRegion("loader",0,loaderMemoryBytes,7))) else
        Formats.map(data.getValue("memoryProfiles")).mapValues { (_, raw) -> (raw as List<*>).map {
            val r = Formats.map(it)
            require(r.keys == setOf("id","address","size","permissions"))
            com.github.nyabanban.fuji.emulator.api.MemoryRegion(r.getValue("id") as String,number(r.getValue("address")),number(r.getValue("size")),Math.toIntExact(number(r.getValue("permissions"))))
        } }
    val sections = (data.getValue("sections") as List<*>).map { raw ->
        val row = Formats.map(raw)
        Section(row.getValue("name") as String, number(row.getValue("address")), Math.toIntExact(number(row.getValue("size"))), Math.toIntExact(number(row.getValue("offset"))), row.getValue("encoding") as String, row.getValue("sha256") as String)
    }
    val devices = (data.getValue("devices") as List<*>).map { raw ->
        val row = Formats.map(raw)
        require(row.keys - "optional" == setOf("id","address","size","port") && ("optional" !in row || row["optional"] is Boolean)) { "invalid device window" }
        DeviceWindow(row.getValue("id") as String, number(row.getValue("address")), number(row.getValue("size")), row.getValue("port") as String, row["optional"] as? Boolean ?: false)
    }
    /** Only explicitly claimed optional windows activate; a missing claimed/required port still refuses. */
    fun activatedDevices(selected: Set<String>, claims: Map<String,List<String>>): List<DeviceWindow> {
        require(claims.keys.all {it in selected})
        val enabled=HashSet<String>()
        claims.forEach { (id,names)->names.forEach { name->
            require(name.isNotBlank() && enabled.add(name)) { "duplicate additional MMIO claim" }
            require(devices.singleOrNull {it.id==id && it.port==name}?.optional==true) { "additional MMIO port absent or not opt-in in metadata: $name" }
        } }
        return devices.filter {it.id in selected && (!it.optional || it.port in enabled)}
    }
    init {
        require(data["schema"] == 1 && data.keys - setOf("memoryProfiles", "bootRom") == setOf("schema", "id", "version", "size", "sha256", "entry", "cpsr", "loaderMemoryBytes", "bootMode", "sections", "devices", "evidence")) { "invalid firmware metadata" }
        require(size in 1..128 * 1024 * 1024 && sha256.matches(Regex("[a-f0-9]{64}")))
        if (bootMode == BOOT_ROM_EXPERIMENT) {
            val rom = requireNotNull(bootRom) { "boot-rom-experiment requires bootRom metadata" }
            require(rom.address == BOOT_ROM_ADDRESS && rom.size == BOOT_ROM_SIZE && rom.sha256 == BOOT_ROM_SHA256) { "invalid bootRom specification" }
            require(entry in listOf(0L, BOOT_ROM_ADDRESS) && cpsr == 0xd3 && loaderMemoryBytes in listOf(0L, 0x20000L)) { "invalid boot-rom-experiment entry metadata" }
            require("memoryProfiles" in data && memoryProfiles.isNotEmpty()) { "boot-rom-experiment requires explicit memoryProfiles" }
        } else {
            require(bootRom == null) { "bootRom metadata requires boot-rom-experiment bootMode" }
            require(entry == 0L && cpsr == 0xd3 && loaderMemoryBytes == 0x20000L) { "only qualified RAM-loader entry supported" }
        }
        require(sections.map { it.name }.distinct().size == sections.size)
        sections.forEach { require(it.size in 1..64 * 1024 * 1024 && it.offset >= 0 && it.address >= 0 && it.address <= 0x1_0000_0000L - it.size) }
    }
}
fun number(value: Any): Long = when (value) {
    is String -> if (value.startsWith("0x")) value.substring(2).toLong(16) else value.toLong()
    is Number -> { require(value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()); value.toLong() }
    else -> error("invalid number")
}
/** Plain non-XOR-inverted Boot ROM loader; never alters FirmwareImage.decode() DAT inversion. */
object BootRomImage {
    fun load(path: Path, metadata: FirmwareMetadata): ByteArray {
        require(metadata.bootMode == FirmwareMetadata.BOOT_ROM_EXPERIMENT) { "BootRomImage requires boot-rom-experiment metadata" }
        val spec = requireNotNull(metadata.bootRom) { "bootRom specification missing" }
        require(Files.isRegularFile(path)) { "bootRom file missing" }
        require(Files.size(path) == spec.size) { "bootRom size differs" }
        val bytes = Files.newInputStream(path).use { it.readNBytes(Math.toIntExact(spec.size + 1)) }
        require(bytes.size.toLong() == spec.size) { "bootRom size differs" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest == spec.sha256) { "bootRom SHA-256 differs" }
        return bytes
    }
}
/** Port of FW-001/002/006; every decoded section checked against independent catalog hashes unless opt-in custom mode is enabled. */
class FirmwareImage @JvmOverloads constructor(
    val metadata: FirmwareMetadata,
    path: Path,
    val allowCustomFirmware: Boolean = false,
) {
    companion object {
        private val LOG = Logger.getLogger(FirmwareImage::class.java.name)
        const val DAT_HEADER_SIZE = 0x274
        const val DAT_HEADER_VERSION_OFFSET = 0x000
        const val DAT_HEADER_VERSION_EXPECTED = 6
        const val DAT_SEG0_CHECKSUM_OFFSET = 0x20c
        const val DAT_SEG0_LENGTH_OFFSET = 0x24c
        const val MAX_DAT_BYTES = 128L * 1024L * 1024L
        const val MAX_SEG0_BYTES = 0x0800_0000L

        /** Computes FUJIFILM type-6 DAT seg0 one's-complement checksum: ~sum((b ^ 0xFF) & 0xFF) mod 2^32. */
        fun computeSeg0Checksum(source: ByteArray, seg0Length: Int): Long {
            require(seg0Length > 0 && DAT_HEADER_SIZE + seg0Length <= source.size) { "invalid seg0 range for checksum" }
            var sum = 0L
            val end = DAT_HEADER_SIZE + seg0Length
            for (i in DAT_HEADER_SIZE until end) {
                sum = (sum + ((source[i].toInt() xor 0xff) and 0xff)) and 0xffff_ffffL
            }
            return sum.inv() and 0xffff_ffffL
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val source: ByteArray
    private val decodedCache = HashMap<String, ByteArray>()
    val actualSha256: String
    val customFirmwareLoaded: Boolean
    val seg0Length: Long
    val seg0Checksum: Long

    fun inputPort(): com.github.nyabanban.fuji.emulator.api.UpdateDataPort = object : com.github.nyabanban.fuji.emulator.api.UpdateDataPort {
        override val size get() = source.size.toLong()
        override val sha256 get() = actualSha256
        override fun read(offset: Long, destination: ByteBuffer) {
            require(offset >= 0 && offset <= size && destination.remaining() <= size - offset)
            destination.put(source, offset.toInt(), destination.remaining())
        }
    }

    init {
        if (!allowCustomFirmware) {
            require(Files.size(path) == metadata.size) { "firmware size differs" }
            source = Files.newInputStream(path).use { it.readNBytes(Math.toIntExact(metadata.size + 1)) }
            actualSha256 = sha256Hex(source)
            require(source.size.toLong() == metadata.size && actualSha256 == metadata.sha256) { "firmware SHA-256 differs" }
            customFirmwareLoaded = false
            if (source.size >= DAT_HEADER_SIZE) {
                val hdr = ByteBuffer.wrap(source, 0, DAT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                seg0Length = hdr.getInt(DAT_SEG0_LENGTH_OFFSET).toLong() and 0xffff_ffffL
                seg0Checksum = hdr.getInt(DAT_SEG0_CHECKSUM_OFFSET).toLong() and 0xffff_ffffL
            } else {
                seg0Length = 0L
                seg0Checksum = 0L
            }
        } else {
            require(Files.isRegularFile(path)) { "custom firmware DAT file missing" }
            val fileSize = Files.size(path)
            require(fileSize in DAT_HEADER_SIZE.toLong()..MAX_DAT_BYTES) {
                "custom firmware DAT size out of bounds: $fileSize"
            }
            source = Files.newInputStream(path).use { it.readNBytes(Math.toIntExact(fileSize + 1)) }
            require(source.size.toLong() == fileSize) { "custom firmware DAT changed during read" }
            val hdr = ByteBuffer.wrap(source, 0, DAT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val version = hdr.getInt(DAT_HEADER_VERSION_OFFSET)
            require(version == DAT_HEADER_VERSION_EXPECTED) {
                "custom firmware DAT header_version differs: $version != $DAT_HEADER_VERSION_EXPECTED"
            }
            val declaredSeg0 = hdr.getInt(DAT_SEG0_LENGTH_OFFSET).toLong() and 0xffff_ffffL
            require(declaredSeg0 in 1L..MAX_SEG0_BYTES && declaredSeg0 <= source.size.toLong() - DAT_HEADER_SIZE) {
                "custom firmware DAT seg0 length out of bounds: 0x${declaredSeg0.toString(16)}"
            }
            val storedChecksum = hdr.getInt(DAT_SEG0_CHECKSUM_OFFSET).toLong() and 0xffff_ffffL
            val computedChecksum = computeSeg0Checksum(source, declaredSeg0.toInt())
            require(storedChecksum == computedChecksum) {
                "custom firmware DAT seg0 checksum differs: stored=0x${storedChecksum.toString(16)}, computed=0x${computedChecksum.toString(16)}"
            }
            seg0Length = declaredSeg0
            seg0Checksum = storedChecksum
            actualSha256 = sha256Hex(source)
            customFirmwareLoaded = actualSha256 != metadata.sha256
            LOG.info(
                "[CUSTOM-FW] Loaded DAT file=${path.fileName} bytes=${source.size} " +
                    "actualSha256=$actualSha256 stockSha256=${metadata.sha256} " +
                    "modified=$customFirmwareLoaded seg0Len=0x${seg0Length.toString(16)} " +
                    "seg0Checksum=0x${seg0Checksum.toString(16)}",
            )
        }
    }

    fun decode(name: String): ByteArray = decodeReadOnly(name).copyOf()

    /** Returns the cached immutable section bytes without copying; callers must treat the array as read-only. */
    fun decodeReadOnly(name: String): ByteArray {
        val cached = synchronized(decodedCache) { decodedCache[name] }
        if (cached != null) return cached
        val section = metadata.sections.single { it.name == name }
        fun extent(size: Int): ByteArray {
            require(size >= 0 && section.offset <= source.size - size) { "DAT extent out of bounds" }
            return ByteArray(size) { source[section.offset + it].toInt().inv().toByte() }
        }
        val result = when (section.encoding) {
            "raw" -> extent(section.size)
            "method4" -> { val header = ByteBuffer.wrap(extent(20)).order(ByteOrder.LITTLE_ENDIAN); val packed = header.getInt(4); Method4.decode(extent(packed)) }
            else -> error("unsupported section encoding")
        }
        if (!allowCustomFirmware) {
            require(result.size == section.size && sha256Hex(result) == section.sha256) { "decoded section differs: $name" }
        } else {
            require(result.size in 1..(64 * 1024 * 1024)) { "custom decoded section size out of bounds: $name" }
            if (section.encoding == "raw") {
                require(result.size == section.size) { "custom raw section size differs: $name" }
            }
        }
        synchronized(decodedCache) { decodedCache[name] = result }
        return result
    }

    fun inspect(): Map<String, Any> {
        val base = linkedMapOf<String, Any>(
            "firmware" to metadata.id,
            "sha256" to actualSha256,
            "bootRomExecuted" to false,
            "completeBoot" to false,
            "sections" to metadata.sections.map { section ->
                val bytes = decode(section.name)
                mapOf(
                    "name" to section.name,
                    "address" to section.address,
                    "bytes" to bytes.size,
                    "sha256" to if (allowCustomFirmware) sha256Hex(bytes) else section.sha256,
                )
            },
        )
        if (allowCustomFirmware) {
            base["customFirmware"] = true
            base["stockSha256"] = metadata.sha256
            base["modified"] = customFirmwareLoaded
            base["seg0Length"] = seg0Length
            base["seg0Checksum"] = seg0Checksum
        }
        return base
    }
}
object Method4 {
    fun decode(source: ByteArray): ByteArray {
        require(source.size >= 20)
        val header = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        val unpacked = header.int; val packed = header.int; val pages = header.int; val pageSize = header.int; val method = header.int
        require(pageSize == 0x4000 && method == 4 && packed in 20..source.size && pages.toLong() == (packed.toLong() + 0x3fff) / 0x4000)
        require(unpacked >= 0 && unpacked <= 64 * 1024 * 1024 && unpacked.toLong() <= (packed - 20).toLong() * 15)
        val output = ByteArray(unpacked); var position = 20; var count = 0
        while (position < packed) {
            val command = source[position++].toInt() and 255
            if (command < 128) {
                require(command > 0 && command <= packed - position && command <= unpacked - count)
                source.copyInto(output, count, position, position + command); position += command; count += command
            } else {
                require(position < packed)
                val second = source[position++].toInt() and 255
                val distance = ((command and 127) shl 4) or (second ushr 4); val length = second and 15
                require(distance in 1..count && length > 0 && length <= unpacked - count)
                repeat(length) { output[count] = output[count - distance]; count++ }
            }
        }
        require(count == unpacked) { "decoded length differs" }; return output
    }
}

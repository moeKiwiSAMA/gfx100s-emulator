package com.github.nyabanban.fuji.emulator.api

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Enumerated host webcam capture device usable by `ffmpeg-pipe` (`webcam:<id>`).
 */
data class WebcamDevice(
    val id: String,
    val displayName: String,
) {
    val sourceRef: String get() = "webcam:$id"

    override fun toString(): String = displayName
}

/**
 * Cross-platform host webcam enumeration for Linux (V4L2 sysfs), Windows (FFmpeg DirectShow with
 * PowerShell CIM fallback), and macOS (FFmpeg AVFoundation).
 *
 * All platform probes are timeout-bounded and failure-tolerant so UI callers never hang or crash.
 */
object WebcamDeviceDiscovery {
    private const val PROBE_TIMEOUT_MS = 3_000L

    data class LinuxVideoSysfsEntry(
        val devName: String,
        val cardName: String,
        val index: Int?,
        val deviceExists: Boolean = true,
    )

    fun discover(
        osName: String = System.getProperty("os.name").orEmpty(),
        ffmpegExecutable: String = resolveFfmpegExecutable(),
    ): List<WebcamDevice> {
        val lowerOs = osName.lowercase()
        return when {
            "win" in lowerOs -> discoverWindows(ffmpegExecutable)
            "mac" in lowerOs || "darwin" in lowerOs -> discoverMac(ffmpegExecutable)
            else -> discoverLinux()
        }
    }

    fun discoverDefaultDeviceId(
        osName: String = System.getProperty("os.name").orEmpty(),
        ffmpegExecutable: String = resolveFfmpegExecutable(),
    ): String? = runCatching { discover(osName, ffmpegExecutable).firstOrNull()?.id }.getOrNull()

    fun resolveFfmpegExecutable(): String =
        System.getProperty("fuji.ffmpeg.bin")?.takeIf { it.isNotBlank() }
            ?: System.getenv("FUJI_FFMPEG_BIN")?.takeIf { it.isNotBlank() }
            ?: "ffmpeg"

    private fun discoverLinux(sysfsRoot: Path = Path.of("/sys/class/video4linux")): List<WebcamDevice> {
        if (!Files.isDirectory(sysfsRoot)) return emptyList()
        val entries = mutableListOf<LinuxVideoSysfsEntry>()
        runCatching {
            Files.list(sysfsRoot).use { stream ->
                stream.forEach { dir ->
                    val name = dir.fileName?.toString().orEmpty()
                    if (!name.startsWith("video") || name.removePrefix("video").toIntOrNull() == null) return@forEach
                    val cardName = runCatching { Files.readString(dir.resolve("name"), StandardCharsets.UTF_8).trim() }
                        .getOrDefault("")
                    val index = runCatching { Files.readString(dir.resolve("index"), StandardCharsets.UTF_8).trim().toIntOrNull() }
                        .getOrNull()
                    val devPath = Path.of("/dev/$name")
                    entries += LinuxVideoSysfsEntry(
                        devName = name,
                        cardName = cardName,
                        index = index,
                        deviceExists = Files.exists(devPath),
                    )
                }
            }
        }
        return parseLinuxSysfsEntries(entries)
    }

    internal fun parseLinuxSysfsEntries(entries: List<LinuxVideoSysfsEntry>): List<WebcamDevice> {
        val existing = entries
            .filter { it.deviceExists && it.devName.startsWith("video") }
            .sortedBy { it.devName.removePrefix("video").toIntOrNull() ?: Int.MAX_VALUE }
        if (existing.isEmpty()) return emptyList()
        // UVC cameras expose index=0 for video capture and index=1 for metadata; prefer index=0 (or unknown).
        val captureNodes = existing.filter { it.index == null || it.index == 0 }
        val chosen = captureNodes.ifEmpty { existing }
        return chosen.map { entry ->
            val path = "/dev/${entry.devName}"
            val label = if (entry.cardName.isNotBlank()) "${entry.cardName} ($path)" else path
            WebcamDevice(id = path, displayName = label)
        }
    }

    private fun discoverWindows(ffmpegExecutable: String): List<WebcamDevice> {
        val dshowOut = runProbeCommand(
            listOf(ffmpegExecutable, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"),
        )
        val parsed = parseWindowsDshowDevices(dshowOut)
        if (parsed.isNotEmpty()) return parsed
        val psOut = runProbeCommand(
            listOf(
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Get-CimInstance Win32_PnPEntity -Filter \"PNPClass='Camera' OR PNPClass='Image'\" | Select-Object -ExpandProperty Name",
            ),
        )
        return parseWindowsPowershellCameraNames(psOut)
    }

    private data class RawDshowEntry(
        val friendlyName: String,
        val alternativeName: String? = null,
    )

    internal fun parseWindowsDshowDevices(output: String): List<WebcamDevice> {
        if (output.isBlank()) return emptyList()
        val raw = mutableListOf<RawDshowEntry>()
        val quotedRegex = Regex("""\[dshow\s+@[^\]]+]\s+"([^"]+)"(?:\s+\(([^)]+)\))?""")
        val altRegex = Regex("""\[dshow\s+@[^\]]+]\s+Alternative name\s+"([^"]+)"""")

        var inLegacyVideoSection = false
        var lastEntryIsVideo = false

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.contains("DirectShow video devices", ignoreCase = true)) {
                inLegacyVideoSection = true
                lastEntryIsVideo = false
                continue
            }
            if (line.contains("DirectShow audio devices", ignoreCase = true)) {
                inLegacyVideoSection = false
                lastEntryIsVideo = false
                continue
            }
            val altMatch = altRegex.find(line)
            if (altMatch != null) {
                if (lastEntryIsVideo && raw.isNotEmpty()) {
                    val prev = raw.removeAt(raw.lastIndex)
                    raw += prev.copy(alternativeName = altMatch.groupValues[1].trim())
                }
                continue
            }
            val match = quotedRegex.find(line) ?: continue
            val name = match.groupValues[1].trim()
            val kind = match.groupValues[2].trim().lowercase()
            val isVideo = when {
                kind == "video" -> true
                kind.isNotEmpty() -> false // e.g. (audio) or (none)
                else -> inLegacyVideoSection
            }
            lastEntryIsVideo = isVideo
            if (isVideo && name.isNotEmpty()) {
                raw += RawDshowEntry(friendlyName = name)
            }
        }

        val nameCounts = raw.groupingBy { it.friendlyName }.eachCount()
        val seenPerName = mutableMapOf<String, Int>()
        val result = mutableListOf<WebcamDevice>()
        val usedIds = HashSet<String>()

        for (entry in raw) {
            val dupCount = nameCounts[entry.friendlyName] ?: 1
            val idx = (seenPerName[entry.friendlyName] ?: 0) + 1
            seenPerName[entry.friendlyName] = idx
            val id = if (dupCount > 1 && !entry.alternativeName.isNullOrBlank()) {
                entry.alternativeName
            } else {
                entry.friendlyName
            }
            if (!usedIds.add(id)) continue
            val label = if (dupCount > 1) "${entry.friendlyName} #$idx" else entry.friendlyName
            result += WebcamDevice(id = id, displayName = label)
        }
        return result
    }

    internal fun parseWindowsPowershellCameraNames(output: String): List<WebcamDevice> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Get-CimInstance", ignoreCase = true) }
            .distinct()
            .map { WebcamDevice(id = it, displayName = it) }
            .toList()

    private fun discoverMac(ffmpegExecutable: String): List<WebcamDevice> {
        val out = runProbeCommand(
            listOf(ffmpegExecutable, "-hide_banner", "-list_devices", "true", "-f", "avfoundation", "-i", ""),
        )
        return parseMacAvFoundationDevices(out)
    }

    internal fun parseMacAvFoundationDevices(output: String): List<WebcamDevice> {
        if (output.isBlank()) return emptyList()
        val result = mutableListOf<WebcamDevice>()
        val itemRegex = Regex("""\[AVFoundation[^\]]*]\s+\[(\d+)]\s+(.+)$""")
        var inVideoSection = false
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.contains("AVFoundation video devices:", ignoreCase = true)) {
                inVideoSection = true
                continue
            }
            if (line.contains("AVFoundation audio devices:", ignoreCase = true)) {
                inVideoSection = false
                continue
            }
            if (!inVideoSection) continue
            val m = itemRegex.find(line) ?: continue
            val idx = m.groupValues[1].trim()
            val name = m.groupValues[2].trim()
            if (idx.isNotEmpty() && name.isNotEmpty() && !name.startsWith("Capture screen", ignoreCase = true)) {
                result += WebcamDevice(id = idx, displayName = "[$idx] $name")
            }
        }
        return result
    }

    private fun runProbeCommand(command: List<String>): String {
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return ""
        }
        return try {
            runCatching { process.outputStream.close() }
            val reader = StringBuilder()
            val t = Thread.ofVirtual().name("webcam-probe-read").start {
                runCatching {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { br ->
                        val buf = CharArray(2048)
                        var total = 0
                        while (total < 65_536) {
                            val n = br.read(buf)
                            if (n < 0) break
                            reader.append(buf, 0, n)
                            total += n
                        }
                    }
                }
            }
            val finished = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            t.join(500L)
            reader.toString()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

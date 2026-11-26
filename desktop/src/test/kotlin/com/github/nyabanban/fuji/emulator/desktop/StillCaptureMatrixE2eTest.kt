package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import java.awt.image.BufferedImage
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Cold-boot firmware 2.13 and drive physical controls on one writable card. */
class StillCaptureMatrixE2eTest {
    private val fixture = V030FinalPreReleaseGauntletE2eTest()

    private enum class Drive { S, CH, CL }
    private enum class Quality(val menuRow: Int, val extensions: Set<String>) {
        JPEG(1, setOf("JPG")), RAW_JPEG(4, setOf("JPG", "RAF")), RAW(6, setOf("RAF")),
    }
    private enum class Shutter { MS, ES }

    private data class Case(
        val drive: Drive, val quality: Quality, val shutter: Shutter, val focus: String, val nd: Int,
        val minimumGroups: Int = if (drive == Drive.S) 1 else 2,
        val shutterHoldMs: Long = if (drive == Drive.S) 260L else 1800L,
    ) {
        fun values() = listOf(drive.name, quality.name, shutter.name, focus, nd.toString())
    }

    private val rows = listOf(
        Case(Drive.S, Quality.JPEG, Shutter.MS, "MF", 0),
        Case(Drive.S, Quality.JPEG, Shutter.MS, "AF-C", 5),
        Case(Drive.S, Quality.JPEG, Shutter.ES, "AF-S", 10),
        Case(Drive.CH, Quality.JPEG, Shutter.ES, "AF-S", 5),
        Case(Drive.CL, Quality.JPEG, Shutter.ES, "MF", 0),
        Case(Drive.CH, Quality.RAW_JPEG, Shutter.MS, "MF", 10),
        Case(Drive.CL, Quality.RAW_JPEG, Shutter.MS, "AF-C", 5),
        Case(Drive.S, Quality.RAW_JPEG, Shutter.ES, "AF-S", 0),
        Case(Drive.S, Quality.RAW, Shutter.MS, "MF", 5),
        Case(Drive.CL, Quality.RAW, Shutter.MS, "AF-S", 0),
        Case(Drive.CH, Quality.RAW, Shutter.ES, "AF-C", 0),
        Case(Drive.CL, Quality.RAW, Shutter.ES, "AF-C", 10),
    )

    @Test
    fun cardPollingOnlySeesPublishedFiles(@TempDir root: Path) {
        val camera = Files.createDirectories(root.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM/100_FUJI"))
        val jpeg = Files.createFile(camera.resolve("DSCF0001.JPG"))
        val pending = Files.createFile(camera.resolve(".DSCF0001.RAF.fuji-tmp"))
        assertEquals(listOf(jpeg), fixture.listDcimFiles(root).values.toList())
        val raw = Files.move(pending, camera.resolve("DSCF0001.RAF"))
        assertEquals(listOf(jpeg, raw), fixture.listDcimFiles(root).values.toList())
    }

    @Test
    fun matrixIncludesAllSupportedValuePairs() {
        val domains = listOf(
            Drive.entries.map { it.name }, Quality.entries.map { it.name }, Shutter.entries.map { it.name },
            listOf("MF", "AF-S", "AF-C"), listOf("0", "5", "10"),
        )
        for (a in domains.indices) for (b in a + 1 until domains.size) {
            val expected = domains[a].flatMap { first -> domains[b].map { second -> first to second } }.toSet()
            assertEquals(expected, rows.map { it.values()[a] to it.values()[b] }.toSet(), "axes $a / $b")
        }
    }

    @Test
    fun firmwareWritesCompleteStillsAcrossAllPairs(@TempDir temp: Path) = exerciseRows(temp, rows)

    @Test
    fun mechanicalRawBurstReturnsToLiveViewAndAcceptsTheNextDrive(@TempDir temp: Path) = exerciseRows(
        temp,
        listOf(
            Case(Drive.S, Quality.RAW, Shutter.MS, "MF", 0),
            Case(Drive.CH, Quality.RAW, Shutter.MS, "MF", 0, minimumGroups = 3),
            Case(Drive.CL, Quality.RAW, Shutter.MS, "MF", 0),
        ),
    )

    @Test
    fun electronicRawBurstsResumeAcrossDifferentReleaseTimes(@TempDir temp: Path) {
        val low = Case(Drive.CL, Quality.RAW, Shutter.ES, "AF-C", 10)
        exerciseRows(temp, listOf(
            Case(Drive.CH, Quality.RAW, Shutter.ES, "AF-C", 0),
            low,
            low.copy(shutterHoldMs = 1550),
            low.copy(shutterHoldMs = 2050),
            low.copy(shutterHoldMs = 1700),
            low.copy(shutterHoldMs = 1900),
        ))
    }

    private fun exerciseRows(temp: Path, rows: List<Case>) = with(fixture) {
        withLiveImagingSession(temp, "still-matrix") { rt, root ->
            val screens = Files.createDirectories(temp.resolve("matrix-screens"))
            var tab = 0
            var menuRow = 0
            var quality = Quality.JPEG
            var drive = Drive.S
            var shutter: Shutter? = null
            val failures = mutableListOf<String>()
            fun tap(key: String) = tapButton(rt, key, 85, 270)
            fun move(steps: Int) = repeat(abs(steps)) { tap(if (steps > 0) "down" else "up") }
            fun openItem(nextTab: Int, nextRow: Int) {
                check(ensureMenuOpen(rt))
                if (nextTab != tab) {
                    tap("left")
                    move(nextTab - tab)
                    tap("right")
                    tab = nextTab
                    menuRow = 0
                }
                move(nextRow - menuRow)
                menuRow = nextRow
                tap("right")
            }
            fun confirm() {
                tap("menu")
                returnToStillHud(rt)
            }
            for ((index, row) in rows.withIndex()) {
                val label = "row-${index + 1}"
                val failuresBefore = failures.size
                if (row.shutter != shutter) {
                    openItem(2, 9)
                    val current = selectedShutter(snapshot(rt))
                    move(row.shutter.ordinal - current)
                    val image = snapshot(rt)
                    ImageIO.write(image, "png", screens.resolve("$label-shutter.png").toFile())
                    assertEquals(row.shutter.ordinal, selectedShutter(image), "$label shutter selection")
                    confirm()
                    shutter = row.shutter
                }
                if (row.quality != quality) {
                    openItem(0, 1)
                    move(row.quality.menuRow - quality.menuRow)
                    ImageIO.write(snapshot(rt), "png", screens.resolve("$label-quality.png").toFile())
                    confirm()
                    quality = row.quality
                }
                if (row.drive != drive) {
                    tap("drive")
                    move(row.drive.ordinal - drive.ordinal)
                    ImageIO.write(snapshot(rt), "png", screens.resolve("$label-drive.png").toFile())
                    confirm()
                    drive = row.drive
                }
                patchComp(rt, "soc-gpio", mapOf("focusMode" to row.focus))
                patchComp(rt, "optical-filter", mapOf("enabled" to true, "ndStops" to row.nd.toDouble()))
                stepVirtualMs(rt, 500)
                assertEquals(row.focus, compState(rt, "soc-gpio")["effectiveFocusMode"])
                assertEquals(row.nd.toDouble(), compState(rt, "optical-filter")["effectiveNdStops"])
                val existing = listDcimFiles(root).keys
                val before = compState(rt, "shutter")
                val startTick = rt.telemetry.virtualTick
                patchComp(rt, "buttons", mapOf("shutterHalf" to true))
                stepVirtualMs(rt, 420)
                patchComp(rt, "buttons", mapOf("shutterFull" to true))
                stepVirtualMs(rt, row.shutterHoldMs)
                patchComp(rt, "buttons", mapOf("shutterHalf" to false, "shutterFull" to false))
                var stable = 0
                var sizes = emptyMap<String, Long>()
                var ready = false
                for (poll in 0 until 55) {
                    stepVirtualMs(rt, 350)
                    val now = listDcimFiles(root).filterKeys { it !in existing }.mapValues { Files.size(it.value) }
                    stable = if (now.isNotEmpty() && now == sizes) stable + 1 else 0
                    sizes = now
                    val publishedGroups = now.keys.groupBy { it.substringBeforeLast('.') }
                    val completeGroups = publishedGroups.values.count { files ->
                        files.map { it.substringAfterLast('.').uppercase() }.toSet() == row.quality.extensions
                    }
                    if (stable >= 3 && completeGroups >= row.minimumGroups &&
                        readU8(rt, 0x01CBFCA2L) == 0 && readU32(rt, 0x01CC6424L) in listOf(0x100, 0x102)
                    ) {
                        ready = true
                        break
                    }
                }
                val added = listDcimFiles(root).filterKeys { it !in existing }
                val groups = added.keys.groupBy { it.substringBeforeLast('.') }
                val after = compState(rt, "shutter")
                fun delta(key: String) = (after.getValue(key) as Number).toLong() - (before.getValue(key) as Number).toLong()
                val curtain = delta("curtainPulses")
                val motor = delta("cam2Cycles")
                val prefix = "$label ${row.values()}"
                if (!ready) {
                    val screen = readU32(rt, 0x01CC6424L).toString(16)
                    val busy = readU8(rt, 0x01CBFCA2L)
                    failures += "$prefix failed to return to live shooting (screen=0x$screen busy=$busy stablePolls=$stable)"
                }
                if (groups.size < row.minimumGroups || row.drive == Drive.S && groups.size != 1) {
                    failures += "$prefix produced ${groups.size} groups"
                }
                if (row.shutter == Shutter.ES && (curtain != 0L || motor != 0L) ||
                    row.shutter == Shutter.MS && (curtain < groups.size || motor < groups.size)) {
                    failures += "$prefix curtain=$curtain motor=$motor"
                }
                for ((name, files) in groups) {
                    if (files.map { it.substringAfterLast('.').uppercase() }.toSet() != row.quality.extensions) {
                        failures += "$prefix $name has incomplete file group $files"
                    }
                }
                for (path in added.values) {
                    try {
                        if (path.toString().endsWith(".RAF", true)) {
                            val (bits, peak) = validateUncompressedRaw(path)
                            val expectedBits = if (row.drive == Drive.S) 16 else 14
                            check(bits == expectedBits) { "expected $expectedBits-bit RAF, got $bits" }
                            if (row.drive == Drive.S && row.nd == 0) {
                                check(peak > 0x3fff) { "bright reference patch uses only 14 bits in a 16-bit RAF" }
                            }
                        } else {
                            validateJpeg(path)
                        }
                    } catch (e: Exception) {
                        failures += "$prefix ${path.fileName}: ${e.message}"
                    }
                }
                ImageIO.write(snapshot(rt), "png", screens.resolve("$label-end.png").toFile())
                val rowFailures = failures.drop(failuresBefore)
                println("[STILL-MATRIX] $prefix $startTick..${rt.telemetry.virtualTick} files=$sizes curtain=$curtain " +
                    "motor=$motor passed=${rowFailures.isEmpty()} failures=$rowFailures")
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }
    }

    private fun snapshot(rt: EmulatorRuntime): BufferedImage = rt.owner.submit {
        val d = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(d.width * d.height)
        check(d.copyArgb(px))
        BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, d.width, d.height, px, 0, d.width) }
    }.join()

    private fun selectedShutter(image: BufferedImage): Int = (0..4).single { row ->
        // Sample the right end of each selection bar, outside text and the left-hand check mark.
        val rgb = image.getRGB(image.width * 95 / 100, (135 + 76 * row) * image.height / 768)
        (rgb ushr 16 and 255) > 120 && (rgb ushr 8 and 255) > 120 && (rgb and 255) > 120
    }

    private fun validateJpeg(path: Path) {
        ImageIO.createImageInputStream(path.toFile()).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().first()
            try {
                reader.input = input
                check(reader.getWidth(0) == 11648 && reader.getHeight(0) == 8736)
                val fullHeight = reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(16, 16, 0, 0) })
                check(fullHeight.height == 546 && fullHeight.width == 728)
                for (band in 0 until 4) {
                    val rows = (fullHeight.height * band / 4 until fullHeight.height * (band + 1) / 4)
                    check(rows.step(17).any { y ->
                        (0 until fullHeight.width step 17).any { x -> (fullHeight.getRGB(x, y) and 0xFFFFFF) > 0 }
                    }) { "no visible scene pixels in JPEG band ${band + 1}/4" }
                }
            } finally {
                reader.dispose()
            }
        }
    }

    private fun validateUncompressedRaw(path: Path) = RandomAccessFile(path.toFile(), "r").use { file ->
        val head = ByteArray(108).also(file::readFully)
        check(String(head, 0, 16, Charsets.US_ASCII) == "FUJIFILMCCD-RAW ")
        val outer = ByteBuffer.wrap(head)
        val start = Integer.toUnsignedLong(outer.getInt(100))
        val size = Integer.toUnsignedLong(outer.getInt(104))
        check(start + size <= file.length())
        file.seek(start)
        val data = ByteBuffer.wrap(ByteArray(2048).also(file::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        check(data.getShort(0).toInt() == 0x4949 && data.getShort(2).toInt() == 42)
        val ifd = data.getInt(4)
        check(data.getShort(ifd + 2).toInt() and 65535 == 0xf000)
        val sub = data.getInt(ifd + 10)
        val tags = (0 until (data.getShort(sub).toInt() and 65535)).associate {
            val entry = sub + 2 + 12 * it
            (data.getShort(entry).toInt() and 65535) to data.getInt(entry + 8)
        }
        val width = tags.getValue(0xf001)
        val height = tags.getValue(0xf002)
        check(width == 11808 && height == 8754) { "unexpected raw geometry ${width}x$height" }
        val count = width.toLong() * height
        val bits = tags.getValue(0xf003)
        check(bits in listOf(12, 14, 16))
        val plane = tags.getValue(0xf007)
        check(count * 2 == tags.getValue(0xf008).toLong() && plane + 2 * count <= size)
        file.seek(start + plane)
        val buf = ByteArray(128 * 1024)
        val words = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var lo = 65535
        var hi = 0
        val bandMaxima = IntArray(4)
        for (band in bandMaxima.indices) {
            val rows = height * (band + 1) / 4 - height * band / 4
            var remaining = rows.toLong() * width
            var bandMax = 0
            while (remaining > 0) {
                val samples = minOf(remaining, words.capacity().toLong()).toInt()
                file.readFully(buf, 0, samples * 2)
                for (i in 0 until samples) {
                    val v = words.get(i).toInt() and 65535
                    lo = minOf(lo, v)
                    bandMax = maxOf(bandMax, v)
                }
                remaining -= samples
            }
            bandMaxima[band] = bandMax
            hi = maxOf(hi, bandMax)
        }
        check(hi > lo && hi < (1 shl bits) && bandMaxima.all { it > 0 }) {
            "$bits-bit uncompressed plane min=$lo max=$hi bandMaxima=${bandMaxima.contentToString()}"
        }
        bits to hi
    }
}

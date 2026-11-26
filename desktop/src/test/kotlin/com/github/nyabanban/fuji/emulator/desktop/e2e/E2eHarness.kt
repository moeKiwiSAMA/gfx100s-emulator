package com.github.nyabanban.fuji.emulator.desktop.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.ExecutionLocation
import com.github.nyabanban.fuji.emulator.api.ExecutionLocationSource
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogCompiler
import com.github.nyabanban.fuji.emulator.catalog.CatalogIO
import com.github.nyabanban.fuji.emulator.catalog.PortBinding
import com.github.nyabanban.fuji.emulator.desktop.EmulatorSnapshot
import com.github.nyabanban.fuji.emulator.desktop.configuredComponentProfiles
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.MmioRouter
import com.github.nyabanban.fuji.emulator.runtime.MmioWindow
import com.github.nyabanban.fuji.emulator.runtime.OwnerLoop
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shared opaque-box E2E harness for resolving staged artifacts/external assets,
 * building isolated `@TempDir` homes with sparse-preserving 4 GiB SD copies,
 * running CLI/HTTP/DisplayBringupSnapshot processes, and inspecting images & FAT filesystems.
 */
object E2eHarness {
    val projectRoot: Path by lazy {
        System.getProperty("componentProjectRoot")?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: run {
                var dir = Path.of("").toAbsolutePath().normalize()
                while (!Files.isRegularFile(dir.resolve("settings.gradle.kts")) && dir.parent != null) {
                    dir = dir.parent
                }
                dir
            }
    }

    val stagedLib: Path get() = projectRoot.resolve("build/emulator/lib")
    val stagedComponents: Path get() = projectRoot.resolve("build/emulator/compoments")
    val stagedCatalog: Path get() = projectRoot.resolve("build/emulator/config/catalog.compiled.json")

    fun resolveFirmwareFile(): Path? {
        val env = System.getenv("FUJI_FIRMWARE_FILE")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (env != null && Files.isRegularFile(env)) return env
        val local = projectRoot.resolve("firmware-data/gfx100s/2.13/GXUP0004.DAT")
        if (Files.isRegularFile(local)) return local
        val mainRepo = Path.of("/home/coder/fuji-emulator-gfx100s/firmware-data/gfx100s/2.13/GXUP0004.DAT")
        return mainRepo.takeIf { Files.isRegularFile(it) }
    }

    fun resolveNativeLibrary(): Path? =
        com.github.nyabanban.fuji.emulator.desktop.CpuEngineConfig.findDynarmicLibrary(projectRoot, emptyMap())

    fun resolveDcfFixtureDir(): Path? {
        val env = System.getenv("FUJI_SD_DCF_FIXTURE")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (env != null && Files.isDirectory(env)) return env
        val fallback = Path.of("/home/coder/fuji-emulator-gfx100s/local/sd-dcf-playback-01")
        return fallback.takeIf { Files.isRegularFile(it.resolve("slot1.img")) }
    }

    fun resolveBootRomArchive(): Path? {
        val local = projectRoot.resolve("firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
        if (Files.isRegularFile(local)) return local
        val fallback = Path.of("/home/coder/fuji-emulator-gfx100s/firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
        return fallback.takeIf { Files.isRegularFile(it) }
    }

    val blankCardHomeDir: Path = Path.of("/home/coder/boot-play-configured/on-shooting-hud2/home")
    val blankCardSnapshotDir: Path = Path.of("/home/coder/boot-play-configured/on-shooting-hud2/snap2300k")
    val blankCardBootSnapshotDir: Path = Path.of("/home/coder/boot-play-configured/on-shooting-boot3/snap1200k")
    val graphicsOnHomeDir: Path = Path.of("/home/coder/boot-play-configured/graphics-on/home")
    val graphicsOnSnapshotDir: Path = Path.of("/home/coder/boot-play-configured/graphics-on/after-command")
    val photoCardHomeDir: Path = Path.of("/home/coder/orig-4c6/photo-on/t79-shooting-boot3/home")
    val photoCardSnapshotDir: Path = Path.of("/home/coder/orig-4c6/photo-on/t79-shooting-boot3/snap1200k")
    val photoCardBootInputDir: Path = Path.of("/home/coder/orig-4c6/photo-on/t79-shooting-boot3-input")

    fun stageComponentHome(tempRoot: Path, legacyVga: Boolean = false): Path {
        val compDir = tempRoot.resolve("compoments")
        Files.createDirectories(compDir)
        Files.createDirectories(tempRoot.resolve("config"))
        Files.createDirectories(tempRoot.resolve("logs"))
        if (Files.isDirectory(stagedComponents)) {
            Files.list(stagedComponents).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jar") }
                    .forEach { Files.copy(it, compDir.resolve(it.fileName), StandardCopyOption.REPLACE_EXISTING) }
            }
        } else {
            Files.list(projectRoot.resolve("components")).use { modules ->
                modules.filter { Files.isRegularFile(it.resolve("build.gradle.kts")) }.forEach { module ->
                    val libs = module.resolve("build/libs")
                    if (Files.isDirectory(libs)) {
                        Files.list(libs).use { jars ->
                            jars.filter { it.toString().endsWith(".jar") }
                                .forEach { Files.copy(it, compDir.resolve(it.fileName), StandardCopyOption.REPLACE_EXISTING) }
                        }
                    }
                }
            }
        }
        if (legacyVga) {
            Formats.atomicYaml(compDir.resolve("display/display.yaml"), mapOf("width" to 640, "height" to 480))
        }
        return tempRoot
    }

    fun copySparsePreserving(source: Path, target: Path) {
        Files.createDirectories(target.parent)
        val size = Files.size(source)
        if (size >= 1024L * 1024L) {
            val process = ProcessBuilder("cp", "--sparse=always", source.toString(), target.toString())
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            val ok = process.waitFor(60, TimeUnit.SECONDS)
            if (ok && process.exitValue() == 0) {
                target.toFile().setWritable(true, true)
                return
            }
            if (!ok) {
                process.destroyForcibly()
                process.waitFor()
            }
            throw IllegalStateException("cp --sparse=always failed for $source -> $target: $out")
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        target.toFile().setWritable(true, true)
    }

    private val skippedTopDirs = setOf("logs", "diagnostics", "dumps", "checkpoint-tmp", "lib", "bringup-ram", "tmp")
    private val skippedTopPrefix = listOf("bringup.result", "bringup.summary", "bringup.frames", "bringup.capture", "bringup.button-events", "snapshot.")

    fun cloneBringupHome(
        sourceHome: Path,
        targetHome: Path,
        copySdImage: Boolean = true,
        overrideSdReadOnly: Boolean? = null,
        firmwareFile: Path? = resolveFirmwareFile(),
        nativeLibrary: Path? = resolveNativeLibrary(),
        preserveSourceCatalog: Boolean = false,
    ): Path {
        require(Files.isDirectory(sourceHome)) { "Source home missing: $sourceHome" }
        val flashMutable = sourceHome.resolve("compoments/flash-image/mutable.json")
        val runtimeFlashCopies: Set<String> = if (Files.isRegularFile(flashMutable)) {
            val flash = Formats.readData(flashMutable)
            @Suppress("UNCHECKED_CAST")
            (flash["regions"] as? List<Map<String, Any?>>).orEmpty()
                .mapNotNull { it["file"]?.toString() }
                .flatMap { listOf("compoments/flash-image/$it", "compoments/flash-image/$it.lock") }
                .toSet()
        } else {
            emptySet()
        }
        Files.createDirectories(targetHome)
        Files.walk(sourceHome).use { stream ->
            stream.forEach { src ->
                val rel = sourceHome.relativize(src).toString().replace('\\', '/')
                if (rel.isEmpty()) return@forEach
                val top = rel.substringBefore('/')
                if (top in skippedTopDirs || top.startsWith("checkpoint")) return@forEach
                if (skippedTopPrefix.any { rel.startsWith(it) }) return@forEach
                if (rel in runtimeFlashCopies) return@forEach
                if (rel.endsWith(".lock")) return@forEach
                if (rel.startsWith("compoments/") && rel.endsWith(".jar")) return@forEach
                if (!copySdImage && rel.startsWith("compoments/sdcard-reader/") && rel.endsWith(".img")) return@forEach

                val dst = targetHome.resolve(rel)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else if (Files.isRegularFile(src, NOFOLLOW_LINKS)) {
                    copySparsePreserving(src, dst)
                }
            }
        }
        Files.createDirectories(targetHome.resolve("logs"))
        Files.createDirectories(targetHome.resolve("lib"))
        Files.createDirectories(targetHome.resolve("compoments"))
        if (Files.isDirectory(stagedLib)) {
            Files.list(stagedLib).use { s ->
                s.filter { it.fileName.toString().endsWith(".jar") }
                    .forEach { Files.copy(it, targetHome.resolve("lib").resolve(it.fileName), StandardCopyOption.REPLACE_EXISTING) }
            }
        }
        if (Files.isDirectory(stagedComponents)) {
            Files.list(stagedComponents).use { s ->
                s.filter { it.fileName.toString().endsWith(".jar") }
                    .forEach { Files.copy(it, targetHome.resolve("compoments").resolve(it.fileName), StandardCopyOption.REPLACE_EXISTING) }
            }
        }
        if (!preserveSourceCatalog && Files.isRegularFile(stagedCatalog)) {
            Files.copy(stagedCatalog, targetHome.resolve("config/catalog.compiled.json"), StandardCopyOption.REPLACE_EXISTING)
        }
        val emulatorYaml = targetHome.resolve("config/emulator.yaml")
        if (Files.isRegularFile(emulatorYaml)) {
            val cfg = Formats.read(emulatorYaml).toMutableMap()
            if (firmwareFile != null) cfg["firmwareFile"] = firmwareFile.toString()
            if (nativeLibrary != null) cfg["nativeLibrary"] = nativeLibrary.toString()
            cfg["httpPort"] = 0
            Formats.atomicYaml(emulatorYaml, cfg)
        }
        if (overrideSdReadOnly != null) {
            val sdYaml = targetHome.resolve("compoments/sdcard-reader/sdcard-reader.yaml")
            if (Files.isRegularFile(sdYaml)) {
                val sdCfg = Formats.read(sdYaml).toMutableMap()
                sdCfg["slot1ReadOnly"] = overrideSdReadOnly
                Formats.atomicYaml(sdYaml, sdCfg)
            }
            val sdHostDir = targetHome.resolve("compoments/soc-sd-host")
            val sdHostYaml = sdHostDir.resolve("soc-sd-host.yaml")
            if (Files.isRegularFile(sdHostYaml)) {
                val sdHostCfg = Formats.read(sdHostYaml)
                val inputRel = sdHostCfg["inputFile"]?.toString()?.takeIf { it.isNotBlank() }
                if (inputRel != null && inputRel.startsWith("sd-card-v") && inputRel.endsWith(".json")) {
                    val cardFile = sdHostDir.resolve(inputRel).normalize()
                    if (cardFile.startsWith(sdHostDir.normalize()) && Files.isRegularFile(cardFile)) {
                        val cardData = Formats.readData(cardFile).toMutableMap()
                        val cards = (cardData["cards"] as? Map<*, *>)?.toMutableMap()
                        val slot0 = (cards?.get("0") as? Map<*, *>)?.toMutableMap()
                        if (cards != null && slot0 != null) {
                            slot0["readOnly"] = overrideSdReadOnly
                            cards["0"] = slot0
                            cardData["cards"] = cards
                            Formats.writeJson(cardFile, cardData)
                        }
                    }
                }
            }
        }
        return targetHome
    }

    fun <T> withComponentHost(
        home: Path,
        catalog: AddressCatalog? = null,
        action: (OwnerLoop, ComponentHost) -> T,
    ): T {
        OwnerLoop().use { owner ->
            val host = ComponentHost(owner, home.resolve("compoments"), catalog)
            try {
                owner.submit { host.load() }.join()
                return action(owner, host)
            } finally {
                owner.submit { host.close() }.join()
            }
        }
    }

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    fun javaExecutable(): String = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
    ).toString()

    fun runJavaProcess(
        classpath: String,
        mainClass: String,
        args: List<String>,
        timeoutSeconds: Long = 30,
        jvmArgs: List<String> = emptyList(),
        workDir: Path? = null,
    ): ProcessResult {
        val cmd = ArrayList<String>()
        cmd += javaExecutable()
        cmd += "--enable-native-access=ALL-UNNAMED"
        cmd += "-Dstdout.encoding=UTF-8"
        cmd += "-Dstderr.encoding=UTF-8"
        cmd += jvmArgs
        cmd += "-cp"
        cmd += classpath
        cmd += mainClass
        cmd += args
        val pb = ProcessBuilder(cmd)
        if (workDir != null) pb.directory(workDir.toFile())
        val outFile = Files.createTempFile("fuji-e2e-out-", ".log")
        val errFile = Files.createTempFile("fuji-e2e-err-", ".log")
        try {
            pb.redirectOutput(outFile.toFile())
            pb.redirectError(errFile.toFile())
            val process = pb.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            val stdout = Files.readString(outFile)
            val stderr = Files.readString(errFile)
            return ProcessResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdout,
                stderr = stderr,
                timedOut = !finished,
            )
        } finally {
            Files.deleteIfExists(outFile)
            Files.deleteIfExists(errFile)
        }
    }

    fun runMainCli(home: Path, args: List<String>, timeoutSeconds: Long = 30): ProcessResult {
        require(Files.isDirectory(stagedLib)) { "Staged runtime lib directory missing: $stagedLib" }
        val cp = "$stagedLib${File.separator}*"
        return runJavaProcess(
            classpath = cp,
            mainClass = "com.github.nyabanban.fuji.emulator.desktop.MainKt",
            args = listOf("--home", home.toString()) + args,
            timeoutSeconds = timeoutSeconds,
        )
    }

    fun runDisplayBringupResume(
        home: Path,
        sourceCommit: String,
        snapshotDir: Path,
        args: List<String>,
        timeoutSeconds: Long = 90,
        jvmArgs: List<String> = listOf("-Xmx2g"),
    ): ProcessResult {
        val libDir = home.resolve("lib").takeIf { Files.isDirectory(it) } ?: stagedLib
        val cp = "$libDir${File.separator}*"
        return runJavaProcess(
            classpath = cp,
            mainClass = "com.github.nyabanban.fuji.emulator.study.DisplayBringupSnapshot",
            args = listOf("resume", home.toString(), sourceCommit, snapshotDir.toString()) + args,
            timeoutSeconds = timeoutSeconds,
            jvmArgs = jvmArgs,
        )
    }

    class HeadlessServerHandle(
        val process: Process,
        val port: Int,
        val home: Path,
    ) : AutoCloseable {
        val baseUrl: String get() = "http://127.0.0.1:$port"
        fun client(): HttpApiClient = HttpApiClient(baseUrl)
        override fun close() {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    fun startHeadlessServer(home: Path, timeoutSeconds: Long = 30): HeadlessServerHandle {
        val cp = "$stagedLib${File.separator}*"
        val outFile = home.resolve("headless.stdout.log")
        val errFile = home.resolve("headless.stderr.log")
        val process = ProcessBuilder(
            javaExecutable(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            cp,
            "com.github.nyabanban.fuji.emulator.desktop.MainKt",
            "--home",
            home.toString(),
            "--headless",
        ).redirectOutput(outFile.toFile()).redirectError(errFile.toFile()).start()

        val logFile = home.resolve("logs/latest.log")
        val pattern = Regex("(?m)^.*Control API listening on [^\\r\\n]*:([0-9]+)\\n")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var port: Int? = null
        while (port == null && process.isAlive && System.nanoTime() < deadline) {
            if (Files.isRegularFile(logFile)) {
                port = pattern.findAll(Files.readString(logFile)).map { it.groupValues[1].toInt() }.lastOrNull()
            }
            if (port == null) Thread.sleep(20)
        }
        if (port == null) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            val err = if (Files.isRegularFile(errFile)) Files.readString(errFile) else ""
            val out = if (Files.isRegularFile(outFile)) Files.readString(outFile) else ""
            throw IllegalStateException("Headless server failed to bind Control API within ${timeoutSeconds}s.\nstdout=$out\nstderr=$err")
        }
        return HeadlessServerHandle(process, port, home)
    }

    class HttpApiClient(private val baseUrl: String) : AutoCloseable {
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        fun get(pathAndQuery: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI.create("$baseUrl$pathAndQuery"))
                .timeout(Duration.ofSeconds(10))
                .GET()
            headers.forEach { (k, v) -> builder.header(k, v) }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }

        fun postEmptyJson(path: String): HttpResponse<String> {
            val req = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            return http.send(req, HttpResponse.BodyHandlers.ofString())
        }

        fun patchComponent(id: String, revision: Long, changes: Map<String, Any?>, origin: String? = null): HttpResponse<String> {
            val body = Formats.json.writeValueAsString(mapOf("revision" to revision, "changes" to changes))
            val builder = HttpRequest.newBuilder(URI.create("$baseUrl/api/v1/components/$id"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
            if (origin != null) builder.header("Origin", origin)
            return http.send(
                builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }

        fun getCatalogJson(query: String): JsonNode = Formats.json.readTree(get("/api/v1/catalog?$query").body())
        fun getStateJson(): JsonNode = Formats.json.readTree(get("/api/v1/state").body())

        override fun close() {
            http.close()
        }
    }

    // ---- Image Metrics ----

    fun readPng(path: Path): BufferedImage =
        requireNotNull(ImageIO.read(path.toFile())) { "Failed to decode image: $path" }

    fun toArgbPixels(img: BufferedImage): IntArray =
        img.getRGB(0, 0, img.width, img.height, null, 0, img.width)

    fun distinctRgbCount(pixels: IntArray): Int {
        val seen = HashSet<Int>(minOf(pixels.size, 4096))
        for (px in pixels) seen.add(px and 0x00ffffff)
        return seen.size
    }

    fun nonBlackRatio(pixels: IntArray, threshold: Int = 8): Double {
        if (pixels.isEmpty()) return 0.0
        var count = 0
        for (px in pixels) {
            val r = (px ushr 16) and 0xff
            val g = (px ushr 8) and 0xff
            val b = px and 0xff
            if (r > threshold || g > threshold || b > threshold) count++
        }
        return count.toDouble() / pixels.size.toDouble()
    }

    fun changedPixelRatio(a: IntArray, b: IntArray, channelThreshold: Int = 4): Double {
        require(a.size == b.size) { "Pixel buffer sizes differ: ${a.size} vs ${b.size}" }
        if (a.isEmpty()) return 0.0
        var changed = 0
        for (i in a.indices) {
            val pa = a[i]
            val pb = b[i]
            val dr = abs(((pa ushr 16) and 0xff) - ((pb ushr 16) and 0xff))
            val dg = abs(((pa ushr 8) and 0xff) - ((pb ushr 8) and 0xff))
            val db = abs((pa and 0xff) - (pb and 0xff))
            if (dr > channelThreshold || dg > channelThreshold || db > channelThreshold) changed++
        }
        return changed.toDouble() / a.size.toDouble()
    }

    fun normalizedCrossCorrelation(
        a: IntArray,
        widthA: Int,
        heightA: Int,
        b: IntArray,
        widthB: Int,
        heightB: Int,
    ): Double {
        val lumaA = DoubleArray(widthA * heightA)
        val lumaB = DoubleArray(widthA * heightA)
        var sumA = 0.0
        var sumB = 0.0
        for (y in 0 until heightA) {
            val sy = (y * heightB) / heightA
            for (x in 0 until widthA) {
                val sx = (x * widthB) / widthA
                val idx = y * widthA + x
                val pa = a[idx]
                val pb = b[sy * widthB + sx]
                val la = 0.299 * ((pa ushr 16) and 0xff) + 0.587 * ((pa ushr 8) and 0xff) + 0.114 * (pa and 0xff)
                val lb = 0.299 * ((pb ushr 16) and 0xff) + 0.587 * ((pb ushr 8) and 0xff) + 0.114 * (pb and 0xff)
                lumaA[idx] = la
                lumaB[idx] = lb
                sumA += la
                sumB += lb
            }
        }
        val n = lumaA.size.toDouble()
        val meanA = sumA / n
        val meanB = sumB / n
        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        for (i in lumaA.indices) {
            val da = lumaA[i] - meanA
            val db = lumaB[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        val denom = sqrt(denA * denB)
        return if (denom == 0.0) 0.0 else num / denom
    }

    fun cropArgb(pixels: IntArray, width: Int, height: Int, x: Int, y: Int, cropW: Int, cropH: Int): IntArray {
        require(x >= 0 && y >= 0 && cropW > 0 && cropH > 0 && x + cropW <= width && y + cropH <= height) {
            "Invalid crop ($x,$y,${cropW}x$cropH) for ${width}x$height"
        }
        val out = IntArray(cropW * cropH)
        for (row in 0 until cropH) {
            System.arraycopy(pixels, (y + row) * width + x, out, row * cropW, cropW)
        }
        return out
    }

    fun maxPlayCorrelation(frame: BufferedImage, ref: BufferedImage): Double {
        val framePx = toArgbPixels(frame)
        val refPx = toArgbPixels(ref)
        val full = normalizedCrossCorrelation(framePx, frame.width, frame.height, refPx, ref.width, ref.height)
        val cwA = (frame.width * 60) / 100
        val chA = (frame.height * 60) / 100
        val cwB = (ref.width * 60) / 100
        val chB = (ref.height * 60) / 100
        if (cwA <= 0 || chA <= 0 || cwB <= 0 || chB <= 0) return full
        val cropA = cropArgb(framePx, frame.width, frame.height, (frame.width - cwA) / 2, (frame.height - chA) / 2, cwA, chA)
        val cropB = cropArgb(refPx, ref.width, ref.height, (ref.width - cwB) / 2, (ref.height - chB) / 2, cwB, chB)
        val center = normalizedCrossCorrelation(cropA, cwA, chA, cropB, cwB, chB)
        return maxOf(full, center)
    }

    fun decodeJpegSubsampled(jpegBytes: ByteArray, subsample: Int = 16): BufferedImage {
        require(subsample >= 1) { "Invalid subsample factor: $subsample" }
        ImageIO.createImageInputStream(ByteArrayInputStream(jpegBytes)).use { iis ->
            val readers = ImageIO.getImageReadersByFormatName("jpg")
            require(readers.hasNext()) { "No JPEG ImageReader available" }
            val reader = readers.next()
            try {
                reader.input = iis
                val param = reader.defaultReadParam
                if (subsample > 1) {
                    param.setSourceSubsampling(subsample, subsample, 0, 0)
                }
                return requireNotNull(reader.read(0, param)) { "JPEG reader returned null image" }
            } finally {
                reader.dispose()
            }
        }
    }

    // ---- FAT16 / FAT32 Validation ----

    data class FatInspection(
        val fsType: String,
        val partitionStartLba: Long,
        val bytesPerSector: Int,
        val sectorsPerCluster: Int,
        val reservedSectors: Int,
        val numFats: Int,
        val fatSizeSectors: Long,
        val rootEntries: List<String>,
        val fat0MediaByte: Int,
        val fsInfoValid: Boolean = true,
        val fatMirrorValid: Boolean = true,
        val fat32EocMarkersValid: Boolean = true,
    )

    fun inspectFatFilesystem(imagePath: Path): FatInspection {
        FileChannel.open(imagePath, StandardOpenOption.READ).use { ch ->
            fun readRange(byteOffset: Long, size: Int): ByteArray {
                val buf = ByteBuffer.allocate(size)
                var read = 0
                while (read < size) {
                    val n = ch.read(buf, byteOffset + read)
                    require(n >= 0) { "Unexpected EOF reading byteOffset=$byteOffset in $imagePath" }
                    read += n
                }
                return buf.array()
            }

            fun readSector(lba: Long): ByteArray = readRange(lba * 512L, 512)

            fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
            fun u32(b: ByteArray, off: Int): Long =
                (b[off].toLong() and 0xff) or
                    ((b[off + 1].toLong() and 0xff) shl 8) or
                    ((b[off + 2].toLong() and 0xff) shl 16) or
                    ((b[off + 3].toLong() and 0xff) shl 24)

            val mbr = readSector(0)
            require(u16(mbr, 510) == 0xAA55) { "Missing 0x55AA signature at sector 0 in $imagePath" }

            val partType = mbr[446 + 4].toInt() and 0xff
            val partLba = u32(mbr, 446 + 8)
            val vbrLba = if (partType in setOf(0x04, 0x06, 0x0B, 0x0C, 0x0E) && partLba > 0L) partLba else 0L
            val vbr = if (vbrLba == 0L) mbr else readSector(vbrLba)
            require(u16(vbr, 510) == 0xAA55) { "Missing 0x55AA signature at VBR LBA $vbrLba in $imagePath" }

            val bytesPerSector = u16(vbr, 11)
            val sectorsPerCluster = vbr[13].toInt() and 0xff
            val reservedSectors = u16(vbr, 14)
            val numFats = vbr[16].toInt() and 0xff
            val rootEntryCount = u16(vbr, 17)
            val totalSectors32 = u32(vbr, 32)
            val fatSize16 = u16(vbr, 22).toLong()
            val fatSize32 = u32(vbr, 36)
            val isFat32 = fatSize16 == 0L && fatSize32 > 0L
            val fsType = if (isFat32) {
                vbr.copyOfRange(82, 90).toString(Charsets.US_ASCII)
            } else {
                vbr.copyOfRange(54, 62).toString(Charsets.US_ASCII)
            }
            val fatSize = if (isFat32) fatSize32 else fatSize16
            require(bytesPerSector == 512) { "Unsupported bytesPerSector=$bytesPerSector" }
            require(sectorsPerCluster > 0 && (sectorsPerCluster and (sectorsPerCluster - 1)) == 0) {
                "Invalid sectorsPerCluster=$sectorsPerCluster"
            }
            require(numFats >= 1 && reservedSectors >= 1 && fatSize > 0) {
                "Invalid FAT geometry: fats=$numFats reserved=$reservedSectors fatSize=$fatSize"
            }

            val fat0Lba = vbrLba + reservedSectors
            val fat0 = readSector(fat0Lba)
            val fat0Media = fat0[0].toInt() and 0xff
            if (isFat32) {
                require(totalSectors32 > 0L) { "Invalid FAT32 BPB_TotSec32=$totalSectors32 in $imagePath" }
                val backupVbr = readSector(vbrLba + 6L)
                require(u16(backupVbr, 510) == 0xAA55) {
                    "Missing 0x55AA signature at FAT32 backup VBR LBA ${vbrLba + 6L} in $imagePath"
                }
                val fsInfo = readSector(vbrLba + 1L)
                require(u32(fsInfo, 0) == 0x41615252L) { "Invalid FAT32 FSInfo LeadSig at LBA ${vbrLba + 1L} in $imagePath" }
                require(u32(fsInfo, 484) == 0x61417272L) { "Invalid FAT32 FSInfo StrucSig at LBA ${vbrLba + 1L} in $imagePath" }
                require(u32(fsInfo, 508) == 0xAA550000L) { "Invalid FAT32 FSInfo TrailSig at LBA ${vbrLba + 1L} in $imagePath" }
                val e0 = u32(fat0, 0) and 0x0FFFFFFFL
                val e1 = u32(fat0, 4) and 0x0FFFFFFFL
                val e2 = u32(fat0, 8) and 0x0FFFFFFFL
                require(fat0Media in setOf(0xF8, 0xF0) && e0 == (0x0FFFFF00L or fat0Media.toLong())) {
                    "Invalid FAT32 FAT0[0]=0x${e0.toString(16)} media=0x${fat0Media.toString(16)} in $imagePath"
                }
                require(e1 >= 0x0FFFFFF8L) { "Invalid FAT32 FAT0[1]=0x${e1.toString(16)} in $imagePath" }
                require(e2 >= 0x0FFFFFF8L) { "Invalid FAT32 FAT0[2]=0x${e2.toString(16)} in $imagePath" }
                if (numFats >= 2) {
                    var remaining = fatSize * 512L
                    var offset = 0L
                    while (remaining > 0L) {
                        val chunk = minOf(remaining, 64L * 1024L).toInt()
                        val a = readRange(fat0Lba * 512L + offset, chunk)
                        val b = readRange((fat0Lba + fatSize) * 512L + offset, chunk)
                        require(a.contentEquals(b)) { "FAT0 and FAT1 mismatch at byte offset $offset in $imagePath" }
                        offset += chunk
                        remaining -= chunk
                    }
                }
            }
            val fatMirrorValid = if (numFats >= 2) {
                val fat1 = readSector(fat0Lba + fatSize)
                fat1.contentEquals(fat0)
            } else true

            var fsInfoValid = true
            var fat32EocMarkersValid = true
            val rootCluster = if (isFat32) u32(vbr, 44) else 0L
            if (isFat32) {
                val fsInfoSector = u16(vbr, 48)
                fsInfoValid = if (fsInfoSector in 1 until reservedSectors) {
                    val fsInfo = readSector(vbrLba + fsInfoSector)
                    u32(fsInfo, 0) == 0x41615252L &&
                        u32(fsInfo, 484) == 0x61417272L &&
                        u32(fsInfo, 508) == 0xAA550000L
                } else false
                val e0 = u32(fat0, 0) and 0x0FFFFFFFL
                val e1 = u32(fat0, 4) and 0x0FFFFFFFL
                val rootEntry = if (rootCluster in 2L until 128L) u32(fat0, rootCluster.toInt() * 4) and 0x0FFFFFFFL else 0L
                fat32EocMarkersValid = (e0 == (0x0FFFFF00L or fat0Media.toLong())) &&
                    (e1 >= 0x0FFFFFF8L) &&
                    (rootCluster !in 2L until 128L || rootEntry >= 0x0FFFFFF8L || rootEntry in 2L until 0x0FFFFFF8L)
            }

            val dataStartLba = fat0Lba + numFats * fatSize + ((rootEntryCount * 32 + 511) / 512)
            val rootSector = if (isFat32) {
                readSector(dataStartLba + (rootCluster - 2L) * sectorsPerCluster)
            } else {
                readSector(fat0Lba + numFats * fatSize)
            }
            val entries = ArrayList<String>()
            for (off in 0 until 512 step 32) {
                val first = rootSector[off].toInt() and 0xff
                if (first == 0x00) break
                if (first == 0xE5) continue
                val attr = rootSector[off + 11].toInt() and 0xff
                if ((attr and 0x0F) == 0x0F) continue // LFN
                val name = rootSector.copyOfRange(off, off + 8).toString(Charsets.US_ASCII).trimEnd()
                val ext = rootSector.copyOfRange(off + 8, off + 11).toString(Charsets.US_ASCII).trimEnd()
                entries.add(if (ext.isEmpty()) name else "$name.$ext")
            }

            return FatInspection(
                fsType = fsType,
                partitionStartLba = vbrLba,
                bytesPerSector = bytesPerSector,
                sectorsPerCluster = sectorsPerCluster,
                reservedSectors = reservedSectors,
                numFats = numFats,
                fatSizeSectors = fatSize,
                rootEntries = entries,
                fat0MediaByte = fat0Media,
                fsInfoValid = fsInfoValid,
                fatMirrorValid = fatMirrorValid,
                fat32EocMarkersValid = fat32EocMarkersValid,
            )
        }
    }

    data class FatFileLocation(
        val firstCluster: Long,
        val firstLba: Long,
        val byteOffset: Long,
        val fileSize: Long,
        val clusterChain: List<Long>,
        val dataStartLba: Long,
        val sectorsPerCluster: Int,
    )

    fun locateFat32File(imagePath: Path, fatPath: String): FatFileLocation {
        val parts = fatPath.trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Empty FAT32 path" }
        FileChannel.open(imagePath, StandardOpenOption.READ).use { ch ->
            fun readBytesAt(offset: Long, size: Int): ByteArray {
                val buf = ByteBuffer.allocate(size)
                var read = 0
                while (read < size) {
                    val n = ch.read(buf, offset + read)
                    require(n >= 0) { "Unexpected EOF reading offset $offset in $imagePath" }
                    read += n
                }
                return buf.array()
            }
            fun readSector(lba: Long): ByteArray = readBytesAt(lba * 512L, 512)
            fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
            fun u32(b: ByteArray, off: Int): Long =
                (b[off].toLong() and 0xff) or
                    ((b[off + 1].toLong() and 0xff) shl 8) or
                    ((b[off + 2].toLong() and 0xff) shl 16) or
                    ((b[off + 3].toLong() and 0xff) shl 24)

            val mbr = readSector(0)
            require(u16(mbr, 510) == 0xAA55) { "Missing 0x55AA signature at sector 0 in $imagePath" }
            val partType = mbr[446 + 4].toInt() and 0xff
            val partLba = u32(mbr, 446 + 8)
            val vbrLba = if (partType in setOf(0x04, 0x06, 0x0B, 0x0C, 0x0E) && partLba > 0L) partLba else 0L
            val vbr = if (vbrLba == 0L) mbr else readSector(vbrLba)
            require(u16(vbr, 510) == 0xAA55) { "Missing 0x55AA at VBR LBA $vbrLba in $imagePath" }
            val bytesPerSector = u16(vbr, 11)
            val sectorsPerCluster = vbr[13].toInt() and 0xff
            val reservedSectors = u16(vbr, 14)
            val numFats = vbr[16].toInt() and 0xff
            val fatSize16 = u16(vbr, 22).toLong()
            val fatSize32 = u32(vbr, 36)
            require(bytesPerSector == 512 && fatSize16 == 0L && fatSize32 > 0L) { "Expected FAT32 image in $imagePath" }
            val rootCluster = u32(vbr, 44)
            val fat0Lba = vbrLba + reservedSectors
            val dataStartLba = fat0Lba + numFats * fatSize32

            fun clusterChain(startCluster: Long): List<Long> {
                val chain = ArrayList<Long>()
                val seen = HashSet<Long>()
                var cur = startCluster
                while (cur in 2L until 0x0FFFFFF8L) {
                    require(seen.add(cur)) { "Cycle in FAT32 cluster chain at $cur" }
                    chain.add(cur)
                    val entryBytes = readBytesAt(fat0Lba * 512L + cur * 4L, 4)
                    cur = u32(entryBytes, 0) and 0x0FFFFFFFL
                    require(chain.size <= 1_000_000) { "Cluster chain too long" }
                }
                return chain
            }

            fun findEntry(dirCluster: Long, target: String, expectDir: Boolean): Pair<Long, Long> {
                for (cluster in clusterChain(dirCluster)) {
                    val data = readBytesAt((dataStartLba + (cluster - 2L) * sectorsPerCluster) * 512L, sectorsPerCluster * 512)
                    for (off in 0 until data.size step 32) {
                        val first = data[off].toInt() and 0xff
                        if (first == 0x00) throw IllegalArgumentException("FAT32 entry '$target' not found in $imagePath")
                        if (first == 0xE5) continue
                        val attr = data[off + 11].toInt() and 0xff
                        if ((attr and 0x0F) == 0x0F || (attr and 0x08) != 0) continue
                        val isDir = (attr and 0x10) != 0
                        val name = data.copyOfRange(off, off + 8).toString(Charsets.US_ASCII).trimEnd()
                        val ext = data.copyOfRange(off + 8, off + 11).toString(Charsets.US_ASCII).trimEnd()
                        val entryName = if (ext.isEmpty()) name else "$name.$ext"
                        if (entryName.equals(target.trim(), ignoreCase = true) && isDir == expectDir) {
                            val hi = u16(data, off + 20).toLong()
                            val lo = u16(data, off + 26).toLong()
                            return ((hi shl 16) or lo) to u32(data, off + 28)
                        }
                    }
                }
                throw IllegalArgumentException("FAT32 entry '$target' not found in $imagePath")
            }

            var currentCluster = rootCluster
            for (i in 0 until parts.size - 1) {
                currentCluster = findEntry(currentCluster, parts[i], expectDir = true).first
            }
            val (fileCluster, fileSize) = findEntry(currentCluster, parts.last(), expectDir = false)
            val chain = clusterChain(fileCluster)
            val firstLba = dataStartLba + (fileCluster - 2L) * sectorsPerCluster
            return FatFileLocation(
                firstCluster = fileCluster,
                firstLba = firstLba,
                byteOffset = firstLba * 512L,
                fileSize = fileSize,
                clusterChain = chain,
                dataStartLba = dataStartLba,
                sectorsPerCluster = sectorsPerCluster,
            )
        }
    }

    fun extractFat32File(imagePath: Path, fatPath: String): ByteArray {
        val loc = locateFat32File(imagePath, fatPath)
        require(loc.fileSize in 0L..128L * 1024L * 1024L) { "Unexpected FAT32 file size ${loc.fileSize} for $fatPath" }
        val out = ByteArrayOutputStream(loc.fileSize.toInt())
        val clusterBytes = loc.sectorsPerCluster * 512
        var remaining = loc.fileSize
        FileChannel.open(imagePath, StandardOpenOption.READ).use { ch ->
            for (cluster in loc.clusterChain) {
                if (remaining <= 0L) break
                val toRead = minOf(clusterBytes.toLong(), remaining).toInt()
                val buf = ByteBuffer.allocate(toRead)
                val offset = (loc.dataStartLba + (cluster - 2L) * loc.sectorsPerCluster) * 512L
                var done = 0
                while (done < toRead) {
                    val n = ch.read(buf, offset + done)
                    require(n >= 0) { "Unexpected EOF extracting cluster $cluster" }
                    done += n
                }
                out.write(buf.array())
                remaining -= toRead
            }
        }
        val bytes = out.toByteArray()
        require(bytes.size.toLong() == loc.fileSize) { "Extracted ${bytes.size} bytes != expected ${loc.fileSize}" }
        return bytes
    }

    private data class ComponentCapabilities(
        val vglibHasEvfScanout: Boolean,
        val i2cHasLiveEyeSensor: Boolean,
    )

    @Volatile
    private var cachedComponentCaps: ComponentCapabilities? = null

    private fun componentCapabilities(tempRoot: Path): ComponentCapabilities {
        cachedComponentCaps?.let { return it }
        val probeHome = stageComponentHome(tempRoot.resolve("cap-probe"))
        val caps = withComponentHost(probeHome) { owner, host ->
            owner.submit {
                val vglibDesc = host.components().singleOrNull { it.descriptor.id == "soc-vglib-init" }?.descriptor
                val hasEvf = vglibDesc?.settings?.any { it.key == "evfScanout" } == true &&
                    vglibDesc.settings.any { it.key == "evfScanoutFrameLimit" }
                val i2cBusDesc = host.components().singleOrNull { it.descriptor.id == "i2c-bus" }?.descriptor
                val hasLiveEye = i2cBusDesc?.settings?.any { it.key == "eyeSensorNear" && it.live } == true
                ComponentCapabilities(hasEvf, hasLiveEye)
            }.join()
        }
        cachedComponentCaps = caps
        return caps
    }

    private fun applyM4HomeIntegration(workHome: Path, caps: ComponentCapabilities) {
        if (caps.vglibHasEvfScanout) {
            val vglibYaml = workHome.resolve("compoments/soc-vglib-init/soc-vglib-init.yaml")
            if (Files.isRegularFile(vglibYaml)) {
                val vglibCfg = Formats.read(vglibYaml).toMutableMap()
                vglibCfg["evfScanout"] = "lcdc1-pic-yuv-osd0-nearest-v1"
                vglibCfg["evfScanoutFrameLimit"] = 256L
                Formats.atomicYaml(vglibYaml, vglibCfg)
            }
        }
        if (caps.i2cHasLiveEyeSensor) {
            val i2cDir = workHome.resolve("compoments/soc-i2c")
            val i2cYaml = i2cDir.resolve("soc-i2c.yaml")
            if (Files.isRegularFile(i2cYaml)) {
                val i2cCfg = Formats.read(i2cYaml).toMutableMap()
                val inputRel = i2cCfg["inputFile"]?.toString()?.takeIf { it.isNotBlank() } ?: "input.json"
                val i2cInputPath = i2cDir.resolve(inputRel)
                if (Files.isRegularFile(i2cInputPath)) {
                    val i2cData = Formats.readData(i2cInputPath).toMutableMap()
                    @Suppress("UNCHECKED_CAST")
                    val ctrls = (i2cData["controllers"] as? List<Map<String, Any?>>)?.map { row ->
                        if ((row["index"] as? Number)?.toInt() == 1) {
                            row.toMutableMap().apply { this["irq"] = 66 }
                        } else {
                            row
                        }
                    }
                    if (ctrls != null) {
                        i2cData["controllers"] = ctrls
                        val eyeInputName = "input-eye-irq66.json"
                        Formats.writeJson(i2cDir.resolve(eyeInputName), i2cData)
                        i2cCfg["inputFile"] = eyeInputName
                        Formats.atomicYaml(i2cYaml, i2cCfg)
                    }
                }
            }
        }
    }

    fun preparePhotoCardHome(
        tempRoot: Path,
        overrideSdReadOnly: Boolean? = false,
        enableDirectionButtons: Boolean = false,
    ): Pair<Path, Path> {
        require(overrideSdReadOnly != true) { "snap1200k has slot1ReadOnly=false; overrideSdReadOnly=true is forbidden" }
        Files.createDirectories(tempRoot)
        val caps = componentCapabilities(tempRoot)
        val workHome = cloneBringupHome(photoCardHomeDir, tempRoot.resolve("home"), copySdImage = true, overrideSdReadOnly = overrideSdReadOnly)
        val emulatorYaml = workHome.resolve("config/emulator.yaml")
        val cfg = Formats.read(emulatorYaml).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val discovery = (cfg["discovery"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        val existingReads = (discovery["hdmiLinkStatusReads"] as? List<*>).orEmpty()
        require(existingReads.size <= 4096 && existingReads.all { (it as? Number)?.toLong() == 0L }) {
            "Unexpected hdmiLinkStatusReads prefix in $emulatorYaml"
        }
        discovery["hdmiLinkStatusReads"] = List(4096) { 0 }
        cfg["discovery"] = discovery
        Formats.atomicYaml(emulatorYaml, cfg)

        val ppDir = workHome.resolve("compoments/power-peer")
        val origPp = Formats.readData(ppDir.resolve("input.json")).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val origCandidates = (origPp["candidate_reads"] as List<Map<String, Any?>>).map { entry ->
            val row = entry.toMutableMap()
            if ((row["selector"] as? Number)?.toInt() == 3) {
                val vals = (row["values"] as List<*>).map { (it as Number).toLong() }
                require(vals.isNotEmpty()) { "Empty selector 3 values in photo power-peer input.json" }
                val fill = vals.last()
                row["values"] = if (vals.size >= 320) vals else vals + List(320 - vals.size) { fill }
            }
            row
        }
        origPp["candidate_reads"] = origCandidates
        Formats.writeJson(ppDir.resolve("input-320.json"), origPp)
        val ppYaml = ppDir.resolve("power-peer.yaml")
        val ppCfg = Formats.read(ppYaml).toMutableMap()
        ppCfg["inputFile"] = "input-320.json"
        Formats.atomicYaml(ppYaml, ppCfg)

        // Upgrade soc-vglib-init & graphics-fill matching menu-photo3
        val vglibDir = workHome.resolve("compoments/soc-vglib-init")
        val vglibYaml = vglibDir.resolve("soc-vglib-init.yaml")
        val vglibCfg = Formats.read(vglibYaml).toMutableMap()
        vglibCfg["firstScanout"] = "lcdc1-pic-yuv-osd0-nearest-v1"
        vglibCfg["graphicsIrq92Sequence"] = "fill,copy,fill,either*509"
        vglibCfg["frameSyncFollowupTicks"] =
            "581600000,582100000,582600000,583100000,583600000,584100000,584600000,585100000,585600000,586100000,586600000,587100000,589000000,591000000,593000000,595000000,597000000,599000000,601000000,603000000,605000000,607000000,609000000,611000000,613000000,615000000,617000000,619000000,621000000,623000000,625000000,627000000"
        Formats.atomicYaml(vglibYaml, vglibCfg)

        val gfPath = vglibDir.resolve("graphics-fill.yaml")
        val gf = Formats.readData(gfPath).toMutableMap()
        gf["commandExecution"] = "axis-rect-morton-rgba-solid-alpha-v1"
        gf["accessLimit"] = 16384
        gf["commandPairLimit"] = 64
        gf["commandIrq92Tick"] = 605000000L
        gf["commandIrq92Followups"] = listOf(615000000L, 619000000L, 623000000L, 627000000L)
        @Suppress("UNCHECKED_CAST")
        val execMap = (gf["execution"] as Map<String, Any?>).toMutableMap()
        execMap["submissionLimit"] = 513
        execMap["crossSurfaceFills"] = listOf(mapOf("start" to 118382720L, "end" to 118407296L))
        gf["execution"] = execMap
        @Suppress("UNCHECKED_CAST")
        val copyExecMap = (gf["copyExecution"] as Map<String, Any?>).toMutableMap()
        copyExecMap["submissionLimit"] = 513
        copyExecMap["mode2TileProfile"] = "aligned-8x8-1024x768-v1"
        gf["copyExecution"] = copyExecMap
        Formats.writeJson(gfPath, gf)

        // Raise SPI4 (audio16) and SPI5 (panel16) exchange limits so post-576M HUD/menu runs do not exhaust scripted replies
        val spiPortsPath = workHome.resolve("compoments/soc-spi/ports.json")
        if (Files.isRegularFile(spiPortsPath)) {
            val portsData = Formats.readData(spiPortsPath).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val controllers = (portsData["controllers"] as? List<Map<String, Any?>>)?.map { ctrl ->
                val updated = ctrl.toMutableMap()
                when ((updated["index"] as? Number)?.toInt()) {
                    4 -> (updated["audio16Replies"] as? Map<String, Any?>)?.let { audio ->
                        updated["audio16Replies"] = audio.toMutableMap().apply { this["exchangeLimit"] = 16384 }
                    }
                    5 -> (updated["panel16Replies"] as? Map<String, Any?>)?.let { panel ->
                        updated["panel16Replies"] = panel.toMutableMap().apply { this["exchangeLimit"] = 2048 }
                    }
                }
                updated
            }
            if (controllers != null) {
                portsData["controllers"] = controllers
                Formats.writeJson(spiPortsPath, portsData)
            }
        }

        // Extend soc-serial-engine channel 1 status samples for post-580M menu/PLAY runs
        val serialInputPath = workHome.resolve("compoments/soc-serial-engine/input.yaml")
        if (Files.isRegularFile(serialInputPath)) {
            val serialCfg = Formats.read(serialInputPath).toMutableMap()
            if ("resumeChannel1StatusSamples" !in serialCfg && "resumeChannel1StatusSample" !in serialCfg) {
                serialCfg["resumeChannel1StatusSamples"] = List(32) { 0 }
                Formats.atomicYaml(serialInputPath, serialCfg)
            }
        }

        if (enableDirectionButtons) {
            val gpioYaml = workHome.resolve("compoments/soc-gpio/soc-gpio.yaml")
            if (Files.isRegularFile(gpioYaml)) {
                val gpioCfg = Formats.read(gpioYaml).toMutableMap()
                gpioCfg["profile"] = "gfx100s-2.13-gpio-buttons-v3"
                gpioCfg["directionButtonProfile"] = "gfx100s-2.13-direction-input-v1"
                Formats.atomicYaml(gpioYaml, gpioCfg)
            }
            val adcDir = workHome.resolve("compoments/soc-adc")
            val adcYaml = adcDir.resolve("soc-adc.yaml")
            if (Files.isRegularFile(adcYaml)) {
                val adcCfg = Formats.read(adcYaml).toMutableMap()
                adcCfg["directionButtonProfile"] = "gfx100s-2.13-direction-input-v1"
                Formats.atomicYaml(adcYaml, adcCfg)
            }
            val adcInput = adcDir.resolve("input.json")
            if (Files.isRegularFile(adcInput)) {
                val adcData = Formats.readData(adcInput).toMutableMap()
                @Suppress("UNCHECKED_CAST")
                val inputs = (adcData["inputs"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
                inputs["9"] = 4000
                adcData["inputs"] = inputs
                adcData["direction_pressed_samples"] = mapOf(
                    "up" to 2074,
                    "upRight" to 665,
                    "right" to 1568,
                    "downRight" to 1339,
                    "down" to 2823,
                    "downLeft" to 2412,
                    "left" to 3380,
                    "upLeft" to 1809,
                )
                Formats.writeJson(adcInput, adcData)
            }
        }

        applyM4HomeIntegration(workHome, caps)

        val snapManifest = EmulatorSnapshot.readJsonObject(photoCardSnapshotDir.resolve(EmulatorSnapshot.MANIFEST_FILE))
        val uartTailId = "gfx100s.fff3a034.uart-tail.candidate"
        @Suppress("UNCHECKED_CAST")
        val discoverySnap = snapManifest["discovery"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val snapRegions = discoverySnap["regions"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val snapUartTail = snapRegions[uartTailId] as Map<String, Any?>
        val snapBase = (snapUartTail["base"] as Number).toLong()
        val snapEndExclusive = (snapUartTail["endExclusive"] as Number).toLong()

        val workCatalogPath = workHome.resolve("config/catalog.compiled.json")
        @Suppress("UNCHECKED_CAST")
        val env = CatalogIO.json.readValue(CatalogIO.readText(Files.readAllBytes(workCatalogPath)), Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val data = LinkedHashMap(env.getValue("data") as Map<String, Any?>)
        var patched = false
        @Suppress("UNCHECKED_CAST")
        val updatedCandidates = (data.getValue("candidates") as List<Map<String, Any?>>).map { cand ->
            if (cand["id"] == uartTailId && (cand["endExclusive"] as Number).toLong() != snapEndExclusive) {
                require((cand["base"] as Number).toLong() == snapBase) {
                    "Unexpected base for $uartTailId: ${cand["base"]} != $snapBase"
                }
                patched = true
                cand + mapOf("endExclusive" to snapEndExclusive)
            } else {
                cand
            }
        }
        if (patched) {
            data["candidates"] = updatedCandidates
            val newSha = CatalogIO.sha256(CatalogIO.canonical(data))
            val newBytes = CatalogIO.canonical(linkedMapOf("schema" to 1, "payloadSha256" to newSha, "data" to data))
            CatalogIO.readCompiled(newBytes)
            Files.write(workCatalogPath, newBytes)
        }

        @Suppress("UNCHECKED_CAST")
        val memMap = snapManifest["memory"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val receiptMap = memMap["additionalRamReceipt"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val planMap = (receiptMap["plan"] as Map<String, Any?>).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val existingAdditional = (planMap["additionalRegions"] as List<Map<String, Any?>>).toMutableList()
        val existingIds = existingAdditional.map { it["id"].toString() }.toSet()
        val uiAeAdditions = listOf(
            mapOf("id" to "ui-text-scratch-05608000", "address" to 90210304L, "size" to 8192L, "permissions" to 3),
            mapOf("id" to "ui-text-scratch-tail-0560a000", "address" to 90218496L, "size" to 299008L, "permissions" to 3),
            mapOf("id" to "ui-graphics-heap-06fe6000", "address" to 117334016L, "size" to 10485760L, "permissions" to 3),
            mapOf("id" to "t75-ae-2c551000", "address" to 743772160L, "size" to 258048L, "permissions" to 3),
            mapOf("id" to "t75-ae-2c5b4000", "address" to 744177664L, "size" to 8192L, "permissions" to 3),
            mapOf("id" to "lcdc-pic1-liveview-89960000", "address" to 2308308992L, "size" to 2457600L, "permissions" to 3),
        )
        for (region in uiAeAdditions) {
            if (region["id"] !in existingIds) existingAdditional += region
        }
        planMap["additionalRegions"] = existingAdditional
        val ramPlanPath = tempRoot.resolve("photo-extend-ram-plan.json")
        Formats.writeJson(ramPlanPath, planMap)
        return workHome to ramPlanPath
    }

    fun prepareBlankCardHome(
        tempRoot: Path,
        overrideSdReadOnly: Boolean? = false,
    ): Pair<Path, Path> {
        Files.createDirectories(tempRoot)
        val caps = componentCapabilities(tempRoot)
        val workHome = cloneBringupHome(blankCardHomeDir, tempRoot.resolve("home"), copySdImage = true, overrideSdReadOnly = overrideSdReadOnly)

        val snapManifest = EmulatorSnapshot.readJsonObject(blankCardSnapshotDir.resolve(EmulatorSnapshot.MANIFEST_FILE))
        val snapAttempts = (snapManifest["attempts"] as? Number)?.toLong() ?: 630_000_000L
        val followupStart = maxOf(snapAttempts + 1_000_000L, 631_000_000L)
        val followupTicks = (0 until 32).joinToString(",") { idx -> (followupStart + idx * 500_000L).toString() }

        val vglibDir = workHome.resolve("compoments/soc-vglib-init")
        val vglibYaml = vglibDir.resolve("soc-vglib-init.yaml")
        val vglibCfg = Formats.read(vglibYaml).toMutableMap()
        vglibCfg["firstScanout"] = "lcdc1-pic-yuv-osd0-nearest-v1"
        vglibCfg["graphicsIrq92Sequence"] = "fill,copy,fill,copy,either*508"
        vglibCfg["frameSyncFollowupTicks"] = followupTicks
        Formats.atomicYaml(vglibYaml, vglibCfg)

        val gfPath = vglibDir.resolve("graphics-fill.yaml")
        val gf = Formats.readData(gfPath).toMutableMap()
        gf["commandExecution"] = "axis-rect-morton-rgba-solid-alpha-v1"
        gf["commandPairLimit"] = 64
        gf["commandIrq92AutoDelayTicks"] = 1000L
        gf["accessLimit"] = 16384
        @Suppress("UNCHECKED_CAST")
        val execMap = (gf["execution"] as Map<String, Any?>).toMutableMap()
        execMap["submissionLimit"] = 513
        gf["execution"] = execMap
        @Suppress("UNCHECKED_CAST")
        val copyExecMap = (gf["copyExecution"] as Map<String, Any?>).toMutableMap()
        copyExecMap["submissionLimit"] = 513
        copyExecMap["mode2TileProfile"] = "aligned-8x8-1024x768-v1"
        gf["copyExecution"] = copyExecMap
        Formats.writeJson(gfPath, gf)

        val gpioYaml = workHome.resolve("compoments/soc-gpio/soc-gpio.yaml")
        if (Files.isRegularFile(gpioYaml)) {
            val gpioCfg = Formats.read(gpioYaml).toMutableMap()
            gpioCfg["profile"] = "gfx100s-2.13-gpio-buttons-v3"
            gpioCfg["directionButtonProfile"] = "gfx100s-2.13-direction-input-v1"
            Formats.atomicYaml(gpioYaml, gpioCfg)
        }

        applyM4HomeIntegration(workHome, caps)

        @Suppress("UNCHECKED_CAST")
        val memMap = snapManifest["memory"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val receiptMap = memMap["additionalRamReceipt"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val planMap = (receiptMap["plan"] as Map<String, Any?>).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val existingAdditional = (planMap["additionalRegions"] as List<Map<String, Any?>>).toMutableList()
        val existingIds = existingAdditional.map { it["id"].toString() }.toSet()
        val liveviewAdditions = listOf(
            mapOf("id" to "lcdc-pic1-liveview-89960000", "address" to 2308308992L, "size" to 2457600L, "permissions" to 3),
            mapOf("id" to "liveview-dma-ring-1a0ca000", "address" to 437035008L, "size" to 2957312L, "permissions" to 3),
        )
        for (region in liveviewAdditions) {
            if (region["id"] !in existingIds) existingAdditional += region
        }
        planMap["additionalRegions"] = existingAdditional
        val ramPlanPath = tempRoot.resolve("blank-extend-ram-plan.json")
        Formats.writeJson(ramPlanPath, planMap)
        return workHome to ramPlanPath
    }

    fun allocatedDiskBytes(path: Path): Long {
        val proc = ProcessBuilder("stat", "-c", "%b", path.toAbsolutePath().toString()).start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val exit = proc.waitFor()
        check(exit == 0) { "stat -c %b failed (exit=$exit) for $path: $stderr" }
        val blocks = stdout.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toLong()
            ?: error("Unexpected stat -c %b output for $path: $stdout")
        return blocks * 512L
    }

    fun stageDiscoveryBootHome(tempRoot: Path, instructionBudget: Int = 200000, enableBootSgi: Boolean = true): Pair<Path, Map<String, Any>> {
        val fw = requireNotNull(resolveFirmwareFile()) { "Missing firmware file" }
        val lib = requireNotNull(resolveNativeLibrary()) { "Missing native library" }
        val home = stageComponentHome(tempRoot)
        Files.createDirectories(home.resolve("compoments/soc-flash"))
        Formats.atomicYaml(
            home.resolve("compoments/soc-flash/soc-flash.yaml"),
            mapOf("profile" to "dat-readonly-experimental-v1", "imagePort" to "flash.image", "jedec" to 3351072L, "interruptPort" to "soc-gic.lines"),
        )
        Files.createDirectories(home.resolve("compoments/flash-image"))
        Formats.atomicYaml(
            home.resolve("compoments/flash-image/flash-image.yaml"),
            mapOf("sourcePort" to "firmware.update", "tailProfile" to "dat-end-block-ff-experimental-v1", "regionsProfile" to "strict", "regionsFile" to ""),
        )
        Files.createDirectories(home.resolve("compoments/soc-dma"))
        Formats.atomicYaml(
            home.resolve("compoments/soc-dma/soc-dma.yaml"),
            mapOf("softwareProfile" to "software-request44-experimental-v1", "interruptPort" to "soc-gic.lines", "flashPeerPort" to "soc-flash.dma"),
        )
        Files.createDirectories(home.resolve("compoments/system-memory"))
        Formats.atomicYaml(
            home.resolve("compoments/system-memory/system-memory.yaml"),
            mapOf("profile" to "kernel-byte-pool-experimental-v7"),
        )
        // The stock boot program broadcasts SGI 1 to the other CPUs, including in a single-core run.
        Files.createDirectories(home.resolve("compoments/soc-gic"))
        Formats.atomicYaml(
            home.resolve("compoments/soc-gic/soc-gic.yaml"),
            mapOf("sgiProfile" to if (enableBootSgi) "single-core-experimental-v1" else ""),
        )
        val cfg = mapOf(
            "schema" to 1,
            "executionMode" to "discovery",
            "components" to "compoments",
            "httpPort" to 0,
            "firmwareMetadata" to projectRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
            "firmwareFile" to fw.toString(),
            "nativeLibrary" to lib.toString(),
            "instructionBudget" to instructionBudget,
            "enabledDevices" to listOf(
                "soc-gpio",
                "soc-timer",
                "soc-dma",
                "soc-configuration",
                "soc-flash",
                "soc-gic",
                "soc-system-control",
                "soc-keyed-io",
                "soc-method4",
            ),
        )
        return home to cfg
    }

    const val DEFAULT_FORMAT_BUTTON_EVENTS: String =
        "632000000:menu:down,632500000:menu:up,635000000:right:down,635500000:right:up,638000000:menu:down,638500000:menu:up"

    fun runInCameraFormat(
        workHome: Path,
        framesDir: Path,
        savePostFormatSnapshotDir: Path? = null,
        formatDoneAttempts: Long = 655_000_000L,
        extraButtonEvents: String? = null,
        ramPlan: Path? = null,
        timeoutSeconds: Long = 180,
    ): ProcessResult {
        val gpioYaml = workHome.resolve("compoments/soc-gpio/soc-gpio.yaml")
        if (Files.isRegularFile(gpioYaml)) {
            val gpioCfg = Formats.read(gpioYaml).toMutableMap()
            gpioCfg["profile"] = "gfx100s-2.13-gpio-buttons-v3"
            gpioCfg["directionButtonProfile"] = "gfx100s-2.13-direction-input-v1"
            Formats.atomicYaml(gpioYaml, gpioCfg)
        }
        val baseEvents = System.getProperty("fuji.e2e.formatButtonEvents")?.takeIf { it.isNotBlank() } ?: DEFAULT_FORMAT_BUTTON_EVENTS
        val events = if (extraButtonEvents.isNullOrBlank()) baseEvents else "$baseEvents,$extraButtonEvents"
        val budget = if (savePostFormatSnapshotDir != null) {
            (formatDoneAttempts + 1L).toString()
        } else {
            System.getProperty("fuji.e2e.formatBudget")?.takeIf { it.isNotBlank() } ?: "660000000"
        }
        Files.createDirectories(framesDir)
        val args = ArrayList<String>()
        args += listOf(
            "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
            "--button-events", events,
            "--budget", budget,
            "--export-frames-dir", framesDir.toString(),
        )
        if (ramPlan != null) {
            args += listOf("--extend-ram-plan", ramPlan.toString())
        }
        if (savePostFormatSnapshotDir != null) {
            args += listOf(
                "--bound-step-to-save",
                "--save-snapshot-at", "$formatDoneAttempts:$savePostFormatSnapshotDir",
            )
        }
        return runDisplayBringupResume(
            workHome,
            probeSourceCommit(workHome),
            blankCardSnapshotDir,
            args,
            timeoutSeconds = timeoutSeconds,
        )
    }

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65536)
        Files.newInputStream(path).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    fun snapshotDirectoryFileDigests(dir: Path): Map<String, String> {
        val out = sortedMapOf<String, String>()
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                out[dir.relativize(file).toString().replace('\\', '/')] = sha256(file)
            }
        }
        return out
    }

    // ---- Opaque-Box Milestone Readiness Probes ----

    fun probeCmosExposeImplemented(tempRoot: Path): Boolean {
        val home = stageComponentHome(tempRoot)
        val jpegFile = home.resolve("compoments/cmos/probe.jpg")
        Files.createDirectories(jpegFile.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage("PROBE-QR", modulePixels = 4, quietZoneModules = 2), "jpg", jpegFile.toFile())) {
            "Failed to write probe.jpg"
        }
        return withComponentHost(home) { owner, host ->
            owner.submit {
                host.patch("cmos", 0, mapOf("sourceKind" to "JPEG", "sourceFile" to "probe.jpg")).join()
                val sensor = checkNotNull(host.ports.find("cmos", SensorPort::class.java))
                val dst = ByteBuffer.allocate(640 * 480 * 4)
                try {
                    sensor.expose(Exposure(0L, 1000L, 100, LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)), dst) > 0
                } catch (err: IllegalStateException) {
                    if (err.message?.contains("not implemented", ignoreCase = true) == true) false else throw err
                }
            }.join()
        }
    }

    /**
     * Stages a self-contained real-component home (`display`, `soc-vglib-init`, `cmos`, `buttons`,
     * `soc-gpio`, `soc-gic`) configured for `t80-isp-banks-v1` + `lcdc1-pic-yuv-osd0-nearest-v1`
     * live-view compositing so `@Tag("e2e-m2")` tests run deterministically in CI without external
     * firmware snapshots while still exercising real hardware components and HTTP/OSD0/PIC1 paths.
     */
    fun stageCmosLiveViewComponentHome(
        tempRoot: Path,
        initialCmosConfig: Map<String, Any>? = null,
        scanoutFrameLimit: Int = 32,
    ): Path {
        val home = stageComponentHome(tempRoot)
        val vglibDir = Files.createDirectories(home.resolve("compoments/soc-vglib-init"))
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf(
                "profile" to "lcdc-functional-experimental-v1",
                "inputFile" to "sequence.yaml",
                "firstScanout" to "lcdc1-pic-yuv-osd0-nearest-v1",
                "scanoutFrameLimit" to scanoutFrameLimit,
            ),
        )
        Formats.atomicYaml(
            vglibDir.resolve("sequence.yaml"),
            com.github.nyabanban.fuji.emulator.desktop.tools.VglibInitPreparation.input(projectRoot),
        )
        if (initialCmosConfig != null) {
            val cmosDir = Files.createDirectories(home.resolve("compoments/cmos"))
            Formats.atomicYaml(cmosDir.resolve("cmos.yaml"), initialCmosConfig)
        }
        return home
    }

    fun writeOsd0RgbaPixel(osdRam: ByteArray, width: Int, x: Int, y: Int, rgbaWord: Int) {
        val off = (y * width + x) * 4
        osdRam[off] = (rgbaWord and 0xff).toByte()
        osdRam[off + 1] = ((rgbaWord ushr 8) and 0xff).toByte()
        osdRam[off + 2] = ((rgbaWord ushr 16) and 0xff).toByte()
        osdRam[off + 3] = ((rgbaWord ushr 24) and 0xff).toByte()
    }

    fun paintShootingHudOsd0(osdRam: ByteArray, width: Int = 1024, height: Int = 768) {
        osdRam.fill(0)
        val barRgba = 0x2040_E0FF
        val badgeRgba = 0x40A0_2080
        for (y in 0 until 48) {
            for (x in 0 until width) writeOsd0RgbaPixel(osdRam, width, x, y, barRgba)
        }
        for (y in (height - 48) until height) {
            for (x in 0 until width) writeOsd0RgbaPixel(osdRam, width, x, y, barRgba)
        }
        for (y in 80 until 140) {
            for (x in 16 until 96) writeOsd0RgbaPixel(osdRam, width, x, y, badgeRgba)
        }
    }

    fun paintOpaqueMenuOsd0(osdRam: ByteArray, width: Int = 1024, height: Int = 768) {
        val panelRgba = 0x2028_30FF
        val highlightRgba = 0xE0A0_20FF.toInt()
        for (y in 0 until height) {
            for (x in 0 until width) writeOsd0RgbaPixel(osdRam, width, x, y, panelRgba)
        }
        for (y in 120 until 180) {
            for (x in 160 until width - 160) writeOsd0RgbaPixel(osdRam, width, x, y, highlightRgba)
        }
    }

    fun configureLcdc1Composite(
        lcdc1: MmioDevice,
        osdAddr: Long,
        picAddr: Long,
        picStride: Int,
        picY: Int = 96,
        picHeight: Int = 576,
        picWidth: Int = 1024,
        enableOsd0: Boolean = true,
    ) {
        val layerBits = (if (enableOsd0) 0x10000L else 0L) or 0x10L or 0x1000L
        lcdc1.write(0x008, 4, layerBits)
        lcdc1.write(0x030, 4, 0x2008_0001L)
        lcdc1.write(0x040, 4, 0L)
        lcdc1.write(0x044, 4, osdAddr)
        lcdc1.write(0x050, 4, 0x10400L)
        lcdc1.write(0x054, 4, 0x0300_0400L)
        lcdc1.write(0x058, 4, 0L)
        lcdc1.write(0x05c, 4, 0x0300_0400L)
        lcdc1.write(0x060, 4, 0x1000_1000L)
        lcdc1.write(0x160, 4, 0xffL)
        lcdc1.write(0x168, 4, picAddr)
        lcdc1.write(0x16c, 4, (picStride / 8).toLong())
        lcdc1.write(0x170, 4, 0x0300_0400L)
        lcdc1.write(0x174, 4, (picY.toLong() and 0xffffL) shl 16)
        lcdc1.write(0x178, 4, ((picHeight.toLong() and 0xffffL) shl 16) or (picWidth.toLong() and 0xffffL))
        lcdc1.write(0x17c, 4, 0x1000_1000L)
    }

    fun probeEvfPortAndCatalogReady(tempRoot: Path): Boolean {
        val home = stageComponentHome(tempRoot)
        val hasPort = withComponentHost(home) { owner, host ->
            owner.submit { host.ports.find("display.evf", DisplayPort::class.java) != null }.join()
        }
        val panels = PanelCatalog.load()
        val evfControl = panels.flatMap { it.controls }.firstOrNull { it.id == "rear-evf" }
        return hasPort && evfControl?.displayPort == "display.evf"
    }

    fun probeI2cEyeSensorReady(tempRoot: Path): Boolean {
        val controls = PanelCatalog.load().flatMap { it.controls }.associateBy { it.id }
        val eyeCtrl = controls["rear-eye-sensor"] ?: return false
        val compId = eyeCtrl.componentId ?: return false
        val settingKey = eyeCtrl.settingKey ?: return false
        if (eyeCtrl.bindingStatus == "not-connected") return false
        val home = stageComponentHome(tempRoot)
        return withComponentHost(home) { owner, host ->
            owner.submit {
                val loaded = host.components().singleOrNull { it.descriptor.id == compId } ?: return@submit false
                val setting = loaded.descriptor.settings.singleOrNull { it.key == settingKey } ?: return@submit false
                setting.live
            }.join()
        }
    }

    private val convergedProvenanceValues = setOf("DAT", "TEST", "SIM", "SIM_UNDER_HYPOTHESIS", "VERIFIED")

    fun probeCatalogVerifiedCount(): Int {
        val catalog = AddressCatalog.bundled()
        var count = 0
        @Suppress("UNCHECKED_CAST")
        val devices = catalog.data["devices"] as? List<Map<String, Any?>> ?: emptyList()
        for (device in devices) {
            @Suppress("UNCHECKED_CAST")
            val ports = device["ports"] as? List<Map<String, Any?>> ?: emptyList()
            for (port in ports) {
                @Suppress("UNCHECKED_CAST")
                val regs = port["registers"] as? List<Map<String, Any?>> ?: emptyList()
                for (reg in regs) {
                    @Suppress("UNCHECKED_CAST")
                    val prov = reg["provenance"] as? Map<String, Any?> ?: emptyMap()
                    val behaviorConf = (prov["behavior"] as? Map<*, *>)?.get("confidence")?.toString()
                    val hasVerified = behaviorConf in convergedProvenanceValues ||
                        prov.values.any { v -> (v as? Map<*, *>)?.get("confidence") == "VERIFIED" || v == "VERIFIED" }
                    if (hasVerified || reg["status"] == "VERIFIED") count++
                }
            }
        }
        return count
    }

    fun probeM6CatalogConvergedReady(): Boolean {
        if (runCatching { CatalogCompiler.verify(projectRoot) }.isFailure) return false
        val catalog = AddressCatalog.bundled()
        @Suppress("UNCHECKED_CAST")
        val devices = catalog.data["devices"] as? List<Map<String, Any?>> ?: emptyList()
        return devices.size > 33 || probeCatalogVerifiedCount() > 0
    }

    fun resolveBootRomMetadataPath(): Path {
        val bootRomYaml = projectRoot.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml")
        if (Files.isRegularFile(bootRomYaml)) return bootRomYaml
        return projectRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml")
    }

    fun probeBootRomProfileReady(): Boolean {
        val path = resolveBootRomMetadataPath()
        if (!Files.isRegularFile(path)) return false
        val yaml = Formats.read(path)
        val bootMode = yaml["bootMode"]?.toString().orEmpty()
        val profiles = yaml["memoryProfiles"] as? Map<*, *> ?: emptyMap<String, Any?>()
        return bootMode.contains("boot-rom", ignoreCase = true) ||
            profiles.keys.any { it.toString().contains("boot-rom", ignoreCase = true) }
    }

    fun probeSourceCommit(home: Path): String {
        val receipt = home.resolve("bringup-input.generated.json")
        return if (Files.isRegularFile(receipt)) {
            Formats.json.readTree(receipt.toFile()).path("sourceCommit").asText("")
        } else {
            ""
        }
    }

    fun probeLcdcYuvCompositeReady(tempRoot: Path): Boolean {
        val home = stageComponentHome(tempRoot)
        val vglibDir = home.resolve("compoments/soc-vglib-init")
        Files.createDirectories(vglibDir)
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf(
                "profile" to "lcdc-functional-experimental-v1",
                "firstScanout" to "lcdc1-pic-yuv-osd0-nearest-v1",
                "inputFile" to "mode-input.json",
            ),
        )
        Formats.writeJson(
            vglibDir.resolve("mode-input.json"),
            mapOf(
                "schema" to 1,
                "profile" to "vglib-full-lane-init-experimental-v1",
                "readConfidence" to "HYPOTHESIS",
                "transactions" to List(18) { i -> mapOf("offset" to i * 4, "readValue" to 0, "writeValue" to 0) },
            ),
        )
        val osdAddr = 0x06140000L
        val yuvAddr = 0x8999C000L
        val picW = 1024
        val picH = 576
        val picStride = picW * 2
        val osdBuf = ByteArray(1024 * 768 * 4) // transparent black RGBA
        ByteBuffer.wrap(osdBuf).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0xFF0000FF.toInt()) // opaque red at (0,0)
        // UYVY bright white: U=0x80, Y0=0xEB, V=0x80, Y1=0xEB
        val yuvBuf = ByteArray(picStride * picH) { idx -> if (idx % 2 == 0) 0x80.toByte() else 0xEB.toByte() }
        return withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                host.ports.register(
                    "probe-memory",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(!write) { "Unexpected probe DMA write check" }
                            val okOsd = address >= osdAddr && address + size <= osdAddr + osdBuf.size
                            val okYuv = address >= yuvAddr && address + size <= yuvAddr + yuvBuf.size
                            require(okOsd || okYuv) { "Unmapped probe DMA: 0x${address.toString(16)}" }
                        }

                        override fun read(address: Long, destination: ByteBuffer) {
                            val rem = destination.remaining()
                            check(address, rem.toLong(), false)
                            if (address >= yuvAddr) {
                                destination.put(yuvBuf, (address - yuvAddr).toInt(), rem)
                            } else {
                                destination.put(osdBuf, (address - osdAddr).toInt(), rem)
                            }
                        }

                        override fun write(address: Long, source: ByteBuffer) {
                            error("Unexpected probe DMA write")
                        }
                    },
                )
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                lcdc1.write(0x008L, 4, 0x10010L)
                lcdc1.write(0x030L, 4, 0x20080001L)
                lcdc1.write(0x040L, 4, 0L)
                lcdc1.write(0x044L, 4, osdAddr)
                lcdc1.write(0x050L, 4, 0x00010400L)
                lcdc1.write(0x054L, 4, 0x03000400L)
                lcdc1.write(0x058L, 4, 0L)
                lcdc1.write(0x05cL, 4, 0x03000400L)
                lcdc1.write(0x060L, 4, 0x10001000L)
                lcdc1.write(0x160L, 4, 0xFFL)
                lcdc1.write(0x164L, 4, 0L)
                lcdc1.write(0x168L, 4, yuvAddr)
                lcdc1.write(0x16cL, 4, (picStride / 8).toLong())
                lcdc1.write(0x170L, 4, 0x03000400L)
                lcdc1.write(0x174L, 4, 96L shl 16)
                lcdc1.write(0x178L, 4, (picH.toLong() shl 16) or picW.toLong())
                lcdc1.write(0x17cL, 4, 0x10001000L)
                lcdc1.write(0x600L, 4, 1L)
                val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val pixels = IntArray(display.width * display.height)
                if (!display.copyArgb(pixels)) return@submit false
                pixels[0] == 0xFFFF0000.toInt() && nonBlackRatio(pixels) > 0.10
            }.join()
        }
    }

    private val harnessConfigFailureStatusPrefixes = listOf(
        "Graphics fill outside selected physical surface",
        "Unsupported command pair control or lengths",
        "Unqualified mode 2 tile geometry",
        "RAM region",
        "Incompatible",
        "Missing ",
    )

    private fun requireBringupProbeSummary(probeName: String, workHome: Path, res: ProcessResult): JsonNode {
        val summaryFile = workHome.resolve("bringup.summary.json")
        if (!Files.isRegularFile(summaryFile)) {
            throw IllegalStateException(
                "$probeName failed before writing bringup.summary.json " +
                    "(exit=${res.exitCode}, timedOut=${res.timedOut}): ${res.stderr}",
            )
        }
        val summary = Formats.json.readTree(summaryFile.toFile())
        val errorNode = summary.path("error")
        val hasError = !errorNode.isMissingNode && !errorNode.isNull
        val attempts = summary.path("attempts").asLong(0L)
        val status = summary.path("status").asText()
        if (res.exitCode != 0 || hasError || attempts <= 0L) {
            throw IllegalStateException(
                "$probeName crashed or aborted " +
                    "(exit=${res.exitCode}, timedOut=${res.timedOut}, attempts=$attempts, " +
                    "status=$status, error=$errorNode): ${res.stderr}",
            )
        }
        if (status != "instruction-limit" && harnessConfigFailureStatusPrefixes.any { status.startsWith(it) }) {
            throw IllegalStateException(
                "$probeName stopped due to harness configuration mismatch " +
                    "(attempts=$attempts, status=$status): ${res.stderr}",
            )
        }
        return summary
    }

    @Volatile
    private var cachedM3Ready: Boolean? = null

    fun probeM3JpegDecoderReady(tempRoot: Path): Boolean {
        cachedM3Ready?.let { return it }
        if (!Files.isDirectory(photoCardHomeDir) || !Files.isDirectory(photoCardSnapshotDir)) return false
        if (resolveFirmwareFile() == null || resolveNativeLibrary() == null) return false
        val srcSdImg = photoCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        if (!Files.isRegularFile(srcSdImg)) return false
        val commit = probeSourceCommit(photoCardHomeDir)
        if (commit.isBlank()) return false

        val refImg = decodeJpegSubsampled(extractFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG"), 16)
        val (workHome, ramPlan) = preparePhotoCardHome(tempRoot.resolve("m3-probe-home"))
        val framesDir = tempRoot.resolve("m3-probe-frames")
        Files.createDirectories(framesDir)
        val res = runDisplayBringupResume(
            home = workHome,
            sourceCommit = commit,
            snapshotDir = photoCardSnapshotDir,
            args = listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "power-peer,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "581000000:play:down,581500000:play:up",
                "--budget", "589000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        val summary = requireBringupProbeSummary("probeM3JpegDecoderReady", workHome, res)
        val ready = summary.path("status").asText() == "instruction-limit" &&
            Files.list(framesDir).use { stream ->
                stream.filter { it.toString().endsWith(".png") }.anyMatch { png ->
                    val img = readPng(png)
                    val px = toArgbPixels(img)
                    distinctRgbCount(px) > 64 && maxPlayCorrelation(img, refImg) >= 0.65
                }
            }
        cachedM3Ready = ready
        return ready
    }

    private fun runSdHostProtocolCheck(
        tempRoot: Path,
        readOnly: Boolean = false,
        action: (
            router: MmioRouter,
            base: Long,
            ram: ByteArray,
            sdDir: Path,
            adv: (Int) -> Unit,
            issue: (Int, Long, Int, Int) -> Unit,
        ) -> Boolean,
    ): Boolean {
        val home = stageComponentHome(tempRoot)
        val sdDir = home.resolve("compoments/sdcard-reader")
        Files.createDirectories(sdDir)
        Files.write(sdDir.resolve("probe.img"), ByteArray(8 * 1024 * 1024))
        Formats.atomicYaml(
            sdDir.resolve("sdcard-reader.yaml"),
            mapOf("slot1Image" to "probe.img", "slot1Inserted" to true, "slot1ReadOnly" to readOnly, "slot2Inserted" to false),
        )
        val sdHostDir = home.resolve("compoments/soc-sd-host")
        Files.createDirectories(sdHostDir)
        val profile = "gfx100s-sd-host-address-extension-experimental-v7"
        Formats.atomicYaml(sdHostDir.resolve("soc-sd-host.yaml"), mapOf("profile" to profile, "inputFile" to "input.yaml"))
        val cidBytes = byteArrayOf(
            0xfe.toByte(), 0x54, 0x45, 0x53, 0x54, 0x30, 0x30, 0x31,
            0x10, 1, 2, 3, 4, 1, 0xa1.toByte(), 0,
        )
        var crc = 0
        for (b in cidBytes.take(15)) for (bit in 7 downTo 0) {
            val fb = ((b.toInt() ushr bit) and 1) xor ((crc ushr 6) and 1)
            crc = (crc shl 1) and 0x7f
            if (fb != 0) crc = crc xor 9
        }
        cidBytes[15] = ((crc shl 1) or 1).toByte()
        val cidHex = HexFormat.of().formatHex(cidBytes)
        Formats.atomicYaml(
            sdHostDir.resolve("input.yaml"),
            mapOf(
                "schema" to 1,
                "profile" to profile,
                "addressHighBits" to mapOf("0" to 0, "1" to 0),
                "legacy" to mapOf(
                    "schema" to 1,
                    "profile" to "gfx100s-sd-host-phy-word-retention-experimental-v5",
                    "initialRegisters" to (0..1).associate {
                        it.toString() to mapOf("word80" to 0, "control623e" to 0, "mixed6078" to 0, "control403e" to 0, "word60ec" to 0)
                    },
                    "clocks" to listOf("0:0", "0:1", "1:0", "1:1").associateWith {
                        mapOf("initialWord" to if (it.endsWith(":0")) 5 else 0, "bit1Samples" to listOf(1, 1, 1))
                    },
                    "initWords" to (0..1).associate {
                        it.toString() to mapOf("capability6244" to 0, "present4024" to 0, "present6224" to 0, "signal6238" to 0, "block4006" to 0, "host6228" to 0, "host4028" to 0x0f00)
                    },
                ),
                "cards" to mapOf(
                    "0" to mapOf(
                        "basis" to "SYNTHETIC",
                        "capacityBytes" to 8L * 1024 * 1024,
                        "highCapacity" to false,
                        "readOnly" to readOnly,
                        "rca" to 1,
                        "cidHex" to cidHex,
                        "ocrVoltageWindow" to 0x00ff8000L,
                        "supportsHighSpeed" to true,
                    ),
                    "1" to null,
                ),
                "commandTicks" to 2,
                "blockTicks" to 3,
                "resetTicks" to 2,
            ),
        )
        val catalog = AddressCatalog.bundled()
        return withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                val sd = host.components().single { it.descriptor.id == "soc-sd-host" }.component
                val claims = sd.additionalMmioPorts().toSet()
                val windows = catalog.windowsFor(mapOf("soc-sd-host" to profile)).filter {
                    it.componentId == "soc-gic" || (it.componentId == "soc-sd-host" && it.portId in claims)
                }
                val profiles = configuredComponentProfiles(host, windows.map { it.componentId }.toSet())
                val catRt = CatalogRuntime(catalog, "strict", home, emptyMap())
                catRt.bindPorts(
                    windows.map { PortBinding(it.componentId, it.portId, it.base, it.size) },
                    catalog.defaultMemoryProfile,
                    catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),
                    profiles,
                )
                val router = MmioRouter(
                    windows.map {
                        MmioWindow(it.id, it.base, it.size, checkNotNull(host.ports.find(it.portId, MmioDevice::class.java)))
                    },
                )
                val base = 0xfffc4000L
                val ram = ByteArray(0x10000)
                host.ports.register(
                    "probe-sd-mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(address >= 0 && size >= 0 && address + size <= ram.size)
                        }

                        override fun read(address: Long, destination: ByteBuffer) {
                            val rem = destination.remaining()
                            check(address, rem.toLong(), false)
                            destination.put(ram, address.toInt(), rem)
                        }

                        override fun write(address: Long, source: ByteBuffer) {
                            val rem = source.remaining()
                            check(address, rem.toLong(), true)
                            source.get(ram, address.toInt(), rem)
                        }
                    },
                )
                fun adv(n: Int) = repeat(n) {
                    host.advanceTick(host.tick + 1)
                    router.beforeInstruction(host.tick)
                }
                fun issue(idx: Int, arg: Long, flags: Int, mode: Int) {
                    router.write(base + 0x30, 2, 0xffff)
                    router.write(base + 0x32, 2, 0xffff)
                    router.write(base + 8, 2, arg and 0xffff)
                    router.write(base + 10, 2, arg ushr 16)
                    router.write(base + 12, 4, (idx.toLong() shl 24) or (flags.toLong() shl 16) or mode.toLong())
                    adv(16)
                }
                adv(2)
                issue(0, 0L, 0, 0)
                issue(8, 0x1aaL, 0x1a, 0)
                var ready = false
                repeat(8) {
                    if (!ready) {
                        issue(55, 0L, 0x1a, 0)
                        issue(41, 0x40ff8000L, 2, 0)
                        val resp = router.read(base + 0x10, 2) or (router.read(base + 0x12, 2) shl 16)
                        ready = (resp and 0x80000000L) != 0L
                    }
                }
                check(ready) { "Probe SD card failed to become ready" }
                issue(2, 0L, 9, 0)
                issue(3, 0L, 0x1a, 0)
                issue(9, 0x10000L, 9, 0)
                issue(7, 0x10000L, 0x1b, 0)
                issue(16, 512L, 0x1a, 0)
                try {
                    action(router, base, ram, sdDir, ::adv, ::issue)
                } catch (_: Throwable) {
                    false
                }
            }.join()
        }
    }

    fun probeSocJpegPortReady(tempRoot: Path): Boolean {
        val catalog = AddressCatalog.bundled()
        val hasWindow = catalog.windows.any { it.componentId == "soc-jpeg" && it.base == 0xFFFA1000L }
        if (!hasWindow) return false
        val home = stageComponentHome(tempRoot)
        val nonDisabledProfile = withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                val loaded = host.components().singleOrNull { it.descriptor.id == "soc-jpeg" } ?: return@submit null
                val profileSetting = loaded.descriptor.settings.singleOrNull { it.key == "profile" }
                if (profileSetting != null && profileSetting.default.toString().contains("disabled")) {
                    profileSetting.choices.firstOrNull { !it.contains("disabled") }
                } else {
                    ""
                }
            }.join()
        } ?: return false
        if (nonDisabledProfile.isNotEmpty()) {
            val jpegDir = home.resolve("compoments/soc-jpeg")
            Files.createDirectories(jpegDir)
            Formats.atomicYaml(jpegDir.resolve("soc-jpeg.yaml"), mapOf("profile" to nonDisabledProfile))
        }
        return withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                val loaded = host.components().singleOrNull { it.descriptor.id == "soc-jpeg" } ?: return@submit false
                val hasIrq136 = loaded.component.interruptRoutes().any { 136 in it.lines }
                val mmioPorts = loaded.component.additionalMmioPorts()
                val hasMmio = mmioPorts.any { host.ports.find(it, MmioDevice::class.java) != null } ||
                    host.ports.find("soc-jpeg", MmioDevice::class.java) != null ||
                    host.ports.find("soc-jpeg.registers", MmioDevice::class.java) != null
                hasIrq136 && hasMmio
            }.join()
        }
    }

    fun probeUniReszPixelDmaReady(tempRoot: Path): Boolean {
        val catalog = AddressCatalog.bundled()
        val hasComponentWindow = catalog.windows.any { it.base == 0xFFF7F000L && it.componentId != "runtime" }
        return hasComponentWindow && probeM3JpegDecoderReady(tempRoot.resolve("m3"))
    }

    fun probeM5SdFormatProtocolReady(tempRoot: Path): Boolean {
        val sdmaOk = runSdHostProtocolCheck(tempRoot.resolve("cmd25-sdma-auto-cmd12"), readOnly = false) { router, base, ram, sdDir, _, issue ->
            router.write(base + 0x34, 2, 0xfbL)
            if ((router.read(base + 0x24, 4) and 0x80000L) != 0L) return@runSdHostProtocolCheck false

            val multiPattern = ByteArray(1024) { (it * 11 + 3).toByte() }
            multiPattern.copyInto(ram, 0x2000)
            router.write(base + 4, 2, 0x7000L or 512L)
            router.write(base + 6, 2, 2L)
            router.write(base + 0, 4, 0x2000L)
            issue(25, 0L, 0x3a, 0x27) // 193A0027h: CMD25 SDMA + blockCount + Auto CMD12
            if (router.read(base + 6, 2) != 0L || (router.read(base + 0x30, 2) and 2L) != 2L) return@runSdHostProtocolCheck false
            issue(13, 0x10000L, 0x1a, 0)
            if (((router.read(base + 0x10, 2) ushr 9) and 15L) != 4L) return@runSdHostProtocolCheck false

            // Single-block metadata write via CMD25 mode=0x27 (blockCount=1)
            val singlePattern = ByteArray(512) { (it * 13 + 7).toByte() }
            singlePattern.copyInto(ram, 0x3000)
            router.write(base + 4, 2, 0x7000L or 512L)
            router.write(base + 6, 2, 1L)
            router.write(base + 0, 4, 0x3000L)
            issue(25, 1024L, 0x3a, 0x27)
            if (router.read(base + 6, 2) != 0L || (router.read(base + 0x30, 2) and 2L) != 2L) return@runSdHostProtocolCheck false
            issue(13, 0x10000L, 0x1a, 0)
            if (((router.read(base + 0x10, 2) ushr 9) and 15L) != 4L) return@runSdHostProtocolCheck false

            // CMD18 mode=0x37 readback verification
            router.write(base + 4, 2, 0x7000L or 512L)
            router.write(base + 6, 2, 3L)
            router.write(base + 0, 4, 0x4000L)
            issue(18, 0L, 0x3a, 0x37)
            if ((router.read(base + 0x30, 2) and 2L) != 2L) return@runSdHostProtocolCheck false

            val img = Files.readAllBytes(sdDir.resolve("probe.img"))
            img.copyOfRange(0, 1024).contentEquals(multiPattern) &&
                img.copyOfRange(1024, 1536).contentEquals(singlePattern) &&
                ram.copyOfRange(0x4000, 0x4400).contentEquals(multiPattern) &&
                ram.copyOfRange(0x4400, 0x4600).contentEquals(singlePattern)
        }
        if (!sdmaOk) return false

        val pioOk = runSdHostProtocolCheck(tempRoot.resolve("cmd25-pio-auto-cmd12"), readOnly = false) { router, base, ram, sdDir, adv, issue ->
            router.write(base + 0x34, 2, 0xfbL)
            val pioPattern = ByteArray(1024) { (it * 17 + 5).toByte() }
            router.write(base + 4, 2, 0x7000L or 512L)
            router.write(base + 6, 2, 2L)
            router.write(base + 0, 4, 0L)
            issue(25, 2048L, 0x3a, 0x26)
            for (block in 0..1) {
                if ((router.read(base + 0x30, 2) and 0x10L) != 0x10L) return@runSdHostProtocolCheck false
                router.write(base + 0x30, 2, 0x10L)
                for (w in 0 until 128) {
                    val off = block * 512 + w * 4
                    val word = (pioPattern[off].toLong() and 0xffL) or
                        ((pioPattern[off + 1].toLong() and 0xffL) shl 8) or
                        ((pioPattern[off + 2].toLong() and 0xffL) shl 16) or
                        ((pioPattern[off + 3].toLong() and 0xffL) shl 24)
                    router.write(base + 0x20, 4, word)
                }
                adv(4)
            }
            if (router.read(base + 6, 2) != 0L || (router.read(base + 0x30, 2) and 2L) != 2L) return@runSdHostProtocolCheck false
            issue(13, 0x10000L, 0x1a, 0)
            if (((router.read(base + 0x10, 2) ushr 9) and 15L) != 4L) return@runSdHostProtocolCheck false

            router.write(base + 4, 2, 0x7000L or 512L)
            router.write(base + 6, 2, 2L)
            router.write(base + 0, 4, 0x5000L)
            issue(18, 2048L, 0x3a, 0x37)
            if ((router.read(base + 0x30, 2) and 2L) != 2L) return@runSdHostProtocolCheck false

            Files.readAllBytes(sdDir.resolve("probe.img")).copyOfRange(2048, 3072).contentEquals(pioPattern) &&
                ram.copyOfRange(0x5000, 0x5400).contentEquals(pioPattern)
        }
        if (!pioOk) return false

        return runSdHostProtocolCheck(tempRoot.resolve("ro-present-wp"), readOnly = true) { router, base, _, _, _, _ ->
            (router.read(base + 0x24, 4) and 0x80000L) == 0x80000L
        }
    }

    @Volatile
    private var cachedM5FormatReady: Boolean? = null

    fun probeM5InCameraFormatReady(tempRoot: Path): Boolean {
        cachedM5FormatReady?.let { return it }
        if (!probeM5SdFormatProtocolReady(tempRoot.resolve("proto"))) {
            cachedM5FormatReady = false
            return false
        }
        if (!probeM1BlankCardHudReady(tempRoot.resolve("m1"))) {
            cachedM5FormatReady = false
            return false
        }
        val (workHome, ramPlan) = prepareBlankCardHome(tempRoot.resolve("format-home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("format-frames")
        val res = runInCameraFormat(workHome, framesDir, ramPlan = ramPlan)
        val summary = requireBringupProbeSummary("probeM5InCameraFormatReady", workHome, res)
        val ready = summary.path("status").asText() == "instruction-limit" &&
            runCatching {
                val fat = inspectFatFilesystem(workHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img"))
                fat.fsType.startsWith("FAT32") && fat.fat0MediaByte in setOf(0xF8, 0xF0)
            }.getOrDefault(false)
        cachedM5FormatReady = ready
        return ready
    }

    @Volatile
    private var cachedM1PhotoReady: Boolean? = null

    fun probeM1PhotoCardReady(tempRoot: Path): Boolean {
        cachedM1PhotoReady?.let { return it }
        if (!Files.isDirectory(photoCardHomeDir) || !Files.isDirectory(photoCardSnapshotDir)) return false
        if (resolveFirmwareFile() == null || resolveNativeLibrary() == null) return false
        val commit = probeSourceCommit(photoCardHomeDir)
        if (commit.isBlank()) return false
        val (workHome, ramPlan) = preparePhotoCardHome(tempRoot.resolve("m1-photo-probe-home"))
        val framesDir = tempRoot.resolve("m1-photo-probe-frames")
        Files.createDirectories(framesDir)
        val res = runDisplayBringupResume(
            home = workHome,
            sourceCommit = commit,
            snapshotDir = photoCardSnapshotDir,
            args = listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "power-peer,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--budget", "582000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        val summary = requireBringupProbeSummary("probeM1PhotoCardReady", workHome, res)
        val ready = summary.path("status").asText() == "instruction-limit" &&
            summary.path("frontier").isNull &&
            Files.list(framesDir).use { stream ->
                stream.filter { it.toString().endsWith(".png") }.anyMatch { png ->
                    val pixels = toArgbPixels(readPng(png))
                    nonBlackRatio(pixels) > 0.02 && distinctRgbCount(pixels) > 16
                }
            }
        cachedM1PhotoReady = ready
        return ready
    }

    @Volatile
    private var cachedM1CleanPhotoReady: Boolean? = null

    fun probeM1CleanPhotoCrec6Ready(tempRoot: Path): Boolean {
        cachedM1CleanPhotoReady?.let { return it }
        if (!Files.isDirectory(photoCardHomeDir) || !Files.isDirectory(photoCardSnapshotDir)) return false
        if (resolveFirmwareFile() == null || resolveNativeLibrary() == null) return false
        val commit = probeSourceCommit(photoCardHomeDir)
        if (commit.isBlank()) return false
        val (workHome, ramPlan) = preparePhotoCardHome(tempRoot.resolve("m1-clean-photo-home"))
        val framesDir = tempRoot.resolve("m1-clean-photo-frames")
        Files.createDirectories(framesDir)
        val res = runDisplayBringupResume(
            home = workHome,
            sourceCommit = commit,
            snapshotDir = photoCardSnapshotDir,
            args = listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "power-peer,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "581000000:menu:down,581500000:menu:up",
                "--budget", "586000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        val summary = requireBringupProbeSummary("probeM1CleanPhotoCrec6Ready", workHome, res)
        val framesMeta = Formats.json.readTree(workHome.resolve("bringup.frames.json").toFile()).path("frames")
        val preMenu = framesMeta.filter { it.path("attempts").asLong() < 581_000_000L }
            .map { toArgbPixels(readPng(Path.of(it.path("file").asText()))) }
        val postMenu = framesMeta.filter { it.path("attempts").asLong() >= 581_500_000L }
            .map { toArgbPixels(readPng(Path.of(it.path("file").asText()))) }
        val ready = summary.path("status").asText() == "instruction-limit" &&
            summary.path("frontier").isNull &&
            preMenu.any { nonBlackRatio(it) > 0.02 && distinctRgbCount(it) > 16 } &&
            postMenu.any { p -> preMenu.any { b -> changedPixelRatio(b, p) > 0.05 } }
        cachedM1CleanPhotoReady = ready
        return ready
    }

    @Volatile
    private var cachedM1HudReady: Boolean? = null

    fun probeM1BlankCardHudReady(tempRoot: Path): Boolean {
        cachedM1HudReady?.let { return it }
        if (!Files.isDirectory(blankCardHomeDir) || !Files.isDirectory(blankCardSnapshotDir)) return false
        if (resolveFirmwareFile() == null || resolveNativeLibrary() == null) return false
        val commit = probeSourceCommit(blankCardHomeDir)
        if (commit.isBlank()) return false
        val (workHome, ramPlan) = prepareBlankCardHome(tempRoot.resolve("m1-probe-home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("m1-probe-frames")
        Files.createDirectories(framesDir)
        val res = runDisplayBringupResume(
            home = workHome,
            sourceCommit = commit,
            snapshotDir = blankCardSnapshotDir,
            args = listOf(
                "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--budget", "640000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        val summary = requireBringupProbeSummary("probeM1BlankCardHudReady", workHome, res)
        val ready = summary.path("status").asText() == "instruction-limit" &&
            summary.path("frontier").isNull &&
            Files.list(framesDir).use { stream ->
                stream.filter { it.toString().endsWith(".png") }.anyMatch { png ->
                    val pixels = toArgbPixels(readPng(png))
                    nonBlackRatio(pixels) > 0.02 && distinctRgbCount(pixels) > 16
                }
            }
        cachedM1HudReady = ready
        return ready
    }

    @Volatile
    private var cachedM1MenuNavReady: Boolean? = null

    fun probeM1MenuNavReady(tempRoot: Path): Boolean {
        cachedM1MenuNavReady?.let { return it }
        if (resolveFirmwareFile() == null || resolveNativeLibrary() == null) return false
        val ready = if (probeM1CleanPhotoCrec6Ready(tempRoot.resolve("clean-photo"))) {
            val (workHome, ramPlan) = preparePhotoCardHome(
                tempRoot.resolve("m1-menunav-photo-home"),
                enableDirectionButtons = true,
            )
            val framesDir = tempRoot.resolve("m1-menunav-photo-frames")
            Files.createDirectories(framesDir)
            val res = runDisplayBringupResume(
                home = workHome,
                sourceCommit = probeSourceCommit(photoCardHomeDir),
                snapshotDir = photoCardSnapshotDir,
                args = listOf(
                    "--allow-promoted-discovery",
                    "--allow-profile-changes", "power-peer,soc-gpio,soc-adc,soc-vglib-init",
                    "--extend-ram-plan", ramPlan.toString(),
                    "--button-events", "581000000:menu:down,581500000:menu:up,584000000:down:down,584500000:down:up",
                    "--budget", "588000000",
                    "--export-frames-dir", framesDir.toString(),
                ),
                timeoutSeconds = 120,
            )
            val summary = requireBringupProbeSummary("probeM1MenuNavReady-photo", workHome, res)
            val framesMeta = Formats.json.readTree(workHome.resolve("bringup.frames.json").toFile()).path("frames")
            val hud = framesMeta.filter { it.path("attempts").asLong() < 581_000_000L }
                .map { toArgbPixels(readPng(Path.of(it.path("file").asText()))) }
            val menu = framesMeta.filter { it.path("attempts").asLong() in 581_500_000L..583_999_999L }
                .map { toArgbPixels(readPng(Path.of(it.path("file").asText()))) }
            val nav = framesMeta.filter { it.path("attempts").asLong() >= 584_500_000L }
                .map { toArgbPixels(readPng(Path.of(it.path("file").asText()))) }
            summary.path("status").asText() == "instruction-limit" &&
                summary.path("frontier").isNull &&
                hud.isNotEmpty() &&
                menu.any { m -> hud.any { h -> changedPixelRatio(h, m) > 0.05 } } &&
                nav.any { n -> menu.any { m -> changedPixelRatio(m, n) > 0.002 } }
        } else if (probeM1BlankCardHudReady(tempRoot.resolve("blank-hud"))) {
            val (workHome, ramPlan) = prepareBlankCardHome(tempRoot.resolve("m1-menunav-blank-home"), overrideSdReadOnly = false)
            val framesDir = tempRoot.resolve("m1-menunav-blank-frames")
            Files.createDirectories(framesDir)
            val res = runDisplayBringupResume(
                home = workHome,
                sourceCommit = probeSourceCommit(blankCardHomeDir),
                snapshotDir = blankCardSnapshotDir,
                args = listOf(
                    "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                    "--extend-ram-plan", ramPlan.toString(),
                    "--button-events", "632000000:menu:down,632500000:menu:up,635000000:down:down,635500000:down:up",
                    "--budget", "645000000",
                    "--export-frames-dir", framesDir.toString(),
                ),
                timeoutSeconds = 120,
            )
            val summary = requireBringupProbeSummary("probeM1MenuNavReady-blank", workHome, res)
            val framesMeta = Formats.json.readTree(workHome.resolve("bringup.frames.json").toFile()).path("frames")
            val hud = framesMeta.filter { it.path("attempts").asLong() < 632_000_000L }
                .map { toArgbPixels(readPng(Path.of(it.path("file").asText()))) }
            val menu = framesMeta.filter { it.path("attempts").asLong() in 632_500_000L..634_999_999L }
                .map { toArgbPixels(readPng(Path.of(it.path("file").asText()))) }
            val nav = framesMeta.filter { it.path("attempts").asLong() >= 635_500_000L }
                .map { toArgbPixels(readPng(Path.of(it.path("file").asText()))) }
            summary.path("status").asText() == "instruction-limit" &&
                summary.path("frontier").isNull &&
                hud.isNotEmpty() &&
                menu.any { m -> hud.any { h -> changedPixelRatio(h, m) > 0.05 } } &&
                nav.any { n -> menu.any { m -> changedPixelRatio(m, n) > 0.002 } }
        } else {
            false
        }
        cachedM1MenuNavReady = ready
        return ready
    }

    @Volatile
    private var cachedM1BlankCh21Ready: Boolean? = null

    fun probeM1BlankCh21Ready(tempRoot: Path): Boolean {
        cachedM1BlankCh21Ready?.let { return it }
        if (!probeM1BlankCardHudReady(tempRoot.resolve("hud"))) {
            cachedM1BlankCh21Ready = false
            return false
        }
        val (workHome, ramPlan) = prepareBlankCardHome(tempRoot.resolve("m1-ch21-home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("m1-ch21-frames")
        Files.createDirectories(framesDir)
        val res = runDisplayBringupResume(
            home = workHome,
            sourceCommit = probeSourceCommit(blankCardHomeDir),
            snapshotDir = blankCardSnapshotDir,
            args = listOf(
                "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "636000000:menu:down,636500000:menu:up",
                "--budget", "645000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        val summary = requireBringupProbeSummary("probeM1BlankCh21Ready", workHome, res)
        val framesMeta = Formats.json.readTree(workHome.resolve("bringup.frames.json").toFile()).path("frames")
        val preMenu = framesMeta.filter { it.path("attempts").asLong() < 636_000_000L }
            .map { toArgbPixels(readPng(Path.of(it.path("file").asText()))) }
        val postMenu = framesMeta.filter { it.path("attempts").asLong() >= 636_500_000L }
            .map { toArgbPixels(readPng(Path.of(it.path("file").asText()))) }
        val ready = summary.path("status").asText() == "instruction-limit" &&
            summary.path("frontier").isNull &&
            preMenu.any { nonBlackRatio(it) > 0.02 && distinctRgbCount(it) > 16 } &&
            postMenu.any { p -> preMenu.any { b -> changedPixelRatio(b, p) > 0.05 } }
        cachedM1BlankCh21Ready = ready
        return ready
    }

    internal data class VglibScissorFixture(
        val heapBase: Long,
        val targetAddr: Long,
        val vertAddr: Long,
        val fragAddr: Long,
        val vertCountWord: Long,
        val fragCountWord: Long,
        val regions: MutableMap<Long, ByteArray>,
        val targetFrame: ByteArray,
    )

    internal fun buildVglibScissorFixture(
        groupId: Int = 0,
        // x=[128..255] (0x00ff0080), OpenVG bottom-up y=[512..639] (0x027f0200) -> screen rows [128..255]
        scissorWords: List<Int> = listOf(0x00ff0080, 0x027f0200),
        colorAbgr: Int = 0xff0000ff.toInt(),
    ): VglibScissorFixture {
        val heapBase = 0x04000000L
        val targetAddr = 0x01000000L
        val vertAddr = heapBase
        val fragAddr = heapBase + 0x1000L
        val coordsAddr = heapBase + 0x2000L
        val pathAddr = heapBase + 0x2100L

        fun packInts(vararg words: Int): ByteArray =
            ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { words.forEach(::putInt) }.array()

        val coords = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0f); putFloat(768f)
            putFloat(512f); putFloat(768f)
            putFloat(512f); putFloat(256f)
            putFloat(0f); putFloat(256f)
        }.array()
        val path = byteArrayOf(2, 4, 4, 4, 0)
        val vertices = packInts(
            0x85f004, groupId,
            0x5ef00e, 0x3f800000, 0,
            0x63f004, 5,
            0x40f004, 0xc001000,
            0x40f004, 0xc001000,
            0x41f046,
            java.lang.Float.floatToRawIntBits(1.0f), 0, 0,
            0, java.lang.Float.floatToRawIntBits(1.0f), 0,
            0, 0, java.lang.Float.floatToRawIntBits(1.0f),
            0x4f004, coordsAddr.toInt(),
            0x5f004, 0xd0000008.toInt(),
            0x2f004, pathAddr.toInt(),
            0x3f004, 0x10000005,
            0x653004, 0x2100,
            0x66f004, 0x6004,
            0x80f026, 0, 0, 0x200, 0x9601000, 0,
            0x6f004, 1,
            0x85f004, groupId + 1, 0,
        )
        val fragments = packInts(
            0xa6f004, groupId,
            0x1a61004, 1, 0x1a11004, 0, 0x1b01004, 11, 0x1b31004, 0,
            0x1aa1004, 0, 0x1841004, 0, 0x1842004, 0, 0x1a02004, 0,
            0x1a21004, 0, 0x1a4f004, targetAddr.toInt(), 0x1a51004, 1, 0x1842004, 0,
            0x1a74004, 0, 0x11c8004, 0, 0x1401004, 0, 0x14df004, 0, 0x14df004, 0,
            0xc0f00e, scissorWords[0], scissorWords[1],
            0x1001004, 1, 0x1151004, 1, 0x1a04004, 0x10000, 0x1848004, 0,
            0x1841004, 8, 0x1401004, 0, 0x14df004, 0, 0x14df004, 0,
            0x1402004, 0, 0x14df004, 0, 0x14df004, 0, 0x115c004, 0,
            0x185f004, colorAbgr, 0x1863004, 0x100,
            0xa0f02e, 0, 0, 0x200, 0x9601000, 0, 0x1040002,
            0x101004, 1,
            0xa6f004, groupId + 1,
        )
        val targetFrame = ByteArray(1024 * 768 * 4)
        val regions = mutableMapOf(
            vertAddr to vertices,
            fragAddr to fragments,
            coordsAddr to coords,
            pathAddr to path,
            targetAddr to targetFrame,
        )
        return VglibScissorFixture(
            heapBase = heapBase,
            targetAddr = targetAddr,
            vertAddr = vertAddr,
            fragAddr = fragAddr,
            vertCountWord = (vertices.size / 8).toLong(),
            fragCountWord = (fragments.size / 8).toLong(),
            regions = regions,
            targetFrame = targetFrame,
        )
    }

    fun probeVglibScissorReady(tempRoot: Path): Boolean {
        val home = stageComponentHome(tempRoot)
        val vglibDir = home.resolve("compoments/soc-vglib-init")
        Files.createDirectories(vglibDir)
        val fixture = buildVglibScissorFixture(groupId = 0, scissorWords = listOf(0x00ff0080, 0x027f0200))
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf(
                "profile" to "lcdc-functional-experimental-v1",
                "graphicsFillTail" to "graphics-fill-retained-experimental-v2",
                "inputFile" to "mode-input.json",
            ),
        )
        Formats.writeJson(
            vglibDir.resolve("mode-input.json"),
            mapOf(
                "schema" to 1,
                "profile" to "vglib-full-lane-init-experimental-v1",
                "readConfidence" to "HYPOTHESIS",
                "transactions" to List(18) { i -> mapOf("offset" to i * 4, "readValue" to 0, "writeValue" to 0) },
            ),
        )
        Formats.atomicYaml(
            vglibDir.resolve("graphics-fill.yaml"),
            mapOf(
                "profile" to "graphics-fill-retained-experimental-v2",
                "initialWords" to listOf(0, 0, 0, 0),
                "statusReads" to emptyList<Int>(),
                "accessLimit" to 64,
                "copyInitialWords" to List(9) { 0 },
                "copyExecution" to mapOf(
                    "profile" to "same-geometry-shared-ram-copy-experimental-v1",
                    "sourceBase" to fixture.targetAddr,
                    "destinationBase" to 0x02000000L,
                    "surfaceBytes" to 0x300000L,
                    "submissionLimit" to 2,
                    "mode2Workspace" to mapOf("base" to fixture.heapBase, "bytes" to 0x10000L),
                ),
                "commandInitialWords" to List(8) { 0 },
                "commandExecution" to "axis-rect-morton-rgba-solid-alpha-v1",
            ),
        )
        return withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                host.ports.register(
                    "probe-vglib-loc",
                    "cpu.execution-location",
                    ExecutionLocationSource::class.java,
                    ExecutionLocationSource { ExecutionLocation(0, 0x01400000L) },
                )
                host.ports.register(
                    "probe-vglib-mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            val buf = fixture.regions[address]
                            require(buf != null && buf.size.toLong() == size && (!write || address == fixture.targetAddr))
                        }

                        override fun read(address: Long, destination: ByteBuffer) {
                            val rem = destination.remaining()
                            check(address, rem.toLong(), false)
                            destination.put(fixture.regions.getValue(address))
                        }

                        override fun write(address: Long, source: ByteBuffer) {
                            val rem = source.remaining()
                            check(address, rem.toLong(), true)
                            source.get(fixture.regions.getValue(address))
                        }
                    },
                )
                val regs = checkNotNull(host.ports.find("soc-vglib-init.registers", MmioDevice::class.java))
                for (i in 0 until 18) {
                    regs.read(i * 4L, 4)
                    regs.write(i * 4L, 4, 0L)
                }
                regs.write(0x1214L, 4, 0x0100ffffL)
                regs.write(0x1298L, 4, 0L)
                regs.write(0x10d4L, 4, fixture.vertAddr)
                regs.write(0x10dcL, 4, fixture.vertCountWord)
                regs.write(0x10d0L, 4, 1L)
                regs.write(0x10e4L, 4, fixture.fragAddr)
                regs.write(0x10ecL, 4, fixture.fragCountWord)
                regs.write(0x10e0L, 4, 1L)
                val insideOff = 4 * (160 * 1024 + 160)
                val outsideBeforeOff = 4 * (32 * 1024 + 32)
                val outsideAfterOff = 4 * (300 * 1024 + 300)
                val insidePainted = (fixture.targetFrame[insideOff].toInt() and 0xff) == 255
                val outsideClean = fixture.targetFrame[outsideBeforeOff] == 0.toByte() &&
                    fixture.targetFrame[outsideAfterOff] == 0.toByte()
                insidePainted && outsideClean
            }.join()
        }
    }
}

package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster
import com.github.nyabanban.fuji.emulator.cpu.ResetSchedule
import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.OwnerLoop
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/**
 * Full-SoC hardware-fidelity test booting Wave-5 Complete Lite Windows XP SP1
 * (`fuji-gfx100s-winxp`: `ntldr.img` + `gfx100s.dtb` + `winxp_initrd.img`) inside a full
 * 4-core GFX100S [FirmwareSession] with `armv7-full-vectors-v1`, 4-core `soc-gic`, real
 * byte-mode `soc-i2c` (`I2C2 @ 0x55` capacitive touch), strict `LCDC1` `bit 12` active output
 * gate, retail-gated `LCDC0` (`0xFFF7A000`) & `UART0` (`0xFFF3A000`), and `soc-timer`
 * `Ch6`/`Ch7` write-mask observability.
 *
 * Verifies:
 *  1. Wave-5 WXRD initrd contains multi-`hbin` NT 5.1 binary `regf` hives (`SYSTEM`, `SOFTWARE`,
 *     `SAM`, `SECURITY`, `DEFAULT`), `nt_session` daemons (`smss`/`csrss`/`winlogon`/`services`/
 *     `lsass`/`svchost`), authentic pure-ARM32 (`0x01C0`) `C:\WINDOWS\explorer.exe` + Luna
 *     visual style + dynamic `StartMenu` `.lnk` shortcuts, and Dual-Arch PE32 Minesweeper
 *     (`winmine_arm.exe` `0x01C0` + `winmine_x86.exe` `0x014C`).
 *  2. Cold-boot to NT 5.1 `KUSER_SHARED_DATA` (`5.1`), live `nt_session` boot chain over multi-
 *     `hbin` hives (`g_w5_session_ready == 1`, `services >= 10`, `svchosts >= 2`), and authentic
 *     1024x768 Windows XP Luna Desktop on `display.main`.
 *  3. Live `I2C2 @ 0x55` capacitive touch through `soc-i2c` opening the authentic Start Menu and
 *     launching BOTH native pure-ARM32 (`0x01C0`) `winmine_arm.exe` AND BanBan Rosetta x86
 *     (`0x014C`) `winmine_x86.exe` simultaneously side-by-side!
 */
class BanbanWinXpFidelityTest {
    private val project = Path.of(System.getProperty("componentProjectRoot"))

    private data class WxrdEntry(val path: String, val payload: ByteArray)

    private fun nativeLibrary(): Path {
        val candidate = System.getenv("FUJI_PREFETCH_ABORT_LIBRARY")
            ?: System.getenv("FUJI_DYNARMIC_LIBRARY")
            ?: System.getenv("FUJI_NATIVE_LINUX_CACHE")
        return Path.of(checkNotNull(candidate) { "A Unicorn native library env var must be configured" })
    }

    private fun findWinXpArtifactsDir(): Path? {
        val env = System.getenv("FUJI_WINXP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val candidates = listOfNotNull(
            env,
            project.parent?.resolve("fuji-gfx100s-winxp"),
            project.parent?.parent?.resolve("fuji-gfx100s-winxp"),
        )
        return candidates.firstOrNull { dir ->
            Files.isRegularFile(dir.resolve("build/arm32/ntldr.img")) &&
                Files.isRegularFile(dir.resolve("build/arm32/gfx100s.dtb")) &&
                Files.isRegularFile(dir.resolve("build/arm32/winxp_initrd.img"))
        }
    }

    private fun parseWxrdEntries(initrdBytes: ByteArray): Map<String, WxrdEntry> {
        val bb = ByteBuffer.wrap(initrdBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x44525857, bb.getInt(0), "Invalid WXRD magic in winxp_initrd.img")
        val count = bb.getInt(8)
        assertTrue(count >= 90, "Expected complete Wave-5 WXRD initrd (>=90 files), got $count")
        val out = LinkedHashMap<String, WxrdEntry>(count)
        for (i in 0 until count) {
            val base = 16 + i * 80
            var end = base
            while (end < base + 64 && initrdBytes[end] != 0.toByte()) end++
            val path = String(initrdBytes, base, end - base, StandardCharsets.US_ASCII)
            val off = bb.getInt(base + 64)
            val sz = bb.getInt(base + 68)
            val payload = initrdBytes.copyOfRange(off, off + sz)
            out[path.lowercase()] = WxrdEntry(path, payload)
        }
        return out
    }

    private fun countRegfHbins(hive: ByteArray): Int {
        assertTrue(hive.size >= 8192, "Hive too small: ${hive.size}")
        assertEquals("regf", String(hive, 0, 4, StandardCharsets.US_ASCII), "Missing regf hive header")
        val bb = ByteBuffer.wrap(hive).order(ByteOrder.LITTLE_ENDIAN)
        var off = 4096
        var hbins = 0
        while (off + 32 <= hive.size) {
            if (String(hive, off, 4, StandardCharsets.US_ASCII) != "hbin") break
            val hbinSize = bb.getInt(off + 8)
            assertTrue(hbinSize >= 4096 && (hbinSize and 0xfff) == 0, "Invalid hbin size $hbinSize at $off")
            hbins++
            off += hbinSize
        }
        return hbins
    }

    private fun peMachine(pe: ByteArray): Int {
        assertTrue(pe.size >= 0x100, "PE binary too small: ${pe.size}")
        assertEquals('M'.code.toByte(), pe[0], "Missing MZ signature")
        assertEquals('Z'.code.toByte(), pe[1], "Missing MZ signature")
        val bb = ByteBuffer.wrap(pe).order(ByteOrder.LITTLE_ENDIAN)
        val peOff = bb.getInt(0x3c)
        assertEquals("PE\u0000\u0000", String(pe, peOff, 4, StandardCharsets.US_ASCII), "Missing PE\\0\\0 signature")
        return bb.getShort(peOff + 4).toInt() and 0xffff
    }

    /** Minimal ELF32 symbol-table reader for `build/arm32/ntoskrnl.elf`. */
    private fun readElf32Symbols(elfPath: Path): Map<String, Long> {
        val raw = Files.readAllBytes(elfPath)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x464c457f, bb.getInt(0), "Invalid ELF magic in $elfPath")
        val shoff = bb.getInt(32)
        val shentsize = bb.getShort(46).toInt() and 0xffff
        val shnum = bb.getShort(48).toInt() and 0xffff
        val syms = HashMap<String, Long>()
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            val shType = bb.getInt(sh + 4)
            if (shType != 2) continue // SHT_SYMTAB
            val symOff = bb.getInt(sh + 16)
            val symSize = bb.getInt(sh + 20)
            val strIdx = bb.getInt(sh + 24)
            val entSize = bb.getInt(sh + 36)
            val strSh = shoff + strIdx * shentsize
            val strOff = bb.getInt(strSh + 16)
            val strSize = bb.getInt(strSh + 20)
            var pos = symOff
            while (pos + entSize <= symOff + symSize) {
                val nameIdx = bb.getInt(pos)
                val value = bb.getInt(pos + 4).toLong() and 0xffff_ffffL
                if (nameIdx > 0 && nameIdx < strSize) {
                    var end = strOff + nameIdx
                    while (end < strOff + strSize && raw[end] != 0.toByte()) end++
                    val name = String(raw, strOff + nameIdx, end - (strOff + nameIdx), StandardCharsets.US_ASCII)
                    if (name.isNotEmpty()) syms[name] = value
                }
                pos += entSize
            }
        }
        return syms
    }

    private fun patchDtbInitrdEnd(dtb: ByteArray, initrdAddr: Int, initrdSize: Int): ByteArray {
        val out = dtb.copyOf()
        val marker = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(initrdAddr).array()
        fun findFrom(start: Int): Int {
            outer@ for (i in start..(out.size - 4)) {
                for (j in 0 until 4) {
                    if (out[i + j] != marker[j]) continue@outer
                }
                return i
            }
            return -1
        }
        val first = findFrom(0)
        val second = if (first >= 0) findFrom(first + 4) else -1
        if (second >= 0) {
            ByteBuffer.wrap(out, second, 4).order(ByteOrder.BIG_ENDIAN).putInt(initrdAddr + initrdSize)
        }
        return out
    }

    private fun frameHashHex(pixels: IntArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bb = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (px in pixels) bb.putInt(px)
        return digest.digest(bb.array()).take(8).joinToString("") { "%02x".format(it) }
    }

    /** Clean NACK peer for unused I2C0 / I2C1 buses (e.g. EVF eye sensor probe). */
    private class EmptyNackI2cBusPort : I2cBusPort {
        override fun connect(owner: String): I2cConnection = object : I2cConnection {
            override fun address(byte: Int): Boolean = false
            override fun checkWrite(byte: Int) {}
            override fun write(byte: Int) {}
            override fun peekRead(): Int = 0xff
            override fun consumeRead(expected: Int) {}
            override fun stop() {}
            override fun close() {}
        }
    }

    /**
     * Scripted `I2C2 @ 0x55` capacitive touch peer implementing the exact 22-byte packet protocol
     * polled by `fuji_touch_poll_i2c()` (`reg 0x00`, `count + 5*(id, xyHi, xLo, yLo)`).
     */
    private class ScriptedWinXpTouchI2cBusPort : I2cBusPort {
        private val queue = ArrayDeque<ByteArray>()
        var pollCount: Int = 0
            private set

        private fun encodePacket(x: Int?, y: Int?): ByteArray {
            val pkt = ByteArray(22)
            if (x != null && y != null) {
                pkt[0] = 1
                pkt[1] = 0
                pkt[2] = (((x ushr 8) and 0x0f) shl 4 or ((y ushr 8) and 0x0f)).toByte()
                pkt[3] = (x and 0xff).toByte()
                pkt[4] = (y and 0xff).toByte()
            }
            return pkt
        }

        fun enqueueTap(x: Int, y: Int) {
            queue.addLast(encodePacket(x, y))
            queue.addLast(encodePacket(null, null))
        }

        fun pendingPackets(): Int = queue.size

        override fun connect(owner: String): I2cConnection = object : I2cConnection {
            private var activePkt = ByteArray(22)
            private var rxIdx = 0

            override fun address(byte: Int): Boolean {
                val addr7 = (byte ushr 1) and 0x7f
                if (addr7 != 0x55) return false
                val isRead = (byte and 1) != 0
                if (isRead) {
                    pollCount++
                    activePkt = if (queue.isNotEmpty()) queue.removeFirst() else ByteArray(22)
                    rxIdx = 0
                }
                return true
            }

            override fun checkWrite(byte: Int) {}
            override fun write(byte: Int) {}
            override fun peekRead(): Int = if (rxIdx < activePkt.size) (activePkt[rxIdx].toInt() and 0xff) else 0
            override fun consumeRead(expected: Int) {
                if (rxIdx < activePkt.size) rxIdx++
            }
            override fun stop() { rxIdx = 0 }
            override fun close() {}
        }
    }

    private fun stageWinXpSessionHome(home: Path): AddressCatalog {
        val compDir = home.resolve("compoments")
        Files.createDirectories(compDir)
        Files.list(project.resolve("build/emulator/compoments")).use { paths ->
            paths.filter { it.toString().endsWith(".jar") }
                .forEach { Files.copy(it, compDir.resolve(it.fileName)) }
        }
        val catalog = AddressCatalog.load(project.resolve("catalog/compiled/catalog.json"))
        val meta = catalog.firmwareMetadata(mapOf("soc-vglib-init" to "lcdc-functional-experimental-v1"))
        Formats.atomicYaml(home.resolve("gfx100s-213.yaml"), meta.mapValues { requireNotNull(it.value) })

        Formats.atomicYaml(
            compDir.resolve("display/display.yaml"),
            mapOf("width" to 1024L, "height" to 768L, "evfWidth" to 1024L, "evfHeight" to 768L),
        )
        Formats.atomicYaml(
            compDir.resolve("soc-gic/soc-gic.yaml"),
            mapOf(
                "profile" to "gicv2-catalog-experimental-v1",
                "coreProfile" to DynarmicCluster.PROFILE,
            ),
        )
        Formats.atomicYaml(
            compDir.resolve("system-memory/system-memory.yaml"),
            mapOf(
                "profile" to "linux-contiguous-256m-experimental-v1",
                "additionalRamProfile" to AdditionalRamRegions.EXEC_CAPABLE_PROFILE,
            ),
        )
        val vglibDir = compDir.resolve("soc-vglib-init")
        Files.createDirectories(vglibDir)
        Files.writeString(
            vglibDir.resolve("vglib-input.json"),
            Formats.jsonText(
                mapOf(
                    "schema" to 1,
                    "profile" to "vglib-full-lane-init-experimental-v1",
                    "readConfidence" to "HYPOTHESIS",
                    "transactions" to (0 until 18).map { i ->
                        mapOf("offset" to (i * 4L), "readValue" to 0L, "writeValue" to 0L)
                    },
                ),
            ),
        )
        Files.writeString(
            vglibDir.resolve("mode-word.json"),
            Formats.jsonText(
                mapOf(
                    "schema" to 1,
                    "profile" to "vglib-mode-word-experimental-v1",
                    "readConfidence" to "HYPOTHESIS",
                    "readSample" to 0x0000_0200L,
                    "sourcePreserveMask" to 0xffff_fcffL,
                ),
            ),
        )
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf(
                "profile" to "lcdc-functional-experimental-v1",
                "inputFile" to "vglib-input.json",
                "controlWordInputFile" to "mode-word.json",
                "firstScanout" to "lcdc1-pic-yuv-osd0-nearest-v1",
                "scanoutFrameLimit" to 256L,
                "continuousScanout" to true,
                "strictActiveOutputGate" to true,
                "lcdc0ClockGated" to true,
                "unclockedAccessPolicy" to "abort",
            ),
        )
        val timerDir = compDir.resolve("soc-timer")
        Files.createDirectories(timerDir)
        Files.copy(
            project.resolve("configurations/jvm/experiments/timer6-63-timer7-64.json"),
            timerDir.resolve("timer6-7.json"),
        )
        Formats.atomicYaml(
            timerDir.resolve("soc-timer.yaml"),
            mapOf(
                "profile" to "interval-edge-routing-experimental-v1",
                "inputFile" to "timer6-7.json",
                "irqSink" to "soc-gic.lines",
            ),
        )
        val i2cDir = compDir.resolve("soc-i2c")
        Files.createDirectories(i2cDir)
        Files.writeString(
            i2cDir.resolve("input.json"),
            Formats.jsonText(
                mapOf(
                    "profile" to "byte-i2c-functional-experimental-v1",
                    "controllers" to listOf(
                        mapOf(
                            "index" to 0,
                            "irq" to 510,
                            "interruptPort" to "soc-gic.lines",
                            "busPort" to "winxp-touch.bus0",
                            "byte_ticks" to 8,
                            "reset" to mapOf("ccr" to 0, "adr" to 0, "csr" to 0, "fsr" to 0),
                        ),
                        mapOf(
                            "index" to 1,
                            "irq" to 66,
                            "interruptPort" to "soc-gic.lines",
                            "busPort" to "winxp-touch.bus1",
                            "byte_ticks" to 8,
                            "reset" to mapOf("ccr" to 0, "adr" to 0, "csr" to 0, "fsr" to 0),
                        ),
                        mapOf(
                            "index" to 2,
                            "irq" to 67,
                            "interruptPort" to "soc-gic.lines",
                            "busPort" to "winxp-touch.bus2",
                            "byte_ticks" to 8,
                            "reset" to mapOf("ccr" to 0, "adr" to 0, "csr" to 0, "fsr" to 0),
                        ),
                    ),
                ),
            ),
        )
        Formats.atomicYaml(
            i2cDir.resolve("soc-i2c.yaml"),
            mapOf(
                "profile" to "byte-i2c-functional-experimental-v1",
                "inputFile" to "input.json",
            ),
        )
        Formats.atomicYaml(
            compDir.resolve("soc-uart/soc-uart.yaml"),
            mapOf(
                "profile" to "pl011-tx-ready-experimental-v1",
                "initialFill" to 0L,
                "initiallyClocked" to false,
                "unclockedAccessPolicy" to "abort",
            ),
        )
        return catalog
    }

    @Test
    fun wave5InitrdContainsMultiHbinHivesNtSessionExplorerAndDualArchWinMine() {
        val winXpDir = findWinXpArtifactsDir()
        assumeTrue(winXpDir != null, "Sibling fuji-gfx100s-winxp build/arm32 artifacts not present in this environment")
        val dir = checkNotNull(winXpDir)
        val entries = parseWxrdEntries(Files.readAllBytes(dir.resolve("build/arm32/winxp_initrd.img")))

        // 1. Multi-hbin NT 5.1 binary regf hives
        val sysHbins = countRegfHbins(checkNotNull(entries["windows/system32/config/system"]).payload)
        val swHbins = countRegfHbins(checkNotNull(entries["windows/system32/config/software"]).payload)
        val defHbins = countRegfHbins(checkNotNull(entries["windows/system32/config/default"]).payload)
        val samHbins = countRegfHbins(checkNotNull(entries["windows/system32/config/sam"]).payload)
        val secHbins = countRegfHbins(checkNotNull(entries["windows/system32/config/security"]).payload)
        assertTrue(sysHbins >= 2, "Expected multi-hbin SYSTEM regf hive, got $sysHbins hbin(s)")
        assertTrue(swHbins >= 2, "Expected multi-hbin SOFTWARE regf hive, got $swHbins hbin(s)")
        assertTrue(defHbins >= 2, "Expected multi-hbin DEFAULT regf hive, got $defHbins hbin(s)")
        assertTrue(samHbins >= 1 && secHbins >= 1, "Expected SAM and SECURITY regf hives")

        // 2. Authentic opensourcexp explorer.exe (pure ARM32 0x01C0), Luna visual style & Start Menu *.lnk
        val explorerPe = checkNotNull(entries["windows/explorer.exe"]).payload
        assertEquals(0x01c0, peMachine(explorerPe), "C:\\WINDOWS\\explorer.exe must be pure ARM32 PE32 (0x01C0)")
        assertNotNull(entries["windows/resources/themes/luna/luna.msstyles"], "Missing luna.msstyles")
        assertNotNull(entries["windows/win.ini"], "Missing C:\\WINDOWS\\win.ini")
        assertNotNull(entries["windows/system.ini"], "Missing C:\\WINDOWS\\system.ini")
        val lnkCount = entries.keys.count { it.startsWith("startmenu/") && it.endsWith(".lnk") }
        assertTrue(lnkCount >= 8, "Expected >=8 dynamic StartMenu/*.lnk descriptors, got $lnkCount")

        // 3. NT 5.1 nt_session subsystem daemons (smss/csrss/winlogon/services/lsass/svchost)
        for (daemon in listOf("smss.exe", "csrss.exe", "winlogon.exe", "services.exe", "lsass.exe", "svchost.exe")) {
            val bin = checkNotNull(entries["windows/system32/$daemon"]) { "Missing nt_session binary $daemon" }.payload
            val mach = peMachine(bin)
            assertTrue(mach == 0x014c || mach == 0x01c0, "Unexpected PE machine 0x${mach.toString(16)} for $daemon")
        }

        // 4. Dual-Arch PE32 Minesweeper (Native ARM32 0x01C0 + Rosetta x86 0x014C) + XP Suite
        assertEquals(
            0x01c0,
            peMachine(checkNotNull(entries["windows/system32/winmine_arm.exe"]).payload),
            "winmine_arm.exe must be ARM32 PE32 (0x01C0)",
        )
        assertEquals(
            0x014c,
            peMachine(checkNotNull(entries["program files/winmine/winmine_x86.exe"]).payload),
            "winmine_x86.exe must be x86 PE32 (0x014C)",
        )
        assertNotNull(entries["windows/system32/control.exe"], "Missing control.exe")
        assertNotNull(entries["windows/system32/notepad.exe"], "Missing notepad.exe")
        assertNotNull(entries["windows/system32/calc.exe"], "Missing calc.exe")
    }

    @Test
    fun realCompiledArm32WinXpBootsTo1024x768LunaDesktopUnderFullSocFidelityGuards() {
        val winXpDir = findWinXpArtifactsDir()
        assumeTrue(winXpDir != null, "Sibling fuji-gfx100s-winxp build/arm32 artifacts not present in this environment")
        val dir = checkNotNull(winXpDir)
        val elfPath = dir.resolve("build/arm32/ntoskrnl.elf")
        assumeTrue(Files.isRegularFile(elfPath), "Sibling fuji-gfx100s-winxp build/arm32/ntoskrnl.elf not present")
        val syms = readElf32Symbols(elfPath)
        val lib = nativeLibrary()
        val home = Files.createTempDirectory("banban-winxp-fidelity-")
        try {
            val catalog = stageWinXpSessionHome(home)
            val ntldrPath = dir.resolve("build/arm32/ntldr.img")
            val dtbBytes = Files.readAllBytes(dir.resolve("build/arm32/gfx100s.dtb"))
            val initrdPath = dir.resolve("build/arm32/winxp_initrd.img")
            val initrdSize = Files.size(initrdPath).toInt()
            val patchedDtb = patchDtbInitrdEnd(dtbBytes, 0x0310_0000, initrdSize)

            Files.copy(ntldrPath, home.resolve("ntldr.img"))
            Files.write(home.resolve("gfx100s.dtb"), patchedDtb)
            Files.copy(initrdPath, home.resolve("winxp_initrd.img"))

            val config = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to "gfx100s-213.yaml",
                "nativeLibrary" to lib.toAbsolutePath().toString(),
                "instructionBudget" to 1_350_000_000L,
                "enabledDevices" to listOf("soc-gic", "soc-timer", "soc-vglib-init", "soc-uart", "soc-i2c"),
                "cbarInput" to 0xffff_0000L,
                "clusterProfile" to DynarmicCluster.PROFILE,
                "clusterReset" to mapOf(
                    "profile" to ResetSchedule.PROFILE,
                    "releaseTicks" to listOf(0L, 10_000_000L, 10_000_000L, 10_000_000L),
                ),
                "mmuProfile" to "arm-short-descriptor-native-experimental-v1",
                "exceptionProfile" to "armv7-full-vectors-v1",
                "customRawImage" to mapOf(
                    "file" to "ntldr.img",
                    "loadAddress" to "0x02800000",
                    "entry" to "0x02800000",
                    "dtbFile" to "gfx100s.dtb",
                    "dtbAddress" to "0x03000000",
                    "initrdFile" to "winxp_initrd.img",
                    "initrdAddress" to "0x03100000",
                ),
            )
            validateLaunchConfiguration(config)

            val baseProfile = "linux-contiguous-256m-experimental-v1"
            val baseRegions = com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata(
                Formats.read(home.resolve("gfx100s-213.yaml")),
            ).memoryProfiles.getValue(baseProfile)
            // Fill the 4 gaps between segment-5 (0x03bf3000) and 0x06000000 so Wave-5 WinXP
            // BSS_RAM (0x04000000..0x05373000), stacks (0x05373000..0x0539B000), and Rear LCDC1
            // framebuffer (0x059ED000..0x05CED000) are contiguously mapped across [0x02000000..0x06000000).
            val winXpRamPlan = AdditionalRamPlan(
                profile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE,
                sourceProfile = "winxp-contiguous-ddr-v1",
                sourceCommit = "0".repeat(40),
                catalogSha256 = catalog.artifactSha256,
                baseProfile = baseProfile,
                baseRegions = baseRegions,
                additionalRegions = listOf(
                    MemoryRegion("winxp-bss-stack-03bf3000", 0x03bf_3000L, 0x0120_d000L, 7),
                    MemoryRegion("winxp-gap-04e03000", 0x04e0_3000L, 0x0000_1000L, 3),
                    MemoryRegion("winxp-gap-05608000", 0x0560_8000L, 0x0029_7000L, 3),
                    MemoryRegion("winxp-lcdc1-fb-05901000", 0x0590_1000L, 0x006f_f000L, 3),
                ),
            )

            val touchPeer = ScriptedWinXpTouchI2cBusPort()
            OwnerLoop().use { owner ->
                val host = ComponentHost(owner, home.resolve("compoments"))
                try {
                    owner.submit {
                        host.ports.register("winxp-touch", "winxp-touch.bus0", I2cBusPort::class.java, EmptyNackI2cBusPort())
                        host.ports.register("winxp-touch", "winxp-touch.bus1", I2cBusPort::class.java, EmptyNackI2cBusPort())
                        host.ports.register("winxp-touch", "winxp-touch.bus2", I2cBusPort::class.java, touchPeer)
                        host.load()

                        // Seed stock RTOS live state prior to BANBANINJ -> ntldr.img handoff:
                        //   LCDC1 in live view (0x008 = 0x00011010, 0x030 = 0x20080001)
                        //   soc-timer Ch6 (display) + Ch7 (ThreadX tick) active with edge-triggered GIC SPI 63/64
                        val gicd = checkNotNull(host.ports.find("soc-gic.distributor", MmioDevice::class.java))
                        gicd.write(0xc0cL, 4, 0x8000_0000L) // IRQ 63 edge-triggered
                        gicd.write(0xc10L, 4, 0x0000_0002L) // IRQ 64 edge-triggered
                        val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                        lcdc1.write(0x008, 4, 0x0001_1010L)
                        lcdc1.write(0x030, 4, 0x2008_0001L)
                        val timer = checkNotNull(host.ports.find("soc-timer", MmioDevice::class.java))
                        timer.write(0x40, 4, 500_000L)
                        timer.write(0x44, 4, 500_000L)
                        timer.write(0x48, 4, 500_000L)
                        timer.write(0x4c, 4, 500_000L)
                        timer.write(0x00, 4, 0x00c0_00c0L)

                        FirmwareSession(host, home, config).use { session ->
                            session.initialize()
                            session.selectAdditionalRam(winXpRamPlan)

                            fun readU32Sym(name: String): Long {
                                val addr = checkNotNull(syms[name]) { "Missing symbol $name in ntoskrnl.elf" }
                                return ByteBuffer.wrap(session.memory(addr, 4))
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .int.toLong() and 0xffff_ffffL
                            }

                            val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                            assertEquals(1024, display.width)
                            assertEquals(768, display.height)
                            val pixels = IntArray(1024 * 768)

                            var lunaReached = false
                            var nonBlackCount = 0
                            var opaqueCount = 0
                            var uniqueColors = 0
                            var lastSeq = 0L
                            var bootFrameHash = ""
                            var presentsAtBoot = 0L
                            var touchEnqueued = false
                            var dualWinMineRendered = false
                            var dualFrameHash = ""

                            while (session.step()) {
                                val seq = display.sequence
                                if (seq > lastSeq) {
                                    lastSeq = seq
                                    if (display.copyArgb(pixels)) {
                                        var nb = 0
                                        var op = 0
                                        val colors = HashSet<Int>(512)
                                        for (px in pixels) {
                                            if ((px ushr 24) == 0xff) op++
                                            val rgb = px and 0x00ffffff
                                            if (rgb != 0) nb++
                                            if (colors.size < 256) colors.add(rgb)
                                        }
                                        nonBlackCount = nb
                                        opaqueCount = op
                                        uniqueColors = colors.size
                                        if (!lunaReached &&
                                            readU32Sym("g_full_os_ready") == 1L &&
                                            nb > 700_000 &&
                                            op == 1024 * 768 &&
                                            colors.size >= 30
                                        ) {
                                            lunaReached = true
                                            bootFrameHash = frameHashHex(pixels)
                                            presentsAtBoot = readU32Sym("g_desktop_presents")
                                            // Phase 2: inject real I2C2 @ 0x55 capacitive touch taps
                                            // through soc-i2c to open Start Menu and launch BOTH
                                            // native ARM32 winmine_arm.exe (0x01C0) AND Rosetta x86
                                            // winmine_x86.exe (0x014C) side-by-side!
                                            touchPeer.enqueueTap(40, 750)  // Start Button
                                            touchPeer.enqueueTap(80, 510)  // Minesweeper (ARM32 0x01C0)
                                            touchPeer.enqueueTap(367, 303) // Reveal center cell in ARM32 PE
                                            touchPeer.enqueueTap(40, 750)  // Start Button
                                            touchPeer.enqueueTap(80, 556)  // Minesweeper (x86 0x014C Rosetta)
                                            touchEnqueued = true
                                        } else if (touchEnqueued &&
                                            touchPeer.pendingPackets() == 0 &&
                                            readU32Sym("g_native_calls") >= 2L &&
                                            readU32Sym("g_native_presents") >= 2L &&
                                            readU32Sym("g_wm_x86_inited") == 1L &&
                                            readU32Sym("g_desktop_presents") > presentsAtBoot
                                        ) {
                                            dualFrameHash = frameHashHex(pixels)
                                            if (dualFrameHash != bootFrameHash) {
                                                dualWinMineRendered = true
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                            if (!dualWinMineRendered &&
                                touchEnqueued &&
                                touchPeer.pendingPackets() == 0 &&
                                readU32Sym("g_native_calls") >= 2L &&
                                readU32Sym("g_native_presents") >= 2L &&
                                readU32Sym("g_wm_x86_inited") == 1L &&
                                display.copyArgb(pixels)
                            ) {
                                dualFrameHash = frameHashHex(pixels)
                                if (dualFrameHash != bootFrameHash) {
                                    dualWinMineRendered = true
                                }
                            }

                            val st = session.state()
                            val summary = "ticks=${st["ticks"]}, frontier=${st["frontier"]}, " +
                                "ready=${readU32Sym("g_full_os_ready")}, presents=${readU32Sym("g_desktop_presents")}, " +
                                "polls=${touchPeer.pollCount}, pending=${touchPeer.pendingPackets()}, " +
                                "nativeCalls=${readU32Sym("g_native_calls")}, nativePresents=${readU32Sym("g_native_presents")}, " +
                                "wmX86=${readU32Sym("g_wm_x86_inited")}, bootHash=$bootFrameHash, dualHash=$dualFrameHash"
                            assertNull(st["frontier"], "WinXP ARM32 OS hit unexpected frontier: $summary")

                            // 1. LCDC1 real-silicon bit 12 active output gate & opaque OSD0 contract
                            val layers = checkNotNull(
                                host.ports.find("soc-vglib-init.lcdc1-layers", LcdcLayerObservabilityPort::class.java),
                            ).lcdc1LayerState()
                            assertEquals(0x0001_1000L, layers.reg008, "WinXP HAL must preserve LCDC1 bit 12 + OSD0 and clear PIC0/PIC1")
                            assertEquals(0x1fc7_f088L, layers.reg030, "WinXP HAL must set opaque LCDC1 OSD0 control 0x1FC7F088")
                            assertTrue(layers.activeOutputGate, "LCDC1 bit 12 active output gate must stay enabled")
                            assertTrue(layers.outputActive, "LCDC1 output must be active")
                            assertNull(layers.blankReason, "LCDC1 must never blank")
                            assertEquals(0L, layers.activeGateBlankCount, "WinXP must have ZERO bit-12 gray blanks")

                            // 2. Retail clock-gated peripherals (LCDC0 & UART0) must remain completely untouched
                            val lcdc0Power = checkNotNull(
                                host.ports.find("soc-vglib-init.lcdc0-power", LcdcPowerGatePort::class.java),
                            ).lcdc0PowerState()
                            assertEquals(0L, lcdc0Power.unclockedAccessCount, "WinXP must never touch retail-gated LCDC0 (0xFFF7A000)")
                            val uartPower = checkNotNull(
                                host.ports.find("soc-uart.clock-gate", UartClockGatePort::class.java),
                            ).clockGateState()
                            assertEquals(0L, uartPower.unclockedTxAttempts, "WinXP must never TX to retail-gated UART0 (0xFFF3A000)")
                            assertEquals(0L, uartPower.unclockedReadAttempts, "WinXP must never read retail-gated UART0 (0xFFF3A000)")

                            // 3. SPX4 Timer Ch6 display timer must remain active while Ch7 NT tick is active
                            val timerAudit = checkNotNull(
                                host.ports.find("soc-timer.channels", TimerChannelObservabilityPort::class.java),
                            ).channelAuditState()
                            assertTrue(timerAudit.ch6DisplayTimerActive, "SPX4 Ch6 display/backlight timer must stay active")
                            assertTrue(timerAudit.ch7OsTickTimerActive, "SPX4 Ch7 NT OS tick timer must be enabled")
                            assertEquals(0L, timerAudit.ch6DisableCount, "WinXP HAL must never disable Ch6")
                            assertEquals(0L, timerAudit.blindMultiChannelDisableCount, "WinXP HAL must never issue blind multi-channel timer disable")

                            // 4. Full 1024x768 Windows XP Luna Desktop + Wave-5 NT 5.1 Session State
                            assertTrue(
                                lunaReached,
                                "Expected full 1024x768 Wave-5 Windows XP Luna Desktop on display.main " +
                                    "(seq=$lastSeq, nonBlack=$nonBlackCount, opaque=$opaqueCount, colors=$uniqueColors, $summary)",
                            )
                            assertEquals(5L, readU32Sym("g_w5_kuser_major"), "Expected KUSER_SHARED_DATA NtMajorVersion=5")
                            assertEquals(1L, readU32Sym("g_w5_kuser_minor"), "Expected KUSER_SHARED_DATA NtMinorVersion=1")
                            assertEquals(1L, readU32Sym("g_w5_session_ready"), "Expected live nt_session boot chain ready")
                            val svcCount = readU32Sym("g_w5_session_services")
                            val svchostCount = readU32Sym("g_w5_session_svchosts")
                            assertTrue(svcCount >= 10L, "Expected >=10 services started from multi-hbin SYSTEM hive, got $svcCount")
                            assertTrue(svchostCount >= 2L, "Expected >=2 svchost groups started by services.exe, got $svchostCount")

                            // 5. Simultaneous Dual-Arch Minesweeper via live soc-i2c I2C2 @ 0x55 touch!
                            val nativeCalls = readU32Sym("g_native_calls")
                            val nativePresents = readU32Sym("g_native_presents")
                            val wmX86Inited = readU32Sym("g_wm_x86_inited")
                            assertTrue(touchPeer.pollCount >= 10, "Expected >=10 live soc-i2c I2C2 @ 0x55 polls, got ${touchPeer.pollCount}")
                            assertTrue(
                                nativeCalls >= 2L && nativePresents >= 2L,
                                "Expected live native ARM32 (0x01C0) winmine_arm.exe execution: calls=$nativeCalls, presents=$nativePresents",
                            )
                            assertEquals(1L, wmX86Inited, "Expected live Rosetta x86 (0x014C) winmine_x86.exe execution")
                            assertTrue(
                                dualWinMineRendered && dualFrameHash != bootFrameHash,
                                "Expected updated 1024x768 framebuffer showing dual-arch Minesweeper ($summary)",
                            )
                        }
                    }.join()
                } finally {
                    owner.submit { host.close() }.join()
                }
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}

package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.MmioMirrorHandle
import com.github.nyabanban.fuji.emulator.api.MmioMirrorSlotSpec
import com.github.nyabanban.fuji.emulator.api.MmioMirrorWindowSpec
import com.github.nyabanban.fuji.emulator.api.NativeMmioMirrorPort
import com.github.nyabanban.fuji.emulator.cpu.Dynarmic
import com.github.nyabanban.fuji.emulator.desktop.session.NativeMmioShadowCoordinator
import com.github.nyabanban.fuji.emulator.firmware.DeviceWindow
import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import com.github.nyabanban.fuji.emulator.runtime.OwnerLoop
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeMmioMirrorAndWfiIdleTest {

    @Test
    fun mirrorSpecAndHandleValidateLayoutAndPublishReleaseAcquireValues() {
        assertFailsWith<IllegalArgumentException> { MmioMirrorSlotSpec(0x01L, 4) } // unaligned u32
        assertFailsWith<IllegalArgumentException> { MmioMirrorSlotSpec(0x00L, 3) } // invalid width
        assertFailsWith<IllegalArgumentException> {
            MmioMirrorWindowSpec("test.dev", 0x10L, listOf(MmioMirrorSlotSpec(0x10L, 4)))
        }
        assertFailsWith<IllegalArgumentException> {
            MmioMirrorWindowSpec(
                "test.dev",
                0x20L,
                listOf(MmioMirrorSlotSpec(0x04L, 4), MmioMirrorSlotSpec(0x04L, 4)),
            )
        }

        val spec = MmioMirrorWindowSpec(
            portName = "test.dev",
            windowSize = 0x20L,
            slots = listOf(
                MmioMirrorSlotSpec(0x00L, 4),
                MmioMirrorSlotSpec(0x04L, 2),
                MmioMirrorSlotSpec(0x08L, 1),
            ),
        )
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(MmioMirrorHandle.requiredBytes(spec.slots.size), 8L)
            val handle = MmioMirrorHandle(spec, seg)
            assertEquals(0L, handle.currentValidMask())
            handle.writeSlot(0, 0x1234_5678)
            assertEquals(1L, handle.currentValidMask())
            assertEquals(0x1234_5678, handle.readSlot(0))
            handle.writeSlotRaw(1, 0xBEEF)
            handle.writeSlotRaw(2, 0xA5)
            handle.setValidMask(handle.allSlotsMask)
            assertEquals(0x7L, handle.currentValidMask())
            assertEquals(0xBEEF, handle.readSlot(1))
            assertEquals(0xA5, handle.readSlot(2))
            handle.setSlotValid(1, false)
            assertEquals(0x5L, handle.currentValidMask())
            handle.invalidateAll()
            assertEquals(0L, handle.currentValidMask())
        }
    }

    @Test
    fun dynarmicNativeMirrorBankServesExactWidthReadsLockFreeAndRespectsValidMaskAndWrites() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val lib = CpuEngineConfig.findDynarmicLibrary(repo, emptyMap())
        assumeTrue(lib != null, "Dynarmic native library not built locally")

        val codeBase = 0x0001_0000L
        val mmioBase = 0x4000_5000L
        val prog = intArrayOf(
            // r10 = mmioBase
            0xE59A0000.toInt(), // ldr  r0, [r10, #0]   -> mirror hit u32
            0xE1DA10B4.toInt(), // ldrh r1, [r10, #4]   -> mirror hit u16
            0xE5DA2008.toInt(), // ldrb r2, [r10, #8]   -> mirror hit u8
            0xE58A0000.toInt(), // str  r0, [r10, #0]   -> upcall write updates mirror slot 0
            0xE59A3000.toInt(), // ldr  r3, [r10, #0]   -> mirror hit updated value (no invalidation!)
            0xE1DA40B0.toInt(), // ldrh r4, [r10, #0]   -> width mismatch (2 != 4) -> upcall read
            0xE5CA2008.toInt(), // strb r2, [r10, #8]   -> upcall write disables valid bit 2
            0xE5DA5008.toInt(), // ldrb r5, [r10, #8]   -> invalid bit -> upcall read
            0xE59A600C.toInt(), // ldr  r6, [r10, #12]  -> unmirrored FIFO-pop offset -> upcall read
            0xE320F003.toInt(), // wfi
        )
        val progBytes = ByteBuffer.allocate(prog.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (w in prog) putInt(w)
        }.array()

        val spec = MmioMirrorWindowSpec(
            portName = "test.mirror",
            windowSize = 0x40L,
            slots = listOf(
                MmioMirrorSlotSpec(0x00L, 4),
                MmioMirrorSlotSpec(0x04L, 2),
                MmioMirrorSlotSpec(0x08L, 1),
            ),
        )

        Dynarmic(lib!!, 4, 0xFFFF_0000L).use { dyn ->
            assumeTrue(dyn.supportsMmioMirrors(), "Native Dynarmic library lacks fuji_dyn_register_mmio_mirror")
            dyn.map(0x0000_0000L, 0x0010_0000L, 7)
            dyn.mmio(mmioBase, 0x1000L)
            dyn.writeMemory(codeBase, progBytes)

            Arena.ofConfined().use { arena ->
                val seg = arena.allocate(MmioMirrorHandle.requiredBytes(spec.slots.size), 8L)
                val handle = MmioMirrorHandle(spec, seg)
                handle.writeSlotRaw(0, 0x1122_3344)
                handle.writeSlotRaw(1, 0x0000_BEEF)
                handle.writeSlotRaw(2, 0x0000_00A5)
                handle.setValidMask(handle.allSlotsMask)

                val upcallReads = AtomicInteger(0)
                val upcallWrites = AtomicInteger(0)
                dyn.setMmioHandler(object : Dynarmic.MmioHandler {
                    override fun read(physicalAddress: Long, size: Int): Long {
                        upcallReads.incrementAndGet()
                        return when {
                            physicalAddress == mmioBase && size == 2 -> 0x3344L
                            physicalAddress == mmioBase + 8L && size == 1 -> 0x5AL
                            physicalAddress == mmioBase + 12L && size == 4 -> 0xCAFE_BABEL
                            else -> 0xDEAD_BEEFL
                        }
                    }
                    override fun write(physicalAddress: Long, size: Int, value: Long) {
                        upcallWrites.incrementAndGet()
                        when (physicalAddress) {
                            mmioBase -> handle.writeSlot(0, (value.toInt() xor 0xFF))
                            mmioBase + 8L -> handle.setSlotValid(2, false)
                        }
                    }
                })

                assertTrue(dyn.registerMmioMirror(mmioBase, 0x40L, seg))
                dyn.resetMmioStats()
                dyn.resetCore(0)
                dyn.setReg(0, 10, mmioBase)
                dyn.setReg(0, 15, codeBase)

                val res = dyn.runFast(0, 1000L, 0L, false)
                assertEquals(Dynarmic.STOP_WFI, res.stopReason)
                assertEquals(0x1122_3344L, dyn.getReg(0, 0))
                assertEquals(0x0000_BEEFL, dyn.getReg(0, 1))
                assertEquals(0x0000_00A5L, dyn.getReg(0, 2))
                assertEquals(0x1122_33BBL, dyn.getReg(0, 3))
                assertEquals(0x0000_3344L, dyn.getReg(0, 4))
                assertEquals(0x0000_005AL, dyn.getReg(0, 5))
                assertEquals(0xCAFE_BABEL, dyn.getReg(0, 6))
                assertEquals(3, upcallReads.get(), "Expected exactly 3 upcall reads (width mismatch + invalid bit + unmirrored)")
                assertEquals(2, upcallWrites.get(), "Expected exactly 2 upcall writes")
                assertEquals(4L, res.fastMmioHits, "Expected 4 lock-free mirror hits")

                assertTrue(dyn.unregisterMmioMirror(mmioBase))
                dyn.clearMmioMirrors()
            }
        }
    }

    @Test
    fun coordinatorAutoDiscoversTopFivePeripheralsOnlyInRelaxedModeAndMatchesMmioReads(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val lib = CpuEngineConfig.findDynarmicLibrary(repo, emptyMap())
        assumeTrue(lib != null, "Dynarmic native library not built locally")
        val compDir = repo.resolve("build/release-stage/linux-x86_64/runtime/compoments")
        assumeTrue(java.nio.file.Files.isDirectory(compDir), "Requires staged components directory")

        val jarDir = tempRoot.resolve("jars").also { java.nio.file.Files.createDirectories(it) }
        val cfgDir = tempRoot.resolve("cfg").also { java.nio.file.Files.createDirectories(it) }
        for (prefix in listOf("soc-spi", "soc-keyed-io", "soc-uart")) {
            val src = java.nio.file.Files.list(compDir).use { stream ->
                stream.filter { p ->
                    val name = p.fileName.toString()
                    java.nio.file.Files.isRegularFile(p) &&
                        (name == "$prefix.jar" || (name.startsWith("$prefix-") && name.endsWith(".jar")))
                }.findFirst().orElse(null)
            }
            assumeTrue(src != null, "Missing $prefix jar in $compDir")
            java.nio.file.Files.copy(src!!, jarDir.resolve(src.fileName))
        }
        fun writeComponentYaml(id: String, body: String) {
            val dir = cfgDir.resolve(id).also { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(dir.resolve("$id.yaml"), body)
        }
        writeComponentYaml("soc-spi", "profile: spi-fifo-no-peer-experimental-v1\npeerPort: \"\"\nportsFile: \"\"\n")
        writeComponentYaml("soc-keyed-io", "profile: keyed-io-functional-experimental-v1\n")
        writeComponentYaml("soc-uart", "profile: pl011-multi-uart-experimental-v1\ninitialFill: 0\n")

        OwnerLoop().use { owner ->
            val host = ComponentHost(owner, jarDir, configRoot = cfgDir, relaxedMode = true)
            try {
                owner.submit { host.load() }.join()

                val windows = listOf(
                    DeviceWindow("soc-spi", 0xFFF3_6000L, 0x28L, "soc-spi.spi1"),
                    DeviceWindow("soc-keyed-io", 0xFFF4_2000L, 0xA0L, "soc-keyed-io"),
                    DeviceWindow("soc-uart", 0xFFF3_9000L, 0x4CL, "soc-uart.uart0"),
                    DeviceWindow("soc-uart", 0xFFF3_A000L, 0x4CL, "soc-uart.uart1"),
                    DeviceWindow("soc-uart", 0xFFF3_B000L, 0x4CL, "soc-uart.uart2"),
                )

                Dynarmic(lib!!, 1, 0xFFFF_0000L).use { dyn ->
                    assumeTrue(dyn.supportsMmioMirrors(), "Native Dynarmic library lacks fuji_dyn_register_mmio_mirror")
                    val coord = NativeMmioShadowCoordinator()

                    // Strict / discovery mode MUST NOT attach any mirrors
                    owner.submit { coord.initialize(host, dyn, windows, relaxedMode = false) }.join()
                    assertEquals(0, coord.mmioMirrorWindowCount, "strict/discovery must attach 0 mirrors")
                    assertEquals(0, coord.mmioMirrorSlotCount)

                    // Relaxed mode MUST auto-discover all 5 windows (soc-spi.spi1 + soc-keyed-io + uart0/1/2)
                    owner.submit { coord.initialize(host, dyn, windows, relaxedMode = true) }.join()
                    assertEquals(5, coord.mmioMirrorWindowCount, "relaxed must auto-discover 5 mirror windows")
                    assertEquals(6 + 13 + 30, coord.mmioMirrorSlotCount, "expected 49 total mirror slots")

                    // Mutate soc-spi and soc-keyed-io via standard MmioDevice.write and verify Dynarmic reads live values with 0 upcalls
                    val upcallReads = AtomicInteger(0)
                    dyn.map(0x0001_0000L, 0x0001_0000L, 7)
                    dyn.mmio(0xFFF3_6000L, 0x1000L)
                    dyn.mmio(0xFFF4_2000L, 0x1000L)
                    dyn.setMmioHandler(object : Dynarmic.MmioHandler {
                        override fun read(physicalAddress: Long, size: Int): Long {
                            upcallReads.incrementAndGet()
                            return 0L
                        }
                        override fun write(physicalAddress: Long, size: Int, value: Long) {}
                    })

                    owner.submit {
                        val spi = host.ports.find("soc-spi.spi1", MmioDevice::class.java)!!
                        spi.write(0x00L, 4, 0x0007L) // control = 8-bit
                        spi.write(0x10L, 4, 4L)      // divider = 4
                        val kio = host.ports.find("soc-keyed-io", MmioDevice::class.java)!!
                        kio.write(0x20L, 4, 0x0000_0055L) // enable
                        kio.write(0x00L, 4, 0x0000_AA01L) // command -> sets active=0x55, status=0x10
                    }.join()

                    val prog = intArrayOf(
                        0xE59A0000.toInt(), // ldr r0, [r10, #0x00] (spi control == 0x7)
                        0xE59A100C.toInt(), // ldr r1, [r10, #0x0C] (spi status == 0x3: tx empty & not full)
                        0xE59A2010.toInt(), // ldr r2, [r10, #0x10] (spi divider == 4)
                        0xE59B300C.toInt(), // ldr r3, [r11, #0x0C] (keyed-io status == 0x10)
                        0xE59B4018.toInt(), // ldr r4, [r11, #0x18] (keyed-io active == 0x55)
                        0xE320F003.toInt(), // wfi
                    )
                    val bytes = ByteBuffer.allocate(prog.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                        for (w in prog) putInt(w)
                    }.array()
                    dyn.writeMemory(0x0001_0000L, bytes)
                    dyn.resetCore(0)
                    dyn.setReg(0, 10, 0xFFF3_6000L)
                    dyn.setReg(0, 11, 0xFFF4_2000L)
                    dyn.setReg(0, 15, 0x0001_0000L)

                    val res = dyn.runFast(0, 1000L, 0L, false)
                    assertEquals(Dynarmic.STOP_WFI, res.stopReason)
                    assertEquals(0x7L, dyn.getReg(0, 0))
                    assertEquals(0x3L, dyn.getReg(0, 1))
                    assertEquals(0x4L, dyn.getReg(0, 2))
                    assertEquals(0x10L, dyn.getReg(0, 3))
                    assertEquals(0x55L, dyn.getReg(0, 4))
                    assertEquals(0, upcallReads.get(), "All 5 live peripheral status/control reads must hit mirror with 0 upcalls")
                    assertEquals(5L, res.fastMmioHits)

                    // Ensure reset detaches cleanly
                    owner.submit { coord.reset() }.join()
                    assertEquals(0, coord.mmioMirrorWindowCount)
                    assertFalse(coord.adcRoEnabled)
                }
            } finally {
                owner.submit { host.close() }.join()
            }
        }
    }

    @Test
    fun argbFrameBufferSkipsIdenticalFrameCopyAndAdvancesContentSequenceOnlyOnPixelChange() {
        val fb = com.github.nyabanban.fuji.emulator.api.ArgbFrameBuffer(4, 2)
        val frameA = IntArray(8) { i -> 0xFF102030.toInt() + i }
        val frameACopy = frameA.copyOf()
        val frameB = frameA.copyOf().also { it[5] = 0xFFAA5500.toInt() }

        assertEquals(0L, fb.sequence)
        assertEquals(0L, fb.contentSequence)
        assertEquals(0L, fb.skippedIdenticalFrames)

        fb.presentArgb(frameA, 4, 2)
        assertEquals(1L, fb.sequence)
        assertEquals(1L, fb.contentSequence)
        assertEquals(0L, fb.skippedIdenticalFrames)

        // Bit-identical re-presentation: sequence increments for compatibility, contentSequence stays constant!
        fb.presentArgb(frameACopy, 4, 2)
        assertEquals(2L, fb.sequence)
        assertEquals(1L, fb.contentSequence)
        assertEquals(1L, fb.skippedIdenticalFrames)

        // Single-pixel change immediately advances contentSequence and copies pixels!
        fb.presentArgb(frameB, 4, 2)
        assertEquals(3L, fb.sequence)
        assertEquals(2L, fb.contentSequence)
        assertEquals(1L, fb.skippedIdenticalFrames)

        val readback = IntArray(8)
        assertTrue(fb.copyArgb(readback))
        assertEquals(0xFFAA5500.toInt(), readback[5])

        // Snapshot restore resets contentSequence to restored sequence so first frame publishes immediately.
        val snap = fb.saveSnapshot()
        val restored = com.github.nyabanban.fuji.emulator.api.ArgbFrameBuffer(4, 2)
        restored.restoreSnapshot(snap)
        assertEquals(3L, restored.sequence)
        assertEquals(3L, restored.contentSequence)
        assertEquals(0L, restored.skippedIdenticalFrames)
    }

    @Test
    fun expandedWave12PeripheralsAutoDiscoverMirrorsAndServeLockFreeReads(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val lib = CpuEngineConfig.findDynarmicLibrary(repo, emptyMap())
        assumeTrue(lib != null, "Dynarmic native library not built locally")
        val compDir = repo.resolve("build/release-stage/linux-x86_64/runtime/compoments")
        assumeTrue(java.nio.file.Files.isDirectory(compDir), "Requires staged components directory")

        val jarDir = tempRoot.resolve("jars").also { java.nio.file.Files.createDirectories(it) }
        val cfgDir = tempRoot.resolve("cfg").also { java.nio.file.Files.createDirectories(it) }
        for (prefix in listOf("soc-configuration", "soc-system-control", "soc-dma", "soc-hdmi")) {
            val src = java.nio.file.Files.list(compDir).use { stream ->
                stream.filter { p ->
                    val name = p.fileName.toString()
                    java.nio.file.Files.isRegularFile(p) &&
                        (name == "$prefix.jar" || (name.startsWith("$prefix-") && name.endsWith(".jar")))
                }.findFirst().orElse(null)
            }
            assumeTrue(src != null, "Missing $prefix jar in $compDir")
            java.nio.file.Files.copy(src!!, jarDir.resolve(src.fileName))
        }
        fun writeYaml(id: String, body: String) {
            val dir = cfgDir.resolve(id).also { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(dir.resolve("$id.yaml"), body)
        }
        val hdmiDataBody =
            "schema: 1\nprofile: hdmi-init-retained-experimental-v1\ninitialFill: 0\npairs:\n  - [0, 0]\nlinkReads:\n  - 7\n  - 11\n"
        val hdmiDataDir = jarDir.resolve("soc-hdmi").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(hdmiDataDir.resolve("hdmi-input.yaml"), hdmiDataBody)
        val hdmiCfgDir = cfgDir.resolve("soc-hdmi").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(hdmiCfgDir.resolve("hdmi-input.yaml"), hdmiDataBody)
        writeYaml("soc-configuration", "profile: configuration-command2-experimental-v1\ninitialValue: 0\ncompletionTicks: 8\nbaseProfile: disabled\nbaseInputFile: \"\"\n")
        writeYaml("soc-system-control", "profile: retained-banks-experimental-v1\n")
        writeYaml("soc-dma", "profile: standalone-experimental-v1\n")
        writeYaml(
            "soc-hdmi",
            "profile: hdmi-init-retained-experimental-v1\ninputFile: \"hdmi-input.yaml\"\nrepeatLastLinkRead: true\nrepeatLastStatusPair: true\n",
        )

        OwnerLoop().use { owner ->
            val host = ComponentHost(owner, jarDir, configRoot = cfgDir, relaxedMode = true)
            try {
                owner.submit { host.load() }.join()
                val windows = listOf(
                    DeviceWindow("soc-configuration", 0xFF70_1000L, 0x84L, "soc-configuration.group0"),
                    DeviceWindow("soc-system-control", 0xFF70_F000L, 0x40L, "soc-system-control.a"),
                    DeviceWindow("soc-dma", 0xFFFE_0000L, 0x10000L, "soc-dma.registers"),
                    DeviceWindow("soc-dma", 0xFFFF_5000L, 0x20L, "soc-dma.mux"),
                    DeviceWindow("soc-hdmi", 0xFFF7_B000L, 2704L, "soc-hdmi"),
                )
                owner.submit {
                    Dynarmic(lib!!, 1, 0xFFFF_0000L).use { dyn ->
                        assumeTrue(dyn.supportsMmioMirrors(), "Native Dynarmic library lacks fuji_dyn_register_mmio_mirror")
                        val coord = NativeMmioShadowCoordinator()
                        coord.initialize(host, dyn, windows, relaxedMode = true)
                        assertEquals(5, coord.mmioMirrorWindowCount, "Expected 5 expanded W12 mirror windows")
                        assertFalse(coord.configRoEnabled, "Legacy configRoEnabled must be superseded by native mirror")

                        val upcallReads = AtomicInteger(0)
                        dyn.map(0x0001_0000L, 0x0001_0000L, 7)
                        dyn.mmio(0xFF70_1000L, 0x1000L)
                        dyn.mmio(0xFF70_F000L, 0x1000L)
                        dyn.mmio(0xFFFE_0000L, 0x10000L)
                        dyn.mmio(0xFFF7_B000L, 0x1000L)

                        val hdmiDev = host.ports.find("soc-hdmi", MmioDevice::class.java)!!
                        dyn.setMmioHandler(object : Dynarmic.MmioHandler {
                            override fun read(physicalAddress: Long, size: Int): Long {
                                upcallReads.incrementAndGet()
                                return if (physicalAddress in 0xFFF7_B000L until (0xFFF7_B000L + 2704L)) {
                                    hdmiDev.read(physicalAddress - 0xFFF7_B000L, size)
                                } else {
                                    0L
                                }
                            }
                            override fun write(physicalAddress: Long, size: Int, value: Long) {}
                        })

                        host.ports.find("soc-configuration.group0", MmioDevice::class.java)!!.write(0x40L, 4, 0x123L)
                        host.ports.find("soc-system-control.a", MmioDevice::class.java)!!.write(0x00L, 4, 0x5L)
                        host.ports.find("soc-dma.registers", MmioDevice::class.java)!!.write(0x10L, 4, 0x0000_03FFL)

                        val prog = intArrayOf(
                            0xE5980040.toInt(), // ldr r0, [r8, #0x40]  (soc-configuration.group0 staged[0] == 0x123)
                            0xE5981080.toInt(), // ldr r1, [r8, #0x80]  (soc-configuration.group0 status == 0)
                            0xE5992000.toInt(), // ldr r2, [r9, #0x00]  (soc-system-control.a[0] == 5)
                            0xE59A3010.toInt(), // ldr r3, [r10, #0x10] (soc-dma ch0 count == 0x3FF)
                            0xE59B4008.toInt(), // ldr r4, [r11, #0x08] (hdmi link[0] == 7 -> upcall 1)
                            0xE59B5008.toInt(), // ldr r5, [r11, #0x08] (hdmi link[1] == 11 -> upcall 2, activates mirror!)
                            0xE59B6008.toInt(), // ldr r6, [r11, #0x08] (hdmi repeat == 11 -> MIRROR HIT!)
                            0xE320F003.toInt(), // wfi
                        )
                        val bytes = ByteBuffer.allocate(prog.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                            for (w in prog) putInt(w)
                        }.array()
                        dyn.writeMemory(0x0001_0000L, bytes)
                        dyn.resetCore(0)
                        dyn.setReg(0, 8, 0xFF70_1000L)
                        dyn.setReg(0, 9, 0xFF70_F000L)
                        dyn.setReg(0, 10, 0xFFFE_0000L)
                        dyn.setReg(0, 11, 0xFFF7_B000L)
                        dyn.setReg(0, 15, 0x0001_0000L)

                        val res = dyn.runFast(0, 1000L, 0L, false)
                        assertEquals(Dynarmic.STOP_WFI, res.stopReason)
                        assertEquals(0x123L, dyn.getReg(0, 0))
                        assertEquals(0L, dyn.getReg(0, 1))
                        assertEquals(0x5L, dyn.getReg(0, 2))
                        assertEquals(0x3FFL, dyn.getReg(0, 3))
                        assertEquals(7L, dyn.getReg(0, 4))
                        assertEquals(11L, dyn.getReg(0, 5))
                        assertEquals(11L, dyn.getReg(0, 6))
                        assertEquals(2, upcallReads.get(), "Only the first 2 HDMI linkReads should upcall; repeat + config/sysctrl/dma must hit mirror")
                        assertEquals(5L, res.fastMmioHits, "Expected 5 mirror hits (config x2 + sysctrl + dma + repeated hdmi link)")

                        coord.reset()
                    }
                }.join()
            } finally {
                owner.submit { host.close() }.join()
            }
        }
    }

    @Test
    fun buttonsConfigurationEventAndGpioAdcLivePatchesPropagateUnderNativeShadows(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val lib = CpuEngineConfig.findDynarmicLibrary(repo, emptyMap())
        assumeTrue(lib != null, "Dynarmic native library not built locally")
        val compDir = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).resolve("runtime/compoments") }
            ?: repo.resolve("build/release-stage/linux-x86_64/runtime/compoments")
        assumeTrue(java.nio.file.Files.isDirectory(compDir), "Requires staged components directory at $compDir")

        val jarDir = tempRoot.resolve("jars").also { java.nio.file.Files.createDirectories(it) }
        val cfgDir = tempRoot.resolve("cfg").also { java.nio.file.Files.createDirectories(it) }
        for (prefix in listOf("buttons", "soc-gpio", "soc-adc")) {
            val src = java.nio.file.Files.list(compDir).use { stream ->
                stream.filter { p ->
                    val name = p.fileName.toString()
                    java.nio.file.Files.isRegularFile(p) &&
                        (name == "$prefix.jar" || (name.startsWith("$prefix-") && name.endsWith(".jar")))
                }.findFirst().orElse(null)
            }
            assumeTrue(src != null, "Missing $prefix jar in $compDir")
            java.nio.file.Files.copy(src!!, jarDir.resolve(src.fileName))
        }
        val btnDir = cfgDir.resolve("buttons").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(btnDir.resolve("buttons.yaml"), "movieMode: false\npower: true\n")
        val gpioDir = cfgDir.resolve("soc-gpio").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(
            gpioDir.resolve("soc-gpio.yaml"),
            "input7: 8256\nprofile: \"gfx100s-2.13-gpio-buttons-v6\"\ndirectionButtonProfile: \"gfx100s-2.13-direction-input-v1\"\n",
        )
        val adcDir = cfgDir.resolve("soc-adc").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(
            adcDir.resolve("input.json"),
            """{"profile":"adc-source-functional-experimental-v1","sample_ticks":16,"inputs":{"0":2712,"1":257,"2":514,"3":4000,"4":1028,"5":1285,"6":4000,"7":1799,"8":2056,"9":4000,"10":2570,"11":2827},"word_44":null,"menu_pressed_sample":2800,"direction_pressed_samples":{"up":2074,"upRight":665,"right":1568,"downRight":1339,"down":2823,"downLeft":2412,"left":3380,"upLeft":1809}}""",
        )
        java.nio.file.Files.writeString(
            adcDir.resolve("soc-adc.yaml"),
            "profile: \"adc-source-functional-experimental-v1\"\ninputFile: \"input.json\"\nmenuButtonProfile: \"gfx100s-2.13-menu-adc-v1\"\ndirectionButtonProfile: \"gfx100s-2.13-direction-input-v1\"\n",
        )

        OwnerLoop().use { owner ->
            val host = ComponentHost(owner, jarDir, configRoot = cfgDir, relaxedMode = true)
            try {
                owner.submit { host.load() }.join()
                val windows = listOf(
                    DeviceWindow("soc-gpio", 0xFFF4_1000L, 0x400L, "soc-gpio"),
                    DeviceWindow("soc-adc", 0xFFF3_8000L, 0x48L, "soc-adc"),
                )
                owner.submit {
                    Dynarmic(lib!!, 1, 0xFFFF_0000L).use { dyn ->
                        val coord = NativeMmioShadowCoordinator()
                        coord.initialize(host, dyn, windows, relaxedMode = true)
                        assertTrue(coord.gpioRoEnabled, "soc-gpio RO-MMIO shadow must be active")

                        dyn.map(0x0001_0000L, 0x0001_0000L, 7)
                        dyn.mmio(0xFFF4_1000L, 0x1000L)
                        dyn.mmio(0xFFF3_8000L, 0x1000L)
                        val upcallReads = AtomicInteger(0)
                        dyn.setMmioHandler(object : Dynarmic.MmioHandler {
                            override fun read(physicalAddress: Long, size: Int): Long {
                                upcallReads.incrementAndGet()
                                return 0L
                            }
                            override fun write(physicalAddress: Long, size: Int, value: Long) {}
                        })

                        // Program reads GPIO Bank 7 input word (0xFFF411D4 = base + 7*0x40 + 0x14) into r0
                        val prog = intArrayOf(
                            0xE59A01D4.toInt(), // ldr r0, [r10, #0x1D4]
                            0xE320F003.toInt(), // wfi
                        )
                        val bytes = ByteBuffer.allocate(prog.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                            for (w in prog) putInt(w)
                        }.array()
                        dyn.writeMemory(0x0001_0000L, bytes)

                        fun runBank7Read(): Long {
                            coord.syncBeforeRun(dyn)
                            dyn.resetCore(0)
                            dyn.setReg(0, 10, 0xFFF4_1000L)
                            dyn.setReg(0, 15, 0x0001_0000L)
                            val res = dyn.runFast(0, 100L, 0L, false)
                            assertEquals(Dynarmic.STOP_WFI, res.stopReason)
                            return dyn.getReg(0, 0)
                        }

                        val before = runBank7Read()
                        // Patch buttons.movieMode=true and deliver ONLY the ("buttons", "configuration") host event
                        host.patchLatestDirect("buttons", mapOf("movieMode" to true), persistToDisk = false)
                        coord.onHostEvent(dyn, "buttons", "configuration")
                        val afterMovie = runBank7Read()
                        assertTrue(afterMovie != before, "buttons configuration event must refresh GPIO Bank 7 shadow for movieMode")

                        // Patch soc-gpio.focusMode=MF and deliver ("soc-gpio", "configuration")
                        host.patchLatestDirect("soc-gpio", mapOf("focusMode" to "MF"), persistToDisk = false)
                        coord.onHostEvent(dyn, "soc-gpio", "configuration")
                        val afterMf = runBank7Read()
                        assertEquals(0x4000L, afterMf and 0x6000L, "focusMode=MF must appear immediately in GPIO Bank 7 shadow (bits 13..14)")

                        // Patch soc-gpio.focusMode=AF-C
                        host.patchLatestDirect("soc-gpio", mapOf("focusMode" to "AF-C"), persistToDisk = false)
                        coord.onHostEvent(dyn, "soc-gpio", "configuration")
                        val afterAfc = runBank7Read()
                        assertEquals(0x6000L, afterAfc and 0x6000L, "focusMode=AF-C must appear immediately in GPIO Bank 7 shadow (bits 13..14)")

                        assertEquals(0, upcallReads.get(), "All GPIO Bank 7 reads must be served from native RO-MMIO shadow")
                        coord.reset()
                    }
                }.join()
            } finally {
                owner.submit { host.close() }.join()
            }
        }
    }

    @Test
    fun updateNextOtherDeadlinePreventsInlineWfiFastForwardFromSkippingNewlyArmedPeripheralDeadline(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val lib = CpuEngineConfig.findDynarmicLibrary(repo, emptyMap())
        assumeTrue(lib != null, "Dynarmic native library not built locally")
        val compDir = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).resolve("runtime/compoments") }
            ?: repo.resolve("build/release-stage/linux-x86_64/runtime/compoments")
        assumeTrue(java.nio.file.Files.isDirectory(compDir), "Requires staged components directory")

        val jarDir = tempRoot.resolve("jars").also { java.nio.file.Files.createDirectories(it) }
        val cfgDir = tempRoot.resolve("cfg").also { java.nio.file.Files.createDirectories(it) }
        for (prefix in listOf("soc-timer", "soc-gic")) {
            val src = java.nio.file.Files.list(compDir).use { stream ->
                stream.filter { p ->
                    val name = p.fileName.toString()
                    java.nio.file.Files.isRegularFile(p) &&
                        (name == "$prefix.jar" || (name.startsWith("$prefix-") && name.endsWith(".jar")))
                }.findFirst().orElse(null)
            }
            assumeTrue(src != null, "Missing $prefix jar in $compDir")
            java.nio.file.Files.copy(src!!, jarDir.resolve(src.fileName))
        }
        val timerDir = cfgDir.resolve("soc-timer").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(
            timerDir.resolve("input.json"),
            """{"profile":"interval-edge-routing-experimental-v1","clock":"functional-device-ticks-with-idle","routes":[{"channel":0,"irq":32}]}""",
        )
        java.nio.file.Files.writeString(
            timerDir.resolve("soc-timer.yaml"),
            "profile: interval-edge-routing-experimental-v1\ninputFile: input.json\nirqSink: soc-gic.lines\n",
        )
        val gicDir = cfgDir.resolve("soc-gic").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(
            gicDir.resolve("soc-gic.yaml"),
            "profile: gicv2-catalog-experimental-v1\ncoreProfile: four-context-functional-experimental-v1\n",
        )

        OwnerLoop().use { owner ->
            val host = ComponentHost(owner, jarDir, configRoot = cfgDir, relaxedMode = true)
            try {
                owner.submit { host.load() }.join()
                val windows = listOf(
                    DeviceWindow("soc-timer", 0xFFF3_C300L, 0x50L, "soc-timer"),
                    DeviceWindow("soc-gic", 0xFFF0_1000L, 0x1000L, "soc-gic.distributor"),
                    DeviceWindow("soc-gic", 0xFFF0_2000L, 0x1000L, "soc-gic.interface"),
                )
                owner.submit {
                    Dynarmic(lib!!, 1, 0xFFFF_0000L).use { dyn ->
                        assumeTrue(dyn.supportsTimerIrqShadow(), "Requires inline timer IRQ shadow")
                        val coord = NativeMmioShadowCoordinator()
                        coord.initialize(host, dyn, windows, relaxedMode = true)
                        assertTrue(coord.timerIrqShadowEnabled, "Expected inline timer IRQ shadow to bind")

                        val irqSeg = dyn.timerIrqShadowSegment()
                        coord.prepareInlineTimerBeforeRun(Long.MAX_VALUE, allowWfiFastForward = true)
                        assertEquals(
                            Long.MAX_VALUE,
                            irqSeg.get(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0x70L),
                        )

                        // Arming a non-timer peripheral deadline mid-slice (e.g. shutter/SPI/ADC at tick=80_000)
                        // must immediately tighten timer_irq_shadow->next_other_deadline so WFI fast-forward stops.
                        assertTrue(coord.updateNextOtherDeadline(80_000L))
                        assertEquals(
                            80_000L,
                            irqSeg.get(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0x70L),
                        )
                        // Repeated same-value sync returns false (no-op) and tightening further updates live.
                        assertFalse(coord.updateNextOtherDeadline(80_000L))
                        assertTrue(coord.updateNextOtherDeadline(60_000L))
                        assertEquals(
                            60_000L,
                            irqSeg.get(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0x70L),
                        )

                        coord.reset()
                    }
                }.join()
            } finally {
                owner.submit { host.close() }.join()
            }
        }
    }
}

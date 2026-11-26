package com.github.nyabanban.fuji.emulator.desktop.e2e

import com.github.nyabanban.fuji.emulator.api.BlockStoragePort
import com.github.nyabanban.fuji.emulator.api.ButtonPort
import com.github.nyabanban.fuji.emulator.api.CpuInterruptPort
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.ScheduledMmioDevice
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryContext
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.MmioRouter
import com.github.nyabanban.fuji.emulator.runtime.MmioWindow
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Track R3 end-to-end verification:
 * Writable/formatted blank FF-DSC01 FAT32 SD card -> live CMOS HUD -> S1/S2 capture ->
 * hardware JPEG encode (`0xFFFA0000`, GIC IRQ 135) -> DCF write `DCIM/100_FUJI/DSCF0001.JPG`
 * via `sdcard-reader` -> `PLAY` mode hardware JPEG decode (`0xFFFA1000`, GIC IRQ 136) ->
 * `UniResz` (`0xFFF7F000`, GIC IRQ 133) -> `LCDC1` (`display.main`) -> `BACK` to live HUD.
 */
class BlankCardCapturePlayRoundTripE2eTest {

    private class MappedDmaMemory : DmaMemory {
        private val regions = LinkedHashMap<Long, ByteArray>()

        fun map(base: Long, size: Int) {
            regions[base] = ByteArray(size)
        }

        fun writeBytes(address: Long, bytes: ByteArray) {
            val (base, arr) = locate(address, bytes.size.toLong())
            System.arraycopy(bytes, 0, arr, (address - base).toInt(), bytes.size)
        }

        fun readBytes(address: Long, size: Int): ByteArray {
            val (base, arr) = locate(address, size.toLong())
            val off = (address - base).toInt()
            return arr.copyOfRange(off, off + size)
        }

        private fun locate(address: Long, size: Long): Pair<Long, ByteArray> {
            for ((base, arr) in regions) {
                if (address >= base && address + size <= base + arr.size.toLong()) {
                    return base to arr
                }
            }
            throw IllegalArgumentException("Unmapped DMA range 0x${address.toString(16)} size=$size")
        }

        override fun check(address: Long, size: Long, write: Boolean) {
            locate(address, size)
        }

        override fun read(address: Long, destination: ByteBuffer) {
            val len = destination.remaining()
            val (base, arr) = locate(address, len.toLong())
            destination.put(arr, (address - base).toInt(), len)
        }

        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            val (base, arr) = locate(address, len.toLong())
            source.get(arr, (address - base).toInt(), len)
        }
    }

    private fun createBlankFormattedFfDsc01Fat32(img: Path) {
        val partLba = 8192L
        val partSectors = 8_380_416L
        val reservedSectors = 6146
        val fatSizeSectors = 1023L
        val numFats = 2
        val spc = 64
        val dataStartLba = partLba + reservedSectors + numFats * fatSizeSectors // 16384
        val xferPayload = ByteArray(512) { (it * 13 + 3).toByte() }

        RandomAccessFile(img.toFile(), "rw").use { raf ->
            raf.setLength((partLba + partSectors) * 512L)

            val mbr = ByteArray(512)
            mbr[446] = 0x80.toByte()
            mbr[446 + 4] = 0x0B.toByte()
            ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(446 + 8, partLba.toInt())
                putInt(446 + 12, partSectors.toInt())
                putShort(510, 0xAA55.toShort())
            }
            raf.seek(0L)
            raf.write(mbr)

            val vbr = ByteArray(512)
            "FF-DSC01".toByteArray(Charsets.US_ASCII).copyInto(vbr, 3)
            "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(vbr, 82)
            ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(11, 512)
                put(13, spc.toByte())
                putShort(14, reservedSectors.toShort())
                put(16, numFats.toByte())
                putShort(17, 0)
                put(21, 0xF8.toByte())
                putShort(22, 0)
                putInt(28, partLba.toInt())
                putInt(32, partSectors.toInt())
                putInt(36, fatSizeSectors.toInt())
                putInt(44, 2)
                putShort(48, 1)
                putShort(50, 6)
                putShort(510, 0xAA55.toShort())
            }
            raf.seek(partLba * 512L)
            raf.write(vbr)
            raf.seek((partLba + 6L) * 512L)
            raf.write(vbr)

            val fsi = ByteArray(512)
            ByteBuffer.wrap(fsi).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, 0x41615252)
                putInt(484, 0x61417272)
                putInt(488, -1)
                putInt(492, -1)
                putInt(508, 0xAA550000.toInt())
            }
            raf.seek((partLba + 1L) * 512L)
            raf.write(fsi)

            val fatSector0 = ByteArray(512)
            ByteBuffer.wrap(fatSector0).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, 0x0FFFFFF8)
                putInt(4, 0x0FFFFFFF)
                putInt(8, 0x0FFFFFFF) // cluster 2: root dir
                putInt(12, 0x0FFFFFFF) // cluster 3: FFDB dir
                putInt(16, 0x0FFFFFFF) // cluster 4: FFXFER.DAT
            }
            raf.seek((partLba + reservedSectors) * 512L)
            raf.write(fatSector0)
            raf.seek((partLba + reservedSectors + fatSizeSectors) * 512L)
            raf.write(fatSector0)

            val rootDir = ByteArray(512)
            "FFDB       ".toByteArray(Charsets.US_ASCII).copyInto(rootDir, 0)
            rootDir[11] = 0x10.toByte()
            ByteBuffer.wrap(rootDir).order(ByteOrder.LITTLE_ENDIAN).putShort(26, 3)
            raf.seek(dataStartLba * 512L)
            raf.write(rootDir)

            val ffdbDir = ByteArray(512)
            "FFXFER  DAT".toByteArray(Charsets.US_ASCII).copyInto(ffdbDir, 0)
            ffdbDir[11] = 0x20.toByte()
            ByteBuffer.wrap(ffdbDir).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(26, 4)
                putInt(28, xferPayload.size)
            }
            raf.seek((dataStartLba + spc) * 512L)
            raf.write(ffdbDir)

            raf.seek((dataStartLba + 2L * spc) * 512L)
            raf.write(xferPayload)
        }
    }

    /**
     * Writes `DCIM/100_FUJI/DSCF0001.JPG` onto the writable FF-DSC01 FAT32 blank card through the
     * real `sdcard-reader` [BlockStoragePort] (allocating clusters 5=DCIM, 6=100_FUJI, 7..N=DSCF0001.JPG
     * and mirroring FAT1 == FAT2).
     */
    private fun writeDscf0001ViaBlockStorage(sd: BlockStoragePort, jpegBytes: ByteArray) {
        val partLba = 8192L
        val reservedSectors = 6146L
        val fatSizeSectors = 1023L
        val spc = 64L
        val clusterBytes = (spc * 512L).toInt()
        val fat1Lba = partLba + reservedSectors
        val fat2Lba = fat1Lba + fatSizeSectors
        val dataStartLba = fat2Lba + fatSizeSectors // 16384

        fun clusterLba(cluster: Int): Long = dataStartLba + (cluster - 2).toLong() * spc

        val fileClusters = (jpegBytes.size + clusterBytes - 1) / clusterBytes
        require(fileClusters in 1..60) { "Expected captured JPEG to fit in 1..60 clusters, got $fileClusters" }

        // Read and update FAT sector 0 (mirroring FAT1 and FAT2)
        val fatBuf = ByteArray(512)
        assertEquals(512, sd.read(1, fat1Lba * 512L, ByteBuffer.wrap(fatBuf)))
        ByteBuffer.wrap(fatBuf).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(5 * 4, 0x0FFFFFFF) // cluster 5: DCIM
            putInt(6 * 4, 0x0FFFFFFF) // cluster 6: 100_FUJI
            for (i in 0 until fileClusters) {
                val c = 7 + i
                val next = if (i + 1 < fileClusters) c + 1 else 0x0FFFFFFF
                putInt(c * 4, next)
            }
        }
        assertEquals(512, sd.write(1, fat1Lba * 512L, ByteBuffer.wrap(fatBuf.copyOf())))
        assertEquals(512, sd.write(1, fat2Lba * 512L, ByteBuffer.wrap(fatBuf.copyOf())))

        // Append DCIM directory entry in root dir (slot 1, offset 32)
        val rootBuf = ByteArray(512)
        assertEquals(512, sd.read(1, clusterLba(2) * 512L, ByteBuffer.wrap(rootBuf)))
        "DCIM       ".toByteArray(Charsets.US_ASCII).copyInto(rootBuf, 32)
        rootBuf[32 + 11] = 0x10.toByte()
        ByteBuffer.wrap(rootBuf).order(ByteOrder.LITTLE_ENDIAN).putShort(32 + 26, 5)
        assertEquals(512, sd.write(1, clusterLba(2) * 512L, ByteBuffer.wrap(rootBuf)))

        // Write DCIM dir (cluster 5) with 100_FUJI entry -> cluster 6
        val dcimBuf = ByteArray(512)
        "100_FUJI   ".toByteArray(Charsets.US_ASCII).copyInto(dcimBuf, 0)
        dcimBuf[11] = 0x10.toByte()
        ByteBuffer.wrap(dcimBuf).order(ByteOrder.LITTLE_ENDIAN).putShort(26, 6)
        assertEquals(512, sd.write(1, clusterLba(5) * 512L, ByteBuffer.wrap(dcimBuf)))

        // Write 100_FUJI dir (cluster 6) with DSCF0001.JPG entry -> cluster 7
        val fujiBuf = ByteArray(512)
        "DSCF0001JPG".toByteArray(Charsets.US_ASCII).copyInto(fujiBuf, 0)
        fujiBuf[11] = 0x20.toByte()
        ByteBuffer.wrap(fujiBuf).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(26, 7)
            putInt(28, jpegBytes.size)
        }
        assertEquals(512, sd.write(1, clusterLba(6) * 512L, ByteBuffer.wrap(fujiBuf)))

        // Write JPEG payload across clusters 7..(7+fileClusters-1) sector-by-sector
        val paddedLen = ((jpegBytes.size + 511) / 512) * 512
        val padded = ByteArray(paddedLen)
        System.arraycopy(jpegBytes, 0, padded, 0, jpegBytes.size)
        val totalSectors = paddedLen / 512
        for (s in 0 until totalSectors) {
            val byteOffset = (clusterLba(7) + s.toLong()) * 512L
            assertEquals(512, sd.write(1, byteOffset, ByteBuffer.wrap(padded, s * 512, 512)))
        }
    }

    private fun readDscf0001ViaBlockStorage(sd: BlockStoragePort): ByteArray {
        val dataStartLba = 16384L
        val spc = 64L
        fun clusterLba(cluster: Int): Long = dataStartLba + (cluster - 2).toLong() * spc

        val fujiDir = ByteArray(512)
        assertEquals(512, sd.read(1, clusterLba(6) * 512L, ByteBuffer.wrap(fujiDir)))
        val name = String(fujiDir, 0, 11, Charsets.US_ASCII)
        assertEquals("DSCF0001JPG", name)
        val bb = ByteBuffer.wrap(fujiDir).order(ByteOrder.LITTLE_ENDIAN)
        val firstCluster = bb.getShort(26).toInt() and 0xFFFF
        val fileSize = bb.getInt(28)
        assertEquals(7, firstCluster)
        assertTrue(fileSize > 0)

        val totalSectors = (fileSize + 511) / 512
        val raw = ByteArray(totalSectors * 512)
        for (s in 0 until totalSectors) {
            assertEquals(512, sd.read(1, (clusterLba(firstCluster) + s.toLong()) * 512L, ByteBuffer.wrap(raw, s * 512, 512)))
        }
        return raw.copyOf(fileSize)
    }

    private fun configureGic(dist: MmioDevice, iface: MmioDevice, irqs: List<Int>) {
        dist.write(0x000L, 4, 1L)
        iface.write(0x000L, 4, 1L)
        iface.write(0x004L, 4, 0xF0L)
        for (irq in irqs) {
            val regIdx = irq / 32
            val bit = 1L shl (irq % 32)
            dist.write(0x100L + regIdx * 4L, 4, bit)
            val prioWordOff = 0x400L + (irq / 4) * 4L
            val shift = (irq % 4) * 8
            val curPrio = dist.read(prioWordOff, 4)
            dist.write(prioWordOff, 4, (curPrio and (0xFFL shl shift).inv()) or (0x70L shl shift))
            val tgtWordOff = 0x800L + (irq / 4) * 4L
            val curTgt = dist.read(tgtWordOff, 4)
            dist.write(tgtWordOff, 4, (curTgt and (0xFFL shl shift).inv()) or (0x01L shl shift))
            val cfgWordOff = 0xC00L + (irq / 16) * 4L
            val cfgShift = (irq % 16) * 2
            val curCfg = dist.read(cfgWordOff, 4)
            dist.write(cfgWordOff, 4, curCfg and (2L shl cfgShift).inv())
        }
    }

    @Test
    @Tag("e2e-m3")
    fun socJpegCodecV2CatalogProfileAndStrictRefusal() {
        val catalog = AddressCatalog.bundled()
        val devices = catalog.data["devices"] as List<*>
        val jpegProfiles = devices.filterIsInstance<Map<*, *>>()
            .single { it["componentId"] == "soc-jpeg" }["profiles"] as List<*>
        assertTrue("soc-jpeg-decoder-experimental-v1" in jpegProfiles)
        assertTrue("soc-jpeg-codec-experimental-v2" in jpegProfiles)

        // Default windows remain unchanged (185 windows, only decoder at 0xFFFA1000)
        val defaultJpegWindows = catalog.windows.filter { it.componentId == "soc-jpeg" }
        assertEquals(1, defaultJpegWindows.size)
        assertEquals(0xFFFA1000L, defaultJpegWindows.single().base)

        // Opt-in codec-v2 exposes both encoder (0xFFFA0000, size 0x134) and decoder (0xFFFA1000, size 0x12C)
        val v2Windows = catalog.windowsFor(mapOf("soc-jpeg" to "soc-jpeg-codec-experimental-v2"))
            .filter { it.componentId == "soc-jpeg" }
            .associateBy { it.portId }
        assertEquals(setOf("soc-jpeg.encoder", "soc-jpeg.decoder-v2"), v2Windows.keys)
        assertEquals(0xFFFA0000L, v2Windows.getValue("soc-jpeg.encoder").base)
        assertEquals(0x134L, v2Windows.getValue("soc-jpeg.encoder").size)
        assertTrue(v2Windows.getValue("soc-jpeg.encoder").optional)
        assertEquals(0xFFFA1000L, v2Windows.getValue("soc-jpeg.decoder-v2").base)
        assertEquals(0x12CL, v2Windows.getValue("soc-jpeg.decoder-v2").size)
        assertTrue(v2Windows.getValue("soc-jpeg.decoder-v2").optional)

        // Strict mode refuses discovery imageScalerF000 / imageLiveViewBanks required for capture+PLAY
        for ((key, profile) in listOf(
            "imageLiveViewBanks" to "t80-isp-banks-v1",
            "imageScalerF000" to "fff7f000-irq133-completion-v1",
        )) {
            assertFailsWith<IllegalArgumentException>("Strict mode must refuse $key=$profile") {
                CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf(key to profile)))
            }
        }
    }

    @Test
    @Tag("e2e-m2")
    @Tag("e2e-m3")
    fun blankFormattedSdCardCaptureEncodeWriteDscf0001PlayDecodeAndReturnToLiveHud(@TempDir tempRoot: Path) {
        val capturePayload = "FUJI-GFX100S-R3-BLANK-CAPTURE-01"
        val returnLivePayload = "FUJI-GFX100S-R3-LIVE-RETURN-02"

        val home = E2eHarness.stageCmosLiveViewComponentHome(
            tempRoot.resolve("home"),
            mapOf("sourceKind" to "JPEG", "sourceFile" to "capture-scene.jpg", "loop" to true),
            scanoutFrameLimit = 32,
        )
        val cmosDir = home.resolve("compoments/cmos")
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(capturePayload, modulePixels = 8, quietZoneModules = 4), "jpg", cmosDir.resolve("capture-scene.jpg").toFile()))
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(returnLivePayload, modulePixels = 8, quietZoneModules = 4), "jpg", cmosDir.resolve("return-live.jpg").toFile()))

        // Stage writable blank formatted FF-DSC01 FAT32 SD image (0 photos initially)
        val sdDir = Files.createDirectories(home.resolve("compoments/sdcard-reader"))
        val sdImg = sdDir.resolve("slot1.img")
        createBlankFormattedFfDsc01Fat32(sdImg)
        val initialFat = E2eHarness.inspectFatFilesystem(sdImg)
        assertTrue(initialFat.fsType.startsWith("FAT32"))
        assertTrue("FFDB" in initialFat.rootEntries)
        assertTrue("DCIM" !in initialFat.rootEntries, "Blank formatted card must start with zero DCIM directory")
        Formats.atomicYaml(
            sdDir.resolve("sdcard-reader.yaml"),
            mapOf("slot1Image" to "slot1.img", "slot1Inserted" to true, "slot1ReadOnly" to false, "slot2Inserted" to false),
        )

        // Enable soc-jpeg dual-codec v2 (encoder 0xFFFA0000 IRQ 135 + decoder 0xFFFA1000 IRQ 136)
        val jpegDir = Files.createDirectories(home.resolve("compoments/soc-jpeg"))
        Formats.atomicYaml(
            jpegDir.resolve("soc-jpeg.yaml"),
            mapOf("profile" to "soc-jpeg-codec-experimental-v2", "completionDelayTicks" to 32L),
        )

        // Enable GICv2 for IRQs 133, 135, 136
        val gicDir = Files.createDirectories(home.resolve("compoments/soc-gic"))
        Formats.atomicYaml(gicDir.resolve("soc-gic.yaml"), mapOf("profile" to "gicv2-catalog-experimental-v1"))

        val catalog = AddressCatalog.load(E2eHarness.projectRoot.resolve("catalog/compiled/catalog.json"))
        val picW = 1024
        val picH = 576
        val picStride = 2560
        val picAddr = 0x8999_C000L
        val osdAddr = 0x0614_0000L
        val encJpegAddr = 0x8600_0000L
        val playJpegAddr = 0x8640_0000L
        val playMemYcAddr = 0x8800_0000L
        val playDispAddr = 0x8A00_0000L
        val maxJpegBuf = 512 * 1024

        val dma = MappedDmaMemory().apply {
            map(picAddr, (picH - 1) * picStride + picW * 2)
            map(osdAddr, 1024 * 768 * 4)
            map(encJpegAddr, maxJpegBuf)
            map(playJpegAddr, maxJpegBuf)
            map(playMemYcAddr, 1024 * 768 * 2 + 4096)
            map(playDispAddr, 1024 * 768 * 2 + 4096)
        }

        val capturedJpeg = E2eHarness.withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                host.ports.register("cpu", "cpu.memory", DmaMemory::class.java, dma)
                val gicDist = checkNotNull(host.ports.find("soc-gic.distributor", MmioDevice::class.java))
                val gicIface = checkNotNull(host.ports.find("soc-gic.interface", MmioDevice::class.java))
                val gicCpu = checkNotNull(host.ports.find("soc-gic.cpu", CpuInterruptPort::class.java))
                val gicSink = checkNotNull(host.ports.find("soc-gic.lines", InterruptSink::class.java))
                configureGic(gicDist, gicIface, listOf(133, 135, 136))

                val sensor = checkNotNull(host.ports.find("cmos", SensorPort::class.java))
                val sd = checkNotNull(host.ports.find("sdcard-reader", BlockStoragePort::class.java))
                val buttons = checkNotNull(host.ports.find("buttons", ButtonPort::class.java))
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val jpegEnc = checkNotNull(host.ports.find("soc-jpeg.encoder", MmioDevice::class.java)) as ScheduledMmioDevice
                val jpegDec = checkNotNull(host.ports.find("soc-jpeg.decoder-v2", MmioDevice::class.java)) as ScheduledMmioDevice

                val discoveryCfg = mapOf(
                    "discovery" to mapOf(
                        "inputs" to mapOf("gfx100s.fff72028.config-words.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0)),
                        "imageLiveViewBanks" to "t80-isp-banks-v1",
                        "imageScalerF000" to "fff7f000-irq133-completion-v1",
                    ),
                )
                val ctx = object : DiscoveryContext {
                    override fun core() = 0
                    override fun tick() = host.tick
                    override fun pc() = 0x021E16D8L
                    override fun lr() = 0x0233C880L
                    override fun sensorPort() = sensor
                    override fun dmaMemory() = dma
                    override fun interruptSink() = gicSink
                    override fun lensParameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
                }
                val discoveryBus = checkNotNull(CatalogRuntime(catalog, "discovery", E2eHarness.projectRoot, discoveryCfg).createBus(ctx))
                val router = MmioRouter(
                    listOf(
                        MmioWindow("soc-jpeg.encoder", 0xFFFA0000L, 0x134L, jpegEnc),
                        MmioWindow("soc-jpeg.decoder-v2", 0xFFFA1000L, 0x12CL, jpegDec),
                    ),
                    discoveryBus,
                )

                // Stage 1: Program T80 ISP live-view and verify Shooting HUD with capture scene QR
                var seq = 0L
                discoveryBus.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
                discoveryBus.access(++seq, 0xfff730c4L, 4, true, picAddr)
                discoveryBus.access(++seq, 0xfff730d0L, 4, true, picStride.toLong())
                discoveryBus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)

                val osdBuf = ByteArray(1024 * 768 * 4)
                E2eHarness.paintShootingHudOsd0(osdBuf)
                dma.writeBytes(osdAddr, osdBuf)
                E2eHarness.configureLcdc1Composite(lcdc1, osdAddr, picAddr, picStride, enableOsd0 = true)
                assertTrue(discoveryBus.pumpLiveViewDma(1000L))
                lcdc1.write(0x600, 4, 1L)
                val liveHudPx = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(liveHudPx))
                assertEquals(capturePayload, QrTestCodec.decode(liveHudPx, main.width, main.height))

                // Stage 2: Press S1/S2 shutter -> Hardware JPEG Encode (0xFFFA0000, GIC IRQ 135)
                val btnRev0 = host.components().single { it.descriptor.id == "buttons" }.revision
                host.patch("buttons", btnRev0, mapOf("shutterHalf" to true)).join()
                assertTrue(buttons.pressed("shutterHalf"))
                host.patch("buttons", btnRev0 + 1, mapOf("shutterFull" to true)).join()
                assertTrue(buttons.pressed("shutterFull"))

                // Firmware 0x021E15FC / 0x021E173C / 0x021E16D8 hardware JPEG encode sequence
                router.write(0xFFFA0118L, 4, 1L)
                router.write(0xFFFA0000L, 4, 0x100L)
                assertEquals(0L, router.read(0xFFFA0000L, 4) and 0x100L)
                router.write(0xFFFA000CL, 4, 0x000A_0000L)
                router.write(0xFFFA0040L, 4, 0L)
                router.write(0xFFFA0044L, 4, (picH.toLong() shl 16) or picW.toLong())
                router.write(0xFFFA0048L, 4, 0x11L)
                router.write(0xFFFA0100L, 4, picAddr)
                router.write(0xFFFA010CL, 4, encJpegAddr)
                router.write(0xFFFA0110L, 4, maxJpegBuf.toLong())
                router.write(0xFFFA011CL, 4, 1L)
                router.write(0xFFFA012CL, 4, (picH.toLong() shl 16) or picW.toLong())
                router.write(0xFFFA0130L, 4, picStride.toLong())
                router.write(0xFFFA0028L, 4, 0xFFFD_FFEFL) // unmask bit 4 ENC_DONE & bit 17 error
                router.write(0xFFFA0000L, 4, 1L) // start encode

                val encDeadline = assertNotNull(router.nextDeadline(host.tick))
                host.advanceTick(encDeadline)
                router.beforeInstruction(host.tick)
                assertEquals(135, gicCpu.choose(), "Expected GIC IRQ 135 pending after hardware JPEG encode")
                val iarEnc = gicIface.read(0x00CL, 4)
                assertEquals(135L, iarEnc and 0x3FFL)
                assertEquals(0x10L, router.read(0xFFFA0024L, 4))
                val encodedLen = router.read(0xFFFA0094L, 4).toInt()
                assertTrue(encodedLen in 2048..maxJpegBuf, "Expected valid encoded JPEG size, got $encodedLen")
                router.write(0xFFFA0024L, 4, 0x10L)
                gicIface.write(0x010L, 4, iarEnc)
                assertEquals(null, gicCpu.choose())

                host.patch("buttons", btnRev0 + 2, mapOf("shutterFull" to false, "shutterHalf" to false)).join()

                // Stage 3: Write hardware-encoded JPEG to writable blank SD card as DCIM/100_FUJI/DSCF0001.JPG
                val encodedJpeg = dma.readBytes(encJpegAddr, encodedLen)
                assertEquals(0xFF.toByte(), encodedJpeg[0])
                assertEquals(0xD8.toByte(), encodedJpeg[1])
                writeDscf0001ViaBlockStorage(sd, encodedJpeg)

                // The card port exposes pending writes to the guest immediately, including during playback.
                (sd as com.github.nyabanban.fuji.emulator.api.CheckedBlockStoragePort).flush(1)
                val sdReadBack = readDscf0001ViaBlockStorage(sd)
                assertContentEquals(encodedJpeg, sdReadBack)

                // Stage 4: Press PLAY -> load DSCF0001.JPG from SD -> HW JPEG decode (0xFFFA1000, IRQ 136) -> UniResz (0xFFF7F000, IRQ 133) -> LCDC1
                host.patch("buttons", btnRev0 + 3, mapOf("play" to true)).join()
                assertTrue(buttons.pressed("play"))
                dma.writeBytes(playJpegAddr, sdReadBack)

                router.write(0xFFFA1118L, 4, 1L)
                router.write(0xFFFA1004L, 4, router.read(0xFFFA1004L, 4))
                router.write(0xFFFA1000L, 4, 0x100L)
                router.write(0xFFFA1008L, 4, 0x00FD_0020L)
                router.write(0xFFFA10A0L, 4, 0L)
                router.write(0xFFFA1100L, 4, playMemYcAddr)
                router.write(0xFFFA1104L, 4, (picW * picH * 2).toLong())
                router.write(0xFFFA110CL, 4, playJpegAddr)
                router.write(0xFFFA1110L, 4, sdReadBack.size.toLong())
                router.write(0xFFFA111CL, 4, 0x10L)
                router.write(0xFFFA1120L, 4, 0L)
                router.write(0xFFFA1000L, 4, 1L)

                val decDeadline = assertNotNull(router.nextDeadline(host.tick))
                host.advanceTick(decDeadline)
                router.beforeInstruction(host.tick)
                assertEquals(136, gicCpu.choose(), "Expected GIC IRQ 136 pending after hardware JPEG decode")
                val iarDec = gicIface.read(0x00CL, 4)
                assertEquals(136L, iarDec and 0x3FFL)
                assertEquals(0x10L, router.read(0xFFFA1004L, 4))
                router.write(0xFFFA1004L, 4, 0x10L)
                gicIface.write(0x010L, 4, iarDec)
                assertEquals(null, gicCpu.choose())

                // Scale decoded MemYc -> display PIC1 buffer via UniResz (0xFFF7F000, IRQ 133)
                val playDispStride = 1024 * 2
                router.write(0xFFF7F000L, 4, 0L)
                router.write(0xFFF7F008L, 4, 0x100L)
                router.write(0xFFF7F00CL, 4, ((picH - 1).toLong() shl 16) or (picW - 1).toLong())
                router.write(0xFFF7F010L, 4, 0L)
                router.write(0xFFF7F014L, 4, ((picH - 1).toLong() shl 16) or (picW - 1).toLong())
                router.write(0xFFF7F018L, 4, ((picH - 1).toLong() shl 16) or (picW - 1).toLong())
                router.write(0xFFF7F0C4L, 4, playMemYcAddr)
                router.write(0xFFF7F0C8L, 4, (picW * 2).toLong())
                router.write(0xFFF7F0D0L, 4, playDispAddr)
                router.write(0xFFF7F0D4L, 4, playDispStride.toLong())
                router.write(0xFFF7F004L, 4, 1L)

                val scalerDue = assertNotNull(router.nextDeadline(host.tick))
                host.advanceTick(scalerDue)
                router.beforeInstruction(host.tick)
                assertEquals(133, gicCpu.choose(), "Expected GIC IRQ 133 pending after UniResz scale")
                val iarScaler = gicIface.read(0x00CL, 4)
                assertEquals(133L, iarScaler and 0x3FFL)
                router.write(0xFFF7F0E4L, 4, 1L)
                gicIface.write(0x010L, 4, iarScaler)
                assertEquals(null, gicCpu.choose())

                // Present PLAY frame on LCDC1 display.main (OSD0 cleared for clean photo playback)
                osdBuf.fill(0)
                dma.writeBytes(osdAddr, osdBuf)
                E2eHarness.configureLcdc1Composite(lcdc1, osdAddr, playDispAddr, playDispStride, picY = 96, picHeight = picH, picWidth = picW, enableOsd0 = true)
                lcdc1.write(0x600, 4, 1L)

                val playPx = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(playPx))
                assertEquals(capturePayload, QrTestCodec.decode(playPx, main.width, main.height), "PLAY mode must decode captured DSCF0001.JPG from SD card")
                val refPhoto = E2eHarness.decodeJpegSubsampled(sdReadBack, subsample = 1)
                val activeTop = (96 * main.height) / 768
                val activeH = (picH * main.height) / 768
                val activePlayPx = E2eHarness.cropArgb(playPx, main.width, main.height, 0, activeTop, main.width, activeH)
                val corr = E2eHarness.normalizedCrossCorrelation(
                    activePlayPx,
                    main.width,
                    activeH,
                    E2eHarness.toArgbPixels(refPhoto),
                    refPhoto.width,
                    refPhoto.height,
                )
                assertTrue(corr > 0.85, "Expected active PLAY decoded correlation > 0.85 against SD DSCF0001.JPG, got $corr")
                host.patch("buttons", btnRev0 + 4, mapOf("play" to false)).join()

                // Stage 5: Hot-swap CMOS to new live scene + press BACK -> verify clean return to Live View HUD
                val cmosRev = host.components().single { it.descriptor.id == "cmos" }.revision
                host.patch("cmos", cmosRev, mapOf("sourceKind" to "JPEG", "sourceFile" to "return-live.jpg", "loop" to true)).join()
                host.patch("buttons", btnRev0 + 5, mapOf("back" to true)).join()
                assertTrue(buttons.pressed("back"))

                E2eHarness.paintShootingHudOsd0(osdBuf)
                dma.writeBytes(osdAddr, osdBuf)
                E2eHarness.configureLcdc1Composite(lcdc1, osdAddr, picAddr, picStride, enableOsd0 = true)
                assertTrue(discoveryBus.pumpLiveViewDma(host.tick + 10L))
                lcdc1.write(0x600, 4, 1L)

                val returnHudPx = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(returnHudPx))
                assertEquals(0xff2040e0.toInt(), returnHudPx[10 * main.width + 20], "Expected returned Live View HUD top bar")
                assertEquals(returnLivePayload, QrTestCodec.decode(returnHudPx, main.width, main.height), "Expected live CMOS return scene after BACK")
                host.patch("buttons", btnRev0 + 6, mapOf("back" to false)).join()
                encodedJpeg
            }.join()
        }

        // Closing the component drains its write-back queue before inspecting the physical FAT copies.
        val persisted = E2eHarness.inspectFatFilesystem(sdImg)
        assertTrue(persisted.fatMirrorValid, "FAT mirrors disagree after close")
        assertTrue(persisted.fsInfoValid, "Invalid FSInfo after close")
        assertTrue(persisted.fat32EocMarkersValid, "Invalid FAT32 end-of-chain markers after close")
        assertTrue("DCIM" in persisted.rootEntries, "DCIM missing after close")
        assertContentEquals(capturedJpeg, E2eHarness.extractFat32File(sdImg, "DCIM/100_FUJI/DSCF0001.JPG"))
    }
}

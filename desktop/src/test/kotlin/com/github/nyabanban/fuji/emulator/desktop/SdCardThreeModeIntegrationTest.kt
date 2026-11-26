package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.PortBinding
import com.github.nyabanban.fuji.emulator.firmware.SdInitContracts
import com.github.nyabanban.fuji.emulator.firmware.SdTransactionContracts
import com.github.nyabanban.fuji.emulator.runtime.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** End-to-end SDHCI v7 DMA integration test for qcow2 and directory backends through real component JARs. */
class SdCardThreeModeIntegrationTest {
    private val projectRoot = Path.of(System.getProperty("componentProjectRoot"))
    private val base = 0xfffc4000L

    private fun syntheticCid(): String {
        val bytes = byteArrayOf(
            0xfe.toByte(), 0x54, 0x45, 0x53, 0x54, 0x30, 0x30, 0x31,
            0x10, 1, 2, 3, 4, 1, 0xa1.toByte(), 0,
        )
        var crc = 0
        for (byte in bytes.take(15)) for (bit in 7 downTo 0) {
            val feedback = ((byte.toInt() ushr bit) and 1) xor ((crc ushr 6) and 1)
            crc = (crc shl 1) and 0x7f
            if (feedback != 0) crc = crc xor 9
        }
        bytes[15] = ((crc shl 1) or 1).toByte()
        return java.util.HexFormat.of().formatHex(bytes)
    }

    private fun sdhcV7Input(capacityBytes: Long, readOnly: Boolean = false): Map<String, Any?> = mapOf(
        "schema" to 1,
        "profile" to SdTransactionContracts.ADDRESS_PROFILE,
        "legacy" to mapOf(
            "schema" to 1,
            "profile" to SdInitContracts.PHY_PROFILE,
            "initialRegisters" to (0..1).associate {
                it.toString() to mapOf(
                    "word80" to 0, "control623e" to 0, "mixed6078" to 0,
                    "control403e" to 0, "word60ec" to 0,
                )
            },
            "clocks" to listOf("0:0", "0:1", "1:0", "1:1").associateWith {
                mapOf("initialWord" to if (it.endsWith(":0")) 5 else 0, "bit1Samples" to listOf(1, 1, 1))
            },
            "initWords" to (0..1).associate {
                it.toString() to mapOf(
                    "capability6244" to 0, "present4024" to 0, "present6224" to 0,
                    "signal6238" to 0, "block4006" to 0, "host6228" to 0, "host4028" to 0x0f00,
                )
            },
        ),
        "cards" to mapOf(
            "0" to mapOf(
                "basis" to "SYNTHETIC",
                "capacityBytes" to capacityBytes,
                "highCapacity" to true,
                "readOnly" to readOnly,
                "rca" to 1,
                "cidHex" to syntheticCid(),
                "ocrVoltageWindow" to 0x00ff8000L,
                "supportsHighSpeed" to true,
            ),
            "1" to null,
        ),
        "commandTicks" to 2,
        "blockTicks" to 3,
        "resetTicks" to 2,
        "addressHighBits" to mapOf("0" to 0, "1" to 0),
    )

    private fun stageJars(home: Path) {
        val compDir = home.resolve("compoments")
        Files.createDirectories(compDir)
        for (id in listOf("sdcard-reader", "soc-gic", "soc-sd-host")) {
            Files.copy(
                projectRoot.resolve("build/emulator/compoments/$id-0.3.0.jar"),
                compDir.resolve("$id-0.3.0.jar"),
            )
        }
    }

    private fun withSdHost(home: Path, block: (OwnerLoop, ComponentHost, MmioRouter, ByteArray) -> Unit) {
        val owner = OwnerLoop()
        val catalog = AddressCatalog.bundled()
        val host = ComponentHost(owner, home.resolve("compoments"), catalog)
        val ram = ByteArray(0x10000) { 0x6d }
        try {
            lateinit var router: MmioRouter
            owner.submit {
                host.load()
                val sd = host.components().single { it.descriptor.id == "soc-sd-host" }.component
                val claims = sd.additionalMmioPorts().toSet()
                val windows = catalog.windowsFor(mapOf("soc-sd-host" to SdTransactionContracts.ADDRESS_PROFILE)).filter {
                    it.componentId == "soc-gic" || (it.componentId == "soc-sd-host" && it.portId in claims)
                }
                val profiles = configuredComponentProfiles(host, windows.map { it.componentId }.toSet())
                val runtime = CatalogRuntime(catalog, "strict", home, emptyMap())
                runtime.bindPorts(
                    windows.map { PortBinding(it.componentId, it.portId, it.base, it.size) },
                    catalog.defaultMemoryProfile,
                    catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),
                    profiles,
                )
                router = MmioRouter(
                    windows.map {
                        MmioWindow(it.id, it.base, it.size, checkNotNull(host.ports.find(it.portId, MmioDevice::class.java)))
                    },
                )
                host.ports.register("test-physical-memory", "cpu.memory", DmaMemory::class.java, object : DmaMemory {
                    override fun check(address: Long, size: Long, write: Boolean) {
                        require(address >= 0 && size >= 0 && address <= ram.size && size <= ram.size - address)
                    }
                    override fun read(address: Long, destination: ByteBuffer) {
                        check(address, destination.remaining().toLong(), false)
                        destination.put(ram, address.toInt(), destination.remaining())
                    }
                    override fun write(address: Long, source: ByteBuffer) {
                        check(address, source.remaining().toLong(), true)
                        source.get(ram, address.toInt(), source.remaining())
                    }
                })
                router.write(base + 0x34, 2, 0xffff)
                router.write(base + 0x36, 2, 0xffff)
                router.write(base + 0x38, 2, 0)
                router.write(base + 0x3a, 2, 0)
            }.join()
            block(owner, host, router, ram)
        } finally {
            owner.submit { host.close() }.join()
            owner.close()
        }
    }

    private fun initSdhcCard(host: ComponentHost, router: MmioRouter) {
        fun advance(ticks: Int = 16) = repeat(ticks) {
            host.advanceTick(host.tick + 1)
            router.beforeInstruction(host.tick)
        }
        fun issue(index: Int, arg: Long = 0L, flags: Int = 0x1a, mode: Int = 0) {
            router.write(base + 0x30, 2, 0xffff)
            router.write(base + 0x32, 2, 0xffff)
            router.write(base + 0x08, 2, arg and 0xffff)
            router.write(base + 0x0a, 2, arg ushr 16)
            router.write(base + 0x0c, 4, (index.toLong() shl 24) or (flags.toLong() shl 16) or mode.toLong())
            advance()
        }
        fun resp() = router.read(base + 0x10, 2) or (router.read(base + 0x12, 2) shl 16)

        advance(2)
        issue(0, flags = 0)
        issue(8, 0x1aa)
        assertEquals(0x1aaL, resp() and 0xfff)
        var ready = false
        repeat(8) {
            if (!ready) {
                issue(55)
                issue(41, 0x40ff8000, flags = 2)
                ready = resp() and 0x80000000L != 0L
            }
        }
        assertTrue(ready)
        assertNotEquals(0L, resp() and 0x40000000L, "SDHC must advertise CCS=1 block addressing")
        issue(2, flags = 9)
        issue(3)
        issue(9, 0x10000, flags = 9)
        issue(7, 0x10000, flags = 0x1b)
    }

    private fun dmaReadSector(host: ComponentHost, router: MmioRouter, ram: ByteArray, lba: Long): ByteArray {
        ram.fill(0x6d)
        router.write(base + 0x00, 4, 0x1000L) // SDMA addr = 0x1000
        router.write(base + 0x04, 2, 0x7000L or 512L)
        router.write(base + 0x06, 2, 1L)
        router.write(base + 0x30, 2, 0xffff)
        router.write(base + 0x32, 2, 0xffff)
        router.write(base + 0x08, 2, lba and 0xffff)
        router.write(base + 0x0a, 2, lba ushr 16)
        // CMD17 single-block read with DMA (mode = 0x11: read + DMA)
        router.write(base + 0x0c, 4, (17L shl 24) or (0x3aL shl 16) or 0x11L)
        repeat(32) {
            host.advanceTick(host.tick + 1)
            router.beforeInstruction(host.tick)
        }
        assertEquals(0L, router.read(base + 0x32, 2), "SDHCI error status must be 0")
        return ram.copyOfRange(0x1000, 0x1200)
    }

    private fun dmaWriteSector(host: ComponentHost, router: MmioRouter, ram: ByteArray, lba: Long, payload: ByteArray) {
        require(payload.size == 512)
        System.arraycopy(payload, 0, ram, 0x2000, 512)
        router.write(base + 0x00, 4, 0x2000L) // SDMA addr = 0x2000
        router.write(base + 0x04, 2, 0x7000L or 512L)
        router.write(base + 0x06, 2, 1L)
        router.write(base + 0x30, 2, 0xffff)
        router.write(base + 0x32, 2, 0xffff)
        router.write(base + 0x08, 2, lba and 0xffff)
        router.write(base + 0x0a, 2, lba ushr 16)
        // CMD24 single-block write with DMA (mode = 0x01: write + DMA)
        router.write(base + 0x0c, 4, (24L shl 24) or (0x3aL shl 16) or 0x01L)
        repeat(32) {
            host.advanceTick(host.tick + 1)
            router.beforeInstruction(host.tick)
        }
        assertEquals(0L, router.read(base + 0x32, 2), "SDHCI error status must be 0")
    }

    @Test
    fun sdhcV7DmaReadsAndWritesTransparentlyAgainstAutoInitializedQcow2(@TempDir tempRoot: Path) {
        val home = tempRoot.resolve("qcow2-home")
        val cap4g = 4L * 1024L * 1024L * 1024L
        stageJars(home)
        val readerDir = home.resolve("compoments/sdcard-reader")
        val sdHostDir = home.resolve("compoments/soc-sd-host")
        Files.createDirectories(readerDir)
        Files.createDirectories(sdHostDir)
        Formats.atomicYaml(
            readerDir.resolve("sdcard-reader.yaml"),
            mapOf(
                "slot1Image" to "slot1.qcow2",
                "slot1Inserted" to true,
                "slot1ReadOnly" to false,
                "slot1Backend" to "qcow2",
                "slot1CapacityBytes" to cap4g,
                "slot2Inserted" to false,
            ),
        )
        Formats.atomicYaml(sdHostDir.resolve("soc-sd-host.yaml"), mapOf("profile" to SdTransactionContracts.ADDRESS_PROFILE, "inputFile" to "input.yaml"))
        Formats.atomicYaml(sdHostDir.resolve("input.yaml"), sdhcV7Input(cap4g, readOnly = false))

        assertEquals(1, RelaxedFlashPersistence.ensureBlankSdImages(home, mapOf("executionMode" to "relaxed", "components" to "compoments")))
        assertTrue(Files.size(readerDir.resolve("slot1.qcow2")) <= 512L * 1024L)

        val pattern = ByteArray(512) { idx -> ((idx * 17 + 0x42) and 0xFF).toByte() }
        withSdHost(home) { owner, host, router, ram ->
            owner.submit {
                initSdhcCard(host, router)
                assertContentEquals(ByteArray(512), dmaReadSector(host, router, ram, 2048L))
                dmaWriteSector(host, router, ram, 2048L, pattern)
                assertContentEquals(pattern, dmaReadSector(host, router, ram, 2048L))
            }.join()
        }

        // Reopen fresh ComponentHost and confirm QCOW2 sector persisted cleanly
        withSdHost(home) { owner, host, router, ram ->
            owner.submit {
                initSdhcCard(host, router)
                assertContentEquals(pattern, dmaReadSector(host, router, ram, 2048L))
            }.join()
        }
    }

    @Test
    fun sdhcV7DmaReadsVirtualFat32MbrVbrAndHostPhotoFromDirectoryBackend(@TempDir tempRoot: Path) {
        val home = tempRoot.resolve("dir-home")
        val cap4g = 4L * 1024L * 1024L * 1024L
        stageJars(home)
        val readerDir = home.resolve("compoments/sdcard-reader")
        val sdHostDir = home.resolve("compoments/soc-sd-host")
        val cardFolder = readerDir.resolve("slot1-folder")
        Files.createDirectories(cardFolder.resolve("DCIM/100_FUJI"))
        Files.createDirectories(sdHostDir)
        val photoBytes = "FUJIFILM-GFX100S-VIRTUAL-FAT32-TEST-PAYLOAD".toByteArray(StandardCharsets.UTF_8)
        Files.write(cardFolder.resolve("DCIM/100_FUJI/DSCF0001.JPG"), photoBytes)

        Formats.atomicYaml(
            readerDir.resolve("sdcard-reader.yaml"),
            mapOf(
                "slot1Image" to "slot1-folder",
                "slot1Inserted" to true,
                "slot1ReadOnly" to false,
                "slot1Backend" to "directory",
                "slot1CapacityBytes" to cap4g,
                "slot2Inserted" to false,
            ),
        )
        Formats.atomicYaml(sdHostDir.resolve("soc-sd-host.yaml"), mapOf("profile" to SdTransactionContracts.ADDRESS_PROFILE, "inputFile" to "input.yaml"))
        Formats.atomicYaml(sdHostDir.resolve("input.yaml"), sdhcV7Input(cap4g, readOnly = false))

        withSdHost(home) { owner, host, router, ram ->
            owner.submit {
                initSdhcCard(host, router)
                val mbr = dmaReadSector(host, router, ram, 0L)
                assertEquals(0x55.toByte(), mbr[0x1FE])
                assertEquals(0xAA.toByte(), mbr[0x1FF])
                assertEquals(0x0C, mbr[0x1BE + 4].toInt() and 0xFF)
                val partStart = ((mbr[0x1BE + 8].toInt() and 0xFF) or
                    ((mbr[0x1BE + 9].toInt() and 0xFF) shl 8) or
                    ((mbr[0x1BE + 10].toInt() and 0xFF) shl 16) or
                    ((mbr[0x1BE + 11].toInt() and 0xFF) shl 24)).toLong()
                val vbr = dmaReadSector(host, router, ram, partStart)
                assertEquals("FAT32   ", String(vbr, 0x52, 8, StandardCharsets.US_ASCII))
            }.join()
        }
        assertTrue(Files.isRegularFile(readerDir.resolve("slot1-folder.fuji-sd.json")))
    }
}

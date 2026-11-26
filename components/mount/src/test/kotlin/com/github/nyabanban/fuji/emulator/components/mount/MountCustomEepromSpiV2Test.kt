package com.github.nyabanban.fuji.emulator.components.mount

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MountCustomEepromSpiV2Test {
    @TempDir
    lateinit var tempDir: Path

    private fun readBulkEeprom128K(spi: SpiPeer): ByteArray {
        fun checksum(word: Long): Long =
            (((word ushr 27) and 0x1fL) + ((word ushr 22) and 0x1fL) + ((word ushr 17) and 0x1fL) +
                ((word ushr 12) and 0x1fL) + ((word ushr 7) and 0x1fL) + ((word ushr 6) and 1L)) and 0x1fL

        fun buildFrame(cmdId: Long, frameType: Long, payload16: Long = 0L): Long {
            val base = ((payload16 and 0xffffL) shl 16) or ((frameType and 1L) shl 15) or ((cmdId and 0x1ffL) shl 6)
            return base or (checksum(base) shl 1)
        }

        fun sendFrame(word: Long): Long {
            var acc = 0L
            for (shift in listOf(24, 16, 8, 0)) {
                val out = spi.exchange(8, ((word ushr shift) and 0xffL).toInt(), 100L)
                acc = (acc shl 8) or (out.toLong() and 0xffL)
            }
            return acc
        }

        assertEquals(0L, sendFrame(MountFactory.SYNC_MASTER_PROBE))
        assertEquals(MountFactory.SYNC_SLAVE_READY, sendFrame(MountFactory.SYNC_MASTER_PROBE))
        assertEquals(MountFactory.SYNC_MASTER_PROBE, sendFrame(buildFrame(0x018L, 0L, 11L shl 11)))
        assertEquals(0x018L, (sendFrame(buildFrame(0x018L, 1L, 0x0800L)) ushr 6) and 0x1ffL)
        assertEquals(0x018L, (sendFrame(buildFrame(0x0C0L, 0L, 6L)) ushr 6) and 0x1ffL)
        assertEquals(0x0C0L, (sendFrame(buildFrame(0x0C0L, 1L, 0x0800L)) ushr 6) and 0x1ffL)
        val dataC0 = sendFrame(0L)
        assertEquals(0x0C0L, (dataC0 ushr 6) and 0x1ffL)
        assertEquals(0x0040L, (dataC0 ushr 16) and 0xffffL)
        assertEquals(1L, (sendFrame(buildFrame(0L, 1L, 0x0800L)) ushr 15) and 1L)

        val ram = ByteArray(0x20000)
        for (blk in 0 until 64) {
            val pkt = ByteArray(2051) { i -> spi.exchange(8, if (i < 2) 0xff else 0x00, 200L + blk * 3000L + i).toByte() }
            val destOff = if (blk < 60) 0x02000 + blk * 2048 else (blk - 60) * 2048
            pkt.copyInto(ram, destOff, 2, 2050)
        }
        // Final END transfer
        ByteArray(2051) { i -> spi.exchange(8, if (i < 2) 0xff else 0x00, 999999L + i).toByte() }
        return ram
    }

    private fun createHarness(customModuleEeprom: ByteArray?): ComponentContext {
        val portsMap = linkedMapOf<String, Any>()
        val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                portsMap[name] = port
            }
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> find(name: String, type: Class<T>): T? = portsMap[name] as? T
            override fun removeOwner(owner: String) = Unit
        }
        val fakeLens = object : LensPort, ElectronicLensPort, ModularLensPort {
            override fun parameters() = LensParameters("GF50mmF2Custom", 50.0, 2.0, 2.5, 0.92, 0.0)
            override val electronicContacts: Boolean = true
            override val oisEnabled: Boolean = false
            override val minFocalLengthMm: Double = 50.0
            override val maxFocalLengthMm: Double = 50.0
            override val minAperture: Double = 2.0
            override val maxAperture: Double = 22.0
            override val minFocusDistanceM: Double = 0.5
            override val focusMode: String = "AF-S"
            override val activeLensId: String = "custom-eeprom-lens"
            override val activeAdapterId: String? = null
            override val communicationMode: LensCommunicationMode = LensCommunicationMode.GENERIC_SIMULATED
            override val controlContract: LensControlContract = LensControlContract.GenericSimulated(
                LenscomIdentitySpec(shortId = "GF50CUS", modelAscii = "GF50mmF2Custom"),
            )
            override val lenscomIdentity: LenscomIdentitySpec =
                (controlContract as LensControlContract.GenericSimulated).identitySpec
            override val realFirmwarePayload: ByteArray? = null
            override val focusMotorBusy: Boolean = false
            override val focusEncoderPosition: Int = 0
            override fun commandFocusEncoder(targetEncoder: Int) = Unit
            override fun commandApertureThirdStopIndex(avThirds: Int) = Unit
            override val customEepromBytes: ByteArray? = customModuleEeprom
        }
        ports.register("lens", "lens", LensPort::class.java, fakeLens)
        return object : ComponentContext {
            override val directory: Path = tempDir
            override val clock: EmulationClock = EmulationClock { 0L }
            override val ports: Ports = ports
            override val events: EventChannel = object : EventChannel {
                override fun publish(event: ComponentEvent) = Unit
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override fun dataPath(relative: String): Path = tempDir.resolve(relative)
        }
    }

    @Test
    fun `cmd 0x0C0 serves ModularLensPort customEepromBytes and prioritizes explicit eepromFile over module EEPROM`() {
        val moduleEeprom = ByteArray(MountFactory.LENS_EEPROM_BYTES) { idx -> ((idx * 17 + 0x33) and 0xff).toByte() }
        val fileEeprom = ByteArray(MountFactory.LENS_EEPROM_BYTES) { idx -> ((idx * 29 + 0xA7) and 0xff).toByte() }
        Files.write(tempDir.resolve("override-eeprom.bin"), fileEeprom)

        // 1. No explicit eepromFile -> MountFactory serves ModularLensPort.customEepromBytes verbatim!
        val ctx1 = createHarness(customModuleEeprom = moduleEeprom)
        val mount1 = MountFactory().create(
            ctx1,
            mapOf("attached" to true, "lensPort" to "lens", "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE),
        )
        try {
            val spi1 = checkNotNull(ctx1.ports.find("mount.spi", SpiPeer::class.java))
            val read1 = readBulkEeprom128K(spi1)
            assertContentEquals(moduleEeprom, read1)
        } finally {
            mount1.close()
        }

        // 2. Explicit eepromFile takes precedence over ModularLensPort.customEepromBytes!
        val ctx2 = createHarness(customModuleEeprom = moduleEeprom)
        val mount2 = MountFactory().create(
            ctx2,
            mapOf(
                "attached" to true,
                "lensPort" to "lens",
                "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE,
                "eepromFile" to "override-eeprom.bin",
            ),
        )
        try {
            val spi2 = checkNotNull(ctx2.ports.find("mount.spi", SpiPeer::class.java))
            val read2 = readBulkEeprom128K(spi2)
            assertContentEquals(fileEeprom, read2)
        } finally {
            mount2.close()
        }

        // 3. Invalid customEepromBytes length fails fast instead of silently truncating
        val ctx3 = createHarness(customModuleEeprom = ByteArray(1024))
        val mount3 = MountFactory().create(
            ctx3,
            mapOf("attached" to true, "lensPort" to "lens", "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE),
        )
        try {
            val spi3 = checkNotNull(ctx3.ports.find("mount.spi", SpiPeer::class.java))
            assertFailsWith<IllegalArgumentException> {
                readBulkEeprom128K(spi3)
            }
        } finally {
            mount3.close()
        }
    }
}

package com.github.nyabanban.fuji.emulator.components.mount

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class MountSnapshotTest {
    private class MountTestHarness {
        private val registeredPorts = LinkedHashMap<String, Any>()
        val syntheticLens = LensParameters("gf-110mm", 110.0, 2.0, 3.0, 0.92, 0.01)

        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-mount-test")
            override val clock = EmulationClock { 0L }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    assertTrue(type.isInstance(port))
                    registeredPorts[name] = port
                }
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> find(name: String, type: Class<T>): T? = registeredPorts[name] as? T
                override fun removeOwner(owner: String) {
                    registeredPorts.clear()
                }
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = error("unexpected mount event")
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = error("unexpected subscribe")
            }
            override fun dataPath(relative: String): Path = error("unexpected dataPath")
        }

        init {
            context.ports.register("lens", "lens", LensPort::class.java, object : LensPort {
                override fun parameters(): LensParameters = syntheticLens
            })
        }

        fun mountPort(): MountPort = checkNotNull(context.ports.find("mount", MountPort::class.java))
    }

    @Suppress("UNCHECKED_CAST")
    private fun simulateJsonRoundTripMap(map: Map<String, Any?>): Map<String, Any?> =
        simulateJsonRoundTripValue(map) as Map<String, Any?>

    private fun simulateJsonRoundTripValue(value: Any?): Any? = when (value) {
        null, is Boolean, is String -> value
        is Byte, is Short, is Int -> (value as Number).toInt()
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else value
        is Float, is Double -> (value as Number).toDouble()
        is List<*> -> value.map { element -> simulateJsonRoundTripValue(element) }
        is Map<*, *> -> value.entries.associate { entry -> (entry.key as String) to simulateJsonRoundTripValue(entry.value) }
        else -> error("Unsupported non-JSON value: ${value::class.java.name}")
    }

    @Test
    fun roundTripPreservesAttachmentStateAndRejectsRestartOnlyLensPortChangesAtomically() {
        val factory = MountFactory()
        val defaultConfig = mapOf("attached" to true, "lensPort" to "lens")

        val harnessOne = MountTestHarness()
        val mountOne = factory.create(harnessOne.context, defaultConfig) as StatefulComponent
        assertEquals(harnessOne.syntheticLens, harnessOne.mountPort().attachedLens())
        mountOne.applySettings(mapOf("attached" to false))
        assertNull(harnessOne.mountPort().attachedLens())

        val snapshot = simulateJsonRoundTripMap(mountOne.saveSnapshot())

        val harnessTwo = MountTestHarness()
        val mountTwo = factory.create(harnessTwo.context, defaultConfig) as StatefulComponent
        mountTwo.restoreSnapshot(snapshot)
        assertNull(harnessTwo.mountPort().attachedLens())
        assertEquals(mountOne.state(), mountTwo.state())

        @Suppress("UNCHECKED_CAST")
        val validScalars = snapshot["scalarValues"] as Map<String, Any?>
        val invalidCandidates = listOf<Map<String, Any?>>(
            snapshot + ("schema" to 2),
            snapshot + ("componentId" to "lens"),
            snapshot + ("scalarValues" to (validScalars + ("lensPort" to "alternate-lens") + ("attached" to true))),
            snapshot + ("scalarValues" to (validScalars + ("attached" to 1))),
        )
        for (candidate in invalidCandidates) {
            assertFails { mountTwo.restoreSnapshot(candidate) }
            assertNull(harnessTwo.mountPort().attachedLens())
            assertEquals(mountOne.state(), mountTwo.state())
        }

        mountOne.applySettings(mapOf("attached" to true))
        mountTwo.applySettings(mapOf("attached" to true))
        assertEquals(harnessOne.mountPort().attachedLens(), harnessTwo.mountPort().attachedLens())
        assertEquals(mountOne.state(), mountTwo.state())
    }

    @Test
    fun contactBusAndMountSpiExchangeValid32BitFramesAndPersistAcrossRestore() {
        val factory = MountFactory()
        val config = mapOf("attached" to true, "lensPort" to "lens")
        val harnessOne = MountTestHarness()
        val mountOne = factory.create(harnessOne.context, config) as StatefulComponent
        val contacts = checkNotNull(harnessOne.context.ports.find("mount.contacts", ContactBus::class.java))
        val spi = checkNotNull(harnessOne.context.ports.find("mount.spi", SpiPeer::class.java))

        fun checksum(word: Long): Long =
            (((word ushr 27) and 0x1fL) + ((word ushr 22) and 0x1fL) + ((word ushr 17) and 0x1fL) +
                ((word ushr 12) and 0x1fL) + ((word ushr 7) and 0x1fL) + ((word ushr 6) and 1L)) and 0x1fL

        // Valid rx1 request (cmd=0x83, seq=2, payload=0x08, frameType=0)
        val base0 = (2L shl 24) or (0x08L shl 16) or (0x83L shl 6)
        val cmd0 = base0 or (checksum(base0) shl 1)
        val req = java.nio.ByteBuffer.allocate(4).putInt(cmd0.toInt()).apply { flip() }
        val resp = java.nio.ByteBuffer.allocate(4)
        assertEquals(4, contacts.exchange(req, resp))
        resp.flip()
        val reply0 = resp.int.toLong() and 0xffffffffL
        assertEquals(0L, reply0 and 1L)
        assertEquals(checksum(reply0), (reply0 ushr 1) and 0x1fL)
        assertEquals(0x83L, (reply0 ushr 6) and 0x1ffL)
        assertEquals(1L, (reply0 ushr 15) and 1L)
        assertEquals(1L, (reply0 ushr 27) and 0x1fL)
        assertEquals(0L, (reply0 ushr 16) and 0x1fL)
        assertEquals(2L, (reply0 ushr 24) and 0x7L)

        // SPI 1-frame pipelined exchange: frame 0 clocks out 0, frame 1 (frameType=1) clocks out frame 0 reply
        val base1 = (1L shl 27) or (3L shl 24) or (1L shl 15) or (0x12L shl 6)
        val cmd1 = base1 or (checksum(base1) shl 1)
        fun sendFrame(word: Long): Long {
            var acc = 0L
            for (shift in listOf(24, 16, 8, 0)) {
                val out = spi.exchange(8, ((word ushr shift) and 0xffL).toInt(), 100L)
                acc = (acc shl 8) or (out.toLong() and 0xffL)
            }
            return acc
        }
        assertEquals(0L, sendFrame(cmd0))
        val midSnap = simulateJsonRoundTripMap(mountOne.saveSnapshot())
        val harnessTwo = MountTestHarness()
        val mountTwo = factory.create(harnessTwo.context, config) as StatefulComponent
        mountTwo.restoreSnapshot(midSnap)
        val spiTwo = checkNotNull(harnessTwo.context.ports.find("mount.spi", SpiPeer::class.java))
        var pipe1 = 0L
        for (shift in listOf(24, 16, 8, 0)) {
            pipe1 = (pipe1 shl 8) or (spiTwo.exchange(8, ((cmd1 ushr shift) and 0xffL).toInt(), 200L).toLong() and 0xffL)
        }
        assertEquals(reply0, pipe1)

        // Poll with 0xA55A3CC3 clocks out 0 (since cmd1 had frameType=1)
        var pipe2 = 0L
        for (shift in listOf(24, 16, 8, 0)) {
            pipe2 = (pipe2 shl 8) or (spiTwo.exchange(8, ((0xA55A3CC3L ushr shift) and 0xffL).toInt(), 300L).toLong() and 0xffL)
        }
        assertEquals(0L, pipe2)

        // Detached mount stops responding on contact bus
        mountTwo.applySettings(mapOf("attached" to false))
        val emptyResp = java.nio.ByteBuffer.allocate(4)
        assertEquals(0, checkNotNull(harnessTwo.context.ports.find("mount.contacts", ContactBus::class.java))
            .exchange(java.nio.ByteBuffer.allocate(4).putInt(cmd0.toInt()).apply { flip() }, emptyResp))
    }

    @Test
    fun pipelinedLenscomProtocolExchangesTwoPhaseFramesAndPromotesSnapshotMonotonically() {
        val factory = MountFactory()
        val legacyCfg = mapOf("attached" to true, "lensPort" to "lens")
        val pipelinedCfg = mapOf(
            "attached" to true,
            "lensPort" to "lens",
            "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE,
        )

        fun checksum(word: Long): Long =
            (((word ushr 27) and 0x1fL) + ((word ushr 22) and 0x1fL) + ((word ushr 17) and 0x1fL) +
                ((word ushr 12) and 0x1fL) + ((word ushr 7) and 0x1fL) + ((word ushr 6) and 1L)) and 0x1fL

        fun buildFrame(cmdId: Long, frameType: Long, seq: Long = 0L, upper: Long = 0L): Long {
            val base = ((upper and 0x1fL) shl 27) or ((seq and 0x7L) shl 24) or ((frameType and 1L) shl 15) or ((cmdId and 0x1ffL) shl 6)
            return base or (checksum(base) shl 1)
        }

        fun sendFrame(spi: SpiPeer, word: Long): Long {
            var acc = 0L
            for (shift in listOf(24, 16, 8, 0)) {
                val out = spi.exchange(8, ((word ushr shift) and 0xffL).toInt(), 100L)
                acc = (acc shl 8) or (out.toLong() and 0xffL)
            }
            return acc
        }

        // Legacy pre-exchange snapshot promotes cleanly to gf-lenscom-frame32-experimental-v1
        val legacyHarness = MountTestHarness()
        val legacyMount = factory.create(legacyHarness.context, legacyCfg) as StatefulComponent
        val preSnap = simulateJsonRoundTripMap(legacyMount.saveSnapshot())

        val liveHarness = MountTestHarness()
        val liveMount = factory.create(liveHarness.context, pipelinedCfg) as StatefulComponent
        liveMount.restoreSnapshot(preSnap)
        val spi = checkNotNull(liveHarness.context.ports.find("mount.spi", SpiPeer::class.java))

        // Boot S12 sync: 1st 0xA55A3CC3 clocks out initial 0L and loads 0xC33CA55A; 2nd clocks out 0xC33CA55A and loads 0xA55A3CC3 for C_0
        assertEquals(0L, sendFrame(spi, MountFactory.SYNC_MASTER_PROBE))
        assertEquals(MountFactory.SYNC_SLAVE_READY, sendFrame(spi, MountFactory.SYNC_MASTER_PROBE))
        val cmd10 = buildFrame(cmdId = 0x10L, frameType = 0L)
        assertEquals(MountFactory.SYNC_MASTER_PROBE, sendFrame(spi, cmd10))

        // Phase 2 poll (frameType=1) clocks out Phase 1 ACK for cmdId=0x10 and queues Phase 2 DATA
        val poll1 = buildFrame(cmdId = 0x10L, frameType = 1L, upper = 1L)
        val ack10 = sendFrame(spi, poll1)
        assertEquals(0x10L, (ack10 ushr 6) and 0x1ffL)
        assertEquals(1L, (ack10 ushr 15) and 1L)
        assertEquals(1L, (ack10 ushr 27) and 0x1fL)
        assertEquals(checksum(ack10), (ack10 ushr 1) and 0x1fL)

        // Mid-exchange snapshot preserves pending Phase-2 DATA frame and slaveSeq
        val midSnap = simulateJsonRoundTripMap(liveMount.saveSnapshot())
        assertFails { legacyMount.restoreSnapshot(midSnap) } // downgrade refused

        val resumedHarness = MountTestHarness()
        val resumedMount = factory.create(resumedHarness.context, pipelinedCfg) as StatefulComponent
        resumedMount.restoreSnapshot(midSnap)
        val resumedSpi = checkNotNull(resumedHarness.context.ports.find("mount.spi", SpiPeer::class.java))

        // Next command (cmdId=0x23 focusDistanceM=3.0 -> 300) clocks out Phase-2 DATA for cmdId=0x10 (0x0100)
        val cmd23 = buildFrame(cmdId = 0x23L, frameType = 0L)
        val data10 = sendFrame(resumedSpi, cmd23)
        assertEquals(0x10L, (data10 ushr 6) and 0x1ffL)
        assertEquals(0L, (data10 ushr 15) and 1L)
        assertEquals(0x0100L, (data10 ushr 16) and 0xffffL)
        assertEquals(checksum(data10), (data10 ushr 1) and 0x1fL)

        // Phase-2 poll clocks out ACK for cmdId=0x23 (slaveSeq=0 confirms DATA(0x10), advances slaveSeq->1)
        val ack23 = sendFrame(resumedSpi, buildFrame(cmdId = 0x23L, frameType = 1L, upper = 1L))
        assertEquals(0x23L, (ack23 ushr 6) and 0x1ffL)
        assertEquals(1L, (ack23 ushr 15) and 1L)
        assertEquals(0L, (ack23 ushr 24) and 0x7L)
        assertEquals(checksum(ack23), (ack23 ushr 1) and 0x1fL)

        // Next command (cmdId=0x24 prime zoom step 0 << 8 = 0x0000) clocks out DATA for cmdId=0x23 (300)
        val data23 = sendFrame(resumedSpi, buildFrame(cmdId = 0x24L, frameType = 0L))
        assertEquals(0x23L, (data23 ushr 6) and 0x1ffL)
        assertEquals(0L, (data23 ushr 15) and 1L)
        assertEquals(300L, (data23 ushr 16) and 0xffffL)
        assertEquals(checksum(data23), (data23 ushr 1) and 0x1fL)

        val ack24 = sendFrame(resumedSpi, buildFrame(cmdId = 0x24L, frameType = 1L, upper = 1L))
        assertEquals(0x24L, (ack24 ushr 6) and 0x1ffL)
        assertEquals(1L, (ack24 ushr 15) and 1L)
        assertEquals(1L, (ack24 ushr 24) and 0x7L)
        assertEquals(checksum(ack24), (ack24 ushr 1) and 0x1fL)

        // Burst flush tx=0x00000000 clocks out DATA(0x24) and loads ACK(cmdId=0, seq=2)
        val data24 = sendFrame(resumedSpi, 0L)
        assertEquals(0x24L, (data24 ushr 6) and 0x1ffL)
        assertEquals(0L, (data24 ushr 15) and 1L)
        assertEquals(0x0000L, (data24 ushr 16) and 0xffffL)
        assertEquals(checksum(data24), (data24 ushr 1) and 0x1fL)

        // Final S05 poll clocks out burst-flush ACK(cmdId=0, seq=2)
        val flushAck = sendFrame(resumedSpi, buildFrame(cmdId = 0L, frameType = 1L, upper = 1L))
        assertEquals(0L, (flushAck ushr 6) and 0x1ffL)
        assertEquals(1L, (flushAck ushr 15) and 1L)
        assertEquals(2L, (flushAck ushr 24) and 0x7L)
        assertEquals(checksum(flushAck), (flushAck ushr 1) and 0x1fL)

        // Boot Bulk Read cmd 0x014 (128B Lens Identity Header + 0xFFFF END) & Bulk Write cmd 0x015 (0xFF00 + 0x5A ACK)
        assertEquals(0L, sendFrame(resumedSpi, buildFrame(cmdId = 0x014L, frameType = 0L)))
        val ack14 = sendFrame(resumedSpi, buildFrame(cmdId = 0x014L, frameType = 1L, upper = 1L))
        assertEquals(0x014L, (ack14 ushr 6) and 0x1ffL)
        val data14 = sendFrame(resumedSpi, 0L)
        assertEquals(0x014L, (data14 ushr 6) and 0x1ffL)
        assertEquals(0L, (data14 ushr 16) and 0xffffL)
        val flushAck14 = sendFrame(resumedSpi, buildFrame(cmdId = 0L, frameType = 1L, upper = 1L))
        assertEquals(1L, (flushAck14 ushr 15) and 1L)

        // Bulk Read Xfer 1 (131 bytes): rx[0..1]=0x00,0x00, rx[2..129]=128B Lens Identity, rx[130]=sum&0xFF
        val bulkRx1 = ByteArray(131) { idx ->
            val tx = if (idx < 2) 0xff else 0x00
            resumedSpi.exchange(8, tx, 400L + idx).toByte()
        }
        assertEquals(0x00.toByte(), bulkRx1[0])
        assertEquals(0x00.toByte(), bulkRx1[1])
        assertEquals('G'.code.toByte(), bulkRx1[2 + 8])
        assertEquals('L'.code.toByte(), bulkRx1[2 + 9])
        assertEquals(0x01.toByte(), bulkRx1[2 + 98])
        assertEquals(0x30.toByte(), bulkRx1[2 + 99])
        val sum1 = (0 until 130).sumOf { bulkRx1[it].toInt() and 0xff } and 0xff
        assertEquals(sum1.toByte(), bulkRx1[130])

        // Bulk Read Xfer 2 (131 bytes): rx[0..1]=0xFF,0xFF (END), rx[130]=0xFE -> exits Bulk Mode back to Frame32
        val bulkRx2 = ByteArray(131) { idx ->
            val tx = if (idx == 0) 0xff else 0x00
            resumedSpi.exchange(8, tx, 600L + idx).toByte()
        }
        assertEquals(0xff.toByte(), bulkRx2[0])
        assertEquals(0xff.toByte(), bulkRx2[1])
        assertEquals(0xfe.toByte(), bulkRx2[130])

        // Bulk Write cmd 0x015 -> Xfer 1 replies rx[0..1]=0xFF,0x00 and rx[130]=0x5A, then exits to Frame32
        assertEquals(0L, sendFrame(resumedSpi, buildFrame(cmdId = 0x015L, frameType = 0L)))
        assertEquals(0x015L, (sendFrame(resumedSpi, buildFrame(cmdId = 0x015L, frameType = 1L, upper = 1L)) ushr 6) and 0x1ffL)
        assertEquals(0x015L, (sendFrame(resumedSpi, 0L) ushr 6) and 0x1ffL)
        assertEquals(1L, (sendFrame(resumedSpi, buildFrame(cmdId = 0L, frameType = 1L, upper = 1L)) ushr 15) and 1L)
        val bulkWr = ByteArray(131) { idx -> resumedSpi.exchange(8, 0x00, 800L + idx).toByte() }
        assertEquals(0xff.toByte(), bulkWr[0])
        assertEquals(0x00.toByte(), bulkWr[1])
        assertEquals(0x5a.toByte(), bulkWr[130])

        // Back in Frame32: cmd 0x016 (init-complete) replies DATA=0x0000
        assertEquals(0L, sendFrame(resumedSpi, buildFrame(cmdId = 0x016L, frameType = 0L)))
        assertEquals(0x016L, (sendFrame(resumedSpi, buildFrame(cmdId = 0x016L, frameType = 1L, upper = 1L)) ushr 6) and 0x1ffL)
        val data16 = sendFrame(resumedSpi, 0L)
        assertEquals(0x016L, (data16 ushr 6) and 0x1ffL)
        assertEquals(0L, (data16 ushr 16) and 0xffffL)
    }

    @Test
    fun dumbMechanicalAdapterSilencesSpiAndContactsWhileProjectingAttachedLensOptics() {
        val factory = MountFactory()
        val harness = MountTestHarness()
        val mechanicalLens = LensParameters("M42/M Mechanical Adapter (50mm F1.4)", 50.0, 1.4, 2.5, 0.88, -0.02)
        harness.context.ports.register(
            "lens",
            "lens",
            LensPort::class.java,
            object : LensPort, ElectronicLensPort {
                override fun parameters(): LensParameters = mechanicalLens
                override val electronicContacts: Boolean = false
            },
        )
        val cfg = mapOf(
            "attached" to true,
            "lensPort" to "lens",
            "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE,
        )
        val mount = factory.create(harness.context, cfg) as StatefulComponent
        val mountPort = harness.mountPort()
        val contacts = checkNotNull(harness.context.ports.find("mount.contacts", ContactBus::class.java))
        val spi = checkNotNull(harness.context.ports.find("mount.spi", SpiPeer::class.java))

        // Optical lens parameters remain attached for CMOS optical projection
        assertEquals(mechanicalLens, mountPort.attachedLens())
        assertEquals(false, mount.state()["electronicContacts"])
        assertEquals("mechanical-no-contacts", mount.state()["contactProtocol"])

        // ContactBus and SPI0 return zero (no electronic handshake)
        val req = java.nio.ByteBuffer.allocate(4).putInt(0xC33CA55A.toInt()).apply { flip() }
        val resp = java.nio.ByteBuffer.allocate(4)
        assertEquals(0, contacts.exchange(req, resp))
        for (byteVal in listOf(0xC3, 0x3C, 0xA5, 0x5A)) {
            assertEquals(0, spi.exchange(8, byteVal, 100L))
        }
    }

    @Test
    fun cmd0C0DownloadsValid128KiBLensOpticalEepromWithMagicAndApertureTables() {
        val factory = MountFactory()
        val harness = MountTestHarness()
        val gf45100 = LensParameters("GF45-100mmF4 R LM OIS WR", 45.0, 4.0, 2.5, 0.91, -0.02)
        harness.context.ports.register(
            "lens",
            "lens",
            LensPort::class.java,
            object : LensPort, ElectronicLensPort {
                override fun parameters(): LensParameters = gf45100
                override val electronicContacts: Boolean = true
                override val oisEnabled: Boolean = true
                override val minFocalLengthMm: Double = 45.0
                override val maxFocalLengthMm: Double = 100.0
                override val minAperture: Double = 4.0
                override val maxAperture: Double = 32.0
            },
        )
        val cfg = mapOf(
            "attached" to true,
            "lensPort" to "lens",
            "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE,
        )
        val mount = factory.create(harness.context, cfg) as StatefulComponent
        val spi = checkNotNull(harness.context.ports.find("mount.spi", SpiPeer::class.java))

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

        // Sync + cmd 0x018 with lenShift=11 (2048B blocks)
        assertEquals(0L, sendFrame(MountFactory.SYNC_MASTER_PROBE))
        assertEquals(MountFactory.SYNC_SLAVE_READY, sendFrame(MountFactory.SYNC_MASTER_PROBE))
        assertEquals(MountFactory.SYNC_MASTER_PROBE, sendFrame(buildFrame(0x018L, 0L, 11L shl 11)))
        assertEquals(0x018L, (sendFrame(buildFrame(0x018L, 1L, 0x0800L)) ushr 6) and 0x1ffL)
        // cmd 0x0C0 mode=6 -> DATA reply must report 64 blocks (0x0040)
        assertEquals(0x018L, (sendFrame(buildFrame(0x0C0L, 0L, 6L)) ushr 6) and 0x1ffL)
        assertEquals(0x0C0L, (sendFrame(buildFrame(0x0C0L, 1L, 0x0800L)) ushr 6) and 0x1ffL)
        val dataC0 = sendFrame(0L)
        assertEquals(0x0C0L, (dataC0 ushr 6) and 0x1ffL)
        assertEquals(0x0040L, (dataC0 ushr 16) and 0xffffL)
        assertEquals(1L, (sendFrame(buildFrame(0L, 1L, 0x0800L)) ushr 15) and 1L)

        // Read all 64 x 2051B data blocks into reconstructed 128KiB RAM cache (0x720000..0x73FFFF)
        val ram = ByteArray(0x20000)
        for (blk in 0 until 64) {
            val pkt = ByteArray(2051) { i -> spi.exchange(8, if (i < 2) 0xff else 0x00, 200L + blk * 3000L + i).toByte() }
            val blkIdx = ((pkt[0].toInt() and 0xff) shl 8) or (pkt[1].toInt() and 0xff)
            assertEquals(blk, blkIdx)
            val sum = (0 until 2050).sumOf { pkt[it].toInt() and 0xff } and 0xff
            assertEquals(sum.toByte(), pkt[2050])
            val destOff = if (blk < 60) 0x02000 + blk * 2048 else (blk - 60) * 2048
            pkt.copyInto(ram, destOff, 2, 2050)
        }
        // Final transfer is 0xFFFF END packet
        val endPkt = ByteArray(2051) { i -> spi.exchange(8, if (i < 2) 0xff else 0x00, 999999L + i).toByte() }
        assertEquals(0xff.toByte(), endPkt[0])
        assertEquals(0xff.toByte(), endPkt[1])
        assertEquals(0xfe.toByte(), endPkt[2050])

        fun u16Le(off: Int): Int = (ram[off].toInt() and 0xff) or ((ram[off + 1].toInt() and 0xff) shl 8)
        assertEquals(0xAA55, u16Le(0x000BE))
        assertEquals(0, ram[0x000C2].toInt() and 0xff) // 0 = native GF PDAF+CDAF-capable lens (0x01331DBC / 0x0132D6FC)
        assertEquals(64, ram[0x00210].toInt() and 0xff)
        assertEquals(19, ram[0x00212].toInt() and 0xff) // F4.0..F32 = 19 1/3 stops
        assertEquals(400, u16Le(0x0035A)) // INF base encoder position (openAv100 = 400)
        assertEquals(600, u16Le(0x004EC)) // avDelta100 (F4.0..F32 = 600)
        assertEquals(0x3A00, u16Le(0x005B5)) // mid-range/macro focus boundary < 0x0067E (DAT S4 0x0132EC60)
        assertEquals(0x7400, u16Le(0x0067E)) // far-to-near AF focus travel span (DAT S4 0x0132E87C)
        assertEquals(0x7790, u16Le(0x009A2)) // MOD mechanical focus limit (400 + 0x7400 + 0x200 = 30608)
        assertEquals(0, u16Le(0x00A6A)) // INF mechanical focus limit
        assertEquals(450, u16Le(0x0217D)) // 45.0mm -> 450
        assertEquals(1000, u16Le(0x0217D + 63 * 2)) // 100.0mm -> 1000
        assertEquals(400, u16Le(0x0230E)) // F4.0
        assertEquals(3200, u16Le(0x0230E + 18 * 2)) // F32
        assertEquals(0, ram[0x02411].toInt() and 0xff)
        assertEquals(18, ram[0x02476].toInt() and 0xff)
        assertEquals(0, ram[0x1C400].toInt() and 0xff)
        assertEquals(0xff, ram[0x1C400 + 19].toInt() and 0xff)
        assertEquals(4000, u16Le(0x18000)) // AV*1000 for F4.0
        // Focus-shift-with-aperture knots at 0x731C00 + z*12 (S4 0x0133088C) must strictly increase up to 0x7400
        for (z in listOf(0, 31, 63)) {
            var prev = 0
            for (k in 1..6) {
                val knot = u16Le(0x11C00 + z * 12 + (k - 1) * 2)
                assertTrue(knot > prev, "Expected strictly increasing aperture focus-shift knot z=$z k=$k: $knot > $prev")
                prev = knot
            }
            assertEquals(0x7400, prev)
        }

        // Back in Frame32 mode cleanly after END
        assertEquals(0L, sendFrame(buildFrame(0x016L, 0L)))
        assertEquals(0x016L, (sendFrame(buildFrame(0x016L, 1L, 0x0800L)) ushr 6) and 0x1ffL)
        assertEquals(0x016L, (sendFrame(0L) ushr 6) and 0x1ffL)
        mount.close()
    }

    @Test
    fun lensPowerGpioCycleResetsFrame32ProtocolState() {
        val harness = MountTestHarness()
        var pwrLevel = true
        var pwrSeq = 1L
        harness.context.ports.register("soc-gpio", "soc-gpio.outputs", GpioOutputPort::class.java, object : GpioOutputPort {
            override fun outputLevel(port: Int, pin: Int): Boolean =
                if (port == MountFactory.LENS_PWR_GPIO_BANK && pin == MountFactory.LENS_PWR_GPIO_PIN) pwrLevel else false
            override fun outputSequence(port: Int, pin: Int): Long =
                if (port == MountFactory.LENS_PWR_GPIO_BANK && pin == MountFactory.LENS_PWR_GPIO_PIN) pwrSeq else 0L
        })
        val mount = MountFactory().create(
            harness.context,
            mapOf("attached" to true, "lensPort" to "lens", "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE),
        )
        val spi = requireNotNull(harness.context.ports.find("mount.spi", SpiPeer::class.java))
        fun buildFrame(cmdId: Long, frameType: Long, payload16: Long = 0L): Long {
            val base = ((payload16 and 0xffffL) shl 16) or ((frameType and 1L) shl 15) or ((cmdId and 0x1ffL) shl 6)
            return base or (MountFactory.checksum32(base) shl 1)
        }
        fun sendFrame(word: Long): Long {
            var acc = 0L
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val out = spi.exchange(8, ((word ushr shift) and 0xffL).toInt(), 100L)
                acc = (acc shl 8) or (out.toLong() and 0xffL)
            }
            return acc
        }

        // Advance slaveSeq from 0 -> 1 via a normal Frame32 command + Phase-2 DATA + burst-flush
        assertEquals(0L, sendFrame(buildFrame(0x010L, 0L)))
        assertEquals(0L, (sendFrame(buildFrame(0x010L, 1L)) ushr 24) and 0x7L) // ACK(seq=0)
        assertEquals(0x010L, (sendFrame(0L) ushr 6) and 0x1ffL) // DATA(0x010)
        assertEquals(0L, (sendFrame(buildFrame(0x011L, 0L)) ushr 24) and 0x7L) // ACK(0, seq=0)
        assertEquals(1L, (sendFrame(buildFrame(0x011L, 1L)) ushr 24) and 0x7L) // ACK(0x011, seq=1)

        // Simulate STILL -> PLAY (GPIO(1,13)=0) -> STILL (GPIO(1,13)=1) lens power cycle
        pwrLevel = false; pwrSeq++
        pwrLevel = true; pwrSeq++

        // First frame after power cycle must clock out rx=0 and ACK with reset slaveSeq=0
        assertEquals(0L, sendFrame(buildFrame(0x0FFL, 0L)))
        val ackAfterCycle = sendFrame(buildFrame(0x0FFL, 1L))
        assertEquals(0x0FFL, (ackAfterCycle ushr 6) and 0x1ffL)
        assertEquals(0L, (ackAfterCycle ushr 24) and 0x7L)
        mount.close()
    }

    @Test
    fun modularLensPortDrivesUniqueSerialIdentityFocusIrisCommandsAndSilencesManualMode() {
        val harness = MountTestHarness()
        var mode = LensCommunicationMode.GENERIC_SIMULATED
        var encoderPos = 0x1234
        var focusBusy = false
        var lastFocusCmd = -1
        var lastFocusSpeed = -1
        var lastApertureCmd = -1
        val gf110Spec = LenscomIdentitySpec(
            shortId = "GF110F2",
            modelAscii = "GF110mmF2 R LM WR",
            serialAscii = "GF11002001",
            isZoom = false,
            hasOis = false,
            minFocalMm = 110.0,
            maxFocalMm = 110.0,
            minAperture = 2.0,
            maxAperture = 22.0,
        )
        harness.context.ports.register(
            "lens",
            "lens",
            LensPort::class.java,
            object : LensPort, ElectronicLensPort, ModularLensPort {
                override fun parameters(): LensParameters = harness.syntheticLens
                override val electronicContacts: Boolean get() = mode != LensCommunicationMode.MANUAL
                override val minFocalLengthMm: Double = 110.0
                override val maxFocalLengthMm: Double = 110.0
                override val minAperture: Double = 2.0
                override val maxAperture: Double = 22.0
                override val activeLensId: String = "gf110"
                override val activeAdapterId: String? = null
                override val communicationMode: LensCommunicationMode get() = mode
                override val controlContract: LensControlContract
                    get() = if (mode == LensCommunicationMode.MANUAL) LensControlContract.Manual else LensControlContract.GenericSimulated(gf110Spec)
                override val lenscomIdentity: LenscomIdentitySpec?
                    get() = if (mode == LensCommunicationMode.MANUAL) null else gf110Spec
                override val realFirmwarePayload: ByteArray? = null
                override val focusMotorBusy: Boolean get() = focusBusy
                override val focusEncoderPosition: Int get() = encoderPos
                override fun commandFocusEncoder(targetEncoder: Int) {
                    lastFocusCmd = targetEncoder
                    encoderPos = targetEncoder
                }
                override fun commandFocusEncoder(targetEncoder: Int, speedPulsesPerSec: Int) {
                    lastFocusCmd = targetEncoder
                    lastFocusSpeed = speedPulsesPerSec
                    encoderPos = targetEncoder
                }
                override fun commandApertureThirdStopIndex(avThirds: Int) {
                    lastApertureCmd = avThirds
                }
            },
        )
        val mount = MountFactory().create(
            harness.context,
            mapOf("attached" to true, "lensPort" to "lens", "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE),
        )
        val spi = requireNotNull(harness.context.ports.find("mount.spi", SpiPeer::class.java))
        assertEquals("GENERIC_SIMULATED", mount.state()["communicationMode"])
        assertEquals("gf110", mount.state()["activeLensId"])

        // 1) Identity header uses unique 10-byte serial from ModularLensPort
        val hdr = MountFactory.buildLensIdentityHeader128(
            harness.syntheticLens,
            harness.context.ports.find("lens", LensPort::class.java) as ElectronicLensPort,
            harness.context.ports.find("lens", LensPort::class.java) as ModularLensPort,
        )
        assertEquals("GF110F2", String(hdr, 0, 7, Charsets.US_ASCII))
        assertEquals("GF11002001", String(hdr, 80, 10, Charsets.US_ASCII))
        assertEquals('G'.code.toByte(), hdr[8])
        assertEquals('L'.code.toByte(), hdr[9])
        assertEquals('S'.code.toByte(), hdr[10])
        assertEquals('N'.code.toByte(), hdr[11])
        assertEquals(0x01.toByte(), hdr[98])
        assertEquals(0x30.toByte(), hdr[99])

        // 2) Focus drive (0x054 speed + 0x055 ctrl + 0x056 target, 0x058 stop, 0x151/0x152 rel-step)
        //    + iris drive (0x0A0) + live encoder readback (0x021 & 0x023)
        val modular = harness.context.ports.find("lens", LensPort::class.java) as ModularLensPort
        val elec = modular as ElectronicLensPort
        assertEquals(0x0000L, MountFactory.payloadForLensCommand(0x020L, harness.syntheticLens, elec, 0L, modular))
        focusBusy = true
        assertEquals(0x0001L, MountFactory.payloadForLensCommand(0x020L, harness.syntheticLens, elec, 0L, modular))
        focusBusy = false
        assertEquals(0x1234L, MountFactory.payloadForLensCommand(0x021L, harness.syntheticLens, elec, 0L, modular))
        assertEquals(0x1234L, MountFactory.payloadForLensCommand(0x023L, harness.syntheticLens, elec, 0L, modular))
        assertEquals(0L, MountFactory.payloadForLensCommand(0x054L, harness.syntheticLens, elec, 2000L, modular))
        assertEquals(0L, MountFactory.payloadForLensCommand(0x055L, harness.syntheticLens, elec, 0x1101L, modular))
        assertEquals(0L, MountFactory.payloadForLensCommand(0x056L, harness.syntheticLens, elec, 0x2468L, modular, lastFocusCtrl = 0x1101, lastFocusSpeed = 2000))
        assertEquals(0x2468, lastFocusCmd)
        assertEquals(2000, lastFocusSpeed)
        // DAT S4 0x01362C68/0x01361D54 (0x1201/0x1203) & 0x01362E18/0x01361E1C (0x1200): cmd 0x056 is always absolute
        // 0x7fff = native lens max motor speed (passed as speed=0 so LensFactory honors focusSweepSeconds)
        assertEquals(0L, MountFactory.payloadForLensCommand(0x056L, harness.syntheticLens, elec, 0x2368L, modular, lastFocusCtrl = 0x1201, lastFocusSpeed = 0x7fff))
        assertEquals(0x2368, lastFocusCmd)
        assertEquals(0, lastFocusSpeed)
        assertEquals(0L, MountFactory.payloadForLensCommand(0x056L, harness.syntheticLens, elec, 0x2568L, modular, lastFocusCtrl = 0x1203, lastFocusSpeed = 0x7fff))
        assertEquals(0x2568, lastFocusCmd)
        assertEquals(0L, MountFactory.payloadForLensCommand(0x056L, harness.syntheticLens, elec, 0x2468L, modular, lastFocusCtrl = 0x1200, lastFocusSpeed = 8282))
        assertEquals(0x2468, lastFocusCmd)
        assertEquals(8282, lastFocusSpeed)
        assertEquals(0x2468L, MountFactory.payloadForLensCommand(0x021L, harness.syntheticLens, elec, 0L, modular))
        assertEquals(0x2468L, MountFactory.payloadForLensCommand(0x023L, harness.syntheticLens, elec, 0L, modular))
        // Relative step focus drive (0x152: dirNear=1 bit1, steps=16 in bits[15:3] -> 0x0082)
        assertEquals(0L, MountFactory.payloadForLensCommand(0x152L, harness.syntheticLens, elec, (16L shl 3) or 0x2L, modular))
        assertEquals(0x2478, lastFocusCmd)
        assertEquals(0L, MountFactory.payloadForLensCommand(0x058L, harness.syntheticLens, elec, 0L, modular))
        assertEquals(0x2478, lastFocusCmd)
        assertEquals(0, lastFocusSpeed)
        // DAT S4 0x0121CC2C / 0x01365158: Live iris drive uses cmd 0x061 (speed) + cmd 0x062 (stepIdx << 6)
        assertEquals(0L, MountFactory.payloadForLensCommand(0x061L, harness.syntheticLens, elec, 0x0000L, modular))
        assertEquals(0x2478, lastFocusCmd, "cmd 0x061 is iris speed and must not overwrite focus encoder")
        assertEquals(0L, MountFactory.payloadForLensCommand(0x062L, harness.syntheticLens, elec, (6L shl 6) or 0x7000L, modular))
        assertEquals(6, lastApertureCmd)
        // DAT S4 0x0121D220: cmd 0x0A0 is iris power/mode enable (1/4/16), not target F-stop
        assertEquals(0L, MountFactory.payloadForLensCommand(0x0A0L, harness.syntheticLens, elec, 0x8010L, modular))
        assertEquals(6, lastApertureCmd)

        // 3) MANUAL mode immediately silences SPI0 to zero
        mode = LensCommunicationMode.MANUAL
        assertEquals("MANUAL", mount.state()["communicationMode"])
        assertEquals(false, mount.state()["electronicContacts"])
        for (b in listOf(0xA5, 0x5A, 0x3C, 0xC3)) {
            assertEquals(0, spi.exchange(8, b, 500L))
        }
        mount.close()
    }

    @Test
    fun lensIdentityAndCalibrationFollowModularHardwareCapabilities() {
        val harness = MountTestHarness()
        fun stubModular(spec: LenscomIdentitySpec?): ModularLensPort = object : ModularLensPort {
            override val activeLensId: String = spec?.shortId ?: "generic"
            override val activeAdapterId: String? = null
            override val communicationMode: LensCommunicationMode = LensCommunicationMode.GENERIC_SIMULATED
            override val controlContract: LensControlContract =
                if (spec != null) LensControlContract.GenericSimulated(spec) else LensControlContract.Manual
            override val lenscomIdentity: LenscomIdentitySpec? = spec
            override val realFirmwarePayload: ByteArray? = null
            override val focusMotorBusy: Boolean = false
            override val focusEncoderPosition: Int = 0
            override fun commandFocusEncoder(targetEncoder: Int) {}
            override fun commandApertureThirdStopIndex(avThirds: Int) {}
        }

        // Native GF lens with LMO/LEQ capability bit 0 set (DAT 0x0122C088 -> hdr[101] bit 0; bits 3/4 kept 0)
        val gfSpec = LenscomIdentitySpec(
            shortId = "GF45100",
            modelAscii = "GF45-100mmF4 R LM OIS WR",
            serialAscii = "GF04510001",
            capabilityFlags1 = 0x01,
            isZoom = true,
            hasOis = true,
            minFocalMm = 45.0,
            maxFocalMm = 100.0,
            minAperture = 4.0,
            maxAperture = 32.0,
        )
        var focusSelection = "AF-S"
        val electronic = object : ElectronicLensPort {
            override val electronicContacts = true
            override val focusMode get() = focusSelection
        }
        val modular = stubModular(gfSpec)
        val gfHdr = MountFactory.buildLensIdentityHeader128(harness.syntheticLens, electronic, modular)
        val gfEeprom = MountFactory.buildLensOpticalEeprom128K(harness.syntheticLens, electronic, modular)
        focusSelection = "MF"
        assertContentEquals(gfHdr, MountFactory.buildLensIdentityHeader128(harness.syntheticLens, electronic, modular))
        assertContentEquals(gfEeprom, MountFactory.buildLensOpticalEeprom128K(harness.syntheticLens, electronic, modular))
        assertEquals('A'.code.toByte(), gfHdr[12])
        assertEquals(0x80, gfHdr[100].toInt() and 0x82)
        assertEquals(100.toByte(), gfEeprom[0x02600])

        for (manualSpec in listOf(gfSpec.copy(focusMotorType = "MANUAL"), gfSpec.copy(capabilityFlags0 = 0x32))) {
            val manual = stubModular(manualSpec)
            val hdr = MountFactory.buildLensIdentityHeader128(harness.syntheticLens, electronic, manual)
            assertEquals('M'.code.toByte(), hdr[12])
            assertEquals(0x02, hdr[100].toInt() and 0x82)
            assertEquals(0.toByte(), MountFactory.buildLensOpticalEeprom128K(harness.syntheticLens, electronic, manual)[0x02600])
        }
        assertEquals(0x01.toByte(), gfHdr[101], "Native GF header must set hdr[101] bit 0 (LMO/LEQ) with bits 3/4 clear")
        assertEquals(0, gfHdr[101].toInt() and 0x18, "hdr[101] bits 3/4 must remain 0")

        // Adapted electronic lens (default capabilityFlags1 = 0x00) and legacy null ModularLensPort keep LMO bit clear
        val adaptedSpec = LenscomIdentitySpec(
            shortId = "EF85F14",
            modelAscii = "Fringer EF-GFX (EF85mm f/1.4L IS USM)",
            serialAscii = "EF08501401",
            isAdapter = true,
            minFocalMm = 85.0,
            maxFocalMm = 85.0,
            minAperture = 1.4,
            maxAperture = 22.0,
        )
        val adaptedHdr = MountFactory.buildLensIdentityHeader128(harness.syntheticLens, null, stubModular(adaptedSpec))
        assertEquals(0x00.toByte(), adaptedHdr[101], "Adapted electronic lens must keep hdr[101] LMO bit 0 clear")

        val legacyHdr = MountFactory.buildLensIdentityHeader128(harness.syntheticLens, null, null)
        assertEquals(0x00.toByte(), legacyHdr[101], "Null ModularLensPort fallback must keep hdr[101] = 0x00")
    }

    @Test
    fun lenscomTwoWaySyncEncodesApertureRingModeIrisReadbackZoomAndOisSwitch() {
        val harness = MountTestHarness()
        var ringMode = "A"
        var ringStep = 0
        var irisStep = 0
        var focalMm = 45.0
        var oisOn = true
        var focusEnc = 0x1000
        val gf45100Spec = LenscomIdentitySpec(
            shortId = "GF45100",
            modelAscii = "GF45-100mmF4 R LM OIS WR",
            serialAscii = "GF04510099",
            capabilityFlags1 = 0x01,
            isZoom = true,
            hasOis = true,
            minFocalMm = 45.0,
            maxFocalMm = 100.0,
            minAperture = 4.0,
            maxAperture = 32.0,
        )
        val lensPort = object : LensPort, ElectronicLensPort, ModularLensPort {
            override fun parameters(): LensParameters = LensParameters("GF45-100", focalMm, 4.0, 2.5, 0.9, 0.0)
            override val electronicContacts: Boolean = true
            override val oisEnabled: Boolean get() = oisOn
            override val minFocalLengthMm: Double = 45.0
            override val maxFocalLengthMm: Double = 100.0
            override val minAperture: Double = 4.0
            override val maxAperture: Double = 32.0
            override val activeLensId: String = "gf45-100"
            override val activeAdapterId: String? = null
            override val communicationMode: LensCommunicationMode = LensCommunicationMode.GENERIC_SIMULATED
            override val controlContract: LensControlContract = LensControlContract.GenericSimulated(gf45100Spec)
            override val lenscomIdentity: LenscomIdentitySpec = gf45100Spec
            override val realFirmwarePayload: ByteArray? = null
            override val focusMotorBusy: Boolean = false
            override val focusEncoderPosition: Int get() = focusEnc
            override val apertureRingMode: String get() = ringMode
            override val irisStepIndex: Int get() = irisStep
            override val apertureRingStepIndex: Int get() = ringStep
            override fun commandFocusEncoder(targetEncoder: Int) { focusEnc = targetEncoder }
            override fun commandApertureThirdStopIndex(avThirds: Int) { irisStep = avThirds }
        }
        harness.context.ports.register("lens", "lens", LensPort::class.java, lensPort)
        val mount = MountFactory().create(
            harness.context,
            mapOf("attached" to true, "lensPort" to "lens", "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE),
        )
        val contact = requireNotNull(harness.context.ports.find("mount.contacts", ContactBus::class.java))

        // OIS lens header must advertise hdr[11]='G' + hdr[13]=0x01 so body enables KEY_OIS on cmd 0x020 bit 6
        val hdr = MountFactory.buildLensIdentityHeader128(lensPort.parameters(), lensPort, lensPort)
        assertEquals('G'.code.toByte(), hdr[11])
        assertEquals(0x01.toByte(), hdr[13])

        fun frame(cmdId: Long, frameType: Long, payload16: Long = 0L): Long {
            val base = ((payload16 and 0xffffL) shl 16) or ((frameType and 1L) shl 15) or ((cmdId and 0x1ffL) shl 6)
            return base or (MountFactory.checksum32(base) shl 1)
        }
        fun exchangeWord(word: Long): Long {
            val req = java.nio.ByteBuffer.allocate(4).putInt(word.toInt()).flip()
            val resp = java.nio.ByteBuffer.allocate(4)
            assertEquals(4, contact.exchange(req, resp))
            resp.flip()
            return resp.int.toLong() and 0xffffffffL
        }
        fun queryCmd(cmdId: Long, txPayload: Long = 0L): Long {
            exchangeWord(frame(cmdId, 0L, txPayload))
            val dataWord = exchangeWord(frame(cmdId, 1L, 0L))
            return (dataWord ushr 16) and 0xffffL
        }

        // Initial handshake polls cmd 0x030 (A -> 0), cmd 0x024 (45mm -> 0), cmd 0x021 (0x1000)
        assertEquals(0x0000L, queryCmd(0x030L))
        assertEquals(0x0000L, queryCmd(0x024L))
        assertEquals(0x1000L, queryCmd(0x021L))
        // Idle status: OIS ON (bit 6 = 0x0040), ring mode A (bits[11:10]=0)
        assertEquals(0x0040L, queryCmd(0x020L))

        // Turn physical aperture ring to MANUAL f/8.0 (stepIdx = 6 -> cmd 0x030 = 7 = KEY_M_IRIS_6)
        ringMode = "MANUAL"
        ringStep = 6
        irisStep = 6
        // cmd 0x020 asserts bit 4 (0x0010) to notify body to poll cmd 0x030
        assertEquals(0x0050L, queryCmd(0x020L))
        assertEquals(0x0007L, queryCmd(0x030L))
        // After cmd 0x030 is read, bit 4 clears
        assertEquals(0x0040L, queryCmd(0x020L))
        // cmd 0x022 reports live iris step in bits[13:8]
        assertEquals(0x0600L, queryCmd(0x022L))

        // Turn aperture ring to C (Command Dial): cmd 0x020 sets bits[11:10]=2 (0x0800 -> KEY_M_IRIS_C)
        ringMode = "C"
        assertEquals(0x0840L, queryCmd(0x020L))
        // Body drives iris to step 9 (f/11) via cmd 0x061 + cmd 0x062
        assertEquals(0x0000L, queryCmd(0x061L, 0x0000L))
        assertEquals(0x0000L, queryCmd(0x062L, (9L shl 6) or 0x7000L))
        assertEquals(9, irisStep)
        assertEquals(0x0900L, queryCmd(0x022L))

        // Turn back from C to A: bits[11:10] clears to 0 AND bit 4 pulses to trigger cmd 0x030 = 0 (KEY_M_IRIS_A)
        ringMode = "A"
        assertEquals(0x0050L, queryCmd(0x020L))
        assertEquals(0x0000L, queryCmd(0x030L))

        // Zoom to 100mm & toggle OIS OFF: cmd 0x020 clears bit 6 and asserts bit 5 -> cmd 0x024 returns step 63
        focalMm = 100.0
        oisOn = false
        assertEquals(0x0020L, queryCmd(0x020L))
        assertEquals((63L shl 8), queryCmd(0x024L))
        assertEquals(0x0000L, queryCmd(0x020L))

        // Manual focus change pulses bit 0 -> cmd 0x021 reads updated encoder -> bit 0 clears
        focusEnc = 0x3456
        assertEquals(0x0001L, queryCmd(0x020L))
        assertEquals(0x3456L, queryCmd(0x021L))
        assertEquals(0x0000L, queryCmd(0x020L))
        mount.close()
    }

    @Test
    fun slewingFocusMotorAssertsCmd020Bit0AndStreamsLiveEncoderThroughCmd021AndCmd023() {
        val harness = MountTestHarness()
        val gf45100Spec = LenscomIdentitySpec(
            shortId = "GF45100",
            modelAscii = "GF45-100mmF4 R LM OIS WR",
            isZoom = true,
            hasOis = true,
            minFocalMm = 45.0,
            maxFocalMm = 100.0,
            minAperture = 4.0,
            maxAperture = 32.0,
            focusMotorType = "LM",
            focusSweepSeconds = 0.35,
        )
        var busy = false
        var focusEnc = 0x0800
        val lensPort = object : LensPort, ElectronicLensPort, ModularLensPort {
            override fun parameters() = LensParameters("GF45-100mmF4 R LM OIS WR", 45.0, 4.0, 5.0, 0.91, -0.02)
            override val electronicContacts: Boolean = true
            override val oisEnabled: Boolean = false
            override val minFocalLengthMm: Double = 45.0
            override val maxFocalLengthMm: Double = 100.0
            override val minAperture: Double = 4.0
            override val maxAperture: Double = 32.0
            override val minFocusDistanceM: Double = 0.65
            override val focusMode: String = "AF-S"
            override val activeLensId: String = "gf45-100"
            override val activeAdapterId: String? = null
            override val communicationMode: LensCommunicationMode = LensCommunicationMode.GENERIC_SIMULATED
            override val controlContract: LensControlContract = LensControlContract.GenericSimulated(gf45100Spec)
            override val lenscomIdentity: LenscomIdentitySpec = gf45100Spec
            override val realFirmwarePayload: ByteArray? = null
            override val focusMotorBusy: Boolean get() = busy
            override val focusEncoderPosition: Int get() = focusEnc
            override val apertureRingMode: String = "A"
            override val irisStepIndex: Int = 0
            override val apertureRingStepIndex: Int = 0
            override fun commandFocusEncoder(targetEncoder: Int) { focusEnc = targetEncoder }
            override fun commandApertureThirdStopIndex(avThirds: Int) {}
        }
        harness.context.ports.register("lens", "lens", LensPort::class.java, lensPort)
        val mount = MountFactory().create(
            harness.context,
            mapOf("attached" to true, "lensPort" to "lens", "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE),
        )
        val contact = requireNotNull(harness.context.ports.find("mount.contacts", ContactBus::class.java))

        fun frame(cmdId: Long, frameType: Long, payload16: Long = 0L): Long {
            val base = ((payload16 and 0xffffL) shl 16) or ((frameType and 1L) shl 15) or ((cmdId and 0x1ffL) shl 6)
            return base or (MountFactory.checksum32(base) shl 1)
        }
        fun exchangeWord(word: Long): Long {
            val req = java.nio.ByteBuffer.allocate(4).putInt(word.toInt()).flip()
            val resp = java.nio.ByteBuffer.allocate(4)
            assertEquals(4, contact.exchange(req, resp))
            resp.flip()
            return resp.int.toLong() and 0xffffffffL
        }
        fun queryCmd(cmdId: Long, txPayload: Long = 0L): Long {
            exchangeWord(frame(cmdId, 0L, txPayload))
            val dataWord = exchangeWord(frame(cmdId, 1L, 0L))
            return (dataWord ushr 16) and 0xffffL
        }

        // Initial handshake clears ring/focus dirty flags
        assertEquals(0x0000L, queryCmd(0x030L))
        assertEquals(0x0800L, queryCmd(0x021L))
        assertEquals(0x0000L, queryCmd(0x020L))

        // While focus motor is actively slewing, cmd 0x020 bit 0 stays asserted and 0x021/0x023 report live encoder
        busy = true
        focusEnc = 0x1400
        assertEquals(0x0001L, queryCmd(0x020L) and 0x0001L)
        assertEquals(0x1400L, queryCmd(0x021L))
        focusEnc = 0x2600
        assertEquals(0x0001L, queryCmd(0x020L) and 0x0001L)
        assertEquals(0x2600L, queryCmd(0x023L))

        // On final arrival, bit 0 pulses once more if encoder moved past last readback, then clears cleanly
        busy = false
        focusEnc = 0x3000
        assertEquals(0x0001L, queryCmd(0x020L) and 0x0001L)
        assertEquals(0x3000L, queryCmd(0x021L))
        assertEquals(0x0000L, queryCmd(0x020L) and 0x0001L)
        mount.close()
    }

    @Test
    fun lensDetectGpioDrivesActiveLowForElectronicLensAndHighForDumbAdapterOrDetached() {
        val gpioWrites = mutableListOf<Triple<Int, Int, Boolean>>()
        val harness = MountTestHarness()
        harness.context.ports.register("soc-gpio", "soc-gpio.inputs", GpioInputPort::class.java, GpioInputPort { bank, pin, high ->
            gpioWrites += Triple(bank, pin, high)
        })
        var hasContacts = true
        var electronicLensId = "gf45-100"
        var commMode = LensCommunicationMode.GENERIC_SIMULATED
        val spec = LenscomIdentitySpec(shortId = "GF45100", modelAscii = "GF45-100mmF4 R LM OIS WR")
        val lensPort = object : LensPort, ElectronicLensPort, ModularLensPort {
            override fun parameters() = LensParameters("GF45-100mmF4 R LM OIS WR", 45.0, 4.0, 2.5, 0.91, -0.02)
            override val electronicContacts: Boolean get() = hasContacts
            override val activeLensId: String get() = if (hasContacts) electronicLensId else "takumar-50-f14"
            override val activeAdapterId: String? get() = if (hasContacts) null else "adapter-m42-dumb"
            override val communicationMode: LensCommunicationMode get() = commMode
            override val controlContract: LensControlContract
                get() = if (hasContacts) LensControlContract.GenericSimulated(spec) else LensControlContract.Manual
            override val lenscomIdentity: LenscomIdentitySpec? get() = if (hasContacts) spec else null
            override val realFirmwarePayload: ByteArray? = null
            override val focusMotorBusy: Boolean = false
            override val focusEncoderPosition: Int = 0
            override fun commandFocusEncoder(targetEncoder: Int) {}
            override fun commandApertureThirdStopIndex(avThirds: Int) {}
        }
        harness.context.ports.register("lens", "lens", LensPort::class.java, lensPort)
        val mount = MountFactory().create(
            harness.context,
            mapOf("attached" to true, "lensPort" to "lens", "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE),
        )
        val mountPort = requireNotNull(harness.context.ports.find("mount", MountPort::class.java))

        // Electronic GF lens -> KEY_LENS_DET GPIO(0,11) = LOW (false)
        mount.start()
        assertEquals(Triple(0, 11, false), gpioWrites.last())
        electronicLensId = "gf110"
        mountPort.syncHardwareDetect()
        assertEquals(listOf(Triple(0, 11, true), Triple(0, 11, false)), gpioWrites.takeLast(2))
        val writesAfterSwap = gpioWrites.size
        mountPort.syncHardwareDetect()
        assertEquals(writesAfterSwap, gpioWrites.size)
        electronicLensId = "gf45-100"
        mountPort.restoreHardwareDetect()
        assertEquals(listOf(Triple(0, 11, false)), gpioWrites.drop(writesAfterSwap))

        // Hot-swap to dumb M42 adapter (no electronic contacts) -> KEY_LENS_DET GPIO(0,11) = HIGH (true)
        hasContacts = false
        commMode = LensCommunicationMode.MANUAL
        mountPort.syncHardwareDetect()
        assertEquals(Triple(0, 11, true), gpioWrites.last())

        // Hot-swap back to electronic GF lens -> LOW (false)
        hasContacts = true
        commMode = LensCommunicationMode.GENERIC_SIMULATED
        mountPort.syncHardwareDetect()
        assertEquals(Triple(0, 11, false), gpioWrites.last())

        // Detach lens -> HIGH (true); re-attach -> LOW (false)
        mount.applySettings(mapOf("attached" to false))
        assertEquals(Triple(0, 11, true), gpioWrites.last())
        mount.applySettings(mapOf("attached" to true))
        assertEquals(Triple(0, 11, false), gpioWrites.last())
        mount.close()
    }

    @Test
    fun eepromFileBinServesVerbatim128KiBOverBulkCmd0c0AndRejectsBadSizeOrEscape() {
        val tempDir = java.nio.file.Files.createTempDirectory("mount-eeprom-bin-")
        try {
            val eepromBytes = ByteArray(MountFactory.LENS_EEPROM_BYTES) { idx ->
                ((idx * 37 + 0x5a) and 0xff).toByte()
            }
            java.nio.file.Files.write(tempDir.resolve("gf-lens-eeprom.bin"), eepromBytes)
            java.nio.file.Files.write(tempDir.resolve("bad-size.bin"), ByteArray(4096))

            fun makeContext(): ComponentContext {
                val portsMap = LinkedHashMap<String, Any>()
                val ctx = object : ComponentContext {
                    override val directory: Path = tempDir
                    override val clock = EmulationClock { 0L }
                    override val ports = object : Ports {
                        override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                            portsMap[name] = port
                        }
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : Any> find(name: String, type: Class<T>): T? = portsMap[name] as? T
                        override fun removeOwner(owner: String) { portsMap.clear() }
                    }
                    override val events = object : EventChannel {
                        override fun publish(event: ComponentEvent) {}
                        override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
                    }
                    override fun dataPath(relative: String): Path = tempDir.resolve(relative)
                }
                ctx.ports.register("lens", "lens", LensPort::class.java, object : LensPort {
                    override fun parameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 2.5, 0.9, 0.0)
                })
                return ctx
            }

            // Invalid size and path escape must fail closed
            assertFailsWith<IllegalArgumentException> {
                MountFactory().create(makeContext(), mapOf(
                    "attached" to true,
                    "lensPort" to "lens",
                    "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE,
                    "eepromFile" to "bad-size.bin",
                ))
            }
            assertFailsWith<IllegalArgumentException> {
                MountFactory().create(makeContext(), mapOf(
                    "attached" to true,
                    "lensPort" to "lens",
                    "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE,
                    "eepromFile" to "../escape.bin",
                ))
            }

            // Valid 128 KiB eepromFile.bin is served verbatim on LENSCOM cmd 0x0C0 mode 0 (block 0 = first 2048 B)
            val ctx = makeContext()
            val mount = assertIs<StatefulComponent>(MountFactory().create(ctx, mapOf(
                "attached" to true,
                "lensPort" to "lens",
                "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE,
                "eepromFile" to "gf-lens-eeprom.bin",
            )))
            mount.start()
            assertEquals("gf-lens-eeprom.bin", mount.state()["eepromFile"])

            val spi = checkNotNull(ctx.ports.find("mount.spi", SpiPeer::class.java))
            fun frame(cmdId: Long, frameType: Long, payload16: Long = 0L): Long {
                val base = ((payload16 and 0xffffL) shl 16) or ((frameType and 1L) shl 15) or ((cmdId and 0x1ffL) shl 6)
                return base or (MountFactory.checksum32(base) shl 1)
            }
            fun sendWord(word: Long): Long {
                var acc = 0L
                for (shift in listOf(24, 16, 8, 0)) {
                    acc = (acc shl 8) or (spi.exchange(8, ((word ushr shift) and 0xffL).toInt(), 0L).toLong() and 0xffL)
                }
                return acc
            }

            // Sync + cmd 0x018 (2048B blocks) + cmd 0x0C0 mode 6 (64 blocks = 128 KiB)
            sendWord(MountFactory.SYNC_MASTER_PROBE)
            sendWord(MountFactory.SYNC_MASTER_PROBE)
            sendWord(frame(0x018L, 0L, 11L shl 11))
            sendWord(frame(0x018L, 1L, 0x0800L))
            sendWord(frame(0x0C0L, 0L, 6L))
            sendWord(frame(0x0C0L, 1L, 0x0800L))
            sendWord(0L)
            sendWord(frame(0L, 1L, 0x0800L))

            // Read all 64 x 2051B bulk blocks into reconstructed 128 KiB buffer and assert byte-for-byte match!
            val reconstructed = ByteArray(MountFactory.LENS_EEPROM_BYTES)
            for (blk in 0 until 64) {
                val pkt = ByteArray(2051) { i -> spi.exchange(8, if (i < 2) 0xff else 0x00, 0L).toByte() }
                val blkIdx = ((pkt[0].toInt() and 0xff) shl 8) or (pkt[1].toInt() and 0xff)
                assertEquals(blk, blkIdx)
                val destOff = if (blk < 60) 0x02000 + blk * 2048 else (blk - 60) * 2048
                pkt.copyInto(reconstructed, destOff, 2, 2050)
            }
            val endPkt = ByteArray(2051) { i -> spi.exchange(8, if (i < 2) 0xff else 0x00, 0L).toByte() }
            assertEquals(0xff.toByte(), endPkt[0])
            assertEquals(0xff.toByte(), endPkt[1])
            assertContentEquals(eepromBytes, reconstructed)

            // Snapshot round-trip preserves eepromFile
            val snap = simulateJsonRoundTripMap(mount.saveSnapshot())
            val ctx2 = makeContext()
            val mount2 = assertIs<StatefulComponent>(MountFactory().create(ctx2, mapOf(
                "attached" to true,
                "lensPort" to "lens",
                "protocolProfile" to MountFactory.LENSCOM_FRAME32_PROFILE,
                "eepromFile" to "gf-lens-eeprom.bin",
            )))
            mount2.start()
            mount2.restoreSnapshot(snap)
            assertEquals(mount.state(), mount2.state())
            mount.close()
            mount2.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}

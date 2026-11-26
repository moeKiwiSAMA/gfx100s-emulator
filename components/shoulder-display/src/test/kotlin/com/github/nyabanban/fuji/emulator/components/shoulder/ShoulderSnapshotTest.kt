package com.github.nyabanban.fuji.emulator.components.shoulder

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.api.display.DotLcdTransportCodec
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class ShoulderSnapshotTest {
    private class ShoulderTestHarness(registerCaptureSource: Boolean = false) {
        var currentTick: Long = 0L
        var activeCapture: TransferCapture? = null
        var activePayload: ByteArray = ByteArray(0)
        var partialReadBytes: Int? = null

        private val portEntries = LinkedHashMap<String, Pair<String, Any>>()
        private val listeners = ArrayList<(ComponentEvent) -> Unit>()

        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-shoulder-test")
            override val clock = EmulationClock { currentTick }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    assertTrue(type.isInstance(port))
                    portEntries[name] = owner to port
                }
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> find(name: String, type: Class<T>): T? {
                    val registered = portEntries[name]?.second ?: return null
                    return if (type.isInstance(registered)) registered as T else null
                }
                override fun removeOwner(owner: String) {
                    val iterator = portEntries.entries.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().value.first == owner) iterator.remove()
                    }
                }
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {
                    for (listener in listeners.toList()) listener(event)
                }
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable {
                    listeners.add(listener)
                    return AutoCloseable { listeners.remove(listener) }
                }
            }
            override fun dataPath(relative: String): Path = error("unexpected dataPath")
        }

        init {
            if (registerCaptureSource) {
                context.ports.register("soc-serial-engine", ShoulderFactory.CAPTURE, TransferCapturePort::class.java, object : TransferCapturePort {
                    override fun capture(): TransferCapture? = activeCapture
                    override fun read(sequence: Long, offset: Long, destination: ByteBuffer) {
                        assertEquals(activeCapture?.sequence, sequence)
                        assertEquals(0L, offset)
                        val byteCount = partialReadBytes ?: activePayload.size
                        destination.put(activePayload, 0, byteCount)
                    }
                })
            }
        }

        fun displayPort(): DisplayPort = checkNotNull(context.ports.find(ShoulderFactory.DISPLAY, DisplayPort::class.java))

        fun publishCaptureComplete(sequence: Long, size: Any = DotLcdTransportCodec.BYTES) {
            context.events.publish(
                ComponentEvent(
                    "soc-serial-engine",
                    "transfer-capture-complete",
                    currentTick,
                    mapOf("port" to ShoulderFactory.CAPTURE, "sequence" to sequence, "size" to size),
                )
            )
        }
    }

    private fun encodePattern(patternModulus: Int, patternRemainder: Int): ByteArray {
        val bitPlane = ByteArray(DotLcdTransportCodec.WIDTH * DotLcdTransportCodec.TRANSPORT_ROWS) { index ->
            if (index % patternModulus == patternRemainder) 1 else 0
        }
        return DotLcdTransportCodec.encode(bitPlane)
    }

    @Suppress("UNCHECKED_CAST")
    private fun simulateJsonRoundTripMap(map: Map<String, Any?>): Map<String, Any?> =
        simulateJsonRoundTripValue(map) as Map<String, Any?>

    private fun simulateJsonRoundTripValue(value: Any?): Any? = when (value) {
        null, is Boolean, is String -> value
        is Byte, is Short, is Int -> (value as Number).toInt()
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else value
        is Float, is Double -> {
            val decimal = (value as Number).toDouble()
            check(decimal.isFinite()) { "Non-finite JSON number" }
            decimal
        }
        is List<*> -> value.map { element -> simulateJsonRoundTripValue(element) }
        is Map<*, *> -> {
            val copy = LinkedHashMap<String, Any?>()
            for ((entryKey, entryValue) in value) {
                check(entryKey is String) { "Expected string key" }
                copy[entryKey] = simulateJsonRoundTripValue(entryValue)
            }
            copy
        }
        else -> error("Unsupported non-JSON snapshot value: ${value::class.java.name}")
    }

    @Test
    fun unboundModeRoundTripPreservesLegacyStateFrameAndContinuation() {
        val factory = ShoulderFactory()
        val width = 32
        val height = 16
        val pixelCount = width * height

        val harnessOne = ShoulderTestHarness()
        val shoulderOne = factory.create(
            harnessOne.context,
            mapOf("width" to width, "height" to height, "brightness" to 1.0, "profile" to ShoulderFactory.UNBOUND),
        ) as StatefulComponent
        shoulderOne.start()
        val framePixels = IntArray(pixelCount) { index -> 0xff123456.toInt() + index }
        harnessOne.displayPort().presentArgb(framePixels, width, height)
        shoulderOne.applySettings(mapOf("brightness" to 0.35))

        val snapshot = simulateJsonRoundTripMap(shoulderOne.saveSnapshot())

        val harnessTwo = ShoulderTestHarness()
        val shoulderTwo = factory.create(
            harnessTwo.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 0.9),
        ) as StatefulComponent
        shoulderTwo.start()
        shoulderTwo.restoreSnapshot(snapshot)

        assertFalse(shoulderTwo.state().containsKey("profile"))
        assertEquals(shoulderOne.state(), shoulderTwo.state())
        val copiedOne = IntArray(pixelCount)
        val copiedTwo = IntArray(pixelCount)
        assertTrue(harnessOne.displayPort().copyArgb(copiedOne))
        assertTrue(harnessTwo.displayPort().copyArgb(copiedTwo))
        assertContentEquals(copiedOne, copiedTwo)

        val nextPixels = IntArray(pixelCount) { index -> 0xff654321.toInt() xor index }
        harnessOne.displayPort().presentArgb(nextPixels, width, height)
        harnessTwo.displayPort().presentArgb(nextPixels, width, height)
        assertEquals(shoulderOne.state(), shoulderTwo.state())
        assertEquals(shoulderOne.saveSnapshot(), shoulderTwo.saveSnapshot())
    }

    @Test
    fun dotLcdModeRoundTripPreservesCaptureCountersMetadataAndOperationalEquivalence() {
        val factory = ShoulderFactory()
        val width = DotLcdTransportCodec.WIDTH
        val height = DotLcdTransportCodec.TRANSPORT_ROWS
        val pixelCount = width * height

        val harnessOne = ShoulderTestHarness(registerCaptureSource = true)
        val shoulderOne = factory.create(
            harnessOne.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 1.0, "profile" to ShoulderFactory.DOTLCD),
        ) as StatefulComponent
        shoulderOne.start()

        harnessOne.currentTick = 10L
        harnessOne.activePayload = encodePattern(3, 0)
        harnessOne.activeCapture = TransferCapture(1L, DotLcdTransportCodec.BYTES, "sha256-seq-1", 8L, 10L)
        harnessOne.publishCaptureComplete(1L)

        harnessOne.currentTick = 12L
        harnessOne.publishCaptureComplete(1L)

        harnessOne.currentTick = 20L
        harnessOne.activePayload = encodePattern(5, 2)
        harnessOne.activeCapture = TransferCapture(4L, DotLcdTransportCodec.BYTES, "sha256-seq-4", 18L, 20L)
        harnessOne.publishCaptureComplete(4L)

        harnessOne.currentTick = 25L
        harnessOne.partialReadBytes = DotLcdTransportCodec.BYTES - 4
        harnessOne.activeCapture = TransferCapture(5L, DotLcdTransportCodec.BYTES, "sha256-seq-5", 24L, 25L)
        harnessOne.publishCaptureComplete(5L)
        harnessOne.partialReadBytes = null

        shoulderOne.applySettings(mapOf("brightness" to 0.65))
        assertEquals(4L, shoulderOne.state()["captureEvents"])
        assertEquals(2L, shoulderOne.state()["acceptedCaptures"])
        assertEquals(1L, shoulderOne.state()["rejectedCaptures"])
        assertEquals(1L, shoulderOne.state()["duplicateEvents"])
        assertEquals(2L, shoulderOne.state()["skippedCaptureSequences"])
        assertEquals(5L, shoulderOne.state()["lastSeenCaptureSequence"])
        assertNotNull(shoulderOne.state()["lastRejection"])

        val frontierSnapshot = simulateJsonRoundTripMap(shoulderOne.saveSnapshot())

        val harnessTwo = ShoulderTestHarness(registerCaptureSource = true)
        val shoulderTwo = factory.create(
            harnessTwo.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 1.0, "profile" to ShoulderFactory.DOTLCD),
        ) as StatefulComponent
        shoulderTwo.start()
        shoulderTwo.restoreSnapshot(frontierSnapshot)

        assertEquals(shoulderOne.state(), shoulderTwo.state())
        val pixelsOne = IntArray(pixelCount)
        val pixelsTwo = IntArray(pixelCount)
        assertTrue(harnessOne.displayPort().copyArgb(pixelsOne))
        assertTrue(harnessTwo.displayPort().copyArgb(pixelsTwo))
        assertContentEquals(pixelsOne, pixelsTwo)

        for (activeHarness in listOf(harnessOne, harnessTwo)) {
            activeHarness.currentTick = 30L
            activeHarness.publishCaptureComplete(5L)

            activeHarness.currentTick = 40L
            activeHarness.activePayload = encodePattern(7, 4)
            activeHarness.activeCapture = TransferCapture(7L, DotLcdTransportCodec.BYTES, "sha256-seq-7", 36L, 40L)
            activeHarness.publishCaptureComplete(7L)
        }

        assertEquals(shoulderOne.state(), shoulderTwo.state())
        assertEquals(3L, harnessTwo.displayPort().sequence)
        assertEquals(2L, shoulderTwo.state()["duplicateEvents"])
        assertEquals(3L, shoulderTwo.state()["skippedCaptureSequences"])
        assertNull(shoulderTwo.state()["lastRejection"])
        assertTrue(harnessOne.displayPort().copyArgb(pixelsOne))
        assertTrue(harnessTwo.displayPort().copyArgb(pixelsTwo))
        assertContentEquals(pixelsOne, pixelsTwo)
        assertEquals(shoulderOne.saveSnapshot(), shoulderTwo.saveSnapshot())
    }

    @Test
    fun compatibleZeroFrameUnboundSnapshotCanPromoteToDotLcdProfile() {
        val factory = ShoulderFactory()
        val width = DotLcdTransportCodec.WIDTH
        val height = DotLcdTransportCodec.TRANSPORT_ROWS

        val unboundHarness = ShoulderTestHarness()
        val unboundShoulder = factory.create(
            unboundHarness.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 0.7, "profile" to ShoulderFactory.UNBOUND),
        ) as StatefulComponent
        val unboundSnapshot = simulateJsonRoundTripMap(unboundShoulder.saveSnapshot())

        val dotLcdHarness = ShoulderTestHarness(registerCaptureSource = true)
        val dotLcdShoulder = factory.create(
            dotLcdHarness.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 1.0, "profile" to ShoulderFactory.DOTLCD),
        ) as StatefulComponent
        dotLcdShoulder.start()
        dotLcdShoulder.restoreSnapshot(unboundSnapshot)
        assertEquals(0.7, dotLcdShoulder.state()["brightness"])
        assertEquals(ShoulderFactory.DOTLCD, dotLcdShoulder.state()["profile"])

        dotLcdHarness.currentTick = 5L
        dotLcdHarness.activePayload = encodePattern(2, 1)
        dotLcdHarness.activeCapture = TransferCapture(1L, DotLcdTransportCodec.BYTES, "promoted-seq-1", 4L, 5L)
        dotLcdHarness.publishCaptureComplete(1L)
        assertEquals(1L, dotLcdHarness.displayPort().sequence)
        assertEquals(1L, dotLcdShoulder.state()["acceptedCaptures"])
    }

    @Test
    fun closedDetachedAndInvalidSnapshotsRejectAtomically() {
        val factory = ShoulderFactory()
        val width = DotLcdTransportCodec.WIDTH
        val height = DotLcdTransportCodec.TRANSPORT_ROWS
        val pixelCount = width * height

        val harness = ShoulderTestHarness(registerCaptureSource = true)
        val shoulder = factory.create(
            harness.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 0.85, "profile" to ShoulderFactory.DOTLCD),
        ) as StatefulComponent
        shoulder.start()
        harness.currentTick = 15L
        harness.activePayload = encodePattern(4, 1)
        harness.activeCapture = TransferCapture(2L, DotLcdTransportCodec.BYTES, "baseline-sha", 12L, 15L)
        harness.publishCaptureComplete(2L)

        val baselineState = shoulder.state()
        val baselinePixels = IntArray(pixelCount)
        assertTrue(harness.displayPort().copyArgb(baselinePixels))
        val validSnapshot = simulateJsonRoundTripMap(shoulder.saveSnapshot())
        @Suppress("UNCHECKED_CAST")
        val validScalars = validSnapshot["scalarValues"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val validFrame = validSnapshot["frame"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val validAccepted = validSnapshot["lastAccepted"] as Map<String, Any?>

        val nonDiagnosticBuffer = ArgbFrameBuffer(width, height)
        nonDiagnosticBuffer.presentArgb(IntArray(pixelCount) { 0xff123456.toInt() }, width, height)
        val nonDiagnosticFrame = nonDiagnosticBuffer.saveSnapshot()

        val mutatedScalars = validScalars + ("brightness" to 0.25)
        val invalidCandidates = listOf<Map<String, Any?>>(
            validSnapshot + ("schema" to 2),
            validSnapshot + ("componentId" to "display"),
            validSnapshot + ("extra" to 1),
            validSnapshot + ("scalarValues" to (validScalars + ("brightness" to 2.0))),
            validSnapshot + ("scalarValues" to (validScalars + ("width" to 320))),
            validSnapshot + ("scalarValues" to (validScalars + ("profile" to "invalid-profile"))),
            validSnapshot + ("scalarValues" to mutatedScalars) + ("eventCount" to 99),
            validSnapshot + ("scalarValues" to mutatedScalars) + ("accepted" to 0),
            validSnapshot + ("scalarValues" to mutatedScalars) + ("lastAccepted" to null),
            validSnapshot + ("scalarValues" to mutatedScalars) + ("lastRejection" to "unexpected-without-rejection"),
            validSnapshot + ("scalarValues" to mutatedScalars) + ("skippedSequences" to 10),
            validSnapshot + ("scalarValues" to mutatedScalars) + ("lastAccepted" to (validAccepted + ("completedAt" to 20))),
            validSnapshot + ("scalarValues" to mutatedScalars) + ("lastAccepted" to (validAccepted + ("size" to 64))),
            validSnapshot + ("scalarValues" to mutatedScalars) + ("frame" to (validFrame + ("sequence" to 2))),
            validSnapshot + ("scalarValues" to mutatedScalars) + ("frame" to nonDiagnosticFrame),
        )

        for (candidate in invalidCandidates) {
            assertFails { shoulder.restoreSnapshot(candidate) }
            assertEquals(baselineState, shoulder.state())
            val currentPixels = IntArray(pixelCount)
            assertTrue(harness.displayPort().copyArgb(currentPixels))
            assertContentEquals(baselinePixels, currentPixels)
        }

        val unboundHarness = ShoulderTestHarness()
        val unboundShoulder = factory.create(
            unboundHarness.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 1.0, "profile" to ShoulderFactory.UNBOUND),
        ) as StatefulComponent
        assertFails { unboundShoulder.restoreSnapshot(validSnapshot) }
        assertEquals(0L, unboundHarness.displayPort().sequence)

        shoulder.close()
        assertFails { shoulder.saveSnapshot() }
        assertFails { shoulder.restoreSnapshot(validSnapshot) }

        val detachedHarness = ShoulderTestHarness()
        val detachedShoulder = factory.create(
            detachedHarness.context,
            mapOf("width" to 320L, "height" to 160L, "brightness" to 1.0),
        ) as StatefulComponent
        detachedHarness.context.ports.removeOwner("shoulder-display")
        assertFails { detachedShoulder.saveSnapshot() }
        assertFails { detachedShoulder.restoreSnapshot(unboundShoulder.saveSnapshot()) }
    }

    @Test
    fun uprightOrientationRotatesActiveCanvas180DegreesSupports230And240AndRoundTripsSnapshots() {
        val factory = ShoulderFactory()
        val rawBits = ByteArray(DotLcdTransportCodec.WIDTH * DotLcdTransportCodec.TRANSPORT_ROWS)
        // Firmware logical top-left (0, 0) is encoded at canvas (303, 229)
        rawBits[229 * DotLcdTransportCodec.WIDTH + 303] = 1
        // Firmware logical (4, 1) is encoded at canvas (299, 228)
        rawBits[228 * DotLcdTransportCodec.WIDTH + 299] = 1
        // Extra transport row 235 at raw column 2
        rawBits[235 * DotLcdTransportCodec.WIDTH + 2] = 1
        val packet = DotLcdTransportCodec.encode(rawBits)

        // Raw mode rejects 230 height (preserves strict 304x240 discovery contract)
        assertFails {
            factory.create(
                ShoulderTestHarness(registerCaptureSource = true).context,
                mapOf("width" to 304L, "height" to 230L, "brightness" to 1.0, "profile" to ShoulderFactory.DOTLCD),
            )
        }

        // Upright 304x230 crops trailing 10 padding rows and maps (303, 229) -> (0, 0)
        val h230 = ShoulderTestHarness(registerCaptureSource = true)
        val s230 = factory.create(
            h230.context,
            mapOf(
                "width" to 304L,
                "height" to 230L,
                "brightness" to 1.0,
                "profile" to ShoulderFactory.DOTLCD,
                "orientation" to ShoulderFactory.ORIENTATION_UPRIGHT,
            ),
        ) as StatefulComponent
        s230.start()
        h230.currentTick = 100L
        h230.activePayload = packet
        h230.activeCapture = TransferCapture(1L, DotLcdTransportCodec.BYTES, "upright-230-sha", 90L, 100L)
        h230.publishCaptureComplete(1L)

        assertEquals(ShoulderFactory.ORIENTATION_UPRIGHT, s230.state()["orientation"])
        assertEquals(240, s230.state()["emittedRows"])
        assertEquals(230, s230.state()["drawingRows"])
        assertEquals(0, s230.state()["extraRowsPreserved"])
        val px230 = IntArray(304 * 230)
        assertTrue(h230.displayPort().copyArgb(px230))
        assertEquals(0xffffffff.toInt(), px230[0 * 304 + 0])
        assertEquals(0xffffffff.toInt(), px230[1 * 304 + 4])
        assertEquals(0xff000000.toInt(), px230[229 * 304 + 303])
        assertEquals(2, px230.count { it == 0xffffffff.toInt() })

        // Snapshot round-trip preserves upright 304x230 frame & state cleanly
        val saved230 = simulateJsonRoundTripMap(s230.saveSnapshot())
        val h230Restore = ShoulderTestHarness(registerCaptureSource = true)
        val s230Restore = factory.create(
            h230Restore.context,
            mapOf(
                "width" to 304L,
                "height" to 230L,
                "brightness" to 0.5,
                "profile" to ShoulderFactory.DOTLCD,
                "orientation" to ShoulderFactory.ORIENTATION_UPRIGHT,
            ),
        ) as StatefulComponent
        s230Restore.start()
        s230Restore.restoreSnapshot(saved230)
        assertEquals(s230.state(), s230Restore.state())
        val restoredPx230 = IntArray(304 * 230)
        assertTrue(h230Restore.displayPort().copyArgb(restoredPx230))
        assertContentEquals(px230, restoredPx230)

        // Upright 304x240 preserves extra row 235 at column-aligned x = 303 - 2 = 301
        val h240 = ShoulderTestHarness(registerCaptureSource = true)
        val s240 = factory.create(
            h240.context,
            mapOf(
                "width" to 304L,
                "height" to 240L,
                "brightness" to 1.0,
                "profile" to ShoulderFactory.DOTLCD,
                "orientation" to ShoulderFactory.ORIENTATION_UPRIGHT,
            ),
        ) as StatefulComponent
        s240.start()
        h240.currentTick = 200L
        h240.activePayload = packet
        h240.activeCapture = TransferCapture(1L, DotLcdTransportCodec.BYTES, "upright-240-sha", 190L, 200L)
        h240.publishCaptureComplete(1L)
        val px240 = IntArray(304 * 240)
        assertTrue(h240.displayPort().copyArgb(px240))
        assertEquals(0xffffffff.toInt(), px240[0 * 304 + 0])
        assertEquals(0xffffffff.toInt(), px240[1 * 304 + 4])
        assertEquals(0xffffffff.toInt(), px240[235 * 304 + 301])
        assertEquals(3, px240.count { it == 0xffffffff.toInt() })
    }

    @Test fun backlightOnTracksGpioBank8Pin9WithoutMutatingRawDisplayPixels() {
        val factory = ShoulderFactory()
        val harness = ShoulderTestHarness(registerCaptureSource = true)
        var bank8Pin9High = false
        var otherPinHigh = false
        harness.context.ports.register(
            "soc-gpio",
            ShoulderFactory.BACKLIGHT_GPIO_PORT,
            GpioOutputPort::class.java,
            object : GpioOutputPort {
                override fun outputLevel(port: Int, pin: Int): Boolean = when {
                    port == ShoulderFactory.BACKLIGHT_GPIO_BANK && pin == ShoulderFactory.BACKLIGHT_GPIO_PIN -> bank8Pin9High
                    port == 8 && pin == 8 -> otherPinHigh
                    else -> false
                }
                override fun outputSequence(port: Int, pin: Int): Long = 0L
            },
        )
        val shoulder = factory.create(
            harness.context,
            mapOf(
                "width" to 304L,
                "height" to 230L,
                "brightness" to 1.0,
                "profile" to ShoulderFactory.DOTLCD,
                "orientation" to ShoulderFactory.ORIENTATION_UPRIGHT,
            ),
        ) as StatefulComponent
        shoulder.start()

        // Default boot state: backlight OFF (GPIO Bank 8 Pin 9 LOW)
        assertEquals(false, shoulder.state()["backlightOn"])
        otherPinHigh = true
        assertEquals(false, shoulder.state()["backlightOn"], "Unrelated GPIO pin must not toggle shoulder backlight")

        // Emit a capture and verify raw display.shoulder pixels remain binary 0xFFFFFFFF / 0xFF000000
        val rawBits = ByteArray(DotLcdTransportCodec.WIDTH * DotLcdTransportCodec.TRANSPORT_ROWS)
        rawBits[229 * DotLcdTransportCodec.WIDTH + 303] = 1
        val packet = DotLcdTransportCodec.encode(rawBits)
        harness.currentTick = 50L
        harness.activePayload = packet
        harness.activeCapture = TransferCapture(1L, DotLcdTransportCodec.BYTES, "bl-test-sha", 45L, 50L)
        harness.publishCaptureComplete(1L)

        val beforePixels = IntArray(304 * 230)
        assertTrue(harness.displayPort().copyArgb(beforePixels))
        assertTrue(beforePixels.all { it == 0xffffffff.toInt() || it == 0xff000000.toInt() })

        // Firmware drives GPIO(8, 9) HIGH (0x02000200 -> 0xFFF41200) -> backlightOn becomes true
        bank8Pin9High = true
        assertEquals(true, shoulder.state()["backlightOn"])
        val afterPixels = IntArray(304 * 230)
        assertTrue(harness.displayPort().copyArgb(afterPixels))
        assertContentEquals(beforePixels, afterPixels, "Raw display.shoulder pixels must remain untouched by backlight state")

        // Firmware drives GPIO(8, 9) LOW (0x02000000 -> 0xFFF41200) -> backlightOn returns to false
        bank8Pin9High = false
        assertEquals(false, shoulder.state()["backlightOn"])
    }
}

package com.github.nyabanban.fuji.emulator.components.display

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class DisplaySnapshotTest {
    private class DisplayTestHarness {
        private val registeredPorts = LinkedHashMap<String, Any>()
        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-display-test")
            override val clock = EmulationClock { 0L }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    assertEquals("display", owner)
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
                override fun publish(event: ComponentEvent) = error("unexpected display event")
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = error("unexpected subscribe")
            }
            override fun dataPath(relative: String): Path = error("unexpected dataPath")
        }

        fun displayPort(): DisplayPort = checkNotNull(context.ports.find("display.main", DisplayPort::class.java))
        fun evfPort(): DisplayPort = checkNotNull(context.ports.find("display.evf", DisplayPort::class.java))
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
    fun zeroSequenceAndMultiFrameRoundTripPreserveExactOperationalEquivalence() {
        val factory = DisplayFactory()
        val width = 16
        val height = 12
        val pixelCount = width * height

        val harnessOne = DisplayTestHarness()
        val displayOne = factory.create(
            harnessOne.context,
            mapOf("width" to width, "height" to height, "brightness" to 1.0),
        ) as StatefulComponent
        val portOne = harnessOne.displayPort()

        val initialSnapshot = simulateJsonRoundTripMap(displayOne.saveSnapshot())
        val emptyHarness = DisplayTestHarness()
        val emptyDisplay = factory.create(
            emptyHarness.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 0.2),
        ) as StatefulComponent
        emptyDisplay.restoreSnapshot(initialSnapshot)
        assertEquals(displayOne.state(), emptyDisplay.state())
        assertEquals(0L, emptyHarness.displayPort().sequence)
        assertFalse(emptyHarness.displayPort().copyArgb(IntArray(pixelCount)))

        val firstFrame = IntArray(pixelCount) { index -> 0xff000000.toInt() or (index * 97) }
        val secondFrame = IntArray(pixelCount) { index -> 0xff102030.toInt() xor (index * 131) }
        portOne.presentArgb(firstFrame, width, height)
        portOne.presentArgb(secondFrame, width, height)
        displayOne.applySettings(mapOf("brightness" to 0.45))

        val frontierSnapshot = simulateJsonRoundTripMap(displayOne.saveSnapshot())

        val harnessTwo = DisplayTestHarness()
        val displayTwo = factory.create(
            harnessTwo.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 1.0),
        ) as StatefulComponent
        val portTwo = harnessTwo.displayPort()
        displayTwo.restoreSnapshot(frontierSnapshot)

        assertEquals(displayOne.state(), displayTwo.state())
        assertEquals(2L, portTwo.sequence)
        val copiedOne = IntArray(pixelCount)
        val copiedTwo = IntArray(pixelCount)
        assertTrue(portOne.copyArgb(copiedOne))
        assertTrue(portTwo.copyArgb(copiedTwo))
        assertContentEquals(copiedOne, copiedTwo)

        val thirdFrame = IntArray(pixelCount) { index -> 0xff55aa00.toInt() + index }
        portOne.presentArgb(thirdFrame, width, height)
        portTwo.presentArgb(thirdFrame, width, height)
        displayOne.applySettings(mapOf("brightness" to 0.8))
        displayTwo.applySettings(mapOf("brightness" to 0.8))

        assertEquals(displayOne.state(), displayTwo.state())
        assertEquals(portOne.sequence, portTwo.sequence)
        assertTrue(portOne.copyArgb(copiedOne))
        assertTrue(portTwo.copyArgb(copiedTwo))
        assertContentEquals(copiedOne, copiedTwo)
        assertEquals(displayOne.saveSnapshot(), displayTwo.saveSnapshot())
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyWithoutLeakingScalarOrFramebufferState() {
        val factory = DisplayFactory()
        val width = 8
        val height = 6
        val pixelCount = width * height
        val harness = DisplayTestHarness()
        val display = factory.create(
            harness.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 0.75),
        ) as StatefulComponent
        val port = harness.displayPort()
        val baselinePixels = IntArray(pixelCount) { index -> 0xff112233.toInt() + index }
        port.presentArgb(baselinePixels, width, height)
        val baselineState = display.state()
        val validSnapshot = simulateJsonRoundTripMap(display.saveSnapshot())

        val replacementHarness = DisplayTestHarness()
        val replacementDisplay = factory.create(
            replacementHarness.context,
            mapOf("width" to width.toLong(), "height" to height.toLong(), "brightness" to 0.25),
        ) as StatefulComponent
        replacementHarness.displayPort().presentArgb(IntArray(pixelCount) { index -> 0xff998877.toInt() - index }, width, height)
        replacementHarness.displayPort().presentArgb(IntArray(pixelCount) { index -> 0xff445566.toInt() + index }, width, height)
        val replacementSnapshot = simulateJsonRoundTripMap(replacementDisplay.saveSnapshot())
        @Suppress("UNCHECKED_CAST")
        val validMainMap = replacementSnapshot["main"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val validScalars = replacementSnapshot["scalarValues"] as Map<String, Any?>

        val invalidCandidates = listOf<Map<String, Any?>>(
            validSnapshot + ("schema" to 2),
            validSnapshot + ("schema" to 1.5),
            validSnapshot + ("componentId" to "shoulder-display"),
            validSnapshot + ("unexpected" to true),
            validSnapshot + ("scalarValues" to "not-a-map"),
            replacementSnapshot + ("scalarValues" to (validScalars + ("brightness" to 1.5))),
            replacementSnapshot + ("scalarValues" to (validScalars + ("brightness" to -0.1))),
            replacementSnapshot + ("scalarValues" to (validScalars + ("width" to 16))),
            replacementSnapshot + ("scalarValues" to (validScalars + ("unknownKey" to 1))),
            replacementSnapshot + ("main" to (validMainMap + ("width" to 4))),
            replacementSnapshot + ("main" to (validMainMap + ("sequence" to -1))),
            replacementSnapshot + ("main" to (validMainMap + ("sequence" to 2.5))),
            replacementSnapshot + ("main" to (validMainMap + ("sequence" to 0) + ("pixelsBase64" to validMainMap["pixelsBase64"]))),
            replacementSnapshot + ("main" to (validMainMap + ("pixelsBase64" to null))),
            replacementSnapshot + ("main" to (validMainMap + ("pixelsBase64" to Base64.getEncoder().encodeToString(ByteArray(12))))),
            replacementSnapshot + ("main" to (validMainMap + ("pixelsBase64" to "***not-base64***"))),
        )

        for (candidate in invalidCandidates) {
            assertFails { display.restoreSnapshot(candidate) }
            assertEquals(baselineState, display.state())
            assertEquals(1L, port.sequence)
            val verifyPixels = IntArray(pixelCount)
            assertTrue(port.copyArgb(verifyPixels))
            assertContentEquals(baselinePixels, verifyPixels)
        }
    }

    @Test
    fun evfPortIsSeparateRoundTripsSnapshotsAndRestoresLegacyV1SnapshotsCleanly() {
        val factory = DisplayFactory()
        val harnessOne = DisplayTestHarness()
        val displayOne = factory.create(
            harnessOne.context,
            mapOf("width" to 8L, "height" to 6L, "evfWidth" to 12L, "evfHeight" to 10L, "brightness" to 0.9),
        ) as StatefulComponent
        val mainOne = harnessOne.displayPort()
        val evfOne = harnessOne.evfPort()
        assertTrue(evfOne !== mainOne)
        assertEquals(12, evfOne.width)
        assertEquals(10, evfOne.height)
        assertEquals(0L, evfOne.sequence)

        val mainPixels = IntArray(8 * 6) { idx -> 0xff224466.toInt() + idx }
        val evfPixels = IntArray(12 * 10) { idx -> 0xffaa00cc.toInt() xor idx }
        mainOne.presentArgb(mainPixels, 8, 6)
        evfOne.presentArgb(evfPixels, 12, 10)
        assertEquals(1L, mainOne.sequence)
        assertEquals(1L, evfOne.sequence)
        assertEquals(1L, displayOne.state()["evfSequence"])

        // Presenting on EVF must never alter main pixels or sequence
        val evfPixels2 = IntArray(12 * 10) { idx -> 0xff112233.toInt() + idx * 3 }
        evfOne.presentArgb(evfPixels2, 12, 10)
        assertEquals(1L, mainOne.sequence)
        assertEquals(2L, evfOne.sequence)
        val mainCopied = IntArray(8 * 6)
        assertTrue(mainOne.copyArgb(mainCopied))
        assertContentEquals(mainPixels, mainCopied)

        // Round-trip save/restore preserves both main and evf
        val snap = simulateJsonRoundTripMap(displayOne.saveSnapshot())
        val harnessTwo = DisplayTestHarness()
        val displayTwo = factory.create(
            harnessTwo.context,
            mapOf("width" to 8L, "height" to 6L, "evfWidth" to 12L, "evfHeight" to 10L, "brightness" to 0.5),
        ) as StatefulComponent
        displayTwo.restoreSnapshot(snap)
        assertEquals(displayOne.state(), displayTwo.state())
        val evfTwoCopied = IntArray(12 * 10)
        assertTrue(harnessTwo.evfPort().copyArgb(evfTwoCopied))
        assertContentEquals(evfPixels2, evfTwoCopied)

        // Malformed "evf" snapshot must reject atomically without mutating main, evf, or scalarValues
        @Suppress("UNCHECKED_CAST")
        val validEvfMap = snap["evf"] as Map<String, Any?>
        val stateBeforeError = displayTwo.state()
        for (badEvf in listOf<Any?>(
            null,
            validEvfMap + ("width" to 4),
            validEvfMap + ("sequence" to -1),
            validEvfMap + ("pixelsBase64" to "***bad***"),
        )) {
            assertFails { displayTwo.restoreSnapshot(snap + ("evf" to badEvf)) }
            assertEquals(stateBeforeError, displayTwo.state())
            val evfCheck = IntArray(12 * 10)
            assertTrue(harnessTwo.evfPort().copyArgb(evfCheck))
            assertContentEquals(evfPixels2, evfCheck)
        }

        // Legacy v1 snapshot (no "evf" key and no evfWidth/evfHeight in scalarValues) resets evf to sequence 0
        @Suppress("UNCHECKED_CAST")
        val legacyScalars = (snap["scalarValues"] as Map<String, Any?>) - "evfWidth" - "evfHeight"
        val legacySnap = mapOf(
            "schema" to 1,
            "componentId" to "display",
            "scalarValues" to legacyScalars,
            "main" to snap["main"],
        )
        displayTwo.restoreSnapshot(legacySnap)
        assertEquals(1L, harnessTwo.displayPort().sequence)
        assertEquals(0L, harnessTwo.evfPort().sequence)
        assertFalse(harnessTwo.evfPort().copyArgb(IntArray(12 * 10)))
        assertEquals(0L, displayTwo.state()["evfSequence"])
    }
}

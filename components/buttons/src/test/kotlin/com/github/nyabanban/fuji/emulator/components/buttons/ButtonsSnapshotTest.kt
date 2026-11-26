package com.github.nyabanban.fuji.emulator.components.buttons

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class ButtonsSnapshotTest {
    private class ButtonsTestHarness {
        var currentTick: Long = 0L
        val publishedEvents = ArrayList<ComponentEvent>()
        lateinit var buttonPort: ButtonPort

        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-buttons-test")
            override val clock = EmulationClock { currentTick }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    assertEquals("buttons", owner)
                    assertEquals("buttons", name)
                    assertEquals<Class<*>>(ButtonPort::class.java, type)
                    buttonPort = port as ButtonPort
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? = null
                override fun removeOwner(owner: String) {}
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {
                    publishedEvents.add(event)
                }
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = AutoCloseable {}
            }
            override fun dataPath(relative: String): Path = error("unexpected dataPath")
        }
    }

    private fun defaultConfig(factory: ButtonsFactory): Map<String, Any> =
        factory.descriptor.settings.associate { setting -> setting.key to setting.default }

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
    fun restoreSilentlyAppliesSwitchesWithoutSpuriousEventsAndMatchesSubsequentTransitions() {
        val factory = ButtonsFactory()
        val harnessOne = ButtonsTestHarness()
        val buttonsOne = factory.create(harnessOne.context, defaultConfig(factory)) as StatefulComponent

        harnessOne.currentTick = 10L
        buttonsOne.applySettings(mapOf("menu" to true, "shutterHalf" to true))
        assertEquals(1, harnessOne.publishedEvents.size)

        val frontierSnapshot = simulateJsonRoundTripMap(buttonsOne.saveSnapshot())

        val harnessTwo = ButtonsTestHarness()
        val buttonsTwo = factory.create(harnessTwo.context, defaultConfig(factory)) as StatefulComponent
        harnessTwo.currentTick = 10L
        buttonsTwo.restoreSnapshot(frontierSnapshot)

        assertTrue(harnessTwo.publishedEvents.isEmpty())
        assertEquals(buttonsOne.state(), buttonsTwo.state())
        for (setting in factory.descriptor.settings) {
            assertEquals(harnessOne.buttonPort.pressed(setting.key), harnessTwo.buttonPort.pressed(setting.key))
        }

        harnessOne.publishedEvents.clear()
        harnessOne.currentTick = 20L
        harnessTwo.currentTick = 20L
        buttonsOne.applySettings(mapOf("menu" to true))
        buttonsTwo.applySettings(mapOf("menu" to true))
        assertTrue(harnessOne.publishedEvents.isEmpty())
        assertTrue(harnessTwo.publishedEvents.isEmpty())

        harnessOne.currentTick = 30L
        harnessTwo.currentTick = 30L
        buttonsOne.applySettings(mapOf("menu" to false, "shutterFull" to true))
        buttonsTwo.applySettings(mapOf("menu" to false, "shutterFull" to true))

        assertEquals(harnessOne.publishedEvents, harnessTwo.publishedEvents)
        assertEquals(buttonsOne.state(), buttonsTwo.state())
        assertEquals(buttonsOne.saveSnapshot(), buttonsTwo.saveSnapshot())
    }

    @Test
    fun legacySnapshotReleasesShortcutKeysAndNewSnapshotRestoresBothHeld() {
        val factory = ButtonsFactory()
        val harness = ButtonsTestHarness()
        val component = factory.create(harness.context, defaultConfig(factory)) as StatefulComponent
        component.applySettings(mapOf("delete" to true, "rearDialPress" to true))
        val held = component.saveSnapshot()
        val legacy = held + ("scalarValues" to (defaultConfig(factory) - setOf("delete", "rearDialPress")))
        component.restoreSnapshot(legacy)
        assertFalse(harness.buttonPort.pressed("delete"))
        assertFalse(harness.buttonPort.pressed("rearDialPress"))
        component.restoreSnapshot(held)
        assertTrue(harness.buttonPort.pressed("delete"))
        assertTrue(harness.buttonPort.pressed("rearDialPress"))
    }

    @Test
    fun legacySnapshotDefaultsMovieModeFalseAndNewSnapshotPreservesMovieMode() {
        val factory = ButtonsFactory()
        val harness = ButtonsTestHarness()
        val component = factory.create(harness.context, defaultConfig(factory)) as StatefulComponent
        assertFalse(harness.buttonPort.pressed("movieMode"))
        component.applySettings(mapOf("movieMode" to true))
        assertTrue(harness.buttonPort.pressed("movieMode"))
        val movieHeld = simulateJsonRoundTripMap(component.saveSnapshot())
        val legacyWithoutMovieMode = movieHeld + ("scalarValues" to (defaultConfig(factory) - "movieMode"))
        component.restoreSnapshot(legacyWithoutMovieMode)
        assertFalse(harness.buttonPort.pressed("movieMode"))
        component.restoreSnapshot(movieHeld)
        assertTrue(harness.buttonPort.pressed("movieMode"))
    }

    @Test
    fun legacySnapshotDefaultsDriveAfOnFocusModeFalseAndNewSnapshotPreservesAllThree() {
        val factory = ButtonsFactory()
        val harness = ButtonsTestHarness()
        val component = factory.create(harness.context, defaultConfig(factory)) as StatefulComponent
        assertFalse(harness.buttonPort.pressed("drive"))
        assertFalse(harness.buttonPort.pressed("afOn"))
        assertFalse(harness.buttonPort.pressed("focusMode"))
        component.applySettings(mapOf("drive" to true, "afOn" to true, "focusMode" to true))
        assertTrue(harness.buttonPort.pressed("drive"))
        assertTrue(harness.buttonPort.pressed("afOn"))
        assertTrue(harness.buttonPort.pressed("focusMode"))
        val held = simulateJsonRoundTripMap(component.saveSnapshot())
        val legacyV4 = held + ("scalarValues" to (defaultConfig(factory) - setOf("drive", "afOn", "focusMode")))
        component.restoreSnapshot(legacyV4)
        assertFalse(harness.buttonPort.pressed("drive"))
        assertFalse(harness.buttonPort.pressed("afOn"))
        assertFalse(harness.buttonPort.pressed("focusMode"))
        component.restoreSnapshot(held)
        assertTrue(harness.buttonPort.pressed("drive"))
        assertTrue(harness.buttonPort.pressed("afOn"))
        assertTrue(harness.buttonPort.pressed("focusMode"))
    }

    @Test
    fun legacyV5SnapshotDefaultsFrontDialPressFn4FocusStickPressFalseAndV6PreservesAllThree() {
        val factory = ButtonsFactory()
        val harness = ButtonsTestHarness()
        val component = factory.create(harness.context, defaultConfig(factory)) as StatefulComponent
        assertFalse(harness.buttonPort.pressed("frontDialPress"))
        assertFalse(harness.buttonPort.pressed("fn4"))
        assertFalse(harness.buttonPort.pressed("focusStickPress"))
        component.applySettings(mapOf("frontDialPress" to true, "fn4" to true, "focusStickPress" to true))
        assertTrue(harness.buttonPort.pressed("frontDialPress"))
        assertTrue(harness.buttonPort.pressed("fn4"))
        assertTrue(harness.buttonPort.pressed("focusStickPress"))
        val held = simulateJsonRoundTripMap(component.saveSnapshot())
        val legacyV5 = held + ("scalarValues" to (defaultConfig(factory) - setOf("frontDialPress", "fn4", "focusStickPress")))
        component.restoreSnapshot(legacyV5)
        assertFalse(harness.buttonPort.pressed("frontDialPress"))
        assertFalse(harness.buttonPort.pressed("fn4"))
        assertFalse(harness.buttonPort.pressed("focusStickPress"))
        component.restoreSnapshot(held)
        assertTrue(harness.buttonPort.pressed("frontDialPress"))
        assertTrue(harness.buttonPort.pressed("fn4"))
        assertTrue(harness.buttonPort.pressed("focusStickPress"))
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyWithoutMutatingSwitchesOrPublishingEvents() {
        val factory = ButtonsFactory()
        val harness = ButtonsTestHarness()
        val buttons = factory.create(harness.context, defaultConfig(factory)) as StatefulComponent
        buttons.applySettings(mapOf("power" to true, "play" to true))
        harness.publishedEvents.clear()

        val baselineState = buttons.state()
        val validSnapshot = simulateJsonRoundTripMap(buttons.saveSnapshot())
        @Suppress("UNCHECKED_CAST")
        val validScalars = validSnapshot["scalarValues"] as Map<String, Any?>

        val invalidCandidates = listOf<Map<String, Any?>>(
            validSnapshot + ("schema" to 2),
            validSnapshot + ("schema" to 1.25),
            validSnapshot + ("componentId" to "battery"),
            validSnapshot + ("extraKey" to false),
            validSnapshot + ("scalarValues" to (validScalars + ("power" to "false"))),
            validSnapshot + ("scalarValues" to (validScalars + ("menu" to 1))),
            validSnapshot + ("scalarValues" to (validScalars + ("unknownSwitch" to true))),
        )

        for (candidate in invalidCandidates) {
            assertFails { buttons.restoreSnapshot(candidate) }
            assertEquals(baselineState, buttons.state())
            assertTrue(harness.buttonPort.pressed("power"))
            assertTrue(harness.buttonPort.pressed("play"))
            assertTrue(harness.publishedEvents.isEmpty())
        }
    }

    @Test
    fun primitiveBitmaskTracksAllThirteenKeysAccuratelyAcrossTransitionsAndSnapshots() {
        val factory = ButtonsFactory()
        val harness = ButtonsTestHarness()
        val initial = defaultConfig(factory) + mapOf("power" to true, "down" to true)
        val buttons = factory.create(harness.context, initial) as StatefulComponent
        assertTrue(harness.buttonPort.pressed("power"))
        assertTrue(harness.buttonPort.pressed("down"))
        assertFalse(harness.buttonPort.pressed("up"))

        for (setting in factory.descriptor.settings) {
            buttons.applySettings(mapOf(setting.key to true))
            assertTrue(harness.buttonPort.pressed(setting.key), "Expected ${setting.key}=true")
            buttons.applySettings(mapOf(setting.key to false))
            assertFalse(harness.buttonPort.pressed(setting.key), "Expected ${setting.key}=false")
        }
        harness.publishedEvents.clear()
        // Idempotent no-op emits zero events
        buttons.applySettings(mapOf("menu" to false, "down" to false))
        assertTrue(harness.publishedEvents.isEmpty())
    }
}

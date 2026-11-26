package com.github.nyabanban.fuji.emulator.components.gpio

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class GpioGlobalControlTest {
    private class GpioContext : ComponentContext {
        val registeredPorts = linkedMapOf<String, Any>()
        override val directory: Path = Path.of("unused-gpio-global-test")
        override val clock = EmulationClock { 0L }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }

        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(registeredPorts.putIfAbsent(name, port) == null)
            }

            override fun <T : Any> find(name: String, type: Class<T>): T? =
                registeredPorts[name]?.let(type::cast)

            override fun removeOwner(owner: String) = error("not used")
        }

        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    @Suppress("UNCHECKED_CAST")
    private fun roundTripJson(raw: Map<String, Any?>): Map<String, Any?> {
        fun convert(v: Any?): Any? = when (v) {
            null, is Boolean, is String -> v
            is Byte, is Short, is Int, is Long -> {
                val n = (v as Number).toLong()
                if (n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) n.toInt() else n
            }
            is List<*> -> v.map(::convert)
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                for ((k, elem) in v) put(k.toString(), convert(elem))
            }
            else -> error("Unsupported type: ${v::class.java.name}")
        }
        return convert(raw) as Map<String, Any?>
    }

    @Test
    fun globalControlRegisterModelsMasked16BitWritesAndRoundTripsSnapshots() {
        val ctx = GpioContext()
        val factory = GpioFactory()
        val defaults = factory.descriptor.settings.associate { it.key to it.default }
        val comp = assertIs<StatefulComponent>(
            factory.create(ctx, defaults + mapOf("globalControlProfile" to GpioFactory.GLOBAL_CONTROL_PROFILE)),
        )
        comp.start()
        assertEquals(listOf(GpioFactory.GLOBAL_CONTROL_PORT), comp.additionalMmioPorts())

        val globalPort = ctx.ports.find(GpioFactory.GLOBAL_CONTROL_PORT, MmioDevice::class.java)!!
        assertEquals(0L, globalPort.read(0, 4))

        // Boot ROM entry 0: WRITE4 0xFFF41500 = 0xE3F4E3F4 -> latches 0xE3F4
        globalPort.write(0, 4, 0xe3f4_e3f4L)
        assertEquals(0xe3f4L, globalPort.read(0, 4))

        // Masked selective clear of bit 4 & set of bit 0, leaving all other bits intact
        globalPort.write(0, 4, 0x0011_0001L)
        assertEquals(0xe3e5L, globalPort.read(0, 4))

        // Fail closed on unaligned offset or non-32-bit width
        assertFailsWith<IllegalArgumentException> { globalPort.read(4, 4) }
        assertFailsWith<IllegalArgumentException> { globalPort.write(0, 2, 0xffffL) }

        val saved = roundTripJson(comp.saveSnapshot())
        assertEquals(0xe3e5, saved["globalControlFff41500"])

        val ctx2 = GpioContext()
        val comp2 = assertIs<StatefulComponent>(
            factory.create(ctx2, defaults + mapOf("globalControlProfile" to GpioFactory.GLOBAL_CONTROL_PROFILE)),
        )
        comp2.start()
        comp2.restoreSnapshot(saved)
        assertEquals(comp.state(), comp2.state())
        assertEquals(0xe3e5L, ctx2.ports.find(GpioFactory.GLOBAL_CONTROL_PORT, MmioDevice::class.java)!!.read(0, 4))

        // Disabled component still preserves historical snapshot shape & restores cleanly into enabled component
        val disabledCtx = GpioContext()
        val disabledComp = assertIs<StatefulComponent>(factory.create(disabledCtx, defaults))
        disabledComp.start()
        assertTrue(disabledComp.additionalMmioPorts().isEmpty())
        assertFalse("globalControlFff41500" in disabledComp.state())
        val disabledSnap = roundTripJson(disabledComp.saveSnapshot())
        assertFalse("globalControlFff41500" in disabledSnap)
        comp2.restoreSnapshot(disabledSnap)
        assertEquals(0L, ctx2.ports.find(GpioFactory.GLOBAL_CONTROL_PORT, MmioDevice::class.java)!!.read(0, 4))

        // Invalid value > 0xFFFF rejects atomically
        val baseline = comp2.state()
        val badSnap = LinkedHashMap(saved).apply { put("globalControlFff41500", 0x1_0000) }
        assertFailsWith<IllegalArgumentException> { comp2.restoreSnapshot(badSnap) }
        assertEquals(baseline, comp2.state())

        comp.close()
        comp2.close()
        disabledComp.close()
    }
}

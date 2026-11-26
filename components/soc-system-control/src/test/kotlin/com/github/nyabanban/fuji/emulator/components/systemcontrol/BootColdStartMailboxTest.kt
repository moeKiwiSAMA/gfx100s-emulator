package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class BootColdStartMailboxTest {
    private class TestContext(private val strapValue: Long = 8L) : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of("unused-sysctrl-bootrom-test").toAbsolutePath().normalize()
        override val clock = EmulationClock { 0L }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }

            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }

        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = mapOf(
            "schema" to 1,
            "profile" to BootStrapInput.PROFILE,
            "value" to strapValue,
        )
    }

    private fun createComponent(withStrap: Boolean = true): Pair<TestContext, StatefulComponent> {
        val ctx = TestContext()
        val factory = SystemControlFactory()
        val defaults = factory.descriptor.settings.associate { it.key to it.default }
        val cfg = defaults + mapOf(
            "profile" to BootColdStartMailbox.PROFILE,
            "booleanWrite" to true,
            "bootStrapInputFile" to if (withStrap) "boot-strap.json" else "",
        )
        val comp = assertIs<StatefulComponent>(factory.create(ctx, cfg))
        comp.start()
        return ctx to comp
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
    fun bootColdStartMailboxAndCtrl8180CoexistWithBootStrapAndRoundTripSnapshots() {
        val (ctx, comp) = createComponent(withStrap = true)
        assertTrue(BootStrapInput.PORT in comp.additionalMmioPorts())
        assertTrue(BootColdStartMailbox.CTRL_8180_PORT in comp.additionalMmioPorts())
        assertTrue(BootColdStartMailbox.BOOT_STATUS_MAILBOX_PORT in comp.additionalMmioPorts())

        val strap = ctx.ports.find(BootStrapInput.PORT, MmioDevice::class.java)!!
        val ctrl8180 = ctx.ports.find(BootColdStartMailbox.CTRL_8180_PORT, MmioDevice::class.java)!!
        val mailbox = ctx.ports.find(BootColdStartMailbox.BOOT_STATUS_MAILBOX_PORT, MmioDevice::class.java)!!

        assertEquals(8L, strap.read(0, 4))
        assertEquals(0L, ctrl8180.read(0, 4))
        ctrl8180.write(0, 4, 5L)
        assertEquals(5L, ctrl8180.read(0, 4))

        // Write 4-core Boot ROM status tokens: CPU0..CPU3
        mailbox.write(0x0, 4, 0x0000_900dL)
        mailbox.write(0x4, 4, 0x1000_900dL)
        mailbox.write(0x8, 4, 0x2000_900dL)
        mailbox.write(0xc, 4, 0x3000_900dL)
        assertEquals(0x0000_900dL, mailbox.read(0x0, 4))
        assertEquals(0x1000_900dL, mailbox.read(0x4, 4))
        assertEquals(0x2000_900dL, mailbox.read(0x8, 4))
        assertEquals(0x3000_900dL, mailbox.read(0xc, 4))

        // Fail closed on invalid offsets/widths
        assertFailsWith<IllegalArgumentException> { ctrl8180.read(4, 4) }
        assertFailsWith<IllegalArgumentException> { ctrl8180.write(0, 2, 1L) }
        assertFailsWith<IllegalArgumentException> { mailbox.read(0x10, 4) }
        assertFailsWith<IllegalArgumentException> { mailbox.write(0x2, 4, 1L) }

        // Snapshot round-trip
        val saved = roundTripJson(comp.saveSnapshot())
        val (ctx2, comp2) = createComponent(withStrap = true)
        comp2.restoreSnapshot(saved)
        assertEquals(comp.state(), comp2.state())
        val mailbox2 = ctx2.ports.find(BootColdStartMailbox.BOOT_STATUS_MAILBOX_PORT, MmioDevice::class.java)!!
        assertEquals(0x3000_900dL, mailbox2.read(0xc, 4))

        // Atomic invalid snapshot rejection
        val baseline = comp2.state()
        val badSnap = LinkedHashMap(saved)
        @Suppress("UNCHECKED_CAST")
        val badCold = LinkedHashMap(badSnap["bootColdStartState"] as Map<String, Any?>)
        badCold["bootStatusWords"] = listOf(1, 2, 3) // missing 4th core slot
        badSnap["bootColdStartState"] = badCold
        assertFailsWith<IllegalArgumentException> { comp2.restoreSnapshot(badSnap) }
        assertEquals(baseline, comp2.state())
    }
}

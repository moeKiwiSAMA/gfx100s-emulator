package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice
import com.github.nyabanban.fuji.emulator.runtime.MmioRouter
import com.github.nyabanban.fuji.emulator.runtime.MmioWindow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdleClockRingTest {
    @Test
    fun ringBufferWrapsAt256AndRoundTripsSnapshotExactly() {
        var next = 10L
        val timer = object : TickAwareMmioDevice {
            override fun read(offset: Long, size: Int) = 0L
            override fun write(offset: Long, size: Int, value: Long) {}
            override fun nextDeadline(): Long? = next
            override fun nextInterestedTick(): Long = next
            override fun beforeInstruction(tick: Long) {
                next = tick + 10L
            }
        }
        val router = MmioRouter(listOf(MmioWindow("soc-timer", 0x100, 4, timer)))
        val spec = mapOf("profile" to IdleClock.PROFILE, "maxAdvances" to 500, "maxIdleTicks" to 10_000)
        val clock = requireNotNull(IdleClock.parse(spec))

        var now = 0L
        for (i in 1..300) {
            assertTrue(clock.advance(now, i.toLong(), router) { t -> now = t })
        }
        assertEquals(3000L, now)

        val st = clock.state(now, 300L)
        assertEquals(300L, st["advanceCount"])
        assertEquals(3000L, st["idleTicks"])
        assertEquals(true, st["eventLogTruncated"])
        @Suppress("UNCHECKED_CAST")
        val events = st["events"] as List<Map<String, Any?>>
        assertEquals(256, events.size)
        assertEquals(440L, events.first()["from"])
        assertEquals(450L, events.first()["to"])
        assertEquals(45L, events.first()["instructionAttempts"])
        assertEquals(2990L, events.last()["from"])
        assertEquals(3000L, events.last()["to"])
        assertEquals(300L, events.last()["instructionAttempts"])
        assertEquals(true, events.last()["devicesAdvanced"])
        assertEquals(null, events.last()["failure"])

        val snap = clock.saveSnapshot()
        val restored = requireNotNull(IdleClock.parse(spec))
        restored.restoreSnapshot(snap)
        assertEquals(snap, restored.saveSnapshot())
        assertEquals(st, restored.state(now, 300L))
    }
}

package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class C000StopReplyTest {
    private val base = 0xfff6c000L
    private val clear = 0xa5a5a5a4L
    private val poll = 0x011b3508L
    private val request = 0x011b34f8L
    private var core = 1
    private var pc = 0L
    private var lr = 0L
    private var tick = 833362203L
    private val context = object : DiscoveryContext {
        override fun core() = core
        override fun tick() = tick
        override fun pc() = pc
        override fun lr() = lr
    }
    private val catalog = AddressCatalog.bundled()
    private fun bus(profile: String? = C000StopReply.PROFILE, fill: Int = 165): DiscoveryBus {
        val declaration = mapOf("strategy" to "retained-bytes", "fill" to fill) +
            if (profile == null) emptyMap() else mapOf("completion" to profile)
        val config = mapOf("discovery" to mapOf("inputs" to mapOf(RETAINED_6C000_REGION to declaration)))
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), config).createBus(context))
    }
    private fun DiscoveryBus.word(ret: Long, write: Long? = null, address: Long = base, width: Int = 4,
                                  helper: Long = if (write == null) 0x5afe0 else 0x5af98): Long {
        lr = ret
        pc = helper
        return access(++tick, address, width, write != null, write ?: 0)
    }

    @Test fun qualifiesOnlyTheWaitingHelperAndCompletesOneActualWrite() {
        val bus = bus()
        for (other in listOf(0x11b2934L, 0x11b29dcL, 0x11b34bcL)) {
            bus.word(other, clear)
            assertEquals(clear, bus.word(poll))
        }
        core = 0
        bus.word(request, clear)
        core = 1
        bus.word(request, clear, helper = 0x5af94)
        bus.word(request, 4, base + 0x140)
        assertEquals(clear, bus.word(poll))
        assertEquals(clear, bus.word(0x11b34ec))
        bus.word(request, clear)
        assertEquals(clear, bus.word(0x11b34ec))
        core = 0
        assertEquals(clear, bus.word(poll))
        core = 1
        assertEquals(clear, bus.word(poll, helper = 0x5afdc))
        assertEquals(clear, bus.word(poll))
        assertEquals(clear + 1, bus.word(poll))
        val rows = bus.snapshot()["accesses"] as List<*>
        val firstPoll = rows[rows.size - 2] as Map<*, *>
        assertEquals(clear, firstPoll["value"])
        assertEquals(clear, firstPoll["before"])
        assertEquals(clear + 1, firstPoll["after"])
        assertFailsWith<IllegalStateException> { bus.word(request, clear) }
    }

    @Test fun preservesPendingAndSpentAcrossRestoreAndNeverInfersARequestFromLegacyBytes() {
        val old = bus(null)
        old.word(request, clear)
        assertEquals(clear, old.word(poll))
        val prior = old.saveSnapshot()
        assertFails { bus().restoreSnapshot(prior) }
        val selected = bus().apply { restoreSnapshot(prior, true) }
        assertEquals(clear, selected.word(poll))
        selected.word(request, clear)
        val pending = selected.saveSnapshot()
        assertFails { bus(null).restoreSnapshot(pending, true) }
        assertFails { bus(fill = 0).restoreSnapshot(pending, true) }
        val resumed = bus().apply { restoreSnapshot(pending) }
        assertEquals(clear, resumed.word(poll))
        val spent = bus().apply { restoreSnapshot(resumed.saveSnapshot()) }
        assertEquals(clear + 1, spent.word(poll))
        assertFails { spent.word(request, clear) }
        val superseded = bus().apply { restoreSnapshot(pending) }
        superseded.word(0x11b29dc, 0x23456788)
        val cancelled = bus().apply { restoreSnapshot(superseded.saveSnapshot()) }
        assertEquals(0x23456788L, cancelled.word(poll))
        assertFails { cancelled.word(request, 0x23456788) }
    }

    @Test fun rejectsMalformedRequestsAndLeavesOtherRetainedBitsIntact() {
        assertFails { bus(C204_COMPLETION) }
        assertFails { bus().word(request, clear, width = 2) }
        assertFails { bus().word(request, clear, address = base + 1) }
        assertFails { bus().word(request, clear + 1) }
        assertFails { bus().word(request, 0) }
        val selected = bus()
        selected.word(0x11b29dc, 0x12345679)
        selected.word(request, 0x12345678)
        assertEquals(0x12345678L, selected.word(poll))
        assertEquals(0x12345679L, selected.word(poll))
    }

    @Test fun datClearWriteAndWaitUseDistinctCallSites() {
        val root = Path.of(System.getProperty("componentProjectRoot"))
        val bytes = ByteBuffer.wrap(Files.readAllBytes(root.resolve("firmware-data/gfx100s/2.13/decoded/section4.bin")))
            .order(ByteOrder.LITTLE_ENDIAN)
        fun word(address: Int) = bytes.getInt(address - 0x01021000)
        for ((call, target) in mapOf(0x11b2924 to 0x5afd8, 0x11b2930 to 0x5af90, 0x11b34ac to 0x5afd8,
            0x11b34b8 to 0x5af90, 0x11b34e8 to 0x5afd8, 0x11b34f4 to 0x5af90, 0x11b3504 to 0x5afd8)) {
            assertEquals(0xeb000000.toInt() or (((target - call - 8) shr 2) and 0xffffff), word(call))
        }
        assertEquals(0xe3a04903.toInt(), word(0x11b34dc))
        assertEquals(0xe34f4ff6.toInt(), word(0x11b34e0))
        assertEquals(0xe3c01001.toInt(), word(0x11b34ec))
        assertEquals(0xe3100001.toInt(), word(0x11b3508))
        assertEquals(0x0afffffb, word(0x11b350c))
    }

    @Test fun repeatableProfileSupportsMultipleStopCyclesAndPromotesFromOnceSnapshot() {
        val once = bus(C000StopReply.PROFILE)
        once.word(request, clear)
        assertEquals(clear, once.word(poll))
        assertEquals(clear + 1, once.word(poll))
        val onceSpent = once.saveSnapshot()

        // Promoting once->repeatable requires allowPromotedRegions=true; downgrading is rejected
        assertFailsWith<IllegalArgumentException> {
            bus(C000StopReply.REPEATABLE_PROFILE).restoreSnapshot(onceSpent, allowPromotedRegions = false)
        }
        val promoted = bus(C000StopReply.REPEATABLE_PROFILE).apply {
            restoreSnapshot(onceSpent, allowPromotedRegions = true)
        }
        // Second stop cycle succeeds under repeatable profile
        promoted.word(request, clear)
        assertEquals(clear, promoted.word(poll))
        assertEquals(clear + 1, promoted.word(poll))
        // Third stop cycle after another save/restore also succeeds
        val resumed = bus(C000StopReply.REPEATABLE_PROFILE).apply {
            restoreSnapshot(promoted.saveSnapshot(), allowPromotedRegions = false)
        }
        resumed.word(request, clear)
        assertEquals(clear, resumed.word(poll))
        assertEquals(clear + 1, resumed.word(poll))
        assertFailsWith<IllegalArgumentException> {
            bus(C000StopReply.PROFILE).restoreSnapshot(resumed.saveSnapshot(), allowPromotedRegions = true)
        }
    }
}

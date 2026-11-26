package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import org.junit.jupiter.api.Test

class ProgrammedImageReplyTest {
    private var tick = 0L
    private var caller = 0L
    private var pc = 0L
    private var selector = 1
    private var payload = ByteArray(0)
    private val readers = listOf(0x11b669cL, 0x11b667cL, 0x11b665cL, 0x11b663cL,
        0x11b661cL, 0x11b65fcL, 0x11b65dcL, 0x11b65b8L)
    private val context = object : DiscoveryContext {
        override fun core() = 1
        override fun tick() = tick
        override fun pc() = pc
        override fun lr() = caller
        override fun hasImageRequest82(selector: Int) = selector == this@ProgrammedImageReplyTest.selector
        override fun imageCommandBytes(address: Long, length: Int): ByteArray {
            assertEquals(if (length == 8) 0x028f2000L else 0x028f2940L, address)
            return payload
        }
    }
    private val catalog = AddressCatalog.bundled()
    private fun bus(enabled: Boolean = true, profile: String = ProgrammedImageReply.PROFILE): DiscoveryBus {
        val settings = mutableMapOf<String, Any>("imageReply820040" to "zero-alternating-16-pairs-v1",
            "imageReply820001" to "zero-once-v1", "inputs" to mapOf(
                RETAINED_6C204_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0, "completion" to C204_COMPLETION),
                RETAINED_6C20C_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0)))
        if (enabled) settings[ProgrammedImageReply.KEY] = profile
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to settings)).createBus(context))
    }

    private fun access(active: DiscoveryBus, offset: Int, lr: Long, value: Long? = null): Long {
        pc = if (value == null) 0x5afe0 else 0x5af98
        caller = lr
        return active.access(++tick, 0xfff6c000L + offset, 4, value != null, value ?: 0L)
    }

    private fun read(active: DiscoveryBus, word: Int) = access(active, 0x240 - word * 4, readers[word])

    private fun promoted(): DiscoveryBus {
        val old = bus(false)
        selector = 0x40
        repeat(8) { read(old, it) }
        selector = 1
        repeat(8) { read(old, it) }
        val snapshot = old.saveSnapshot()
        assertFails { bus().restoreSnapshot(snapshot) }
        return bus().apply { restoreSnapshot(snapshot, true) }
    }

    private fun setup(active: DiscoveryBus, mode: Int) {
        access(active, 0x208, 0x11b6500, mode.toLong())
        access(active, 0x21c, 0x11b3988, 0)
        access(active, 0x20c, 0x11b399c, 0)
        access(active, 0x210, 0x11b39ac, if (mode and 255 == 1) 0x028f2000 else 0x028f2940)
        access(active, 0x214, 0x11b39c0, 0)
        access(active, 0x218, 0x11b39d0, 8)
    }

    private fun submit(active: DiscoveryBus, mode: Int) {
        setup(active, mode)
        access(active, 0x204, 0x11b6464, 1)
        assertEquals(1, access(active, 0x204, 0x11b648c))
        assertEquals(0, access(active, 0x204, 0x11b648c))
    }

    private fun command(op: Int, address: Int, value: Int) =
        byteArrayOf(op.toByte(), (address ushr 8).toByte(), address.toByte(), value.toByte(), 0, 0, 0, 0)

    private fun completedByte(active: DiscoveryBus): Any? {
        val states = active.boundedSnapshot(1)["regions"] as List<*>
        val reply = states.filterIsInstance<Map<*, *>>().single { it["id"] == IMAGE_REPLY_PAIRS }
        return (reply["programmedReply"] as Map<*, *>)["completedByte"]
    }

    @Test fun snapshotsAtSetupStartCompletionAndReadKeepActualLastWrite() {
        var active = promoted()
        setup(active, 0x104ff)
        fun restore() { active = bus().apply { restoreSnapshot(active.saveSnapshot()) } }
        restore()
        payload = ByteArray(255 * 8) { if (it % 8 == 0) 0x81.toByte() else 0 }
        command(0x81, 1, 0x35).copyInto(payload, 8)
        access(active, 0x204, 0x11b6464, 1)
        payload.fill(0)
        restore()
        assertEquals(null, completedByte(active))
        assertEquals(1, access(active, 0x204, 0x11b648c))
        restore()
        assertEquals(0, access(active, 0x204, 0x11b648c))
        restore()
        assertEquals(0x35, completedByte(active))
        selector = 0x41
        payload = command(0x82, selector, 0)
        submit(active, 0x30401)
        repeat(8) { assertEquals(0L, read(active, it)) }
        selector = 1
        payload = command(0x81, 1, 4) + command(0x81, 1, 0x71)
        submit(active, 0x10402)
        assertEquals(0x71, completedByte(active))
        payload = command(0x81, 1, 0x53)
        submit(active, 0x10401)
        assertEquals(0x53, completedByte(active))
        payload = command(0x81, 0x101, 0x19)
        submit(active, 0x10401)
        assertFails { read(bus().apply { restoreSnapshot(active.saveSnapshot()) }, 0) }
        payload = command(0x82, 1, 0)
        setup(active, 0x30401)
        access(active, 0x204, 0x11b6464, 1)
        assertFails { read(bus().apply { restoreSnapshot(active.saveSnapshot()) }, 0) }
        access(active, 0x204, 0x11b648c)
        access(active, 0x204, 0x11b648c)
        assertFails { bus(false).restoreSnapshot(active.saveSnapshot(), true) }
        repeat(8) { word ->
            restore()
            assertEquals(if (word == 0) 0x530000L else 0L, read(active, word))
        }
        restore()
        selector = 0x40
        repeat(8) { assertEquals(0L, read(active, it)) }
        selector = 1
        assertFails { read(active, 0) }
    }

    @Test fun partialSetupWrongCallerBadPayloadAndMissingWriteCannotArmReply() {
        val active = promoted()
        val fresh = active.saveSnapshot()
        assertFails { read(active, 0) }
        fun restored() = bus().apply { restoreSnapshot(fresh) }
        assertFails { access(restored(), 0x208, 0x11b6464, 0x104ff) }
        val missing = restored()
        access(missing, 0x208, 0x11b6500, 0x104ff)
        assertFails { access(missing, 0x204, 0x11b6464, 1) }
        val prepared = restored()
        setup(prepared, 0x104ff)
        val before = prepared.saveSnapshot()
        val rows = before["regions"] as Map<*, *>
        val bank = rows[RETAINED_6C20C_REGION] as Map<*, *>
        assertFails { bus().restoreSnapshot(before + ("regions" to rows +
            (RETAINED_6C20C_REGION to bank + ("mutatedBytesBase64" to null)))) }
        for (bad in listOf(ByteArray(8), ByteArray(2040))) {
            payload = bad
            assertFails { access(bus().apply { restoreSnapshot(before) }, 0x204, 0x11b6464, 1) }
        }
        payload = ByteArray(2040) { if (it % 8 == 0) 0x81.toByte() else 0 }
        access(prepared, 0x204, 0x11b6464, 1)
        access(prepared, 0x204, 0x11b648c)
        access(prepared, 0x204, 0x11b648c)
        payload = command(0x82, 1, 0)
        setup(prepared, 0x30401)
        assertFails { access(prepared, 0x204, 0x11b6464, 1) }
    }

    @Test fun capsCommitHistoryAndAcceptsProgrammedZero() {
        val active = promoted()
        payload = ByteArray(2040) { if (it % 8 == 0) 0x81.toByte() else 0 }
        command(0x81, 1, 0).copyInto(payload, 16)
        submit(active, 0x104ff)
        assertEquals(0, completedByte(active))
        payload = command(0x81, 2, 0xee)
        repeat(15) { submit(active, 0x10401) }
        val full = active.saveSnapshot()
        val restored = bus().apply { restoreSnapshot(full) }
        assertFails { setup(restored, 0x10401) }
        assertEquals(0, completedByte(restored))
    }

    @Test fun repeatableProfilePromotesFromSpentOnceProfileAndServesSubsequentReadbacks() {
        val once = promoted()
        payload = ByteArray(255 * 8) { if (it % 8 == 0) 0x81.toByte() else 0 }
        command(0x81, 1, 0x35).copyInto(payload, 8)
        submit(once, 0x104ff)
        selector = 1
        payload = command(0x82, 1, 0)
        submit(once, 0x30401)
        repeat(8) { word -> assertEquals(if (word == 0) 0x350000L else 0L, read(once, word)) }
        val spentSnap = once.saveSnapshot()
        assertFails { bus(true, ProgrammedImageReply.REPEATABLE_PROFILE).restoreSnapshot(spentSnap) }
        var active = bus(true, ProgrammedImageReply.REPEATABLE_PROFILE).apply { restoreSnapshot(spentSnap, true) }
        // Subsequent non-0x0001 bulk write preserves learned byte and allows a second 82 00 01 readback
        payload = command(0x81, 0x46, 0x07) + command(0x81, 0x47, 0x00)
        submit(active, 0x10402)
        payload = command(0x82, 1, 0)
        submit(active, 0x30401)
        active = bus(true, ProgrammedImageReply.REPEATABLE_PROFILE).apply { restoreSnapshot(active.saveSnapshot()) }
        repeat(8) { word -> assertEquals(if (word == 0) 0x350000L else 0L, read(active, word)) }
        // Third cycle updates learned byte to 0x7a and survives round-trip snapshot
        payload = command(0x81, 1, 0x7a)
        submit(active, 0x10401)
        payload = command(0x82, 1, 0)
        submit(active, 0x30401)
        repeat(4) { word -> assertEquals(if (word == 0) 0x7a0000L else 0L, read(active, word)) }
        active = bus(true, ProgrammedImageReply.REPEATABLE_PROFILE).apply { restoreSnapshot(active.saveSnapshot()) }
        for (word in 4..7) assertEquals(0L, read(active, word))
        assertFails { read(active, 0) }
        // Downgrade to once profile is refused even with promotion
        assertFails { bus(true, ProgrammedImageReply.PROFILE).restoreSnapshot(active.saveSnapshot(), true) }
    }
}

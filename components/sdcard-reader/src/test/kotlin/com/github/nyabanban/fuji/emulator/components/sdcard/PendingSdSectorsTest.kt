package com.github.nyabanban.fuji.emulator.components.sdcard

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PendingSdSectorsTest {
    @Test
    fun failedEvictionPreservesInputAndDirtyBytesUntilRetry(@TempDir temp: Path) {
        val scratch = temp.resolve("spool")
        val a = ByteArray(512) { (it * 11).toByte() }
        val b = ByteArray(512) { (it / 7).toByte() }
        PendingSdSectors(scratch, cachePages = 1).use { pending ->
            pending.write(7, 1, ByteBuffer.wrap(a))
            val next = ByteBuffer.wrap(b)
            assertFailsWith<IOException> { pending.write(129, 1, next) }
            assertEquals(0, next.position())
            assertEquals(1, pending.size)
            assertContentEquals(a, pending[7])

            Files.createDirectory(scratch)
            pending.write(129, 1, next)
            pending.write(256, 1, ByteBuffer.wrap(a))
            assertContentEquals(b, pending[129])
            pending.retain(PendingSdSectors.Selection().apply { add(7, 8); add(256, 257) })
            assertNull(pending[129])
            assertEquals(256L, pending.ceilingKey(8))
            pending.write(515, 1, ByteBuffer.wrap(b))
            assertContentEquals(a, pending[7])
            assertContentEquals(b, pending[515])
            assertNull(pending[514])
            assertEquals(3, pending.size)
        }
        assertEquals(0L, Files.list(scratch).use { it.count() })
    }
}

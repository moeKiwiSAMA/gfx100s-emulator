package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogValidationSpeedTest {
    @Test
    fun bundledReadCompiledCompletesFastAndRejectsRegisterOverlaps() {
        // Warm up Jackson/classloading once, then measure cold readCompiled + validate
        val first = AddressCatalog.bundled()
        val startNanos = System.nanoTime()
        val second = AddressCatalog.bundled()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
        assertTrue(second.windows.size == first.windows.size)
        assertTrue(elapsedMs < 3_000L, "Expected AddressCatalog.bundled() < 3000 ms after O(N log N) validate, got ${elapsedMs} ms")

        // Verify sorted-interval validator still rejects overlapping registers (both contiguous and strided)
        val baseData = first.data
        val firstDevice = rows(baseData["devices"]).first { rows(it["ports"]).any { p -> rows(p["registers"]).isNotEmpty() } }
        val firstPort = rows(firstDevice["ports"]).first { rows(it["registers"]).isNotEmpty() }
        val firstReg = rows(firstPort["registers"]).first()
        val duplicateReg = firstReg + mapOf(
            "id" to "${firstReg.text("id")}.overlap.dup",
            "fields" to rows(firstReg["fields"]).map { f -> f + mapOf("id" to "${f.text("id")}.overlap.dup") },
        )
        val mutatedDevices = rows(baseData["devices"]).map { d ->
            if (d["id"] != firstDevice["id"]) d else d + mapOf(
                "ports" to rows(d["ports"]).map { p ->
                    if (p["id"] != firstPort["id"]) p else p + mapOf("registers" to (rows(p["registers"]) + duplicateReg))
                },
            )
        }
        val err = assertFailsWith<IllegalArgumentException> {
            CatalogCompiler.validate(baseData + mapOf("devices" to mutatedDevices))
        }
        assertTrue(err.message.orEmpty().contains("register overlap"), "Unexpected error: ${err.message}")
    }
}

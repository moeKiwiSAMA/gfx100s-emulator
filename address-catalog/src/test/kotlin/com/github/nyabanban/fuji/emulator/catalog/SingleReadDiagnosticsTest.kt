package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import kotlin.test.*

class SingleReadDiagnosticsTest {
    private val declaration = mapOf<String, Any?>(
        "id" to "test-single-read", "parentProfile" to "test-retained", "inventoryId" to "test-inventory",
        "routeBase" to 0x1000L, "routeEndExclusive" to 0x1004L, "width" to 4, "strategy" to "scripted-read",
        "samples" to listOf(0), "exhaustion" to "refuse", "addressKind" to "observed-native-callback-address",
        "confidence" to "HYPOTHESIS", "physicalAddress" to null, "reset" to null, "function" to null,
        "dataEffect" to null, "automaticEvents" to false, "evidence" to listOf("TEST finite layout"))
    private fun layout(row: Map<String, Any?> = declaration) = mapOf<String, Any?>(
        "singleReadDiagnosticProfiles" to listOf(row),
        "diagnosticProfiles" to listOf(mapOf("id" to "test-retained", "routeBase" to 0x2000L, "routeEndExclusive" to 0x2004L)),
        "inventory" to listOf(mapOf("id" to "test-inventory")),
        "windows" to listOf(mapOf("base" to 0x3000L, "size" to 16)),
        "candidates" to listOf(mapOf("base" to 0x4000L, "endExclusive" to 0x4010L)),
        "memoryProfiles" to mapOf("TEST" to listOf(mapOf("base" to 0x5000L, "endExclusive" to 0x5010L))))
    private fun validate(data: Map<String, Any?>) = validateSingleReadDiagnostics(data) { require(it["id"] == "test-single-read") }
    private fun refuses(change: Map<String, Any?>, message: String) {
        val error = assertFailsWith<IllegalArgumentException> { validate(layout(declaration + change)) }
        assertEquals(message, error.message)
    }
    @Test fun finiteDeclarationRejectsWrongLawAndUnknownStatePromotionAtIntendedGate() {
        validate(layout())
        for (samples in listOf(emptyList<Any>(), listOf(null), listOf(1), listOf(-1), listOf(0x100000000L), listOf(0, 0), listOf("0"), listOf(0.0))) {
            refuses(mapOf("samples" to samples), "Single-read requires exactly one explicit zero then refusal")
        }
        for (change in listOf(mapOf("strategy" to "fixed-input"), mapOf("exhaustion" to "repeat-last"))) {
            refuses(change, "Single-read requires exactly one explicit zero then refusal")
        }
        for (key in listOf("physicalAddress", "reset", "function", "dataEffect")) {
            refuses(mapOf(key to 0), "Single-read cannot assert physical, reset, function or completion state")
        }
        refuses(mapOf("automaticEvents" to true), "Single-read cannot assert physical, reset, function or completion state")
        refuses(mapOf("irq" to false), "Single-read diagnostic declaration keys differ")
        for (parent in listOf("missing", "test-single-read", "gfx100s-sd-finite-v1")) {
            refuses(mapOf("parentProfile" to parent), "Single-read parent must name one retained diagnostic")
        }
        refuses(mapOf("inventoryId" to "missing"), "Single-read inventory reference missing")
    }
    @Test fun exactWordCannotOverlapRetainedKnownCandidateOrAnyRamProfile() {
        for (base in listOf(0x2000L, 0x3000L, 0x4000L, 0x5000L)) {
            refuses(mapOf("routeBase" to base, "routeEndExclusive" to base + 4), "Single-read overlaps an existing route or RAM")
        }
        for (change in listOf(mapOf("width" to 1), mapOf("routeEndExclusive" to 0x1008L),
            mapOf("routeBase" to 0x1001L, "routeEndExclusive" to 0x1005L))) {
            refuses(change, "Single-read requires one aligned four-byte word")
        }
        val nine=(1..9).map { declaration+("id" to "independent-$it") };val seen=mutableListOf<String>()
        validateSingleReadDiagnostics(layout()+("singleReadDiagnosticProfiles" to nine)) { seen+=it.getValue("id") as String }
        assertEquals(nine.map {it["id"]},seen)
        val failure = assertFailsWith<IllegalArgumentException> {
            val ten=(1..10).map { declaration+("id" to "independent-$it") }
            validateSingleReadDiagnostics(layout() + ("singleReadDiagnosticProfiles" to ten)) {}
        }
        assertEquals("At most nine independently selected single-read diagnostics are supported", failure.message)
    }
}

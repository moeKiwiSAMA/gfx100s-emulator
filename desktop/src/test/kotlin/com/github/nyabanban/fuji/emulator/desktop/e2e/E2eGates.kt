package com.github.nyabanban.fuji.emulator.desktop.e2e

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.fail
import java.awt.GraphicsEnvironment

/**
 * Central opaque-box gating helper for the 4-tier E2E test suite.
 *
 * System properties (forwarded by `desktop/build.gradle.kts`):
 * - `fuji.e2e.strict` (default `false`): when `true`, any unready milestone gate, missing
 *   external asset, or headless display failure becomes an explicit test failure (`fail(...)`).
 * - `fuji.e2e.strict.milestones` (optional CSV e.g. `M1,M2`): enforces strict failure only
 *   for the listed milestone IDs.
 * - `fuji.e2e.requireDisplay` (default = `fuji.e2e.strict`): controls graphical display gates.
 * - `fuji.e2e.requireAssets` (default = `fuji.e2e.strict`): controls external asset gates.
 */
object E2eGates {
    fun isStrictAll(): Boolean =
        System.getProperty("fuji.e2e.strict")?.trim()?.toBooleanStrictOrNull() ?: false

    fun strictMilestones(): Set<String> =
        System.getProperty("fuji.e2e.strict.milestones")
            .orEmpty()
            .split(',')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun requireDisplay(): Boolean =
        System.getProperty("fuji.e2e.requireDisplay")?.trim()?.toBooleanStrictOrNull() ?: isStrictAll()

    fun requireAssetsFlag(): Boolean =
        System.getProperty("fuji.e2e.requireAssets")?.trim()?.toBooleanStrictOrNull() ?: isStrictAll()

    fun assumeOrFailFeature(milestone: String, feature: String, ready: Boolean, detail: String) {
        if (ready) return
        val normalized = milestone.trim().uppercase()
        if (isStrictAll() || normalized in strictMilestones()) {
            fail("[$normalized/$feature] Strict E2E gate failed: $detail")
        } else {
            Assumptions.assumeTrue(false, "[$normalized/$feature] Gated on unmerged milestone: $detail")
        }
    }

    fun requireAssets(assetName: String, available: Boolean, detail: String) {
        if (available) return
        if (requireAssetsFlag()) {
            fail("[ASSETS/$assetName] Strict asset gate failed: $detail")
        } else {
            Assumptions.assumeTrue(false, "[ASSETS/$assetName] Missing external asset: $detail")
        }
    }

    fun requireGraphicalDisplay(feature: String) {
        val headless = GraphicsEnvironment.isHeadless()
        if (!headless) return
        if (requireDisplay()) {
            fail("[DISPLAY/$feature] Strict display gate failed: GraphicsEnvironment is headless")
        } else {
            Assumptions.assumeTrue(false, "[DISPLAY/$feature] Headless environment; graphical display required")
        }
    }
}

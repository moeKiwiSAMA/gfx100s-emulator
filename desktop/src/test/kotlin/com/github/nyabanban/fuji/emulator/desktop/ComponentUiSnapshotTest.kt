package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import com.github.nyabanban.fuji.emulator.runtime.OwnerLoop
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ComponentUiSnapshotTest {
    @Test
    fun uiSnapshotDirectSkipsHeavySocStatesAndTracksConfigRevision(@TempDir tempDir: Path) {
        val projectRoot = Path.of(System.getProperty("componentProjectRoot"))
        val stagedDir = projectRoot.resolve("build/emulator/compoments")
        val workDir = tempDir.resolve("compoments")
        Files.createDirectories(workDir)
        val selectedJars = listOf(
            "battery-0.3.1.jar",
            "buttons-0.3.1.jar",
            "lens-0.3.1.jar",
            "mount-0.3.1.jar",
            "soc-gic-0.3.1.jar",
            "soc-timer-0.3.1.jar",
        )
        for (jar in selectedJars) {
            Files.copy(stagedDir.resolve(jar), workDir.resolve(jar))
        }

        OwnerLoop().use { owner ->
            val host = ComponentHost(owner, workDir)
            try {
                assertEquals(0L, host.configRevision)
                owner.submit { host.load() }.join()
                val loadedRevision = host.configRevision
                assertEquals(1L, loadedRevision)

                val lightStateIds = setOf("buttons", "mount", "battery")
                @Suppress("UNCHECKED_CAST")
                val lightSnap = owner.submit {
                    host.uiSnapshotDirect(stateComponentIds = lightStateIds, includeAllConfigs = false)
                }.join()
                assertEquals(loadedRevision, lightSnap["configRevision"])
                @Suppress("UNCHECKED_CAST")
                val lightComponents = lightSnap["components"] as List<Map<String, Any?>>
                assertEquals(lightStateIds, lightComponents.map { it["id"] as String }.toSet())
                assertTrue(lightComponents.all { it["state"] is Map<*, *> && it["config"] is Map<*, *> })

                @Suppress("UNCHECKED_CAST")
                val configSnap = owner.submit {
                    host.uiSnapshotDirect(stateComponentIds = lightStateIds, includeAllConfigs = true)
                }.join()
                @Suppress("UNCHECKED_CAST")
                val configComponents = configSnap["components"] as List<Map<String, Any?>>
                assertEquals(selectedJars.size, configComponents.size)
                val byId = configComponents.associateBy { it["id"] as String }
                // Excluded SoC components expose config without invoking state()
                assertFalse("state" in byId.getValue("soc-gic"))
                assertFalse("state" in byId.getValue("soc-timer"))
                assertTrue(byId.getValue("soc-gic")["config"] is Map<*, *>)
                assertTrue(byId.getValue("buttons")["state"] is Map<*, *>)

                // Patching a live setting increments configRevision cleanly
                val buttonsRev = owner.submit {
                    host.components().single { it.descriptor.id == "buttons" }.revision
                }.join()
                host.patch("buttons", buttonsRev, mapOf("menu" to true)).join()
                assertEquals(loadedRevision + 1L, host.configRevision)

                // Full snapshot() contract remains completely intact
                val fullSnap = host.snapshot().join()
                assertEquals(setOf("tick", "components"), fullSnap.keys)
                @Suppress("UNCHECKED_CAST")
                val fullComponents = fullSnap["components"] as List<Map<String, Any?>>
                assertEquals(selectedJars.size, fullComponents.size)
                assertTrue(fullComponents.all { "state" in it && "settings" in it && "jarSha256" in it })
            } finally {
                owner.submit { host.close() }.join()
            }
        }
    }
}

package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Files
import java.nio.file.Path

/** Compare one passive addition with a Git baseline without executing a guest. */
object PassiveInventoryQualification {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "usage: <repository> <baseline commit> <added inventory ID>" }
        val root = Path.of(args[0]).toRealPath()
        val baseline = args[1]
        require(baseline.matches(Regex("[0-9a-f]{40}")))
        val process = ProcessBuilder("git", "show", "$baseline:catalog/compiled/catalog.json")
            .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val bytes = process.inputStream.use { it.readAllBytes() }
        check(process.waitFor() == 0)
        val old = CatalogIO.readCompiled(bytes)
        val current = CatalogIO.readCompiled(Files.readAllBytes(root.resolve("catalog/compiled/catalog.json")))
        fun same(a: Any?, b: Any?) = CatalogIO.canonical(a).contentEquals(CatalogIO.canonical(b))
        check(same(CatalogCompiler.compile(root).data, current.data))
        val before = rows(old.data["inventory"])
        val after = rows(current.data["inventory"])
        val id = args[2]
        check(before.none { it["id"] == id })
        val added = after.single { it["id"] == id }
        check(same(current.data + ("inventory" to after.filterNot { it["id"] == id }), old.data)) {
            "Removing only the new inventory entry must restore the complete baseline payload"
        }
        check(added["runtimeActivation"] == false && added["cpuRoutable"] == false)
        check(added["binding"] == mapOf("active" to false, "componentId" to null, "portId" to null))
        check(current.componentIds == old.componentIds)
        current.componentIds.forEach { check(same(current.componentDescriptor(it), old.componentDescriptor(it))) }
        // Key by stable ID to report added leaves instead of dumping the whole unequal-sized list.
        val changes = ConfigRecordPagesCatalogQualification.leafDifferences(
            before.associateBy { it.text("id") }, after.associateBy { it.text("id") }, "/inventory")
        val address = valueLong(obj(added["softwareEffectiveAddress"])["address"])
        var queries = 0
        for (profile in current.memoryProfiles.keys) for (mode in listOf("strict", "discovery")) {
            for (direction in listOf("read", "write", "execute")) {
                val previous = old.query("cpu-physical", address, 4, direction, profile, mode).toMap()
                val now = current.query("cpu-physical", address, 4, direction, profile, mode).toMap()
                check(now["classification"] == "UNASSIGNED" && now["permitted"] == false)
                check(same(now + ("artifactSha256" to old.artifactSha256), previous))
                queries++
            }
        }
        println(CatalogIO.json.writeValueAsString(mapOf(
            "baselineCommit" to baseline, "onlyAddedInventoryId" to id,
            "removalRestoresCompleteBaseline" to true, "completeLeafDifferences" to changes,
            "unchangedComponentIds" to current.componentIds, "unchangedDeniedPhysicalQueries" to queries,
            "queryAddress" to address, "guestExecuted" to false)))
    }
}

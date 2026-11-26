package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class AddressCatalogAdditionalMemoryTest {
    private val catalog=CatalogCompiler.compile(Path.of(System.getProperty("catalogRoot")))
    private val profile="flashload-request-buffer-experimental-v24"
    private val start=0x03bf3000L
    private val end=0x0466c000L
    private fun region(base:Long=start,endExclusive:Long=end)=mapOf<String,Any?>(
        "id" to "selected-data-input","base" to base,"endExclusive" to endExclusive,
        "size" to endExclusive-base,"permissions" to 3)

    @Test fun elfloaderExecProfileRetainsCompleteV24AndOnlyChangesV25OutputPermissions() {
        val baseline=catalog.memoryProfiles.getValue(profile)
        val previous=catalog.memoryProfiles.getValue("method4-runtime-output-experimental-v25")
        val executable=catalog.memoryProfiles.getValue("method4-elfloader-exec-experimental-v26")
        val outputId="method4-runtime-output"
        val previousOutput=previous.single { it["id"]==outputId }
        val executableOutput=executable.single { it["id"]==outputId }
        assertEquals(25,baseline.size)
        assertEquals(26,previous.size)
        assertEquals(26,executable.size)
        assertEquals(baseline,previous.filterNot { it["id"]==outputId })
        assertEquals(baseline,executable.filterNot { it["id"]==outputId })
        assertEquals(previous.map { if(it["id"]==outputId)it+("permissions" to executableOutput["permissions"])else it },executable)
        for(output in listOf(previousOutput,executableOutput)) {
            assertEquals(start,output.long("base"))
            assertEquals(end,output.long("endExclusive"))
            assertEquals(0x00a79000L,output.long("size"))
            assertEquals("HYPOTHESIS",obj(output["provenance"])["permissions"])
        }
        assertEquals(3L,previousOutput.long("permissions"))
        assertEquals(7L,executableOutput.long("permissions"))
    }

    @Test fun elfloaderExecSelectionExposesOnlyTheDeclaredPhysicalRangeAndPermissions() {
        val executableProfile="method4-elfloader-exec-experimental-v26"
        val previousProfile="method4-runtime-output-experimental-v25"
        val selected=listOf(catalog.memoryProfiles.getValue(executableProfile).single { it["id"]=="method4-runtime-output" })
        val originalMemory=catalog.memoryProfiles.getValue(profile)
        catalog.verifyAdditionalMemory(selected)
        for(address in listOf(start,end-4))for(direction in listOf("read","write","execute")) {
            val declared=catalog.query("cpu-physical",address,4,direction,executableProfile)
            val runtime=catalog.query("cpu-physical",address,4,direction,profile,runtimeRAM=selected)
            assertEquals("RAM",declared.classification)
            assertTrue(declared.permitted)
            assertEquals("RAM",runtime.classification)
            assertTrue(runtime.permitted)
            assertEquals("HYPOTHESIS",runtime.details["evidenceClass"])
            assertEquals(false,runtime.details["staticDictionaryDeclaration"])
            assertEquals(direction!="execute",catalog.query("cpu-physical",address,4,direction,previousProfile).permitted)
        }
        for(address in listOf(start-2,end-2)) {
            assertEquals("CROSS_BOUNDARY",catalog.query("cpu-physical",address,4,"execute",executableProfile).classification)
            assertEquals("CROSS_BOUNDARY",catalog.query("cpu-physical",address,4,"execute",profile,runtimeRAM=selected).classification)
        }
        assertEquals("UNASSIGNED",catalog.query("cpu-physical",end,4,"execute",executableProfile).classification)
        assertEquals("UNASSIGNED",catalog.query("cpu-physical",end,4,"execute",profile,runtimeRAM=selected).classification)
        val coverage=catalog.coverage(base=start,endExclusive=end+4096,memoryProfile=profile,runtimeRAM=selected)
        assertEquals(listOf("RAM","UNASSIGNED"),coverage.map { it["classification"] })
        assertEquals(listOf(start,end),coverage.map { it["base"] })
        assertEquals(selected.single(),coverage.first()["region"])
        assertEquals("UNKNOWN",catalog.query("guest-virtual",start,4,"execute",profile,runtimeRAM=selected).classification)
        assertEquals("UNASSIGNED",catalog.query("cpu-physical",start,4,"execute",profile).classification)
        assertSame(originalMemory,catalog.memoryProfiles.getValue(profile))
    }

    @Test fun runtimeQueryAndCoverageExposeFiniteBackingWithoutChangingStaticDictionary() {
        val selected=listOf(region())
        val before=catalog.query("cpu-physical",start,4,"read",profile)
        val originalMemory=catalog.memoryProfiles.getValue(profile)
        val originalIds=catalog.ids.toSet()
        catalog.verifyAdditionalMemory(selected)
        assertEquals("UNASSIGNED",before.classification)
        val read=catalog.query("cpu-physical",start,4,"read",profile,runtimeRAM=selected)
        assertEquals("RAM",read.classification)
        assertTrue(read.permitted)
        assertEquals("HYPOTHESIS",read.details["evidenceClass"])
        assertEquals(false,read.details["staticDictionaryDeclaration"])
        assertTrue(catalog.query("cpu-physical",end-4,4,"write",profile,runtimeRAM=selected).permitted)
        assertFalse(catalog.query("cpu-physical",start,4,"execute",profile,runtimeRAM=selected).permitted)
        assertEquals("CROSS_BOUNDARY",catalog.query("cpu-physical",start-2,4,"read",profile,runtimeRAM=selected).classification)
        assertEquals("CROSS_BOUNDARY",catalog.query("cpu-physical",end-2,4,"write",profile,runtimeRAM=selected).classification)
        val coverage=catalog.coverage(base=start,endExclusive=end+4096,memoryProfile=profile,runtimeRAM=selected)
        assertEquals(listOf("RAM","UNASSIGNED"),coverage.map { it["classification"] })
        assertEquals(listOf(start,end),coverage.map { it["base"] })
        assertEquals("HYPOTHESIS",coverage.first()["evidenceClass"])
        assertEquals(false,coverage.first()["staticDictionaryDeclaration"])
        assertEquals(before,catalog.query("cpu-physical",start,4,"read",profile))
        assertSame(originalMemory,catalog.memoryProfiles.getValue(profile))
        assertEquals(originalIds,catalog.ids)
        assertNull(catalog.byId("selected-data-input"))
        assertEquals("UNKNOWN",catalog.query("guest-virtual",start,4,"read",profile,runtimeRAM=selected).classification)
    }

    @Test fun conflictsIncludeInactiveAlternativesCandidatesDiagnosticsRomAndFlash() {
        val windows=catalog.allWindows.filter { it.space=="cpu-physical" }.map { it.base to it.endExclusive }
        val candidates=catalog.candidates.map { it.base to it.endExclusive }
        val diagnostics=catalog.diagnosticProfiles.map { it.base to it.endExclusive }+
            catalog.singleReadDiagnosticProfiles.map { it.base to it.endExclusive }
        for((base,endExclusive) in windows+candidates+diagnostics+listOf(0xfff00000L to 0xfff08000L,0xf0000000L to 0xf8000000L)) {
            val failure=assertFailsWith<IllegalArgumentException> { catalog.verifyAdditionalMemory(listOf(region(),region(base,endExclusive))) }
            assertTrue(failure.message.orEmpty().startsWith("Additional RAM overlaps catalog region:"),failure.message)
        }
        catalog.verifyAdditionalMemory(listOf(region()))
    }

    @Test fun knownMmioAndFlashRulesTakePriorityAndStrictOrTestViewsCannotUseOverlay() {
        for((address,direction) in listOf(0xfffc6244L to "write",0xf0000000L to "write",0xfff41020L to "read")) {
            val original=catalog.query("cpu-physical",address,4,direction,profile)
            val overlap=listOf(region(address and -4096L,(address and -4096L)+4096))
            assertEquals(original,catalog.query("cpu-physical",address,4,direction,profile,runtimeRAM=overlap))
            assertFalse(original.permitted)
        }
        assertFailsWith<IllegalArgumentException> {
            catalog.query("cpu-physical",start,4,"read",profile,mode="strict",runtimeRAM=listOf(region()))
        }
        assertFailsWith<IllegalArgumentException> {
            catalog.coverage(base=start,endExclusive=end,memoryProfile=profile,mode="strict",runtimeRAM=listOf(region()))
        }
        val testView=CatalogAddressView(emptyList(),catalog.memoryProfiles.getValue(profile),"TEST","test","0".repeat(64))
        assertFailsWith<IllegalArgumentException> {
            catalog.query("cpu-physical",start,4,"read",profile,view=testView,runtimeRAM=listOf(region()))
        }
    }

    @Test fun originalJvmQueryAndCoverageSignaturesRemainAvailable() {
        val query=AddressCatalog::class.java.getMethod("query",String::class.java,Long::class.javaPrimitiveType,Int::class.javaPrimitiveType,
            String::class.java,String::class.java,String::class.java,CatalogAddressView::class.java,Map::class.java)
        val result=query.invoke(catalog,"cpu-physical",start,4,"read",profile,"discovery",null,emptyMap<String,String>()) as CatalogQuery
        assertEquals("UNASSIGNED",result.classification)
        val coverage=AddressCatalog::class.java.getMethod("coverage",String::class.java,Long::class.javaPrimitiveType,Long::class.javaPrimitiveType,
            String::class.java,String::class.java,CatalogAddressView::class.java,Map::class.java)
        val rows=coverage.invoke(catalog,"cpu-physical",start,end,profile,"discovery",null,emptyMap<String,String>()) as List<*>
        assertEquals("UNASSIGNED",(rows.single() as Map<*,*>)["classification"])
    }
}

package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class FirmwareDeclarationsTest {
    private fun catalog()=CatalogCompiler.compile(Path.of(System.getProperty("catalogRoot")))
    private fun metadata(c:AddressCatalog):Map<String,Any?> = mapOf("schema" to 1,"id" to "synthetic-memory","version" to "TEST","size" to 0x20284,"sha256" to "a".repeat(64),"entry" to 0,"cpsr" to 0xd3,"loaderMemoryBytes" to 0x20000,"bootMode" to "public-synthetic-guest","sections" to listOf(mapOf("name" to "boot","address" to 0,"size" to 16,"offset" to 0x20274,"encoding" to "raw","sha256" to "b".repeat(64))),"devices" to listOf(mapOf("id" to "soc-spi","port" to "soc-spi.spi1","address" to 0xfff32000L,"size" to 0x28)),"evidence" to listOf("TEST"))
    @Test fun publicTestUsesActualFiniteLayoutWithoutCaseRegistration() {
        val c=catalog();val raw=metadata(c);val fixture=c.validatePublicTest(raw,"strict")
        val view=fixture.view("loader-low128-experimental-v1")
        assertEquals("REGISTER",c.query("cpu-physical",0xfff32000,4,"read",mode="strict",view=view).classification)
        assertEquals("UNASSIGNED",c.query("cpu-physical",0xfffc41e0,4,"read",mode="strict",view=view).classification)
        for(space in listOf("flash-logical","dat-file","flash-aperture","guest-virtual","ram-backing"))assertEquals("UNKNOWN",c.query(space,0,4,mode="strict",view=view).classification)
        assertFails{c.validatePublicTest(raw,"discovery")}
        assertNotNull(c.validatePublicTest(raw+mapOf("id" to "new-public-case"),"strict"))
        for((key,value) in listOf("version" to "2.13","bootMode" to "ram-loader-experiment","entry" to 0x100000000L))assertFails{c.validatePublicTest(raw+(key to value),"strict")}
        val relocatedPort=rows(raw["devices"]).single()+mapOf("address" to 0xfff34000L)
        assertNotNull(c.validatePublicTest(raw+mapOf("devices" to listOf(relocatedPort)),"strict"))
        val wrongPort=relocatedPort+mapOf("size" to 4096)
        assertFails{c.validatePublicTest(raw+mapOf("devices" to listOf(wrongPort)),"strict")}
    }
    @Test fun fixtureRamRejectsOverflowOverlapAlignmentPermissionsAndOversize() {
        val c=catalog();val raw=metadata(c)
        val low=mapOf("id" to "ram","address" to 0,"size" to 0x300000,"permissions" to 1)
        fun withRegions(regions:List<Map<String,Any>>)=raw+mapOf("memoryProfiles" to mapOf("test-profile" to regions))
        val readonly=mapOf("id" to "readonly","address" to 0x500000,"size" to 4096,"permissions" to 1)
        val valid=c.validatePublicTest(withRegions(listOf(low,readonly)),"strict")
        assertFalse(c.query("cpu-physical",0,4,"execute",mode="strict",view=valid.view("test-profile")).permitted)
        assertFalse(c.query("cpu-physical",0x500000,4,"write",mode="strict",view=valid.view("test-profile")).permitted)
        for(bad in listOf(low+mapOf("size" to (64*1024*1024+4096)),low+mapOf("address" to 1),low+mapOf("address" to 0xfffff000L,"size" to 8192),low+mapOf("permissions" to 8)))assertFails{c.validatePublicTest(withRegions(listOf(bad)),"strict")}
        assertFails{c.validatePublicTest(withRegions(listOf(low,readonly+mapOf("address" to 0x1000))),"strict")}
        assertFails{c.validatePublicTest(withRegions(List(33){low+mapOf("id" to "ram"+it)}),"strict")}
    }
    @Test fun onlySelectedProfileMustContainEntireBootSection() {
        val c=catalog();val raw=metadata(c)
        val boot=rows(raw["sections"]).single()+mapOf("size" to 0x300000)
        val profiles=mapOf("small" to listOf(mapOf("id" to "loader","address" to 0,"size" to 0x20000,"permissions" to 7)),"large" to listOf(mapOf("id" to "loader","address" to 0,"size" to 0x400000,"permissions" to 7)))
        val fixture=c.validatePublicTest(raw+mapOf("size" to (0x20274+0x300000),"sections" to listOf(boot),"memoryProfiles" to profiles),"strict")
        assertNotNull(fixture.view("large"));assertFails{fixture.view("small")};assertFails{fixture.view("absent")}
    }
    @Test fun officialIdentityDoesNotAuthorizeChangedSectionsInactivePortsOrRam() {
        val c=catalog();val raw=c.legacyFirmwareMetadata();c.verifyStockDeclaration(raw)
        c.verifyStockDeclaration(raw+mapOf("devices" to rows(raw["devices"]).take(1)))
        val sections=rows(raw["sections"]).toMutableList();sections[0]=sections[0]+mapOf("offset" to 0)
        assertFails{c.verifyStockDeclaration(raw+mapOf("sections" to sections))}
        val windows=rows(raw["devices"]).toMutableList();windows[windows.lastIndex]=windows.last()+mapOf("address" to 0x10000)
        assertFails{c.verifyStockDeclaration(raw+mapOf("devices" to windows))}
        val regions=c.memoryProfiles.getValue(c.defaultMemoryProfile);c.verifyStockMemory(c.defaultMemoryProfile,regions)
        assertFails{c.verifyStockMemory(c.defaultMemoryProfile,regions.mapIndexed{i,r->if(i==0)r+mapOf("permissions" to 1)else r})}
    }
    @Test fun bootRomExperimentValidatesStaticProfileWithoutAlteringStockCatalog() {
        val c=catalog()
        val raw=CatalogIO.readYaml(Path.of(System.getProperty("catalogRoot")).resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml"))
        val layout=c.validateBootRomExperiment(raw)
        val expectedRegions=layout.memoryProfiles.getValue("boot-rom-cold-start-experimental-v1")
        layout.verifyMemory("boot-rom-cold-start-experimental-v1",expectedRegions)
        assertFails{layout.verifyMemory("boot-rom-cold-start-experimental-v1",expectedRegions.drop(1))}
        assertFails{c.validateBootRomExperiment(raw+("bootMode" to "ram-loader-experiment"))}
        assertFails{c.validateBootRomExperiment(raw+("memoryProfiles" to mapOf("boot-rom-cold-start-experimental-v1" to expectedRegions.filterNot{it["id"]=="boot-rom"})))}
        val view=layout.view("boot-rom-cold-start-experimental-v1")
        assertEquals("RAM",c.query("cpu-physical",0xfff00000L,4,"execute",mode="strict",view=view).classification)
        assertTrue(c.query("cpu-physical",0xfff00000L,4,"execute",mode="strict",view=view).permitted)
        assertFalse(c.query("cpu-physical",0xfff00000L,4,"write",mode="strict",view=view).permitted)
        assertEquals("REGISTER",c.query("cpu-physical",0xfff4a000L,4,"read",mode="strict",view=view).classification)
    }
    @Test fun exportFirmwareMetadataBootRomFlagEmitsValidatedFull26DeviceBootRomLayout() {
        val c=catalog()
        val compiled=Path.of(System.getProperty("catalogRoot")).resolve("catalog/compiled/catalog.json")
        val tmp=java.nio.file.Files.createTempDirectory("bootrom-export-")
        try {
            val out=tmp.resolve("gfx100s-213-bootrom-release.yaml")
            main(arrayOf(
                "--export-firmware-metadata",
                compiled.toString(),
                out.toString(),
                "--boot-rom",
                "soc-system-control=retained-banks-usb-dwc3-experimental-v2",
            ))
            val exported=CatalogIO.readYaml(out)
            assertEquals("boot-rom-experiment",exported["bootMode"])
            assertEquals("0xfff00000",exported["entry"])
            val layout=c.validateBootRomExperiment(exported)
            assertTrue("boot-rom-cold-start-experimental-v1" in layout.memoryProfiles)
            assertTrue("boot-rom-cold-start-segments-experimental-v2" in layout.memoryProfiles)
            assertTrue("boot-rom-full-boot-v1" in layout.memoryProfiles)
            val fullRegions=layout.memoryProfiles.getValue("boot-rom-full-boot-v1")
            assertEquals(28,fullRegions.size)
            assertTrue(fullRegions.any { it["id"]=="boot-rom" })
            assertTrue(fullRegions.any { it["id"]=="flashload-request-buffer" })
            layout.verifyMemory("boot-rom-full-boot-v1",fullRegions)
            val view=layout.view("boot-rom-full-boot-v1")
            assertEquals("RAM",c.query("cpu-physical",0xfff00000L,4,"execute",mode="strict",view=view).classification)
            assertEquals("REGISTER",c.query("cpu-physical",0xfff4a000L,4,"read",mode="strict",view=view).classification)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}

package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class LogicalCatalogTest {
    private val root=Path.of(System.getProperty("catalogRoot"))
    private val v1="gfx100s-rtc-functional-experimental-v1"
    private val v2="gfx100s-rtc-scripted-stage-read-experimental-v2"
    private fun catalog()=CatalogCompiler.compile(root)
    @Suppress("UNCHECKED_CAST") private fun mutable(c:AddressCatalog):MutableMap<String,Any?> = CatalogIO.json.readValue(CatalogIO.canonical(c.data),MutableMap::class.java) as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun logical(data:Map<String,Any?>):MutableMap<String,Any?> = rows(rows(data["devices"]).single {it["componentId"]=="rtc"}["logicalSpaces"]).single() as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun first(data:Map<String,Any?>)=rows(logical(data)["registers"]).first() as MutableMap<String,Any?>
    private fun validBinding(c:AddressCatalog,profile:String=v1):LogicalPortBinding {
        val s=c.logicalSpaces.single()
        return LogicalPortBinding("rtc","rtc.registers",profile,s.registers.map {it.contract(profile)})
    }
    @Test fun protocolSelectorsHaveIndependentCoordinatesAndPreserveAllCpuDeclarations() {
        val c=catalog();val s=c.logicalSpaces.single()
        assertEquals("rtc-power-register",s.id);assertEquals(7,s.selectorBits);assertEquals(16,s.wordBits)
        assertEquals(listOf(29,30,35,36,39),s.registers.map {it.selector})
        assertEquals(212,c.windows.size);assertEquals(4,c.candidates.size)
        assertTrue(c.windows.none {it.componentId=="rtc"})
        val q=c.queryLogical(s.id,29,16,"write",mapOf("rtc" to v1))
        assertEquals("LOGICAL_REGISTER",q["classification"]);assertEquals(false,q["cpuRoutable"])
        assertTrue(q.containsKey("physicalAddress"));assertNull(q["physicalAddress"])
        assertEquals("PROTOCOL_SELECTOR",q["coordinateKind"]);assertEquals(true,q["permitted"])
        assertEquals("UNASSIGNED",c.query(s.id,29,2).classification)
        assertEquals("RAM",c.query("cpu-physical",29,2).classification);assertNull(c.query("cpu-physical",29,2).register)
        assertTrue(c.query("cpu-physical",0xfff33008L,4,"write").permitted)
        assertEquals("instance.gfx100s.rtc",s.deviceId)
    }
    @Test fun profilesSelectOnlyDeclaredDirectionsAndNeverGrantDisabledOrUnknownAccess() {
        val c=catalog()
        fun q(selector:Long,direction:String,profile:String?)=c.queryLogical("rtc-power-register",selector,16,direction,profile?.let {mapOf("rtc" to it)} ?: emptyMap())
        assertEquals(false,q(29,"read",v1)["permitted"]);assertEquals(true,q(29,"write",v1)["permitted"])
        assertEquals(true,q(29,"read",v2)["permitted"]);assertEquals("EXPERIMENTAL_INPUT",q(29,"read",v2)["accessStatus"])
        assertEquals("HYPOTHESIS",obj(obj(q(29,"read",v2)["effectiveAccessProvenance"])["readable"])["confidence"])
        assertEquals(false,q(35,"write",v2)["permitted"]);assertEquals(true,q(35,"read",v1)["permitted"])
        assertEquals(true,q(39,"read",v1)["permitted"]);assertEquals(true,q(39,"write",v1)["permitted"])
        for(profile in listOf(null,"disabled","unrecognized"))assertEquals(false,q(29,"read",profile)["permitted"])
    }
    @Test fun selectorWidthDirectionAndUnknownSpaceRefusalsAreExplicit() {
        val c=catalog();val profiles=mapOf("rtc" to v2)
        for(selector in listOf(-1L,128L,Long.MAX_VALUE))assertEquals(false,c.queryLogical("rtc-power-register",selector,16,"read",profiles)["permitted"])
        assertEquals("UNKNOWN",c.queryLogical("rtc-power-register",31,16,"read",profiles)["classification"])
        assertEquals("UNASSIGNED",c.queryLogical("cpu-physical",29,16,"read",profiles)["classification"])
        for(width in listOf(0,2,8,32))assertEquals(false,c.queryLogical("rtc-power-register",29,width,"read",profiles)["permitted"])
        for(direction in listOf("execute","READ",""))assertEquals(false,c.queryLogical("rtc-power-register",29,16,direction,profiles)["permitted"])
    }
    @Test fun stableRegisterFieldIdsAndPropertyConfidenceResolveThroughCompiledIndex() {
        val c=CatalogIO.readCompiled(CatalogIO.compiledBytes(catalog()));val profiles=mapOf("rtc" to v2)
        val r=assertNotNull(c.byId("reg.rtc.stage-low",profiles));val f=assertNotNull(c.byId("reg.rtc.stage-low.raw",profiles))
        assertEquals("rtc-power-register",r["logicalSpaceId"]);assertEquals("rtc.registers",r["portId"])
        assertEquals(true,r["effectiveReadable"]);assertEquals(29,r["selector"])
        assertEquals(true,f["effectiveWritable"]);assertEquals("reg.rtc.stage-low",f["registerId"])
        assertEquals("HYPOTHESIS",obj(c.property("reg.rtc.stage-low","effectiveReadable",profiles)["provenance"])["confidence"])
        assertEquals("UNKNOWN",c.property("reg.rtc.stage-low","initial.value",profiles)["status"])
        assertEquals("SOURCE",obj(c.property("reg.rtc.stage-low.raw","bitWidth",profiles)["provenance"])["confidence"])
        assertNotNull(c.byId("rtc-power-register"));assertNotNull(c.byId("logical.gfx100s.rtc-power-register"))
    }
    @Test fun queryAndContractDescriptionNeverMutateDictionaryOrUseDynamicReads() {
        val c=catalog();val before=CatalogIO.canonical(c.data)
        repeat(100){c.queryLogical("rtc-power-register",29,16,"read",mapOf("rtc" to v2));c.byId("reg.rtc.control.load-request",mapOf("rtc" to v1));c.componentDescriptor("rtc");c.verifyLogicalContracts(listOf(validBinding(c)))}
        assertContentEquals(before,CatalogIO.canonical(c.data))
        assertEquals(false,c.queryLogical("rtc-power-register",29,16,"read",mapOf("rtc" to v2))["dynamicReadPerformed"])
        @Suppress("UNCHECKED_CAST") val data=c.logicalSpaces.single().registers.first().data as MutableMap<String,Any?>
        assertFailsWith<UnsupportedOperationException>{data["selector"]=0}
    }
    @Test fun exactActualContractsRejectMissingExtraDuplicateWrongProfileAndAccess() {
        val c=catalog();val valid=validBinding(c);c.verifyLogicalContracts(listOf(valid));c.verifyLogicalContracts(listOf(validBinding(c,v2)))
        val first=valid.registers.first()
        val invalid=listOf(valid.copy(componentId="power-peer"),valid.copy(portId="other"),valid.copy(profile="disabled"),valid.copy(profile="unknown"),
            valid.copy(registers=valid.registers.dropLast(1)),valid.copy(registers=valid.registers+first),
            valid.copy(registers=listOf(first.copy(selector=31))+valid.registers.drop(1)),
            valid.copy(registers=listOf(first.copy(registerId="other"))+valid.registers.drop(1)),
            valid.copy(registers=listOf(first.copy(wordBits=8))+valid.registers.drop(1)),
            valid.copy(registers=listOf(first.copy(readable=true))+valid.registers.drop(1)))
        invalid.forEach {assertFails {c.verifyLogicalContracts(listOf(it))}}
        assertFailsWith<IllegalArgumentException>{c.verifyLogicalContracts(listOf(valid,valid))}
        c.verifyLogicalContracts(emptyList()) // Host separately requires enabled declared endpoints to be present.
    }
    @Test fun schemasRejectUnknownPhysicalCoordinatesNullFloatAndIncompleteProfileDeclarations() {
        val c=catalog();val deviceSchema=CatalogIO.readYaml(root.resolve("catalog/schema/device.schema.json"));val registerSchema=CatalogIO.readYaml(root.resolve("catalog/schema/logical-register.schema.json"))
        val source=CatalogIO.readYaml(root.resolve("catalog/devices/rtc.yaml"));CatalogSchema.validate(source,deviceSchema)
        val register=rows(rows(source["logicalSpaces"]).single()["registers"]).first();CatalogSchema.validate(register,registerSchema)
        for(change in listOf(mapOf("selector" to null),mapOf("selector" to 29.0),mapOf("selector" to "29"),mapOf("offset" to 29),mapOf("base" to 0xfff33008L)))assertFailsWith<IllegalArgumentException>{CatalogSchema.validate(register+change,registerSchema)}
        val changed=mutable(c);logical(changed)["base"]=0xfff33008L
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(changed)}
        val incomplete=mutable(c);first(incomplete)["accessByProfile"]=obj(first(incomplete)["accessByProfile"])-v2
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(incomplete)}
    }
    @Test fun semanticValidationRejectsSelectorDuplicatesMasksFieldsAndInstanceAliasing() {
        val c=catalog()
        val wrongSelector=mutable(c);first(wrongSelector)["selector"]=128;assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(wrongSelector)}
        val duplicate=mutable(c);@Suppress("UNCHECKED_CAST") val second=rows(logical(duplicate)["registers"])[1] as MutableMap<String,Any?>;second["selector"]=29;assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(duplicate)}
        val mask=mutable(c);first(mask)["masks"]=mapOf("writable" to 65535,"preserve" to 1,"unknown" to 0,"prohibited" to 0);assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(mask)}
        val fields=mutable(c);val field=rows(first(fields)["fields"]).single();first(fields)["fields"]=listOf(field,field+mapOf("id" to "duplicate.field"));assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(fields)}
        val alias=mutable(c);@Suppress("UNCHECKED_CAST") val instance=rows(alias["logicalSpaces"]).single() as MutableMap<String,Any?>;instance["portId"]="soc-spi.mmio";assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(alias)}
    }
    @Test fun oldCompiledArtifactsWithoutLogicalExtensionRemainReadableButExplicitNullRefuses() {
        val c=catalog();val old=mutable(c);old.remove("logicalSpaces")
        @Suppress("UNCHECKED_CAST") val rtc=rows(old["devices"]).single {it["componentId"]=="rtc"} as MutableMap<String,Any?>
        rtc.remove("logicalSpaces");rtc.remove("profiles");rtc["implementationStatus"]="HOST_CONTRACT_ONLY"
        CatalogCompiler.validate(old);val loaded=CatalogIO.readCompiled(CatalogIO.compiledBytes(AddressCatalog(old)))
        assertTrue(loaded.logicalSpaces.isEmpty());assertNull(loaded.byId("reg.rtc.stage-low"))
        assertTrue(loaded.query("cpu-physical",0xfff33008L,4,"write").permitted)
        val topNull=mutable(c);topNull["logicalSpaces"]=null;assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(topNull)}
        val deviceNull=mutable(c);@Suppress("UNCHECKED_CAST") val r=rows(deviceNull["devices"]).single {it["componentId"]=="rtc"} as MutableMap<String,Any?>;r["logicalSpaces"]=null
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(deviceNull)}
    }
    @Test fun historicalRtcMigrationPreservesOtherDescriptorsAndCurrentResearchAnnotationsPreserveContracts() {
        val c=catalog();val before="6a9dd296bd7a634a5dce3dd4d595d9d47e53c0bb";val after="c6788f3bd6f6afa1f963e0ec9899d45dd96a04d9"
        val history = HistoricalCatalogFixture(root)
        // Freeze both historical endpoints; later physical additions are not part of the RTC migration.
        val migrated=history.catalog(after)
        for(component in migrated.componentIds.filter {it!="rtc"}) {
            val path="catalog/compiled/components/"+component+".json"
            assertContentEquals(history.bytes(before,path),history.bytes(after,path),component)
        }
        val changed=mutable(c);first(changed)["name"]="new interpretation";first(changed)["unknowns"]=listOf("new question");first(changed)["provenance"]=emptyMap<String,Any?>()
        assertEquals(c.componentDescriptor("rtc"),AddressCatalog(changed).componentDescriptor("rtc"))
        assertEquals(1,(c.componentDescriptor("rtc")["logicalSpaces"] as List<*>).size)
    }
}

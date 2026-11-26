package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AddressCatalogTest {
    private val root=Path.of(System.getProperty("catalogRoot"))
    private fun catalog()=CatalogCompiler.compile(root)
    @Test fun allCurrentPortsAndMemoryProfilesMigrateWithoutChangingLegacyBehavior() {
        val c=catalog()
        val old=CatalogIO.readYaml(root.resolve("configurations/jvm/firmware/gfx100s-213.yaml"))
        assertEquals(CatalogIO.json.writeValueAsString(old),CatalogIO.json.writeValueAsString(c.legacyFirmwareMetadata()))
        // P951 (8f7c5c6bd) + M2/M3/M6.1/M6.2/M6.3 + F8/O4 + CONN-BT-WIFI + CHIP-DECOUPLE-I2C + ARCH-SHUTTER promoted components & optional windows.
        assertEquals(212,c.windows.size);assertTrue("optical-filter" in c.componentIds);assertEquals(49,c.componentIds.size)
        assertEquals(13,c.memoryProfiles.getValue(c.defaultMemoryProfile).size)
        c.verifyPorts(c.windows.map{PortBinding(it.componentId,it.portId,it.base,it.size)})
        val binding=PortBinding("soc-sd-host","soc-sd-host.word80-0",0xfffc6080,4)
        for((changed,expected) in listOf(
            binding.copy(componentId="soc-spi") to "field=componentId expected=soc-sd-host actual=soc-spi",
            binding.copy(base=0xfffc6084) to "field=address expected=0xfffc6080 actual=0xfffc6084",
            binding.copy(size=4096) to "field=size expected=0x4 actual=0x1000"
        )) {
            val error=assertFailsWith<IllegalArgumentException>{c.verifyPorts(listOf(changed))}
            assertEquals("dictionary/port binding differs: port=soc-sd-host.word80-0 "+expected,error.message)
        }
        assertFailsWith<IllegalArgumentException>{c.verifyPorts(List(2){PortBinding("soc-sd-host","soc-sd-host.word80-0",0xfffc6080,4)})}
    }
    @Test fun finiteDiscoveryKeepsKnownRulesAndCrossBoundaryRefusals() {
        val c=catalog()
        val vendor=c.query("cpu-physical",0xfffc41e0,4,"read")
        assertEquals("CANDIDATE_REGISTER",vendor.classification);assertTrue(vendor.permitted)
        assertEquals("retained-bytes",vendor.candidate?.policy);assertEquals(0,vendor.candidate?.initialFill)
        assertFalse(c.query("cpu-physical",0xfffc41e0,4,"read",mode="strict").permitted)
        assertEquals("UNASSIGNED",c.query("cpu-physical",0xfffc4200,4,"read").classification)
        assertEquals("CROSS_BOUNDARY",c.query("cpu-physical",0xfffc41de,4,"read").classification)
        assertEquals("CROSS_BOUNDARY",c.query("cpu-physical",0xfffc623c,4,"read").classification)
        assertFalse(c.query("cpu-physical",0xfffc623e,4,"write").permitted)
        assertFalse(c.query("cpu-physical",0xfffc6244,4,"write").permitted)
        assertFalse(c.query("cpu-physical",0xfffc6078,4,"write").permitted)
        assertTrue(c.query("cpu-physical",0xfffc6078,2,"write").permitted)
        assertFalse(c.query("cpu-physical",0xfffc6000,4,"execute").permitted)
        assertEquals(4,c.candidates.size)
        assertTrue(c.candidates.zipWithNext().all{!it.first.interval.overlaps(it.second.interval)})
        assertTrue(c.candidates.filter{it.id.contains("phy")}.all{rows(it.data["groups"]).single().long("base")==it.base+0x200})
    }
    @Test fun fullUnsignedAddressSpaceIsClassifiedWithFiniteIntervalsAndExplicitSpaces() {
        val c=catalog();val cover=c.coverage()
        assertEquals(0L,cover.first()["base"]);assertEquals(ARM32_LIMIT,cover.last()["endExclusive"])
        cover.zipWithNext().forEach{assertEquals(it.first["endExclusive"],it.second["base"])}
        assertTrue(cover.size<32768)
        assertEquals("UNASSIGNED",c.query("cpu-physical",0xffff_ffff,2).classification)
        assertEquals("UNASSIGNED",c.query("cpu-physical",-1,4).classification)
        assertEquals("UNKNOWN",c.query("guest-virtual",0xfffc41e0,4).classification)
        assertEquals("MAPPED",c.query("flash-aperture",0xf0000000,4).classification)
        assertEquals("UNASSIGNED",c.query("dat-file",50197040,1).classification)
        assertFalse(c.query("cpu-physical",0xf0000000,4,"write").permitted)
        assertEquals("KNOWN_DEVICE_UNKNOWN_REGISTER",c.query("cpu-physical",0xfff41020,4,"read").classification)
        assertFalse(c.query("cpu-physical",0x88000,4,"execute").permitted)
    }
    @Test fun idsAndPropertyProvenanceAreQueryableWithoutDynamicAccess() {
        val c=catalog();val query=c.query("cpu-physical",0xfffc6244,4,"read")
        val id=assertNotNull(query.register).id
        assertNotNull(c.byId(id));assertNotNull(c.byId(query.register.fields.first().id))
        assertNotNull(c.property(query.register.fields.first().id,"bitWidth")["provenance"])
        assertNotNull(c.property(id,"offset")["provenance"])
        assertEquals("UNKNOWN",c.property("does-not-exist","initial")["status"])
        val property=c.property(id,"initial")
        assertEquals("HYPOTHESIS",obj(property["provenance"])["confidence"])
        val before=CatalogIO.canonical(c.data)
        repeat(50){c.query("cpu-physical",0xfffc6094,4,"read");c.componentDescriptor("battery");c.property(id,"name")}
        assertContentEquals(before,CatalogIO.canonical(c.data))
        assertEquals(emptyList<Any>(),c.componentDescriptor("battery")["ports"])
        assertFalse(c.componentDescriptor("battery").containsKey("sourceSha256"))
    }
    @Test fun componentContractIgnoresResearchMetadataButDetectsAccessShapeChanges() {
        val c=catalog()
        @Suppress("UNCHECKED_CAST") val changed=CatalogIO.json.readValue(CatalogIO.canonical(c.data),MutableMap::class.java) as MutableMap<String,Any?>
        changed["version"]="research-revision"
        for(device in rows(changed["devices"])) {
            @Suppress("UNCHECKED_CAST") val mutable=device as MutableMap<String,Any?>
            mutable["name"]="updated research name";mutable["unknowns"]=listOf("new evidence question")
            for(port in rows(device["ports"]))for(register in rows(port["registers"])) {
                @Suppress("UNCHECKED_CAST") val r=register as MutableMap<String,Any?>
                r["name"]="new interpretation";r["evidence"]=listOf("new-report")
            }
        }
        val updated=AddressCatalog(changed)
        c.componentIds.forEach {assertEquals(c.componentDescriptor(it),updated.componentDescriptor(it))}
        val sd=rows(changed["devices"]).single {it["componentId"]=="soc-sd-host"}
        @Suppress("UNCHECKED_CAST") val register=rows(rows(sd["ports"]).first()["registers"]).first() as MutableMap<String,Any?>
        for((key,value) in listOf("readWidths" to listOf(1),"size" to 8,"alignment" to 8,"behaviorId" to "other-implementation")) {
            val prior=register[key];register[key]=value
            assertNotEquals(c.componentDescriptor("soc-sd-host"),AddressCatalog(changed).componentDescriptor("soc-sd-host"),key)
            register[key]=prior
        }
    }
    @Test fun payloadAndSourceIdentityAreDeterministicAndTamperingRefuses() {
        val c=catalog();val bytes=CatalogIO.compiledBytes(c)
        assertContentEquals(bytes,CatalogIO.compiledBytes(catalog()))
        assertEquals(c.artifactSha256,CatalogIO.readCompiled(bytes).artifactSha256)
        val broken=bytes.toString(Charsets.UTF_8).replaceFirst(c.firmwareSha256,"0".repeat(64)).toByteArray()
        assertFailsWith<IllegalArgumentException>{CatalogIO.readCompiled(broken)}
        assertFalse(c.data.containsKey("sourceFiles"));assertFalse(c.data.containsKey("sourceSha256"))
        (c.data["sourcePaths"] as List<*>).forEach {source->CatalogIO.readText(Files.readAllBytes(root.resolve(source as String)))}
    }
    @Test fun semanticValidationRejectsOverlapsMasksFieldsAndOwnership() {
        fun copy():MutableMap<String,Any?> { @Suppress("UNCHECKED_CAST") return CatalogIO.json.readValue(CatalogIO.canonical(catalog().data),MutableMap::class.java) as MutableMap<String,Any?> }
        fun firstRegister(data:Map<String,Any?>):MutableMap<String,Any?> { @Suppress("UNCHECKED_CAST") return rows(rows(rows(data["devices"]).first{rows(it["ports"]).isNotEmpty()}["ports"]).first()["registers"]).first() as MutableMap<String,Any?> }
        val overlap=copy();@Suppress("UNCHECKED_CAST") val windows=overlap["windows"] as MutableList<MutableMap<String,Any?>>;windows[1]["base"]=windows[0]["base"]
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(overlap)}
        val mask=copy();firstRegister(mask)["masks"]=mapOf("writable" to 1,"preserve" to 1,"unknown" to 0xffff_fffeL,"prohibited" to 0)
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(mask)}
        val field=copy();@Suppress("UNCHECKED_CAST") val fields=firstRegister(field)["fields"] as MutableList<MutableMap<String,Any?>>;fields[0]["bitWidth"]=33
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(field)}
        val candidates=copy();@Suppress("UNCHECKED_CAST") val list=candidates["candidates"] as MutableList<MutableMap<String,Any?>>;list[1]["base"]=list[0]["base"]
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(candidates)}
        val missingFill=copy();@Suppress("UNCHECKED_CAST") val c=(missingFill["candidates"] as MutableList<MutableMap<String,Any?>>)[0];c["initialFill"]=null
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(missingFill)}
    }
    @Test fun flashDomainsKeepContainerLengthAndIndependentWritePermissions() {
        val c=catalog()
        val mapping=assertNotNull(c.byId("map.dat-container"))
        assertEquals(49283104L,valueLong(mapping["sourceEndExclusive"]))
        assertTrue(valueLong(mapping["sourceEndExclusive"])<50197040-628)
        assertFalse(c.query("flash-logical",0x03d40000,4,"write").permitted)
        assertTrue(c.query("flash-logical",0x03dc0000,4,"write").permitted)
        assertFalse(c.query("flash-logical",0x03f40000,4,"write").permitted)
        assertEquals("CROSS_BOUNDARY",c.query("flash-logical",0x03f3fffe,4,"read").classification)
        assertEquals(false,c.query("flash-logical",0x03f00000,4,"read").details["backingActivatedByQuery"])
        assertNotNull(c.byId("INV-MOTION-001"))
        assertNotNull(c.byId("INV-LED-003"))
        for (d in rows(c.data["devices"])) {
            assertFalse(d.containsKey("implementationBindings"))
            (d["implementationSources"] as List<*>).forEach {assertTrue(Files.isRegularFile(root.resolve(it as String)))}
        }
    }
    @Test fun logicalInstancesGroupsAndMirrorsAreResolvableAndReadOnly() {
        val c=catalog()
        assertNotNull(c.byId("instance.gfx100s.soc-sd-host.slot0"))
        assertNotNull(c.byId("instance.gfx100s.battery"))
        assertNotNull(c.byId("gfx100s.sd.slot0.b.group"))
        assertNotNull(c.byId("mirror.soc-dram.phy"))
        assertNotNull(c.byId("memory."+c.defaultMemoryProfile+".loader"))
        assertFalse(c.query("cpu-physical",0xffff1401,2,"read").permitted)
        assertTrue(c.query("cpu-physical",0xffff1401,1,"read").permitted)
        @Suppress("UNCHECKED_CAST") val fields=c.query("cpu-physical",0xfffc6244,4).register!!.fields as MutableList<CatalogField>
        assertFailsWith<UnsupportedOperationException>{fields.clear()}
        @Suppress("UNCHECKED_CAST") val windows=c.windows as MutableList<CatalogWindow>
        assertFailsWith<UnsupportedOperationException>{windows.clear()}
        @Suppress("UNCHECKED_CAST") val data=c.byId("device.battery") as MutableMap<String,Any?>
        assertFailsWith<UnsupportedOperationException>{data["physicalIdentity"]="fabricated"}
        val dat=c.coverage("dat-file")
        assertEquals("UNASSIGNED",dat.last()["classification"])
        assertEquals(50197040L,dat.last()["base"])
    }
    @Test fun mirroredWindowsShareOneValidatedRegisterDefinition() {
        val c=catalog()
        assertTrue(rows(c.data["windows"]).all { "registers" !in it })
        c.windows.groupBy { it.definitionId }.values.filter { it.size>1 }.forEach { windows ->
            windows.drop(1).forEach { assertSame(windows.first().registers,it.registers) }
        }
        assertTrue(CatalogIO.compiledBytes(c).size < 8*1024*1024)
        fun mutated(edit:(MutableMap<String,Any?>)->Unit): Map<String,Any?> {
            @Suppress("UNCHECKED_CAST") val data=CatalogIO.json.readValue(CatalogIO.canonical(c.data),MutableMap::class.java) as MutableMap<String,Any?>
            @Suppress("UNCHECKED_CAST") val window=rows(data["windows"]).first() as MutableMap<String,Any?>
            edit(window);return data
        }
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(mutated { it["registers"]=emptyList<Any>() })}
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(mutated { it["size"]=1 })}
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(mutated { it["kind"]="CANDIDATE" })}
        assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(mutated { it["componentId"]="battery" })}
    }
    @Test fun candidateQueryReportsDirectionalWidthsNaturalAlignmentAndSelectedMode() {
        val c=catalog()
        assertFalse(c.query("cpu-physical",0xfffc6001,2,"read").permitted)
        assertTrue(c.query("cpu-physical",0xfffc6002,2,"read").permitted)
        val readOnly=c.query("cpu-physical",0xfffc6060,4,"read")
        assertTrue(readOnly.permitted);assertEquals(listOf(4),readOnly.details["readWidths"]);assertEquals(emptyList<Int>(),readOnly.details["writeWidths"])
        assertFalse(c.query("cpu-physical",0xfffc6060,4,"write").permitted)
        val strict=c.coverage(base=0xfffc6000,endExclusive=0xfffc6004,mode="strict")
        assertTrue(strict.all {it["refusalReason"]=="strict mode refuses candidate access"})
        val discovery=c.coverage(base=0xfffc6000,endExclusive=0xfffc6004)
        assertTrue(discovery.all {it["refusalReason"]==null})
    }
    @Test fun verificationChecksExistingOutputsWithoutRepairingThem() {
        CatalogCompiler.verify(root)
        val destination=Files.createTempDirectory("catalog-stale-test-")
        try {
            for(base in listOf("catalog/compiled","docs/generated/address-space.md","docs/generated/devices.md","docs/generated/flash-map.md","catalog/soc/xp4","catalog/bodies/gfx100s","catalog/firmware/gfx100s/2.13","configurations/jvm/firmware")) {
                Files.walk(root.resolve(base)).use {files->files.filter {Files.isRegularFile(it)}.forEach {source->
                    val target=destination.resolve(root.relativize(source));Files.createDirectories(target.parent);Files.copy(source,target)
                }}
            }
            val altered=destination.resolve("configurations/jvm/firmware/gfx100s-213.yaml")
            Files.writeString(altered,"{}\n")
            assertFailsWith<IllegalArgumentException> {CatalogCompiler.verify(root,destination)}
            assertEquals("{}\n",Files.readString(altered))
        } finally {Files.walk(destination).use {files->files.sorted(java.util.Comparator.reverseOrder()).forEach {Files.delete(it)}}}
    }
    @Test fun strictUtf8LfAndClosedSchemasRejectMalformedInputs() {
        listOf("{}","{}\r\n","\uFEFF{}\n","{\r}\n").forEach{assertFails{CatalogIO.readText(it.toByteArray())}}
        assertFails{CatalogIO.readText(byteArrayOf(0xc3.toByte(),10))}
        val schema=CatalogIO.readYaml(root.resolve("catalog/schema/device.schema.json"))
        val device=CatalogIO.readYaml(root.resolve("catalog/devices/battery.yaml"))
        assertFailsWith<IllegalArgumentException>{CatalogSchema.validate(device+mapOf("unexpected" to 1),schema)}
        assertFailsWith<IllegalArgumentException>{CatalogSchema.validate(device-"componentId",schema)}
    }
}

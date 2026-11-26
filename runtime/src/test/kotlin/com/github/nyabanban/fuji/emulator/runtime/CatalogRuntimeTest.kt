package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogIO
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CatalogRuntimeTest {
    private val context=object:DiscoveryContext {override fun core()=1;override fun tick()=99L;override fun pc()=0x5afe0L;override fun lr()=0x11f1c78L}
    @Test fun selectableFixedInputsOverrideOnlyDefaultPolicyAndRemainUnselectedUntilOwnerChoice() {
        val catalog=AddressCatalog.bundled()
        val id="gfx100s.system-control.status-a200.candidate"
        val address=0xfff4a200L
        fun runtime(input:Map<String,Any>,mode:String="discovery")=CatalogRuntime(catalog,mode,Path.of("."),
            mapOf("discovery" to mapOf("inputs" to mapOf(id to input))))
        val baseline=CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap())
        assertEquals("REGISTER",baseline.query(mapOf("address" to "0xFFF4A200","direction" to "read"))["classification"])
        assertEquals(true,baseline.query(mapOf("address" to "0xFFF4A200","direction" to "read"))["permitted"])
        assertFails { checkNotNull(baseline.createBus(context)).access(1,address,4,false) }
        for (value in listOf(0L,1L)) {
            val selections=mutableListOf(0L,1L)
            val runtime=runtime(mapOf("strategy" to "fixed-input","selectionValues" to selections))
            selections.clear() // The runtime configuration is frozen at construction.
            val b=checkNotNull(runtime.createBus(context))
            val before=b.fixedInputSelection(id)
            val read=runtime.query(mapOf("address" to "0xFFF4A200","direction" to "read"))
            assertEquals(true,read["permitted"]);assertNull(read["refusalReason"])
            assertEquals(before,b.fixedInputSelection(id));assertNull(before["selectedValue"])
            b.selectFixedInput(id,value)
            assertEquals(value,b.access(1,address,4,false))
            for ((offset,width,write) in listOf(Triple(0L,1,false),Triple(1L,4,false),Triple(0L,4,true),Triple(4L,4,false))) {
                assertFails { b.access(2,address+offset,width,write,0) }
                assertEquals(false,runtime.query(mapOf("address" to (address+offset).toString(),"width" to width.toString(),"direction" to if(write)"write" else "read"))["permitted"])
            }
            assertEquals(false,runtime.query(mapOf("address" to "0xFFF4A200","direction" to "execute"))["permitted"])
        }
        val pending=checkNotNull(runtime(mapOf("strategy" to "fixed-input","selectionValues" to listOf(0,1))).createBus(context))
        assertFails { pending.access(1,address,4,false) };assertFails { pending.selectFixedInput(id,0) }
        val strict=runtime(mapOf("strategy" to "fixed-input","selectionValues" to listOf(0,1)),"strict")
        assertNull(strict.createBus(context))
        val boundInactive=CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap()).apply {
            bindPorts(emptyList(),"loader-low128-experimental-v1",catalog.memoryProfiles.getValue("loader-low128-experimental-v1"))
        }
        assertEquals(false,boundInactive.query(mapOf("address" to "0xFFF4A200","direction" to "read"))["permitted"])
        assertEquals("known port is not active",boundInactive.query(mapOf("address" to "0xFFF4A200","direction" to "read"))["refusalReason"])
        val fixed=checkNotNull(runtime(mapOf("strategy" to "fixed-input","value" to 1)).createBus(context))
        assertEquals(1L,fixed.access(1,address,4,false))
        assertFails { fixed.selectFixedInput(id,0) }
        // Even a writable input law must preserve the candidate's READ4-only declaration.
        val retainedRuntime=runtime(mapOf("strategy" to "retained-bytes","fill" to 0))
        val retained=checkNotNull(retainedRuntime.createBus(context))
        assertEquals(0L,retained.access(1,address,4,false))
        assertFails { retained.access(2,address,4,true,1) }
        assertEquals(false,retainedRuntime.query(mapOf("address" to "0xFFF4A200","direction" to "write"))["permitted"])
    }

    @Test fun fixedInputSelectionSchemaRejectsMissingAmbiguousAndInvalidLists() {
        val catalog=AddressCatalog.bundled();val id="gfx100s.system-control.status-a200.candidate"
        val invalid=listOf(
            mapOf("strategy" to "fixed-input"),
            mapOf("strategy" to "fixed-input","selectionValues" to emptyList<Long>()),
            mapOf("strategy" to "fixed-input","selectionValues" to listOf(0,0)),
            mapOf("strategy" to "fixed-input","selectionValues" to listOf(null,1)),
            mapOf("strategy" to "fixed-input","selectionValues" to listOf(-1,1)),
            mapOf("strategy" to "fixed-input","selectionValues" to listOf(0x1_0000_0000L)),
            mapOf("strategy" to "fixed-input","selectionValues" to listOf(0.0,1.0)),
            mapOf("strategy" to "fixed-input","selectionValues" to 0),
            mapOf("strategy" to "fixed-input","selectionValues" to listOf(0,1),"value" to 0),
            mapOf("strategy" to "refuse","selectionValues" to listOf(0,1)))
        for (input in invalid) {
            assertFails { CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("inputs" to mapOf(id to input)))).createBus(context) }
        }
    }

    @Test fun productionDefaultCreatesFiniteDiscoveryWhileExplicitStrictPreservesRefusal() {
        val catalog=AddressCatalog.bundled();assertEquals("discovery",CatalogRuntime.mode(emptyMap()))
        val runtime=CatalogRuntime(catalog,CatalogRuntime.mode(emptyMap()),Path.of("."),emptyMap())
        val bus=checkNotNull(runtime.createBus(context))
        assertEquals(catalog.candidates.filterNot { it.id in setOf(POLL_7801C_REGION, WRITE_49530_REGION, TAIL_49500_REGION, IMAGE_RESULT_SURFACE) || it.id in retainedConfigRegions }.sortedBy { it.base }.map { it.id to (it.endExclusive - it.base) },
            bus.regions.map { it.id to it.length.toLong() })
        assertEquals(0L,bus.access(1,0xfffc41e0,4,false))
        bus.access(2,0xfffc41e0,4,true,0x10400);assertEquals(0x10400L,bus.access(3,0xfffc41e0,4,false))
        val rows=runtime.observation(bus)["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertNotNull(rows.first()["registerId"]);assertEquals("HYPOTHESIS",rows.first()["confidence"])
        assertEquals(0x11f1c78L,rows.first()["lr"]);assertEquals(catalog.artifactSha256,runtime.observation(bus)["dictionarySha256"]);assertFalse(rows.first().containsKey("dictionarySha256"));assertEquals(runtime.observation(bus)["runId"],rows.first()["runId"])
        assertFails {bus.access(4,0xfffc4200,4,false)}
        assertFails {bus.access(5,0xfffc6078,4,true,0)} // known port cannot fall through when inactive
        val strict=CatalogRuntime(catalog,"strict",Path.of("."),mapOf("executionMode" to "strict"))
        assertNull(strict.createBus(context));assertEquals(false,strict.query(mapOf("address" to "0xFFFC41E0","direction" to "read"))["permitted"])
        assertFails {CatalogRuntime.mode(mapOf("executionMode" to "automatic"))}
    }
    @Test fun queryByAddressIdPropertyAndCoverageNeverConsumesInputsOrChangesClock() {
        val catalog=AddressCatalog.bundled();val runtime=CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap())
        val bus=checkNotNull(runtime.createBus(context));val before=bus.snapshot()
        val address=runtime.query(mapOf("address" to "0xFFFC41E0","width" to "4","direction" to "read"))
        assertEquals("CANDIDATE_REGISTER",address["classification"]);assertEquals(false,address["deviceReadPerformed"])
        val id=address["registerId"].toString();assertEquals("DECLARED",runtime.query(mapOf("id" to id))["status"])
        assertNotNull(runtime.query(mapOf("id" to id,"property" to "provenance"))["value"])
        val coverage=runtime.query(mapOf("coverage" to "true"))["coverage"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertEquals(0L,(coverage.first()["base"] as Number).toLong());assertEquals(0x1_0000_0000L,(coverage.last()["endExclusive"] as Number).toLong())
        assertTrue(coverage.any {it["classification"]=="UNASSIGNED"});assertEquals(before,bus.snapshot());assertEquals(99L,context.tick())
        assertEquals("UNASSIGNED",runtime.query(mapOf("address" to "0x100000000"))["classification"])
        assertFails {runtime.query(mapOf("firmware" to "another-camera"))}
    }
    @Test fun activeMemoryAndPortsAreReportedSeparatelyFromExplicitStaticResearchProfiles() {
        val catalog=AddressCatalog.bundled();val runtime=CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap())
        runtime.bindPorts(emptyList(),"loader-low128-experimental-v1",catalog.memoryProfiles.getValue("loader-low128-experimental-v1"))
        assertEquals("UNASSIGNED",runtime.query(mapOf("address" to "0x40000"))["classification"])
        val static=runtime.query(mapOf("address" to "0x40000","memoryProfile" to catalog.defaultMemoryProfile))
        assertEquals("RAM",static["classification"]);assertEquals("explicit-static-profile",static["memoryView"])
        val disabled=runtime.query(mapOf("address" to "0xFFFC6078","width" to "4","direction" to "read"))
        assertEquals("INACTIVE",disabled["runtimeActivation"]);assertEquals(false,disabled["permitted"])
        assertFails { runtime.bindPorts(emptyList(),catalog.defaultMemoryProfile,catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile)) }
    }
    @Test fun nativeRefusalsResolveKnownIdsAndActiveMemoryWithoutInventingReturnedValues() {
        val catalog=AddressCatalog.bundled();val runtime=CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap())
        runtime.bindPorts(emptyList(),"loader-low128-experimental-v1",catalog.memoryProfiles.getValue("loader-low128-experimental-v1"))
        val bus=checkNotNull(runtime.createBus(context));val router=MmioRouter(emptyList(),bus)
        router.refusedNative(19,0xfffc6078,4,0)
        router.refusedNative(21,0x40000,4,0)
        val rows=runtime.observation(bus)["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertNotNull(rows[0]["registerId"]);assertNotNull(rows[0]["regionId"]);assertNotNull(rows[0]["deviceId"])
        assertNull(rows[0]["value"]);assertEquals("read",rows[0]["direction"])
        assertEquals("UNASSIGNED",rows[1]["classification"]);assertEquals("execute",rows[1]["direction"]);assertNull(rows[1]["value"])
        val strict=CatalogRuntime(catalog,"strict",Path.of("."),emptyMap())
        val coverage=strict.query(mapOf("coverage" to "true","base" to "0xFFFC6000","endExclusive" to "0xFFFC6004"))["coverage"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertTrue(coverage.all {it["refusalReason"]=="strict mode refuses candidate access"})
    }
    @Test fun candidateWriteMaskChangesCannotSilentlyEnterGenericRetainedStorage() {
        val catalog=AddressCatalog.bundled()
        for(maskName in listOf("writable","preserve","prohibited")) {
            val data=Formats.map(Formats.json.readValue(Formats.jsonBytes(catalog.data),Map::class.java))
            @Suppress("UNCHECKED_CAST") val candidates=data["candidates"] as List<Map<String,Any?>>
            @Suppress("UNCHECKED_CAST") val candidateRegister=(candidates.first()["registers"] as List<MutableMap<String,Any?>>).first()
            val masks=mapOf("writable" to if(maskName=="writable")1 else 0,"preserve" to if(maskName=="preserve")1 else 0,"prohibited" to if(maskName=="prohibited")1 else 0,"unknown" to 0xfffffffeL)
            candidateRegister["masks"]=masks
            @Suppress("UNCHECKED_CAST") val devices=data["devices"] as List<Map<String,Any?>>
            @Suppress("UNCHECKED_CAST")
        for(device in devices)for(port in device["ports"] as List<Map<String,Any?>>)for(register in port["registers"] as List<MutableMap<String,Any?>>)
                if(register["id"]==candidateRegister["id"])register["masks"]=masks
            val changed=CatalogIO.readCompiled(CatalogIO.canonical(mapOf("schema" to 1,"payloadSha256" to CatalogIO.sha256(CatalogIO.canonical(data)),"data" to data)))
            assertFailsWith<IllegalArgumentException>{CatalogRuntime(changed,"discovery",Path.of("."),emptyMap()).createBus(context)}
        }
    }
    @Test fun publicFixtureQueriesUseActualIdentityRamPermissionsAndPortsWithoutVendorMappings() {
        val catalog=AddressCatalog.bundled()
        val regions=listOf(mapOf<String,Any?>("id" to "loader","address" to 0,"size" to 0x300000,"permissions" to 7),mapOf("id" to "readonly","address" to 0xe20000,"size" to 4096,"permissions" to 1))
        val ports=listOf(com.github.nyabanban.fuji.emulator.catalog.PortBinding("soc-spi","soc-spi.spi1",0xfff32000,0x28))
        val raw=mapOf<String,Any?>("schema" to 1,"id" to "synthetic-memory","version" to "TEST","size" to 0x20284,"sha256" to "a".repeat(64),"entry" to 0,"cpsr" to 0xd3,"loaderMemoryBytes" to 0x20000,"bootMode" to "public-synthetic-guest","sections" to listOf(mapOf("name" to "boot","address" to 0,"size" to 16,"offset" to 0x20274,"encoding" to "raw","sha256" to "b".repeat(64))),"devices" to listOf(mapOf("id" to "soc-spi","port" to "soc-spi.spi1","address" to 0xfff32000L,"size" to 0x28)),"memoryProfiles" to mapOf("fixture-profile" to regions),"evidence" to listOf("TEST"))
        val runtime=CatalogRuntime(catalog,"strict",Path.of("."),emptyMap())
        runtime.validateFirmware(raw)
        assertFails {runtime.query(mapOf("address" to "0xE20000"))}
        assertFails {runtime.bindPorts(ports,"fixture-profile",regions.map{it+mapOf("permissions" to 7)})}
        runtime.bindPorts(ports,"fixture-profile",regions)
        assertFails {runtime.query(mapOf("memoryProfile" to catalog.defaultMemoryProfile,"address" to "0xE20000"))}
        val denied=runtime.query(mapOf("address" to "0xE20000","width" to "4","direction" to "write"))
        assertEquals("synthetic-memory",denied["firmwareId"]);assertEquals("a".repeat(64),denied["firmwareSha256"])
        assertEquals("TEST",denied["evidenceClass"]);assertEquals(false,denied["permitted"])
        assertEquals("REGISTER",runtime.query(mapOf("address" to "0xFFF32000","width" to "4","direction" to "read"))["classification"])
        assertEquals("UNASSIGNED",runtime.query(mapOf("address" to "0xFFFC41E0"))["classification"])
        for(space in listOf("flash-logical","dat-file","flash-aperture"))assertEquals("UNKNOWN",runtime.query(mapOf("space" to space,"address" to "0"))["classification"])
        assertNull(runtime.createBus(context))
        assertFails {CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap()).validateFirmware(raw)}
        val official=CatalogRuntime(catalog,"strict",Path.of("."),emptyMap())
        official.validateFirmware(catalog.legacyFirmwareMetadata())
        val original=catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile)
        assertFails {official.bindPorts(emptyList(),catalog.defaultMemoryProfile,original.mapIndexed{i,r->if(i==0)r+mapOf("permissions" to 1)else r})}
        official.bindPorts(emptyList(),catalog.defaultMemoryProfile,original)
    }
    @Test fun overrideInputsRequireIdentityAndDoNotEnlargeEnvelopeOrRepeatScripts() {
        val catalog=AddressCatalog.bundled();val first=catalog.candidates.single {it.base==0xfffc4000L};val home=Files.createTempDirectory("catalog-input")
        fun runtime(input:Map<String,Any>)=CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to mapOf(first.id to input))))
        try {
            assertFails {runtime(mapOf("strategy" to "retained-bytes")).createBus(context)}
            assertFails {runtime(mapOf("strategy" to "image-backed","file" to "missing.img","sha256" to "a".repeat(64))).createBus(context)}
            Files.write(home.resolve("input.img"),ByteArray(first.size.toInt()){0x12})
            val image=checkNotNull(runtime(mapOf("strategy" to "image-backed","file" to "input.img","sha256" to sha256(home.resolve("input.img")))).createBus(context))
            assertEquals(0x12121212L,image.access(1,0xfffc41e0,4,false));assertFails {image.access(2,0xfffc41e0,4,true,0)}
            val scripted=checkNotNull(runtime(mapOf("strategy" to "scripted-read","samples" to listOf(5L))).createBus(context))
            assertEquals(5L,scripted.access(1,0xfffc41e0,4,false));assertFails {scripted.access(2,0xfffc41e0,4,false)}
            assertFails {CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to mapOf("unknown" to mapOf("strategy" to "refuse"))))).createBus(context)}
            assertFails {runtime(mapOf("strategy" to "fixed-input","value" to 0,"base" to 0)).createBus(context)}
        } finally {home.toFile().deleteRecursively()}
    }

    @Test fun retainedFileSeedIsExactFrozenWritableAndReadOnlyQueriesDoNotConsumeIt(@TempDir home: Path) {
        val catalog=AddressCatalog.bundled();val region=catalog.candidates.single {it.base==0xfffc6000L}
        val initial=ByteArray(region.size.toInt()){((it*13+7) and 255).toByte()};initial[0x7c]=1
        val file=home.resolve("seed.img");Files.write(file,initial)
        val input=mapOf("strategy" to "retained-bytes","file" to "seed.img","sha256" to CatalogIO.sha256(initial))
        val runtime=CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to mapOf(region.id to input))))
        val bus=checkNotNull(runtime.createBus(context))
        assertContentEquals(initial,bus.regions.single {it.id==region.id}.initial)
        val before=bus.snapshot()
        val query=runtime.query(mapOf("address" to "0xFFFC6094","width" to "4","direction" to "write"))
        assertEquals(true,query["permitted"]);assertEquals("retained-bytes",query["policy"])
        assertEquals(input,query["selectedInput"]);assertEquals("frozen-runtime-configuration",query["policySource"])
        assertEquals(false,query["deviceReadPerformed"]);assertEquals(before,bus.snapshot());assertEquals(99L,context.tick())
        fun word(offset:Int)=(0 until 4).fold(0L){value,index -> value or ((initial[offset+index].toLong() and 255) shl (index*8))}
        Files.write(file,ByteArray(initial.size){0x77})
        assertEquals(word(0x60),bus.access(1,0xfffc6060,4,false))
        assertEquals(word(0x7c),bus.access(2,0xfffc607c,4,false))
        Files.delete(file)
        bus.access(3,0xfffc6094,4,true,0xa1b2c3d4L)
        assertEquals(0xa1b2c3d4L,bus.access(4,0xfffc6094,4,false))
        val expected=initial.clone();repeat(4){expected[0x94+it]=(0xa1b2c3d4L ushr (it*8)).toByte()}
        val state=(bus.snapshot()["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).single {it["id"]==region.id}
        assertEquals(CatalogIO.sha256(initial),state["initialSha256"]);assertEquals(CatalogIO.sha256(expected),state["bytesSha256"])
        val rows=runtime.observation(bus)["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertTrue(rows.all {it["completed"]==true && it["confidence"]=="HYPOTHESIS"})
        assertEquals(listOf("retained-bytes","retained-bytes","guest-write","retained-bytes"),rows.map{it["valueSource"]})
    }

    @Test fun retainedSeedCannotBypassKnownPortsHolesWidthsOrEnvelope(@TempDir home: Path) {
        val original=AddressCatalog.bundled()
        val data=Formats.map(Formats.json.readValue(Formats.jsonBytes(original.data),Map::class.java)).toMutableMap()
        @Suppress("UNCHECKED_CAST") val candidates=data["candidates"] as List<Map<String,Any?>>
        data["candidates"]=candidates.map { c -> if(c["base"]==0xfffc6000L || (c["base"] as Number).toLong()==0xfffc6000L)
            c+mapOf("policy" to "refuse","prohibited" to listOf(mapOf("base" to 0xfffc6040L,"endExclusive" to 0xfffc6044L))) else c }
        val catalog=CatalogIO.readCompiled(CatalogIO.canonical(mapOf("schema" to 1,"payloadSha256" to CatalogIO.sha256(CatalogIO.canonical(data)),"data" to data)))
        val region=catalog.candidates.single {it.base==0xfffc6000L};val initial=ByteArray(region.size.toInt()){0xa5.toByte()}
        Files.write(home.resolve("seed.img"),initial)
        val runtime=CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to mapOf(region.id to mapOf("strategy" to "retained-bytes","file" to "seed.img","sha256" to CatalogIO.sha256(initial))))))
        val bus=checkNotNull(runtime.createBus(context));var knownCalls=0
        assertEquals(0xa5a5a5a5L,bus.access(0,0xfffc6060L,4,false))
        assertEquals(true,runtime.query(mapOf("address" to "0xFFFC6060","direction" to "read"))["permitted"])
        val known=object:com.github.nyabanban.fuji.emulator.api.MmioDevice {
            override fun read(offset:Long,size:Int):Long {require(offset==0L && size==4);knownCalls++;return 9}
            override fun write(offset:Long,size:Int,value:Long) {require(offset==0L && size==2);knownCalls++}
        }
        val router=MmioRouter(listOf(MmioWindow("known",0xfffc6078L,4,known)),bus)
        val before=bus.snapshot()["regions"]
        assertFails {router.write(0xfffc6078L,4,1)}
        assertFails {router.read(0xfffc6040L,4)}
        assertFails {router.write(0xfffc6040L,4,0)}
        assertFails {router.read(0xfffc6060L,8)}
        assertFails {router.write(0xfffc62feL,4,0)}
        assertFails {router.read(0xfffc6300L,4)}
        assertEquals(0,knownCalls);assertEquals(before,bus.snapshot()["regions"])
        val rows=(runtime.observation(bus)["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).drop(1)
        assertEquals(listOf("known-device-refusal","prohibited-hole","prohibited-hole","unsupported-width","unaligned","outside-selected-envelope"),rows.map{it["reason"]})
        assertTrue(rows.all {it["completed"]==false && it["before"]==null && it["after"]==null})
        assertNull(rows[1]["value"]);assertNull(rows[3]["value"]);assertNull(rows[5]["value"])
        assertEquals(9L,router.read(0xfffc6078L,4));assertEquals(1,knownCalls)
        assertEquals(before,bus.snapshot()["regions"])
    }

    @Test fun retainedFileAndReadonlyImageShareExactInitializationButKeepDifferentWriteLaws(@TempDir home: Path) {
        val catalog=AddressCatalog.bundled();val region=catalog.candidates.single {it.base==0xfffc6000L}
        val data=ByteArray(region.size.toInt()){it.toByte()};Files.write(home.resolve("seed.img"),data)
        fun runtime(strategy:String)=CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to mapOf(region.id to mapOf("strategy" to strategy,"file" to "seed.img","sha256" to CatalogIO.sha256(data))))))
        val imageRuntime=runtime("image-backed");val retainedRuntime=runtime("retained-bytes")
        val image=checkNotNull(imageRuntime.createBus(context));val retained=checkNotNull(retainedRuntime.createBus(context))
        assertEquals(image.snapshot()["regions"].let{it.let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }}.single{it["id"]==region.id}["initialSha256"],retained.snapshot()["regions"].let{it.let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }}.single{it["id"]==region.id}["initialSha256"])
        assertEquals(image.access(1,0xfffc6094,4,false),retained.access(1,0xfffc6094,4,false))
        assertEquals(false,imageRuntime.query(mapOf("address" to "0xFFFC6094","direction" to "write"))["permitted"])
        assertEquals(true,retainedRuntime.query(mapOf("address" to "0xFFFC6094","direction" to "write"))["permitted"])
        val before=image.snapshot()["regions"];assertFails {image.access(2,0xfffc6094,4,true,0x1234)};assertEquals(before,image.snapshot()["regions"])
        retained.access(2,0xfffc6094,4,true,0x1234);assertEquals(0x1234L,retained.access(3,0xfffc6094,4,false))
    }

    @Test fun retainedFileConfigurationRejectsAmbiguityNullIdentityBoundsAndEscapes(@TempDir root: Path) {
        val home=Files.createDirectory(root.resolve("home"));val catalog=AddressCatalog.bundled();val region=catalog.candidates.single{it.base==0xfffc6000L}
        val original=ByteArray(region.size.toInt()){0x33};Files.write(home.resolve("seed.img"),original)
        Files.write(home.resolve("short.img"),original.copyOf(original.size-1));Files.write(home.resolve("long.img"),original+0.toByte())
        Files.createDirectory(home.resolve("directory.img"));Files.write(root.resolve("outside.img"),original)
        val valid=mapOf<String,Any?>("strategy" to "retained-bytes","file" to "seed.img","sha256" to CatalogIO.sha256(original))
        fun create(input:Map<String,Any?>)=CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to mapOf(region.id to input)))).createBus(context)
        val malformed=listOf(
            (valid-"file") to "retained-bytes needs explicit fill or exact file identity",
            (valid-"sha256") to "retained-bytes needs explicit fill or exact file identity",
            (valid+("fill" to 0)) to "retained-bytes needs explicit fill or exact file identity",
            (valid+("samples" to listOf(0))) to "retained-bytes needs explicit fill or exact file identity",
            (valid+("file" to null)) to "expected string-keyed object without nulls",
            (valid+("file" to 1)) to "Discovery image path must be a nonblank string",
            (valid+("file" to "")) to "Discovery image path must be a nonblank string",
            (valid+("sha256" to null)) to "expected string-keyed object without nulls",
            (valid+("sha256" to 1)) to "Discovery image SHA-256 must be 64 lowercase hex characters",
            (valid+("sha256" to "a".repeat(63))) to "Discovery image SHA-256 must be 64 lowercase hex characters",
            (valid+("sha256" to "A".repeat(64))) to "Discovery image SHA-256 must be 64 lowercase hex characters",
            (valid+("sha256" to "0".repeat(64))) to "Discovery image frozen identity differs",
            (valid+("file" to "short.img")) to "Discovery image length differs",
            (valid+("file" to "long.img")) to "Discovery image length differs",
            (valid+("file" to "directory.img")) to "Discovery image must be a regular file",
            (valid+("file" to "missing.img")) to "Discovery image must be a regular file",
            (valid+("file" to "../outside.img")) to "path outside component directory",
            (valid+("file" to root.resolve("outside.img").toString())) to "path outside component directory",
            (valid+("strategy" to null)) to "expected string-keyed object without nulls",
            mapOf<String,Any?>("strategy" to "retained-bytes","fill" to null) to "expected string-keyed object without nulls",
            mapOf<String,Any?>("strategy" to "retained-bytes","fill" to 1.0) to "integer required",
            mapOf<String,Any?>("strategy" to "retained-bytes","fill" to BigInteger.ONE.shiftLeft(80)) to "integer required",
            mapOf<String,Any?>("strategy" to "retained-bytes","fill" to 256) to "retained fill outside byte range",
            mapOf<String,Any?>("strategy" to "retained-bytes","fill" to -1) to "retained fill outside byte range"
        )
        for((input,reason) in malformed) {
            val error=assertFails(input.toString()){create(input)}
            assertTrue(error.message.orEmpty().contains(reason),"Expected rejection: $reason; actual: $error")
            assertContentEquals(original,Files.readAllBytes(home.resolve("seed.img")))
            assertEquals(99L,context.tick())
        }
        for(fill in listOf(0,255)) {
            val bus=checkNotNull(create(mapOf("strategy" to "retained-bytes","fill" to fill)))
            assertContentEquals(ByteArray(original.size){fill.toByte()},bus.regions.single{it.id==region.id}.initial)
        }
        // A later invalid region cannot publish or mutate a previously read seed.
        val later=catalog.candidates.last()
        assertFails {CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to mapOf(region.id to valid,later.id to (valid+("file" to "missing.img")))))).createBus(context)}
        assertContentEquals(original,Files.readAllBytes(home.resolve("seed.img")))
    }

    @Test fun explicitNullFillCannotSilentlyUseTheDictionaryDefault(@TempDir home: Path) {
        val catalog=AddressCatalog.bundled();val region=catalog.candidates.single{it.base==0xfffc6000L}
        val runtime=CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to mapOf(region.id to mapOf("strategy" to "retained-bytes","fill" to null)))))
        val error=assertFailsWith<IllegalArgumentException>{runtime.createBus(context)}
        assertEquals("expected string-keyed object without nulls",error.message)
    }
    @Test fun explicitNullStrategyCannotSilentlyUseTheDictionaryPolicy(@TempDir home: Path) {
        val catalog=AddressCatalog.bundled();val region=catalog.candidates.single{it.base==0xfffc6000L}
        val runtime=CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to mapOf(region.id to mapOf("strategy" to null,"fill" to 0)))))
        val error=assertFailsWith<IllegalArgumentException>{runtime.createBus(context)}
        assertEquals("expected string-keyed object without nulls",error.message)
    }
    @Test fun explicitNullInputsAndEntriesAreRejectedWhileOmissionKeepsDictionaryFill(@TempDir home: Path) {
        val catalog=AddressCatalog.bundled();val region=catalog.candidates.single{it.base==0xfffc6000L}
        val settingsError=assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to null))).createBus(context)
        }
        assertEquals("expected string-keyed object without nulls",settingsError.message)
        val entryError=assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog,"discovery",home,mapOf("discovery" to mapOf("inputs" to mapOf(region.id to null)))).createBus(context)
        }
        assertEquals("expected string-keyed object without nulls",entryError.message)
        for(config in listOf(emptyMap(),mapOf("discovery" to emptyMap<String,Any>()),mapOf("discovery" to mapOf("inputs" to emptyMap<String,Any>())))) {
            val bus=checkNotNull(CatalogRuntime(catalog,"discovery",home,config).createBus(context))
            val (legacy, refused) = bus.regions.partition { it.id.startsWith("gfx100s.sd.") }
            assertEquals(4, legacy.size)
            assertTrue(legacy.all {it.strategy==DiscoveryStrategy.RETAINED && it.initial!!.all {byte -> byte==0.toByte()}})
            assertEquals(emptyList(), refused.map { it.id })
            for (region in refused) {
                assertEquals(DiscoveryStrategy.REFUSE, region.strategy)
                assertNull(region.initial); assertNull(region.fixedValue); assertNull(region.selectionValues)
            }
        }
    }

    @Test fun bridgedInactiveOptionalM61PortsSurfacePermittedInDiscoveryQueryAndObservation() {
        val catalog = AddressCatalog.bundled()
        val discoveryConfig = mapOf(
            "discovery" to mapOf(
                "hdmiStatusPairs" to listOf(listOf(1L, 2L)),
                "hdmiLinkStatusReads" to listOf(4L, 8L),
                "inputs" to mapOf(
                    "gfx100s.system-control.status-a200.candidate" to mapOf("strategy" to "fixed-input", "value" to 1L),
                    HdmiStatusSamples.REGION to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    PanelInitOnce.REGION to mapOf("strategy" to "panel-init-once-v1"),
                    "gfx100s.fff3a000.uart-bank.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    "gfx100s.fff3a030.uart-config.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    UART_TAIL_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    WRITE_49530_REGION to mapOf("strategy" to DiscoveryStrategy.WRITE_49530.id),
                    TAIL_49500_REGION to mapOf("strategy" to DiscoveryStrategy.TAIL_49500.id),
                ),
            ),
        )
        // Bind only baseline required ports (omitting optional M6.1 ports)
        val runtime = CatalogRuntime(catalog, "discovery", Path.of("."), discoveryConfig).apply {
            bindPorts(
                emptyList(),
                catalog.defaultMemoryProfile,
                catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),
            )
        }
        // Valid bridged accesses return permitted = true instead of "known port is not active"
        val a200Read = runtime.query(mapOf("address" to "0xFFF4A200", "width" to "4", "direction" to "read"))
        assertEquals(true, a200Read["permitted"])
        assertNull(a200Read["refusalReason"])
        assertEquals("fixed-input", a200Read["policy"])
        assertEquals("frozen-runtime-configuration", a200Read["policySource"])

        val a200Write = runtime.query(mapOf("address" to "0xFFF4A200", "width" to "4", "direction" to "write"))
        assertEquals(false, a200Write["permitted"])

        val hdmiPairRead = runtime.query(mapOf("address" to "0xFFF7B090", "width" to "4", "direction" to "read"))
        assertEquals(true, hdmiPairRead["permitted"])
        assertNull(hdmiPairRead["refusalReason"])
        assertEquals("retained-bytes", hdmiPairRead["policy"])

        val hdmiHole = runtime.query(mapOf("address" to "0xFFF7B000", "width" to "4", "direction" to "read"))
        assertEquals(false, hdmiHole["permitted"])

        val panelWrite = runtime.query(mapOf("address" to "0xFFFAB004", "width" to "4", "direction" to "write"))
        assertEquals(true, panelWrite["permitted"])
        assertNull(panelWrite["refusalReason"])
        assertEquals("panel-init-once-v1", panelWrite["policy"])

        val uartRw = runtime.query(mapOf("address" to "0xFFF3A02C", "width" to "4", "direction" to "write"))
        assertEquals(true, uartRw["permitted"])
        assertNull(uartRw["refusalReason"])

        val write49530 = runtime.query(mapOf("address" to "0xFFF49530", "width" to "4", "direction" to "write"))
        assertEquals(true, write49530["permitted"])
        assertNull(write49530["refusalReason"])

        val tail49500 = runtime.query(mapOf("address" to "0xFFF49500", "width" to "4", "direction" to "write"))
        assertEquals(true, tail49500["permitted"])
        assertNull(tail49500["refusalReason"])

        // enrichObservation() reflects catalogPermitted = true on completed bridged DiscoveryBus accesses
        val bus = checkNotNull(runtime.createBus(context))
        assertEquals(1L, bus.access(1L, 0xfff4a200L, 4, false))
        assertEquals(1L, bus.access(2L, 0xfff7b090L, 4, false))
        assertEquals(0L, bus.access(3L, 0xfffab004L, 4, false))
        bus.access(4L, 0xfff3a02cL, 4, true, 0x42L)
        val rows = runtime.observation(bus)["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertEquals(4, rows.size)
        for (row in rows) {
            assertEquals(true, row["catalogPermitted"], "Expected catalogPermitted=true for $row")
            assertNull(row["catalogRefusalReason"], "Expected null catalogRefusalReason for $row")
        }

        // Strict mode and explicit refuse strategy still report permitted = false
        val strictRuntime = CatalogRuntime(catalog, "strict", Path.of("."), discoveryConfig).apply {
            bindPorts(
                emptyList(),
                catalog.defaultMemoryProfile,
                catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),
            )
        }
        val strictQuery = strictRuntime.query(mapOf("address" to "0xFFF4A200", "width" to "4", "direction" to "read"))
        assertEquals(false, strictQuery["permitted"])
        assertEquals("known port is not active", strictQuery["refusalReason"])

        val refusedRuntime = CatalogRuntime(
            catalog,
            "discovery",
            Path.of("."),
            mapOf("discovery" to mapOf("inputs" to mapOf("gfx100s.panel.init-words.candidate" to mapOf("strategy" to "refuse")))),
        ).apply {
            bindPorts(
                emptyList(),
                catalog.defaultMemoryProfile,
                catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),
            )
        }
        val refusedQuery = refusedRuntime.query(mapOf("address" to "0xFFFAB004", "width" to "4", "direction" to "read"))
        assertEquals(false, refusedQuery["permitted"])
    }
}

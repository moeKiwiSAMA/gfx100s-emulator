package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProtocolCandidatesTest {
    private val root=Path.of(System.getProperty("catalogRoot"))
    private val namespace="gfx100s-power-protocol-requests"
    private val request="request.power-peer.selector0x25"
    private fun catalog()=CatalogCompiler.compile(root)
    @Suppress("UNCHECKED_CAST") private fun mutable(c:AddressCatalog)=CatalogIO.json.readValue(CatalogIO.canonical(c.data),MutableMap::class.java) as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun power(data:Map<String,Any?>)=rows(data["devices"]).single {it["componentId"]=="power-peer"} as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun space(data:Map<String,Any?>)=rows(power(data)["protocolCandidates"]).single() as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun first(data:Map<String,Any?>)=rows(space(data)["requests"]).first() as MutableMap<String,Any?>
    @Test fun observedRequestsRetainUnknownDeviceValueAndBehaviorDespiteFiniteHypotheses() {
        val c=catalog();val s=c.protocolCandidateSpaces.single()
        assertEquals(namespace,s.id);assertEquals(7,s.selectorBits);assertEquals(16,s.wordBits)
        assertEquals(listOf(37,38,14,3,47,48,49,50,15,37,38),s.requests.map {it.selector})
        assertEquals("REQUEST_OBSERVED_PENDING",s.requests[0].data["observationStatus"])
        assertEquals("REQUEST_OBSERVED_PENDING",s.requests[1].data["observationStatus"])
        assertEquals("SIM",obj(obj(s.requests[0].data["provenance"])["observation"])["confidence"])
        assertEquals("SIM",obj(obj(s.requests[1].data["provenance"])["observation"])["confidence"])
        assertTrue(obj(obj(s.requests[1].data["provenance"])["observation"])["limitation"].toString().contains("HYPOTHESIS"))
        for(r in s.requests) {
            assertEquals("UNKNOWN",r.data["name"]);assertNull(obj(r.data["initial"])["value"])
            assertEquals("UNKNOWN",obj(obj(r.data["provenance"])["behavior"])["confidence"])
            assertEquals(65535L,valueLong(obj(r.data["masks"])["unknown"]))
            if(r.selector==14)assertEquals(listOf(0 to 4,4 to 1,5 to 1,6 to 10),r.fields.map {it.bitOffset to it.bitWidth})
            else if(r.selector==3)assertEquals(listOf(0 to 10,10 to 6),r.fields.map {it.bitOffset to it.bitWidth})
            else assertEquals(16,r.fields.single().bitWidth)
        }
        assertEquals(1,c.logicalSpaces.size);assertEquals("rtc",c.logicalSpaces.single().componentId)
        assertEquals(listOf(29,30,35,36,39),c.logicalSpaces.single().registers.map {it.selector})
    }
    @Test fun selector14HasAnObservedPendingRequestAndRawWidthButNoReplyOrHardwarePermission() {
        val c=CatalogIO.readCompiled(CatalogIO.compiledBytes(catalog()))
        val id="request.power-peer.selector0x0e"
        val r=c.protocolCandidateSpaces.single().requests.single {it.selector==14}
        assertEquals(id,r.id);assertEquals(16,r.wordBits)
        assertEquals("REQUEST_OBSERVED_PENDING",r.data["observationStatus"])
        val observation=obj(obj(r.data["provenance"])["observation"])
        assertEquals("SIM",observation["confidence"])
        assertEquals("a91ef33b4eaf55ebd1696429da3d40da828ef54e",obj(observation["source"])["commit"])
        assertTrue(observation["limitation"].toString().contains("no returned value"))
        assertEquals("DAT",obj(c.property(id,"wordBits")["provenance"])["confidence"])
        assertEquals("UNKNOWN",c.property(id,"initial.value")["status"])
        for(property in listOf("name","deviceOwner"))assertEquals("UNKNOWN",obj(c.property(id,property)["provenance"])["confidence"])
        val before=CatalogIO.canonical(c.data)
        for(direction in listOf("read","write")) {
            val q=c.queryLogicalCandidate(namespace,14,16,direction)
            assertEquals("UNASSIGNED_LOGICAL_REQUEST",q["classification"])
            assertEquals(false,q["permitted"]);assertEquals(false,q["dynamicReadPerformed"])
        }
        for(entityId in listOf(id,id+".bits0_3",id+".bit4",id+".bit5",id+".bits6_15")) {
            val entity=assertNotNull(c.byId(entityId))
            assertEquals(false,entity["cpuRoutable"]);assertEquals(false,entity["readable"])
            assertEquals("NOT_IMPLEMENTED",entity["runtimeActivation"]);assertNull(entity["componentId"])
        }
        for(bit in listOf(4,5)) {
            val fieldId=id+".bit"+bit
            assertEquals(bit.toLong(),valueLong(assertNotNull(c.byId(fieldId))["bitOffset"]))
            assertEquals("DAT",obj(c.property(fieldId,"bitOffset")["provenance"])["confidence"])
            assertEquals("UNKNOWN",obj(c.property(fieldId,"unit")["provenance"])["confidence"])
            assertEquals("UNKNOWN",obj(obj(assertNotNull(c.byId(fieldId))["provenance"])["behavior"])["confidence"])
        }
        assertContentEquals(before,CatalogIO.canonical(c.data))
    }
    @Test fun selector3PartitionsSoftwareRaw16WithoutGrantingPhysicalMeaningOrPermission() {
        val c=CatalogIO.readCompiled(CatalogIO.compiledBytes(catalog()));val id="request.power-peer.selector0x03"
        val r=c.protocolCandidateSpaces.single().requests.single {it.selector==3}
        assertEquals(id,r.id);assertEquals(16,r.wordBits);assertEquals("read",r.data["direction"])
        assertEquals(listOf(id+".bits0_9",id+".bits10_15"),r.fields.map {it.id})
        assertEquals(listOf(0 to 10,10 to 6),r.fields.map {it.bitOffset to it.bitWidth})
        assertEquals("REQUEST_OBSERVED_PENDING",r.data["observationStatus"])
        assertEquals("SIM",obj(obj(r.data["provenance"])["observation"])["confidence"])
        assertNull(obj(r.data["initial"])["value"]);assertNull(r.data["behaviorId"])
        for(entityId in listOf(id,id+".bits0_9",id+".bits10_15")) {
            val entity=assertNotNull(c.byId(entityId))
            for(key in listOf("permitted","readable","writable","cpuRoutable"))assertEquals(false,entity[key],key)
            assertEquals("NOT_IMPLEMENTED",entity["runtimeActivation"]);assertNull(entity["physicalAddress"]);assertNull(entity["componentId"])
            assertEquals("UNKNOWN",obj(c.property(entityId,"deviceOwner")["provenance"])["confidence"])
        }
        for(f in r.fields) {
            assertEquals("DAT",obj(c.property(f.id,"bitOffset")["provenance"])["confidence"])
            for(key in listOf("unit","initial","access","behavior"))assertEquals("UNKNOWN",obj(obj(f.data["provenance"])[key])["confidence"])
        }
        val low=obj(obj(r.fields[0].data["provenance"])["definition"])["limitation"].toString()
        val high=obj(obj(r.fields[1].data["provenance"])["definition"])["limitation"].toString()
        assertTrue(low.contains("bits0..9") && low.contains("Full16"))
        assertTrue(high.contains("All16") && high.contains("not established as unused") && high.contains("not been dynamically observed"))
        val frozen=CatalogIO.canonical(c.data)
        for(direction in listOf("read","write")) {
            val q=c.queryLogicalCandidate(namespace,3,16,direction)
            assertEquals("UNASSIGNED_LOGICAL_REQUEST",q["classification"])
            assertEquals(false,q["permitted"]);assertEquals(false,q["dynamicReadPerformed"])
        }
        assertEquals("UNASSIGNED",c.query(namespace,3,2,"read").classification)
        assertContentEquals(frozen,CatalogIO.canonical(c.data))
    }
    @Test fun selector3IsTheOnlyG5ToG6ChangeIncludingAllEarlierRequestsAndPhysicalData() {
        val history = HistoricalCatalogFixture(root)
        val c = history.catalog("059e43841a2d8e5ef6e29e4f9f594f7c3550bfb7")
        val data=mutable(c)
        val current=rows(space(data)["requests"])
        assertEquals(listOf(37L,38L,14L,3L),current.map {valueLong(it["selector"])})
        space(data)["requests"]=current.dropLast(1)
        val old = history.catalog("0775b26915cac9e5327983da359144ccefb8fe22")
        assertContentEquals(CatalogIO.canonical(old.data),CatalogIO.canonical(data))
        val dat = CatalogIO.json.readValue(history.bytes("059e43841a2d8e5ef6e29e4f9f594f7c3550bfb7",
            "evidence/refactor/power03-dat-fresh-08/actual-02.generated.json"), Map::class.java)
        assertEquals("b80f5fac8489420d9d035eaaa7527f33ff1dfa6f",dat["sourceCommit"])
        val definitions=obj(dat["dat"])
        assertEquals(16L,valueLong(obj(definitions["storage"])["rawBitsStored"]))
        assertEquals(16L,valueLong(obj(definitions["fullRingConsumer"])["sourceBits"]))
        assertEquals(4,rows(definitions["low10Consumers"]).size)
    }
    @Test fun selector3CannotHideIncompleteOverlappingOrOutOfRangeFields() {
        val c=catalog()
        for(change in listOf(mapOf("bitOffset" to 9),mapOf("bitWidth" to 7),mapOf("bitOffset" to Long.MAX_VALUE))) {
            val data=mutable(c);val r=rows(space(data)["requests"]).single {valueLong(it["selector"])==3L}
            @Suppress("UNCHECKED_CAST") val request3=r as MutableMap<String,Any?>
            val fields=rows(r["fields"])
            request3["fields"]=listOf(fields[0],fields[1]+change)
            assertFailsWith<IllegalArgumentException> {CatalogCompiler.validate(data)}
        }
    }
    @Test fun compiledIdFieldPropertyAndSelectorQueriesNeverGrantPermission() {
        val c=CatalogIO.readCompiled(CatalogIO.compiledBytes(catalog()))
        for(id in listOf(namespace,request,request+".raw")) {
            val entity=assertNotNull(c.byId(id));assertEquals(namespace,entity["protocolCandidateSpaceId"])
            assertEquals(false,entity["permitted"]);assertEquals(false,entity["readable"]);assertEquals(false,entity["cpuRoutable"])
            assertEquals("UNKNOWN",entity["deviceOwner"]);assertEquals("NOT_IMPLEMENTED",entity["runtimeActivation"])
            assertTrue(entity.containsKey("physicalAddress"));assertNull(entity["physicalAddress"])
            assertNull(entity["componentId"])
        }
        assertEquals("UNKNOWN",c.property(request,"initial.value")["status"])
        assertEquals("UNKNOWN",obj(c.property(request,"name")["provenance"])["confidence"])
        assertEquals("DAT",obj(c.property(request,"wordBits")["provenance"])["confidence"])
        assertEquals("UNKNOWN",obj(c.property(request,"deviceOwner")["provenance"])["confidence"])
        assertEquals("UNKNOWN",obj(c.property(request+".raw","unit")["provenance"])["confidence"])
        val q=c.queryLogicalCandidate(namespace,37,16,"read")
        assertEquals("UNASSIGNED_LOGICAL_REQUEST",q["classification"]);assertEquals(false,q["permitted"])
        assertEquals(false,q["dynamicReadPerformed"]);assertEquals("NOT_SAMPLED",q["dynamicAvailability"])
        assertEquals("UNKNOWN",c.queryLogicalCandidate(namespace,39)["classification"])
        assertEquals("UNASSIGNED",c.queryLogicalCandidate("cpu-physical",37)["classification"])
        assertEquals("RAM",c.query("cpu-physical",37,2,"read").classification)
        assertEquals("UNASSIGNED",c.query(namespace,37,2,"read").classification)
    }
    @Test fun badWidthDirectionAndBoundsRemainExplicitlyRefusedWithoutFallback() {
        val c=catalog()
        for(selector in listOf(-1L,127L,128L,Long.MAX_VALUE))assertEquals(false,c.queryLogicalCandidate(namespace,selector)["permitted"])
        for(bits in listOf(0,2,8,32)) {
            val q=c.queryLogicalCandidate(namespace,37,bits,"read")
            assertEquals(false,q["permitted"]);assertEquals(bits,q["wordBits"]);assertEquals(16,q["declaredWordBits"])
        }
        for(direction in listOf("write","execute","READ",""))assertEquals(false,c.queryLogicalCandidate(namespace,37,16,direction)["permitted"])
        assertEquals("direction differs from the evidenced request",c.queryLogicalCandidate(namespace,14,16,"write")["refusalReason"])
        assertEquals("request has no qualified device ownership or behavior; declaration grants no access",c.queryLogicalCandidate(namespace,37,16,"write")["refusalReason"])
    }
    @Test fun candidateSchemaRejectsPermissionsPhysicalCoordinatesProfilesAndInventedInitials() {
        val schema=CatalogIO.readYaml(root.resolve("catalog/schema/protocol-candidate.schema.json"))
        val source=rows(CatalogIO.readYaml(root.resolve("catalog/devices/power-peer.yaml"))["protocolCandidates"]).single()
        CatalogSchema.validate(source,schema)
        for(change in listOf(mapOf("base" to 0),mapOf("portId" to "rtc.registers"),mapOf("profiles" to listOf("enabled")),mapOf("unknownPolicy" to "retained-bytes"),mapOf("deviceOwner" to "rtc")))
            assertFailsWith<IllegalArgumentException> {CatalogSchema.validate(source+change,schema)}
        val r=rows(source["requests"]).first()
        for(change in listOf(mapOf("permitted" to true),mapOf("behaviorId" to "fake"),mapOf("selector" to 37.0),mapOf("wordBits" to "16"),mapOf("initial" to mapOf("value" to 0,"source" to "UNKNOWN"))))
            assertFailsWith<IllegalArgumentException> {CatalogSchema.validate(source+mapOf("requests" to listOf(r+change)),schema)}
    }
    @Test fun compiledSemanticChecksRejectDuplicatesNullFloatMasksCoverageAndOwnershipPromotion() {
        val c=catalog()
        fun reject(change:(MutableMap<String,Any?>)->Unit) {val data=mutable(c);change(data);assertFails {CatalogCompiler.validate(data)}}
        reject {power(it)["protocolCandidates"]=null}
        reject {space(it)["base"]=0}
        reject {space(it)["selectorBits"]="7"}
        reject {space(it)["deviceOwner"]="rtc"}
        reject {space(it)["applicability"]=mapOf("body" to "gfx100s","firmware" to "other")}
        reject {first(it)["selector"]=128}
        reject {first(it)["selector"]=37.0}
        reject {first(it)["id"]="reg.rtc.stage-low"}
        reject {val r=first(it);space(it)["requests"]=listOf(r,r+mapOf("id" to "other.request"))}
        reject {first(it)["masks"]=mapOf("writable" to 1,"preserve" to 0,"unknown" to 65534,"prohibited" to 0)}
        reject {first(it)["initial"]=mapOf("value" to 0,"source" to "UNKNOWN")}
        reject {val r=first(it);r["fields"]=rows(r["fields"]).map {f->f+mapOf("bitOffset" to Long.MAX_VALUE-63)}}
        reject {val r=first(it);r["fields"]=rows(r["fields"]).map {f->f+mapOf("bitWidth" to Long.MAX_VALUE)}}
        reject {val r=first(it);r["fields"]=rows(r["fields"]).map {f->f+mapOf("bitWidth" to 15)}}
        reject {val r=first(it);r["fields"]=rows(r["fields"]).map {f->f+mapOf("access" to "read")}}
    }
    @Test fun optionalExtensionIsBackwardsReadableAndQueriesAreImmutable() {
        val c=catalog();val data=mutable(c);power(data).remove("protocolCandidates")
        CatalogCompiler.validate(data);val old=CatalogIO.readCompiled(CatalogIO.compiledBytes(AddressCatalog(data)))
        assertTrue(old.protocolCandidateSpaces.isEmpty());assertNull(old.byId(request))
        assertEquals(1,old.logicalSpaces.size)
        val before=CatalogIO.canonical(c.data)
        repeat(100) {c.queryLogicalCandidate(namespace,37,16,"read");c.byId(request);c.property(request,"initial.value")}
        assertContentEquals(before,CatalogIO.canonical(c.data))
        @Suppress("UNCHECKED_CAST") val row=c.protocolCandidateSpaces.single().requests.first().data as MutableMap<String,Any?>
        assertFailsWith<UnsupportedOperationException> {row["selector"]=0}
    }
    @Test fun historicalShoulderMigrationPreservesG2PhysicalDataAndOtherDescriptors() {
        val base="c6788f3bd6f6afa1f963e0ec9899d45dd96a04d9"
        val history = HistoricalCatalogFixture(root)
        fun git(path: String, commit: String = base) = history.bytes(commit, path)
        val old=CatalogIO.readCompiled(git("catalog/compiled/catalog.json"))
        // Both endpoints belong to the shoulder migration, before later device additions.
        val c=CatalogIO.readCompiled(git("catalog/compiled/catalog.json","0775b26915cac9e5327983da359144ccefb8fe22"))
        for(key in listOf("windows","candidates","logicalSpaces","instances","memoryProfiles","mappings","imageDomains","legacyFirmwareMetadata","legacyCatalogs"))
            assertContentEquals(CatalogIO.canonical(old.data.getValue(key)),CatalogIO.canonical(c.data.getValue(key)),key)
        assertEquals(132,c.windows.size);assertEquals(4,c.candidates.size)
        for(component in c.componentIds) {
            val path="catalog/compiled/components/"+component+".json"
            val actual=CatalogIO.canonical(c.componentDescriptor(component))
            val g2=git(path)
            if(component=="shoulder-display") {
                val legacy=obj(CatalogIO.json.readValue(g2,Map::class.java))
                assertFalse(legacy.containsKey("profiles"))
                val expected=CatalogIO.canonical(legacy+mapOf("profiles" to listOf("unbound","dotlcd-serial1-experimental-v1")))
                assertContentEquals(git(path,"1cb5b1307636cd2cdb98f231ac822e69ec0bb8ea"),expected)
                assertContentEquals(expected,actual,component)
            } else assertContentEquals(g2,actual,component)
            assertContentEquals(git(path,"0775b26915cac9e5327983da359144ccefb8fe22"),actual,component+" historical binding baseline")
        }
        c.verifyLogicalContracts(listOf(LogicalPortBinding("rtc","rtc.registers","gfx100s-rtc-functional-experimental-v1",c.logicalSpaces.single().registers.map {it.contract("gfx100s-rtc-functional-experimental-v1")})))
        assertFails {c.verifyLogicalContracts(listOf(LogicalPortBinding("power-peer","power-peer.spi","unassigned",emptyList())))}
    }
}

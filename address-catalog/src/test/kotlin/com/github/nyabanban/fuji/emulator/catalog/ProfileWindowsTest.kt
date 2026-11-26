package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class ProfileWindowsTest {
    private val profile="gfx100s-sd-host-card-transactions-experimental-v6"
    private val profiles=mapOf("soc-sd-host" to profile)
    private val catalog by lazy {CatalogCompiler.compile(Path.of(System.getProperty("catalogRoot")))}
    private fun bindings(c:AddressCatalog=catalog)=c.windowsFor(profiles).filter{it.componentId=="soc-sd-host"}.map{PortBinding(it.componentId,it.portId,it.base,it.size)}
    @Test fun selectionReplacesOnlyTenNormalPortsAndPreservesDefaultMetadata() {
        val c=catalog
        assertEquals(212,c.windows.size);assertEquals(28,c.windows.count{it.componentId=="soc-sd-host"})
        assertEquals(204,c.windowsFor(profiles).size);assertEquals(20,bindings().size)
        assertEquals(28,rows(c.legacyFirmwareMetadata()["devices"]).count{it["id"]=="soc-sd-host"})
        assertEquals(20,rows(c.firmwareMetadata(profiles)["devices"]).count{it["id"]=="soc-sd-host"})
        c.verifyStockDeclaration(c.legacyFirmwareMetadata());c.verifyStockDeclaration(c.firmwareMetadata(profiles))
        c.verifyPorts(bindings(),profiles)
        assertEquals(c.windows.filter{it.componentId!="soc-sd-host"},c.windowsFor(profiles).filter{it.componentId!="soc-sd-host"})
        for(slot in 0..1) {
            val base=0xfffc4000L+slot*0x4000
            fun query(offset:Long,width:Int,direction:String)=c.query("cpu-physical",base+offset,width,direction,componentProfiles=profiles)
            for((offset,width) in listOf(0x2eL to 2,0x2fL to 1,0x30L to 4,0x3cL to 4,0x3eL to 2))assertTrue(query(offset,width,"read").permitted)
            assertTrue(query(0x3e,2,"write").permitted)
            assertTrue(query(0x2e,2,"write").permitted);assertTrue(query(0x2f,1,"write").permitted)
            for((offset,width,direction) in listOf(Triple(0x3cL,2,"write"),Triple(0x3cL,2,"read"),Triple(0x2eL,1,"read"),Triple(0x2eL,1,"write"),Triple(0x2fL,2,"read"),Triple(0x2fL,2,"write"),Triple(0x30L,4,"write"),Triple(0x32L,4,"read")))assertFalse(query(offset,width,direction).permitted)
            assertFalse(c.query("cpu-physical",base+0x30,4,"read",mode="strict").permitted)
        }
        assertFails {c.verifyPorts(bindings())}
        assertFails {c.verifyPorts(bindings().filter{it.portId!="soc-sd-host.transaction-0"},profiles)}
        assertFails {c.verifyPorts(bindings().map{if(it.portId.endsWith("transaction-0"))it.copy(size=32)else it},profiles)}
        val old=c.windows.single{it.portId=="soc-sd-host.control403e-0"}
        assertFails {c.verifyPorts(bindings()+PortBinding(old.componentId,old.portId,old.base,old.size),profiles)}
        assertFails {c.verifyStockDeclaration(c.firmwareMetadata(profiles)+("devices" to (rows(c.firmwareMetadata(profiles)["devices"])+rows(c.legacyFirmwareMetadata()["devices"]).single{it["port"]==old.portId})))}
    }
    @Test fun writeWidthLanesRejectMissingWidthsMisalignmentAndOutOfBoundsOffsets() {
        val data=catalog.data
        fun modified(lanes:Map<String,Any?>):Map<String,Any?> = data+("devices" to rows(data["devices"]).map { device ->
            if(device["componentId"]!="soc-sd-host")device else device+("ports" to rows(device["ports"]).map {port ->
                if(port["id"]!="definition.soc-sd-host.transaction")port else port+("registers" to rows(port["registers"]).map {register ->
                    if(register["id"]!="reg.soc-sd-host.transaction.reset")register else register+("writeOffsetsByWidth" to lanes)
                })
            })
        })
        for (bad in listOf(mapOf("1" to listOf(1)), mapOf("1" to listOf(1),"2" to listOf(1)),
            mapOf("1" to listOf(2),"2" to listOf(0)), mapOf("1" to emptyList<Int>(),"2" to listOf(0)),
            mapOf("1" to listOf(1,1),"2" to listOf(0)), mapOf("1" to listOf(1),"2" to listOf(0),"4" to listOf(0)))) {
            assertFails {CatalogCompiler.validate(modified(bad))}
        }
    }
    @Test fun malformedAlternativesNeverRelaxTheOrdinaryOverlapValidator() {
        val data=catalog.data;val original=profileWindowRows(data).single {it["profile"]==profile}
        val variants=listOf(
            listOf(original,original),
            listOf(original+("profile" to "unknown")),
            listOf(original+("componentId" to "soc-gpio")),
            listOf(original+("replacePortIds" to listOf("absent"))),
            listOf(original+("replacePortIds" to listOf("soc-sd-host.control403e-0","soc-sd-host.control403e-0"))),
            listOf(original+("windows" to rows(original["windows"]).map{it+("base" to 0xfffc4000L)}))
        )
        variants.forEach {assertFails {CatalogCompiler.validate(data+("profileWindows" to it))}}
        val overlap=rows(data["windows"]).map{if(it["portId"]=="soc-sd-host.control403e-0")it+("base" to 0xfffc4006L)else it}
        assertFails {CatalogCompiler.validate(data+("windows" to overlap))}
    }
    @Test fun addressExtensionUsesTwoExactWriteOnlyWordsAndExclusiveNormalPorts() {
        val c=catalog
        val extended=mapOf("soc-sd-host" to "gfx100s-sd-host-address-extension-experimental-v7")
        val windows=c.windowsFor(extended)
        val selected=windows.filter{it.componentId=="soc-sd-host"}
        assertEquals(206,windows.size);assertEquals(22,selected.size)
        assertEquals(212,c.windows.size);assertEquals(204,c.windowsFor(profiles).size)
        assertEquals(c.windows.filter{it.componentId!="soc-sd-host"},windows.filter{it.componentId!="soc-sd-host"})
        val ports=selected.map{PortBinding(it.componentId,it.portId,it.base,it.size)}
        c.verifyPorts(ports,extended);c.verifyStockDeclaration(c.firmwareMetadata(extended))
        assertFalse(selected.any{it.portId in setOf("soc-sd-host.transaction-0","soc-sd-host.transaction-1")})
        for(slot in 0..1) {
            val high=0xfff4a888L+slot*12
            assertTrue(c.query("cpu-physical",high,4,"write",componentProfiles=extended).permitted)
            assertFalse(c.query("cpu-physical",high,4,"read",componentProfiles=extended).permitted)
            for(width in listOf(1,2,8))assertFalse(c.query("cpu-physical",high,width,"write",componentProfiles=extended).permitted)
            for(address in listOf(high-8,high-4,high+1,high+4))assertFalse(c.query("cpu-physical",address,4,"write",componentProfiles=extended).permitted)
            assertFalse(c.query("cpu-physical",high,4,"write",componentProfiles=profiles).permitted)
            assertFalse(c.query("cpu-physical",high,4,"write").permitted)
            assertTrue(c.query("cpu-physical",0xfffc6078L+slot*0x4000,4,"write",componentProfiles=extended).permitted)
            val bit=assertNotNull(c.query("cpu-physical",high,4,"write",componentProfiles=extended).register)
            assertEquals(listOf(4),bit.writeWidths);assertEquals(emptyList<Int>(),bit.readWidths)
        }
        assertFailsWith<IllegalArgumentException>{c.verifyPorts(ports.filter{it.portId!="soc-sd-host.address-high-0"},extended)}
        val v6=rows(c.firmwareMetadata(profiles)["devices"]).single{it["port"]=="soc-sd-host.transaction-0"}
        val mixed=c.firmwareMetadata(extended)+("devices" to (rows(c.firmwareMetadata(extended)["devices"])+v6))
        assertContains(assertFailsWith<IllegalArgumentException>{c.verifyStockDeclaration(mixed)}.message.orEmpty(),"Mixed profile layouts")
        val dma=c.windows.single{it.portId=="soc-dma.requests"}
        assertEquals(0xfff4a800L,dma.base);assertEquals(0x80L,dma.size)
    }

    @Test fun vendorPhyV8PromotesBootExercisedRegistersWhilePreservingV7AndStrictRefusal() {
        val c = catalog
        val v8 = mapOf("soc-sd-host" to "gfx100s-sd-host-vendor-phy-experimental-v8")
        val windows = c.windowsFor(v8)
        val selected = windows.filter { it.componentId == "soc-sd-host" }
        assertEquals(214, windows.size)
        assertEquals(30, selected.size)
        assertEquals(212, c.windows.size)
        assertEquals(206, c.windowsFor(mapOf("soc-sd-host" to "gfx100s-sd-host-address-extension-experimental-v7")).size)
        val ports = selected.map { PortBinding(it.componentId, it.portId, it.base, it.size) }
        c.verifyPorts(ports, v8)
        c.verifyStockDeclaration(c.firmwareMetadata(v8))
        for (slot in 0..1) {
            val baseA = 0xfffc4000L + slot * 0x4000
            val basePhy = 0xfffc6000L + slot * 0x4000
            // vendor41e0 & vendor41e4 promoted to exact aligned RW4 REGISTER under v8
            for (off in listOf(0x1e0L, 0x1e4L)) {
                val r = c.query("cpu-physical", baseA + off, 4, "read", componentProfiles = v8)
                assertEquals("REGISTER", r.classification); assertTrue(r.permitted)
                val w = c.query("cpu-physical", baseA + off, 4, "write", componentProfiles = v8)
                assertEquals("REGISTER", w.classification); assertTrue(w.permitted)
                assertFalse(c.query("cpu-physical", baseA + off, 2, "read", componentProfiles = v8).permitted)
                assertFalse(c.query("cpu-physical", baseA + off, 2, "write", componentProfiles = v8).permitted)
                // strict mode still refuses unexercised candidate addresses outside promoted ports
                assertFalse(c.query("cpu-physical", baseA + 0x100L, 2, "read", mode = "strict", componentProfiles = v8).permitted)
            }
            // phy6060 & phy607c promoted to exact RO4 REGISTER under v8
            for (off in listOf(0x60L, 0x7cL)) {
                val r = c.query("cpu-physical", basePhy + off, 4, "read", componentProfiles = v8)
                assertEquals("REGISTER", r.classification); assertTrue(r.permitted)
                assertFalse(c.query("cpu-physical", basePhy + off, 4, "write", componentProfiles = v8).permitted)
                assertFalse(c.query("cpu-physical", basePhy + off, 2, "read", componentProfiles = v8).permitted)
            }
            // phy6094 promoted to exact RW4 REGISTER under v8
            assertEquals("REGISTER", c.query("cpu-physical", basePhy + 0x94L, 4, "read", componentProfiles = v8).classification)
            assertTrue(c.query("cpu-physical", basePhy + 0x94L, 4, "write", componentProfiles = v8).permitted)
            assertFalse(c.query("cpu-physical", basePhy + 0x94L, 2, "write", componentProfiles = v8).permitted)
            // mixed6078 write4 still allowed under v8; address-high-v8 WO4
            assertTrue(c.query("cpu-physical", basePhy + 0x78L, 4, "write", componentProfiles = v8).permitted)
            assertTrue(c.query("cpu-physical", 0xfff4a888L + slot * 12, 4, "write", componentProfiles = v8).permitted)
            assertFalse(c.query("cpu-physical", 0xfff4a888L + slot * 12, 4, "read", componentProfiles = v8).permitted)
            // Unexercised UHS-II / PHY trim gaps remain refused in strict mode
            assertFalse(c.query("cpu-physical", basePhy + 0x20L, 4, "read", mode = "strict", componentProfiles = v8).permitted)
            assertFalse(c.query("cpu-physical", basePhy + 0x2c2L, 2, "read", mode = "strict", componentProfiles = v8).permitted)
        }
    }

    @Test fun uhsPhyV9PromotesAllVerifiedUhsAndPhyTrimRegistersWhilePreservingStrictGapRefusal() {
        val c = catalog
        val v9 = mapOf("soc-sd-host" to "gfx100s-sd-host-uhs-phy-experimental-v9")
        val windows = c.windowsFor(v9)
        val selected = windows.filter { it.componentId == "soc-sd-host" }
        assertEquals(234, windows.size)
        assertEquals(50, selected.size)
        assertEquals(212, c.windows.size)
        assertEquals(214, c.windowsFor(mapOf("soc-sd-host" to "gfx100s-sd-host-vendor-phy-experimental-v8")).size)
        val ports = selected.map { PortBinding(it.componentId, it.portId, it.base, it.size) }
        c.verifyPorts(ports, v9)
        c.verifyStockDeclaration(c.firmwareMetadata(v9))
        for (slot in 0..1) {
            val baseA = 0xfffc4000L + slot * 0x4000
            val basePhy = 0xfffc6000L + slot * 0x4000
            // UHS-I Bank A RW2: +0x100, +0x108, +0x10A; RO2: +0x10C, +0x10E; RW4: +0x114, +0x124
            for (off in listOf(0x100L, 0x108L, 0x10aL)) {
                assertTrue(c.query("cpu-physical", baseA + off, 2, "read", componentProfiles = v9).permitted)
                assertTrue(c.query("cpu-physical", baseA + off, 2, "write", componentProfiles = v9).permitted)
                assertFalse(c.query("cpu-physical", baseA + off, 4, "read", componentProfiles = v9).permitted)
            }
            for (off in listOf(0x10cL, 0x10eL)) {
                assertTrue(c.query("cpu-physical", baseA + off, 2, "read", componentProfiles = v9).permitted)
                assertFalse(c.query("cpu-physical", baseA + off, 2, "write", componentProfiles = v9).permitted)
            }
            for (off in listOf(0x114L, 0x124L)) {
                assertTrue(c.query("cpu-physical", baseA + off, 4, "read", componentProfiles = v9).permitted)
                assertTrue(c.query("cpu-physical", baseA + off, 4, "write", componentProfiles = v9).permitted)
                assertFalse(c.query("cpu-physical", baseA + off, 2, "write", componentProfiles = v9).permitted)
            }
            // PHY analog trim RW4: +0x20, +0x24
            for (off in listOf(0x20L, 0x24L)) {
                assertTrue(c.query("cpu-physical", basePhy + off, 4, "read", componentProfiles = v9).permitted)
                assertTrue(c.query("cpu-physical", basePhy + off, 4, "write", componentProfiles = v9).permitted)
            }
            // UHS-II Bank B: DMA +0x258/+0x25C RW4, +0x280 RW2, +0x284..+0x290 & +0x29C WO4, +0x2C0/+0x2C2 RW2, +0x2C4/+0x2C8 RW4, +0x2CC WO4
            for (off in listOf(0x258L, 0x25cL, 0x2c4L, 0x2c8L)) {
                assertTrue(c.query("cpu-physical", basePhy + off, 4, "read", componentProfiles = v9).permitted)
                assertTrue(c.query("cpu-physical", basePhy + off, 4, "write", componentProfiles = v9).permitted)
            }
            for (off in listOf(0x280L, 0x2c0L, 0x2c2L)) {
                assertTrue(c.query("cpu-physical", basePhy + off, 2, "read", componentProfiles = v9).permitted)
                assertTrue(c.query("cpu-physical", basePhy + off, 2, "write", componentProfiles = v9).permitted)
            }
            for (off in listOf(0x284L, 0x288L, 0x28cL, 0x290L, 0x29cL, 0x2ccL)) {
                assertTrue(c.query("cpu-physical", basePhy + off, 4, "write", componentProfiles = v9).permitted)
                assertFalse(c.query("cpu-physical", basePhy + off, 4, "read", componentProfiles = v9).permitted)
            }
            // Unmodeled gap addresses inside the 4 candidates remain strictly refused
            assertFalse(c.query("cpu-physical", baseA + 0x102L, 2, "read", mode = "strict", componentProfiles = v9).permitted)
            assertFalse(c.query("cpu-physical", baseA + 0x110L, 4, "read", mode = "strict", componentProfiles = v9).permitted)
            assertFalse(c.query("cpu-physical", basePhy + 0x294L, 4, "read", mode = "strict", componentProfiles = v9).permitted)
            assertFalse(c.query("cpu-physical", basePhy + 0x2d0L, 4, "read", mode = "strict", componentProfiles = v9).permitted)
        }
    }

}

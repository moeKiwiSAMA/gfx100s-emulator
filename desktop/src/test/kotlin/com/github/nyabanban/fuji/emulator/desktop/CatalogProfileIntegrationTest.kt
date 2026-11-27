package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogIO
import com.github.nyabanban.fuji.emulator.catalog.PortBinding
import com.github.nyabanban.fuji.emulator.runtime.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.*

class CatalogProfileIntegrationTest {
    private val root=Path.of(System.getProperty("componentProjectRoot"))
    private val v4="gfx100s-sd-host-init-registers-experimental-v4"
    private val v5="gfx100s-sd-host-phy-word-retention-experimental-v5"
    private fun input(profile:String):Map<String,Any> = mapOf("schema" to 1,"profile" to profile,
        "initialRegisters" to (0..1).associate { it.toString() to mapOf("word80" to 0L,"control623e" to 0L,"mixed6078" to 0xa55a0005L,"control403e" to 0L,"word60ec" to 0L) },
        "clocks" to listOf("0:0","0:1","1:0","1:1").associateWith { mapOf("initialWord" to 0L,"bit1Samples" to listOf(1,0)) },
        "initWords" to (0..1).associate { it.toString() to listOf("capability6244","present4024","present6224","signal6238","block4006","host6228","host4028").associateWith { 0L } })
    private fun home(profile:String,config:Map<String,Any> = emptyMap()):Path {
        val home=Files.createTempDirectory("catalog-profile")
        Files.createDirectories(home.resolve("compoments/soc-sd-host"))
        Files.copy(root.resolve("build/emulator/compoments/soc-sd-host-0.3.1.jar"),home.resolve("compoments/soc-sd-host-0.3.1.jar"))
        Formats.atomicYaml(home.resolve("compoments/soc-sd-host/soc-sd-host.yaml"),mapOf("profile" to profile,"inputFile" to "input.yaml")+config)
        Formats.atomicYaml(home.resolve("compoments/soc-sd-host/input.yaml"),input(profile))
        return home
    }
    private fun <T> withHost(home:Path,catalog:AddressCatalog=AddressCatalog.bundled(),action:(OwnerLoop,ComponentHost)->T):T = OwnerLoop().use { owner ->
        val host=ComponentHost(owner,home.resolve("compoments"),catalog)
        try { owner.submit { host.load() }.join();action(owner,host) }
        finally {owner.submit {host.close()}.join()}
    }

    @Test fun allLegacyProfilesLoadCurrentJarWithoutRegisteringV6Banks() {
        val profiles=listOf("gfx100s-sd-host-word80-retention-experimental-v1","gfx100s-sd-host-controls-retention-experimental-v2","gfx100s-sd-host-clock-samples-experimental-v3",v4,v5)
        for((index,profile) in profiles.withIndex()) {
            val home=home(profile)
            try {
                val full=input(profile)
                val legacy=when(index) {
                    0->mapOf("schema" to 1,"profile" to profile,"initialWords" to mapOf("0" to 0,"1" to 0))
                    1->full.filterKeys {it in setOf("schema","profile","initialRegisters")}
                    2->full-"initWords"
                    else->full
                }
                Formats.atomicYaml(home.resolve("compoments/soc-sd-host/input.yaml"),legacy)
                withHost(home) {owner,host->owner.submit {
                    val names=host.components().single().component.additionalMmioPorts()
                    assertEquals(listOf(2,10,14,28,28)[index],names.size)
                    for(slot in 0..1)assertNull(host.ports.find("soc-sd-host.transaction-$slot",MmioDevice::class.java))
                    val catalog=AddressCatalog.bundled();val windows=catalog.windows.filter {it.portId in names}
                    val runtime=CatalogRuntime(catalog,"strict",home,emptyMap())
                    runtime.validateFirmware(catalog.legacyFirmwareMetadata())
                    runtime.bindPorts(windows.map{PortBinding(it.componentId,it.portId,it.base,it.size)},catalog.defaultMemoryProfile,catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),mapOf("soc-sd-host" to profile))
                    for(name in names)assertNotNull(host.ports.find(name,MmioDevice::class.java))
                }.join()}
            } finally {home.toFile().deleteRecursively()}
        }
    }

    @Test fun realJarOwnerProfilesDriveHttpAddressIdPropertyAndKnownRefusalWithoutFallback() {
        for(profile in listOf(v4,v5)) {
            val home=home(profile)
            try { withHost(home) { owner,host ->
                val catalog=AddressCatalog.bundled();val runtime=CatalogRuntime(catalog,"discovery",home,emptyMap())
                assertFails { configuredComponentProfiles(host,setOf("soc-sd-host")) }
                owner.submit {
                    val component=host.components().single()
                    val names=component.component.additionalMmioPorts().toSet()
                    val windows=catalog.windows.filter {it.componentId=="soc-sd-host" && it.portId in names}
                    assertEquals(28,windows.size)
                    val profiles=configuredComponentProfiles(host,windows.map {it.componentId}.toSet())
                    assertEquals(mapOf("soc-sd-host" to profile),profiles)
                    assertEquals(emptyMap(),configuredComponentProfiles(host,emptySet()))
                    assertFails {configuredComponentProfiles(host,setOf("absent"))}
                    @Suppress("UNCHECKED_CAST") val mutable=profiles as MutableMap<String,String>
                    assertFails {mutable["soc-sd-host"]="forged"}
                    runtime.bindPorts(windows.map {PortBinding(it.componentId,it.portId,it.base,it.size)},catalog.defaultMemoryProfile,catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),profiles)
                }.join()
                val before=host.snapshot().join()
                ControlServer(host,0,addressQuery={ parameters -> owner.submit {runtime.query(parameters)}.join() }).use { server ->
                    HttpClient.newHttpClient().use { client ->
                        fun query(suffix:String)=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.address.port+"/api/v1/catalog?"+suffix)).GET().build(),HttpResponse.BodyHandlers.ofString())
                        for(address in listOf("0xFFFC6078","0xFFFCA078")) {
                            val response=query("address="+address+"&width=4&direction=write")
                            assertEquals(200,response.statusCode())
                            val row=Formats.json.readTree(response.body())
                            assertEquals(profile,row["componentProfile"].asText());assertEquals(profile==v5,row["permitted"].asBoolean())
                            assertEquals(listOf(2),row["declaredWriteWidths"].map {it.asInt()})
                            assertEquals(if(profile==v5)listOf(2,4)else listOf(2),row["effectiveWriteWidths"].map {it.asInt()})
                            val id=row["registerId"].asText()
                            val entity=Formats.json.readTree(query("id="+id).body())["entity"]
                            assertEquals(profile,entity["componentProfile"].asText());assertEquals(row["effectiveWriteWidths"],entity["effectiveWriteWidths"])
                            val property=Formats.json.readTree(query("id="+id+"&property=effectiveWriteWidths").body())
                            assertEquals(row["effectiveWriteWidths"],property["value"])
                            assertEquals(false,row["deviceReadPerformed"].asBoolean());assertEquals(false,row["guestStateChanged"].asBoolean())
                        }
                        assertEquals(400,query("address=0xFFFC6078&profile="+v5).statusCode())
                        assertEquals(400,query("address=0xFFFC6078&componentProfile="+v5).statusCode())
                    }
                }
                assertEquals(before,host.snapshot().join())
                owner.submit {
                    val context=object:DiscoveryContext {override fun core()=0;override fun tick()=host.tick;override fun pc()=0x5af98L;override fun lr()=0x11f1d88L}
                    val bus=checkNotNull(runtime.createBus(context));val canary=bus.snapshot()["regions"]
                    val windows=catalog.windows.filter {it.portId.startsWith("soc-sd-host.mixed6078-")}
                    val router=MmioRouter(windows.map {MmioWindow(it.id,it.base,it.size,checkNotNull(host.ports.find(it.portId,MmioDevice::class.java)))},bus)
                    if(profile==v4)assertFails {router.write(windows.first().base,4,0)} else router.write(windows.first().base,4,0xdeadbeefL)
                    val state=host.components().single().component.state()
                    assertFails {router.write(windows.last().base,1,0)}
                    assertEquals(state,host.components().single().component.state())
                    assertEquals(canary,bus.snapshot()["regions"])
                    val rows=runtime.observation(bus)["accesses"] as List<*>
                    assertEquals(if(profile==v4)2 else 1,rows.size)
                    for(raw in rows) {val row=raw as Map<*,*>;assertEquals(profile,row["componentProfile"]);assertEquals("known-device-refusal",row["reason"]);assertEquals(false,row["completed"])}
                }.join()
            } } finally {home.toFile().deleteRecursively()}
        }
    }

    @Test fun unknownFieldsAndUnimplementedProfilesAreRejectedByActualOwnerConfiguration() {
        for(config in listOf(mapOf("profile" to "unimplemented-v99"),mapOf("unknownProfileField" to true))) {
            val home=home(v5,config)
            try { OwnerLoop().use {owner ->
                val host=ComponentHost(owner,home.resolve("compoments"),AddressCatalog.bundled())
                try {
                    val failure=assertFails {owner.submit {host.load()}.join()}
                    assertTrue(generateSequence(failure as Throwable?) {it.cause}.any {it.message?.contains(if("profile" in config)"invalid choice" else "unknown setting")==true})
                    owner.submit {assertTrue(host.components().isEmpty());assertNull(host.ports.find("soc-sd-host.mixed6078-0",MmioDevice::class.java))}.join()
                } finally {owner.submit {host.close()}.join()}
            } } finally {home.toFile().deleteRecursively()}
        }
    }

    @Test fun copiedMatchingCatalogDescriptorCannotInventFactoryProfileSupport() {
        val original=AddressCatalog.bundled()
        val data=Formats.map(Formats.json.readValue(Formats.jsonBytes(original.data),Map::class.java))
        @Suppress("UNCHECKED_CAST") val devices=data["devices"] as List<MutableMap<String,Any?>>
        val sd=devices.single {it["componentId"]=="soc-sd-host"}
        sd["profiles"]=(sd["profiles"] as List<*>)+"unimplemented-v99"
        val catalog=CatalogIO.readCompiled(CatalogIO.canonical(mapOf("schema" to 1,"payloadSha256" to CatalogIO.sha256(CatalogIO.canonical(data)),"data" to data)))
        val home=home(v4)
        try {
            val target=home.resolve("compoments/soc-sd-host-0.3.1.jar")
            JarFile(root.resolve("build/emulator/compoments/soc-sd-host-0.3.1.jar").toFile()).use { source -> JarOutputStream(Files.newOutputStream(target)).use { output ->
                source.entries().asSequence().forEach {entry ->
                    output.putNextEntry(JarEntry(entry.name))
                    if(entry.name=="META-INF/fuji/catalog.json")output.write(Formats.jsonBytes(catalog.componentDescriptor("soc-sd-host")))
                    else source.getInputStream(entry).use {it.copyTo(output)}
                    output.closeEntry()
                }
            } }
            OwnerLoop().use {owner ->
                val host=ComponentHost(owner,home.resolve("compoments"),catalog)
                try {
                    val failure=assertFails {owner.submit {host.load()}.join()}
                    assertTrue(generateSequence(failure as Throwable?) {it.cause}.any {it.message?.contains("Component profile contract differs")==true})
                    owner.submit {assertTrue(host.components().isEmpty());assertNull(host.ports.find("soc-sd-host.mixed6078-0",MmioDevice::class.java))}.join()
                } finally {owner.submit {host.close()}.join()}
            }
        } finally {home.toFile().deleteRecursively()}
    }
}

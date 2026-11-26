package com.github.nyabanban.fuji.emulator.catalog

/** An explicit TEST layout has no vendor Flash/DAT mapping or discovery envelope. */
class CatalogAddressView internal constructor(val windows:List<CatalogWindow>,val memoryRegions:List<Map<String,Any?>>,val confidence:String,val firmwareId:String,val firmwareSha256:String)
class PublicTestLayout internal constructor(
    val firmwareId:String,val firmwareSha256:String,val memoryProfiles:Map<String,List<Map<String,Any?>>>,
    val ports:List<PortBinding>,private val windows:List<CatalogWindow>,private val boot:Interval
) {
    fun view(memoryProfile:String):CatalogAddressView {
        val regions=memoryProfiles[memoryProfile]?:error("TEST RAM profile not declared")
        require(regions.any {it.long("base")<=boot.base&&it.long("endExclusive")>=boot.endExclusive}) {"Selected TEST RAM must contain declared code"}
        // Preserve permissions, including deliberate native permission-fault fixtures.
        return CatalogAddressView(windows,regions,"TEST",firmwareId,firmwareSha256)
    }
}

/** Explicit Boot ROM cold-start experimental layout (HYPOTHESIS); keeps stock 2.13 RAM-loader catalog pinned. */
class BootRomLayout internal constructor(
    val firmwareId:String,val firmwareSha256:String,val bootRomAddress:Long,val bootRomSize:Long,val bootRomSha256:String,
    val memoryProfiles:Map<String,List<Map<String,Any?>>>,val ports:List<PortBinding>,private val windows:List<CatalogWindow>
) {
    fun view(memoryProfile:String):CatalogAddressView {
        val regions=memoryProfiles[memoryProfile]?:error("Boot ROM memory profile not declared: "+memoryProfile)
        return CatalogAddressView(windows,regions,"HYPOTHESIS",firmwareId,firmwareSha256)
    }
    fun verifyMemory(profile:String,actual:List<Map<String,Any?>>) {
        val expected=memoryProfiles[profile]?:error("Unknown Boot ROM RAM profile: "+profile)
        require(FirmwareDeclarations.normalizeRegions(actual)==FirmwareDeclarations.normalizeRegions(expected)) {"Actual RAM differs from Boot ROM experiment profile"}
    }
}

/** Public test inputs are checked structurally, not registered by case name or fixed hashes. */
object FirmwareDeclarations {
    fun normalizeRegions(regions:List<Map<String,Any?>>):List<Map<String,Any?>> = immutableList(regions.map { r ->
        val base=valueLong(r["address"]?:r["base"]);val size=r.long("size")
        java.util.Collections.unmodifiableMap(linkedMapOf<String,Any?>("id" to r.text("id"),"address" to base,"base" to base,"size" to size,
            "endExclusive" to Math.addExact(base,size),"space" to "cpu-physical","permissions" to r.long("permissions")))
    })
    fun verifyStockMemory(catalog:AddressCatalog,profile:String,actual:List<Map<String,Any?>>) {
        require(normalizeRegions(actual)==normalizeRegions(catalog.memoryProfiles[profile]?:error("Unknown vendor RAM profile"))) {"Actual RAM differs from dictionary profile"}
    }
    fun verifyStockDeclaration(catalog:AddressCatalog,raw:Map<String,Any?>) {
        val expected=catalog.legacyFirmwareMetadata()
        for(key in listOf("schema","size","entry","cpsr","loaderMemoryBytes"))require(raw.long(key)==expected.long(key)){"Vendor metadata numeric field differs: "+key}
        for(key in listOf("id","version","sha256","bootMode"))require(raw[key]==expected[key]){"Vendor metadata identity differs: "+key}
        fun section(row:Map<String,Any?>):Map<String,Any?> {require(row.keys==setOf("name","address","size","offset","encoding","sha256"));return row+listOf("address","size","offset").associateWith{row.long(it)}}
        require(rows(raw["sections"]).map(::section)==rows(expected["sections"]).map(::section)){"Vendor section mapping differs from dictionary"}
        val ports=rows(raw["devices"]).map(::binding)
        catalog.verifyPorts(ports,catalog.declarationProfiles(raw))
    }
    private fun binding(row:Map<String,Any?>):PortBinding {
        require(row.keys - "optional" == setOf("id","port","address","size")&&("optional" !in row||row["optional"] is Boolean))
        return PortBinding(row.text("id"),row.text("port"),row.long("address"),row.long("size"))
    }
    fun publicTest(catalog:AddressCatalog,raw:Map<String,Any?>,mode:String):PublicTestLayout {
        require(mode=="strict"&&raw["version"]=="TEST"&&raw["bootMode"]=="public-synthetic-guest") {"Public TEST inputs require explicit strict mode"}
        require(raw.text("id").isNotBlank()&&raw.text("id").length<=128)
        val firmwareSha=raw.text("sha256");require(firmwareSha.matches(Regex("[a-f0-9]{64}")))
        require(raw.long("schema")==1L&&raw.long("size") in 1..(128L*1024*1024))
        require(raw.long("entry") in 0 until ARM32_LIMIT&&raw.long("cpsr") in 0 until ARM32_LIMIT)
        val section=rows(raw["sections"]).single();require(section["name"]=="boot"&&section["encoding"]=="raw")
        require(section.text("sha256").matches(Regex("[a-f0-9]{64}")))
        val sectionSize=section.long("size");require(sectionSize in 1..(64L*1024*1024))
        val boot=Interval(section.long("address"),Math.addExact(section.long("address"),sectionSize))
        require(section.long("offset")>=0&&Math.addExact(section.long("offset"),sectionSize)<=raw.long("size"))
        val ports=rows(raw["devices"]).map(::binding);require(ports.map {it.portId}.distinct().size==ports.size)
        val windows=ports.map {p->
            val known=catalog.allWindows.singleOrNull {it.portId==p.portId}?:error("TEST port absent from dictionary: "+p.portId)
            require(p.componentId==known.componentId&&p.size==known.size&&p.base%4==0L) {"TEST port contract differs"}
            Interval(p.base,Math.addExact(p.base,p.size))
            known.copy(base=p.base,data=java.util.Collections.unmodifiableMap(known.data+mapOf("base" to p.base,"evidenceClass" to "TEST")))
        }.sortedBy {it.base}
        windows.zipWithNext().forEach {(a,b)->require(a.endExclusive<=b.base) {"TEST ports overlap"}}
        val loader=raw.long("loaderMemoryBytes");require(loader in 4096..(64L*1024*1024)&&loader%4096==0L)
        val declared=raw["memoryProfiles"]?.let(::obj)?:mapOf("loader-low128-experimental-v1" to listOf(mapOf("id" to "loader","address" to 0,"size" to loader,"permissions" to 7)))
        require(declared.isNotEmpty())
        val memory=declared.mapValues {(name,value)->
            require(name.safeId());val regions=normalizeRegions(rows(value));require(regions.size in 1..32)
            require(regions.map {it["id"]}.distinct().size==regions.size)
            val spans=regions.map {r->
                require(r.text("id").safeId()&&r.long("permissions") in 1..7)
                require(r.long("base")%4096==0L&&r.long("size")%4096==0L)
                Interval(r.long("base"),r.long("endExclusive"))
            }
            require(regions.fold(0L) {sum,r->Math.addExact(sum,r.long("size"))}<=64L*1024*1024)
            spans.sortedBy {it.base}.zipWithNext().forEach {(a,b)->require(a.endExclusive<=b.base) {"TEST RAM overlaps"}}
            require(spans.none {ram->windows.any {ram.overlaps(it.interval)}}) {"TEST RAM overlaps MMIO"}
            immutableList(regions.map {java.util.Collections.unmodifiableMap(it+mapOf("evidenceClass" to "TEST","backingSource" to "explicit public synthetic input"))})
        }
        return PublicTestLayout(raw.text("id"),firmwareSha,java.util.Collections.unmodifiableMap(memory),immutableList(ports),immutableList(windows),boot)
    }
    private const val BOOT_ROM_EXPERIMENT = "boot-rom-experiment"
    private const val BOOT_ROM_COLD_START_PROFILE = "boot-rom-cold-start-experimental-v1"
    private const val BOOT_ROM_ADDRESS = 0xfff00000L
    private const val BOOT_ROM_SIZE = 0x8000L
    private const val BOOT_ROM_SHA256 = "2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4"
    private const val BOOT_STRAP_PORT = "soc-system-control.boot-strap"
    private const val BOOT_STRAP_ADDRESS = 0xfff4a000L

    fun validateBootRomExperiment(catalog:AddressCatalog,raw:Map<String,Any?>):BootRomLayout {
        require(raw["bootMode"]==BOOT_ROM_EXPERIMENT) {"Boot ROM layout requires boot-rom-experiment bootMode"}
        val expected=catalog.legacyFirmwareMetadata()
        for(key in listOf("schema","size"))require(raw.long(key)==expected.long(key)){"Boot ROM metadata numeric field differs: "+key}
        for(key in listOf("id","version","sha256"))require(raw[key]==expected[key]){"Boot ROM metadata identity differs: "+key}
        require(raw.long("cpsr")==0xd3L&&raw.long("entry") in listOf(0L,BOOT_ROM_ADDRESS)&&raw.long("loaderMemoryBytes") in listOf(0L,0x20000L)) {
            "Boot ROM entry/CPSR/loaderMemoryBytes invalid"
        }
        val rom=obj(raw["bootRom"])
        require(rom.keys==setOf("address","size","sha256")) {"Boot ROM block keys invalid"}
        val romAddress=rom.long("address");val romSize=rom.long("size");val romSha=rom.text("sha256")
        require(romAddress==BOOT_ROM_ADDRESS&&romSize==BOOT_ROM_SIZE&&romSha==BOOT_ROM_SHA256) {"Boot ROM specification differs"}
        fun section(row:Map<String,Any?>):Map<String,Any?> {require(row.keys==setOf("name","address","size","offset","encoding","sha256"));return row+listOf("address","size","offset").associateWith{row.long(it)}}
        require(rows(raw["sections"]).map(::section)==rows(expected["sections"]).map(::section)){"Boot ROM section mapping differs from dictionary"}
        val rawDevices=rows(raw["devices"])
        val ports=rawDevices.map(::binding);require(ports.map {it.portId}.distinct().size==ports.size) {"Duplicate Boot ROM port"}
        val strapBinding=ports.singleOrNull {it.portId==BOOT_STRAP_PORT}
        if(strapBinding!=null) {
            val strapRow=rawDevices.single {it.text("port")==BOOT_STRAP_PORT}
            require(strapBinding.componentId=="soc-system-control"&&strapBinding.base==BOOT_STRAP_ADDRESS&&strapBinding.size==4L&&strapRow["optional"]==true) {
                "Invalid boot-strap port contract"
            }
        }
        val catalogDevices=rawDevices.filterNot {it.text("port")==BOOT_STRAP_PORT}
        val catalogPorts=ports.filterNot {it.portId==BOOT_STRAP_PORT}
        val profiles=catalog.declarationProfiles(raw+("devices" to catalogDevices))
        catalog.verifyPorts(catalogPorts,profiles)
        val knownWindows=catalog.windowsFor(profiles).associateBy {it.portId}
        val windows=(catalogPorts.map {knownWindows.getValue(it.portId)}+listOfNotNull(strapBinding?.let {
            val regData=mapOf<String,Any?>("id" to "reg.soc-system-control.boot-strap.o0","offset" to 0L,"size" to 4L,"count" to 1L,"stride" to 4L,"alignment" to 4L,"readWidths" to listOf(4),"writeWidths" to emptyList<Int>(),"status" to "EXPERIMENTAL_OPT_IN","behaviorId" to "kotlin:soc-system-control","fields" to listOf(mapOf("id" to "field.soc-system-control.boot-strap.word","bitOffset" to 0L,"bitWidth" to 32L)))
            val reg=CatalogRegister("reg.soc-system-control.boot-strap.o0",0L,4,1,4L,listOf(4),emptyList(),4,"EXPERIMENTAL_OPT_IN","kotlin:soc-system-control",immutableList(listOf(CatalogField("field.soc-system-control.boot-strap.word",0,32,obj(rows(regData["fields"]).single())))),regData)
            CatalogWindow("gfx100s.soc-system-control.boot-strap","soc-system-control",BOOT_STRAP_PORT,"instance.gfx100s.soc-system-control","definition.soc-system-control.boot-strap","cpu-physical",BOOT_STRAP_ADDRESS,4L,true,"MMIO",immutableList(listOf(reg)),mapOf("id" to "gfx100s.soc-system-control.boot-strap","componentId" to "soc-system-control","portId" to BOOT_STRAP_PORT,"base" to BOOT_STRAP_ADDRESS,"size" to 4L,"optional" to true,"kind" to "MMIO","evidenceClass" to "HYPOTHESIS"))
        })).sortedBy {it.base}
        windows.zipWithNext().forEach {(a,b)->require(a.endExclusive<=b.base) {"Boot ROM device windows overlap"}}
        val declared=obj(raw["memoryProfiles"]);require(BOOT_ROM_COLD_START_PROFILE in declared) {"Boot ROM cold-start memory profile missing"}
        val memory=declared.mapValues {(name,value)->
            require(name.safeId());val regions=normalizeRegions(rows(value));require(regions.size in 1..64)
            require(regions.map {it["id"]}.distinct().size==regions.size)
            val spans=regions.map {r->
                require(r.text("id").safeId()&&r.long("permissions") in 1..7)
                require(r.long("base")%4096==0L&&r.long("size")%4096==0L)
                r.text("id") to Interval(r.long("base"),r.long("endExclusive"))
            }
            require(regions.fold(0L) {sum,r->Math.addExact(sum,r.long("size"))}<=128L*1024*1024)
            spans.map { it.second }.sortedBy {it.base}.zipWithNext().forEach {(a,b)->require(a.endExclusive<=b.base) {"Boot ROM RAM overlaps"}}
            require(spans.none {(ramId, ram)->windows.any {w->
                val sharedBootSramIsp = ramId == "boot-rom-stage1-sram" &&
                    ram.base == 0xfff10000L && ram.endExclusive == 0xfff12000L &&
                    w.componentId == "soc-isp" &&
                    (w.portId.startsWith("soc-isp.result-surface") || w.portId.startsWith("soc-isp.stats-head-0"))
                !sharedBootSramIsp && ram.overlaps(w.interval)
            }}) {"Boot ROM RAM overlaps MMIO"}
            require(regions.any {it.long("base")<=romAddress&&it.long("endExclusive")>=romAddress+romSize&&(it.long("permissions") and 5L)==5L}) {
                "Boot ROM profile must map [0xfff00000, 0xfff08000) RX"
            }
            immutableList(regions.map {java.util.Collections.unmodifiableMap(it+mapOf("evidenceClass" to "HYPOTHESIS","backingSource" to "explicit boot-rom-experiment layout"))})
        }
        return BootRomLayout(raw.text("id"),raw.text("sha256"),romAddress,romSize,romSha,java.util.Collections.unmodifiableMap(memory),immutableList(ports),immutableList(windows))
    }
}

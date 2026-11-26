package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Files
import java.nio.file.Path

/** Immutable dictionary. Queries never call a component or read guest memory. */
class AddressCatalog internal constructor(input: Map<String, Any?>, val artifactSha256:String = CatalogIO.sha256(CatalogIO.canonical(input))) {
    val data: Map<String, Any?> = freezeMap(input)
    val version = data.text("version")
    val firmwareId = obj(data["firmware"]).text("id")
    val firmwareSha256 = obj(data["firmware"]).text("sha256")
    val defaultMemoryProfile = obj(data["firmware"]).text("defaultMemoryProfile")
    private val portRegisters: Map<String,List<CatalogRegister>> = rows(data["devices"]).flatMap { device -> rows(device["ports"]).map { port ->
        port.text("id") to immutableList(rows(port["registers"]).map(::register))
    } }.toMap()
    private fun window(w:Map<String,Any?>):CatalogWindow =
        CatalogWindow(w.text("id"),w.text("componentId"),w.text("portId"),w.text("deviceId"),w.text("definitionId"),
            w.text("space"),w.long("base"),w.long("size"),w["optional"] as Boolean,w.text("kind"),
            portRegisters.getValue(w.text("definitionId")),w)
    val windows: List<CatalogWindow> = immutableList(rows(data["windows"]).map(::window).sortedBy { it.base })
    val allWindows: List<CatalogWindow> = immutableList(windows + profileWindowRows(data).flatMap { rows(it["windows"]).map(::window) })
    private val alternativeLayouts=profileWindowRows(data).associate { variant ->
        variant.text("id") to immutableList(selectedWindowRows(data,mapOf(variant.text("componentId") to variant.text("profile"))).map(::window).sortedBy {it.base})
    }
    fun windowsFor(componentProfiles:Map<String,String>):List<CatalogWindow> {
        val selected=profileWindowRows(data).filter {componentProfiles[it.text("componentId")]==it.text("profile")}
        if(selected.isEmpty())return windows
        if(selected.size==1)return alternativeLayouts.getValue(selected.single().text("id"))
        return immutableList(selectedWindowRows(data,componentProfiles).map(::window).sortedBy {it.base})
    }
    val candidates = immutableList(rows(data["candidates"]).map { c -> CandidateRegion(c.text("id"),c.text("deviceId"),c.text("space"),
        c.long("base"),c.long("endExclusive"),c.text("policy"),c.ints("widths"),Math.toIntExact(c.long("alignment")),c.text("byteOrder"),
        c["initialFill"]?.let { Math.toIntExact(valueLong(it)) },immutableList(rows(c["prohibited"]).map { Interval(it.long("base"),it.long("endExclusive")) }),c) })
    val memoryProfiles = java.util.Collections.unmodifiableMap(obj(data["memoryProfiles"]).mapValues { (_,v) -> immutableList(rows(v)) })
    val componentIds = immutableList(rows(data["devices"]).map { it.text("componentId") })
    val diagnosticProfiles = immutableList(rows(data["diagnosticProfiles"] ?: emptyList<Any>()).map(::CatalogRetainedDiagnostic))
    val singleReadDiagnosticProfiles = immutableList(rows(data["singleReadDiagnosticProfiles"] ?: emptyList<Any>()).map(::CatalogSingleReadDiagnostic))
    private val logicalDefinitions=protocolDefinitions(data).associateBy {it.second.text("id")}
    val logicalSpaces:List<CatalogLogicalSpace> = immutableList(rows(data["logicalSpaces"] ?: emptyList<Any>()).map {logicalSpace(it,logicalDefinitions.getValue(it.text("definitionId")))})
    val protocolCandidateSpaces:List<CatalogProtocolCandidateSpace> = immutableList(protocolCandidateDefinitions(data).map(::protocolCandidateSpace))
    private val candidateEntitySpaces=buildMap<String,CatalogProtocolCandidateSpace> {
        protocolCandidateSpaces.forEach {s->put(s.id,s);s.requests.forEach {r->put(r.id,s);r.fields.forEach {put(it.id,s)}}}
    }
    private val candidateRequestById=protocolCandidateSpaces.flatMap {it.requests}.associateBy {it.id}
    private val candidateFieldRequests=candidateRequestById.values.flatMap {r->r.fields.map {it.id to r}}.toMap()
    private val logicalRegisterById=logicalSpaces.flatMap {it.registers}.associateBy {it.id}
    private val logicalFieldRegisters=logicalRegisterById.values.flatMap {r->r.fields.map {it.id to r}}.toMap()
    private val logicalEntitySpaces=buildMap<String,CatalogLogicalSpace> {
        logicalSpaces.forEach {s->put(s.id,s);put(s.instanceId,s);s.registers.forEach {r->put(r.id,s);r.fields.forEach {put(it.id,s)}}}
    }
    private val byId: Map<String, Map<String, Any?>> = buildMap {
        fun add(row: Map<String, Any?>) { val id=row.text("id"); val old=put(id,row); require(old==null||old==row) { "conflicting ID: " + id } }
        rows(data["spaces"]).forEach(::add)
        rows(data["devices"]).forEach { device ->
            add(device)
            rows(device["ports"]).forEach { port -> add(port); rows(port["registers"]).forEach { r -> add(r); rows(r["fields"]).forEach(::add) } }
        }
        logicalDefinitions.values.forEach {(_,s)->add(s);rows(s["registers"]).forEach {r->add(r);rows(r["fields"]).forEach(::add)}}
        protocolCandidateSpaces.forEach {s->add(s.data);s.requests.forEach {r->add(r.data);r.fields.forEach {add(it.data)}}}
        rows(data["logicalSpaces"] ?: emptyList<Any>()).forEach(::add)
        rows(data["instances"]?:emptyList<Any>()).forEach(::add)
        rows(data["mirrors"]?:emptyList<Any>()).forEach(::add)
        allWindows.forEach { add(it.data) }; candidates.forEach { add(it.data);rows(it.data["groups"]).forEach(::add) }
        memoryProfiles.forEach { (profile,regions) -> regions.forEach { region -> add(region+mapOf("id" to ("memory."+profile+"."+region.text("id")))) } }
        rows(data["mappings"]).forEach(::add)
        rows(data["imageDomains"]?:emptyList<Any>()).forEach(::add)
        rows(data["inventory"]).forEach(::add)
    }
    private val windowById = allWindows.associateBy { it.id }
    private val registerById = portRegisters.values.flatten().associateBy { it.id }
    private val fieldRegisters = registerById.values.flatMap { register -> register.fields.map { it.id to register } }.toMap()
    private val inheritedAccessKeys = setOf("readWidths", "writeWidths", "declaredWriteWidths", "effectiveWriteWidths", "writeWidthsByProfile")
    private fun accessProvenance(register: CatalogRegister, key: String, profile: String?): Any? {
        val sources = obj(register.data["provenance"])
        val sourceKey = if (key == "effectiveWriteWidths" && profile in register.writeWidthsByProfile) "writeWidthsByProfile" else key
        return sources[sourceKey] ?: sources["width"]
    }
    private val entityComponents: Map<String, String> = buildMap {
        rows(data["devices"]).forEach { device ->
            val component = device.text("componentId")
            put(device.text("id"), component)
            rows(device["ports"]).forEach { port ->
                put(port.text("id"), component)
                rows(port["registers"]).forEach { register ->
                    put(register.text("id"), component)
                    rows(register["fields"]).forEach { put(it.text("id"), component) }
                }
            }
        }
        allWindows.forEach { put(it.id, it.componentId) }
        rows(data["instances"] ?: emptyList<Any>()).forEach { put(it.text("id"), it.text("componentId")) }
    }
    val ids: Set<String> get() = byId.keys
    /** Profiles come from the trusted owner binding; raw declarations remain in [data]. */
    fun byId(id: String, componentProfiles: Map<String, String> = emptyMap()): Map<String, Any?>? {
        val declaration = byId[id] ?: return null
        val entity = windowById[id]?.let { declaration + mapOf("registers" to it.registers.map(CatalogRegister::toMap)) } ?: declaration
        fun resolved(row: Map<String, Any?>): Map<String, Any?> {
            val component = entityComponents[row["id"]]
            val profile = componentProfiles[component]
            var result = row
            if (component != null) result = result + mapOf("componentId" to component, "componentProfile" to profile)
            logicalEntitySpaces[row["id"]]?.let {space->
                val logicalProfile=componentProfiles[space.componentId]
                result=result+mapOf("logicalSpaceId" to space.id,"logicalInstanceId" to space.instanceId,"componentId" to space.componentId,
                    "componentProfile" to logicalProfile,"portId" to space.portId,"coordinateKind" to "PROTOCOL_SELECTOR","cpuRoutable" to false,"physicalAddress" to null)
                val r=logicalRegisterById[row["id"]] ?: logicalFieldRegisters[row["id"]]
                if(r!=null)result=result+r.accessDetails(logicalProfile)+mapOf("registerId" to r.id,"selector" to r.selector,"wordBits" to r.wordBits)
            }
            candidateEntitySpaces[row["id"]]?.let {space->
                result=result+protocolCandidateAnnotations(space,candidateRequestById[row["id"]] ?: candidateFieldRequests[row["id"]])
            }
            registerById[row["id"]]?.let { result = result + it.writeAccessDetails(profile) }
            fieldRegisters[row["id"]]?.takeIf { row["access"] == "inherits-register" }?.let { register ->
                result = result + register.writeAccessDetails(profile) + mapOf(
                    "registerId" to register.id, "accessInheritedFromRegister" to true,
                    "readWidths" to register.readWidths, "writeWidths" to register.writeWidths,
                    "inheritedAccessProvenance" to inheritedAccessKeys.associateWith { accessProvenance(register, it, profile) }
                )
            }
            for (key in listOf("ports", "logicalSpaces", "registers", "fields", "protocolCandidates", "requests")) {
                if (key in row) result = result + mapOf(key to rows(row[key]).map(::resolved))
            }
            return result
        }
        return freezeMap(resolved(entity))
    }
    fun property(id: String, path: String, componentProfiles: Map<String, String> = emptyMap()): Map<String, Any?> {
        val entity=byId(id, componentProfiles) ?: return mapOf("id" to id,"property" to path,"status" to "UNKNOWN","value" to null)
        var current: Any?=entity
        path.split('.').filter { it.isNotBlank() }.forEach { key -> current=(current as? Map<*, *>)?.get(key) }
        val key=path.substringBefore('.')
        val alias=when(key){"base","offset"->"address";"size","wordBits","readWidths","writeWidths","declaredWriteWidths","effectiveWriteWidths","writeWidthsByProfile","alignment"->"width";"bitOffset","bitWidth","type","signed","unit","enums"->"definition";"masks","unknownBitPolicy","sideEffects","timing","stateTransitions","irq","dma"->"behavior";else->key}
        val propertySources=entity["provenance"] as? Map<*, *>
        val selectedOverride = (entity["writeWidthsByProfile"] as? Map<*, *>)?.containsKey(entity["componentProfile"]) == true
        val sourceKey = if (key == "effectiveWriteWidths" && selectedOverride) "writeWidthsByProfile" else key
        val inheritedAccess = entity["accessInheritedFromRegister"] == true && key in inheritedAccessKeys
        val provenance = if (inheritedAccess) obj(entity["inheritedAccessProvenance"])[key] else propertySources?.get(sourceKey)?:propertySources?.get(alias)
        val provenanceEntity = if (inheritedAccess) mapOf("provenanceEntityId" to entity["registerId"]) else emptyMap()
        val logical=logicalRegisterById[id] ?: logicalFieldRegisters[id]
        val logicalSource=logical?.let {r->
            val profile=componentProfiles[logicalEntitySpaces[id]?.componentId]
            val sources=r.access(profile)["provenance"] as? Map<*,*>
            when(key){"readable","effectiveReadable"->sources?.get("readable");"writable","effectiveWritable"->sources?.get("writable");else->null}
        }
        val candidateSource=candidateEntitySpaces[id]?.let {space->
            val sources=obj(space.data["provenance"])
            when(key){"deviceOwner"->sources["deviceOwner"];"transportPort","transportComponentId"->sources["transport"];else->null}
        }
        return provenanceEntity + mapOf("id" to id,"property" to path,"value" to current,"provenance" to (logicalSource ?: candidateSource ?: provenance),"status" to if(current==null) "UNKNOWN" else if(logicalSource==null && provenance==null) "DECLARED_UNVERIFIED" else "DECLARED") + entity.filterKeys { it in setOf("componentId", "componentProfile", "registerId", "accessInheritedFromRegister", "declaredWriteWidths", "effectiveWriteWidths", "writeWidthsByProfile", "inheritedAccessProvenance", "logicalSpaceId", "logicalInstanceId", "portId", "coordinateKind", "cpuRoutable", "physicalAddress", "selector", "wordBits", "effectiveReadable", "effectiveWritable", "protocolCandidateSpaceId", "requestId", "deviceOwner", "implementationStatus", "runtimeActivation", "permitted", "transportComponentId", "transportPort") }
    }
    private fun componentDevice(componentId: String) = rows(data["devices"]).singleOrNull { it["componentId"] == componentId }
        ?: error("component absent from dictionary: " + componentId)

    fun componentDependencies(componentId: String): List<String> = deviceDependencies(componentDevice(componentId))

    fun componentDescriptor(componentId: String): Map<String, Any?> {
        val device = componentDevice(componentId)
        val selected=allWindows.filter {it.componentId==componentId}
        val used=selected.map {it.definitionId}.toSet()
        val definitions=rows(device["ports"]).filter {it["id"] in used}.map { port ->
            port.filterKeys {it in setOf("id","kind","byteOrder")} + mapOf("registers" to rows(port["registers"]).map { register ->
                register.filterKeys {it in setOf("id","offset","size","alignment","count","stride","readWidths","writeWidths","writeWidthsByProfile","writeOffsets","readOffsetsByWidth","writeOffsetsByWidth","masks","behaviorId")}
            })
        }
        val logical=logicalSpaces.filter {it.componentId==componentId}
        val contract = linkedMapOf("schema" to 2,"componentId" to componentId,"definitions" to definitions,
            "ports" to selected.map {mapOf("id" to it.portId,"size" to it.size,"definitionId" to it.definitionId)}) + device.filterKeys { it == "profiles" } + (if(logical.isEmpty()) emptyMap() else mapOf("logicalSpaces" to logical.map(::logicalDescriptor)))
        val dependencies = deviceDependencies(device)
        return if (dependencies.isEmpty()) contract else contract + ("depends" to dependencies)
    }
    fun queryLogical(space:String,selector:Long,wordBits:Int=16,direction:String?=null,componentProfiles:Map<String,String> = emptyMap()):Map<String,Any?> =
        queryLogicalCatalog(logicalSpaces,space,selector,wordBits,direction,componentProfiles)
    fun queryLogicalCandidate(space:String,selector:Long,wordBits:Int=16,direction:String?=null):Map<String,Any?> =
        queryProtocolCandidate(protocolCandidateSpaces,space,selector,wordBits,direction)
    fun verifyLogicalContracts(bindings:List<LogicalPortBinding>) = verifyProtocolContracts(logicalSpaces,bindings)
    fun verifyPorts(bindings: List<PortBinding>, componentProfiles:Map<String,String> = emptyMap()) {
        val windows=windowsFor(componentProfiles)
        profileWindowRows(data).filter {componentProfiles[it.text("componentId")]==it.text("profile")}.forEach { variant ->
            val actual=bindings.filter {it.componentId==variant["componentId"]}.map {it.portId}.toSet()
            require(actual==windows.filter{it.componentId==variant["componentId"]}.map{it.portId}.toSet() && (variant["replacePortIds"] as List<*>).none {it in actual}) {"Active alternative layout is incomplete or mixed"}
        }
        require(bindings.map { it.portId }.distinct().size==bindings.size) { "duplicate port binding" }
        bindings.forEach { binding ->
            val window=windows.singleOrNull { it.portId==binding.portId } ?: error("port absent from dictionary: " + binding.portId)
            require(window.componentId==binding.componentId) {
                "dictionary/port binding differs: port=${binding.portId} field=componentId expected=${window.componentId} actual=${binding.componentId}"
            }
            require(window.base==binding.base) {
                "dictionary/port binding differs: port=${binding.portId} field=address expected=0x${window.base.toString(16)} actual=0x${binding.base.toString(16)}"
            }
            require(window.size==binding.size) {
                "dictionary/port binding differs: port=${binding.portId} field=size expected=0x${window.size.toString(16)} actual=0x${binding.size.toString(16)}"
            }
        }
    }
    fun validatePublicTest(metadata:Map<String,Any?>,mode:String):PublicTestLayout=FirmwareDeclarations.publicTest(this,metadata,mode)
    fun validateBootRomExperiment(metadata:Map<String,Any?>):BootRomLayout=FirmwareDeclarations.validateBootRomExperiment(this,metadata)
    fun verifyBootRomMemory(layout:BootRomLayout,profile:String,regions:List<Map<String,Any?>>)=layout.verifyMemory(profile,regions)
    fun verifyStockDeclaration(metadata:Map<String,Any?>)=FirmwareDeclarations.verifyStockDeclaration(this,metadata)
    fun verifyStockMemory(profile:String,regions:List<Map<String,Any?>>)=FirmwareDeclarations.verifyStockMemory(this,profile,regions)
    private val additionalMemoryExclusions:List<Pair<String,Interval>> = buildList {
        allWindows.filter { it.space=="cpu-physical" }.forEach { add(it.id to it.interval) }
        candidates.filter { it.space=="cpu-physical" }.forEach { add(it.id to it.interval) }
        diagnosticProfiles.forEach { add(it.id to Interval(it.base,it.endExclusive)) }
        singleReadDiagnosticProfiles.forEach { add(it.id to Interval(it.base,it.endExclusive)) }
        rows(data["mappings"]).filter { it["sourceSpace"]=="cpu-physical" && it["sourceBase"]!=null }.forEach {
            add(it.text("id") to Interval(it.long("sourceBase"),it.long("sourceEndExclusive")))
        }
        rows(data["imageDomains"] ?: emptyList<Any>()).filter { it["space"]=="cpu-physical" }.forEach {
            add(it.text("id") to Interval(it.long("base"),it.long("endExclusive")))
        }
        val legacy=obj(data["legacyCatalogs"] ?: emptyMap<String,Any?>())
        legacy["soc"]?.let { soc ->
            rows(obj(soc)["blocks"] ?: emptyList<Any>()).forEach {
                add("legacy.soc."+it.text("id") to Interval(it.long("base"),Math.addExact(it.long("base"),it.long("size"))))
            }
        }
        legacy["firmware"]?.let { firmware ->
            obj(firmware)["boot_rom"]?.let { raw ->
                val rom=obj(raw)
                add("legacy.firmware.boot_rom" to Interval(rom.long("address"),Math.addExact(rom.long("address"),obj(rom["content"]).long("size"))))
            }
        }
    }
    fun verifyAdditionalMemory(regions:List<Map<String,Any?>>) {
        for(region in regions) {
            val span=Interval(region.long("base"),region.long("endExclusive"))
            val conflict=additionalMemoryExclusions.firstOrNull { span.overlaps(it.second) }
            require(conflict==null) { "Additional RAM overlaps catalog region: "+conflict?.first }
        }
    }
    fun legacyFirmwareMetadata(): Map<String, Any?> = obj(data["legacyFirmwareMetadata"])
    fun firmwareMetadata(componentProfiles:Map<String,String>):Map<String,Any?> = legacyFirmwareMetadata() + mapOf("devices" to windowsFor(componentProfiles).map { w -> mapOf("id" to w.componentId,"address" to ("0x"+w.base.toString(16)),"size" to ("0x"+w.size.toString(16)),"port" to w.portId) + if(w.optional)mapOf("optional" to true)else emptyMap() })
    fun declarationProfiles(metadata:Map<String,Any?>):Map<String,String> {
        val ports=rows(metadata["devices"]).map { it.text("port") }.toSet()
        val selected=profileWindowRows(data).filter { variant -> rows(variant["windows"]).any { it["portId"] in ports } }
        require(selected.map {it["componentId"]}.distinct().size==selected.size) {"Mixed profile layouts"}
        selected.forEach { variant -> require(rows(variant["windows"]).all {it["portId"] in ports} && (variant["replacePortIds"] as List<*>).none {it in ports}) {"Incomplete or mixed profile layout"} }
        return selected.associate {it.text("componentId") to it.text("profile")}
    }
    fun exportLegacyMetadata(path: Path) = CatalogIO.writeJson(path,legacyFirmwareMetadata())
    @JvmOverloads
    fun query(space: String,address: Long,width: Int,direction: String?=null,memoryProfile: String=defaultMemoryProfile,mode: String="discovery",view:CatalogAddressView?=null,componentProfiles:Map<String,String> = emptyMap(),runtimeRAM:List<Map<String,Any?>> = emptyList()): CatalogQuery {
        require(runtimeRAM.isEmpty() || (mode!="strict" && (view==null || view.confidence!="TEST"))) { "Runtime additional RAM requires discovery without a TEST view" }
        val path=mutableListOf("architecture:"+space,"firmware:"+(view?.firmwareId ?: firmwareId),"mode:"+mode)
        val identity=mapOf("version" to version,"artifactSha256" to artifactSha256,"dictionaryDigestKind" to "compiled-payload-sha256","firmwareSha256" to (view?.firmwareSha256 ?: firmwareSha256))
        fun result(kind:String,window:CatalogWindow?=null,candidate:CandidateRegion?=null,register:CatalogRegister?=null,reason:String?=null,extra:Map<String,Any?> = emptyMap()):CatalogQuery =
            CatalogQuery(kind,space,address,width,window,candidate,register,reason,identity+mapOf("resolutionPath" to path.toList())+extra)
        if(width<=0||address<0||address>=ARM32_LIMIT||address>ARM32_LIMIT-width.toLong())return result("UNASSIGNED",reason="address/width outside ARM32")
        require(mode in setOf("strict","discovery","relaxed")) { "unknown execution mode" }
        require(direction==null||direction in setOf("read","write","execute")) { "unknown access direction" }
        val sp=rows(data["spaces"]).singleOrNull { it["id"]==space } ?: return result("UNASSIGNED",reason="unknown address space")
        if(address>sp.long("endExclusive")-width)return result("UNASSIGNED",reason="outside selected address space")
        if(view!=null&&space!="cpu-physical")return result("UNKNOWN",reason="TEST fixture has no declared mapping in this address space",extra=mapOf("evidenceClass" to view.confidence))
        if(space=="flash-logical") {
            val touched=rows(data["imageDomains"]?:emptyList<Any>()).filter{it.long("base")<address+width&&address<it.long("endExclusive")}
            if(touched.isNotEmpty()) {
                if(touched.size!=1||address<touched.single().long("base")||address+width>touched.single().long("endExclusive"))return result("CROSS_BOUNDARY",reason="image domain boundary; permissions never merge")
                val domain=touched.single();val writable=rows(domain["writable"]).any{address>=it.long("base")&&address+width<=it.long("endExclusive")}
                val reason=when{direction=="execute"->"image data is non-executable in logical space";direction=="write"&&!writable->"outside independent writable subrange";else->null}
                return result("FLASH_LOGICAL_DOMAIN",reason=reason,extra=mapOf("domain" to domain,"writeRequiresProtocolTransaction" to true,"backingActivatedByQuery" to false,"confidence" to domain["provenance"]))
            }
        }
        if(space!="cpu-physical") {
            val mappings=rows(data["mappings"]).filter { it["sourceSpace"]==space&&it["sourceBase"]!=null&&address>=it.long("sourceBase")&&address<it.long("sourceEndExclusive") }
            path.add("mapping-resolution")
            return result(if(mappings.isEmpty())"UNKNOWN" else "MAPPED",reason=if(mappings.isEmpty())"no static mapping; guest-virtual translation requires an owner snapshot" else null,extra=mapOf("mappings" to mappings,"dynamicReadPerformed" to false))
        }
        val end=address+width
        val touched=(view?.windows ?: windowsFor(componentProfiles)).filter { it.space==space&&it.base<end&&address<it.endExclusive }
        if(touched.isNotEmpty()) {
            if(touched.size!=1||!touched.single().interval.contains(address,width))return result("CROSS_BOUNDARY",reason="access crosses a known window boundary",extra=mapOf("intersectedWindowIds" to touched.map { it.id }))
            val w=touched.single();path.add("window:"+w.id)
            val relative=address-w.base
            val rs=w.registers.filter { r -> (0 until r.count).any { i -> val start=r.offset+i*r.stride;start<relative+width&&relative<start+r.size } }
            if(w.kind=="FLASH_APERTURE")return result("FLASH_APERTURE",w,reason=if(direction=="write"||direction=="execute")"read-only aperture; controller protocol and image permissions remain independent" else null,extra=mapOf("policy" to "image-backed","backing" to w.data["backing"]))
            if(rs.size!=1||!rs.single().contains(relative,width))return result(if(rs.isEmpty())"KNOWN_DEVICE_UNKNOWN_REGISTER" else "CROSS_BOUNDARY",w,reason=if(rs.isEmpty())"device implementation owns this window; unknown register cannot use discovery fallback" else "access crosses register or hole")
            val r=rs.single();path.add("register:"+r.id)
            val profile=componentProfiles[w.componentId]
            val effectiveWrites=r.effectiveWriteWidths(profile)
            val widths=when(direction){"read"->r.readWidths;"write"->effectiveWrites;"execute"->emptyList();else->(r.readWidths+effectiveWrites).distinct()}
            val lane=(relative-r.offset)%r.stride
            val laneDenied=(direction=="write" && r.data["writeOffsets"]?.let {lane !in (it as List<*>).map(::valueLong)}==true) ||
                (direction=="write" && r.data["writeOffsetsByWidth"]?.let {lane !in ((it as Map<*,*>)[width.toString()] as? List<*>).orEmpty().map(::valueLong)}==true) ||
                (direction=="read" && r.data["readOffsetsByWidth"]?.let {lane !in ((it as Map<*,*>)[width.toString()] as? List<*>).orEmpty().map(::valueLong)}==true)
            val refusal=if(laneDenied)"register access lane prohibited" else if(width !in widths)"register access width/direction prohibited" else if(address%r.alignment!=0L||address%width!=0L)"register alignment prohibited" else null
            return result("REGISTER",w,register=r,reason=refusal,extra=r.writeAccessDetails(profile)+mapOf("readWidths" to r.readWidths,"writeWidths" to effectiveWrites,"implementationId" to r.behaviorId,"status" to r.status,"policy" to "known-device","statefulValidation" to "component-owner-thread","provenance" to r.data["provenance"]))
        }
        val declaredMemory=view?.memoryRegions ?: memoryProfiles[memoryProfile] ?: error("unknown memory profile: "+memoryProfile)
        val memory=if(runtimeRAM.isEmpty())declaredMemory else declaredMemory+runtimeRAM
        val ram=memory.filter { it.long("base")<end&&address<it.long("endExclusive") }
        if(ram.isNotEmpty()) {
            val runtimeDetails=if(ram.any { it in runtimeRAM })mapOf("evidenceClass" to "HYPOTHESIS","confidence" to "HYPOTHESIS",
                "policy" to "explicit-runtime-ram-backing","backingSource" to "owner-selected-additional-memory","staticDictionaryDeclaration" to false)else emptyMap()
            if(ram.size!=1||address<ram.single().long("base")||end>ram.single().long("endExclusive"))return result("CROSS_BOUNDARY",reason="RAM boundary or unmapped hole",extra=runtimeDetails)
            val m=ram.single();val permission=when(direction){"read"->1L;"write"->2L;"execute"->4L;else->0L}
            return result("RAM",reason=if(permission!=0L&&m.long("permissions") and permission==0L)"RAM permission denied" else null,extra=mapOf("memoryProfile" to memoryProfile,"region" to m,"policy" to "explicit-ram-backing")+runtimeDetails)
        }
        val cs=(if(view==null)candidates else emptyList()).filter { it.space==space&&it.base<end&&address<it.endExclusive }
        if(cs.isNotEmpty()) {
            if(cs.size!=1||!cs.single().interval.contains(address,width))return result("CROSS_BOUNDARY",reason="candidate boundary or gap")
            val c=cs.single();path.add("candidate:"+c.id)
            if(c.prohibited.any { it.base<end&&address<it.endExclusive })return result("PROHIBITED",candidate=c,reason="explicit prohibited hole")
            val candidates=rows(c.data["registers"]).map(::register)
            val relative=address-c.base
            val touchedRegisters=candidates.filter { r -> (0 until r.count).any { i -> val start=r.offset+i*r.stride;start<relative+width&&relative<start+r.size } }
            if(touchedRegisters.size>1||(touchedRegisters.size==1&&!touchedRegisters.single().contains(relative,width)))return result("CROSS_BOUNDARY",candidate=c,reason="candidate access crosses a declared field/register boundary")
            val r=touchedRegisters.singleOrNull()
            val widths=if(r==null)c.widths else when(direction){"read"->r.readWidths;"write"->r.writeWidths;"execute"->emptyList();else->(r.readWidths+r.writeWidths).distinct()}
            val reason=when { mode=="strict"->"strict mode refuses candidate access";direction=="execute"->"candidate is non-executable";width !in widths->"candidate width/direction prohibited";address%c.alignment!=0L||address%width!=0L||(r!=null&&address%r.alignment!=0L)->"candidate alignment prohibited";c.policy=="refuse"->"candidate policy refuses";else->null }
            return result(if(r==null)"CANDIDATE_UNKNOWN" else "CANDIDATE_REGISTER",candidate=c,register=r,reason=reason,extra=mapOf("policy" to c.policy,"confidence" to "HYPOTHESIS","initialFill" to c.initialFill,"readWidths" to (r?.readWidths ?: c.widths),"writeWidths" to (r?.writeWidths ?: c.widths),"noAutomaticEvents" to true))
        }
        return result("UNASSIGNED",reason="no selected region; no backing allocated")
    }
    /** Interval partition, not a 4-GiB array. Includes unknown gaps. */
    @JvmOverloads
    fun coverage(space:String="cpu-physical",base:Long=0,endExclusive:Long=ARM32_LIMIT,memoryProfile:String=defaultMemoryProfile,mode:String="discovery",view:CatalogAddressView?=null,componentProfiles:Map<String,String> = emptyMap(),runtimeRAM:List<Map<String,Any?>> = emptyList()):List<Map<String,Any?>> {
        require(runtimeRAM.isEmpty() || (mode=="discovery" && view==null)) { "Runtime additional RAM requires discovery without a TEST view" }
        Interval(base,endExclusive)
        val cuts=sortedSetOf(base,endExclusive)
        rows(data["spaces"]).singleOrNull{it["id"]==space}?.long("endExclusive")?.let{if(it>base&&it<endExclusive)cuts.add(it)}
        (if(view==null)rows(data["mappings"])else emptyList()).filter{it["sourceSpace"]==space&&it["sourceBase"]!=null}.forEach{m->listOf(m.long("sourceBase"),m.long("sourceEndExclusive")).forEach{if(it>base&&it<endExclusive)cuts.add(it)}}
        (view?.windows ?: windowsFor(componentProfiles)).filter {it.space==space}.forEach{ w -> if(w.base>base&&w.base<endExclusive)cuts.add(w.base);if(w.endExclusive>base&&w.endExclusive<endExclusive)cuts.add(w.endExclusive); w.registers.forEach {r->repeat(r.count){i->listOf(w.base+r.offset+i*r.stride,w.base+r.offset+i*r.stride+r.size).forEach{if(it>base&&it<endExclusive)cuts.add(it)}}} }
        (if(view==null)candidates else emptyList()).filter{it.space==space}.forEach { c -> listOf(c.base,c.endExclusive).forEach{if(it>base&&it<endExclusive)cuts.add(it)}; rows(c.data["registers"]).forEach{r->repeat(Math.toIntExact(r.long("count"))){i->listOf(c.base+r.long("offset")+i*r.long("stride"),c.base+r.long("offset")+i*r.long("stride")+r.long("size")).forEach{if(it>base&&it<endExclusive)cuts.add(it)}}};c.prohibited.forEach { p ->listOf(p.base,p.endExclusive).forEach {if(it>base&&it<endExclusive)cuts.add(it)} } }
        if(view==null&&space=="flash-logical")rows(data["imageDomains"]?:emptyList<Any>()).forEach{d->(listOf(d.long("base"),d.long("endExclusive"))+rows(d["writable"]).flatMap{listOf(it.long("base"),it.long("endExclusive"))}).forEach{if(it>base&&it<endExclusive)cuts.add(it)}}
        if(space=="cpu-physical")((view?.memoryRegions ?: memoryProfiles.getValue(memoryProfile))+runtimeRAM).forEach {m->listOf(m.long("base"),m.long("endExclusive")).forEach{if(it>base&&it<endExclusive)cuts.add(it)} }
        return cuts.toList().zipWithNext().map{(a,b)->val q=query(space,a,1,memoryProfile=memoryProfile,mode=mode,view=view,componentProfiles=componentProfiles,runtimeRAM=runtimeRAM);mapOf("base" to a,"endExclusive" to b,"classification" to q.classification,"regionId" to(q.window?.id?:q.candidate?.id),"refusalReason" to q.refusalReason)+if(q.details["staticDictionaryDeclaration"]==false)mapOf("evidenceClass" to "HYPOTHESIS","staticDictionaryDeclaration" to false,"region" to q.details["region"])else emptyMap()}
    }
    companion object {
        fun load(path:Path):AddressCatalog = if(Files.isDirectory(path)||path.fileName.toString().endsWith(".yaml"))CatalogCompiler.compile(if(Files.isDirectory(path))path else path.parent) else {require(Files.size(path)<=CatalogIO.MAX_BYTES){"compiled dictionary exceeds limit"};CatalogIO.readCompiled(Files.readAllBytes(path))}
        fun bundled():AddressCatalog = AddressCatalog::class.java.getResourceAsStream("/catalog/compiled.json")?.use {CatalogIO.readCompiled(it.readNBytes(CatalogIO.MAX_BYTES+1))} ?: error("bundled address dictionary missing")
        private fun freeze(value:Any?):Any? = when(value) {
            is Map<*,*> -> freezeMap(obj(value))
            is List<*> -> java.util.Collections.unmodifiableList(value.map(::freeze))
            else -> value
        }
        private fun freezeMap(value:Map<String,Any?>):Map<String,Any?> = java.util.Collections.unmodifiableMap(value.mapValues{freeze(it.value)})
        private fun register(r:Map<String,Any?>)=CatalogRegister(r.text("id"),r.long("offset"),Math.toIntExact(r.long("size")),Math.toIntExact(r.long("count")),r.long("stride"),r.ints("readWidths"),r.ints("writeWidths"),Math.toIntExact(r.long("alignment")),r.text("status"),r.text("behaviorId"),immutableList(rows(r["fields"]).map{CatalogField(it.text("id"),Math.toIntExact(it.long("bitOffset")),Math.toIntExact(it.long("bitWidth")),it)}),r)
    }
}

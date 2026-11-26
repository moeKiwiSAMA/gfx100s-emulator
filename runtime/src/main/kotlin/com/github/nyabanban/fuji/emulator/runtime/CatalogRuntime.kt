package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.BootRomLayout
import com.github.nyabanban.fuji.emulator.catalog.FirmwareDeclarations
import com.github.nyabanban.fuji.emulator.catalog.PublicTestLayout
import com.github.nyabanban.fuji.emulator.catalog.CatalogAddressView
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** One immutable dictionary powers all host query surfaces and precompiled access gates. */
class CatalogRuntime(val catalog: AddressCatalog, val mode: String, private val home: Path, inputConfig: Map<String,Any>) {
    private val config=Formats.map(Formats.json.readValue(Formats.jsonBytes(inputConfig),Map::class.java))
    private var firmwareValidated=false
    private var declarationProfiles:Map<String,String> = emptyMap()
    private var activePorts: Set<String>? = null
    private var activeLogicalPorts: Set<String>? = null
    private var activeLogicalProfiles: Map<String,String> = emptyMap()
    private var activeMemoryProfile: String? = null
    private var baseMemory:List<MemoryRegion>? = null
    private var additionalRamProfile = ""
    private data class AdditionalMemorySelection(val regions:List<Map<String,Any?>>,val receipt:Map<String,Any?>)
    private var additionalMemory:AdditionalMemorySelection? = null
    private var activeComponentProfiles: Map<String,String> = emptyMap()
    private var candidateInputs:Map<String,Map<String,Any>>?=null
    private var candidateInputProfiles:Map<String,String> = emptyMap()
    private var publicTest:PublicTestLayout?=null
    private var bootRomLayout:BootRomLayout?=null
    private var activeView:CatalogAddressView?=null
    private var activeFirmwareId=catalog.firmwareId
    private var activeFirmwareSha256=catalog.firmwareSha256

    /** Resets dynamic port/firmware bindings for a `relaxed`-mode Sub-MCU cold reset; refused in `strict`/`discovery`. */
    fun resetForColdReset() {
        require(mode == RelaxedPeripheralFallback.MODE) { "Cold-reset catalog reset is only permitted in relaxed mode" }
        firmwareValidated = false
        declarationProfiles = emptyMap()
        activePorts = null
        activeLogicalPorts = null
        activeLogicalProfiles = emptyMap()
        activeMemoryProfile = null
        baseMemory = null
        additionalRamProfile = ""
        additionalMemory = null
        activeComponentProfiles = emptyMap()
        candidateInputs = null
        candidateInputProfiles = emptyMap()
        publicTest = null
        bootRomLayout = null
        activeView = null
        activeFirmwareId = catalog.firmwareId
        activeFirmwareSha256 = catalog.firmwareSha256
        diagnosticBuses.clear()
    }
    fun validateFirmware(raw:Map<String,Any?>) {
        check(activePorts==null) { "Firmware identity already bound" }
        val validated=if(raw["version"]=="TEST")catalog.validatePublicTest(raw,mode) else null
        val validatedBootRom=if(validated==null && raw["bootMode"]=="boot-rom-experiment")catalog.validateBootRomExperiment(raw) else null
        if(validated==null && validatedBootRom==null) { catalog.verifyStockDeclaration(raw); declarationProfiles=catalog.declarationProfiles(raw) }
        firmwareValidated=true
        publicTest=validated;bootRomLayout=validatedBootRom;activeFirmwareId=raw.getValue("id").toString();activeFirmwareSha256=raw.getValue("sha256").toString()
    }
    @JvmOverloads
    fun bindPorts(ports: List<com.github.nyabanban.fuji.emulator.catalog.PortBinding>, memoryProfile: String, actualRegions:List<Map<String,Any?>>, componentProfiles: Map<String,String> = emptyMap(), additionalRamProfile:String = "") {
        check(activePorts==null) { "Dictionary ports already bound" }
        require(additionalRamProfile.isEmpty() || (additionalRamProfile in AdditionalRamRegions.PROFILES && mode!="strict")) {
            "Additional RAM requires an explicit finite RAM profile in discovery mode"
        }
        val activeComponents=ports.map { it.componentId }.toSet()
        require(componentProfiles.keys.all { it in activeComponents && it in catalog.componentIds }) { "Profile supplied for an inactive or unknown component" }
        componentProfiles.forEach { (id,profile) ->
            require(profile.isNotBlank() && profile.length<=4096) { "Invalid component profile: " + id }
            val declared=catalog.componentDescriptor(id)["profiles"] as? List<*>
            require(declared==null || profile in declared) { "Unknown component profile: " + id + "/" + profile }
        }
        val frozenProfiles=java.util.Collections.unmodifiableMap(LinkedHashMap(componentProfiles))
        val selectedTest=publicTest
        val selectedBootRom=bootRomLayout
        val view=if(selectedTest!=null) {
            require(ports.map {it.portId}.distinct().size==ports.size&&ports.all {it in selectedTest.ports}) { "Actual TEST ports differ from declaration" }
            val selected=selectedTest.view(memoryProfile)
            require(FirmwareDeclarations.normalizeRegions(actualRegions)==FirmwareDeclarations.normalizeRegions(selected.memoryRegions)) { "Actual TEST RAM differs from validated declaration" }
            selected
        } else if(selectedBootRom!=null) {
            require(ports.map {it.portId}.distinct().size==ports.size&&ports.all {it in selectedBootRom.ports}) { "Actual Boot ROM ports differ from declaration" }
            catalog.verifyPorts(ports.filterNot { it.portId == "soc-system-control.boot-strap" }, frozenProfiles)
            catalog.verifyBootRomMemory(selectedBootRom, memoryProfile, actualRegions)
            selectedBootRom.view(memoryProfile)
        } else {
            require(!firmwareValidated || declarationProfiles==catalog.declarationProfiles(catalog.firmwareMetadata(frozenProfiles))) {"Firmware layout differs from active profile"}
            catalog.verifyPorts(ports,frozenProfiles);catalog.verifyStockMemory(memoryProfile,actualRegions);null
        }
        val initialMemory=java.util.List.copyOf(FirmwareDeclarations.normalizeRegions(actualRegions).map {
            MemoryRegion(it.getValue("id").toString(),integer(checkNotNull(it["base"])),integer(checkNotNull(it["size"])),Math.toIntExact(integer(checkNotNull(it["permissions"]))))
        })
        baseMemory=initialMemory;this.additionalRamProfile=additionalRamProfile
        activePorts=java.util.Collections.unmodifiableSet(ports.map {it.portId}.toSet());activeMemoryProfile=memoryProfile;activeView=view;activeComponentProfiles=frozenProfiles
    }
    fun validateAdditionalMemory(regions:List<MemoryRegion>) {
        prepareAdditionalMemory(regions)
    }
    fun selectAdditionalMemory(regions:List<MemoryRegion>) {
        val selection=prepareAdditionalMemory(regions)
        additionalMemory=selection
    }
    fun extendAdditionalMemory(regions:List<MemoryRegion>, beforePublish: () -> Unit) {
        val current = checkNotNull(additionalMemory) { "Extended additional RAM requires an existing selection" }
        val selection=prepareAdditionalMemory(regions, extending = true)
        require(selection.regions.containsAll(current.regions) && selection.regions.size > current.regions.size) {
            "Extended additional RAM must strictly preserve all previously selected catalog regions"
        }
        val existingIds = current.regions.map { it["id"] }.toSet()
        AdditionalRamRegions.validatePageAllowance(existingIds.size, regions.filterNot { it.id in existingIds })
        beforePublish()
        additionalMemory=selection
    }
    fun additionalMemorySelection():Map<String,Any?> = additionalMemory?.receipt ?: mapOf(
        "profile" to additionalRamProfile,"enabled" to (additionalRamProfile in AdditionalRamRegions.PROFILES),
        "selected" to false,"regions" to emptyList<Any>(),"baseMemoryProfile" to activeMemoryProfile,
        "confidence" to "HYPOTHESIS","staticDictionaryDeclaration" to false)
    private fun prepareAdditionalMemory(regions:List<MemoryRegion>, extending:Boolean = false):AdditionalMemorySelection {
        check(activePorts!=null && baseMemory!=null) { "Additional RAM requires bound memory" }
        require(mode!="strict" && additionalRamProfile in AdditionalRamRegions.PROFILES && (activeView==null || bootRomLayout!=null)) {
            "Additional RAM is not enabled for this runtime"
        }
        check(extending || additionalMemory==null) { "Additional RAM already selected" }
        val requested=regions.toList()
        val base=checkNotNull(baseMemory)
        val complete=AdditionalRamRegions.validate(base,requested,additionalRamProfile)
        val baseIds=base.map { it.id }.toSet()
        val selected=FirmwareDeclarations.normalizeRegions(complete.filter { it.id !in baseIds }.map {
            mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)
        })
        catalog.verifyAdditionalMemory(selected)
        val receipt=java.util.Collections.unmodifiableMap(mapOf(
            "profile" to additionalRamProfile,"enabled" to true,"selected" to true,"regions" to selected,
            "baseMemoryProfile" to activeMemoryProfile,"confidence" to "HYPOTHESIS","staticDictionaryDeclaration" to false,
            "scope" to if(additionalRamProfile==AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
                "Explicit owner-selected additional RW/NX or RWX backing; original dictionary and base memory remain unchanged"
            else "Explicit owner-selected additional RW/NX backing; original dictionary and base memory remain unchanged"))
        return AdditionalMemorySelection(selected,receipt)
    }
    init { require(mode in setOf("discovery","strict",RelaxedPeripheralFallback.MODE)) }
    private val settings: Map<String,Any> = config["discovery"]?.let(Formats::map).orEmpty()
    private val profile = settings["profile"] ?: "gfx100s-sd-finite-v1"
    private val singleReadProfile = catalog.singleReadDiagnosticProfiles.singleOrNull { it.id == profile }
    private val diagnosticProfile = catalog.diagnosticProfiles.singleOrNull { it.id == (singleReadProfile?.parentProfile ?: profile) }
    init {
        require(settings.keys.all { it in setOf("profile","logCapacity","diskBudgetBytes","inputs","diagnosticInputs","hdmiStatusPairs","hdmiLinkStatusReads","channel7Programming","channel4Programming","channel11Programming","imageConfigPairs","imageFace7c000","image50000","image50000Control","image51000","image59000","image70000","image6e000","image68000","imageReply820001","imageReply820040","imageReply820041",ImageIrq106Status.KEY,ProgrammedImageReply.KEY,ImageLiveViewBanks.KEY,ImageScalerF000.KEY,ImageScalerE000.KEY,"playDspFa00",ImageResultStats.KEY,BootRomColdStart.KEY,IspAutoSync.KEY) })
        if ("imageFace7c000" in settings) require(mode != "strict" && settings["imageFace7c000"] in ImageFace7c000.ALL_PROFILES)
        if ("playDspFa00" in settings) require(mode != "strict" && settings["playDspFa00"] == "fa000000-ready-idle-v1")
        if (ImageIrq106Status.KEY in settings) require(mode != "strict" && settings["image70000"] is String &&
            settings[ImageIrq106Status.KEY] in ImageIrq106Status.PROFILES)
        if (ImageLiveViewBanks.KEY in settings) require(mode != "strict" &&
            settings[ImageLiveViewBanks.KEY] == ImageLiveViewBanks.PROFILE)
        if (ImageScalerF000.KEY in settings) require(mode != "strict" &&
            settings[ImageScalerF000.KEY] in ImageScalerF000.PROFILES)
        if (ImageScalerE000.KEY in settings) require(mode != "strict" &&
            settings[ImageScalerE000.KEY] in ImageScalerE000.PROFILES)
        if (ImageResultStats.KEY in settings) require(mode != "strict" &&
            settings[ImageResultStats.KEY] in ImageResultStats.PROFILES)
        if (BootRomColdStart.KEY in settings) require(mode != "strict" &&
            settings[BootRomColdStart.KEY] in BootRomColdStart.PROFILES)
        if (IspAutoSync.KEY in settings) require(mode != "strict" &&
            settings[IspAutoSync.KEY] == IspAutoSync.PROFILE)
        require("hdmiLinkStatusReads" !in settings || (settings["hdmiLinkStatusReads"] != null && settings["hdmiStatusPairs"] != null))
        require(profile=="gfx100s-sd-finite-v1" || diagnosticProfile != null) { "Unknown finite discovery profile" }
    }
    private val diagnosticInputs:Map<String,Map<String,Any>> = java.util.Collections.unmodifiableMap(
        (if("diagnosticInputs" in settings) {
            require(singleReadProfile!=null) { "Diagnostic inputs require a selected single-read profile" }
            Formats.map(settings.getValue("diagnosticInputs"))
        } else emptyMap()).mapValues { (id,raw) ->
            val declaration=requireNotNull(singleReadProfile) { "Diagnostic input requires a selected single-read profile" }
            require(id==declaration.id) { "Diagnostic input must name the selected single-read profile" }
            val input=Formats.map(raw)
            require(input.keys==setOf("strategy","confidence","samples")&&
                input["strategy"]=="scripted-read"&&input["confidence"]=="HYPOTHESIS") {
                "Diagnostic input requires only scripted-read HYPOTHESIS samples"
            }
            java.util.Collections.unmodifiableMap(input+("samples" to validateDiagnosticSamples(input["samples"],declaration.samples)))
        })
    private val selectedSamples=singleReadProfile?.let { declaration ->
        (diagnosticInputs[declaration.id]?.get("samples") as? List<*>)?.let { samples ->
            java.util.Collections.unmodifiableList(samples.map { it as Long })
        } ?: declaration.samples
    }
    private val diagnosticBuses=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<DiscoveryBus,Boolean>())
    private fun validateDiagnosticSamples(raw:Any?,declared:List<Long>):List<Long> {
        require(raw is List<*> && raw.size in 1..64) { "Diagnostic input requires 1..64 explicit samples" }
        val samples=raw.map { value ->
            require(value is Byte||value is Short||value is Int||value is Long) { "Diagnostic sample must be an exact non-null u32 integer" }
            (value as Number).toLong().also { require(it in 0..0xffffffffL) { "Diagnostic sample is outside u32" } }
        }
        require(samples.take(declared.size)==declared) { "Diagnostic samples must preserve the complete declared prefix" }
        return java.util.Collections.unmodifiableList(samples)
    }
    fun diagnosticInputSelection(bus:DiscoveryBus,id:String):Map<String,Any?> {
        val declaration=requireNotNull(singleReadProfile) { "Diagnostic input requires a selected single-read profile" }
        require(mode=="discovery" && id==declaration.id && bus in diagnosticBuses) { "Diagnostic input must belong to this runtime's selected bus and profile" }
        require(declaration.data["confidence"]=="HYPOTHESIS")
        return bus.scriptInputSelection(id)+mapOf("strategy" to "scripted-read","confidence" to "HYPOTHESIS",
            "declaredSamples" to declaration.samples,"configuredSamples" to selectedSamples)
    }
    fun selectDiagnosticInputTail(bus:DiscoveryBus,id:String,expectedSampleIndex:Int,samples:List<Long>):Map<String,Any?> {
        diagnosticInputSelection(bus,id)
        val validated=validateDiagnosticSamples(samples,checkNotNull(singleReadProfile).samples)
        bus.selectScriptInputTail(id,expectedSampleIndex,validated)
        return diagnosticInputSelection(bus,id)
    }
    fun extendF01cReads(bus: DiscoveryBus, extra: Int): Map<String, Any?> {
        val profile = requireNotNull(diagnosticProfile) { "F01C retained diagnostic must be selected" }
        require(mode == "discovery" && bus in diagnosticBuses) { "Expected this runtime's discovery bus" }
        return bus.extendF01cReads(profile.id, extra)
    }
    fun completeAudioF01c(bus: DiscoveryBus, attempts: Long, tick: Long): Map<String, Any> {
        val profile = requireNotNull(diagnosticProfile)
        require(mode == "discovery" && bus in diagnosticBuses)
        return bus.completeAudioF01c(profile.id, attempts, tick)
    }
    fun selectF01cRepeats(bus: DiscoveryBus, limit: Int): Map<String, Any> {
        require(mode == "discovery" && bus in diagnosticBuses)
        return bus.selectF01cRepeats(requireNotNull(diagnosticProfile).id, limit)
    }
    /** Validate all logical exports before publishing any active contract. No endpoint reads. */
    fun bindLogicalPorts(bindings:List<com.github.nyabanban.fuji.emulator.catalog.LogicalPortBinding>, profiles:Map<String,String>) {
        check(activeLogicalPorts==null) {"Logical dictionary ports already bound"}
        val spaces=catalog.logicalSpaces
        require(profiles.keys.all {id->spaces.any {it.componentId==id}}) {"Profile supplied for unknown logical component"}
        profiles.forEach {(id,profile)->require(spaces.filter {it.componentId==id}.all {profile in it.profiles}) {"Unknown logical component profile"}}
        require(bindings.map {it.portId}.distinct().size==bindings.size && bindings.all {profiles[it.componentId]==it.profile})
        val expected=spaces.filter {profiles[it.componentId]?.let {p->p!="disabled"}==true}.map {it.portId}.toSet()
        require(bindings.map {it.portId}.toSet()==expected) {"Enabled logical endpoints differ from actual exports"}
        catalog.verifyLogicalContracts(bindings)
        val frozenProfiles=java.util.Collections.unmodifiableMap(LinkedHashMap(profiles))
        val frozenPorts=java.util.Collections.unmodifiableSet(bindings.map {it.portId}.toSet())
        activeLogicalProfiles=frozenProfiles;activeLogicalPorts=frozenPorts
    }
    /** Atomically validate immutable hypothesis metadata; does not grant device access. */
    fun bindCandidateInputs(bindings:List<ProtocolCandidateInputBinding>) {
        check(candidateInputs==null) {"Candidate experiment inputs already bound"}
        val validated=validateCandidateInputs(catalog,bindings)
        val profiles=java.util.Collections.unmodifiableMap(bindings.associate {it.componentId to it.profile})
        candidateInputs=validated;candidateInputProfiles=profiles
    }
    fun boundCandidateProfile(componentId:String):String? = candidateInputProfiles[componentId]
    fun boundCandidateInputs(componentId:String):List<Map<String,Any>> = candidateInputs.orEmpty().values.filter {it["transportComponentId"]==componentId}
    fun boundLogicalProfile(componentId:String):String? = activeLogicalProfiles[componentId]
    private fun logicalQuery(parameters:Map<String,String>, entity:Map<String,Any?>?):Map<String,Any?> {
        require(entity==null || entity["logicalSpaceId"] is String) {"Known nonlogical ID cannot be relabeled as a protocol selector entity"}
        require(parameters.keys.none {it in setOf("address","width","coverage","base","endExclusive","memoryProfile")}) {"Logical selectors are not CPU byte addresses"}
        require(!(parameters.containsKey("id") && parameters.containsKey("selector"))) {"Choose stable ID or logical selector"}
        require(!parameters.containsKey("selector") || parameters.containsKey("space")) {"Logical selector query requires its explicit space"}
        require(!parameters.containsKey("id") || parameters.keys.none {it in setOf("wordBits","direction")}) {"Stable ID lookup does not accept access operands"}
        val requestedSpace=parameters["space"]
        val entitySpace=entity?.get("logicalSpaceId")?.toString()
        val space=catalog.logicalSpaces.singleOrNull {s->
            val requestedMatches=requestedSpace==null || requestedSpace==s.id || requestedSpace==s.instanceId
            requestedMatches && (entitySpace==null || entitySpace==s.id || entitySpace==s.instanceId)
        } ?: throw IllegalArgumentException("Unknown or ambiguous logical selector space")
        val active=space.portId in activeLogicalPorts.orEmpty()
        val explicitProfile=parameters["componentProfile"]
        require(explicitProfile==null || explicitProfile in space.profiles) {"Unknown explicit logical component profile"}
        val profile=activeLogicalProfiles[space.componentId]
        require(activeLogicalPorts==null || explicitProfile==null || explicitProfile==profile) {"Explicit profile differs from active logical component"}
        val selected=explicitProfile ?: profile
        val selectedProfiles=selected?.let {mapOf(space.componentId to it)}.orEmpty()
        val result=if(parameters.containsKey("id")) {
            val id=parameters.getValue("id")
            if(parameters.containsKey("property"))catalog.property(id,parameters.getValue("property"),selectedProfiles)
            else catalog.byId(id,selectedProfiles).let { row ->
                if(row==null)mapOf("entity" to null,"status" to "UNKNOWN","classification" to "UNKNOWN","permitted" to false,"refusalReason" to "unknown logical identifier")
                else mapOf("entity" to row,"status" to "DECLARED")
            }
        } else {
            require(!parameters.containsKey("property")) {"Property lookup requires stable ID"}
            val selector=integer(parameters["selector"] ?: error("Logical selector missing"))
            val bits=Math.toIntExact(integer(parameters["wordBits"] ?: space.wordBits.toString()))
            catalog.queryLogical(space.id,selector,bits,parameters["direction"],selectedProfiles)
        }
        val unbound=activeLogicalPorts==null
        val identity=mapOf("firmwareId" to activeFirmwareId,"firmwareSha256" to activeFirmwareSha256,
            "dictionaryVersion" to catalog.version,"dictionarySha256" to catalog.artifactSha256,"dictionaryDigestKind" to "compiled-payload-sha256","executionMode" to mode)
        val denied=unbound || !active
        return result+identity+mapOf("space" to space.id,"logicalSpaceId" to space.id,"physicalAddress" to null,"cpuRoutable" to false,
            "runtimeActivation" to if(unbound)"NOT_BOUND" else if(active)"ACTIVE_LOGICAL_PORT" else "INACTIVE_LOGICAL_PORT",
            "componentProfile" to selected,"profileSource" to if(explicitProfile!=null && unbound)"explicit-static-selection" else if(profile!=null)"active-component" else "unbound",
            "activeLogicalProfiles" to activeLogicalProfiles,"declaredPermitted" to result["permitted"],
            "deviceReadPerformed" to false,"guestStateChanged" to false,"dynamicAvailability" to "NOT_SAMPLED",
            "evidenceClass" to if(publicTest!=null)"TEST" else "DICTIONARY")+
            if(denied)mapOf("permitted" to false,"refusalReason" to if(unbound)"logical ports are not bound" else "logical endpoint is inactive")else emptyMap()
    }
    /** Unassigned request evidence never participates in active endpoint binding or discovery. */
    private fun protocolCandidateQuery(parameters:Map<String,String>,entity:Map<String,Any?>?):Map<String,Any?> {
        require(entity==null || entity["protocolCandidateSpaceId"] is String) {"Known physical or logical ID cannot be relabeled as an unassigned protocol request"}
        require(parameters.keys.none {it in setOf("address","width","coverage","base","endExclusive","memoryProfile","componentProfile")}) {"Protocol candidates have no CPU coordinates, memory profile or component permissions"}
        require(!(parameters.containsKey("id") && parameters.containsKey("selector"))) {"Choose candidate ID or selector"}
        require(!parameters.containsKey("selector") || parameters.containsKey("space")) {"Candidate selector requires its explicit namespace"}
        require(!parameters.containsKey("id") || parameters.keys.none {it in setOf("wordBits","direction")}) {"Candidate ID lookup does not accept access operands"}
        require(!parameters.containsKey("property") || parameters.containsKey("id")) {"Candidate property lookup requires a stable ID"}
        val entitySpace=entity?.get("protocolCandidateSpaceId")?.toString()
        val selected=catalog.protocolCandidateSpaces.singleOrNull {s->
            (parameters["space"]==null || parameters["space"]==s.id) && (entitySpace==null || entitySpace==s.id)
        } ?: throw IllegalArgumentException("Unknown or mismatched protocol candidate namespace")
        val result=if(parameters["id"]!=null) {
            val id=parameters.getValue("id")
            if(parameters["property"]!=null)catalog.property(id,parameters.getValue("property"))
            else mapOf("entity" to catalog.byId(id),"status" to if(entity==null)"UNKNOWN" else "DECLARED_UNIMPLEMENTED")
        } else catalog.queryLogicalCandidate(selected.id,integer(requireNotNull(parameters["selector"]) {"Candidate selector missing"}),
            Math.toIntExact(integer(parameters["wordBits"] ?: selected.wordBits.toString())),parameters["direction"])
        val requestId=entity?.get("requestId")?.toString() ?: result["requestId"]?.toString() ?: parameters["id"]
        val experimentalInput=requestId?.let {candidateInputs?.get(it)}
        return result+mapOf("experimentalInput" to experimentalInput,"experimentalInputActivation" to if(experimentalInput==null)"NOT_CONFIGURED" else "CONFIGURED_ONLY",
            "classification" to (result["classification"] ?: if(entity==null && parameters.containsKey("id"))"UNKNOWN" else "PROTOCOL_CANDIDATE_DECLARATION"),
            "refusalReason" to (result["refusalReason"] ?: "candidate evidence grants no executable access"),
            "resolutionPath" to (result["resolutionPath"] ?: (listOf("protocol-candidate-space:"+selected.id)+parameters["id"]?.let {listOf("candidate-id:"+it)}.orEmpty())),
            "firmwareId" to activeFirmwareId,"firmwareSha256" to activeFirmwareSha256,
            "dictionaryVersion" to catalog.version,"dictionarySha256" to catalog.artifactSha256,"dictionaryDigestKind" to "compiled-payload-sha256",
            "executionMode" to mode,"protocolCandidateSpaceId" to selected.id,"coordinateKind" to "PROTOCOL_SELECTOR",
            "applicability" to selected.data["applicability"],"applicableToActiveFirmware" to ((selected.data["applicability"] as Map<*,*>)["firmware"]==activeFirmwareId),
            "transportComponentId" to selected.transportComponentId,"transportPort" to selected.transportPort,"deviceOwner" to "UNKNOWN",
            "permitted" to false,"readable" to false,"writable" to false,"implementationStatus" to "NOT_IMPLEMENTED","behaviorId" to null,
            "cpuRoutable" to false,"physicalAddress" to null,"runtimeActivation" to "NOT_IMPLEMENTED",
            "deviceReadPerformed" to false,"guestStateChanged" to false,"dynamicAvailability" to "NOT_SAMPLED","evidenceClass" to "DICTIONARY")
    }
    fun query(parameters: Map<String,String>): Map<String,Any?> {
        require(parameters.keys.all { it in setOf("id","property","space","address","width","direction","coverage","base","endExclusive","memoryProfile","firmware","selector","wordBits","componentProfile") })
        require(parameters["firmware"]==null || parameters["firmware"]==activeFirmwareId) { "Unknown firmware dictionary" }
        val queryEntity=parameters["id"]?.let { catalog.byId(it) }
        val protocolCandidate=queryEntity?.containsKey("protocolCandidateSpaceId")==true || catalog.protocolCandidateSpaces.any {it.id==parameters["space"]}
        if(protocolCandidate)return protocolCandidateQuery(parameters,queryEntity)
        val logical=parameters.containsKey("selector") || queryEntity?.containsKey("logicalSpaceId")==true || catalog.logicalSpaces.any {it.id==parameters["space"] || it.instanceId==parameters["space"]}
        if(logical)return logicalQuery(parameters,queryEntity)
        require(parameters.keys.none {it in setOf("wordBits","componentProfile")}) {"Logical query options require a logical selector space"}
        val identity=mapOf("firmwareId" to activeFirmwareId,"firmwareSha256" to activeFirmwareSha256,"dictionaryVersion" to catalog.version,"dictionarySha256" to catalog.artifactSha256,"dictionaryDigestKind" to "compiled-payload-sha256","executionMode" to mode)
        val space=parameters["space"] ?: "cpu-physical"
        val memory=parameters["memoryProfile"] ?: activeMemoryProfile ?: catalog.defaultMemoryProfile
        val runtimeRAM=if(parameters["memoryProfile"]==null)additionalMemory?.regions.orEmpty()else emptyList()
        val view=if(publicTest!=null) {
            check(activePorts!=null && activeView!=null) { "TEST fixture query requires validated and bound memory" }
            if(parameters["memoryProfile"]==null)checkNotNull(activeView) else checkNotNull(publicTest).view(memory)
        } else if(bootRomLayout!=null) {
            check(activePorts!=null && activeView!=null) { "Boot ROM experiment query requires validated and bound memory" }
            if(parameters["memoryProfile"]==null)checkNotNull(activeView) else checkNotNull(bootRomLayout).view(memory)
        } else null
        val entityId=parameters["id"]
        val relatedWindows=entityId?.let(::entityWindows).orEmpty()
        val relatedLogical=entityId?.let(::entityLogicalSpaces).orEmpty()
        val logicalActive=relatedLogical.any {it.portId in activeLogicalPorts.orEmpty()}
        val physicalActive=relatedWindows.any {it.portId in activePorts.orEmpty()}
        val entityProfiles=(if(physicalActive)activeComponentProfiles else emptyMap())+(if(logicalActive)activeLogicalProfiles else emptyMap())
        val result=when {
            entityId!=null -> if(parameters["property"]!=null)catalog.property(entityId,parameters.getValue("property"),entityProfiles) else {
                val entity=catalog.byId(entityId,entityProfiles)
                mapOf("entity" to entity,"status" to if(entity==null)"UNKNOWN" else "DECLARED")
            }
            parameters["coverage"]=="true" -> mapOf("coverage" to catalog.coverage(space,parameters["base"]?.let(::integer) ?: 0L,parameters["endExclusive"]?.let(::integer) ?: 0x1_0000_0000L,memory,mode,view,activeComponentProfiles,runtimeRAM))
            else -> {
                val address=integer(parameters["address"] ?: "0");val width=Math.toIntExact(integer(parameters["width"] ?: "4"))
                catalog.query(space,address,width,parameters["direction"],memory,mode,view,profilesForAddress(space,address,width),runtimeRAM).toMap()
            }
        }
        val candidate=result["candidate"] as? Map<*,*>
        val inputs=settings["inputs"]?.let(Formats::map).orEmpty()
        val entity=result["entity"] as? Map<*,*>
        val port=((result["window"] as? Map<*,*>)?.get("portId") ?: entity?.get("portId") ?: entityId?.let { catalog.byId(it)?.get("portId") })?.toString()
        val componentId=(entity?.get("componentId") ?: result["componentId"])?.toString()
        val inactive=port!=null && activePorts!=null && port !in checkNotNull(activePorts)
        val bridgedSpecs=if(mode!="strict" && inactive)PromotedLegacyCandidates.byPortId[port].orEmpty().filter { spec -> spec.allPorts.none { it in activePorts.orEmpty() } } else emptyList()
        val bridgedSpec=if(bridgedSpecs.isEmpty())null else if(parameters["address"]!=null) {
            val addr=integer(parameters.getValue("address"))
            val width=Math.toIntExact(integer(parameters["width"] ?: "4"))
            bridgedSpecs.firstOrNull { addr>=it.base && addr+width<=it.base+it.length }
        } else bridgedSpecs.firstOrNull { it.id in inputs || (it.id==HdmiStatusSamples.REGION && "hdmiStatusPairs" in settings) } ?: bridgedSpecs.firstOrNull()
        val staticBridgedConfigured=bridgedSpec!=null && (bridgedSpec.id in inputs || (bridgedSpec.id==HdmiStatusSamples.REGION && "hdmiStatusPairs" in settings))
        val dynamicBridged=!staticBridgedConfigured && mode!="strict" && inactive && PromotedLegacyCandidates.isDynamicPortConfigured(port,settings)
        val dynamicPolicy=if(dynamicBridged)PromotedLegacyCandidates.dynamicPolicyForPort(checkNotNull(port),settings) else null
        val bridgedConfigured=staticBridgedConfigured || dynamicBridged
        val bridgedInput=if(bridgedSpec!=null && staticBridgedConfigured)inputs[bridgedSpec.id]?.let(Formats::map) else null
        val selected=candidate?.get("id")?.toString()?.let { inputs[it]?.let(Formats::map) } ?: bridgedInput
        val selectedPolicy=selected?.get("strategy")?.toString() ?: if(dynamicBridged)dynamicPolicy else if(staticBridgedConfigured)bridgedSpec.policy else result["policy"]
        val effectiveInactive=inactive && !bridgedConfigured
        val policyTarget=candidate!=null || bridgedConfigured
        val policyReadOnly=policyTarget && parameters["direction"]=="write" && selectedPolicy in setOf("image-backed","fixed-input","scripted-read",DiscoveryStrategy.IMAGE_RESULT_SELECTOR10.id,DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE.id,DiscoveryStrategy.INTERLEAVED_REPLY.id,DiscoveryStrategy.PROGRAMMED_REPLY.id)
        val legacyResultHole = (candidate?.get("id") ?: bridgedSpec?.id) == IMAGE_RESULT_SURFACE && selectedPolicy == "image-backed" &&
            parameters["address"]?.let { integer(it) in SELECTOR10_WORDS } == true
        val policyRefuse=policyTarget && (selectedPolicy=="refuse" || legacyResultHole)
        val extra=if(effectiveInactive || policyReadOnly || policyRefuse)mapOf("permitted" to false,"refusalReason" to if(effectiveInactive)"known port is not active" else "selected discovery input policy refuses direction")
            else if(selected!=null && selectedPolicy in setOf("image-backed","fixed-input","retained-bytes","scripted-read",DiscoveryStrategy.IMAGE_RESULT_SELECTOR10.id,DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE.id,DiscoveryStrategy.POLL_7801C.id,DiscoveryStrategy.SETUP_REPEAT_7801C.id,DiscoveryStrategy.SETUP_REPEATABLE_7801C.id,DiscoveryStrategy.WRITE_49530.id,DiscoveryStrategy.WRITE_49530_REPEATABLE.id,DiscoveryStrategy.TAIL_49500.id,DiscoveryStrategy.TAIL_49500_REPEATABLE.id) && result["refusalReason"]=="candidate policy refuses")
                mapOf("permitted" to true,"refusalReason" to null) else emptyMap()
        return result + identity + mapOf("evidenceClass" to if(result["staticDictionaryDeclaration"]==false || bootRomLayout!=null)"HYPOTHESIS" else if(view!=null)"TEST" else "DICTIONARY","deviceReadPerformed" to false,"guestStateChanged" to false,"runtimeActivation" to if(relatedLogical.isNotEmpty() && entityId!=null) {
                if(logicalActive || physicalActive)"ACTIVE_COMPONENT_DEFINITION"
                else if(activeLogicalPorts==null && (relatedWindows.isEmpty() || activePorts==null))"NOT_BOUND" else "INACTIVE_COMPONENT_DEFINITION"
            } else if(activePorts==null)"NOT_BOUND" else if(port==null && componentId!=null) { if(relatedWindows.any { it.portId in activePorts.orEmpty() })"ACTIVE_COMPONENT_DEFINITION" else "INACTIVE_COMPONENT_DEFINITION" } else if(port==null)"NOT_A_KNOWN_PORT" else if(inactive)"INACTIVE" else "ACTIVE",
            "activeWindowIds" to relatedWindows.filter {it.portId in activePorts.orEmpty()}.map {it.id},
            "inactiveWindowIds" to relatedWindows.filter {it.portId !in activePorts.orEmpty()}.map {it.id},
            "activeComponentProfiles" to activeComponentProfiles,"memoryProfile" to memory,"activeMemoryProfile" to activeMemoryProfile,"memoryView" to if(publicTest!=null)"public-synthetic-test" else if(bootRomLayout!=null)"boot-rom-experiment" else if(parameters["memoryProfile"]!=null)"explicit-static-profile" else if(runtimeRAM.isNotEmpty())"active-profile-with-additional-ram" else if(activeMemoryProfile!=null)"active-profile" else "unbound-static-default",
            "policy" to selectedPolicy,"selectedInput" to selected,"policySource" to if(result["staticDictionaryDeclaration"]==false)"owner-selected-additional-memory" else if(selected==null)"dictionary" else "frozen-runtime-configuration") +
            (if(relatedLogical.isNotEmpty())mapOf("activeLogicalSpaceIds" to relatedLogical.filter {it.portId in activeLogicalPorts.orEmpty()}.map {it.id},
                "inactiveLogicalSpaceIds" to relatedLogical.filter {it.portId !in activeLogicalPorts.orEmpty()}.map {it.id},"activeLogicalProfiles" to activeLogicalProfiles)else emptyMap()) + extra
    }
    private fun entityLogicalSpaces(id:String):List<com.github.nyabanban.fuji.emulator.catalog.CatalogLogicalSpace> {
        val row=catalog.byId(id) ?: return emptyList()
        val definitions=(row["logicalSpaces"] as? List<*>)?.mapNotNull {(it as? Map<*,*>)?.get("id")}.orEmpty()
        return catalog.logicalSpaces.filter {space ->
            id==space.id || id==space.instanceId || id==space.deviceId || space.id in definitions ||
                space.registers.any {register->register.id==id || register.fields.any {it.id==id}}
        }
    }
    private fun entityWindows(id:String):List<com.github.nyabanban.fuji.emulator.catalog.CatalogWindow> {
        val row=catalog.byId(id) ?: return emptyList()
        return catalog.windowsFor(activeComponentProfiles).filter { window ->
            window.id==id || window.definitionId==id || window.registers.any { register ->
                register.id==id || register.fields.any { it.id==id }
            }
        }.ifEmpty { catalog.windowsFor(activeComponentProfiles).filter { it.componentId==row["componentId"] } }
    }
    /** An inactive address never receives the active component's profile permissions. */
    private fun profilesForAddress(space:String,address:Long,width:Int):Map<String,String> {
        if(space!="cpu-physical" || width<=0 || address<0 || address>0x1_0000_0000L-width)return emptyMap()
        val window=(activeView?.windows ?: catalog.windowsFor(activeComponentProfiles)).singleOrNull { it.space==space && it.interval.contains(address,width) }
        return if(window!=null && window.portId in activePorts.orEmpty())activeComponentProfiles else emptyMap()
    }
    fun createBus(context: DiscoveryContext): DiscoveryBus? {
        if(mode=="strict")return null
        val rawInputs=settings["inputs"]?.let(Formats::map).orEmpty()
        var selectC000StopReply: String? = null
        val activePortSet = activePorts.orEmpty()
        val activePromotedIds = (PromotedLegacyCandidates.all
            .filter { spec -> spec.allPorts.any { it in activePortSet } }
            .map { it.id } + PromotedLegacyCandidates.activeDynamicRegionIds(activePortSet))
            .toSet()
        if (PromotedLegacyCandidates.isDynamicPortConfigured(PromotedLegacyCandidates.ISP_REPLY_PORT, settings)) {
            require(PromotedLegacyCandidates.ISP_REPLY_PORT !in activePortSet) {
                "Discovery image reply settings conflict with active component port ${PromotedLegacyCandidates.ISP_REPLY_PORT}"
            }
        }
        if ("imageConfigPairs" in settings) {
            for (bankPort in PromotedLegacyCandidates.imageConfigPortsByRegionId.values) {
                require(bankPort !in activePortSet) {
                    "Discovery imageConfigPairs conflicts with active component port $bankPort"
                }
            }
        }
        if ("channel4Programming" in settings) {
            for (chPort in PromotedLegacyCandidates.channel4Ports) {
                require(chPort !in activePortSet) {
                    "Discovery channel4Programming conflicts with active component port $chPort"
                }
            }
        }
        if ("channel7Programming" in settings) {
            for (chPort in PromotedLegacyCandidates.channel7Ports) {
                require(chPort !in activePortSet) {
                    "Discovery channel7Programming conflicts with active component port $chPort"
                }
            }
        }
        if ("channel11Programming" in settings) {
            for (chPort in PromotedLegacyCandidates.channel11Ports) {
                require(chPort !in activePortSet) {
                    "Discovery channel11Programming conflicts with active component port $chPort"
                }
            }
        }
        if ("image70000" in settings || ImageIrq106Status.KEY in settings) {
            require(PromotedLegacyCandidates.ISP_CORE_PORT !in activePortSet) {
                "Discovery image70000/imageIrq106Status conflicts with active component port ${PromotedLegacyCandidates.ISP_CORE_PORT}"
            }
        }
        if ("image50000" in settings || "image50000Control" in settings) {
            require(PromotedLegacyCandidates.ISP_BANK_50000_PORT !in activePortSet) {
                "Discovery image50000/image50000Control conflicts with active component port ${PromotedLegacyCandidates.ISP_BANK_50000_PORT}"
            }
        }
        if ("image51000" in settings) {
            for (port in PromotedLegacyCandidates.isp51000Ports) {
                require(port !in activePortSet) {
                    "Discovery image51000 conflicts with active component port $port"
                }
            }
        }
        if ("image59000" in settings) {
            require(PromotedLegacyCandidates.ISP_LUT_59000_PORT !in activePortSet) {
                "Discovery image59000 conflicts with active component port ${PromotedLegacyCandidates.ISP_LUT_59000_PORT}"
            }
        }
        if ("image68000" in settings) {
            require(PromotedLegacyCandidates.ISP_BANK_68000_PORT !in activePortSet) {
                "Discovery image68000 conflicts with active component port ${PromotedLegacyCandidates.ISP_BANK_68000_PORT}"
            }
        }
        if ("image6e000" in settings) {
            require(PromotedLegacyCandidates.ISP_BANK_6E000_PORT !in activePortSet) {
                "Discovery image6e000 conflicts with active component port ${PromotedLegacyCandidates.ISP_BANK_6E000_PORT}"
            }
        }
        if (ImageResultStats.KEY in settings) {
            for (port in PromotedLegacyCandidates.ispResultStatsPorts) {
                require(port !in activePortSet) {
                    "Discovery ${ImageResultStats.KEY} conflicts with active component port $port"
                }
            }
        }
        if (ImageLiveViewBanks.KEY in settings) {
            for (port in PromotedLegacyCandidates.ispLiveViewPorts) {
                require(port !in activePortSet) {
                    "Discovery ${ImageLiveViewBanks.KEY} conflicts with active component port $port"
                }
            }
        }
        if (BootRomColdStart.KEY in settings && settings[BootRomColdStart.KEY] != BootRomColdStart.RELEASE_COLDSTART_PROFILE) {
            require("soc-dram.controller-0" !in activePortSet) {
                "Discovery ${BootRomColdStart.KEY} conflicts with active component port soc-dram.controller-0"
            }
            if (settings[BootRomColdStart.KEY] in BootRomColdStart.STAGE1_DDR_PROFILES) {
                require("soc-dram.phy-0" !in activePortSet) {
                    "Discovery ${BootRomColdStart.KEY} conflicts with active component port soc-dram.phy-0"
                }
            }
        }
        require(rawInputs.keys.all { id -> catalog.candidates.any { it.id==id } || id in PromotedLegacyCandidates.byId }) {
            "Discovery input outside selected candidate regions"
        }
        val bridgedSpecs = PromotedLegacyCandidates.all.filter { spec ->
            val configured = spec.id in rawInputs ||
                (spec.id == HdmiStatusSamples.REGION && "hdmiStatusPairs" in settings) ||
                (spec.id == PromotedLegacyCandidates.LIVE_VIEW_CONFIG_WORDS_REGION && ImageLiveViewBanks.KEY in settings)
            if (configured) {
                for (conflictPort in spec.allPorts) {
                    require(conflictPort !in activePortSet) {
                        "Discovery input ${spec.id} conflicts with active component port $conflictPort"
                    }
                }
            }
            configured
        }

        fun buildRegion(
            id: String,
            deviceId: String,
            base: Long,
            length: Int,
            defaultPolicy: String,
            defaultInitialFill: Int?,
            widths: Set<Int>,
            alignment: Int,
            littleEndian: Boolean,
            prohibited: List<AddressInterval>,
            evidence: List<String>,
            readAllowed: IntArray,
            writeAllowed: IntArray,
            configuredInput: Map<String, Any>?,
            isCatalogCandidate: Boolean,
        ): DiscoveryRegion? {
            val hasCompletion = configuredInput?.containsKey("completion") == true
            val input = if (hasCompletion) {
                requireNotNull(configuredInput)
                when (id) {
                    RETAINED_6C000_REGION -> {
                        val completion = configuredInput["completion"] as? String
                        require(completion in C000StopReply.PROFILES)
                        selectC000StopReply = completion
                    }
                    RETAINED_6C204_REGION -> require(configuredInput["completion"] == C204_COMPLETION)
                    else -> error("Unsupported completion for $id")
                }
                require(configuredInput.keys == setOf("strategy", "fill", "completion") && configuredInput["strategy"] == "retained-bytes")
                configuredInput - "completion"
            } else configuredInput
            val strategy=DiscoveryStrategy.parse(input?.get("strategy")?.toString() ?: defaultPolicy)
            if (id == IMAGE_RESULT_SURFACE) {
                if (strategy == DiscoveryStrategy.REFUSE) {
                    require(input == null || input.keys == setOf("strategy"))
                    return null
                }
                require(strategy == DiscoveryStrategy.IMAGE || strategy.isImageResultSelector10) { "Image result surface requires image-backed input" }
            }
            if (id == TAIL_49500_REGION) {
                require(input == null || input.keys == setOf("strategy"))
                if (strategy == DiscoveryStrategy.REFUSE) return null
                require(strategy in setOf(DiscoveryStrategy.TAIL_49500, DiscoveryStrategy.TAIL_49500_REPEATABLE))
            }
            if (strategy in setOf(DiscoveryStrategy.TAIL_49500, DiscoveryStrategy.TAIL_49500_REPEATABLE)) require(id == TAIL_49500_REGION && input != null)
            if (id == WRITE_49530_REGION) {
                require(input == null || input.keys == setOf("strategy"))
                if (strategy == DiscoveryStrategy.REFUSE) return null
                require(strategy in setOf(DiscoveryStrategy.WRITE_49530, DiscoveryStrategy.WRITE_49530_REPEATABLE))
            }
            if (strategy in setOf(DiscoveryStrategy.WRITE_49530, DiscoveryStrategy.WRITE_49530_REPEATABLE)) require(id == WRITE_49530_REGION && input != null)
            if (id in retainedConfigRegions) {
                if (strategy == DiscoveryStrategy.REFUSE) {
                    require(input == null || input.keys == setOf("strategy"))
                    return null
                }
                require(strategy == DiscoveryStrategy.RETAINED && input?.keys == setOf("strategy", "fill"))
            }
            if (id == POLL_7801C_REGION) {
                require(input == null || input.keys == setOf("strategy"))
                if (strategy == DiscoveryStrategy.REFUSE) return null
                require(strategy in setOf(DiscoveryStrategy.POLL_7801C, DiscoveryStrategy.SETUP_REPEAT_7801C, DiscoveryStrategy.SETUP_REPEATABLE_7801C))
            }
            if (strategy in setOf(DiscoveryStrategy.POLL_7801C, DiscoveryStrategy.SETUP_REPEAT_7801C, DiscoveryStrategy.SETUP_REPEATABLE_7801C))
                require(id == POLL_7801C_REGION && input != null)
            if (id == PanelInitOnce.REGION) require(strategy in setOf(DiscoveryStrategy.REFUSE, DiscoveryStrategy.PANEL_ONCE, DiscoveryStrategy.PANEL_TWICE))
            if (strategy == DiscoveryStrategy.PANEL_ONCE || strategy == DiscoveryStrategy.PANEL_TWICE)
                require(input?.keys == setOf("strategy") && id == PanelInitOnce.REGION)
            require(length in 1..1048576)
            input?.let { require(it.keys.all { key -> key in setOf("strategy","fill","file","sha256","value","samples","selectionValues") }) }
            val initial=when(strategy) {
                DiscoveryStrategy.IMAGE, DiscoveryStrategy.IMAGE_RESULT_SELECTOR10,
                DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE -> {
                    require(input!=null && input.keys==setOf("strategy","file","sha256")) { "image-backed needs explicit file and SHA" }
                    readDiscoveryImage(input,length)
                }
                DiscoveryStrategy.RETAINED -> {
                    require(input==null || input.keys==setOf("strategy","fill") || input.keys==setOf("strategy","file","sha256")) {
                        "retained-bytes needs explicit fill or exact file identity"
                    }
                    if(input!=null && "file" in input)readDiscoveryImage(input,length) else {
                        val fill=if(input==null)defaultInitialFill?.toLong() ?: error("Missing explicit retained fill") else integer(input.getValue("fill"))
                        require(fill in 0..255) { "retained fill outside byte range" }
                        ByteArray(length) { fill.toByte() }
                    }
                }
                else -> null
            }
            if(strategy==DiscoveryStrategy.FIXED)require(input!=null && (input.keys==setOf("strategy","value") || input.keys==setOf("strategy","selectionValues"))) {
                "fixed-input requires exactly one value or selectionValues"
            }
            val fixed=if(strategy==DiscoveryStrategy.FIXED && input!!.containsKey("value"))integer(input.getValue("value")) else null
            val selections=if(strategy==DiscoveryStrategy.FIXED && input!!.containsKey("selectionValues")) {
                val raw=input["selectionValues"] as? List<*> ?: error("Finite fixed selection list required")
                raw.map { integer(requireNotNull(it) { "Fixed selection values cannot be null" }) }
            } else null
            val samples=if(strategy==DiscoveryStrategy.SCRIPT) {
                require(input!=null && input.keys==setOf("strategy","samples")); val raw=input["samples"] as? List<*> ?: error("Finite sample list required")
                raw.map { it?.let(::integer) }
            } else null
            if(strategy==DiscoveryStrategy.REFUSE)require(input==null || input.keys==setOf("strategy"))
            if (!isCatalogCandidate) {
                val explicitInput = input != null && strategy != DiscoveryStrategy.REFUSE
                if (!explicitInput) {
                    readAllowed.fill(0)
                    writeAllowed.fill(0)
                }
            }
            return DiscoveryRegion(id,deviceId,base,length,strategy,widths,alignment,littleEndian,
                initial,fixed,samples,prohibited,evidence,readAllowed,writeAllowed,selectionValues=selections,clearC204Start=hasCompletion && id == RETAINED_6C204_REGION)
        }

        val catalogRegions=catalog.candidates.mapNotNull { c ->
            require(c.space=="cpu-physical")
            // A generic temporary byte model cannot implement reserved/forbidden/W1C bit laws.
            for(raw in c.data["registers"] as List<*>) {
                val register=raw as Map<*,*>
                val masks=register["masks"] as Map<*,*>
                require((register["writeWidths"] as List<*>).isEmpty() || listOf("writable","preserve","prohibited").all { integer(checkNotNull(masks[it]))==0L }) {
                    "Candidate write masks require a qualified implementation: "+register["id"]
                }
            }
            val configuredInput=rawInputs[c.id]?.let(Formats::map)
            val length=Math.toIntExact(c.size)
            val read=IntArray(length); val write=IntArray(length)
            for(offset in 0 until length)for(width in c.widths) {
                val readQuery=catalog.query(c.space,c.base+offset,width,"read",mode=mode,componentProfiles=activeComponentProfiles)
                val writeQuery=catalog.query(c.space,c.base+offset,width,"write",mode=mode)
                val explicitInput=configuredInput!=null && configuredInput["strategy"]!=DiscoveryStrategy.REFUSE.id
                if(readQuery.permitted || (explicitInput && readQuery.refusalReason=="candidate policy refuses"))read[offset]=read[offset] or width
                if(writeQuery.permitted || (explicitInput && writeQuery.refusalReason=="candidate policy refuses"))write[offset]=write[offset] or width
            }
            buildRegion(
                id = c.id,
                deviceId = c.deviceId,
                base = c.base,
                length = length,
                defaultPolicy = c.policy,
                defaultInitialFill = c.initialFill,
                widths = c.widths.toSet(),
                alignment = c.alignment,
                littleEndian = c.byteOrder in setOf("little","little-endian"),
                prohibited = c.prohibited.map { AddressInterval(it.base,it.endExclusive) },
                evidence = (((c.data["provenance"] as? Map<*,*>)?.get("base") as? Map<*,*>)?.get("evidence") as? List<*>)?.map { it.toString() }.orEmpty(),
                readAllowed = read,
                writeAllowed = write,
                configuredInput = configuredInput,
                isCatalogCandidate = true,
            )
        }
        val legacyPromotedRegions = bridgedSpecs.mapNotNull { spec ->
            buildRegion(
                id = spec.id,
                deviceId = spec.deviceId,
                base = spec.base,
                length = spec.length,
                defaultPolicy = spec.policy,
                defaultInitialFill = null,
                widths = spec.widths,
                alignment = spec.alignment,
                littleEndian = spec.littleEndian,
                prohibited = spec.prohibited,
                evidence = spec.evidence,
                readAllowed = spec.readAllowed.clone(),
                writeAllowed = spec.writeAllowed.clone(),
                configuredInput = rawInputs[spec.id]?.let(Formats::map),
                isCatalogCandidate = false,
            )
        }
        val regions = catalogRegions + legacyPromotedRegions + listOfNotNull(diagnosticProfile?.let { d ->
            DiscoveryRegion(d.id,d.inventoryId,d.base,Math.toIntExact(d.endExclusive-d.base),DiscoveryStrategy.RETAINED,
                setOf(d.width),d.width,true,evidence=d.evidence,
                retainedDiagnostic=RetainedWriteDiagnostic(d.writeValue,d.successfulReadLimit))
        }, singleReadProfile?.let { d ->
            DiscoveryRegion(d.id,d.inventoryId,d.base,Math.toIntExact(d.endExclusive-d.base),DiscoveryStrategy.SCRIPT,
                setOf(d.width),d.width,true,samples=selectedSamples,evidence=d.evidence)
        }) + settings["channel7Programming"]?.let { selection ->
            require(selection == "retained-words-zero-v1")
            listOf(0xfff3c000L to 4, 0xfff3c02cL to 4, 0xfff3c170L to 16).map { (base, length) ->
                DiscoveryRegion("diagnostic.channel7.${base.toString(16)}", "kernel-0005a1f8", base, length,
                    DiscoveryStrategy.RETAINED, setOf(4), 4, true, initial = ByteArray(length))
            }
        }.orEmpty() + listOf(4, 11).flatMap { channel ->
            val selection = settings["channel${channel}Programming"] ?: return@flatMap emptyList()
            val readback = selection == DiscoveryStrategy.CHANNEL_CONTROL.id
            require(selection == "write-words-v1" || readback)
            val controlAddress = 0xfff3c010L + 4 * channel
            listOf(controlAddress to 4, (0xfff3c100L + 16 * channel) to 16).map { (base, length) ->
                val controlReadback = readback && base == controlAddress
                DiscoveryRegion("diagnostic.channel$channel.${base.toString(16)}", "kernel-0005a1f8", base, length,
                    if (controlReadback) DiscoveryStrategy.CHANNEL_CONTROL else DiscoveryStrategy.RETAINED,
                    setOf(4), 4, true, initial = ByteArray(length),
                    readAllowed = if (controlReadback) intArrayOf(4, 0, 0, 0) else IntArray(length))
            }
        } + settings["imageConfigPairs"]?.let { selection ->
            require(selection == "retained-words-zero-v1")
            (0..6).map { bank ->
                val base = 0xfff6c280L + bank * 64
                val length = if (bank == 6) 44 else 28
                DiscoveryRegion("diagnostic.image-config.${base.toString(16)}", "section4-011b66bc", base, length,
                    DiscoveryStrategy.RETAINED, setOf(4), 4, true, initial = ByteArray(length))
            }
        }.orEmpty() + settings["imageFace7c000"]?.let { selection ->
            require(selection in ImageFace7c000.ALL_PROFILES)
            if (selection == ImageFace7c000.IRQ130_PROFILE) {
                listOf(ImageFace7c000.region())
            } else {
                val readyMode = selection == ImageFace7c000.STATUS_READY_PROFILE
                val initialBytes = ByteArray(4096).apply {
                    if (readyMode) {
                        repeat(4) { n -> this[0x700 + n] = (FACE_7C000_READY_BITS ushr (n * 8)).toByte() }
                    }
                }
                listOf(
                    DiscoveryRegion(
                        FACE_7C000_REGION,
                        "section5-011ff890",
                        0xfff7c000L,
                        4096,
                        DiscoveryStrategy.RETAINED,
                        setOf(4),
                        4,
                        true,
                        initial = initialBytes,
                        faceStatusReady = readyMode,
                        evidence = listOf(
                            "DAT section5 011FF890..011FF9D8 (caller 025A988C..025A9FA4 face): aligned 4-byte config/status block 0xFFF7C000..0xFFF7CFFF",
                            if (readyMode) {
                                "DAT section4 01201AD4/01201B94/01201DD8/01201E00 & section5 025A9798..025A9FD4: 0xFFF7C700 bits {0,4,8,16,20,24} (0x01110111) ready/done; 0xFFF7C708=0 sets ready, nonzero clears; 0xFFF7C738=1 sets 0xFFF7C700 ready, =0 sets 0xFFF7C734 bit 0"
                            } else {
                                "HYPOTHESIS retained-words-zero-v1: zero-initialized retained 32-bit registers"
                            },
                        ),
                    ),
                )
            }
        }.orEmpty()
        val replyKeys = listOf("imageReply820001", "imageReply820040", "imageReply820041").filter { it in settings }
        val replyRegions = replyKeys.map { key ->
            val selection = settings[key]
            val (region, firstWord) = when (key to selection) {
                "imageReply820001" to "zero-once-v1" -> IMAGE_REPLY_820001 to 0L
                "imageReply820001" to "af-once-v1" -> IMAGE_REPLY_820001_AF to 0x00af0000L
                "imageReply820040" to "zero-once-v1" -> IMAGE_REPLY_820040 to 0L
                "imageReply820040" to "zero-alternating-16-pairs-v1" -> IMAGE_REPLY_PAIRS to 0L
                "imageReply820041" to "zero-once-v1" -> IMAGE_REPLY_820041 to 0L
                else -> error("Unsupported $key selection")
            }
            val paired = region == IMAGE_REPLY_PAIRS
            val requests = if (paired) "16 ordered pairs of 82 00 40 and 82 00 41 requests"
                else "one ${key.takeLast(6).chunked(2).joinToString(" ")} request"
            DiscoveryRegion(region, "section5-023937c0", 0xfff6c224L, 32,
                DiscoveryStrategy.SCRIPT, setOf(4), 4, true,
                samples = if (paired) List(IMAGE_REPLY_TOTAL_WORDS) { 0L } else listOf(firstWord) + List(7) { 0L },
                evidence = listOf("HYPOTHESIS $selection for $requests"))
        }
        val programmedSelection = settings[ProgrammedImageReply.KEY] as? String
        val programmed = ProgrammedImageReply.KEY in settings
        if (programmed) require(programmedSelection in ProgrammedImageReply.PROFILES &&
            replyRegions.size == 2 && regions.any { it.id == RETAINED_6C204_REGION && it.clearC204Start } &&
            regions.any { it.id == RETAINED_6C20C_REGION && it.strategy == DiscoveryStrategy.RETAINED })
        val imageReply = if (replyRegions.size <= 1) replyRegions.singleOrNull() else {
            require(replyRegions.size == 2)
            val pair = replyRegions.single { it.id == IMAGE_REPLY_PAIRS }
            val single = replyRegions.single { it.id == IMAGE_REPLY_820001 || it.id == IMAGE_REPLY_820001_AF }
            val replyStrategy = when (programmedSelection) {
                ProgrammedImageReply.REPEATABLE_PROFILE -> DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE
                ProgrammedImageReply.PROFILE -> DiscoveryStrategy.PROGRAMMED_REPLY
                else -> DiscoveryStrategy.INTERLEAVED_REPLY
            }
            pair.copy(strategy = replyStrategy,
                samples = pair.samples!! + single.samples!!, evidence = pair.evidence + single.evidence +
                    if (programmed) listOf("HYPOTHESIS $programmedSelection: completed guest 81 00 01 write then 82 00 01 read") else emptyList())
        }
        val image59000 = settings["image59000"]?.let { selection ->
            require(selection == "retained-tables-v1")
            val size = 0x2c00
            DiscoveryRegion(IMAGE_59000_TABLES, "section5-021f2a78", 0xfff59000L, size, DiscoveryStrategy.RETAINED,
                setOf(4), 4, true, initial = ByteArray(size), readAllowed = IntArray(size),
                prohibited = listOf(AddressInterval(0xfff59900, 0xfff5a000), AddressInterval(0xfff5ac00, 0xfff5b000)),
                evidence = listOf("on-51008 tick 833252378 core1 0005AF98/011C44F0 WRITE4 FFF59000=02000000",
                    "011C4480 packs nine 256-byte tables; 021F2EF4/021F2F1C call 011C508C/011C516C for two triples of 256 words",
                    "HYPOTHESIS: retain aligned guest writes within the three table spans; no table readback"))
        }
        val imageInputs = settings["image50000"]?.let { selection ->
            val configRmw = selection == "retained-words-502b0-rmw-v1"
            require(selection == "retained-words-zero-v1" || configRmw)
            val reads = listOf(4..16, 36..40, 76..76, 248..248, 356..368, 388..392, 548..548,
                568..568, 588..592, 612..612, 676..676, 812..812, 828..828) + listOfNotNull(
                (0x248..0x248).takeIf { image59000 != null }, (0x2b0..0x2b0).takeIf { configRmw })
            val writes = listOf(4..76, 144..432, 548..612, 672..980, 4096..4100)
            (writes + reads).flatMap { range -> range step 4 }.distinct().sorted().map { offset ->
                val base = 0xfff50000L + offset
                DiscoveryRegion("diagnostic.image-word.${base.toString(16)}", "section4-011c0fc0", base, 4,
                    DiscoveryStrategy.RETAINED, setOf(4), 4, true, initial = ByteArray(4),
                    readAllowed = intArrayOf(if (reads.any { offset in it }) 4 else 0, 0, 0, 0),
                    writeAllowed = intArrayOf(if (writes.any { offset in it }) 4 else 0, 0, 0, 0),
                    evidence = if (configRmw && offset == 0x2b0) listOf(
                        "on-59000 tick 833292967 core1 READ4 0005AFE0/011C52C0; 011C52C8 replaces bits 29:16",
                        "011C52D0 writes the merged word; 011C5430 later replaces it with two packed fields")
                    else emptyList())
            }
        }.orEmpty()
        val imageControl = settings["image50000Control"]?.let { selection ->
            val strategy = when (selection) {
                DiscoveryStrategy.IMAGE_50000_CONTROL.id -> DiscoveryStrategy.IMAGE_50000_CONTROL
                DiscoveryStrategy.IMAGE_50000_CONTROL_TWICE.id -> DiscoveryStrategy.IMAGE_50000_CONTROL_TWICE
                DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE.id -> DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE
                else -> error("Unknown image50000Control selection: $selection")
            }
            DiscoveryRegion(IMAGE_50000_CONTROL_REGION, "section4-011c0f50", 0xfff50000L, 4,
                strategy, setOf(4), 4, true,
                evidence = listOf("ON core1 011C0F74 writes zero; 011C0F84 reads the same word and 011C0F88 tests bit 0",
                    "HYPOTHESIS: the first poll returns 1 once") +
                    (if (strategy == DiscoveryStrategy.IMAGE_50000_CONTROL_TWICE ||
                        strategy == DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE)
                        listOf("T79 1540 repeats WRITE4 zero at tick 860677416; HYPOTHESIS: its first poll also returns 1")
                    else emptyList()) +
                    if (strategy == DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE)
                        listOf("HYPOTHESIS: repeatable core1 WRITE4 0 (LR 011C0F78) followed by READ4 -> 1 (LR 011C0F88)")
                    else emptyList())
        }
        val configurationBytes=Formats.jsonBytes(config)
        val identity=MessageDigest.getInstance("SHA-256").digest(configurationBytes).joinToString("") { "%02x".format(it) }
        val capacity=Math.toIntExact(settings["logCapacity"]?.let(::integer) ?: 16384)
        val disk=settings["diskBudgetBytes"]?.let(::integer) ?: 32L*1024*1024
        val journal=DiscoveryJournal(
            java.util.UUID.randomUUID().toString(),
            catalog.version,
            catalog.artifactSha256,
            catalog.firmwareSha256,
            identity,
            capacity,
            disk,
            allowOverflow = mode == RelaxedPeripheralFallback.MODE,
        )
        val image70000 = settings["image70000"]?.let(Image70000::region)
        val image6e000 = settings["image6e000"]?.let(Image6e000::region)
        val image68000 = settings["image68000"]?.let(Image68000::region)
        val imageTables = settings["image51000"]?.let(Image51000::regions).orEmpty()
        val imageLiveView = settings[ImageLiveViewBanks.KEY]?.let(ImageLiveViewBanks::regions).orEmpty()
        val imageScalerF000 = settings[ImageScalerF000.KEY]?.let(ImageScalerF000::region)
        val imageScalerE000 = settings[ImageScalerE000.KEY]?.let(ImageScalerE000::region)
        val playDspFa00 = settings["playDspFa00"]?.let {
            require(it == "fa000000-ready-idle-v1") { "Unknown playDspFa00 selection: $it" }
            val dspInitial = ByteArray(0x1000).apply {
                repeat(4) { n -> this[n] = (PLAY_DSP_FA00_READY_BIT ushr (n * 8)).toByte() }
            }
            listOf(
                DiscoveryRegion(
                    id = PLAY_DSP_FA00_REGION,
                    deviceId = "section4-011e28b0",
                    base = 0xfa000000L,
                    length = 0x1000,
                    strategy = DiscoveryStrategy.RETAINED,
                    widths = setOf(1, 2, 4),
                    alignment = 1,
                    littleEndian = true,
                    initial = dspInitial,
                    dspFa00StatusReady = true,
                    evidence = listOf(
                        "DAT section4 011E28B0..011E30A0 (callers 011E1B58..011E2B9C): 0xFA000000 control/status + 0xFA000008..01C config + 0xFA000040..0EFF parameter/constant SRAM",
                        "HYPOTHESIS fa000000-ready-idle-v1: 0xFA000000 keeps bit 16 ready and clears bit 0 busy on write; remaining bytes retained",
                    ),
                ),
                DiscoveryRegion(
                    id = "diagnostic.play-dsp.fa400518",
                    deviceId = "section4-011e2dc8",
                    base = 0xfa400518L,
                    length = 4,
                    strategy = DiscoveryStrategy.RETAINED,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    initial = ByteArray(4),
                    evidence = listOf(
                        "DAT section4 011E2DC8..011E2DDC: aligned 4-byte DSP idle status word 0xFA400518 (bit 1 clear = idle)",
                        "HYPOTHESIS fa000000-ready-idle-v1: zero-initialized retained 32-bit register",
                    ),
                ),
                DiscoveryRegion(
                    id = "diagnostic.play-dsp.fff4a760",
                    deviceId = "section4-011e2f14",
                    base = 0xfff4a760L,
                    length = 0x10,
                    strategy = DiscoveryStrategy.RETAINED,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    initial = ByteArray(0x10),
                    evidence = listOf(
                        "DAT section4 011E2F14..011E2FD8: aligned 4-byte DSP config words 0xFFF4A760, 0xFFF4A768, 0xFFF4A76C",
                        "HYPOTHESIS fa000000-ready-idle-v1: zero-initialized retained 32-bit registers",
                    ),
                ),
                DiscoveryRegion(
                    id = "diagnostic.play-dsp.fff4aa10",
                    deviceId = "section4-011e2d84",
                    base = 0xfff4aa10L,
                    length = 0x14,
                    strategy = DiscoveryStrategy.RETAINED,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    initial = ByteArray(0x14),
                    evidence = listOf(
                        "DAT section4 011E2D84..011E3074 (caller 011E1BA0): aligned 4-byte bus/arbiter control words 0xFFF4AA10, 0xFFF4AA1C, 0xFFF4AA20",
                        "HYPOTHESIS fa000000-ready-idle-v1: zero-initialized retained 32-bit registers",
                    ),
                ),
            )
        }.orEmpty()
        val imageResultStats = settings[ImageResultStats.KEY]?.let(ImageResultStats::regions).orEmpty()
        val bootRomColdStartRegions = settings[BootRomColdStart.KEY]?.let(BootRomColdStart::regions).orEmpty()
        val allRegions = regions + imageInputs + imageTables + imageLiveView + imageResultStats + bootRomColdStartRegions + playDspFa00 +
            listOfNotNull(imageControl, imageReply, image59000, image70000, image6e000, image68000, imageScalerF000, imageScalerE000)
        val bridgedIntervals = allRegions
            .filter {
                it.id in PromotedLegacyCandidates.byId ||
                    it.id in PromotedLegacyCandidates.dynamicRegionIds ||
                    it.id in BootRomColdStart.ALL_REGION_IDS
            }
            .map { AddressInterval(it.base, it.endExclusive) }
        val knownIntervals = catalog.windowsFor(activeComponentProfiles)
            .map { AddressInterval(it.base, it.endExclusive) }
            .filterNot { w -> bridgedIntervals.any { b -> w.overlaps(b.base, b.endExclusive) } }
        return DiscoveryBus(allRegions,knownIntervals,journal,context,
            imageIrq106Profile = settings[ImageIrq106Status.KEY] as? String,
            imageScalerProfile = settings[ImageScalerF000.KEY] as? String,
            bootRomColdStartProfile = settings[BootRomColdStart.KEY] as? String,
            imageScalerE000Profile = settings[ImageScalerE000.KEY] as? String,
            ispAutoSyncProfile = settings[IspAutoSync.KEY] as? String,
            promotedRegions = activePromotedIds,
            relaxedContinuous = mode == RelaxedPeripheralFallback.MODE)
            .also { bus -> selectC000StopReply?.let(bus::selectC000StopReply) }
            .also { bus -> settings["hdmiStatusPairs"]?.let { bus.selectHdmiStatus(it, settings["hdmiLinkStatusReads"]) } }
            .also { if(singleReadProfile!=null || diagnosticProfile!=null)diagnosticBuses.add(it) }
    }
    /** Read and verify one bounded input; DiscoveryBus owns clones independent of this file. */
    private fun readDiscoveryImage(input:Map<String,Any>,length:Int):ByteArray {
        val file=input["file"]
        require(file is String && file.isNotBlank()) { "Discovery image path must be a nonblank string" }
        val sha256=input["sha256"]
        require(sha256 is String && sha256.matches(Regex("[a-f0-9]{64}"))) {
            "Discovery image SHA-256 must be 64 lowercase hex characters"
        }
        val path=containedPath(home,file)
        require(Files.isRegularFile(path)) { "Discovery image must be a regular file" }
        require(Files.size(path)==length.toLong()) { "Discovery image length differs" }
        val frozen=Files.newInputStream(path).use { it.readNBytes(length+1) }
        require(frozen.size==length && java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frozen))==sha256) {
            "Discovery image frozen identity differs"
        }
        return frozen
    }
    /** Adds static field references without calling devices or consuming experimental inputs. */
    fun observation(bus: DiscoveryBus): Map<String,Any?> = enrichObservation(bus.snapshot(),bus)
    fun boundedObservation(bus: DiscoveryBus, maximum: Int): Map<String,Any?> = enrichObservation(bus.boundedSnapshot(maximum),bus)
    private fun enrichObservation(snapshot: Map<String,Any?>,bus:DiscoveryBus): Map<String,Any?> {
        // Bindings remain fixed during this owner call. Never retain resolutions across observations.
        data class ResolutionKey(val address:Long,val width:Int,val direction:String)
        val resolutions=HashMap<ResolutionKey,Map<String,Any?>>()
        val selection=singleReadProfile?.takeIf { bus.scriptTailSelected(it.id) }?.let { diagnosticInputSelection(bus,it.id) }
        val start=(snapshot["exportStartIndex"] as? Number)?.toInt() ?: 0
        val accesses: List<Map<String,Any?>> = (snapshot.getValue("accesses") as List<*>).mapIndexed { index,raw ->
            @Suppress("UNCHECKED_CAST") val access=raw as Map<String,Any?>
            val key=ResolutionKey((access.getValue("addressPhysical") as Number).toLong(),
                (access.getValue("width") as Number).toInt(),access.getValue("direction").toString())
            val fields=resolutions[key] ?: run {
                val q=query(mapOf("space" to "cpu-physical","address" to key.address.toString(),
                    "width" to key.width.toString(),"direction" to key.direction))
                val resolved=mapOf("regionId" to q["regionId"],"deviceId" to q["deviceId"],"registerId" to q["registerId"],
                    "fieldIds" to q["fieldIds"],"classification" to q["classification"],"resolutionPath" to q["resolutionPath"],
                    "propertyProvenance" to (q["register"] as? Map<*,*>)?.get("provenance"),
                    "componentProfile" to q["componentProfile"],"declaredWriteWidths" to q["declaredWriteWidths"],
                    "effectiveWriteWidths" to q["effectiveWriteWidths"],"writeWidthsByProfile" to q["writeWidthsByProfile"],
                    "runtimeActivation" to q["runtimeActivation"],"catalogPermitted" to q["permitted"],"catalogRefusalReason" to q["refusalReason"])
                if(resolutions.size<4096)resolutions[key]=resolved
                resolved
            }
            access + fields +
                ((diagnosticProfile?.takeIf { access["regionId"] == it.id }?.data
                    ?: singleReadProfile?.takeIf { access["regionId"] == it.id }?.let { declaration ->
                        val afterSelection=selection!=null && start+index>=(selection.getValue("selectedAtJournalIndex") as Int)
                        if(afterSelection)declaration.data+mapOf("declaredSamples" to declaration.samples,"samples" to selection["samples"])
                        else if(declaration.id in diagnosticInputs)declaration.data+mapOf("declaredSamples" to declaration.samples,"samples" to selectedSamples)
                        else declaration.data
                    })?.let { mapOf<String,Any?>("diagnosticRouting" to it) } ?: emptyMap<String,Any?>())
        }
        val selectionFields=if(selection!=null) {
            val id=checkNotNull(singleReadProfile).id
            mapOf("diagnosticInputs" to (diagnosticInputs+mapOf(id to mapOf("strategy" to "scripted-read","confidence" to "HYPOTHESIS","samples" to selection["samples"]))),
                "diagnosticInputSelections" to mapOf(id to selection))
        } else if("diagnosticInputs" in settings)mapOf("diagnosticInputs" to diagnosticInputs)else emptyMap()
        return snapshot+mapOf("profile" to profile,"accesses" to accesses)+selectionFields
    }
    companion object {
        fun mode(config: Map<String,Any?>): String = (config["executionMode"] ?: "discovery").also { require(it in setOf("discovery","strict",RelaxedPeripheralFallback.MODE)) { "Unknown execution mode" } } as String
        fun load(home: Path,config: Map<String,Any>): CatalogRuntime {
            val catalog=config["addressDictionary"]?.let { require(it is String && it.isNotBlank());AddressCatalog.load(home.resolve(it)) } ?: AddressCatalog.bundled()
            return CatalogRuntime(catalog,mode(config),home,config)
        }
        private fun integer(value: Any):Long=when(value) {
            is String -> if(value.startsWith("0x",true))value.substring(2).toLong(16) else value.toLong()
            is Byte,is Short,is Int,is Long -> (value as Number).toLong()
            else -> error("integer required")
        }
    }
}

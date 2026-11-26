package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Files
import java.nio.file.Path

/** Passive catalog qualification only; does not import a firmware runner or native engine. */
object F01cInventoryQualification {
    const val ID="INV-SYS-F01C-001"
    const val G13="11d4cf6ca13b843c5028f05d5bbca9079b3b6279"
    const val RESEARCH="7ff5966be7a3e62b897d0e9132558dd33a634b52"
    private fun git(root:Path,vararg args:String):ByteArray {
        val p=ProcessBuilder(listOf("git")+args).directory(root.toFile()).start()
        val bytes=p.inputStream.use {it.readAllBytes()};val errors=p.errorStream.use {it.readAllBytes()}
        check(p.waitFor()==0) {errors.toString(Charsets.UTF_8)};return bytes
    }
    private fun same(a:Any?,b:Any?)=CatalogIO.canonical(a).contentEquals(CatalogIO.canonical(b))
    fun baseline(root:Path)=CatalogIO.readCompiled(git(root,"show","$G13:catalog/compiled/catalog.json"))
    fun record(catalog:AddressCatalog):Map<String,Any?> {
        val r=checkNotNull(catalog.byId(ID));check(r["status"]=="RESEARCH_RECORD")
        for(key in listOf("address","physicalAddress","physicalSpan","deviceOwner","dataEffect","clearingTrigger","clearingLatency","interruptDmaRoute"))check(r.containsKey(key) && r[key]==null) {"Unknown property must remain explicit null: $key"}
        check(r["physicalIdentity"]=="UNKNOWN" && r["cpuRoutable"]==false && r["runtimeActivation"]==false)
        check(same(r["binding"],mapOf("active" to false,"componentId" to null,"portId" to null)))
        check(same(r["reset"],mapOf("value" to null)))
        check("componentId" !in r && "portId" !in r && "deviceId" !in r)
        val sources=obj(r["provenance"])
        val confidence=mapOf("softwareEffectiveAddress" to "DAT","nativeCallbackAddress" to "SIM","sourceAddressCandidate" to "SOURCE","attemptedWrite" to "SIM","poll" to "DAT","reset" to "UNKNOWN","physicalAddress" to "UNKNOWN","dataEffect" to "UNKNOWN")
        confidence.forEach {(key,c)->val p=obj(sources[key]);check(p["confidence"]==c && (p["support"] as List<*>).isNotEmpty() && p.text("counterexample").isNotBlank() && p.text("nextValidation").isNotBlank())}
        val software=obj(r["softwareEffectiveAddress"]);check(software["coordinateKind"]=="GUEST_VIRTUAL" && software["address"]=="0xFFF4F01C" && software.long("widthBytes")==4L)
        val callback=obj(r["nativeCallbackAddress"]);check(callback["field"]=="addressPhysical" && callback["coordinateKind"]=="NATIVE_CALLBACK_ADDRESS_PHYSICAL_FIELD" && callback["address"]==software["address"])
        val candidate=obj(r["sourceAddressCandidate"]);check(candidate["coordinateKind"]=="SOURCE_ADDRESS_CONSTRUCTION_CANDIDATE" && candidate["address"]==software["address"])
        for(key in listOf("diagnosticMappingDecoded","fullSixteenDescriptorGroupCaptured","guestPermissionsQualified","tlbIdentityQualified","nativeBinarySourceAttested"))check(candidate[key]==false)
        val write=obj(r["attemptedWrite"]);check(write["instructionSet"]=="A32" && write.long("nativeKind")==20L && write.long("widthBytes")==4L && write.long("value")==1L && write["direction"]=="write")
        for(key in listOf("committed","completed","postWriteReadObserved","conditionalReturnObserved"))check(write[key]==false)
        val poll=obj(r["poll"]);check(poll["comparison"]=="entire32bitWordEqualsZero" && poll["comparisonMask"]=="0xFFFFFFFF" && poll.long("readWidthBytes")==4L && poll.long("exitValue")==0L)
        check(poll["actualPostWriteReadOccurred"]==false && poll["timeoutPresent"]==false)
        check(r["qualificationSourceCommit"]==RESEARCH)
        return mapOf("id" to ID,"fieldConfidence" to confidence,"physicalAddress" to r["physicalAddress"],"resetValue" to obj(r["reset"])["value"],"softwareExitValue" to poll["exitValue"],"binding" to r["binding"],"record" to r)
    }
    fun delta(old:AddressCatalog,current:AddressCatalog):Map<String,Any?> {
        val before=rows(old.data["inventory"]);val after=rows(current.data["inventory"])
        check(before.size==36 && after.size==37 && after.last()["id"]==ID)
        check(same(after.dropLast(1),before))
        check(same(current.data+("inventory" to after.dropLast(1)),old.data)) {"Deleting only the added inventory entry must restore complete G13 payload"}
        check(current.componentIds==old.componentIds && current.componentIds.size==32)
        current.componentIds.forEach {check(same(old.componentDescriptor(it),current.componentDescriptor(it))) {"Narrow ABI differs: $it"}}
        val exactKeys=listOf("devices","windows","candidates","memoryProfiles","instances","mappings","imageDomains","legacyFirmwareMetadata","legacyCatalogs")
        exactKeys.forEach {check(same(old.data[it],current.data[it])) {"Existing object differs: $it"}}
        // The only appended array element is expanded into every added leaf, including null/false/empty values.
        fun leaves(value:Any?,path:String):List<Map<String,Any?>> = when {
            value is Map<*,*> && value.isNotEmpty()->value.entries.flatMap {(k,v)->leaves(v,path+"/"+k.toString().replace("~","~0").replace("/","~1"))}
            value is List<*> && value.isNotEmpty()->value.indices.flatMap {leaves(value[it],"$path/$it")}
            else->listOf(mapOf("path" to path,"beforePresent" to false,"afterPresent" to true,"afterValue" to value))
        }
        val differences=leaves(after.last(),"/inventory/36")
        return mapOf("baselineArtifactCommit" to G13,"baselinePayloadSha256" to old.artifactSha256,"payloadSha256" to current.artifactSha256,"onlyAddedInventoryId" to ID,
            "deletingOnlyNewInventoryRestoresCompleteG13" to true,"oldInventoryCount" to before.size,"newInventoryCount" to after.size,"unchangedComponentDescriptorCount" to 32,
            "unchangedComponentDescriptors" to current.componentIds,"unchangedCompleteObjects" to exactKeys,"completePayloadLeafDifferences" to differences,"leafDifferenceCount" to differences.size)
    }
    fun queries(old:AddressCatalog,catalog:AddressCatalog):Map<String,Any?> {
        val id=checkNotNull(catalog.byId(ID));check(id["binding"]==mapOf("active" to false,"componentId" to null,"portId" to null))
        val propertyPaths=listOf("softwareEffectiveAddress.address","nativeCallbackAddress.address","sourceAddressCandidate.address","poll.exitValue","reset.value","physicalAddress","cpuRoutable","binding.active")
        val properties=propertyPaths.map {catalog.property(ID,it)}
        check(properties.take(3).all {it["value"]=="0xFFF4F01C" && it["status"]=="DECLARED"})
        check(properties.single {it["property"]=="poll.exitValue"}["status"]=="DECLARED")
        check(properties.filter {it["property"] in listOf("reset.value","physicalAddress")}.all {it["value"]==null && it["status"]=="UNKNOWN" && obj(it["provenance"])["confidence"]=="UNKNOWN"})
        val queries=ArrayList<Map<String,Any?>>()
        for(profile in catalog.memoryProfiles.keys)for(mode in listOf("strict","discovery"))for(direction in listOf("read","write"))for(address in listOf(0xfff4f000L,0xfff4f01cL,0xfff4fffcL)) {
            val q=catalog.query("cpu-physical",address,4,direction,profile,mode).toMap()
            check(q["classification"]=="UNASSIGNED" && q["permitted"]==false)
            val before=old.query("cpu-physical",address,4,direction,profile,mode).toMap()
            check(q["artifactSha256"]==catalog.artifactSha256 && before["artifactSha256"]==old.artifactSha256)
            check(same(q+("artifactSha256" to old.artifactSha256),before)) {"Only the independently verified payload identity may differ in the complete query result"}
            queries+=mapOf("memoryProfile" to profile,"mode" to mode,"direction" to direction,"address" to address,"width" to 4,"classification" to q["classification"],"permitted" to false)
        }
        return mapOf("idQuery" to id,"propertyQueries" to properties,"physicalQueries" to queries,"physicalQueryCount" to queries.size,"componentBound" to false,"pageOrWordAccessGranted" to false,
            "onlyCompletePhysicalQueryDifference" to mapOf("field" to "artifactSha256","before" to old.artifactSha256,"after" to catalog.artifactSha256))
    }
    @JvmStatic fun main(args:Array<String>) {
        check(args.size==6);val o=args.toList().chunked(2).associate {it[0] to it[1]};check(o.keys==setOf("--repo","--output","--source-commit"))
        val root=Path.of(o.getValue("--repo")).toRealPath();val source=o.getValue("--source-commit")
        check(git(root,"rev-parse","HEAD").toString(Charsets.UTF_8).trim()==source)
        check(git(root,"merge-base","--is-ancestor",RESEARCH,"HEAD").isEmpty())
        val old=baseline(root);val current=CatalogIO.readCompiled(Files.readAllBytes(root.resolve("catalog/compiled/catalog.json")))
        check(same(CatalogCompiler.compile(root).data,current.data))
        val report=mapOf("schema" to 1,"sourceCommit" to source,"catalog" to delta(old,current),"recordQualification" to record(current),"queries" to queries(old,current),"guestExecuted" to false,"nativeExecuted" to false,
            "scope" to "G14 passive inventory only; all complete G13 objects and32 narrow ABIs unchanged; old G13 original inputs/artifact not rewritten.")
        val output=root.resolve(o.getValue("--output")).normalize();check(output.startsWith(root) && !Files.exists(output));CatalogIO.writeJson(output,report)
        println("Qualified passive F01C inventory, complete G13 restoration,32 unchanged narrow ABIs and non-routable ID/property queries")
    }
}

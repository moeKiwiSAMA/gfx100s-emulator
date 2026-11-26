package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogIO
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** One finite observation extension. No device completion, native execution or firmware input. */
object DispatchTailDiagnosticQualification {
    const val PROFILE="gfx100s-sd-finite-dispatch-tail-diagnostic-v1"
    const val COMBINED="gfx100s-sd-finite-dispatch-tail-a300-diagnostic-v1"
    const val OLD_A300="gfx100s-sd-finite-a300-zero-diagnostic-v1"
    const val LIMIT=598016
    const val BASELINE="f4e6804738cf33a6fd6e18108d6e9ba8168b396d"
    const val EVIDENCE="evidence/refactor/dispatch-tail-allowance-fresh-83/README.md"
    internal val oldIds=DispatchDiagnosticQualification.oldIds+DispatchDiagnosticQualification.PROFILE
    internal val oldSingles=DispatchDiagnosticQualification.oldSingles+DispatchDiagnosticQualification.COMBINED
    internal fun same(a:Any?,b:Any?)=CatalogIO.canonical(a).contentEquals(CatalogIO.canonical(b))
    @Suppress("UNCHECKED_CAST") internal fun rows(data:Map<String,Any?>,key:String)=(data[key] as List<*>).map{it as Map<String,Any?>}
    // Exact qualification contracts: never derive the new law from a possibly co-mutated current row.
    private fun retained()=mapOf<String,Any?>("id" to PROFILE,"inventoryId" to "INV-SYS-F01C-001",
        "routeBase" to 0xfff4f01cL,"routeEndExclusive" to 0xfff4f020L,"width" to 4,"strategy" to "retained-bytes",
        "writeValue" to 1,"successfulReadLimit" to LIMIT,"initialValue" to null,"repeatWrite" to "refuse",
        "addressKind" to "observed-native-callback-address","confidence" to "HYPOTHESIS","physicalAddress" to null,
        "reset" to null,"dataEffect" to null,"automaticEvents" to false,"evidence" to listOf(EVIDENCE))
    private fun singleRead()=mapOf<String,Any?>("id" to COMBINED,"parentProfile" to PROFILE,"inventoryId" to "INV-SYS-A300-001",
        "routeBase" to 0xfff4a300L,"routeEndExclusive" to 0xfff4a304L,"width" to 4,"strategy" to "scripted-read",
        "samples" to listOf(0),"exhaustion" to "refuse","addressKind" to "observed-native-callback-address",
        "confidence" to "HYPOTHESIS","physicalAddress" to null,"reset" to null,"function" to null,"dataEffect" to null,
        "automaticEvents" to false,"evidence" to listOf(EVIDENCE))

    /** Preserve the old projection chain; only the complete, single new retained row may be removed. */
    fun profilesBeforeDispatchTail(inputProfiles:List<Map<String,Any?>>):List<Map<String,Any?>> {
        val profiles=DispatchExitDiagnosticQualification.profilesBeforeDispatchExit(inputProfiles)
        val added=profiles.filter{it["id"]==PROFILE}
        if(added.isEmpty())return profiles
        require(profiles.size==9&&added.size==1&&profiles.map{it["id"]}.toSet()==oldIds.toSet()+PROFILE){"Dispatch-tail declaration set differs"}
        require(same(added.single(),retained())){"Complete dispatch-tail retained declaration differs"}
        return profiles.filter{it["id"]!=PROFILE}
    }

    /** Current qualification checks both new rows against the fixed old catalog before projecting. */
    fun priorData(data:Map<String,Any?>,baseline:Map<String,Any?>):Map<String,Any?> {
        val old=rows(baseline,"diagnosticProfiles");val oldSingle=rows(baseline,"singleReadDiagnosticProfiles")
        require(old.size==8&&old.map{it["id"]}.toSet()==oldIds.toSet()&&oldSingle.size==3&&oldSingle.map{it["id"]}.toSet()==oldSingles.toSet()){"Baseline diagnostic set differs"}
        val all=rows(data,"diagnosticProfiles");val singles=rows(data,"singleReadDiagnosticProfiles")
        require(all.size==9&&all.map{it["id"]}.toSet()==oldIds.toSet()+PROFILE){"Dispatch-tail declaration set differs"}
        require(singles.size==4&&singles.map{it["id"]}.toSet()==oldSingles.toSet()+COMBINED){"Dispatch-tail single-read set differs"}
        require(same(all.single{it["id"]==PROFILE},retained())){"Complete dispatch-tail retained declaration differs"}
        require(same(singles.single{it["id"]==COMBINED},singleRead())){"Complete dispatch-tail single-read declaration differs"}
        val restored=data+mapOf("diagnosticProfiles" to all.filter{it["id"]!=PROFILE},"singleReadDiagnosticProfiles" to singles.filter{it["id"]!=COMBINED})
        require(same(restored,baseline)){"Existing catalog data differs"}
        return restored
    }

    private fun binding(r:DiscoveryRegion)=mapOf("id" to r.id,"deviceId" to r.deviceId,"base" to r.base,"length" to r.length,
        "strategy" to r.strategy.id,"widths" to r.widths.sorted(),"alignment" to r.alignment,"littleEndian" to r.littleEndian,
        "initial" to r.initial?.toList(),"fixedValue" to r.fixedValue,"samples" to r.samples,
        "prohibited" to r.prohibited.map{listOf(it.base,it.endExclusive)},"evidence" to r.evidence,
        "readAllowed" to r.readAllowed?.toList(),"writeAllowed" to r.writeAllowed?.toList(),
        "retainedDiagnostic" to r.retainedDiagnostic?.let{mapOf("writeValue" to it.writeValue,"successfulReadLimit" to it.successfulReadLimit)})
    private fun bindings(bus:DiscoveryBus)=bus.regions.map(::binding)

    @JvmStatic fun main(args:Array<String>) {
        require(args.size==4)
        val o=args.toList().chunked(2).associate{it[0] to it[1]};require(o.keys==setOf("--repo","--output"))
        val root=Path.of(o.getValue("--repo")).toRealPath();val output=root.resolve(o.getValue("--output")).normalize()
        require(output.startsWith(root)&&!Files.exists(output))
        fun git(maximum:Int,vararg argv:String):ByteArray {
            val p=ProcessBuilder(listOf("git")+argv).directory(root.toFile()).start()
            val errors=java.util.concurrent.CompletableFuture.supplyAsync{p.errorStream.use{it.readNBytes(65537)}}
            val bytes=p.inputStream.use{it.readNBytes(maximum+1)}
            check(p.waitFor()==0&&bytes.size<=maximum&&errors.get().isEmpty()){errors.get().toString(Charsets.UTF_8)}
            return bytes
        }
        val source=git(4096,"rev-parse","HEAD").toString(Charsets.UTF_8).trim()
        require(source.matches(Regex("[a-f0-9]{40}"))&&git(65536,"status","--porcelain","--untracked-files=no").isEmpty())
        val refs=mutableListOf<Map<String,Any>>()
        fun reference(commit:String,path:String,max:Int=1048576)=git(max,"show","$commit:$path").also{refs+=mapOf("commit" to commit,"path" to path,"bytes" to it.size)}
        val factsPublication="4cee3c43aa2016ea8f943c885fed134f89233fc5"
        val factsSource="28e532cad2f43e1b577bbcd08fc52603d3b2f620"
        val factsDirectory="evidence/refactor/dispatch-reader-fresh-77/"
        val pre=CatalogIO.json.readTree(reference(factsPublication,factsDirectory+"formal-01/prelaunch.generated.json"))
        val run=CatalogIO.json.readTree(reference(factsPublication,factsDirectory+"formal-01/run.generated.json"))
        require(pre["sourceCommit"].asText()==factsSource&&run["sourceCommit"].asText()==factsSource&&run["finalSourceCommit"].asText()==factsSource)
        require(pre["statusPorcelain"].asText()==""&&run["statusPorcelain"].asText()==""&&run["finalStatusPorcelain"].asText()=="")
        require(run["exitCode"].isIntegralNumber&&run["exitCode"].asInt()==0&&run["signal"].isNull&&run["spawnError"].isNull)
        require(pre["argv"]==run["argv"]&&pre["cwd"]==run["cwd"]&&run["argv"].any{it.asText()=="readDispatchObservation"})
        for(name in listOf("stdout","stderr")) {
            require(run[name].asText()=="$name.raw.log.gz")
            val bytes=reference(factsPublication,factsDirectory+"formal-01/$name.raw.log.gz")
            java.util.zip.GZIPInputStream(bytes.inputStream()).use{require(it.readNBytes(65537).size<=65536)}
        }
        val prior=CatalogIO.json.readTree(reference(factsPublication,factsDirectory+"original-observation.generated.json"))
        require(prior["readerSourceCommit"].asText()==factsSource&&prior["producerSourceCommit"].asText()==BASELINE)
        for(flag in listOf("accepted","formalExecutionVerified"))require(prior[flag].isBoolean&&prior[flag].booleanValue())
        require(prior["qualificationOnly"].isBoolean&&!prior["qualificationOnly"].booleanValue()&&prior["failure"].isNull)
        val details=prior["details"];val diagnostic=details["diagnostic"];val configuration=details["inputs"]["configuration"]
        require(details["prefixes"]["wholeMmioPrefix"].asLong()+details["prefixes"]["newCompletedMmioCount"].asLong()==643496L)
        require(diagnostic["successfulReadCount"].asInt()==589824&&diagnostic["successfulWriteCount"].asInt()==1&&diagnostic["retainedValue"].asInt()==1)
        require(diagnostic["a300SuccessfulReads"].asInt()==1&&diagnostic["a300Sample"]["value"].asInt()==0)
        require(diagnostic["a300ExhaustionActuallyRequested"].isBoolean&&!diagnostic["a300ExhaustionActuallyRequested"].booleanValue())
        require(diagnostic["allAccessesVerified"].asInt()==589827&&diagnostic["completedDiagnosticRows"].asInt()==589826)
        require(details["terminal"]["attempts"].asLong()==134617809L&&details["terminal"]["idleClock"]["virtualTick"].asLong()==159731281L)
        require(configuration["instructionBudget"].asLong()==183606388L&&configuration["transactionLimit"].asInt()==655360)
        require(configuration["discovery"]["logCapacity"].asInt()==655360&&configuration["discovery"]["diskBudgetBytes"].asLong()==1610612736L)
        require(details["inputs"]["marker"]["traceMode"].asText()=="rolling-last-256"&&configuration["tracePoints"].size()==56)
        val baseline=CatalogIO.readCompiled(reference(BASELINE,"catalog/compiled/catalog.json",CatalogIO.MAX_BYTES))
        val catalog=AddressCatalog.bundled();priorData(catalog.data,baseline.data)
        require(same(catalog.data,CatalogIO.readCompiled(Files.readAllBytes(root.resolve("catalog/compiled/catalog.json"))).data))
        require(catalog.componentIds.size==32&&catalog.componentIds==baseline.componentIds&&catalog.componentIds.all{same(catalog.componentDescriptor(it),baseline.componentDescriptor(it))})
        val previousAlgorithm=reference(BASELINE,"runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/Discovery.kt").toString(Charsets.UTF_8)
        val currentAlgorithm=Files.readString(root.resolve("runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/Discovery.kt"))
        require(currentAlgorithm.replace("successfulReadLimit in 1..598016","successfulReadLimit in 1..589824")==previousAlgorithm)
        require(reference(BASELINE,"runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/CatalogRuntime.kt").contentEquals(Files.readAllBytes(root.resolve("runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/CatalogRuntime.kt"))))
        val changed=git(65536,"diff","--name-only",BASELINE,source,"--","address-catalog/src/main","runtime/src/main","catalog").toString(Charsets.UTF_8).lineSequence().filter{it.isNotBlank()}.toSet()
        require(changed==setOf("address-catalog/src/main/kotlin/com/github/nyabanban/fuji/emulator/catalog/RetainedDiagnostics.kt",
            "address-catalog/src/main/kotlin/com/github/nyabanban/fuji/emulator/catalog/SingleReadDiagnostics.kt",
            "runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/Discovery.kt",
            "runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/DispatchDiagnosticQualification.kt",
            "runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/DispatchTailDiagnosticQualification.kt",
            "catalog/soc/xp4/address-space.yaml","catalog/compiled/catalog.json","catalog/compiled/manifest.json")){"Unexpected model source changes: $changed"}
        val projectionPath="runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/DispatchDiagnosticQualification.kt"
        val projectionBefore=reference(BASELINE,projectionPath).toString(Charsets.UTF_8)
        val projectionNow=Files.readString(root.resolve(projectionPath))
        require(projectionNow.replace("fun profilesBeforeDispatch(inputProfiles:","fun profilesBeforeDispatch(profiles:")
            .replace("        val profiles=DispatchTailDiagnosticQualification.profilesBeforeDispatchTail(inputProfiles)\n","")==projectionBefore)
        val context=object:DiscoveryContext {override fun core()=3;override fun tick()=0L;override fun pc()=0L;override fun lr()=0L}
        fun runtime(c:AddressCatalog,id:String?=null,mode:String="discovery")=CatalogRuntime(c,mode,root,if(id==null)emptyMap()else mapOf("discovery" to mapOf("profile" to id)))
        val oldSelections=(listOf<String?>(null)+oldIds+oldSingles).map{id->
            val now=checkNotNull(runtime(catalog,id).createBus(context));val old=checkNotNull(runtime(baseline,id).createBus(context))
            require(same(bindings(now),bindings(old))&&now.pageRanges()==old.pageRanges())
            mapOf("profile" to (id?:"default"),"regions" to now.regions.size,"allBindingFieldsExact" to true,"transportPagesExact" to true)
        }
        val ordinary=checkNotNull(runtime(catalog).createBus(context))
        val parent=checkNotNull(runtime(catalog,PROFILE).createBus(context));val selectedRuntime=runtime(catalog,COMBINED)
        val selected=checkNotNull(selectedRuntime.createBus(context))
        require(same(bindings(parent).filter{it["id"]!=PROFILE},bindings(ordinary)))
        require(same(bindings(selected).filter{it["id"]!=COMBINED},bindings(parent)))
        require(parent.regions.single{it.id==PROFILE}.retainedDiagnostic==RetainedWriteDiagnostic(1,LIMIT))
        val oldA300=checkNotNull(runtime(baseline,OLD_A300).createBus(context)).regions.single{it.id==OLD_A300}
        val newA300=selected.regions.single{it.id==COMBINED}
        require(same(binding(newA300)-setOf("id","evidence"),binding(oldA300)-setOf("id","evidence")))
        require(newA300.samples==listOf(0L)&&newA300.strategy==DiscoveryStrategy.SCRIPT)
        val ids=oldIds+oldSingles+listOf(PROFILE,COMBINED,"gfx100s-sd-finite-v1")
        require(ids.all{runtime(catalog,it,"strict").createBus(context)==null})
        var queries=0
        for(address in listOf(0xfff4f01cL,0xfff4a300L))for(mode in listOf("strict","discovery"))for(direction in listOf("read","write")) {
            val a=baseline.query("cpu-physical",address,4,direction,mode=mode).toMap();val b=catalog.query("cpu-physical",address,4,direction,mode=mode).toMap()
            require(b["classification"]=="UNASSIGNED"&&b["permitted"]==false&&same(b+("artifactSha256" to baseline.artifactSha256),a));queries++
        }
        val router=MmioRouter(emptyList(),selected)
        require(runCatching{router.read(0xfff4f01cL,4)}.isFailure)
        router.write(0xfff4f01cL,4,1);require(router.read(0xfff4f01cL,4)==1L)
        require(router.read(0xfff4a300L,4)==0L&&runCatching{router.read(0xfff4a300L,4)}.isFailure)
        require(runCatching{router.write(0xfff4f01cL,4,0)}.isFailure)
        val secondWrite=(selected.snapshot()["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).last()
        require(secondWrite["reason"]=="diagnostic-repeated-write"&&secondWrite["value"]==0L&&secondWrite["completed"]==false)
        val before=selected.snapshot();router.beforeInstruction(1)
        require(router.nextDeadline(1)==null&&same(before,selected.snapshot())&&context.tick()==0L)
        val report=mapOf("schema" to 1,"sourceCommit" to source,"qualification" to "TEST","qualified" to true,"references" to refs,
            "baselineCatalogCommit" to BASELINE,"baselineCatalogPath" to "catalog/compiled/catalog.json","allPriorCatalogDataExact" to true,
            "baselineCatalogPayloadSha256" to baseline.artifactSha256,"catalogPayloadSha256" to catalog.artifactSha256,"unchangedComponentDescriptors" to 32,
            "addedRetainedDeclaration" to catalog.diagnosticProfiles.single{it.id==PROFILE}.data,"addedSingleReadDeclaration" to catalog.singleReadDiagnosticProfiles.single{it.id==COMBINED}.data,
            "oldActualSelections" to oldSelections,"newActualRegions" to selected.regions.filter{it.id in setOf(PROFILE,COMBINED)}.map(::binding),
            "newSelectedPages" to selected.pageRanges(),"strictProfilesRefused" to ids,"staticQueriesUnchanged" to queries,
            "productionSourcePaths" to changed,"routingAlgorithmUnchanged" to true,"retainedAlgorithmOnlyCeilingChanged" to true,"noAutomaticEventOrClockMutation" to true,
            "selectedBindingObservation" to selectedRuntime.observation(selected),
            "observationPolicy" to mapOf("confidence" to "HYPOTHESIS","previousSuccessfulReadLimit" to 589824,"additionalSuccessfulReads" to 8192,"selectedSuccessfulReadLimit" to LIMIT,
                "instructionBudgetUnchanged" to 183606388,"remainingPriorInstructionAttempts" to 48988579,
                "mmioRetentionCapacityUnchanged" to 655360,"discoveryJournalCapacityUnchanged" to 655360,"diskBudgetBytesUnchanged" to 1610612736L,
                "a300SamplesUnchanged" to listOf(0),"a300SecondRequestPreviouslyObserved" to false,"tracePolicySelectedByThisModel" to false,
                "priorCompletedMmio" to 643496,"priorNonF01cReadMmio" to 53672,"hypotheticalCompletedMmioIfOtherAccessesUnchanged" to 651688,
                "conditionalCompleteMmioHeadroom" to 3672,"mmioRingStopsGuest" to false,"journalCapacityRefusalBeforeMutation" to true,
                "priorCompletedDiagnosticRows" to 589826,"hypotheticalCompletedDiagnosticRowsAtReadLimit" to 598018,
                "hypotheticalJournalRowsIncludingReadLimitRefusal" to 598019,"conditionalJournalNormalSlotHeadroomBeforeRefusal" to 57342,
                "journalAndMmioCountSameAccesses" to false,"journalGuaranteedToStopBeforeMmioOverwrite" to false,
                "mmioOverwriteIfOtherCompletedAccessesExceedHeadroom" to "Possible; refuse complete-prefix acceptance and preserve original",
                "secondGuestWriteIncludingZero" to "refuse-and-preserve-original-value","automaticClear" to false,
                "slotsConvertedToTimeOrReadCount" to false,"readLimitGuaranteed" to false,"completeMmioGuaranteed" to false,"completionGuaranteed" to false,
                "newProducerFrozen" to false,"allowanceAppliedToGuest" to false),
            "priorProducerAccepted" to true,"priorProducerFormalExecutionVerified" to true,"physicalResetAndClearLaw" to "UNKNOWN",
            "guestExecuted" to false,"nativeLoaded" to false,"hardwareValidated" to false,"newDatWords" to 0,"dumpPayloadBytesRead" to 0,
            "scope" to "Two explicitly selected declarations only. Preserve every old profile and A300 single zero. This report qualifies actual software binding, not a function return, ready/ACK, physical completion or boot.")
        Files.createDirectories(output.parent);Files.write(output,Formats.jsonBytes(report),CREATE_NEW)
        println("P83 qualified two declarations;12 old selections and32 descriptors exact;598016 finite retained1;second writes refused;no guest/native/DAT")
    }
}

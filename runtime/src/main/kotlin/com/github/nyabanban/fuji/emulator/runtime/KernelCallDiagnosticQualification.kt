package com.github.nyabanban.fuji.emulator.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogIO
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** A separately selected finite input for one bounded call observation; no completion or guest execution. */
object KernelCallDiagnosticQualification {
    const val PROFILE="gfx100s-sd-finite-kernel-call-diagnostic-v1"
    const val COMBINED="gfx100s-sd-finite-kernel-call-a300-diagnostic-v1"
    const val LIMIT=697344
    const val LCD_PROFILE="gfx100s-sd-finite-lcd-progress-diagnostic-v1"
    const val LCD_COMBINED="gfx100s-sd-finite-lcd-progress-a300-diagnostic-v1"
    const val LCD_LIMIT=1048576
    const val LCD_EVIDENCE="evidence/refactor/lcd-finite-horizon-fresh-189/README.md"
    const val COMPLETION_PROFILE="gfx100s-sd-finite-lens-completion-diagnostic-v1"
    const val COMPLETION_COMBINED="gfx100s-sd-finite-lens-completion-a300-diagnostic-v1"
    const val COMPLETION_LIMIT=2097152
    const val COMPLETION_EVIDENCE="evidence/refactor/lens-completion-horizon-fresh-199/README.md"
    const val BASELINE="515440caa5030c874238c1e9359c43b97b351130"
    const val EVIDENCE="evidence/refactor/kernel-call-finite-input-fresh-121/README.md"
    internal val oldIds=DispatchFollowupDiagnosticQualification.oldIds+DispatchFollowupDiagnosticQualification.PROFILE
    internal val oldSingles=DispatchFollowupDiagnosticQualification.oldSingles+DispatchFollowupDiagnosticQualification.COMBINED
    internal fun same(a:Any?,b:Any?)=CatalogIO.canonical(a).contentEquals(CatalogIO.canonical(b))
    @Suppress("UNCHECKED_CAST") internal fun rows(data:Map<String,Any?>,key:String)=(data[key] as List<*>).map{it as Map<String,Any?>}
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

    internal fun profilesBeforeCompletion(profiles:List<Map<String,Any?>>):List<Map<String,Any?>> {
        val added=profiles.filter{it["id"]==COMPLETION_PROFILE}
        if(added.isEmpty())return profiles
        require(added.size==1&&same(added.single(),retained()+mapOf("id" to COMPLETION_PROFILE,
            "successfulReadLimit" to COMPLETION_LIMIT,"evidence" to listOf(COMPLETION_EVIDENCE)))) {"Complete completion-horizon retained declaration differs"}
        return profiles.filter{it["id"]!=COMPLETION_PROFILE}
    }
    internal fun dataBeforeCompletion(data:Map<String,Any?>):Map<String,Any?> {
        val profiles=rows(data,"diagnosticProfiles");val singles=rows(data,"singleReadDiagnosticProfiles")
        val added=singles.filter{it["id"]==COMPLETION_COMBINED}
        if(profiles.none{it["id"]==COMPLETION_PROFILE}&&added.isEmpty())return data
        require(profiles.count{it["id"]==COMPLETION_PROFILE}==1&&added.size==1) {"Completion-horizon declaration pair differs"}
        require(same(added.single(),singleRead()+mapOf("id" to COMPLETION_COMBINED,"parentProfile" to COMPLETION_PROFILE,
            "evidence" to listOf(COMPLETION_EVIDENCE)))) {"Complete completion-horizon single-read declaration differs"}
        return data+mapOf("diagnosticProfiles" to profilesBeforeCompletion(profiles),
            "singleReadDiagnosticProfiles" to singles.filter{it["id"]!=COMPLETION_COMBINED})
    }
    internal fun profilesBeforeLcdProgress(inputProfiles:List<Map<String,Any?>>):List<Map<String,Any?>> {
        val profiles=profilesBeforeCompletion(inputProfiles)
        val added=profiles.filter{it["id"]==LCD_PROFILE}
        if(added.isEmpty())return profiles
        require(added.size==1&&same(added.single(),retained()+mapOf("id" to LCD_PROFILE,
            "successfulReadLimit" to LCD_LIMIT,"evidence" to listOf(LCD_EVIDENCE)))) {"Complete LCD-progress retained declaration differs"}
        return profiles.filter{it["id"]!=LCD_PROFILE}
    }
    internal fun dataBeforeLcdProgress(inputData:Map<String,Any?>):Map<String,Any?> {
        val data=dataBeforeCompletion(inputData)
        val profiles=rows(data,"diagnosticProfiles");val singles=rows(data,"singleReadDiagnosticProfiles")
        val added=singles.filter{it["id"]==LCD_COMBINED}
        if(profiles.none{it["id"]==LCD_PROFILE}&&added.isEmpty())return data
        require(profiles.count{it["id"]==LCD_PROFILE}==1&&added.size==1) {"LCD-progress declaration pair differs"}
        require(same(added.single(),singleRead()+mapOf("id" to LCD_COMBINED,"parentProfile" to LCD_PROFILE,
            "evidence" to listOf(LCD_EVIDENCE)))) {"Complete LCD-progress single-read declaration differs"}
        return data+mapOf("diagnosticProfiles" to profilesBeforeLcdProgress(profiles),
            "singleReadDiagnosticProfiles" to singles.filter{it["id"]!=LCD_COMBINED})
    }
    fun profilesBeforeKernelCall(inputProfiles:List<Map<String,Any?>>):List<Map<String,Any?>> {
        val profiles=profilesBeforeLcdProgress(inputProfiles)
        val added=profiles.filter{it["id"]==PROFILE}
        if(added.isEmpty())return profiles
        require(profiles.size==12&&added.size==1&&profiles.map{it["id"]}.toSet()==oldIds.toSet()+PROFILE){"Kernel-call declaration set differs"}
        require(same(added.single(),retained())){"Complete kernel-call retained declaration differs"}
        return profiles.filter{it["id"]!=PROFILE}
    }

    /** Remove exactly the complete new pair; missing halves and coordinated changes are not projected away. */
    internal fun dataBeforeKernelCall(inputData:Map<String,Any?>):Map<String,Any?> {
        val data=dataBeforeLcdProgress(inputData)
        val all=rows(data,"diagnosticProfiles");val singles=rows(data,"singleReadDiagnosticProfiles")
        if(all.none{it["id"]==PROFILE}&&singles.none{it["id"]==COMBINED})return data
        require(all.size==12&&all.map{it["id"]}.toSet()==oldIds.toSet()+PROFILE){"Kernel-call declaration set differs"}
        require(singles.size==7&&singles.map{it["id"]}.toSet()==oldSingles.toSet()+COMBINED){"Kernel-call single-read set differs"}
        require(same(all.single{it["id"]==PROFILE},retained())){"Complete kernel-call retained declaration differs"}
        require(same(singles.single{it["id"]==COMBINED},singleRead())){"Complete kernel-call single-read declaration differs"}
        return data+mapOf("diagnosticProfiles" to all.filter{it["id"]!=PROFILE},"singleReadDiagnosticProfiles" to singles.filter{it["id"]!=COMBINED})
    }

    fun priorData(data:Map<String,Any?>,baseline:Map<String,Any?>):Map<String,Any?> {
        val old=rows(baseline,"diagnosticProfiles");val scripts=rows(baseline,"singleReadDiagnosticProfiles")
        require(old.size==11&&old.map{it["id"]}.toSet()==oldIds.toSet()&&scripts.size==6&&scripts.map{it["id"]}.toSet()==oldSingles.toSet()){"Baseline diagnostic set differs"}
        val restored=dataBeforeKernelCall(data);require(same(restored,baseline)){"Existing catalog data differs"};return restored
    }

    internal fun binding(r:DiscoveryRegion)=mapOf("id" to r.id,"deviceId" to r.deviceId,"base" to r.base,"length" to r.length,
        "strategy" to r.strategy.id,"widths" to r.widths.sorted(),"alignment" to r.alignment,"littleEndian" to r.littleEndian,
        "initial" to r.initial?.toList(),"fixedValue" to r.fixedValue,"samples" to r.samples,
        "prohibited" to r.prohibited.map{listOf(it.base,it.endExclusive)},"evidence" to r.evidence,
        "readAllowed" to r.readAllowed?.toList(),"writeAllowed" to r.writeAllowed?.toList(),
        "retainedDiagnostic" to r.retainedDiagnostic?.let{mapOf("writeValue" to it.writeValue,"successfulReadLimit" to it.successfulReadLimit)})
    private fun bindings(bus:DiscoveryBus)=bus.regions.map(::binding)
    private fun JsonNode.n(key:String):Long {val v=get(key);require(v!=null&&v.isIntegralNumber){key};return v.longValue()}
    private fun JsonNode.flag(key:String,value:Boolean){val v=get(key);require(v!=null&&v.isBoolean&&v.booleanValue()==value){key}}

    @JvmStatic fun main(args:Array<String>) {
        require(args.size==4);val o=args.toList().chunked(2).associate{it[0] to it[1]};require(o.keys==setOf("--repo","--output"))
        val root=Path.of(o.getValue("--repo")).toRealPath();val output=root.resolve(o.getValue("--output")).normalize()
        require(output.startsWith(root)&&!Files.exists(output))
        fun git(maximum:Int,vararg argv:String):ByteArray {
            val p=ProcessBuilder(listOf("git")+argv).directory(root.toFile()).start()
            val errors=java.util.concurrent.CompletableFuture.supplyAsync{p.errorStream.use{it.readNBytes(65537)}}
            val bytes=p.inputStream.use{it.readNBytes(maximum+1)}
            check(p.waitFor()==0&&bytes.size<=maximum&&errors.get().isEmpty()){errors.get().toString(Charsets.UTF_8)};return bytes
        }
        val source=git(4096,"rev-parse","HEAD").toString(Charsets.UTF_8).trim()
        require(source.matches(Regex("[a-f0-9]{40}"))&&git(65536,"status","--porcelain","--untracked-files=no").isEmpty())
        val refs=mutableListOf<Map<String,Any>>()
        fun reference(commit:String,path:String,max:Int=1048576)=git(max,"show","$commit:$path").also{refs+=mapOf("commit" to commit,"path" to path,"bytes" to it.size)}
        val factsPublication="43d600f9954e170956a7c266d81f279d7047a663";val factsSource="ea24e8938fef843c972bc40491fd606f490b4034"
        val factsDirectory="evidence/refactor/dispatch-followup-reader-fresh-104/"
        git(4096,"merge-base","--is-ancestor",factsSource,factsPublication);git(4096,"merge-base","--is-ancestor",factsPublication,source)
        val pre=CatalogIO.json.readTree(reference(factsPublication,factsDirectory+"formal-01/prelaunch.generated.json"))
        val run=CatalogIO.json.readTree(reference(factsPublication,factsDirectory+"formal-01/run.generated.json"))
        require(pre["sourceCommit"].asText()==factsSource&&run["sourceCommit"].asText()==factsSource&&run["finalSourceCommit"].asText()==factsSource)
        require(pre["statusPorcelain"].asText()==""&&run["statusPorcelain"].asText()==""&&run["finalStatusPorcelain"].asText()=="")
        require(run.n("exitCode")==0L&&run["signal"].isNull&&run["spawnError"].isNull&&pre["argv"]==run["argv"]&&pre["cwd"]==run["cwd"])
        require(run["argv"].any{it.asText()=="readDispatchFollowupObservation"})
        for(name in listOf("stdout","stderr")) {
            require(run[name].asText()=="$name.raw.log.gz")
            java.util.zip.GZIPInputStream(reference(factsPublication,factsDirectory+"formal-01/$name.raw.log.gz").inputStream()).use{require(it.readNBytes(65537).size<=65536)}
        }
        val priorBytes=reference(factsPublication,factsDirectory+"original-observation.generated.json");require(priorBytes.size==596043)
        val prior=CatalogIO.json.readTree(priorBytes)
        require(prior["readerSourceCommit"].asText()==factsSource&&prior["producerSourceCommit"].asText()==BASELINE)
        prior.flag("accepted",true);prior.flag("formalExecutionVerified",true);prior.flag("qualificationOnly",false);require(prior["failure"].isNull)
        val details=prior["details"];val diagnostic=details["diagnostic"];val config=details["inputs"]["configuration"];val terminal=details["terminal"]
        require(details["prefixes"].n("wholeMmioPrefix")+details["prefixes"].n("newCompletedMmioCount")==750435L)
        require(diagnostic.n("successfulReadCount")==696320L&&diagnostic.n("successfulWriteCount")==1L&&diagnostic.n("retainedValue")==1L&&diagnostic.n("remainingRetainedReads")==0L)
        require(diagnostic.n("a300SuccessfulReads")==1L&&diagnostic["a300Sample"].n("value")==0L);diagnostic.flag("a300ExhaustionActuallyRequested",false)
        require(diagnostic.n("allAccessesVerified")==696323L&&diagnostic.n("completedDiagnosticRows")==696322L)
        require(terminal.n("attempts")==136887635L&&terminal["idleClock"].n("virtualTick")==162001107L)
        val refusal=diagnostic["refusal"];require(refusal["reason"].asText()=="diagnostic-limit"&&refusal.n("sequence")==750436L&&refusal["value"].isNull)
        require(refusal.n("core")==3L&&refusal.n("pc")==0x5afe0L&&refusal.n("lr")==0x12919e0L&&refusal.n("addressPhysical")==0xfff4f01cL&&refusal.n("width")==4L)
        require(config.n("instructionBudget")==183606388L&&config.n("transactionLimit")==786432L&&config["discovery"].n("logCapacity")==786432L)
        require(config["discovery"].n("diskBudgetBytes")==1610612736L&&config["tracePoints"].size()==65&&config.n("tracePerPointLimit")==8L)
        val trace=details["selectedTrace"];trace.flag("fullRunPerPointLedgerQualified",true)
        require(trace["currentSampling"].asText()=="first-per-point-core"&&trace.n("currentObservedAll65")==11256L&&trace.n("currentRetainedAll65")==343L)
        terminal.flag("completeBootReported",false);terminal.flag("menuVerifiedReported",false)
        require(terminal["display"].n("mainSequence")==0L&&terminal["shoulder"].n("sequence")==1L)

        val baseline=CatalogIO.readCompiled(reference(BASELINE,"catalog/compiled/catalog.json",CatalogIO.MAX_BYTES));val catalog=AddressCatalog.bundled()
        priorData(catalog.data,baseline.data);require(same(catalog.data,CatalogIO.readCompiled(Files.readAllBytes(root.resolve("catalog/compiled/catalog.json"))).data))
        require(catalog.componentIds.size==32&&catalog.componentIds==baseline.componentIds&&catalog.componentIds.all{same(catalog.componentDescriptor(it),baseline.componentDescriptor(it))})
        val algorithm="runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/Discovery.kt"
        require(Files.readString(root.resolve(algorithm)).replace("successfulReadLimit in 1..697344","successfulReadLimit in 1..696320")==reference(BASELINE,algorithm).toString(Charsets.UTF_8))
        val routing="runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/CatalogRuntime.kt"
        require(reference(BASELINE,routing).contentEquals(Files.readAllBytes(root.resolve(routing))))
        val projection="runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/DispatchFollowupDiagnosticQualification.kt"
        val originalProjection=reference(BASELINE,projection).toString(Charsets.UTF_8)
        val restoredProjection=Files.readString(root.resolve(projection)).replace("fun profilesBeforeDispatchFollowup(inputProfiles:","fun profilesBeforeDispatchFollowup(profiles:")
            .replace("        val profiles=KernelCallDiagnosticQualification.profilesBeforeKernelCall(inputProfiles)\n","")
            .replace("fun dataBeforeDispatchFollowup(inputData:","fun dataBeforeDispatchFollowup(data:")
            .replace("        val data=KernelCallDiagnosticQualification.dataBeforeKernelCall(inputData)\n","")
        require(restoredProjection==originalProjection)
        for((path,changes)in mapOf(
            "address-catalog/src/main/kotlin/com/github/nyabanban/fuji/emulator/catalog/RetainedDiagnostics.kt" to listOf("profiles.size <= 11" to "profiles.size <= 12","At most eleven independently" to "At most twelve independently","1..696320" to "1..697344"),
            "address-catalog/src/main/kotlin/com/github/nyabanban/fuji/emulator/catalog/SingleReadDiagnostics.kt" to listOf("profiles.size <= 6" to "profiles.size <= 7","At most six independently" to "At most seven independently"))) {
            var expected=reference(BASELINE,path).toString(Charsets.UTF_8)
            for((before,after)in changes){require(expected.indexOf(before)>=0&&expected.indexOf(before)==expected.lastIndexOf(before));expected=expected.replace(before,after)}
            require(Files.readString(root.resolve(path))==expected){"Schema differs outside explicit ceilings: $path"}
        }
        val productionPaths=setOf("address-catalog/src/main/kotlin/com/github/nyabanban/fuji/emulator/catalog/RetainedDiagnostics.kt",
            "address-catalog/src/main/kotlin/com/github/nyabanban/fuji/emulator/catalog/SingleReadDiagnostics.kt",algorithm,projection,
            "runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/KernelCallDiagnosticQualification.kt",
            "catalog/soc/xp4/address-space.yaml","catalog/compiled/catalog.json","catalog/compiled/manifest.json")
        require(git(65536,"diff","--name-only",BASELINE,source,"--","address-catalog/src/main","runtime/src/main","catalog").toString(Charsets.UTF_8).lineSequence().filter{it.isNotBlank()}.toSet()==productionPaths)
        val capacityPath="runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/MmioTrace.kt"
        require(reference(BASELINE,capacityPath).contentEquals(Files.readAllBytes(root.resolve(capacityPath))))
        val context=object:DiscoveryContext {override fun core()=3;override fun tick()=0L;override fun pc()=0L;override fun lr()=0L}
        fun runtime(c:AddressCatalog,id:String?=null,mode:String="discovery")=CatalogRuntime(c,mode,root,if(id==null)emptyMap()else mapOf("discovery" to mapOf("profile" to id)))
        val oldSelections=(listOf<String?>(null)+oldIds+oldSingles).map{id->
            val now=checkNotNull(runtime(catalog,id).createBus(context));val old=checkNotNull(runtime(baseline,id).createBus(context))
            require(same(bindings(now),bindings(old))&&now.pageRanges()==old.pageRanges())
            mapOf("profile" to (id?:"default"),"regions" to now.regions.size,"allBindingFieldsExact" to true,"transportPagesExact" to true)
        };require(oldSelections.size==18)
        val ordinary=checkNotNull(runtime(catalog).createBus(context));val parent=checkNotNull(runtime(catalog,PROFILE).createBus(context))
        val selectedRuntime=runtime(catalog,COMBINED);val selected=checkNotNull(selectedRuntime.createBus(context))
        require(same(bindings(parent).filter{it["id"]!=PROFILE},bindings(ordinary))&&same(bindings(selected).filter{it["id"]!=COMBINED},bindings(parent)))
        require(parent.regions.single{it.id==PROFILE}.retainedDiagnostic==RetainedWriteDiagnostic(1,LIMIT))
        val oldScript=checkNotNull(runtime(baseline,DispatchFollowupDiagnosticQualification.COMBINED).createBus(context)).regions.single{it.id==DispatchFollowupDiagnosticQualification.COMBINED}
        require(same(binding(selected.regions.single{it.id==COMBINED})-setOf("id","evidence"),binding(oldScript)-setOf("id","evidence")))
        val ids=oldIds+oldSingles+listOf(PROFILE,COMBINED,"gfx100s-sd-finite-v1");require(ids.all{runtime(catalog,it,"strict").createBus(context)==null})
        var queries=0
        for(address in listOf(0xfff4f01cL,0xfff4a300L))for(mode in listOf("strict","discovery"))for(direction in listOf("read","write")) {
            val a=baseline.query("cpu-physical",address,4,direction,mode=mode).toMap();val b=catalog.query("cpu-physical",address,4,direction,mode=mode).toMap()
            require(b["classification"]=="UNASSIGNED"&&b["permitted"]==false&&same(b+("artifactSha256" to baseline.artifactSha256),a));queries++
        }
        val router=MmioRouter(emptyList(),selected);require(runCatching{router.read(0xfff4f01cL,4)}.isFailure)
        router.write(0xfff4f01cL,4,1);require(router.read(0xfff4f01cL,4)==1L&&router.read(0xfff4a300L,4)==0L)
        require(runCatching{router.read(0xfff4a300L,4)}.isFailure&&runCatching{router.write(0xfff4f01cL,4,0)}.isFailure)
        val last=(selected.snapshot()["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).last();require(last["reason"]=="diagnostic-repeated-write"&&last["value"]==0L&&last["completed"]==false)
        val before=selected.snapshot();router.beforeInstruction(1);require(router.nextDeadline(1)==null&&same(before,selected.snapshot())&&context.tick()==0L)
        val report=mapOf("schema" to 1,"sourceCommit" to source,"qualification" to "TEST","qualified" to true,"references" to refs,
            "baselineCatalogCommit" to BASELINE,"allPriorCatalogDataExact" to true,"baselineCatalogPayloadSha256" to baseline.artifactSha256,"catalogPayloadSha256" to catalog.artifactSha256,
            "unchangedComponentDescriptors" to 32,"addedRetainedDeclaration" to catalog.diagnosticProfiles.single{it.id==PROFILE}.data,
            "addedSingleReadDeclaration" to catalog.singleReadDiagnosticProfiles.single{it.id==COMBINED}.data,"oldActualSelections" to oldSelections,
            "newActualRegions" to selected.regions.filter{it.id in setOf(PROFILE,COMBINED)}.map(::binding),"strictProfilesRefused" to ids,"staticQueriesUnchanged" to queries,
            "productionSourcePaths" to productionPaths,"routingAlgorithmUnchanged" to true,"retainedAlgorithmOnlyCeilingChanged" to true,
            "selectedBindingObservation" to selectedRuntime.observation(selected),"noAutomaticEventOrClockMutation" to true,
            "observationPolicy" to mapOf("confidence" to "HYPOTHESIS","previousSuccessfulReadLimit" to 696320,"additionalSuccessfulReads" to 1024,"selectedSuccessfulReadLimit" to LIMIT,
                "priorAttemptCutoff" to 136887635,"priorTickCutoff" to 162001107,"plannedGlobalTailHookStop" to 1040,"plannedAggregateMmioAllowance" to 1024,
                "plannedMmioCounterIncludesCutoffAttempt" to true,"priorCompletedMmio" to 750435,"conditionalMaximumCompletedMmio" to 751459,"conditionalMmioHeadroom" to 34973,
                "priorCompletedDiagnosticRows" to 696322,"conditionalMaximumCompletedDiagnosticRows" to 697346,"conditionalMaximumWithOneRouteRefusal" to 697347,
                "mmioAndJournalCapacityUnchanged" to 786432,"instructionBudgetUnchanged" to 183606388,"remainingPriorInstructionAttempts" to 46718753,
                "existingTracePoints" to 65,"existingPerPointLimit" to 8,"existingObservedCount" to 11256,"existingRetainedCount" to 343,
                "boundsRequireSeparatelyQualifiedObserver" to true,"observerImplementedOrExecutedByThisQualification" to false,"newProducerFrozen" to false,"allowanceAppliedToGuest" to false,
                "readLimitOrBootGuaranteed" to false,"sameRefusalAutomaticallyAuthorizesFurtherExtension" to false,"automaticClear" to false,"secondGuestWriteIncludingZero" to "refuse"),
            "fullFirmwareSessionInitializationQualified" to false,"physicalResetAndClearLaw" to "UNKNOWN","guestExecuted" to false,"nativeLoaded" to false,"hardwareValidated" to false,"newDatWords" to 0,"dumpPayloadBytesRead" to 0,"largeOriginalRead" to false,
            "scope" to "Two finite software input declarations, actual binding and unchanged prior catalog. Tail bounds require the separately qualified observer; no current call, physical completion, ready/ACK or boot is established.")
        Files.createDirectories(output.parent);Files.write(output,Formats.jsonBytes(report),CREATE_NEW)
        println("P121 finite input pair:697344 retained1 reads;18 old selections and32 descriptors exact;no guest/native/DAT")
    }
}

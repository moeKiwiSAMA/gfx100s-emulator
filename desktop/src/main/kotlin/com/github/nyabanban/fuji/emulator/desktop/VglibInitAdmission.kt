package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.GraphicsSuccessors
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata

/** Narrow state/port/SOURCE-sequence admission. Read samples remain explicit hypotheses. */
object VglibInitAdmission {
    const val ID="soc-vglib-init"
    const val PROFILE="vglib-full-lane-init-experimental-v1"
    const val MODE_PROFILE="vglib-mode-word-experimental-v1"
    const val MODE_CASE4_PROFILE="vglib-mode-word-case4-twice-experimental-v2"
    const val MODE_TAIL_PROFILE = "vglib-mode-word-case4-samples-experimental-v3"
    const val MODE_PORT="soc-vglib-init.mode-word"
    const val SNAPSHOT_PROFILE="lcdc-instance1-snapshot-experimental-v1"
    const val UNREADY_PROFILE="lcdc-instance1-unready-experimental-v1"
    const val UNREADY_PORT="soc-vglib-init.lcdc-unready"
    const val CONTROL_READ_LIMIT=400000
    const val FUNCTIONAL_PROFILE="lcdc-functional-experimental-v1"
    val FUNCTIONAL_PORTS=listOf("soc-vglib-init.lcdc0-functional","soc-vglib-init.lcdc1-functional")
    const val SNAPSHOT_PORT="soc-vglib-init.lcdc-snapshot"
    private fun word(x:Any?):Long {require(x is Byte||x is Short||x is Int||x is Long);return (x as Number).toLong()}
    private fun pairs(x:Any?):List<Pair<Long,Long>> = (x as List<*>).map{val r=it as Map<*,*>;word(r["offset"]) to word(r["writeValue"])}
    private fun words(port:Map<String,Any?>):List<Long> = (port["registers"] as List<*>).map{r->val row=r as Map<*,*>;require(word(row["size"])==4L&&word(row["count"])==1L&&word(row["alignment"])==4L&&(row["readWidths"] as List<*>).map(::word)==listOf(4L)&&(row["writeWidths"] as List<*>).map(::word)==listOf(4L));word(row["offset"])}.sorted()
    fun observe(catalog:AddressCatalog,meta:FirmwareMetadata,selected:List<String>,state:Map<String,Any?>?,claims:List<String>,
                kernel:(()->ByteArray)? = null, section4:(()->ByteArray)? = null):Map<String,Any>? {
        if(state==null||state["enabled"]!=true)return null
        val app by lazy { requireNotNull(section4) { "Selected vglib input requires DAT section4" }.invoke() }
        val unready=state["profile"]==UNREADY_PROFILE
        val functional=state["profile"]==FUNCTIONAL_PROFILE
        val edgeSelected = "irq118Tick" in state
        val phaseSelected = "irq117Tick" in state
        require("irq117FollowupTicks" !in state || phaseSelected)
        require(("irq117" in state) == phaseSelected && (!phaseSelected || functional)) { "LCD IRQ117 selection differs from component state" }
        require(("frameSync" in state) == ("frameSyncStartTick" in state) && ("frameSync" !in state || edgeSelected))
        require("frameSyncResumeTick" !in state || word(state["frameSyncResumeTick"]) == 0L || "frameSync" in state)
        require("frameSyncFollowupTicks" !in state || "frameSync" in state)
        require(("scanout" in state) == ("firstScanout" in state) && ("scanout" !in state || functional))
        require("scanoutFrameLimit" !in state || "firstScanout" in state)
        require(("evfScanoutState" in state) == ("evfScanout" in state) && ("evfScanoutState" !in state || functional))
        require("evfScanoutFrameLimit" !in state || "evfScanout" in state)
        require(("irq118" in state) == edgeSelected && (!edgeSelected || functional)) { "LCD IRQ118 selection differs from component state" }
        require(ID in selected&&state["profile"] in setOf(PROFILE,UNREADY_PROFILE,FUNCTIONAL_PROFILE)) {"vglib profile must be explicitly selected"}
        val windows=catalog.windowsFor(if(unready)mapOf(ID to UNREADY_PROFILE)else if(functional)mapOf(ID to FUNCTIONAL_PROFILE)else emptyMap())
        val window=windows.single{it.componentId==ID&&it.portId=="$ID.registers"}
        val mode=state["modeWord"] as? Map<*,*>
        val snapshot=state["lcdcSnapshot"] as? Map<*,*>
        require(!unready||snapshot?.get("profile")==UNREADY_PROFILE) {"LCD unready requires explicit matching snapshot input"}
        val snapshotPort=if(unready)UNREADY_PORT else SNAPSHOT_PORT
        val banks=(state["lcdcFunctional"] as? List<*>).orEmpty().map{it as Map<*,*>}
        require(functional==banks.isNotEmpty()&&(!functional||snapshot==null)) {"LCD functional profile requires its two banks and no finite snapshot"}
        val expectedPorts=listOf(window.portId)+(if(mode==null)emptyList()else listOf(MODE_PORT))+(if(snapshot==null)emptyList()else listOf(snapshotPort))+(if(functional)FUNCTIONAL_PORTS else emptyList())
        require(claims==expectedPorts) {"vglib additional port claims differ"}
        val declaration=meta.devices.filter{it.id==ID}
        require(declaration.size==expectedPorts.size&&declaration.map{it.port}.toSet()==expectedPorts.toSet()) {"vglib complete metadata window differs"}
        fun checkWindow(portId:String) {
            val w=windows.single{it.componentId==ID&&it.portId==portId}
            require(declaration.single{it.port==portId}.let{it.address==w.base&&it.size==w.size&&it.optional}) {"vglib complete metadata window differs"}
        }
        checkWindow(window.portId)
        val port=catalog.byId(window.definitionId)!!
        val backing=port["backing"] as Map<*,*>
        val expected=pairs(backing["finiteFullLaneRmw"])
        require(backing["sequenceProfile"]==PROFILE&&expected.size==18&&pairs(state["sourceSequence"])==expected) {"vglib SOURCE sequence differs"}
        val bankWords=words(port)
        require(window.size==bankWords.last()+4L) {"vglib window has unqualified padding"}
        require(bankWords==expected.map{it.first}.distinct().sorted()&&(state["wordOffsets"] as List<*>).map(::word)==bankWords) {"vglib complete word declaration differs"}
        require(state["readConfidence"]=="HYPOTHESIS"&&state["transactionCount"]==0&&state["transactionCapacity"]==36) {"vglib finite initial state differs"}
        var result:Map<String,Any> = mapOf("profile" to state.getValue("profile")!!,"pairs" to expected.size,"wordCount" to bankWords.size,"port" to window.portId,
            "scope" to "SOURCE sequence and complete word/window contract checked before native allocation; read samples HYPOTHESIS, hardware effects UNKNOWN")
        require(("graphicsFill" in state) == ("graphicsFillTail" in state))
        require(("fillIrq92" in state) == ("graphicsFillIrq92Tick" in state))
        require(("copyIrq92" in state) == ("graphicsCopyIrq92Tick" in state))
        require(("irq92Successors" in state) == ("graphicsIrq92Sequence" in state))
        if ("irq92Successors" in state) {
            require("copyIrq92" in state && state["irq92Successors"] == emptyList<Any>())
            val contract = mapOf("setting" to "graphicsIrq92Sequence", "capacity" to GraphicsSuccessors.CAPACITY, "trigger" to "successful-submission",
                "prefix" to listOf("fill", "copy"), "latencyTicks" to 1, "sink" to "soc-gic.lines", "line" to 92,
                "fillStatus" to 0x04000000, "copyStatus" to 0x40000000, "confidence" to "HYPOTHESIS")
            require(backing["irq92Successors"] == contract)
            val tail = state["graphicsFill"] as Map<*, *>
            val fillLimit = word((tail["execution"] as Map<*, *>)["submissionLimit"])
            val copyLimit = word((tail["copyExecution"] as Map<*, *>)["submissionLimit"])
            val order = GraphicsSuccessors.checkedOrder(state["graphicsIrq92Sequence"] as String, fillLimit, copyLimit)
            result += "irq92Successors" to contract + ("order" to order)
        }
        if ("copyIrq92" in state) {
            require("fillIrq92" in state)
            result += "copyIrq92" to GraphicsFillIrqAdmission.verifyCopy(state, backing, meta.sections.single { it.name == "section4" }.address, app)
        }
        if ("fillIrq92" in state) {
            require(functional && "soc-gic" in selected)
            result += "fillIrq92" to GraphicsFillIrqAdmission.verify(state, backing, meta.sections.single { it.name == "section4" }.address, app)
        }
        if ("graphicsFill" in state) {
            require(functional)
            result += "graphicsFill" to GraphicsFillAdmission.observe(state, backing, meta.sections.single { it.name == "section4" }.address, app)
        }
        if(mode!=null) {
        checkWindow(MODE_PORT)
        val modeWindow=windows.single{it.componentId==ID&&it.portId==MODE_PORT}
        val modePort=catalog.byId(modeWindow.definitionId)!!
        val modeBacking=modePort["backing"] as Map<*,*>
        require(modeWindow.size==4L&&words(modePort)==listOf(0L)) {"vglib mode word complete declaration differs"}
        val tailSelected = mode["profile"] == MODE_TAIL_PROFILE
        val extended = tailSelected || mode["profile"] == MODE_CASE4_PROFILE
        require(("additionalReadSamples" in mode) == tailSelected) { "vglib mode tail selection differs" }
        val tail = if (tailSelected) {
            require(modeBacking["additionalPairsProfile"] == MODE_TAIL_PROFILE && word(modeBacking["maxAdditionalPairs"]) == 16L)
            val samples = mode["additionalReadSamples"]
            require(samples is List<*> && samples.size in 1..16) { "vglib mode tail length differs" }
            samples.map(::word).also { values -> require(values.all { it in 0..0xffffffffL }) }
        } else emptyList()
        val capacity = if (extended) 4 + 2 * tail.size else 2
        require(modeBacking["sequenceProfile"]==MODE_PROFILE&&(extended||mode["profile"]==MODE_PROFILE)&&word(mode["sourcePreserveMask"])==word(modeBacking["sourcePreserveMask"])) {"vglib mode SOURCE mask differs"}
        require(mode["readConfidence"]=="HYPOTHESIS"&&mode["transactionCount"]==0&&mode["transactionCapacity"]==capacity&&mode["remainingTransactions"]==capacity&&mode["eventLogTruncated"]==false) {"vglib mode finite initial state differs"}
        require(("secondReadSample" in mode) == extended && word(mode["readSample"]) in 0..0xffffffffL) { "vglib mode sample declaration differs" }
        val extra = if (extended) {
            require(functional && modeBacking["secondPairProfile"] == MODE_CASE4_PROFILE && word(modeBacking["secondPairLimit"]) == 1L &&
                word(mode["sourcePreserveMask"]) == 0xfffffcffL && word(mode["secondReadSample"]) in 0..0xffffffffL) { "vglib second mode pair differs" }
            VglibModeCase4Dat.verify(meta.sections.single { it.name == "section4" }.address, app,
                meta.sections.single { it.name == "kernel" }.address, requireNotNull(kernel).invoke())
            buildMap {
                put("readSample", word(mode["readSample"]))
                put("secondReadSample", word(mode["secondReadSample"]))
                put("datSelector", 4)
                if (tailSelected) put("additionalReadSamples", tail)
            }
        } else emptyMap()
        result=result+mapOf("modeWord" to (mapOf("profile" to mode["profile"],"port" to MODE_PORT,"sourcePreserveMask" to word(modeBacking["sourcePreserveMask"]),"transactionCapacity" to capacity,"readConfidence" to "HYPOTHESIS","physicalIdentity" to "UNKNOWN","scope" to "Software-path grouping only; shared physical hardware with bank UNKNOWN; no storage/ready/IRQ/clear/render completion")+extra))
        }
        if(snapshot!=null) {
            checkWindow(snapshotPort)
            val w=windows.single{it.componentId==ID&&it.portId==snapshotPort}
            val definition=catalog.byId(w.definitionId)!!
            val b=definition["backing"] as Map<*,*>
            val order=(b["sourceReadOffsets"] as List<*>).map(::word)
            val registers=definition["registers"] as List<*>
            if(unready) {
                val control=registers.map{it as Map<*,*>}.singleOrNull{word(it["offset"])==0L}?:error("LCD unready exact control word missing")
                require(word(control["size"])==4L&&word(control["count"])==1L&&word(control["stride"])==4L&&word(control["alignment"])==4L&&(control["readWidths"] as List<*>).map(::word)==listOf(4L)&&(control["writeWidths"] as List<*>).map(::word)==listOf(4L)) {"LCD unready exact control declaration differs"}
            }
            val declared=registers.filter{!unready||word((it as Map<*,*>)["offset"])!=0L}.flatMap{raw->val r=raw as Map<*,*>;val count=word(r["count"]);val offset=word(r["offset"])
                require(word(r["size"])==4L&&word(r["stride"])==4L&&word(r["alignment"])==4L&&count in 1..140&&offset>=0&&offset%4==0L&&(r["readWidths"] as List<*>).map(::word)==listOf(4L)&&(r["writeWidths"] as List<*>).isEmpty()) {"LCD snapshot RO declaration differs"}
                (0 until count.toInt()).map{offset+it*4L}
            }
            require(w.size==0x604L&&declared.size==140&&declared.distinct().size==140&&declared.all{it in 0..w.size-4}&&declared.max()+4==w.size&&declared.sorted()==order.sorted()) {"LCD snapshot complete word/window differs"}
            require(b["sequenceProfile"]==(if(unready)UNREADY_PROFILE else SNAPSHOT_PROFILE)&&b["sourceReadOffsetsConfidence"]=="SOURCE"&&snapshot["profile"]==(if(unready)UNREADY_PROFILE else SNAPSHOT_PROFILE)&&order.size==140&&order.distinct().size==140&&(snapshot["sourceReadOffsets"] as List<*>).map(::word)==order) {"LCD snapshot SOURCE read order differs"}
            require(snapshot["readConfidence"]=="HYPOTHESIS"&&snapshot["transactionCount"]==0&&snapshot["transactionCapacity"]==140&&snapshot["remainingTransactions"]==140&&snapshot["eventLogTruncated"]==false) {"LCD snapshot finite initial state differs"}
            var controlReport:Map<String,Any> = emptyMap()
            if(unready) {
                val control=snapshot["control"] as? Map<*,*>?:error("LCD unready initial control state missing")
                require(word(b["controlReadLimitMaximum"])==CONTROL_READ_LIMIT.toLong()&&b["controlConfidence"]=="HYPOTHESIS"&&listOf("controlInitialValue","controlWriteValue","controlReadValue").all{word(b[it])==0L}) {"LCD unready declared hypothesis differs"}
                require(control["confidence"]=="HYPOTHESIS"&&listOf("initialValue","writeValue","readValue").all{word(control[it])==0L}&&listOf("write","firstRead","lastRead").all{control.containsKey(it)&&control[it]==null}&&control["phase"]=="snapshot"&&word(control["readCount"])==0L&&word(control["writeCount"])==0L&&word(control["retainedValue"])==0L&&word(control["readLimit"]) in 1..CONTROL_READ_LIMIT.toLong()&&word(control["remainingReads"])==word(control["readLimit"])) {"LCD unready finite initial state differs"}
                controlReport=mapOf("controlReadLimit" to word(control["readLimit"]),"controlInitialValue" to 0,"controlWriteValue" to 0,"controlReadValue" to 0,"controlConfidence" to "HYPOTHESIS","scope" to "Explicit no-completion hypothesis: one retained0 control write followed by finite zero reads. No ready/IRQ/ACK/clear/frame.")
            }
            result=result+mapOf("lcdcSnapshot" to (mapOf("profile" to (if(unready)UNREADY_PROFILE else SNAPSHOT_PROFILE),"port" to snapshotPort,"readCount" to 140,"readConfidence" to "HYPOTHESIS","physicalPanel" to "UNKNOWN","scope" to "SOURCE order and complete RO words checked; samples HYP, guest owns software shadow/flags/return; no frame or physical effects asserted")+controlReport))
        }
        if(functional) {
            require(banks.map{it["port"]}==FUNCTIONAL_PORTS&&banks.map{it["instance"]}==listOf(0,1)) {"LCD functional bank claims differ"}
            FUNCTIONAL_PORTS.forEach{port->
                checkWindow(port)
                val w=windows.single{it.componentId==ID&&it.portId==port}
                val definition=catalog.byId(w.definitionId)!!
                val b=definition["backing"] as Map<*,*>
                require(w.size==0x1000L&&b["sequenceProfile"]==FUNCTIONAL_PROFILE&&b["readConfidence"]=="HYPOTHESIS"&&b["controlConfidence"]=="HYPOTHESIS"&&word(b["controlCompletionMask"])==1L) {"LCD functional declared hypothesis differs"}
                val declared=(definition["registers"] as List<*>).map{it as Map<*,*>}.sumOf{r->
                    require(word(r["size"])==4L&&word(r["stride"])==4L&&word(r["alignment"])==4L&&(r["readWidths"] as List<*>).map(::word)==listOf(4L)&&(r["writeWidths"] as List<*>).map(::word)==listOf(4L)) {"LCD functional word declaration differs"}
                    word(r["count"])*4L}
                require(declared==w.size) {"LCD functional bank has undeclared words"}
            }
            require(banks.all{it["profile"]==FUNCTIONAL_PROFILE&&it["confidence"]=="HYPOTHESIS"&&word(it["sizeBytes"])==0x1000L&&word(it["observedAccesses"])==0L&&word(it["resetWrites"])==0L&&it["resetCompleted"]==false}) {"LCD functional initial state differs"}
            result=result+mapOf("lcdcFunctional" to mapOf("profile" to FUNCTIONAL_PROFILE,"ports" to FUNCTIONAL_PORTS,"bankBytes" to 0x1000,"confidence" to "HYPOTHESIS",
                "scope" to "Behavioural retained banks for both software instances; word0 bit0 reads1 after guest WRITE4(0). Consumer-derived hypothesis, physical controller/panel UNKNOWN; no IRQ or frame asserted"))
            if (phaseSelected) {
                val window = windows.single { it.portId == FUNCTIONAL_PORTS[1] }
                val backing = catalog.byId(window.definitionId)!!.getValue("backing") as Map<*, *>
                val declared = backing["framePhaseEdge"] as Map<*, *>
                val route = mapOf("instance" to 1, "group" to 0, "line" to 117, "sink" to "soc-gic.lines", "capacity" to 1, "confidence" to "HYPOTHESIS")
                val deadline = word(state["irq117Tick"])
                require("soc-gic" in selected && declared["setting"] == "irq117Tick" && word(declared["disabledValue"]) == 0L &&
                    declared["clock"] == "absolute-owner-virtual-tick" && declared["precondition"] == "enabled-edge-low-nonpending" &&
                    route.all { (key, value) -> declared[key] == value }) { "LCD IRQ117 route declaration differs" }
                var initialPhase: Map<String, Any?> = route + mapOf("deadline" to deadline, "firedAt" to null)
                var followupReport = emptyMap<String, Any>()
                if ("irq117FollowupTicks" in state) {
                    val contract = mapOf("setting" to "irq117FollowupTicks", "maxAdditionalEdges" to 32,
                        "clock" to "absolute-owner-virtual-tick", "order" to "strictly-increasing", "restore" to "append-only")
                    require(declared["followups"] == contract)
                    val text = state["irq117FollowupTicks"] as String
                    require(text.length in 1..351)
                    val ticks = text.split(',').map { token ->
                        val tick = token.toLongOrNull()
                        require(tick != null && tick.toString() == token && tick in 1..0xffffffffL)
                        tick
                    }
                    require(ticks.size <= 32 && ticks.first() > deadline && ticks.zipWithNext().all { (before, after) -> before < after })
                    initialPhase += mapOf("capacity" to 1 + ticks.size, "followupFiredAt" to emptyList<Long>())
                    followupReport = mapOf("capacity" to 1 + ticks.size, "followupTicks" to ticks)
                }
                require(deadline in 1..0xffffffffL && state["irq117"] == initialPhase) { "LCD IRQ117 initial state differs" }
                val base = meta.sections.single { it.name == "section4" }.address
                require(base == 0x01021000L)
                verifyVglibWords("LCD IRQ117", base, app, mapOf(0x016d551c to listOf(0x0126fb9cL),
                    0x0126fb9c to listOf(0xe92d4800L, 0xe28db004L, 0xe3a01000L, 0xe3a00001L, 0xebffff76L, 0xe8bd8800L)))
                result += "irq117" to route + followupReport + mapOf("deadline" to deadline, "datCallback" to 0x0126fb9c, "source" to (declared["source"] as String))
            }
            if (edgeSelected) {
                val window1 = windows.single { it.portId == FUNCTIONAL_PORTS[1] }
                val backing1 = catalog.byId(window1.definitionId)!!.getValue("backing") as Map<*, *>
                val declared = backing1["optionalEdge"] as Map<*, *>
                val edge = state["irq118"] as Map<*, *>
                require("soc-gic" in selected && declared["setting"] == "irq118Tick" && word(declared["disabledValue"]) == 0L &&
                    declared["clock"] == "absolute-owner-virtual-tick" && word(declared["instance"]) == 1L &&
                    word(declared["line"]) == 118L && declared["sink"] == "soc-gic.lines" && word(declared["capacity"]) == 1L &&
                    declared["confidence"] == "HYPOTHESIS") { "LCD IRQ118 route declaration differs" }
                val deadline = word(state["irq118Tick"])
                require(deadline in 1..0xffffffffL && edge.keys == setOf("confidence","instance","line","sink","capacity","deadline","firedAt") &&
                    listOf("confidence","instance","line","sink","capacity").all { edge[it] == declared[it] } &&
                    word(edge["deadline"]) == deadline && edge["firedAt"] == null) { "LCD IRQ118 initial state differs" }
                val appBase = meta.sections.single { it.name == "section4" }.address
                LcdcIrq118Dat.verify(appBase, app)
                if ("frameSync" in state) {
                    val contract = mapOf("setting" to "frameSyncStartTick", "disabledValue" to 0, "periodTicks" to 2000000,
                        "capacity" to 32, "clock" to "absolute-owner-virtual-tick", "pending" to "gic-coalesces-edges", "confidence" to "HYPOTHESIS")
                    require(declared["frameSync"] == contract)
                    val start = word(state["frameSyncStartTick"])
                    val resume = state["frameSyncResumeTick"]?.let { word(it) } ?: 0L
                    val periodicCapacity = if (resume == 0L) 32 else 64
                    val extension = if (resume == 0L) emptyMap() else {
                        require(declared["frameSyncResume"] == mapOf("setting" to "frameSyncResumeTick",
                            "disabledValue" to 0, "additionalEdges" to 32, "clock" to "absolute-owner-virtual-tick",
                            "restore" to "append-only", "confidence" to "HYPOTHESIS")) { "LCD frame sync resume declaration differs" }
                        require(resume in start + 32 * 2000000L..0xffffffffL)
                        mapOf<String, Any>("resumeTick" to resume, "capacity" to 64)
                    }
                    var followupState = emptyMap<String, Any>()
                    var followupReport = emptyMap<String, Any>()
                    if ("frameSyncFollowupTicks" in state) {
                        val followupContract = mapOf("setting" to "frameSyncFollowupTicks", "maxAdditionalEdges" to 256,
                            "clock" to "absolute-owner-virtual-tick", "order" to "strictly-increasing",
                            "restore" to "append-only", "confidence" to "HYPOTHESIS")
                        require(declared["frameSyncFollowups"] == followupContract) { "LCD frame sync followups declaration differs" }
                        val text = state["frameSyncFollowupTicks"] as String
                        require(text.length in 1..(256 * 11 - 1))
                        val floor = (if (resume > 0L) resume else start) + 32 * 2000000L
                        val ticks = text.split(',').map { token ->
                            val tick = token.toLongOrNull()
                            require(tick != null && tick.toString() == token && tick in 1..0xffffffffL)
                            tick
                        }
                        require(ticks.size <= 256 && ticks.first() >= floor && ticks.zipWithNext().all { (before, after) -> before < after })
                        followupState = mapOf("capacity" to periodicCapacity + ticks.size, "followupFiredAt" to emptyList<Long>())
                        followupReport = mapOf("capacity" to periodicCapacity + ticks.size, "followupTicks" to ticks)
                    }
                    require(start in deadline + 1..0xffffffffL && state["frameSync"] == mapOf("firstTick" to start,
                        "periodTicks" to 2000000L, "capacity" to 32, "confidence" to "HYPOTHESIS", "edgeTicks" to emptyList<Long>()) + extension + followupState)
                    result += "frameSync" to contract + ("firstTick" to start) + extension + followupReport
                }
                result += "irq118" to mapOf("line" to 118, "sink" to "soc-gic.lines", "capacity" to 1,
                    "deadline" to deadline, "confidence" to "HYPOTHESIS", "datCallback" to 0x0126fb84,
                    "datBridge" to 0x012708a8, "source" to (declared["source"] as String))
            }
        }
        if ("firstScanout" in state) result += "scanout" to LcdcScanoutAdmission.verify(catalog, meta, state, app, requireNotNull(kernel).invoke())
        if ("evfScanout" in state) result += "evfScanout" to LcdcScanoutAdmission.verifyEvf(catalog, meta, state, app, requireNotNull(kernel).invoke())
        return result
    }
}

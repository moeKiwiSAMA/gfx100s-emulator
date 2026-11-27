package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*

/** Finite full-lane observations with an optional explicit unready control hypothesis; no autonomous effects. */
class VglibInitFactory:ComponentFactory {
    companion object {
        const val PROFILE="vglib-full-lane-init-experimental-v1"
        const val UNREADY_PROFILE=LcdcSnapshotInput.UNREADY_PROFILE
        const val FUNCTIONAL_PROFILE=LcdcFunctional.PROFILE
        val DESCRIPTOR=ComponentDescriptor("soc-vglib-init","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,"disabled",choices=listOf("disabled",PROFILE,UNREADY_PROFILE,FUNCTIONAL_PROFILE),live=false),
            Setting("inputFile",ValueKind.TEXT,"",live=false),
            Setting(GraphicsFillTail.SETTING,ValueKind.TEXT,"disabled",choices=listOf("disabled",GraphicsFillTail.PROFILE),live=false),
            Setting(GraphicsFillIrq92.SETTING,ValueKind.INTEGER,0L,0.0,4294967295.0,live=false),
            Setting(GraphicsCopyIrq92.SETTING,ValueKind.INTEGER,0L,0.0,4294967295.0,live=false),
            Setting(GraphicsIrq92Sequence.SETTING,ValueKind.TEXT,"",live=false),
            Setting(LcdcFirstScanout.SETTING,ValueKind.TEXT,"disabled",choices=listOf("disabled",LcdcFirstScanout.PROFILE,LcdcFirstScanout.RGBA,LcdcFirstScanout.PIC_YUV_OSD0),live=false),
            Setting(LcdcFirstScanout.LIMIT_SETTING,ValueKind.INTEGER,1L,1.0,LcdcFirstScanout.MAX_FRAMES.toDouble(),live=false),
            Setting(LcdcFirstScanout.EVF_SETTING,ValueKind.TEXT,"disabled",choices=listOf("disabled",LcdcFirstScanout.PROFILE,LcdcFirstScanout.RGBA,LcdcFirstScanout.PIC_YUV_OSD0),live=false),
            Setting(LcdcFirstScanout.EVF_LIMIT_SETTING,ValueKind.INTEGER,1L,1.0,LcdcFirstScanout.MAX_FRAMES.toDouble(),live=false),
            Setting("controlWordInputFile",ValueKind.TEXT,"",live=false),
            Setting(LcdcIrq118.SETTING,ValueKind.INTEGER,0L,minimum=0.0,maximum=4294967295.0,live=false),
            Setting(LcdcIrq117.SETTING,ValueKind.INTEGER,0L,minimum=0.0,maximum=4294967295.0,live=false),
            Setting(LcdcIrq117.PERIOD_SETTING, ValueKind.INTEGER, LcdcIrq118.PERIOD, minimum=1.0, maximum=4294967295.0, live=false,
                description="Continuous LCDC1 phase interrupt period in SoC ticks"),
            Setting(LcdcIrq117.FOLLOWUPS,ValueKind.TEXT,"",live=false),
            Setting(LcdcIrq118.SYNC,ValueKind.INTEGER,0L,minimum=0.0,maximum=4294967295.0,live=false),
            Setting(LcdcIrq118.RESUME,ValueKind.INTEGER,0L,minimum=0.0,maximum=4294967295.0,live=false),
            Setting(LcdcIrq118.FOLLOWUPS,ValueKind.TEXT,"",live=false),
            Setting(LcdcIrq118.PERIOD_SETTING,ValueKind.INTEGER,0L,minimum=0.0,maximum=4294967295.0,live=false,description="Optional IRQ118 frame-sync period in ticks (0 = legacy 2,000,000; 900,900 = exact 59.94 Hz NTSC @ 54 MHz)"),
            Setting("continuousScanout",ValueKind.BOOLEAN,false,live=false),
            Setting(LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING,ValueKind.BOOLEAN,false,live=false),
            Setting("lcdc0ClockGated",ValueKind.BOOLEAN,false,live=false),
            Setting("lcdc1ClockGated",ValueKind.BOOLEAN,false,live=false),
            Setting("unclockedAccessPolicy",ValueKind.TEXT,LcdcFunctional.POLICY_WARN,choices=listOf(LcdcFunctional.POLICY_WARN,LcdcFunctional.POLICY_REFUSE,LcdcFunctional.POLICY_ABORT),live=false),
            Setting("lcdcSnapshotInputFile",ValueKind.TEXT,"",live=false)))
    }
    override val descriptor=DESCRIPTOR
    override fun create(context:ComponentContext,config:Map<String,Any>):HardwareComponent=Sequence(context,config)
    private class Sequence(context:ComponentContext,config:Map<String,Any>):ScalarComponent("soc-vglib-init",context,config),StatefulComponent {
        private val active=config["profile"] in listOf(PROFILE,UNREADY_PROFILE,FUNCTIONAL_PROFILE)
        private val continuousScanout = (config.getOrDefault("continuousScanout", false) as? Boolean)
            ?: throw IllegalArgumentException("continuousScanout requires boolean")
        private val strictActiveOutputGate = (config.getOrDefault(LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING, false) as? Boolean)
            ?: throw IllegalArgumentException("${LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING} requires boolean")
        private val frameLimit = VglibSnapshotCodec.requireInt(config.getOrDefault(LcdcFirstScanout.LIMIT_SETTING, 1L),
            LcdcFirstScanout.LIMIT_SETTING, 1..LcdcFirstScanout.MAX_FRAMES)
        private val scanout = when (val profile = config.getOrDefault(LcdcFirstScanout.SETTING, "disabled")) {
            "disabled" -> {
                require(frameLimit == 1) { "Multiple frames require explicit LCD scanout" }
                null
            }
            LcdcFirstScanout.PROFILE, LcdcFirstScanout.RGBA, LcdcFirstScanout.PIC_YUV_OSD0 -> {
                require(config["profile"] == FUNCTIONAL_PROFILE) { "LCD scanout requires functional banks" }
                LcdcFirstScanout(context, frameLimit, profile as String, continuous = continuousScanout, strictActiveOutputGate = strictActiveOutputGate)
            }
            else -> throw IllegalArgumentException("Unknown LCD scanout hypothesis")
        }
        private val evfFrameLimit = VglibSnapshotCodec.requireInt(config.getOrDefault(LcdcFirstScanout.EVF_LIMIT_SETTING, 1L),
            LcdcFirstScanout.EVF_LIMIT_SETTING, 1..LcdcFirstScanout.MAX_FRAMES)
        private val evfScanout = when (val profile = config.getOrDefault(LcdcFirstScanout.EVF_SETTING, "disabled")) {
            "disabled" -> {
                require(evfFrameLimit == 1) { "Multiple EVF frames require explicit EVF scanout" }
                null
            }
            LcdcFirstScanout.PROFILE, LcdcFirstScanout.RGBA, LcdcFirstScanout.PIC_YUV_OSD0 -> {
                require(config["profile"] == FUNCTIONAL_PROFILE) { "EVF scanout requires functional banks" }
                LcdcFirstScanout(context, evfFrameLimit, profile as String, displayPort = "display.evf", continuous = continuousScanout, strictActiveOutputGate = strictActiveOutputGate)
            }
            else -> throw IllegalArgumentException("Unknown EVF scanout hypothesis")
        }
        private val lcdc0ClockGated = (config.getOrDefault("lcdc0ClockGated", false) as? Boolean)
            ?: throw IllegalArgumentException("lcdc0ClockGated requires boolean")
        private val lcdc1ClockGated = (config.getOrDefault("lcdc1ClockGated", false) as? Boolean)
            ?: throw IllegalArgumentException("lcdc1ClockGated requires boolean")
        private val unclockedAccessPolicy = (config.getOrDefault("unclockedAccessPolicy", LcdcFunctional.POLICY_WARN) as? String)
            ?.also { require(it in LcdcFunctional.VALID_POLICIES) { "Unsupported unclockedAccessPolicy: $it" } }
            ?: throw IllegalArgumentException("unclockedAccessPolicy requires string")
        private val functional = if (config["profile"] == FUNCTIONAL_PROFILE) {
            listOf(
                LcdcFunctional(
                    context = context,
                    instance = 0,
                    scanout = evfScanout,
                    initiallyPowered = !lcdc0ClockGated,
                    unclockedAccessPolicy = unclockedAccessPolicy,
                ),
                LcdcFunctional(
                    context = context,
                    instance = 1,
                    scanout = scanout,
                    initiallyPowered = !lcdc1ClockGated,
                    unclockedAccessPolicy = unclockedAccessPolicy,
                ),
            )
        } else {
            emptyList()
        }
        private val syncStart = unsigned(config.getOrDefault(LcdcIrq118.SYNC, 0L))
        private val resumeTick = unsigned(config.getOrDefault(LcdcIrq118.RESUME, 0L)).also {
            require(it == 0L || syncStart > 0)
        }
        private val frameSyncPeriodSetting = unsigned(config.getOrDefault(LcdcIrq118.PERIOD_SETTING, 0L))
        private val frameSyncPeriod = if (frameSyncPeriodSetting == 0L) LcdcIrq118.PERIOD else frameSyncPeriodSetting
        private val syncFollowups = LcdcIrq118.parseTicks(
            config.getOrDefault(LcdcIrq118.FOLLOWUPS, "") as String,
            syncStart,
            resumeTick,
            frameSyncPeriod,
        )
        private val irq = unsigned(config.getOrDefault(LcdcIrq118.SETTING, 0L)).also {
            require(syncStart == 0L || it > 0)
            require(frameSyncPeriodSetting == 0L || it > 0) { "${LcdcIrq118.PERIOD_SETTING} requires ${LcdcIrq118.SETTING}" }
        }.takeIf { it > 0 }?.let { deadline ->
            require(functional.size == 2) { "LCD IRQ118 requires functional LCD banks" }
            LcdcIrq118(context, functional[1], deadline, syncStart, resumeTick, syncFollowups, continuous = continuousScanout, periodTicks = frameSyncPeriod)
        }
        private val phaseDeadline = unsigned(config.getOrDefault(LcdcIrq117.SETTING, 0L))
        private val phaseTicks = LcdcIrq117.parseTicks(config.getOrDefault(LcdcIrq117.FOLLOWUPS, "") as String, phaseDeadline)
        private val phase = phaseDeadline.takeIf { it > 0 }?.let { deadline ->
            require(functional.size == 2) { "LCD IRQ117 requires functional LCD banks" }
            val period = unsigned(config.getOrDefault(LcdcIrq117.PERIOD_SETTING, LcdcIrq118.PERIOD))
            LcdcIrq117(context, irq ?: functional[1], deadline, phaseTicks, continuous = continuousScanout, periodTicks = period)
        }
        private val lcdc0Irqs = if (continuousScanout && functional.size == 2 && (irq != null || phase != null)) {
            Lcdc0ContinuousIrqs(context, functional[0])
        } else {
            null
        }
        private val mode=run {
            val file=config.getOrDefault("controlWordInputFile","") as String
            require(active||file.isEmpty()) {"mode word requires selected vglib profile"}
            ModeWordInput.load(context,file,continuous=continuousScanout) {
                // section4.bin @ 0x012556BC writes 0xFFF49700 (soc-vglib-init.mode-word) during lcdevf
                // display bring-up (msg 0x13DC) immediately before 0x01250340 -> 0x01263014 reads LCDC1!
                if (functional.size > 1) {
                    val lcdc1 = functional[1]
                    if (!lcdc1.powerGateState().clocked) {
                        lcdc1.setPowerClock(nextPowered = true, nextClocked = true)
                    }
                }
            }
        }
        private val snapshot=run { val file=config.getOrDefault("lcdcSnapshotInputFile","") as String;require(active||file.isEmpty()) {"LCD snapshot requires selected vglib profile"};LcdcSnapshotInput.load(context,file) }
        private val offsets=LongArray(18);private val reads=LongArray(18);private val writes=LongArray(18)
        private val logValues=LongArray(36);private val logTicks=LongArray(36)
        private val logCores=IntArray(36){-1};private val logPcs=LongArray(36){-1}
        private var position=0
        private val fill = when(config.getOrDefault(GraphicsFillTail.SETTING,"disabled")) {
            "disabled" -> null
            GraphicsFillTail.PROFILE -> {
                require(functional.isNotEmpty()) { "Graphics fill requires functional banks" }
                GraphicsFillTail(context, continuous = continuousScanout)
            }
            else -> error("Unknown graphics fill tail")
        }
        private val fillDeadline = unsigned(config.getOrDefault(GraphicsFillIrq92.SETTING, 0L))
        private var fillIrq: GraphicsFillIrq92? = null
        private val copyDeadline = unsigned(config.getOrDefault(GraphicsCopyIrq92.SETTING, 0L))
        private var copyIrq: GraphicsCopyIrq92? = null
        private var successors: GraphicsIrq92Sequence? = null
        init {
            val selection = config.getOrDefault(GraphicsIrq92Sequence.SETTING, "") as String
            require(fill?.commandIrq == null || selection.isNotEmpty())
            require(selection.isEmpty() || copyDeadline > 0) { "IRQ92 successors require both legacy IRQ92 inputs" }
            require(fillDeadline == 0L || fill != null)
            require(copyDeadline == 0L || fillDeadline > 0)
            require(config["profile"] in listOf("disabled",PROFILE,UNREADY_PROFILE,FUNCTIONAL_PROFILE)) {"unknown vglib init profile"}
            require(functional.isEmpty()||snapshot==null) {"functional LCD controller replaces the finite snapshot input"}
            require(mode?.multiplePairs != true || functional.isNotEmpty()) { "Additional mode word pairs require functional LCD banks" }
            require((config["profile"]==UNREADY_PROFILE)==(snapshot?.unready==true)) {"LCD unready input requires matching explicit component profile"}
            if (continuousScanout) scanout?.attachEvfPeer(evfScanout)
            val file=config.getValue("inputFile") as String
            require(active==file.isNotBlank()) {"explicit finite vglib input required only when selected"}
            if(active) {
                val data=context.readData(file)
                require(data.keys==setOf("schema","profile","readConfidence","transactions")&&data["schema"]==1&&data["profile"]==PROFILE&&data["readConfidence"]=="HYPOTHESIS") {"invalid finite vglib input"}
                val rows=data["transactions"] as? List<*> ?: error("missing finite vglib transactions")
                require(rows.size==18) {"finite vglib profile requires eighteen pairs"}
                rows.forEachIndexed { i,raw ->
                    val row=raw as? Map<*,*> ?: error("invalid vglib pair")
                    require(row.keys==setOf("offset","readValue","writeValue"))
                    offsets[i]=unsigned(row["offset"]);reads[i]=unsigned(row["readValue"]);writes[i]=unsigned(row["writeValue"])
                    require(offsets[i] in 0..0x18c0L&&offsets[i]%4==0L) {"vglib pair outside bounded aligned domain"}
                }
                val registers = object:MmioDevice {
                    override val clockOrder=90
                    override fun read(offset:Long,size:Int):Long {
                        if(position==36 && fill!=null) return fill.read(offset,size)
                        check(offset,size,false,null)
                        val value=reads[position/2];record(value);return value
                    }
                    override fun write(offset:Long,size:Int,value:Long) {
                        if(position==36 && fill!=null) return fill.write(offset,size,value)
                        check(offset,size,true,value);record(value)
                    }
                }
                if (fillDeadline > 0) fillIrq = GraphicsFillIrq92(context, registers, requireNotNull(fill), fillDeadline, continuous = continuousScanout) { position == 36 }
                if (copyDeadline > 0) copyIrq = GraphicsCopyIrq92(context, requireNotNull(fillIrq), requireNotNull(fill), copyDeadline, continuous = continuousScanout)
                if (selection.isNotEmpty()) successors = GraphicsIrq92Sequence(context, requireNotNull(fill),
                    requireNotNull(fillIrq), requireNotNull(copyIrq), selection, continuous = continuousScanout) { position == 36 }
                context.ports.register(id,"$id.registers",MmioDevice::class.java,successors ?: copyIrq ?: fillIrq ?: registers)
            }
            mode?.let{context.ports.register(id,ModeWordInput.PORT,MmioDevice::class.java,it)}
            snapshot?.let{context.ports.register(id,it.port,MmioDevice::class.java,it)}
            functional.forEach { bank ->
                val device = if (bank.instance == 1) phase ?: irq ?: bank else lcdc0Irqs ?: bank
                context.ports.register(id, bank.port, MmioDevice::class.java, device)
            }
            if (functional.isNotEmpty()) {
                val lcdc0 = functional[0]
                context.ports.register(id, LcdcFunctional.LCDC0_POWER_PORT, LcdcPowerGatePort::class.java, object : LcdcPowerGatePort {
                    override fun setLcdc0PowerClock(powered: Boolean, clocked: Boolean) {
                        lcdc0.setPowerClock(powered, clocked)
                    }
                    override fun lcdc0PowerState(): LcdcPowerGateState = lcdc0.powerGateState()
                })
                val lcdc1 = functional[1]
                context.ports.register(id, LcdcFunctional.LCDC1_POWER_PORT, LcdcPowerGatePort::class.java, object : LcdcPowerGatePort {
                    override fun setLcdc0PowerClock(powered: Boolean, clocked: Boolean) {
                        lcdc1.setPowerClock(powered, clocked)
                    }
                    override fun lcdc0PowerState(): LcdcPowerGateState = lcdc1.powerGateState()
                })
                context.ports.register(id, LcdcFunctional.LCDC1_LAYERS_PORT, LcdcLayerObservabilityPort::class.java, object : LcdcLayerObservabilityPort {
                    override fun lcdc1LayerState(): LcdcLayerOutputState = lcdc1.layerOutputState()
                })
            }
        }
        override fun start() { irq?.bind(); phase?.bind(); lcdc0Irqs?.bind(); scanout?.bind(); evfScanout?.bind(); fillIrq?.bind() }
        override fun interruptRoutes() = listOfNotNull(
            lcdc0Irqs?.let { InterruptRoute(Lcdc0ContinuousIrqs.SINK, Lcdc0ContinuousIrqs.LINE_0..Lcdc0ContinuousIrqs.LINE_1) },
            phase?.let { InterruptRoute(LcdcIrq117.SINK, LcdcIrq117.LINE..LcdcIrq117.LINE) },
            irq?.let { InterruptRoute(LcdcIrq118.SINK, LcdcIrq118.LINE..LcdcIrq118.LINE) },
            fillIrq?.let { InterruptRoute(GraphicsFillIrq92.SINK, GraphicsFillIrq92.LINE..GraphicsFillIrq92.LINE) })
        private fun unsigned(value:Any?):Long {
            require(value is Byte||value is Short||value is Int||value is Long) {"vglib input requires integer"}
            return (value as Number).toLong().also{require(it in 0..0xffffffffL){"vglib input requires unsigned word"}}
        }
        private fun check(offset:Long,size:Int,write:Boolean,value:Long?) {
            require(active&&size==4&&offset>=0&&offset%4==0L&&offset<=0x18c0) {"vglib exact aligned word required"}
            check(position<36) {"vglib finite transaction input exhausted"}
            require(write==(position%2==1)&&offset==offsets[position/2]) {"vglib transaction direction/order differs"}
            if(write)require(value==writes[position/2]) {"vglib expected full-lane write differs"}
        }
        private fun record(value:Long) {
            val tick=context.clock.ticks();require(tick>=0){"invalid vglib clock"}
            val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current()
            logValues[position]=value;logTicks[position]=tick;logCores[position]=location?.core?:-1;logPcs[position]=location?.pcVirtual?:-1
            position++
        }
        override fun additionalMmioPorts()=(if(active)listOf("$id.registers")else emptyList())+(if(mode==null)emptyList()else listOf(ModeWordInput.PORT))+(snapshot?.let{listOf(it.port)}?:emptyList())+functional.map{it.port}
        override fun state():Map<String,Any?> = ((if(mode==null)super.state()-"controlWordInputFile" else super.state()).let{if(snapshot==null)it-"lcdcSnapshotInputFile" else it}).let {
            if (irq == null) it - LcdcIrq118.SETTING else it + ("irq118" to irq.state())
        }.let {
            if (phase == null) it - LcdcIrq117.SETTING else it + ("irq117" to phase.state())
        }.let {
            if (lcdc0Irqs == null) it else it + ("lcdc0Irqs" to lcdc0Irqs.state())
        }.let {
            if (phaseTicks.isEmpty()) it - LcdcIrq117.FOLLOWUPS else it
        }.let {
            if (syncStart == 0L) it - LcdcIrq118.SYNC else it + ("frameSync" to requireNotNull(irq).syncState())
        }.let {
            if (resumeTick == 0L) it - LcdcIrq118.RESUME else it
        }.let {
            if (syncFollowups.isEmpty()) it - LcdcIrq118.FOLLOWUPS else it
        }.let {
            if (frameSyncPeriodSetting == 0L) it - LcdcIrq118.PERIOD_SETTING else it
        }.let {
            if (fill == null) it - GraphicsFillTail.SETTING else it + ("graphicsFill" to fill.state())
        }.let {
            fillIrq?.let { candidate -> it + ("fillIrq92" to candidate.state() + mapOf("confidence" to "HYPOTHESIS")) } ?: (it - GraphicsFillIrq92.SETTING)
        }.let {
            copyIrq?.let { candidate -> it + ("copyIrq92" to candidate.snapshot() + mapOf("confidence" to "HYPOTHESIS")) } ?: (it - GraphicsCopyIrq92.SETTING)
        }.let {
            if (successors == null) it - GraphicsIrq92Sequence.SETTING else it + ("irq92Successors" to successors!!.snapshot())
        }.let {
            if (scanout == null) it - LcdcFirstScanout.SETTING else it + ("scanout" to scanout.state())
        }.let {
            if (frameLimit == 1) it - LcdcFirstScanout.LIMIT_SETTING else it
        }.let {
            if (evfScanout == null) it - LcdcFirstScanout.EVF_SETTING else it + ("evfScanoutState" to evfScanout.state())
        }.let {
            if (evfFrameLimit == 1) it - LcdcFirstScanout.EVF_LIMIT_SETTING else it
        }+mapOf("enabled" to active,"transactionCapacity" to 36,"transactionCount" to position,"completedPairs" to position/2,
            "sourceSequence" to if(active)offsets.indices.map{i->mapOf("offset" to offsets[i],"writeValue" to writes[i])}else emptyList<Map<String,Long>>(),
            "wordOffsets" to if(active)offsets.distinct().sorted()else emptyList<Long>(),"readSamples" to if(active)reads.toList()else emptyList<Long>(),
            "remainingTransactions" to if(active)36-position else 0,"eventLogTruncated" to false,"readConfidence" to "HYPOTHESIS",
            "transactions" to (0 until position).map{i->mapOf("offset" to offsets[i/2],"direction" to if(i%2==0)"read" else "write","size" to 4,"value" to logValues[i],"tick" to logTicks[i],"core" to logCores[i].takeIf{it>=0},"pcVirtual" to logPcs[i].takeIf{it>=0})},
            "scope" to if(snapshot?.unready==true)"Finite expected full-lane transactions, explicit samples, and selected finite LCD unready retained-zero hypothesis only. No reset/W1C/read-clear/IRQ/ready/DMA/rendering completion law supplied." else "Finite expected full-lane transactions and explicit read samples only. No reset/storage/W1C/read-clear/IRQ/ready/DMA/rendering completion law supplied.")+(mode?.let{mapOf("modeWord" to it.state())}?:emptyMap())+(snapshot?.let{mapOf("lcdcSnapshot" to it.state())}?:emptyMap())+(if(functional.isEmpty())emptyMap()else mapOf("lcdcFunctional" to functional.map{it.state()}, "lcdc0Powered" to functional[0].powerGateState().powered, "lcdc0Clocked" to functional[0].powerGateState().clocked, "unclockedLcdc0AccessCount" to functional[0].powerGateState().unclockedAccessCount, "unclockedAccessPolicy" to unclockedAccessPolicy))
        override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "settings" to saveScalarValues().filterKeys { (it != GraphicsIrq92Sequence.SETTING || successors != null) && (it != LcdcIrq117.SETTING || phase != null) && (it != LcdcIrq118.SYNC || syncStart > 0) && (it != GraphicsCopyIrq92.SETTING || copyIrq != null) && (it != GraphicsFillIrq92.SETTING || fillIrq != null) && (it != GraphicsFillTail.SETTING || fill != null) && (it != LcdcIrq118.SETTING || irq != null) &&
                (it != LcdcIrq118.RESUME || resumeTick > 0) && (it != LcdcIrq118.FOLLOWUPS || syncFollowups.isNotEmpty()) && (it != LcdcIrq118.PERIOD_SETTING || frameSyncPeriodSetting > 0L) && (it != LcdcIrq117.FOLLOWUPS || phaseTicks.isNotEmpty()) && (it != LcdcFirstScanout.SETTING || scanout != null) && (it != LcdcFirstScanout.LIMIT_SETTING || frameLimit > 1) && (it != LcdcFirstScanout.EVF_SETTING || evfScanout != null) && (it != LcdcFirstScanout.EVF_LIMIT_SETTING || evfFrameLimit > 1) && (it != LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING || strictActiveOutputGate) && (it != "lcdc0ClockGated" || lcdc0ClockGated) && (it != "lcdc1ClockGated" || lcdc1ClockGated) && (it != "unclockedAccessPolicy" || unclockedAccessPolicy != LcdcFunctional.POLICY_WARN) },
            "active" to active,
            "sequence" to linkedMapOf(
                "position" to position,
                "offsets" to VglibSnapshotCodec.encodeWords(if (active) offsets else LongArray(0)),
                "reads" to VglibSnapshotCodec.encodeWords(if (active) reads else LongArray(0)),
                "writes" to VglibSnapshotCodec.encodeWords(if (active) writes else LongArray(0)),
                "transactions" to (0 until position).map { index ->
                    linkedMapOf(
                        "value" to logValues[index],
                        "tick" to logTicks[index],
                        "core" to logCores[index],
                        "pcVirtual" to logPcs[index],
                    )
                },
            ),
            "modeWord" to mode?.saveSnapshotState(),
            "lcdcSnapshot" to snapshot?.saveSnapshotState(),
            "lcdcFunctional" to if (functional.isEmpty()) null else functional.map { bank -> bank.saveSnapshotState() },
        ) + (fill?.let { mapOf("graphicsFill" to it.snapshot()) } ?: emptyMap()) + (irq?.let { mapOf("irq118" to mapOf("deadline" to it.deadline, "firedAt" to it.firedAt)) } ?: emptyMap()) +
            (scanout?.let { mapOf("scanout" to it.snapshot()) } ?: emptyMap()) +
            (evfScanout?.let { mapOf("evfScanout" to it.snapshot()) } ?: emptyMap()) +
            (phase?.let { mapOf("irq117" to it.snapshot()) } ?: emptyMap()) +
            (fillIrq?.let { mapOf("fillIrq92" to it.state()) } ?: emptyMap()) +
            (copyIrq?.let { mapOf("copyIrq92" to it.snapshot()) } ?: emptyMap()) +
            (successors?.let { mapOf("irq92Successors" to it.snapshot()) } ?: emptyMap()) +
            (if (syncStart > 0) mapOf("frameSync" to requireNotNull(irq).syncState()) else emptyMap())
        override fun restoreSnapshot(data: Map<String, Any?>) {
            val expectedKeys = setOf(
                "schema", "componentId", "settings", "active",
                "sequence", "modeWord", "lcdcSnapshot", "lcdcFunctional",
            )
            require(VglibSnapshotCodec.requireInt(data["schema"], "schema") == snapshotSchemaVersion) {
                "Unsupported soc-vglib-init snapshot schema"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val settingsMap = VglibSnapshotCodec.requireMap(data["settings"], "settings")
            require(settingsMap.containsKey("profile") && settingsMap.containsKey("inputFile")) {
                "Missing required scalar settings in soc-vglib-init snapshot"
            }
            val savedProfile = VglibSnapshotCodec.requireString(settingsMap["profile"], "settings.profile")
            val savedIrq = LcdcIrq118.SETTING in settingsMap
            val savedPhase = LcdcIrq117.SETTING in settingsMap
            require(("irq117" in data) == savedPhase)
            if (savedPhase || phase != null) {
                require(savedProfile == FUNCTIONAL_PROFILE && phase != null && data["lcdcFunctional"] != null)
            }
            val savedTicks = if (LcdcIrq117.FOLLOWUPS in settingsMap) {
                require(savedPhase)
                VglibSnapshotCodec.requireString(settingsMap[LcdcIrq117.FOLLOWUPS], LcdcIrq117.FOLLOWUPS)
            } else null
            val savedPhaseDeadline = if (savedPhase) unsigned(settingsMap[LcdcIrq117.SETTING]) else null
            val phaseHistory = if (savedPhaseDeadline != null) requireNotNull(phase).parse(data["irq117"], savedTicks, savedPhaseDeadline) else emptyList()
            if (!savedPhase && phase != null) require(phase.deadline > context.clock.ticks()) { "Pending IRQ117 deadline must follow restore tick" }
            val savedSync = LcdcIrq118.SYNC in settingsMap
            if (savedSync) require(savedIrq && syncStart > 0 && data["frameSync"] is Map<*, *>)
            val savedClock = data["frameSync"] as? Map<*, *>
            val savedResume = LcdcIrq118.RESUME in settingsMap
            require(savedResume == (savedClock?.containsKey("resumeTick") == true))
            if (savedResume) require(savedSync && unsigned(settingsMap[LcdcIrq118.RESUME]) == resumeTick && resumeTick > 0)
            val savedSyncFollowups = if (LcdcIrq118.FOLLOWUPS in settingsMap) {
                require(savedSync)
                VglibSnapshotCodec.requireString(settingsMap[LcdcIrq118.FOLLOWUPS], LcdcIrq118.FOLLOWUPS)
            } else null
            require((savedSyncFollowups != null) == (savedClock?.containsKey("followupFiredAt") == true))
            val savedFrameSyncPeriod = if (LcdcIrq118.PERIOD_SETTING in settingsMap) {
                unsigned(settingsMap[LcdcIrq118.PERIOD_SETTING]).also { saved ->
                    if (saved > 0L) require(savedIrq) { "${LcdcIrq118.PERIOD_SETTING} in snapshot requires ${LcdcIrq118.SETTING}" }
                }
            } else 0L
            require(savedFrameSyncPeriod == frameSyncPeriodSetting) {
                "Saved ${LcdcIrq118.PERIOD_SETTING} ($savedFrameSyncPeriod) differs from live ($frameSyncPeriodSetting)"
            }
            val savedScanout = LcdcFirstScanout.SETTING in settingsMap
            val savedLimit = if (LcdcFirstScanout.LIMIT_SETTING in settingsMap) {
                require(savedScanout)
                VglibSnapshotCodec.requireInt(settingsMap[LcdcFirstScanout.LIMIT_SETTING], "saved scanoutFrameLimit",
                    2..LcdcFirstScanout.MAX_FRAMES).also { require(it == frameLimit) { "Saved LCD frame limit differs" } }
            } else 1
            val savedEvfScanout = LcdcFirstScanout.EVF_SETTING in settingsMap
            val savedEvfLimit = if (LcdcFirstScanout.EVF_LIMIT_SETTING in settingsMap) {
                require(savedEvfScanout)
                VglibSnapshotCodec.requireInt(settingsMap[LcdcFirstScanout.EVF_LIMIT_SETTING], "saved evfScanoutFrameLimit",
                    2..LcdcFirstScanout.MAX_FRAMES).also { require(it == evfFrameLimit) { "Saved EVF LCD frame limit differs" } }
            } else 1
            val savedFill = GraphicsFillTail.SETTING in settingsMap
            if(savedFill) require(savedProfile == FUNCTIONAL_PROFILE && fill != null && settingsMap[GraphicsFillTail.SETTING] == GraphicsFillTail.PROFILE)
            val savedFillIrq = GraphicsFillIrq92.SETTING in settingsMap
            val savedCopyIrq = GraphicsCopyIrq92.SETTING in settingsMap
            val savedSuccessors = GraphicsIrq92Sequence.SETTING in settingsMap
            if (savedSuccessors) require(savedCopyIrq && successors != null && data["irq92Successors"] is List<*>)
            if (savedCopyIrq) require(savedFillIrq && copyIrq != null && data["copyIrq92"] is Map<*, *>)
            if (savedFillIrq) require(savedFill && fillIrq != null && data["fillIrq92"] is Map<*, *>)
            require(data.keys == expectedKeys + listOfNotNull("irq92Successors".takeIf { savedSuccessors }, "irq117".takeIf { savedPhase }, "frameSync".takeIf { savedSync }, "copyIrq92".takeIf { savedCopyIrq }, "fillIrq92".takeIf { savedFillIrq }, "graphicsFill".takeIf { savedFill }, "irq118".takeIf { savedIrq }, "scanout".takeIf { savedScanout }, "evfScanout".takeIf { savedEvfScanout })) { "Unexpected keys in soc-vglib-init snapshot" }
            val savedScanoutProfile = if (savedScanout) {
                VglibSnapshotCodec.requireString(settingsMap[LcdcFirstScanout.SETTING], "saved firstScanout")
            } else null
            val cursor = if (savedScanout) {
                val selection = requireNotNull(savedScanoutProfile)
                require(savedProfile == FUNCTIONAL_PROFILE && scanout != null &&
                    (selection == scanout.profile ||
                        selection == LcdcFirstScanout.PROFILE && scanout.profile in listOf(LcdcFirstScanout.RGBA, LcdcFirstScanout.PIC_YUV_OSD0) ||
                        selection == LcdcFirstScanout.RGBA && scanout.profile == LcdcFirstScanout.PIC_YUV_OSD0))
                scanout.parse(data["scanout"], savedLimit, selection)
            } else LcdcFirstScanout.Cursor()
            val savedEvfScanoutProfile = if (savedEvfScanout) {
                VglibSnapshotCodec.requireString(settingsMap[LcdcFirstScanout.EVF_SETTING], "saved evfScanout")
            } else null
            val evfCursor = if (savedEvfScanout) {
                val selection = requireNotNull(savedEvfScanoutProfile)
                require(savedProfile == FUNCTIONAL_PROFILE && evfScanout != null &&
                    (selection == evfScanout.profile ||
                        selection == LcdcFirstScanout.PROFILE && evfScanout.profile in listOf(LcdcFirstScanout.RGBA, LcdcFirstScanout.PIC_YUV_OSD0) ||
                        selection == LcdcFirstScanout.RGBA && evfScanout.profile == LcdcFirstScanout.PIC_YUV_OSD0))
                evfScanout.parse(data["evfScanout"], savedEvfLimit, selection)
            } else LcdcFirstScanout.Cursor()
            if (savedIrq || irq != null) {
                require(savedProfile == FUNCTIONAL_PROFILE && irq != null && data["lcdcFunctional"] != null) { "IRQ118 requires saved functional banks" }
                if (savedIrq) require(unsigned(settingsMap[LcdcIrq118.SETTING]) == irq.deadline) { "IRQ118 setting differs" }
            }
            val firedAt = if (savedIrq) checkNotNull(irq).parseFiredAt(data["irq118"]) else null
            val syncHistory = irq?.parseSync(data["frameSync"], firedAt, savedSyncFollowups)
            if (irq != null && firedAt == null) require(irq.deadline > context.clock.ticks()) { "Pending IRQ118 deadline must follow restore tick" }
            val liveProfile = VglibSnapshotCodec.requireString(values["profile"], "live.profile")
            val savedActive = VglibSnapshotCodec.requireBoolean(data["active"], "active")
            require(savedActive == (savedProfile != "disabled")) { "Saved active flag disagrees with saved profile" }
            require(savedActive == active) { "Cannot restore between disabled and active soc-vglib-init profiles" }

            val optionalKeys = setOf("controlWordInputFile", "lcdcSnapshotInputFile")
            val absentDefaultOptionalKeys = LinkedHashSet<String>()
            for (optionalKey in optionalKeys) {
                val savedValue = settingsMap[optionalKey]?.let { rawVal -> VglibSnapshotCodec.requireString(rawVal, optionalKey) } ?: ""
                val liveValue = (values[optionalKey] as? String) ?: ""
                if (optionalKey != "lcdcSnapshotInputFile" || savedProfile == liveProfile) {
                    require(savedValue == liveValue) { "Restart-only setting $optionalKey differs in snapshot" }
                }
                if (optionalKey !in values && optionalKey in settingsMap && savedValue == "") {
                    absentDefaultOptionalKeys.add(optionalKey)
                }
            }

            val rawMode = data["modeWord"]
            val rawSnapshot = data["lcdcSnapshot"]
            val rawFunctional = data["lcdcFunctional"]

            val continuousUpgrade = continuousScanout && (settingsMap["continuousScanout"] as? Boolean != true)
            val clockGateCompatible = buildSet {
                if ("lcdc0ClockGated" in settingsMap && "lcdc0ClockGated" !in values && settingsMap["lcdc0ClockGated"] == false) {
                    add("lcdc0ClockGated")
                }
                if ("lcdc1ClockGated" in settingsMap && "lcdc1ClockGated" !in values && settingsMap["lcdc1ClockGated"] == false) {
                    add("lcdc1ClockGated")
                }
                if ("unclockedAccessPolicy" in settingsMap && "unclockedAccessPolicy" !in values && settingsMap["unclockedAccessPolicy"] == LcdcFunctional.POLICY_WARN) {
                    add("unclockedAccessPolicy")
                }
                if (LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING in settingsMap && LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING !in values && settingsMap[LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING] == false) {
                    add(LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING)
                }
                if (strictActiveOutputGate && settingsMap[LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING] as? Boolean != true) {
                    add(LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING)
                }
                if (LcdcIrq118.PERIOD_SETTING in settingsMap && LcdcIrq118.PERIOD_SETTING !in values && savedFrameSyncPeriod == 0L) {
                    add(LcdcIrq118.PERIOD_SETTING)
                }
            }
            val compatibleKeys = (when {
                savedProfile == FUNCTIONAL_PROFILE && functional.isNotEmpty() && rawFunctional != null -> {
                    require(rawSnapshot == null && snapshot == null) { "Functional LCD profile conflicts with snapshot input" }
                    setOf("profile") + absentDefaultOptionalKeys
                }
                savedProfile == PROFILE && rawSnapshot == null && rawFunctional == null && liveProfile == FUNCTIONAL_PROFILE -> {
                    require(((values["lcdcSnapshotInputFile"] as? String) ?: "").isEmpty()) {
                        "Functional LCD upgrade cannot carry lcdcSnapshotInputFile"
                    }
                    setOf("profile", "lcdcSnapshotInputFile") + absentDefaultOptionalKeys
                }
                savedProfile == liveProfile -> {
                    require((rawFunctional != null) == functional.isNotEmpty()) { "Functional LCD bank presence mismatch" }
                    require((rawSnapshot != null) == (snapshot != null)) { "LCD snapshot input presence mismatch" }
                    absentDefaultOptionalKeys
                }
                else -> throw IllegalArgumentException("Incompatible soc-vglib-init profile transition: $savedProfile -> $liveProfile")
            }) + (if (continuousUpgrade) setOf("continuousScanout") else emptySet()) + clockGateCompatible
            require((rawMode != null) == (mode != null)) { "ModeWord input presence mismatch" }

            val parsedSequence = VglibSnapshotCodec.parseSequence(data["sequence"], active, offsets, reads, writes)
            val parsedFill = if(savedFill) checkNotNull(fill).parse(data["graphicsFill"],parsedSequence.position) else null
            val savedFillDeadline = if (savedFillIrq) unsigned(settingsMap[GraphicsFillIrq92.SETTING]) else null
            val parsedFired = fillIrq?.parse(data["fillIrq92"], parsedFill, savedFillDeadline)
            val firstCleared = (data["fillIrq92"] as? Map<*, *>)?.get("cleared") == true
            val firstPostponed = savedFillDeadline != null && savedFillDeadline != fillDeadline
            val savedCopyDeadline = if (savedCopyIrq) unsigned(settingsMap[GraphicsCopyIrq92.SETTING]) else null
            if (savedCopyDeadline != null) require(savedCopyDeadline > requireNotNull(savedFillDeadline))
            val parsedCopyIrq = copyIrq?.parse(data["copyIrq92"], parsedFill, firstCleared, savedCopyDeadline, firstPostponed)
            val parsedSuccessors = successors?.parse(data["irq92Successors"],
                if (savedSuccessors) VglibSnapshotCodec.requireString(settingsMap[GraphicsIrq92Sequence.SETTING], "IRQ92 sequence") else null,
                requireNotNull(parsedFill), requireNotNull(parsedCopyIrq))
            parsedFill?.commandHistories?.filter { it.isNotEmpty() }?.forEach { history ->
                val fired = history.first()
                require(parsedCopyIrq?.reads?.lastOrNull()?.let { it <= fired } == true)
                val callbackEnd = history.getOrNull(6)
                require(parsedSuccessors != null && parsedSuccessors.all { event ->
                    event.reads.getOrNull(1)?.let { it <= fired } == true || callbackEnd != null && event.armed >= callbackEnd
                })
            }
            val parsedMode = mode?.parseSnapshotState(rawMode)
            val parsedSnapshot = snapshot?.parseSnapshotState(rawSnapshot)
            val parsedFunctional = if (rawFunctional == null) {
                null
            } else {
                val bankList = rawFunctional as? List<*> ?: throw IllegalArgumentException("Invalid lcdcFunctional list")
                require(bankList.size == 2 && functional.size == 2) { "Functional LCD controller requires exactly 2 banks" }
                functional.mapIndexed { bankIndex, bank -> bank.parseSnapshotState(bankList[bankIndex]) }
            }

            if (savedScanout) requireNotNull(scanout).checkBank(cursor, requireNotNull(parsedFunctional)[1], requireNotNull(savedScanoutProfile))
            if (savedEvfScanout) requireNotNull(evfScanout).checkBank(evfCursor, requireNotNull(parsedFunctional)[0], requireNotNull(savedEvfScanoutProfile))
            val appendable = listOfNotNull(GraphicsIrq92Sequence.SETTING.takeIf { savedSuccessors }, LcdcIrq117.FOLLOWUPS.takeIf { savedTicks != null }, LcdcIrq118.FOLLOWUPS.takeIf { savedSyncFollowups != null })
            val scanoutKeys = buildSet {
                if (savedScanout) add(LcdcFirstScanout.SETTING)
                if (savedEvfScanout) add(LcdcFirstScanout.EVF_SETTING)
            }
            val postponed = buildSet {
                if (savedPhaseDeadline != null && savedPhaseDeadline != phaseDeadline) add(LcdcIrq117.SETTING)
                if (firstPostponed) add(GraphicsFillIrq92.SETTING)
                if (savedCopyDeadline != null && savedCopyDeadline != copyIrq?.deadline) add(GraphicsCopyIrq92.SETTING)
            }
            restoreScalarValues(settingsMap, DESCRIPTOR, compatibleKeys + appendable + scanoutKeys + postponed)
            fill?.restore(parsedFill)
            fillIrq?.restore(parsedFired, firstCleared)
            parsedCopyIrq?.let { copyIrq!!.restore(it) }
            parsedSuccessors?.let { successors!!.restore(it) }
            scanout?.restore(cursor)
            evfScanout?.restore(evfCursor)
            irq?.restore(firedAt)
            phase?.restore(phaseHistory)
            if (syncHistory != null) irq.restoreSync(syncHistory)
            lcdc0Irqs?.resetSchedule(context.clock.ticks())
            position = parsedSequence.position
            parsedSequence.logValues.copyInto(logValues)
            parsedSequence.logTicks.copyInto(logTicks)
            parsedSequence.logCores.copyInto(logCores)
            parsedSequence.logPcs.copyInto(logPcs)
            if (mode != null && parsedMode != null) {
                mode.applySnapshotState(parsedMode)
            }
            if (snapshot != null && parsedSnapshot != null) {
                snapshot.applySnapshotState(parsedSnapshot)
            }
            if (functional.isNotEmpty()) {
                if (parsedFunctional != null) {
                    functional.forEachIndexed { bankIndex, bank -> bank.applySnapshotState(parsedFunctional[bankIndex]) }
                } else {
                    functional.forEach { bank -> bank.resetState() }
                }
            }
        }
    }
}

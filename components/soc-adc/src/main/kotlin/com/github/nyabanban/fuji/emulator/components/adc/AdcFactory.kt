package com.github.nyabanban.fuji.emulator.components.adc

import com.github.nyabanban.fuji.emulator.api.*
import java.util.ArrayDeque
import java.util.Properties

/** FW-017/SRC-013: bounded static 12-bit samples and explicit functional conversion ticks; no analog/IRQ claim. */
class AdcFactory : ComponentFactory {
    companion object {
        private val MODE_DIAL_SAMPLES = mapOf(
            "M" to 345,
            "C1" to 792,
            "C2" to 1194,
            "C3" to 1595,
            "C4" to 1997,
            "C5" to 2402,
            "C6" to 2815,
            "P" to 3237,
            "S" to 3672,
            "A" to 3994,
        )
        // DAT (section4.bin 0x01813004, bank14 0x3809=0x0C): 2-bit Gray state = (ch7 << 1) | ch11
        // The decoder at 0x0123FEF8 indexes [current][previous]. With bank14 bit 3 set,
        // transitions with value 1 emit KEY_CMDL_RIGHT (0xA6).
        private val REAR_DIAL_CW_CYCLE = intArrayOf(0, 1, 3, 2)
        private const val REAR_DIAL_LOW_SAMPLE = 500
        private const val REAR_DIAL_HIGH_SAMPLE = 3500
        private const val REAR_DIAL_MIN_HOLD_PAIRS = 3
        private val LEGACY_SCALAR_KEYS = setOf("profile", "inputFile", "menuButtonProfile", "directionButtonProfile")
        private val DESCRIPTOR=ComponentDescriptor("soc-adc","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,"disabled",choices=listOf("disabled","adc-source-functional-experimental-v1"),live=false),
            Setting("inputFile",ValueKind.TEXT,"",live=false),
            Setting("menuButtonProfile",ValueKind.TEXT,"disabled",choices=listOf("disabled","gfx100s-2.13-menu-adc-v1"),live=false),
            Setting("directionButtonProfile",ValueKind.TEXT,"disabled",choices=listOf("disabled","gfx100s-2.13-direction-input-v1"),live=false),
            Setting("qButton",ValueKind.BOOLEAN,false,live=true,description="Live Q Quick Menu button on ADC CH3 (DAT [3103..3776], mid 3440)"),
            Setting("aelButton",ValueKind.BOOLEAN,false,live=true,description="Live AEL button on ADC CH3 (DAT [2117..2344], mid 2230)"),
            Setting("modeDial",ValueKind.TEXT,"default",choices=listOf("default","M","C1","C2","C3","C4","C5","C6","P","S","A"),live=true,description="Live Mode Dial position on ADC CH5 (DAT bank14 table; 'default' uses inputFile)"),
            Setting("fn1Button",ValueKind.BOOLEAN,false,live=true,description="Live Fn1 button on ADC CH6 (DAT [3103..3776], mid 3440)"),
            Setting("fn2Button",ValueKind.BOOLEAN,false,live=true,description="Live Fn2 button on ADC CH6 (DAT [2117..2344], mid 2230)"),
            Setting("fn3Button",ValueKind.BOOLEAN,false,live=true,description="Live Fn3 button on ADC CH6 (DAT [2637..3102], mid 2870)"),
            Setting("lightButton",ValueKind.BOOLEAN,false,live=true,description="Live Sub-LCD LIGHT button on ADC CH6 (DAT [0..782], mid 391)"),
            Setting("rearDialPosition",ValueKind.INTEGER,-1L,minimum=-1.0,maximum=3.0,live=true,description="Live Rear Command Dial 2-bit Gray state on CH7/CH11 (-1=default, 0..3)"),
            Setting("rearDialDetents",ValueKind.INTEGER,0L,minimum=-10000.0,maximum=10000.0,live=true,description="Cumulative Rear Command Dial detent steps (+CW / -CCW; auto-steps CH7/CH11 Gray quadrature)"),
            Setting("ch3Override",ValueKind.INTEGER,-1L,minimum=-1.0,maximum=4095.0,live=true,description="Raw 12-bit live override for ADC CH3 (-1=disabled)"),
            Setting("ch5Override",ValueKind.INTEGER,-1L,minimum=-1.0,maximum=4095.0,live=true,description="Raw 12-bit live override for ADC CH5 (-1=disabled)"),
            Setting("ch6Override",ValueKind.INTEGER,-1L,minimum=-1.0,maximum=4095.0,live=true,description="Raw 12-bit live override for ADC CH6 (-1=disabled)"),
            Setting("ch7Override",ValueKind.INTEGER,-1L,minimum=-1.0,maximum=4095.0,live=true,description="Raw 12-bit live override for ADC CH7 (-1=disabled)"),
            Setting("ch11Override",ValueKind.INTEGER,-1L,minimum=-1.0,maximum=4095.0,live=true,description="Raw 12-bit live override for ADC CH11 (-1=disabled)")))
        private fun normalizeConfig(config: Map<String, Any>): Map<String, Any> {
            val defaults = DESCRIPTOR.settings.associate { it.key to it.default }
            return (defaults + config).mapValues { (key, value) ->
                DESCRIPTOR.settings.firstOrNull { it.key == key }?.validate(value) ?: value
            }
        }
        private fun snapshotLong(value: Any?,field: String,min: Long,max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
            val number=(value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $field")
            require(number in min..max) { "Out-of-range integer for $field" }
            return number
        }
        private fun snapshotInt(value: Any?,field: String,min: Int,max: Int): Int =
            snapshotLong(value,field,min.toLong(),max.toLong()).toInt()
    }
    override val descriptor=DESCRIPTOR
    override fun create(context: ComponentContext,config: Map<String,Any>): HardwareComponent=Adc(context,normalizeConfig(config))
    private class Adc(context: ComponentContext,config: Map<String,Any>) : ScalarComponent("soc-adc",context,config),StatefulComponent,TickAwareMmioDevice,NativeMmioMirrorPort {
        override val clockOrder=4 // Explicit HYP: timer0/SPI3 precede ADC, flash5/DMA10 follow it.
        private val enabled=config.getValue("profile")!="disabled"
        private var closed=false
        private val menuProfile=config.getValue("menuButtonProfile") as String
        private val menuChannel=when(menuProfile) {
            "disabled"->null
            "gfx100s-2.13-menu-adc-v1"->{
                val data=Properties()
                checkNotNull(javaClass.getResourceAsStream("/META-INF/fuji/menu-adc.properties")) { "MENU ADC binding resource missing" }.use(data::load)
                require(data.stringPropertyNames()==setOf("profile","menu") && data.getProperty("profile")==menuProfile)
                data.getProperty("menu").toInt().also {require(it in 0..11)}
            }
            else->error("unsupported MENU ADC profile")
        }
        private var menuSample: Int?=null
        private data class Direction(val name:String,val mask:Int,val minimum:Int,val maximum:Int)
        private val directionProfile=config.getValue("directionButtonProfile") as String
        private val directionNames=listOf("up","upRight","right","downRight","down","downLeft","left","upLeft")
        private val directionMasks=listOf(1,3,2,6,4,12,8,9)
        private var directionChannel: Int?=null
        private var directionKeys: List<String> = emptyList()
        private val directions: List<Direction> = when(directionProfile) {
            "disabled"->emptyList()
            "gfx100s-2.13-direction-input-v1"->{
                val data=Properties()
                checkNotNull(javaClass.getResourceAsStream("/META-INF/fuji/direction-input.properties")) { "Direction input binding resource missing" }.use(data::load)
                require(data.stringPropertyNames()==directionNames.toSet()+setOf("profile","adcChannel","gpioBank","gpioPin","gpioActiveLow","buttonKeys") && data.getProperty("profile")==directionProfile)
                directionChannel=data.getProperty("adcChannel").toInt().also { require(it in 0..11 && it!=menuChannel) }
                require(data.getProperty("gpioBank").toInt() in 0..15 && data.getProperty("gpioPin").toInt() in 0..15 && data.getProperty("gpioActiveLow") in setOf("true","false"))
                directionKeys=data.getProperty("buttonKeys").split(',').also { require(it==listOf("up","right","down","left")) }
                directionNames.mapIndexed { index,name ->
                    val fields=data.getProperty(name).split(',');require(fields.size==3)
                    Direction(name,fields[0].toInt(),fields[1].toInt(),fields[2].toInt()).also {
                        require(it.mask==directionMasks[index] && it.minimum in 0..4095 && it.maximum in it.minimum..4095)
                    }
                }.also { rows -> rows.sortedBy { it.minimum }.zipWithNext().forEach { (left,right)->require(left.maximum<right.minimum) } }
            }
            else->error("unsupported direction input profile")
        }
        private val directionSamples=IntArray(16) { -1 }
        private var buttons: ButtonPort?=null
        private val inputs=IntArray(12) { -1 }
        private var sampleTicks=0L;private var word44: Int?=null
        private var specification: Map<String,Any?> = emptyMap()
        private data class Registers(val control: Long=0,val divider: Long=0,val gate: Long=0,val status: Long=0,val trim: Long=0,
            val samples: IntArray=IntArray(12),val deadline: Long?=null,val lastEdge: Long?=null)
        private var s=Registers()
        private var mirror: MmioMirrorHandle? = null
        override fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec> =
            if (!enabled) emptyList() else listOf(
                MmioMirrorWindowSpec(
                    portName = id,
                    windowSize = 0x48L,
                    slots = buildList(18) {
                        add(MmioMirrorSlotSpec(0x00L, 4)) // control
                        add(MmioMirrorSlotSpec(0x04L, 4)) // divider
                        add(MmioMirrorSlotSpec(0x08L, 4)) // gate
                        add(MmioMirrorSlotSpec(0x0CL, 4)) // status
                        for (ch in 0..11) add(MmioMirrorSlotSpec(0x10L + (ch shl 2).toLong(), 4))
                        add(MmioMirrorSlotSpec(0x40L, 4)) // trim
                        add(MmioMirrorSlotSpec(0x44L, 4)) // word44 (valid iff non-null)
                    },
                ),
            )
        override fun attachMmioMirror(portName: String, handle: MmioMirrorHandle) {
            if (!enabled || portName != id) return
            mirror = handle
            syncMirror()
        }
        override fun detachMmioMirrors() {
            mirror?.invalidateAll()
            mirror = null
        }
        private fun syncMirror() {
            val h = mirror ?: return
            h.writeSlotRaw(0, s.control.toInt())
            h.writeSlotRaw(1, s.divider.toInt())
            h.writeSlotRaw(2, s.gate.toInt())
            h.writeSlotRaw(3, s.status.toInt())
            val smp = s.samples
            for (ch in 0..11) h.writeSlotRaw(4 + ch, smp[ch])
            h.writeSlotRaw(16, s.trim.toInt())
            val w44 = word44
            if (w44 != null) {
                h.writeSlotRaw(17, w44)
                h.setValidMask(h.allSlotsMask)
            } else {
                h.setValidMask(h.allSlotsMask and (1L shl 17).inv())
            }
        }
        override fun nextDeadline():Long?=s.deadline
        override fun nextInterestedTick():Long=s.deadline ?: Long.MAX_VALUE
        private class AdcReadRing {
            private val cap = 256
            private val ticks = LongArray(cap)
            private val channels = IntArray(cap)
            private val values = IntArray(cap)
            private var head = 0
            var size = 0
                private set
            fun clear() { head = 0; size = 0 }
            fun add(tick: Long, channel: Int, value: Int) {
                val slot = if (size < cap) {
                    val s = (head + size) and 255
                    size++
                    s
                } else {
                    val s = head
                    head = (head + 1) and 255
                    s
                }
                ticks[slot] = tick; channels[slot] = channel; values[slot] = value
            }
            fun toList(): List<Map<String, Any>> {
                val out = ArrayList<Map<String, Any>>(size)
                for (i in 0 until size) {
                    val s = (head + i) and 255
                    out.add(linkedMapOf("tick" to ticks[s], "channel" to channels[s], "value" to values[s]))
                }
                return out
            }
            fun restore(entries: List<Map<String, Any>>) {
                clear()
                for (e in entries) {
                    add(
                        (e.getValue("tick") as Number).toLong(),
                        (e.getValue("channel") as Number).toInt(),
                        (e.getValue("value") as Number).toInt(),
                    )
                }
            }
        }
        private class AdcEventRing {
            private val cap = 256
            private val ticks = LongArray(cap)
            private val masks = IntArray(cap)
            private val samples = Array(cap) { IntArray(12) }
            private val hasObserved = BooleanArray(cap)
            private val observedTicks = LongArray(cap)
            private var head = 0
            var size = 0
                private set
            fun clear() { head = 0; size = 0 }
            fun add(tick: Long, mask: Int, srcSamples: IntArray, hasObs: Boolean, obsTick: Long) {
                val slot = if (size < cap) {
                    val s = (head + size) and 255
                    size++
                    s
                } else {
                    val s = head
                    head = (head + 1) and 255
                    s
                }
                ticks[slot] = tick; masks[slot] = mask
                srcSamples.copyInto(samples[slot])
                hasObserved[slot] = hasObs; observedTicks[slot] = obsTick
            }
            fun toList(): List<Map<String, Any>> {
                val out = ArrayList<Map<String, Any>>(size)
                for (i in 0 until size) {
                    val s = (head + i) and 255
                    val m = masks[s]
                    val chList = ArrayList<Int>(Integer.bitCount(m))
                    val smpList = ArrayList<Int>(Integer.bitCount(m))
                    val arr = samples[s]
                    for (ch in 0..11) {
                        if ((m and (1 shl ch)) != 0) {
                            chList.add(ch)
                            smpList.add(arr[ch])
                        }
                    }
                    val entry = linkedMapOf<String, Any>(
                        "tick" to ticks[s],
                        "mask" to m,
                        "channels" to chList,
                        "samples" to smpList,
                    )
                    if (hasObserved[s]) entry["sampleObservedTick"] = observedTicks[s]
                    out.add(entry)
                }
                return out
            }
            @Suppress("UNCHECKED_CAST")
            fun restore(entries: List<Map<String, Any>>) {
                clear()
                val tmp = IntArray(12)
                for (e in entries) {
                    tmp.fill(0)
                    val chs = e.getValue("channels") as List<Int>
                    val smps = e.getValue("samples") as List<Int>
                    for (j in chs.indices) tmp[chs[j]] = smps[j]
                    val obs = e["sampleObservedTick"] as? Number
                    add(
                        (e.getValue("tick") as Number).toLong(),
                        (e.getValue("mask") as Number).toInt(),
                        tmp,
                        obs != null,
                        obs?.toLong() ?: 0L,
                    )
                }
            }
        }
        private val events = AdcEventRing(); private var eventCount = 0L
        private val reads = AdcReadRing(); private var readCount = 0L
        init {
            val file=config.getValue("inputFile") as String
            require(enabled==file.isNotBlank()) { "ADC profile/inputFile selection mismatch" }
            require(enabled || menuChannel==null) { "MENU input requires an enabled ADC" }
            require(enabled || directionChannel==null) { "Direction input requires an enabled ADC" }
            if(enabled) {
                val data=context.readData(file)
                require(data.keys==setOf("profile","sample_ticks","inputs","word_44")+(if(menuChannel==null)emptySet()else setOf("menu_pressed_sample"))+(if(directionChannel==null)emptySet()else setOf("direction_pressed_samples")) && data["profile"]=="adc-source-functional-experimental-v1") { "ADC requires complete explicit input specification" }
                fun integer(value: Any?,max: Int): Int { require(value is Byte || value is Short || value is Int || value is Long);val n=(value as Number).toLong();require(n in 0..max.toLong());return n.toInt() }
                sampleTicks=integer(data["sample_ticks"],1024).toLong();require(sampleTicks>=1)
                val words=data["inputs"];require(words is Map<*,*>)
                words.forEach { (key,value)->require(key is String && key.matches(Regex("0|[1-9][0-9]?")));val channel=key.toInt();require(channel<12);inputs[channel]=integer(value,4095) }
                word44=data["word_44"]?.let { integer(it,511) }
                if(menuChannel!=null) {
                    require(inputs[menuChannel]>=0) { "MENU input requires an explicit released channel sample" }
                    menuSample=integer(data["menu_pressed_sample"],4095)
                }
                directionChannel?.let { channel ->
                    require(inputs[channel]>=0 && directions.none { inputs[channel] in it.minimum..it.maximum }) { "Direction input requires an explicit released sample outside all direction intervals" }
                    val pressed=data["direction_pressed_samples"]
                    require(pressed is Map<*,*> && pressed.keys==directionNames.toSet()) { "Direction input requires all eight explicit pressed samples" }
                    for(direction in directions) {
                        val sample=integer(pressed[direction.name],4095)
                        require(sample in direction.minimum..direction.maximum) { "Direction sample outside its declared SOURCE interval: ${direction.name}" }
                        directionSamples[direction.mask]=sample
                    }
                }
                specification=mapOf("profile" to "adc-source-functional-experimental-v1","sample_ticks" to sampleTicks,
                    "inputs" to (0..11).filter { inputs[it]>=0 }.associate { it.toString() to inputs[it] },"word_44" to word44)+(menuSample?.let {mapOf("menu_pressed_sample" to it)}?:emptyMap())+
                    (if(directionChannel==null)emptyMap()else mapOf("direction_pressed_samples" to directions.associate { it.name to directionSamples[it.mask] }))
                context.ports.register(id,id,MmioDevice::class.java,this)
            }
        }
        override fun start() {
            check(!closed) { "soc-adc closed" }
            if(menuChannel!=null || directionChannel!=null)buttons=checkNotNull(context.ports.find("buttons",ButtonPort::class.java)) { if(directionChannel!=null)"Direction ADC profile requires buttons port" else "MENU ADC profile requires buttons port" }
        }
        override fun close() { closed=true; detachMmioMirrors(); buttons=null }
        private var rearDialCycleIdx = 0
        private var rearDialPendingSteps = 0
        private var rearDialActive = false
        private var rearDialSeenChMask = 0
        private var rearDialHoldPairs = 0
        private var lastRearDialDetents = (config.getValue("rearDialDetents") as Number).toLong()
        init {
            val initialPos = (config.getValue("rearDialPosition") as Number).toInt()
            if (initialPos in 0..3) {
                rearDialCycleIdx = grayIndexOf(initialPos)
                rearDialActive = true
            }
        }
        private fun grayIndexOf(pos: Int): Int = REAR_DIAL_CW_CYCLE.indexOf(pos).takeIf { it >= 0 } ?: 0
        private fun syncRearDialFromConfig(previousDetents: Long) {
            val pos = (values["rearDialPosition"] as? Number)?.toInt() ?: -1
            val detents = (values["rearDialDetents"] as? Number)?.toLong() ?: 0L
            if (pos in 0..3) {
                rearDialCycleIdx = grayIndexOf(pos)
                rearDialActive = true
                rearDialPendingSteps = 0
                rearDialSeenChMask = 0
                rearDialHoldPairs = 0
            } else if (detents != previousDetents) {
                val delta = (detents - previousDetents).toInt()
                if (!rearDialActive) {
                    // Derive initial Gray state from static input.json CH7/CH11 so first edge is glitch-free
                    val b7 = if (inputs[7] > 2048) 1 else 0
                    val b11 = if (inputs[11] > 2048) 1 else 0
                    rearDialCycleIdx = grayIndexOf((b7 shl 1) or b11)
                    rearDialActive = true
                }
                if (rearDialPendingSteps == 0) {
                    rearDialHoldPairs = REAR_DIAL_MIN_HOLD_PAIRS
                    rearDialSeenChMask = 0
                }
                rearDialPendingSteps += delta
            }
            lastRearDialDetents = detents
        }
        private fun advanceRearDialIfDue() {
            if (rearDialPendingSteps == 0) return
            if (rearDialHoldPairs >= REAR_DIAL_MIN_HOLD_PAIRS) {
                val step = if (rearDialPendingSteps > 0) 1 else -1
                rearDialCycleIdx = (rearDialCycleIdx + step + 4) and 3
                rearDialPendingSteps -= step
                rearDialHoldPairs = 0
                rearDialSeenChMask = 0
            }
        }
        private fun recordRearDialObservation(mask: Int) {
            if (!rearDialActive) return
            if ((mask and (1 shl 7)) != 0) rearDialSeenChMask = rearDialSeenChMask or 1
            if ((mask and (1 shl 11)) != 0) rearDialSeenChMask = rearDialSeenChMask or 2
            if (rearDialSeenChMask == 3) {
                rearDialHoldPairs++
                rearDialSeenChMask = 0
            }
        }
        private fun hasActiveLiveAdcControl(mask: Int): Boolean {
            if ((mask and (1 shl 3)) != 0 && (values["qButton"] == true || values["aelButton"] == true || ((values["ch3Override"] as? Number)?.toInt() ?: -1) >= 0)) return true
            if ((mask and (1 shl 5)) != 0 && (values["modeDial"] != "default" || ((values["ch5Override"] as? Number)?.toInt() ?: -1) >= 0)) return true
            if ((mask and (1 shl 6)) != 0 && (values["fn1Button"] == true || values["fn2Button"] == true || values["fn3Button"] == true || values["lightButton"] == true || ((values["ch6Override"] as? Number)?.toInt() ?: -1) >= 0)) return true
            if ((mask and ((1 shl 7) or (1 shl 11))) != 0 && (rearDialActive || ((values["ch7Override"] as? Number)?.toInt() ?: -1) >= 0 || ((values["ch11Override"] as? Number)?.toInt() ?: -1) >= 0)) return true
            return false
        }
        private fun sampleCh3(menuPressed: Boolean): Int {
            val raw = (values["ch3Override"] as? Number)?.toInt() ?: -1
            if (raw >= 0) return raw
            val q = values["qButton"] == true
            val ael = values["aelButton"] == true
            return when {
                q && menuPressed -> 2490
                q && ael -> 2005
                menuPressed && ael -> 1802
                q -> 3440
                menuPressed -> checkNotNull(menuSample)
                ael -> 2230
                else -> inputs[3]
            }
        }
        private fun sampleCh5(): Int {
            val raw = (values["ch5Override"] as? Number)?.toInt() ?: -1
            if (raw >= 0) return raw
            val mode = values["modeDial"] as? String ?: "default"
            return MODE_DIAL_SAMPLES[mode] ?: inputs[5]
        }
        private fun sampleCh6(): Int {
            val raw = (values["ch6Override"] as? Number)?.toInt() ?: -1
            if (raw >= 0) return raw
            val fn1 = values["fn1Button"] == true
            val fn2 = values["fn2Button"] == true
            val fn3 = values["fn3Button"] == true
            val light = values["lightButton"] == true
            return when {
                light -> 391
                fn1 && fn3 -> 2490
                fn1 && fn2 -> 2005
                fn2 && fn3 -> 1802
                fn1 -> 3440
                fn3 -> 2870
                fn2 -> 2230
                else -> inputs[6]
            }
        }
        private fun sampleRearDialChannel(ch: Int): Int {
            val rawKey = if (ch == 7) "ch7Override" else "ch11Override"
            val raw = (values[rawKey] as? Number)?.toInt() ?: -1
            if (raw >= 0) return raw
            if (!rearDialActive) return inputs[ch]
            val gray = REAR_DIAL_CW_CYCLE[rearDialCycleIdx]
            val bit = if (ch == 7) ((gray ushr 1) and 1) else (gray and 1)
            return if (bit != 0) REAR_DIAL_HIGH_SAMPLE else REAR_DIAL_LOW_SAMPLE
        }
        override fun validateSettings(settings: Map<String,Any>) {
            require("menuButtonProfile" !in settings || settings["menuButtonProfile"]==menuProfile) { "MENU ADC profile requires restart" }
            require("directionButtonProfile" !in settings || settings["directionButtonProfile"]==directionProfile) { "Direction ADC profile requires restart" }
        }
        override fun applySettings(settings: Map<String,Any>) {
            validateSettings(settings)
            val prevDetents = lastRearDialDetents
            super.applySettings(settings)
            syncRearDialFromConfig(prevDetents)
        }
        private fun arm(candidate: Registers,now: Long,restart: Boolean): Registers {
            require(now>=0) { "ADC tick out of range" }
            val state=if(restart) candidate.copy(deadline=null,lastEdge=null) else candidate
            val mask=(state.control ushr 16).toInt() and 4095
            if(state.control and 1==0L || state.gate==0L || mask==0 || state.deadline!=null) return state
            val period=sampleTicks*(state.divider+1)*Integer.bitCount(mask)
            val edge=state.lastEdge
            require(edge==null || edge<=now) { "ADC clock moved backwards" }
            val delay=if(edge==null) period else period-(now-edge)%period
            val deadline=try { Math.addExact(now,delay) } catch(error: ArithmeticException) { throw IllegalArgumentException("ADC deadline overflow",error) }
            return state.copy(deadline=deadline)
        }
        override fun beforeInstruction(tick: Long) {
            check(enabled);require(tick>=0)
            val at=s.deadline ?: return
            if(tick<at) return
            val mask=(s.control ushr 16).toInt() and 4095
            for (ch in 0..11) {
                if ((mask and (1 shl ch)) != 0 && inputs[ch] < 0) {
                    val missing = (0..11).filter { (mask and (1 shl it)) != 0 && inputs[it] < 0 }
                    require(missing.isEmpty()) { "ADC conversion requires explicit channel inputs $missing" }
                }
            }
            val mc = menuChannel
            val menuSelected = mc != null && (mask and (1 shl mc)) != 0
            val pressed = menuSelected && checkNotNull(buttons) { "MENU ADC profile has not started" }.pressed("menu")
            val dc = directionChannel
            val dirSelected = dc != null && (mask and (1 shl dc)) != 0
            val directionSample = if (dirSelected) {
                val port = checkNotNull(buttons) { "Direction ADC profile has not started" }
                var keyMask = 0
                for (index in directionKeys.indices) {
                    if (port.pressed(directionKeys[index])) keyMask = keyMask or (1 shl index)
                }
                require(keyMask == 0 || directionSamples[keyMask] >= 0) { "Conflicting direction buttons" }
                if (keyMask == 0) inputs[dc] else directionSamples[keyMask]
            } else 0
            val samples = s.samples
            if ((mask and ((1 shl 7) or (1 shl 11))) != 0) {
                advanceRearDialIfDue()
            }
            for (ch in 0..11) {
                if ((mask and (1 shl ch)) != 0) {
                    samples[ch] = when {
                        ch == 3 -> sampleCh3(ch == mc && pressed)
                        ch == 5 -> sampleCh5()
                        ch == 6 -> sampleCh6()
                        ch == 7 || ch == 11 -> sampleRearDialChannel(ch)
                        ch == mc && pressed -> checkNotNull(menuSample)
                        ch == dc -> directionSample
                        else -> inputs[ch]
                    }
                }
            }
            if ((mask and ((1 shl 7) or (1 shl 11))) != 0) {
                recordRearDialObservation(mask)
            }
            s = s.copy(samples = samples, status = 1, deadline = null, lastEdge = at, control = if (s.control and 2 != 0L) s.control else s.control and 1L.inv())
            events.add(at, mask, samples, menuSelected || dirSelected || hasActiveLiveAdcControl(mask), tick); eventCount++
            syncMirror()
        }
        private fun check(offset: Long,size: Int) { require(size==4 && offset in 0..0x44 && (offset and 3L) == 0L) { "ADC aligned word access required" } }
        override fun read(offset: Long,size: Int): Long {
            beforeInstruction(context.clock.ticks());check(offset,size)
            return when(offset) {
                0L->s.control;4L->s.divider;8L->s.gate;0xcL->s.status;0x40L->s.trim
                0x44L->checkNotNull(word44) { "ADC +44 raw read input is absent" }.toLong()
                else->{val channel=((offset-0x10) ushr 2).toInt();val value=s.samples[channel];reads.add(context.clock.ticks(),channel,value);readCount++;value.toLong()}
            }
        }
        override fun write(offset: Long,size: Int,value: Long) {
            beforeInstruction(context.clock.ticks());check(offset,size);require(value in 0..0xffffffffL)
            val now=context.clock.ticks()
            s=when(offset) {
                0L->{require(value and 0x0fff0003L.inv()==0L);if(value==s.control) s else arm(s.copy(control=value),now,true)}
                4L->{require(value<0x100000);arm(s.copy(divider=value),now,true)}
                8L->{require(value<=1);arm(s.copy(gate=value),now,true)}
                0xcL->{require(value<=1);val next=s.copy(status=s.status and value.inv());if(s.control and 2!=0L) arm(next,now,false) else next}
                0x40L->{require(value<=255);s.copy(trim=value)}
                else->error("unmodeled ADC register/value/write")
            }
            syncMirror()
        }
        override fun saveSnapshot(): Map<String,Any?> {
            check(!closed) { "soc-adc closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to enabled,
                "sampleTicks" to sampleTicks,
                "registers" to linkedMapOf(
                    "control" to s.control,
                    "divider" to s.divider,
                    "gate" to s.gate,
                    "status" to s.status,
                    "trim" to s.trim,
                    "samples" to s.samples.toList(),
                    "deadline" to s.deadline,
                    "lastEdge" to s.lastEdge,
                ),
                "eventCount" to eventCount,
                "events" to events.toList(),
                "sampleReadCount" to readCount,
                "sampleReads" to reads.toList(),
            )
        }
        override fun restoreSnapshot(data: Map<String,Any?>) {
            check(!closed) { "soc-adc closed" }
            val expectedKeys=setOf(
                "schema","componentId","scalars","enabled","sampleTicks",
                "registers","eventCount","events","sampleReadCount","sampleReads",
            )
            require(data.keys==expectedKeys) { "Unexpected or missing keys in soc-adc snapshot" }
            require(snapshotInt(data["schema"],"schema",1,1)==snapshotSchemaVersion) { "Unsupported soc-adc snapshot schema" }
            require(data["componentId"]==id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw=data["scalars"] as? Map<*,*> ?: throw IllegalArgumentException("Missing scalar settings map for $id")
            val fullScalarKeys=DESCRIPTOR.settings.map { setting -> setting.key }.toSet()
            require(scalarsRaw.keys==fullScalarKeys || scalarsRaw.keys==LEGACY_SCALAR_KEYS) { "Scalar setting keys differ for $id" }
            val normalizedScalars=LinkedHashMap<String,Any?>(DESCRIPTOR.settings.associate { it.key to it.default })
            for((k,v) in scalarsRaw) { normalizedScalars[k.toString()]=v }
            val savedProfile=scalarsRaw["profile"] as? String ?: throw IllegalArgumentException("Invalid profile in soc-adc snapshot")
            require(savedProfile in listOf("disabled","adc-source-functional-experimental-v1")) { "Unknown soc-adc profile in snapshot" }
            val savedMenuProfile=scalarsRaw["menuButtonProfile"] as? String ?: throw IllegalArgumentException("Invalid menuButtonProfile in snapshot")
            require(savedMenuProfile in listOf("disabled","gfx100s-2.13-menu-adc-v1")) { "Unknown menuButtonProfile in snapshot" }
            require(savedMenuProfile=="disabled" || savedMenuProfile==menuProfile) { "Incompatible menuButtonProfile in snapshot" }
            val savedDirectionProfile=scalarsRaw["directionButtonProfile"] as? String ?: throw IllegalArgumentException("Invalid directionButtonProfile in snapshot")
            require(savedDirectionProfile in listOf("disabled","gfx100s-2.13-direction-input-v1")) { "Unknown directionButtonProfile in snapshot" }
            require(savedDirectionProfile=="disabled" || savedDirectionProfile==directionProfile) { "Incompatible directionButtonProfile in snapshot" }
            val savedEnabled=data["enabled"] as? Boolean ?: throw IllegalArgumentException("Missing enabled boolean in soc-adc snapshot")
            require(savedEnabled==(savedProfile!="disabled")) { "soc-adc enabled flag disagrees with profile in snapshot" }
            val savedSampleTicks=snapshotLong(data["sampleTicks"],"sampleTicks",0L,1024L)
            val regMap=data["registers"] as? Map<*,*> ?: throw IllegalArgumentException("Missing registers map in soc-adc snapshot")
            require(regMap.keys==setOf("control","divider","gate","status","trim","samples","deadline","lastEdge")) {
                "Invalid registers keys in soc-adc snapshot"
            }
            val nextControl=snapshotLong(regMap["control"],"registers.control",0L,0x0fffffffL)
            require((nextControl and 0x0fff0003L.inv())==0L) { "Illegal reserved bits in restored ADC control register" }
            val nextDivider=snapshotLong(regMap["divider"],"registers.divider",0L,0xfffffL)
            val nextGate=snapshotLong(regMap["gate"],"registers.gate",0L,1L)
            val nextStatus=snapshotLong(regMap["status"],"registers.status",0L,1L)
            val nextTrim=snapshotLong(regMap["trim"],"registers.trim",0L,255L)
            val rawSamples=regMap["samples"] as? List<*> ?: throw IllegalArgumentException("Missing registers.samples list")
            require(rawSamples.size==12) { "ADC registers.samples requires 12 channel entries" }
            val nextSamples=IntArray(12) { channel -> snapshotInt(rawSamples[channel],"registers.samples[$channel]",0,4095) }
            val nextDeadline=regMap["deadline"]?.let { deadlineVal ->
                snapshotLong(deadlineVal,"registers.deadline",0L,Long.MAX_VALUE)
            }
            val nextLastEdge=regMap["lastEdge"]?.let { edgeVal ->
                snapshotLong(edgeVal,"registers.lastEdge",0L,Long.MAX_VALUE)
            }
            if(nextDeadline!=null) {
                val channelMask=(nextControl ushr 16).toInt() and 4095
                require((nextControl and 1L)!=0L && nextGate==1L && channelMask!=0) {
                    "Restored ADC deadline requires active start bit, gate, and channel mask"
                }
                require(nextLastEdge==null || nextLastEdge<=nextDeadline) { "Restored ADC lastEdge exceeds deadline" }
            }
            val nextEventCount=snapshotLong(data["eventCount"],"eventCount",0L,Long.MAX_VALUE)
            val nextEvents=parseConversionEvents(data["events"],nextEventCount)
            val nextReadCount=snapshotLong(data["sampleReadCount"],"sampleReadCount",0L,Long.MAX_VALUE)
            val nextReads=parseSampleReads(data["sampleReads"],nextReadCount)
            if(!savedEnabled) {
                require(
                    savedSampleTicks==0L && nextControl==0L && nextDivider==0L && nextGate==0L &&
                        nextStatus==0L && nextTrim==0L && nextSamples.all { sample -> sample==0 } &&
                        nextDeadline==null && nextLastEdge==null && nextEventCount==0L && nextReadCount==0L,
                ) { "Disabled soc-adc snapshot contains non-zero runtime state" }
                restoreScalarValues(
                    normalizedScalars,
                    DESCRIPTOR,
                    compatibleNonLiveKeys=LEGACY_SCALAR_KEYS,
                )
                rearDialActive=false;rearDialPendingSteps=0;rearDialSeenChMask=0;rearDialHoldPairs=0
                lastRearDialDetents=(values["rearDialDetents"] as? Number)?.toLong() ?: 0L
                s=Registers()
                eventCount=0L;events.clear()
                readCount=0L;reads.clear()
                syncMirror()
                return
            }
            require(enabled) { "Cannot restore enabled soc-adc snapshot into disabled component" }
            require(savedSampleTicks==sampleTicks) { "ADC sampleTicks differs in snapshot" }
            restoreScalarValues(
                normalizedScalars,
                DESCRIPTOR,
                compatibleNonLiveKeys=LEGACY_SCALAR_KEYS,
            )
            rearDialActive=false;rearDialPendingSteps=0;rearDialSeenChMask=0;rearDialHoldPairs=0
            lastRearDialDetents=(values["rearDialDetents"] as? Number)?.toLong() ?: 0L
            syncRearDialFromConfig(lastRearDialDetents)
            s=Registers(
                control=nextControl,divider=nextDivider,gate=nextGate,status=nextStatus,trim=nextTrim,
                samples=nextSamples,deadline=nextDeadline,lastEdge=nextLastEdge,
            )
            eventCount=nextEventCount;events.restore(nextEvents)
            readCount=nextReadCount;reads.restore(nextReads)
            syncMirror()
        }
        private fun parseConversionEvents(raw: Any?,totalCount: Long): List<Map<String,Any>> {
            val rows=raw as? List<*> ?: throw IllegalArgumentException("Missing soc-adc events list")
            val expectedSize=minOf(totalCount,256L).toInt()
            require(rows.size==expectedSize) { "soc-adc events size mismatches eventCount" }
            return rows.mapIndexed { eventIndex,item ->
                val row=item as? Map<*,*> ?: throw IllegalArgumentException("Invalid soc-adc event at $eventIndex")
                val baseKeys=setOf("tick","mask","channels","samples")
                require(row.keys==baseKeys || row.keys==baseKeys+"sampleObservedTick") {
                    "Invalid soc-adc event keys at $eventIndex"
                }
                val tick=snapshotLong(row["tick"],"events[$eventIndex].tick",0L,Long.MAX_VALUE)
                val mask=snapshotInt(row["mask"],"events[$eventIndex].mask",1,4095)
                val expectedChannels=(0..11).filter { channel -> mask and (1 shl channel)!=0 }
                val rawChannels=row["channels"] as? List<*> ?: throw IllegalArgumentException("Invalid event channels list")
                val channels=rawChannels.mapIndexed { channelPos,channelVal ->
                    snapshotInt(channelVal,"events[$eventIndex].channels[$channelPos]",0,11)
                }
                require(channels==expectedChannels) { "ADC event channels disagree with mask at $eventIndex" }
                val rawSamples=row["samples"] as? List<*> ?: throw IllegalArgumentException("Invalid event samples list")
                require(rawSamples.size==channels.size) { "ADC event samples count disagrees with channels at $eventIndex" }
                val samples=rawSamples.mapIndexed { samplePos,sampleVal ->
                    snapshotInt(sampleVal,"events[$eventIndex].samples[$samplePos]",0,4095)
                }
                val entry=linkedMapOf<String,Any>(
                    "tick" to tick,
                    "mask" to mask,
                    "channels" to channels,
                    "samples" to samples,
                )
                if("sampleObservedTick" in row.keys) {
                    entry["sampleObservedTick"]=snapshotLong(row["sampleObservedTick"],"events[$eventIndex].sampleObservedTick",tick,Long.MAX_VALUE)
                }
                entry
            }
        }
        private fun parseSampleReads(raw: Any?,totalCount: Long): List<Map<String,Any>> {
            val rows=raw as? List<*> ?: throw IllegalArgumentException("Missing soc-adc sampleReads list")
            val expectedSize=minOf(totalCount,256L).toInt()
            require(rows.size==expectedSize) { "soc-adc sampleReads size mismatches sampleReadCount" }
            return rows.mapIndexed { readIndex,item ->
                val row=item as? Map<*,*> ?: throw IllegalArgumentException("Invalid soc-adc sampleRead at $readIndex")
                require(row.keys==setOf("tick","channel","value")) { "Invalid soc-adc sampleRead keys at $readIndex" }
                linkedMapOf<String,Any>(
                    "tick" to snapshotLong(row["tick"],"sampleReads[$readIndex].tick",0L,Long.MAX_VALUE),
                    "channel" to snapshotInt(row["channel"],"sampleReads[$readIndex].channel",0,11),
                    "value" to snapshotInt(row["value"],"sampleReads[$readIndex].value",0,4095),
                )
            }
        }
        override fun state(): Map<String,Any?> = super.state()+mapOf("enabled" to enabled,"specification" to specification,
            "registers" to mapOf("control" to s.control,"divider" to s.divider,"gate" to s.gate,"status" to s.status,"trim" to s.trim,"samples" to s.samples.toList(),"deadline" to s.deadline,"lastEdge" to s.lastEdge),
            "events" to events.toList(),"eventCount" to eventCount,"eventLogTruncated" to (eventCount>events.size),"sampleReads" to reads.toList(),"sampleReadCount" to readCount,"sampleReadLogTruncated" to (readCount>reads.size),
            "basis" to ("Pinned ADC functional law; explicit zero register/result reset, "+(if(menuChannel==null)"static raw inputs" else "explicit HYP MENU samples at conversion processing")+", instruction ticks and clockOrder4. Missing selected samples retain pending/no partial result or done; optional +44 is unmeasured raw9bit, no voltage/IRQ/dynamic analog qualification"+(if(menuChannel==null)"" else "; SOURCE channel binding, result remains latched; physical sampling instant and original menu event unverified")+
                (if(directionChannel==null)"" else "; SOURCE direction channel/intervals with explicit HYP external samples; normal latched conversion, GPIO/scanner timing and original direction delivery remain unverified")))+
            (if(directionChannel==null)emptyMap()else mapOf("directionInputBinding" to mapOf("profile" to directionProfile,"channel" to directionChannel,"sampleConfidence" to "HYPOTHESIS","bindingConfidence" to "SOURCE")))
    }
}

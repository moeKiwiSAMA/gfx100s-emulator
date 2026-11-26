package com.github.nyabanban.fuji.emulator.components.timer

import com.github.nyabanban.fuji.emulator.api.*

/** Explicit functional expiry edges; default missing-route guard remains unchanged. */
class TimerFactory : ComponentFactory {
    companion object {
        const val ROUTED="interval-edge-routing-experimental-v1"
        val DESCRIPTOR=ComponentDescriptor("soc-timer","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,"strict",choices=listOf("strict",ROUTED),live=false),
            Setting("inputFile",ValueKind.TEXT,"",live=false),
            Setting("irqSink",ValueKind.TEXT,"soc-gic.lines",live=false)))
    }
    override val descriptor=DESCRIPTOR
    override fun create(context: ComponentContext,config: Map<String,Any>): HardwareComponent=Timer(context,config)
    class Timer(context: ComponentContext,config: Map<String,Any>): ScalarComponent("soc-timer",context,config),TickAwareMmioDevice,StatefulComponent {
        override val clockOrder=0
        private class Counter(
            var stored: Long = 0L,
            var reload: Long = 0L,
            var enabled: Boolean = false,
            var hasDeadline: Boolean = false,
            var deadlineValue: Long = 0L,
        ) {
            val deadline: Long? get() = if (hasDeadline) deadlineValue else null
            fun value(now: Long): Long {
                if (!hasDeadline) return stored
                val dl = deadlineValue
                return if (now < dl) dl - now - 1L else reload - (now - dl) % (reload + 1L)
            }
            fun load(now: Long, word: Long) {
                if (enabled) {
                    deadlineValue = Math.addExact(now, word + 1L)
                    hasDeadline = true
                } else {
                    hasDeadline = false
                    deadlineValue = 0L
                }
                stored = word
            }
        }
        private class EventRing {
            private val cap = 256
            private val channel = IntArray(cap)
            private val line = IntArray(cap)
            private val scheduled = LongArray(cap)
            private val observed = LongArray(cap)
            private val reloadPeriod = LongArray(cap)
            private val elapsed = LongArray(cap)
            private val nextDeadline = LongArray(cap)
            private val pendingBefore = BooleanArray(cap)
            private val hasLoc = BooleanArray(cap)
            private val core = IntArray(cap)
            private val pc = LongArray(cap)
            private var head = 0
            var size = 0
                private set
            fun clear() { head = 0; size = 0 }
            fun add(
                ch: Int, irq: Int, sched: Long, obs: Long, period: Long, count: Long,
                next: Long, pending: Boolean, hasLocation: Boolean, coreId: Int, pcVirtual: Long,
            ) {
                val idx = if (size < cap) {
                    val slot = (head + size) and 255
                    size++
                    slot
                } else {
                    val slot = head
                    head = (head + 1) and 255
                    slot
                }
                channel[idx] = ch; line[idx] = irq; scheduled[idx] = sched; observed[idx] = obs
                reloadPeriod[idx] = period; elapsed[idx] = count; nextDeadline[idx] = next
                pendingBefore[idx] = pending; hasLoc[idx] = hasLocation; core[idx] = coreId; pc[idx] = pcVirtual
            }
            fun toList(): List<Map<String, Any?>> {
                val result = ArrayList<Map<String, Any?>>(size)
                for (i in 0 until size) {
                    val idx = (head + i) and 255
                    result.add(linkedMapOf(
                        "channel" to channel[idx],
                        "line" to line[idx],
                        "scheduledDeadline" to scheduled[idx],
                        "observedTick" to observed[idx],
                        "reloadPeriod" to reloadPeriod[idx],
                        "elapsedPeriods" to elapsed[idx],
                        "nextDeadline" to nextDeadline[idx],
                        "pendingBefore" to pendingBefore[idx],
                        "core" to if (hasLoc[idx]) core[idx] else null,
                        "pcVirtual" to if (hasLoc[idx]) pc[idx] else null,
                    ))
                }
                return result
            }
            fun restore(entries: List<Map<String, Any?>>) {
                clear()
                for (entry in entries) {
                    val c = entry["core"] as Int?
                    val p = entry["pcVirtual"] as Long?
                    add(
                        ch = entry["channel"] as Int,
                        irq = entry["line"] as Int,
                        sched = entry["scheduledDeadline"] as Long,
                        obs = entry["observedTick"] as Long,
                        period = entry["reloadPeriod"] as Long,
                        count = entry["elapsedPeriods"] as Long,
                        next = entry["nextDeadline"] as Long,
                        pending = entry["pendingBefore"] as Boolean,
                        hasLocation = c != null && p != null,
                        coreId = c ?: 0,
                        pcVirtual = p ?: 0L,
                    )
                }
            }
        }
        private class AccessRing {
            private val cap = 512
            private val tick = LongArray(cap)
            private val offset = LongArray(cap)
            private val accessSize = IntArray(cap)
            private val write = BooleanArray(cap)
            private val value = LongArray(cap)
            private val hasLoc = BooleanArray(cap)
            private val core = IntArray(cap)
            private val pc = LongArray(cap)
            private var head = 0
            var size = 0
                private set
            fun clear() { head = 0; size = 0 }
            fun add(
                t: Long, off: Long, sz: Int, isWrite: Boolean, v: Long,
                hasLocation: Boolean, coreId: Int, pcVirtual: Long,
            ) {
                val idx = if (size < cap) {
                    val slot = (head + size) and 511
                    size++
                    slot
                } else {
                    val slot = head
                    head = (head + 1) and 511
                    slot
                }
                tick[idx] = t; offset[idx] = off; accessSize[idx] = sz; write[idx] = isWrite
                value[idx] = v; hasLoc[idx] = hasLocation; core[idx] = coreId; pc[idx] = pcVirtual
            }
            fun toList(): List<Map<String, Any?>> {
                val result = ArrayList<Map<String, Any?>>(size)
                for (i in 0 until size) {
                    val idx = (head + i) and 511
                    result.add(linkedMapOf(
                        "tick" to tick[idx],
                        "offset" to offset[idx],
                        "size" to accessSize[idx],
                        "operation" to if (write[idx]) "write" else "read",
                        "value" to value[idx],
                        "core" to if (hasLoc[idx]) core[idx] else null,
                        "pcVirtual" to if (hasLoc[idx]) pc[idx] else null,
                    ))
                }
                return result
            }
            fun restore(entries: List<Map<String, Any?>>) {
                clear()
                for (entry in entries) {
                    val c = entry["core"] as Int?
                    val p = entry["pcVirtual"] as Long?
                    add(
                        t = entry["tick"] as Long,
                        off = entry["offset"] as Long,
                        sz = entry["size"] as Int,
                        isWrite = entry["operation"] == "write",
                        v = entry["value"] as Long,
                        hasLocation = c != null && p != null,
                        coreId = c ?: 0,
                        pcVirtual = p ?: 0L,
                    )
                }
            }
        }
        private val counters=Array(8){Counter()}
        private val preflightStored=LongArray(8)
        private val preflightDeadline=LongArray(8)
        private val preflightHasDeadline=BooleanArray(8)
        private val preflightEnabled=BooleanArray(8)
        private val preflightTouched=BooleanArray(8)
        private val shadowVersionCell=LongArray(1) { 1L }
        private var earliestDeadlineValue: Long = Long.MAX_VALUE
        private fun bumpShadowVersion() { shadowVersionCell[0]++ }
        private fun refreshDeadline() {
            var earliest = Long.MAX_VALUE
            for (c in counters) {
                if (!c.hasDeadline) continue
                val at = c.deadlineValue
                if (at < earliest) earliest = at
            }
            earliestDeadlineValue = earliest
        }
        private var inlinePullHook: (() -> Unit)? = null
        fun bindInlinePullHook(hook: (() -> Unit)?) {
            inlinePullHook = hook
        }
        fun copyRoutes(routesOut: IntArray): Boolean {
            for (i in 0..7) routesOut[i] = routes[i]
            return routed
        }
        fun applyInlineNativeExpiries(deadlinesIn: LongArray, firedPeriodsIn: LongArray, lastFireTick: Long) {
            var any = false
            for (i in 0..7) {
                val fired = firedPeriodsIn[i]
                if (fired > 0L) {
                    val c = counters[i]
                    if (c.hasDeadline) {
                        c.deadlineValue = deadlinesIn[i]
                    }
                    elapsedPeriods = Math.addExact(elapsedPeriods, fired)
                    eventCount = Math.addExact(eventCount, 1L)
                    any = true
                }
            }
            if (any) {
                if (routed && lastFireTick > lastTick) lastTick = lastFireTick
                refreshDeadline()
            }
        }
        fun shadowVersion(): Long = shadowVersionCell[0]
        fun shadowVersionCell(): LongArray = shadowVersionCell
        fun copyShadowState(
            storedOut: LongArray,
            reloadOut: LongArray,
            deadlineOut: LongArray,
            enabledOut: IntArray,
            hasDeadlineOut: IntArray,
        ): Long {
            for (i in 0..7) {
                val c = counters[i]
                storedOut[i] = c.stored
                reloadOut[i] = c.reload
                deadlineOut[i] = if (c.hasDeadline) c.deadlineValue else 0L
                enabledOut[i] = if (c.enabled) 1 else 0
                hasDeadlineOut[i] = if (c.hasDeadline) 1 else 0
            }
            return shadowVersionCell[0]
        }
        private val routed=config.getValue("profile")==ROUTED
        private val sinkName=config.getValue("irqSink") as String
        private val routes=IntArray(8){-1}
        private val sink by lazy {context.ports.find(sinkName,InterruptSink::class.java) as? EdgeInterruptSink}
        private val location by lazy {context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)}
        private var lastTick=0L
        private val events=EventRing()
        private val accesses=AccessRing()
        private val planChannel=IntArray(8)
        private val planIrq=IntArray(8)
        private val planScheduled=LongArray(8)
        private val planPeriod=LongArray(8)
        private val planCount=LongArray(8)
        private val planNext=LongArray(8)
        private val planPending=BooleanArray(8)
        private var eventCount=0L;private var accessCount=0L;private var elapsedPeriods=0L
        private var clockName="one-tick-per-attempt"
        private var lastControlWriteMask = 0
        private var lastControlWriteEnableBits = 0
        private var ch6DisableCount = 0L
        private var blindMultiChannelDisableCount = 0L
        private val perChannelDisableCounts = LongArray(8)
        private fun currentChannelAudit(): TimerChannelAuditState {
            var enabledMask = 0
            for (i in 0..7) if (counters[i].enabled) enabledMask = enabledMask or (1 shl i)
            return TimerChannelAuditState(
                enabledMask = enabledMask,
                ch6DisplayTimerActive = counters[6].enabled,
                ch7OsTickTimerActive = counters[7].enabled,
                lastControlWriteMask = lastControlWriteMask,
                lastControlWriteEnableBits = lastControlWriteEnableBits,
                ch6DisableCount = ch6DisableCount,
                blindMultiChannelDisableCount = blindMultiChannelDisableCount,
                perChannelDisableCounts = perChannelDisableCounts.toList(),
            )
        }
        override fun nextDeadline(): Long? { inlinePullHook?.invoke(); return if (earliestDeadlineValue == Long.MAX_VALUE) null else earliestDeadlineValue }
        override fun nextInterestedTick(): Long { inlinePullHook?.invoke(); return earliestDeadlineValue }
        override fun requiresEveryTick(): Boolean = false
        override fun readMayMutateSchedule(): Boolean = false
        private fun syncLastTick(now: Long) { if (routed && now > lastTick) lastTick = now }
        private val specification: Map<String,Any>
        init {
            val input=config.getValue("inputFile") as String
            require(routed==input.isNotBlank()){ "timer profile/inputFile selection mismatch" }
            require(sinkName.isNotBlank()){ "timer irqSink is empty" }
            if(routed) {
                val data=context.readData(input)
                require(data.keys==setOf("profile","clock","routes")&&data["profile"]==ROUTED&&data["clock"] in setOf("one-tick-per-attempt","functional-device-ticks-with-idle")){ "invalid explicit timer profile/clock" }
                clockName=data.getValue("clock") as String
                val rows=data["routes"];require(rows is List<*>&&rows.size<=8){ "invalid timer route list" }
                fun integer(v: Any?,range: IntRange): Int {require(v is Byte||v is Short||v is Int||v is Long);val n=(v as Number).toLong();require(n in range.first.toLong()..range.last.toLong());return n.toInt()}
                val used=HashSet<Int>()
                rows.forEach {raw->require(raw is Map<*,*>&&raw.keys==setOf("channel","irq"));val channel=integer(raw["channel"],0..7);val irq=integer(raw["irq"],32..511)
                    require(routes[channel]<0&&used.add(irq)){ "duplicate timer channel/IRQ" };routes[channel]=irq}
            }
            specification=mapOf("profile" to ROUTED,"clock" to clockName,"routes" to routes.indices.filter{routes[it]>=0}.map{mapOf("channel" to it,"irq" to routes[it])})
            context.ports.register(id,id,MmioDevice::class.java,this)
            context.ports.register(id,"$id.channels",TimerChannelObservabilityPort::class.java,object:TimerChannelObservabilityPort {
                override fun channelAuditState(): TimerChannelAuditState = currentChannelAudit()
            })
        }
        override fun interruptRoutes()=if(routed)routes.filter{it>=0}.map{InterruptRoute(sinkName,it..it)}else emptyList()
        override fun beforeInstruction(tick: Long) {
            inlinePullHook?.invoke()
            require(tick>=0&&(!routed||tick>=lastTick)){ "timer clock moved backwards" }
            val at=earliestDeadlineValue
            if(tick<at){if(routed)lastTick=tick;return}
            check(routed){ "timer expiry: JVM IRQ route not qualified" }
            var planned=0
            val target=sink
            var total=elapsedPeriods
            // Preflight ALL due counters before phase, pending, logs or lastTick changes.
            for(i in counters.indices) {
                val c=counters[i]
                if(!c.hasDeadline)continue
                val due=c.deadlineValue
                if(tick<due)continue
                val irq=routes[i];require(irq>=0&&target!=null){ "unmodeled-interval-timer-route channel=$i" }
                val stateBits=target.edgeStateBits(irq)
                require((stateBits and 1)!=0&&(stateBits and 2)==0){ "unqualified-interval-timer-edge line=$irq" }
                val period=c.reload+1
                val count=Math.addExact((tick-due)/period,1)
                val next=Math.addExact(due,Math.multiplyExact(count,period))
                total=Math.addExact(total,count)
                planChannel[planned]=i; planIrq[planned]=irq; planScheduled[planned]=due
                planPeriod[planned]=period; planCount[planned]=count; planNext[planned]=next
                planPending[planned]=(stateBits and 4)!=0; planned++
            }
            val nextEvents=Math.addExact(eventCount,planned.toLong())
            val loc=location
            val hasLoc=loc!=null
            val coreId=if(hasLoc) loc.currentCore() else 0
            val pcVirt=if(hasLoc) loc.currentPcVirtual() else 0L
            lastTick=tick
            for(p in 0 until planned) {
                val ch=planChannel[p]
                val irq=planIrq[p]
                val c=counters[ch]
                c.hasDeadline=true
                c.deadlineValue=planNext[p]
                bumpShadowVersion()
                // Publish derived state before calling an external sink, which can throw.
                refreshDeadline()
                checkNotNull(target).setLine(irq,true);target.setLine(irq,false)
                events.add(ch,irq,planScheduled[p],tick,planPeriod[p],planCount[p],planNext[p],planPending[p],hasLoc,coreId,pcVirt)
            }
            eventCount=nextEvents;elapsedPeriods=total
        }
        private fun access(offset: Long,size: Int,write: Boolean,value: Long): Long {
            inlinePullHook?.invoke()
            require(size==4&&offset>=0&&offset%4==0L){ "timer aligned word access required" }
            val now=context.clock.ticks();require(now>=0)
            val word=value and 0xffffffffL
            val result=if(offset==0L) {
                if(write) {
                    require(word and 0x00ff00ffL.inv()==0L){ "unsupported timer control bits" }
                    val writeMask = ((word ushr 16) and 0xffL).toInt()
                    val enableBits = (word and 0xffL).toInt()
                    var disabledMask = 0
                    for(i in 0..7) {
                        if(word and (1L shl (i+16))!=0L) {
                            val nextEnabled = (word and (1L shl i)) != 0L
                            val c = counters[i]
                            val v = c.value(now)
                            preflightStored[i] = v
                            preflightEnabled[i] = nextEnabled
                            preflightHasDeadline[i] = nextEnabled
                            preflightDeadline[i] = if (nextEnabled) Math.addExact(now, Math.addExact(v, 1L)) else 0L
                            preflightTouched[i] = true
                            if (c.enabled && !nextEnabled) disabledMask = disabledMask or (1 shl i)
                        } else {
                            preflightTouched[i] = false
                        }
                    }
                    for(i in 0..7) if (preflightTouched[i]) {
                        val c = counters[i]
                        c.stored = preflightStored[i]
                        c.enabled = preflightEnabled[i]
                        c.hasDeadline = preflightHasDeadline[i]
                        c.deadlineValue = preflightDeadline[i]
                    }
                    bumpShadowVersion()
                    lastControlWriteMask = writeMask
                    lastControlWriteEnableBits = enableBits
                    for (i in 0..7) {
                        if ((disabledMask and (1 shl i)) != 0) perChannelDisableCounts[i]++
                    }
                    if ((disabledMask and (1 shl 6)) != 0) {
                        ch6DisableCount++
                        context.events.publish(
                            ComponentEvent(
                                id,
                                "display-timer-disabled",
                                now,
                                mapOf(
                                    "channel" to 6,
                                    "writeMask" to writeMask,
                                    "enableBits" to enableBits,
                                    "disabledMask" to disabledMask,
                                ),
                            ),
                        )
                    }
                    if ((disabledMask and (1 shl 7)) != 0 && (disabledMask and 0x7f) != 0) {
                        blindMultiChannelDisableCount++
                    }
                    refreshDeadline()
                }
                var bits=0L
                for(i in 0..7) if(counters[i].enabled) bits=bits or (1L shl i)
                bits
            } else {
                require(offset in 0x10L..<0x50L){ "unknown timer register" }
                val index=((offset-0x10)/8).toInt();val c=counters[index]
                if(offset and 4==0L){if(write){c.load(now,word);bumpShadowVersion();refreshDeadline()};c.value(now)}
                else {if(write){c.reload=word;bumpShadowVersion()};c.reload}
            }
            if(routed) {
                val loc=location
                if(loc!=null) accesses.add(now,offset,size,write,if(write)word else result,true,loc.currentCore(),loc.currentPcVirtual())
                else accesses.add(now,offset,size,write,if(write)word else result,false,0,0L)
                accessCount=Math.addExact(accessCount,1)
            }
            return result
        }
        override fun read(offset: Long,size: Int)=access(offset,size,false,0)
        override fun write(offset: Long,size: Int,value: Long){access(offset,size,true,value)}
        override fun state(): Map<String,Any?> {
            inlinePullHook?.invoke()
            syncLastTick(context.clock.ticks())
            val base=mapOf("counters" to counters.map{mapOf("value" to it.value(context.clock.ticks()),"reload" to it.reload,"enabled" to it.enabled,"deadline" to it.deadline)},"clock" to if(clockName=="one-tick-per-attempt")"instruction attempts (hypothesis)"else"functional device ticks with explicit idle advancement (hypothesis)")
            if(!routed)return base
            return base+mapOf("profile" to ROUTED,"specification" to specification,"storedValues" to counters.map{it.stored},"lastTick" to lastTick,
                "events" to events.toList(),"eventCount" to eventCount,"elapsedPeriods" to elapsedPeriods,"eventLogTruncated" to(eventCount>events.size),
                "accesses" to accesses.toList(),"accessCount" to accessCount,"accessLogTruncated" to(accessCount>accesses.size),
                "basis" to "Explicit instruction-attempt clock and routes. Preflight all expiries before phase/pending mutation. Coalesced edge pulses; GIC masks/targets/priorities/IAR/EOI decide delivery. No physical wiring/clock/level-ACK or idle advancement qualification")
        }
        override fun saveSnapshot(): Map<String,Any?> {
            inlinePullHook?.invoke()
            syncLastTick(context.clock.ticks())
            return linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "settings" to saveScalarValues(),
            "routed" to routed,
            "clockName" to clockName,
            "lastTick" to lastTick,
            "eventCount" to eventCount,
            "elapsedPeriods" to elapsedPeriods,
            "accessCount" to accessCount,
            "counters" to counters.map { counter ->
                linkedMapOf(
                    "stored" to counter.stored,
                    "reload" to counter.reload,
                    "enabled" to counter.enabled,
                    "deadline" to counter.deadline,
                )
            },
            "events" to events.toList(),
            "accesses" to accesses.toList(),
        ).apply {
            val hasAudit = lastControlWriteMask != 0 || lastControlWriteEnableBits != 0 ||
                ch6DisableCount > 0L || blindMultiChannelDisableCount > 0L ||
                perChannelDisableCounts.any { it > 0L }
            if (hasAudit) {
                put(
                    "channelAudit",
                    linkedMapOf(
                        "lastControlWriteMask" to lastControlWriteMask,
                        "lastControlWriteEnableBits" to lastControlWriteEnableBits,
                        "ch6DisableCount" to ch6DisableCount,
                        "blindMultiChannelDisableCount" to blindMultiChannelDisableCount,
                        "perChannelDisableCounts" to perChannelDisableCounts.toList(),
                    ),
                )
            }
        }
        }
        override fun restoreSnapshot(data: Map<String,Any?>) {
            val parsed = TimerSnapshotCodec.parse(
                data = data,
                expectedSchema = snapshotSchemaVersion,
                expectedId = id,
                liveRouted = routed,
                liveClockName = clockName,
                requiredSettingKeys = values.keys,
            )
            val compatibleKeys = if (routed && parsed.savedRouted) setOf("profile", "inputFile") else emptySet()
            restoreScalarValues(parsed.settingsMap, DESCRIPTOR, compatibleKeys)
            parsed.counters.forEachIndexed { channel, counter ->
                val c = counters[channel]
                c.stored = counter.stored
                c.reload = counter.reload
                c.enabled = counter.enabled
                val dl = counter.deadline
                c.hasDeadline = dl != null
                c.deadlineValue = dl ?: 0L
            }
            bumpShadowVersion()
            lastTick = parsed.lastTick
            eventCount = parsed.eventCount
            elapsedPeriods = parsed.elapsedPeriods
            accessCount = parsed.accessCount
            lastControlWriteMask = parsed.channelAudit.lastControlWriteMask
            lastControlWriteEnableBits = parsed.channelAudit.lastControlWriteEnableBits
            ch6DisableCount = parsed.channelAudit.ch6DisableCount
            blindMultiChannelDisableCount = parsed.channelAudit.blindMultiChannelDisableCount
            parsed.channelAudit.perChannelDisableCounts.copyInto(perChannelDisableCounts)
            events.restore(parsed.events)
            accesses.restore(parsed.accesses)
            refreshDeadline()
        }
    }
}

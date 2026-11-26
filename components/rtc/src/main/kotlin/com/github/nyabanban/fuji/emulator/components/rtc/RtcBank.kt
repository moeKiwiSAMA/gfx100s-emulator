package com.github.nyabanban.fuji.emulator.components.rtc

import com.github.nyabanban.fuji.emulator.api.*

/** Explicit functional hypothesis. Selected DAT proves consumers, not oscillator/reset/strobe timing. */
internal class RtcBank(
    private val ticksPerSecond: Long,
    initialSeconds: Long,
    initialControl: Int,
    private val counterRunning: Boolean,
    private val loadDelayTicks: Long,
    private val onLoadCommitted: ((seconds: Long, control: Int) -> Unit)? = null,
) : DescribedPowerRegisterPort {
    init {
        require(ticksPerSecond > 0 && loadDelayTicks > 0)
        require(initialSeconds in 0..0xffffffffL && initialControl in 0..0xffff && initialControl and 0x200 == 0) { "explicit bounded initial RTC state required" }
    }
    private val initialSecondsValue=initialSeconds
    private val initialControlValue=initialControl
    private var baseSeconds=initialSeconds
    private var baseTick=0L
    private var control=initialControl
    private var low=-1
    private var high=-1
    private var fresh=0
    private var loadedSeconds=0L
    private var loadAt: Long?=null
    private var lastWriteTick=0L
    private var writes=0L
    private var loads=0L
    private var owner: String?=null
    private var token: Any?=null
    private var closed=false
    private val claims=intArrayOf(0x1d,0x1e,0x23,0x24,0x27)
    override fun registerContracts(): List<PowerRegisterContract> = rtcRegisterContracts(false)
    internal fun checkReadTime(tick: Long) { at(tick) }
    private fun at(tick: Long) { require(tick>=lastWriteTick) { "RTC clock moved before last committed request" } }
    private fun seconds(tick: Long): Long {
        at(tick)
        val deadline=loadAt
        val loaded=deadline!=null && tick>=deadline
        val anchor=if(loaded) deadline else baseTick
        val seconds=if(loaded) loadedSeconds else baseSeconds
        val elapsed=if(counterRunning) (tick-anchor)/ticksPerSecond else 0L
        return (seconds+(elapsed and 0xffffffffL)) and 0xffffffffL
    }
    private fun control(tick: Long): Int { at(tick);return if(loadAt?.let{tick>=it}==true) control and 0xfdff else control }
    private fun busy(tick: Long)=loadAt?.let{tick<it}==true
    internal fun currentSeconds(tick: Long): Long = seconds(tick)
    internal fun currentControl(tick: Long): Int = control(tick)
    private fun read(register: Int,tick: Long): Int=when(register) {
        0x23 -> (seconds(tick) and 0xffff).toInt()
        0x24 -> (seconds(tick) ushr 16).toInt()
        0x27 -> control(tick)
        else -> error("RTC read from undeclared/read-unqualified register$register")
    }
    private fun checkWrite(register: Int,value: Int,tick: Long) {
        at(tick);require(value in 0..0xffff)
        require(!busy(tick)) { "RTC load pending; new write refused" }
        when(register) {
            0x1d,0x1e -> Unit
            0x27 -> if(value and 0x200!=0) {
                require(value and 2!=0 && fresh==3) { "RTC load requires both fresh halves and explicit valid bit" }
                Math.addExact(tick,loadDelayTicks)
            }
            else -> error("RTC write to undeclared/read-only register$register")
        }
    }
    private fun write(register: Int,value: Int,tick: Long) {
        checkWrite(register,value,tick)
        when(register) {
            0x1d -> {low=value;fresh=fresh or 1}
            0x1e -> {high=value;fresh=fresh or 2}
            0x27 -> {
                if(value and 0x200!=0) {
                    // Preserve the old counter anchor through a second delayed load; no lost subsecond phase.
                    loadAt?.let{baseTick=it;baseSeconds=loadedSeconds}
                    loadedSeconds=(high.toLong() shl 16) or low.toLong()
                    loadAt=Math.addExact(tick,loadDelayTicks);fresh=0;loads++
                    onLoadCommitted?.invoke(loadedSeconds, value and 0xfdff)
                }
                control=value
            }
        }
        writes++;lastWriteTick=tick
    }
    override fun connect(owner: String): PowerRegisterConnection {
        check(!closed) { "RTC endpoint closed" };require(owner.isNotBlank());check(this.owner==null) { "RTC register endpoint already owned" }
        val lease=Any();this.owner=owner;token=lease
        return object: PowerRegisterConnection {
            private var closed=false
            private fun live(){check(!closed && token===lease) { "closed RTC register connection" }}
            override fun registers(): IntArray {live();return claims.copyOf()}
            override fun read(register: Int,tick: Long): Int {live();return this@RtcBank.read(register,tick)}
            override fun checkWrite(register: Int,value: Int,tick: Long){live();this@RtcBank.checkWrite(register,value,tick)}
            override fun write(register: Int,value: Int,tick: Long){live();this@RtcBank.write(register,value,tick)}
            override fun close(){if(!closed){closed=true;if(token===lease){token=null;this@RtcBank.owner=null}}}
        }
    }
    fun close() {closed=true;token=null;owner=null}
    val isClosed: Boolean get() = closed

    internal class ParsedSnapshot(
        val baseSeconds: Long,
        val baseTick: Long,
        val control: Int,
        val low: Int,
        val high: Int,
        val fresh: Int,
        val loadedSeconds: Long,
        val loadAt: Long?,
        val lastWriteTick: Long,
        val writes: Long,
        val loads: Long,
    )

    internal fun computeReadFromParsed(register: Int, tick: Long, parsed: ParsedSnapshot): Int {
        require(tick >= parsed.lastWriteTick) { "RTC clock moved before last committed request" }
        val deadline = parsed.loadAt
        val loaded = deadline != null && tick >= deadline
        val anchor = if (loaded) deadline else parsed.baseTick
        val activeSeconds = if (loaded) parsed.loadedSeconds else parsed.baseSeconds
        val elapsed = if (counterRunning) (tick - anchor) / ticksPerSecond else 0L
        val computedSeconds = (activeSeconds + (elapsed and 0xffffffffL)) and 0xffffffffL
        val computedControl = if (deadline != null && tick >= deadline) parsed.control and 0xfdff else parsed.control
        return when (register) {
            0x23 -> (computedSeconds and 0xffffL).toInt()
            0x24 -> (computedSeconds ushr 16).toInt()
            0x27 -> computedControl
            else -> error("RTC read from undeclared/read-unqualified register$register")
        }
    }

    fun saveSnapshot(): Map<String, Any?> {
        check(!closed) { "RTC endpoint closed" }
        return linkedMapOf(
            "ticksPerSecond" to ticksPerSecond,
            "counterRunning" to counterRunning,
            "loadDelayTicks" to loadDelayTicks,
            "baseSeconds" to baseSeconds,
            "baseTick" to baseTick,
            "control" to control,
            "stageLow" to low.takeIf { stage -> stage >= 0 },
            "stageHigh" to high.takeIf { stage -> stage >= 0 },
            "freshHalves" to fresh,
            "loadedSeconds" to loadedSeconds,
            "loadAt" to loadAt,
            "lastWriteTick" to lastWriteTick,
            "writeCount" to writes,
            "loadCount" to loads,
        )
    }

    fun parseSnapshot(raw: Any?): ParsedSnapshot {
        check(!closed) { "RTC endpoint closed" }
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing RTC bank snapshot")
        val expectedKeys = setOf(
            "ticksPerSecond", "counterRunning", "loadDelayTicks", "baseSeconds", "baseTick", "control",
            "stageLow", "stageHigh", "freshHalves", "loadedSeconds", "loadAt", "lastWriteTick", "writeCount", "loadCount",
        )
        require(map.keys == expectedKeys) { "Invalid RTC bank snapshot keys" }
        require(snapshotLong(map["ticksPerSecond"], "ticksPerSecond", 1L, Long.MAX_VALUE) == ticksPerSecond) {
            "RTC ticksPerSecond differs in snapshot"
        }
        require((map["counterRunning"] as? Boolean) == counterRunning) { "RTC counterRunning differs in snapshot" }
        require(snapshotLong(map["loadDelayTicks"], "loadDelayTicks", 1L, Long.MAX_VALUE) == loadDelayTicks) {
            "RTC loadDelayTicks differs in snapshot"
        }
        val nextBaseSeconds = snapshotLong(map["baseSeconds"], "baseSeconds", 0L, 0xffffffffL)
        val nextBaseTick = snapshotLong(map["baseTick"], "baseTick", 0L, Long.MAX_VALUE)
        val nextControl = snapshotInt(map["control"], "control", 0, 0xffff)
        val nextLow = map["stageLow"]?.let { stage -> snapshotInt(stage, "stageLow", 0, 0xffff) } ?: -1
        val nextHigh = map["stageHigh"]?.let { stage -> snapshotInt(stage, "stageHigh", 0, 0xffff) } ?: -1
        val nextFresh = snapshotInt(map["freshHalves"], "freshHalves", 0, 3)
        require((nextFresh and 1 == 0 || nextLow >= 0) && (nextFresh and 2 == 0 || nextHigh >= 0)) {
            "RTC freshHalves requires corresponding staged value"
        }
        val nextLoadedSeconds = snapshotLong(map["loadedSeconds"], "loadedSeconds", 0L, 0xffffffffL)
        val nextLoadAt = map["loadAt"]?.let { deadline -> snapshotLong(deadline, "loadAt", 1L, Long.MAX_VALUE) }
        val nextLastWriteTick = snapshotLong(map["lastWriteTick"], "lastWriteTick", 0L, Long.MAX_VALUE)
        val nextWrites = snapshotLong(map["writeCount"], "writeCount", 0L, Long.MAX_VALUE)
        val nextLoads = snapshotLong(map["loadCount"], "loadCount", 0L, nextWrites)
        if (nextWrites == 0L) {
            require(
                nextLastWriteTick == 0L && nextLoads == 0L && nextLoadAt == null &&
                    nextLow < 0 && nextHigh < 0 && nextFresh == 0 && nextBaseTick == 0L,
            ) { "RTC zero-write snapshot has inconsistent staged or load state" }
        }
        if (nextLoads == 0L) {
            require(nextLoadAt == null && (nextControl and 0x200) == 0 && nextLoadedSeconds == 0L && nextBaseTick == 0L) {
                "RTC zero-load snapshot has active load state"
            }
        } else {
            require(nextLoadAt != null) { "RTC non-zero loadCount requires loadAt deadline" }
        }
        require((nextControl and 0x200) == 0 || nextLoadAt != null) { "RTC control strobe bit requires loadAt" }
        return ParsedSnapshot(
            baseSeconds = nextBaseSeconds,
            baseTick = nextBaseTick,
            control = nextControl,
            low = nextLow,
            high = nextHigh,
            fresh = nextFresh,
            loadedSeconds = nextLoadedSeconds,
            loadAt = nextLoadAt,
            lastWriteTick = nextLastWriteTick,
            writes = nextWrites,
            loads = nextLoads,
        )
    }

    fun applySnapshot(parsed: ParsedSnapshot) {
        check(!closed) { "RTC endpoint closed" }
        baseSeconds = parsed.baseSeconds
        baseTick = parsed.baseTick
        control = parsed.control
        low = parsed.low
        high = parsed.high
        fresh = parsed.fresh
        loadedSeconds = parsed.loadedSeconds
        loadAt = parsed.loadAt
        lastWriteTick = parsed.lastWriteTick
        writes = parsed.writes
        loads = parsed.loads
    }

    fun reset() {
        check(!closed) { "RTC endpoint closed" }
        baseSeconds = initialSecondsValue
        baseTick = 0L
        control = initialControlValue
        low = -1
        high = -1
        fresh = 0
        loadedSeconds = 0L
        loadAt = null
        lastWriteTick = 0L
        writes = 0L
        loads = 0L
    }

    private fun snapshotLong(value: Any?, field: String, min: Long, max: Long): Long {
        require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
        val number = (value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $field")
        require(number in min..max) { "Out-of-range integer for $field" }
        return number
    }

    private fun snapshotInt(value: Any?, field: String, min: Int, max: Int): Int =
        snapshotLong(value, field, min.toLong(), max.toLong()).toInt()

    fun state(tick: Long): Map<String,Any?> = mapOf(
        "closed" to closed,"seconds" to seconds(tick),"control" to control(tick),"stageLow" to low.takeIf{it>=0},"stageHigh" to high.takeIf{it>=0},
        "freshHalves" to fresh,"loadAt" to loadAt,"loadPending" to busy(tick),"lastWriteTick" to lastWriteTick,
        "writeCount" to writes,"loadCount" to loads,"owner" to owner,"ticksPerSecond" to ticksPerSecond,
        "counterRunning" to counterRunning,"loadDelayTicks" to loadDelayTicks,
    )
}

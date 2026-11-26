package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.runtime.MmioRouter

/** Explicit functional idle-time input; no guest instruction, PC, interrupt or device state is fabricated. */
class IdleClock private constructor(private val maxAdvances:Long,private val maxIdleTicks:Long) {
    companion object {
        const val PROFILE="next-deadline-functional-experimental-v1"
        const val TIMER_CLOCK="functional-device-ticks-with-idle"
        private const val EVENT_CAP = 256
        fun parse(raw:Any?):IdleClock? {
            if(raw==null)return null
            require(raw is Map<*,*> && raw.keys==setOf("profile","maxAdvances","maxIdleTicks") && raw["profile"]==PROFILE) { "invalid explicit idleClock schema/profile" }
            fun integer(value:Any?,maximum:Long):Long {
                require(value is Byte||value is Short||value is Int||value is Long) { "idleClock limit requires integer" }
                return (value as Number).toLong().also{require(it in 1..maximum){"idleClock limit out of bounds"}}
            }
            return IdleClock(integer(raw["maxAdvances"],65536),integer(raw["maxIdleTicks"],1_000_000_000))
        }
    }
    private var advances=0L
    private var idleTicks=0L
    private var unlimited=false
    private val evFrom = LongArray(EVENT_CAP)
    private val evTo = LongArray(EVENT_CAP)
    private val evAttempts = LongArray(EVENT_CAP)
    private val evAdvanced = BooleanArray(EVENT_CAP)
    private val evFailure = arrayOfNulls<String>(EVENT_CAP)
    private var evHead = 0
    private var evSize = 0
    private fun recordEvent(from: Long, to: Long, instructionAttempts: Long, devicesAdvanced: Boolean, failure: String?) {
        val slot = if (evSize < EVENT_CAP) {
            val s = (evHead + evSize) and (EVENT_CAP - 1)
            evSize++
            s
        } else {
            val s = evHead
            evHead = (evHead + 1) and (EVENT_CAP - 1)
            s
        }
        evFrom[slot] = from
        evTo[slot] = to
        evAttempts[slot] = instructionAttempts
        evAdvanced[slot] = devicesAdvanced
        evFailure[slot] = failure
    }
    private fun eventsToList(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>(evSize)
        for (i in 0 until evSize) {
            val s = (evHead + i) and (EVENT_CAP - 1)
            out.add(
                mapOf(
                    "from" to evFrom[s],
                    "to" to evTo[s],
                    "instructionAttempts" to evAttempts[s],
                    "devicesAdvanced" to evAdvanced[s],
                    "failure" to evFailure[s],
                ),
            )
        }
        return out
    }
    internal fun enableUnlimited() { unlimited = true }
    internal fun resetForColdReset() {
        advances = 0L
        idleTicks = 0L
        evHead = 0
        evSize = 0
        evFailure.fill(null)
    }
    fun advance(now:Long,instructionAttempts:Long,router:MmioRouter,clock:(Long)->Unit):Boolean {
        val next=router.nextDeadline(now) ?: return false
        val delta=Math.subtractExact(next,now)
        if (!unlimited) {
            require(advances<maxAdvances) { "idleClock advance budget exhausted" }
            require(delta<=maxIdleTicks-idleTicks) { "idleClock tick budget exhausted" }
        }
        // All limits precede clock/device mutation. The normal router still owns event ordering/refusal.
        clock(next);advances++;idleTicks+=delta
        var failure:String?=null
        try {router.beforeInstruction(next)}
        catch(error:Throwable){failure=error.message?:error.javaClass.simpleName;throw error}
        finally {
            recordEvent(now, next, instructionAttempts, failure == null, failure)
        }
        return true
    }
    fun state(virtualTick:Long,instructionAttempts:Long)=mapOf("profile" to PROFILE,"timerClock" to TIMER_CLOCK,"virtualTick" to virtualTick,
        "instructionAttempts" to instructionAttempts,"maxAdvances" to maxAdvances,"maxIdleTicks" to maxIdleTicks,
        "advanceCount" to advances,"idleTicks" to idleTicks,"events" to eventsToList(),"eventLogTruncated" to (advances>evSize),
        "basis" to "HYP: one functional device tick per instruction attempt plus bounded jumps to already declared device deadlines only while every core waits; not physical cycles/frequency. Reset release thresholds remain instruction attempts")
    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "schema" to 1,
        "profile" to PROFILE,
        "maxAdvances" to maxAdvances,
        "maxIdleTicks" to maxIdleTicks,
        "advanceCount" to advances,
        "idleTicks" to idleTicks,
        "events" to eventsToList(),
    )
    fun restoreSnapshot(data: Map<*, *>) {
        require((data["schema"] as? Number)?.toInt() == 1) { "Unsupported IdleClock snapshot schema" }
        require(data["profile"] == PROFILE) { "IdleClock profile mismatch" }
        val nextAdvances = (data["advanceCount"] as? Number)?.toLong() ?: error("Missing IdleClock advanceCount")
        val nextIdleTicks = (data["idleTicks"] as? Number)?.toLong() ?: error("Missing IdleClock idleTicks")
        require(nextAdvances in 0..maxAdvances && nextIdleTicks in 0..maxIdleTicks) {
            "Restored IdleClock counters exceed configured limits"
        }
        val rawEvents = data["events"] as? List<*> ?: error("Missing IdleClock events")
        require(rawEvents.size <= EVENT_CAP && rawEvents.size.toLong() <= nextAdvances) { "Invalid IdleClock event list" }
        val count = rawEvents.size
        val nextFrom = LongArray(count)
        val nextTo = LongArray(count)
        val nextAttempts = LongArray(count)
        val nextAdvanced = BooleanArray(count)
        val nextFailure = arrayOfNulls<String>(count)
        for (i in 0 until count) {
            val entry = rawEvents[i] as? Map<*, *> ?: error("Invalid IdleClock event")
            nextFrom[i] = (entry["from"] as Number).toLong()
            nextTo[i] = (entry["to"] as Number).toLong()
            nextAttempts[i] = (entry["instructionAttempts"] as Number).toLong()
            nextAdvanced[i] = entry["devicesAdvanced"] as Boolean
            nextFailure[i] = entry["failure"] as String?
        }
        advances = nextAdvances
        idleTicks = nextIdleTicks
        evHead = 0
        evSize = 0
        evFailure.fill(null)
        for (i in 0 until count) {
            recordEvent(nextFrom[i], nextTo[i], nextAttempts[i], nextAdvanced[i], nextFailure[i])
        }
    }
}

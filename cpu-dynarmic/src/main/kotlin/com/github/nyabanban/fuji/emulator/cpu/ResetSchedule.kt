package com.github.nyabanban.fuji.emulator.cpu

/** Explicit earliest global instruction thresholds. Not a board pin, PC trigger or idle-clock producer. */
class ResetSchedule private constructor(private val thresholds: LongArray) {
    companion object {
        const val PROFILE="secondary-reset-schedule-experimental-v1"
        fun parse(raw: Any): ResetSchedule {
            require(raw is Map<*,*> && raw.keys==setOf("profile","releaseTicks") && raw["profile"]==PROFILE) { "invalid reset schedule" }
            val values=raw["releaseTicks"];require(values is List<*> && values.size==4)
            val ticks=values.map {v->require(v is Byte || v is Short || v is Int || v is Long);(v as Number).toLong().also {require(it in 0..10_000_000)}}.toLongArray()
            require(ticks[0]==0L) { "primary core0 must start at zero" };return ResetSchedule(ticks)
        }
    }
    private val actual=LongArray(4) {if(thresholds[it]==0L)0 else -1}
    fun resetForColdReset() {
        for (i in actual.indices) actual[i] = if (thresholds[i] == 0L) 0L else -1L
    }
    fun held(core: Int)=actual[core]<0
    fun threshold(core: Int)=thresholds[core]
    fun release(core: Int,tick: Long): Boolean {
        require(tick>=0)
        if(!held(core))return true
        if(tick<thresholds[core])return false
        actual[core]=tick;return true
    }
    fun state()=mapOf("profile" to PROFILE,"configuredReleaseTicks" to thresholds.toList(),"actualReleaseTicks" to actual.map {if(it<0)null else it},
        "basis" to "Explicit earliest instruction-attempt thresholds, release on first eligible serialized scheduling visit. Held cores execute nothing; IRQ/SEV cannot bypass reset. No physical pin timing, PC trigger, sync-word write or invented idle progress")
    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "profile" to PROFILE,
        "configuredReleaseTicks" to thresholds.toList(),
        "actualReleaseTicks" to actual.map { if (it < 0) null else it },
    )
    fun restoreSnapshot(data: Map<*, *>) {
        require(data["profile"] == PROFILE) { "reset schedule snapshot profile differs" }
        val configured = (data["configuredReleaseTicks"] as? List<*>)?.map { (it as Number).toLong() }
        require(configured == thresholds.toList()) { "reset schedule configured thresholds differ" }
        val restored = data["actualReleaseTicks"] as? List<*> ?: error("missing actualReleaseTicks")
        require(restored.size == 4) { "expected 4 actualReleaseTicks" }
        val parsed = LongArray(4) { core ->
            val raw = restored[core]
            if (raw == null) {
                require(thresholds[core] > 0L) { "core0 cannot remain held in reset" }
                -1L
            } else {
                val tick = (raw as Number).toLong()
                require(tick >= thresholds[core]) { "actual release tick precedes threshold" }
                tick
            }
        }
        parsed.copyInto(actual)
    }
}

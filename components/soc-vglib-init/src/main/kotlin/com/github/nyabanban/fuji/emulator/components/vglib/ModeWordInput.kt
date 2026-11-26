package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*

/** One observed software READ/AND/WRITE path; neither storage nor physical register semantics. */
internal class ModeWordInput private constructor(
    private val context: ComponentContext,
    private val profile: String,
    private val samples: List<Long>,
    private val preserveMask: Long,
    private val continuous: Boolean = false,
    private val onWrite: ((Long) -> Unit)? = null,
): MmioDevice {
    override val clockOrder=90
    private var position=0
    private var retainedWord: Long = samples.last() and preserveMask
    private var retainedBypass = false
    val multiplePairs = samples.size > 1
    private val capacity = samples.size * 2
    private val values=LongArray(capacity);private val ticks=LongArray(capacity);private val cores=IntArray(capacity){-1};private val pcs=LongArray(capacity){-1}
    private fun expected(index: Int): Long {
        val old = samples[index / 2]
        return if (index % 2 == 0) old else old and preserveMask
    }
    private fun matchesScriptedStep(write: Boolean, value: Long?, location: ExecutionLocation?): Boolean {
        if (position >= capacity) return false
        if (write != (position % 2 == 1)) return false
        if (write && value != expected(position)) return false
        if (position >= 2 && location?.pcVirtual != helperPc(position)) return false
        return true
    }
    private fun check(offset:Long,size:Int,write:Boolean,value:Long?=null) {
        require(offset==0L&&size==4) {"mode word requires exact aligned word"}
        if (continuous) return
        check(position<capacity) {"mode word finite input exhausted"}
        require(write==(position%2==1)) {"mode word direction/order differs"}
        if(write)require(value==expected(position)) {"mode word SOURCE masked write differs"}
    }
    private fun record(value:Long) {
        val tick=context.clock.ticks();require(tick>=0) {"invalid mode word clock"}
        if (position < capacity) {
            val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current()
            if(position>=2) require(location?.pcVirtual == helperPc(position)) { "mode word additional pair requires DAT helper PC" }
            values[position]=value;ticks[position]=tick;cores[position]=location?.core?:-1;pcs[position]=location?.pcVirtual?:-1
        }
        position++
    }
    override fun read(offset:Long,size:Int):Long {
        check(offset,size,false)
        if (continuous) {
            val location = context.ports.find("cpu.execution-location", ExecutionLocationSource::class.java)?.current()
            if (!retainedBypass && matchesScriptedStep(write = false, value = null, location = location)) {
                return expected(position).also { sample ->
                    retainedWord = sample
                    record(sample)
                }
            }
            retainedBypass = true
            return retainedWord
        }
        return expected(position).also(::record)
    }
    override fun write(offset:Long,size:Int,value:Long) {
        require(value in 0..0xffffffffL){"mode word write exceeds u32"}
        check(offset,size,true,value)
        retainedWord = value
        onWrite?.invoke(value)
        if (continuous) {
            val location = context.ports.find("cpu.execution-location", ExecutionLocationSource::class.java)?.current()
            if (!retainedBypass && matchesScriptedStep(write = true, value = value, location = location)) {
                record(value)
            } else {
                retainedBypass = true
            }
            return
        }
        record(value)
    }
    private fun loggedCount(): Int = minOf(position, capacity)
    fun state():Map<String,Any?> = mapOf("profile" to profile,"readConfidence" to "HYPOTHESIS","readSample" to samples.first(),"sourcePreserveMask" to preserveMask,
        "transactionCapacity" to capacity,"transactionCount" to position,"remainingTransactions" to (capacity-position).coerceAtLeast(0),"eventLogTruncated" to (position > capacity || retainedBypass),
        "transactions" to (0 until loggedCount()).map{i->mapOf("offset" to 0,"direction" to if(i%2==0)"read" else "write","size" to 4,"value" to values[i],"tick" to ticks[i],"core" to cores[i].takeIf{it>=0},"pcVirtual" to pcs[i].takeIf{it>=0})},
        "scope" to "Software-path grouping only; other thirty read bits are explicit HYPOTHESIS. Physical identity, shared hardware with FFFD bank, reset and read/write side effects UNKNOWN. No storage/readback/ready/IRQ/clear/rendering completion law.") + extraFields()
    private fun extraFields(): Map<String, Any> = buildMap {
        if (multiplePairs) put("secondReadSample", samples[1])
        if (profile == TAIL_PROFILE) put("additionalReadSamples", samples.drop(2))
    }
    internal fun saveSnapshotState(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "sample" to samples.first(),
        "preserveMask" to preserveMask,
        "position" to position,
        "transactions" to (0 until loggedCount()).map { index ->
            linkedMapOf(
                "value" to values[index],
                "tick" to ticks[index],
                "core" to cores[index],
                "pcVirtual" to pcs[index],
            )
        },
    ) + extraFields() + (if (continuous && (position > capacity || retainedBypass)) mapOf("retainedWord" to retainedWord) else emptyMap())
    internal fun parseSnapshotState(raw: Any?): VglibSnapshotCodec.ParsedModeWordState {
        val map = VglibSnapshotCodec.requireMap(raw, "modeWord")
        val savedSecond = "secondReadSample" in map
        val savedTail = "additionalReadSamples" in map
        val savedRetained = "retainedWord" in map
        val allowedKeys = setOf("sample", "preserveMask", "position", "transactions", "secondReadSample", "additionalReadSamples") +
            (if (continuous) setOf("retainedWord") else emptySet())
        require(map.keys.all { it in allowedKeys } && (!savedTail || savedSecond)) {
            "Unexpected keys in modeWord snapshot"
        }
        require(VglibSnapshotCodec.requireUnsignedWord(map["preserveMask"], "modeWord.preserveMask") == preserveMask) {
            "ModeWord preserveMask differs from live input"
        }
        val savedSamples = buildList {
            add(VglibSnapshotCodec.requireUnsignedWord(map["sample"], "modeWord.sample"))
            if (savedSecond) add(VglibSnapshotCodec.requireUnsignedWord(map["secondReadSample"], "modeWord.secondReadSample"))
            if (savedTail) addAll(tailSamples(map["additionalReadSamples"]))
        }
        require(samples.take(savedSamples.size) == savedSamples) { "ModeWord sample prefix differs from live input" }
        val maxPos = if (continuous) Int.MAX_VALUE else 2 * savedSamples.size
        val nextPosition = VglibSnapshotCodec.requireInt(map["position"], "modeWord.position", 0..maxPos)
        val rawTransactions = map["transactions"] as? List<*> ?: throw IllegalArgumentException("Missing modeWord.transactions")
        val expectedTxCount = if (continuous) minOf(nextPosition, capacity) else nextPosition
        require(rawTransactions.size == expectedTxCount) { "ModeWord transaction count disagrees with position" }
        val nextValues = LongArray(capacity)
        val nextTicks = LongArray(capacity)
        val nextCores = IntArray(capacity) { -1 }
        val nextPcs = LongArray(capacity) { -1L }
        rawTransactions.forEachIndexed { index, rawEntry ->
            val entry = VglibSnapshotCodec.requireMap(rawEntry, "modeWord.transactions[$index]")
            require(entry.keys == setOf("value", "tick", "core", "pcVirtual")) {
                "Invalid keys in modeWord.transactions[$index]"
            }
            val entryValue = VglibSnapshotCodec.requireUnsignedWord(entry["value"], "modeWord.transactions[$index].value")
            val expectedValue = expected(index)
            require(entryValue == expectedValue) { "ModeWord logged value differs from expected sequence" }
            val tick = VglibSnapshotCodec.requireLong(entry["tick"], "modeWord.transactions[$index].tick")
            require(tick >= 0L) { "Negative tick in modeWord transaction" }
            val core = VglibSnapshotCodec.requireInt(entry["core"], "modeWord.transactions[$index].core", -1..Int.MAX_VALUE)
            val pcVirtual = VglibSnapshotCodec.requireLong(entry["pcVirtual"], "modeWord.transactions[$index].pcVirtual")
            require(pcVirtual >= -1L) { "Invalid pcVirtual in modeWord transaction" }
            if(index>=2) require(pcVirtual==helperPc(index)) { "Saved mode word helper differs" }
            nextValues[index] = entryValue
            nextTicks[index] = tick
            nextCores[index] = core
            nextPcs[index] = pcVirtual
        }
        val nextRetained = if (savedRetained) VglibSnapshotCodec.requireUnsignedWord(map["retainedWord"], "modeWord.retainedWord") else null
        return VglibSnapshotCodec.ParsedModeWordState(
            position = nextPosition,
            values = nextValues,
            ticks = nextTicks,
            cores = nextCores,
            pcs = nextPcs,
            retainedWord = nextRetained,
        )
    }
    internal fun applySnapshotState(parsed: VglibSnapshotCodec.ParsedModeWordState) {
        position = parsed.position
        retainedWord = parsed.retainedWord ?: (samples.last() and preserveMask)
        retainedBypass = parsed.retainedWord != null
        parsed.values.copyInto(values)
        parsed.ticks.copyInto(ticks)
        parsed.cores.copyInto(cores)
        parsed.pcs.copyInto(pcs)
    }
    companion object {
        const val PROFILE="vglib-mode-word-experimental-v1"
        const val TWO_PAIR_PROFILE="vglib-mode-word-case4-twice-experimental-v2"
        const val TAIL_PROFILE = "vglib-mode-word-case4-samples-experimental-v3"
        const val MAX_ADDITIONAL_PAIRS = 16
        private fun inputWord(raw: Any?): Long {
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "Mode word input requires integer" }
            return VglibSnapshotCodec.requireUnsignedWord(raw, "modeWord input")
        }
        private fun tailSamples(raw: Any?): List<Long> {
            require(raw is List<*> && raw.size in 1..MAX_ADDITIONAL_PAIRS) { "Invalid mode word additional sample count" }
            return raw.map(::inputWord)
        }
        private fun helperPc(index:Int) = if(index%2==0) 0x5afe0L else 0x5af98L
        const val PORT="soc-vglib-init.mode-word"
        fun load(context:ComponentContext,file:String,continuous:Boolean=false,onWrite:((Long)->Unit)?=null):ModeWordInput? {
            if(file.isEmpty())return null
            require(file.isNotBlank()) {"mode word input file cannot be blank"}
            val data=context.readData(file)
            val profile = data["profile"]
            val extraKeys = when (profile) {
                PROFILE -> emptySet()
                TWO_PAIR_PROFILE -> setOf("secondReadSample")
                TAIL_PROFILE -> setOf("secondReadSample", "additionalReadSamples")
                else -> error("Unknown mode word input profile")
            }
            val commonKeys = setOf("schema", "profile", "readConfidence", "readSample", "sourcePreserveMask")
            require(data.keys == commonKeys + extraKeys && data["schema"] == 1 && data["readConfidence"] == "HYPOTHESIS") {
                "Invalid mode word input"
            }
            fun word(key: String) = inputWord(data[key])
            val mask = word("sourcePreserveMask")
            if (profile != PROFILE) require(mask == 0xfffffcffL) { "case4 mode word requires DAT mask" }
            val samples = buildList {
                add(word("readSample"))
                if (profile != PROFILE) add(word("secondReadSample"))
                if (profile == TAIL_PROFILE) addAll(tailSamples(data["additionalReadSamples"]))
            }
            return ModeWordInput(context, profile as String, samples, mask, continuous, onWrite)
        }
    }
}

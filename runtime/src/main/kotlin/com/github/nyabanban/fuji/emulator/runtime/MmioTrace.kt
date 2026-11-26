package com.github.nyabanban.fuji.emulator.runtime

/** Successful MMIO observations. Primitive ring; map construction belongs to host snapshots. */
class MmioTrace(val capacity:Int) {
    init {require(capacity in 1..2228224)}
    private val addresses=LongArray(capacity)
    private val values=LongArray(capacity)
    private val pcs=LongArray(capacity)
    private val ticks=LongArray(capacity)
    private val widths=IntArray(capacity)
    private val cores=IntArray(capacity)
    private val directions=BooleanArray(capacity)
    private var next=0
    var size=0;private set
    var observed=0L;private set
    fun record(write:Boolean,address:Long,width:Int,value:Long,pc:Long,tick:Long,core:Int) {
        addresses[next]=address;values[next]=value;pcs[next]=pc;ticks[next]=tick;widths[next]=width;cores[next]=core;directions[next]=write
        val n = next + 1
        next = if (n == capacity) 0 else n
        if(size<capacity)size++
        observed++
    }
    private fun row(offset:Int):Map<String,Any?> {
        val index=if(size<capacity)offset else (next+offset)%capacity
        val row=linkedMapOf<String,Any?>("kind" to if(directions[index])"write" else "read","addressPhysical" to addresses[index],"size" to widths[index],"value" to values[index],"pcVirtual" to pcs[index],"tick" to ticks[index])
        if(cores[index]>=0)row["core"]=cores[index]
        return row
    }
    fun toList():List<Map<String,Any?>> = (0 until size).map(::row)
    /** Bounded suffix of the existing ring, never a reconstructed or silently truncated prefix. */
    fun tail(after:Long,maximum:Int=4096):List<Map<String,Any?>> {
        require(maximum in 1..65536 && after>=0 && after<=observed && after>=observed-size) { "MMIO cursor no longer retained" }
        val count=observed-after
        require(count<=maximum) { "MMIO tail exceeds explicit comparison bound" }
        val start=Math.toIntExact(after-(observed-size))
        return (0 until count.toInt()).map { row(start+it) }
    }
    /** Owner-side observation only. Hashes retained suffix primitives; omitted records are never reconstructed. */
    fun retainedSuffix(after:Long):Map<String,Any?> {
        require(after in 0..observed) { "Invalid MMIO checkpoint cursor" }
        val retainedStart=observed-size
        val suffixStart=maxOf(after,retainedStart)
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        val buffer=java.nio.ByteBuffer.allocate(49).order(java.nio.ByteOrder.BIG_ENDIAN)
        for(absolute in suffixStart until observed) {
            val offset=Math.toIntExact(absolute-retainedStart)
            val index=if(size<capacity)offset else (next+offset)%capacity
            buffer.clear()
            buffer.putLong(absolute).putLong(addresses[index]).putLong(values[index]).putLong(pcs[index]).putLong(ticks[index])
                .putInt(widths[index]).putInt(cores[index]).put(if(directions[index])1.toByte() else 0.toByte())
            digest.update(buffer.array())
        }
        val tailStart=maxOf(suffixStart,observed-4096)
        return linkedMapOf("format" to "retained-mmio-suffix-v1","observed" to observed,"cursor" to after,
            "ringCapacity" to capacity,"ringRetainedStart" to retainedStart,"ringRetainedEndExclusive" to observed,
            "retainedSuffixStart" to suffixStart,"retainedSuffixEndExclusive" to observed,
            "suffixObservedCount" to observed-after,"omittedSuffixCount" to suffixStart-after,"truncated" to (suffixStart>after),
            "retainedSuffixSha256" to java.util.HexFormat.of().formatHex(digest.digest()),
            "digestEncoding" to "49 bytes per retained row in observed order: big-endian i64 absoluteIndex,address,value,pc,tick; i32 width,core(-1 absent); u8 write(0/1)",
            "digestScope" to "Only [retainedSuffixStart, retainedSuffixEndExclusive); excludes omitted suffix and pre-checkpoint records. Not a complete stream digest when truncated.",
            "tailRecordsStart" to tailStart,"tailRecordsOmitted" to tailStart-suffixStart,
            "tailRecords" to (tailStart until observed).map { row(Math.toIntExact(it-retainedStart)) })
    }
    fun saveSnapshot(maxRetained: Int = 4096): Map<String, Any?> {
        require(maxRetained in 1..65536) { "Invalid MmioTrace snapshot retention bound" }
        val retainedCount = minOf(size, maxRetained)
        val startOffset = size - retainedCount
        val records = (0 until retainedCount).map { offset -> row(startOffset + offset) }
        return linkedMapOf(
            "schema" to 1,
            "observed" to observed,
            "records" to records,
        )
    }
    fun restoreSnapshot(data: Map<*, *>) {
        require((data["schema"] as? Number)?.toInt() == 1) { "Unsupported MmioTrace snapshot schema" }
        val nextObserved = (data["observed"] as? Number)?.toLong() ?: error("Missing MmioTrace observed count")
        val rawRecords = data["records"] as? List<*> ?: error("Missing MmioTrace records")
        require(nextObserved >= rawRecords.size.toLong() && rawRecords.size <= capacity) {
            "Invalid MmioTrace snapshot counts"
        }
        val count = rawRecords.size
        val nextAddresses = LongArray(count)
        val nextValues = LongArray(count)
        val nextPcs = LongArray(count)
        val nextTicks = LongArray(count)
        val nextWidths = IntArray(count)
        val nextCores = IntArray(count) { -1 }
        val nextDirections = BooleanArray(count)
        rawRecords.forEachIndexed { index, raw ->
            val entry = raw as? Map<*, *> ?: error("Invalid MmioTrace record")
            val kind = entry["kind"] as? String ?: error("Missing MmioTrace record kind")
            require(kind == "read" || kind == "write") { "Invalid MmioTrace kind: $kind" }
            nextDirections[index] = kind == "write"
            nextAddresses[index] = (entry["addressPhysical"] as? Number)?.toLong() ?: error("Missing addressPhysical")
            nextWidths[index] = (entry["size"] as? Number)?.toInt() ?: error("Missing size")
            nextValues[index] = (entry["value"] as? Number)?.toLong() ?: error("Missing value")
            nextPcs[index] = (entry["pcVirtual"] as? Number)?.toLong() ?: error("Missing pcVirtual")
            nextTicks[index] = (entry["tick"] as? Number)?.toLong() ?: error("Missing tick")
            nextCores[index] = (entry["core"] as? Number)?.toInt() ?: -1
        }
        for (index in 0 until count) {
            addresses[index] = nextAddresses[index]
            values[index] = nextValues[index]
            pcs[index] = nextPcs[index]
            ticks[index] = nextTicks[index]
            widths[index] = nextWidths[index]
            cores[index] = nextCores[index]
            directions[index] = nextDirections[index]
        }
        size = count
        next = count % capacity
        observed = nextObserved
    }
}

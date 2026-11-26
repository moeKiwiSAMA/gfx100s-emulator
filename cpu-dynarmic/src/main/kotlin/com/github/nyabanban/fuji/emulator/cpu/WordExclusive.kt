package com.github.nyabanban.fuji.emulator.cpu

/** Serialized owner-thread logical A32 word (v1), word/halfword (v2), or byte/halfword/word (v3) exclusives. Explicit physical coherent granule hypothesis.
 * CPU/DMA writers MUST call written for every completed/intended qualified physical store, including
 * equal bytes. Native host reservations are never reused. CPU context changes preserve each slot;
 * IRQ exception entry calls clear for its core. Whole-machine snapshot rollback is unqualified.
 */
class WordExclusive private constructor(val granule: Int, val profile: String) {
    interface Cpu {
        /** Architectural register number r0..r15. */
        fun readRegister(index: Int): Long
        fun writeRegister(index: Int, value: Long)
        fun cpsr(): Long
    }
    enum class Access(val native: Int) { READ(1), WRITE(2) }
    fun interface Translation { fun physical(virtualAddress: Long, size: Int, access: Access): Long }
    interface Ram {
        /** Validate the whole exact 1/2/4-byte access in existing checked RAM, without expanding holes. */
        fun check(physicalAddress: Long, size: Int)
        fun readWord(physicalAddress: Long): Long
        /** On success, caller must use its ordinary checked host/DMA write path, which calls written. */
        fun writeWord(physicalAddress: Long, value: Long)
        fun readByte(physicalAddress: Long): Long = error("byte RAM read port not qualified")
        fun writeByte(physicalAddress: Long, value: Long): Unit = error("byte RAM write port not qualified")
        fun readHalfword(physicalAddress: Long): Long = error("halfword RAM read port not qualified")
        fun writeHalfword(physicalAddress: Long, value: Long): Unit = error("halfword RAM write port not qualified")
    }
    data class Event(val tick: Long, val core: Int, val virtualPc: Long, val word: Long,
                     val operation: String, val executed: Boolean,
                     val virtualAddress: Long? = null, val physicalAddress: Long? = null,
                     val value: Long? = null, val status: Int? = null, val size: Int = 4) {
        fun asMap(): Map<String,Any?> = linkedMapOf("tick" to tick,"core" to core,"virtualPc" to virtualPc,
            "word" to word,"operation" to operation,"executed" to executed,"virtualAddress" to virtualAddress,
            "physicalAddress" to physicalAddress,"size" to physicalAddress?.let {size},"value" to value,"status" to status)
    }
    private val reservations = LongArray(4) { -1 }
    private val reservationWidths = IntArray(4)
    private val byWidth = Array(3) { LongArray(3) } // indices 0/1 retain widths 2/4; index2=byte; loads/successes/failures
    private var epochLow = 0L
    private var epochHigh = 0L
    var loads = 0L; private set
    var successes = 0L; private set
    var failures = 0L; private set
    var clears = 0L; private set
    var invalidations = 0L; private set
    var eventCount = 0L; private set
    private val events = arrayOfNulls<Event>(256)
    private var eventCursor = 0

    fun profileCode(): Int = when (profile) {
        BYTE_PROFILE -> 2
        HALFWORD_PROFILE -> 1
        else -> 0
    }
    fun reservation(core: Int): Long? { require(core in 0..3); return reservations[core].takeIf { it >= 0 } }
    fun reservationSize(core: Int): Int? { require(core in 0..3); return reservationWidths[core].takeIf { reservations[core] >= 0 } }
    fun clear(core: Int) {
        require(core in 0..3)
        reservations[core] = -1
        reservationWidths[core] = 0
        clears = Math.addExact(clears, 1)
    }
    fun written(physicalAddress: Long, size: Long) {
        require(physicalAddress in 0..0xffffffffL && size >= 0 && size <= 0x100000000L - physicalAddress) { "exclusive write invalidation range invalid" }
        if (size == 0L) return
        require(epochHigh != -1L || epochLow != -1L) { "exclusive write epoch exhausted" }
        epochLow++; if(epochLow == 0L) epochHigh++
        val mask = granule.toLong() - 1
        val end = physicalAddress + size
        repeat(4) { core ->
            val address = reservations[core]
            if (address >= 0 && physicalAddress <= ((address + reservationWidths[core] - 1) or mask) && end > (address and mask.inv())) {
                reservations[core] = -1; reservationWidths[core] = 0; invalidations = Math.addExact(invalidations, 1)
            }
        }
    }
    fun execute(cpu: Cpu, word: Long, ram: Ram, translation: Translation?, tick: Long, core: Int) {
        require(core in 0..3 && tick >= 0 && word in 0..0xffffffffL)
        val cpsr = cpu.cpsr(); val pc = cpu.readRegister(15)
        require(cpsr and 0x220 == 0L) { "word exclusive requires qualified little-endian ARM state" }
        require(pc in 0..0xffffffffL && pc and 3 == 0L) { "unaligned A32 exclusive PC" }
        val operation = when {
            word == 0xf57ff01fL -> "clrex"
            word and 0x0ff00fffL == 0x01900f9fL -> "ldrex"
            word and 0x0ff00ff0L == 0x01800f90L -> "strex"
            profile != PROFILE && word and 0x0ff00fffL == 0x01f00f9fL -> "ldrexh"
            profile != PROFILE && word and 0x0ff00ff0L == 0x01e00f90L -> "strexh"
            profile == BYTE_PROFILE && word and 0x0ff00fffL == 0x01d00f9fL -> "ldrexb"
            profile == BYTE_PROFILE && word and 0x0ff00ff0L == 0x01c00f90L -> "strexb"
            else -> error("unqualified nonword/reserved exclusive instruction")
        }
        val size = when(operation) { "ldrexb", "strexb" -> 1; "ldrexh", "strexh" -> 2; else -> 4 }
        val storing = operation == "strex" || operation == "strexh" || operation == "strexb"
        val widthIndex = when(size) { 1 -> 2; 2 -> 0; else -> 1 }
        val widthMask = when(size) { 1 -> 0xffL; 2 -> 0xffffL; else -> 0xffffffffL }
        val executed = operation == "clrex" || condition(cpsr, (word ushr 28).toInt())
        var virtual: Long? = null; var physical: Long? = null; var value: Long? = null; var status: Int? = null
        if (executed) {
            if (operation == "clrex") clear(core)
            else {
                val rn = ((word ushr 16) and 15).toInt()
                val rd = ((word ushr 12) and 15).toInt()
                val rt = (word and 15).toInt()
                require(rn != 15 && rd != 15 && (!storing || (rt != 15 && rd != rn && rd != rt))) { "unpredictable word exclusive register" }
                virtual = cpu.readRegister(rn)
                require(virtual in 0..(0x100000000L - size) && virtual and (size - 1).toLong() == 0L) { "unaligned/out-of-range word exclusive access" }
                physical = translation?.physical(virtual, size, if (storing) Access.WRITE else Access.READ) ?: virtual
                require(physical in 0..(0x100000000L - size) && physical and (size - 1).toLong() == 0L) { "unaligned/out-of-range exclusive physical address" }
                ram.check(physical, size)
                if (!storing) {
                    value = when(size) { 1 -> ram.readByte(physical); 2 -> ram.readHalfword(physical); else -> ram.readWord(physical) }
                    require(value in 0..widthMask) { "exclusive read width/value differs" }
                    reservations[core] = physical
                    reservationWidths[core] = size
                    cpu.writeRegister(rd, value)
                    loads = Math.addExact(loads, 1)
                    byWidth[widthIndex][0] = Math.addExact(byWidth[widthIndex][0], 1)
                } else {
                    val success = reservations[core] == physical && reservationWidths[core] == size
                    val source = if (success) cpu.readRegister(rt) else null
                    require(source == null || source in 0..0xffffffffL) { "non-u32 exclusive source" }
                    reservations[core] = -1
                    reservationWidths[core] = 0
                    if (success) {
                        value = checkNotNull(source) and widthMask
                        when(size) { 1 -> ram.writeByte(physical, value); 2 -> ram.writeHalfword(physical, value); else -> ram.writeWord(physical, value) }
                        successes = Math.addExact(successes, 1)
                        byWidth[widthIndex][1] = Math.addExact(byWidth[widthIndex][1], 1)
                    } else { failures = Math.addExact(failures, 1); byWidth[widthIndex][2] = Math.addExact(byWidth[widthIndex][2], 1) }
                    status = if (success) 0 else 1
                    cpu.writeRegister(rd, status.toLong())
                }
            }
        }
        cpu.writeRegister(15, (pc + 4) and 0xffffffffL)
        check(cpu.cpsr() == cpsr) { "logical exclusive changed CPSR" }
        events[eventCursor] = Event(tick,core,pc,word,operation,executed,virtual,physical,value,status,size)
        eventCursor = (eventCursor + 1) and 255
        eventCount = Math.addExact(eventCount, 1)
    }
    fun recentEvents(): List<Event> {
        val count = minOf(eventCount, 256).toInt()
        val start = if(eventCount <= 256) 0 else eventCursor
        return List(count) { events[(start + it) and 255]!! }
    }
    fun state(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "profile" to profile, "specification" to mapOf("profile" to profile, "reservationGranule" to granule),
        "reservations" to List(4) { reservation(it)?.let { a -> mapOf("address" to a, "size" to reservationWidths[it]) } },
        "writeEpochHex" to java.lang.Long.toUnsignedString(epochHigh,16).padStart(16,'0') + java.lang.Long.toUnsignedString(epochLow,16).padStart(16,'0'),
        "loads" to loads, "successes" to successes, "failures" to failures, "clears" to clears,
        "invalidations" to invalidations, "events" to recentEvents().map {it.asMap()}, "eventCount" to eventCount,
        "eventLogTruncated" to (eventCount > 256),
        "basis" to "Pinned generic exclusive.rs / accepted Python word-only rules. Serialized checked physical RAM; granule unmeasured. All CPU and DMA stores must invalidate even same bytes. Native host reservations, nonword/Thumb/endian variants and snapshot rollback unqualified."
    ).also { state -> if (profile == HALFWORD_PROFILE) {
        state["bySize"] = listOf(2, 4).withIndex().associate { (index, width) -> width.toString() to mapOf("loads" to byWidth[index][0], "successes" to byWidth[index][1], "failures" to byWidth[index][2]) }
        state["basis"] = "Explicit v2 adds aligned A32 LDREXH/STREXH: exact physical address and width ownership, zero-extended load, low16 store. CPU/DMA/ABA/alias/IRQ/CLREX share the per-core monitor. Generic exclusive.rs monitor law is separate from the word-only R-profile decoder; no R-profile hardware inference. Granule/coherence remain HYP; byte/doubleword/Thumb/endian/snapshot rollback unqualified."
    } else if(profile == BYTE_PROFILE) {
        state["bySize"] = listOf(1 to 2, 2 to 0, 4 to 1).associate { (width,index) -> width.toString() to mapOf("loads" to byWidth[index][0], "successes" to byWidth[index][1], "failures" to byWidth[index][2]) }
        state["basis"] = "Explicit v3 adds A32 LDREXB/STREXB to v2: zero-extended exact byte load and low8 exact byte store, preserving adjacent bytes. Physical address and access width own each core monitor; CPU/DMA/same-value/ABA/alias/IRQ/CLREX invalidate it. Granule/coherence remain HYP; doubleword/Thumb/endian/snapshot rollback unqualified."
    } }
    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "schema" to 1,
        "profile" to profile,
        "granule" to granule,
        "reservations" to reservations.toList(),
        "reservationWidths" to reservationWidths.toList(),
        "epochHigh" to java.lang.Long.toUnsignedString(epochHigh, 16).padStart(16, '0'),
        "epochLow" to java.lang.Long.toUnsignedString(epochLow, 16).padStart(16, '0'),
        "loads" to loads,
        "successes" to successes,
        "failures" to failures,
        "clears" to clears,
        "invalidations" to invalidations,
        "byWidth" to byWidth.map { it.toList() },
    )
    fun restoreSnapshot(data: Map<*, *>) {
        require((data["schema"] as? Number)?.toInt() == 1) { "unsupported WordExclusive snapshot schema" }
        require(data["profile"] == profile && (data["granule"] as? Number)?.toInt() == granule) {
            "WordExclusive profile or granule differs"
        }
        val res = (data["reservations"] as? List<*>)?.map { (it as Number).toLong() } ?: error("missing reservations")
        val widths = (data["reservationWidths"] as? List<*>)?.map { (it as Number).toInt() } ?: error("missing reservationWidths")
        require(res.size == 4 && widths.size == 4) { "expected 4 core reservations" }
        for (i in 0..3) {
            val addr = res[i]; val w = widths[i]
            if (addr < 0L) {
                require(addr == -1L && w == 0) { "cleared reservation requires address=-1 and width=0" }
            } else {
                require(w in setOf(1, 2, 4) && addr <= 0x1_0000_0000L - w && (addr and (w - 1).toLong()) == 0L) {
                    "invalid active reservation on core $i"
                }
            }
        }
        val bw = data["byWidth"] as? List<*> ?: error("missing byWidth")
        require(bw.size == 3) { "expected 3 width buckets" }
        val parsedByWidth = Array(3) { row ->
            val list = bw[row] as? List<*> ?: error("invalid byWidth row $row")
            require(list.size == 3) { "expected 3 counters in byWidth row $row" }
            LongArray(3) { col -> (list[col] as Number).toLong().also { require(it >= 0L) } }
        }
        val highHex = data["epochHigh"] as? String ?: error("missing epochHigh")
        val lowHex = data["epochLow"] as? String ?: error("missing epochLow")
        require(highHex.length == 16 && lowHex.length == 16) { "invalid epoch hex width" }
        for (i in 0..3) { reservations[i] = res[i]; reservationWidths[i] = widths[i] }
        for (r in 0..2) parsedByWidth[r].copyInto(byWidth[r])
        epochHigh = java.lang.Long.parseUnsignedLong(highHex, 16)
        epochLow = java.lang.Long.parseUnsignedLong(lowHex, 16)
        loads = (data["loads"] as Number).toLong().also { require(it >= 0L) }
        successes = (data["successes"] as Number).toLong().also { require(it >= 0L) }
        failures = (data["failures"] as Number).toLong().also { require(it >= 0L) }
        clears = (data["clears"] as Number).toLong().also { require(it >= 0L) }
        invalidations = (data["invalidations"] as Number).toLong().also { require(it >= 0L) }
        events.fill(null); eventCursor = 0; eventCount = 0L
    }
    companion object {
        const val PROFILE = "arm-word-exclusive-logical-experimental-v1"
        const val HALFWORD_PROFILE = "arm-word-halfword-exclusive-logical-experimental-v2"
        const val BYTE_PROFILE = "arm-byte-halfword-word-exclusive-logical-experimental-v3"
        fun parse(raw: Map<*, *>): WordExclusive {
            require(raw.keys == setOf("profile", "reservationGranule") && raw["profile"] in setOf(PROFILE, HALFWORD_PROFILE, BYTE_PROFILE)) { "word exclusive profile/schema invalid" }
            val n = raw["reservationGranule"]
            require(n is Byte || n is Short || n is Int || n is Long) { "reservationGranule must be integer" }
            val granule = (n as Number).toLong()
            require(granule in 4..2048 && granule and (granule - 1) == 0L) { "explicit power-of-two reservation granule 4..2048 required" }
            return WordExclusive(granule.toInt(), raw["profile"] as String)
        }
        fun matches(word: Long) = word == 0xf57ff01fL || word and 0x0f8000f0L == 0x01800090L
        private fun condition(cpsr: Long, condition: Int): Boolean {
            val n=cpsr and 0x80000000L != 0L;val z=cpsr and 0x40000000L != 0L
            val c=cpsr and 0x20000000L != 0L;val v=cpsr and 0x10000000L != 0L
            return when(condition) {0->z;1->!z;2->c;3->!c;4->n;5->!n;6->v;7->!v;8->c&&!z;9->!c||z;10->n==v;11->n!=v;12->!z&&n==v;13->z||n!=v;14->true;else->error("unconditional exclusive encoding unqualified")}
        }
    }
}

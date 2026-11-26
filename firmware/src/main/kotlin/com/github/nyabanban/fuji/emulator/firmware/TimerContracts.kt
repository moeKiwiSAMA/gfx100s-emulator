package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only selected-DAT contracts. Channel7 -> GIC64 remains an explicit wiring hypothesis. */
object TimerContracts {
    private const val KERNEL_BASE = 0x40000L
    const val SECTION4_BASE = 0x1021000L
    private const val UINT_MAX = 0xffffffffL
    private class Words(private val data: ByteArray, private val base: Long) {
        fun at(address: Long): Long {
            require(address and 3L == 0L && address >= base && data.size >= 4 && address - base <= data.size - 4L) {
                "DAT timer instruction/literal outside initialized section at "+address.toString(16)
            }
            return ByteBuffer.wrap(data, (address - base).toInt(), 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and UINT_MAX
        }
    }
    private fun immediate(word: Long) = Integer.rotateRight((word and 255).toInt(), (((word ushr 8) and 15) * 2).toInt()).toLong() and UINT_MAX
    private fun imm16(word: Long) = (word and 0xfff) or ((word ushr 4) and 0xf000)
    private fun pair(words: Words, pc: Long, rd: Int): Long {
        val low = words.at(pc); val high = words.at(pc + 4)
        require(low and 0xfff0f000L == (0xe3000000L or (rd.toLong() shl 12)) && high and 0xfff0f000L == (0xe3400000L or (rd.toLong() shl 12))) { "DAT timer MOVW/MOVT register/opcode differs" }
        return imm16(low) or (imm16(high) shl 16)
    }
    private fun literal(words: Words, pc: Long, rd: Int): Pair<Long, Long> {
        val w = words.at(pc)
        require(w and 0xfffff000L == (0xe59f0000L or (rd.toLong() shl 12))) { "DAT timer literal consumer differs" }
        val address = pc + 8 + (w and 0xfff)
        return address to words.at(address)
    }
    private fun branch(words: Words, pc: Long, link: Boolean): Long {
        val w = words.at(pc)
        require(w and 0xff000000L == (if (link) 0xeb000000L else 0xea000000L)) { "DAT timer branch kind/condition differs" }
        return pc + 8 + (((w and 0xffffff).toInt() shl 8 shr 8).toLong() shl 2)
    }
    /** Unsigned 32-bit inputs/output with bounded 64-bit intermediate; no physical timebase claim. */
    fun ceilingCount(frequency: Long, argument: Long, divisor: Long): Long {
        require(frequency in 1..UINT_MAX && argument in 1..UINT_MAX && divisor in 1..UINT_MAX) { "invalid timer count conversion input" }
        require(frequency <= (Long.MAX_VALUE - (divisor - 1)) / argument) { "timer count conversion overflow" }
        val result = (frequency * argument + divisor - 1) / divisor
        require(result in 1..UINT_MAX) { "timer count outside 32-bit load register" }
        return result
    }
    private val kernelContracts = linkedMapOf(
        0x58e50L to 0xe3a01001L, 0x58e54L to 0xe3a00040L, 0x58e58L to 0xeb00079cL,
        0x58e5cL to 0xe3a01010L, 0x58e60L to 0xe3a00040L, 0x58e64L to 0xeb00073fL,
        0x58e68L to 0xe3a01003L, 0x58e6cL to 0xe3a00040L, 0x58e70L to 0xeb000753L,
        0x58e74L to 0xe3a00040L, 0x58e78L to 0xeb000774L,
        0x58e80L to 0xe3a00007L, 0x58e84L to 0xeb00064cL,
        0x58e98L to 0xe3a00007L, 0x58e9cL to 0xeb000713L,
        0x58ea0L to 0xe3a02000L, 0x58ea4L to 0xe3a0100aL, 0x58ea8L to 0xe3a00007L, 0x58eacL to 0xeb000713L,
        0x5a7ccL to 0xe3500007L, 0x5a7d4L to 0xe3073862L, 0x5a7d8L to 0xe3413ffeL,
        0x5a7dcL to 0xe0803003L, 0x5a7e0L to 0xe1a00183L,
        0x5ab34L to 0xe1a03002L, 0x5ab38L to 0xe1a04000L, 0x5ab3cL to 0xe24b2010L,
        0x5ab40L to 0xe1a00003L, 0x5ab44L to 0xebffff75L, 0x5ab50L to 0xe51b1010L,
        0x5ab54L to 0xe1a00004L, 0x5ab58L to 0xebffff3eL,
        0x5a934L to 0x979ff100L, 0x5a93cL to 0x5a960L, 0x5a968L to 0xe3a03ffaL,
        0x5a9b0L to 0xe1a02003L, 0x5a9b4L to 0xe3a03000L,
        0x5a9b8L to 0xe30f0980L, 0x5a9bcL to 0xe3400337L,
        0x5a9c0L to 0xe1a06002L, 0x5a9c4L to 0xe1a07003L, 0x5a9c8L to 0xe0a76190L,
        0x5a9ccL to 0xe2560001L, 0x5a9d0L to 0xe2c71000L, 0x5a9d4L to 0xeb000231L,
        0x5a868L to 0xe1a05001L, 0x5a86cL to 0xe3074862L, 0x5a870L to 0xe3414ffeL, 0x5a874L to 0xe0804004L, 0x5a878L to 0xe1a04184L,
        0x5a87cL to 0xe1a00004L, 0x5a880L to 0xeb0001c2L, 0x5a884L to 0xe1a01005L, 0x5a888L to 0xe1a00004L, 0x5a88cL to 0xeb0001bfL,
        0x5a890L to 0xe1a01005L, 0x5a894L to 0xe1a00004L, 0x5a898L to 0xeb0001bcL,
        0x5a89cL to 0xe2844004L, 0x5a8a0L to 0xe2455001L, 0x5a8a4L to 0xe1a01005L, 0x5a8a8L to 0xe1a00004L, 0x5a8acL to 0xeb0001b7L,
        0x5a8b0L to 0xe1a01005L, 0x5a8b4L to 0xe1a00004L, 0x5a8b8L to 0xeb0001b4L, 0x5a8bcL to 0xe1a01005L, 0x5a8c0L to 0xe1a00004L, 0x5a8c4L to 0xeb0001b1L,
        0x58d98L to 0xeaffffceL, 0x58cd8L to 0xeaff9d81L,
        0x58d18L to 0xe3a00040L, 0x58d1cL to 0xe1540000L, 0x58d24L to 0xebff9df4L,
        0x4051cL to 0xe59f10acL, 0x40524L to 0xe2800001L, 0x40528L to 0xe5810000L,
        0x40530L to 0xe59f109cL, 0x40538L to 0xe2800001L, 0x4053cL to 0xe5810000L,
        0x405acL to 0xe59f101cL, 0x405b4L to 0xe2400001L, 0x405b8L to 0xe5810000L,
        0x45e98L to 0xe30c3104L, 0x45e9cL to 0xe3403005L, 0x45ea0L to 0xe5834000L,
        0x56b20L to 0xe1fc8f9fL, 0x56b2cL to 0xe1ec9f91L
    )
    private val applicationContracts = linkedMapOf(
        0x11f7d58L to 0xe3a06c86L, 0x11f7d5cL to 0xe34f6ff4L,
        0x11f7d60L to 0xe1a00006L, 0x11f7d64L to 0xebb98c9bL
    )
    fun observe(image: FirmwareImage): Map<String, Any> {
        require(image.metadata.id == "gfx100s-213" && image.metadata.sections.single { it.name == "kernel" }.address == KERNEL_BASE) { "timer contracts require selected GFX100S DAT layout" }
        val base = image.metadata.sections.single { it.name == "section4" }.address
        return observe(image.decode("kernel"), image.decode("section4"), base) + mapOf("inputIdentityVerified" to true, "inputSha256" to image.metadata.sha256)
    }
    fun observe(kernel: ByteArray, section4: ByteArray, section4Base: Long = SECTION4_BASE): Map<String, Any> {
        require(section4Base == SECTION4_BASE) { "DAT timer application base differs" }
        val k = Words(kernel, KERNEL_BASE); val app = Words(section4, section4Base)
        val checked = ArrayList<Map<String, Any>>()
        fun check(words: Words, contracts: Map<Long, Long>) { contracts.forEach { (pc, expected) ->
            val actual = words.at(pc)
            require(actual == expected) { "DAT timer word differs at "+pc.toString(16) }
            checked.add(mapOf("address" to pc, "expected" to expected, "actual" to actual, "matches" to true))
        } }
        check(k, kernelContracts); check(app, applicationContracts)
        val base = pair(k, 0x5a7d4, 3)
        val channel = immediate(k.at(0x58e80))
        val address = ((base + channel) shl 3) and UINT_MAX
        val line = immediate(k.at(0x58e54))
        val gicCalls = listOf(Triple(0x58e58L, 0x58e50L, 0x5acd0L), Triple(0x58e64L, 0x58e5cL, 0x5ab68L), Triple(0x58e70L, 0x58e68L, 0x5abc4L)).map { (pc, arg, target) ->
            require(branch(k, pc, true) == target) { "DAT timer GIC initialization target differs" }
            mapOf("callsite" to pc, "target" to target, "r0" to line, "r1" to immediate(k.at(arg)))
        }
        require(branch(k, 0x58e78, true) == 0x5ac50L && branch(k, 0x58e84, true) == 0x5a7bcL && branch(k, 0x58eac, true) == 0x5ab00L)
        require(branch(k, 0x5ab44, true) == 0x5a920L && branch(k, 0x5ab58, true) == 0x5a858L && branch(k, 0x5a9d4, true) == 0x5b2a0L)
        val frequency = pair(k, 0x5a9b8, 0)
        val argument = immediate(k.at(0x58ea4)); val divisor = immediate(k.at(0x5a968))
        val count = ceilingCount(frequency, argument, divisor)
        val mode = immediate(k.at(0x58ea0))
        require(mode == 0L && k.at(0x5a93c + mode * 4) == 0x5a960L)
        val activity = literal(k, 0x4051c, 1); val ticks = literal(k, 0x40530, 1)
        val activityExit = literal(k, 0x405ac, 1)
        require(activity.second == 0x5bc04L && ticks.second == 0x5c104L && activityExit.second == activity.second && pair(k, 0x45e98, 3) == ticks.second) { "DAT timer counter pointer differs" }
        require(address == 0xfff3c348L && channel == 7L && line == 64L && frequency == 54_000_000L && argument == 10L && divisor == 1000L && count == 540_000L)
        require(branch(k, 0x58d98, false) == 0x58cd8L && branch(k, 0x58cd8, false) == 0x402e4L && branch(k, 0x58d24, true) == 0x404fcL)
        val low = immediate(app.at(0x11f7d58)); val high = imm16(app.at(0x11f7d5c))
        val next = (low and 0xffff) or (high shl 16)
        require(next == 0xfff48600L && branch(app, 0x11f7d64, true) == 0x5afd8L)
        val load = k.at(0x56b20); val store = k.at(0x56b2c)
        val pointers = mapOf("activity" to activity.second, "ticks" to ticks.second)
        val literalWords = listOf(activity.first to activity.second, ticks.first to ticks.second).map { (pc, w) -> mapOf("address" to pc, "actual" to w) }
        return linkedMapOf(
            "schema" to 1, "evidenceClass" to "DAT / SOURCE / HYPOTHESIS", "matches" to true,
            "inputIdentityVerified" to false, "runtimeInjected" to false,
            "contracts" to checked, "literalWords" to literalWords, "checkedInstructions" to kernelContracts.keys.toList() + applicationContracts.keys.toList(),
            "addressProducer" to mapOf("base" to base, "channel" to channel, "leftShift" to 3, "computedWordAddress" to address),
            "gicInitializers" to gicCalls,
            "countConversion" to mapOf("nominalConstant" to frequency, "argument" to argument, "mode" to mode, "divisor" to divisor, "ceilingCount" to count, "load" to count, "reload" to count - 1, "reloadAddress" to address + 4, "scope" to "DAT arithmetic only; not a measured clock rate or calibrated timebase"),
            "tickCounterPointers" to pointers,
            "irqPath" to mapOf("vector" to 0x58d98L, "vectorTarget" to 0x58cd8L, "dispatcher" to 0x402e4L, "specialLine" to immediate(k.at(0x58d18)), "tickCallsite" to 0x58d24L, "tickTarget" to 0x404fcL),
            "halfwordConsumers" to mapOf("load" to 0x56b20L, "store" to 0x56b2cL, "bytes" to 2, "baseRegister" to ((load ushr 16) and 15), "loadTargetRegister" to ((load ushr 12) and 15), "storeStatusRegister" to ((store ushr 12) and 15), "storeValueRegister" to (store and 15)),
            "nextApplicationMmio" to mapOf("virtualCallsite" to 0x11f7d64L, "computedAddress" to next, "readHelper" to 0x5afd8L, "scope" to "Application consumer only; access remains independently modeled or terminal"),
            "routeCandidate" to mapOf("channel" to channel, "line" to line, "evidenceClass" to "HYPOTHESIS", "physicalWiringMeasured" to false, "enabled" to false),
            "limitation" to "Read-only selected-DAT words/fields; channel7->64 wiring, pulse shape and instruction timebase are explicit HYP, not measurements. This report injects no route or hardware state and proves no original ISR/return, task, display, boot or REC acceptance"
        )
    }
}

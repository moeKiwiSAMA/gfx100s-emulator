package com.github.nyabanban.fuji.emulator.cpu

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * ARMv7-A CPU execution engine selector.
 *
 * - [DYNARMIC]: Native Dynarmic ARMv7-A + VFPv4 + NEON -> x86-64/AVX2 Fastmem JIT backend.
 * - [AUTO]: Resolves to [DYNARMIC].
 */
enum class CpuEngineKind(val configName: String) {
    DYNARMIC("dynarmic"),
    AUTO("auto"),
    ;

    companion object {
        @JvmStatic
        fun parse(raw: Any?): CpuEngineKind {
            if (raw == null) return DYNARMIC
            require(raw is String) { "cpuEngine requires string (dynarmic | auto)" }
            return when (raw.trim().lowercase()) {
                "", "dynarmic" -> DYNARMIC
                "auto" -> AUTO
                else -> throw IllegalArgumentException("Unknown cpuEngine '$raw' (expected dynarmic | auto)")
            }
        }
    }
}

/**
 * Architectural ARMv7-A register identifier constants for [ArmCpuBackend.register].
 */
object ArmReg {
    const val FPEXC: Int = 4
    const val FPSCR: Int = 6
    const val LR: Int = 10
    const val PC: Int = 11
    const val SP: Int = 12
    const val SPSR: Int = 13
    const val D0: Int = 14
    const val CPSR: Int = 25
    const val R0: Int = 66
}

/**
 * Physical MMIO and execution observer callback contract for [Dynarmic].
 */
interface ArmCpuObserver {
    fun instruction(address: Long, size: Int) {}
    fun read(address: Long, size: Int): Long
    fun write(address: Long, size: Int, value: Long)
    fun invalid(kind: Int, address: Long, size: Int, value: Long) {}
    fun exception(number: Int) {}
}

/**
 * Full 4-core ARM cluster architectural snapshot envelope.
 */
data class ArmClusterSnapshot(
    val metadata: Map<String, Any?>,
    val coreSnapshots: List<Map<String, Any?>> = emptyList(),
)

/**
 * Common ARMv7-A CPU backend contract implemented by [Dynarmic].
 */
interface ArmCpuBackend : AutoCloseable {
    val engineKind: CpuEngineKind

    fun map(address: Long, size: Long, permissions: Int)
    fun unmap(address: Long, size: Long)
    fun mmio(base: Long, size: Long)

    fun writeMemory(address: Long, bytes: ByteArray)
    fun writeMemory(address: Long, bytes: ByteArray, offset: Int, size: Int, flush: Boolean)
    fun writeMemory(address: Long, source: ByteBuffer, flush: Boolean)

    fun readMemory(address: Long, size: Int): ByteArray
    fun readMemoryInto(address: Long, dest: ByteArray, destOffset: Int, size: Int)
    fun readMemoryInto(address: Long, dest: ByteBuffer)
    fun hashMemory(address: Long, size: Long, digest: MessageDigest)

    fun physicalWord(address: Long): Long
    fun writePhysicalWord(address: Long, value: Long)
    fun physicalHalfword(address: Long): Long
    fun writePhysicalHalfword(address: Long, value: Long)
    fun physicalByte(address: Long): Long
    fun writePhysicalByte(address: Long, value: Long)

    fun register(register: Int): Long
    fun register(register: Int, value: Long)
    fun readCp(cp: Int, opc1: Int, crn: Int, crm: Int, opc2: Int): Long
    fun writeCp(cp: Int, opc1: Int, crn: Int, crm: Int, opc2: Int, value: Long)

    fun translate(virtualAddress: Long, access: Int): Long
    fun flushBlocks()
    fun flushTlb()
    fun stop()
}

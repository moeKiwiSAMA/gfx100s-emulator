package com.github.nyabanban.fuji.emulator.cpu

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-JVM Windows PE32+ (COFF x86_64) export and import table inspector.
 *
 * Used to verify cross-compiled MinGW-w64 `fuji_dynarmic.dll` binaries
 * without requiring Wine or native Windows tooling on Linux/macOS CI hosts.
 */
object Win64PeCoffInspector {
    /** PE COFF machine code for AMD64 / x86_64 (`IMAGE_FILE_MACHINE_AMD64`). */
    const val MACHINE_AMD64: Int = 0x8664

    /** PE32+ Optional Header magic (`IMAGE_NT_OPTIONAL_HDR64_MAGIC`). */
    const val OPTIONAL_MAGIC_PE32_PLUS: Int = 0x020B

    /** Lowercase Windows OS system DLLs allowed in the import table of a self-contained MinGW DLL. */
    val ALLOWED_SYSTEM_IMPORTS: Set<String> = setOf(
        "kernel32.dll",
        "msvcrt.dll",
        // Windows Universal CRT imports emitted by the LLVM-MinGW UCRT toolchain.
        "ucrtbase.dll",
        "api-ms-win-crt-convert-l1-1-0.dll",
        "api-ms-win-crt-environment-l1-1-0.dll",
        "api-ms-win-crt-filesystem-l1-1-0.dll",
        "api-ms-win-crt-heap-l1-1-0.dll",
        "api-ms-win-crt-locale-l1-1-0.dll",
        "api-ms-win-crt-math-l1-1-0.dll",
        "api-ms-win-crt-multibyte-l1-1-0.dll",
        "api-ms-win-crt-private-l1-1-0.dll",
        "api-ms-win-crt-runtime-l1-1-0.dll",
        "api-ms-win-crt-stdio-l1-1-0.dll",
        "api-ms-win-crt-string-l1-1-0.dll",
        "api-ms-win-crt-time-l1-1-0.dll",
        "advapi32.dll",
        "user32.dll",
        "ntdll.dll",
        "ws2_32.dll",
    )

    /** Parsed section header mapping RVA ranges to raw file offsets. */
    data class Section(
        val name: String,
        val virtualAddress: Long,
        val virtualSize: Long,
        val rawPointer: Long,
        val rawSize: Long,
    )

    /** Parsed PE32+ DLL header, export symbol table, and imported DLL list. */
    data class PeDllSummary(
        val machine: Int,
        val optionalMagic: Int,
        val sections: List<Section>,
        val exports: Set<String>,
        val imports: Set<String>,
    )

    /** Verification result checking required exports and forbidden external MinGW runtime DLLs. */
    data class VerificationResult(
        val summary: PeDllSummary,
        val missingExports: Set<String>,
        val forbiddenImports: Set<String>,
    ) {
        /** True when machine is AMD64 PE32+, every required export is present, and no MinGW runtime DLLs are imported. */
        val isValid: Boolean
            get() = summary.machine == MACHINE_AMD64 &&
                summary.optionalMagic == OPTIONAL_MAGIC_PE32_PLUS &&
                missingExports.isEmpty() &&
                forbiddenImports.isEmpty()
    }

    /**
     * Parses a Windows PE32+ DLL from [path].
     *
     * @param path path to the `.dll` binary
     * @return parsed [PeDllSummary]
     * @throws IllegalArgumentException if the file is not a valid PE32+ image
     */
    fun inspect(path: Path): PeDllSummary = inspect(Files.readAllBytes(path))

    /**
     * Parses a Windows PE32+ DLL from raw [bytes].
     *
     * @param bytes raw PE file contents
     * @return parsed [PeDllSummary]
     * @throws IllegalArgumentException if the byte buffer is not a valid PE32+ image
     */
    fun inspect(bytes: ByteArray): PeDllSummary {
        require(bytes.size >= 0x40) { "PE image too small (${bytes.size} bytes)" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dosMagic = buf.getShort(0).toInt() and 0xFFFF
        require(dosMagic == 0x5A4D) { "Invalid DOS MZ header: 0x${dosMagic.toString(16)}" }

        val lfanew = buf.getInt(0x3C)
        require(lfanew >= 0 && lfanew + 24 <= bytes.size) { "Invalid e_lfanew offset: $lfanew" }
        val peSig = buf.getInt(lfanew)
        require(peSig == 0x00004550) { "Invalid PE signature: 0x${peSig.toString(16)}" }

        val coffOff = lfanew + 4
        val machine = buf.getShort(coffOff).toInt() and 0xFFFF
        val numSections = buf.getShort(coffOff + 2).toInt() and 0xFFFF
        val optHeaderSize = buf.getShort(coffOff + 16).toInt() and 0xFFFF
        val optOff = coffOff + 20
        require(optOff + optHeaderSize <= bytes.size && optHeaderSize >= 112) {
            "Truncated PE optional header (size=$optHeaderSize)"
        }

        val optMagic = buf.getShort(optOff).toInt() and 0xFFFF
        require(optMagic == OPTIONAL_MAGIC_PE32_PLUS) {
            "Expected PE32+ optional magic 0x020B, found 0x${optMagic.toString(16)}"
        }

        val numDataDirs = buf.getInt(optOff + 108)
        val dataDirsOff = optOff + 112
        val sectionsOff = optOff + optHeaderSize
        require(sectionsOff + numSections * 40 <= bytes.size) { "Truncated PE section table" }

        val sections = buildList(numSections) {
            for (i in 0 until numSections) {
                val off = sectionsOff + i * 40
                val rawName = bytes.copyOfRange(off, off + 8)
                val nul = rawName.indexOf(0.toByte()).let { if (it < 0) 8 else it }
                val name = String(rawName, 0, nul, StandardCharsets.US_ASCII)
                val vSize = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL
                val vAddr = buf.getInt(off + 12).toLong() and 0xFFFFFFFFL
                val rSize = buf.getInt(off + 16).toLong() and 0xFFFFFFFFL
                val rPtr = buf.getInt(off + 20).toLong() and 0xFFFFFFFFL
                add(Section(name, vAddr, vSize, rPtr, rSize))
            }
        }

        fun rvaToOffset(rva: Long): Int? {
            if (rva == 0L) return null
            for (sec in sections) {
                val span = maxOf(sec.virtualSize, sec.rawSize)
                if (rva >= sec.virtualAddress && rva < sec.virtualAddress + span) {
                    val off = sec.rawPointer + (rva - sec.virtualAddress)
                    if (off in 0 until bytes.size.toLong()) return off.toInt()
                }
            }
            return null
        }

        fun readCStringAtRva(rva: Long): String? {
            val start = rvaToOffset(rva) ?: return null
            var end = start
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            return String(bytes, start, end - start, StandardCharsets.US_ASCII)
        }

        val exports = linkedSetOf<String>()
        if (numDataDirs > 0 && dataDirsOff + 8 <= bytes.size) {
            val expRva = buf.getInt(dataDirsOff).toLong() and 0xFFFFFFFFL
            val expOff = rvaToOffset(expRva)
            if (expOff != null && expOff + 40 <= bytes.size) {
                val numNames = buf.getInt(expOff + 24)
                val namesRva = buf.getInt(expOff + 32).toLong() and 0xFFFFFFFFL
                val namesOff = rvaToOffset(namesRva)
                if (namesOff != null && numNames > 0) {
                    for (i in 0 until numNames) {
                        val ptrOff = namesOff + i * 4
                        if (ptrOff + 4 > bytes.size) break
                        val symRva = buf.getInt(ptrOff).toLong() and 0xFFFFFFFFL
                        readCStringAtRva(symRva)?.let { exports.add(it) }
                    }
                }
            }
        }

        val imports = linkedSetOf<String>()
        if (numDataDirs > 1 && dataDirsOff + 16 <= bytes.size) {
            val impRva = buf.getInt(dataDirsOff + 8).toLong() and 0xFFFFFFFFL
            var cur = rvaToOffset(impRva)
            while (cur != null && cur + 20 <= bytes.size) {
                val origFirstThunk = buf.getInt(cur)
                val nameRva = buf.getInt(cur + 12).toLong() and 0xFFFFFFFFL
                val firstThunk = buf.getInt(cur + 16)
                if (origFirstThunk == 0 && nameRva == 0L && firstThunk == 0) break
                readCStringAtRva(nameRva)?.let { imports.add(it) }
                cur += 20
            }
        }

        return PeDllSummary(
            machine = machine,
            optionalMagic = optMagic,
            sections = sections,
            exports = exports,
            imports = imports,
        )
    }
}

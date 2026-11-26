package com.github.nyabanban.fuji.emulator.desktop.input

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Panama FFM binding to `libfuji_gamepad.so` / `fuji_gamepad.dll` (ABI v1, `tools/native/gamepad-bridge/fuji_gamepad.h`).
 * Use [tryLoad]; any missing library, ABI mismatch or backend failure degrades to [UnavailableGamepadBackend] with a
 * single `WARN [FALLBACK][gamepad]`.
 */
class NativeGamepadBridge private constructor(
    override val name: String,
    val libraryPath: Path,
    private val fnPoll: MethodHandle,
    private val fnShutdown: MethodHandle,
) : GamepadBackend {
    private val bufferArena = Arena.ofShared()
    private val buffer: MemorySegment = bufferArena.allocate(STATE_SIZE * MAX_SLOTS, 8)
    @Volatile private var closed = false

    override fun poll(): List<GamepadFrame> {
        check(!closed) { "Native gamepad bridge closed" }
        val rc = fnPoll.invokeExact(buffer, MAX_SLOTS.toInt()) as Int
        check(rc >= 0) { "fuji_gamepad_poll returned $rc" }
        return (0 until MAX_SLOTS.toInt()).map { slot -> readState(buffer.asSlice(slot * STATE_SIZE, STATE_SIZE), slot) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { fnShutdown.invokeExact() }
        runCatching { bufferArena.close() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NativeGamepadBridge::class.java)
        const val EXPECTED_ABI_VERSION: Int = 1
        const val STATE_SIZE: Long = 176L
        const val MAX_SLOTS: Long = 4L
        private const val OFF_CONNECTED = 0x00L
        private const val OFF_SLOT = 0x04L
        private const val OFF_BUTTONS = 0x08L
        private const val OFF_LX = 0x10L
        private const val OFF_LY = 0x14L
        private const val OFF_RX = 0x18L
        private const val OFF_RY = 0x1CL
        private const val OFF_LT = 0x20L
        private const val OFF_RT = 0x24L
        private const val OFF_NAME = 0x30L
        private const val NAME_BYTES = 128

        internal fun readState(segment: MemorySegment, slot: Int): GamepadFrame {
            val connected = segment.get(ValueLayout.JAVA_INT, OFF_CONNECTED) != 0
            if (!connected) return GamepadFrame(connected = false, slot = slot)
            fun f(offset: Long) = segment.get(ValueLayout.JAVA_FLOAT, offset).toDouble()
            val nameBytes = ByteArray(NAME_BYTES)
            var length = 0
            while (length < NAME_BYTES) {
                val b = segment.get(ValueLayout.JAVA_BYTE, OFF_NAME + length)
                if (b.toInt() == 0) break
                nameBytes[length++] = b
            }
            return GamepadFrame(
                connected = true,
                slot = segment.get(ValueLayout.JAVA_INT, OFF_SLOT).takeIf { it in 0 until MAX_SLOTS } ?: slot,
                name = String(nameBytes, 0, length, StandardCharsets.UTF_8),
                buttons = segment.get(ValueLayout.JAVA_INT, OFF_BUTTONS) and 0xFFFF,
                lx = f(OFF_LX), ly = f(OFF_LY), rx = f(OFF_RX), ry = f(OFF_RY), lt = f(OFF_LT), rt = f(OFF_RT),
            )
        }

        fun libraryFileName(): String =
            if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) "fuji_gamepad.dll" else "libfuji_gamepad.so"

        fun candidatePaths(home: Path? = null): List<Path> {
            val libName = libraryFileName()
            val paths = ArrayList<Path>()
            System.getProperty("fuji.gamepad.library")?.takeIf { it.isNotBlank() }?.let { paths.add(Path.of(it)) }
            System.getenv("FUJI_GAMEPAD_LIBRARY")?.takeIf { it.isNotBlank() }?.let { paths.add(Path.of(it)) }
            val roots = buildList {
                if (home != null) add(home.toAbsolutePath().normalize())
                add(Path.of("").toAbsolutePath().normalize())
            }.distinct()
            val relative = listOf(
                "runtime/native/$libName",
                "lib/$libName",
                libName,
                ".tools/gamepad-bridge/build-linux/$libName",
                ".tools/gamepad-bridge/build-win64/$libName",
            )
            roots.forEach { root -> relative.forEach { paths.add(root.resolve(it)) } }
            return paths.distinct()
        }

        fun tryLoad(home: Path? = null): GamepadBackend {
            val existing = candidatePaths(home).firstOrNull { Files.isRegularFile(it) }
            if (existing == null) {
                log.warn("[FALLBACK][gamepad] Native gamepad bridge {} not found; gamepad input unavailable (keyboard shortcuts still work)", libraryFileName())
                return UnavailableGamepadBackend
            }
            return try {
                val lookup = SymbolLookup.libraryLookup(existing, Arena.ofAuto())
                val linker = Linker.nativeLinker()
                fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle =
                    linker.downcallHandle(lookup.find(name).orElseThrow { UnsatisfiedLinkError("Missing symbol $name in $existing") }, descriptor)
                val abi = bind("fuji_gamepad_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact() as Int
                if (abi != EXPECTED_ABI_VERSION) {
                    log.warn("[FALLBACK][gamepad] Ignoring {} with ABI {} (expected {})", existing, abi, EXPECTED_ABI_VERSION)
                    return UnavailableGamepadBackend
                }
                val init = bind("fuji_gamepad_init", FunctionDescriptor.of(ValueLayout.JAVA_INT))
                val poll = bind("fuji_gamepad_poll", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
                val shutdown = bind("fuji_gamepad_shutdown", FunctionDescriptor.ofVoid())
                val backendName = bind("fuji_gamepad_backend_name", FunctionDescriptor.of(ValueLayout.ADDRESS))
                val rc = init.invokeExact() as Int
                if (rc != 0) {
                    log.warn("[FALLBACK][gamepad] {} could not initialize a gamepad backend (rc={}); gamepad input unavailable", existing, rc)
                    runCatching { shutdown.invokeExact() }
                    return UnavailableGamepadBackend
                }
                val namePtr = backendName.invokeExact() as MemorySegment
                val name = if (namePtr == MemorySegment.NULL) "native" else namePtr.reinterpret(64L).getString(0L, StandardCharsets.US_ASCII)
                log.info("Loaded native gamepad bridge {} (backend={})", existing, name)
                NativeGamepadBridge(name, existing, poll, shutdown)
            } catch (error: Throwable) {
                log.warn("[FALLBACK][gamepad] Native gamepad bridge {} unavailable: {}", existing, error.message ?: error.javaClass.simpleName)
                UnavailableGamepadBackend
            }
        }
    }
}

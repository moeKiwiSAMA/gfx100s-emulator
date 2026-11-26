package com.github.nyabanban.fuji.emulator.desktop.session

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.MmioMirrorHandle
import com.github.nyabanban.fuji.emulator.api.NativeMmioMirrorPort
import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice
import com.github.nyabanban.fuji.emulator.cpu.Dynarmic
import com.github.nyabanban.fuji.emulator.firmware.DeviceWindow
import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.ByteOrder

/**
 * Zero-allocation coordinator for Dynarmic C++ native MMIO read fastpaths in `relaxed` mode:
 * - `soc-timer` live counter shadow (`0xFFF3C300..0xFFF3C34F`)
 * - `soc-gpio` 16-bank RO-MMIO shadow (auto-invalidated on writes and button/input epoch changes)
 * - `soc-configuration` lazy 2nd-read RO-MMIO cache (O(1) per-group invalidation on writes)
 * - `soc-adc` lazy static/idle RO-MMIO cache (auto-cleared on writes, deadlines, and button changes)
 */
internal class NativeMmioShadowCoordinator {
    var fastMmioHits: Long = 0L

    var timerShadowEnabled: Boolean = false
        private set
    var timerIrqShadowEnabled: Boolean = false
        private set
    var timerDevice: TickAwareMmioDevice? = null
        private set
    private var timerBase: Long = 0L
    private var timerEnd: Long = 0L
    private var timerVersionCell: LongArray? = null
    private var timerCopyHandle: MethodHandle? = null
    private var timerApplyInlineHandle: MethodHandle? = null
    private var timerBindPullHookHandle: MethodHandle? = null
    private var timerShadowSeg: MemorySegment? = null
    private var timerIrqShadowSeg: MemorySegment? = null
    private var timerLastVersion: Long = Long.MIN_VALUE
    private var lastNextOtherDeadline: Long = Long.MAX_VALUE
    private val timerStored = LongArray(8)
    private val timerReload = LongArray(8)
    private val timerDeadline = LongArray(8)
    private val timerEnabledArr = IntArray(8)
    private val timerHasDeadlineArr = IntArray(8)
    private val timerRoutesArr = IntArray(8) { -1 }
    private val timerPullDeadlines = LongArray(8)
    private val timerPullFired = LongArray(8)

    var gpioRoEnabled: Boolean = false
        private set
    private var gpioBase: Long = 0L
    private var gpioEnd: Long = 0L
    private var gpioPort: MmioDevice? = null
    private var gpioEpochCell: LongArray? = null
    private var gpioCopyInputsHandle: MethodHandle? = null
    private var gpioCopyAllHandle: MethodHandle? = null
    private var gpioLastEpoch: Long = Long.MIN_VALUE
    private var gpioInputsDirty: Boolean = false
    private val gpioInputsBuf = IntArray(16)
    private val gpioInputAddrs = IntArray(16)
    private val gpioAllBuf = IntArray(128)
    private val gpioAllAddrs = IntArray(128)

    var gicDistRoEnabled: Boolean = false
        private set
    private var gicDistBase: Long = 0L
    private var gicDistEnd: Long = 0L
    private val gicCachedAddrs = LongArray(128)
    private var gicCachedCount: Int = 0

    var gicShadowEnabled: Boolean = false
        private set
    private var gicCpuBase: Long = 0L
    private var gicCpuEnd: Long = 0L
    private var gicShadowArena: Arena? = null
    private var gicVersionCell: LongArray? = null
    private var gicBindHandle: MethodHandle? = null
    private var gicSyncHandle: MethodHandle? = null
    private var gicPullHandle: MethodHandle? = null
    private var gicClearNativeHandle: MethodHandle? = null
    private var gicLastVersion: Long = Long.MIN_VALUE

    var ispRoRangeCount: Int = 0
        private set
    private var ispBindStatsShadowHandle: MethodHandle? = null

    var configRoEnabled: Boolean = false
        private set
    private var configRanges: LongArray = LongArray(0)
    private var configBusyCell: IntArray? = null
    private val configCachedAddrs = LongArray(64)
    private var configCachedCount: Int = 0
    private var configLastReadAddr: Long = -1L

    var adcRoEnabled: Boolean = false
        private set
    private var adcBase: Long = 0L
    private var adcEnd: Long = 0L
    private var adcDevice: TickAwareMmioDevice? = null
    private val adcCachedAddrs = LongArray(20)
    private var adcCachedCount: Int = 0
    private var adcLastReadAddr: Long = -1L

    var mmioMirrorWindowCount: Int = 0
        private set
    var mmioMirrorSlotCount: Int = 0
        private set
    private var mmioMirrorArena: Arena? = null
    private val attachedMirrorPorts = ArrayList<NativeMmioMirrorPort>()
    private var mmioMirrorClearAction: (() -> Unit)? = null

    fun readStats(dyn: Dynarmic): Dynarmic.MmioStats {
        val out = Dynarmic.MmioStats()
        dyn.readMmioStats(out)
        return out
    }

    fun reset() {
        fastMmioHits = 0L
        if (timerIrqShadowEnabled) {
            try { pullInlineTimerExpiriesIfDirty() } catch (_: Throwable) {}
            try { timerBindPullHookHandle?.invoke(null as (() -> Unit)?) } catch (_: Throwable) {}
        }
        timerShadowEnabled = false
        timerIrqShadowEnabled = false
        timerDevice = null
        timerBase = 0L
        timerEnd = 0L
        timerVersionCell = null
        timerCopyHandle = null
        timerApplyInlineHandle = null
        timerBindPullHookHandle = null
        timerShadowSeg = null
        timerIrqShadowSeg = null
        timerLastVersion = Long.MIN_VALUE
        lastNextOtherDeadline = Long.MAX_VALUE
        gpioRoEnabled = false
        gpioBase = 0L
        gpioEnd = 0L
        gpioPort = null
        gpioEpochCell = null
        gpioCopyInputsHandle = null
        gpioCopyAllHandle = null
        gpioLastEpoch = Long.MIN_VALUE
        gpioInputsDirty = false
        configRoEnabled = false
        configRanges = LongArray(0)
        configBusyCell = null
        configCachedCount = 0
        configLastReadAddr = -1L
        adcRoEnabled = false
        adcBase = 0L
        adcEnd = 0L
        adcDevice = null
        adcCachedCount = 0
        adcLastReadAddr = -1L
        gicDistRoEnabled = false
        gicDistBase = 0L
        gicDistEnd = 0L
        gicCachedCount = 0
        if (gicShadowEnabled) {
            try { gicBindHandle?.invokeExact(null as MemorySegment?) } catch (_: Throwable) {}
            try { gicClearNativeHandle?.invokeExact() } catch (_: Throwable) {}
        }
        gicShadowEnabled = false
        gicCpuBase = 0L
        gicCpuEnd = 0L
        gicVersionCell = null
        gicBindHandle = null
        gicSyncHandle = null
        gicPullHandle = null
        gicClearNativeHandle = null
        gicLastVersion = Long.MIN_VALUE
        gicShadowArena?.let { arena ->
            try { arena.close() } catch (_: Throwable) {}
        }
        gicShadowArena = null
        ispBindStatsShadowHandle?.let { mh ->
            try { mh.invokeExact(null as MemorySegment?) } catch (_: Throwable) {}
        }
        ispBindStatsShadowHandle = null
        ispRoRangeCount = 0
        for (port in attachedMirrorPorts) {
            try { port.detachMmioMirrors() } catch (_: Throwable) {}
        }
        attachedMirrorPorts.clear()
        mmioMirrorClearAction?.let { clear ->
            try { clear() } catch (_: Throwable) {}
        }
        mmioMirrorClearAction = null
        mmioMirrorArena?.let { arena ->
            try { arena.close() } catch (_: Throwable) {}
        }
        mmioMirrorArena = null
        mmioMirrorWindowCount = 0
        mmioMirrorSlotCount = 0
    }

    fun initialize(
        host: ComponentHost,
        dyn: Dynarmic,
        activeWindows: List<DeviceWindow>,
        relaxedMode: Boolean,
    ) {
        reset()
        // Strictly relaxed-mode only; strict & discovery keep 100% of MMIO accesses on Java for qualification.
        if (!relaxedMode || !dyn.supportsNativeMmioFastpaths()) return
        val lookup = MethodHandles.publicLookup()

        // 1. soc-timer C++ read shadow (0xFFF3C300..0xFFF3C34F)
        val timerWindow = activeWindows.singleOrNull { it.id == "soc-timer" && it.size == 0x50L }
        val timerComp = host.components().singleOrNull { it.descriptor.id == "soc-timer" }?.component
        if (timerWindow != null && timerComp != null) {
            try {
                val cellMh = lookup.findVirtual(
                    timerComp.javaClass,
                    "shadowVersionCell",
                    MethodType.methodType(LongArray::class.java),
                ).bindTo(timerComp)
                val copyMh = lookup.findVirtual(
                    timerComp.javaClass,
                    "copyShadowState",
                    MethodType.methodType(
                        Long::class.javaPrimitiveType,
                        LongArray::class.java,
                        LongArray::class.java,
                        LongArray::class.java,
                        IntArray::class.java,
                        IntArray::class.java,
                    ),
                ).bindTo(timerComp)
                timerBase = timerWindow.address
                timerEnd = timerWindow.address + timerWindow.size
                timerVersionCell = cellMh.invokeExact() as LongArray
                timerCopyHandle = copyMh
                timerShadowEnabled = true
                syncTimerShadowIfNeeded(dyn, force = true)
            } catch (_: Throwable) {
                timerShadowEnabled = false
            }
        }

        // 2. soc-gpio C++ RO-MMIO shadow (16 banks x 8 aligned u32 regs, auto-invalidated on write & button/input changes)
        val gpioWindow = activeWindows.singleOrNull { it.id == "soc-gpio" && it.size == 0x400L }
        val gpioComp = host.components().singleOrNull { it.descriptor.id == "soc-gpio" }?.component
        val gpioPortFound = host.ports.find("soc-gpio", MmioDevice::class.java)
        if (gpioWindow != null && gpioComp != null && gpioPortFound != null) {
            try {
                val cellMh = lookup.findVirtual(
                    gpioComp.javaClass,
                    "inputEpochCell",
                    MethodType.methodType(LongArray::class.java),
                ).bindTo(gpioComp)
                val inputsMh = lookup.findVirtual(
                    gpioComp.javaClass,
                    "copyInputBankWords",
                    MethodType.methodType(Void.TYPE, IntArray::class.java),
                ).bindTo(gpioComp)
                val allMh = lookup.findVirtual(
                    gpioComp.javaClass,
                    "copyAllRegisterWords",
                    MethodType.methodType(Void.TYPE, IntArray::class.java),
                ).bindTo(gpioComp)
                gpioBase = gpioWindow.address
                gpioEnd = gpioWindow.address + gpioWindow.size
                gpioPort = gpioPortFound
                gpioEpochCell = cellMh.invokeExact() as LongArray
                gpioCopyInputsHandle = inputsMh
                gpioCopyAllHandle = allMh
                var aIdx = 0
                for (bank in 0..15) {
                    val bankBase = gpioBase + (bank shl 6).toLong()
                    gpioInputAddrs[bank] = (bankBase + 0x14L).toInt()
                    for (reg in 0..7) {
                        gpioAllAddrs[aIdx++] = (bankBase + (reg shl 2).toLong()).toInt()
                    }
                }
                gpioRoEnabled = true
                syncGpioAllRegisters(dyn)
            } catch (_: Throwable) {
                gpioRoEnabled = false
            }
        }

        // 3. soc-configuration lazy RO-MMIO shadow (2nd-read promotion per group; O(1) per-group invalidation on write)
        val ranges = buildRanges(activeWindows, setOf("soc-configuration"))
        val configComp = host.components().singleOrNull { it.descriptor.id == "soc-configuration" }?.component
        if (ranges.isNotEmpty() && configComp != null) {
            try {
                val cellMh = lookup.findVirtual(
                    configComp.javaClass,
                    "activeDeadlineCountCell",
                    MethodType.methodType(IntArray::class.java),
                ).bindTo(configComp)
                configRanges = ranges
                configBusyCell = cellMh.invokeExact() as IntArray
                configRoEnabled = true
                configCachedCount = 0
                configLastReadAddr = -1L
            } catch (_: Throwable) {
                configRoEnabled = false
            }
        }

        // 4. soc-adc lazy RO-MMIO shadow (caches static/idle u32 reads when no conversion deadline is pending)
        val adcWindow = activeWindows.singleOrNull { it.id == "soc-adc" && it.size <= 0x100L }
        val adcPort = host.ports.find("soc-adc", MmioDevice::class.java) as? TickAwareMmioDevice
        if (adcWindow != null && adcPort != null) {
            adcBase = adcWindow.address
            adcEnd = adcWindow.address + adcWindow.size
            adcDevice = adcPort
            adcRoEnabled = true
            adcCachedCount = 0
            adcLastReadAddr = -1L
        }

        // 5. soc-isp read-only stats & result surface ranges (0xFFF10000..0xFFF1E000, ~21-41% of all MMIO reads!)
        // When soc-isp exposes a live 28 KiB Stats SRAM engine (AE photometry + 256-bin Histogram), back both
        // Bank 0 (0xFFF10000..0xFFF16FFF) and Bank 1 (0xFFF17000..0xFFF1DFFF) with one shared live MemorySegment
        // so Dynarmic reads live stats at C++ fastpath speed with zero per-read Java upcalls.
        if (dyn.supportsRoMmioRanges()) {
            dyn.clearRoMmioRanges()
            var rangeCount = 0
            val ispComp = host.components().singleOrNull { it.descriptor.id == "soc-isp" }?.component
            var liveStatsSeg: MemorySegment? = null
            if (ispComp != null) {
                try {
                    val bindMh = lookup.findVirtual(
                        ispComp.javaClass,
                        "bindLiveStatsShadow",
                        MethodType.methodType(Boolean::class.javaPrimitiveType, MemorySegment::class.java),
                    ).bindTo(ispComp)
                    val seg = dyn.allocateRoMmioBuffer(ISP_STATS_BANK_SIZE)
                    if (seg != null && (bindMh.invokeExact(seg) as Boolean)) {
                        liveStatsSeg = seg
                        ispBindStatsShadowHandle = bindMh
                    }
                } catch (_: Throwable) {
                    liveStatsSeg = null
                    ispBindStatsShadowHandle = null
                }
            }
            for (win in activeWindows) {
                if (win.id != "soc-isp") continue
                val port = win.port
                if (liveStatsSeg != null && (port in ISP_ZERO_RO_PORTS || port in ISP_BUFFER_RO_PORTS)) {
                    val bankOff = when {
                        win.address in 0xFFF1_0000L until 0xFFF1_7000L -> win.address - 0xFFF1_0000L
                        win.address in 0xFFF1_7000L until 0xFFF1_E000L -> win.address - 0xFFF1_7000L
                        else -> -1L
                    }
                    if (bankOff >= 0L && bankOff + win.size <= ISP_STATS_BANK_SIZE.toLong()) {
                        val slice = liveStatsSeg.asSlice(bankOff, win.size)
                        if (dyn.addRoMmioSegmentRange(win.address, slice, win.size.toInt())) {
                            rangeCount++
                        }
                        continue
                    }
                }
                if (port in ISP_ZERO_RO_PORTS) {
                    if (dyn.addRoMmioZeroRange(win.address, win.size)) rangeCount++
                } else if (port in ISP_BUFFER_RO_PORTS && win.size in 4L..0x10000L && (win.size and 3L) == 0L) {
                    val dev = host.ports.find(port, MmioDevice::class.java) ?: continue
                    val sizeInt = win.size.toInt()
                    val buf = ByteArray(sizeInt)
                    var off = 0
                    var allReadable = true
                    while (off < sizeInt) {
                        val word = try {
                            dev.read(off.toLong(), 4)
                        } catch (_: Throwable) {
                            // Unmodeled hole inside v1 result surface: skip range caching for safety
                            allReadable = false
                            break
                        }
                        buf[off] = (word and 0xffL).toByte()
                        buf[off + 1] = ((word ushr 8) and 0xffL).toByte()
                        buf[off + 2] = ((word ushr 16) and 0xffL).toByte()
                        buf[off + 3] = ((word ushr 24) and 0xffL).toByte()
                        off += 4
                    }
                    if (allReadable && dyn.addRoMmioBufferRange(win.address, buf)) {
                        rangeCount++
                    }
                }
            }
            ispRoRangeCount = rangeCount
        }

        // 6. soc-gic native C++ GICv2 shadow fastpath (DYN-2/DYN-3 shared 2464 B C-ABI) with RO-MMIO fallback
        val gicDistWindow = activeWindows.firstOrNull { it.id == "soc-gic" && it.port == "soc-gic.distributor" && it.size == 0x1000L }
        val gicCpuWindow = activeWindows.firstOrNull { it.id == "soc-gic" && it.port == "soc-gic.interface" && it.size == 0x1000L }
        val gicComp = host.components().singleOrNull { it.descriptor.id == "soc-gic" }?.component
        if (gicDistWindow != null) {
            gicDistBase = gicDistWindow.address
            gicDistEnd = gicDistWindow.address + gicDistWindow.size
            var nativeGicBound = false
            if (gicCpuWindow != null && gicComp != null) {
                try {
                    val supportsGicMh = lookup.findVirtual(
                        dyn.javaClass,
                        "supportsGicShadow",
                        MethodType.methodType(Boolean::class.javaPrimitiveType),
                    ).bindTo(dyn)
                    if (supportsGicMh.invokeExact() as Boolean) {
                        val setPtrMh = lookup.findVirtual(
                            dyn.javaClass,
                            "setGicShadowPtr",
                            MethodType.methodType(Void.TYPE, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, MemorySegment::class.java),
                        ).bindTo(dyn)
                        val clearPtrMh = lookup.findVirtual(
                            dyn.javaClass,
                            "clearGicShadow",
                            MethodType.methodType(Void.TYPE),
                        ).bindTo(dyn)
                        val verMh = lookup.findVirtual(
                            gicComp.javaClass,
                            "shadowVersionCell",
                            MethodType.methodType(LongArray::class.java),
                        ).bindTo(gicComp)
                        val bindMh = lookup.findVirtual(
                            gicComp.javaClass,
                            "bindNativeShadow",
                            MethodType.methodType(Void.TYPE, MemorySegment::class.java),
                        ).bindTo(gicComp)
                        val syncMh = lookup.findVirtual(
                            gicComp.javaClass,
                            "syncToNativeShadow",
                            MethodType.methodType(Void.TYPE),
                        ).bindTo(gicComp)
                        val pullMh = lookup.findVirtual(
                            gicComp.javaClass,
                            "pullFromNativeShadowIfDirty",
                            MethodType.methodType(Boolean::class.javaPrimitiveType),
                        ).bindTo(gicComp)
                        val arena = Arena.ofConfined()
                        val seg = arena.allocate(GIC_SHADOW_SIZE, 8L)
                        seg.fill(0.toByte())
                        gicCpuBase = gicCpuWindow.address
                        gicCpuEnd = gicCpuWindow.address + gicCpuWindow.size
                        gicShadowArena = arena
                        gicVersionCell = verMh.invokeExact() as LongArray
                        gicBindHandle = bindMh
                        gicSyncHandle = syncMh
                        gicPullHandle = pullMh
                        gicClearNativeHandle = clearPtrMh
                        bindMh.invokeExact(seg)
                        setPtrMh.invokeExact(gicDistBase, gicCpuBase, seg)
                        gicLastVersion = gicVersionCell!![0]
                        gicShadowEnabled = true
                        nativeGicBound = true
                    }
                } catch (_: Throwable) {
                    gicShadowArena?.let { a -> try { a.close() } catch (_: Throwable) {} }
                    gicShadowArena = null
                    gicShadowEnabled = false
                }
            }
            if (!nativeGicBound) {
                gicDistRoEnabled = true
                gicCachedCount = 0
            }
        }

        // 7. DYN-V3 inline soc-timer comparator IRQ auto-raise + WFI fast-forward (requires timer shadow + GIC shadow)
        if (timerShadowEnabled && gicShadowEnabled && dyn.supportsTimerIrqShadow() && timerComp != null) {
            val timerPortFound = host.ports.find("soc-timer", MmioDevice::class.java) as? TickAwareMmioDevice
            if (timerPortFound != null) {
                try {
                    val copyRoutesMh = lookup.findVirtual(
                        timerComp.javaClass,
                        "copyRoutes",
                        MethodType.methodType(Boolean::class.javaPrimitiveType, IntArray::class.java),
                    ).bindTo(timerComp)
                    val routed = copyRoutesMh.invokeExact(timerRoutesArr) as Boolean
                    if (routed) {
                        val applyMh = lookup.findVirtual(
                            timerComp.javaClass,
                            "applyInlineNativeExpiries",
                            MethodType.methodType(Void.TYPE, LongArray::class.java, LongArray::class.java, Long::class.javaPrimitiveType),
                        ).bindTo(timerComp)
                        val bindHookMh = lookup.findVirtual(
                            timerComp.javaClass,
                            "bindInlinePullHook",
                            MethodType.methodType(Void.TYPE, Function0::class.java),
                        ).bindTo(timerComp)
                        val tSeg = dyn.timerShadowSegment()
                        val irqSeg = dyn.timerIrqShadowSegment()
                        irqSeg.fill(0.toByte())
                        for (i in 0..7) {
                            irqSeg.set(LITTLE_INT, TIMER_IRQ_OFF_ROUTES + (i shl 2).toLong(), timerRoutesArr[i])
                        }
                        irqSeg.set(LITTLE_LONG, TIMER_IRQ_OFF_NEXT_OTHER, Long.MAX_VALUE)
                        dyn.bindTimerIrqShadow()
                        timerDevice = timerPortFound
                        timerApplyInlineHandle = applyMh
                        timerBindPullHookHandle = bindHookMh
                        timerShadowSeg = tSeg
                        timerIrqShadowSeg = irqSeg
                        val pullHook: () -> Unit = { pullInlineTimerExpiriesIfDirty() }
                        bindHookMh.invoke(pullHook)
                        timerIrqShadowEnabled = true
                    }
                } catch (_: Throwable) {
                    timerIrqShadowEnabled = false
                    timerDevice = null
                    timerApplyInlineHandle = null
                    timerBindPullHookHandle = null
                    timerShadowSeg = null
                    timerIrqShadowSeg = null
                }
            }
        }

        // 8. Generic component-declared native MMIO read-mirror banks (relaxed-mode only; zero hardcoded peripheral logic)
        if (dyn.supportsMmioMirrors()) {
            dyn.clearMmioMirrors()
            val windowByPort = HashMap<String, DeviceWindow>(activeWindows.size * 2)
            for (win in activeWindows) {
                windowByPort.putIfAbsent(win.port, win)
            }
            var arena: Arena? = null
            var mirrorWindows = 0
            var mirrorSlots = 0
            var adcMirrored = false
            var configMirrored = false
            for (loaded in host.components()) {
                val mirrorPort = loaded.component as? NativeMmioMirrorPort ?: continue
                val specs = try {
                    mirrorPort.describeMmioMirrorWindows()
                } catch (_: Throwable) {
                    emptyList()
                }
                if (specs.isEmpty()) continue
                var boundAnyForComponent = false
                for (spec in specs) {
                    val win = windowByPort[spec.portName] ?: continue
                    if (win.size < spec.windowSize) continue
                    val targetArena = arena ?: Arena.ofConfined().also { arena = it }
                    val seg = targetArena.allocate(MmioMirrorHandle.requiredBytes(spec.slots.size), 8L)
                    val handle = MmioMirrorHandle(spec, seg)
                    mirrorPort.attachMmioMirror(spec.portName, handle)
                    if (dyn.registerMmioMirror(win.address, win.size, seg)) {
                        mirrorWindows++
                        mirrorSlots += spec.slots.size
                        boundAnyForComponent = true
                        if (win.id == "soc-adc") adcMirrored = true
                        if (win.id == "soc-configuration") configMirrored = true
                    } else {
                        handle.invalidateAll()
                    }
                }
                if (boundAnyForComponent) {
                    attachedMirrorPorts.add(mirrorPort)
                }
            }
            if (mirrorWindows > 0) {
                mmioMirrorArena = arena
                mmioMirrorClearAction = { dyn.clearMmioMirrors() }
                mmioMirrorWindowCount = mirrorWindows
                mmioMirrorSlotCount = mirrorSlots
                if (adcMirrored && adcRoEnabled) {
                    clearAdcRoCache(dyn)
                    adcRoEnabled = false
                }
                if (configMirrored && configRoEnabled) {
                    clearConfigRoCache(dyn)
                    configRoEnabled = false
                }
            } else {
                arena?.close()
            }
        }
    }

    fun prepareInlineTimerBeforeRun(nextOtherDeadline: Long, allowWfiFastForward: Boolean) {
        if (!timerIrqShadowEnabled) return
        val irqSeg = timerIrqShadowSeg ?: return
        lastNextOtherDeadline = nextOtherDeadline
        irqSeg.set(LITTLE_LONG, TIMER_IRQ_OFF_NEXT_OTHER, nextOtherDeadline)
        irqSeg.set(LITTLE_INT, TIMER_IRQ_OFF_ACTIVE, if (allowWfiFastForward) 3 else 1)
    }

    /**
     * Synchronizes `timer_irq_shadow->next_other_deadline` live during an active native cluster run
     * whenever an MMIO read/write or scheduled device tick arms or shortens a non-timer peripheral
     * deadline (e.g. shutter curtains/motors, LENSCOM/BISCOM SPI, HDMAC/Ch14, ADC, JPEG, I2C).
     * Prevents `try_inline_wfi_fastforward()` and parallel SMP epochs from leaping past a newly
     * armed peripheral deadline.
     */
    fun updateNextOtherDeadline(nextOtherDeadline: Long): Boolean {
        if (!timerIrqShadowEnabled) return false
        val irqSeg = timerIrqShadowSeg ?: return false
        val earlier = nextOtherDeadline < lastNextOtherDeadline
        if (nextOtherDeadline != lastNextOtherDeadline) {
            lastNextOtherDeadline = nextOtherDeadline
            irqSeg.set(LITTLE_LONG, TIMER_IRQ_OFF_NEXT_OTHER, nextOtherDeadline)
        }
        return earlier
    }

    fun disableInlineTimerForSingleStep() {
        if (!timerIrqShadowEnabled) return
        val irqSeg = timerIrqShadowSeg ?: return
        irqSeg.set(LITTLE_INT, TIMER_IRQ_OFF_ACTIVE, 0)
    }

    fun pullInlineTimerExpiriesIfDirty(): Boolean {
        if (!timerIrqShadowEnabled) return false
        val irqSeg = timerIrqShadowSeg ?: return false
        if (irqSeg.get(LITTLE_INT, TIMER_IRQ_OFF_DIRTY) == 0) return false
        val tSeg = timerShadowSeg ?: return false
        irqSeg.set(LITTLE_INT, TIMER_IRQ_OFF_DIRTY, 0)
        val lastTick = irqSeg.get(LITTLE_LONG, TIMER_IRQ_OFF_LAST_FIRE)
        for (i in 0..7) {
            timerPullDeadlines[i] = tSeg.get(LITTLE_LONG, 8L + i * 32L + 16L)
            val off = TIMER_IRQ_OFF_FIRED + (i shl 3).toLong()
            timerPullFired[i] = irqSeg.get(LITTLE_LONG, off)
            if (timerPullFired[i] != 0L) irqSeg.set(LITTLE_LONG, off, 0L)
        }
        timerApplyInlineHandle?.invokeExact(timerPullDeadlines, timerPullFired, lastTick)
        return true
    }

    fun onHostEvent(dyn: Dynarmic, source: String, kind: String) {
        if (source == "buttons") {
            gpioInputsDirty = true
            if (adcRoEnabled && adcCachedCount > 0) clearAdcRoCache(dyn)
        } else if (kind == "configuration") {
            if (source == "soc-gpio") gpioInputsDirty = true
            else if (source == "soc-adc" && adcRoEnabled && adcCachedCount > 0) clearAdcRoCache(dyn)
        }
    }

    fun onMmioRead(dyn: Dynarmic, physicalAddress: Long, size: Int, value: Long) {
        if (size == 4 && (physicalAddress and 3L) == 0L) {
            onMmioReadWord(dyn, physicalAddress, value)
        }
        if (gicShadowEnabled) {
            syncGicShadowIfNeeded(force = false)
        }
    }

    fun onMmioReadWord(dyn: Dynarmic, physicalAddress: Long, value: Long) {
        if (configRoEnabled && inRanges(physicalAddress, configRanges)) {
            maybeCacheConfigWord(dyn, physicalAddress, value)
        } else if (adcRoEnabled && physicalAddress >= adcBase && physicalAddress < adcEnd) {
            maybeCacheAdcWord(dyn, physicalAddress, value)
        } else if (gicDistRoEnabled && physicalAddress >= gicDistBase && physicalAddress < gicDistEnd) {
            maybeCacheGicSharedDistWord(dyn, physicalAddress, value)
        }
    }

    fun onMmioWrite(dyn: Dynarmic, physicalAddress: Long, size: Int) {
        if (timerShadowEnabled && physicalAddress >= timerBase && physicalAddress < timerEnd) {
            syncTimerShadowIfNeeded(dyn, force = false)
        } else if (gpioRoEnabled && physicalAddress >= gpioBase && physicalAddress < gpioEnd) {
            if (size == 4 && (physicalAddress and 3L) == 0L) {
                val updated = gpioPort!!.read(physicalAddress - gpioBase, 4)
                dyn.setRoMmioWord(physicalAddress, updated)
            }
        } else if (adcRoEnabled && physicalAddress >= adcBase && physicalAddress < adcEnd) {
            clearAdcRoCache(dyn)
        } else if (configRoEnabled && inRanges(physicalAddress, configRanges)) {
            invalidateConfigGroup(dyn, physicalAddress)
        } else if (gicDistRoEnabled && physicalAddress >= gicDistBase && physicalAddress < gicDistEnd) {
            invalidateGicDistWord(physicalAddress and -4L)
        }
        if (gicShadowEnabled) {
            syncGicShadowIfNeeded(force = false)
        }
        if (gpioRoEnabled) {
            syncGpioInputsIfNeeded(dyn)
        }
    }

    fun onScheduledDevicesTicked(dyn: Dynarmic) {
        if (timerShadowEnabled) syncTimerShadowIfNeeded(dyn, force = false)
        if (gpioRoEnabled) syncGpioInputsIfNeeded(dyn)
        if (gicShadowEnabled) syncGicShadowIfNeeded(force = false)
        if (adcRoEnabled && adcCachedCount > 0) clearAdcRoCache(dyn)
    }

    fun onIdleClockAdvanced(dyn: Dynarmic) {
        if (timerShadowEnabled) syncTimerShadowIfNeeded(dyn, force = false)
        if (gpioRoEnabled) syncGpioInputsIfNeeded(dyn)
        if (gicShadowEnabled) syncGicShadowIfNeeded(force = false)
    }

    fun syncBeforeRun(dyn: Dynarmic) {
        if (gpioRoEnabled) syncGpioInputsIfNeeded(dyn)
        if (gicShadowEnabled) syncGicShadowIfNeeded(force = false)
    }

    fun refreshAll(dyn: Dynarmic) {
        if (timerShadowEnabled) syncTimerShadowIfNeeded(dyn, force = true)
        if (gpioRoEnabled) syncGpioAllRegisters(dyn)
        if (configRoEnabled) clearConfigRoCache(dyn)
        if (adcRoEnabled) clearAdcRoCache(dyn)
        if (gicDistRoEnabled) clearGicDistRoCache(dyn)
        if (gicShadowEnabled) syncGicShadowIfNeeded(force = true)
    }

    private fun syncGicShadowIfNeeded(force: Boolean) {
        if (!gicShadowEnabled) return
        val ver = gicVersionCell!![0]
        if (!force && ver == gicLastVersion) return
        gicSyncHandle!!.invokeExact()
        gicLastVersion = gicVersionCell!![0]
    }

    private fun syncTimerShadowIfNeeded(dyn: Dynarmic, force: Boolean) {
        if (!timerShadowEnabled) return
        val ver = timerVersionCell!![0]
        if (!force && ver == timerLastVersion) return
        timerLastVersion = timerCopyHandle!!.invokeExact(
            timerStored,
            timerReload,
            timerDeadline,
            timerEnabledArr,
            timerHasDeadlineArr,
        ) as Long
        dyn.setTimerShadow(
            timerBase,
            true,
            timerStored,
            timerReload,
            timerDeadline,
            timerEnabledArr,
            timerHasDeadlineArr,
        )
    }

    private fun syncGpioAllRegisters(dyn: Dynarmic) {
        if (!gpioRoEnabled) return
        dyn.clearRoMmioRange(gpioBase, gpioEnd - gpioBase)
        gpioCopyAllHandle!!.invokeExact(gpioAllBuf)
        dyn.setRoMmioBatch(gpioAllAddrs, gpioAllBuf, 128)
        gpioLastEpoch = gpioEpochCell!![0]
        gpioInputsDirty = false
    }

    private fun syncGpioInputsIfNeeded(dyn: Dynarmic) {
        if (!gpioRoEnabled) return
        val ep = gpioEpochCell!![0]
        if (!gpioInputsDirty && ep == gpioLastEpoch) return
        gpioCopyInputsHandle!!.invokeExact(gpioInputsBuf)
        dyn.setRoMmioBatch(gpioInputAddrs, gpioInputsBuf, 16)
        gpioLastEpoch = ep
        gpioInputsDirty = false
    }

    private fun maybeCacheConfigWord(dyn: Dynarmic, physicalAddress: Long, value: Long) {
        if (configBusyCell!![0] != 0) return
        if (physicalAddress != configLastReadAddr) {
            configLastReadAddr = physicalAddress
            return
        }
        if (configCachedCount >= configCachedAddrs.size) return
        if (dyn.setRoMmioWord(physicalAddress, value)) {
            configCachedAddrs[configCachedCount++] = physicalAddress
        }
    }

    private fun invalidateConfigGroup(dyn: Dynarmic, writtenAddr: Long) {
        val groupMask = writtenAddr and -256L
        if ((configLastReadAddr and -256L) == groupMask) configLastReadAddr = -1L
        var i = 0
        while (i < configCachedCount) {
            val addr = configCachedAddrs[i]
            if ((addr and -256L) == groupMask) {
                dyn.removeRoMmioWord(addr)
                configCachedCount--
                configCachedAddrs[i] = configCachedAddrs[configCachedCount]
            } else {
                i++
            }
        }
    }

    private fun clearConfigRoCache(dyn: Dynarmic) {
        if (!configRoEnabled || configCachedCount <= 0) return
        for (i in 0 until configCachedCount) {
            dyn.removeRoMmioWord(configCachedAddrs[i])
        }
        configCachedCount = 0
        configLastReadAddr = -1L
    }

    private fun maybeCacheAdcWord(dyn: Dynarmic, physicalAddress: Long, value: Long) {
        if (adcDevice!!.nextInterestedTick() != Long.MAX_VALUE) return
        val off = physicalAddress - adcBase
        val immediateStatic = off == 0x44L || off == 0x04L || off == 0x08L || off == 0x40L
        if (!immediateStatic) {
            if (physicalAddress != adcLastReadAddr) {
                adcLastReadAddr = physicalAddress
                return
            }
        }
        if (adcCachedCount >= adcCachedAddrs.size) return
        if (dyn.setRoMmioWord(physicalAddress, value)) {
            adcCachedAddrs[adcCachedCount++] = physicalAddress
        }
    }

    private fun clearAdcRoCache(dyn: Dynarmic) {
        if (!adcRoEnabled || adcCachedCount <= 0) return
        for (i in 0 until adcCachedCount) {
            dyn.removeRoMmioWord(adcCachedAddrs[i])
        }
        adcCachedCount = 0
        adcLastReadAddr = -1L
    }

    private fun maybeCacheGicSharedDistWord(dyn: Dynarmic, physicalAddress: Long, value: Long) {
        val off = physicalAddress - gicDistBase
        // Only shared (non-banked) SPI distributor registers: priority (SPI 32..511), targets (SPI 32..511), config (SPI 32..511)
        val isSharedSpi = (off in 0x420L..<0x600L) || (off in 0x820L..<0xA00L) || (off in 0xC08L..<0xD00L)
        if (!isSharedSpi || gicCachedCount >= gicCachedAddrs.size) return
        if (dyn.setRoMmioWord(physicalAddress, value)) {
            gicCachedAddrs[gicCachedCount++] = physicalAddress
        }
    }

    private fun invalidateGicDistWord(alignedAddr: Long) {
        var i = 0
        while (i < gicCachedCount) {
            if (gicCachedAddrs[i] == alignedAddr) {
                gicCachedCount--
                gicCachedAddrs[i] = gicCachedAddrs[gicCachedCount]
                return
            }
            i++
        }
    }

    private fun clearGicDistRoCache(dyn: Dynarmic) {
        if (!gicDistRoEnabled || gicCachedCount <= 0) return
        for (i in 0 until gicCachedCount) {
            dyn.removeRoMmioWord(gicCachedAddrs[i])
        }
        gicCachedCount = 0
    }

    companion object {
        private const val GIC_SHADOW_SIZE = 0x9A0L
        private const val TIMER_IRQ_OFF_ACTIVE = 0x00L
        private const val TIMER_IRQ_OFF_DIRTY = 0x04L
        private const val TIMER_IRQ_OFF_LAST_FIRE = 0x08L
        private const val TIMER_IRQ_OFF_ROUTES = 0x10L
        private const val TIMER_IRQ_OFF_FIRED = 0x30L
        private const val TIMER_IRQ_OFF_NEXT_OTHER = 0x70L
        private val LITTLE_INT: ValueLayout.OfInt = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN)
        private val LITTLE_LONG: ValueLayout.OfLong = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN)
        private const val ISP_STATS_BANK_SIZE = 0x7000
        private val ISP_ZERO_RO_PORTS = setOf(
            "soc-isp.stats-0",
            "soc-isp.stats-1",
            "soc-isp.stats-head-0-v2",
            "soc-isp.stats-head-1-v2",
            "soc-isp.stats-head-0-v3",
            "soc-isp.stats-head-1-v3",
        )
        private val ISP_BUFFER_RO_PORTS = setOf(
            "soc-isp.result-surface",
            "soc-isp.result-surface-v2",
            "soc-isp.result-surface-1-v2",
            "soc-isp.result-surface-v3",
            "soc-isp.result-surface-1-v3",
        )

        fun buildRanges(windows: List<DeviceWindow>, ids: Set<String>): LongArray {
            val matched = windows.filter { it.id in ids }
            val result = LongArray(matched.size * 2)
            for (i in matched.indices) {
                result[i * 2] = matched[i].address
                result[i * 2 + 1] = matched[i].address + matched[i].size
            }
            return result
        }

        fun inRanges(address: Long, ranges: LongArray): Boolean {
            var i = 0
            while (i < ranges.size) {
                if (address >= ranges[i] && address < ranges[i + 1]) return true
                i += 2
            }
            return false
        }
    }
}

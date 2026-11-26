package com.github.nyabanban.fuji.emulator.api

import java.nio.ByteBuffer

interface MmioDevice {
    /** Explicit functional scheduling order; not a measured chip arbitration priority. */
    val clockOrder: Int get() = 100
    /** Explicit order within a hardware family, independent of address/JAR iteration. */
    val clockIndex: Int get() = 0
    fun read(offset: Long, size: Int): Long
    fun write(offset: Long, size: Int, value: Long)
    fun beforeInstruction(tick: Long) {}
    /** True if read() may change nextDeadline()/nextInterestedTick() or requires schedule refresh. */
    fun readMayMutateSchedule(): Boolean = false
}
interface BatteryPort { fun chargePercent(): Double; fun voltageMillivolts(): Int; fun present(): Boolean }
interface ButtonPort { fun pressed(key: String): Boolean }
interface BlockStoragePort {
    fun size(slot: Int): Long?
    fun read(slot: Int, offset: Long, target: ByteBuffer): Int
    fun write(slot: Int, offset: Long, source: ByteBuffer): Int
}
/** Optional owner-thread preflight. Inspection never moves bytes or changes media. */
interface CheckedBlockStoragePort : BlockStoragePort {
    fun readOnly(slot: Int): Boolean
    fun checkAccess(slot: Int, offset: Long, length: Long, write: Boolean)
    /** Best-effort post-transfer flush/sync (e.g. materialize completed FAT32 writes to host directory). */
    fun flush(slot: Int) {}
}
/** Frame bytes remain producer-owned. copyArgb is the explicit ownership boundary. */
interface DisplayPort {
    val width: Int
    val height: Int
    val sequence: Long
    /** Monotonic version incremented only when presented pixels actually differ from the previous frame. */
    val contentSequence: Long get() = sequence
    /** Count of bit-identical frames whose pixel copy/publish was skipped. */
    val skippedIdenticalFrames: Long get() = 0L
    fun copyArgb(target: IntArray): Boolean
    fun presentArgb(pixels: IntArray, width: Int, height: Int)
}
data class LensParameters(
    val model: String, val focalLengthMm: Double, val aperture: Double,
    val focusDistanceM: Double, val transmission: Double, val focusBreathing: Double,
)
interface LensPort { fun parameters(): LensParameters }
/** Optional electronic-contacts & optical range metadata exposed by a [LensPort] implementation. */
interface ElectronicLensPort {
    val electronicContacts: Boolean
    val oisEnabled: Boolean get() = false
    val minFocalLengthMm: Double get() = 1.0
    val maxFocalLengthMm: Double get() = 2000.0
    val minAperture: Double get() = 0.5
    val maxAperture: Double get() = 64.0
    val minFocusDistanceM: Double get() = 0.01
    val focusMode: String get() = "AF-S"
}
/** Optional live focus/AF control exposed by a [LensPort] implementation for mount/AF-drive integration. */
interface LensControlPort {
    fun triggerAutoFocus(): Double
    fun setFocusDistanceM(distanceM: Double)
}
interface MountPort {
    fun attachedLens(): LensParameters?
    /** Synchronize mount hardware detect lines (e.g. GPIO(0,11) KEY_LENS_DET) after live lens/contact updates. */
    fun syncHardwareDetect() {}
    /** Restore the sampled contact level without generating a physical lens exchange. */
    fun restoreHardwareDetect() = syncHardwareDetect()
}
enum class MediaKind {
    H264,
    RAF,
    JPEG,
    PNG,
    VIDEO_FILE,
    RTMP_RTSP,
    WEBCAM,
    SHARED_TEXTURE,
}
enum class BayerPattern { RGGB, GRBG, GBRG, BGGR }
data class Exposure(val startTick: Long, val durationNanos: Long, val iso: Int, val lens: LensParameters)
data class SensorGeometry(val width: Int, val height: Int, val bitDepth: Int, val bayer: BayerPattern, val pitchMicrometres: Double)
data class MediaInput(val kind: MediaKind, val relativePath: String, val loop: Boolean)
/** Descriptor is not decoded radiance. H264/JPEG require calibrated inverse imaging; RAF requires metadata/mosaic validation. */
interface SceneSource : AutoCloseable {
    fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer)
}
/**
 * Optional GPU-resident ISP statistics reduction request bundled inside [LiveViewIspFusionRequest]
 * so a single Vulkan command buffer produces both YUV output and the compact 9,280-byte
 * (`2,320 x u32`) AE/AWB/Histogram/CDAF stats payload without reading back raw Bayer bytes.
 */
data class LiveViewIspStatsRequest(
    val viewX: Int = 0,
    val viewY: Int = 0,
    val viewW: Int = 0,
    val viewH: Int = 0,
    val gridCols: Int = 16,
    val gridRows: Int = 16,
    val stepQuad: Int = 2,
    val awbHighlightThresh10: Int = 960,
    val afRoiX0: Int = 0,
    val afRoiY0: Int = 0,
    val afRoiX1: Int = 0,
    val afRoiY1: Int = 0,
    val flags: Int = 7,
)

/**
 * Decoupled LiveView ISP demosaic + color + focus-peaking parameters passed from `soc-isp`
 * (`LiveViewDmaEngine`) to an optional GPU-resident fused Optics->ISP sensor/lens fast path.
 */
class LiveViewIspFusionRequest(
    val exposure: Exposure,
    val dstWidth: Int,
    val dstHeight: Int,
    val dstStrideBytes: Int,
    val outputFormat: Int,
    val flags: Int,
    val offX: Int,
    val offY: Int,
    val fitW: Int,
    val fitH: Int,
    val gainR1024: Int,
    val gainG1024: Int,
    val gainB1024: Int,
    val yuvShift: Int,
    val ccm: IntArray? = null,
    val yuvMat: IntArray? = null,
    val lutR: IntArray? = null,
    val lutG: IntArray? = null,
    val lutB: IntArray? = null,
    val peakingThreshold: Int = 0,
    val peakingHiY: Int = 250,
    val peakingHiCb: Int = 0x80,
    val peakingHiCr: Int = 0x80,
    val statsRequest: LiveViewIspStatsRequest? = null,
    val outStatsWords: IntArray? = null,
) {
    @JvmField
    var statsProduced: Boolean = false

    @JvmField
    var statsGpuMs: Double = 0.0

    companion object {
        const val FMT_UYVY422 = 0
        const val FMT_NV16 = 1
        const val FMT_NV12 = 2
        const val FMT_YYUV422 = 3

        const val FLAG_HAS_CCM = 1
        const val FLAG_HAS_YUV_MAT = 2
        const val FLAG_MONO_MODE = 4
        const val FLAG_PEAKING_ENABLED = 8
        const val FLAG_SRC_CROP = 16

        const val STATS_OUTPUT_WORDS = 2320
    }
}

/** Sensor-side packed sample bytes feed modeled electronics/DMA/ISP, never a host-made camera output file. */
interface SensorPort {
    fun expose(exposure: Exposure, destination: ByteBuffer): Int

    /**
     * Monotonic frame generation token bumped whenever the sensor's rendered Bayer output changes
     * (new source frame sequence, optical filter stack state, lens optical settings, exposure/gain,
     * or sensor configuration). Returns `-1L` when untracked (callers fall back to byte comparison).
     */
    fun currentFrameGeneration(): Long = -1L

    /**
     * Synchronous deterministic exposure for still captures (`RECC_S`) and single-shot callers
     * that must immediately observe the exact updated optics/filter frame rather than an async
     * double-buffered LiveView frame. Defaults to [expose].
     */
    fun exposeStill(exposure: Exposure, destination: ByteBuffer): Int = expose(exposure, destination)

    /**
     * Returns `true` when [tryExposeFusedIspYuv] can compute exact GPU-resident ISP statistics
     * ([LiveViewIspFusionRequest.statsRequest] -> [LiveViewIspFusionRequest.outStatsWords]) in the
     * same Vulkan submission without reading back raw Bayer bytes to the host.
     */
    fun supportsFusedIspStats(): Boolean = false

    /**
     * Lightweight O(1) state signature for the current GPU-fused LiveView frame given [exposure].
     * Returns a non-negative token when the scene and optics are deterministic/static so
     * `LiveViewDmaEngine` can reuse its cached YUV slot on unchanged V-Sync frames; returns `-1L`
     * for animated, streaming, gyro-shaken, or stochastic-noise frames that must always re-render.
     */
    fun fusedFrameStateKey(exposure: Exposure): Long = -1L

    /**
     * Optional GPU-resident fused Optics -> CMOS Bayer -> LiveView ISP Demosaic fast path that
     * stays in VRAM without reading back intermediate Bayer bytes to the host. Returns `true`
     * when YUV output was produced on GPU, or `false` to fall back cleanly to standard `expose()`.
     */
    fun tryExposeFusedIspYuv(
        request: LiveViewIspFusionRequest,
        outYOrPacked: ByteArray,
        outUvPlane: ByteArray? = null,
    ): Boolean = false

    /**
     * Synchronous GPU-resident fused still-capture path (Optics -> CMOS Bayer -> ISP Demosaic +
     * optional source crop -> packed UYVY422). Defaults to [tryExposeFusedIspYuv].
     */
    fun tryExposeStillUyvy(
        request: LiveViewIspFusionRequest,
        outUyvy: ByteArray,
    ): Boolean = tryExposeFusedIspYuv(request, outUyvy, null)
}
/** Live-view ISP DMA pump triggered on frame sync/arming. */
interface LiveViewDmaPort { fun pumpLiveViewDma(tick: Long): Boolean }
/** ISP interrupt injection/observation port for hardware-owned ISP lines (106, 296). */
interface IspInterruptPort {
    fun signalInterrupt(line: Int, withStatus: Boolean, statusMask: Long = 1L)
    fun irq106State(): Map<String, Any?>
}
interface ContactBus { fun exchange(request: ByteBuffer, response: ByteBuffer): Int }
/** Owner-thread bounded serial word exchange. Missing input is not a fabricated MISO word. */
interface SpiPeer { fun exchange(bits: Int, tx: Int, tick: Long): Int }
/** Owner-thread exclusive wire connection. Connect once; hot byte phases reuse the lease. */
interface I2cBusPort {
    fun connect(owner: String): I2cConnection
    fun registerSlave(device: I2cSlaveDevice) {
        throw UnsupportedOperationException("I2C bus does not support external slave registration")
    }
    fun unregisterSlave(address7: Int, device: I2cSlaveDevice? = null) {}
}
/** Methods validate before mutation on failure; a successful write/consume commits the peer phase once. */
interface I2cConnection : AutoCloseable {
    fun address(byte: Int): Boolean
    fun checkWrite(byte: Int)
    fun write(byte: Int)
    fun peekRead(): Int
    fun consumeRead(expected: Int)
    fun stop()
}
/** Owner-thread physical output observation; the sequence changes only when this pin's output level changes. */
interface GpioOutputPort {
    fun outputLevel(port: Int, pin: Int): Boolean
    fun outputSequence(port: Int, pin: Int): Long
    fun addOutputListener(port: Int, pin: Int, listener: (level: Boolean, sequence: Long) -> Unit): AutoCloseable =
        AutoCloseable {}
}
/** Owner-thread external peripheral pin drive into GPIO input banks (e.g., IBIS BIS_RDY on port 0, pin 4). */
fun interface GpioInputPort { fun setInputLevel(port: Int, pin: Int, high: Boolean) }
/** Optional physical contact break when the host replaces a mounted electronic lens in one action. */
interface LensMountDetectPort : GpioInputPort {
    val replacingLens: Boolean
    fun breakForLensExchange()
}
interface InterruptSink { fun setLine(irq: Int, high: Boolean) }
/** Read-only owner-thread preflight for edge producers. Unknown sinks remain unqualified. */
data class EdgeInterruptState(val edge: Boolean, val high: Boolean, val pending: Boolean)
interface EdgeInterruptSink : InterruptSink {
    fun edgeState(irq: Int): EdgeInterruptState
    /** Zero-allocation packed edge state bits: bit 0 = edge, bit 1 = high, bit 2 = pending. */
    fun edgeStateBits(irq: Int): Int {
        val s = edgeState(irq)
        return (if (s.edge) 1 else 0) or (if (s.high) 2 else 0) or (if (s.pending) 4 else 0)
    }
}
interface EnabledEdgeInterruptSink : EdgeInterruptSink { fun lineEnabled(irq: Int): Boolean }
interface CoupledEdgeInterruptSink : EnabledEdgeInterruptSink {
    fun pulseWithDevice(irq: Int, targetMask: Int, latchDevice: () -> Unit)
}
data class ExecutionLocation(val core: Int, val pcVirtual: Long)
fun interface ExecutionLocationSource {
    fun current(): ExecutionLocation
    fun currentCore(): Int = current().core
    fun currentPcVirtual(): Long = current().pcVirtual
}
data class InterruptRoute(val sinkPort: String, val lines: IntRange)
interface CpuInterruptPort {
    fun choose(): Int?
    /** Zero-allocation check equivalent to `choose() != null`. */
    fun hasPending(): Boolean = choose() != null
    /** Zero-allocation primitive choice equivalent to `choose() ?: -1`. */
    fun chooseOrNegative(): Int = choose() ?: -1
}
/** Owner-thread selection of a qualified banked CPU interface; independent of concrete GIC hardware. */
interface CoreInterruptPort : CpuInterruptPort { val coreCount: Int; fun selectCore(core: Int) }
data class AddressWindow(val port: String, val base: Long, val size: Long)
interface AddressLayout { fun window(port: String): AddressWindow }
/** Native permission bits: read=1, write=2, execute=4; physical addresses throughout. */
data class MemoryRegion(val id: String, val address: Long, val size: Long, val permissions: Int)
interface MemoryLayoutSource { fun regions(profile: String): List<MemoryRegion> }
interface MemoryLayout {
    val profile: String
    fun regions(): List<MemoryRegion>
    fun check(address: Long, size: Long, permissions: Int)
}
/** Immutable verified update bytes; components cannot mutate the selected DAT. */
interface UpdateDataPort { val size: Long; val sha256: String; fun read(offset: Long, destination: ByteBuffer) }
data class ExperimentalImageTail(val profile: String, val offset: Long, val size: Long, val fill: Int)
interface ReadOnlyImagePort {
    val length: Long
    val sha256: String
    /** Kept separate from the supplied length/hash; never a claim of original data. */
    val experimentalTail: ExperimentalImageTail? get() = null
    fun check(offset: Long, size: Long)
    fun read(offset: Long, destination: ByteBuffer)
}
/** Request-paced source; fixed-source DMA consumes a linear peripheral stream. */
interface DmaSourcePeer {
    fun overlaps(address: Long, size: Long): Boolean
    fun check(address: Long, size: Long)
    fun acceptedAt(): Long?
    fun claim(channel: Int, address: Long, size: Long): Long?
    fun release(channel: Int)
    fun read(channel: Int, address: Long, destination: ByteBuffer)
    fun consume(channel: Int)
    /** Bytes per paced sample frame for request-timed audio DMA (4 for 16-bit packed stereo, 8 for 24/32-bit stereo). */
    fun pacedBytesPerFrame(): Int = 4
}
interface DmaMemory {
    fun check(address: Long, size: Long, write: Boolean) { error("DMA memory bounds not qualified") }
    fun read(address: Long, destination: ByteBuffer)
    fun write(address: Long, source: ByteBuffer)
}

/** Fixed-address destination stream. All methods run on the emulation owner thread. */
interface DmaDestinationPeer {
    fun matches(address: Long): Boolean
    /** Observational whole-transfer bounds/width/request preflight; never claims or consumes. */
    fun check(address: Long, size: Long, width: Int, request: Int)
    fun acceptedAt(): Long?
    fun claim(channel: Int, address: Long, size: Long, width: Int, request: Int): Long?
    /** Cancel this owner's lease without consuming the armed request. Other owners are untouched. */
    fun release(channel: Int)
    fun write(channel: Int, address: Long, offset: Long, source: ByteBuffer)
    fun readBack(channel: Int, address: Long, offset: Long, destination: ByteBuffer)
    fun finish(channel: Int, completedAt: Long, observedAt: Long)
    /** Bytes per paced sample frame for request-timed audio DMA (4 for 16-bit packed stereo, 8 for 24/32-bit stereo). */
    fun pacedBytesPerFrame(): Int = 4
}
data class TransferCapture(val sequence: Long, val size: Int, val sha256: String, val completedAt: Long, val observedAt: Long)
/** Bounded last-completed immutable capture; generation mismatch refuses stale reads. */
interface TransferCapturePort {
    fun capture(): TransferCapture?
    fun read(sequence: Long, offset: Long, destination: ByteBuffer)
}

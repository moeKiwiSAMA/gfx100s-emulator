package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.*
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Unified SoC ISP hardware peripheral component (`soc-isp`) covering:
 * - Core ISP bank & IRQ 106 (`0xfff70000..0xfff702ec`)
 * - Companion control, LUT, config, and stats banks (`0xfff50000..0xfff5bc00`, `0xfff68000..0xfff68d04`,
 *   `0xfff69000..0xfff6902c`, `0xfff69100..0xfff69254` w/ IRQ 296, `0xfff6b000..0xfff6b0f8`,
 *   `0xfff6e000..0xfff6e070`, `0xfff11060..0xfff129e0`, `0xfff16000..0xfff17000`, `0xfff1d000..0xfff1e000`)
 * - Live-view LUT and channel banks + CMOS Bayer->UYVY DMA (`0xfff6d000..0xfff6e000`,
 *   `0xfff72000..0xfff72130`, `0xfff73000..0xfff73130`, `0xfff74000..0xfff74030`)
 *
 * Addresses, register spans, and directional rules are derived from GXUP0004.DAT section4/5;
 * physical reset values, timing, and behavioral state laws remain HYPOTHESIS.
 */
class SocIspFactory : ComponentFactory {
    override val descriptor = DESCRIPTOR

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        IspController(context, config)

    companion object {
        const val COMPONENT_ID = "soc-isp"
        const val PROFILE = "isp-banks-experimental-v1"
        const val PROFILE_AUTO_FRAME_IRQS = "isp-banks-auto-frame-irqs-v1"
        const val PROFILE_V2 = "isp-banks-experimental-v2"
        const val PROFILE_V3 = "isp-banks-movie-capture-v3"
        const val IRQ_100 = 100
        const val IRQ_101 = 101
        const val IRQ_102 = 102
        const val IRQ_104 = 104
        const val IRQ_105 = 105
        const val IRQ_106 = 106
        const val IRQ_107 = 107
        const val IRQ_108 = 108
        const val IRQ_109 = 109
        const val IRQ_296 = 296
        const val IRQ_297 = SlvsEcRxPhyEngine.IRQ_SLVSEC_ERR
        const val IRQ_298 = SlvsEcRxPhyEngine.IRQ_SLVSEC_CH1
        const val IRQ_110 = ImagingCompanionEngines.IRQ_MOVDC
        const val IRQ_122 = PdafHardwareEngine.IRQ_LINE
        const val IRQ_126 = ImagingCompanionEngines.IRQ_80000
        const val IRQ_127 = ImagingCompanionEngines.IRQ_B9000
        const val IRQ_129 = ImagingCompanionEngines.IRQ_ISP_82000
        const val IRQ_134 = ImagingCompanionEngines.IRQ_6F000
        const val IRQ_137 = ImagingCompanionEngines.IRQ_LSRAW
        const val IRQ_138 = MaliV61VpuEngine.IRQ_LINE
        const val IRQ_139 = ImagingCompanionEngines.IRQ_UNIDMAC
        const val IRQ_140 = ImagingCompanionEngines.IRQ_BB000
        const val IRQ_142 = ImagingCompanionEngines.IRQ_RETCH
        const val AUTO_FRAME_IRQ_PERIOD = 2_000_000L
        /**
         * Authentic IMX461 102MP full-resolution still readout frame interval (`388.9 ms` @ 54 MHz).
         * DAT section5 timing table (`0x0283D778`):
         * - `sub[7]` (14-bit Still `T[1]`, `V=1352`): `5.984 fps` = `167.1 ms` (Timer #36 `ev=0x15af` ~152 ms)
         * - `sub[0]` (16-bit RAW Still `T[0]`, `V=2948`): `2.744 fps` = `364.4 ms` (Timer #36 `ev=0x15af` ~330 ms)
         * Pacing 102MP STILL readout (`0xFFF6E018[14:0] >= 1500` lines, LiveView `0x03EE` vs STILL `0x0B8F`)
         * at `21,000,000` ticks (`388.9 ms`) ensures Timer #36 completes before `st=6` exits across BOTH
         * 14-bit JPEG and 16-bit RAW (Uncompressed / Lossless Compressed / Compressed) EFCS/MS captures.
         */
        const val STILL_FRAME_IRQ_PERIOD = 21_000_000L
        const val STILL_READOUT_VLINES_THRESHOLD = 1_500L
        private const val IMX461_HIRES_LV_WIDTH = 3888
        private val AUTO_EDGE_IRQS = intArrayOf(IRQ_100, IRQ_102, IRQ_107, IRQ_108, IRQ_109)
        private val AUTO_EDGE_IRQS_V3 = intArrayOf(IRQ_100, IRQ_101, IRQ_102, IRQ_107, IRQ_108, IRQ_109)

        const val PORT_CORE = "soc-isp.core"
        const val PORT_BANK_50000 = "soc-isp.bank-50000"
        const val PORT_LUT_51000 = "soc-isp.lut-51000"
        const val PORT_LUT_55000 = "soc-isp.lut-55000"
        const val PORT_LUT_59000 = "soc-isp.lut-59000"
        const val PORT_LUT_60000 = "soc-isp.lut-60000"
        const val PORT_BANK_68000 = "soc-isp.bank-68000"
        const val PORT_CONFIG_69000 = "soc-isp.config-69000"
        const val PORT_CONFIG_69100 = "soc-isp.config-69100"
        const val PORT_SLVSEC_BANK1_GLOBAL = SlvsEcRxPhyEngine.PORT_SLVSEC_BANK1_GLOBAL
        const val PORT_SLVSEC_BANK1_CH = SlvsEcRxPhyEngine.PORT_SLVSEC_BANK1_CH
        const val PORT_CONFIG_6B000 = "soc-isp.config-6b000"
        const val PORT_LUT_6D000 = "soc-isp.lut-6d000"
        const val PORT_BANK_6E000 = "soc-isp.bank-6e000"
        const val PORT_RESULT_SURFACE = "soc-isp.result-surface"
        const val PORT_RESULT_SURFACE_V2 = "soc-isp.result-surface-v2"
        const val PORT_RESULT_SURFACE_1_V2 = "soc-isp.result-surface-1-v2"
        const val PORT_STATS_HEAD_0_V2 = "soc-isp.stats-head-0-v2"
        const val PORT_STATS_HEAD_1_V2 = "soc-isp.stats-head-1-v2"
        const val PORT_RESULT_SURFACE_V3 = "soc-isp.result-surface-v3"
        const val PORT_RESULT_SURFACE_1_V3 = "soc-isp.result-surface-1-v3"
        const val PORT_STATS_HEAD_0_V3 = "soc-isp.stats-head-0-v3"
        const val PORT_STATS_HEAD_1_V3 = "soc-isp.stats-head-1-v3"
        const val PORT_STATS_0 = "soc-isp.stats-0"
        const val PORT_STATS_1 = "soc-isp.stats-1"
        const val PORT_LIVE_VIEW_0 = "soc-isp.live-view-0"
        const val PORT_LIVE_VIEW_1 = "soc-isp.live-view-1"
        const val PORT_LIVE_VIEW_2 = "soc-isp.live-view-2"

        const val PORT_LIVE_VIEW_DMA = "soc-isp.live-view-dma"
        const val PORT_INTERRUPT_CONTROL = "soc-isp.interrupt-control"

        const val CORE_SIZE = 0x2ecL
        const val BANK_50000_SIZE = 0x1008L
        const val LUT_51000_SIZE = 0x3ff8
        const val LUT_55000_SIZE = 0x4000
        const val LUT_59000_SIZE = 0x2c00
        const val LUT_60000_SIZE = 0x8000
        const val BANK_68000_SIZE = 0xd04L
        const val CONFIG_69000_SIZE = 0x2cL
        const val CONFIG_69100_SIZE = 0x154L
        const val CONFIG_6B000_SIZE = 0xf8L
        const val LUT_6D000_SIZE = 0x1000
        const val BANK_6E000_SIZE = 0x74L
        const val RESULT_SURFACE_SIZE = 0x1980
        const val RESULT_SURFACE_V2_SIZE = 0x4fa0
        const val STATS_HEAD_V2_SIZE = 0x1060L
        const val STATS_SIZE = 0x1000L
        const val LIVE_VIEW_CH_SIZE = 0x130L
        const val LIVE_VIEW_2_SIZE = 0x30L

        private const val POLL_READY_WORD = 0x10000L
        private const val IRQ106_VALID_MASK = 0x3ffL

        val ALL_MMIO_PORTS: List<String> = listOf(
            PORT_CORE,
            PORT_BANK_50000,
            PORT_LUT_51000,
            PORT_LUT_55000,
            PORT_LUT_59000,
            PORT_BANK_68000,
            PORT_CONFIG_69000,
            PORT_CONFIG_69100,
            PORT_CONFIG_6B000,
            PORT_LUT_6D000,
            PORT_BANK_6E000,
            PORT_RESULT_SURFACE,
            PORT_STATS_0,
            PORT_STATS_1,
            PORT_LIVE_VIEW_0,
            PORT_LIVE_VIEW_1,
            PORT_LIVE_VIEW_2,
        )

        val ALL_MMIO_PORTS_V2: List<String> = ALL_MMIO_PORTS.map { port ->
            if (port == PORT_RESULT_SURFACE) PORT_RESULT_SURFACE_V2 else port
        } + listOf(PORT_RESULT_SURFACE_1_V2, PORT_STATS_HEAD_0_V2, PORT_STATS_HEAD_1_V2)

        val MOVIE_CAPTURE_V3_EXTRA_PORTS: List<String> = listOf(
            MaliV61VpuEngine.PORT_ID,
            PdafHardwareEngine.PORT_ID,
            ImagingCompanionEngines.PORT_MOVDC,
            ImagingCompanionEngines.PORT_COMPANION_80000,
            ImagingCompanionEngines.PORT_COMPANION_B9000,
            ImagingCompanionEngines.PORT_COMPANION_6F000,
            ImagingCompanionEngines.PORT_LSRAW,
            ImagingCompanionEngines.PORT_UNIDMAC_BA000,
            ImagingCompanionEngines.PORT_COMPANION_BB000,
            ImagingCompanionEngines.PORT_RETCH,
            ImagingCompanionEngines.PORT_AXI_HIGH34,
            ImagingCompanionEngines.PORT_VCODEC_IDLE,
            ImagingCompanionEngines.PORT_ISP_82000,
            PORT_SLVSEC_BANK1_GLOBAL,
            PORT_SLVSEC_BANK1_CH,
            ImagingCompanionEngines.PORT_ISP_83000,
            ImagingCompanionEngines.PORT_ISP_84000,
            ImagingCompanionEngines.PORT_ISP_85000,
            ImagingCompanionEngines.PORT_ISP_86000,
            ImagingCompanionEngines.PORT_ISP_87000,
            ImagingCompanionEngines.PORT_ISP_88000,
            ImagingCompanionEngines.PORT_ISP_89000,
            ImagingCompanionEngines.PORT_ISP_8A000,
            ImagingCompanionEngines.PORT_ISP_90000,
            ImagingCompanionEngines.PORT_ISP_98000,
            PORT_LUT_60000,
        )

        val ALL_MMIO_PORTS_V3: List<String> = ALL_MMIO_PORTS.map { port ->
            if (port == PORT_RESULT_SURFACE) PORT_RESULT_SURFACE_V3 else port
        } + listOf(PORT_RESULT_SURFACE_1_V3, PORT_STATS_HEAD_0_V3, PORT_STATS_HEAD_1_V3) + MOVIE_CAPTURE_V3_EXTRA_PORTS

        val DESCRIPTOR = ComponentDescriptor(
            id = COMPONENT_ID,
            version = "0.0.1",
            settings = listOf(
                Setting(
                    key = "profile",
                    kind = ValueKind.TEXT,
                    default = "disabled",
                    choices = listOf("disabled", PROFILE, PROFILE_AUTO_FRAME_IRQS, PROFILE_V2, PROFILE_V3),
                    live = false,
                ),
                Setting(
                    key = "initialFill",
                    kind = ValueKind.INTEGER,
                    default = 0L,
                    minimum = 0.0,
                    maximum = 255.0,
                    live = false,
                ),
                Setting(
                    key = "resultSurfaceFile",
                    kind = ValueKind.TEXT,
                    default = "",
                    live = false,
                ),
                Setting(
                    key = "resultSurfaceSha256",
                    kind = ValueKind.TEXT,
                    default = "",
                    live = false,
                ),
                Setting(
                    key = "autoFrameIrqs",
                    kind = ValueKind.BOOLEAN,
                    default = false,
                    live = false,
                ),
                Setting(
                    key = "slvsEcSilicon",
                    kind = ValueKind.BOOLEAN,
                    default = false,
                    live = false,
                ),
            ),
        )

        private val SNAPSHOT_KEYS = setOf(
            "schema",
            "componentId",
            "scalars",
            "enabled",
            "coreWords",
            "irq106Mask",
            "irq106CompletedCycles",
            "bank50000Words",
            "lut51000Base64",
            "lut55000Base64",
            "lut59000Base64",
            "bank68000Words",
            "config69000Words",
            "config69100Words",
            "irq296Pending",
            "config6b000Words",
            "lut6d000Base64",
            "bank6e000Words",
            "liveView0Words",
            "liveView1Words",
            "liveView2Words",
            "resultSurfaceSha256",
        )

        private fun snapshotLong(value: Any?, field: String, min: Long, max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) {
                "Snapshot field $field requires integer"
            }
            val converted = (value as Number).toLong()
            require(converted in min..max) { "Snapshot field $field out of range: $converted" }
            return converted
        }

        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { it is String }) { "Snapshot field $field requires string keys" }
            return map
        }

        private fun snapshotList(value: Any?, field: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("Snapshot field $field requires list")

        private fun snapshotBoolean(value: Any?, field: String): Boolean =
            value as? Boolean ?: throw IllegalArgumentException("Snapshot field $field requires boolean")

        private fun readLittleWord(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xffL) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xffL) shl 24)

        private fun writeLittleWord(bytes: ByteArray, offset: Int, value: Long) {
            bytes[offset] = (value and 0xffL).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xffL).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xffL).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xffL).toByte()
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun encodeBase64OrNull(bytes: ByteArray?): String? =
            bytes?.takeIf { data -> data.any { it != 0.toByte() } }?.let { Base64.getEncoder().encodeToString(it) }

        private fun decodeBase64Fixed(raw: Any?, field: String, expectedSize: Int): ByteArray {
            if (raw == null) return ByteArray(expectedSize)
            require(raw is String && raw.isNotEmpty()) { "Snapshot $field must be null or non-empty base64" }
            val decoded = Base64.getDecoder().decode(raw)
            require(decoded.size == expectedSize) { "Snapshot $field size mismatch: ${decoded.size} != $expectedSize" }
            return decoded
        }
    }

    class IspController(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent(COMPONENT_ID, context, mapOf("autoFrameIrqs" to false, "slvsEcSilicon" to false) + config), StatefulComponent {
        private var started = false
        private var closed = false
        private val profile = config.getValue("profile") as String
        private val initialFill = (config.getValue("initialFill") as Number).toInt()
        private val resultSurfaceFile = config.getValue("resultSurfaceFile") as String
        private val resultSurfaceSha256Cfg = config.getValue("resultSurfaceSha256") as String
        private val autoFrameIrqsSetting = (config.getOrDefault("autoFrameIrqs", false) as? Boolean)
            ?: throw IllegalArgumentException("soc-isp autoFrameIrqs requires boolean")
        private val slvsEcSilicon = (config.getOrDefault("slvsEcSilicon", false) as? Boolean)
            ?: throw IllegalArgumentException("soc-isp slvsEcSilicon requires boolean")
        private val movieCaptureV3 = profile == PROFILE_V3
        private val extendedResultSurface = profile == PROFILE_V2 || movieCaptureV3
        private val autoFrameIrqs = profile == PROFILE_AUTO_FRAME_IRQS || (extendedResultSurface && autoFrameIrqsSetting)
        private val active = profile == PROFILE || autoFrameIrqs || extendedResultSurface
        private var nextAutoIrqTick = AUTO_FRAME_IRQ_PERIOD
        private var lastFrameSyncTick = Long.MIN_VALUE
        private var firstReceiverSyncTick = Long.MAX_VALUE
        private var irq106ArmedAt: Long? = null
        private val maliV61 = if (movieCaptureV3) {
            MaliV61VpuEngine(
                clockProvider = { context.clock.ticks() },
                sinkProvider = { interruptSink },
                dmaMemoryProvider = { dmaMemory ?: context.ports.find("cpu.memory", DmaMemory::class.java)?.also { dmaMemory = it } },
                onVideoFramePeriodChanged = ::rescheduleFrameDeadline,
            )
        } else {
            null
        }
        private val pdafEngine = if (movieCaptureV3) {
            PdafHardwareEngine(
                clockProvider = { context.clock.ticks() },
                sinkProvider = { interruptSink },
                dmaMemoryProvider = { dmaMemory ?: context.ports.find("cpu.memory", DmaMemory::class.java)?.also { dmaMemory = it } },
                lensProvider = {
                    context.ports.find("mount", MountPort::class.java)?.attachedLens()
                        ?: context.ports.find("lens", LensPort::class.java)?.parameters()
                },
                depthProvider = { context.ports.find(SceneDepthPort.PORT_NAME, SceneDepthPort::class.java) },
                afRoiYProvider = { liveStatsEngine?.activeAfBoxNormY() },
            )
        } else {
            null
        }
        private val companionEngines = if (movieCaptureV3) ImagingCompanionEngines({ context.clock.ticks() }, { interruptSink }) else null

        private val coreWords = if (active) LongArray(IspCoreOffsets.CORE_70000_OFFSETS.size) else LongArray(0)
        private var irq106Mask = 0L
        private var irq106CompletedCycles = 0L
        private val bank50000Words = if (active) LongArray(IspCoreOffsets.BANK_50000_OFFSETS.size) else LongArray(0)
        private val lut51000 = if (active) ByteArray(LUT_51000_SIZE) else null
        private val lut55000 = if (active) ByteArray(LUT_55000_SIZE) else null
        private val lut59000 = if (active) ByteArray(LUT_59000_SIZE) else null
        private val lut60000 = if (movieCaptureV3) ByteArray(LUT_60000_SIZE) else null
        private val bank68000Words = if (active) LongArray(IspCoreOffsets.BANK_68000_OFFSETS.size) else LongArray(0)
        private val config69000Words = if (active) LongArray(IspCoreOffsets.CONFIG_69000_OFFSETS.size) else LongArray(0)
        private val config69100Words = if (active) LongArray(IspCoreOffsets.CONFIG_69100_OFFSETS.size) else LongArray(0)
        private var irq296Pending = false
        private val config6b000Words = if (active) LongArray(IspCoreOffsets.CONFIG_6B000_OFFSETS.size) else LongArray(0)
        private val slvsEc = if (active && slvsEcSilicon) {
            SlvsEcRxPhyEngine(config69000Words, config69100Words, config6b000Words) { interruptSink }
        } else {
            null
        }
        private val bank1GlobalRetained = if (movieCaptureV3 && slvsEc == null) LongArray(IspCoreOffsets.CONFIG_69000_OFFSETS.size) else LongArray(0)
        private val bank1ChRetained = if (movieCaptureV3 && slvsEc == null) LongArray(IspCoreOffsets.CONFIG_69100_OFFSETS.size) else LongArray(0)
        private val lut6d000 = if (active) ByteArray(LUT_6D000_SIZE) else null
        private val bank6e000Words = if (active) LongArray(IspCoreOffsets.BANK_6E000_OFFSETS.size) else LongArray(0)
        private var bank6e000PendingTick: Long? = null
        private var bank6e000Completions = 0L
        private var lastBank6e000Tick = Long.MIN_VALUE
        private var stillReadouts = StillReadoutLatch()
        private var lastLiveViewPumpTick = Long.MIN_VALUE
        private val liveView0Words = if (active) LongArray(IspCoreOffsets.LIVE_VIEW_CH_OFFSETS.size) else LongArray(0)
        private val liveView1Words = if (active) LongArray(IspCoreOffsets.LIVE_VIEW_CH_OFFSETS.size) else LongArray(0)
        private val liveView2Words = if (active) LongArray(IspCoreOffsets.LIVE_VIEW_2_OFFSETS.size) else LongArray(0)
        private val resultSurface: ByteArray?
        private val resultSurfaceSha256: String?
        private val liveStatsEngine: IspStatsSramEngine? = if (active && resultSurfaceFile.isEmpty()) IspStatsSramEngine() else null
        private val colorPipeline = IspColorPipeline()
        private val stillColorPipeline = IspColorPipeline()
        private val dmaEngine = LiveViewDmaEngine()
        private var lastExposureParams: LiveViewDmaEngine.ExposureParams = LiveViewDmaEngine.DEFAULT_EXPOSURE_PARAMS
        private var imx461ExposurePort: Imx461ExposurePort? = null

        /** Binds a live 28 KiB native shadow segment for Dynarmic RO-MMIO fastpath reads of `0xFFF10000..0xFFF1DFFF`. */
        @Suppress("unused")
        fun bindLiveStatsShadow(seg: MemorySegment?): Boolean {
            val engine = liveStatsEngine ?: return false
            engine.bindNativeShadow(seg)
            return true
        }

        private var interruptSink: InterruptSink? = null
        private var dmaMemory: DmaMemory? = null

        init {
            NativeIspSimdBridge.ensureLoaded(context.directory)
            require(profile == "disabled" || active) { "Unsupported soc-isp profile: $profile" }
            if (autoFrameIrqsSetting) {
                require(profile == PROFILE_V2 || profile == PROFILE_V3 || profile == PROFILE_AUTO_FRAME_IRQS) {
                    "soc-isp autoFrameIrqs requires $PROFILE_V2, $PROFILE_V3, or $PROFILE_AUTO_FRAME_IRQS"
                }
            }
            if (slvsEcSilicon) {
                require(active) { "soc-isp slvsEcSilicon requires an active profile" }
            }
            require(initialFill in 0..255) { "initialFill outside 0..255: $initialFill" }
            if (resultSurfaceSha256Cfg.isNotEmpty()) {
                require(resultSurfaceFile.isNotEmpty()) { "resultSurfaceSha256 requires resultSurfaceFile" }
                require(resultSurfaceSha256Cfg.matches(Regex("[a-f0-9]{64}"))) {
                    "resultSurfaceSha256 requires 64 lowercase hex chars"
                }
            }
            if (!active) {
                require(resultSurfaceFile.isEmpty()) { "Disabled soc-isp cannot configure resultSurfaceFile" }
                resultSurface = null
                resultSurfaceSha256 = null
            } else {
                val fillWord = (initialFill.toLong() and 0xffL).let { b ->
                    b or (b shl 8) or (b shl 16) or (b shl 24)
                }
                if (fillWord != 0L) {
                    config69000Words.fill(fillWord)
                    config69100Words.fill(fillWord)
                    config6b000Words.fill(fillWord)
                    slvsEc?.bank1GlobalWords?.fill(fillWord)
                    slvsEc?.bank1ChWords?.fill(fillWord)
                    bank1GlobalRetained.fill(fillWord)
                    bank1ChRetained.fill(fillWord)
                    lut59000!!.fill(initialFill.toByte())
                    bank50000Words.fill(fillWord)
                    bank50000Words[IspCoreOffsets.BANK_50000_INDEX.getValue(0x000)] = 0L
                }
                coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x2e8)] = POLL_READY_WORD
                resultSurface = if (resultSurfaceFile.isEmpty()) {
                    ByteArray(RESULT_SURFACE_SIZE)
                } else {
                    val path = context.dataPath(resultSurfaceFile)
                    require(Files.isRegularFile(path)) { "Missing soc-isp resultSurfaceFile: $path" }
                    require(Files.size(path) == RESULT_SURFACE_SIZE.toLong()) {
                        "soc-isp resultSurfaceFile size must be $RESULT_SURFACE_SIZE"
                    }
                    Files.readAllBytes(path).also { bytes ->
                        val actualSha = sha256Hex(bytes)
                        if (resultSurfaceSha256Cfg.isNotEmpty()) {
                            require(actualSha == resultSurfaceSha256Cfg) {
                                "soc-isp resultSurfaceSha256 mismatch: expected=$resultSurfaceSha256Cfg actual=$actualSha"
                            }
                        }
                    }
                }
                resultSurfaceSha256 = sha256Hex(resultSurface)
                registerPorts()
            }
        }

        private fun requireWordAccess(portId: String, offset: Long, size: Int, limit: Long) {
            check(!closed) { "$portId closed" }
            require(size == 4 && offset >= 0L && offset < limit && offset % 4L == 0L) {
                "$portId requires aligned 32-bit word within [0, $limit): offset=0x${offset.toString(16)} size=$size"
            }
        }

        private fun requireU32(portId: String, value: Long) {
            require(value in 0L..0xffffffffL) { "$portId write value outside u32: $value" }
        }

        private fun registerPorts() {
            // 1. Core Bank & IRQ 106 (0xfff70000..0xfff702ec)
            context.ports.register(id, PORT_CORE, MmioDevice::class.java, object : TickAwareMmioDevice {
                override val clockOrder = 80
                override fun nextDeadline(): Long? = if (autoFrameIrqs && !closed) nextInterestedTick() else null
                override fun nextInterestedTick(): Long =
                    if (autoFrameIrqs && !closed) minOf(nextAutoIrqTick, firstReceiverSyncTick) else Long.MAX_VALUE

                override fun beforeInstruction(tick: Long) {
                    if (!autoFrameIrqs || closed) return
                    if (tick >= firstReceiverSyncTick) {
                        firstReceiverSyncTick = Long.MAX_VALUE
                        (interruptSink as? EnabledEdgeInterruptSink)?.let(::stepPrimaryReceiver)
                    }
                    if (tick >= nextAutoIrqTick) stepAutoFrameIrqs(tick)
                }
                override fun read(offset: Long, size: Int): Long {
                    requireWordAccess(PORT_CORE, offset, size, CORE_SIZE)
                    val off = offset.toInt()
                    val idx = IspCoreOffsets.CORE_70000_INDEX[off]
                        ?: error("$PORT_CORE unmodeled offset 0x${off.toString(16)}")
                    return when (off) {
                        0x000 -> coreWords[idx] or 1L
                        0x00c -> irq106Mask
                        0x2e8 -> POLL_READY_WORD
                        else -> coreWords[idx]
                    }
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_CORE, offset, size, CORE_SIZE)
                    requireU32(PORT_CORE, value)
                    val off = offset.toInt()
                    val idx = IspCoreOffsets.CORE_70000_INDEX[off]
                        ?: error("$PORT_CORE unmodeled offset 0x${off.toString(16)}")
                    when (off) {
                        0x2e8 -> error("$PORT_CORE offset 0x2e8 is read-only")
                        0x004, 0x008 -> {
                            // DAT section4 0x011b822c..0x011b8350 & 0x011be9f8..0x011bea80:
                            // bits [25:16] are write-enable mask for bits [9:0].
                            val writeMask = (value ushr 16) and IRQ106_VALID_MASK
                            if (writeMask != 0L) {
                                coreWords[idx] = (coreWords[idx] and writeMask.inv()) or (value and writeMask)
                            } else {
                                coreWords[idx] = value and IRQ106_VALID_MASK
                            }
                        }
                        0x00c -> {
                            val wasActive = irq106Mask != 0L
                            irq106Mask = irq106Mask and (value and IRQ106_VALID_MASK).inv()
                            if (wasActive && irq106Mask == 0L) {
                                irq106CompletedCycles++
                            }
                        }
                        else -> coreWords[idx] = value
                    }
                }
            })

            // 2. Bank 50000 (0xfff50000..0xfff51008)
            context.ports.register(id, PORT_BANK_50000, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long {
                    requireWordAccess(PORT_BANK_50000, offset, size, BANK_50000_SIZE)
                    val off = offset.toInt()
                    require(off in IspCoreOffsets.BANK_50000_READ_OFFSETS) {
                        "$PORT_BANK_50000 read refused at offset 0x${off.toString(16)}"
                    }
                    val idx = IspCoreOffsets.BANK_50000_INDEX.getValue(off)
                    return if (off == 0x000) bank50000Words[idx] or 1L else bank50000Words[idx]
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_BANK_50000, offset, size, BANK_50000_SIZE)
                    requireU32(PORT_BANK_50000, value)
                    val off = offset.toInt()
                    val idx = IspCoreOffsets.BANK_50000_INDEX[off]
                        ?: error("$PORT_BANK_50000 unmodeled offset 0x${off.toString(16)}")
                    bank50000Words[idx] = value
                }
            })

            // 3. LUT 51000 (0xfff51008..0xfff55000) - write-only
            context.ports.register(id, PORT_LUT_51000, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long =
                    error("$PORT_LUT_51000 is write-only")

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_LUT_51000, offset, size, LUT_51000_SIZE.toLong())
                    requireU32(PORT_LUT_51000, value)
                    writeLittleWord(checkNotNull(lut51000), offset.toInt(), value)
                }
            })

            // 4. LUT 55000 (0xfff55000..0xfff59000) - write-only
            context.ports.register(id, PORT_LUT_55000, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long =
                    error("$PORT_LUT_55000 is write-only")

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_LUT_55000, offset, size, LUT_55000_SIZE.toLong())
                    requireU32(PORT_LUT_55000, value)
                    writeLittleWord(checkNotNull(lut55000), offset.toInt(), value)
                }
            })

            // 5. LUT 59000 (0xfff59000..0xfff5bc00) - write-only with 2 holes
            context.ports.register(id, PORT_LUT_59000, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long =
                    error("$PORT_LUT_59000 is write-only")

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_LUT_59000, offset, size, LUT_59000_SIZE.toLong())
                    requireU32(PORT_LUT_59000, value)
                    val off = offset.toInt()
                    require(IspCoreOffsets.validLut59000Offset(off)) {
                        "$PORT_LUT_59000 prohibited gap at offset 0x${off.toString(16)}"
                    }
                    writeLittleWord(checkNotNull(lut59000), off, value)
                }
            })

            // 6. Bank 68000 (0xfff68000..0xfff68d04) - write-only
            context.ports.register(id, PORT_BANK_68000, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long =
                    error("$PORT_BANK_68000 is write-only")

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_BANK_68000, offset, size, BANK_68000_SIZE)
                    requireU32(PORT_BANK_68000, value)
                    val off = offset.toInt()
                    val idx = IspCoreOffsets.BANK_68000_INDEX[off]
                        ?: error("$PORT_BANK_68000 unmodeled offset 0x${off.toString(16)}")
                    bank68000Words[idx] = value
                }
            })
            if (autoFrameIrqs) {
                context.ports.register(id, CcdFrameClockPort.PORT_ID, CcdFrameClockPort::class.java,
                    object : CcdFrameClockPort {
                        override fun resumeLiveView() = rescheduleFrameDeadline(null)

                        override fun latestMovieFrameSyncTick(): Long? {
                            val edge = lastFrameSyncTick
                            if (closed || edge < 0L || lastLiveViewPumpTick < edge) return null
                            val period = maliV61?.activeVideoFramePeriodTicks() ?: return null
                            return edge.takeIf { context.clock.ticks() - it in 0 until period }
                        }
                    })
            }
            context.ports.register(id, IspStillReadoutPort.PORT_ID, IspStillReadoutPort::class.java,
                IspStillReadoutPort { stillReadouts.processing })
            context.ports.register(id, IspColorStatePort.PORT_ID, IspColorStatePort::class.java, object : IspColorStatePort {
                private fun refresh(): IspColorPipeline {
                    colorPipeline.updateFromBank68000(bank68000Words)
                    return colorPipeline
                }

                private fun rawGains(): Triple<Int, Int, Int> {
                    val lvGain0 = bank68000Words[IspCoreOffsets.BANK_68000_INDEX.getValue(0x108)]
                    val lvGain1 = bank68000Words[IspCoreOffsets.BANK_68000_INDEX.getValue(0x10c)]
                    val rawR = (lvGain0 and 0x7FFFL).toInt()
                    val rawG = ((lvGain0 ushr 16) and 0x7FFFL).toInt()
                    val rawB = (lvGain1 and 0x7FFFL).toInt()
                    return if (rawR > 0 && rawG > 0 && rawB > 0) {
                        Triple(
                            ((rawR.toLong() * 1024L) / 919L).toInt().coerceIn(64, 8192),
                            ((rawG.toLong() * 1024L) / 512L).toInt().coerceIn(64, 8192),
                            ((rawB.toLong() * 1024L) / 972L).toInt().coerceIn(64, 8192),
                        )
                    } else {
                        Triple(1024, 1024, 1024)
                    }
                }

                override val gainR1024: Int get() = rawGains().first
                override val gainG1024: Int get() = rawGains().second
                override val gainB1024: Int get() = rawGains().third
                override val toneCurves: IspToneCurves?
                    get() = refresh().let { cp ->
                        if (cp.hasToneLut) IspToneCurves(
                            cp.toneLutR.copyOf(), cp.toneLutG.copyOf(), cp.toneLutB.copyOf(), cp.signature,
                        ) else null
                    }
                override val ccmQ10: IntArray? get() = refresh().let { if (it.hasCcm) it.ccm.copyOf() else null }
                override val chromaMatQ10: IntArray?
                    get() = refresh().let { cp ->
                        if (!cp.monochromeMode && cp.monoToneDeltaCb == 0 && cp.monoToneDeltaCr == 0 && cp.chromaMatrix.any { it != 0 }) {
                            cp.chromaMatrix.copyOf()
                        } else {
                            null
                        }
                    }
                override val monochromeMode: Boolean get() = refresh().monochromeMode
                override val monoToneDeltaCb10: Int get() = refresh().monoToneDeltaCb
                override val monoToneDeltaCr10: Int get() = refresh().monoToneDeltaCr
            })

            // 6b. LUT 60000 (0xfff60000..0xfff68000) - 32 KiB ISP shading/color LUT SRAM (v3)
            if (movieCaptureV3) {
                context.ports.register(id, PORT_LUT_60000, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 80
                    override fun read(offset: Long, size: Int): Long {
                        requireWordAccess(PORT_LUT_60000, offset, size, LUT_60000_SIZE.toLong())
                        return readLittleWord(checkNotNull(lut60000), offset.toInt())
                    }

                    override fun write(offset: Long, size: Int, value: Long) {
                        requireWordAccess(PORT_LUT_60000, offset, size, LUT_60000_SIZE.toLong())
                        requireU32(PORT_LUT_60000, value)
                        writeLittleWord(checkNotNull(lut60000), offset.toInt(), value)
                    }
                })
            }

            // 7. Config 69000 (0xfff69000..0xfff6902c, SLVS-EC RX Bank 0 Global)
            context.ports.register(id, PORT_CONFIG_69000, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long {
                    requireWordAccess(PORT_CONFIG_69000, offset, size, CONFIG_69000_SIZE)
                    val off = offset.toInt()
                    val rx = slvsEc
                    if (rx != null) return rx.readGlobal(0, off)
                    val idx = IspCoreOffsets.CONFIG_69000_INDEX[off]
                        ?: error("$PORT_CONFIG_69000 unmodeled offset 0x${off.toString(16)}")
                    return config69000Words[idx]
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_CONFIG_69000, offset, size, CONFIG_69000_SIZE)
                    requireU32(PORT_CONFIG_69000, value)
                    val off = offset.toInt()
                    val rx = slvsEc
                    if (rx != null) {
                        rx.writeGlobal(0, off, value)
                        return
                    }
                    val idx = IspCoreOffsets.CONFIG_69000_INDEX[off]
                        ?: error("$PORT_CONFIG_69000 unmodeled offset 0x${off.toString(16)}")
                    config69000Words[idx] = value
                }
            })

            // 8. Config 69100 (0xfff69100..0xfff69254, SLVS-EC RX Bank 0 Ch0/Ch1 w/ IRQ 296/297/298)
            context.ports.register(id, PORT_CONFIG_69100, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long {
                    requireWordAccess(PORT_CONFIG_69100, offset, size, CONFIG_69100_SIZE)
                    val off = offset.toInt()
                    val rx = slvsEc
                    if (rx != null) return rx.readChannel(0, off)
                    val idx = IspCoreOffsets.CONFIG_69100_INDEX[off]
                        ?: error("$PORT_CONFIG_69100 unmodeled offset 0x${off.toString(16)}")
                    return config69100Words[idx]
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_CONFIG_69100, offset, size, CONFIG_69100_SIZE)
                    requireU32(PORT_CONFIG_69100, value)
                    val off = offset.toInt()
                    val rx = slvsEc
                    val normalMask = if (rx == null) 3L else SlvsEcRxPhyEngine.NORMAL_IRQ_MASK
                    val receiverWasArmed = off == 0x00c &&
                        config69100Words[IspCoreOffsets.CONFIG_69100_INDEX.getValue(off)] and normalMask != 0L
                    if (rx != null) {
                        rx.writeChannel(0, off, value)
                        irq296Pending = rx.irq296Asserted
                    } else {
                        require(off in IspCoreOffsets.CONFIG_69100_WRITE_OFFSETS) {
                            "$PORT_CONFIG_69100 write refused at offset 0x${off.toString(16)}"
                        }
                        val idx = IspCoreOffsets.CONFIG_69100_INDEX.getValue(off)
                        if (off == 0x008) {
                            config69100Words[idx] = config69100Words[idx] and (value and 3L).inv()
                            if (irq296Pending && (config69100Words[idx] and 3L) == 0L) {
                                interruptSink?.setLine(IRQ_296, false)
                                irq296Pending = false
                            }
                        } else {
                            config69100Words[idx] = value
                        }
                    }
                    if (autoFrameIrqs && off == 0x00c) {
                        if (value and normalMask == 0L) {
                            firstReceiverSyncTick = Long.MAX_VALUE
                        } else if (!receiverWasArmed) {
                            // The receiver restarts before the next full-resolution readout completes.
                            firstReceiverSyncTick = context.clock.ticks() + AUTO_FRAME_IRQ_PERIOD
                        }
                    }
                }
            })

            // 8b. SLVS-EC RX Bank 1 (0xfff6a000..0xfff6a02c & 0xfff6a100..0xfff6a254)
            if (slvsEc != null || movieCaptureV3) {
                context.ports.register(id, PORT_SLVSEC_BANK1_GLOBAL, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 80
                    override fun read(offset: Long, size: Int): Long {
                        requireWordAccess(PORT_SLVSEC_BANK1_GLOBAL, offset, size, CONFIG_69000_SIZE)
                        val off = offset.toInt()
                        if (slvsEc != null) return slvsEc.readGlobal(1, off)
                        val idx = IspCoreOffsets.CONFIG_69000_INDEX[off]
                            ?: error("$PORT_SLVSEC_BANK1_GLOBAL unmodeled offset 0x${off.toString(16)}")
                        return bank1GlobalRetained[idx]
                    }

                    override fun write(offset: Long, size: Int, value: Long) {
                        requireWordAccess(PORT_SLVSEC_BANK1_GLOBAL, offset, size, CONFIG_69000_SIZE)
                        requireU32(PORT_SLVSEC_BANK1_GLOBAL, value)
                        val off = offset.toInt()
                        if (slvsEc != null) {
                            slvsEc.writeGlobal(1, off, value)
                            return
                        }
                        val idx = IspCoreOffsets.CONFIG_69000_INDEX[off]
                            ?: error("$PORT_SLVSEC_BANK1_GLOBAL unmodeled offset 0x${off.toString(16)}")
                        bank1GlobalRetained[idx] = value
                    }
                })
                context.ports.register(id, PORT_SLVSEC_BANK1_CH, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 80
                    override fun read(offset: Long, size: Int): Long {
                        requireWordAccess(PORT_SLVSEC_BANK1_CH, offset, size, CONFIG_69100_SIZE)
                        val off = offset.toInt()
                        if (slvsEc != null) return slvsEc.readChannel(1, off)
                        val idx = IspCoreOffsets.CONFIG_69100_INDEX[off]
                            ?: error("$PORT_SLVSEC_BANK1_CH unmodeled offset 0x${off.toString(16)}")
                        return bank1ChRetained[idx]
                    }

                    override fun write(offset: Long, size: Int, value: Long) {
                        requireWordAccess(PORT_SLVSEC_BANK1_CH, offset, size, CONFIG_69100_SIZE)
                        requireU32(PORT_SLVSEC_BANK1_CH, value)
                        val off = offset.toInt()
                        if (slvsEc != null) {
                            slvsEc.writeChannel(1, off, value)
                            irq296Pending = slvsEc.irq296Asserted
                            return
                        }
                        require(off in IspCoreOffsets.CONFIG_69100_WRITE_OFFSETS) {
                            "$PORT_SLVSEC_BANK1_CH write refused at offset 0x${off.toString(16)}"
                        }
                        val idx = IspCoreOffsets.CONFIG_69100_INDEX.getValue(off)
                        bank1ChRetained[idx] = value
                    }
                })
            }

            // 9. Config 6b000 (0xfff6b000..0xfff6b0f8, SLVS-EC PHY)
            context.ports.register(id, PORT_CONFIG_6B000, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long {
                    requireWordAccess(PORT_CONFIG_6B000, offset, size, CONFIG_6B000_SIZE)
                    val off = offset.toInt()
                    val rx = slvsEc
                    if (rx != null) return rx.readPhy(off)
                    val idx = IspCoreOffsets.CONFIG_6B000_INDEX[off]
                        ?: error("$PORT_CONFIG_6B000 unmodeled offset 0x${off.toString(16)}")
                    return config6b000Words[idx]
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_CONFIG_6B000, offset, size, CONFIG_6B000_SIZE)
                    requireU32(PORT_CONFIG_6B000, value)
                    val off = offset.toInt()
                    val rx = slvsEc
                    if (rx != null) {
                        rx.writePhy(off, value)
                        return
                    }
                    val idx = IspCoreOffsets.CONFIG_6B000_INDEX[off]
                        ?: error("$PORT_CONFIG_6B000 unmodeled offset 0x${off.toString(16)}")
                    config6b000Words[idx] = value
                }
            })

            // 10. LUT 6d000 (0xfff6d000..0xfff6e000)
            context.ports.register(id, PORT_LUT_6D000, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long {
                    requireWordAccess(PORT_LUT_6D000, offset, size, LUT_6D000_SIZE.toLong())
                    return readLittleWord(checkNotNull(lut6d000), offset.toInt())
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_LUT_6D000, offset, size, LUT_6D000_SIZE.toLong())
                    requireU32(PORT_LUT_6D000, value)
                    writeLittleWord(checkNotNull(lut6d000), offset.toInt(), value)
                }
            })

            // 11. Bank 6e000 (0xfff6e000..0xfff6e074, CCDIF Live RAW+OB Write-DMA, GIC IRQ 105 rising-edge in v3)
            context.ports.register(id, PORT_BANK_6E000, MmioDevice::class.java, object : TickAwareMmioDevice {
                override val clockOrder = 80
                override val clockIndex = 11
                override fun nextDeadline(): Long? = if (movieCaptureV3 && !closed) bank6e000PendingTick else null
                override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE
                override fun beforeInstruction(tick: Long) {
                    if (!movieCaptureV3 || closed) return
                    val due = bank6e000PendingTick ?: return
                    if (tick < due) return
                    bank6e000PendingTick = null
                    val idx40 = IspCoreOffsets.BANK_6E000_INDEX.getValue(0x040)
                    val idx68 = IspCoreOffsets.BANK_6E000_INDEX.getValue(0x068)
                    val idx6c = IspCoreOffsets.BANK_6E000_INDEX.getValue(0x06c)
                    bank6e000Words[idx40] = bank6e000Words[idx40] and 1L.inv()
                    bank6e000Words[idx6c] = bank6e000Words[idx6c] or 1L
                    bank6e000Completions++
                    lastBank6e000Tick = tick
                    // DAT section4 0x011e337c..0x011e3410 & 0x011e3e54: GIC IRQ 105 (lid 0x53) is rising-edge triggered.
                    if ((bank6e000Words[idx68] and 1L) != 0L) {
                        val sink = interruptSink ?: context.ports.find("soc-gic.lines", InterruptSink::class.java)?.also {
                            interruptSink = it
                        }
                        sink?.setLine(IRQ_105, true)
                        sink?.setLine(IRQ_105, false)
                    }
                }

                override fun read(offset: Long, size: Int): Long {
                    requireWordAccess(PORT_BANK_6E000, offset, size, BANK_6E000_SIZE)
                    val off = offset.toInt()
                    val idx = IspCoreOffsets.BANK_6E000_INDEX[off]
                        ?: error("$PORT_BANK_6E000 unmodeled offset 0x${off.toString(16)}")
                    return if (off == 0x000) bank6e000Words[idx] or 1L else bank6e000Words[idx]
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_BANK_6E000, offset, size, BANK_6E000_SIZE)
                    requireU32(PORT_BANK_6E000, value)
                    val off = offset.toInt()
                    val idx = IspCoreOffsets.BANK_6E000_INDEX[off]
                        ?: error("$PORT_BANK_6E000 unmodeled offset 0x${off.toString(16)}")
                    if (off in setOf(0x014, 0x018, 0x020, 0x024)) {
                        require(IspCoreOffsets.valid6e000ConfigWord(off, value)) {
                            "$PORT_BANK_6E000 config word out of DAT bounds at 0x${off.toString(16)}: 0x${value.toString(16)}"
                        }
                    }
                    when (off) {
                        0x008 -> {
                            // +0x008 bit 8 (0x100) is W1C IRQ 105 status; other control bits are retained
                            val nextBit8 = bank6e000Words[idx] and 0x100L and value.inv()
                            bank6e000Words[idx] = (value and 0x100L.inv()) or nextBit8
                        }
                        0x06c -> {
                            if (movieCaptureV3) {
                                // DAT section4 0x011e3360: W1C bit 0 clear in IRQ 105 ISR
                                val nextBit0 = bank6e000Words[idx] and 1L and value.inv()
                                bank6e000Words[idx] = (value and 1L.inv()) or nextBit0
                            } else {
                                bank6e000Words[idx] = value
                            }
                        }
                        0x040 -> {
                            bank6e000Words[idx] = value
                            if (movieCaptureV3 && (value and 1L) != 0L) {
                                bank6e000PendingTick = context.clock.ticks() + ImagingCompanionEngines.COMPLETION_DELAY_TICKS
                            }
                        }
                        0x018 -> {
                            val previousLines = bank6e000Words[idx] and 0x7FFFL
                            bank6e000Words[idx] = value
                            val period = activeFrameIrqPeriod()
                            val expPort = sensorExposurePort()
                            val isStillMode = expPort != null && (
                                expPort.mdsel8 == Imx461ExposurePort.MDSEL_STILL_14BIT ||
                                    (!expPort.liveViewTimingActive && Imx461ExposurePort.isStillCaptureMode(expPort.mdsel8))
                                )
                            if (period == STILL_FRAME_IRQ_PERIOD && isStillMode) {
                                val sensor = context.ports.find("cmos", SensorPort::class.java)
                                val lens = context.ports.find("mount", MountPort::class.java)?.attachedLens()
                                    ?: context.ports.find("lens", LensPort::class.java)?.parameters()
                                val stillEff = Imx461ExposurePort.computeEffectiveStillExposure(
                                    port = expPort,
                                    nominalDurationNanos = LiveViewDmaEngine.NOMINAL_DURATION_NANOS,
                                    nominalIso = LiveViewDmaEngine.NOMINAL_ISO,
                                )
                                val overrideExposure = stillEff?.let { eff ->
                                    val base = stillReadouts.currentIntegration() ?: computeLiveExposureParams()
                                    LiveViewDmaEngine.ExposureParams(
                                        durationNanos = eff.durationNanos,
                                        iso = eff.iso,
                                        gainR1024 = base.gainR1024,
                                        gainG1024 = base.gainG1024,
                                        gainB1024 = base.gainB1024,
                                    )
                                }
                                stillReadouts.capture(context.clock.ticks(), sensor, lens, overrideExposure)
                            } else if (previousLines >= STILL_READOUT_VLINES_THRESHOLD && (value and 0x7FFFL) < STILL_READOUT_VLINES_THRESHOLD) {
                                stillReadouts.endReadout()
                            }
                            if (autoFrameIrqs) {
                                // Each still is configured at the next input sync, including consecutive
                                // captures with identical geometry. The full readout interval follows that sync.
                                val firstSyncPeriod = if (period == STILL_FRAME_IRQ_PERIOD) AUTO_FRAME_IRQ_PERIOD else period
                                nextAutoIrqTick = minOf(nextAutoIrqTick, context.clock.ticks() + firstSyncPeriod)
                            }
                        }
                        else -> bank6e000Words[idx] = value
                    }
                }
            })

            // 12. Result surface (v1: bank 0 0xfff11060..0xfff129e0; v2: bank 0 0xfff11060..0xfff16000 & bank 1 0xfff18060..0xfff1d000) - read-only
            if (extendedResultSurface) {
                fun resultSurfaceV2Device(portId: String): MmioDevice = object : MmioDevice {
                    override val clockOrder = 80
                    override fun read(offset: Long, size: Int): Long {
                        requireWordAccess(portId, offset, size, RESULT_SURFACE_V2_SIZE.toLong())
                        val off = offset.toInt()
                        require(IspCoreOffsets.validResultSurfaceV2Offset(off)) {
                            "$portId unmodeled offset 0x${off.toString(16)}"
                        }
                        val stats = liveStatsEngine
                        if (stats != null && stats.aeFramesUpdated > 0L) {
                            return stats.readResultSurfaceWord(off)
                        }
                        return if (IspCoreOffsets.validResultSurfaceOffset(off)) {
                            readLittleWord(checkNotNull(resultSurface), off)
                        } else {
                            0L
                        }
                    }

                    override fun write(offset: Long, size: Int, value: Long): Unit =
                        error("$portId is read-only")
                }
                val surf0 = if (movieCaptureV3) PORT_RESULT_SURFACE_V3 else PORT_RESULT_SURFACE_V2
                val surf1 = if (movieCaptureV3) PORT_RESULT_SURFACE_1_V3 else PORT_RESULT_SURFACE_1_V2
                context.ports.register(id, surf0, MmioDevice::class.java, resultSurfaceV2Device(surf0))
                context.ports.register(id, surf1, MmioDevice::class.java, resultSurfaceV2Device(surf1))
            } else {
                context.ports.register(id, PORT_RESULT_SURFACE, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 80
                    override fun read(offset: Long, size: Int): Long {
                        requireWordAccess(PORT_RESULT_SURFACE, offset, size, RESULT_SURFACE_SIZE.toLong())
                        val off = offset.toInt()
                        require(IspCoreOffsets.validResultSurfaceOffset(off)) {
                            "$PORT_RESULT_SURFACE unmodeled offset 0x${off.toString(16)}"
                        }
                        val stats = liveStatsEngine
                        if (stats != null && stats.aeFramesUpdated > 0L) {
                            return stats.readResultSurfaceWord(off)
                        }
                        return readLittleWord(checkNotNull(resultSurface), off)
                    }

                    override fun write(offset: Long, size: Int, value: Long): Unit =
                        error("$PORT_RESULT_SURFACE is read-only")
                })
            }

            // 13 & 14. Stats buffers 0 & 1 (0xfff16000..0xfff17000, 0xfff1d000..0xfff1e000) - read-only Histogram SRAM
            // Under v2/v3 also expose Bank 0 & Bank 1 selector 0..5 stats heads (0xfff10000..0xfff11060, 0xfff17000..0xfff18060).
            fun statsDevice(portId: String, limit: Long = STATS_SIZE, isHead: Boolean = false): MmioDevice = object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long {
                    check(!closed) { "$portId closed" }
                    require(size in setOf(1, 2, 4) && offset >= 0L && offset + size <= limit) {
                        "$portId invalid read offset=0x${offset.toString(16)} size=$size"
                    }
                    val stats = liveStatsEngine ?: return 0L
                    return if (isHead) {
                        if (stats.aeFramesUpdated > 0L) stats.readHead(offset.toInt(), size) else 0L
                    } else {
                        if (stats.histFramesUpdated > 0L) stats.readHistogram(offset.toInt(), size) else 0L
                    }
                }

                override fun write(offset: Long, size: Int, value: Long): Unit =
                    error("$portId is read-only")
            }
            context.ports.register(id, PORT_STATS_0, MmioDevice::class.java, statsDevice(PORT_STATS_0))
            context.ports.register(id, PORT_STATS_1, MmioDevice::class.java, statsDevice(PORT_STATS_1))
            if (extendedResultSurface) {
                val head0 = if (movieCaptureV3) PORT_STATS_HEAD_0_V3 else PORT_STATS_HEAD_0_V2
                val head1 = if (movieCaptureV3) PORT_STATS_HEAD_1_V3 else PORT_STATS_HEAD_1_V2
                context.ports.register(id, head0, MmioDevice::class.java, statsDevice(head0, STATS_HEAD_V2_SIZE, isHead = true))
                context.ports.register(id, head1, MmioDevice::class.java, statsDevice(head1, STATS_HEAD_V2_SIZE, isHead = true))
            }

            // 15 & 16. Live-View Channel 0 & 1 (0xfff72000..0xfff72130, 0xfff73000..0xfff73130)
            fun liveViewChannelDevice(portId: String, words: LongArray, triggerDma: Boolean): MmioDevice = object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long {
                    requireWordAccess(portId, offset, size, LIVE_VIEW_CH_SIZE)
                    val off = offset.toInt()
                    require(off in IspCoreOffsets.LIVE_VIEW_CH_READ_OFFSETS) {
                        "$portId read refused at offset 0x${off.toString(16)}"
                    }
                    val idx = IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(off)
                    val stored = words[idx]
                    return when (off) {
                        0x000 -> stored or 1L
                        0x0dc -> {
                            val ctrl = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0d4)]
                            val cleared = stored and LiveViewDmaEngine.IDLE_BIT_8.inv()
                            if ((ctrl and LiveViewDmaEngine.ENABLE_BIT_28) != 0L) cleared
                            else cleared or LiveViewDmaEngine.IDLE_BIT_8
                        }
                        else -> stored
                    }
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(portId, offset, size, LIVE_VIEW_CH_SIZE)
                    requireU32(portId, value)
                    val off = offset.toInt()
                    val idx = IspCoreOffsets.LIVE_VIEW_CH_INDEX[off]
                        ?: error("$portId unmodeled offset 0x${off.toString(16)}")
                    words[idx] = value
                    if (triggerDma && off == 0x0d4 && (value and LiveViewDmaEngine.ENABLE_BIT_28) != 0L) {
                        pumpLiveViewChannel(context.clock.ticks(), words)
                    }
                }
            }
            context.ports.register(id, PORT_LIVE_VIEW_0, MmioDevice::class.java, liveViewChannelDevice(PORT_LIVE_VIEW_0, liveView0Words, true))
            context.ports.register(id, PORT_LIVE_VIEW_1, MmioDevice::class.java, liveViewChannelDevice(PORT_LIVE_VIEW_1, liveView1Words, true))

            // 17. Live-View Bank 2 / Merger (0xfff74000..0xfff74030)
            context.ports.register(id, PORT_LIVE_VIEW_2, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                override fun read(offset: Long, size: Int): Long {
                    requireWordAccess(PORT_LIVE_VIEW_2, offset, size, LIVE_VIEW_2_SIZE)
                    val off = offset.toInt()
                    val idx = IspCoreOffsets.LIVE_VIEW_2_INDEX.getValue(off)
                    val stored = liveView2Words[idx]
                    return when (off) {
                        0x000 -> stored or 1L
                        0x028 -> {
                            val ctrl = liveView2Words[IspCoreOffsets.LIVE_VIEW_2_INDEX.getValue(0x024)]
                            val cleared = stored and LiveViewDmaEngine.IDLE_BIT_8.inv()
                            if ((ctrl and LiveViewDmaEngine.ENABLE_BIT_28) != 0L) cleared
                            else cleared or LiveViewDmaEngine.IDLE_BIT_8
                        }
                        else -> stored
                    }
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    requireWordAccess(PORT_LIVE_VIEW_2, offset, size, LIVE_VIEW_2_SIZE)
                    requireU32(PORT_LIVE_VIEW_2, value)
                    val off = offset.toInt()
                    val idx = IspCoreOffsets.LIVE_VIEW_2_INDEX.getValue(off)
                    liveView2Words[idx] = value
                }
            })

            // Live-view DMA pump port
            context.ports.register(id, PORT_LIVE_VIEW_DMA, LiveViewDmaPort::class.java, object : LiveViewDmaPort {
                override fun pumpLiveViewDma(tick: Long): Boolean = this@IspController.pumpLiveViewDma(tick)
            })

            // Interrupt injection/observation port
            context.ports.register(id, PORT_INTERRUPT_CONTROL, IspInterruptPort::class.java, object : IspInterruptPort {
                override fun signalInterrupt(line: Int, withStatus: Boolean, statusMask: Long) =
                    this@IspController.signalInterrupt(line, withStatus, statusMask)

                override fun irq106State(): Map<String, Any?> = this@IspController.irq106Receipt()
            })

            // 18..28. Opt-in M8 Movie / Capture / Playback-Menu Companion Engines (isp-banks-movie-capture-v3)
            if (movieCaptureV3) {
                val closedCheck = { closed }
                val vpu = checkNotNull(maliV61)
                val pdaf = checkNotNull(pdafEngine)
                val comp = checkNotNull(companionEngines)
                context.ports.register(id, MaliV61VpuEngine.PORT_ID, MmioDevice::class.java, vpu.createMmioDevice(closedCheck))
                context.ports.register(id, PdafHardwareEngine.PORT_ID, MmioDevice::class.java, pdaf.createMmioDevice(closedCheck))
                context.ports.register(
                    id,
                    ImagingCompanionEngines.PORT_MOVDC,
                    MmioDevice::class.java,
                    comp.movdc.createMmioDevice(closedCheck) { words -> handleMovdcComplete(words) },
                )
                context.ports.register(id, ImagingCompanionEngines.PORT_COMPANION_80000, MmioDevice::class.java, comp.companion80000.createMmioDevice(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_COMPANION_B9000, MmioDevice::class.java, comp.companionB9000.createMmioDevice(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_COMPANION_6F000, MmioDevice::class.java, comp.companion6F000.createMmioDevice(closedCheck))
                context.ports.register(
                    id,
                    ImagingCompanionEngines.PORT_LSRAW,
                    MmioDevice::class.java,
                    comp.createLsrawMmioDevice(closedCheck) {
                        dmaMemory ?: context.ports.find("cpu.memory", DmaMemory::class.java)?.also { dmaMemory = it }
                    },
                )
                context.ports.register(id, ImagingCompanionEngines.PORT_UNIDMAC_BA000, MmioDevice::class.java, comp.unidmacBA000.createMmioDevice(closedCheck))
                context.ports.register(
                    id,
                    ImagingCompanionEngines.PORT_COMPANION_BB000,
                    MmioDevice::class.java,
                    comp.companionBB000.createMmioDevice(closedCheck) { words -> handleCompanionBB000Complete(words) },
                )
                context.ports.register(id, ImagingCompanionEngines.PORT_RETCH, MmioDevice::class.java, comp.createRetchMmioDevice(closedCheck) {
                    dmaMemory ?: context.ports.find("cpu.memory", DmaMemory::class.java)?.also { dmaMemory = it }
                })
                context.ports.register(id, ImagingCompanionEngines.PORT_AXI_HIGH34, MmioDevice::class.java, comp.createAxiHigh34Device(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_VCODEC_IDLE, MmioDevice::class.java, comp.createVcodecIdleDevice(closedCheck))
                context.ports.register(
                    id,
                    ImagingCompanionEngines.PORT_ISP_82000,
                    MmioDevice::class.java,
                    comp.createIsp82000Device(closedCheck) { words -> handleStillCaptureDma(context.clock.ticks(), words) },
                )
                context.ports.register(id, ImagingCompanionEngines.PORT_ISP_83000, MmioDevice::class.java, comp.createIsp83000Device(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_ISP_84000, MmioDevice::class.java, comp.createIsp84000Device(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_ISP_85000, MmioDevice::class.java, comp.createIsp85000Device(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_ISP_86000, MmioDevice::class.java, comp.createIsp86000Device(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_ISP_87000, MmioDevice::class.java, comp.createIsp87000Device(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_ISP_88000, MmioDevice::class.java, comp.createIsp88000Device(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_ISP_89000, MmioDevice::class.java, comp.createIsp89000Device(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_ISP_8A000, MmioDevice::class.java, comp.createIsp8a000Device(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_ISP_90000, MmioDevice::class.java, comp.createIsp90000Device(closedCheck))
                context.ports.register(id, ImagingCompanionEngines.PORT_ISP_98000, MmioDevice::class.java, comp.createIsp98000Device(closedCheck))
            }
        }

        private var stillDmaPumps = 0L
        private var lastStillDmaInfo: Map<String, Any?> = emptyMap()

        private fun handleStillCaptureDma(tick: Long, words: LongArray) {
            if (!active || closed) return
            val dstAddrLo = words[0x070 / 4]
            val dstAddrHi = words[0x074 / 4]
            val w50 = words[0x050 / 4]
            val w54 = words[0x054 / 4]
            val w58 = words[0x058 / 4]
            val w5c = words[0x05C / 4]
            val w80 = words[0x080 / 4]
            val w88 = words[0x088 / 4]
            val w8c = words[0x08C / 4]
            val w90 = words[0x090 / 4]
            val lo50 = (w50 and 0x3FFFL).toInt()
            val hi50 = ((w50 ushr 16) and 0x3FFFL).toInt()
            val lo54 = (w54 and 0x3FFFL).toInt()
            val hi54 = ((w54 ushr 16) and 0x3FFFL).toInt()
            val lo58 = (w58 and 0x3FFFL).toInt()
            val targetAddr = ((dstAddrHi and 3L) shl 32) or dstAddrLo
            val rawOutput = words[0x008 / 4] and 0x103L == 0x101L
            // DAT live RECC_S (0xFFF82000) STILL/RAW-conversion geometry:
            //   Pass 1 (RAW->YC, 0x008=0x490100, 0x00C=RAW src):
            //     0x050[29:16] = height (e.g. 0x223c = 8764, low16 == 0)
            //     0x058[13:0]  = width  (e.g. 0x2d90 = 11664)
            //     0x080[17:0]  = stride bytes (e.g. 0x7bc0 = 31680 B = 15840 px * 2)
            //   Pass 2 (YC->YC post-process for Monochrome/ACROS/Grain, 0x008=0x102, 0x03C=YC src):
            //     0x050[29:16] = height (e.g. 0x2230 = 8752), 0x050[13:0] = width (0x2d90 = 11664)
            //     0x058[13:0]  = width  (0x2d90 = 11664), 0x080[17:0] = stride bytes (0x7bc0 = 31680 B)
            // Keep fallback for synthetic test layout where width is in 0x050[13:0] and height in 0x058[13:0].
            val realSiliconLayout = lo58 >= 16 && (lo58 and 1) == 0 && hi50 >= 8 &&
                ((lo50 == 0 && lo54 == 0) || (lo50 == lo58 && w80 >= lo58 * 2L))
            val width = when {
                realSiliconLayout -> lo58
                lo50 >= 16 && (lo50 and 1) == 0 -> lo50
                lo54 >= 16 && (lo54 and 1) == 0 -> lo54
                else -> 0
            }
            val height = when {
                realSiliconLayout -> hi50
                lo58 >= 8 -> lo58
                hi54 >= 8 -> hi54
                hi50 >= 8 -> hi50
                else -> 0
            }
            val stride = when {
                w80 >= width * 2L && w80 <= 0x3FFFFL -> w80.toInt()
                !realSiliconLayout && hi50 >= width * 2 -> hi50
                else -> width * 2
            }
            if (realSiliconLayout && hi54 == 0 && words[0x008 / 4] in 0x490100L..0x490101L) {
                stillReadouts.beginProcessing()
            }
            var pumped = false
            if (targetAddr != 0L && width >= 16 && height >= 8) {
                val sensor = context.ports.find("cmos", SensorPort::class.java)
                val memory = dmaMemory ?: context.ports.find("cpu.memory", DmaMemory::class.java)?.also { dmaMemory = it }
                val lens = context.ports.find("mount", MountPort::class.java)?.attachedLens()
                    ?: context.ports.find("lens", LensPort::class.java)?.parameters()
                val lvCtrlIdx = IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0d4)
                val lvActive = ((liveView0Words[lvCtrlIdx] or liveView1Words[lvCtrlIdx]) and LiveViewDmaEngine.ENABLE_BIT_28) != 0L
                val offlinePlaybackRawDev = lastLiveViewPumpTick != Long.MIN_VALUE &&
                    !lvActive &&
                    (tick - lastLiveViewPumpTick) > 50_000_000L &&
                    (lastBank6e000Tick == Long.MIN_VALUE || (tick - lastBank6e000Tick) > 50_000_000L)
                // During offline Playback RAW CONVERSION, the IMX461 sensor is powered down / idle,
                // so stale LiveView SHR/PGC registers do not belong to the stored .RAF frame; use
                // calibrated nominal optical exposure and 1.0x full-frame geometry instead.
                val exposure = if (offlinePlaybackRawDev) {
                    LiveViewDmaEngine.DEFAULT_EXPOSURE_PARAMS
                } else {
                    computeLiveExposureParams()
                }
                if (realSiliconLayout && words[0x06c / 4] == 3L && words[0x008 / 4] == 0x410100L) {
                    val firstRow = ((w54 ushr 16) and 0x3fffL).toInt()
                    // RECC_S requests an upper and lower strip. Only the second request gives
                    // the exact active frame height; the first exposure is retained across both.
                    val frameHeight = if (firstRow == 0) height * 2 else firstRow + height
                    colorPipeline.updateFromBank68000(bank68000Words)
                    pumped = dmaEngine.renderMovieTileStrip(
                        tick, targetAddr, width, frameHeight, firstRow, height, stride,
                        sensor, memory, lens, exposure, colorPipeline,
                    )
                } else if (rawOutput && realSiliconLayout) {
                    val firstRow = ((words[0x01c / 4] ushr 16) and 0x3fff).toInt()
                    // RAW reader +0x4c is the crop origin, +0x50 is the inclusive extent.
                    // The two RECC_S transfers share its padded, 16-line Bayer envelope.
                    val reader = companionEngines?.companion6F000?.words
                    val readerEnd = if (reader == null) 0 else {
                        val top = (reader[0x4c / 4] ushr 16) and 0x3fff
                        val extent = (reader[0x50 / 4] ushr 16) and 0x3fff
                        (top + extent + 1).toInt()
                    }
                    val frameHeight = maxOf(firstRow + height, (readerEnd + 15) and 15.inv())
                    pumped = dmaEngine.renderStillBayerStrip(
                        tick, targetAddr, width, frameHeight, firstRow, height, stride,
                        sensorExposurePort()?.stillReadoutBitDepth ?: 14,
                        sensor, memory, lens, exposure, stillReadouts.processing,
                    )
                } else {
                    val comp = companionEngines
                    if (comp != null) {
                        stillColorPipeline.updateFromStillIspBanks(
                            isp86000Words = comp.isp86000Regs,
                            isp87000Words = comp.isp87000Regs,
                            isp88000Words = comp.isp88000Regs,
                            isp98000Words = comp.isp98000Regs,
                            fallbackBank68000 = bank68000Words,
                        )
                    } else {
                        stillColorPipeline.updateFromBank68000(bank68000Words)
                    }
                    val cropFactor35mm = if (offlinePlaybackRawDev) 1.0 else computeStill35mmCropFactor()
                    pumped = dmaEngine.renderOpticalUyvyToDma(
                        tick = tick,
                        addr = targetAddr,
                        width = width,
                        height = height,
                        stride = stride,
                        sensor = sensor,
                        memory = memory,
                        lens = lens,
                        exposure = exposure,
                        colorPipeline = stillColorPipeline,
                        cropFactor = cropFactor35mm,
                        stillReadout = if (offlinePlaybackRawDev) null else stillReadouts.processing,
                    )
                }
                if (pumped) stillDmaPumps++
            }
            lastStillDmaInfo = linkedMapOf(
                "dstAddr" to "0x${targetAddr.toString(16)}",
                "w50" to "0x${w50.toString(16)}",
                "w54" to "0x${w54.toString(16)}",
                "w58" to "0x${w58.toString(16)}",
                "w5c" to "0x${w5c.toString(16)}",
                "w88" to "0x${w88.toString(16)}",
                "w8c" to "0x${w8c.toString(16)}",
                "w90" to "0x${w90.toString(16)}",
                "width" to width,
                "height" to height,
                "stride" to stride,
                "pumped" to pumped,
            )
        }

        private fun sensorExposurePort(): Imx461ExposurePort? =
            imx461ExposurePort ?: context.ports.find(Imx461ExposurePort.PORT_ID, Imx461ExposurePort::class.java)?.also {
                imx461ExposurePort = it
            }

        private fun computeLiveExposureParams(): LiveViewDmaEngine.ExposureParams {
            val port = sensorExposurePort()
            val eff = Imx461ExposurePort.computeEffectiveExposure(
                port = port,
                nominalDurationNanos = LiveViewDmaEngine.NOMINAL_DURATION_NANOS,
                nominalIso = LiveViewDmaEngine.NOMINAL_ISO,
            )
            val durationNanos = eff.durationNanos
            val iso = eff.iso

            // Live-View RGB digital gain from 0xFFF68108 ([14:0] R, [30:16] G) & 0xFFF6810C ([14:0] B).
            // Real IMX461 CFA daylight WB ratio in GFX100S 2.13 is (R:G:B) ~= (919:512:972) at unity exposure;
            // since synthetic CMOS already outputs white-balanced linear RGB, divide out the CFA baseline
            // so neutral daylight WB renders color-neutral while WB shifts and exposure-sim scaling apply cleanly.
            val lvGain0 = bank68000Words[IspCoreOffsets.BANK_68000_INDEX.getValue(0x108)]
            val lvGain1 = bank68000Words[IspCoreOffsets.BANK_68000_INDEX.getValue(0x10c)]
            val rawR = (lvGain0 and 0x7FFFL).toInt()
            val rawG = ((lvGain0 ushr 16) and 0x7FFFL).toInt()
            val rawB = (lvGain1 and 0x7FFFL).toInt()
            val (gainR1024, gainG1024, gainB1024) = if (rawR > 0 && rawG > 0 && rawB > 0) {
                val r1024 = ((rawR.toLong() * LiveViewDmaEngine.UNITY_GAIN_1024) / 919L).toInt().coerceIn(64, 8192)
                val g1024 = ((rawG.toLong() * LiveViewDmaEngine.UNITY_GAIN_1024) / 512L).toInt().coerceIn(64, 8192)
                val b1024 = ((rawB.toLong() * LiveViewDmaEngine.UNITY_GAIN_1024) / 972L).toInt().coerceIn(64, 8192)
                Triple(r1024, g1024, b1024)
            } else {
                Triple(LiveViewDmaEngine.UNITY_GAIN_1024, LiveViewDmaEngine.UNITY_GAIN_1024, LiveViewDmaEngine.UNITY_GAIN_1024)
            }
            return LiveViewDmaEngine.ExposureParams(
                durationNanos = durationNanos,
                iso = iso,
                gainR1024 = gainR1024,
                gainG1024 = gainG1024,
                gainB1024 = gainB1024,
            ).also { lastExposureParams = it }
        }

        private fun buildStatsConfig(): LiveViewDmaEngine.StatsConfig? {
            val engine = liveStatsEngine ?: return null
            val optics = context.ports.find("lens", LensPort::class.java) as? OpticalSceneFilter
            val blackLevel16 = optics?.sensorBlackLevelAdu(16) ?: 0
            for (window in 0 until IspStatsSramEngine.BLACK_REFERENCE_WINDOWS) {
                val xOffset = 0x1AC + window * 8
                val x = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(xOffset)]
                val y = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(xOffset + 4)]
                engine.updateBlackReferenceWindow(window, x, y, blackLevel16)
            }
            val reg5c = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x05c)]
            val reg60 = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x060)]
            val reg68 = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x068)]
            val reg6c = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x06c)]
            val reg124 = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x124)]
            val reg128 = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x128)]
            val reg12c = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x12c)]
            val reg134 = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x134)]
            val reg138 = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x138)]
            val reg13c = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x13c)]
            val reg1fc = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x1fc)]
            val crop6e0c = bank6e000Words[IspCoreOffsets.BANK_6E000_INDEX.getValue(0x00c)]
            val crop6e10 = bank6e000Words[IspCoreOffsets.BANK_6E000_INDEX.getValue(0x010)]
            val crop6e14 = bank6e000Words[IspCoreOffsets.BANK_6E000_INDEX.getValue(0x014)]
            val crop6e18 = bank6e000Words[IspCoreOffsets.BANK_6E000_INDEX.getValue(0x018)]
            // Front-end OB registers 0xFFF50034/38: 4x 11-bit signed OB offsets.
            // Verified live on GXUP0004.DAT (S5 0x024B9364): when 0xFFF50034/38 == 0, firmware
            // subtracts OB=0 (net-linear stats expected), so never inject a synthetic +1024 pedestal!
            val obWord0 = bank50000Words[IspCoreOffsets.BANK_50000_INDEX.getValue(0x034)]
            val obWord1 = bank50000Words[IspCoreOffsets.BANK_50000_INDEX.getValue(0x038)]
            fun parseOb(raw11: Int): Int {
                val signed = if ((raw11 and 0x400) != 0) raw11 - 0x800 else raw11
                return kotlin.math.abs(signed)
            }
            val obR = parseOb((obWord0 and 0x7FFL).toInt())
            val obG1 = parseOb(((obWord0 ushr 16) and 0x7FFL).toInt())
            val obG2 = parseOb((obWord1 and 0x7FFL).toInt())
            val obB = parseOb(((obWord1 ushr 16) and 0x7FFL).toInt())
            return LiveViewDmaEngine.StatsConfig(
                engine = engine,
                reg5c = reg5c,
                reg60 = reg60,
                reg68 = reg68,
                reg6c = reg6c,
                reg124 = reg124,
                reg128 = reg128,
                reg12c = reg12c,
                reg134 = reg134,
                reg138 = reg138,
                reg13c = reg13c,
                reg1fc = reg1fc,
                crop6e0c = crop6e0c,
                crop6e10 = crop6e10,
                crop6e14 = crop6e14,
                crop6e18 = crop6e18,
                obR = obR,
                obG1 = obG1,
                obB = obB,
                obG2 = obG2,
            )
        }

        private fun pumpLiveViewChannel(tick: Long, words: LongArray): Boolean {
            if (!active || closed) return false
            val sensor = context.ports.find("cmos", SensorPort::class.java)
            val memory = dmaMemory ?: context.ports.find("cpu.memory", DmaMemory::class.java)?.also { dmaMemory = it }
            val lens = context.ports.find("mount", MountPort::class.java)?.attachedLens()
                ?: context.ports.find("lens", LensPort::class.java)?.parameters()
            val yAddr = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0c4)]
            val yHigh = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0c0)]
            val uvAddr = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0cc)]
            val uvHigh = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0c8)]
            val stride = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0d0)]
            val sizeWord = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x018)]
            val ctrl = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0d4)]
            val peaking = MfAssistPeakingFilter.fromBank68000(bank68000Words)
            val exposure = computeLiveExposureParams()
            val statsConfig = buildStatsConfig()
            colorPipeline.updateFromBank68000(bank68000Words)
            val isLvCh0 = words === liveView0Words
            // DAT S4 0x011C0CF0: LV Ch0/Ch1 +0xD4[18:16] encodes 6 packed/semi-planar output formats (0..5):
            //   - mode 4 (0x10040000) = Face/Eye-AF packed YYUV422 [Y0, Y1, Cb, Cr] (640x480 ring)
            //   - modes 2/3/5 (0x10020000/0x10030000/0x10050000) = LCD PIC0/PIC1 packed UYVY422 [U, Y0, V, Y1]
            //     (including MF Dual Display PIC0 on LV Ch0 with ctrl=0x10030000 -> BUG-V3-5-3).
            val formatMode = ((ctrl ushr 16) and 0x7L).toInt()
            val isYyuv422 = isLvCh0 && formatMode == 4
            val cropZoom = computeLiveViewCropZoom(words)
            val pumped = dmaEngine.pumpLiveViewDma(
                tick,
                yAddr,
                stride,
                sizeWord,
                ctrl,
                sensor,
                memory,
                lens,
                uvAddr,
                yHigh,
                uvHigh,
                peaking,
                exposure,
                statsConfig,
                colorPipeline,
                yyuv422 = isYyuv422,
                cropZoom = cropZoom,
            )
            if (pumped) {
                lastLiveViewPumpTick = tick
                stillReadouts.observeIntegration(sensorExposurePort()?.mdsel8, exposure)
            }
            return pumped
        }

        /**
         * Computes the hardware live-view zoom crop fraction from the real SPX4 resizer
         * (`0xFFF72000`/`0xFFF73000` `+0x0C` inDims, `+0x18` outDims, `+0x1C/+0x20` pre-decim + Q12 step)
         * combined with the `CCDIF` (`0xFFF6E000` `+0x04/+0x14/+0x18`) sensor ROI window:
         * - Normal 1.0x LiveView (`1302x983` -> `1024x768`, `hStep=0x1421`) & Dual Display left `PIC0`
         *   (`3888x1470` -> `704x504`, `hDecim=4, hStep=0x1702`): `fracW >= 0.90` -> `FULL_FRAME`.
         * - MF Dual Display right `PIC1` (`3888x1470` -> `288x192`, `hDecim=2, hStep=0x1434`): `fracW ~= 0.187` (`5.35x`).
         * - MF Focus Check `scr=0x0121`:
         *   - Z1 (`6E014=0x03e2_078d`, `inW=1933`, `hStep=0x102c`): `0.493 * 0.535 ~= 0.264` (`3.79x`).
         *   - Z3 (`6E004=1, 6E014=0x02ee`, `inW=750`, `hStep=0x0b5e`): `0.193 * 0.970 ~= 0.187` (`5.35x`).
         *   - Z2 (`6E004=1, 6E014=0x02ee`, `inW=750`, `hStep=0x0791`): `0.193 * 0.645 ~= 0.124` (`8.04x`).
         */
        private fun computeLiveViewCropZoom(words: LongArray): LiveViewDmaEngine.CropZoom {
            val inSizeWord = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x00c)]
            val outSizeWord = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x018)]
            val hScaleWord = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x01c)]
            val vScaleWord = words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x020)]
            if (inSizeWord == 0L || outSizeWord == 0L || hScaleWord == 0L || vScaleWord == 0L) {
                return LiveViewDmaEngine.CropZoom.FULL_FRAME
            }
            val inW = (inSizeWord and 0xFFFFL).toInt() + 1
            val inH = ((inSizeWord ushr 16) and 0xFFFFL).toInt() + 1
            val outW = (outSizeWord and 0xFFFFL).toInt() + 1
            val outH = ((outSizeWord ushr 16) and 0xFFFFL).toInt() + 1
            val hDecim = maxOf(1, ((hScaleWord ushr 16) and 0xFFFFL).toInt())
            val hStepQ12 = (hScaleWord and 0xFFFFL).toInt()
            if (inW < 64 || inH < 64 || outW < 2 || outH < 2 || hStepQ12 <= 0) {
                return LiveViewDmaEngine.CropZoom.FULL_FRAME
            }
            val covInW = outW.toDouble() * hDecim.toDouble() * (hStepQ12.toDouble() / 4096.0)
            val resizerFracW = (covInW / inW.toDouble()).coerceIn(0.05, 1.0)

            val reg04 = bank6e000Words[IspCoreOffsets.BANK_6E000_INDEX.getValue(0x004)]
            val reg14 = bank6e000Words[IspCoreOffsets.BANK_6E000_INDEX.getValue(0x014)]
            val reg18 = bank6e000Words[IspCoreOffsets.BANK_6E000_INDEX.getValue(0x018)]
            val c1StartX = ((reg14 ushr 16) and 0xFFFFL).toInt()
            val c1W = (reg14 and 0xFFFFL).toInt()
            val c1H = (reg18 and 0xFFFFL).toInt()
            val sensorFracW = when {
                c1StartX >= 128 && c1W >= 256 -> (c1W.toDouble() / (c1W + 2 * c1StartX).toDouble()).coerceIn(0.10, 1.0)
                (reg04 and 1L) != 0L && c1W in 256..1024 && c1H in 256..1024 ->
                    (c1W.toDouble() / IMX461_HIRES_LV_WIDTH.toDouble()).coerceIn(0.10, 1.0)
                else -> 1.0
            }

            val totalFracW = (sensorFracW * resizerFracW).coerceIn(0.08, 1.0)
            return if (totalFracW >= LiveViewDmaEngine.CropZoom.ZOOM_DEADBAND_MAX) {
                LiveViewDmaEngine.CropZoom.FULL_FRAME
            } else {
                LiveViewDmaEngine.CropZoom(fracW = totalFracW)
            }
        }

        /**
         * Derives the real-silicon 35mm FORMAT MODE crop factor (`~1.23x`) for STILL captures from
         * `LV1` resizer registers (`0xFFF73000 +0x0C/+0x18/+0x1C`: `hStepQ12 = 0x1088` vs `0x1421` -> `fracW = 0.8126`)
         * while ignoring MF Focus Check (`sensorFracW < 0.90` or `resizerFracW < 0.72`).
         */
        private fun computeStill35mmCropFactor(): Double {
            val inSizeWord = liveView1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x00c)]
            val outSizeWord = liveView1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x018)]
            val hScaleWord = liveView1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x01c)]
            if (inSizeWord == 0L || outSizeWord == 0L || hScaleWord == 0L) return 1.0
            val inW = (inSizeWord and 0xFFFFL).toInt() + 1
            val outW = (outSizeWord and 0xFFFFL).toInt() + 1
            val hDecim = maxOf(1, ((hScaleWord ushr 16) and 0xFFFFL).toInt())
            val hStepQ12 = (hScaleWord and 0xFFFFL).toInt()
            if (inW < 64 || outW < 2 || hStepQ12 <= 0) return 1.0
            val reg04 = bank6e000Words[IspCoreOffsets.BANK_6E000_INDEX.getValue(0x004)]
            val reg14 = bank6e000Words[IspCoreOffsets.BANK_6E000_INDEX.getValue(0x014)]
            val c1StartX = ((reg14 ushr 16) and 0xFFFFL).toInt()
            val c1W = (reg14 and 0xFFFFL).toInt()
            if (c1StartX >= 128 && c1W >= 256) return 1.0
            if ((reg04 and 1L) != 0L && c1W in 256..1024) return 1.0
            val resizerFracW = (outW.toDouble() * hDecim.toDouble() * (hStepQ12.toDouble() / 4096.0)) / inW.toDouble()
            return if (resizerFracW in 0.72..0.88) (1.0 / resizerFracW).coerceIn(1.0, 2.0) else 1.0
        }

        private fun handleMovdcComplete(words: LongArray) {
            if (!active || closed) return
            val memory = dmaMemory ?: context.ports.find("cpu.memory", DmaMemory::class.java)?.also { dmaMemory = it } ?: return
            val lens = context.ports.find("mount", MountPort::class.java)?.attachedLens()
                ?: context.ports.find("lens", LensPort::class.java)?.parameters()
            MovdcMfAssistEngine.onMovdcComplete(words, memory, liveView1Words, lens)
        }

        private fun handleCompanionBB000Complete(words: LongArray) {
            if (!active || closed) return
            val memory = dmaMemory ?: context.ports.find("cpu.memory", DmaMemory::class.java)?.also { dmaMemory = it } ?: return
            MovdcMfAssistEngine.onCompanionBB000Complete(words, memory)
        }

        private fun pumpLiveViewDma(tick: Long): Boolean {
            val ch1 = pumpLiveViewChannel(tick, liveView1Words)
            val ch0 = pumpLiveViewChannel(tick, liveView0Words)
            return ch1 || ch0
        }

        private fun activeFrameIrqPeriod(): Long {
            maliV61?.activeVideoFramePeriodTicks()?.let { return it }
            val vLines = bank6e000Words[IspCoreOffsets.BANK_6E000_INDEX.getValue(0x018)] and 0x7FFFL
            if (vLines < STILL_READOUT_VLINES_THRESHOLD) return AUTO_FRAME_IRQ_PERIOD
            // The full-height CCDIF window survives several input syncs after CCDTG returns to
            // live view. Only an active still integration needs the long readout interval.
            val sensor = sensorExposurePort()
            return if (sensor?.liveViewTimingActive == true || Imx461ExposurePort.isMovieShutterMode(sensor?.mdsel8 ?: 0)) {
                AUTO_FRAME_IRQ_PERIOD
            } else {
                STILL_FRAME_IRQ_PERIOD
            }
        }

        private fun rescheduleFrameDeadline(period: Long?) {
            if (!autoFrameIrqs || closed) return
            val targetPeriod = period ?: activeFrameIrqPeriod()
            val maxNext = context.clock.ticks() + targetPeriod
            if (nextAutoIrqTick > maxNext) {
                nextAutoIrqTick = maxNext
            }
        }

        private fun stepPrimaryReceiver(sink: EnabledEdgeInterruptSink) {
            val rx = slvsEc
            if (!sink.lineEnabled(IRQ_296) || (rx?.irq296Asserted ?: irq296Pending)) return
            val statusIndex = IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x008)
            val enableIndex = IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x00c)
            val mask = config69100Words[enableIndex] and (if (rx == null) 3L else SlvsEcRxPhyEngine.NORMAL_IRQ_MASK)
            if (mask == 0L || config69100Words[statusIndex] and mask != 0L) return
            val line = sink.edgeState(IRQ_296)
            if (line.edge || line.high || line.pending) return

            // The receiver's normal status is level-triggered until the guest acknowledges it.
            firstReceiverSyncTick = Long.MAX_VALUE
            if (rx != null) {
                rx.raiseNormalIrq(0, 0, mask)
                irq296Pending = rx.irq296Asserted
            } else {
                config69100Words[statusIndex] = config69100Words[statusIndex] or mask
                irq296Pending = true
                sink.setLine(IRQ_296, true)
            }
        }

        private fun stepAutoFrameIrqs(tick: Long) {
            val due = nextAutoIrqTick
            val framePeriod = activeFrameIrqPeriod()
            val anchor = if (tick >= due && tick - due < framePeriod) due else tick
            lastFrameSyncTick = anchor
            nextAutoIrqTick = anchor + framePeriod
            val sink = interruptSink ?: context.ports.find("soc-gic.lines", InterruptSink::class.java)?.also {
                interruptSink = it
            } ?: return
            val enabledSink = sink as? EnabledEdgeInterruptSink ?: return

            stepPrimaryReceiver(enabledSink)
            val rx = slvsEc
            if (rx != null) {
                if (enabledSink.lineEnabled(IRQ_298) && !rx.irq298Asserted) {
                    val idx108 = IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x108)
                    val idx10c = IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x10c)
                    val state298 = enabledSink.edgeState(IRQ_298)
                    val enabledMask = config69100Words[idx10c] and SlvsEcRxPhyEngine.NORMAL_IRQ_MASK
                    if (enabledMask != 0L &&
                        (config69100Words[idx108] and enabledMask) == 0L &&
                        !state298.edge && !state298.high && !state298.pending
                    ) {
                        rx.raiseNormalIrq(0, 1, enabledMask)
                    }
                }
            }

            // 2. ISP frame-sync / live-view channel edge IRQs (100, 102, 107, 108, 109; +101, 104 in v3)
            val edgeIrqs = if (movieCaptureV3) AUTO_EDGE_IRQS_V3 else AUTO_EDGE_IRQS
            for (line in edgeIrqs) {
                if (!enabledSink.lineEnabled(line)) continue
                val st = enabledSink.edgeState(line)
                if (st.edge && !st.high && !st.pending) {
                    if (line == IRQ_107) {
                        pumpLiveViewChannel(tick, liveView0Words)
                    } else if (line == IRQ_108) {
                        pumpLiveViewChannel(tick, liveView1Words)
                    }
                    sink.setLine(line, true)
                    sink.setLine(line, false)
                }
            }

            // 3. Core ISP frame-completion IRQ 106 (W1C 0xfff7000c, armed one frame after enable)
            if (!enabledSink.lineEnabled(IRQ_106)) {
                irq106ArmedAt = null
            } else if (irq106Mask == 0L) {
                val st106 = enabledSink.edgeState(IRQ_106)
                val distActive = (context.ports.find("soc-gic.distributor", MmioDevice::class.java)
                    ?.read(0x30c, 4)?.and(0x400L) ?: 0L) != 0L
                if (st106.edge && !st106.high && !st106.pending && !distActive) {
                    val armed = irq106ArmedAt
                    if (armed == null) {
                        irq106ArmedAt = tick
                    } else if (tick - armed >= framePeriod) {
                        val coupled = sink as? CoupledEdgeInterruptSink
                        if (coupled != null) {
                            val en004 = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x004)] and IRQ106_VALID_MASK
                            val en008 = coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x008)] and IRQ106_VALID_MASK
                            val enabledBits = en004 or en008
                            val baseMask = if (enabledSink.lineEnabled(IRQ_108)) 0x41L else 0x01L
                            val mask = if (enabledBits != 0L) enabledBits else baseMask
                            coupled.pulseWithDevice(IRQ_106, 2) { irq106Mask = mask }
                        }
                    }
                }
            }
        }

        private fun signalInterrupt(line: Int, withStatus: Boolean, statusMask: Long) {
            check(active && !closed) { "soc-isp interrupt control inactive or closed" }
            val sink = interruptSink ?: checkNotNull(context.ports.find("soc-gic.lines", InterruptSink::class.java)) {
                "soc-isp requires soc-gic.lines"
            }.also { interruptSink = it }
            val enabledSink = sink as EnabledEdgeInterruptSink
            require(enabledSink.lineEnabled(line)) { "IRQ $line is not enabled in GIC" }
            val state = enabledSink.edgeState(line)
            val expectedEdge = line == IRQ_106
            require(!state.high && !state.pending && state.edge == expectedEdge) {
                "IRQ $line GIC edge state invalid: $state"
            }
            when (line) {
                IRQ_106 -> {
                    require(!withStatus) { "IRQ 106 does not accept withStatus=true" }
                    require(statusMask in 1L..IRQ106_VALID_MASK) {
                        "IRQ 106 status mask must be in 0x1..0x3ff"
                    }
                    require(irq106Mask == 0L) { "IRQ 106 device status already latched: 0x${irq106Mask.toString(16)}" }
                    context.ports.find("soc-gic.distributor", MmioDevice::class.java)?.let { dist ->
                        require(dist.read(0x30c, 4) and 0x400L == 0L) { "IRQ 106 is active in GIC" }
                    }
                    (dmaMemory ?: context.ports.find("cpu.memory", DmaMemory::class.java)?.also { dmaMemory = it })?.let { mem ->
                        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        mem.read(0x01871ad0L, buf)
                        require(buf.getInt(0) == 0x011b7f14) { "IRQ106 image handler is not registered" }
                    }
                    val coupled = requireNotNull(sink as? CoupledEdgeInterruptSink) {
                        "GIC lacks CoupledEdgeInterruptSink for IRQ 106"
                    }
                    coupled.pulseWithDevice(IRQ_106, 2) { irq106Mask = statusMask }
                }
                IRQ_296 -> {
                    val rx = slvsEc
                    if (rx != null) {
                        val mask = if (statusMask == 1L && withStatus) 2L else (statusMask and SlvsEcRxPhyEngine.NORMAL_IRQ_MASK)
                        require(mask != 0L) { "IRQ 296 requires valid SLVS-EC normal mask bits in 0xF3" }
                        require(!rx.irq296Asserted) { "IRQ 296 already pending" }
                        rx.raiseNormalIrq(0, 0, mask)
                        irq296Pending = rx.irq296Asserted
                    } else {
                        require(statusMask == 1L) { "IRQ 296 requires statusMask=1" }
                        require(!irq296Pending) { "IRQ 296 already pending" }
                        val idx08 = IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x008)
                        val idx0c = IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x00c)
                        require((config69100Words[idx0c] and 2L) != 0L && (config69100Words[idx08] and 2L) == 0L) {
                            "IRQ 296 requires enable bit1 in 0xfff6910c and clear status bit1 in 0xfff69108"
                        }
                        if (withStatus) {
                            config69100Words[idx08] = config69100Words[idx08] or 2L
                        }
                        irq296Pending = true
                        sink.setLine(IRQ_296, true)
                    }
                }
                IRQ_297 -> {
                    val rx = requireNotNull(slvsEc) { "IRQ 297 requires slvsEcSilicon=true" }
                    require(!rx.irq297Asserted) { "IRQ 297 already pending" }
                    require(statusMask in 1L..SlvsEcRxPhyEngine.PCS_ERR_MASK) {
                        "IRQ 297 requires non-zero error mask"
                    }
                    if ((statusMask and SlvsEcRxPhyEngine.LINK_ERR_MASK) != 0L) {
                        rx.raiseChannelError(0, 0, linkBits = statusMask and SlvsEcRxPhyEngine.LINK_ERR_MASK)
                    } else {
                        rx.raisePcsError(0, statusMask)
                    }
                }
                IRQ_298 -> {
                    val rx = requireNotNull(slvsEc) { "IRQ 298 requires slvsEcSilicon=true" }
                    val mask = statusMask and SlvsEcRxPhyEngine.NORMAL_IRQ_MASK
                    require(mask != 0L) { "IRQ 298 requires valid SLVS-EC normal mask bits in 0xF3" }
                    require(!rx.irq298Asserted) { "IRQ 298 already pending" }
                    rx.raiseNormalIrq(0, 1, mask)
                }
                else -> throw IllegalArgumentException("Unsupported soc-isp interrupt line: $line")
            }
        }

        private fun irq106Receipt(): Map<String, Any?> = if (!active) emptyMap() else mapOf(
            "mask" to irq106Mask,
            "completedCycles" to irq106CompletedCycles,
            "phase" to if (irq106Mask != 0L) "LATCHED" else if (irq106CompletedCycles > 0L) "FINISHED" else "IDLE",
            "confidence" to "HYPOTHESIS",
        )

        override fun start() {
            check(!closed) { "soc-isp closed" }
            if (active) {
                dmaMemory = context.ports.find("cpu.memory", DmaMemory::class.java)
                interruptSink = checkNotNull(context.ports.find("soc-gic.lines", InterruptSink::class.java)) {
                    "soc-isp requires soc-gic.lines"
                }
                val rx = slvsEc
                if (rx != null) {
                    rx.syncIrqLines()
                } else {
                    interruptSink?.setLine(IRQ_296, irq296Pending)
                }
                maliV61?.syncIrqLine()
                pdafEngine?.syncIrqLine()
                companionEngines?.syncIrqLines()
            }
            started = true
        }

        override fun close() {
            if (started && active) {
                val rx = slvsEc
                if (rx != null) {
                    rx.clearIrqsOnClose()
                    irq296Pending = false
                } else if (irq296Pending) {
                    irq296Pending = false
                    interruptSink?.setLine(IRQ_296, false)
                }
                maliV61?.clearIrqOnClose()
                pdafEngine?.clearIrqOnClose()
                companionEngines?.clearIrqsOnClose()
            }
            closed = true
        }

        override fun validateSettings(settings: Map<String, Any>) {
            check(!closed) { "soc-isp closed" }
            require(settings.all { values[it.key] == it.value }) { "soc-isp settings require restart" }
        }

        override fun applySettings(settings: Map<String, Any>) {
            validateSettings(settings)
        }

        override fun additionalMmioPorts(): List<String> {
            val base = when {
                !active -> emptyList()
                movieCaptureV3 -> ALL_MMIO_PORTS_V3
                extendedResultSurface -> ALL_MMIO_PORTS_V2
                else -> ALL_MMIO_PORTS
            }
            return if (slvsEc != null && !movieCaptureV3) base + listOf(PORT_SLVSEC_BANK1_GLOBAL, PORT_SLVSEC_BANK1_CH) else base
        }

        override fun interruptRoutes(): List<InterruptRoute> {
            val base = if (autoFrameIrqs) {
                listOf(
                    InterruptRoute("soc-gic.lines", IRQ_100..IRQ_100),
                    InterruptRoute("soc-gic.lines", IRQ_102..IRQ_102),
                    InterruptRoute("soc-gic.lines", IRQ_106..IRQ_109),
                    InterruptRoute("soc-gic.lines", IRQ_296..IRQ_296),
                )
            } else if (active) {
                listOf(
                    InterruptRoute("soc-gic.lines", IRQ_106..IRQ_106),
                    InterruptRoute("soc-gic.lines", IRQ_296..IRQ_296),
                )
            } else {
                emptyList()
            }
            val withSlvs = if (slvsEc != null) base + InterruptRoute("soc-gic.lines", IRQ_297..IRQ_298) else base
            if (!movieCaptureV3) return withSlvs
            return withSlvs + listOf(
                InterruptRoute("soc-gic.lines", IRQ_101..IRQ_101),
                InterruptRoute("soc-gic.lines", IRQ_105..IRQ_105),
                InterruptRoute("soc-gic.lines", IRQ_110..IRQ_110),
                InterruptRoute("soc-gic.lines", IRQ_122..IRQ_122),
                InterruptRoute("soc-gic.lines", IRQ_126..IRQ_127),
                InterruptRoute("soc-gic.lines", IRQ_129..IRQ_129),
                InterruptRoute("soc-gic.lines", IRQ_134..IRQ_134),
                InterruptRoute("soc-gic.lines", IRQ_137..IRQ_140),
                InterruptRoute("soc-gic.lines", IRQ_142..IRQ_142),
            )
        }

        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "enabled" to active,
            "ports" to additionalMmioPorts(),
            "irq106" to irq106Receipt(),
            "irq296Pending" to (slvsEc?.irq296Asserted ?: irq296Pending),
            "lut51000Sha256" to lut51000?.let(::sha256Hex),
            "lut55000Sha256" to lut55000?.let(::sha256Hex),
            "lut59000Sha256" to lut59000?.let(::sha256Hex),
            "lut6d000Sha256" to lut6d000?.let(::sha256Hex),
            "resultSurfaceSha256" to resultSurfaceSha256,
            "basis" to "DAT section4/5 ISP register addresses & directional runs; W1C IRQ106/296/297/298, SLVS-EC RX/PHY, bounds, and CMOS live-view DMA are HYPOTHESIS; physical identity/timing UNKNOWN",
        ) + (if (movieCaptureV3) {
            mapOf(
                "maliV61" to maliV61?.state(),
                "pdaf" to pdafEngine?.state(),
                "companionEngines" to companionEngines?.state(),
                "bank6e000Completions" to bank6e000Completions,
                "stillDmaPumps" to stillDmaPumps,
                "lastStillDmaInfo" to lastStillDmaInfo,
            )
        } else {
            emptyMap()
        }) + (if (slvsEc != null) {
            mapOf("slvsEc" to slvsEc.state())
        } else {
            emptyMap()
        }) + (if (liveStatsEngine != null) {
            mapOf(
                "stats3a" to liveStatsEngine.state(),
                "colorPipeline" to colorPipeline.state(),
                "liveExposure" to mapOf(
                    "durationNanos" to lastExposureParams.durationNanos,
                    "iso" to lastExposureParams.iso,
                    "gainR1024" to lastExposureParams.gainR1024,
                    "gainG1024" to lastExposureParams.gainG1024,
                    "gainB1024" to lastExposureParams.gainB1024,
                    "reg5c" to "0x${coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x05c)].toString(16)}",
                    "reg60" to "0x${coreWords[IspCoreOffsets.CORE_70000_INDEX.getValue(0x060)].toString(16)}",
                    "feOb0" to "0x${bank50000Words[IspCoreOffsets.BANK_50000_INDEX.getValue(0x034)].toString(16)}",
                    "feOb1" to "0x${bank50000Words[IspCoreOffsets.BANK_50000_INDEX.getValue(0x038)].toString(16)}",
                    "feGain0" to "0x${bank50000Words[IspCoreOffsets.BANK_50000_INDEX.getValue(0x044)].toString(16)}",
                    "feGain1" to "0x${bank50000Words[IspCoreOffsets.BANK_50000_INDEX.getValue(0x048)].toString(16)}",
                    "lvGain0" to "0x${bank68000Words[IspCoreOffsets.BANK_68000_INDEX.getValue(0x108)].toString(16)}",
                    "lvGain1" to "0x${bank68000Words[IspCoreOffsets.BANK_68000_INDEX.getValue(0x10c)].toString(16)}",
                ),
            )
        } else {
            emptyMap()
        })

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "soc-isp closed" }
            val result = linkedMapOf<String, Any?>(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to active,
                "coreWords" to coreWords.toList(),
                "irq106Mask" to irq106Mask,
                "irq106CompletedCycles" to irq106CompletedCycles,
                "bank50000Words" to bank50000Words.toList(),
                "lut51000Base64" to encodeBase64OrNull(lut51000),
                "lut55000Base64" to encodeBase64OrNull(lut55000),
                "lut59000Base64" to encodeBase64OrNull(lut59000),
                "bank68000Words" to bank68000Words.toList(),
                "config69000Words" to config69000Words.toList(),
                "config69100Words" to config69100Words.toList(),
                "irq296Pending" to (slvsEc?.irq296Asserted ?: irq296Pending),
                "config6b000Words" to config6b000Words.toList(),
                "lut6d000Base64" to encodeBase64OrNull(lut6d000),
                "bank6e000Words" to bank6e000Words.toList(),
                "liveView0Words" to liveView0Words.toList(),
                "liveView1Words" to liveView1Words.toList(),
                "liveView2Words" to liveView2Words.toList(),
                "resultSurfaceSha256" to resultSurfaceSha256,
            )
            if (firstReceiverSyncTick != Long.MAX_VALUE) {
                result["receiverSyncDelay"] = (firstReceiverSyncTick - context.clock.ticks()).coerceAtLeast(0L)
            }
            if (movieCaptureV3) {
                stillReadouts.save()?.let { result["stillReadout"] = it }
                result["maliV61"] = checkNotNull(maliV61).saveSnapshot()
                result["pdaf"] = checkNotNull(pdafEngine).saveSnapshot()
                result["companionEngines"] = checkNotNull(companionEngines).saveSnapshot()
                result["lut60000Base64"] = encodeBase64OrNull(lut60000)
            }
            if (slvsEc != null) {
                result["slvsEc"] = slvsEc.saveSnapshot()
            }
            return result
        }

        private fun decodeU32Array(raw: Any?, field: String, expectedSize: Int): LongArray {
            val list = snapshotList(raw, field)
            require(list.size == expectedSize) { "Snapshot $field size mismatch: ${list.size} != $expectedSize" }
            return LongArray(expectedSize) { i -> snapshotLong(list[i], "$field[$i]", 0L, 0xffffffffL) }
        }

        private fun decodeOffsetMappedU32Array(
            raw: Any?,
            field: String,
            currentOffsets: IntArray,
            currentIndex: Map<Int, Int>,
            legacyOffsets: IntArray,
        ): LongArray {
            val list = snapshotList(raw, field)
            return when (list.size) {
                currentOffsets.size -> LongArray(currentOffsets.size) { i ->
                    snapshotLong(list[i], "$field[$i]", 0L, 0xffffffffL)
                }
                legacyOffsets.size -> LongArray(currentOffsets.size).also { out ->
                    for (i in legacyOffsets.indices) {
                        out[currentIndex.getValue(legacyOffsets[i])] = snapshotLong(list[i], "$field[$i]", 0L, 0xffffffffL)
                    }
                }
                else -> throw IllegalArgumentException(
                    "Snapshot $field size mismatch: ${list.size} != ${currentOffsets.size} (or legacy ${legacyOffsets.size})",
                )
            }
        }

        private fun decodeBank68000Words(raw: Any?): LongArray = decodeOffsetMappedU32Array(
            raw = raw,
            field = "bank68000Words",
            currentOffsets = IspCoreOffsets.BANK_68000_OFFSETS,
            currentIndex = IspCoreOffsets.BANK_68000_INDEX,
            legacyOffsets = IspCoreOffsets.LEGACY_BANK_68000_OFFSETS,
        )

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "soc-isp closed" }
            val v3ExtraKeys = setOf("maliV61", "companionEngines")
            val hasV3Blocks = "maliV61" in data || "companionEngines" in data || "lut60000Base64" in data || "pdaf" in data
            val hasLut60 = "lut60000Base64" in data
            val hasPdafBlock = "pdaf" in data
            val hasSlvsEcBlock = "slvsEc" in data
            val expectedKeys = LinkedHashSet(SNAPSHOT_KEYS).apply {
                if (autoFrameIrqs && "receiverSyncDelay" in data) add("receiverSyncDelay")
                if (hasV3Blocks) {
                    require(movieCaptureV3) { "Unexpected v3 snapshot keys for $id" }
                    addAll(v3ExtraKeys)
                    if ("stillReadout" in data) add("stillReadout")
                    if (hasLut60) add("lut60000Base64")
                    if (hasPdafBlock) add("pdaf")
                }
                if (hasSlvsEcBlock) {
                    require(slvsEc != null) { "Unexpected slvsEc snapshot block when slvsEcSilicon=false for $id" }
                    add("slvsEc")
                }
            }
            val nextStillReadouts = StillReadoutLatch.restore(data["stillReadout"])
            require(data.keys == expectedKeys) { "Unexpected snapshot keys for $id" }
            require(snapshotLong(data["schema"], "schema", 1L, Int.MAX_VALUE.toLong()).toInt() == snapshotSchemaVersion) {
                "Unsupported snapshot schema for $id"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = snapshotMap(data["scalars"], "scalars")
            val legacyScalarKeys4 = setOf("profile", "initialFill", "resultSurfaceFile", "resultSurfaceSha256")
            val legacyScalarKeys5 = legacyScalarKeys4 + "autoFrameIrqs"
            val allScalarKeys = DESCRIPTOR.settings.map { it.key }.toSet()
            require(scalarsRaw.keys == allScalarKeys || scalarsRaw.keys == legacyScalarKeys5 || scalarsRaw.keys == legacyScalarKeys4) {
                "Scalar setting keys differ in snapshot for $id"
            }
            val previousValues = values
            try {
                require(snapshotBoolean(data["enabled"], "enabled") == active) {
                    "Snapshot enabled flag differs for $id"
                }
                if (!active) {
                    val normalizedDisabled = LinkedHashMap<Any?, Any?>(scalarsRaw).apply {
                        putIfAbsent("autoFrameIrqs", false)
                        putIfAbsent("slvsEcSilicon", false)
                    }
                    restoreScalarValues(normalizedDisabled, DESCRIPTOR)
                    require(
                        snapshotList(data["coreWords"], "coreWords").isEmpty() &&
                            snapshotLong(data["irq106Mask"], "irq106Mask", 0L, 0L) == 0L &&
                            snapshotLong(data["irq106CompletedCycles"], "irq106CompletedCycles", 0L, 0L) == 0L &&
                            snapshotList(data["bank50000Words"], "bank50000Words").isEmpty() &&
                            data["lut51000Base64"] == null &&
                            data["lut55000Base64"] == null &&
                            data["lut59000Base64"] == null &&
                            snapshotList(data["bank68000Words"], "bank68000Words").isEmpty() &&
                            snapshotList(data["config69000Words"], "config69000Words").isEmpty() &&
                            snapshotList(data["config69100Words"], "config69100Words").isEmpty() &&
                            !snapshotBoolean(data["irq296Pending"], "irq296Pending") &&
                            snapshotList(data["config6b000Words"], "config6b000Words").isEmpty() &&
                            data["lut6d000Base64"] == null &&
                            snapshotList(data["bank6e000Words"], "bank6e000Words").isEmpty() &&
                            snapshotList(data["liveView0Words"], "liveView0Words").isEmpty() &&
                            snapshotList(data["liveView1Words"], "liveView1Words").isEmpty() &&
                            snapshotList(data["liveView2Words"], "liveView2Words").isEmpty() &&
                            data["resultSurfaceSha256"] == null,
                    ) { "Disabled soc-isp snapshot cannot carry active state" }
                    return
                }

                val savedProfile = scalarsRaw["profile"] as? String
                    ?: throw IllegalArgumentException("Snapshot scalars.profile requires string for $id")
                val savedAutoFrameIrqs = if ("autoFrameIrqs" in scalarsRaw) {
                    snapshotBoolean(scalarsRaw["autoFrameIrqs"], "scalars.autoFrameIrqs")
                } else {
                    false
                }
                val savedSlvsEcSilicon = if ("slvsEcSilicon" in scalarsRaw) {
                    snapshotBoolean(scalarsRaw["slvsEcSilicon"], "scalars.slvsEcSilicon")
                } else {
                    false
                }
                require(savedSlvsEcSilicon == slvsEcSilicon || (!savedSlvsEcSilicon && slvsEcSilicon)) {
                    "Incompatible soc-isp slvsEcSilicon restore: saved=$savedSlvsEcSilicon live=$slvsEcSilicon"
                }
                val savedEffectiveAutoIrqs = savedProfile == PROFILE_AUTO_FRAME_IRQS ||
                    ((savedProfile == PROFILE_V2 || savedProfile == PROFILE_V3) && savedAutoFrameIrqs)
                require(
                    savedProfile == profile ||
                        (savedProfile == PROFILE && (profile == PROFILE_V2 || profile == PROFILE_V3)) ||
                        (savedProfile == PROFILE_V2 && profile == PROFILE_V3) ||
                        (savedProfile == PROFILE_AUTO_FRAME_IRQS && (profile == PROFILE_V2 || profile == PROFILE_V3) && autoFrameIrqs),
                ) {
                    "Incompatible soc-isp profile restore: saved=$savedProfile live=$profile"
                }
                require(
                    savedEffectiveAutoIrqs == autoFrameIrqs || (!savedEffectiveAutoIrqs && autoFrameIrqs),
                ) {
                    "Incompatible soc-isp autoFrameIrqs restore: saved=$savedAutoFrameIrqs live=$autoFrameIrqsSetting"
                }
                val normalizedScalars = LinkedHashMap<Any?, Any?>(scalarsRaw).apply {
                    put("profile", profile)
                    put("autoFrameIrqs", autoFrameIrqsSetting)
                    put("slvsEcSilicon", slvsEcSilicon)
                }
                restoreScalarValues(normalizedScalars, DESCRIPTOR)
                require(data["resultSurfaceSha256"] == resultSurfaceSha256) {
                    "soc-isp resultSurfaceSha256 differs from snapshot"
                }
                val nextCore = decodeOffsetMappedU32Array(
                    data["coreWords"],
                    "coreWords",
                    IspCoreOffsets.CORE_70000_OFFSETS,
                    IspCoreOffsets.CORE_70000_INDEX,
                    IspCoreOffsets.CORE_70000_LEGACY_OFFSETS,
                )
                require(nextCore[IspCoreOffsets.CORE_70000_INDEX.getValue(0x2e8)] == POLL_READY_WORD) {
                    "soc-isp core 0x2e8 must remain 0x10000"
                }
                val nextIrq106Mask = snapshotLong(data["irq106Mask"], "irq106Mask", 0L, IRQ106_VALID_MASK)
                val nextIrq106Cycles = snapshotLong(data["irq106CompletedCycles"], "irq106CompletedCycles", 0L, Long.MAX_VALUE)
                val nextBank50000 = decodeU32Array(data["bank50000Words"], "bank50000Words", bank50000Words.size)
                val nextLut51000 = decodeBase64Fixed(data["lut51000Base64"], "lut51000Base64", LUT_51000_SIZE)
                val nextLut55000 = decodeBase64Fixed(data["lut55000Base64"], "lut55000Base64", LUT_55000_SIZE)
                val nextLut59000 = decodeBase64Fixed(data["lut59000Base64"], "lut59000Base64", LUT_59000_SIZE)
                val nextLut60000 = if (movieCaptureV3 && "lut60000Base64" in data) {
                    decodeBase64Fixed(data["lut60000Base64"], "lut60000Base64", LUT_60000_SIZE)
                } else if (movieCaptureV3) {
                    ByteArray(LUT_60000_SIZE)
                } else {
                    null
                }
                val nextBank68000 = decodeBank68000Words(data["bank68000Words"])
                val nextConfig69000 = decodeU32Array(data["config69000Words"], "config69000Words", config69000Words.size)
                val nextConfig69100 = decodeU32Array(data["config69100Words"], "config69100Words", config69100Words.size)
                val nextReceiverSync = if ("receiverSyncDelay" in data) {
                    val delay = snapshotLong(data["receiverSyncDelay"], "receiverSyncDelay", 0L, AUTO_FRAME_IRQ_PERIOD)
                    Math.addExact(context.clock.ticks(), delay)
                } else Long.MAX_VALUE
                val nextIrq296 = snapshotBoolean(data["irq296Pending"], "irq296Pending")
                val nextConfig6b000 = decodeU32Array(data["config6b000Words"], "config6b000Words", config6b000Words.size)
                val nextLut6d000 = decodeBase64Fixed(data["lut6d000Base64"], "lut6d000Base64", LUT_6D000_SIZE)
                val nextBank6e000 = decodeOffsetMappedU32Array(
                    data["bank6e000Words"],
                    "bank6e000Words",
                    IspCoreOffsets.BANK_6E000_OFFSETS,
                    IspCoreOffsets.BANK_6E000_INDEX,
                    IspCoreOffsets.BANK_6E000_LEGACY_OFFSETS,
                )
                for (off in listOf(0x014, 0x018, 0x020, 0x024)) {
                    val w = nextBank6e000[IspCoreOffsets.BANK_6E000_INDEX.getValue(off)]
                    require(w == 0L || IspCoreOffsets.valid6e000ConfigWord(off, w)) {
                        "Invalid bank6e000 config word in snapshot at 0x${off.toString(16)}: 0x${w.toString(16)}"
                    }
                }
                val nextLiveView0 = decodeU32Array(data["liveView0Words"], "liveView0Words", liveView0Words.size)
                val nextLiveView1 = decodeU32Array(data["liveView1Words"], "liveView1Words", liveView1Words.size)
                val nextLiveView2 = decodeU32Array(data["liveView2Words"], "liveView2Words", liveView2Words.size)
                if (hasV3Blocks) {
                    checkNotNull(maliV61).restoreSnapshot(data["maliV61"])
                    checkNotNull(companionEngines).restoreSnapshot(data["companionEngines"])
                    if (hasPdafBlock) {
                        checkNotNull(pdafEngine).restoreSnapshot(data["pdaf"])
                    }
                }
                if (hasSlvsEcBlock) {
                    checkNotNull(slvsEc).restoreSnapshot(data["slvsEc"])
                }

                nextCore.copyInto(coreWords)
                irq106Mask = nextIrq106Mask
                irq106CompletedCycles = nextIrq106Cycles
                nextBank50000.copyInto(bank50000Words)
                nextLut51000.copyInto(checkNotNull(lut51000))
                nextLut55000.copyInto(checkNotNull(lut55000))
                nextLut59000.copyInto(checkNotNull(lut59000))
                if (nextLut60000 != null) nextLut60000.copyInto(checkNotNull(lut60000))
                nextBank68000.copyInto(bank68000Words)
                nextConfig69000.copyInto(config69000Words)
                nextConfig69100.copyInto(config69100Words)
                irq296Pending = slvsEc?.irq296Asserted ?: nextIrq296
                firstReceiverSyncTick = nextReceiverSync
                nextConfig6b000.copyInto(config6b000Words)
                nextLut6d000.copyInto(checkNotNull(lut6d000))
                nextBank6e000.copyInto(bank6e000Words)
                stillReadouts = nextStillReadouts
                dmaEngine.invalidateStillBayerExposure()
                nextLiveView0.copyInto(liveView0Words)
                nextLiveView1.copyInto(liveView1Words)
                nextLiveView2.copyInto(liveView2Words)
                liveStatsEngine?.clear()
                colorPipeline.reset()
                lastFrameSyncTick = Long.MIN_VALUE
                nextAutoIrqTick = context.clock.ticks() + activeFrameIrqPeriod()
                irq106ArmedAt = null
                if (started) {
                    val rx = slvsEc
                    if (rx != null) {
                        if (!hasSlvsEcBlock) rx.recomputeIrqs() else rx.syncIrqLines()
                    } else {
                        interruptSink?.setLine(IRQ_296, irq296Pending)
                    }
                    maliV61?.syncIrqLine()
                    pdafEngine?.syncIrqLine()
                    companionEngines?.syncIrqLines()
                }
            } catch (failure: Throwable) {
                values = previousValues
                throw failure
            }
        }
    }
}

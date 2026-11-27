package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Hardware component factory for the CMOS image sensor (`cmos`).
 *
 * Routes configured `sourceKind` values through [SceneSourceRegistry] so static image
 * formats (`JPEG`, `PNG`) produce cached linear RGGB Bayer radiance today while M-Optics
 * P1-P3 stream/capture providers (`VIDEO_FILE`, `RTMP_RTSP`, `WEBCAM`, `SHARED_TEXTURE`)
 * plug in cleanly without changing snapshot schemas or breaking existing callers.
 */
class CmosFactory(
    private val registry: SceneSourceRegistry,
) : ComponentFactory {
    constructor() : this(SceneSourceRegistry.defaultRegistry())

    override val descriptor = ComponentDescriptor(
        id = "cmos",
        version = "0.0.1",
        settings = listOf(
            Setting("sourceKind", ValueKind.TEXT, "JPEG", choices = registry.registeredKinds().toList()),
            Setting("sourceFile", ValueKind.TEXT, ""),
            Setting("loop", ValueKind.BOOLEAN, false),
        ),
    )

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        object : ScalarComponent("cmos", context, config), SensorPort, LiveSensorReadout, CameraTimedSceneAudioProvider, StatefulComponent {
            @Volatile
            private var sceneSource: SceneSource? = null
            @Volatile
            private var latestScenePose3D: CameraPose3D = CameraPose3D.IDENTITY
            private var framesExposed: Long = 0L
            private var exhausted: Boolean = false
            private var asyncOpticsWorker: AsyncOpticsWorker? = null
            private val frameGeneration = java.util.concurrent.atomic.AtomicLong(0L)
            private var lastExposeStateSig: Long = 0L
            private var hasExposedFrame: Boolean = false
            private var activeReadoutTick: Long? = null
            private var capturedExposure: Exposure? = null
            private var capturedImage: ReadoutImage? = null
            private var readoutBayer = CachedPackedBayerConverter()
            private val endReadout = AutoCloseable { activeReadoutTick = null }

            override fun beginLiveReadout(tick: Long): AutoCloseable {
                activeReadoutTick = tick
                return endReadout
            }

            private fun captureReadout(source: SceneSource, exposure: Exposure): ReadoutImage? {
                if (source !is PackedReadoutSource || activeReadoutTick != exposure.startTick) return null
                val previous = capturedImage
                if (previous != null && capturedExposure == exposure) return previous
                val packed = source.acquirePackedFrame() ?: return null
                val image = if (previous != null && previous.packed === packed) previous else {
                    ReadoutImage(packed, (source as? SceneSource2D)?.virtualSubjectDistanceM)
                }
                capturedImage = image
                capturedExposure = exposure
                return image
            }

            private fun clearReadout() {
                capturedExposure = null
                capturedImage = null
                readoutBayer = CachedPackedBayerConverter()
            }

            private val filterScratch = OpticalFilterScratch()
            private var filteredR = FloatArray(0)
            private var filteredG = FloatArray(0)
            private var filteredB = FloatArray(0)
            private var cachedSyncSpatialSrcRed: FloatArray? = null
            private var cachedSyncSpatialSrcSeq: Long = -1L
            private var cachedSyncSpatialFilterRev: Long = -1L
            private var cachedSyncSpatialFrame: LinearRadianceFrame? = null

            override val sceneAudioEnabled: Boolean
                get() = (sceneSource as? SceneAudioProvider)?.sceneAudioEnabled == true

            override val hasCameraTimedAudio: Boolean
                get() = sceneSource is MilkFrogSceneSource

            override fun sampleAudioAtTick(out: ShortArray, frames: Int, pose: CameraPose3D, tick: Long): Int {
                val source = sceneSource as? MilkFrogSceneSource ?: return 0
                return source.sampleAudioAtNanos(out, frames, if (pose.isIdentity) latestScenePose3D else pose, cameraNanos(tick))
            }

            override val sceneAudioName: String
                get() = (sceneSource as? SceneAudioProvider)?.sceneAudioName ?: "3D Scene Audio"

            override fun sampleAudioStereo48k(
                pcmStereoInterleaved: ShortArray,
                frames: Int,
                pose: CameraPose3D,
            ): Int {
                val active = sceneSource as? SceneAudioProvider
                if (active == null || !active.sceneAudioEnabled) {
                    if (frames > 0) pcmStereoInterleaved.fill(0, 0, frames * 2)
                    return frames.coerceAtLeast(0)
                }
                val effectivePose = if (pose.isIdentity) latestScenePose3D else pose
                return active.sampleAudioStereo48k(pcmStereoInterleaved, frames, effectivePose)
            }

            init {
                sceneSource = loadValidatedSource(values)
                context.ports.register(id, "cmos", SensorPort::class.java, this)
            }

            override fun currentFrameGeneration(): Long = frameGeneration.get()

            private fun recordExposeGeneration(stateSig: Long, alwaysBump: Boolean) {
                if (alwaysBump || !hasExposedFrame || stateSig != lastExposeStateSig) {
                    lastExposeStateSig = stateSig
                    hasExposedFrame = true
                    frameGeneration.incrementAndGet()
                }
            }

            private fun mix64(h: Long, v: Long): Long = (h xor v) * 0x9E3779B97F4A7C15UL.toLong()

            private fun computeSyncStateSig(
                source: SceneSource,
                sourceSeq: Long,
                poseKey: Long,
                exposure: Exposure,
                opticalFilter: OpticalSceneFilter?,
                filterStack: OpticalFilterStackPort?,
                ibis: IbisShiftPort?,
            ): Long {
                var h = 1125899906842597L
                h = mix64(h, System.identityHashCode(source).toLong())
                h = mix64(h, sourceSeq)
                h = mix64(h, poseKey)
                h = mix64(h, exposure.durationNanos)
                h = mix64(h, exposure.iso.toLong())
                h = mix64(h, exposure.lens.hashCode().toLong())
                h = mix64(h, if (opticalFilter?.enabled == true) 1L else 0L)
                h = mix64(h, opticalFilter?.opticalStateVersion() ?: 0L)
                if (filterStack != null) {
                    h = mix64(h, if (filterStack.enabled) 1L else 0L)
                    h = mix64(h, filterStack.revision)
                    h = mix64(h, filterStack.totalTransmittance().toRawBits())
                    h = mix64(h, if (filterStack.hasActiveSpatialEffects()) 1L else 0L)
                }
                if (ibis != null) {
                    h = mix64(h, ibis.ibisMode.toLong())
                    h = mix64(h, ibis.shiftXUm.toRawBits())
                    h = mix64(h, ibis.shiftYUm.toRawBits())
                    h = mix64(h, ibis.rollDeg.toRawBits())
                }
                return h xor (h ushr 31)
            }

            private var lastFocusMotorBusyTick: Long = Long.MIN_VALUE

            private fun resetAsyncOpticsWorker() {
                clearReadout()
                asyncOpticsWorker?.close()
                asyncOpticsWorker = null
                cachedSyncSpatialSrcRed = null
                cachedSyncSpatialFrame = null
                lastFocusMotorBusyTick = Long.MIN_VALUE
            }

            private fun buildRequest(settings: Map<String, Any>): SceneSourceRequest {
                val kind = (settings["sourceKind"] ?: values["sourceKind"]) as? String ?: "JPEG"
                val file = ((settings["sourceFile"] ?: values["sourceFile"]) as? String).orEmpty()
                val loop = (settings["loop"] ?: values["loop"]) as? Boolean ?: false
                return SceneSourceRequest(
                    kind = kind,
                    sourceRef = file,
                    loop = loop,
                    resolveDataPath = context::dataPath,
                )
            }

            private fun loadValidatedSource(settings: Map<String, Any>, existing: SceneSource? = null): SceneSource? {
                val request = buildRequest(settings)
                val provider = registry.providerFor(request.kind)
                if (request.sourceRef.isEmpty()) return null
                return if (provider.capability == SceneSourceCapability.READY) {
                    if (
                        request.kind == StreamIngestSceneSource.KIND &&
                        existing is StreamIngestSceneSource
                    ) {
                        val nextEndpoint = StreamIngestSceneSource.parseAndValidateEndpoint(request.sourceRef)
                        if (nextEndpoint.port != 0 && nextEndpoint == existing.endpoint) {
                            return existing
                        }
                    }
                    if (provider is ProceduralOpticsSceneSourceProvider) {
                        provider.open(request) { cameraNanos(context.clock.ticks()) }
                    } else {
                        provider.open(request)
                    }
                } else {
                    provider.validate(request)
                    null
                }
            }

            private fun findOpticalFilter(): OpticalSceneFilter? =
                context.ports.find("lens", LensPort::class.java) as? OpticalSceneFilter

            private fun findOrientationPort(): CameraOrientationPort? =
                context.ports.find(CameraOrientationPort.MMA8452Q_PORT_NAME, CameraOrientationPort::class.java)
                    ?: context.ports.find("i2c-bus.orientation", CameraOrientationPort::class.java)

            private fun findIbisShiftPort(): IbisShiftPort? =
                context.ports.find(IbisShiftPort.PORT_NAME, IbisShiftPort::class.java)

            private fun findOpticalFilterStack(): OpticalFilterStackPort? =
                context.ports.find(OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java)

            private fun applySpatialFiltersSync(
                frame: LinearRadianceFrame,
                filterStack: OpticalFilterStackPort?,
                exposure: Exposure,
                frameIndex: Long,
                opticalFilter: OpticalSceneFilter? = null,
            ): LinearRadianceFrame {
                if (filterStack == null || !filterStack.hasActiveSpatialEffects()) return frame
                if (opticalFilter != null && opticalFilter.handlesSpatialFilterStack(filterStack)) return frame
                val filterRev = filterStack.revision
                val cached = cachedSyncSpatialFrame
                if (
                    cached != null &&
                    frame.sequence > 0L &&
                    cachedSyncSpatialSrcRed === frame.red &&
                    cachedSyncSpatialSrcSeq == frame.sequence &&
                    cachedSyncSpatialFilterRev == filterRev &&
                    cached.width == frame.width &&
                    cached.height == frame.height
                ) {
                    return cached
                }
                val count = frame.width * frame.height
                if (filteredR.size < count) {
                    filteredR = FloatArray(count)
                    filteredG = FloatArray(count)
                    filteredB = FloatArray(count)
                }
                System.arraycopy(frame.red, 0, filteredR, 0, count)
                System.arraycopy(frame.green, 0, filteredG, 0, count)
                System.arraycopy(frame.blue, 0, filteredB, 0, count)
                val filterCtx = OpticalFilterContext(
                    depthMeters = frame.depthMeters,
                    fNumber = exposure.lens.aperture,
                    focalLengthMm = exposure.lens.focalLengthMm,
                    focusDistanceM = exposure.lens.focusDistanceM,
                    rollDeg = frame.pose.rollDeg,
                    pitchDeg = frame.pose.pitchDeg,
                    frameIndex = frameIndex,
                )
                filterStack.applySpatialStack(filteredR, filteredG, filteredB, frame.width, frame.height, filterScratch, filterCtx)
                val outSeq = mixSpatialSequence(frame.sequence, filterRev)
                val outFrame = LinearRadianceFrame(
                    width = frame.width,
                    height = frame.height,
                    red = filteredR,
                    green = filteredG,
                    blue = filteredB,
                    depthMeters = frame.depthMeters,
                    dimension = frame.dimension,
                    virtualSubjectDistanceM = frame.virtualSubjectDistanceM,
                    pose = frame.pose,
                    sequence = outSeq,
                )
                cachedSyncSpatialSrcRed = frame.red
                cachedSyncSpatialSrcSeq = frame.sequence
                cachedSyncSpatialFilterRev = filterRev
                cachedSyncSpatialFrame = outFrame
                return outFrame
            }

            private fun exposureWithDirectFilterTransmittance(
                exposure: Exposure,
                filterStack: OpticalFilterStackPort?,
            ): Exposure {
                val trans = filterStack?.totalTransmittance() ?: 1.0
                if (trans >= 0.999999) return exposure
                return exposure.copy(
                    lens = exposure.lens.copy(
                        transmission = (exposure.lens.transmission * trans).coerceIn(1e-6, 1.0),
                    ),
                )
            }

            override fun validateSettings(settings: Map<String, Any>) {
                val request = buildRequest(settings)
                val provider = registry.providerFor(request.kind)
                if (request.sourceRef.isEmpty()) return
                provider.validate(request)
            }

            override fun applySettings(settings: Map<String, Any>) {
                val nextValues = values + settings
                val previous = sceneSource
                val nextSource = loadValidatedSource(nextValues, existing = previous)
                if (previous !== nextSource) {
                    resetAsyncOpticsWorker()
                    previous?.close()
                }
                sceneSource = nextSource
                values = nextValues
                framesExposed = 0L
                exhausted = false
                hasExposedFrame = false
                frameGeneration.incrementAndGet()
            }

            override fun expose(exposure: Exposure, destination: java.nio.ByteBuffer): Int =
                exposeInternal(exposure, destination, allowAsync = (values["loop"] as Boolean))

            override fun exposeStill(exposure: Exposure, destination: java.nio.ByteBuffer): Int =
                exposeInternal(exposure, destination, allowAsync = false)

            override fun supportsFusedIspStats(): Boolean {
                val file = values["sourceFile"] as String
                if (file.isEmpty() || exhausted) return false
                val kind = values["sourceKind"] as String
                if (registry.providerFor(kind).capability != SceneSourceCapability.READY) return false
                if (sceneSource == null) return false
                val opticalFilter = findOpticalFilter() ?: return false
                if (!opticalFilter.enabled || !opticalFilter.supportsFusedIspStats()) return false
                val filterStack = findOpticalFilterStack()
                if (filterStack != null && filterStack.hasActiveSpatialEffects() && !opticalFilter.handlesSpatialFilterStack(filterStack)) {
                    return false
                }
                return true
            }

            override fun fusedFrameStateKey(exposure: Exposure): Long {
                val file = values["sourceFile"] as String
                if (file.isEmpty() || exhausted) return -1L
                val kind = values["sourceKind"] as String
                if (registry.providerFor(kind).capability != SceneSourceCapability.READY) return -1L
                val source = sceneSource ?: return -1L
                // A live producer can reuse its output only while the exact sampled generation is unchanged.
                val liveGeneration = if (source is LiveStreamSceneSource) {
                    if (activeReadoutTick == exposure.startTick && source is PackedReadoutSource) {
                        captureReadout(source, exposure)?.packed?.sequence ?: return -1L
                    } else {
                        source.fusedFrameSequence ?: return -1L
                    }
                } else null
                val opticalFilter = findOpticalFilter() ?: return -1L
                if (!opticalFilter.enabled) return -1L
                if (opticalFilter.opticalStateVersion() < 0L) return -1L
                val orientation = findOrientationPort()
                if (orientation?.accelEnabled == true && orientation.shakeAmpDeg > 0.0) return -1L
                val ibis = findIbisShiftPort()
                val filterStack = findOpticalFilterStack()
                val poseKey = if (source is SceneSource3D) {
                    CameraPose3D.fromOrientationAndIbis(
                        orientation = orientation,
                        ibis = ibis,
                        tick = context.clock.ticks(),
                        focalLengthMm = exposure.lens.focalLengthMm,
                    ).quantizedFullKey()
                } else {
                    0L
                }
                val sourceSeq = liveGeneration ?: when (source) {
                    is SceneSource2D -> source.samplePlanarRadiance().sequence
                    is SceneSourceTelemetry -> source.framesReceived
                    else -> 1L
                }
                return computeSyncStateSig(
                    source = source,
                    sourceSeq = sourceSeq,
                    poseKey = poseKey,
                    exposure = exposure,
                    opticalFilter = opticalFilter,
                    filterStack = filterStack,
                    ibis = ibis,
                ) and Long.MAX_VALUE
            }

            override fun tryExposeFusedIspYuv(
                request: LiveViewIspFusionRequest,
                outYOrPacked: ByteArray,
                outUvPlane: ByteArray?,
            ): Boolean {
                val file = values["sourceFile"] as String
                if (file.isEmpty() || exhausted) return false
                val kind = values["sourceKind"] as String
                val provider = registry.providerFor(kind)
                if (provider.capability != SceneSourceCapability.READY) return false
                val source = sceneSource ?: return false
                val opticalFilter = findOpticalFilter() ?: return false
                if (!opticalFilter.enabled) return false
                val filterStack = findOpticalFilterStack()
                if (filterStack != null && filterStack.hasActiveSpatialEffects() && !opticalFilter.handlesSpatialFilterStack(filterStack)) {
                    return false
                }
                val orientation = findOrientationPort()
                val ibis = findIbisShiftPort()
                val pose = if (source is SceneSource3D) {
                    CameraPose3D.fromOrientationAndIbis(
                        orientation = orientation,
                        ibis = ibis,
                        tick = context.clock.ticks(),
                        focalLengthMm = request.exposure.lens.focalLengthMm,
                    ).also { latestScenePose3D = it }
                } else {
                    null
                }
                if (source is SceneSource3D && pose != null && opticalFilter.supportsGpuScene3d()) {
                    val gpuSpec = source.gpuScene3dRenderSpec(pose)
                    if (gpuSpec != null && opticalFilter.tryFilterGpuScene3dToIspYuv(
                            spec = gpuSpec,
                            geometry = SENSOR_GEOMETRY,
                            request = request,
                            outYOrPacked = outYOrPacked,
                            outUvPlane = outUvPlane,
                            frameIndex = framesExposed,
                        )
                    ) {
                        val sig = computeSyncStateSig(
                            source = source,
                            sourceSeq = gpuSpec.sequence,
                            poseKey = gpuSpec.pose.quantizedFullKey(),
                            exposure = request.exposure,
                            opticalFilter = opticalFilter,
                            filterStack = filterStack,
                            ibis = ibis,
                        )
                        recordExposeGeneration(sig, alwaysBump = opticalFilter.opticalStateVersion() < 0L)
                        framesExposed++
                        if (!(values["loop"] as Boolean)) {
                            exhausted = true
                        }
                        return true
                    }
                }
                val frame = captureReadout(source, request.exposure)?.radiance ?: sampleRadianceFrame(source, pose) ?: return false
                val ok = opticalFilter.tryFilterRadianceToIspYuv(
                    frame = frame,
                    geometry = SENSOR_GEOMETRY,
                    request = request,
                    outYOrPacked = outYOrPacked,
                    outUvPlane = outUvPlane,
                    frameIndex = framesExposed,
                )
                if (!ok) return false
                val sig = computeSyncStateSig(
                    source = source,
                    sourceSeq = frame.sequence,
                    poseKey = frame.pose.quantizedFullKey(),
                    exposure = request.exposure,
                    opticalFilter = opticalFilter,
                    filterStack = filterStack,
                    ibis = ibis,
                )
                recordExposeGeneration(sig, alwaysBump = opticalFilter.opticalStateVersion() < 0L)
                framesExposed++
                if (!(values["loop"] as Boolean)) {
                    exhausted = true
                }
                return true
            }

            private fun exposeInternal(
                exposure: Exposure,
                destination: java.nio.ByteBuffer,
                allowAsync: Boolean,
            ): Int {
                StaticImageSceneSource.validateExposure(exposure)
                val file = values["sourceFile"] as String
                check(file.isNotEmpty()) { "CMOS sourceFile is not configured" }
                val kind = values["sourceKind"] as String
                val provider = registry.providerFor(kind)
                check(provider.capability == SceneSourceCapability.READY) {
                    "CMOS sourceKind $kind is ${provider.capability.name}: ${provider.pipelineStatus(kind)}"
                }
                if (exhausted) return 0
                val source = checkNotNull(sceneSource) { "CMOS $kind scene source not initialized" }
                val opticalFilter = findOpticalFilter()
                val filterStack = findOpticalFilterStack()
                val ibis = findIbisShiftPort()
                val orientation = if (source is SceneSource3D) findOrientationPort() else null
                val pose = if (source is SceneSource3D) {
                    CameraPose3D.fromOrientationAndIbis(
                        orientation = orientation,
                        ibis = ibis,
                        tick = context.clock.ticks(),
                        focalLengthMm = exposure.lens.focalLengthMm,
                    ).also { latestScenePose3D = it }
                } else {
                    null
                }
                var asyncPublishedGen: Long = -1L
                var syncSourceSeq: Long = 0L
                val syncPoseKey: Long = pose?.quantizedFullKey() ?: 0L
                val readout = captureReadout(source, exposure)
                val supportsRadiance = source is SceneSource3D || source is SceneSource2D || source is LinearRgbFrameSource

                if (opticalFilter != null && opticalFilter.enabled && supportsRadiance) {
                    val useAsync = allowAsync && when {
                        source is SceneSource3D -> {
                            val dynamicHandheldShake = orientation?.accelEnabled == true && orientation.shakeAmpDeg > 0.0
                            val dynamicAnimated3D = source is LiveStreamSceneSource && source.latestFrameSequence > 0L
                            val lensPort = context.ports.find("lens", LensPort::class.java)
                            val nowTick = context.clock.ticks()
                            val motorBusy = (lensPort as? ModularLensPort)?.focusMotorBusy == true
                            if (motorBusy) {
                                lastFocusMotorBusyTick = nowTick
                            }
                            val recentFocusSweep = motorBusy ||
                                (lastFocusMotorBusyTick != Long.MIN_VALUE && (nowTick - lastFocusMotorBusyTick) in 0L..10_800_000L)
                            dynamicHandheldShake || dynamicAnimated3D || recentFocusSweep
                        }
                        source is LiveStreamSceneSource -> source.latestFrameSequence > 0L
                        source is SceneSource2D -> true
                        else -> false
                    }
                    if (useAsync) {
                        val worker = asyncOpticsWorker ?: AsyncOpticsWorker(FRAME_BYTES).also { asyncOpticsWorker = it }
                        asyncPublishedGen = worker.exposeDoubleBuffered(
                            source = source,
                            readout = readout,
                            pose = pose,
                            opticalFilter = opticalFilter,
                            filterStack = filterStack,
                            exposure = exposure,
                            geometry = SENSOR_GEOMETRY,
                            frameIndex = framesExposed,
                            destination = destination,
                            periodicRefresh = source is LiveStreamSceneSource && source !is SceneSource3D,
                        )
                    } else {
                        val gpuSpec = if (source is SceneSource3D && pose != null && opticalFilter.supportsGpuScene3d() &&
                            (filterStack == null || !filterStack.hasActiveSpatialEffects() || opticalFilter.handlesSpatialFilterStack(filterStack))
                        ) {
                            source.gpuScene3dRenderSpec(pose)
                        } else {
                            null
                        }
                        val gpuBytes = if (gpuSpec != null) {
                            opticalFilter.tryFilterGpuScene3dToBayer(
                                spec = gpuSpec,
                                exposure = exposure,
                                geometry = SENSOR_GEOMETRY,
                                destination = destination,
                                frameIndex = framesExposed,
                            )
                        } else {
                            -1
                        }
                        if (gpuSpec != null && gpuBytes > 0) {
                            syncSourceSeq = gpuSpec.sequence
                        } else {
                            val rawFrame = readout?.radiance ?: checkNotNull(sampleRadianceFrame(source, pose))
                            syncSourceSeq = rawFrame.sequence
                            val frame = applySpatialFiltersSync(rawFrame, filterStack, exposure, framesExposed, opticalFilter)
                            opticalFilter.filterRadianceFrame(
                                frame = frame,
                                exposure = exposure,
                                geometry = SENSOR_GEOMETRY,
                                destination = destination,
                                frameIndex = framesExposed,
                            )
                        }
                    }
                } else {
                    val directExposure = exposureWithDirectFilterTransmittance(exposure, filterStack)
                    val rawFrame = if (filterStack != null && filterStack.hasActiveSpatialEffects()) {
                        readout?.radiance ?: sampleRadianceFrame(source, pose)
                    } else {
                        null
                    }
                    if (rawFrame != null) {
                        syncSourceSeq = rawFrame.sequence
                        val frame = applySpatialFiltersSync(rawFrame, filterStack, directExposure, framesExposed)
                        SceneFrameCodec.writeLinearRadianceToBayer(frame, directExposure, SENSOR_GEOMETRY, destination)
                    } else if (readout != null) {
                        syncSourceSeq = readout.packed.sequence
                        readoutBayer.writeCached(readout.packed, directExposure, SENSOR_GEOMETRY, destination)
                    } else {
                        syncSourceSeq = if (source is SceneSource3D && pose != null && (pose.hasViewTransform || source.currentPose.hasViewTransform)) {
                            source.renderForPose(pose).sequence
                        } else {
                            (source as? LiveStreamSceneSource)?.latestFrameSequence
                                ?: (source as? SceneSourceTelemetry)?.framesReceived
                                ?: 1L
                        }
                        source.radiance(directExposure, SENSOR_GEOMETRY, destination)
                    }
                }
                if (asyncPublishedGen >= 0L) {
                    val sig = mix64(0x4153594E4347454EL, asyncPublishedGen)
                    recordExposeGeneration(sig, alwaysBump = false)
                } else {
                    val sig = computeSyncStateSig(
                        source = source,
                        sourceSeq = syncSourceSeq,
                        poseKey = syncPoseKey,
                        exposure = exposure,
                        opticalFilter = opticalFilter,
                        filterStack = filterStack,
                        ibis = ibis,
                    )
                    val unversionedFilter = opticalFilter != null && opticalFilter.enabled && opticalFilter.opticalStateVersion() < 0L
                    recordExposeGeneration(sig, alwaysBump = unversionedFilter)
                }
                framesExposed++
                if (!(values["loop"] as Boolean)) {
                    exhausted = true
                }
                return FRAME_BYTES
            }

            override fun state(): Map<String, Any?> {
                val file = values["sourceFile"] as String
                val kind = values["sourceKind"] as String
                val provider = registry.findProvider(kind)
                val pipeline = when {
                    file.isEmpty() -> "unconfigured"
                    provider != null -> provider.pipelineStatus(kind)
                    else -> "UNSUPPORTED: unknown sourceKind $kind"
                }
                val base = LinkedHashMap<String, Any?>(super.state())
                base["pipeline"] = pipeline
                base["sourceCapability"] = provider?.capability?.name ?: SceneSourceCapability.UNSUPPORTED.name
                base["supportedSourceKinds"] = registry.registeredKinds().toList()
                base["framesExposed"] = framesExposed
                base["exhausted"] = exhausted
                base["opticsFilterActive"] = findOpticalFilter()?.enabled == true
                val filterStack = findOpticalFilterStack()
                base["opticalFilterStackActive"] = (filterStack?.enabled == true && filterStack.activeElements().isNotEmpty())
                base["opticalFilterTransmittance"] = filterStack?.totalTransmittance() ?: 1.0
                val activeSource = sceneSource
                val telemetry = activeSource as? SceneSourceTelemetry
                base["activeSourceRef"] = telemetry?.activeSourceRef ?: file
                base["sourceWidth"] = telemetry?.sourceWidth ?: 0
                base["sourceHeight"] = telemetry?.sourceHeight ?: 0
                base["hasDepthBuffer"] = telemetry?.hasDepthBuffer ?: false
                base["hasSceneAudio"] = (activeSource as? SceneAudioProvider)?.sceneAudioEnabled == true
                base["sceneDimension"] = when (activeSource) {
                    is SceneSource3D -> SceneDimension.DIM_3D.name
                    is SceneSource2D -> SceneDimension.DIM_2D.name
                    else -> "UNKNOWN"
                }
                if (activeSource is SceneSource3D) {
                    val p = activeSource.currentPose
                    base["cameraPose3D"] = linkedMapOf(
                        "posXM" to p.posXM,
                        "posYM" to p.posYM,
                        "posZM" to p.posZM,
                        "rollDeg" to p.rollDeg,
                        "pitchDeg" to p.pitchDeg,
                        "yawDeg" to p.yawDeg,
                        "ibisShiftXUm" to p.ibisShiftXUm,
                        "ibisShiftYUm" to p.ibisShiftYUm,
                        "ibisRollDeg" to p.ibisRollDeg,
                        "focalLengthMm" to p.focalLengthMm,
                    )
                } else if (activeSource is SceneSource2D && activeSource.virtualSubjectDistanceM != null) {
                    base["virtualSubjectDistanceM"] = activeSource.virtualSubjectDistanceM
                }
                base["lastSourceError"] = telemetry?.lastSourceError
                base["framesReceived"] = telemetry?.framesReceived ?: 0L
                base["framesDropped"] = telemetry?.framesDropped ?: 0L
                if (activeSource is StreamIngestSceneSource) {
                    base["streamIngestPort"] = activeSource.boundPort
                }
                return base
            }

            override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalarValues" to saveScalarValues(),
                "framesExposed" to framesExposed,
                "exhausted" to exhausted,
            )

            override fun restoreSnapshot(data: Map<String, Any?>) {
                require(exactInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
                require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
                require(data.keys == SNAPSHOT_KEYS_V1 || data.keys == SNAPSHOT_KEYS) {
                    "Unexpected keys in snapshot for $id"
                }
                val isLegacyV1 = data.keys == SNAPSHOT_KEYS_V1
                val nextFramesExposed = if (!isLegacyV1) {
                    exactLong(data["framesExposed"], "framesExposed").also {
                        require(it >= 0L) { "Negative framesExposed in snapshot for $id" }
                    }
                } else {
                    0L
                }
                val nextExhausted = if (!isLegacyV1) {
                    (data["exhausted"] as? Boolean)
                        ?: throw IllegalArgumentException("Snapshot exhausted for $id requires boolean")
                } else {
                    false
                }
                val supplied = data["scalarValues"] as? Map<*, *> ?: error("Missing scalar settings map for $id")
                require(supplied.keys.all { it is String && descriptor.settings.any { s -> s.key == it } }) {
                    "Unknown scalar setting key in snapshot for $id"
                }
                val nextScalars = LinkedHashMap<String, Any>(values)
                for (setting in descriptor.settings) {
                    if (!supplied.containsKey(setting.key)) continue
                    val validated = setting.validate(checkNotNull(supplied[setting.key]) { "Null setting ${setting.key} in $id" })
                    nextScalars[setting.key] = validated
                }
                val liveSourceFile = values["sourceFile"] as String
                val snapshotSourceFile = nextScalars["sourceFile"] as String
                val nextLoop = nextScalars["loop"] as Boolean
                if (!isLegacyV1) {
                    require(!(nextLoop && nextExhausted)) {
                        "Contradictory snapshot for $id: loop=true cannot be exhausted=true"
                    }
                    require(!(snapshotSourceFile.isEmpty() && (nextFramesExposed > 0L || nextExhausted))) {
                        "Contradictory snapshot for $id: unconfigured sourceFile cannot have framesExposed > 0 or exhausted=true"
                    }
                } else if (liveSourceFile.isNotEmpty() && snapshotSourceFile.isEmpty()) {
                    return
                }
                val previous = sceneSource
                val nextSource = loadValidatedSource(nextScalars, existing = previous)
                if (previous !== nextSource) {
                    resetAsyncOpticsWorker()
                    previous?.close()
                } else {
                    clearReadout()
                }
                sceneSource = nextSource
                values = nextScalars.toMap()
                framesExposed = nextFramesExposed
                exhausted = nextExhausted
                hasExposedFrame = false
                frameGeneration.incrementAndGet()
            }

            override fun close() {
                resetAsyncOpticsWorker()
                sceneSource?.close()
                sceneSource = null
            }
        }

    /** Per-pixel conversion remains lazy, including when the readout is handed to the optics worker. */
    private class ReadoutImage(val packed: PackedRawFrame, subjectDistanceM: Double?) {
        val radiance by lazy {
            SceneFrameCodec.toLinearRgbFrame(packed).toRadianceFrame(
                dimension = if (packed.depthMeters == null) SceneDimension.DIM_2D else SceneDimension.DIM_3D,
                virtualSubjectDistanceM = subjectDistanceM,
                sequence = packed.sequence,
            )
        }
    }

    /**
     * Single-thread daemon worker offloading optical filtering for live-updating
     * streaming CMOS sources (`ffmpeg-pipe`, `screen-capture`, `stream-ingest`), 3D dynamic
     * scenes, and static `SceneSource2D` (`StaticImageSceneSource` / `JpegSceneSource`) LiveView.
     *
     * The first frame renders synchronously into the published buffer;
     * subsequent frames serve the published Bayer data while coalescing parameter/frame updates onto
     * the background worker so the ARM emulation owner thread never stalls.
     */
    private class AsyncOpticsWorker(private val frameBytes: Int) : AutoCloseable {
        private class PendingJob(
            val source: SceneSource,
            val readout: ReadoutImage?,
            val pose: CameraPose3D?,
            val poseKey: Long,
            val filter: OpticalSceneFilter,
            val filterStack: OpticalFilterStackPort?,
            val filterTransmittance: Double,
            val opticsVersion: Long,
            val sourceSeq: Long,
            val exposure: Exposure,
            val geometry: SensorGeometry,
            val frameIndex: Long,
        )

        private val lock = java.util.concurrent.locks.ReentrantLock()
        private val jobAvailable = lock.newCondition()
        @Volatile
        private var closed = false
        private val buffers = PublishedBayerBuffers(frameBytes)
        private val exposureScaler = ExposureBayerScaler()
        private var hasReadyFrame: Boolean = false
        private var publishedGeneration: Long = 0L
        private var publishedDurationNanos: Long = -1L
        private var publishedIso: Int = -1
        private var publishedAperture: Double = Double.NaN
        private var publishedTransmission: Double = 1.0
        private var publishedFilterTransmittance: Double = 1.0
        private var publishedBlackLevel: Int = 0
        private var publishedBitDepth: Int = 16
        private var publishedPoseKey: Long = 0L
        private var publishedFocalLengthMm: Double = Double.NaN
        private var publishedFocusDistanceM: Double = Double.NaN
        private var publishedOpticsVersion: Long = Long.MIN_VALUE
        private var publishedSeq: Long = -1L
        private var pendingJob: PendingJob? = null
        private var lastSubmittedSource: Any? = null
        private var lastSubmittedFilter: OpticalSceneFilter? = null
        private var lastSubmittedSeq: Long = -1L
        private var lastSubmittedPoseKey: Long = 0L
        private var lastSubmittedDurationNanos: Long = -1L
        private var lastSubmittedIso: Int = -1
        private var lastSubmittedLens: LensParameters? = null
        private var lastSubmittedFilterRev: Long = -1L
        private var lastSubmittedFilterTransBits: Long = 0L
        private var lastSubmittedOpticsVersion: Long = Long.MIN_VALUE
        private val workerFilterScratch = OpticalFilterScratch()
        private var workerR = FloatArray(0)
        private var workerG = FloatArray(0)
        private var workerB = FloatArray(0)
        private var workerSpatialSrcRed: FloatArray? = null
        private var workerSpatialSrcSeq: Long = -1L
        private var workerSpatialFilterRev: Long = -1L
        private var workerSpatialFrame: LinearRadianceFrame? = null
        private val syncFilterScratch = OpticalFilterScratch()
        private var syncR = FloatArray(0)
        private var syncG = FloatArray(0)
        private var syncB = FloatArray(0)

        private fun applySpatialWithBuffers(
            frame: LinearRadianceFrame,
            filterStack: OpticalFilterStackPort?,
            exposure: Exposure,
            frameIndex: Long,
            isWorker: Boolean,
            opticalFilter: OpticalSceneFilter? = null,
        ): LinearRadianceFrame {
            if (filterStack == null || !filterStack.hasActiveSpatialEffects()) return frame
            if (opticalFilter != null && opticalFilter.handlesSpatialFilterStack(filterStack)) return frame
            val filterRev = filterStack.revision
            if (isWorker) {
                val cached = workerSpatialFrame
                if (
                    cached != null &&
                    frame.sequence > 0L &&
                    workerSpatialSrcRed === frame.red &&
                    workerSpatialSrcSeq == frame.sequence &&
                    workerSpatialFilterRev == filterRev &&
                    cached.width == frame.width &&
                    cached.height == frame.height
                ) {
                    return cached
                }
            }
            val count = frame.width * frame.height
            val rBuf: FloatArray
            val gBuf: FloatArray
            val bBuf: FloatArray
            val scratch: OpticalFilterScratch
            if (isWorker) {
                if (workerR.size < count) {
                    workerR = FloatArray(count)
                    workerG = FloatArray(count)
                    workerB = FloatArray(count)
                }
                rBuf = workerR; gBuf = workerG; bBuf = workerB; scratch = workerFilterScratch
            } else {
                if (syncR.size < count) {
                    syncR = FloatArray(count)
                    syncG = FloatArray(count)
                    syncB = FloatArray(count)
                }
                rBuf = syncR; gBuf = syncG; bBuf = syncB; scratch = syncFilterScratch
            }
            System.arraycopy(frame.red, 0, rBuf, 0, count)
            System.arraycopy(frame.green, 0, gBuf, 0, count)
            System.arraycopy(frame.blue, 0, bBuf, 0, count)
            val filterCtx = OpticalFilterContext(
                depthMeters = frame.depthMeters,
                fNumber = exposure.lens.aperture,
                focalLengthMm = exposure.lens.focalLengthMm,
                focusDistanceM = exposure.lens.focusDistanceM,
                rollDeg = frame.pose.rollDeg,
                pitchDeg = frame.pose.pitchDeg,
                frameIndex = frameIndex,
            )
            filterStack.applySpatialStack(rBuf, gBuf, bBuf, frame.width, frame.height, scratch, filterCtx)
            val outFrame = LinearRadianceFrame(
                width = frame.width,
                height = frame.height,
                red = rBuf,
                green = gBuf,
                blue = bBuf,
                depthMeters = frame.depthMeters,
                dimension = frame.dimension,
                virtualSubjectDistanceM = frame.virtualSubjectDistanceM,
                pose = frame.pose,
                sequence = mixSpatialSequence(frame.sequence, filterRev),
            )
            if (isWorker) {
                workerSpatialSrcRed = frame.red
                workerSpatialSrcSeq = frame.sequence
                workerSpatialFilterRev = filterRev
                workerSpatialFrame = outFrame
            }
            return outFrame
        }

        private val thread: Thread = Thread(::workerLoop, "fuji-cmos-optics-worker").apply {
            isDaemon = true
            start()
        }

        private fun computeExposureScale(
            durationNanos: Long,
            iso: Int,
            aperture: Double,
            transmission: Double,
            filterTransmittance: Double,
        ): Double {
            if (durationNanos <= 0L || iso <= 0 || aperture <= 0.0 || transmission <= 0.0 || filterTransmittance <= 0.0) {
                return 0.0
            }
            val invF = 1.0 / aperture
            val scale = durationNanos.toDouble() * iso.toDouble() * transmission * filterTransmittance * invF * invF
            return if (scale.isFinite() && scale > 0.0) scale else 0.0
        }

        fun exposeDoubleBuffered(
            source: SceneSource,
            readout: ReadoutImage?,
            pose: CameraPose3D?,
            opticalFilter: OpticalSceneFilter,
            filterStack: OpticalFilterStackPort?,
            exposure: Exposure,
            geometry: SensorGeometry,
            frameIndex: Long,
            destination: java.nio.ByteBuffer,
            periodicRefresh: Boolean = false,
        ): Long {
            val poseKey = pose?.quantizedFullKey() ?: 0L
            val liveSeq = readout?.packed?.sequence ?: (source as? LiveStreamSceneSource)?.latestFrameSequence ?: -1L
            val filterRev = filterStack?.revision ?: 0L
            val filterTrans = filterStack?.totalTransmittance() ?: 1.0
            val filterTransBits = filterTrans.toRawBits()
            val opticsVer = opticalFilter.opticalStateVersion()
            var readyRef: ByteArray? = null
            var readyGen = 0L
            var pubDuration = -1L
            var pubIso = -1
            var pubAperture = Double.NaN
            var pubTrans = 1.0
            var pubFilterTrans = 1.0
            var pubBlackLevel = 0
            var pubBitDepth = geometry.bitDepth
            var pubPoseKey = 0L
            var pubFocal = Double.NaN
            var pubFocus = Double.NaN
            var pubOpticsVer = Long.MIN_VALUE
            var pubSeq = -1L
            lock.lock()
            try {
                if (hasReadyFrame && lastSubmittedSource === source && lastSubmittedFilter === opticalFilter) {
                    readyRef = buffers.pinForOwner()
                    readyGen = publishedGeneration
                    pubDuration = publishedDurationNanos
                    pubIso = publishedIso
                    pubAperture = publishedAperture
                    pubTrans = publishedTransmission
                    pubFilterTrans = publishedFilterTransmittance
                    pubBlackLevel = publishedBlackLevel
                    pubBitDepth = publishedBitDepth
                    pubPoseKey = publishedPoseKey
                    pubFocal = publishedFocalLengthMm
                    pubFocus = publishedFocusDistanceM
                    pubOpticsVer = publishedOpticsVersion
                    pubSeq = publishedSeq
                }
            } finally {
                lock.unlock()
            }

            if (readyRef == null) {
                val rawFrame = readout?.radiance ?: checkNotNull(sampleRadianceFrame(source, pose))
                val frame = applySpatialWithBuffers(rawFrame, filterStack, exposure, frameIndex, isWorker = false, opticalFilter = opticalFilter)
                val effectiveSeq = if (liveSeq >= 0L) liveSeq else rawFrame.sequence
                val blackLevel = opticalFilter.sensorBlackLevelAdu(geometry.bitDepth)
                lock.lock()
                val gen: Long
                try {
                    val target = buffers.front
                    opticalFilter.filterRadianceFrame(
                        frame = frame,
                        exposure = exposure,
                        geometry = geometry,
                        destination = java.nio.ByteBuffer.wrap(target),
                        frameIndex = frameIndex,
                    )
                    hasReadyFrame = true
                    gen = ++publishedGeneration
                    publishedDurationNanos = exposure.durationNanos
                    publishedIso = exposure.iso
                    publishedAperture = exposure.lens.aperture
                    publishedTransmission = exposure.lens.transmission
                    publishedFilterTransmittance = filterTrans
                    publishedBlackLevel = blackLevel
                    publishedBitDepth = geometry.bitDepth
                    publishedPoseKey = poseKey
                    publishedFocalLengthMm = exposure.lens.focalLengthMm
                    publishedFocusDistanceM = exposure.lens.focusDistanceM
                    publishedOpticsVersion = opticsVer
                    publishedSeq = effectiveSeq
                    lastSubmittedSource = source
                    lastSubmittedFilter = opticalFilter
                    lastSubmittedSeq = effectiveSeq
                    lastSubmittedPoseKey = poseKey
                    lastSubmittedDurationNanos = exposure.durationNanos
                    lastSubmittedIso = exposure.iso
                    lastSubmittedLens = exposure.lens
                    lastSubmittedFilterRev = filterRev
                    lastSubmittedFilterTransBits = filterTransBits
                    lastSubmittedOpticsVersion = opticsVer
                    destination.put(target, 0, frameBytes)
                } finally {
                    lock.unlock()
                }
                return gen
            }

            // Zero-lag AE exposure feedback: when firmware adjusts shutter/ISO/aperture/ND before
            // the background optics worker finishes, reflect linear brightness immediately on frame 0
            // so AE metering never suffers 1-3 frame feedback lag / PID overshoot oscillation!
            val pubScale = computeExposureScale(pubDuration, pubIso, pubAperture, pubTrans, pubFilterTrans)
            val reqScale = computeExposureScale(
                exposure.durationNanos,
                exposure.iso,
                exposure.lens.aperture,
                exposure.lens.transmission,
                filterTrans,
            )
            val evRatio = if (pubScale > 0.0 && reqScale > 0.0) reqScale / pubScale else 1.0
            val exposureChanged = pubDuration > 0L && kotlin.math.abs(evRatio - 1.0) > 1e-3

            // Large exposure jump (>1 EV) saturation guard: when Stage-1 optical cache is hot and only
            // exposure/ISO/ND changed (optics/pose/sequence unchanged), run instant synchronous Stage-2
            // CMOS readout (<1.5 ms) instead of scaling already-clipped highlights.
            val isLargeJump = exposureChanged && (evRatio < 0.5 || evRatio > 2.0)
            val sameOpticsForHotSync = isLargeJump &&
                poseKey == pubPoseKey &&
                exposure.lens.aperture == pubAperture &&
                exposure.lens.focalLengthMm == pubFocal &&
                exposure.lens.focusDistanceM == pubFocus &&
                opticsVer >= 0L &&
                opticsVer == pubOpticsVer &&
                (liveSeq < 0L || liveSeq == pubSeq) &&
                opticalFilter.hasHotStage1CacheForExposureOnlyChange()
            if (sameOpticsForHotSync) {
                val rawFrame = readout?.radiance ?: sampleRadianceFrame(source, pose)
                if (rawFrame != null) {
                    val frame = applySpatialWithBuffers(rawFrame, filterStack, exposure, frameIndex, isWorker = false, opticalFilter = opticalFilter)
                    val effectiveSeq = if (liveSeq >= 0L) liveSeq else rawFrame.sequence
                    val blackLevel = opticalFilter.sensorBlackLevelAdu(geometry.bitDepth)
                    lock.lock()
                    val gen: Long
                    try {
                        val target = buffers.front
                        opticalFilter.filterRadianceFrame(
                            frame = frame,
                            exposure = exposure,
                            geometry = geometry,
                            destination = java.nio.ByteBuffer.wrap(target),
                            frameIndex = frameIndex,
                        )
                        hasReadyFrame = true
                        gen = ++publishedGeneration
                        publishedDurationNanos = exposure.durationNanos
                        publishedIso = exposure.iso
                        publishedAperture = exposure.lens.aperture
                        publishedTransmission = exposure.lens.transmission
                        publishedFilterTransmittance = filterTrans
                        publishedBlackLevel = blackLevel
                        publishedBitDepth = geometry.bitDepth
                        publishedPoseKey = poseKey
                        publishedFocalLengthMm = exposure.lens.focalLengthMm
                        publishedFocusDistanceM = exposure.lens.focusDistanceM
                        publishedOpticsVersion = opticsVer
                        publishedSeq = effectiveSeq
                        lastSubmittedSource = source
                        lastSubmittedFilter = opticalFilter
                        lastSubmittedSeq = effectiveSeq
                        lastSubmittedPoseKey = poseKey
                        lastSubmittedDurationNanos = exposure.durationNanos
                        lastSubmittedIso = exposure.iso
                        lastSubmittedLens = exposure.lens
                        lastSubmittedFilterRev = filterRev
                        lastSubmittedFilterTransBits = filterTransBits
                        lastSubmittedOpticsVersion = opticsVer
                        destination.put(target, 0, frameBytes)
                    } finally {
                        lock.unlock()
                    }
                    return gen
                }
            }

            if (exposureChanged) {
                exposureScaler.copy(readyRef, destination, evRatio, pubBlackLevel, pubBitDepth, sourceGeneration = readyGen)
            } else {
                destination.put(readyRef, 0, frameBytes)
            }

            lock.lock()
            try {
                if (!closed) {
                    val seqChanged = liveSeq >= 0L && liveSeq != lastSubmittedSeq
                    val paramsChanged = poseKey != lastSubmittedPoseKey ||
                        lastSubmittedDurationNanos != exposure.durationNanos ||
                        lastSubmittedIso != exposure.iso ||
                        lastSubmittedLens != exposure.lens ||
                        lastSubmittedFilterRev != filterRev ||
                        lastSubmittedFilterTransBits != filterTransBits ||
                        opticsVer < 0L ||
                        lastSubmittedOpticsVersion != opticsVer
                    if (seqChanged || paramsChanged || (periodicRefresh && (frameIndex and 3L) == 0L)) {
                        if (liveSeq >= 0L) lastSubmittedSeq = liveSeq
                        lastSubmittedPoseKey = poseKey
                        lastSubmittedDurationNanos = exposure.durationNanos
                        lastSubmittedIso = exposure.iso
                        lastSubmittedLens = exposure.lens
                        lastSubmittedFilterRev = filterRev
                        lastSubmittedFilterTransBits = filterTransBits
                        lastSubmittedOpticsVersion = opticsVer
                        pendingJob = PendingJob(
                            source = source,
                            readout = readout,
                            pose = pose,
                            poseKey = poseKey,
                            filter = opticalFilter,
                            filterStack = filterStack,
                            filterTransmittance = filterTrans,
                            opticsVersion = opticsVer,
                            sourceSeq = liveSeq,
                            exposure = exposure,
                            geometry = geometry,
                            frameIndex = frameIndex,
                        )
                        jobAvailable.signalAll()
                    }
                }
            } finally {
                lock.unlock()
            }
            if (!exposureChanged) return readyGen
            // Mix requested exposure scale into returned generation token so LiveViewDmaEngine
            // immediately invalidates cached YUV & refreshes AE stats on frame 0!
            val scaledMix = ((readyGen * 0x9E3779B97F4A7C15UL.toLong()) xor
                (reqScale.toRawBits() * 0xBF58476D1CE4E5B9UL.toLong())) and Long.MAX_VALUE
            return if (scaledMix == readyGen) (scaledMix xor 1L) else scaledMix
        }

        private fun workerLoop() {
            while (true) {
                val job = lock.lock().let {
                    try {
                        while (!closed && pendingJob == null) {
                            try {
                                jobAvailable.await()
                            } catch (_: InterruptedException) {
                                if (closed) return
                            }
                        }
                        if (closed) return
                        val next = pendingJob
                        pendingJob = null
                        next
                    } finally {
                        lock.unlock()
                    }
                } ?: continue

                runCatching {
                    if (!job.filter.enabled) return@runCatching
                    val target = buffers.back
                    val gpuSpec = if (job.source is SceneSource3D && job.pose != null && job.filter.supportsGpuScene3d() &&
                        (job.filterStack == null || !job.filterStack.hasActiveSpatialEffects() || job.filter.handlesSpatialFilterStack(job.filterStack))
                    ) {
                        job.source.gpuScene3dRenderSpec(job.pose)
                    } else {
                        null
                    }
                    val gpuWritten = if (gpuSpec != null) {
                        job.filter.tryFilterGpuScene3dToBayer(
                            spec = gpuSpec,
                            exposure = job.exposure,
                            geometry = job.geometry,
                            destination = java.nio.ByteBuffer.wrap(target),
                            frameIndex = job.frameIndex,
                        )
                    } else {
                        -1
                    }
                    var publishedSeqFromJob = job.sourceSeq
                    if (gpuWritten <= 0) {
                        val rawFrame = job.readout?.radiance ?: sampleRadianceFrame(job.source, job.pose) ?: return@runCatching
                        if (publishedSeqFromJob < 0L) publishedSeqFromJob = rawFrame.sequence
                        val frame = applySpatialWithBuffers(rawFrame, job.filterStack, job.exposure, job.frameIndex, isWorker = true, opticalFilter = job.filter)
                        job.filter.filterRadianceFrame(
                            frame = frame,
                            exposure = job.exposure,
                            geometry = job.geometry,
                            destination = java.nio.ByteBuffer.wrap(target),
                            frameIndex = job.frameIndex,
                        )
                    } else if (publishedSeqFromJob < 0L && gpuSpec != null) {
                        publishedSeqFromJob = gpuSpec.sequence
                    }
                    val blackLevel = job.filter.sensorBlackLevelAdu(job.geometry.bitDepth)
                    lock.lock()
                    try {
                        if (!closed && lastSubmittedSource === job.source && lastSubmittedFilter === job.filter) {
                            buffers.publishBack()
                            hasReadyFrame = true
                            publishedGeneration++
                            publishedDurationNanos = job.exposure.durationNanos
                            publishedIso = job.exposure.iso
                            publishedAperture = job.exposure.lens.aperture
                            publishedTransmission = job.exposure.lens.transmission
                            publishedFilterTransmittance = job.filterTransmittance
                            publishedBlackLevel = blackLevel
                            publishedBitDepth = job.geometry.bitDepth
                            publishedPoseKey = job.poseKey
                            publishedFocalLengthMm = job.exposure.lens.focalLengthMm
                            publishedFocusDistanceM = job.exposure.lens.focusDistanceM
                            publishedOpticsVersion = job.opticsVersion
                            publishedSeq = publishedSeqFromJob
                        }
                    } finally {
                        lock.unlock()
                    }
                }
            }
        }

        override fun close() {
            lock.lock()
            try {
                if (closed) return
                closed = true
                pendingJob = null
                hasReadyFrame = false
                jobAvailable.signalAll()
            } finally {
                lock.unlock()
            }
            thread.interrupt()
            runCatching { thread.join(1_000L) }
        }
    }

    private companion object {
        /**
         * Unified zero-extra-copy radiance frame sampler bridging typed [SceneSource3D],
         * [SceneSource2D], and legacy external [LinearRgbFrameSource] backends.
         */
        fun sampleRadianceFrame(source: SceneSource, pose: CameraPose3D? = null): LinearRadianceFrame? = when (source) {
            is SceneSource3D -> source.renderForPose(pose ?: CameraPose3D.IDENTITY)
            is SceneSource2D -> source.samplePlanarRadiance()
            is LinearRgbFrameSource -> source.latestLinearRgbFrame().toRadianceFrame(
                sequence = (source as? LiveStreamSceneSource)?.latestFrameSequence ?: 1L,
            )
            else -> null
        }

        fun mixSpatialSequence(baseSeq: Long, filterRev: Long): Long {
            if (baseSeq <= 0L) return 0L
            val mixed = (baseSeq * 0x9E3779B97F4A7C15UL.toLong()) xor (filterRev * 0xBF58476D1CE4E5B9UL.toLong())
            val pos = mixed and Long.MAX_VALUE
            return if (pos == 0L) 1L else pos
        }
        const val SENSOR_WIDTH = 1024
        const val SENSOR_HEIGHT = 576
        const val SENSOR_BIT_DEPTH = 16
        const val FRAME_BYTES = SENSOR_WIDTH * SENSOR_HEIGHT * 2
        val SENSOR_GEOMETRY = SensorGeometry(
            width = SENSOR_WIDTH,
            height = SENSOR_HEIGHT,
            bitDepth = SENSOR_BIT_DEPTH,
            bayer = BayerPattern.RGGB,
            pitchMicrometres = 3.76,
        )
        val SNAPSHOT_KEYS_V1 = setOf("schema", "componentId", "scalarValues")
        val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalarValues", "framesExposed", "exhausted")

        fun exactLong(value: Any?, field: String): Long = when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            is java.math.BigInteger -> {
                require(value.bitLength() <= 63) { "$field out of Long range" }
                value.toLong()
            }
            is java.math.BigDecimal -> try {
                value.longValueExact()
            } catch (exception: ArithmeticException) {
                throw IllegalArgumentException("$field requires exact integer", exception)
            }
            is Number -> {
                val decimal = value.toDouble()
                require(
                    decimal.isFinite() &&
                        decimal % 1.0 == 0.0 &&
                        decimal >= Long.MIN_VALUE.toDouble() &&
                        decimal < 9223372036854775808.0
                ) { "$field requires exact integer" }
                decimal.toLong()
            }
            else -> throw IllegalArgumentException("$field requires integer")
        }

        fun exactInt(value: Any?, field: String): Int {
            val number = exactLong(value, field)
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$field out of Int range" }
            return number.toInt()
        }
    }
}

// 54 MHz virtual clock. Dividing first keeps the conversion exact beyond a few minutes of uptime.
internal fun cameraNanos(tick: Long): Long =
    tick / 54_000_000L * 1_000_000_000L + tick % 54_000_000L * 1_000L / 54L

// A camera tick at a 60 Hz edge converts to the integer nanosecond containing that edge.
// Splitting off whole seconds keeps every intermediate in range for long-running clocks.
internal fun cameraAnimationFrame(elapsedNanos: Long): Long {
    val withinSecond = elapsedNanos % 1_000_000_000L
    return elapsedNanos / 1_000_000_000L * 60L + ((withinSecond + 1L) * 60L - 1L) / 1_000_000_000L
}

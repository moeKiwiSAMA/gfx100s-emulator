package com.github.nyabanban.fuji.emulator.lenses.ef85f14l

import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.LenscomIdentitySpec
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class CanonEf85F14LLensModule : LensModule {
    override val id: String = "canon-ef85-f14l"
    override val legacyTemplateIds: List<String> = listOf("adapter-ef-gfx")
    override val displayName: String = "Canon EF 85mm f/1.4L IS USM"
    override val mountType: String = "EF"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Fast stabilized portrait prime with USM (0.34 s), f/1.4 longitudinal CA, and pronounced lemon cat's-eye bokeh across its 43.3 mm image circle.",
        descriptionZh = "佳能 EF 大光圈防抖人像定焦 · USM 0.34s · f/1.4 轴向紫绿边色散与 43.3mm 像场经典柠檬猫眼散景。",
        descriptions = mapOf(
            "en" to "Fast stabilized portrait prime with USM (0.34 s), f/1.4 longitudinal CA, and pronounced lemon cat's-eye bokeh across its 43.3 mm image circle.",
            "zh_CN" to "佳能 EF 大光圈防抖人像定焦 · USM 0.34s · f/1.4 轴向紫绿边色散与 43.3mm 像场经典柠檬猫眼散景。",
            "ja_JP" to "手ブレ補正付き大口径ポートレート単焦点。USM 0.34秒、開放F1.4の軸上色収差と43.3mmイメージサークルによるレモン型口径食ボケ。",
            "de_DE" to "Lichtstarke stabilisierte Porträtfestbrennweite mit USM (0,34 s), f/1,4-Längsfarbfehler und ausgeprägtem Katzenaugen-Bokeh im 43,3-mm-Bildkreis.",
            "fr_FR" to "Focale fixe portrait stabilisée rapide avec USM (0,34 s), aberration chromatique axiale à f/1,4 et bokeh œil-de-chat marqué dans son cercle de 43,3 mm.",
            "es_ES" to "Objetivo fijo de retrato estabilizado luminoso con USM (0,34 s), aberración cromática axial a f/1.4 y marcado bokeh ojo de gato en su círculo de 43,3 mm.",
        ),
    )

    override val opticalSpec: LensOpticalSpec = LensOpticalSpec(
        id = id,
        displayName = displayName,
        mountType = mountType,
        modelName = "EF85mm F1.4L IS USM",
        defaultFocalLengthMm = 85.0,
        referenceFocalLengthMm = 85.0,
        minFocalLengthMm = 85.0,
        maxFocalLengthMm = 85.0,
        defaultAperture = 1.4,
        minAperture = 1.4,
        maxAperture = 22.0,
        minFocusDistanceM = 0.85,
        teleMinFocusDistanceM = 0.85,
        defaultFocusDistanceM = 2.5,
        transmission = 0.91,
        focusBreathing = -0.03,
        distortionK1 = 0.018,
        distortionK2 = -0.003,
        caRedScale = 1.0012,
        caBlueScale = 0.9988,
        longitudinalCaDefocusMm = 0.22,
        wideOpenContrastDrop = 0.12,
        vignettingCos4 = 2.2,
        vignettingMechanical = 0.25,
        imageCircleMm = 43.3,
        electronicContacts = true,
        oisEnabled = true,
        defaultFocusMode = "AF-S",
        focusMotorType = "USM",
        focusSweepSeconds = 0.34,
        flareStrength = 0.13,
        flareTintR = 0.92,
        flareTintG = 0.88,
        flareTintB = 1.0,
        apertureBladeCount = 9,
        apertureBladeRoundness = 0.85,
        bokehCatsEye = 0.45,
        bokehOnionRing = 0.05,
        bokehRimHighlight = 0.04,
        opticalCharacterSummary = "Fast stabilized portrait prime with USM (0.34 s), f/1.4 longitudinal CA, and pronounced lemon cat's-eye bokeh across its 43.3 mm image circle.",
    )

    override val controlContract: LensControlContract = LensControlContract.GenericSimulated(
        LenscomIdentitySpec(
            shortId = "EF85F14",
            modelAscii = "EF85mm F1.4L IS USM",
            serialAscii = "EF08501401",
            isZoom = false,
            hasOis = true,
            hasApertureRing = false,
            minFocalMm = 85.0,
            maxFocalMm = 85.0,
            minAperture = 1.4,
            maxAperture = 22.0,
            focusMotorType = "USM",
            focusSweepSeconds = 0.34,
        ),
    )
}

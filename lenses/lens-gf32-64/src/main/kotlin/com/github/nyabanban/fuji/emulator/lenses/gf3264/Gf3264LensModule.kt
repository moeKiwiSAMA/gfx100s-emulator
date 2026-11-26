package com.github.nyabanban.fuji.emulator.lenses.gf3264

import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.LenscomIdentitySpec
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class Gf3264LensModule : LensModule {
    override val id: String = "gf32-64"
    override val displayName: String = "GF 32-64mm F4 R LM WR"
    override val mountType: String = "GF"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "All-around standard zoom with Linear Motor (0.32 s), 3 aspherical elements, very low CA, high corner sharpness, and 9 rounded blades.",
        descriptionZh = "全能中画幅标准变焦 · 线性马达 0.32s · 3片非球面镜片高边缘锐度与均衡微反差，9片圆润光斑。",
        descriptions = mapOf(
            "en" to "All-around standard zoom with Linear Motor (0.32 s), 3 aspherical elements, very low CA, high corner sharpness, and 9 rounded blades.",
            "zh_CN" to "全能中画幅标准变焦 · 线性马达 0.32s · 3片非球面镜片高边缘锐度与均衡微反差，9片圆润光斑。",
            "ja_JP" to "リニアモーター0.32秒と非球面レンズ3枚を備えた万能標準ズーム。低色収差、高い周辺解像力、9枚円形絞り。",
            "de_DE" to "Vielseitiges Standardzoom mit Linearmotor (0,32 s), 3 asphärischen Linsen, minimaler CA, hoher Randschärfe und 9 abgerundeten Lamellen.",
            "fr_FR" to "Zoom standard polyvalent à moteur linéaire (0,32 s), 3 lentilles asphériques, très faible aberration chromatique et haute netteté dans les coins.",
            "es_ES" to "Zoom estándar versátil con motor lineal (0,32 s), 3 lentes asféricas, muy baja aberración cromática, gran nitidez en esquinas y 9 palas redondeadas.",
        ),
    )

    override val opticalSpec: LensOpticalSpec = LensOpticalSpec(
        id = id,
        displayName = displayName,
        mountType = mountType,
        modelName = "GF32-64mmF4 R LM WR",
        defaultFocalLengthMm = 32.0,
        referenceFocalLengthMm = 32.0,
        minFocalLengthMm = 32.0,
        maxFocalLengthMm = 64.0,
        defaultAperture = 4.0,
        minAperture = 4.0,
        maxAperture = 32.0,
        minFocusDistanceM = 0.50,
        teleMinFocusDistanceM = 0.60,
        defaultFocusDistanceM = 2.5,
        transmission = 0.92,
        focusBreathing = -0.018,
        distortionK1 = -0.04,
        distortionK2 = 0.006,
        caRedScale = 1.0014,
        caBlueScale = 0.9987,
        longitudinalCaDefocusMm = 0.03,
        wideOpenContrastDrop = 0.04,
        vignettingCos4 = 2.1,
        vignettingMechanical = 0.16,
        imageCircleMm = 56.0,
        electronicContacts = true,
        oisEnabled = false,
        defaultFocusMode = "AF-S",
        focusMotorType = "LM",
        focusSweepSeconds = 0.32,
        flareStrength = 0.12,
        flareTintR = 0.96,
        flareTintG = 0.98,
        flareTintB = 1.0,
        apertureBladeCount = 9,
        apertureBladeRoundness = 0.85,
        bokehCatsEye = 0.16,
        bokehOnionRing = 0.12,
        bokehRimHighlight = 0.05,
        opticalCharacterSummary = "All-around standard zoom with Linear Motor (0.32 s), 3 aspherical elements, very low CA, high corner sharpness, and 9 rounded blades.",
    )

    override val controlContract: LensControlContract = LensControlContract.GenericSimulated(
        LenscomIdentitySpec(
            shortId = "GF3264",
            modelAscii = "GF32-64mmF4 R LM WR",
            serialAscii = "GF03206401",
            capabilityFlags1 = 0x01, // DAT 0x0122C088: hdr[101] bit 0 = LMO/LEQ support (bits 3/4 = 0)
            isZoom = true,
            hasOis = false,
            hasApertureRing = true,
            minFocalMm = 32.0,
            maxFocalMm = 64.0,
            minAperture = 4.0,
            maxAperture = 32.0,
            focusMotorType = "LM",
            focusSweepSeconds = 0.32,
        ),
    )
}

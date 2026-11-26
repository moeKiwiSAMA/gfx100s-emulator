package com.github.nyabanban.fuji.emulator.lenses.gf45100

import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.LenscomIdentitySpec
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class Gf45100LensModule : LensModule {
    override val id: String = "gf45-100"
    override val displayName: String = "GF 45-100mm F4 R LM OIS WR"
    override val mountType: String = "GF"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "5-stop OIS standard zoom with Linear Motor (0.35 s), 9 rounded blades, crisp across 45–100 mm with low neutral-warm flare.",
        descriptionZh = "标准变焦 OIS 镜头 · 线性马达 0.35s · 9片圆形光圈 · 45–100mm 全焦段锐利，低暖中性炫光与轻微洋葱圈。",
        descriptions = mapOf(
            "en" to "5-stop OIS standard zoom with Linear Motor (0.35 s), 9 rounded blades, crisp across 45–100 mm with low neutral-warm flare.",
            "zh_CN" to "标准变焦 OIS 镜头 · 线性马达 0.35s · 9片圆形光圈 · 45–100mm 全焦段锐利，低暖中性炫光与轻微洋葱圈。",
            "ja_JP" to "5段分OIS手ブレ補正とリニアモーター0.35秒を搭載した標準ズーム。9枚円形絞り、45–100mm全域で高解像かつ穏やかな耐逆光性。",
            "de_DE" to "Standardzoom mit 5-Stufen-OIS und Linearmotor (0,35 s), 9 abgerundeten Lamellen, scharf über 45–100 mm bei geringem Streulicht.",
            "fr_FR" to "Zoom standard stabilisé OIS 5 stops à moteur linéaire (0,35 s), 9 lamelles arrondies, net de 45 à 100 mm avec faible flare.",
            "es_ES" to "Zoom estándar con OIS de 5 pasos y motor lineal (0,35 s), 9 palas redondeadas, nítido en todo 45–100 mm con bajo flare.",
        ),
    )

    override val opticalSpec: LensOpticalSpec = LensOpticalSpec(
        id = id,
        displayName = displayName,
        mountType = mountType,
        modelName = "GF45-100mmF4 R LM OIS WR",
        defaultFocalLengthMm = 45.0,
        referenceFocalLengthMm = 45.0,
        minFocalLengthMm = 45.0,
        maxFocalLengthMm = 100.0,
        defaultAperture = 4.0,
        minAperture = 4.0,
        maxAperture = 32.0,
        minFocusDistanceM = 0.65,
        teleMinFocusDistanceM = 0.82,
        defaultFocusDistanceM = 2.5,
        transmission = 0.91,
        focusBreathing = -0.02,
        distortionK1 = -0.025,
        distortionK2 = 0.004,
        caRedScale = 1.0018,
        caBlueScale = 0.9985,
        longitudinalCaDefocusMm = 0.04,
        wideOpenContrastDrop = 0.05,
        vignettingCos4 = 1.8,
        vignettingMechanical = 0.14,
        imageCircleMm = 56.0,
        electronicContacts = true,
        oisEnabled = true,
        defaultFocusMode = "AF-S",
        focusMotorType = "LM",
        focusSweepSeconds = 0.35,
        flareStrength = 0.14,
        flareTintR = 1.0,
        flareTintG = 0.95,
        flareTintB = 0.88,
        apertureBladeCount = 9,
        apertureBladeRoundness = 0.85,
        bokehCatsEye = 0.18,
        bokehOnionRing = 0.10,
        bokehRimHighlight = 0.06,
        opticalCharacterSummary = "5-stop OIS standard zoom with Linear Motor (0.35 s), 9 rounded blades, crisp across 45–100 mm with low neutral-warm flare.",
    )

    override val controlContract: LensControlContract = LensControlContract.GenericSimulated(
        LenscomIdentitySpec(
            shortId = "GF45100",
            modelAscii = "GF45-100mmF4 R LM OIS WR",
            serialAscii = "GF04510001",
            capabilityFlags1 = 0x01, // DAT 0x0122C088: hdr[101] bit 0 = LMO/LEQ support (bits 3/4 = 0)
            isZoom = true,
            hasOis = true,
            hasApertureRing = true,
            minFocalMm = 45.0,
            maxFocalMm = 100.0,
            minAperture = 4.0,
            maxAperture = 32.0,
            focusMotorType = "LM",
            focusSweepSeconds = 0.35,
        ),
    )
}

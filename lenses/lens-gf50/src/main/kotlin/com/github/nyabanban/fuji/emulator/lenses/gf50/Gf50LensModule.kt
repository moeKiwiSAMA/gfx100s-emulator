package com.github.nyabanban.fuji.emulator.lenses.gf50

import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.LenscomIdentitySpec
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class Gf50LensModule : LensModule {
    override val id: String = "gf50"
    override val displayName: String = "GF 50mm F3.5 R LM WR"
    override val mountType: String = "GF"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Ultra-light pancake prime with the fastest GF Linear Motor (0.22 s), near-zero chromatic aberration, and crisp wide-open street performance.",
        descriptionZh = "轻量饼干定焦 · 最快 GF 线性马达 0.22s · 几乎零色散，全开锐利高反差，适合街头抓拍。",
        descriptions = mapOf(
            "en" to "Ultra-light pancake prime with the fastest GF Linear Motor (0.22 s), near-zero chromatic aberration, and crisp wide-open street performance.",
            "zh_CN" to "轻量饼干定焦 · 最快 GF 线性马达 0.22s · 几乎零色散，全开锐利高反差，适合街头抓拍。",
            "ja_JP" to "GF最速0.22秒リニアモーターを搭載した超軽量パンケーキ単焦点。色収差ほぼゼロ、開放からシャープでスナップに最適。",
            "de_DE" to "Ultraleichte Pancake-Festbrennweite mit schnellstem GF-Linearmotor (0,22 s), nahezu null CA und hoher Offenblendschärfe für Streetfotografie.",
            "fr_FR" to "Focale fixe pancake ultralégère au moteur linéaire GF le plus rapide (0,22 s), aberration quasi nulle et netteté immédiate pour la rue.",
            "es_ES" to "Objetivo fijo pancake ultraligero con el motor lineal GF más rápido (0,22 s), aberración cromática casi nula y gran nitidez callejera.",
        ),
    )

    override val opticalSpec: LensOpticalSpec = LensOpticalSpec(
        id = id,
        displayName = displayName,
        mountType = mountType,
        modelName = "GF50mmF3.5 R LM WR",
        defaultFocalLengthMm = 50.0,
        referenceFocalLengthMm = 50.0,
        minFocalLengthMm = 50.0,
        maxFocalLengthMm = 50.0,
        defaultAperture = 3.5,
        minAperture = 3.5,
        maxAperture = 32.0,
        minFocusDistanceM = 0.55,
        teleMinFocusDistanceM = 0.55,
        defaultFocusDistanceM = 2.5,
        transmission = 0.93,
        focusBreathing = -0.01,
        distortionK1 = -0.018,
        distortionK2 = 0.003,
        caRedScale = 1.0006,
        caBlueScale = 0.9995,
        longitudinalCaDefocusMm = 0.02,
        wideOpenContrastDrop = 0.03,
        vignettingCos4 = 1.7,
        vignettingMechanical = 0.12,
        imageCircleMm = 56.0,
        electronicContacts = true,
        oisEnabled = false,
        defaultFocusMode = "AF-S",
        focusMotorType = "LM_FAST",
        focusSweepSeconds = 0.22,
        flareStrength = 0.09,
        flareTintR = 0.96,
        flareTintG = 0.98,
        flareTintB = 1.0,
        apertureBladeCount = 9,
        apertureBladeRoundness = 0.82,
        bokehCatsEye = 0.12,
        bokehOnionRing = 0.06,
        bokehRimHighlight = 0.04,
        opticalCharacterSummary = "Ultra-light pancake prime with the fastest GF Linear Motor (0.22 s), near-zero chromatic aberration, and crisp wide-open street performance.",
    )

    override val controlContract: LensControlContract = LensControlContract.GenericSimulated(
        LenscomIdentitySpec(
            shortId = "GF50F35",
            modelAscii = "GF50mmF3.5 R LM WR",
            serialAscii = "GF05003501",
            capabilityFlags1 = 0x01, // DAT 0x0122C088: hdr[101] bit 0 = LMO/LEQ support (bits 3/4 = 0)
            isZoom = false,
            hasOis = false,
            hasApertureRing = true,
            minFocalMm = 50.0,
            maxFocalMm = 50.0,
            minAperture = 3.5,
            maxAperture = 32.0,
            focusMotorType = "LM_FAST",
            focusSweepSeconds = 0.22,
        ),
    )
}

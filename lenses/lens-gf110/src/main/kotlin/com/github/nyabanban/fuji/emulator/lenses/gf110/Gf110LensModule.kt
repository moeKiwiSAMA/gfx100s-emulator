package com.github.nyabanban.fuji.emulator.lenses.gf110

import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.LenscomIdentitySpec
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class Gf110LensModule : LensModule {
    override val id: String = "gf110"
    override val displayName: String = "GF 110mm F2 R LM WR"
    override val mountType: String = "GF"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Flagship portrait prime with 4 ED elements, Linear Motor (0.40 s), near-zero distortion, and creamy f/2.0 bokeh without hard rim outlines.",
        descriptionZh = "中画幅人像镜皇 · 4枚 ED 复消色差 · 线性马达 0.40s · 几乎零畸变与 f/2.0 奶油无亮边圆焦外。",
        descriptions = mapOf(
            "en" to "Flagship portrait prime with 4 ED elements, Linear Motor (0.40 s), near-zero distortion, and creamy f/2.0 bokeh without hard rim outlines.",
            "zh_CN" to "中画幅人像镜皇 · 4枚 ED 复消色差 · 线性马达 0.40s · 几乎零畸变与 f/2.0 奶油无亮边圆焦外。",
            "ja_JP" to "EDレンズ4枚採用の中判ポートレート旗艦単焦点。リニアモーター0.40秒、極めて少ない歪曲と輪郭のないクリーミーなF2.0ボケ。",
            "de_DE" to "Flaggschiff-Porträtfestbrennweite mit 4 ED-Linsen, Linearmotor (0,40 s), nahezu null Verzeichnung und cremigem f/2,0-Bokeh ohne harte Ränder.",
            "fr_FR" to "Focale fixe portrait phare à 4 lentilles ED, moteur linéaire (0,40 s), distorsion quasi nulle et bokeh crémeux à f/2,0 sans franges dures.",
            "es_ES" to "Objetivo fijo de retrato insignia con 4 lentes ED, motor lineal (0,40 s), distorsión casi nula y bokeh cremoso a f/2.0 sin bordes duros.",
        ),
    )

    override val opticalSpec: LensOpticalSpec = LensOpticalSpec(
        id = id,
        displayName = displayName,
        mountType = mountType,
        modelName = "GF110mmF2 R LM WR",
        defaultFocalLengthMm = 110.0,
        referenceFocalLengthMm = 110.0,
        minFocalLengthMm = 110.0,
        maxFocalLengthMm = 110.0,
        defaultAperture = 2.0,
        minAperture = 2.0,
        maxAperture = 22.0,
        minFocusDistanceM = 0.90,
        teleMinFocusDistanceM = 0.90,
        defaultFocusDistanceM = 2.5,
        transmission = 0.94,
        focusBreathing = -0.025,
        distortionK1 = 0.012,
        distortionK2 = -0.002,
        caRedScale = 1.0005,
        caBlueScale = 0.9996,
        longitudinalCaDefocusMm = 0.03,
        wideOpenContrastDrop = 0.03,
        vignettingCos4 = 1.2,
        vignettingMechanical = 0.12,
        imageCircleMm = 57.0,
        electronicContacts = true,
        oisEnabled = false,
        defaultFocusMode = "AF-S",
        focusMotorType = "LM",
        focusSweepSeconds = 0.40,
        flareStrength = 0.08,
        flareTintR = 0.96,
        flareTintG = 0.97,
        flareTintB = 1.0,
        apertureBladeCount = 9,
        apertureBladeRoundness = 0.92,
        bokehCatsEye = 0.10,
        bokehOnionRing = 0.02,
        bokehRimHighlight = 0.00,
        opticalCharacterSummary = "Flagship portrait prime with 4 ED elements, Linear Motor (0.40 s), near-zero distortion, and creamy f/2.0 bokeh without hard rim outlines.",
    )

    override val controlContract: LensControlContract = LensControlContract.GenericSimulated(
        LenscomIdentitySpec(
            shortId = "GF110F2",
            modelAscii = "GF110mmF2 R LM WR",
            serialAscii = "GF11002001",
            capabilityFlags1 = 0x01, // DAT 0x0122C088: hdr[101] bit 0 = LMO/LEQ support (bits 3/4 = 0)
            isZoom = false,
            hasOis = false,
            hasApertureRing = true,
            minFocalMm = 110.0,
            maxFocalMm = 110.0,
            minAperture = 2.0,
            maxAperture = 22.0,
            focusMotorType = "LM",
            focusSweepSeconds = 0.40,
        ),
    )
}

package com.github.nyabanban.fuji.emulator.lenses.zeissplanar80

import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class ZeissPlanar80F28LensModule : LensModule {
    override val id: String = "zeiss-planar-80-f28"
    override val legacyTemplateIds: List<String> = listOf("adapter-hasselblad-v")
    override val displayName: String = "Carl Zeiss Planar T* 80mm f/2.8"
    override val mountType: String = "HASSELBLAD_V"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Medium-format 6x6 Carl Zeiss Planar T* manual prime with a massive 79 mm image circle, virtually zero cat's-eye, signature 5-blade pentagonal bokeh, and crisp micro-contrast.",
        descriptionZh = "哈苏 6x6 中画幅蔡司 Planar T* 手动定焦 · 79mm 超大像场无猫眼、标志性 5叶片五边形光斑与高微反差。",
        descriptions = mapOf(
            "en" to "Medium-format 6x6 Carl Zeiss Planar T* manual prime with a massive 79 mm image circle, virtually zero cat's-eye, signature 5-blade pentagonal bokeh, and crisp micro-contrast.",
            "zh_CN" to "哈苏 6x6 中画幅蔡司 Planar T* 手动定焦 · 79mm 超大像场无猫眼、标志性 5叶片五边形光斑与高微反差。",
            "ja_JP" to "79mmの余裕あるイメージサークルで口径食ほぼゼロ、象徴的な5枚羽根五角形ボケと高いマイクロコントラストを誇る6x6中判ツァイスPlanar T*。",
            "de_DE" to "Mittelformat-6x6-Carl-Zeiss-Planar-T* mit riesigem 79-mm-Bildkreis, praktisch ohne Katzenaugen, ikonischem 5-Lamellen-Fünfeck-Bokeh und hohem Mikrokontrast.",
            "fr_FR" to "Carl Zeiss Planar T* moyen format 6x6 manuel au cercle d'image massif de 79 mm, quasi sans œil-de-chat, bokeh pentagonal 5 lamelles et micro-contraste ciselé.",
            "es_ES" to "Carl Zeiss Planar T* manual de formato medio 6x6 con enorme círculo de imagen de 79 mm, casi sin ojo de gato, bokeh pentagonal de 5 palas y gran microcontraste.",
        ),
    )

    override val opticalSpec: LensOpticalSpec = LensOpticalSpec(
        id = id,
        displayName = displayName,
        mountType = mountType,
        modelName = "Carl Zeiss Planar T* 80mm F2.8",
        defaultFocalLengthMm = 80.0,
        referenceFocalLengthMm = 80.0,
        minFocalLengthMm = 80.0,
        maxFocalLengthMm = 80.0,
        defaultAperture = 2.8,
        minAperture = 2.8,
        maxAperture = 22.0,
        minFocusDistanceM = 0.90,
        teleMinFocusDistanceM = 0.90,
        defaultFocusDistanceM = 2.5,
        transmission = 0.92,
        focusBreathing = -0.01,
        distortionK1 = -0.008,
        distortionK2 = 0.001,
        caRedScale = 1.0014,
        caBlueScale = 0.9986,
        longitudinalCaDefocusMm = 0.08,
        wideOpenContrastDrop = 0.07,
        vignettingCos4 = 1.1,
        vignettingMechanical = 0.06,
        imageCircleMm = 79.0,
        electronicContacts = false,
        oisEnabled = false,
        defaultFocusMode = "MF",
        focusMotorType = "MANUAL",
        focusSweepSeconds = 0.0,
        flareStrength = 0.15,
        flareTintR = 1.0,
        flareTintG = 0.92,
        flareTintB = 0.78,
        apertureBladeCount = 5,
        apertureBladeRoundness = 0.15,
        bokehCatsEye = 0.04,
        bokehOnionRing = 0.03,
        bokehRimHighlight = 0.15,
        opticalCharacterSummary = "Medium-format 6x6 Carl Zeiss Planar T* manual prime with a massive 79 mm image circle, virtually zero cat's-eye, signature 5-blade pentagonal bokeh, and crisp micro-contrast.",
    )

    override val controlContract: LensControlContract = LensControlContract.Manual
}

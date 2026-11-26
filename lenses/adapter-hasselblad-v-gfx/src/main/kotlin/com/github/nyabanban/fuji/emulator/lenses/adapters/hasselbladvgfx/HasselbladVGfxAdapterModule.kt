package com.github.nyabanban.fuji.emulator.lenses.adapters.hasselbladvgfx

import com.github.nyabanban.fuji.emulator.api.LensCommunicationMode
import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.MountAdapterModule
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class HasselbladVGfxAdapterModule : MountAdapterModule {
    override val id: String = "adapter-hasselblad-v-gfx"
    override val legacyTemplateIds: List<String> = listOf("adapter-hasselblad-v")
    override val displayName: String = "Hasselblad V 6x6 Mechanical Adapter (Dumb)"
    override val bodyMount: String = "GF"
    override val lensMount: String = "HASSELBLAD_V"
    override val communicationMode: LensCommunicationMode = LensCommunicationMode.MANUAL
    override val electronicContacts: Boolean = false
    override val defaultLensId: String = "zeiss-planar-80-f28"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Purely mechanical Hasselblad V 6x6 medium-format to GF mount adapter preserving full 79 mm image circle coverage.",
        descriptionZh = "纯机械哈苏 V 6x6 中画幅转 GF 卡口转接环，完整保留 79mm 大像场覆盖与手动机械操控。",
        names = mapOf(
            "en" to "Hasselblad V 6x6 -> GFX Mechanical Adapter (Manual)",
            "zh_CN" to "哈苏 V 6x6 -> GFX 机械转接环（纯手动）",
            "ja_JP" to "ハッセルブラッド V 6x6 → GFX 機械式アダプター（手動）",
            "de_DE" to "Hasselblad V 6x6 -> GFX Mechanischer Adapter (Manuell)",
            "fr_FR" to "Bague mécanique Hasselblad V 6x6 -> GFX (Manuel)",
            "es_ES" to "Adaptador mecánico Hasselblad V 6x6 -> GFX (Manual)",
        ),
        descriptions = mapOf(
            "en" to "Purely mechanical Hasselblad V 6x6 medium-format to GF mount adapter preserving full 79 mm image circle coverage.",
            "zh_CN" to "纯机械哈苏 V 6x6 中画幅转 GF 卡口转接环，完整保留 79mm 大像场覆盖与手动机械操控。",
            "ja_JP" to "79mmの広大なイメージサークルを余すことなく活かせる、電子接点なしの純機械式ハッセルブラッドV-GFアダプター。",
            "de_DE" to "Rein mechanischer Hasselblad-V-6x6-auf-GF-Adapter mit vollem 79-mm-Bildkreis für manuelle Mittelformatobjektive.",
            "fr_FR" to "Bague purement mécanique Hasselblad V 6x6 vers GF préservant l'intégralité du cercle d'image de 79 mm.",
            "es_ES" to "Adaptador puramente mecánico Hasselblad V 6x6 a GF que conserva el círculo de imagen completo de 79 mm.",
        ),
    )

    override fun bridgeOpticalSpec(lens: LensModule): LensOpticalSpec {
        require(lens.mountType == lensMount) {
            "Hasselblad V adapter requires mountType='$lensMount', got '${lens.mountType}' (${lens.id})"
        }
        val spec = lens.opticalSpec
        return spec.copy(
            id = "adapter-hasselblad-v",
            displayName = "Hasselblad V 6x6 + ${spec.displayName}",
            mountType = "Hasselblad-V->GFX",
            modelName = "Hasselblad V ${spec.modelName}",
            electronicContacts = false,
            oisEnabled = false,
            defaultFocusMode = "MF",
            focusMotorType = "MANUAL",
            focusSweepSeconds = 0.0,
        )
    }

    override fun bridgeControlContract(lens: LensModule): LensControlContract {
        require(lens.mountType == lensMount) {
            "Hasselblad V adapter requires mountType='$lensMount', got '${lens.mountType}' (${lens.id})"
        }
        return LensControlContract.Manual
    }
}

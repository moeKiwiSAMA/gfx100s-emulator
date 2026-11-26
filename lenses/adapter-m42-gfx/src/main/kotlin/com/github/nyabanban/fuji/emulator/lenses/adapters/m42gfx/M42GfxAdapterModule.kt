package com.github.nyabanban.fuji.emulator.lenses.adapters.m42gfx

import com.github.nyabanban.fuji.emulator.api.LensCommunicationMode
import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.MountAdapterModule
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class M42GfxAdapterModule : MountAdapterModule {
    override val id: String = "adapter-m42-gfx"
    override val legacyTemplateIds: List<String> = listOf("adapter-m42-dumb")
    override val displayName: String = "M42 Mechanical Adapter (Dumb)"
    override val bodyMount: String = "GF"
    override val lensMount: String = "M42"
    override val communicationMode: LensCommunicationMode = LensCommunicationMode.MANUAL
    override val electronicContacts: Boolean = false
    override val defaultLensId: String = "takumar-50-f14"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Purely mechanical M42 screw-mount to GF adapter with zero electronic contacts for vintage manual-focus primes.",
        descriptionZh = "纯机械 M42 螺口转 GF 卡口转接环，无电子触点，适配各类复古手动定焦镜头。",
        names = mapOf(
            "en" to "M42 -> GFX Mechanical Adapter (Manual)",
            "zh_CN" to "M42 -> GFX 机械转接环（纯手动）",
            "ja_JP" to "M42 → GFX 機械式アダプター（手動）",
            "de_DE" to "M42 -> GFX Mechanischer Adapter (Manuell)",
            "fr_FR" to "Bague mécanique M42 -> GFX (Manuel)",
            "es_ES" to "Adaptador mecánico M42 -> GFX (Manual)",
        ),
        descriptions = mapOf(
            "en" to "Purely mechanical M42 screw-mount to GF adapter with zero electronic contacts for vintage manual-focus primes.",
            "zh_CN" to "纯机械 M42 螺口转 GF 卡口转接环，无电子触点，适配各类复古手动定焦镜头。",
            "ja_JP" to "電子接点を持たない純機械式M42スクリューマウント→GFアダプター。クラシックなマニュアル単焦点レンズに対応。",
            "de_DE" to "Rein mechanischer M42-Schraubgewinde-auf-GF-Adapter ohne elektronische Kontakte für klassische manuelle Festbrennweiten.",
            "fr_FR" to "Bague mécanique M42 à vis vers GF sans contacts électroniques pour objectifs vintage à mise au point manuelle.",
            "es_ES" to "Adaptador mecánico de rosca M42 a GF sin contactos electrónicos para objetivos fijos manuales clásicos.",
        ),
    )

    override fun bridgeOpticalSpec(lens: LensModule): LensOpticalSpec {
        require(lens.mountType == lensMount) {
            "M42 mechanical adapter requires mountType='$lensMount', got '${lens.mountType}' (${lens.id})"
        }
        val spec = lens.opticalSpec
        return spec.copy(
            id = "adapter-m42-dumb",
            displayName = "M42 Mechanical Adapter + ${spec.displayName}",
            mountType = "M42/M->GFX",
            modelName = "M42 ${spec.modelName}",
            electronicContacts = false,
            oisEnabled = false,
            defaultFocusMode = "MF",
            focusMotorType = "MANUAL",
            focusSweepSeconds = 0.0,
        )
    }

    override fun bridgeControlContract(lens: LensModule): LensControlContract {
        require(lens.mountType == lensMount) {
            "M42 mechanical adapter requires mountType='$lensMount', got '${lens.mountType}' (${lens.id})"
        }
        return LensControlContract.Manual
    }
}

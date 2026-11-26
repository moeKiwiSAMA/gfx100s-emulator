package com.github.nyabanban.fuji.emulator.desktop.theme

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared runtime coordinator for [UserProfile], [BodyTheme], and [UiTheme].
 *
 * Bridges all 4 user surfaces (GUI header selectors, CLI `/theme`, Control API `/api/theme`,
 * and `<run-dir>/profile.yaml` persistence) with multicast listeners and live hot-reload
 * (`reloadThemes()`) so any theme switch or external pack edit updates live windows immediately.
 */
class ThemeRuntimeController(
    val home: Path,
    val profilePath: Path = UserProfileStore.defaultPath(home),
) {
    private val log = LoggerFactory.getLogger(ThemeRuntimeController::class.java)
    private val lock = Any()
    private val reloadGenCounter = AtomicLong(0L)

    @Volatile
    var profile: UserProfile
        private set

    @Volatile
    var bodyTheme: BodyTheme
        private set

    @Volatile
    var uiTheme: UiTheme
        private set

    private val listeners = CopyOnWriteArrayList<(BodyTheme, UiTheme, UserProfile) -> Unit>()

    @Volatile
    private var legacyGuiListener: ((BodyTheme, UiTheme, UserProfile) -> Unit)? = null

    data class ApplyResult(
        val bodyTheme: BodyTheme,
        val uiTheme: UiTheme,
        val profile: UserProfile,
        val bodyChanged: Boolean,
        val uiChanged: Boolean,
    )

    data class ReloadResult(
        val bodyTheme: BodyTheme,
        val uiTheme: UiTheme,
        val profile: UserProfile,
        val bodyCount: Int,
        val uiCount: Int,
        val reloadGeneration: Long,
    )

    init {
        val loaded = UserProfileStore.loadOrCreate(profilePath)
        val resolvedBody = BodyThemeRegistry.resolveOrFallback(loaded.bodyTheme, home)
        val resolvedUi = UiThemeRegistry.resolveOrFallback(loaded.uiTheme, home)
        profile = loaded.copy(bodyTheme = resolvedBody.id, uiTheme = resolvedUi.id)
        bodyTheme = resolvedBody
        uiTheme = resolvedUi
    }

    /** Registers a multicast theme change listener; close the returned handle to unsubscribe. */
    fun addThemeListener(listener: (BodyTheme, UiTheme, UserProfile) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { removeThemeListener(listener) }
    }

    fun removeThemeListener(listener: (BodyTheme, UiTheme, UserProfile) -> Unit) {
        listeners.remove(listener)
    }

    /** Legacy single-slot GUI listener kept for backward compatibility. */
    fun setGuiListener(listener: ((BodyTheme, UiTheme, UserProfile) -> Unit)?) {
        synchronized(lock) {
            legacyGuiListener?.let { listeners.remove(it) }
            legacyGuiListener = listener
            if (listener != null) {
                listeners += listener
            }
        }
    }

    private fun notifyListeners(nextBody: BodyTheme, nextUi: UiTheme, nextProfile: UserProfile) {
        listeners.forEach { listener ->
            runCatching { listener(nextBody, nextUi, nextProfile) }
                .onFailure { err ->
                    log.warn("[FALLBACK][theme] Theme listener failed: {}", err.message ?: err.javaClass.simpleName)
                }
        }
    }

    fun availableBodyThemes(): List<BodyTheme> = BodyThemeRegistry.discoverAll(home)

    fun availableUiThemes(): List<UiTheme> = UiThemeRegistry.discoverAll(home, reloadGenCounter.get())

    /**
     * Switches body and/or UI theme immediately, persists `profile.yaml`, and notifies all listeners.
     * Throws [IllegalArgumentException] when an explicitly requested theme ID does not exist.
     */
    fun applyTheme(bodyId: String? = null, uiId: String? = null): ApplyResult {
        val result: ApplyResult
        synchronized(lock) {
            val gen = reloadGenCounter.get()
            val nextBody = if (bodyId != null) {
                BodyThemeRegistry.findExact(bodyId, home)
                    ?: throw IllegalArgumentException(
                        "Unknown body theme '$bodyId'. Available: ${availableBodyThemes().joinToString(", ") { it.id }}"
                    )
            } else {
                bodyTheme
            }
            val nextUi = if (uiId != null) {
                UiThemeRegistry.findExact(uiId, home, gen)
                    ?: throw IllegalArgumentException(
                        "Unknown UI theme '$uiId'. Available: ${availableUiThemes().joinToString(", ") { it.id }}"
                    )
            } else {
                uiTheme
            }
            val bodyChanged = nextBody.id != bodyTheme.id
            val uiChanged = nextUi.id != uiTheme.id
            val updatedProfile = profile.copy(
                bodyTheme = nextBody.id,
                uiTheme = nextUi.id,
            )
            bodyTheme = nextBody
            uiTheme = nextUi
            profile = updatedProfile
            runCatching { UserProfileStore.save(profilePath, updatedProfile) }
                .onFailure { err ->
                    log.warn("[FALLBACK][profile] Failed saving user profile {}: {}", profilePath, err.message)
                }
            result = ApplyResult(
                bodyTheme = nextBody,
                uiTheme = nextUi,
                profile = updatedProfile,
                bodyChanged = bodyChanged,
                uiChanged = uiChanged,
            )
        }
        if (result.bodyChanged || result.uiChanged) {
            notifyListeners(result.bodyTheme, result.uiTheme, result.profile)
        }
        return result
    }

    /**
     * Rescans external `./themes/body/` and `./themes/ui/` packs, re-resolves the active body and
     * UI themes (falling back cleanly to `origin` if an external pack was removed or broken),
     * and notifies every listener even when theme IDs have not changed.
     */
    fun reloadThemes(): ReloadResult {
        val result: ReloadResult
        synchronized(lock) {
            val gen = reloadGenCounter.incrementAndGet()
            val allBodies = BodyThemeRegistry.discoverAll(home)
            val allUis = UiThemeRegistry.discoverAll(home, gen)
            val nextBody = BodyThemeRegistry.resolveOrFallback(profile.bodyTheme, home)
            val nextUi = UiThemeRegistry.resolveOrFallback(profile.uiTheme, home, gen)
            val updatedProfile = profile.copy(
                bodyTheme = nextBody.id,
                uiTheme = nextUi.id,
            )
            bodyTheme = nextBody
            uiTheme = nextUi
            if (updatedProfile != profile) {
                profile = updatedProfile
                runCatching { UserProfileStore.save(profilePath, updatedProfile) }
                    .onFailure { err ->
                        log.warn("[FALLBACK][profile] Failed saving user profile {}: {}", profilePath, err.message)
                    }
            }
            result = ReloadResult(
                bodyTheme = nextBody,
                uiTheme = nextUi,
                profile = profile,
                bodyCount = allBodies.size,
                uiCount = allUis.size,
                reloadGeneration = gen,
            )
        }
        notifyListeners(result.bodyTheme, result.uiTheme, result.profile)
        return result
    }

    /** Updates non-theme window shell preferences in `profile.yaml`. */
    fun updateWindowPreferences(
        fitScale: Double? = null,
        evfWindowOpen: Boolean? = null,
        evfAlwaysOnTop: Boolean? = null,
    ): UserProfile {
        synchronized(lock) {
            val clampedScale = fitScale?.takeIf { it.isFinite() }?.coerceIn(UserProfile.MIN_FIT_SCALE, UserProfile.MAX_FIT_SCALE)
            val updated = profile.copy(
                fitScale = clampedScale ?: profile.fitScale,
                evfWindowOpen = evfWindowOpen ?: profile.evfWindowOpen,
                evfAlwaysOnTop = evfAlwaysOnTop ?: profile.evfAlwaysOnTop,
            )
            if (updated == profile) return profile
            profile = updated
            runCatching { UserProfileStore.save(profilePath, updated) }
                .onFailure { err ->
                    log.warn("[FALLBACK][profile] Failed persisting window preferences to {}: {}", profilePath, err.message)
                }
            return updated
        }
    }

    /** Updates optical compute backend / Vulkan GPU selection in `profile.yaml`. */
    fun updateGraphicsPreferences(opticsBackend: String? = null, vulkanDevice: String? = null): UserProfile {
        synchronized(lock) {
            val nextBackend = opticsBackend?.trim()?.lowercase()?.takeIf { it in UserProfile.SUPPORTED_OPTICS_BACKENDS }
                ?: profile.opticsBackend
            val nextDevice = vulkanDevice?.trim()?.take(280)?.ifEmpty { UserProfile.DEFAULT_VULKAN_DEVICE }
                ?: profile.vulkanDevice
            val updated = profile.copy(
                opticsBackend = nextBackend,
                vulkanDevice = nextDevice,
            )
            if (updated == profile) return profile
            profile = updated
            runCatching { UserProfileStore.save(profilePath, updated) }
                .onFailure { err ->
                    log.warn("[FALLBACK][profile] Failed persisting graphics preferences to {}: {}", profilePath, err.message)
                }
            return updated
        }
    }

    /** Persists an already-resolved canonical UI language code to `profile.yaml`. */
    fun persistLanguage(canonicalCode: String): UserProfile {
        synchronized(lock) {
            if (profile.language == canonicalCode) return profile
            val updated = profile.copy(language = canonicalCode)
            profile = updated
            runCatching { UserProfileStore.save(profilePath, updated) }
                .onFailure { err ->
                    log.warn("[FALLBACK][profile] Failed persisting language '{}' to {}: {}", canonicalCode, profilePath, err.message)
                }
            return updated
        }
    }

    /** Updates the active UI language via [I18n] and persists `language` to `profile.yaml`. */
    fun updateLanguage(localeCode: String): UserProfile {
        val resolved = I18n.setLocale(localeCode)
        return persistLanguage(I18n.localeCode(resolved))
    }

    fun statusMap(): Map<String, Any?> {
        val currentBody = bodyTheme
        val currentUi = uiTheme
        val currentProfile = profile
        return linkedMapOf(
            "schema" to currentProfile.schema,
            "profilePath" to profilePath.toAbsolutePath().normalize().toString(),
            "language" to currentProfile.language,
            "body" to currentBody.id,
            "ui" to currentUi.id,
            "reloadGeneration" to reloadGenCounter.get(),
            "bodyTheme" to currentBody.toSummaryMap(),
            "uiTheme" to currentUi.toSummaryMap(),
            "windows" to linkedMapOf(
                "fitScale" to currentProfile.fitScale,
                "evfWindowOpen" to currentProfile.evfWindowOpen,
                "evfAlwaysOnTop" to currentProfile.evfAlwaysOnTop,
            ),
            "graphics" to linkedMapOf(
                "opticsBackend" to currentProfile.opticsBackend,
                "vulkanDevice" to currentProfile.vulkanDevice,
            ),
            "availableBody" to availableBodyThemes().map { it.toSummaryMap() },
            "availableUi" to availableUiThemes().map { it.toSummaryMap() },
        )
    }
}

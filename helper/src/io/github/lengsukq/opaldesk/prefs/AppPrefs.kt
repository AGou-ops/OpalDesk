package io.github.lengsukq.opaldesk.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences store + live reactive flows for OpalDesk preferences:
 * appearance theme (Light Frosted Glass / Follow System / Dark), wallpapers, pinned apps,
 * external monitor UI scaling, stepless trackpad pointer speed, and activation preferences.
 */
object AppPrefs {
    const val THEME_LIGHT = 0
    const val THEME_SYSTEM = 1
    const val THEME_DARK = 2

    const val MOUSE_TARGET_LOCAL = 0
    const val MOUSE_TARGET_EXTERNAL = 1

    const val MIN_TRACKPAD_SENSITIVITY = 0.50f
    const val MAX_TRACKPAD_SENSITIVITY = 3.00f
    const val DEFAULT_TRACKPAD_SENSITIVITY = 1.45f
    const val MAX_DOCK_PINNED = 8

    private const val PREFS_NAME = "opaldesk_v2_prefs"
    private const val KEY_AUTO_CLOSE_WIFI_ADB = "auto_close_wifi_adb"
    private const val KEY_AUTO_ROOT = "auto_root"
    private const val KEY_AUTO_SHIZUKU = "auto_shizuku"
    private const val KEY_ROOT_ENHANCE = "root_enhance_optional"
    private const val KEY_AUTO_EXT_DISPLAY = "auto_project_ext_display"
    private const val KEY_PINNED_PKGS = "pinned_dock_pkgs"
    private const val KEY_DESKTOP_SHORTCUTS = "desktop_shortcut_pkgs"
    private const val KEY_DOCK_PINNED = "dock_pinned_pkgs_v2"
    private const val KEY_WALLPAPER_PRESET = "desktop_wallpaper_preset"
    private const val KEY_CUSTOM_WALLPAPER_PATH = "custom_wallpaper_path"
    private const val KEY_EXTERNAL_UI_SCALE = "external_ui_scale"
    private const val KEY_TRACKPAD_SENSITIVITY = "trackpad_sensitivity"
    private const val KEY_THEME_MODE = "appearance_theme_mode"
    private const val KEY_MOUSE_TARGET = "physical_mouse_target"

    private val DEFAULT_PINNED = listOf(
        "com.android.chrome",
        "com.android.vending",
        "com.android.settings",
        "com.google.android.youtube",
        "com.tencent.mm",
        "tv.danmaku.bili",
    )

    @Volatile
    private var flowsHydrated = false

    private val _themeModeFlow = MutableStateFlow(THEME_LIGHT)
    val themeModeFlow: StateFlow<Int> = _themeModeFlow.asStateFlow()

    private val _wallpaperPresetFlow = MutableStateFlow(0)
    val wallpaperPresetFlow: StateFlow<Int> = _wallpaperPresetFlow.asStateFlow()

    private val _trackpadSensitivityFlow = MutableStateFlow(DEFAULT_TRACKPAD_SENSITIVITY)
    val trackpadSensitivityFlow: StateFlow<Float> = _trackpadSensitivityFlow.asStateFlow()

    private val _externalUiScaleFlow = MutableStateFlow(1.25f)
    val externalUiScaleFlow: StateFlow<Float> = _externalUiScaleFlow.asStateFlow()

    private val _mouseTargetFlow = MutableStateFlow(MOUSE_TARGET_EXTERNAL)
    val mouseTargetFlow: StateFlow<Int> = _mouseTargetFlow.asStateFlow()

    private val _desktopShortcutsFlow = MutableStateFlow(DEFAULT_PINNED)
    val desktopShortcutsFlow: StateFlow<List<String>> = _desktopShortcutsFlow.asStateFlow()

    private val _dockPinnedFlow = MutableStateFlow(DEFAULT_PINNED.take(MAX_DOCK_PINNED))
    val dockPinnedFlow: StateFlow<List<String>> = _dockPinnedFlow.asStateFlow()

    private val _customWallpaperPathFlow = MutableStateFlow("")
    val customWallpaperPathFlow: StateFlow<String> = _customWallpaperPathFlow.asStateFlow()

    private fun prefs(ctx: Context?) =
        ctx?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hydrateFlows(ctx: Context?) {
        if (flowsHydrated || ctx == null) return
        flowsHydrated = true
        val p = prefs(ctx) ?: return
        _themeModeFlow.value = p.getInt(KEY_THEME_MODE, THEME_LIGHT).coerceIn(THEME_LIGHT, THEME_DARK)
        _wallpaperPresetFlow.value = p.getInt(KEY_WALLPAPER_PRESET, 0).coerceIn(0, 3)
        _customWallpaperPathFlow.value = p.getString(KEY_CUSTOM_WALLPAPER_PATH, "").orEmpty()
        _trackpadSensitivityFlow.value = p.getFloat(KEY_TRACKPAD_SENSITIVITY, DEFAULT_TRACKPAD_SENSITIVITY)
            .coerceIn(MIN_TRACKPAD_SENSITIVITY, MAX_TRACKPAD_SENSITIVITY)
        _externalUiScaleFlow.value = p.getFloat(KEY_EXTERNAL_UI_SCALE, 1.25f).coerceIn(0.85f, 2.0f)
        _mouseTargetFlow.value = p.getInt(KEY_MOUSE_TARGET, MOUSE_TARGET_EXTERNAL)
            .coerceIn(MOUSE_TARGET_LOCAL, MOUSE_TARGET_EXTERNAL)
        _desktopShortcutsFlow.value = loadPkgList(p, KEY_DESKTOP_SHORTCUTS, fallbackKey = KEY_PINNED_PKGS)
        _dockPinnedFlow.value = loadPkgList(p, KEY_DOCK_PINNED, fallbackKey = KEY_PINNED_PKGS).take(MAX_DOCK_PINNED)
    }

    private fun loadPkgList(p: android.content.SharedPreferences, key: String, fallbackKey: String): List<String> {
        val raw = p.getString(key, null) ?: p.getString(fallbackKey, null)
        if (raw == null) return DEFAULT_PINNED
        if (raw.isBlank()) return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun isAutoCloseWifiAdbEnabled(ctx: Context?): Boolean =
        prefs(ctx)?.getBoolean(KEY_AUTO_CLOSE_WIFI_ADB, false) ?: false

    fun setAutoCloseWifiAdbEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_AUTO_CLOSE_WIFI_ADB, enabled)?.apply()
    }

    fun isAutoRootEnabled(ctx: Context?): Boolean =
        prefs(ctx)?.getBoolean(KEY_AUTO_ROOT, false) ?: false

    fun setAutoRootEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_AUTO_ROOT, enabled)?.apply()
    }

    fun isAutoShizukuEnabled(ctx: Context?): Boolean =
        prefs(ctx)?.getBoolean(KEY_AUTO_SHIZUKU, true) ?: true

    fun setAutoShizukuEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_AUTO_SHIZUKU, enabled)?.apply()
    }

    fun isRootEnhanceEnabled(ctx: Context?): Boolean =
        prefs(ctx)?.getBoolean(KEY_ROOT_ENHANCE, false) ?: false

    fun setRootEnhanceEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_ROOT_ENHANCE, enabled)?.apply()
    }

    fun isAutoExternalDisplayEnabled(ctx: Context?): Boolean =
        prefs(ctx)?.getBoolean(KEY_AUTO_EXT_DISPLAY, true) ?: true

    fun setAutoExternalDisplayEnabled(ctx: Context?, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_AUTO_EXT_DISPLAY, enabled)?.apply()
    }

    fun getDesktopShortcuts(ctx: Context?): List<String> {
        hydrateFlows(ctx)
        return _desktopShortcutsFlow.value
    }

    /** Adds or removes [pkg] on the desktop shortcut grid; returns true if now present on desktop. */
    fun toggleDesktopShortcut(ctx: Context?, pkg: String): Boolean {
        hydrateFlows(ctx)
        val current = _desktopShortcutsFlow.value.toMutableList()
        val added = if (current.contains(pkg)) {
            current.remove(pkg)
            false
        } else {
            current.add(pkg)
            true
        }
        prefs(ctx)?.edit()?.putString(KEY_DESKTOP_SHORTCUTS, current.joinToString(","))?.apply()
        _desktopShortcutsFlow.value = current
        return added
    }

    fun getDockPinnedPackages(ctx: Context?): List<String> {
        hydrateFlows(ctx)
        return _dockPinnedFlow.value
    }

    enum class DockPinResult { ADDED, REMOVED, LIMIT_REACHED }

    /**
     * Toggles [pkg] in the bottom Dock pinned strip, strictly enforcing [MAX_DOCK_PINNED].
     */
    fun toggleDockPin(ctx: Context?, pkg: String): DockPinResult {
        hydrateFlows(ctx)
        val current = _dockPinnedFlow.value.toMutableList()
        if (current.contains(pkg)) {
            current.remove(pkg)
            prefs(ctx)?.edit()?.putString(KEY_DOCK_PINNED, current.joinToString(","))?.apply()
            _dockPinnedFlow.value = current
            return DockPinResult.REMOVED
        }
        if (current.size >= MAX_DOCK_PINNED) {
            return DockPinResult.LIMIT_REACHED
        }
        current.add(pkg)
        prefs(ctx)?.edit()?.putString(KEY_DOCK_PINNED, current.joinToString(","))?.apply()
        _dockPinnedFlow.value = current
        return DockPinResult.ADDED
    }

    fun setCustomWallpaperPath(ctx: Context?, path: String) {
        val clean = path.trim()
        prefs(ctx)?.edit()?.putString(KEY_CUSTOM_WALLPAPER_PATH, clean)?.apply()
        _customWallpaperPathFlow.value = clean
    }

    fun clearCustomWallpaper(ctx: Context?) {
        setCustomWallpaperPath(ctx, "")
    }

    fun getThemeMode(ctx: Context?): Int {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getInt(KEY_THEME_MODE, THEME_LIGHT) ?: THEME_LIGHT).coerceIn(THEME_LIGHT, THEME_DARK)
    }

    fun setThemeMode(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(THEME_LIGHT, THEME_DARK)
        prefs(ctx)?.edit()?.putInt(KEY_THEME_MODE, clamped)?.apply()
        _themeModeFlow.value = clamped
    }

    fun getWallpaperPreset(ctx: Context?): Int {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getInt(KEY_WALLPAPER_PRESET, 0) ?: 0).coerceIn(0, 3)
    }

    fun setWallpaperPreset(ctx: Context?, preset: Int) {
        val clamped = preset.coerceIn(0, 3)
        prefs(ctx)?.edit()?.putInt(KEY_WALLPAPER_PRESET, clamped)?.apply()
        _wallpaperPresetFlow.value = clamped
    }

    fun getExternalUiScale(ctx: Context?): Float {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getFloat(KEY_EXTERNAL_UI_SCALE, 1.25f) ?: 1.25f).coerceIn(0.85f, 2.0f)
    }

    fun setExternalUiScale(ctx: Context?, scale: Float) {
        val clamped = scale.coerceIn(0.85f, 2.0f)
        prefs(ctx)?.edit()?.putFloat(KEY_EXTERNAL_UI_SCALE, clamped)?.apply()
        _externalUiScaleFlow.value = clamped
    }

    fun getTrackpadSensitivity(ctx: Context?): Float {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getFloat(KEY_TRACKPAD_SENSITIVITY, DEFAULT_TRACKPAD_SENSITIVITY)
            ?: DEFAULT_TRACKPAD_SENSITIVITY).coerceIn(MIN_TRACKPAD_SENSITIVITY, MAX_TRACKPAD_SENSITIVITY)
    }

    fun setTrackpadSensitivity(ctx: Context?, sensitivity: Float) {
        val clamped = sensitivity.coerceIn(MIN_TRACKPAD_SENSITIVITY, MAX_TRACKPAD_SENSITIVITY)
        prefs(ctx)?.edit()?.putFloat(KEY_TRACKPAD_SENSITIVITY, clamped)?.apply()
        _trackpadSensitivityFlow.value = clamped
    }

    fun getMouseTarget(ctx: Context?): Int {
        hydrateFlows(ctx)
        return (prefs(ctx)?.getInt(KEY_MOUSE_TARGET, MOUSE_TARGET_EXTERNAL) ?: MOUSE_TARGET_EXTERNAL)
            .coerceIn(MOUSE_TARGET_LOCAL, MOUSE_TARGET_EXTERNAL)
    }

    fun setMouseTarget(ctx: Context?, mode: Int) {
        val clamped = mode.coerceIn(MOUSE_TARGET_LOCAL, MOUSE_TARGET_EXTERNAL)
        prefs(ctx)?.edit()?.putInt(KEY_MOUSE_TARGET, clamped)?.apply()
        _mouseTargetFlow.value = clamped
    }

    fun getOrCreatePeerClientId(ctx: Context?): String {
        val p = prefs(ctx) ?: return "opal-android"
        val existing = p.getString("peer_client_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = "android-${java.util.UUID.randomUUID().toString().take(12)}"
        p.edit().putString("peer_client_id", created).apply()
        return created
    }

    fun getPeerAuthToken(ctx: Context?, host: String): String =
        prefs(ctx)?.getString("peer_tok_${host.trim()}", "").orEmpty()

    fun savePeerAuthToken(ctx: Context?, host: String, token: String) {
        if (host.isBlank() || token.isBlank()) return
        prefs(ctx)?.edit()?.putString("peer_tok_${host.trim()}", token)?.apply()
    }

    fun clearPeerAuthToken(ctx: Context?, host: String) {
        if (host.isBlank()) return
        prefs(ctx)?.edit()?.remove("peer_tok_${host.trim()}")?.apply()
    }
}

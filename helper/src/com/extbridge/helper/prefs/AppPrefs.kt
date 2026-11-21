package com.extbridge.helper.prefs

import android.appwidget.AppWidgetHost
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.extbridge.helper.protocol.BridgeProtocol
import com.extbridge.helper.protocol.DisplayGeometry

/**
 * Unified typed SharedPreferences accessor preserving ALL 5 legacy preference file names
 * and key names (`ext_prefs`, `extbridge_prefs`, `extbridge_onboarding`,
 * `extbridge_remote_stream_prefs`, `extbridge_ui_prefs`) for zero-loss upgrades.
 */
object AppPrefs {
    private const val TAG = "ExtAppPrefs"

    // Exact legacy SharedPreferences file names — DO NOT RENAME
    const val FILE_EXT = "ext_prefs"
    const val FILE_BOOTSTRAP = "extbridge_prefs"
    const val FILE_ONBOARDING = "extbridge_onboarding"
    const val FILE_REMOTE_STREAM = "extbridge_remote_stream_prefs"
    const val FILE_UI = "extbridge_ui_prefs"

    // ext_prefs keys
    private const val KEY_DOCK_AUTO_HIDE = "dock_auto_hide"
    private const val KEY_MIRROR_WIDGET_LEFT = "mirror_widget_left_id"
    private const val KEY_MIRROR_WIDGET_RIGHT = "mirror_widget_right_id"
    private const val KEY_MIRROR_PRESETS = "mirror_preset_slots"
    private const val KEY_MIGRATED_SCREENDIM_V340 = "migrated_screendim_v340"
    private const val KEY_MIRROR_MEMO = "mirror_memo_text"
    private const val KEY_MIRROR_THEME = "mirror_theme_id"
    private const val KEY_MIRROR_CUSTOM_BG = "mirror_custom_bg"
    private const val KEY_MIRROR_CURSOR_SPEED = "mirror_cursor_speed_idx"
    private const val KEY_MIRROR_WIDGET_RATIO_L = "mirror_widget_ratio_l"
    private const val KEY_MIRROR_WIDGET_RATIO_R = "mirror_widget_ratio_r"
    private const val KEY_DESKTOP_PINNED_INIT = "desktop_pinned_initialized_v1"
    private const val KEY_DESKTOP_PINNED_APPS = "desktop_pinned_apps"
    private const val KEY_DESKTOP_LAYOUT_V2 = "desktop_layout_v2_json"
    private const val KEY_DOCK_PINNED_APPS = "dock_pinned_apps"
    private const val KEY_DESKTOP_WIDGET_IDS = "desktop_custom_widget_ids"
    private const val KEY_DESKTOP_PRESETS = "desktop_preset_slots"
    private const val KEY_DESKTOP_SYNC_MIRROR = "desktop_sync_mirror_widgets"
    private const val KEY_RIGHT_CLICK_PASSTHROUGH = "dex_right_click_passthrough"
    private const val KEY_DESKTOP_DOUBLE_CLICK = "dex_desktop_double_click"
    private const val KEY_DEX_DEFAULT_LAUNCH_MODE = "dex_default_launch_mode"

    // extbridge_prefs keys
    private const val KEY_AUTO_ROOT = "auto_root_enabled"
    private const val KEY_AUTO_CLOSE_WIFI_ADB = "auto_close_wifi_adb"

    // extbridge_onboarding keys
    private const val KEY_STARTUP_PERMS_DONE = "startup_perms_done"
    private const val KEY_NOTIF_ASKED = "notif_asked"
    private const val KEY_DEV_GUIDE_DONE = "dev_guide_done"

    // extbridge_remote_stream_prefs keys
    private const val KEY_REMOTE_CODEC = "remote_codec_pref"
    private const val KEY_REMOTE_BITRATE_MBPS = "remote_max_bitrate_mbps"
    private const val KEY_REMOTE_AUDIO = "remote_audio_enabled"
    private const val KEY_ADAPTIVE_SINGLE_ENABLED = "adaptive_single_enabled"
    private const val KEY_DEFAULT_SINGLE_WIDTH_DP = "default_single_width_dp"
    private const val KEY_INPUT_BACKEND_MODE = "input_backend_mode"

    // extbridge_ui_prefs keys
    private const val KEY_UI_THEME_MODE = "theme_mode"

    private const val DEFAULT_MIRROR_MEMO =
        "• 点击控制台可随时编辑便签文字\n• 支持虚拟触控板点按滑控小组件"
    private const val DEFAULT_MIRROR_CUSTOM_BG = 0xFF1E293B.toInt()
    private const val DEFAULT_MIRROR_SPEED_IDX = 2

    /** Optional hook set by BridgeService so mirror sidebar prefs trigger live refresh without circular deps. */
    @Volatile
    var onMirrorPrefsChanged: ((Context) -> Unit)? = null

    /** Optional hook delegating widget ID deletion to an active AppWidgetHost when available. */
    @Volatile
    var onDeleteAppWidgetId: ((Context, Int) -> Unit)? = null

    private fun prefs(ctx: Context, name: String): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Optional hook set by LauncherActivity so desktop icon/widget edits trigger live refresh. */
    @Volatile
    var onDesktopPrefsChanged: ((Context) -> Unit)? = null

    fun isDesktopPinnedInitialized(ctx: Context?): Boolean =
        ctx?.let { prefs(it, FILE_EXT).getBoolean(KEY_DESKTOP_PINNED_INIT, false) } ?: false

    fun getDesktopPinnedApps(ctx: Context?): List<String> {
        if (ctx == null) return emptyList()
        val raw = prefs(ctx, FILE_EXT).getString(KEY_DESKTOP_PINNED_APPS, "").orEmpty()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun setDesktopPinnedApps(ctx: Context?, pkgs: List<String>, markInitialized: Boolean = true) {
        if (ctx == null) return
        val joined = pkgs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        prefs(ctx, FILE_EXT).edit().apply {
            putString(KEY_DESKTOP_PINNED_APPS, joined)
            if (markInitialized) putBoolean(KEY_DESKTOP_PINNED_INIT, true)
        }.apply()
        onDesktopPrefsChanged?.invoke(ctx)
    }

    fun isDesktopAppPinned(ctx: Context?, pkg: String): Boolean =
        getDesktopPinnedApps(ctx).contains(pkg.trim())

    fun pinDesktopApp(ctx: Context?, pkg: String) {
        val clean = pkg.trim()
        if (ctx == null || clean.isEmpty()) return
        val next = getDesktopPinnedApps(ctx).toMutableList()
        if (!next.contains(clean)) next.add(clean)
        setDesktopPinnedApps(ctx, next)
    }

    fun unpinDesktopApp(ctx: Context?, pkg: String) {
        val clean = pkg.trim()
        if (ctx == null || clean.isEmpty()) return
        val next = getDesktopPinnedApps(ctx).filterNot { it == clean }
        setDesktopPinnedApps(ctx, next)
    }

    fun getDesktopLayoutJson(ctx: Context?): String =
        ctx?.let { prefs(it, FILE_EXT).getString(KEY_DESKTOP_LAYOUT_V2, "").orEmpty() } ?: ""

    fun setDesktopLayoutJson(ctx: Context?, json: String, notify: Boolean = false) {
        if (ctx == null) return
        prefs(ctx, FILE_EXT).edit().putString(KEY_DESKTOP_LAYOUT_V2, json).apply()
        if (notify) onDesktopPrefsChanged?.invoke(ctx)
    }

    @Volatile
    var onDockPinnedAppsChanged: ((Context) -> Unit)? = null

    fun getDockPinnedApps(ctx: Context?): List<String> {
        if (ctx == null) return emptyList()
        val raw = prefs(ctx, FILE_EXT).getString(KEY_DOCK_PINNED_APPS, "").orEmpty()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun isDockAppPinned(ctx: Context?, pkg: String): Boolean =
        getDockPinnedApps(ctx).contains(pkg.trim())

    fun setDockPinnedApps(ctx: Context?, pkgs: List<String>) {
        if (ctx == null) return
        val joined = pkgs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6).joinToString(",")
        prefs(ctx, FILE_EXT).edit().putString(KEY_DOCK_PINNED_APPS, joined).apply()
        onDockPinnedAppsChanged?.invoke(ctx)
    }

    fun toggleDockPinnedApp(ctx: Context?, pkg: String): Boolean {
        val clean = pkg.trim()
        if (ctx == null || clean.isEmpty()) return false
        val cur = getDockPinnedApps(ctx).toMutableList()
        val nowPinned = if (cur.contains(clean)) {
            cur.remove(clean)
            false
        } else {
            if (cur.size >= 6) cur.removeAt(0)
            cur.add(clean)
            true
        }
        setDockPinnedApps(ctx, cur)
        return nowPinned
    }

    fun isDesktopSyncMirrorWidgets(ctx: Context?): Boolean =
        ctx?.let { prefs(it, FILE_EXT).getBoolean(KEY_DESKTOP_SYNC_MIRROR, true) } ?: true

    fun setDesktopSyncMirrorWidgets(ctx: Context?, sync: Boolean) {
        if (ctx == null) return
        prefs(ctx, FILE_EXT).edit().putBoolean(KEY_DESKTOP_SYNC_MIRROR, sync).apply()
        onDesktopPrefsChanged?.invoke(ctx)
    }

    fun getDesktopCustomWidgetIds(ctx: Context?): List<Int> {
        if (ctx == null) return emptyList()
        val raw = prefs(ctx, FILE_EXT).getString(KEY_DESKTOP_WIDGET_IDS, "").orEmpty()
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.distinct()
    }

    fun addDesktopCustomWidgetId(ctx: Context?, widgetId: Int) {
        if (ctx == null || widgetId <= 0) return
        val next = getDesktopCustomWidgetIds(ctx).toMutableList()
        if (!next.contains(widgetId)) next.add(widgetId)
        prefs(ctx, FILE_EXT).edit().putString(KEY_DESKTOP_WIDGET_IDS, next.joinToString(",")).apply()
        onDesktopPrefsChanged?.invoke(ctx)
    }

    fun removeDesktopCustomWidgetId(ctx: Context?, widgetId: Int) {
        if (ctx == null || widgetId <= 0) return
        val next = getDesktopCustomWidgetIds(ctx).filterNot { it == widgetId }
        prefs(ctx, FILE_EXT).edit().putString(KEY_DESKTOP_WIDGET_IDS, next.joinToString(",")).apply()
        try {
            AppWidgetHost(ctx.applicationContext, MirrorThemeCatalog.DESKTOP_WIDGET_HOST_ID).deleteAppWidgetId(widgetId)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to delete desktop AppWidgetId=$widgetId", t)
        }
        onDesktopPrefsChanged?.invoke(ctx)
    }

    fun getDesktopPresetCards(ctx: Context?): List<String> {
        if (ctx == null) return MirrorThemeCatalog.DEFAULT_DESKTOP_PRESETS.split(",")
        val sp = prefs(ctx, FILE_EXT)
        val raw = sp.getString(KEY_DESKTOP_PRESETS, null)
        val src = if (raw.isNullOrBlank()) {
            if (isDesktopSyncMirrorWidgets(ctx)) {
                getMirrorPresetSlots(ctx).filterNot { it == "quickctrl" || it == "screendim" }
            } else {
                MirrorThemeCatalog.DEFAULT_DESKTOP_PRESETS.split(",")
            }
        } else {
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() && MirrorThemeCatalog.mirrorPresetIndex(it) >= 0 }
        }
        return src.ifEmpty { MirrorThemeCatalog.DEFAULT_DESKTOP_PRESETS.split(",") }
    }

    fun setDesktopPresetCards(ctx: Context?, keys: List<String>?) {
        if (ctx == null) return
        val joined = keys?.map { it.trim() }
            ?.filter { it.isNotEmpty() && MirrorThemeCatalog.mirrorPresetIndex(it) >= 0 }
            ?.distinct()
            ?.joinToString(",")
            .orEmpty()
        prefs(ctx, FILE_EXT).edit().putString(KEY_DESKTOP_PRESETS, joined).apply()
        onDesktopPrefsChanged?.invoke(ctx)
    }

    // --- ext_prefs: Dock & Mirror Sidebar ---

    fun isDockAutoHide(ctx: Context?): Boolean =
        ctx?.let { prefs(it, FILE_EXT).getBoolean(KEY_DOCK_AUTO_HIDE, true) } ?: true

    fun setDockAutoHide(ctx: Context?, enabled: Boolean) {
        ctx ?: return
        prefs(ctx, FILE_EXT).edit().putBoolean(KEY_DOCK_AUTO_HIDE, enabled).apply()
    }

    fun getMirrorPresetSlotsRaw(ctx: Context?): String {
        if (ctx == null) return MirrorThemeCatalog.DEFAULT_MIRROR_PRESETS
        val sp = prefs(ctx, FILE_EXT)
        var v = sp.getString(KEY_MIRROR_PRESETS, MirrorThemeCatalog.DEFAULT_MIRROR_PRESETS)
        if (!sp.getBoolean(KEY_MIGRATED_SCREENDIM_V340, false)) {
            val cur = sp.getString(KEY_MIRROR_PRESETS, null)
            if (cur != null && cur.trim().isNotEmpty() && !cur.split(",").contains("screendim")) {
                v = "${cur.trim()},screendim"
                sp.edit()
                    .putString(KEY_MIRROR_PRESETS, v)
                    .putBoolean(KEY_MIGRATED_SCREENDIM_V340, true)
                    .apply()
            } else {
                sp.edit().putBoolean(KEY_MIGRATED_SCREENDIM_V340, true).apply()
            }
        }
        return if (v.isNullOrBlank()) MirrorThemeCatalog.DEFAULT_MIRROR_PRESETS else v
    }

    fun getMirrorPresetSlots(ctx: Context?): List<String> {
        val raw = getMirrorPresetSlotsRaw(ctx)
        val filtered = raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && MirrorThemeCatalog.mirrorPresetIndex(it) >= 0 }
        return filtered.ifEmpty { MirrorThemeCatalog.DEFAULT_MIRROR_PRESETS.split(",") }
    }

    fun setMirrorPresetSlots(ctx: Context?, slots: List<String>?) {
        if (ctx == null) return
        val joined = slots
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && MirrorThemeCatalog.mirrorPresetIndex(it) >= 0 }
            ?.joinToString(",")
            ?.takeIf { it.isNotEmpty() }
            ?: MirrorThemeCatalog.DEFAULT_MIRROR_PRESETS
        prefs(ctx, FILE_EXT).edit().putString(KEY_MIRROR_PRESETS, joined).apply()
        onMirrorPrefsChanged?.invoke(ctx)
    }

    fun getMirrorMemoText(ctx: Context?): String {
        if (ctx == null) return DEFAULT_MIRROR_MEMO
        return prefs(ctx, FILE_EXT).getString(KEY_MIRROR_MEMO, DEFAULT_MIRROR_MEMO) ?: ""
    }

    fun setMirrorMemoText(ctx: Context?, txt: String?) {
        if (ctx == null) return
        prefs(ctx, FILE_EXT).edit().putString(KEY_MIRROR_MEMO, txt ?: "").apply()
        onMirrorPrefsChanged?.invoke(ctx)
    }

    fun getMirrorWidgetId(ctx: Context?, slot: Int): Int {
        if (ctx == null) return -1
        val key = if (slot == 0) KEY_MIRROR_WIDGET_LEFT else KEY_MIRROR_WIDGET_RIGHT
        return prefs(ctx, FILE_EXT).getInt(key, -1)
    }

    fun setMirrorWidgetId(ctx: Context?, slot: Int, widgetId: Int) {
        if (ctx == null) return
        val sp = prefs(ctx, FILE_EXT)
        val key = if (slot == 0) KEY_MIRROR_WIDGET_LEFT else KEY_MIRROR_WIDGET_RIGHT
        val prevId = sp.getInt(key, -1)
        if (prevId > 0 && prevId != widgetId) {
            try {
                val hook = onDeleteAppWidgetId
                if (hook != null) {
                    hook.invoke(ctx, prevId)
                } else {
                    AppWidgetHost(ctx.applicationContext, MirrorThemeCatalog.MIRROR_WIDGET_HOST_ID)
                        .deleteAppWidgetId(prevId)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to delete previous AppWidgetId=$prevId for slot=$slot", t)
            }
        }
        sp.edit().putInt(key, widgetId).apply()
        onMirrorPrefsChanged?.invoke(ctx)
    }

    fun getMirrorThemeId(ctx: Context?): String {
        if (ctx == null) return "night"
        val id = prefs(ctx, FILE_EXT).getString(KEY_MIRROR_THEME, "night")
        return if (!id.isNullOrEmpty()) id else "night"
    }

    fun setMirrorThemeId(ctx: Context?, id: String?) {
        if (ctx == null) return
        prefs(ctx, FILE_EXT).edit().putString(KEY_MIRROR_THEME, id ?: "night").apply()
        onMirrorPrefsChanged?.invoke(ctx)
    }

    fun getMirrorCustomBg(ctx: Context?): Int {
        if (ctx == null) return DEFAULT_MIRROR_CUSTOM_BG
        return prefs(ctx, FILE_EXT).getInt(KEY_MIRROR_CUSTOM_BG, DEFAULT_MIRROR_CUSTOM_BG)
    }

    fun setMirrorCustomBg(ctx: Context?, bgColor: Int) {
        if (ctx == null) return
        prefs(ctx, FILE_EXT).edit()
            .putString(KEY_MIRROR_THEME, "custom")
            .putInt(KEY_MIRROR_CUSTOM_BG, 0xFF000000.toInt() or (bgColor and 0x00FFFFFF))
            .apply()
        onMirrorPrefsChanged?.invoke(ctx)
    }

    fun getActiveMirrorTheme(ctx: Context?): MirrorTheme =
        MirrorThemeCatalog.resolveMirrorTheme(getMirrorThemeId(ctx), getMirrorCustomBg(ctx))

    fun getMirrorCursorSpeedIdx(ctx: Context?): Int {
        if (ctx == null) return DEFAULT_MIRROR_SPEED_IDX
        val idx = prefs(ctx, FILE_EXT).getInt(KEY_MIRROR_CURSOR_SPEED, DEFAULT_MIRROR_SPEED_IDX)
        return idx.coerceIn(0, MirrorThemeCatalog.MIRROR_SPEED_MULTS.lastIndex)
    }

    fun getMirrorCursorSpeedMult(ctx: Context?): Float =
        MirrorThemeCatalog.MIRROR_SPEED_MULTS[getMirrorCursorSpeedIdx(ctx)]

    fun setMirrorCursorSpeedIdx(ctx: Context?, idx: Int) {
        if (ctx == null) return
        val clamped = idx.coerceIn(0, MirrorThemeCatalog.MIRROR_SPEED_MULTS.lastIndex)
        prefs(ctx, FILE_EXT).edit().putInt(KEY_MIRROR_CURSOR_SPEED, clamped).apply()
    }

    fun getMirrorWidgetRatio(ctx: Context?, slot: Int): Int {
        if (ctx == null) return 0
        val key = if (slot == 0) KEY_MIRROR_WIDGET_RATIO_L else KEY_MIRROR_WIDGET_RATIO_R
        val mode = prefs(ctx, FILE_EXT).getInt(key, 0)
        return mode.coerceIn(0, MirrorThemeCatalog.MIRROR_WIDGET_RATIO_LABELS.lastIndex)
    }

    fun setMirrorWidgetRatio(ctx: Context?, slot: Int, mode: Int) {
        if (ctx == null) return
        val key = if (slot == 0) KEY_MIRROR_WIDGET_RATIO_L else KEY_MIRROR_WIDGET_RATIO_R
        val clamped = mode.coerceIn(0, MirrorThemeCatalog.MIRROR_WIDGET_RATIO_LABELS.lastIndex)
        prefs(ctx, FILE_EXT).edit().putInt(key, clamped).apply()
        onMirrorPrefsChanged?.invoke(ctx)
    }

    // --- extbridge_prefs: Bootstrap ---

    fun isAutoCloseWifiAdbEnabled(ctx: Context?): Boolean =
        ctx?.let { prefs(it, FILE_BOOTSTRAP).getBoolean(KEY_AUTO_CLOSE_WIFI_ADB, false) } ?: false

    fun setAutoCloseWifiAdbEnabled(ctx: Context?, enabled: Boolean) {
        ctx ?: return
        prefs(ctx, FILE_BOOTSTRAP).edit().putBoolean(KEY_AUTO_CLOSE_WIFI_ADB, enabled).apply()
    }

    fun isAutoRootEnabled(ctx: Context?): Boolean =
        ctx?.let { prefs(it, FILE_BOOTSTRAP).getBoolean(KEY_AUTO_ROOT, false) } ?: false

    fun setAutoRootEnabled(ctx: Context?, enabled: Boolean) {
        ctx ?: return
        prefs(ctx, FILE_BOOTSTRAP).edit().putBoolean(KEY_AUTO_ROOT, enabled).apply()
    }

    // --- extbridge_onboarding: Permissions & Developer Guide ---

    fun isStartupPermsDone(ctx: Context): Boolean =
        prefs(ctx, FILE_ONBOARDING).getBoolean(KEY_STARTUP_PERMS_DONE, false)

    fun setStartupPermsDone(ctx: Context, done: Boolean) {
        prefs(ctx, FILE_ONBOARDING).edit().putBoolean(KEY_STARTUP_PERMS_DONE, done).apply()
    }

    fun isNotifAsked(ctx: Context): Boolean =
        prefs(ctx, FILE_ONBOARDING).getBoolean(KEY_NOTIF_ASKED, false)

    fun setNotifAsked(ctx: Context, asked: Boolean) {
        prefs(ctx, FILE_ONBOARDING).edit().putBoolean(KEY_NOTIF_ASKED, asked).apply()
    }

    fun isDevGuideDone(ctx: Context): Boolean =
        prefs(ctx, FILE_ONBOARDING).getBoolean(KEY_DEV_GUIDE_DONE, false)

    fun setDevGuideDone(ctx: Context, done: Boolean) {
        prefs(ctx, FILE_ONBOARDING).edit().putBoolean(KEY_DEV_GUIDE_DONE, done).apply()
    }

    // --- extbridge_remote_stream_prefs: Remote Streaming & v4.1 Desktop Preferences ---

    fun getRemoteCodecPref(ctx: Context): Int =
        prefs(ctx, FILE_REMOTE_STREAM)
            .getInt(KEY_REMOTE_CODEC, BridgeProtocol.RemoteStream.CODEC_AUTO_HEVC)
            .coerceIn(BridgeProtocol.RemoteStream.CODEC_AUTO_HEVC, BridgeProtocol.RemoteStream.CODEC_H264)

    fun getRemoteMaxBitrateMbps(ctx: Context): Int =
        prefs(ctx, FILE_REMOTE_STREAM).getInt(KEY_REMOTE_BITRATE_MBPS, 16).coerceIn(4, 32)

    fun isRemoteAudioEnabled(ctx: Context): Boolean =
        prefs(ctx, FILE_REMOTE_STREAM).getBoolean(KEY_REMOTE_AUDIO, true)

    fun saveRemoteStreamPrefs(ctx: Context, codecPref: Int, maxBitrateMbps: Int, audioEnabled: Boolean) {
        prefs(ctx, FILE_REMOTE_STREAM).edit()
            .putInt(KEY_REMOTE_CODEC, codecPref.coerceIn(0, 1))
            .putInt(KEY_REMOTE_BITRATE_MBPS, maxBitrateMbps.coerceIn(4, 32))
            .putBoolean(KEY_REMOTE_AUDIO, audioEnabled)
            .apply()
    }

    fun isAdaptiveSingleEnabled(ctx: Context): Boolean =
        prefs(ctx, FILE_REMOTE_STREAM).getBoolean(KEY_ADAPTIVE_SINGLE_ENABLED, true)

    fun getDefaultSingleWidthDp(ctx: Context): Int =
        DisplayGeometry.clampUiSingleWidthDp(
            prefs(ctx, FILE_REMOTE_STREAM)
                .getInt(KEY_DEFAULT_SINGLE_WIDTH_DP, DisplayGeometry.DEFAULT_SINGLE_WIDTH_DP),
        )

    fun saveAdaptiveSingleConfig(ctx: Context, enabled: Boolean, defaultWidthDp: Int) {
        prefs(ctx, FILE_REMOTE_STREAM).edit()
            .putBoolean(KEY_ADAPTIVE_SINGLE_ENABLED, enabled)
            .putInt(KEY_DEFAULT_SINGLE_WIDTH_DP, DisplayGeometry.clampUiSingleWidthDp(defaultWidthDp))
            .apply()
    }

    fun getInputBackendMode(ctx: Context): Int =
        prefs(ctx, FILE_REMOTE_STREAM)
            .getInt(KEY_INPUT_BACKEND_MODE, BridgeProtocol.InputBackend.AUTO)
            .coerceIn(BridgeProtocol.InputBackend.AUTO, BridgeProtocol.InputBackend.UHID)

    fun setInputBackendMode(ctx: Context, mode: Int) {
        prefs(ctx, FILE_REMOTE_STREAM).edit()
            .putInt(
                KEY_INPUT_BACKEND_MODE,
                mode.coerceIn(BridgeProtocol.InputBackend.AUTO, BridgeProtocol.InputBackend.UHID),
            )
            .apply()
    }

    /** Optional hook set by BridgeService so theme mode changes live-update DeX glass overlays. */
    @Volatile
    var onThemeModeChanged: ((Context) -> Unit)? = null

    fun isRightClickPassthroughInApps(ctx: Context?): Boolean =
        ctx?.let { prefs(it, FILE_EXT).getBoolean(KEY_RIGHT_CLICK_PASSTHROUGH, true) } ?: true

    fun setRightClickPassthroughInApps(ctx: Context?, passthrough: Boolean) {
        ctx ?: return
        prefs(ctx, FILE_EXT).edit().putBoolean(KEY_RIGHT_CLICK_PASSTHROUGH, passthrough).apply()
    }

    fun isDesktopDoubleClickLaunch(ctx: Context?): Boolean =
        ctx?.let { prefs(it, FILE_EXT).getBoolean(KEY_DESKTOP_DOUBLE_CLICK, false) } ?: false

    fun setDesktopDoubleClickLaunch(ctx: Context?, enabled: Boolean) {
        ctx ?: return
        prefs(ctx, FILE_EXT).edit().putBoolean(KEY_DESKTOP_DOUBLE_CLICK, enabled).apply()
    }

    /** 0 = Floating (Default), 1 = Maximized WorkArea, 2 = Immersive Fullscreen. */
    fun getDexDefaultLaunchMode(ctx: Context?): Int =
        ctx?.let { prefs(it, FILE_EXT).getInt(KEY_DEX_DEFAULT_LAUNCH_MODE, 0).coerceIn(0, 2) } ?: 0

    fun setDexDefaultLaunchMode(ctx: Context?, mode: Int) {
        ctx ?: return
        prefs(ctx, FILE_EXT).edit().putInt(KEY_DEX_DEFAULT_LAUNCH_MODE, mode.coerceIn(0, 2)).apply()
    }

    // --- extbridge_ui_prefs: Compose UI Theme Mode ---

    fun getUiThemeModeIndex(ctx: Context): Int =
        prefs(ctx, FILE_UI).getInt(KEY_UI_THEME_MODE, 0).coerceIn(0, 2)

    fun setUiThemeModeIndex(ctx: Context, idx: Int) {
        prefs(ctx, FILE_UI).edit().putInt(KEY_UI_THEME_MODE, idx.coerceIn(0, 2)).apply()
        onThemeModeChanged?.invoke(ctx)
    }
}

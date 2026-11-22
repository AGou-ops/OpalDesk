package io.github.lengsukq.opaldesk.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.*
import io.github.lengsukq.opaldesk.AdbBootstrap
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.NavAssistService
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.service.DisplayLaunchHelper

enum class PermissionFlowMode { NONE, STARTUP, DEV_GATE }

/**
 * Permission, first-launch onboarding, and Developer Options gate state machine for MainActivity.
 */
class MainPermissionHelper(
    private val activity: Activity,
    private val onPermissionsChanged: () -> Unit,
    private val onStartupOnboardingFinished: () -> Unit,
) {
    companion object {
        private const val TAG = "ExtMainPerms"
        const val REQ_NOTIF_STARTUP = 3901
        const val REQ_NOTIF_DEV_GATE = 3902
        private const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }

    var notifGranted by mutableStateOf(true); private set
    var overlayGranted by mutableStateOf(true); private set
    var a11yGranted by mutableStateOf(true); private set
    var showPermDialog by mutableStateOf(false); private set
    var permDialogIsDevGate by mutableStateOf(false); private set
    var showDevGuideDialog by mutableStateOf(false)
    var startupPermInProgress: Boolean = false; private set

    private var pendingMode = PermissionFlowMode.NONE
    private var waitingSettingsReturn = false

    val allCoreGranted: Boolean get() = notifGranted && overlayGranted && a11yGranted
    val grantedCount: Int get() = listOf(notifGranted, overlayGranted, a11yGranted).count { it }

    fun refreshSnapshot() {
        notifGranted = checkNotifPerm()
        overlayGranted = Settings.canDrawOverlays(activity)
        a11yGranted = checkA11yPerm()
    }

    fun shouldRunFirstLaunchOnboarding(): Boolean = !AppPrefs.isStartupPermsDone(activity) && !hasAllCorePerms()
    fun beginStartupOnboarding() { startupPermInProgress = true }

    fun startFirstLaunchPermissions() {
        if (activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(PERM_POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !AppPrefs.isNotifAsked(activity)
        ) {
            requestNotifOrOpenSettings(REQ_NOTIF_STARTUP); return
        }
        finishOrPromptStartupPerms()
    }

    fun onResumeReturnIfNeeded(): Boolean {
        refreshSnapshot()
        if (!waitingSettingsReturn) return false
        waitingSettingsReturn = false
        BridgeService.refreshFromReceiver(activity)
        onPermissionsChanged()
        when (pendingMode) {
            PermissionFlowMode.STARTUP -> finishOrPromptStartupPerms()
            PermissionFlowMode.DEV_GATE -> if (hasAllCorePerms()) completeDevGateAndLaunch() else openPermDialog(true)
            PermissionFlowMode.NONE -> Unit
        }
        return true
    }

    fun onRequestPermissionsResult(requestCode: Int) {
        refreshSnapshot(); BridgeService.refreshFromReceiver(activity); onPermissionsChanged()
        when (requestCode) {
            REQ_NOTIF_STARTUP -> finishOrPromptStartupPerms()
            REQ_NOTIF_DEV_GATE -> if (hasAllCorePerms()) completeDevGateAndLaunch() else openPermDialog(true)
        }
    }

    fun openPermDialog(isDevGate: Boolean) {
        refreshSnapshot()
        pendingMode = if (isDevGate) PermissionFlowMode.DEV_GATE else PermissionFlowMode.STARTUP
        permDialogIsDevGate = isDevGate; showPermDialog = true
    }

    fun onDialogGrantNotif() = requestNotifOrOpenSettings(if (permDialogIsDevGate) REQ_NOTIF_DEV_GATE else REQ_NOTIF_STARTUP)
    fun onDialogGrantOverlay() = openSettingsWithReturn(
        currentDialogMode(), "请开启「显示在其他应用上层 / 悬浮窗」权限后返回",
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")),
    )

    fun onDialogGrantA11y() = openSettingsWithReturn(
        currentDialogMode(), "请在「无障碍 → 已下载的应用」开启「OpalDesk 平板辅助手势」后返回",
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )

    fun openRestrictedSettings(trackReturn: Boolean) = openSettingsWithReturn(
        if (trackReturn) currentDialogMode() else PermissionFlowMode.NONE,
        "请点击应用详情右上角菜单「允许受限设置」后再开启无障碍",
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
    )

    fun onDialogSkipOrContinue() {
        when {
            allCoreGranted -> if (permDialogIsDevGate) completeDevGateAndLaunch() else completeStartupPerms(true)
            permDialogIsDevGate -> {
                pendingMode = PermissionFlowMode.NONE; showPermDialog = false
                showDevGuideOrLaunchNow(forceDirect = true)
            }
            else -> completeStartupPerms(false)
        }
    }

    fun ensureNotifPermAndShowPairNotif(): Boolean {
        if (!checkNotifPerm()) { requestNotifOrOpenSettings(REQ_NOTIF_DEV_GATE); return false }
        AdbBootstrap.showPairingNotification(activity, null); return true
    }

    fun openDeveloperOptionsWithGate() {
        if (!hasAllCorePerms()) openPermDialog(isDevGate = true) else showDevGuideOrLaunchNow(forceDirect = false)
    }

    fun confirmDevGuideAndLaunch() { showDevGuideDialog = false; launchDeveloperOptionsNow() }

    private fun currentDialogMode() = if (permDialogIsDevGate) PermissionFlowMode.DEV_GATE else PermissionFlowMode.STARTUP

    private fun checkNotifPerm(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(PERM_POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return nm == null || nm.areNotificationsEnabled()
    }

    private fun checkA11yPerm(): Boolean {
        if (NavAssistService.isRunning()) return true
        return try {
            if (Settings.Secure.getInt(activity.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
            val svcs = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val pkg = activity.packageName
            svcs.contains("$pkg/.NavAssistService") || svcs.contains("$pkg/${NavAssistService::class.java.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read enabled accessibility services", t); false
        }
    }

    private fun hasAllCorePerms() = checkNotifPerm() && Settings.canDrawOverlays(activity) && checkA11yPerm()

    private fun finishOrPromptStartupPerms() {
        if (!activity.isFinishing) if (hasAllCorePerms()) completeStartupPerms(true) else openPermDialog(false)
    }

    private fun completeStartupPerms(allGranted: Boolean) {
        startupPermInProgress = false; pendingMode = PermissionFlowMode.NONE
        AppPrefs.setStartupPermsDone(activity, true); showPermDialog = false
        if (allGranted) {
            BridgeService.refreshFromReceiver(activity)
            Toast.makeText(activity, "所需运行权限已全部就绪", Toast.LENGTH_SHORT).show()
        }
        onPermissionsChanged(); onStartupOnboardingFinished()
    }

    private fun requestNotifOrOpenSettings(reqCode: Int) {
        pendingMode = if (reqCode == REQ_NOTIF_DEV_GATE) PermissionFlowMode.DEV_GATE else PermissionFlowMode.STARTUP
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(PERM_POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val canPopup = !AppPrefs.isNotifAsked(activity) || activity.shouldShowRequestPermissionRationale(PERM_POST_NOTIFICATIONS)
            AppPrefs.setNotifAsked(activity, true)
            if (canPopup) { activity.requestPermissions(arrayOf(PERM_POST_NOTIFICATIONS), reqCode); return }
        }
        waitingSettingsReturn = true
        Toast.makeText(activity, "请在系统设置中开启通知权限后返回", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        if (!DisplayLaunchHelper.launchOnDisplay0(activity, intent)) openAppDetailsFallback()
    }

    private fun openSettingsWithReturn(mode: PermissionFlowMode, toastMsg: String, primaryIntent: Intent) {
        if (mode != PermissionFlowMode.NONE) { pendingMode = mode; waitingSettingsReturn = true }
        Toast.makeText(activity, toastMsg, Toast.LENGTH_LONG).show()
        if (!DisplayLaunchHelper.launchOnDisplay0(activity, primaryIntent)) openAppDetailsFallback()
    }

    private fun openAppDetailsFallback() {
        DisplayLaunchHelper.launchOnDisplay0(
            activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
        )
    }

    private fun completeDevGateAndLaunch() {
        pendingMode = PermissionFlowMode.NONE; showPermDialog = false
        Toast.makeText(activity, "权限已齐备，正在发送无线配对通知", Toast.LENGTH_SHORT).show()
        showDevGuideOrLaunchNow(forceDirect = false)
    }

    private fun showDevGuideOrLaunchNow(forceDirect: Boolean) {
        if (!forceDirect && !AppPrefs.isDevGuideDone(activity)) {
            AppPrefs.setDevGuideDone(activity, true); showDevGuideDialog = true; return
        }
        launchDeveloperOptionsNow()
    }

    private fun launchDeveloperOptionsNow() {
        AdbBootstrap.showPairingNotification(activity, null)
        if (!DisplayLaunchHelper.launchOnDisplay0(activity, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) {
            DisplayLaunchHelper.launchOnDisplay0(activity, Intent(Settings.ACTION_SETTINGS))
        }
    }
}

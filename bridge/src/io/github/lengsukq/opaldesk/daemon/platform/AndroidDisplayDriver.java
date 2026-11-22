package io.github.lengsukq.opaldesk.daemon.platform;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.VirtualDisplayPool;
import io.github.lengsukq.opaldesk.daemon.input.PerDisplayInputRouter;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.util.Set;

/**
 * Concrete Android implementation of {@link VirtualDisplayPool.DisplayDriver} and
 * {@link CompositorEngine.AppRuntimeBridge}.
 *
 * <p>Allocates isolated {@link VirtualDisplay}s per window and runs every app in pure
 * {@code WINDOWING_MODE_FULLSCREEN (1)} with zero OEM multi-window / freeform hooks.
 */
public final class AndroidDisplayDriver
        implements VirtualDisplayPool.DisplayDriver, CompositorEngine.AppRuntimeBridge {

    private final AndroidServices services;
    private final TaskApiAdapter taskApi;
    private volatile PerDisplayInputRouter inputRouter;

    public AndroidDisplayDriver(AndroidServices services, TaskApiAdapter taskApi) {
        this.services = services;
        this.taskApi = taskApi;
    }

    public void setInputRouter(PerDisplayInputRouter inputRouter) {
        this.inputRouter = inputRouter;
    }

    // ---- VirtualDisplayPool.DisplayDriver ----

    @Override
    public CreatedDisplay createVirtualDisplay(
            String name, int width, int height, int dpi, float fps, Object surfaceObj, boolean allowSecure) {
        if (!(surfaceObj instanceof Surface) || services.displayManager == null) return null;
        Surface surface = (Surface) surfaceObj;
        if (!surface.isValid()) return null;

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            } catch (RuntimeException ignored) {
            }
        }

        int secBit = allowSecure ? BridgeProtocol.VD_FLAG_SECURE : 0;
        int[] candidates = allowSecure
                ? new int[]{
                        BridgeProtocol.VD_FLAGS_TRUSTED_ISOLATED | secBit,
                        BridgeProtocol.VD_FLAGS_TRUSTED_ISOLATED,
                        BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS | secBit,
                        BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS,
                        BridgeProtocol.VD_FLAGS_BASE_PUBLIC | secBit,
                        BridgeProtocol.VD_FLAGS_BASE_PUBLIC
                }
                : new int[]{
                        BridgeProtocol.VD_FLAGS_TRUSTED_ISOLATED,
                        BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS,
                        BridgeProtocol.VD_FLAGS_BASE_PUBLIC
                };

        VirtualDisplay vd = VirtualDisplayFactory.createWithFlagFallback(
                services.displayManager, name, width, height, dpi, candidates, fps, surface, null);
        if (vd == null || vd.getDisplay() == null) {
            return null;
        }

        Display disp = vd.getDisplay();
        int displayId = disp.getDisplayId();
        String uniqueId = VirtualDisplayFactory.getUniqueId(disp);
        services.configureVirtualDisplayWindowing(displayId, BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY);
        return new CreatedDisplay(vd, displayId, uniqueId);
    }

    @Override
    public void resizeVirtualDisplay(Object handle, int width, int height, int dpi) {
        if (!(handle instanceof VirtualDisplay)) return;
        try {
            ((VirtualDisplay) handle).resize(width, height, dpi);
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_VD_RESIZE_WARN: " + e.getMessage());
        }
    }

    @Override
    public void setVirtualDisplaySurface(Object handle, Object surfaceObj) {
        if (!(handle instanceof VirtualDisplay)) return;
        Surface surf = (surfaceObj instanceof Surface) ? (Surface) surfaceObj : null;
        try {
            ((VirtualDisplay) handle).setSurface(surf);
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_VD_SET_SURF_WARN: " + e.getMessage());
        }
    }

    @Override
    public void releaseVirtualDisplay(Object handle, int displayId) {
        if (!(handle instanceof VirtualDisplay)) return;
        VirtualDisplay vd = (VirtualDisplay) handle;
        try {
            vd.setSurface(null);
        } catch (RuntimeException ignored) {
        }
        try {
            vd.release();
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_VD_RELEASE_WARN id=" + displayId + ": " + e.getMessage());
        }
    }

    @Override
    public byte[] captureTaskSnapshotBytes(int taskId) {
        return taskApi.captureTaskSnapshotJpeg(taskId);
    }

    // ---- CompositorEngine.AppRuntimeBridge ----

    @Override
    public String resolveAppLabel(String pkg) {
        if (services.context == null || pkg == null || pkg.isEmpty()) return pkg != null ? pkg : "";
        try {
            PackageManager pm = services.context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return pkg;
    }

    @Override
    public int launchOrMoveFullscreenApp(String pkg, String cls, int displayId, int existingTaskId) {
        if (displayId <= 0 || PackagePolicy.isBlockedWindowPkg(pkg)) return -1;
        Bundle launchOpts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();

        int candidateTid = (existingTaskId > 0)
                ? existingTaskId
                : taskApi.findRunningOrRecentTaskForPackage(pkg);
        if (candidateTid > 0) {
            taskApi.moveRootTaskToDisplay(candidateTid, displayId);
            if (taskApi.startActivityFromRecents(candidateTid, launchOpts)) {
                services.focusDisplay(displayId);
                return candidateTid;
            }
        }

        Intent launchIntent = resolveSafeLaunchIntent(pkg, cls);
        if (launchIntent == null) return -1;
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (!services.startActivityAsShell(launchIntent, launchOpts)) {
            return -1;
        }
        services.focusDisplay(displayId);
        SystemClock.sleep(45L);
        int topTid = taskApi.findTopTaskIdOnDisplay(displayId, pkg);
        return (topTid > 0) ? topTid : taskApi.findRunningOrRecentTaskForPackage(pkg);
    }

    @Override
    public int findTopTaskIdOnDisplay(int displayId, String expectedPkg) {
        return taskApi.findTopTaskIdOnDisplay(displayId, expectedPkg);
    }

    @Override
    public Set<Integer> collectAliveTaskIds() {
        return taskApi.collectAliveTaskIds();
    }

    @Override
    public void removeTask(int taskId) {
        taskApi.removeTask(taskId);
    }

    @Override
    public void forceStopPackage(String pkg) {
        if (PackagePolicy.isBlockedWindowPkg(pkg)) return;
        ShellCommand.runOutput("am", "force-stop", pkg);
        SystemClock.sleep(90L);
    }

    @Override
    public void onDisplayReleased(int displayId) {
        PerDisplayInputRouter router = inputRouter;
        if (router != null && displayId > 0) {
            router.releaseDisplayResources(displayId);
        }
    }

    @Override
    public void releaseSurfaceHandle(Object surface) {
        if (surface instanceof Surface) {
            try {
                ((Surface) surface).release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private Intent resolveSafeLaunchIntent(String pkg, String cls) {
        if (services.context == null || !PackagePolicy.isValidPackageName(pkg)) return null;
        PackageManager pm = services.context.getPackageManager();
        if (cls != null && PackagePolicy.isValidClassName(cls)) {
            Intent explicit = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(pkg, cls));
            if (pm.resolveActivity(explicit, 0) != null) {
                return explicit;
            }
        }
        return pm.getLaunchIntentForPackage(pkg);
    }
}

package com.extbridge.daemon.display;

import android.content.ComponentName;
import android.os.SystemClock;

import com.extbridge.daemon.DaemonConfig;
import com.extbridge.daemon.DaemonConfig.PackagePolicy;
import com.extbridge.daemon.ipc.BridgeProtocol;
import com.extbridge.daemon.platform.AndroidServices;
import com.extbridge.daemon.platform.TaskApiAdapter;
import com.extbridge.daemon.util.AtomicFiles;
import com.extbridge.daemon.util.ShellCommand;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages display fullscreen/rotation/decor policy, centralized {@code platform_compat}
 * ChangeId overrides, stray compat healing, and MIUI Home secondary-display task sanitization.
 */
public final class CompatPolicyController {
    private static final String HEAL_MARKER_PATH = "/data/local/tmp/extbridge_healed_v2";
    private static final long HOME_CHECK_PERIOD_MS = 3000L;
    private static final long HOME_SANITIZE_BASE_MS = 15_000L;
    private static final long HOME_SANITIZE_MAX_MS = 60_000L;

    // Centralized platform_compat ChangeId SSOT
    private static final String CID_IGNORE_ORIENTATION = "254631730";
    private static final String CID_MIN_ASPECT_RATIO = "310816437";
    private static final String CID_ANY_ORIENTATION = "265464455";
    private static final String CID_FULLSCREEN = "263259275";
    private static final String CID_CAMERA_COMPAT = "208648326";
    private static final String CID_DEFAULT_MIN_ASPECT = "174042936";
    private static final String CID_NON_RESIZE_APP = "265452344";
    private static final String CID_PORTRAIT_COMPAT = "236283604";

    private static final String[] LAND_ENABLE = {
            CID_IGNORE_ORIENTATION, CID_MIN_ASPECT_RATIO, CID_ANY_ORIENTATION,
            CID_FULLSCREEN, CID_CAMERA_COMPAT, CID_DEFAULT_MIN_ASPECT};
    private static final String[] LAND_DISABLE = {CID_NON_RESIZE_APP, CID_PORTRAIT_COMPAT};
    private static final String[] PORT_DISABLE = {CID_IGNORE_ORIENTATION, CID_MIN_ASPECT_RATIO, CID_ANY_ORIENTATION};
    private static final String[] PORT_ENABLE = {CID_NON_RESIZE_APP, CID_PORTRAIT_COMPAT};
    private static final String HEAL_REGEX = String.join("|",
            CID_IGNORE_ORIENTATION, CID_MIN_ASPECT_RATIO, CID_PORTRAIT_COMPAT, CID_ANY_ORIENTATION, CID_FULLSCREEN);
    private static final Set<String> KNOWN_STRAY_PKGS = Set.of(
            "com.tencent.mm", "com.quark.browser", "com.xunmeng.pinduoduo",
            "com.ss.android.ugc.aweme", "com.baidu.netdisk");

    private final AndroidServices services;
    private final TaskApiAdapter taskApi;
    private final DaemonConfig config;
    private final ExecutorService bgIoExec;

    private final HashMap<String, Integer> compatOverriddenModes = new HashMap<>();
    private final AtomicLong lastHomeCheckMs = new AtomicLong(0L);
    private volatile long lastHomeSanitizeMs = 0L;
    private volatile long homeSanitizeIntervalMs = HOME_SANITIZE_BASE_MS;

    public CompatPolicyController(
            AndroidServices services, TaskApiAdapter taskApi, DaemonConfig config, ExecutorService bgIoExec) {
        this.services = services;
        this.taskApi = taskApi;
        this.config = config;
        this.bgIoExec = bgIoExec;
    }

    public void applyDisplayFullscreenPolicy(int vdId, boolean enable) {
        Object wm = services.iWindowManager;
        if (vdId <= 0 || wm == null) return;
        // Disable system decors on virtual display so Display 0 gesture navigation stays isolated.
        invokeWmByArity(wm, "setShouldShowSystemDecors", 2, vdId, false);
        if (!invokeWmByArity(wm, "setIgnoreOrientationRequest", 2, vdId, enable)) {
            ShellCommand.runOutput("wm", "set-ignore-orientation-request", "-d", String.valueOf(vdId), String.valueOf(enable));
        }
        boolean rotOk = enable
                ? (invokeWmByArity(wm, "freezeDisplayRotation", 3, vdId, 0, "ExtBridge")
                        || invokeWmByArity(wm, "freezeDisplayRotation", 2, vdId, 0))
                : (invokeWmByArity(wm, "thawDisplayRotation", 2, vdId, "ExtBridge")
                        || invokeWmByArity(wm, "thawDisplayRotation", 1, vdId));
        if (!rotOk) {
            if (enable) ShellCommand.runOutput("wm", "user-rotation", "-d", String.valueOf(vdId), "lock", "0");
            else ShellCommand.runOutput("wm", "user-rotation", "-d", String.valueOf(vdId), "free");
        }
        // Keep VD container FULLSCREEN (1) so force_desktop_mode cannot default it to FREEFORM (5).
        invokeWmByArity(wm, "setDisplayWindowingMode", 2, vdId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN);
    }

    public void applyAppModeCompat(String pkg, int mode, boolean forceApply) {
        if (!PackagePolicy.shouldCompatOverridePkg(pkg)) return;
        if (mode == BridgeProtocol.APP_MODE_DEFAULT) {
            boolean removed;
            synchronized (compatOverriddenModes) { removed = compatOverriddenModes.remove(pkg) != null; }
            if (removed || forceApply) resetCompatForPkg(pkg);
            return;
        }
        synchronized (compatOverriddenModes) {
            Integer prev = compatOverriddenModes.get(pkg);
            if (!forceApply && prev != null && prev == mode) return;
            compatOverriddenModes.put(pkg, mode);
        }
        if (mode == BridgeProtocol.APP_MODE_LAND_FULL) {
            runCompatBatch("enable", LAND_ENABLE, pkg);
            runCompatBatch("disable", LAND_DISABLE, pkg);
        } else {
            runCompatBatch("disable", PORT_DISABLE, pkg);
            runCompatBatch("enable", PORT_ENABLE, pkg);
        }
    }

    public void resetCompatForPkg(String pkg) {
        if (!PackagePolicy.isValidPackageName(pkg)) return;
        String qp = pkg.replace("'", "");
        ShellCommand.runOutput("sh", "-c", "am compat reset-all --no-kill '" + qp
                + "' >/dev/null 2>&1 || am compat reset-all '" + qp + "' >/dev/null 2>&1");
    }

    public void resetAppFullscreenCompatAll() {
        ArrayList<String> pkgs;
        synchronized (compatOverriddenModes) {
            if (compatOverriddenModes.isEmpty()) return;
            pkgs = new ArrayList<>(compatOverriddenModes.keySet());
            compatOverriddenModes.clear();
        }
        for (String pkg : pkgs) resetCompatForPkg(pkg);
    }

    public void resetAllConfiguredAndActiveCompat() {
        HashSet<String> allPkgs = new HashSet<>(config.clearAllAppModes());
        synchronized (compatOverriddenModes) {
            allPkgs.addAll(compatOverriddenModes.keySet());
            compatOverriddenModes.clear();
        }
        for (String p : allPkgs) resetCompatForPkg(p);
        healStrayCompatOverrides(true);
    }

    public void maybeScheduleInitialHeal(boolean firstConf) {
        if (!firstConf && new File(HEAL_MARKER_PATH).exists()) return;
        bgIoExec.execute(() -> {
            healStrayCompatOverrides(false);
            try {
                AtomicFiles.writeAtomicUtf8(new File(HEAL_MARKER_PATH), "1\n", AtomicFiles.MODE_OWNER_RW);
            } catch (IOException e) {
                System.err.println("EXTBRIDGE_HEAL_MARKER_WRITE_ERR: " + e.getMessage());
            }
        });
    }

    public void healStrayCompatOverrides(boolean resetConfiguredToo) {
        HashSet<String> toReset = new HashSet<>(KNOWN_STRAY_PKGS);
        String dump = ShellCommand.runOutput("sh", "-c",
                "dumpsys platform_compat 2>/dev/null | grep -E 'ChangeId\\((" + HEAL_REGEX + ");'");
        for (String line : dump.split("\n")) {
            int idx = line.indexOf("packageOverrides={");
            if (idx < 0) continue;
            int start = idx + "packageOverrides={".length();
            int end = line.indexOf('}', start);
            if (end <= start) continue;
            for (String kv : line.substring(start, end).split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    String pkg = kv.substring(0, eq).trim();
                    if (PackagePolicy.isValidPackageName(pkg)) toReset.add(pkg);
                }
            }
        }
        if (!resetConfiguredToo) toReset.removeAll(config.getConfiguredAppModePackages());
        for (String pkg : toReset) resetCompatForPkg(pkg);
    }

    public void maybeScheduleHomeSanitize(long nowMs, int vdId, int sideVdId, int extId) {
        long prev = lastHomeCheckMs.get();
        if (nowMs - prev >= HOME_CHECK_PERIOD_MS && lastHomeCheckMs.compareAndSet(prev, nowMs)) {
            bgIoExec.execute(() -> sanitizeSystemHomeTasks(false, vdId, sideVdId, extId));
        }
    }

    public void sanitizeSystemHomeTasks(boolean checkCreatedDisplayConfig, int vdId, int sideVdId, int extId) {
        long now = SystemClock.uptimeMillis();
        if (!checkCreatedDisplayConfig && (now - lastHomeSanitizeMs < homeSanitizeIntervalMs)) return;

        int disp0Count = 0;
        boolean vdLeak = false, homeTop0 = false, seen0 = false;
        ArrayList<Integer> toRemove = new ArrayList<>();
        List<?> running = taskApi.getTasks();
        if (running != null) {
            for (Object t : running) {
                if (t == null) continue;
                String pkg = TaskApiAdapter.extractTaskPkg(t);
                int did = TaskApiAdapter.readIntField(t, "displayId", -1);
                if (did == 0 && !seen0) {
                    seen0 = true;
                    homeTop0 = "com.miui.home".equals(pkg);
                }
                if (!"com.miui.home".equals(pkg)) continue;
                int tid = TaskApiAdapter.extractTaskId(t);
                boolean isOurVd = (vdId > 0 && did == vdId) || (sideVdId > 0 && did == sideVdId);
                if (isOurVd || (checkCreatedDisplayConfig && did > 0 && (extId <= 0 || did != extId))) {
                    vdLeak = true;
                    if (tid > 0) toRemove.add(tid);
                } else if (did == 0 && isMiuiHomeLauncherTask(t) && ++disp0Count > 1 && tid > 0) {
                    toRemove.add(tid);
                }
            }
        }
        boolean staleCfg = checkCreatedDisplayConfig && !vdLeak && toRemove.isEmpty()
                && hasStaleMiuiHomeCreatedConfig(extId);
        if (!vdLeak && toRemove.isEmpty() && !staleCfg) {
            homeSanitizeIntervalMs = HOME_SANITIZE_BASE_MS;
            return;
        }
        lastHomeSanitizeMs = now;
        homeSanitizeIntervalMs = Math.min(HOME_SANITIZE_MAX_MS, homeSanitizeIntervalMs * 2L);
        System.out.println("EXTBRIDGE_SANITIZE_MIUI_HOME leaked=" + vdLeak
                + " remove=" + toRemove.size() + " staleCfg=" + staleCfg + " homeTop0=" + homeTop0);
        for (int tid : toRemove) taskApi.removeTask(tid);
        ShellCommand.runOutput("am", "force-stop", "com.miui.home");
        if (homeTop0) {
            ShellCommand.runOutput("am", "start", "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.HOME", "--display", "0");
        }
    }

    private static void runCompatBatch(String action, String[] ids, String pkg) {
        for (String ch : ids) ShellCommand.runOutput("am", "compat", action, "--no-kill", ch, pkg);
    }

    private static boolean isMiuiHomeLauncherTask(Object t) {
        Object top = TaskApiAdapter.readObjField(t, "topActivity");
        Object base = TaskApiAdapter.readObjField(t, "baseActivity");
        String tc = (top instanceof ComponentName) ? ((ComponentName) top).getClassName() : "";
        String bc = (base instanceof ComponentName) ? ((ComponentName) base).getClassName() : "";
        return tc.contains("Launcher") || bc.contains("Launcher") || (tc.isEmpty() && bc.isEmpty());
    }

    private static boolean hasStaleMiuiHomeCreatedConfig(int extId) {
        String dump = ShellCommand.runOutput("dumpsys", "activity", "com.miui.home");
        for (int from = 0; ; ) {
            int cIdx = dump.indexOf("created_config:", from);
            if (cIdx < 0) return false;
            int endIdx = dump.indexOf("override_config:", cIdx);
            String seg = (endIdx > cIdx) ? dump.substring(cIdx, endIdx)
                    : dump.substring(cIdx, Math.min(dump.length(), cIdx + 2048));
            int mIdx = seg.indexOf("MiuiConfiguration");
            int dIdx = (mIdx >= 0) ? seg.indexOf("displayId:", mIdx) : -1;
            if (dIdx >= 0) {
                int p = dIdx + "displayId:".length();
                while (p < seg.length() && seg.charAt(p) == ' ') p++;
                int q = p;
                while (q < seg.length() && Character.isDigit(seg.charAt(q))) q++;
                if (q > p) {
                    int cfgDid = Integer.parseInt(seg.substring(p, q));
                    if (cfgDid != 0 && (extId <= 0 || cfgDid != extId)) return true;
                }
            }
            from = cIdx + 15;
        }
    }

    private static boolean invokeWmByArity(Object wm, String name, int arity, Object... args) {
        try {
            for (Method m : wm.getClass().getMethods()) {
                if (name.equals(m.getName()) && m.getParameterCount() == arity) {
                    m.invoke(wm, args);
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_WM_POLICY_WARN method=" + name + ": " + e.getMessage());
        }
        return false;
    }
}

package com.extbridge.daemon;

import com.extbridge.daemon.ipc.BridgeProtocol;
import com.extbridge.daemon.sink.DisplaySink;
import com.extbridge.daemon.util.AtomicFiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Persistent daemon configuration ({@code extbridge.conf} & {@code extbridge_apps.conf}),
 * shared runtime display state with lock-consistent snapshots, and unified {@link PackagePolicy} SSOT.
 * Pure Java 11 with zero Android runtime calls.
 */
public final class DaemonConfig {
    public static final String DEFAULT_CONF_PATH = "/data/local/tmp/extbridge.conf";
    public static final String DEFAULT_APP_CONF_PATH = "/data/local/tmp/extbridge_apps.conf";
    public static final String DEFAULT_FIRST_APP_PKG = "com.phoenix.read";
    public static final int MAX_APP_MODES = 512;

    /** Single source of truth for package validation, system/protected/compat/launch/Settings rules. */
    public static final class PackagePolicy {
        private static final Pattern PKG_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$");
        private static final Pattern CLS_PATTERN = Pattern.compile("^[a-zA-Z0-9_$]+(\\.[a-zA-Z0-9_$]+)+$");

        private static final Set<String> SKIP_TASK_PKGS = Set.of(
                BridgeBuildConfig.HELPER_PACKAGE, "com.miui.home", "com.android.systemui",
                "com.miui.securitycenter", "com.android.launcher", "com.android.launcher3");
        private static final Set<String> EXTRA_PROTECTED_PKGS = Set.of(
                "android", "com.android.phone", "com.android.server.telecom");
        private static final Set<String> NO_COMPAT_PKGS = Set.of(
                "android", BridgeBuildConfig.HELPER_PACKAGE, "com.android.settings",
                "com.android.systemui", "com.miui.home", "com.miui.securitycenter");
        private static final Set<String> BLOCKED_LAUNCH_PKGS = Set.of(
                "android", "com.android.systemui", "com.miui.home", "com.miui.securitycenter");
        private static final Set<String> ALLOWED_SETTINGS_ACTIONS = Set.of(
                "android.settings.SETTINGS", "android.settings.WIFI_SETTINGS",
                "android.settings.BLUETOOTH_SETTINGS", "android.settings.DISPLAY_SETTINGS",
                "android.settings.SOUND_SETTINGS", "android.settings.AIRPLANE_MODE_SETTINGS",
                "android.settings.BATTERY_SAVER_SETTINGS", "android.settings.CAST_SETTINGS",
                "android.settings.ACCESSIBILITY_SETTINGS", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
                "android.settings.action.MANAGE_OVERLAY_PERMISSION",
                "android.settings.APPLICATION_DETAILS_SETTINGS", "android.settings.WIRELESS_SETTINGS");

        private PackagePolicy() {}

        public static boolean isValidPackageName(String pkg) {
            return pkg != null && pkg.length() >= 3 && pkg.length() <= 160 && PKG_PATTERN.matcher(pkg).matches();
        }

        public static boolean isValidClassName(String cls) {
            return cls != null && cls.length() >= 3 && cls.length() <= 220 && CLS_PATTERN.matcher(cls).matches();
        }

        public static boolean shouldSkipTaskPkg(String pkg) {
            return pkg == null || pkg.isEmpty() || SKIP_TASK_PKGS.contains(pkg);
        }

        public static boolean isProtectedSystemTaskPkg(String pkg) {
            if (!isValidPackageName(pkg) || shouldSkipTaskPkg(pkg) || EXTRA_PROTECTED_PKGS.contains(pkg)) return true;
            return pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")
                    || pkg.contains("keychain") || pkg.contains("keyguard");
        }

        public static boolean shouldCompatOverridePkg(String pkg) {
            return isValidPackageName(pkg) && !NO_COMPAT_PKGS.contains(pkg);
        }

        public static boolean isBlockedLaunchPkg(String pkg) {
            return pkg == null || BLOCKED_LAUNCH_PKGS.contains(pkg);
        }

        public static boolean isHelperLauncherComponent(String pkg, String cls) {
            return BridgeBuildConfig.HELPER_PACKAGE.equals(pkg)
                    && BridgeProtocol.HELPER_LAUNCHER_CLASS.equals(cls);
        }

        public static boolean isAllowedSettingsAction(String action) {
            return action != null && !action.isEmpty() && ALLOWED_SETTINGS_ACTIONS.contains(action);
        }
    }

    /** Immutable display state snapshot read atomically under {@link #displayLock}. */
    public static final class DisplayStatusSnapshot {
        public final int extId, vdId, physW, physH, virtW, virtH, virtDpi, virtRot;
        public final float physFps, virtFps;
        public final int inputToExt, mirrorMode, fullscreenPolicy, mirrorSideMode;
        public final String renderMode;

        DisplayStatusSnapshot(DaemonConfig c) {
            this.extId = c.extId;
            this.vdId = c.vdId;
            this.physW = c.physW;
            this.physH = c.physH;
            this.physFps = c.physFps;
            this.virtW = c.virtW;
            this.virtH = c.virtH;
            this.virtDpi = c.virtDpi;
            this.virtFps = c.virtFps;
            this.virtRot = c.virtRot;
            this.inputToExt = c.inputToExt;
            this.renderMode = (c.renderMode != null) ? c.renderMode : "NONE";
            this.mirrorMode = c.mirrorMode;
            this.fullscreenPolicy = c.fullscreenPolicy;
            this.mirrorSideMode = c.mirrorSideMode;
        }
    }

    public final Object displayLock = new Object();
    private final Object appModesLock = new Object();
    private final File confFile;
    private final File appConfFile;
    private final HashMap<String, Integer> appModes = new HashMap<>();

    public volatile int extId = -1;
    public volatile int vdId = -1;
    public volatile String vdUniqueId;
    public volatile String renderMode = "NONE";
    public volatile int physW = 2560, physH = 1440;
    public volatile float physFps = 60.0f;
    public volatile int virtW = 2560, virtH = 1440, virtDpi = 240, virtRot = 0;
    public volatile float virtFps = 60.0f;
    public volatile int mirrorMode = BridgeProtocol.MIRROR_MODE_DEX;
    public volatile int mirrorSideMode = BridgeProtocol.MIRROR_SIDE_ALIGN_LEFT;
    public volatile int fullscreenPolicy = 1;
    public volatile boolean hasUserConf = false;
    public volatile boolean forceRebuild = false;
    public volatile int inputToExt = 1;
    public volatile int inputBackendMode = BridgeProtocol.INPUT_BACKEND_AUTO;
    public volatile int activeSinkType = DisplaySink.SINK_NONE;
    public volatile boolean shuttingDown = false;

    public DaemonConfig() {
        this(new File(DEFAULT_CONF_PATH), new File(DEFAULT_APP_CONF_PATH));
    }

    public DaemonConfig(File confFile, File appConfFile) {
        this.confFile = (confFile != null) ? confFile : new File(DEFAULT_CONF_PATH);
        this.appConfFile = (appConfFile != null) ? appConfFile : new File(DEFAULT_APP_CONF_PATH);
    }

    public boolean loadAll() {
        loadConf();
        return loadAppModes();
    }

    public void loadConf() {
        if (!confFile.exists()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(confFile), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) return;
            String[] p = line.trim().split(",");
            if (p.length < 4) return;
            int w = Integer.parseInt(p[0].trim());
            int h = Integer.parseInt(p[1].trim());
            int d = Integer.parseInt(p[2].trim());
            float fps = Float.parseFloat(p[3].trim());
            synchronized (displayLock) {
                if (DisplayGeometryUtil.isValidDims(w, h)
                        && d >= DisplayGeometryUtil.MIN_DPI && d <= DisplayGeometryUtil.MAX_DPI) {
                    virtW = w;
                    virtH = h;
                    virtDpi = d;
                    virtFps = DisplayGeometryUtil.clampFps(fps, 60f);
                    hasUserConf = true;
                }
                if (p.length >= 5) fullscreenPolicy = Integer.parseInt(p[4].trim()) != 0 ? 1 : 0;
                if (p.length >= 6) virtRot = DisplayGeometryUtil.normalizeRotation(Integer.parseInt(p[5].trim()));
                if (p.length >= 7) mirrorMode = BridgeProtocol.MIRROR_MODE_DEX; // never boot into mirror lock
                if (p.length >= 8) {
                    int sm = Integer.parseInt(p[7].trim());
                    if (sm >= BridgeProtocol.MIRROR_SIDE_OFF && sm <= BridgeProtocol.MIRROR_SIDE_CENTER_DUAL) {
                        mirrorSideMode = sm;
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("EXTBRIDGE_LOAD_CONF_ERR path=" + confFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    public void saveConf() {
        String line;
        synchronized (displayLock) {
            line = virtW + "," + virtH + "," + virtDpi + "," + virtFps + ","
                    + fullscreenPolicy + "," + virtRot + ",0," + mirrorSideMode + "\n";
        }
        try {
            AtomicFiles.writeAtomicUtf8(confFile, line, AtomicFiles.MODE_OWNER_RW);
        } catch (IOException e) {
            System.err.println("EXTBRIDGE_SAVE_CONF_ERR path=" + confFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    public boolean loadAppModes() {
        boolean firstConf = !appConfFile.exists();
        boolean prunedLegacy = false;
        synchronized (appModesLock) {
            appModes.clear();
            if (firstConf) {
                appModes.put(DEFAULT_FIRST_APP_PKG, BridgeProtocol.APP_MODE_LAND_FULL);
                saveAppModesLocked();
                return true;
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(appConfFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    int eq = t.indexOf('=');
                    if (eq <= 0) continue;
                    String pkg = t.substring(0, eq).trim();
                    if (!PackagePolicy.isValidPackageName(pkg)) continue;
                    try {
                        int m = Integer.parseInt(t.substring(eq + 1).trim());
                        if (m == BridgeProtocol.APP_MODE_LAND_FULL || m == BridgeProtocol.APP_MODE_PORT_COMPAT) {
                            if (appModes.size() < MAX_APP_MODES || appModes.containsKey(pkg)) appModes.put(pkg, m);
                        } else if (m == 3) {
                            prunedLegacy = true;
                        }
                    } catch (NumberFormatException nfe) {
                        System.err.println("EXTBRIDGE_APP_MODE_BAD_LINE line=" + t);
                    }
                }
            } catch (IOException e) {
                System.err.println("EXTBRIDGE_LOAD_APP_MODES_ERR path=" + appConfFile.getAbsolutePath() + ": " + e.getMessage());
            }
            if (prunedLegacy) saveAppModesLocked();
        }
        return false;
    }

    public int getAppMode(String pkg) {
        if (!PackagePolicy.isValidPackageName(pkg)) return BridgeProtocol.APP_MODE_DEFAULT;
        synchronized (appModesLock) {
            Integer v = appModes.get(pkg);
            return (v != null) ? v : BridgeProtocol.APP_MODE_DEFAULT;
        }
    }

    public boolean setAppMode(String pkg, int mode) {
        int clamped = Math.max(BridgeProtocol.APP_MODE_DEFAULT, Math.min(BridgeProtocol.APP_MODE_PORT_COMPAT, mode));
        if (!PackagePolicy.shouldCompatOverridePkg(pkg)) return false;
        synchronized (appModesLock) {
            if (clamped == BridgeProtocol.APP_MODE_DEFAULT) {
                appModes.remove(pkg);
            } else if (appModes.size() < MAX_APP_MODES || appModes.containsKey(pkg)) {
                appModes.put(pkg, clamped);
            } else {
                return false;
            }
            saveAppModesLocked();
            return true;
        }
    }

    public String serializeAppModesSummary() {
        StringBuilder sb = new StringBuilder();
        synchronized (appModesLock) {
            for (Map.Entry<String, Integer> e : appModes.entrySet()) {
                if (e.getValue() == null || e.getValue() == BridgeProtocol.APP_MODE_DEFAULT) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    public Set<String> getConfiguredAppModePackages() {
        synchronized (appModesLock) {
            Set<String> out = new HashSet<>();
            for (Map.Entry<String, Integer> e : appModes.entrySet()) {
                int m = (e.getValue() == null) ? 0 : e.getValue();
                if (m == BridgeProtocol.APP_MODE_LAND_FULL || m == BridgeProtocol.APP_MODE_PORT_COMPAT) {
                    out.add(e.getKey());
                }
            }
            return out;
        }
    }

    public Set<String> clearAllAppModes() {
        synchronized (appModesLock) {
            Set<String> prev = new HashSet<>(appModes.keySet());
            appModes.clear();
            saveAppModesLocked();
            return prev;
        }
    }

    public DisplayStatusSnapshot snapshotDisplayStatus() {
        synchronized (displayLock) {
            return new DisplayStatusSnapshot(this);
        }
    }

    public int getVirtualDisplayFlags() {
        return (fullscreenPolicy == 1) ? BridgeProtocol.VD_FLAGS_DEX_FIXED : BridgeProtocol.VD_FLAGS_COMPAT;
    }

    private void saveAppModesLocked() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : appModes.entrySet()) {
            String k = e.getKey();
            int m = (e.getValue() == null) ? 0 : e.getValue();
            if (PackagePolicy.isValidPackageName(k)
                    && (m == BridgeProtocol.APP_MODE_LAND_FULL || m == BridgeProtocol.APP_MODE_PORT_COMPAT)) {
                sb.append(k).append('=').append(m).append('\n');
            }
        }
        try {
            AtomicFiles.writeAtomicUtf8(appConfFile, sb.toString(), AtomicFiles.MODE_OWNER_RW);
        } catch (IOException e) {
            System.err.println("EXTBRIDGE_SAVE_APP_MODES_ERR path=" + appConfFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }
}

package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OEM-agnostic persistent daemon configuration ({@code /data/local/tmp/opaldesk_v2.conf} and
 * {@code /data/local/tmp/opaldesk_profiles.conf}) and package safety policy.
 *
 * <p>Contains zero vendor-specific (MIUI/HyperOS/ColorOS/OneUI) package hardcodes.
 */
public final class DaemonConfig {
    public static final String DEFAULT_CONF_PATH = "/data/local/tmp/opaldesk_v2.conf";
    public static final String DEFAULT_PROFILE_CONF_PATH = "/data/local/tmp/opaldesk_profiles.conf";
    public static final int DEFAULT_ROOTLESS_MAX_VD = 6;
    public static final int DEFAULT_ROOT_ENHANCED_MAX_VD = 10;
    public static final int MIN_MAX_VD = 2;
    public static final int HARD_MAX_VD = 12;

    /** Universal AOSP package validation & system task protection rules. */
    public static final class PackagePolicy {
        private static final Pattern PKG_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$");
        private static final Pattern CLS_PATTERN = Pattern.compile("^[a-zA-Z0-9_$]+(\\.[a-zA-Z0-9_$]+)+$");

        private static final Set<String> CORE_BLOCKED_PKGS = Set.of(
                "android",
                BridgeBuildConfig.HELPER_PACKAGE,
                "com.android.systemui",
                "com.android.phone",
                "com.android.server.telecom"
        );

        private PackagePolicy() {}

        public static boolean isValidPackageName(String pkg) {
            return pkg != null && pkg.length() >= 3 && pkg.length() <= 160 && PKG_PATTERN.matcher(pkg).matches();
        }

        public static boolean isValidClassName(String cls) {
            return cls != null && cls.length() >= 3 && cls.length() <= 220 && CLS_PATTERN.matcher(cls).matches();
        }

        public static boolean isBlockedWindowPkg(String pkg) {
            if (!isValidPackageName(pkg) || CORE_BLOCKED_PKGS.contains(pkg)) return true;
            return pkg.contains("permissioncontroller")
                    || pkg.contains("packageinstaller")
                    || pkg.contains("keyguard");
        }
    }

    private final Object lock = new Object();
    private final File confFile;
    private final File profileFile;
    private final Map<String, WindowProfile> appProfiles = new HashMap<>();

    public volatile boolean shuttingDown = false;
    private int canvasWidth = 1920;
    private int canvasHeight = 1200;
    private int canvasDpi = 240;
    private int maxActiveDisplays = DEFAULT_ROOTLESS_MAX_VD;
    private int inputBackendMode = BridgeProtocol.INPUT_BACKEND_AUTO;
    private int targetFps = 0; // 0 = auto match display refresh rate
    private int windowDpiScalePct = 100; // 85=compact, 100=standard/auto, 115=large
    private int mouseTargetMode = BridgeProtocol.MOUSE_TARGET_EXTERNAL;
    private boolean rootEnhanced = false;
    private boolean secureCaptureEnabled = false;

    public DaemonConfig() {
        this(new File(DEFAULT_CONF_PATH), new File(DEFAULT_PROFILE_CONF_PATH));
    }

    public DaemonConfig(File confFile, File profileFile) {
        this.confFile = (confFile != null) ? confFile : new File(DEFAULT_CONF_PATH);
        this.profileFile = (profileFile != null) ? profileFile : new File(DEFAULT_PROFILE_CONF_PATH);
    }

    public void loadAll() {
        loadMainConf();
        loadAppProfiles();
    }

    public int getCanvasWidth() {
        synchronized (lock) { return canvasWidth; }
    }

    public int getCanvasHeight() {
        synchronized (lock) { return canvasHeight; }
    }

    public int getCanvasDpi() {
        synchronized (lock) { return canvasDpi; }
    }

    public int getMaxActiveDisplays() {
        synchronized (lock) { return maxActiveDisplays; }
    }

    public int getInputBackendMode() {
        synchronized (lock) { return inputBackendMode; }
    }

    public int getTargetFps() {
        synchronized (lock) { return targetFps; }
    }

    public int getWindowDpiScalePct() {
        synchronized (lock) { return windowDpiScalePct; }
    }

    public int getMouseTargetMode() {
        synchronized (lock) { return mouseTargetMode; }
    }

    public void setMouseTargetMode(int mode) {
        synchronized (lock) {
            this.mouseTargetMode = (mode == BridgeProtocol.MOUSE_TARGET_LOCAL)
                    ? BridgeProtocol.MOUSE_TARGET_LOCAL
                    : BridgeProtocol.MOUSE_TARGET_EXTERNAL;
        }
        saveMainConf();
    }

    public void updateDisplayPrefs(int reqTargetFps, int reqDpiScalePct) {
        synchronized (lock) {
            if (reqTargetFps == 0 || (reqTargetFps >= 30 && reqTargetFps <= 165)) {
                this.targetFps = reqTargetFps;
            }
            if (reqDpiScalePct >= 70 && reqDpiScalePct <= 140) {
                this.windowDpiScalePct = reqDpiScalePct;
            }
        }
        saveMainConf();
    }

    public boolean isRootEnhanced() {
        synchronized (lock) { return rootEnhanced; }
    }

    public boolean isSecureCaptureEnabled() {
        synchronized (lock) { return secureCaptureEnabled; }
    }

    public void markRootEnhanced(boolean enhanced, boolean secureCapture) {
        synchronized (lock) {
            this.rootEnhanced = enhanced;
            this.secureCaptureEnabled = secureCapture;
            if (enhanced && this.maxActiveDisplays == DEFAULT_ROOTLESS_MAX_VD) {
                this.maxActiveDisplays = DEFAULT_ROOT_ENHANCED_MAX_VD;
            }
        }
    }

    public void updateRuntimeConfig(int inputMode, int maxVd, int workW, int workH, int workDpi) {
        synchronized (lock) {
            if (inputMode >= BridgeProtocol.INPUT_BACKEND_AUTO && inputMode <= BridgeProtocol.INPUT_BACKEND_UHID) {
                this.inputBackendMode = inputMode;
            }
            if (maxVd >= MIN_MAX_VD && maxVd <= HARD_MAX_VD) {
                this.maxActiveDisplays = maxVd;
            }
            if (workW >= 360 && workH >= 360) {
                this.canvasWidth = DisplayGeometryUtil.alignEven(workW, 360, 4096);
                this.canvasHeight = DisplayGeometryUtil.alignEven(workH, 360, 4096);
            }
            if (workDpi >= DisplayGeometryUtil.MIN_DPI && workDpi <= DisplayGeometryUtil.MAX_DPI) {
                this.canvasDpi = workDpi;
            }
        }
        saveMainConf();
    }

    public WindowProfile getSavedProfile(String pkg) {
        if (!PackagePolicy.isValidPackageName(pkg)) return null;
        synchronized (lock) {
            return appProfiles.get(pkg);
        }
    }

    public void rememberProfile(String pkg, WindowProfile profile) {
        if (!PackagePolicy.isValidPackageName(pkg) || profile == null) return;
        synchronized (lock) {
            if (appProfiles.size() < 512 || appProfiles.containsKey(pkg)) {
                appProfiles.put(pkg, profile);
            }
        }
        saveAppProfiles();
    }

    private void loadMainConf() {
        if (!confFile.isFile()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(confFile), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) return;
            String[] parts = line.trim().split(",");
            if (parts.length < 5) return;
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            int dpi = Integer.parseInt(parts[2].trim());
            int maxVd = Integer.parseInt(parts[3].trim());
            int inMode = Integer.parseInt(parts[4].trim());
            synchronized (lock) {
                if (w >= 360 && h >= 360) {
                    canvasWidth = DisplayGeometryUtil.alignEven(w, 360, 4096);
                    canvasHeight = DisplayGeometryUtil.alignEven(h, 360, 4096);
                }
                canvasDpi = DisplayGeometryUtil.clampDpi(dpi);
                maxActiveDisplays = Math.max(MIN_MAX_VD, Math.min(HARD_MAX_VD, maxVd));
                if (inMode >= BridgeProtocol.INPUT_BACKEND_AUTO && inMode <= BridgeProtocol.INPUT_BACKEND_UHID) {
                    inputBackendMode = inMode;
                }
                if (parts.length >= 6) {
                    int fps = Integer.parseInt(parts[5].trim());
                    if (fps == 0 || (fps >= 30 && fps <= 165)) targetFps = fps;
                }
                if (parts.length >= 7) {
                    int dpiPct = Integer.parseInt(parts[6].trim());
                    if (dpiPct >= 70 && dpiPct <= 140) windowDpiScalePct = dpiPct;
                }
                if (parts.length >= 8) {
                    int mt = Integer.parseInt(parts[7].trim());
                    mouseTargetMode = (mt == BridgeProtocol.MOUSE_TARGET_LOCAL)
                            ? BridgeProtocol.MOUSE_TARGET_LOCAL
                            : BridgeProtocol.MOUSE_TARGET_EXTERNAL;
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("OPALDESK_CONF_LOAD_WARN: " + e.getMessage());
        }
    }

    public void saveMainConf() {
        String content;
        synchronized (lock) {
            content = canvasWidth + "," + canvasHeight + "," + canvasDpi + ","
                    + maxActiveDisplays + "," + inputBackendMode + ","
                    + targetFps + "," + windowDpiScalePct + "," + mouseTargetMode + "\n";
        }
        try {
            AtomicFiles.writeAtomicUtf8(confFile, content, AtomicFiles.MODE_OWNER_RW);
        } catch (IOException e) {
            System.err.println("OPALDESK_CONF_SAVE_WARN: " + e.getMessage());
        }
    }

    private void loadAppProfiles() {
        if (!profileFile.isFile()) return;
        synchronized (lock) {
            appProfiles.clear();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(profileFile), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = br.readLine()) != null) {
                    int eq = ln.indexOf('=');
                    if (eq <= 0) continue;
                    String pkg = ln.substring(0, eq).trim();
                    WindowProfile p = WindowProfile.fromName(ln.substring(eq + 1).trim());
                    if (PackagePolicy.isValidPackageName(pkg) && p != null) {
                        appProfiles.put(pkg, p);
                    }
                }
            } catch (IOException e) {
                System.err.println("OPALDESK_PROFILES_LOAD_WARN: " + e.getMessage());
            }
        }
    }

    private void saveAppProfiles() {
        StringBuilder sb = new StringBuilder();
        synchronized (lock) {
            for (Map.Entry<String, WindowProfile> e : appProfiles.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue().name()).append('\n');
            }
        }
        try {
            AtomicFiles.writeAtomicUtf8(profileFile, sb.toString(), AtomicFiles.MODE_OWNER_RW);
        } catch (IOException e) {
            System.err.println("OPALDESK_PROFILES_SAVE_WARN: " + e.getMessage());
        }
    }
}

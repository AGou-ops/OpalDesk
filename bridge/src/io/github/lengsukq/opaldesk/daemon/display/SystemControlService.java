package io.github.lengsukq.opaldesk.daemon.display;

import android.app.ActivityOptions;
import android.app.WallpaperManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.ipc.HelperClientBridge;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * System controls and status collection: crash-safe atomic main-screen dim/blackout,
 * thread-safe wallpaper cache, Wi-Fi/BT/Airplane/PowerSaver toggles, and delegated mirror telemetry.
 */
public final class SystemControlService {
    private static final String DIM_BACKUP_PATH = "/data/local/tmp/opaldesk_dim.bak";
    private static final long SYS_STATUS_CACHE_MS = 2500L;

    private final AndroidServices services;
    private final DaemonConfig config;
    private final HelperClientBridge helperBridge;
    private final MirrorTelemetryCollector telemetry;

    private final Object screenDimLock = new Object();
    private final Object wallpaperLock = new Object();
    private final AtomicLong lastSysCheckMs = new AtomicLong(0L);

    private volatile Runnable onDimStateChanged;
    private volatile boolean screenDimmed = false;
    private int savedBrightMode = 0, savedBrightInt = 100;
    private float savedBrightFloat = -1f;

    private int cachedWpId = Integer.MIN_VALUE;
    private byte[] cachedWpBytes = null;
    private volatile String cachedWifiSsid = "";
    private volatile String cachedBtName = "";

    public SystemControlService(AndroidServices services, DaemonConfig config, HelperClientBridge helperBridge) {
        this.services = services;
        this.config = config;
        this.helperBridge = helperBridge;
        this.telemetry = new MirrorTelemetryCollector(services, () -> cachedBtName);
    }

    public void setOnDimStateChanged(Runnable callback) { this.onDimStateChanged = callback; }
    public boolean isScreenDimmed() { return screenDimmed; }

    public boolean grantAppWidgetBind() {
        return helperBridge.getVerifiedHelperUid() > 0
                && AndroidServices.grantBindAppWidgetPermission(BridgeBuildConfig.HELPER_PACKAGE, 0);
    }

    public void writeMirrorTelemetryToParcel(Parcel reply) {
        telemetry.writeToParcel(reply);
    }

    // ---- Main Screen Blackout / Dim with Atomic Backup ----

    public void restoreOrphanScreenDimBackup() {
        synchronized (screenDimLock) {
            File bak = new File(DIM_BACKUP_PATH);
            if (!bak.exists()) return;
            savedBrightMode = 0;
            savedBrightInt = 800;
            savedBrightFloat = 0.35f;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(bak), StandardCharsets.UTF_8))) {
                String ln = br.readLine();
                if (ln != null && !ln.trim().isEmpty()) {
                    String[] p = ln.trim().split("\\|");
                    if (p.length >= 1) savedBrightMode = Integer.parseInt(p[0].trim());
                    if (p.length >= 2) savedBrightInt = Integer.parseInt(p[1].trim());
                    if (p.length >= 3) {
                        float f = Float.parseFloat(p[2].trim());
                        if (Float.isFinite(f)) savedBrightFloat = f;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                System.err.println("OPALDESK_DIM_BACKUP_CORRUPT restoring safe default: " + e.getMessage());
            }
            screenDimmed = true;
            setMainScreenDim(0);
        }
    }

    public void restoreDimIfActive() {
        if (screenDimmed || new File(DIM_BACKUP_PATH).exists()) setMainScreenDim(0);
    }

    public boolean setMainScreenDim(int arg) {
        boolean result;
        synchronized (screenDimLock) {
            boolean wantDim = (arg < 0) ? !screenDimmed : (arg == 1);
            if (!wantDim && !screenDimmed && !new File(DIM_BACKUP_PATH).exists()) return true;
            result = wantDim ? applyScreenDimLocked() : restoreScreenDimLocked();
        }
        Runnable cb = onDimStateChanged;
        if (cb != null) cb.run();
        return result;
    }

    private boolean applyScreenDimLocked() {
        float minFloat = 0.00171f;
        float curFloat = -1f;
        if (services.displayManager != null) {
            try {
                Display d0 = services.displayManager.getDisplay(0);
                Object bi = (d0 != null) ? Display.class.getMethod("getBrightnessInfo").invoke(d0) : null;
                if (bi != null) {
                    float bm = bi.getClass().getField("brightnessMinimum").getFloat(bi);
                    if (bm > 0f && bm <= 0.1f) minFloat = bm;
                    float cb = bi.getClass().getField("brightness").getFloat(bi);
                    if (cb > 0f && cb <= 1f) curFloat = cb;
                }
            } catch (NoSuchMethodException | NoSuchFieldException ignored) {
                // Hidden BrightnessInfo absent on older ROMs.
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_BRIGHT_INFO_WARN: " + e.getMessage());
            }
        }
        if (!screenDimmed) {
            captureCurrentBrightnessLocked(curFloat);
            if (!writeDimBackupAtomic(savedBrightMode, savedBrightInt, savedBrightFloat)) {
                System.err.println("OPALDESK_SCREEN_DIM_ABORT backup write failed");
                return false;
            }
        }
        ShellCommand.runOutput("sh", "-c",
                "settings put system screen_brightness_mode 0; settings put system screen_brightness 1; "
                        + "cmd display set-brightness " + String.format(Locale.US, "%.6f", minFloat) + " 2>/dev/null || true");
        screenDimmed = true;
        System.out.println("OPALDESK_SCREEN_DIM=1 min=" + minFloat + " savedInt=" + savedBrightInt);
        return true;
    }

    private boolean restoreScreenDimLocked() {
        int restoreInt = Math.max(20, savedBrightInt > 1 ? savedBrightInt : 800);
        int restoreMode = (savedBrightMode == 1) ? 1 : 0;
        if (services.context != null) {
            try {
                Settings.System.putInt(services.context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, restoreInt);
                Settings.System.putInt(services.context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, restoreMode);
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_DIM_SETTINGS_PUT_WARN: " + e.getMessage());
            }
        }
        String floatCmd = (Float.isFinite(savedBrightFloat) && savedBrightFloat > 0.002f && savedBrightFloat <= 1f)
                ? ("cmd display set-brightness " + String.format(Locale.US, "%.6f", savedBrightFloat) + " 2>/dev/null || true; ")
                : "";
        ShellCommand.runOutput("sh", "-c", "settings put system screen_brightness " + restoreInt + "; "
                + floatCmd + "settings put system screen_brightness_mode " + restoreMode);
        int verifyInt = readCurrentBrightnessInt();
        screenDimmed = false;
        if (verifyInt > 1) {
            File bak = new File(DIM_BACKUP_PATH);
            if (bak.exists() && !bak.delete()) System.err.println("OPALDESK_DIM_BACKUP_DELETE_WARN");
        } else {
            System.err.println("OPALDESK_SCREEN_DIM_RESTORE_UNVERIFIED keeping backup verifyInt=" + verifyInt);
        }
        System.out.println("OPALDESK_SCREEN_DIM=0 restoreInt=" + restoreInt + " verifyInt=" + verifyInt);
        return verifyInt > 1;
    }

    private void captureCurrentBrightnessLocked(float curFloat) {
        savedBrightMode = 0;
        savedBrightInt = 1024;
        savedBrightFloat = curFloat;
        try {
            savedBrightMode = Settings.System.getInt(services.context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            savedBrightInt = Settings.System.getInt(services.context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 1024);
        } catch (RuntimeException e) {
            String[] p = ShellCommand.runOutput("sh", "-c",
                    "printf '%s|%s' \"$(settings get system screen_brightness_mode)\" \"$(settings get system screen_brightness)\"")
                    .trim().split("\\|");
            if (p.length >= 1) savedBrightMode = parseIntOrDefault(p[0], 0);
            if (p.length >= 2) savedBrightInt = parseIntOrDefault(p[1], 1024);
        }
    }

    private int readCurrentBrightnessInt() {
        int v = -1;
        if (services.context != null) {
            try {
                v = Settings.System.getInt(services.context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_DIM_VERIFY_WARN: " + e.getMessage());
            }
        }
        return (v > 1) ? v : parseIntOrDefault(ShellCommand.runOutput("settings", "get", "system", "screen_brightness"), v);
    }

    private static boolean writeDimBackupAtomic(int mode, int brightInt, float brightFloat) {
        File bak = new File(DIM_BACKUP_PATH);
        try {
            AtomicFiles.writeAtomicUtf8(bak, mode + "|" + brightInt + "|" + brightFloat + "\n", AtomicFiles.MODE_OWNER_RW);
            return bak.exists() && bak.length() > 0;
        } catch (IOException | RuntimeException e) {
            System.err.println("OPALDESK_DIM_BACKUP_WRITE_ERR: " + e.getMessage());
            return false;
        }
    }

    // ---- Synchronized Wallpaper Cache ----

    @SuppressWarnings("deprecation")
    public byte[] getWallpaperBytes(boolean force) {
        synchronized (wallpaperLock) {
            Bitmap bmp = null;
            try {
                WallpaperManager wm = (WallpaperManager) services.context.getSystemService(Context.WALLPAPER_SERVICE);
                if (wm == null) return null;
                int wpId = 0;
                try { wpId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM); }
                catch (RuntimeException e) { System.err.println("OPALDESK_WP_ID_WARN: " + e.getMessage()); }
                if (!force && cachedWpBytes != null && wpId != 0 && wpId == cachedWpId) return cachedWpBytes;

                bmp = decodeWallpaperBitmap(wm);
                if (bmp == null) return cachedWpBytes;
                int bw = bmp.getWidth(), bh = bmp.getHeight();
                if (Math.max(bw, bh) > 1440) {
                    float sc = 1440f / Math.max(bw, bh);
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bw * sc)), Math.max(1, Math.round(bh * sc)), true);
                    if (scaled != bmp) { bmp.recycle(); bmp = scaled; }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                byte[] out = baos.toByteArray();
                if (out.length > 420_000) {
                    baos.reset();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                    out = baos.toByteArray();
                }
                cachedWpBytes = out;
                cachedWpId = wpId;
                return out;
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_WP_FETCH_ERR: " + e.getMessage());
                return cachedWpBytes;
            } finally {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static Bitmap decodeWallpaperBitmap(WallpaperManager wm) {
        try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
            if (pfd != null) {
                BitmapFactory.Options b = new BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, b);
                int sample = 1;
                while (Math.max(b.outWidth, b.outHeight) / sample > 1600) sample *= 2;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, o);
                if (bmp != null) return bmp;
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("OPALDESK_WP_PFD_WARN: " + e.getMessage());
        }
        try {
            Drawable dr = wm.getDrawable();
            if (dr == null) return null;
            int dw = Math.max(1, dr.getIntrinsicWidth()), dh = Math.max(1, dr.getIntrinsicHeight());
            float sc = Math.min(1f, 1280f / Math.max(dw, dh));
            int tw = Math.max(1, Math.round(dw * sc)), th = Math.max(1, Math.round(dh * sc));
            Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            dr.setBounds(0, 0, tw, th);
            dr.draw(new Canvas(bmp));
            return bmp;
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_WP_DRAWABLE_WARN: " + e.getMessage());
            return null;
        }
    }

    // ---- System Actions, Status & Battery ----

    @SuppressWarnings("deprecation")
    public boolean execSysAction(int act, int arg, String str) {
        lastSysCheckMs.set(0L);
        try {
            switch (act) {
                case BridgeProtocol.SYS_ACTION_SCREEN_DIM:
                    return setMainScreenDim(arg);
                case BridgeProtocol.SYS_ACTION_WIFI: {
                    WifiManager wm = (WifiManager) services.context.getSystemService(Context.WIFI_SERVICE);
                    boolean on = (arg < 0) ? (wm == null || !wm.isWifiEnabled()) : (arg == 1);
                    ShellCommand.runOutput("cmd", "wifi", "set-wifi-enabled", on ? "enabled" : "disabled");
                    return true;
                }
                case BridgeProtocol.SYS_ACTION_BT: {
                    BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                    boolean on = (arg < 0) ? (ba == null || !ba.isEnabled()) : (arg == 1);
                    ShellCommand.runOutput("cmd", "bluetooth_manager", on ? "enable" : "disable");
                    return true;
                }
                case BridgeProtocol.SYS_ACTION_AIRPLANE: {
                    boolean cur = Settings.Global.getInt(services.context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                    boolean on = (arg < 0) ? !cur : (arg == 1);
                    ShellCommand.runOutput("cmd", "connectivity", "airplane-mode", on ? "enable" : "disable");
                    return true;
                }
                case BridgeProtocol.SYS_ACTION_POWER_SAVE: {
                    PowerManager pm = (PowerManager) services.context.getSystemService(Context.POWER_SERVICE);
                    boolean on = (arg < 0) ? (pm == null || !pm.isPowerSaveMode()) : (arg == 1);
                    boolean done = false;
                    if (pm != null) {
                        try {
                            Object r = PowerManager.class.getMethod("setPowerSaveModeEnabled", boolean.class).invoke(pm, on);
                            done = !(r instanceof Boolean) || (Boolean) r;
                        } catch (NoSuchMethodException ignored) {
                        } catch (ReflectiveOperationException | RuntimeException e) {
                            System.err.println("OPALDESK_POWER_SAVE_WARN: " + e.getMessage());
                        }
                    }
                    if (!done) Settings.Global.putInt(services.context.getContentResolver(), "low_power", on ? 1 : 0);
                    return true;
                }
                case BridgeProtocol.SYS_ACTION_OPEN_SETTINGS: {
                    if (!PackagePolicy.isAllowedSettingsAction(str)) return false;
                    Intent i = new Intent(str).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(arg == 0 ? 0 : (config.vdId > 0 ? config.vdId : 0));
                    return services.startActivityAsShell(i, opts.toBundle());
                }
                default:
                    return false;
            }
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_SYS_ACTION_ERR act=" + act + ": " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public void writeSysStatusToParcel(Parcel reply) {
        if (reply == null) return;
        boolean wifiOn = false, btOn = false, airplaneOn = false, powerSaveOn = false;
        try {
            WifiManager wm = (WifiManager) services.context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) wifiOn = wm.isWifiEnabled();
        } catch (RuntimeException ignored) {}
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba != null) btOn = ba.isEnabled();
        } catch (RuntimeException ignored) {}
        try {
            airplaneOn = Settings.Global.getInt(services.context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (RuntimeException ignored) {}
        try {
            PowerManager pm = (PowerManager) services.context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) powerSaveOn = pm.isPowerSaveMode();
        } catch (RuntimeException ignored) {}

        refreshSysStringsIfNeeded(wifiOn, btOn);
        reply.writeInt(wifiOn ? 1 : 0);
        reply.writeString(cachedWifiSsid);
        reply.writeInt(btOn ? 1 : 0);
        reply.writeString(cachedBtName);
        reply.writeInt(airplaneOn ? 1 : 0);
        reply.writeInt(powerSaveOn ? 1 : 0);
        reply.writeInt(screenDimmed ? 1 : 0);
    }

    public int readBatteryPct() {
        try {
            Intent bat = (services.context != null)
                    ? services.context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)) : null;
            if (bat != null) {
                int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (lvl >= 0 && scale > 0) return Math.max(0, Math.min(100, Math.round(lvl * 100f / scale)));
            }
        } catch (RuntimeException ignored) {}
        return -1;
    }

    private void refreshSysStringsIfNeeded(boolean wifiOn, boolean btOn) {
        long now = SystemClock.uptimeMillis();
        long prev = lastSysCheckMs.get();
        if (now - prev < SYS_STATUS_CACHE_MS || !lastSysCheckMs.compareAndSet(prev, now)) return;

        String ssid = "";
        if (wifiOn) {
            String ws = ShellCommand.runOutput("cmd", "wifi", "status");
            int idx = ws.indexOf("Wifi is connected to \"");
            if (idx >= 0) {
                int s = idx + 22, e = ws.indexOf('"', s);
                if (e > s) ssid = ws.substring(s, e);
            }
        }
        cachedWifiSsid = ssid;

        String btDev = "";
        if (btOn) {
            for (String ln : ShellCommand.runOutput("dumpsys", "bluetooth_manager").split("\n")) {
                String t = ln.trim();
                if (t.contains("mActiveDevice:") && !t.endsWith("null")) {
                    int sp = t.indexOf('('), ep = t.indexOf(')', sp + 1);
                    if (sp >= 0 && ep > sp) { btDev = t.substring(sp + 1, ep).trim(); break; }
                }
                if (t.startsWith("Connected:") || t.contains("state=CONNECTED")) {
                    int idx = t.indexOf("name=");
                    if (idx >= 0) {
                        int end = t.indexOf(',', idx);
                        btDev = (end > idx ? t.substring(idx + 5, end) : t.substring(idx + 5)).trim();
                        break;
                    }
                }
            }
        }
        cachedBtName = btDev;
    }

    private static int parseIntOrDefault(String raw, int def) {
        if (raw == null) return def;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) { return def; }
    }
}

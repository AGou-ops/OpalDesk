package com.extbridge.daemon;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.WallpaperManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ExtBridge implements Runnable {
    private static final String CONF_PATH = "/data/local/tmp/extbridge.conf";
    private static final String APP_CONF_PATH = "/data/local/tmp/extbridge_apps.conf";
    private static final int VD_FLAGS_COMPAT = 0x44CB; // includes ROTATES_WITH_CONTENT (0x0080)
    private static final int VD_FLAGS_DEX_FIXED = 0x444B; // strips ROTATES_WITH_CONTENT -> lock landscape desktop!

    private static Context sCtx;
    private static DisplayManager sDm;
    private static InputManager sIm;
    private static Object sIIm;
    private static Object sWm;
    private static Object sAtm;
    private static Handler sHandler;
    private static IBinder sCtlBinder;
    private static volatile IBinder sHelperProcToken;

    private static Method sMInjectInput;
    private static Method sMSetDisplayId;
    private static Method sMGetCursorPos;
    private static Method sMAddAssoc;
    private static Method sMRemAssoc;
    private static Method sMReplaceContent;
    private static Method sMMirrorDisplay;
    private static Method sMStartActivity;
    private static Method sMSetUserPreferredMode;
    private static Object sSetUserPreferredTarget;
    private static int sSetUserPreferredArgCount = 0;

    private static volatile int sExtId = -1;
    private static volatile int sLastPushedExtId = -1;
    private static volatile int sVdId = -1;
    private static volatile String sVdUniqueId;
    private static volatile VirtualDisplay sVd;
    private static volatile Object sBbq;
    private static volatile Surface sSurf;
    private static volatile SurfaceTexture sSt;
    private static volatile Object sRootSc;
    private static volatile Object sBgSc;
    private static volatile Object sScaleSc;
    private static volatile Object sMirrorSc;
    private static volatile String sRenderMode = "NONE";

    private static volatile int sPhysW = 2560;
    private static volatile int sPhysH = 1440;
    private static volatile float sPhysFps = 60.0f;
    private static volatile int sVirtW = 2560;
    private static volatile int sVirtH = 1440;
    private static volatile int sVirtDpi = 240;
    private static volatile float sVirtFps = 60.0f;
    private static volatile int sVirtRot = 0; // 0=0°, 1=90° CW, 2=180°, 3=270° CW

    private static final Object sInputLock = new Object();
    private static final Object sCursorLock = new Object();
    private static final Object sSplitLock = new Object();
    private static final int EDGE_MARGIN_MIN_PX = 48;

    private static volatile int sInputToExt = 1;
    private static volatile int sLastExtInputCnt = -1;
    private static volatile String sLastExtInputSig = "";
    private static final ArrayList<String> sBoundDescs = new ArrayList<>();

    private static volatile float sCursorX = 1280f;
    private static volatile float sCursorY = 720f;
    private static volatile int sMouseButtons = 0;
    private static long sMouseDownTime = 0L;
    private static long sSideTouchDownTime = 0L;
    private static volatile int sLastInjectStatus = 1; // 1=ok, -1=SecurityException (INJECT_EVENTS denied), -2=other error
    private static volatile long sLastInjectDenyLogMs = 0L;
    private static volatile boolean sShuttingDown = false;
    private static volatile boolean sCleanupDoneOnShutdown = false;
    private static volatile boolean sForceRebuild = false;
    private static volatile boolean sSideVdAttempted = false;
    private static volatile int sLastSideVdAttemptMode = -1;

    private static volatile boolean sSplitActive = false;
    private static volatile float sSplitRatio = 0.50f;
    private static volatile String sSplitSlot0Pkg = "";
    private static volatile String sSplitSlot1Pkg = "";
    private static volatile int sSplitSlot0TaskId = -1;
    private static volatile int sSplitSlot1TaskId = -1;
    private static final int SPLIT_GAP_PX = 8;
    private static volatile int sMirrorMode = 0;       // 0=DeX独立桌面, 1=原生镜像
    private static volatile int sMirrorSideMode = 1;   // 0=纯黑原生镜像, 1=贴边信息条, 2=宽边栏+系统小组件
    private static volatile VirtualDisplay sSideVd;
    private static volatile int sSideVdId = -1;
    private static volatile Object sSideBbq;
    private static volatile Surface sSideSurf;
    private static volatile Object sSideMirSc;
    private static volatile Object sMir0Sc;
    private static volatile Object sMirCursorWrapSc;
    private static volatile Object sMirCursorSc;
    private static volatile Object sMirCursorBbq;
    private static volatile Surface sMirCursorSurf;
    private static volatile boolean sMirCursorVisible = false;
    private static volatile boolean sMirCursorLastDrawnPress = false;
    private static volatile int sMirDragDisplayId = -1;
    private static volatile Runnable sMirCursorPollTask;
    private static final Runnable sMirCursorAutoHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (sShuttingDown || sMirrorMode != 1 || sMirCursorWrapSc == null) return;
            if (sMirMouseCaptured || sMouseButtons != 0) {
                if (sHandler != null) sHandler.postDelayed(this, 3000L);
                return;
            }
            long idle = SystemClock.uptimeMillis() - sLastCursorMoveMs;
            if (idle >= 7800L) {
                updateMirrorCursorSurface(sCursorX, sCursorY, false, false);
            } else if (sHandler != null) {
                sHandler.postDelayed(this, Math.max(500L, 8000L - idle));
            }
        }
    };
    private static volatile long sLastTouchpadMs = 0L;
    private static volatile long sLastCursorMoveMs = 0L;
    private static volatile long sLastMirCapActionMs = 0L;
    private static volatile boolean sMirMouseCaptured = false;
    private static volatile boolean sJustExitedSideEdge = false;
    private static volatile long sMirEdgeCooldownUntilMs = 0L;
    private static volatile float sLastPollD0X = -1f;
    private static volatile float sLastPollD0Y = -1f;
    private static volatile boolean sHasLastPollD0 = false;
    private static volatile boolean sCachedExtMousePresent = false;
    private static volatile long sLastExtMouseCheckMs = 0L;
    private static volatile int sHelperUid = -1;

    private static Class<?> sScCls;
    private static Class<?> sScBuilderCls;
    private static Class<?> sTxCls;
    private static Constructor<?> sTxCtor;
    private static Method sTxSetLayer;
    private static Method sTxSetColor;
    private static Method sTxSetAlpha;
    private static Method sTxSetWindowCrop;
    private static Method sTxSetPosition;
    private static Method sTxSetScale;
    private static Method sTxSetMatrix;
    private static Method sTxShow;
    private static Method sTxHide;
    private static Method sTxReparent;
    private static Method sTxRemove;
    private static Method sTxApply;
    private static Method sTxApplySync;
    private static Method sTxClose;
    private static Method sScRelease;
    private static volatile int sD0W = 0;
    private static volatile int sD0H = 0;
    private static volatile int sMirX = 0;
    private static volatile int sMirY = 0;
    private static volatile int sMirW = 0;
    private static volatile int sMirH = 0;
    private static volatile DisplayManager.DisplayListener sMirrorDisplayListener;
    private static volatile long sLastMirTelemetryMs = 0L;
    private static volatile String sCachedAudioRoute = "平板外放";
    private static volatile String sCachedAudioDetail = "";
    private static volatile String sCachedMediaPkg = "";
    private static volatile int sCachedMediaState = 0;
    private static volatile String sCachedMediaTitle = "";
    private static volatile String sCachedMediaSub = "";
    private static volatile String sCachedCalendarText = "";
    private static volatile String sCachedBattDetail = "";
    private static volatile String sCachedWifiDetail = "";
    private static volatile int sFullscreenPolicy = 1; // 1=强制横屏大屏全屏策略, 0=兼容
    private static volatile boolean sHasUserConf = false;
    private static volatile boolean sSavedSysPrefs = false;
    private static int sOrigForceResizable = 0;
    private static int sOrigNonResizableMw = 0;
    private static final HashSet<String> sCompatOverriddenPkgs = new HashSet<>();
    private static final HashMap<String, Integer> sAppModes = new HashMap<>(); // 0=default, 1=land_full, 2=port_compat (freeform disabled on DeX)

    private static volatile int sCachedWpId = Integer.MIN_VALUE;
    private static volatile byte[] sCachedWpBytes = null;
    private static volatile long sLastSysCheckMs = 0L;
    private static volatile long sLastHomeCheckMs = 0L;
    private static volatile long sLastHomeSanitizeMs = 0L;
    private static volatile String sCachedWifiSsid = "";
    private static volatile String sCachedBtName = "";
    private static volatile boolean sScreenDimmed = false;
    private static int sSavedBrightMode = 0;
    private static int sSavedBrightInt = 100;
    private static float sSavedBrightFloat = -1f;

    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() == 0) {
                try {
                    android.system.Os.setgid(2000);
                    android.system.Os.setuid(2000);
                    System.out.println("EXTBRIDGE_ROOT_DROPPED_TO_SHELL uid=2000");
                } catch (Throwable t) {
                    System.err.println("drop root to shell warning: " + t);
                }
            }
            Looper.prepareMainLooper();
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("systemMain").invoke(null);
            Context sysCtx = (Context) atCls.getMethod("getSystemContext").invoke(at);
            try {
                sCtx = sysCtx.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable t) {
                System.err.println("createPackageContext failed: " + t);
                sCtx = sysCtx;
            }
            sCtx = fixShellContext(sCtx);
            try { runCmdOut("appops", "set", "com.extbridge.helper", "10021", "allow"); } catch (Throwable ignored) {}
            try { runCmdOut("appops", "set", "com.extbridge.helper", "ACCESS_RESTRICTED_SETTINGS", "allow"); } catch (Throwable ignored) {}
            sDm = (DisplayManager) sCtx.getSystemService(Context.DISPLAY_SERVICE);
            sIm = (InputManager) sCtx.getSystemService(Context.INPUT_SERVICE);

            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Method getSvc = smCls.getMethod("getService", String.class);

            IBinder wmB = (IBinder) getSvc.invoke(null, "window");
            sWm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, wmB);

            IBinder imB = (IBinder) getSvc.invoke(null, "input");
            sIIm = Class.forName("android.hardware.input.IInputManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, imB);

            IBinder atmB = (IBinder) getSvc.invoke(null, "activity_task");
            sAtm = Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, atmB);

            initReflection();
            loadConf();
            sanitizeSystemHomeTasks(true);

            sCtlBinder = new BridgeBinder();
            try {
                IntentFilter flt = new IntentFilter();
                flt.addAction("com.extbridge.daemon.NEED_BINDER");
                flt.addAction("com.extbridge.daemon.SHUTDOWN");
                sCtx.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (i == null) return;
                        String act = i.getAction();
                        if ("com.extbridge.daemon.NEED_BINDER".equals(act)) {
                            pushBinderToHelper();
                        } else if ("com.extbridge.daemon.SHUTDOWN".equals(act)) {
                            sShuttingDown = true;
                            cleanupDisplay(true);
                            new Thread(() -> { SystemClock.sleep(80); System.exit(0); }).start();
                        }
                    }
                }, flt, Context.RECEIVER_EXPORTED);
            } catch (Throwable ignored) {}

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                sShuttingDown = true;
                cleanupDisplay(true);
            }));

            System.out.println("EXTBRIDGE_V3_DAEMON_STARTED cfg=" + sVirtW + "x" + sVirtH + "@" + sVirtDpi + "dpi," + sVirtFps + "fps");
            System.out.flush();

            sHandler = new Handler(Looper.getMainLooper());
            grantAppWidgetBind();
            pushBinderToHelper();
            sHandler.post(new ExtBridge());
            Looper.loop();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void initReflection() {
        try {
            sMInjectInput = sIIm.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (Throwable t) {
            for (Method m : sIIm.getClass().getMethods()) {
                if (m.getName().equals("injectInputEvent")) { sMInjectInput = m; break; }
            }
        }
        try {
            sMSetDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        } catch (Throwable ignored) {}
        try {
            sMGetCursorPos = sIIm.getClass().getMethod("getCursorPositionInLogicalDisplay", int.class);
        } catch (Throwable ignored) {}
        try {
            sMAddAssoc = InputManager.class.getMethod("addUniqueIdAssociationByDescriptor", String.class, String.class);
            sMRemAssoc = InputManager.class.getMethod("removeUniqueIdAssociationByDescriptor", String.class);
        } catch (Throwable ignored) {}
        try {
            for (Method m : sWm.getClass().getMethods()) {
                if (m.getName().equals("replaceContentOnDisplay")) sMReplaceContent = m;
                else if (m.getName().equals("mirrorDisplay")) sMMirrorDisplay = m;
            }
        } catch (Throwable ignored) {}
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("startActivity") && m.getParameterCount() == 11) {
                    sMStartActivity = m;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        initUserPreferredModeReflection();
        initSurfaceReflection();
    }

    private static void initSurfaceReflection() {
        try {
            sScCls = Class.forName("android.view.SurfaceControl");
            sScBuilderCls = Class.forName("android.view.SurfaceControl$Builder");
            sTxCls = Class.forName("android.view.SurfaceControl$Transaction");
            sTxCtor = sTxCls.getConstructor();
            sTxSetLayer = sTxCls.getMethod("setLayer", sScCls, int.class);
            sTxSetColor = sTxCls.getMethod("setColor", sScCls, float[].class);
            sTxSetAlpha = sTxCls.getMethod("setAlpha", sScCls, float.class);
            sTxSetWindowCrop = sTxCls.getMethod("setWindowCrop", sScCls, int.class, int.class);
            sTxSetPosition = sTxCls.getMethod("setPosition", sScCls, float.class, float.class);
            sTxSetScale = sTxCls.getMethod("setScale", sScCls, float.class, float.class);
            sTxSetMatrix = sTxCls.getMethod("setMatrix", sScCls, float.class, float.class, float.class, float.class);
            sTxShow = sTxCls.getMethod("show", sScCls);
            sTxHide = sTxCls.getMethod("hide", sScCls);
            sTxReparent = sTxCls.getMethod("reparent", sScCls, sScCls);
            sTxRemove = sTxCls.getMethod("remove", sScCls);
            sTxApply = sTxCls.getMethod("apply");
            try { sTxApplySync = sTxCls.getMethod("apply", boolean.class); } catch (Throwable ignored) {}
            try { sTxClose = sTxCls.getMethod("close"); } catch (Throwable ignored) {}
            sScRelease = sScCls.getMethod("release");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static Object newSurfaceTx() throws Exception {
        if (sTxCtor == null) initSurfaceReflection();
        return sTxCtor.newInstance();
    }

    private static void closeSurfaceTx(Object tx) {
        if (tx == null) return;
        if (sTxClose != null) {
            try { sTxClose.invoke(tx); return; } catch (Throwable ignored) {}
        }
        if (tx instanceof AutoCloseable) {
            try { ((AutoCloseable) tx).close(); } catch (Throwable ignored) {}
        }
    }

    private static void initUserPreferredModeReflection() {
        // 1. DisplayManager (Android 17+ has 3-arg; some ROMs have 2-arg)
        try {
            sMSetUserPreferredMode = DisplayManager.class.getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class, boolean.class);
            sSetUserPreferredTarget = sDm;
            sSetUserPreferredArgCount = 3;
            System.out.println("EXTBRIDGE_PHYS_API=DisplayManager(3)");
            return;
        } catch (Throwable ignored) {}
        try {
            sMSetUserPreferredMode = DisplayManager.class.getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class);
            sSetUserPreferredTarget = sDm;
            sSetUserPreferredArgCount = 2;
            System.out.println("EXTBRIDGE_PHYS_API=DisplayManager(2)");
            return;
        } catch (Throwable ignored) {}

        // 2. DisplayManagerGlobal (Android 13-16 standard hidden API: 2-arg / 3-arg)
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgCls.getMethod("getInstance").invoke(null);
            if (dmg != null) {
                try {
                    sMSetUserPreferredMode = dmgCls.getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class);
                    sSetUserPreferredTarget = dmg;
                    sSetUserPreferredArgCount = 2;
                    System.out.println("EXTBRIDGE_PHYS_API=DisplayManagerGlobal(2)");
                    return;
                } catch (Throwable ignored) {}
                try {
                    sMSetUserPreferredMode = dmgCls.getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class, boolean.class);
                    sSetUserPreferredTarget = dmg;
                    sSetUserPreferredArgCount = 3;
                    System.out.println("EXTBRIDGE_PHYS_API=DisplayManagerGlobal(3)");
                    return;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // 3. IDisplayManager Binder proxy directly (2-arg / 3-arg)
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            IBinder dmB = (IBinder) smCls.getMethod("getService", String.class).invoke(null, "display");
            if (dmB != null) {
                Object idm = Class.forName("android.hardware.display.IDisplayManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, dmB);
                if (idm != null) {
                    try {
                        sMSetUserPreferredMode = idm.getClass().getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class);
                        sSetUserPreferredTarget = idm;
                        sSetUserPreferredArgCount = 2;
                        System.out.println("EXTBRIDGE_PHYS_API=IDisplayManager(2)");
                        return;
                    } catch (Throwable ignored) {}
                    try {
                        sMSetUserPreferredMode = idm.getClass().getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class, boolean.class);
                        sSetUserPreferredTarget = idm;
                        sSetUserPreferredArgCount = 3;
                        System.out.println("EXTBRIDGE_PHYS_API=IDisplayManager(3)");
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        System.err.println("EXTBRIDGE_PHYS_API=NONE (will fallback to cmd display)");
    }

    private static void loadConf() {
        try {
            File f = new File(CONF_PATH);
            if (!f.exists()) return;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line = br.readLine();
                if (line == null) return;
                String[] parts = line.trim().split(",");
                if (parts.length >= 4) {
                    int w = Integer.parseInt(parts[0]);
                    int h = Integer.parseInt(parts[1]);
                    int d = Integer.parseInt(parts[2]);
                    float fps = Float.parseFloat(parts[3]);
                    if (isValidDims(w, h) && d >= 80 && d <= 640) {
                        sVirtW = w;
                        sVirtH = h;
                        sVirtDpi = d;
                        sVirtFps = fps > 10f ? fps : 60f;
                        sCursorX = sVirtW * 0.5f;
                        sCursorY = sVirtH * 0.5f;
                        sHasUserConf = true;
                    }
                    if (parts.length >= 5) {
                        sFullscreenPolicy = Integer.parseInt(parts[4].trim()) != 0 ? 1 : 0;
                    }
                    if (parts.length >= 6) {
                        sVirtRot = normalizeRot(Integer.parseInt(parts[5].trim()));
                    }
                    if (parts.length >= 7) {
                        sMirrorMode = 0; // never boot into mirror lock or hijack Display 0 launcher
                    }
                    if (parts.length >= 8) {
                        int sm = Integer.parseInt(parts[7].trim());
                        if (sm >= 0 && sm <= 3) sMirrorSideMode = sm;
                    }
                }
            }
        } catch (Throwable ignored) {}
        loadAppModes();
    }

    private static final String HEAL_MARKER_PATH = "/data/local/tmp/extbridge_healed_v2";

    private static void loadAppModes() {
        File f = new File(APP_CONF_PATH);
        boolean firstConf = !f.exists();
        boolean prunedLegacyFreeform = false;
        synchronized (sAppModes) {
            sAppModes.clear();
            if (firstConf) {
                sAppModes.put("com.phoenix.read", 1);
                saveAppModesLocked();
            } else {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String t = line.trim();
                        int eq = t.indexOf('=');
                        if (eq <= 0) continue;
                        String pkg = t.substring(0, eq).trim();
                        int m = Integer.parseInt(t.substring(eq + 1).trim());
                        if (!pkg.isEmpty() && (m == 1 || m == 2)) {
                            sAppModes.put(pkg, m);
                        } else if (!pkg.isEmpty() && m == 3) {
                            prunedLegacyFreeform = true;
                        }
                    }
                } catch (Throwable ignored) {}
                if (prunedLegacyFreeform) {
                    saveAppModesLocked();
                }
            }
        }
        if (firstConf || !new File(HEAL_MARKER_PATH).exists()) {
            setGlobalIntSafe("force_resizable_activities", 0);
            setGlobalIntSafe("enable_non_resizable_multi_window", 0);
            healStrayCompatOverrides(false);
            try (FileWriter fw = new FileWriter(HEAL_MARKER_PATH, false)) {
                fw.write("1\n");
            } catch (Throwable ignored) {}
        }
    }

    private static void healStrayCompatOverrides(boolean resetConfiguredToo) {
        try {
            HashSet<String> toReset = new HashSet<>();
            toReset.add("com.tencent.mm");
            toReset.add("com.quark.browser");
            toReset.add("com.xunmeng.pinduoduo");
            toReset.add("com.ss.android.ugc.aweme");
            toReset.add("com.baidu.netdisk");
            String dump = runCmdOut("sh", "-c",
                    "dumpsys platform_compat 2>/dev/null | grep -E 'ChangeId\\((254631730|310816437|236283604|265464455|263259275);'");
            if (dump != null) {
                for (String line : dump.split("\n")) {
                    int idx = line.indexOf("packageOverrides={");
                    if (idx < 0) continue;
                    int start = idx + "packageOverrides={".length();
                    int end = line.indexOf('}', start);
                    if (end <= start) continue;
                    String inner = line.substring(start, end);
                    for (String kv : inner.split(",")) {
                        int eq = kv.indexOf('=');
                        if (eq <= 0) continue;
                        String pkg = kv.substring(0, eq).trim();
                        if (!pkg.isEmpty()) toReset.add(pkg);
                    }
                }
            }
            if (!resetConfiguredToo) {
                synchronized (sAppModes) {
                    for (Map.Entry<String, Integer> e : sAppModes.entrySet()) {
                        int m = e.getValue() == null ? 0 : e.getValue();
                        if (m == 1 || m == 2) toReset.remove(e.getKey());
                    }
                }
            }
            if (!toReset.isEmpty()) {
                StringBuilder cmd = new StringBuilder();
                for (String pkg : toReset) {
                    String qp = pkg.replace("'", "");
                    cmd.append("am compat reset-all --no-kill '").append(qp)
                       .append("' >/dev/null 2>&1 || am compat reset-all '").append(qp).append("' >/dev/null 2>&1; ");
                }
                runCmdOut("sh", "-c", cmd.toString());
            }
        } catch (Throwable ignored) {}
    }

    private static void saveAppModesLocked() {
        try (FileWriter fw = new FileWriter(APP_CONF_PATH, false)) {
            for (Map.Entry<String, Integer> e : sAppModes.entrySet()) {
                int m = e.getValue() == null ? 0 : e.getValue();
                if (m >= 1 && m <= 3) {
                    fw.write(e.getKey() + "=" + m + "\n");
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int getAppMode(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        synchronized (sAppModes) {
            Integer v = sAppModes.get(pkg);
            return v != null ? v : 0;
        }
    }

    private static void saveConf() {
        try (FileWriter fw = new FileWriter(CONF_PATH, false)) {
            int persistMode = 0;
            fw.write(sVirtW + "," + sVirtH + "," + sVirtDpi + "," + sVirtFps + "," + sFullscreenPolicy + "," + sVirtRot + "," + persistMode + "," + sMirrorSideMode + "\n");
        } catch (Throwable ignored) {}
    }

    @Override
    public void run() {
        if (sShuttingDown) return;
        try {
            tick();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        if (!sShuttingDown && sHandler != null) {
            sHandler.postDelayed(this, 1500);
        }
    }

    private static Display findExternalDisplay() {
        Display[] displays = sDm.getDisplays();
        if (displays == null) return null;
        for (Display d : displays) {
            if (d == null) continue;
            // Display.TYPE_EXTERNAL == 2
            if (getDisplayType(d) == 2) return d;
        }
        return null;
    }

    private static int getDisplayType(Display d) {
        try {
            return (Integer) Display.class.getMethod("getType").invoke(d);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean isHelperProcessRunning() {
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            IBinder amB = (IBinder) smCls.getMethod("getService", String.class).invoke(null, "activity");
            if (amB == null) return false;
            Object am = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, amB);
            if (am == null) return false;
            @SuppressWarnings("unchecked")
            List<ActivityManager.RunningAppProcessInfo> procs =
                    (List<ActivityManager.RunningAppProcessInfo>) am.getClass().getMethod("getRunningAppProcesses").invoke(am);
            if (procs == null) return false;
            for (ActivityManager.RunningAppProcessInfo info : procs) {
                if (info != null && "com.extbridge.helper".equals(info.processName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static synchronized void tick() {
        if ((sHelperProcToken == null || !sHelperProcToken.pingBinder()) && isHelperProcessRunning()) {
            pushBinderToHelper();
        }
        long nowMs = SystemClock.uptimeMillis();
        if (nowMs - sLastHomeCheckMs >= 3000L) {
            sLastHomeCheckMs = nowMs;
            sanitizeSystemHomeTasks(false);
        }
        Display ext = findExternalDisplay();
        if (ext == null) {
            sLastPushedExtId = -1;
            if (sExtId >= 0 || sVd != null) {
                System.out.println("EXTBRIDGE_DETACHED");
                cleanupDisplay(false);
            }
            return;
        }
        int extId = ext.getDisplayId();
        int prevPhysW = sPhysW;
        int prevPhysH = sPhysH;
        float prevPhysFps = sPhysFps;
        Display.Mode curMode = ext.getMode();
        if (curMode != null) {
            sPhysW = curMode.getPhysicalWidth();
            sPhysH = curMode.getPhysicalHeight();
            sPhysFps = curMode.getRefreshRate();
        }
        boolean physSizeChanged = (prevPhysW != sPhysW || prevPhysH != sPhysH);
        boolean physFpsChanged = Math.abs(prevPhysFps - sPhysFps) > 0.2f;

        if (sMirrorMode == 1) {
            if (sVd != null) {
                cleanupDisplay(false);
            }
            if (sForceRebuild) {
                sForceRebuild = false;
                sSideVdAttempted = false;
                cleanupDisplay(false);
            }
            if (physSizeChanged && (sMir0Sc != null || sSideVd != null)) {
                sSideVdAttempted = false;
                cleanupDisplay(false);
            }
            boolean needSide = (sMirrorSideMode > 0);
            boolean stockFallback = "NATIVE_MIRROR_STOCK".equals(sRenderMode);
            boolean sideRetryNeeded = needSide && sSideVd == null && (!sSideVdAttempted || sLastSideVdAttemptMode != sMirrorSideMode);
            boolean needAttachMirror = (extId != sExtId)
                    || (!stockFallback && (sRootSc == null || sMir0Sc == null || sideRetryNeeded || (!needSide && sSideVd != null)));
            if (needAttachMirror) {
                cleanupDisplay(false);
                attachMirrorDisplay(extId);
                sLastPushedExtId = extId;
            } else {
                boolean geomUpdated = false;
                if (!stockFallback) {
                    geomUpdated = updateMirrorGeometry(true);
                    reassert();
                }
                if ((physSizeChanged || physFpsChanged) && !geomUpdated) {
                    notifyHelperSync();
                }
                if (extId != sLastPushedExtId) {
                    sLastPushedExtId = extId;
                    if (sHelperProcToken == null || !sHelperProcToken.pingBinder()) {
                        pushBinderToHelper();
                    }
                }
            }
            return;
        }

        if (sMir0Sc != null || sSideVd != null || "NATIVE_MIRROR_STOCK".equals(sRenderMode)) {
            sSideVdAttempted = false;
            cleanupDisplay(false);
        }

        if (sForceRebuild && sVd != null) {
            sForceRebuild = false;
            cleanupDisplay(false);
        }

        if (extId != sExtId || sVd == null) {
            cleanupDisplay(false);
            attachDisplay(extId);
            sLastPushedExtId = extId;
        } else {
            if (physSizeChanged) {
                applySurfaceTransform();
            }
            if (physSizeChanged || physFpsChanged) {
                notifyHelperSync();
            }
            reassert();
            checkInput();
            if (sFullscreenPolicy == 1) {
                enforceRunningExtTasksFullscreen();
            }
            if (extId != sLastPushedExtId) {
                sLastPushedExtId = extId;
                if (sHelperProcToken == null || !sHelperProcToken.pingBinder()) {
                    pushBinderToHelper();
                }
            }
        }
    }

    private static boolean isValidDims(int w, int h) {
        return Math.max(w, h) >= 640 && Math.max(w, h) <= 7680
                && Math.min(w, h) >= 480 && Math.min(w, h) <= 4320;
    }

    private static int normalizeRot(int rot) {
        return ((rot % 4) + 4) % 4;
    }

    private static int computeAutoDpi(int w, int h) {
        int minEdge = Math.min(w, h);
        return Math.max(120, Math.min(280, Math.round(minEdge * 160f / 1440f)));
    }

    private static void attachDisplay(int extId) {
        try {
            if (!sHasUserConf && isValidDims(sPhysW, sPhysH)) {
                boolean physSwap = (normalizeRot(sVirtRot) & 1) == 1;
                sVirtW = physSwap ? sPhysH : sPhysW;
                sVirtH = physSwap ? sPhysW : sPhysH;
                sVirtDpi = computeAutoDpi(sVirtW, sVirtH);
                sVirtFps = (sPhysFps >= 20f && sPhysFps <= 240f) ? sPhysFps : 60f;
            }
            System.out.println("EXTBRIDGE_ATTACHING extId=" + extId + " phys=" + sPhysW + "x" + sPhysH + "@" + sPhysFps + " virt=" + sVirtW + "x" + sVirtH + "@" + sVirtDpi + " rot=" + (sVirtRot * 90));
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> bCls = Class.forName("android.view.SurfaceControl$Builder");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");

            Object bRoot = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bRoot, "ExtRoot");
            bCls.getMethod("setContainerLayer").invoke(bRoot);
            bCls.getMethod("setHidden", boolean.class).invoke(bRoot, false);
            sRootSc = bCls.getMethod("build").invoke(bRoot);

            Object bBg = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bBg, "ExtBg");
            bCls.getMethod("setColorLayer").invoke(bBg);
            bCls.getMethod("setParent", scCls).invoke(bBg, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bBg, false);
            sBgSc = bCls.getMethod("build").invoke(bBg);

            Object bScale = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bScale, "ExtScale");
            bCls.getMethod("setContainerLayer").invoke(bScale);
            bCls.getMethod("setParent", scCls).invoke(bScale, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bScale, false);
            sScaleSc = bCls.getMethod("build").invoke(bScale);

            boolean bbqOk = false;
            try {
                Object bMir = bCls.getConstructor().newInstance();
                bCls.getMethod("setName", String.class).invoke(bMir, "ExtDisplay");
                bCls.getMethod("setBLASTLayer").invoke(bMir);
                bCls.getMethod("setParent", scCls).invoke(bMir, sScaleSc);
                bCls.getMethod("setHidden", boolean.class).invoke(bMir, false);
                sMirrorSc = bCls.getMethod("build").invoke(bMir);

                Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
                Constructor<?> bbqCtor = bbqCls.getConstructor(String.class, boolean.class);
                sBbq = bbqCtor.newInstance("ExtBBQ", true);
                bbqCls.getMethod("update", scCls, int.class, int.class, int.class).invoke(sBbq, sMirrorSc, sVirtW, sVirtH, 1);
                sSurf = (Surface) bbqCls.getMethod("createSurface").invoke(sBbq);
                bbqOk = (sSurf != null);
                sRenderMode = "BBQ";
            } catch (Throwable t) {
                t.printStackTrace();
                if (sMirrorSc != null) {
                    try {
                        Object txErr = newSurfaceTx();
                        try { sTxRemove.invoke(txErr, sMirrorSc); sTxApply.invoke(txErr); } finally { closeSurfaceTx(txErr); }
                        sScRelease.invoke(sMirrorSc);
                    } catch (Throwable ignored) {}
                    sMirrorSc = null;
                }
                sRenderMode = "MIRROR";
            }

            if (!bbqOk) {
                sSt = new SurfaceTexture(0);
                sSt.setDefaultBufferSize(sVirtW, sVirtH);
                sSurf = new Surface(sSt);
            }

            if (sSurf != null && Build.VERSION.SDK_INT >= 30) {
                try {
                    sSurf.setFrameRate(sVirtFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                } catch (Throwable ignored) {}
            }

            // Build VirtualDisplayConfig
            Class<?> vdcBCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object vdcB = vdcBCls.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance("RedmiKPad-Extended", sVirtW, sVirtH, sVirtDpi);
            int vdFlags = (sFullscreenPolicy == 1) ? VD_FLAGS_DEX_FIXED : VD_FLAGS_COMPAT;
            vdcBCls.getMethod("setFlags", int.class).invoke(vdcB, vdFlags);
            try { vdcBCls.getMethod("setHomeSupported", boolean.class).invoke(vdcB, false); } catch (Throwable ignored) {}
            try { vdcBCls.getMethod("setRequestedRefreshRate", float.class).invoke(vdcB, sVirtFps); } catch (Throwable ignored) {}
            vdcBCls.getMethod("setSurface", Surface.class).invoke(vdcB, sSurf);
            Object vdc = vdcBCls.getMethod("build").invoke(vdcB);

            sVd = (VirtualDisplay) DisplayManager.class
                    .getMethod("createVirtualDisplay", Class.forName("android.hardware.display.VirtualDisplayConfig"))
                    .invoke(sDm, vdc);

            if (sVd == null || sVd.getDisplay() == null) {
                System.out.println("EXTBRIDGE_CREATE_VD_FAILED");
                cleanupDisplay(false);
                return;
            }
            Display vdDisp = sVd.getDisplay();
            sVdId = vdDisp.getDisplayId();
            try {
                sVdUniqueId = (String) Display.class.getMethod("getUniqueId").invoke(vdDisp);
            } catch (Throwable t) {
                sVdUniqueId = null;
            }

            applyDisplayFullscreenPolicy(sVdId, sFullscreenPolicy == 1);

            if (!bbqOk && sMMirrorDisplay != null) {
                sMirrorSc = mirrorDisplaySafe(sVdId);
                if (sMirrorSc != null) {
                    Object tx0 = txCls.getConstructor().newInstance();
                    txCls.getMethod("reparent", scCls, scCls).invoke(tx0, sMirrorSc, sScaleSc);
                    txCls.getMethod("apply").invoke(tx0);
                }
            }

            sExtId = extId;
            applySurfaceTransform();
            reassert();
            sCursorX = sVirtW * 0.5f;
            sCursorY = sVirtH * 0.5f;
            syncInput();
            launchLauncher();
            sanitizeSystemHomeTasks(false);
            pushBinderToHelper();
            if (sSplitActive && sHandler != null) {
                sHandler.postDelayed(() -> relayoutSplitTasks(false), 220L);
            }
            notifyHelperSync();

            System.out.println("EXTBRIDGE_ATTACHED_OK vdId=" + sVdId + " render=" + sRenderMode);
            System.out.flush();
        } catch (Throwable t) {
            t.printStackTrace();
            cleanupDisplay(false);
        }
    }

    private static Object mirrorDisplaySafe(int displayId) {
        if (sMMirrorDisplay == null || sWm == null) return null;
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            if (sMMirrorDisplay.getParameterCount() == 2) {
                Object outSc = scCls.getConstructor().newInstance();
                Object ok = sMMirrorDisplay.invoke(sWm, displayId, outSc);
                return (ok instanceof Boolean && (Boolean) ok) ? outSc : null;
            }
            return sMMirrorDisplay.invoke(sWm, displayId);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private static void registerMirrorDisplayListener() {
        if (sDm == null || sHandler == null || sMirrorDisplayListener != null) return;
        sMirrorDisplayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                if (displayId == 0 && sMirrorMode == 1 && !sShuttingDown && sHandler != null) {
                    sHandler.post(() -> {
                        if (sMirrorMode == 1 && sMir0Sc != null) {
                            updateMirrorGeometry(true);
                        }
                    });
                }
            }
        };
        try {
            sDm.registerDisplayListener(sMirrorDisplayListener, sHandler);
        } catch (Throwable t) {
            sMirrorDisplayListener = null;
        }
    }

    private static void unregisterMirrorDisplayListener() {
        if (sDm != null && sMirrorDisplayListener != null) {
            try { sDm.unregisterDisplayListener(sMirrorDisplayListener); } catch (Throwable ignored) {}
            sMirrorDisplayListener = null;
        }
    }

    private static void attachMirrorDisplay(int extId) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> bCls = Class.forName("android.view.SurfaceControl$Builder");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");

            Object mir0 = mirrorDisplaySafe(0);
            if (mir0 == null) {
                // Ultimate safety fallback to pure system mirror if mirrorDisplay(0) is unavailable on this ROM
                sExtId = extId;
                sVdId = -1;
                sSideVdId = -1;
                sRenderMode = "NATIVE_MIRROR_STOCK";
                try {
                    if (sWm != null && sMReplaceContent != null) {
                        sMReplaceContent.invoke(sWm, extId, null);
                    }
                } catch (Throwable ignored) {}
                pushBinderToHelper();
                notifyHelperSync();
                System.out.println("EXTBRIDGE_MIRROR_FALLBACK_STOCK extId=" + extId);
                return;
            }

            Object bRoot = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bRoot, "ExtRoot");
            bCls.getMethod("setContainerLayer").invoke(bRoot);
            bCls.getMethod("setHidden", boolean.class).invoke(bRoot, false);
            sRootSc = bCls.getMethod("build").invoke(bRoot);

            Object bBg = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bBg, "ExtBg");
            bCls.getMethod("setColorLayer").invoke(bBg);
            bCls.getMethod("setParent", scCls).invoke(bBg, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bBg, false);
            sBgSc = bCls.getMethod("build").invoke(bBg);

            sMir0Sc = mir0;
            Object txInit = newSurfaceTx();
            try {
                sTxReparent.invoke(txInit, sMir0Sc, sRootSc);
                sTxApply.invoke(txInit);
            } finally {
                closeSurfaceTx(txInit);
            }

            boolean sideReady = false;
            if (sMirrorSideMode > 0) {
                sSideVdAttempted = true;
                sLastSideVdAttemptMode = sMirrorSideMode;
                try {
                    Object bSide = bCls.getConstructor().newInstance();
                    bCls.getMethod("setName", String.class).invoke(bSide, "ExtMirrorSide");
                    bCls.getMethod("setBLASTLayer").invoke(bSide);
                    bCls.getMethod("setParent", scCls).invoke(bSide, sRootSc);
                    bCls.getMethod("setHidden", boolean.class).invoke(bSide, false);
                    sSideMirSc = bCls.getMethod("build").invoke(bSide);

                    Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
                    sSideBbq = bbqCls.getConstructor(String.class, boolean.class).newInstance("ExtSideBBQ", true);
                    bbqCls.getMethod("update", scCls, int.class, int.class, int.class)
                            .invoke(sSideBbq, sSideMirSc, sPhysW, sPhysH, 1);
                    sSideSurf = (Surface) bbqCls.getMethod("createSurface").invoke(sSideBbq);

                    if (sSideSurf != null) {
                        int sideDpi = Math.max(120, Math.min(360, Math.round(Math.min(sPhysW, sPhysH) * 200f / 1440f)));
                        Class<?> vdcBCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
                        Object vdcB = vdcBCls.getConstructor(String.class, int.class, int.class, int.class)
                                .newInstance("RedmiKPad-MirrorSide", sPhysW, sPhysH, sideDpi);
                        // PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | TRUSTED = 0x40B
                        vdcBCls.getMethod("setFlags", int.class).invoke(vdcB, 0x40B);
                        try { vdcBCls.getMethod("setHomeSupported", boolean.class).invoke(vdcB, false); } catch (Throwable ignored) {}
                        try { vdcBCls.getMethod("setRequestedRefreshRate", float.class).invoke(vdcB, sPhysFps > 10f ? sPhysFps : 60f); } catch (Throwable ignored) {}
                        vdcBCls.getMethod("setSurface", Surface.class).invoke(vdcB, sSideSurf);
                        Object vdc = vdcBCls.getMethod("build").invoke(vdcB);

                        sSideVd = (VirtualDisplay) DisplayManager.class
                                .getMethod("createVirtualDisplay", Class.forName("android.hardware.display.VirtualDisplayConfig"))
                                .invoke(sDm, vdc);
                        if (sSideVd != null && sSideVd.getDisplay() != null) {
                            sSideVdId = sSideVd.getDisplay().getDisplayId();
                            applyDisplayFullscreenPolicy(sSideVdId, false);
                            sideReady = true;
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                if (!sideReady) {
                    try { if (sSideVd != null) { sSideVd.release(); sSideVd = null; } } catch (Throwable ignored) {}
                    try { if (sSideBbq != null) { sSideBbq.getClass().getMethod("destroy").invoke(sSideBbq); sSideBbq = null; } } catch (Throwable ignored) {}
                    try { if (sSideSurf != null) { sSideSurf.release(); sSideSurf = null; } } catch (Throwable ignored) {}
                    if (sSideMirSc != null) {
                        try {
                            Object txC = newSurfaceTx();
                            try {
                                sTxRemove.invoke(txC, sSideMirSc);
                                sTxApply.invoke(txC);
                            } finally {
                                closeSurfaceTx(txC);
                            }
                            sScRelease.invoke(sSideMirSc);
                        } catch (Throwable ignored) {}
                        sSideMirSc = null;
                    }
                    sSideVdId = -1;
                }
            }

            sExtId = extId;
            sVdId = -1;
            sMirDragDisplayId = -1;
            sCursorX = sPhysW * 0.5f;
            sCursorY = sPhysH * 0.5f;
            sRenderMode = sideReady ? "MIRROR_SIDEBAR" : "MIRROR_COMP";
            initMirrorCursorOverlay(scCls, bCls);
            registerMirrorDisplayListener();
            updateMirrorGeometry(false);
            reassert();
            grantAppWidgetBind();
            startMirrorCursorPoller();
            pushBinderToHelper();
            notifyHelperSync();
            System.out.println("EXTBRIDGE_MIRROR_ATTACHED extId=" + extId + " sideVdId=" + sSideVdId
                    + " d0=" + sD0W + "x" + sD0H + " mir=" + sMirW + "x" + sMirH + "@" + sMirX + "," + sMirY);
            System.out.flush();
        } catch (Throwable t) {
            t.printStackTrace();
            cleanupDisplay(false);
        }
    }

    private static volatile long sLastMirCursorErrMs = 0L;

    private static void initMirrorCursorOverlay(Class<?> scCls, Class<?> bCls) {
        try {
            Object bWrap = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bWrap, "ExtMirCursorWrap");
            bCls.getMethod("setContainerLayer").invoke(bWrap);
            bCls.getMethod("setParent", scCls).invoke(bWrap, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bWrap, true);
            sMirCursorWrapSc = bCls.getMethod("build").invoke(bWrap);

            Object bCur = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bCur, "ExtMirCursor");
            bCls.getMethod("setBLASTLayer").invoke(bCur);
            bCls.getMethod("setParent", scCls).invoke(bCur, sMirCursorWrapSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bCur, false);
            sMirCursorSc = bCls.getMethod("build").invoke(bCur);

            Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
            sMirCursorBbq = bbqCls.getConstructor(String.class, boolean.class).newInstance("ExtMirCursorBBQ", true);
            bbqCls.getMethod("update", scCls, int.class, int.class, int.class)
                    .invoke(sMirCursorBbq, sMirCursorSc, 48, 48, 1);
            sMirCursorSurf = (Surface) bbqCls.getMethod("createSurface").invoke(sMirCursorBbq);
            sMirCursorVisible = false;
            sMirCursorLastDrawnPress = false;
            drawMirrorCursorBitmap(false);
            updateMirrorCursorSurface(sCursorX, sCursorY, false, false);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void drawMirrorCursorBitmap(boolean pressing) {
        Surface surf = sMirCursorSurf;
        if (surf == null || !surf.isValid()) return;
        Canvas c = null;
        try {
            c = surf.lockCanvas(null);
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            if (pressing) {
                Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                ring.setColor(0xAA38BDF8);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(2.6f);
                c.drawCircle(7f, 5f, 9f, ring);
            }
            Path arrow = new Path();
            arrow.moveTo(6f, 4f);
            arrow.lineTo(6f, 34f);
            arrow.lineTo(13.5f, 26.5f);
            arrow.lineTo(19f, 38f);
            arrow.lineTo(23.5f, 36f);
            arrow.lineTo(18f, 24.5f);
            arrow.lineTo(28f, 24.5f);
            arrow.close();

            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFF0F172A);
            fill.setStyle(Paint.Style.FILL);
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2.8f);
            stroke.setStrokeJoin(Paint.Join.ROUND);

            c.drawPath(arrow, fill);
            c.drawPath(arrow, stroke);
            sMirCursorLastDrawnPress = pressing;
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try { surf.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
            }
        }
    }

    private static void updateMirrorCursorSurface(float cx, float cy, boolean pressing, boolean show) {
        Object wrapSc = sMirCursorWrapSc;
        Object curSc = sMirCursorSc;
        if (sMirrorMode != 1 || curSc == null || wrapSc == null) return;
        Object tx = null;
        try {
            if (show && pressing != sMirCursorLastDrawnPress) {
                drawMirrorCursorBitmap(pressing);
            }
            float curScale = Math.max(0.8f, Math.min(2.2f, Math.min(sPhysW, sPhysH) / 1440f));
            tx = newSurfaceTx();
            sTxSetLayer.invoke(tx, wrapSc, 100);
            sTxSetMatrix.invoke(tx, wrapSc, curScale, 0f, 0f, curScale);
            sTxSetPosition.invoke(tx, wrapSc, cx - 6f * curScale, cy - 4f * curScale);
            sTxSetAlpha.invoke(tx, wrapSc, show ? 1.0f : 0.0f);
            sTxSetLayer.invoke(tx, curSc, 0);
            sTxSetWindowCrop.invoke(tx, curSc, 48, 48);
            sTxSetPosition.invoke(tx, curSc, 0f, 0f);
            sTxSetAlpha.invoke(tx, curSc, show ? 1.0f : 0.0f);
            if (show) {
                sTxShow.invoke(tx, curSc);
                sTxShow.invoke(tx, wrapSc);
                sMirCursorVisible = true;
                sLastCursorMoveMs = SystemClock.uptimeMillis();
                scheduleMirCursorAutoHide();
            } else {
                try {
                    sTxHide.invoke(tx, wrapSc);
                } catch (Throwable ignored) {}
                sMirCursorVisible = false;
            }
            sTxApply.invoke(tx);
        } catch (Throwable t) {
            long now = SystemClock.uptimeMillis();
            if (now - sLastMirCursorErrMs > 1000L) {
                sLastMirCursorErrMs = now;
                System.out.println("EXTBRIDGE_MIR_CUR_ERR " + t);
                t.printStackTrace(System.out);
                System.out.flush();
            }
        } finally {
            closeSurfaceTx(tx);
        }
    }

    private static void scheduleMirCursorAutoHide() {
        if (sHandler == null || sShuttingDown) return;
        sHandler.removeCallbacks(sMirCursorAutoHideRunnable);
        sHandler.postDelayed(sMirCursorAutoHideRunnable, 8000L);
    }

    private static boolean hasExternalCursorDevice() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastExtMouseCheckMs < 4000L) return sCachedExtMousePresent;
        sLastExtMouseCheckMs = now;
        boolean found = false;
        try {
            if (sIm != null) {
                for (int id : sIm.getInputDeviceIds()) {
                    InputDevice dev = sIm.getInputDevice(id);
                    if (dev != null && dev.isExternal() && !dev.isVirtual()) {
                        int src = dev.getSources();
                        if ((src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                                || (src & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                            found = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        sCachedExtMousePresent = found;
        return found;
    }

    private static float[] computeEdgeEntryPoint(float ptX, float ptY, float prevX, float prevY, boolean fromHoverSensor) {
        if (sMirrorMode != 1 || sMirrorSideMode <= 0 || sSideVdId <= 0 || sD0W <= 0 || sD0H <= 0 || sMirW <= 0 || sMirH <= 0) {
            return null;
        }
        float mappedX = Math.max(0f, Math.min(sPhysW - 1f, sMirX + ptX * (sMirW / (float) sD0W)));
        float mappedY = Math.max(0f, Math.min(sPhysH - 1f, sMirY + ptY * (sMirH / (float) sD0H)));
        // Horizontal wings
        if (sMirX >= EDGE_MARGIN_MIN_PX) {
            boolean hit = fromHoverSensor ? (ptX <= 4.5f) : (prevX > 2.5f && ptX <= 2.5f);
            if (hit) return new float[]{Math.max(0f, sMirX - 10f), mappedY};
        }
        int rightMargin = sPhysW - (sMirX + sMirW);
        if (rightMargin >= EDGE_MARGIN_MIN_PX) {
            boolean hit = fromHoverSensor ? (ptX >= sD0W - 5.5f) : (prevX < sD0W - 3.5f && ptX >= sD0W - 3.5f);
            if (hit) return new float[]{Math.min(sPhysW - 1f, sMirX + sMirW + 10f), mappedY};
        }
        // Vertical wings (when horizontal margin doesn't exist)
        if (sPhysW - sMirW < EDGE_MARGIN_MIN_PX) {
            if (sMirY >= EDGE_MARGIN_MIN_PX) {
                boolean hit = fromHoverSensor ? (ptY <= 4.5f) : (prevY > 2.5f && ptY <= 2.5f);
                if (hit) return new float[]{mappedX, Math.max(0f, sMirY - 10f)};
            }
            int bottomMargin = sPhysH - (sMirY + sMirH);
            if (bottomMargin >= EDGE_MARGIN_MIN_PX) {
                boolean hit = fromHoverSensor ? (ptY >= sD0H - 5.5f) : (prevY < sD0H - 3.5f && ptY >= sD0H - 3.5f);
                if (hit) return new float[]{mappedX, Math.min(sPhysH - 1f, sMirY + sMirH + 10f)};
            }
        }
        return null;
    }

    private static void notifyMirrorEdgeCapture(boolean enter, float cx, float cy) {
        try {
            if (sCtx == null || sShuttingDown) return;
            Intent i = new Intent("com.extbridge.helper.MIR_EDGE_CAPTURE");
            i.setClassName("com.extbridge.helper", "com.extbridge.helper.ControlReceiver");
            i.putExtra("enter", enter);
            i.putExtra("cx", cx);
            i.putExtra("cy", cy);
            sCtx.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    private static void startMirrorCursorPoller() {
        stopMirrorCursorPoller();
        if (sHandler == null || sMGetCursorPos == null || sIIm == null) return;
        sHasLastPollD0 = false;
        sMirCursorPollTask = new Runnable() {
            private long lastActiveMoveMs = SystemClock.uptimeMillis();
            @Override
            public void run() {
                if (sShuttingDown || sMirrorMode != 1 || sMirCursorSc == null || sMirCursorPollTask != this) return;
                int delayMs = 250;
                long now = SystemClock.uptimeMillis();
                if (sJustExitedSideEdge && now >= sMirEdgeCooldownUntilMs) {
                    sJustExitedSideEdge = false;
                }
                if (sMirMouseCaptured && sMouseButtons == 0 && sLastMirCapActionMs > 0 && now - sLastMirCapActionMs > 15000L) {
                    synchronized (sCursorLock) {
                        sMirMouseCaptured = false;
                    }
                }
                if (!sMirMouseCaptured && hasExternalCursorDevice() && sMirW > 0 && sMirH > 0 && sD0W > 0 && sD0H > 0) {
                    long idleMs = now - lastActiveMoveMs;
                    delayMs = (idleMs < 500L) ? 16 : (idleMs < 2000L ? 50 : 150);
                    try {
                        Object res = sMGetCursorPos.invoke(sIIm, 0);
                        if (res instanceof PointF) {
                            PointF pt = (PointF) res;
                            if (!Float.isNaN(pt.x) && !Float.isNaN(pt.y)
                                    && pt.x >= -1f && pt.y >= -1f && pt.x <= sD0W + 1f && pt.y <= sD0H + 1f) {
                                boolean moved = !sHasLastPollD0
                                        || Math.abs(pt.x - sLastPollD0X) > 0.25f
                                        || Math.abs(pt.y - sLastPollD0Y) > 0.25f;
                                if (moved) {
                                    lastActiveMoveMs = now;
                                    delayMs = 16;
                                    boolean firstSample = !sHasLastPollD0;
                                    float prevX = sLastPollD0X;
                                    float prevY = sLastPollD0Y;
                                    sLastPollD0X = pt.x;
                                    sLastPollD0Y = pt.y;
                                    sHasLastPollD0 = true;
                                    if (!firstSample && now - sLastTouchpadMs > 350L) {
                                        boolean awayFromEdge = (pt.x > 10f && pt.x < sD0W - 10f && pt.y > 10f && pt.y < sD0H - 10f);
                                        if (sJustExitedSideEdge && awayFromEdge) {
                                            sJustExitedSideEdge = false;
                                        }
                                        float[] entry = (now >= sMirEdgeCooldownUntilMs && !sJustExitedSideEdge)
                                                ? computeEdgeEntryPoint(pt.x, pt.y, prevX, prevY, false)
                                                : null;
                                        if (entry != null) {
                                            synchronized (sCursorLock) {
                                                sMirMouseCaptured = true;
                                                sLastMirCapActionMs = now;
                                                sCursorX = entry[0];
                                                sCursorY = entry[1];
                                            }
                                            updateMirrorCursorSurface(sCursorX, sCursorY, false, true);
                                            notifyMirrorEdgeCapture(true, entry[0], entry[1]);
                                        } else {
                                            float mx = sMirX + Math.max(0f, Math.min(sD0W, pt.x)) * (sMirW / (float) Math.max(1, sD0W));
                                            float my = sMirY + Math.max(0f, Math.min(sD0H, pt.y)) * (sMirH / (float) Math.max(1, sD0H));
                                            synchronized (sCursorLock) {
                                                sCursorX = Math.max(0f, Math.min(sPhysW - 1f, mx));
                                                sCursorY = Math.max(0f, Math.min(sPhysH - 1f, my));
                                            }
                                            updateMirrorCursorSurface(sCursorX, sCursorY, false, true);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                if (sHandler != null && !sShuttingDown && sMirrorMode == 1 && sMirCursorPollTask == this) {
                    sHandler.postDelayed(this, delayMs);
                }
            }
        };
        sHandler.postDelayed(sMirCursorPollTask, 80);
    }

    private static void stopMirrorCursorPoller() {
        Runnable r = sMirCursorPollTask;
        sMirCursorPollTask = null;
        if (r != null && sHandler != null) {
            try { sHandler.removeCallbacks(r); } catch (Throwable ignored) {}
        }
        if (sHandler != null) {
            try { sHandler.removeCallbacks(sMirCursorAutoHideRunnable); } catch (Throwable ignored) {}
        }
        sHasLastPollD0 = false;
        sMirMouseCaptured = false;
        sJustExitedSideEdge = false;
        sMirCursorVisible = false;
    }

    private static boolean updateMirrorGeometry(boolean notifyOnChange) {
        try {
            if (sRootSc == null || sBgSc == null || sMir0Sc == null || sDm == null) return false;
            Display d0 = sDm.getDisplay(0);
            int curD0W = 1920;
            int curD0H = 1200;
            if (d0 != null) {
                Point p = new Point();
                d0.getRealSize(p);
                if (p.x > 0 && p.y > 0) {
                    curD0W = p.x;
                    curD0H = p.y;
                }
            }

            float fit = Math.min(sPhysW / (float) curD0W, sPhysH / (float) curD0H);
            int mirW = Math.max(1, Math.round(curD0W * fit));
            int mirH = Math.max(1, Math.round(curD0H * fit));
            int mirX = Math.max(0, (sPhysW - mirW) / 2);
            int mirY = Math.max(0, (sPhysH - mirH) / 2);
            if (sPhysW - mirW >= 48) {
                if (sMirrorSideMode == 1) {
                    mirX = 0;
                } else if (sMirrorSideMode == 2) {
                    mirX = Math.max(0, sPhysW - mirW);
                }
            } else if (sPhysH - mirH >= 48) {
                if (sMirrorSideMode == 1) {
                    mirY = 0;
                } else if (sMirrorSideMode == 2) {
                    mirY = Math.max(0, sPhysH - mirH);
                }
            }

            boolean changed = (curD0W != sD0W || curD0H != sD0H
                    || mirW != sMirW || mirH != sMirH || mirX != sMirX || mirY != sMirY);
            if (!changed && notifyOnChange) return false;

            sD0W = curD0W;
            sD0H = curD0H;
            sMirW = mirW;
            sMirH = mirH;
            sMirX = mirX;
            sMirY = mirY;

            Object tx = newSurfaceTx();
            try {
                sTxSetLayer.invoke(tx, sBgSc, -1);
                float[] bgRgb = (sMirrorSideMode > 0) ? new float[]{0.025f, 0.035f, 0.065f} : new float[]{0f, 0f, 0f};
                sTxSetColor.invoke(tx, sBgSc, bgRgb);
                sTxSetAlpha.invoke(tx, sBgSc, 1.0f);
                sTxSetWindowCrop.invoke(tx, sBgSc, sPhysW, sPhysH);
                sTxShow.invoke(tx, sBgSc);

                if (sSideMirSc != null) {
                    sTxSetLayer.invoke(tx, sSideMirSc, 0);
                    sTxSetAlpha.invoke(tx, sSideMirSc, 1.0f);
                    sTxSetWindowCrop.invoke(tx, sSideMirSc, sPhysW, sPhysH);
                    sTxSetPosition.invoke(tx, sSideMirSc, 0f, 0f);
                    sTxShow.invoke(tx, sSideMirSc);
                }

                sTxSetLayer.invoke(tx, sMir0Sc, 1);
                sTxSetAlpha.invoke(tx, sMir0Sc, 1.0f);
                sTxSetScale.invoke(tx, sMir0Sc, fit, fit);
                sTxSetWindowCrop.invoke(tx, sMir0Sc, curD0W, curD0H);
                sTxSetPosition.invoke(tx, sMir0Sc, (float) mirX, (float) mirY);
                sTxShow.invoke(tx, sMir0Sc);

                sTxSetWindowCrop.invoke(tx, sRootSc, sPhysW, sPhysH);
                sTxApply.invoke(tx);
            } finally {
                closeSurfaceTx(tx);
            }

            if (notifyOnChange && changed) {
                notifyHelperSync();
            }
            return changed;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    private static void applySurfaceTransform() {
        Object tx = null;
        try {
            if (sMirrorSc == null || sBgSc == null || sRootSc == null) return;
            tx = newSurfaceTx();

            sTxSetLayer.invoke(tx, sBgSc, 0);
            sTxSetColor.invoke(tx, sBgSc, new float[]{0f, 0f, 0f});
            sTxSetAlpha.invoke(tx, sBgSc, 1.0f);
            sTxSetWindowCrop.invoke(tx, sBgSc, sPhysW, sPhysH);
            sTxShow.invoke(tx, sBgSc);

            int rot = normalizeRot(sVirtRot);
            int rotW = ((rot & 1) == 1) ? sVirtH : sVirtW;
            int rotH = ((rot & 1) == 1) ? sVirtW : sVirtH;
            float sx = sPhysW / (float) Math.max(1, rotW);
            float sy = sPhysH / (float) Math.max(1, rotH);
            float scale = Math.min(sx, sy);

            Matrix mat = new Matrix();
            mat.setRotate(rot * 90f);
            mat.postScale(scale, scale);
            RectF bounds = new RectF(0f, 0f, sVirtW, sVirtH);
            mat.mapRect(bounds);
            float posX = (sPhysW - bounds.width()) * 0.5f - bounds.left;
            float posY = (sPhysH - bounds.height()) * 0.5f - bounds.top;
            float[] mv = new float[9];
            mat.getValues(mv);

            sTxSetLayer.invoke(tx, sScaleSc, 1);
            sTxSetMatrix.invoke(tx, sScaleSc, mv[Matrix.MSCALE_X], mv[Matrix.MSKEW_Y], mv[Matrix.MSKEW_X], mv[Matrix.MSCALE_Y]);
            sTxSetPosition.invoke(tx, sScaleSc, posX, posY);
            sTxShow.invoke(tx, sScaleSc);

            sTxSetLayer.invoke(tx, sMirrorSc, 0);
            sTxSetAlpha.invoke(tx, sMirrorSc, 1.0f);
            sTxSetWindowCrop.invoke(tx, sMirrorSc, sVirtW, sVirtH);
            sTxSetPosition.invoke(tx, sMirrorSc, 0f, 0f);
            sTxShow.invoke(tx, sMirrorSc);

            sTxSetWindowCrop.invoke(tx, sRootSc, sPhysW, sPhysH);
            sTxApply.invoke(tx);
            System.out.println("EXTBRIDGE_TRANSFORM virt=" + sVirtW + "x" + sVirtH + " rot=" + (rot * 90) + " phys=" + sPhysW + "x" + sPhysH + " scale=" + scale + " pos=" + posX + "," + posY);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            closeSurfaceTx(tx);
        }
    }

    private static void reassert() {
        Object tx = null;
        try {
            if (sExtId <= 0 || sRootSc == null || sWm == null || sMReplaceContent == null) return;
            sMReplaceContent.invoke(sWm, sExtId, sRootSc);
            tx = newSurfaceTx();
            sTxSetLayer.invoke(tx, sRootSc, Integer.MAX_VALUE);
            sTxSetPosition.invoke(tx, sRootSc, 0f, 0f);
            sTxShow.invoke(tx, sRootSc);
            sTxApply.invoke(tx);
        } catch (Throwable ignored) {
        } finally {
            closeSurfaceTx(tx);
        }
    }

    private static synchronized void cleanupDisplay(boolean unbindWm) {
        if (unbindWm && sShuttingDown && sCleanupDoneOnShutdown) {
            return;
        }
        if (unbindWm && sShuttingDown) {
            sCleanupDoneOnShutdown = true;
        }
        stopMirrorCursorPoller();
        unregisterMirrorDisplayListener();
        unbindInput();
        boolean extUnplugged = (findExternalDisplay() == null);
        if (unbindWm || sShuttingDown || sMirrorMode == 1 || extUnplugged) {
            synchronized (sSplitLock) {
                clearSplitState();
            }
        }
        if (unbindWm || sShuttingDown || extUnplugged) {
            restoreGlobalFullscreenPrefs();
            resetAppFullscreenCompatAll();
            if (sScreenDimmed) {
                setMainScreenDim(0);
            }
        }
        if (extUnplugged) {
            sSideVdAttempted = false;
        }
        int prevExtId = sExtId;
        try {
            if (unbindWm && prevExtId > 0 && sWm != null && sMReplaceContent != null) {
                try { sMReplaceContent.invoke(sWm, prevExtId, null); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            if (sVd != null) { sVd.release(); sVd = null; }
        } catch (Throwable ignored) {}
        try {
            if (sSideVd != null) { sSideVd.release(); sSideVd = null; }
        } catch (Throwable ignored) {}
        try {
            if (sBbq != null) {
                sBbq.getClass().getMethod("destroy").invoke(sBbq);
                sBbq = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (sSideBbq != null) {
                sSideBbq.getClass().getMethod("destroy").invoke(sSideBbq);
                sSideBbq = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (sMirCursorBbq != null) {
                sMirCursorBbq.getClass().getMethod("destroy").invoke(sMirCursorBbq);
                sMirCursorBbq = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (sSurf != null) { sSurf.release(); sSurf = null; }
        } catch (Throwable ignored) {}
        try {
            if (sSideSurf != null) { sSideSurf.release(); sSideSurf = null; }
        } catch (Throwable ignored) {}
        try {
            if (sMirCursorSurf != null) { sMirCursorSurf.release(); sMirCursorSurf = null; }
        } catch (Throwable ignored) {}
        try {
            if (sSt != null) { sSt.release(); sSt = null; }
        } catch (Throwable ignored) {}
        Object tx = null;
        try {
            tx = newSurfaceTx();
            Object[] scs = new Object[]{sMirCursorSc, sMirCursorWrapSc, sMir0Sc, sSideMirSc, sMirrorSc, sScaleSc, sBgSc, sRootSc};
            for (Object sc : scs) {
                if (sc != null) {
                    try { sTxReparent.invoke(tx, sc, null); } catch (Throwable ignored) {}
                    try { sTxRemove.invoke(tx, sc); } catch (Throwable ignored) {}
                }
            }
            sMirCursorSc = null;
            sMirCursorWrapSc = null;
            sMir0Sc = null;
            sSideMirSc = null;
            sMirrorSc = null;
            sScaleSc = null;
            sBgSc = null;
            sRootSc = null;
            if (sTxApplySync != null) {
                try { sTxApplySync.invoke(tx, true); } catch (Throwable ignored) { sTxApply.invoke(tx); }
            } else {
                sTxApply.invoke(tx);
            }
            for (Object sc : scs) {
                if (sc != null && sScRelease != null) {
                    try { sScRelease.invoke(sc); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        } finally {
            closeSurfaceTx(tx);
        }
        sExtId = -1;
        sVdId = -1;
        sSideVdId = -1;
        sMirW = 0;
        sMirH = 0;
        sVdUniqueId = null;
        sRenderMode = "NONE";
    }

    private static volatile int sLastPhysResult = 0; // 0=ok/unchanged, 1=switched, -1=edid unsupported, -2=invoke failed, -3=system locked physical mode

    private static boolean invokeSetUserPreferredMode(int extDisplayId, Display.Mode chosenMode) {
        if (chosenMode == null) return false;
        if (sMSetUserPreferredMode != null && sSetUserPreferredTarget != null) {
            try {
                if (sSetUserPreferredArgCount == 3) {
                    sMSetUserPreferredMode.invoke(sSetUserPreferredTarget, extDisplayId, chosenMode, false);
                } else {
                    sMSetUserPreferredMode.invoke(sSetUserPreferredTarget, extDisplayId, chosenMode);
                }
                return true;
            } catch (Throwable t) {
                System.err.println("EXTBRIDGE_PHYS_INVOKE_FAILED: " + t);
                t.printStackTrace();
            }
        }
        try {
            String w = String.valueOf(chosenMode.getPhysicalWidth());
            String h = String.valueOf(chosenMode.getPhysicalHeight());
            String fps = String.format(java.util.Locale.US, "%.2f", chosenMode.getRefreshRate());
            String did = String.valueOf(extDisplayId);
            String out = runCmdOut("cmd", "display", "set-user-preferred-display-mode", w, h, fps, did, "false");
            if (out != null && out.toLowerCase().contains("error")) {
                out = runCmdOut("cmd", "display", "set-user-preferred-display-mode", w, h, fps, did);
            }
            System.out.println("EXTBRIDGE_PHYS_CMD_OUT=" + (out == null ? "" : out.trim()));
            return out == null || !out.toLowerCase().contains("error");
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_PHYS_CMD_FAILED: " + t);
        }
        return false;
    }

    private static synchronized void syncPhysicalModeIfChanged() {
        Display ext = findExternalDisplay();
        if (ext == null || ext.getDisplayId() != sExtId) return;
        Display.Mode curMode = ext.getMode();
        if (curMode == null) return;
        int cw = curMode.getPhysicalWidth();
        int ch = curMode.getPhysicalHeight();
        float cf = curMode.getRefreshRate();
        boolean sizeChanged = (cw != sPhysW || ch != sPhysH);
        boolean fpsChanged = Math.abs(cf - sPhysFps) > 0.2f;
        if (sizeChanged || fpsChanged) {
            sPhysW = cw;
            sPhysH = ch;
            sPhysFps = cf;
            if (sizeChanged) {
                applySurfaceTransform();
            }
            reassert();
            notifyHelperSync();
        }
    }

    private static synchronized boolean applyConfig(int reqW, int reqH, int reqDpi, int reqFpsX100, int physModeId, int reqRot) {
        try {
            sLastPhysResult = 0;
            if (!isValidDims(reqW, reqH) || reqDpi < 80 || reqDpi > 640) {
                return false;
            }
            float fps = reqFpsX100 > 0 ? (reqFpsX100 / 100.0f) : sVirtFps;
            sVirtW = reqW;
            sVirtH = reqH;
            sVirtDpi = reqDpi;
            sVirtFps = fps;
            if (reqRot >= 0) {
                sVirtRot = normalizeRot(reqRot);
            }
            sHasUserConf = true;
            saveConf();

            // 1. Apply physical external display mode if supported & requested
            Display ext = findExternalDisplay();
            if (ext != null) {
                Display.Mode chosenMode = null;
                Display.Mode[] modes = ext.getSupportedModes();
                if (modes != null && modes.length > 0) {
                    if (physModeId > 0) {
                        for (Display.Mode m : modes) {
                            if (m.getModeId() == physModeId) { chosenMode = m; break; }
                        }
                    } else if (reqFpsX100 > 0) {
                        int matchW = ((sVirtRot & 1) == 1) ? reqH : reqW;
                        int matchH = ((sVirtRot & 1) == 1) ? reqW : reqH;
                        float bestScore = Float.MAX_VALUE;
                        for (Display.Mode m : modes) {
                            float diff = Math.abs(m.getRefreshRate() - fps);
                            if (diff > 2.5f) continue;
                            int mw = m.getPhysicalWidth();
                            int mh = m.getPhysicalHeight();
                            boolean resMatch = (mw == matchW && mh == matchH) || (mw == matchH && mh == matchW);
                            long area = (long) mw * (long) mh;
                            float areaBias = Math.max(0f, 4.0f - (area / 2073600f));
                            float score = diff + (resMatch ? 0f : (5.0f + areaBias));
                            if (score < bestScore) {
                                bestScore = score;
                                chosenMode = m;
                            }
                        }
                        if (chosenMode == null) {
                            sLastPhysResult = -1;
                            System.out.println("EXTBRIDGE_PHYS_SKIP no EDID mode near " + fps + "Hz for " + matchW + "x" + matchH);
                        }
                    }
                    if (chosenMode != null) {
                        boolean invoked = invokeSetUserPreferredMode(ext.getDisplayId(), chosenMode);
                        if (invoked) {
                            SystemClock.sleep(120);
                            Display extNow = findExternalDisplay();
                            Display.Mode cur = (extNow != null) ? extNow.getMode() : null;
                            if (cur != null) {
                                sPhysW = cur.getPhysicalWidth();
                                sPhysH = cur.getPhysicalHeight();
                                sPhysFps = cur.getRefreshRate();
                            }
                            boolean curMatched = cur == null
                                    || (((cur.getPhysicalWidth() == chosenMode.getPhysicalWidth()
                                            && cur.getPhysicalHeight() == chosenMode.getPhysicalHeight())
                                        || (cur.getPhysicalWidth() == chosenMode.getPhysicalHeight()
                                            && cur.getPhysicalHeight() == chosenMode.getPhysicalWidth()))
                                        && Math.abs(cur.getRefreshRate() - chosenMode.getRefreshRate()) <= 2.5f);
                            sLastPhysResult = curMatched ? 1 : -3;
                            if (sHandler != null) {
                                sHandler.postDelayed(ExtBridge::syncPhysicalModeIfChanged, 250);
                                sHandler.postDelayed(ExtBridge::syncPhysicalModeIfChanged, 700);
                            }
                            System.out.println("EXTBRIDGE_PHYS_REQ mode=" + chosenMode.getModeId()
                                    + " target=" + chosenMode.getPhysicalWidth() + "x" + chosenMode.getPhysicalHeight() + "@" + chosenMode.getRefreshRate()
                                    + " actual=" + sPhysW + "x" + sPhysH + "@" + sPhysFps + " res=" + sLastPhysResult);
                        } else {
                            sLastPhysResult = -2;
                        }
                    }
                }
            }

            // 2. Live resize VirtualDisplay + BBQ + hardware scale without tearing down running apps!
            liveResizeOrRebuild();
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    private static synchronized int setRotation(int reqRot) {
        int next = (reqRot < 0) ? ((sVirtRot + 1) % 4) : normalizeRot(reqRot);
        boolean wantPortrait = (next & 1) == 1;
        if (wantPortrait && sVirtW > sVirtH) {
            int tmp = sVirtW;
            sVirtW = sVirtH;
            sVirtH = tmp;
        } else if (!wantPortrait && sVirtH > sVirtW) {
            int tmp = sVirtW;
            sVirtW = sVirtH;
            sVirtH = tmp;
        }
        sVirtRot = next;
        sHasUserConf = true;
        saveConf();
        liveResizeOrRebuild();
        return sVirtRot;
    }

    private static void liveResizeOrRebuild() {
        if (sVd != null && "BBQ".equals(sRenderMode) && sMirrorSc != null) {
            try {
                Class<?> scCls = Class.forName("android.view.SurfaceControl");
                Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
                Object oldBbq = sBbq;
                Surface oldSurf = sSurf;

                Object newBbq = bbqCls.getConstructor(String.class, boolean.class).newInstance("ExtBBQ", true);
                bbqCls.getMethod("update", scCls, int.class, int.class, int.class).invoke(newBbq, sMirrorSc, sVirtW, sVirtH, 1);
                Surface newSurf = (Surface) bbqCls.getMethod("createSurface").invoke(newBbq);
                if (newSurf != null && Build.VERSION.SDK_INT >= 30) {
                    try { newSurf.setFrameRate(sVirtFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE); } catch (Throwable ignored) {}
                }
                sVd.resize(sVirtW, sVirtH, sVirtDpi);
                sVd.setSurface(newSurf);

                sBbq = newBbq;
                sSurf = newSurf;
                if (oldSurf != null) try { oldSurf.release(); } catch (Throwable ignored) {}
                if (oldBbq != null) try { bbqCls.getMethod("destroy").invoke(oldBbq); } catch (Throwable ignored) {}

                applySurfaceTransform();
                if (sHandler != null) sHandler.postDelayed(ExtBridge::applySurfaceTransform, 150);
                sCursorX = Math.min(sCursorX, sVirtW - 1f);
                sCursorY = Math.min(sCursorY, sVirtH - 1f);
                reassert();
                if (sSplitActive) {
                    if (sHandler != null) {
                        sHandler.postDelayed(() -> relayoutSplitTasks(false), 300L);
                        sHandler.postDelayed(() -> relayoutSplitTasks(false), 900L);
                        sHandler.postDelayed(() -> relayoutSplitTasks(false), 1850L);
                    } else {
                        relayoutSplitTasks(false);
                    }
                }
                notifyHelperSync();
                System.out.println("EXTBRIDGE_RESIZED " + sVirtW + "x" + sVirtH + "@" + sVirtDpi + "fps=" + sVirtFps + " rot=" + (sVirtRot * 90));
                return;
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        sForceRebuild = true;
        notifyHelperSync();
    }

    private static void unbindInput() {
        synchronized (sInputLock) {
            try {
                if (sIm == null || sMRemAssoc == null) return;
                for (String desc : sBoundDescs) {
                    try { sMRemAssoc.invoke(sIm, desc); } catch (Throwable ignored) {}
                }
                sBoundDescs.clear();
            } catch (Throwable ignored) {}
        }
    }

    private static void syncInput() {
        synchronized (sInputLock) {
            try {
                unbindInput();
                if (sExtId < 0 || sVdUniqueId == null || sIm == null) return;
                int[] ids = sIm.getInputDeviceIds();
                int extCnt = 0;
                ArrayList<String> descs = new ArrayList<>();
                for (int id : ids) {
                    InputDevice dev = sIm.getInputDevice(id);
                    if (dev == null || !dev.isExternal() || dev.isVirtual()) continue;
                    extCnt++;
                    String desc = dev.getDescriptor();
                    if (desc != null) descs.add(desc);
                    if (sInputToExt != 0 && sMAddAssoc != null && desc != null) {
                        try {
                            sMAddAssoc.invoke(sIm, desc, sVdUniqueId);
                            sBoundDescs.add(desc);
                        } catch (Throwable ignored) {}
                    }
                }
                java.util.Collections.sort(descs);
                sLastExtInputSig = String.join("|", descs);
                sLastExtInputCnt = extCnt;
                System.out.println("EXTBRIDGE_INPUT_MODE=" + sInputToExt + " count=" + extCnt);
                notifyHelperSync();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static void checkInput() {
        try {
            if (sInputToExt == 0 || sIm == null) return;
            int[] ids = sIm.getInputDeviceIds();
            int extCnt = 0;
            ArrayList<String> descs = new ArrayList<>();
            for (int id : ids) {
                InputDevice dev = sIm.getInputDevice(id);
                if (dev != null && dev.isExternal() && !dev.isVirtual()) {
                    extCnt++;
                    String desc = dev.getDescriptor();
                    if (desc != null) descs.add(desc);
                }
            }
            java.util.Collections.sort(descs);
            String sig = String.join("|", descs);
            if (extCnt != sLastExtInputCnt || !sig.equals(sLastExtInputSig)) {
                syncInput();
            }
        } catch (Throwable ignored) {}
    }

    private static boolean invokeInjectInput(InputEvent ev, int targetDisplayId) {
        if (ev == null || targetDisplayId < 0 || sIIm == null || sMInjectInput == null) return false;
        try {
            if (sMSetDisplayId != null) sMSetDisplayId.invoke(ev, targetDisplayId);
            Object res = sMInjectInput.invoke(sIIm, ev, 0);
            boolean ok = !(res instanceof Boolean) || (Boolean) res;
            if (ok) sLastInjectStatus = 1;
            return ok;
        } catch (Throwable t) {
            Throwable root = (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
            if (root instanceof SecurityException) {
                sLastInjectStatus = -1;
                long now = SystemClock.uptimeMillis();
                if (now - sLastInjectDenyLogMs > 5000L) {
                    sLastInjectDenyLogMs = now;
                    System.err.println("EXTBRIDGE_INJECT_SECURITY_DENIED: " + root.getMessage());
                }
            } else {
                sLastInjectStatus = -2;
                t.printStackTrace();
            }
            return false;
        }
    }

    private static void injectKey(int keyCode, int metaState) {
        try {
            int dispId = (sMirrorMode == 1) ? 0 : sVdId;
            if (dispId < 0 || sIIm == null || sMInjectInput == null) return;
            long now = SystemClock.uptimeMillis();
            KeyEvent kd = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            KeyEvent ku = new KeyEvent(now, now + 15, KeyEvent.ACTION_UP, keyCode, 0, metaState, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            invokeInjectInput(kd, dispId);
            invokeInjectInput(ku, dispId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void injectAscii(String text) {
        try {
            int dispId = (sMirrorMode == 1) ? 0 : sVdId;
            if (dispId < 0 || sIIm == null || sMInjectInput == null || text == null || text.isEmpty()) return;
            KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            KeyEvent[] evs = kcm.getEvents(text.toCharArray());
            if (evs == null) return;
            for (KeyEvent e : evs) {
                invokeInjectInput(e, dispId);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static boolean isPointInMirrorMain(float cx, float cy) {
        return sMirW > 0 && sMirH > 0
                && cx >= sMirX && cx < (sMirX + sMirW)
                && cy >= sMirY && cy < (sMirY + sMirH);
    }

    private static int resolveMirrorTargetDisplay(float cx, float cy) {
        if (isPointInMirrorMain(cx, cy)) return 0;
        return (sSideVdId > 0) ? sSideVdId : 0;
    }

    private static float mapMirrorX(int targetDisp, float cx) {
        if (targetDisp == 0 && sMirW > 0 && sD0W > 0) {
            return Math.max(0f, Math.min(sD0W - 1f, (cx - sMirX) * sD0W / (float) sMirW));
        }
        return Math.max(0f, Math.min(sPhysW - 1f, cx));
    }

    private static float mapMirrorY(int targetDisp, float cy) {
        if (targetDisp == 0 && sMirH > 0 && sD0H > 0) {
            return Math.max(0f, Math.min(sD0H - 1f, (cy - sMirY) * sD0H / (float) sMirH));
        }
        return Math.max(0f, Math.min(sPhysH - 1f, cy));
    }

    private static void injectMouseAction(int type, float a1, float a2) {
        synchronized (sCursorLock) {
            try {
                boolean mirror = (sMirrorMode == 1);
                if ((!mirror && sVdId <= 0) || (mirror && (sPhysW <= 0 || sPhysH <= 0))) return;
                if (sIIm == null || sMInjectInput == null) return;
                sLastTouchpadMs = SystemClock.uptimeMillis();
                float maxX = mirror ? (sPhysW - 1f) : (sVirtW - 1f);
                float maxY = mirror ? (sPhysH - 1f) : (sVirtH - 1f);
                long now = SystemClock.uptimeMillis();
                switch (type) {
                    case 0: { // MOVE_REL
                        sCursorX = Math.max(0f, Math.min(maxX, sCursorX + a1));
                        sCursorY = Math.max(0f, Math.min(maxY, sCursorY + a2));
                        int disp = mirror
                                ? (sMirDragDisplayId >= 0 ? sMirDragDisplayId : resolveMirrorTargetDisplay(sCursorX, sCursorY))
                                : sVdId;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0 && sMouseButtons != 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_MOVE, now, tx, ty, disp);
                        } else {
                            dispatchMouseMotion(sMouseButtons != 0 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE,
                                    now, tx, ty, sMouseButtons, 0, 0f, 0f, disp);
                        }
                        break;
                    }
                    case 1: { // LEFT_CLICK
                        int disp = mirror ? resolveMirrorTargetDisplay(sCursorX, sCursorY) : sVdId;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0) {
                            sMouseDownTime = now - 20;
                            dispatchTouchMotion(MotionEvent.ACTION_DOWN, now - 20, tx, ty, disp);
                            dispatchTouchMotion(MotionEvent.ACTION_UP, now, tx, ty, disp);
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now - 25, tx, ty, 0, 0, 0f, 0f, disp);
                            sMouseDownTime = now - 20;
                            dispatchMouseMotion(MotionEvent.ACTION_DOWN, now - 20, tx, ty, MotionEvent.BUTTON_PRIMARY, 0, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now - 20, tx, ty, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, tx, ty, 0, MotionEvent.BUTTON_PRIMARY, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, tx, ty, 0, 0, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now, tx, ty, 0, 0, 0f, 0f, disp);
                        }
                        break;
                    }
                    case 2: { // RIGHT_CLICK
                        int disp = mirror ? resolveMirrorTargetDisplay(sCursorX, sCursorY) : sVdId;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now - 25, tx, ty, 0, 0, 0f, 0f, disp);
                        sMouseDownTime = now - 20;
                        dispatchMouseMotion(MotionEvent.ACTION_DOWN, now - 20, tx, ty, MotionEvent.BUTTON_SECONDARY, 0, 0f, 0f, disp);
                        dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now - 20, tx, ty, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY, 0f, 0f, disp);
                        dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, tx, ty, 0, MotionEvent.BUTTON_SECONDARY, 0f, 0f, disp);
                        dispatchMouseMotion(MotionEvent.ACTION_UP, now, tx, ty, 0, 0, 0f, 0f, disp);
                        dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now, tx, ty, 0, 0, 0f, 0f, disp);
                        break;
                    }
                    case 3: { // LEFT_DOWN (drag)
                        sMouseDownTime = now;
                        sMouseButtons = MotionEvent.BUTTON_PRIMARY;
                        int disp = mirror ? resolveMirrorTargetDisplay(sCursorX, sCursorY) : sVdId;
                        sMirDragDisplayId = mirror ? disp : -1;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_DOWN, now, tx, ty, disp);
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_DOWN, now, tx, ty, MotionEvent.BUTTON_PRIMARY, 0, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now, tx, ty, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, 0f, 0f, disp);
                        }
                        break;
                    }
                    case 4: { // LEFT_UP (drag end)
                        int disp = mirror ? (sMirDragDisplayId >= 0 ? sMirDragDisplayId : resolveMirrorTargetDisplay(sCursorX, sCursorY)) : sVdId;
                        sMirDragDisplayId = -1;
                        sMouseButtons = 0;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_UP, now, tx, ty, disp);
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, tx, ty, 0, MotionEvent.BUTTON_PRIMARY, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, tx, ty, 0, 0, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now + 5, tx, ty, 0, 0, 0f, 0f, disp);
                        }
                        break;
                    }
                    case 5: { // SCROLL (hscroll=a1, vscroll=a2)
                        int disp = mirror ? resolveMirrorTargetDisplay(sCursorX, sCursorY) : sVdId;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        dispatchMouseMotion(MotionEvent.ACTION_SCROLL, now, tx, ty, sMouseButtons, 0, a1, a2, disp);
                        break;
                    }
                    case 7: { // MOVE_ABS
                        sCursorX = Math.max(0f, Math.min(maxX, a1));
                        sCursorY = Math.max(0f, Math.min(maxY, a2));
                        int disp = mirror
                                ? (sMirDragDisplayId >= 0 ? sMirDragDisplayId : resolveMirrorTargetDisplay(sCursorX, sCursorY))
                                : sVdId;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0 && sMouseButtons != 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_MOVE, now, tx, ty, disp);
                        } else {
                            dispatchMouseMotion(sMouseButtons != 0 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE,
                                    now, tx, ty, sMouseButtons, 0, 0f, 0f, disp);
                        }
                        break;
                    }
                }
                if (mirror) {
                    updateMirrorCursorSurface(sCursorX, sCursorY, sMouseButtons != 0 || type == 3, true);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static void handleSideDirectTouch(int action, float sx, float sy) {
        synchronized (sCursorLock) {
            try {
                if (sMirrorMode != 1 || sSideVdId <= 0 || sIIm == null || sMInjectInput == null) return;
                long now = SystemClock.uptimeMillis();
                if (action == MotionEvent.ACTION_DOWN) {
                    sSideTouchDownTime = now;
                }
                float cx = Math.max(0f, Math.min(Math.max(1, sPhysW) - 1f, sx));
                float cy = Math.max(0f, Math.min(Math.max(1, sPhysH) - 1f, sy));
                dispatchTouchMotionWithDownTime(action, now, sSideTouchDownTime > 0 ? sSideTouchDownTime : now, cx, cy, sSideVdId);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    sSideTouchDownTime = 0L;
                }
                if (sMirCursorVisible) {
                    updateMirrorCursorSurface(cx, cy, false, false);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static boolean handleCapturedMirrorMouse(int actionType, float a1, float a2, float a3) {
        synchronized (sCursorLock) {
            try {
                if (sMirrorMode != 1 || sSideVdId <= 0 || sPhysW <= 0 || sPhysH <= 0) {
                    sMirMouseCaptured = false;
                    return false;
                }
                if (!sMirMouseCaptured && actionType != 5) {
                    return false;
                }
                long now = SystemClock.uptimeMillis();
                sLastMirCapActionMs = now;
                float nx = (actionType == 0) ? Math.max(0f, Math.min(sPhysW - 1f, sCursorX + a1)) : sCursorX;
                float ny = (actionType == 0) ? Math.max(0f, Math.min(sPhysH - 1f, sCursorY + a2)) : sCursorY;

                boolean hasHorizMargin = (sPhysW - sMirW >= EDGE_MARGIN_MIN_PX);
                boolean hasVertMargin = !hasHorizMargin && (sPhysH - sMirH >= EDGE_MARGIN_MIN_PX);
                boolean inHorizMain = hasHorizMargin
                        ? (nx >= sMirX + (sMirX >= EDGE_MARGIN_MIN_PX ? 4f : 0f)
                                && nx <= (sMirX + sMirW) - ((sPhysW - sMirX - sMirW >= EDGE_MARGIN_MIN_PX) ? 5f : 0f))
                        : (nx >= sMirX && nx < sMirX + sMirW);
                boolean inVertMain = hasVertMargin
                        ? (ny >= sMirY + (sMirY >= EDGE_MARGIN_MIN_PX ? 4f : 0f)
                                && ny <= (sMirY + sMirH) - ((sPhysH - sMirY - sMirH >= EDGE_MARGIN_MIN_PX) ? 5f : 0f))
                        : (ny >= sMirY && ny < sMirY + sMirH);
                boolean crossedBack = (actionType == 5) || (inHorizMain && inVertMain);
                if (crossedBack) {
                    if (sMouseButtons != 0) {
                        if ((sMouseButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, sSideVdId);
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, sCursorX, sCursorY, 0, sMouseButtons, 0f, 0f, sSideVdId);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, 0, 0, 0f, 0f, sSideVdId);
                        }
                        sMouseButtons = 0;
                        sMouseDownTime = 0L;
                    }
                    sCursorX = Math.max(sMirX, Math.min(sMirX + sMirW - 1f, nx));
                    sCursorY = Math.max(sMirY, Math.min(sMirY + sMirH - 1f, ny));
                    sMirMouseCaptured = false;
                    sHasLastPollD0 = false;
                    sJustExitedSideEdge = true;
                    sMirEdgeCooldownUntilMs = now + 700L;
                    updateMirrorCursorSurface(sCursorX, sCursorY, false, true);
                    return false;
                }

                sMirMouseCaptured = true;
                sCursorX = nx;
                sCursorY = ny;
                switch (actionType) {
                    case 0: { // MOVE_REL
                        if ((sMouseButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_MOVE, now, sCursorX, sCursorY, sSideVdId);
                        } else if (sMouseButtons != 0) {
                            dispatchMouseMotion(MotionEvent.ACTION_MOVE, now, sCursorX, sCursorY, sMouseButtons, 0, 0f, 0f, sSideVdId);
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now, sCursorX, sCursorY, 0, 0, 0f, 0f, sSideVdId);
                        }
                        break;
                    }
                    case 1: { // LEFT_DOWN
                        if (sMouseButtons == 0) {
                            sMouseDownTime = now;
                            sMouseButtons = MotionEvent.BUTTON_PRIMARY;
                            dispatchTouchMotion(MotionEvent.ACTION_DOWN, now, sCursorX, sCursorY, sSideVdId);
                        }
                        break;
                    }
                    case 2: { // LEFT_UP
                        if ((sMouseButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                            sMouseButtons &= ~MotionEvent.BUTTON_PRIMARY;
                            dispatchTouchMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, sSideVdId);
                            sMouseDownTime = 0L;
                        }
                        break;
                    }
                    case 4: { // SCROLL
                        dispatchMouseMotion(MotionEvent.ACTION_SCROLL, now, sCursorX, sCursorY, sMouseButtons, 0, a1, a3, sSideVdId);
                        break;
                    }
                    case 6: { // SECONDARY / MIDDLE DOWN (a1 = button bitmask)
                        int btn = Math.round(a1);
                        if (btn == MotionEvent.BUTTON_SECONDARY || btn == MotionEvent.BUTTON_TERTIARY) {
                            sMouseDownTime = now;
                            sMouseButtons |= btn;
                            dispatchMouseMotion(MotionEvent.ACTION_DOWN, now, sCursorX, sCursorY, sMouseButtons, 0, 0f, 0f, sSideVdId);
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now, sCursorX, sCursorY, sMouseButtons, btn, 0f, 0f, sSideVdId);
                        }
                        break;
                    }
                    case 7: { // SECONDARY / MIDDLE UP (a1 = button bitmask)
                        int btn = Math.round(a1);
                        if (btn == MotionEvent.BUTTON_SECONDARY || btn == MotionEvent.BUTTON_TERTIARY) {
                            sMouseButtons &= ~btn;
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, sCursorX, sCursorY, sMouseButtons, btn, 0f, 0f, sSideVdId);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, sMouseButtons, 0, 0f, 0f, sSideVdId);
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now + 4, sCursorX, sCursorY, 0, 0, 0f, 0f, sSideVdId);
                            if (sMouseButtons == 0) sMouseDownTime = 0L;
                        }
                        break;
                    }
                }
                updateMirrorCursorSurface(sCursorX, sCursorY, sMouseButtons != 0 || actionType == 1, true);
                return true;
            } catch (Throwable t) {
                t.printStackTrace();
                sMirMouseCaptured = false;
                return false;
            }
        }
    }

    private static boolean triggerEdgeHoverEntry(float ptX, float ptY) {
        synchronized (sCursorLock) {
            if (sMirMouseCaptured) return true;
            long now = SystemClock.uptimeMillis();
            if (sJustExitedSideEdge && now >= sMirEdgeCooldownUntilMs) {
                sJustExitedSideEdge = false;
            }
            if (now < sMirEdgeCooldownUntilMs || now - sLastTouchpadMs < 350L || sJustExitedSideEdge) return false;
            float[] entry = computeEdgeEntryPoint(ptX, ptY, ptX, ptY, true);
            if (entry == null) return false;
            sMirMouseCaptured = true;
            sLastMirCapActionMs = now;
            sCursorX = entry[0];
            sCursorY = entry[1];
            updateMirrorCursorSurface(sCursorX, sCursorY, false, true);
            return true;
        }
    }

    private static void dispatchTouchMotion(int action, long now, float x, float y, int targetDisplayId) {
        dispatchTouchMotionWithDownTime(action, now, sMouseDownTime > 0 ? sMouseDownTime : now, x, y, targetDisplayId);
    }

    private static void dispatchTouchMotionWithDownTime(int action, long now, long downTime, float x, float y, int targetDisplayId) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ? 0.0f : 1.0f;
        coords[0].size = 1.0f;

        MotionEvent ev = MotionEvent.obtain(downTime, now, action,
                1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            invokeInjectInput(ev, targetDisplayId);
        } finally {
            ev.recycle();
        }
    }

    private static void dispatchMouseMotion(int action, long now, float x, float y, int buttonState, int actionButton, float hscroll, float vscroll, int targetDisplayId) throws Exception {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = (buttonState != 0) ? 1.0f : 0.0f;
        coords[0].size = 1.0f;
        if (action == MotionEvent.ACTION_SCROLL) {
            coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        }

        long downTime = (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) ? now : sMouseDownTime;
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, 1, props, coords,
                0, buttonState, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        if (actionButton != 0) {
            try { MotionEvent.class.getMethod("setActionButton", int.class).invoke(ev, actionButton); } catch (Throwable ignored) {}
        }
        try {
            invokeInjectInput(ev, targetDisplayId);
        } finally {
            ev.recycle();
        }
    }

    private static boolean launchComponentOnVd(String pkg, String cls) {
        try {
            if (sVdId <= 0 || sAtm == null || sMStartActivity == null) return false;
            boolean isExtLauncher = "com.extbridge.helper".equals(pkg) && cls != null && cls.contains("LauncherActivity");
            synchronized (sSplitLock) {
                if (sSplitActive) {
                    if (isExtLauncher) {
                        int emptySlot = getEmptySplitSlot();
                        if (emptySlot >= 0) {
                            syncLauncherForSplit(emptySlot, true);
                            return true;
                        }
                        ensureLauncherTaskFullscreenInBg();
                    } else if (!"com.extbridge.helper".equals(pkg)) {
                        int targetSlot = matchOrAutoFillSplitSlot(pkg);
                        if (targetSlot >= 0) {
                            return splitAssign(targetSlot, -1, pkg, cls);
                        }
                        exitSplitAndRestore(false);
                    }
                }
            }
            int mode = getAppMode(pkg);
            applyAppModeCompat(pkg, mode, false);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(pkg, cls));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if ("com.extbridge.helper".equals(pkg) && sCtlBinder != null) {
                Bundle b = new Bundle();
                b.putBinder("cb", sCtlBinder);
                intent.putExtras(b);
                intent.putExtra("extId", sVdId);
            }
            ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode);
            Object rc = sMStartActivity.invoke(sAtm, null, "com.android.shell", null, intent, null, null, null, 0, 0, null, opts.toBundle());
            return !(rc instanceof Integer) || (Integer) rc >= 0;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    private static ActivityOptions makeExtLaunchOptions(int displayId) {
        return makeExtLaunchOptionsForMode(displayId, 0);
    }

    private static Rect calcFreeformRect() {
        int sw = Math.max(600, sVirtW);
        int sh = Math.max(600, sVirtH);
        boolean port = sh > sw;
        int w = Math.max(540, Math.min(sw - 120, Math.round(sw * (port ? 0.78f : 0.44f))));
        int h = Math.max(500, Math.min(sh - 120, Math.round(sh * (port ? 0.62f : 0.84f))));
        int left = (sw - w) / 2;
        int top = Math.max(24, (sh - h) / 2 - 20);
        return new Rect(left, top, left + w, top + h);
    }

    private static ActivityOptions makeExtLaunchOptionsForMode(int displayId, int mode) {
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId);
        if (displayId > 0) {
            try {
                // Freeform small window mode (5) is disallowed in DeX / Extended mode
                if (mode == 1) {
                    ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(opts, 1);
                }
            } catch (Throwable ignored) {}
        }
        return opts;
    }

    private static boolean shouldCompatOverridePkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return !"android".equals(pkg)
                && !"com.extbridge.helper".equals(pkg)
                && !"com.android.settings".equals(pkg)
                && !"com.android.systemui".equals(pkg)
                && !"com.miui.home".equals(pkg)
                && !"com.miui.securitycenter".equals(pkg);
    }

    private static void setGlobalIntSafe(String key, int val) {
        try {
            if (sCtx != null && Settings.Global.putInt(sCtx.getContentResolver(), key, val)) return;
        } catch (Throwable ignored) {}
        runCmdOut("settings", "put", "global", key, String.valueOf(val));
    }

    private static void restoreGlobalFullscreenPrefs() {
        if (!sSavedSysPrefs) return;
        setGlobalIntSafe("force_resizable_activities", sOrigForceResizable);
        setGlobalIntSafe("enable_non_resizable_multi_window", sOrigNonResizableMw);
        sSavedSysPrefs = false;
    }

    private static void applyDisplayFullscreenPolicy(int vdId, boolean enable) {
        // Never mutate global Settings.Global force_resizable_activities/enable_non_resizable_multi_window
        // as those globally affect tablet/phone Display 0 apps (WeChat, Quark, Pinduoduo, etc.).
        restoreGlobalFullscreenPrefs();

        if (vdId <= 0 || sWm == null) return;
        // Keep system decors off on virtual display so Display 0 gesture navigation stays isolated.
        try {
            sWm.getClass().getMethod("setShouldShowSystemDecors", int.class, boolean.class)
                    .invoke(sWm, vdId, false);
        } catch (Throwable ignored) {}
        boolean ignoredApplied = false;
        try {
            for (Method m : sWm.getClass().getMethods()) {
                if (m.getName().equals("setIgnoreOrientationRequest") && m.getParameterCount() == 2) {
                    m.invoke(sWm, vdId, enable);
                    ignoredApplied = true;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        if (!ignoredApplied) {
            runCmdOut("wm", "set-ignore-orientation-request", "-d", String.valueOf(vdId), enable ? "true" : "false");
        }

        boolean rotApplied = false;
        try {
            if (enable) {
                for (Method m : sWm.getClass().getMethods()) {
                    if (m.getName().equals("freezeDisplayRotation") && m.getParameterCount() == 3) {
                        m.invoke(sWm, vdId, 0, "ExtBridge");
                        rotApplied = true;
                        break;
                    } else if (m.getName().equals("freezeDisplayRotation") && m.getParameterCount() == 2) {
                        m.invoke(sWm, vdId, 0);
                        rotApplied = true;
                        break;
                    }
                }
            } else {
                for (Method m : sWm.getClass().getMethods()) {
                    if (m.getName().equals("thawDisplayRotation") && m.getParameterCount() == 2) {
                        m.invoke(sWm, vdId, "ExtBridge");
                        rotApplied = true;
                        break;
                    } else if (m.getName().equals("thawDisplayRotation") && m.getParameterCount() == 1) {
                        m.invoke(sWm, vdId);
                        rotApplied = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (!rotApplied) {
            if (enable) {
                runCmdOut("wm", "user-rotation", "-d", String.valueOf(vdId), "lock", "0");
            } else {
                runCmdOut("wm", "user-rotation", "-d", String.valueOf(vdId), "free");
            }
        }

        try {
            for (Method m : sWm.getClass().getMethods()) {
                if (m.getName().equals("setDisplayWindowingMode") && m.getParameterCount() == 2) {
                    m.invoke(sWm, vdId, enable ? 1 : 0);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void applyAppModeCompat(String pkg, int mode, boolean forceApply) {
        if (!shouldCompatOverridePkg(pkg)) return;
        String qPkg = pkg.replace("'", "");
        if (mode == 0 || mode == 3) {
            boolean wasOverridden;
            synchronized (sCompatOverriddenPkgs) {
                wasOverridden = sCompatOverriddenPkgs.remove(pkg);
            }
            if (wasOverridden || forceApply) {
                runCmdOut("sh", "-c", "am compat reset-all --no-kill '" + qPkg
                        + "' >/dev/null 2>&1 || am compat reset-all '" + qPkg + "' >/dev/null 2>&1");
            }
            return;
        }
        synchronized (sCompatOverriddenPkgs) {
            if (!forceApply && sCompatOverriddenPkgs.contains(pkg)) return;
            sCompatOverriddenPkgs.add(pkg);
        }
        String cmd;
        if (mode == 1) {
            cmd = "for ch in 254631730 310816437 265464455 263259275 208648326 174042936; do "
                    + "am compat enable --no-kill $ch '" + qPkg + "' >/dev/null 2>&1; done; "
                    + "for ch in 265452344 236283604; do "
                    + "am compat disable --no-kill $ch '" + qPkg + "' >/dev/null 2>&1; done";
        } else {
            // mode == 2: portrait/phone ratio compat
            cmd = "for ch in 254631730 310816437 265464455; do "
                    + "am compat disable --no-kill $ch '" + qPkg + "' >/dev/null 2>&1; done; "
                    + "for ch in 265452344 236283604; do "
                    + "am compat enable --no-kill $ch '" + qPkg + "' >/dev/null 2>&1; done";
        }
        runCmdOut("sh", "-c", cmd);
    }

    private static void resetAppFullscreenCompatAll() {
        ArrayList<String> pkgs;
        synchronized (sCompatOverriddenPkgs) {
            if (sCompatOverriddenPkgs.isEmpty()) return;
            pkgs = new ArrayList<>(sCompatOverriddenPkgs);
            sCompatOverriddenPkgs.clear();
        }
        StringBuilder cmd = new StringBuilder();
        for (String pkg : pkgs) {
            String qPkg = pkg.replace("'", "");
            cmd.append("am compat reset-all --no-kill '").append(qPkg)
               .append("' >/dev/null 2>&1 || am compat reset-all '").append(qPkg).append("' >/dev/null 2>&1; ");
        }
        if (cmd.length() > 0) {
            runCmdOut("sh", "-c", cmd.toString());
        }
    }

    private static void forceTaskWindowingMode(int taskId, int wmode) {
        if (taskId <= 0 || sAtm == null) return;
        if (applyWctForTask(taskId, null, wmode, null)) return;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("setTaskWindowingMode")) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                        m.invoke(sAtm, taskId, wmode);
                        return;
                    } else if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class && pt[2] == boolean.class) {
                        m.invoke(sAtm, taskId, wmode, false);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void enforceRunningExtTasksFullscreen() {
        if (sVdId <= 0 || sAtm == null) return;
        try {
            List<?> running = invokeTaskQuery("getTasks");
            if (running == null) return;
            for (Object t : running) {
                if (t == null) continue;
                int did = readIntField(t, "displayId", -1);
                if (did != sVdId) continue;
                String pkg = extractTaskPkg(t);
                if (sSplitActive && (pkg.equals(sSplitSlot0Pkg) || pkg.equals(sSplitSlot1Pkg))) continue;
                if (!shouldCompatOverridePkg(pkg)) continue;
                int mode = getAppMode(pkg);
                applyAppModeCompat(pkg, mode, false);
                if (mode == 1) {
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    int wmode = readIntField(t, "windowingMode", 1);
                    if (tid > 0 && wmode != 1) {
                        forceTaskWindowingMode(tid, 1);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void launchLauncher() {
        launchComponentOnVd("com.extbridge.helper", "com.extbridge.helper.LauncherActivity");
    }

    private static boolean minimizeToExtHome(int taskId, String pkg) {
        if (sVdId <= 0 || sMirrorMode != 0) return false;
        if (sSplitActive) {
            exitSplitAndRestore(false);
        }
        int lTid = findExtLauncherTaskId();
        if (lTid > 0 && sAtm != null) {
            try {
                clearTaskAlwaysOnTop(lTid);
                forceTaskWindowingMode(lTid, 1);
                ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, 1);
                Bundle b = opts.toBundle();
                for (Method m : sAtm.getClass().getMethods()) {
                    if (m.getName().equals("startActivityFromRecents") && m.getParameterCount() == 2) {
                        m.invoke(sAtm, lTid, b);
                        notifyHelperSync();
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        boolean ok = launchComponentOnVd("com.extbridge.helper", "com.extbridge.helper.LauncherActivity");
        if (ok) notifyHelperSync();
        return ok;
    }

    private static void pushBinderToHelper() {
        if (sCtx == null || sCtlBinder == null) return;
        Bundle res = null;
        try {
            Bundle b = new Bundle();
            b.putBinder("cb", sCtlBinder);
            try {
                ContentProviderClient cpc = sCtx.getContentResolver().acquireUnstableContentProviderClient("com.extbridge.helper.provider");
                if (cpc != null) {
                    try {
                        res = cpc.call("setBinder", null, b);
                    } finally {
                        try { cpc.close(); } catch (Throwable ignored) {}
                    }
                }
                if (res == null) {
                    res = sCtx.getContentResolver().call(Uri.parse("content://com.extbridge.helper.provider"), "setBinder", null, b);
                }
            } catch (Throwable ignored) {}
            if (res == null || !res.getBoolean("ok", false)) {
                res = callProviderViaAm("com.extbridge.helper.provider", "setBinder", null, b);
            }
            if (res != null) {
                IBinder tok = res.getBinder("proc");
                if (tok != null && tok != sHelperProcToken) {
                    sHelperProcToken = tok;
                    try {
                        tok.linkToDeath(() -> {
                            sHelperProcToken = null;
                        }, 0);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            System.err.println("pushBinderToHelper failed: " + t);
            t.printStackTrace();
        }
        startHelperService();
    }

    private static Bundle callProviderViaAm(String authority, String method, String arg, Bundle extras) {
        IBinder tok = new Binder();
        Object am = null;
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            IBinder amB = (IBinder) smCls.getMethod("getService", String.class).invoke(null, "activity");
            am = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, amB);
            Object holder = am.getClass()
                    .getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                    .invoke(am, authority, 0, tok, "*extbridge*");
            if (holder == null) return null;
            Object prov = holder.getClass().getField("provider").get(holder);
            if (prov == null) return null;
            Method callAttr5 = null;
            Method callStr5 = null;
            Method callStr6 = null;
            StringBuilder sigs = new StringBuilder();
            for (Method m : prov.getClass().getMethods()) {
                if (!m.getName().equals("call")) continue;
                Class<?>[] pts = m.getParameterTypes();
                sigs.append(Arrays.toString(pts)).append("; ");
                if (pts.length == 5 && pts[0].getName().equals("android.content.AttributionSource")) callAttr5 = m;
                else if (pts.length == 5 && pts[0] == String.class) callStr5 = m;
                else if (pts.length == 6 && pts[0] == String.class) callStr6 = m;
            }
            if (callAttr5 != null) {
                Class<?> asBuilderCls = Class.forName("android.content.AttributionSource$Builder");
                Object builder = asBuilderCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
                asBuilderCls.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
                Object attrSrc = asBuilderCls.getMethod("build").invoke(builder);
                return (Bundle) callAttr5.invoke(prov, attrSrc, authority, method, arg, extras);
            } else if (callStr5 != null) {
                return (Bundle) callStr5.invoke(prov, "com.android.shell", authority, method, arg, extras);
            } else if (callStr6 != null) {
                return (Bundle) callStr6.invoke(prov, "com.android.shell", null, authority, method, arg, extras);
            } else {
                System.err.println("callProviderViaAm no matching call overload: " + sigs);
            }
        } catch (Throwable t) {
            System.err.println("callProviderViaAm failed: " + t);
            t.printStackTrace();
        } finally {
            if (am != null) {
                try {
                    am.getClass().getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                            .invoke(am, authority, tok, 0);
                } catch (Throwable ignored) {
                    try {
                        am.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class)
                                .invoke(am, authority, tok);
                    } catch (Throwable ignored2) {}
                }
            }
        }
        return null;
    }

    private static Context fixShellContext(Context base) {
        try {
            Field fOp = base.getClass().getDeclaredField("mOpPackageName");
            fOp.setAccessible(true);
            fOp.set(base, "com.android.shell");
        } catch (Throwable ignored) {}
        try {
            Field fBase = base.getClass().getDeclaredField("mBasePackageName");
            fBase.setAccessible(true);
            fBase.set(base, "com.android.shell");
        } catch (Throwable ignored) {}
        try {
            Class<?> asBuilderCls = Class.forName("android.content.AttributionSource$Builder");
            Object builder = asBuilderCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
            asBuilderCls.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
            Object attrSrc = asBuilderCls.getMethod("build").invoke(builder);
            Field fAttr = base.getClass().getDeclaredField("mAttributionSource");
            fAttr.setAccessible(true);
            fAttr.set(base, attrSrc);
        } catch (Throwable ignored) {}
        return base;
    }

    private static void startHelperService() {
        try {
            Intent svc = new Intent();
            svc.setComponent(new ComponentName("com.extbridge.helper", "com.extbridge.helper.BridgeService"));
            if (sCtlBinder != null) {
                Bundle b = new Bundle();
                b.putBinder("cb", sCtlBinder);
                svc.putExtras(b);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                sCtx.startForegroundService(svc);
            } else {
                sCtx.startService(svc);
            }
        } catch (Throwable ignored) {}
    }

    private static void notifyHelperSync() {
        try {
            if (sShuttingDown) return;
            if (sHelperProcToken == null || !sHelperProcToken.pingBinder()) return;
            Intent b = new Intent("com.extbridge.helper.STATE_SYNC");
            b.setPackage("com.extbridge.helper");
            b.putExtra("extId", sExtId);
            b.putExtra("vdId", sVdId);
            b.putExtra("physW", sPhysW);
            b.putExtra("physH", sPhysH);
            b.putExtra("physFps", sPhysFps);
            b.putExtra("virtW", sVirtW);
            b.putExtra("virtH", sVirtH);
            b.putExtra("virtDpi", sVirtDpi);
            b.putExtra("virtFps", sVirtFps);
            b.putExtra("virtRot", sVirtRot);
            b.putExtra("inputToExt", sInputToExt);
            b.putExtra("splitActive", sSplitActive ? 1 : 0);
            b.putExtra("splitRatioPct", Math.round(sSplitRatio * 100f));
            b.putExtra("splitSlot0Pkg", sSplitSlot0Pkg != null ? sSplitSlot0Pkg : "");
            b.putExtra("splitSlot1Pkg", sSplitSlot1Pkg != null ? sSplitSlot1Pkg : "");
            sCtx.sendBroadcast(b);
        } catch (Throwable ignored) {}
    }

    private static String getModesSummary() {
        try {
            Display ext = findExternalDisplay();
            if (ext == null) return "";
            Display.Mode[] modes = ext.getSupportedModes();
            if (modes == null) return "";
            StringBuilder sb = new StringBuilder();
            for (Display.Mode m : modes) {
                if (sb.length() > 0) sb.append(";");
                sb.append(m.getModeId()).append(":")
                  .append(m.getPhysicalWidth()).append("x").append(m.getPhysicalHeight())
                  .append("@").append(String.format(java.util.Locale.US, "%.1f", m.getRefreshRate()));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isTrustedCaller(int callingUid) {
        if (callingUid == android.os.Process.myUid()
                || callingUid == 0
                || callingUid == 1000
                || callingUid == 2000) {
            return true;
        }
        int cached = sHelperUid;
        if (cached > 0 && callingUid == cached) return true;
        if (sCtx != null) {
            try {
                int uid = sCtx.getPackageManager().getPackageUid("com.extbridge.helper", 0);
                sHelperUid = uid;
                if (callingUid == uid) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static class BridgeBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (!isTrustedCaller(Binder.getCallingUid())) {
                throw new SecurityException("Untrusted ExtBridge caller uid=" + Binder.getCallingUid());
            }
            if (code == 10) {
                sInputToExt = (sInputToExt == 0) ? 1 : 0;
                syncInput();
                if (reply != null) reply.writeInt(sInputToExt);
                return true;
            } else if (code == 11) {
                injectKey(KeyEvent.KEYCODE_BACK, 0);
                return true;
            } else if (code == 12) {
                launchLauncher();
                return true;
            } else if (code == 13) {
                injectKey(KeyEvent.KEYCODE_HOME, 0);
                return true;
            } else if (code == 14) {
                if (reply != null) reply.writeInt(sInputToExt);
                return true;
            } else if (code == 15) {
                if (reply != null) {
                    int cx, cy;
                    synchronized (sCursorLock) {
                        cx = Math.round(sCursorX);
                        cy = Math.round(sCursorY);
                    }
                    int splitAct, splitRatioPct;
                    String s0, s1;
                    synchronized (sSplitLock) {
                        splitAct = sSplitActive ? 1 : 0;
                        splitRatioPct = Math.round(sSplitRatio * 100f);
                        s0 = sSplitSlot0Pkg != null ? sSplitSlot0Pkg : "";
                        s1 = sSplitSlot1Pkg != null ? sSplitSlot1Pkg : "";
                    }
                    reply.writeInt(sExtId);
                    reply.writeInt(sVdId);
                    reply.writeInt(sPhysW);
                    reply.writeInt(sPhysH);
                    reply.writeInt(Math.round(sPhysFps * 100f));
                    reply.writeInt(sMirrorMode == 1 ? sPhysW : sVirtW);
                    reply.writeInt(sMirrorMode == 1 ? sPhysH : sVirtH);
                    reply.writeInt(sVirtDpi);
                    reply.writeInt(Math.round(sVirtFps * 100f));
                    reply.writeInt(sInputToExt);
                    reply.writeInt(cx);
                    reply.writeInt(cy);
                    reply.writeInt(sLastExtInputCnt);
                    reply.writeString(getModesSummary());
                    reply.writeString(sRenderMode);
                    reply.writeInt(sMirrorMode);
                    reply.writeInt(sFullscreenPolicy);
                    reply.writeInt(sVirtRot);
                    reply.writeInt(sLastInjectStatus);
                    reply.writeInt(splitAct);
                    reply.writeInt(splitRatioPct);
                    reply.writeString(s0);
                    reply.writeString(s1);
                    reply.writeInt(sMirrorSideMode);
                    reply.writeInt(sSideVdId);
                    reply.writeInt(sD0W);
                    reply.writeInt(sD0H);
                    reply.writeInt(sMirX);
                    reply.writeInt(sMirY);
                    reply.writeInt(sMirW);
                    reply.writeInt(sMirH);
                }
                return true;
            } else if (code == 16) {
                int w = data.readInt();
                int h = data.readInt();
                int dpi = data.readInt();
                int fpsX100 = data.readInt();
                int physModeId = data.readInt();
                int reqRot = data.dataAvail() > 0 ? data.readInt() : -1;
                boolean ok = applyConfig(w, h, dpi, fpsX100, physModeId, reqRot);
                if (reply != null) {
                    reply.writeInt(ok ? 1 : 0);
                    reply.writeInt(sLastPhysResult);
                    reply.writeInt(sPhysW);
                    reply.writeInt(sPhysH);
                    reply.writeInt(Math.round(sPhysFps * 100f));
                }
                return true;
            } else if (code == 17) {
                int actionType = data.readInt();
                float a1 = data.readFloat();
                float a2 = data.readFloat();
                injectMouseAction(actionType, a1, a2);
                if (reply != null) {
                    reply.writeInt(Math.round(sCursorX));
                    reply.writeInt(Math.round(sCursorY));
                    reply.writeInt(sLastInjectStatus);
                }
                return true;
            } else if (code == 18) {
                int sub = data.readInt();
                int arg1 = data.readInt();
                int arg2 = data.readInt();
                String txt = data.readString();
                if (sub == 0) {
                    injectKey(arg1, arg2);
                } else if (sub == 1) {
                    injectAscii(txt);
                } else if (sub == 2) {
                    if (arg1 == 1) {
                        injectKey(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
                    } else {
                        injectKey(KeyEvent.KEYCODE_PASTE, 0);
                    }
                }
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 19) {
                int mode = data.readInt();
                sInputToExt = (mode != 0) ? 1 : 0;
                syncInput();
                if (reply != null) reply.writeInt(sInputToExt);
                return true;
            } else if (code == 20) {
                String pkg = data.readString();
                String cls = data.readString();
                boolean ok = launchComponentOnVd(pkg, cls);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 21) {
                sForceRebuild = true;
                sSideVdAttempted = false;
                tick();
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 22) {
                boolean force = data.readInt() == 1;
                byte[] wp = getWallpaperBytes(force);
                if (reply != null) reply.writeByteArray(wp);
                return true;
            } else if (code == 23) {
                writeTasksToParcel(reply);
                return true;
            } else if (code == 24) {
                int tid = data.readInt();
                String pkg = data.readString();
                boolean ok = switchToTaskOnVd(tid, pkg);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 25) {
                int tid = data.readInt();
                boolean ok = removeTaskById(tid);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 26) {
                writeSysStatusToParcel(reply);
                return true;
            } else if (code == 27) {
                int act = data.readInt();
                int arg = data.readInt();
                String str = data.readString();
                boolean ok = execSysAction(act, arg, str);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 28) {
                int req = data.readInt();
                int prev = sMirrorMode;
                sMirrorMode = (req < 0) ? (prev == 0 ? 1 : 0) : (req == 1 ? 1 : 0);
                if (prev != sMirrorMode) {
                    sSideVdAttempted = false;
                    synchronized (sSplitLock) {
                        if (sSplitActive) exitSplitAndRestore(false);
                    }
                }
                if (sMirrorMode != 1) {
                    saveConf();
                }
                tick();
                notifyHelperSync();
                if (reply != null) reply.writeInt(sMirrorMode);
                return true;
            } else if (code == 29) {
                int req = data.readInt();
                int next = (req < 0) ? (sFullscreenPolicy == 0 ? 1 : 0) : (req != 0 ? 1 : 0);
                if (next != sFullscreenPolicy) {
                    sFullscreenPolicy = next;
                    saveConf();
                    if (sFullscreenPolicy == 0) {
                        resetAppFullscreenCompatAll();
                    }
                    if (sVdId > 0) {
                        applyDisplayFullscreenPolicy(sVdId, sFullscreenPolicy == 1);
                    }
                    if (sMirrorMode != 1 && sVd != null) {
                        sForceRebuild = true;
                        tick();
                    }
                    notifyHelperSync();
                }
                if (reply != null) reply.writeInt(sFullscreenPolicy);
                return true;
            } else if (code == 30) {
                String pkg = data.readString();
                int mode = getAppMode(pkg);
                if (sMirrorMode != 1) {
                    applyAppModeCompat(pkg, mode, false);
                }
                if (reply != null) reply.writeInt(mode);
                return true;
            } else if (code == 31) {
                StringBuilder sb = new StringBuilder();
                synchronized (sAppModes) {
                    for (Map.Entry<String, Integer> e : sAppModes.entrySet()) {
                        if (e.getValue() == null || e.getValue() == 0) continue;
                        if (sb.length() > 0) sb.append(';');
                        sb.append(e.getKey()).append('=').append(e.getValue());
                    }
                }
                if (reply != null) reply.writeString(sb.toString());
                return true;
            } else if (code == 32) {
                String pkg = data.readString();
                int mode = Math.max(0, Math.min(2, data.readInt()));
                int restart = data.readInt();
                if (pkg != null && !pkg.isEmpty()) {
                    synchronized (sAppModes) {
                        if (mode == 0) sAppModes.remove(pkg);
                        else sAppModes.put(pkg, mode);
                        saveAppModesLocked();
                    }
                    if (restart == 1) {
                        runCmdOut("am", "force-stop", pkg);
                    }
                    applyAppModeCompat(pkg, mode, true);
                    if (restart == 1 && sVdId > 0 && sMirrorMode != 1 && sCtx != null) {
                        try {
                            Intent li = sCtx.getPackageManager().getLaunchIntentForPackage(pkg);
                            if (li != null) {
                                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                                ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode);
                                sMStartActivity.invoke(sAtm, null, "com.android.shell", null, li, null, null, null, 0, 0, null, opts.toBundle());
                            }
                        } catch (Throwable ignored) {}
                    }
                    notifyHelperSync();
                }
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 33) {
                ArrayList<String> allPkgs = new ArrayList<>();
                synchronized (sAppModes) {
                    allPkgs.addAll(sAppModes.keySet());
                    sAppModes.clear();
                    saveAppModesLocked();
                }
                synchronized (sCompatOverriddenPkgs) {
                    allPkgs.addAll(sCompatOverriddenPkgs);
                    sCompatOverriddenPkgs.clear();
                }
                if (!allPkgs.isEmpty()) {
                    StringBuilder cmd = new StringBuilder();
                    for (String p : allPkgs) {
                        String qp = p.replace("'", "");
                        cmd.append("am compat reset-all --no-kill '").append(qp)
                           .append("' >/dev/null 2>&1 || am compat reset-all '").append(qp).append("' >/dev/null 2>&1; ");
                    }
                    runCmdOut("sh", "-c", cmd.toString());
                }
                healStrayCompatOverrides(true);
                notifyHelperSync();
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 34) {
                int reqRot = data.readInt();
                int cur = setRotation(reqRot);
                if (reply != null) reply.writeInt(cur);
                return true;
            } else if (code == 35) {
                int action = data.readInt();
                int arg = data.readInt();
                String pkg = data.readString();
                String cls = data.readString();
                boolean ok = handleSplitAction(action, arg, pkg, cls);
                if (reply != null) {
                    reply.writeInt(ok ? 1 : 0);
                    reply.writeInt(sSplitActive ? 1 : 0);
                    reply.writeInt(Math.round(sSplitRatio * 100f));
                    reply.writeString(sSplitSlot0Pkg != null ? sSplitSlot0Pkg : "");
                    reply.writeString(sSplitSlot1Pkg != null ? sSplitSlot1Pkg : "");
                    reply.writeInt(sMirrorSideMode);
                    reply.writeInt(sSideVdId);
                    reply.writeInt(sD0W);
                    reply.writeInt(sD0H);
                    reply.writeInt(sMirX);
                    reply.writeInt(sMirY);
                    reply.writeInt(sMirW);
                    reply.writeInt(sMirH);
                }
                return true;
            } else if (code == 36) {
                int tid = data.readInt();
                String pkg = data.readString();
                int enableFreeform = data.readInt();
                int res = setTaskLiveWindowMode(tid, pkg, enableFreeform);
                if (reply != null) reply.writeInt(res);
                return true;
            } else if (code == 37) {
                int tid = data.readInt();
                String pkg = data.readString();
                boolean ok = minimizeToExtHome(tid, pkg);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 38) {
                int req = data.readInt();
                int next = (req < 0) ? ((sMirrorSideMode + 1) % 4) : Math.max(0, Math.min(3, req));
                if (next != sMirrorSideMode) {
                    sMirrorSideMode = next;
                    sSideVdAttempted = false;
                    saveConf();
                    if (sMirrorMode == 1) {
                        sForceRebuild = true;
                        tick();
                    }
                    notifyHelperSync();
                }
                if (reply != null) reply.writeInt(sMirrorSideMode);
                return true;
            } else if (code == 39) {
                writeMirrorTelemetryToParcel(reply);
                return true;
            } else if (code == 40) {
                boolean ok = grantAppWidgetBind();
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 41) {
                int action = data.readInt();
                float sx = data.readFloat();
                float sy = data.readFloat();
                handleSideDirectTouch(action, sx, sy);
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 42) {
                int actionType = data.readInt();
                float a1 = data.readFloat();
                float a2 = data.readFloat();
                float a3 = data.readFloat();
                boolean stay = handleCapturedMirrorMouse(actionType, a1, a2, a3);
                if (reply != null) {
                    reply.writeInt(stay ? 1 : 0);
                    reply.writeInt(Math.round(sCursorX));
                    reply.writeInt(Math.round(sCursorY));
                }
                return true;
            } else if (code == 43) {
                float px = data.readFloat();
                float py = data.readFloat();
                boolean entered = triggerEdgeHoverEntry(px, py);
                if (reply != null) {
                    reply.writeInt(entered ? 1 : 0);
                    reply.writeInt(Math.round(sCursorX));
                    reply.writeInt(Math.round(sCursorY));
                }
                return true;
            } else if (code == 99) {
                sShuttingDown = true;
                cleanupDisplay(true);
                if (reply != null) reply.writeInt(1);
                new Thread(() -> {
                    SystemClock.sleep(80);
                    System.exit(0);
                }).start();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static byte[] getWallpaperBytes(boolean force) {
        Bitmap bmp = null;
        try {
            WallpaperManager wm = (WallpaperManager) sCtx.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm == null) return null;
            int wpId = 0;
            try { wpId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM); } catch (Throwable ignored) {}
            if (!force && sCachedWpBytes != null && wpId != 0 && wpId == sCachedWpId) {
                return sCachedWpBytes;
            }

            try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
                if (pfd != null) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, bounds);
                    int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
                    int sample = 1;
                    while (maxDim / sample > 1600) sample *= 2;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    bmp = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
                }
            } catch (Throwable ignored) {}

            if (bmp == null) {
                try {
                    Drawable dr = wm.getDrawable();
                    if (dr != null) {
                        int dw = Math.max(1, dr.getIntrinsicWidth());
                        int dh = Math.max(1, dr.getIntrinsicHeight());
                        float sc = Math.min(1f, 1280f / Math.max(dw, dh));
                        int tw = Math.max(1, Math.round(dw * sc));
                        int th = Math.max(1, Math.round(dh * sc));
                        bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                        Canvas cv = new Canvas(bmp);
                        dr.setBounds(0, 0, tw, th);
                        dr.draw(cv);
                    }
                } catch (Throwable ignored) {}
            }

            if (bmp == null) return sCachedWpBytes;

            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (Math.max(bw, bh) > 1440) {
                float sc = 1440f / Math.max(bw, bh);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bw * sc)), Math.max(1, Math.round(bh * sc)), true);
                if (scaled != bmp) {
                    bmp.recycle();
                    bmp = scaled;
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            byte[] out = baos.toByteArray();
            if (out.length > 420_000) {
                baos.reset();
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                out = baos.toByteArray();
            }
            sCachedWpBytes = out;
            sCachedWpId = wpId;
            return out;
        } catch (Throwable t) {
            return sCachedWpBytes;
        } finally {
            if (bmp != null && !bmp.isRecycled()) {
                try { bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static final class TaskSnap {
        int taskId;
        int rootTaskId;
        String pkg;
        int flags; // bit0=onExt, bit1=isRunning, bit2=isTopExt
    }

    private static int readIntField(Object obj, String name, int def) {
        if (obj == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(obj);
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return def;
    }

    private static boolean readBoolField(Object obj, String name, boolean def) {
        if (obj == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(obj);
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return def;
    }

    private static Object readObjField(Object obj, String name) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static String extractTaskPkg(Object info) {
        Object top = readObjField(info, "topActivity");
        if (top instanceof ComponentName) {
            String p = ((ComponentName) top).getPackageName();
            if (p != null && !p.isEmpty()) return p;
        }
        Object real = readObjField(info, "realActivity");
        if (real instanceof ComponentName) {
            String p = ((ComponentName) real).getPackageName();
            if (p != null && !p.isEmpty()) return p;
        }
        Object bi = readObjField(info, "baseIntent");
        if (bi instanceof Intent) {
            Intent intent = (Intent) bi;
            if (intent.getComponent() != null) return intent.getComponent().getPackageName();
            if (intent.getPackage() != null) return intent.getPackage();
        }
        Object ba = readObjField(info, "baseActivity");
        if (ba instanceof ComponentName) return ((ComponentName) ba).getPackageName();
        return null;
    }

    private static boolean shouldSkipTaskPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        return "com.extbridge.helper".equals(pkg)
                || "com.miui.home".equals(pkg)
                || "com.android.systemui".equals(pkg)
                || "com.miui.securitycenter".equals(pkg)
                || "com.android.launcher".equals(pkg)
                || "com.android.launcher3".equals(pkg);
    }

    private static List<?> invokeTaskQuery(String name) {
        if (sAtm == null) return null;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pt = m.getParameterTypes();
                Object res = null;
                if ("getTasks".equals(name)) {
                    if (pt.length == 1) res = m.invoke(sAtm, 25);
                    else if (pt.length == 2) res = m.invoke(sAtm, 25, false);
                    else if (pt.length == 3) res = m.invoke(sAtm, 25, false, true);
                    else if (pt.length == 4) res = m.invoke(sAtm, 25, false, true, -1);
                } else if ("getRecentTasks".equals(name) && pt.length == 3) {
                    res = m.invoke(sAtm, 25, 0, 0);
                }
                if (res instanceof List<?>) return (List<?>) res;
                if (res != null) {
                    try {
                        Object list = res.getClass().getMethod("getList").invoke(res);
                        if (list instanceof List<?>) return (List<?>) list;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void writeTasksToParcel(Parcel reply) {
        if (reply == null) return;
        LinkedHashMap<String, TaskSnap> map = new LinkedHashMap<>();
        try {
            List<?> running = invokeTaskQuery("getTasks");
            boolean firstOnExtMarked = false;
            if (running != null) {
                for (Object t : running) {
                    if (t == null) continue;
                    String pkg = extractTaskPkg(t);
                    if (shouldSkipTaskPkg(pkg)) continue;
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    int rtid = readIntField(t, "rootTaskId", tid);
                    int did = readIntField(t, "displayId", -1);
                    boolean onExt = (sVdId > 0 && did == sVdId);
                    boolean isVis = readBoolField(t, "isVisible", false);
                    int fl = 2; // isRunning
                    if (onExt) {
                        fl |= 1;
                        if (!firstOnExtMarked && isVis) {
                            fl |= 4;
                            firstOnExtMarked = true;
                        }
                    }
                    TaskSnap ts = map.get(pkg);
                    if (ts == null) {
                        ts = new TaskSnap();
                        ts.taskId = tid;
                        ts.rootTaskId = rtid;
                        ts.pkg = pkg;
                        ts.flags = fl;
                        map.put(pkg, ts);
                    } else {
                        ts.flags |= fl;
                        if (onExt && ts.taskId <= 0) ts.taskId = tid;
                    }
                }
            }

            List<?> recents = invokeTaskQuery("getRecentTasks");
            if (recents != null) {
                for (Object t : recents) {
                    if (t == null) continue;
                    String pkg = extractTaskPkg(t);
                    if (shouldSkipTaskPkg(pkg)) continue;
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    int rtid = readIntField(t, "rootTaskId", tid);
                    int did = readIntField(t, "displayId", -1);
                    boolean isRun = readBoolField(t, "isRunning", false);
                    boolean onExt = (sVdId > 0 && did == sVdId);
                    int fl = (onExt ? 1 : 0) | (isRun ? 2 : 0);
                    TaskSnap ts = map.get(pkg);
                    if (ts == null) {
                        ts = new TaskSnap();
                        ts.taskId = tid;
                        ts.rootTaskId = rtid;
                        ts.pkg = pkg;
                        ts.flags = fl;
                        map.put(pkg, ts);
                    } else {
                        ts.flags |= fl;
                        if (ts.taskId <= 0 && tid > 0) ts.taskId = tid;
                    }
                }
            }
        } catch (Throwable ignored) {}

        ArrayList<TaskSnap> extList = new ArrayList<>();
        ArrayList<TaskSnap> otherList = new ArrayList<>();
        HashMap<Integer, Integer> liveWinModes = new HashMap<>();
        try {
            List<?> rootInfos = sVdId > 0
                    ? (List<?>) sAtm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(sAtm, sVdId)
                    : (List<?>) sAtm.getClass().getMethod("getAllRootTaskInfos").invoke(sAtm);
            if (rootInfos != null) {
                for (Object info : rootInfos) {
                    if (info == null) continue;
                    int rTid = info.getClass().getField("taskId").getInt(info);
                    int wm = 0;
                    try {
                        Object cfg = info.getClass().getField("configuration").get(info);
                        if (cfg != null) {
                            Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                            if (wc != null) wm = (int) wc.getClass().getMethod("getWindowingMode").invoke(wc);
                        }
                    } catch (Throwable ignored) {}
                    liveWinModes.put(rTid, wm);
                }
            }
        } catch (Throwable ignored) {}
        for (TaskSnap ts : map.values()) {
            Integer liveWm = liveWinModes.get(ts.taskId);
            boolean isFf = liveWm != null ? (liveWm == 5) : (getAppMode(ts.pkg) == 3);
            if (isFf) ts.flags |= 8;
            if (sSplitActive && ts.pkg != null
                    && (ts.pkg.equals(sSplitSlot0Pkg) || ts.pkg.equals(sSplitSlot1Pkg))) {
                ts.flags |= 16;
            }
            if ((ts.flags & 1) != 0) extList.add(ts);
            else otherList.add(ts);
        }
        ArrayList<TaskSnap> combined = new ArrayList<>(10);
        for (TaskSnap ts : extList) {
            if (combined.size() >= 10) break;
            combined.add(ts);
        }
        for (TaskSnap ts : otherList) {
            if (combined.size() >= 10) break;
            combined.add(ts);
        }

        reply.writeInt(combined.size());
        for (TaskSnap ts : combined) {
            reply.writeInt(ts.taskId);
            reply.writeString(ts.pkg);
            reply.writeInt(ts.flags);
        }
    }

    private static boolean switchToTaskOnVd(int taskId, String pkg) {
        if (sVdId <= 0) return false;
        int mode = getAppMode(pkg);
        try {
            if (sSplitActive && pkg != null && !pkg.isEmpty() && !"com.extbridge.helper".equals(pkg)) {
                int slot = matchOrAutoFillSplitSlot(pkg);
                if (slot >= 0) {
                    if (slot == 0) {
                        if (taskId > 0) sSplitSlot0TaskId = taskId;
                        sSplitSlot0Pkg = pkg;
                    } else {
                        if (taskId > 0) sSplitSlot1TaskId = taskId;
                        sSplitSlot1Pkg = pkg;
                    }
                    relayoutSplitTasks(true);
                    notifyHelperSync();
                    return true;
                }
                exitSplitAndRestore(false);
            }
            applyAppModeCompat(pkg, mode, false);
            if (taskId > 0 && sAtm != null) {
                try {
                    for (Method m : sAtm.getClass().getMethods()) {
                        if (m.getName().equals("moveRootTaskToDisplay") && m.getParameterCount() == 2) {
                            m.invoke(sAtm, taskId, sVdId);
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
                if (mode == 1) {
                    clearTaskAlwaysOnTop(taskId);
                    forceTaskWindowingMode(taskId, 1);
                } else if (mode == 3) {
                    forceTaskWindowingMode(taskId, 5);
                }

                ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode);
                Bundle b = opts.toBundle();
                for (Method m : sAtm.getClass().getMethods()) {
                    if (m.getName().equals("startActivityFromRecents") && m.getParameterCount() == 2) {
                        m.invoke(sAtm, taskId, b);
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (pkg != null && !pkg.isEmpty()) {
                Intent li = sCtx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode);
                    sMStartActivity.invoke(sAtm, null, "com.android.shell", null, li, null, null, null, 0, 0, null, opts.toBundle());
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static float clampSplitRatio(float r) {
        return Math.max(0.25f, Math.min(0.75f, r));
    }

    private static Rect calcSplitRect(int slot, float ratio) {
        int sw = Math.max(600, sVirtW);
        int sh = Math.max(600, sVirtH);
        boolean portrait = sh > sw;
        float r = clampSplitRatio(ratio);
        int halfGap = SPLIT_GAP_PX / 2;
        if (!portrait) {
            int div = Math.max(Math.round(sw * 0.25f), Math.min(Math.round(sw * 0.75f), Math.round(sw * r)));
            return slot == 0
                    ? new Rect(0, 0, Math.max(240, div - halfGap), sh)
                    : new Rect(Math.min(sw - 240, div + halfGap), 0, sw, sh);
        } else {
            int div = Math.max(Math.round(sh * 0.25f), Math.min(Math.round(sh * 0.75f), Math.round(sh * r)));
            return slot == 0
                    ? new Rect(0, 0, sw, Math.max(240, div - halfGap))
                    : new Rect(0, Math.min(sh - 240, div + halfGap), sw, sh);
        }
    }

    private static int matchOrAutoFillSplitSlot(String pkg) {
        if (!sSplitActive || pkg == null || pkg.isEmpty()) return -1;
        if (pkg.equals(sSplitSlot0Pkg)) return 0;
        if (pkg.equals(sSplitSlot1Pkg)) return 1;
        if (sSplitSlot0Pkg == null || sSplitSlot0Pkg.isEmpty()) return 0;
        if (sSplitSlot1Pkg == null || sSplitSlot1Pkg.isEmpty()) return 1;
        return -1;
    }

    private static void clearSplitState() {
        sSplitActive = false;
        sSplitSlot0Pkg = "";
        sSplitSlot1Pkg = "";
        sSplitSlot0TaskId = -1;
        sSplitSlot1TaskId = -1;
    }

    private static int findTaskIdForPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return -1;
        List<?> running = invokeTaskQuery("getTasks");
        if (running != null) {
            for (Object t : running) {
                if (t == null) continue;
                int did = readIntField(t, "displayId", -1);
                if (did == sVdId && pkg.equals(extractTaskPkg(t))) {
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    if (tid > 0) return tid;
                }
            }
            for (Object t : running) {
                if (t == null) continue;
                if (pkg.equals(extractTaskPkg(t))) {
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    if (tid > 0) return tid;
                }
            }
        }
        List<?> recent = invokeTaskQuery("getRecentTasks");
        if (recent != null) {
            for (Object t : recent) {
                if (t == null) continue;
                if (pkg.equals(extractTaskPkg(t))) {
                    int tid = readIntField(t, "taskId", readIntField(t, "persistentId", readIntField(t, "id", -1)));
                    if (tid > 0) return tid;
                }
            }
        }
        return -1;
    }

    private static int getTaskWindowingMode(int taskId) {
        if (taskId <= 0 || sAtm == null) return 0;
        List<?> running = invokeTaskQuery("getTasks");
        if (running != null) {
            for (Object t : running) {
                if (t == null) continue;
                int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                if (tid == taskId) {
                    int wm = readIntField(t, "windowingMode", 0);
                    if (wm != 0) return wm;
                    try {
                        Object cfg = t.getClass().getField("configuration").get(t);
                        if (cfg != null) {
                            Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                            if (wc != null) {
                                return (Integer) wc.getClass().getMethod("getWindowingMode").invoke(wc);
                            }
                        }
                    } catch (Throwable ignored) {}
                    return wm;
                }
            }
        }
        return 0;
    }

    private static Object findRootTaskInfo(int taskId) {
        if (taskId <= 0 || sAtm == null) return null;
        try {
            if (sVdId > 0) {
                try {
                    List<?> infos = (List<?>) sAtm.getClass()
                            .getMethod("getAllRootTaskInfosOnDisplay", int.class)
                            .invoke(sAtm, sVdId);
                    if (infos != null) {
                        for (Object info : infos) {
                            if (info != null && info.getClass().getField("taskId").getInt(info) == taskId) {
                                return info;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            List<?> all = (List<?>) sAtm.getClass().getMethod("getAllRootTaskInfos").invoke(sAtm);
            if (all != null) {
                for (Object info : all) {
                    if (info != null && info.getClass().getField("taskId").getInt(info) == taskId) {
                        return info;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object findRootTaskToken(int taskId) {
        Object info = findRootTaskInfo(taskId);
        if (info == null) return null;
        try {
            return info.getClass().getField("token").get(info);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Rect getTaskBounds(int taskId) {
        Object info = findRootTaskInfo(taskId);
        if (info == null) return null;
        try {
            Object cfg = info.getClass().getField("configuration").get(info);
            if (cfg != null) {
                Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                if (wc != null) {
                    Object b = wc.getClass().getMethod("getBounds").invoke(wc);
                    if (b instanceof Rect) return new Rect((Rect) b);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Atomically updates alwaysOnTop / windowingMode / bounds via WindowContainerTransaction. */
    private static boolean applyWctForTask(int taskId, Boolean alwaysOnTop, int windowingMode, Rect bounds) {
        if (taskId <= 0 || sAtm == null) return false;
        try {
            Object tok = findRootTaskToken(taskId);
            if (tok == null) return false;
            Object wo = sAtm.getClass().getMethod("getWindowOrganizerController").invoke(sAtm);
            if (wo == null) return false;
            Class<?> wctCls = Class.forName("android.window.WindowContainerTransaction");
            Class<?> tokCls = Class.forName("android.window.WindowContainerToken");
            Object wct = wctCls.getConstructor().newInstance();
            if (alwaysOnTop != null) {
                wctCls.getMethod("setAlwaysOnTop", tokCls, boolean.class).invoke(wct, tok, alwaysOnTop);
            }
            if (windowingMode >= 0) {
                wctCls.getMethod("setWindowingMode", tokCls, int.class).invoke(wct, tok, windowingMode);
            }
            if (bounds != null) {
                wctCls.getMethod("setBounds", tokCls, Rect.class).invoke(wct, tok, bounds);
            }
            wo.getClass().getMethod("applyTransaction", wctCls).invoke(wo, wct);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static void clearTaskAlwaysOnTop(int taskId) {
        if (taskId <= 0 || sAtm == null) return;
        if (applyWctForTask(taskId, Boolean.FALSE, -1, null)) return;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("setTaskAlwaysOnTop") && m.getParameterCount() == 2) {
                    m.invoke(sAtm, taskId, false);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void invokeResizeTask(int taskId, Rect bounds) {
        if (taskId <= 0 || bounds == null || sAtm == null) return;
        boolean resized = false;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("resizeTask")) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 3 && pt[0] == int.class && pt[1] == Rect.class && pt[2] == int.class) {
                        m.invoke(sAtm, taskId, bounds, 0);
                        resized = true;
                        break;
                    } else if (pt.length == 2 && pt[0] == int.class && pt[1] == Rect.class) {
                        m.invoke(sAtm, taskId, bounds);
                        resized = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (!resized) {
            runCmdOut("cmd", "activity", "task", "resize", String.valueOf(taskId),
                    String.valueOf(bounds.left), String.valueOf(bounds.top),
                    String.valueOf(bounds.right), String.valueOf(bounds.bottom));
        }
    }

    private static void resizeTaskBoundsOnly(int taskId, Rect bounds) {
        resizeTaskBoundsOnly(taskId, bounds, false);
    }

    /**
     * Resizes task bounds via ATM resizeTask so both WM mBounds and SurfaceFlinger layer position update.
     * Note: WCT.setBounds updates WM mBounds without moving SurfaceFlinger transform, and resizeTask is a no-op
     * if WM mBounds already matches target, so forceSyncIfEqual nudges 2px first when needed.
     */
    private static void resizeTaskBoundsOnly(int taskId, Rect bounds, boolean forceSyncIfEqual) {
        if (taskId <= 0 || bounds == null || sAtm == null) return;
        if (forceSyncIfEqual) {
            Rect cur = getTaskBounds(taskId);
            if (cur != null && cur.equals(bounds)) {
                Rect nudge = new Rect(bounds);
                if (nudge.left >= 2) nudge.left -= 2;
                else if (nudge.right - nudge.left > 64) nudge.right -= 2;
                else nudge.bottom = Math.max(nudge.top + 64, nudge.bottom - 2);
                invokeResizeTask(taskId, nudge);
            }
        }
        invokeResizeTask(taskId, bounds);
    }

    /** Moves task to virtual display and places it in native MULTI_WINDOW (6), bypassing MIUI Freeform scaling. */
    private static void ensureTaskResizeableAndMultiWindow(int taskId, Rect bounds) {
        ensureTaskResizeableAndMultiWindow(taskId, bounds, false);
    }

    private static void ensureTaskResizeableAndMultiWindow(int taskId, Rect bounds, boolean forceSyncIfEqual) {
        if (taskId <= 0 || sAtm == null) return;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("moveRootTaskToDisplay") && m.getParameterCount() == 2) {
                    m.invoke(sAtm, taskId, sVdId);
                    break;
                }
            }
        } catch (Throwable ignored) {}
        boolean resModeOk = false;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("setTaskResizeable") && m.getParameterCount() == 2) {
                    m.invoke(sAtm, taskId, 2);
                    resModeOk = true;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        if (!resModeOk) {
            runCmdOut("cmd", "activity", "task", "resizeable", String.valueOf(taskId), "2");
        }
        // Detach MiuiFreeformModeController if task was previously FREEFORM (5)
        if (getTaskWindowingMode(taskId) == 5) {
            if (!applyWctForTask(taskId, Boolean.FALSE, 1, null)) {
                clearTaskAlwaysOnTop(taskId);
                forceTaskWindowingMode(taskId, 1);
            }
        }
        // Clear alwaysOnTop and enforce MULTI_WINDOW (6) via WCT, but resize via ATM resizeTask for SF transform sync
        if (!applyWctForTask(taskId, Boolean.FALSE, 6, null)) {
            clearTaskAlwaysOnTop(taskId);
            forceTaskWindowingMode(taskId, 6);
        }
        resizeTaskBoundsOnly(taskId, bounds, forceSyncIfEqual);
    }

    private static void bringSplitTaskFront(int taskId, Rect bounds) {
        if (taskId <= 0 || sAtm == null || sVdId <= 0) return;
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(sVdId);
            opts.setLaunchBounds(bounds);
            try {
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(opts, 6);
            } catch (Throwable ignored) {}
            Bundle b = opts.toBundle();
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("startActivityFromRecents") && m.getParameterCount() == 2) {
                    m.invoke(sAtm, taskId, b);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void applySplitSlot(int slot, int knownTid, String pkg, String cls, boolean launchIfMissing) {
        if (sVdId <= 0 || pkg == null || pkg.isEmpty()) return;
        applyAppModeCompat(pkg, 1, false);
        Rect bounds = calcSplitRect(slot, sSplitRatio);
        int tid = knownTid > 0 ? knownTid : findTaskIdForPkg(pkg);
        if (tid > 0) {
            if (slot == 0) sSplitSlot0TaskId = tid;
            else sSplitSlot1TaskId = tid;
            ensureTaskResizeableAndMultiWindow(tid, bounds, launchIfMissing);
            if (launchIfMissing) {
                bringSplitTaskFront(tid, bounds);
                ensureTaskResizeableAndMultiWindow(tid, bounds, true);
                if (sHandler != null) {
                    sHandler.postDelayed(() -> verifySplitSlotAsync(slot, pkg), 180L);
                }
            }
            return;
        }
        if (!launchIfMissing || sMStartActivity == null || sCtx == null) return;
        try {
            Intent intent = null;
            if (cls != null && !cls.isEmpty()) {
                intent = new Intent();
                intent.setComponent(new ComponentName(pkg, cls));
            } else {
                intent = sCtx.getPackageManager().getLaunchIntentForPackage(pkg);
            }
            if (intent == null) return;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(sVdId);
            opts.setLaunchBounds(bounds);
            try {
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(opts, 6);
            } catch (Throwable ignored) {}
            sMStartActivity.invoke(sAtm, null, "com.android.shell", null, intent, null, null, null, 0, 0, null, opts.toBundle());
            if (sHandler != null) {
                sHandler.postDelayed(() -> verifySplitSlotAsync(slot, pkg), 220L);
                sHandler.postDelayed(() -> verifySplitSlotAsync(slot, pkg), 600L);
                sHandler.postDelayed(() -> verifySplitSlotAsync(slot, pkg), 1200L);
                sHandler.postDelayed(() -> verifySplitSlotAsync(slot, pkg), 1850L);
            }
        } catch (Throwable ignored) {}
    }

    private static void verifySplitSlotAsync(int slot, String pkg) {
        synchronized (sSplitLock) {
            if (!sSplitActive || sVdId <= 0 || pkg == null || pkg.isEmpty()) return;
            String curPkg = (slot == 0) ? sSplitSlot0Pkg : sSplitSlot1Pkg;
            if (!pkg.equals(curPkg)) return;
            int prevTid = (slot == 0) ? sSplitSlot0TaskId : sSplitSlot1TaskId;
            int tid = findTaskIdForPkg(pkg);
            if (tid > 0) {
                if (slot == 0) sSplitSlot0TaskId = tid;
                else sSplitSlot1TaskId = tid;
                Rect bounds = calcSplitRect(slot, sSplitRatio);
                ensureTaskResizeableAndMultiWindow(tid, bounds, prevTid <= 0);
            }
        }
    }

    private static int getEmptySplitSlot() {
        if (!sSplitActive) return -1;
        boolean f0 = sSplitSlot0Pkg != null && !sSplitSlot0Pkg.isEmpty();
        boolean f1 = sSplitSlot1Pkg != null && !sSplitSlot1Pkg.isEmpty();
        if (f0 && !f1) return 1;
        if (!f0 && f1) return 0;
        return -1;
    }

    private static int findExtLauncherTaskId() {
        if (sVdId <= 0 || sAtm == null) return -1;
        try {
            List<?> running = invokeTaskQuery("getTasks");
            if (running != null) {
                for (Object t : running) {
                    if (t == null) continue;
                    int did = readIntField(t, "displayId", -1);
                    if (did != sVdId) continue;
                    String pkg = extractTaskPkg(t);
                    if (!"com.extbridge.helper".equals(pkg)) continue;
                    Object top = null;
                    try { top = t.getClass().getField("topActivity").get(t); } catch (Throwable ignored) {}
                    if (top == null) {
                        try { top = t.getClass().getField("baseActivity").get(t); } catch (Throwable ignored) {}
                    }
                    if (top instanceof ComponentName && ((ComponentName) top).getClassName().contains("LauncherActivity")) {
                        int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                        if (tid > 0) return tid;
                    }
                }
            }
            List<?> infos = (List<?>) sAtm.getClass()
                    .getMethod("getAllRootTaskInfosOnDisplay", int.class)
                    .invoke(sAtm, sVdId);
            if (infos != null) {
                for (Object info : infos) {
                    if (info == null) continue;
                    Object top = null;
                    try { top = info.getClass().getField("topActivity").get(info); } catch (Throwable ignored) {}
                    if (top instanceof ComponentName) {
                        ComponentName cn = (ComponentName) top;
                        if ("com.extbridge.helper".equals(cn.getPackageName())
                                && cn.getClassName().contains("LauncherActivity")) {
                            return info.getClass().getField("taskId").getInt(info);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static void ensureLauncherTaskFullscreenInBg() {
        int lTid = findExtLauncherTaskId();
        if (lTid <= 0) return;
        clearTaskAlwaysOnTop(lTid);
        forceTaskWindowingMode(lTid, 1);
        resizeTaskBoundsOnly(lTid, new Rect(0, 0, Math.max(600, sVirtW), Math.max(600, sVirtH)), false);
    }

    private static void syncLauncherForSplit(int emptySlot, boolean bringFront) {
        if (!sSplitActive || emptySlot < 0 || sVdId <= 0 || sAtm == null) return;
        Rect bounds = calcSplitRect(emptySlot, sSplitRatio);
        int lTid = findExtLauncherTaskId();
        if (lTid > 0) {
            ensureTaskResizeableAndMultiWindow(lTid, bounds, bringFront);
            if (bringFront) {
                bringSplitTaskFront(lTid, bounds);
                ensureTaskResizeableAndMultiWindow(lTid, bounds, true);
                if (sHandler != null) {
                    sHandler.postDelayed(() -> verifySplitLauncherAsync(emptySlot), 180L);
                }
            }
            return;
        }
        if (!bringFront || sMStartActivity == null) return;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.extbridge.helper", "com.extbridge.helper.LauncherActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (sCtlBinder != null) {
                Bundle b = new Bundle();
                b.putBinder("cb", sCtlBinder);
                intent.putExtras(b);
                intent.putExtra("extId", sVdId);
            }
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(sVdId);
            opts.setLaunchBounds(bounds);
            try {
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(opts, 6);
            } catch (Throwable ignored) {}
            sMStartActivity.invoke(sAtm, null, "com.android.shell", null, intent, null, null, null, 0, 0, null, opts.toBundle());
            if (sHandler != null) {
                sHandler.postDelayed(() -> verifySplitLauncherAsync(emptySlot), 220L);
                sHandler.postDelayed(() -> verifySplitLauncherAsync(emptySlot), 600L);
            }
        } catch (Throwable ignored) {}
    }

    private static void verifySplitLauncherAsync(int expectedSlot) {
        synchronized (sSplitLock) {
            if (!sSplitActive || sVdId <= 0 || getEmptySplitSlot() != expectedSlot) return;
            int lTid = findExtLauncherTaskId();
            if (lTid > 0) {
                ensureTaskResizeableAndMultiWindow(lTid, calcSplitRect(expectedSlot, sSplitRatio), true);
            }
        }
    }

    private static void relayoutSplitTasks(boolean bringFront) {
        synchronized (sSplitLock) {
            if (!sSplitActive || sVdId <= 0) return;
            int emptySlot = getEmptySplitSlot();
            if (emptySlot >= 0) {
                syncLauncherForSplit(emptySlot, bringFront);
            } else {
                ensureLauncherTaskFullscreenInBg();
            }
            if (sSplitSlot0Pkg != null && !sSplitSlot0Pkg.isEmpty()) {
                applySplitSlot(0, sSplitSlot0TaskId, sSplitSlot0Pkg, null, bringFront);
            }
            if (sSplitSlot1Pkg != null && !sSplitSlot1Pkg.isEmpty()) {
                applySplitSlot(1, sSplitSlot1TaskId, sSplitSlot1Pkg, null, bringFront);
            }
        }
    }

    private static boolean splitAssign(int slot, int tid, String pkg, String cls) {
        return handleSplitAction(slot == 0 ? 1 : 2, tid, pkg, cls);
    }

    private static boolean handleSplitAction(int action, int arg, String pkg, String cls) {
        synchronized (sSplitLock) {
            if (sVdId <= 0 || sAtm == null) return false;
            if (action == 1 || action == 2) {
                int slot = (action == 1) ? 0 : 1;
                if (pkg == null || pkg.isEmpty() || "com.extbridge.helper".equals(pkg)) return false;
                sSplitActive = true;
                if (slot == 0) {
                    sSplitSlot0Pkg = pkg;
                    sSplitSlot0TaskId = arg;
                    if (pkg.equals(sSplitSlot1Pkg)) {
                        sSplitSlot1Pkg = "";
                        sSplitSlot1TaskId = -1;
                    }
                } else {
                    sSplitSlot1Pkg = pkg;
                    sSplitSlot1TaskId = arg;
                    if (pkg.equals(sSplitSlot0Pkg)) {
                        sSplitSlot0Pkg = "";
                        sSplitSlot0TaskId = -1;
                    }
                }
                int emptySlot = getEmptySplitSlot();
                if (emptySlot >= 0) {
                    syncLauncherForSplit(emptySlot, true);
                } else {
                    ensureLauncherTaskFullscreenInBg();
                }
                if (slot == 0) {
                    applySplitSlot(0, sSplitSlot0TaskId, sSplitSlot0Pkg, cls, true);
                    if (sSplitSlot1Pkg != null && !sSplitSlot1Pkg.isEmpty()) {
                        applySplitSlot(1, sSplitSlot1TaskId, sSplitSlot1Pkg, null, true);
                    }
                } else {
                    applySplitSlot(1, sSplitSlot1TaskId, sSplitSlot1Pkg, cls, true);
                    if (sSplitSlot0Pkg != null && !sSplitSlot0Pkg.isEmpty()) {
                        applySplitSlot(0, sSplitSlot0TaskId, sSplitSlot0Pkg, null, true);
                    }
                }
                notifyHelperSync();
                return true;
            } else if (action == 3) {
                if (sSplitActive && arg == 0) {
                    return handleSplitAction(6, 0, null, null);
                }
                ArrayList<String> cands = new ArrayList<>();
                ArrayList<Integer> candTids = new ArrayList<>();
                List<?> running = invokeTaskQuery("getTasks");
                if (running != null) {
                    for (int pass = 0; pass < 2 && cands.size() < 2; pass++) {
                        boolean onlyExt = (pass == 0);
                        for (Object t : running) {
                            if (t == null) continue;
                            int did = readIntField(t, "displayId", -1);
                            if (onlyExt && did != sVdId) continue;
                            String p = extractTaskPkg(t);
                            if (shouldSkipTaskPkg(p) || "android".equals(p)) continue;
                            int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                            if (tid > 0 && !cands.contains(p)) {
                                cands.add(p);
                                candTids.add(tid);
                                if (cands.size() >= 2) break;
                            }
                        }
                    }
                }
                if (cands.size() < 2) {
                    List<?> recent = invokeTaskQuery("getRecentTasks");
                    if (recent != null) {
                        for (Object t : recent) {
                            if (t == null) continue;
                            String p = extractTaskPkg(t);
                            if (shouldSkipTaskPkg(p) || "android".equals(p)) continue;
                            int tid = readIntField(t, "taskId", readIntField(t, "persistentId", readIntField(t, "id", -1)));
                            if (tid > 0 && !cands.contains(p)) {
                                cands.add(p);
                                candTids.add(tid);
                                if (cands.size() >= 2) break;
                            }
                        }
                    }
                }
                if (cands.isEmpty()) return false;
                sSplitActive = true;
                sSplitSlot0Pkg = cands.get(0);
                sSplitSlot0TaskId = candTids.get(0);
                if (cands.size() >= 2) {
                    sSplitSlot1Pkg = cands.get(1);
                    sSplitSlot1TaskId = candTids.get(1);
                } else {
                    sSplitSlot1Pkg = "";
                    sSplitSlot1TaskId = -1;
                }
                relayoutSplitTasks(true);
                notifyHelperSync();
                return true;
            } else if (action == 4) {
                sSplitRatio = clampSplitRatio(arg / 100f);
                if (sSplitActive) {
                    relayoutSplitTasks(false);
                }
                notifyHelperSync();
                return true;
            } else if (action == 5) {
                String tmpPkg = sSplitSlot0Pkg;
                int tmpTid = sSplitSlot0TaskId;
                sSplitSlot0Pkg = sSplitSlot1Pkg;
                sSplitSlot0TaskId = sSplitSlot1TaskId;
                sSplitSlot1Pkg = tmpPkg;
                sSplitSlot1TaskId = tmpTid;
                if (sSplitActive) {
                    relayoutSplitTasks(true);
                }
                notifyHelperSync();
                return true;
            } else if (action == 6) {
                exitSplitAndRestore(true);
                return true;
            }
            return false;
        }
    }

    private static void detachPkgFromSplit(String pkg, int taskId) {
        synchronized (sSplitLock) {
            if (!sSplitActive) return;
            boolean changed = false;
            if ((taskId > 0 && taskId == sSplitSlot0TaskId) || (pkg != null && !pkg.isEmpty() && pkg.equals(sSplitSlot0Pkg))) {
                sSplitSlot0Pkg = "";
                sSplitSlot0TaskId = -1;
                changed = true;
            }
            if ((taskId > 0 && taskId == sSplitSlot1TaskId) || (pkg != null && !pkg.isEmpty() && pkg.equals(sSplitSlot1Pkg))) {
                sSplitSlot1Pkg = "";
                sSplitSlot1TaskId = -1;
                changed = true;
            }
            if (!changed) return;
            boolean has0 = sSplitSlot0Pkg != null && !sSplitSlot0Pkg.isEmpty();
            boolean has1 = sSplitSlot1Pkg != null && !sSplitSlot1Pkg.isEmpty();
            if (!has0 && !has1) {
                clearSplitState();
                ensureLauncherTaskFullscreenInBg();
            } else {
                relayoutSplitTasks(true);
            }
            notifyHelperSync();
        }
    }

    /** Restores split tasks back to fullscreen (unless configured freeform) and notifies helper. */
    private static void exitSplitAndRestore(boolean focusSlot0) {
        synchronized (sSplitLock) {
            int t0 = sSplitSlot0TaskId > 0 ? sSplitSlot0TaskId : findTaskIdForPkg(sSplitSlot0Pkg);
            int t1 = sSplitSlot1TaskId > 0 ? sSplitSlot1TaskId : findTaskIdForPkg(sSplitSlot1Pkg);
            String p0 = sSplitSlot0Pkg;
            String p1 = sSplitSlot1Pkg;
            clearSplitState();
            ensureLauncherTaskFullscreenInBg();
            Rect fullBounds = new Rect(0, 0, Math.max(600, sVirtW), Math.max(600, sVirtH));
            if (t1 > 0 && getAppMode(p1) != 3) {
                clearTaskAlwaysOnTop(t1);
                forceTaskWindowingMode(t1, 1);
                resizeTaskBoundsOnly(t1, fullBounds, false);
            }
            if (t0 > 0 && getAppMode(p0) != 3) {
                clearTaskAlwaysOnTop(t0);
                forceTaskWindowingMode(t0, 1);
                resizeTaskBoundsOnly(t0, fullBounds, false);
                if (focusSlot0) {
                    switchToTaskOnVd(t0, p0);
                }
            }
            notifyHelperSync();
        }
    }

    /** Switches a task from floating Freeform small window (5) back to Fullscreen (1); enabling Freeform is forbidden in DeX mode. */
    private static int setTaskLiveWindowMode(int taskId, String pkg, int enableFreeform) {
        if (sVdId <= 0 || sAtm == null) return -1;
        int tid = taskId > 0 ? taskId : findTaskIdForPkg(pkg);
        boolean currentlyFf = tid > 0 && getTaskWindowingMode(tid) == 5;
        boolean wantFf = enableFreeform < 0 ? !currentlyFf : (enableFreeform != 0);
        if (wantFf) {
            return -2;
        }

        if (sSplitActive && pkg != null && !pkg.isEmpty()) {
            detachPkgFromSplit(pkg, tid);
        }

        if (pkg != null && !pkg.isEmpty()) {
            synchronized (sAppModes) {
                if (Integer.valueOf(3).equals(sAppModes.get(pkg))) {
                    sAppModes.remove(pkg);
                    saveAppModesLocked();
                }
            }
            applyAppModeCompat(pkg, getAppMode(pkg), true);
        }

        if (tid > 0) {
            try {
                for (Method m : sAtm.getClass().getMethods()) {
                    if (m.getName().equals("moveRootTaskToDisplay") && m.getParameterCount() == 2) {
                        m.invoke(sAtm, tid, sVdId);
                        break;
                    }
                }
            } catch (Throwable ignored) {}
            clearTaskAlwaysOnTop(tid);
            Rect fullBounds = new Rect(0, 0, Math.max(600, sVirtW), Math.max(600, sVirtH));
            forceTaskWindowingMode(tid, 1);
            resizeTaskBoundsOnly(tid, fullBounds, true);
            try {
                Bundle b = makeExtLaunchOptionsForMode(sVdId, 1).toBundle();
                for (Method m : sAtm.getClass().getMethods()) {
                    if (m.getName().equals("startActivityFromRecents") && m.getParameterCount() == 2) {
                        m.invoke(sAtm, tid, b);
                        break;
                    }
                }
            } catch (Throwable ignored) {}
            notifyHelperSync();
            return 0;
        }

        if (pkg != null && !pkg.isEmpty() && sCtx != null && sMStartActivity != null) {
            try {
                Intent li = sCtx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, 1);
                    sMStartActivity.invoke(sAtm, null, "com.android.shell", null, li, null, null, null, 0, 0, null, opts.toBundle());
                    notifyHelperSync();
                    return 0;
                }
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static boolean removeTaskById(int taskId) {
        boolean ok = removeTaskRaw(taskId);
        if (ok && sSplitActive) {
            detachPkgFromSplit(null, taskId);
        } else if (ok) {
            notifyHelperSync();
        }
        return ok;
    }

    /**
     * Prevents MIUI Home's shared Flutter Dock state machine from being corrupted by stale or
     * secondary-display com.miui.home tasks (e.g. left over from earlier external-desktop builds).
     * When multiple com.miui.home tasks or a non-Display-0 com.miui.home task are detected, removes
     * the secondary task(s) and restarts com.miui.home cleanly on Display 0 only if needed.
     */
    private static void sanitizeSystemHomeTasks(boolean checkCreatedDisplayConfig) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastHomeSanitizeMs < 15000L) return;

        int homeCount = 0;
        int disp0Count = 0;
        boolean hasNonZeroDisplay = false;
        boolean homeTopOnDisp0 = false;
        boolean seenTopOnDisp0 = false;
        ArrayList<Integer> nonZeroTaskIds = new ArrayList<>();
        ArrayList<Integer> dupDisp0TaskIds = new ArrayList<>();

        try {
            List<?> running = invokeTaskQuery("getTasks");
            if (running != null) {
                for (Object t : running) {
                    if (t == null) continue;
                    String pkg = extractTaskPkg(t);
                    int did = readIntField(t, "displayId", -1);
                    if (did == 0 && !seenTopOnDisp0) {
                        seenTopOnDisp0 = true;
                        homeTopOnDisp0 = "com.miui.home".equals(pkg);
                    }
                    if (!"com.miui.home".equals(pkg)) continue;
                    homeCount++;
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    if (did != 0) {
                        hasNonZeroDisplay = true;
                        if (tid > 0) nonZeroTaskIds.add(tid);
                    } else {
                        disp0Count++;
                        if (disp0Count > 1 && tid > 0) dupDisp0TaskIds.add(tid);
                    }
                }
            }
        } catch (Throwable ignored) {}

        boolean staleCreatedConfig = false;
        if (checkCreatedDisplayConfig && !hasNonZeroDisplay && homeCount <= 1) {
            staleCreatedConfig = hasStaleMiuiHomeCreatedConfig();
        }

        if (!hasNonZeroDisplay && homeCount <= 1 && !staleCreatedConfig) {
            return;
        }

        sLastHomeSanitizeMs = now;
        System.out.println("EXTBRIDGE_SANITIZE_MIUI_HOME count=" + homeCount
                + " nonZero=" + hasNonZeroDisplay
                + " staleCfg=" + staleCreatedConfig
                + " homeTop0=" + homeTopOnDisp0);

        for (int tid : nonZeroTaskIds) {
            removeTaskRaw(tid);
        }
        for (int tid : dupDisp0TaskIds) {
            removeTaskRaw(tid);
        }

        runCmdOut("am", "force-stop", "com.miui.home");
        if (homeTopOnDisp0) {
            runCmdOut("am", "start",
                    "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.HOME",
                    "--display", "0");
        }
    }

    private static boolean removeTaskRaw(int taskId) {
        if (taskId <= 0 || sAtm == null) return false;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("removeTask") && m.getParameterCount() == 1) {
                    Object r = m.invoke(sAtm, taskId);
                    return !(r instanceof Boolean) || (Boolean) r;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean hasStaleMiuiHomeCreatedConfig() {
        try {
            String dump = runCmdOut("dumpsys", "activity", "com.miui.home");
            if (dump == null || dump.isEmpty()) return false;
            int from = 0;
            while (true) {
                int cIdx = dump.indexOf("created_config:", from);
                if (cIdx < 0) break;
                int endIdx = dump.indexOf("override_config:", cIdx);
                String seg = (endIdx > cIdx)
                        ? dump.substring(cIdx, endIdx)
                        : dump.substring(cIdx, Math.min(dump.length(), cIdx + 2048));
                int mIdx = seg.indexOf("MiuiConfiguration");
                if (mIdx >= 0) {
                    int dIdx = seg.indexOf("displayId:", mIdx);
                    if (dIdx >= 0) {
                        int p = dIdx + "displayId:".length();
                        while (p < seg.length() && seg.charAt(p) == ' ') p++;
                        int q = p;
                        while (q < seg.length() && Character.isDigit(seg.charAt(q))) q++;
                        if (q > p) {
                            int cfgDid = Integer.parseInt(seg.substring(p, q));
                            if (cfgDid != 0) return true;
                        }
                    }
                }
                from = cIdx + 15;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String runCmdOut(String... cmd) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try { p.getOutputStream().close(); } catch (Throwable ignored) {}
            final Process proc = p;
            Thread killer = new Thread(() -> {
                try {
                    if (!proc.waitFor(3000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly();
                    }
                } catch (Throwable ignored) {}
            }, "ExtCmdTimeout");
            killer.setDaemon(true);
            killer.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() < 262144) {
                        sb.append(line).append('\n');
                    }
                }
            }
            p.waitFor(3000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        } finally {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
            }
        }
        return sb.toString();
    }

    private static void refreshSysStringsIfNeeded(boolean wifiOn, boolean btOn) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastSysCheckMs < 2500L) return;
        sLastSysCheckMs = now;

        String ssid = "";
        if (wifiOn) {
            try {
                String ws = runCmdOut("cmd", "wifi", "status");
                int idx = ws.indexOf("Wifi is connected to \"");
                if (idx >= 0) {
                    int s = idx + "Wifi is connected to \"".length();
                    int e = ws.indexOf('"', s);
                    if (e > s) ssid = ws.substring(s, e);
                }
            } catch (Throwable ignored) {}
        }
        sCachedWifiSsid = ssid;

        String btDev = "";
        if (btOn) {
            try {
                String bs = runCmdOut("dumpsys", "bluetooth_manager");
                String[] lines = bs.split("\n");
                for (String ln : lines) {
                    String t = ln.trim();
                    if (t.contains("mActiveDevice:") && !t.endsWith("null")) {
                        int sp = t.indexOf('(');
                        int ep = t.indexOf(')', sp + 1);
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
            } catch (Throwable ignored) {}
        }
        sCachedBtName = btDev;
    }

    private static void writeSysStatusToParcel(Parcel reply) {
        if (reply == null) return;
        boolean wifiOn = false;
        boolean btOn = false;
        boolean airplaneOn = false;
        boolean powerSaveOn = false;
        try {
            WifiManager wm = (WifiManager) sCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) wifiOn = wm.isWifiEnabled();
        } catch (Throwable ignored) {}
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba != null) btOn = ba.isEnabled();
        } catch (Throwable ignored) {}
        try {
            airplaneOn = Settings.Global.getInt(sCtx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (Throwable ignored) {}
        try {
            PowerManager pm = (PowerManager) sCtx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) powerSaveOn = pm.isPowerSaveMode();
        } catch (Throwable ignored) {}

        refreshSysStringsIfNeeded(wifiOn, btOn);
        reply.writeInt(wifiOn ? 1 : 0);
        reply.writeString(sCachedWifiSsid);
        reply.writeInt(btOn ? 1 : 0);
        reply.writeString(sCachedBtName);
        reply.writeInt(airplaneOn ? 1 : 0);
        reply.writeInt(powerSaveOn ? 1 : 0);
        reply.writeInt(sScreenDimmed ? 1 : 0);
    }

    private static final Object sScreenDimLock = new Object();

    private static boolean setMainScreenDim(int arg) {
        synchronized (sScreenDimLock) {
            try {
                boolean wantDim = (arg < 0) ? !sScreenDimmed : (arg == 1);
                if (!wantDim && !sScreenDimmed) {
                    return true;
                }
                if (wantDim) {
                if (!sScreenDimmed) {
                    sSavedBrightMode = 0;
                    sSavedBrightInt = 100;
                    sSavedBrightFloat = -1f;
                    try {
                        String cur = runCmdOut("sh", "-c",
                                "printf '%s|%s|%s' \"$(settings get system screen_brightness_mode)\" "
                                + "\"$(settings get system screen_brightness)\" "
                                + "\"$(cmd display get-brightness --id 0)\"");
                        if (cur != null) {
                            String[] parts = cur.trim().split("\\|");
                            if (parts.length >= 1) {
                                try { sSavedBrightMode = Integer.parseInt(parts[0].trim()); } catch (Throwable ignored) {}
                            }
                            if (parts.length >= 2) {
                                try { sSavedBrightInt = Integer.parseInt(parts[1].trim()); } catch (Throwable ignored) {}
                            }
                            if (parts.length >= 3) {
                                try {
                                    float f = Float.parseFloat(parts[2].trim());
                                    if (f > 0f && f <= 1f) sSavedBrightFloat = f;
                                } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                float minFloat = 0.00171f;
                try {
                    if (sDm != null) {
                        Display d0 = sDm.getDisplay(0);
                        if (d0 != null) {
                            Object bi = Display.class.getMethod("getBrightnessInfo").invoke(d0);
                            if (bi != null) {
                                float bm = bi.getClass().getField("brightnessMinimum").getFloat(bi);
                                if (bm > 0f && bm <= 0.1f) minFloat = bm;
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                runCmdOut("sh", "-c",
                        "settings put system screen_brightness_mode 0; "
                        + "settings put system screen_brightness 1; "
                        + "cmd display set-brightness " + String.format(java.util.Locale.US, "%.6f", minFloat) + " --id 0");
                sScreenDimmed = true;
                System.out.println("EXTBRIDGE_SCREEN_DIM=1 min=" + minFloat + " savedMode=" + sSavedBrightMode + " savedFloat=" + sSavedBrightFloat);
                return true;
            } else {
                int restoreInt = Math.max(1, Math.min(255, sSavedBrightInt > 0 ? sSavedBrightInt : 100));
                float restoreFloat = (sSavedBrightFloat > 0f && sSavedBrightFloat <= 1f)
                        ? sSavedBrightFloat : (restoreInt / 255f);
                runCmdOut("sh", "-c",
                        "settings put system screen_brightness " + restoreInt + "; "
                        + "cmd display set-brightness " + String.format(java.util.Locale.US, "%.6f", restoreFloat) + " --id 0; "
                        + "settings put system screen_brightness_mode " + (sSavedBrightMode == 1 ? 1 : 0));
                sScreenDimmed = false;
                System.out.println("EXTBRIDGE_SCREEN_DIM=0 restoreFloat=" + restoreFloat + " mode=" + sSavedBrightMode);
                return true;
            }
        } catch (Throwable t) {
            System.out.println("EXTBRIDGE_SCREEN_DIM_ERR=" + t);
            return false;
        }
        }
    }

    private static boolean execSysAction(int act, int arg, String str) {
        sLastSysCheckMs = 0L;
        try {
            if (act == 6) { // Main screen minimum brightness / blackout toggle
                return setMainScreenDim(arg);
            } else if (act == 1) { // Wi-Fi toggle
                WifiManager wm = (WifiManager) sCtx.getSystemService(Context.WIFI_SERVICE);
                boolean target = (arg < 0) ? (wm == null || !wm.isWifiEnabled()) : (arg == 1);
                runCmdOut("cmd", "wifi", "set-wifi-enabled", target ? "enabled" : "disabled");
                return true;
            } else if (act == 2) { // Bluetooth toggle
                BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                boolean target = (arg < 0) ? (ba == null || !ba.isEnabled()) : (arg == 1);
                runCmdOut("cmd", "bluetooth_manager", target ? "enable" : "disable");
                return true;
            } else if (act == 3) { // Airplane toggle
                boolean cur = Settings.Global.getInt(sCtx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                boolean target = (arg < 0) ? !cur : (arg == 1);
                runCmdOut("cmd", "connectivity", "airplane-mode", target ? "enable" : "disable");
                return true;
            } else if (act == 4) { // Power saver toggle
                PowerManager pm = (PowerManager) sCtx.getSystemService(Context.POWER_SERVICE);
                boolean target = (arg < 0) ? (pm == null || !pm.isPowerSaveMode()) : (arg == 1);
                boolean done = false;
                if (pm != null) {
                    try {
                        Object res = PowerManager.class.getMethod("setPowerSaveModeEnabled", boolean.class).invoke(pm, target);
                        done = !(res instanceof Boolean) || (Boolean) res;
                    } catch (Throwable ignored) {}
                }
                if (!done) {
                    Settings.Global.putInt(sCtx.getContentResolver(), "low_power", target ? 1 : 0);
                }
                return true;
            } else if (act == 5 && str != null && !str.isEmpty()) { // Open settings action on sVdId
                Intent i = new Intent(str);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                int targetDid = (arg >= 0) ? arg : (sVdId > 0 ? sVdId : 0);
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(targetDid);
                sMStartActivity.invoke(sAtm, null, "com.android.shell", null, i, null, null, null, 0, 0, null, opts.toBundle());
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean grantAppWidgetBind() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder awB = (IBinder) sm.getMethod("getService", String.class).invoke(null, "appwidget");
            if (awB == null) return false;
            Class<?> iawStub = Class.forName("com.android.internal.appwidget.IAppWidgetService$Stub");
            Object aw = iawStub.getMethod("asInterface", IBinder.class).invoke(null, awB);
            if (aw == null) return false;
            aw.getClass().getMethod("setBindAppWidgetPermission", String.class, int.class, boolean.class)
                    .invoke(aw, "com.extbridge.helper", 0, true);
            Object has = aw.getClass().getMethod("hasBindAppWidgetPermission", String.class, int.class)
                    .invoke(aw, "com.extbridge.helper", 0);
            return (has instanceof Boolean) && (Boolean) has;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void refreshMirrorTelemetryIfNeeded() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastMirTelemetryMs < 1800L) return;
        sLastMirTelemetryMs = now;

        // 1. Audio output route + volume detail
        String route = "平板外放";
        String audioDet = "";
        try {
            AudioManager am = (AudioManager) sCtx.getSystemService(Context.AUDIO_SERVICE);
            int curVol = am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : 0;
            int maxVol = am != null ? Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) : 15;
            boolean muted = am != null && (curVol == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC));

            String audioDump = runCmdOut("sh", "-c", "dumpsys audio 2>/dev/null | grep -A 8 'STREAM_MUSIC:' | grep 'Devices:' | head -n 1");
            String devStr = audioDump != null ? audioDump.trim().toLowerCase() : "";
            String btName = sCachedBtName != null ? sCachedBtName : "";
            if (devStr.contains("hdmi") || devStr.contains("aux_digital")) {
                route = "HDMI / DP 外接音频";
            } else if (devStr.contains("bt_a2dp") || devStr.contains("ble_headset") || devStr.contains("bt_sco")) {
                route = btName.isEmpty() ? "蓝牙音频设备" : ("蓝牙 · " + btName);
            } else if (devStr.contains("usb")) {
                route = "USB-C 外置声卡";
            } else if (devStr.contains("wired") || devStr.contains("headphone") || devStr.contains("headset")) {
                route = "有线耳机";
            } else if (devStr.contains("speaker") || am != null) {
                // Double check connected AudioDeviceInfo if dumpsys line wasn't matched
                boolean hasHdmi = false, hasBt = false, hasUsb = false, hasWired = false;
                String extraDevName = "";
                if (am != null) {
                    AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                    if (outs != null) {
                        for (AudioDeviceInfo d : outs) {
                            if (d == null) continue;
                            int tp = d.getType();
                            if (tp == AudioDeviceInfo.TYPE_HDMI || tp == AudioDeviceInfo.TYPE_HDMI_ARC || tp == 10 || tp == 11) {
                                hasHdmi = true;
                            } else if (tp == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || tp == AudioDeviceInfo.TYPE_BLE_HEADSET || tp == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                                hasBt = true;
                                if (d.getProductName() != null) extraDevName = d.getProductName().toString();
                            } else if (tp == AudioDeviceInfo.TYPE_USB_DEVICE || tp == AudioDeviceInfo.TYPE_USB_HEADSET || tp == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                                hasUsb = true;
                            } else if (tp == AudioDeviceInfo.TYPE_WIRED_HEADSET || tp == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                                hasWired = true;
                            }
                        }
                    }
                }
                if (devStr.isEmpty()) {
                    if (hasBt) route = extraDevName.isEmpty() ? "蓝牙音频" : ("蓝牙 · " + extraDevName);
                    else if (hasUsb) route = "USB 音频";
                    else if (hasWired) route = "有线耳机";
                    else if (hasHdmi) route = "HDMI 外接音频";
                    else route = "平板扬声器";
                } else {
                    route = "平板扬声器";
                }
            }
            audioDet = muted ? "静音 (0%)" : ("媒体音量 " + Math.round(curVol * 100f / maxVol) + "% (" + curVol + "/" + maxVol + ")");
        } catch (Throwable ignored) {}
        sCachedAudioRoute = route;
        sCachedAudioDetail = audioDet;

        // 2. Now-playing MediaSession via dumpsys media_session
        String mPkg = "";
        int mState = 0;
        String mTitle = "";
        String mSub = "";
        try {
            String ms = runCmdOut("dumpsys", "media_session");
            if (ms != null) {
                String curPkg = "";
                int curState = 0;
                String curDesc = "";
                String bestPkg = "";
                int bestScore = -1;
                String bestTitle = "";
                String bestSub = "";
                int bestState = 0;

                for (String ln : ms.split("\n")) {
                    String t = ln.trim();
                    if (t.startsWith("package=")) {
                        curPkg = t.substring("package=".length()).trim();
                        curState = 0;
                        curDesc = "";
                    } else if (t.contains("state=PLAYING")) {
                        curState = 3;
                    } else if (t.contains("state=PAUSED")) {
                        curState = 2;
                    } else if (t.contains("state=STOPPED")) {
                        curState = 1;
                    } else if (t.startsWith("metadata:") && t.contains("description=")) {
                        int di = t.indexOf("description=");
                        curDesc = t.substring(di + "description=".length()).trim();
                        if (!curPkg.isEmpty() && !"com.android.server.telecom".equals(curPkg) && !curDesc.isEmpty() && !"null".equals(curDesc)) {
                            int score = (curState == 3 ? 30 : (curState == 2 ? 20 : 10));
                            if (score > bestScore) {
                                bestScore = score;
                                bestPkg = curPkg;
                                bestState = curState;
                                String[] segs = curDesc.split(",");
                                bestTitle = segs.length > 0 ? segs[0].trim() : curDesc;
                                if (segs.length > 1) {
                                    String s1 = segs[1].trim();
                                    bestSub = "null".equals(s1) ? "" : s1;
                                } else {
                                    bestSub = "";
                                }
                            }
                        }
                    }
                }
                if (bestScore >= 0) {
                    mPkg = bestPkg;
                    mState = bestState;
                    mTitle = bestTitle;
                    mSub = bestSub;
                }
            }
        } catch (Throwable ignored) {}
        sCachedMediaPkg = mPkg;
        sCachedMediaState = mState;
        sCachedMediaTitle = mTitle;
        sCachedMediaSub = mSub;

        // 3. Battery details via sticky ACTION_BATTERY_CHANGED intent (zero subprocess fork)
        String bDet = "";
        try {
            Intent bat = (sCtx != null) ? sCtx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)) : null;
            if (bat != null) {
                int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                int temp10 = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                int voltMv = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                String src = ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) ? "AC快充"
                        : (((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) ? "USB供电"
                        : (((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) ? "无线充电" : "电池供电"));
                if (temp10 > 0) src += String.format(Locale.US, " · %.1f℃", temp10 / 10f);
                if (voltMv > 1000) src += String.format(Locale.US, " · %.2fV", voltMv / 1000f);
                bDet = src;
            }
        } catch (Throwable ignored) {}
        sCachedBattDetail = bDet;

        // 4. Wi-Fi link speed / RSSI
        String wDet = "";
        try {
            WifiManager wm = (WifiManager) sCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getLinkSpeed() > 0) {
                    wDet = wi.getLinkSpeed() + " Mbps · " + wi.getRssi() + " dBm";
                }
            }
        } catch (Throwable ignored) {}
        sCachedWifiDetail = wDet;

        // 5. Today's calendar instances via ContentResolver
        String calText = "";
        try {
            java.util.Calendar calStart = java.util.Calendar.getInstance();
            long nowEpoch = calStart.getTimeInMillis();
            calStart.set(java.util.Calendar.HOUR_OF_DAY, 0);
            calStart.set(java.util.Calendar.MINUTE, 0);
            calStart.set(java.util.Calendar.SECOND, 0);
            long dayStart = calStart.getTimeInMillis();
            long dayEnd = dayStart + 48L * 3600L * 1000L; // Today + tomorrow
            Uri uri = Uri.parse("content://com.android.calendar/instances/when/" + dayStart + "/" + dayEnd);
            StringBuilder sb = new StringBuilder();
            try (Cursor c = sCtx.getContentResolver().query(uri,
                    new String[]{"title", "begin", "allDay"}, null, null, "begin ASC")) {
                if (c != null) {
                    java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("HH:mm", Locale.CHINA);
                    java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("MM/dd", Locale.CHINA);
                    int shown = 0;
                    while (c.moveToNext() && shown < 2) {
                        String title = c.getString(0);
                        long begin = c.getLong(1);
                        int allDay = c.getInt(2);
                        if (title == null || title.trim().isEmpty()) continue;
                        if (begin < nowEpoch - 3600_000L && allDay == 0) continue;
                        if (sb.length() > 0) sb.append("\n");
                        String when = (allDay == 1) ? (df.format(new java.util.Date(begin)) + " 全天")
                                : tf.format(new java.util.Date(begin));
                        sb.append(when).append(" ").append(title.trim());
                        shown++;
                    }
                }
            }
            calText = sb.toString();
        } catch (Throwable ignored) {}
        sCachedCalendarText = calText.isEmpty() ? "近两日暂无日历日程" : calText;
    }

    private static void writeMirrorTelemetryToParcel(Parcel reply) {
        if (reply == null) return;
        refreshMirrorTelemetryIfNeeded();
        reply.writeString(sCachedAudioRoute != null ? sCachedAudioRoute : "");
        reply.writeString(sCachedAudioDetail != null ? sCachedAudioDetail : "");
        reply.writeString(sCachedMediaPkg != null ? sCachedMediaPkg : "");
        reply.writeInt(sCachedMediaState);
        reply.writeString(sCachedMediaTitle != null ? sCachedMediaTitle : "");
        reply.writeString(sCachedMediaSub != null ? sCachedMediaSub : "");
        reply.writeString(sCachedCalendarText != null ? sCachedCalendarText : "");
        reply.writeString(sCachedBattDetail != null ? sCachedBattDetail : "");
        reply.writeString(sCachedWifiDetail != null ? sCachedWifiDetail : "");
    }
}

package com.extbridge.helper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BridgeClient {
    public static final int SYS_WIFI = 1;
    public static final int SYS_BT = 2;
    public static final int SYS_AIRPLANE = 3;
    public static final int SYS_POWER_SAVE = 4;
    public static final int SYS_OPEN_SETTINGS = 5;
    public static final int SYS_SCREEN_DIM = 6;

    public static class TaskEntry {
        public int taskId;
        public String pkg;
        public int flags;
        public boolean onExt() { return (flags & 1) != 0; }
        public boolean isRunning() { return (flags & 2) != 0; }
        public boolean isTopExt() { return (flags & 4) != 0; }
        public boolean isFreeform() { return (flags & 8) != 0; }
        public boolean isInSplit() { return (flags & 16) != 0; }
    }

    public static class SysStatus {
        public boolean valid;
        public boolean wifiOn;
        public String wifiSsid = "";
        public boolean btOn;
        public String btName = "";
        public boolean airplaneOn;
        public boolean powerSaveOn;
        public boolean screenDimmed;
    }

    private static volatile Bitmap sCachedWpBitmap;
    public static class Status {
        public boolean connected;
        public int extId = -1;
        public int vdId = -1;
        public int physW = 0;
        public int physH = 0;
        public float physFps = 60f;
        public int virtW = 2560;
        public int virtH = 1440;
        public int virtDpi = 240;
        public float virtFps = 60f;
        public int inputToExt = 1;
        public int cursorX = 1280;
        public int cursorY = 720;
        public int extInputCnt = 0;
        public String modesRaw = "";
        public String renderMode = "NONE";
        public int mirrorMode = 0;
        public int fullscreenPolicy = 1;
        public int virtRot = 0;
        public int injectStatus = 1;
        public int splitActive = 0;
        public int splitRatioPct = 50;
        public String splitSlot0Pkg = "";
        public String splitSlot1Pkg = "";
        public int mirrorSideMode = 1;
        public int sideVdId = -1;
        public int d0W = 0;
        public int d0H = 0;
        public int mirX = 0;
        public int mirY = 0;
        public int mirW = 0;
        public int mirH = 0;
    }

    public static class MirrorTelemetry {
        public boolean valid;
        public String audioRoute = "平板外放";
        public String audioDetail = "";
        public String mediaPkg = "";
        public int mediaState = 0; // 0=none, 1=stopped, 2=paused, 3=playing
        public String mediaTitle = "";
        public String mediaSubtitle = "";
        public String calendarText = "";
        public String battDetail = "";
        public String wifiDetail = "";
    }

    private static volatile IBinder sBinder;
    private static volatile IBinder sLinkedBinder;
    private static final java.util.concurrent.CopyOnWriteArraySet<Runnable> sBinderListeners =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    public static void setOnBinderReady(Runnable r) {
        if (r != null) sBinderListeners.add(r);
    }

    public static void removeOnBinderReady(Runnable r) {
        if (r != null) sBinderListeners.remove(r);
    }

    private static synchronized void linkBinderDeath(final IBinder b) {
        if (b == null || b == sLinkedBinder) return;
        sLinkedBinder = b;
        try {
            b.linkToDeath(() -> {
                if (sBinder == b) sBinder = null;
                if (sLinkedBinder == b) sLinkedBinder = null;
            }, 0);
        } catch (Throwable ignored) {}
    }

    public static void setBinder(IBinder b) {
        if (b != null && b.pingBinder()) {
            sBinder = b;
            linkBinderDeath(b);
            for (Runnable r : sBinderListeners) {
                try { r.run(); } catch (Throwable ignored) {}
            }
        }
    }

    public static void requestBinder(android.content.Context ctx) {
        if (ctx == null) return;
        try {
            android.content.Intent i1 = new android.content.Intent("com.extbridge.daemon.NEED_BINDER");
            i1.setPackage("android");
            ctx.sendBroadcast(i1);
            android.content.Intent i2 = new android.content.Intent("com.extbridge.daemon.NEED_BINDER");
            i2.setPackage("com.android.shell");
            ctx.sendBroadcast(i2);
        } catch (Throwable ignored) {}
    }

    public static IBinder getService() {
        IBinder b = sBinder;
        if (b != null) {
            if (b.pingBinder()) return b;
            sBinder = null;
        }
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "extbridge");
            if (b != null && b.pingBinder()) {
                sBinder = b;
                linkBinderDeath(b);
                return b;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Status getStatus() {
        Status st = new Status();
        IBinder b = getService();
        if (b == null) return st;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (b.transact(15, data, reply, 0)) {
                st.connected = true;
                st.extId = reply.readInt();
                st.vdId = reply.readInt();
                st.physW = reply.readInt();
                st.physH = reply.readInt();
                st.physFps = reply.readInt() / 100f;
                st.virtW = reply.readInt();
                st.virtH = reply.readInt();
                st.virtDpi = reply.readInt();
                st.virtFps = reply.readInt() / 100f;
                st.inputToExt = reply.readInt();
                st.cursorX = reply.readInt();
                st.cursorY = reply.readInt();
                st.extInputCnt = reply.readInt();
                String mr = reply.readString();
                st.modesRaw = mr != null ? mr : "";
                String rm = reply.readString();
                st.renderMode = (rm != null && !rm.isEmpty()) ? rm : "NONE";
                if (reply.dataAvail() >= 8) {
                    st.mirrorMode = reply.readInt();
                    st.fullscreenPolicy = reply.readInt();
                }
                if (reply.dataAvail() >= 4) {
                    st.virtRot = reply.readInt();
                }
                if (reply.dataAvail() >= 4) {
                    st.injectStatus = reply.readInt();
                }
                if (reply.dataAvail() >= 8) {
                    st.splitActive = reply.readInt();
                    st.splitRatioPct = reply.readInt();
                    String s0 = reply.readString();
                    String s1 = reply.readString();
                    st.splitSlot0Pkg = s0 != null ? s0 : "";
                    st.splitSlot1Pkg = s1 != null ? s1 : "";
                }
                if (reply.dataAvail() >= 32) {
                    st.mirrorSideMode = reply.readInt();
                    st.sideVdId = reply.readInt();
                    st.d0W = reply.readInt();
                    st.d0H = reply.readInt();
                    st.mirX = reply.readInt();
                    st.mirY = reply.readInt();
                    st.mirW = reply.readInt();
                    st.mirH = reply.readInt();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
        return st;
    }

    public static int toggleInput() {
        return simpleCall(10);
    }

    public static int setInputMode(int mode) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(mode);
            b.transact(19, d, r, 0);
            return r.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendBack() { simpleOneWay(11); }
    public static void relaunchLauncher() { simpleOneWay(12); }
    public static void sendHome() { simpleOneWay(13); }
    public static void rebuild() { simpleOneWay(21); }

    public static boolean applyConfig(int w, int h, int dpi, float fps, int physModeId) {
        return applyConfig(w, h, dpi, fps, physModeId, -1);
    }

    public static class ApplyResult {
        public boolean ok;
        public int physResult; // 0=ok/unchanged, 1=switched, -1=edid unsupported, -2=invoke failed, -3=system locked physical mode
        public int physW;
        public int physH;
        public float physFps;
    }

    public static boolean applyConfig(int w, int h, int dpi, float fps, int physModeId, int rot) {
        return applyConfigDetailed(w, h, dpi, fps, physModeId, rot).ok;
    }

    public static ApplyResult applyConfigDetailed(int w, int h, int dpi, float fps, int physModeId, int rot) {
        ApplyResult res = new ApplyResult();
        IBinder b = getService();
        if (b == null) return res;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(w);
            d.writeInt(h);
            d.writeInt(dpi);
            d.writeInt(Math.round(fps * 100f));
            d.writeInt(physModeId);
            d.writeInt(rot);
            b.transact(16, d, r, 0);
            res.ok = r.readInt() == 1;
            if (r.dataAvail() >= 16) {
                res.physResult = r.readInt();
                res.physW = r.readInt();
                res.physH = r.readInt();
                res.physFps = r.readInt() / 100f;
            }
            return res;
        } catch (Throwable t) {
            return res;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void shutdownDaemon(android.content.Context ctx) {
        IBinder b = getService();
        if (b != null) {
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            try {
                b.transact(99, d, r, 0);
            } catch (Throwable ignored) {
            } finally {
                d.recycle();
                r.recycle();
            }
        }
        sBinder = null;
        if (ctx != null) {
            try {
                android.content.Intent i1 = new android.content.Intent("com.extbridge.daemon.SHUTDOWN");
                i1.setPackage("android");
                ctx.sendBroadcast(i1);
                android.content.Intent i2 = new android.content.Intent("com.extbridge.daemon.SHUTDOWN");
                i2.setPackage("com.android.shell");
                ctx.sendBroadcast(i2);
            } catch (Throwable ignored) {}
        }
    }

    public static int[] sendMouse(int actionType, float a1, float a2) {
        IBinder b = getService();
        if (b == null) return null;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(actionType);
            d.writeFloat(a1);
            d.writeFloat(a2);
            if (!b.transact(17, d, r, 0) || r.dataAvail() < 8) return null;
            int cx = r.readInt();
            int cy = r.readInt();
            int inj = r.dataAvail() >= 4 ? r.readInt() : 1;
            return new int[]{cx, cy, inj};
        } catch (Throwable t) {
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendKey(int keyCode, int metaState) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(0);
            d.writeInt(keyCode);
            d.writeInt(metaState);
            d.writeString(null);
            b.transact(18, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendAscii(String txt) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(1);
            d.writeInt(0);
            d.writeInt(0);
            d.writeString(txt);
            b.transact(18, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendPaste(boolean useCtrlV) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(2);
            d.writeInt(useCtrlV ? 1 : 0);
            d.writeInt(0);
            d.writeString(null);
            b.transact(18, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean launchComponent(String pkg, String cls) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeString(pkg);
            d.writeString(cls);
            return b.transact(20, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static Bitmap getWallpaperBitmap(boolean force) {
        if (!force && sCachedWpBitmap != null && !sCachedWpBitmap.isRecycled()) {
            return sCachedWpBitmap;
        }
        IBinder b = getService();
        if (b == null) return sCachedWpBitmap;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(force ? 1 : 0);
            if (b.transact(22, d, r, 0)) {
                byte[] bytes = r.createByteArray();
                if (bytes != null && bytes.length > 0) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        sCachedWpBitmap = bmp;
                        return bmp;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return sCachedWpBitmap;
    }

    public static List<TaskEntry> getTasks() {
        List<TaskEntry> out = new ArrayList<>();
        IBinder b = getService();
        if (b == null) return out;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            if (b.transact(23, d, r, 0)) {
                int n = r.readInt();
                for (int i = 0; i < n && i < 20; i++) {
                    TaskEntry te = new TaskEntry();
                    te.taskId = r.readInt();
                    te.pkg = r.readString();
                    te.flags = r.readInt();
                    if (te.pkg != null && !te.pkg.isEmpty()) out.add(te);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return out;
    }

    public static boolean switchToTask(int taskId, String pkg) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(taskId);
            d.writeString(pkg);
            b.transact(24, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean minimizeToExtHome(int taskId, String pkg) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(taskId);
            d.writeString(pkg);
            b.transact(37, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean removeTask(int taskId) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(taskId);
            b.transact(25, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static SysStatus getSysStatus() {
        SysStatus st = new SysStatus();
        IBinder b = getService();
        if (b == null) return st;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            if (b.transact(26, d, r, 0)) {
                st.valid = true;
                st.wifiOn = r.readInt() == 1;
                String ws = r.readString();
                st.wifiSsid = ws != null ? ws : "";
                st.btOn = r.readInt() == 1;
                String bn = r.readString();
                st.btName = bn != null ? bn : "";
                st.airplaneOn = r.readInt() == 1;
                st.powerSaveOn = r.readInt() == 1;
                st.screenDimmed = (r.dataAvail() >= 4) && (r.readInt() == 1);
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return st;
    }

    public static boolean execSysAction(int act, int arg, String str) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(act);
            d.writeInt(arg);
            d.writeString(str);
            b.transact(27, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static final int MIRROR_MODE_DEX = 0;
    public static final int MIRROR_MODE_MIRROR = 1;

    public static String mirrorModeLabel(int mode) {
        return mode == MIRROR_MODE_MIRROR ? "原生镜像" : "DeX 扩展桌面";
    }

    public static int setMirrorMode(int mode) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(mode);
            b.transact(28, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static int toggleMirrorMode() {
        return setMirrorMode(-1);
    }

    public static int setFullscreenPolicy(int enable) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(enable);
            b.transact(29, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static int toggleFullscreenPolicy() {
        return setFullscreenPolicy(-1);
    }

    public static int setRotation(int rot) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(rot);
            b.transact(34, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static int cycleRotation() {
        return setRotation(-1);
    }

    public static String rotationLabel(int rot) {
        int r = ((rot % 4) + 4) % 4;
        switch (r) {
            case 1: return "90° 竖屏";
            case 2: return "180° 倒置";
            case 3: return "270° 竖屏";
            default: return "0° 横屏";
        }
    }

    public static final int APP_MODE_DEFAULT = 0;
    public static final int APP_MODE_LAND_FULL = 1;
    public static final int APP_MODE_PORT_COMPAT = 2;
    public static final int APP_MODE_FREEFORM = 3;

    public static String appModeLabel(int mode) {
        switch (mode) {
            case APP_MODE_LAND_FULL: return "强制横屏全屏";
            case APP_MODE_PORT_COMPAT: return "竖屏居中兼容";
            default: return "跟随系统默认";
        }
    }

    public static String appModeBadge(int mode) {
        switch (mode) {
            case APP_MODE_LAND_FULL: return "横屏全屏";
            case APP_MODE_PORT_COMPAT: return "竖屏兼容";
            default: return "";
        }
    }

    public static int prepareAppLaunch(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        IBinder b = getService();
        if (b == null) return 0;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeString(pkg);
            b.transact(30, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return 0;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static Map<String, Integer> getAllAppModes() {
        Map<String, Integer> map = new HashMap<>();
        IBinder b = getService();
        if (b == null) return map;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            b.transact(31, d, r, 0);
            String s = r.readString();
            if (s != null && !s.isEmpty()) {
                for (String item : s.split(";")) {
                    int eq = item.indexOf('=');
                    if (eq > 0 && eq < item.length() - 1) {
                        String pkg = item.substring(0, eq).trim();
                        int mode = Integer.parseInt(item.substring(eq + 1).trim());
                        if (!pkg.isEmpty() && mode >= 0 && mode <= 3) {
                            map.put(pkg, mode);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return map;
    }

    public static boolean setAppMode(String pkg, int mode, boolean restart) {
        if (pkg == null || pkg.isEmpty()) return false;
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeString(pkg);
            d.writeInt(mode);
            d.writeInt(restart ? 1 : 0);
            b.transact(32, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean resetAllAppModes() {
        return simpleCall(33) == 1;
    }

    public static final int SPLIT_ASSIGN_LEFT_TOP = 1;
    public static final int SPLIT_ASSIGN_RIGHT_BOTTOM = 2;
    public static final int SPLIT_TOGGLE_QUICK = 3;
    public static final int SPLIT_SET_RATIO = 4;
    public static final int SPLIT_SWAP = 5;
    public static final int SPLIT_EXIT = 6;

    public static boolean splitAction(int action, int arg, String pkg, String cls) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(action);
            d.writeInt(arg);
            d.writeString(pkg != null ? pkg : "");
            d.writeString(cls != null ? cls : "");
            b.transact(35, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean assignSplitSlot(int slot, String pkg, String cls) {
        return splitAction(slot == 0 ? SPLIT_ASSIGN_LEFT_TOP : SPLIT_ASSIGN_RIGHT_BOTTOM, -1, pkg, cls);
    }

    public static boolean toggleQuickSplit() {
        return splitAction(SPLIT_TOGGLE_QUICK, 0, null, null);
    }

    public static boolean setSplitRatio(int ratioPct) {
        return splitAction(SPLIT_SET_RATIO, Math.max(25, Math.min(75, ratioPct)), null, null);
    }

    public static boolean swapSplitSlots() {
        return splitAction(SPLIT_SWAP, 0, null, null);
    }

    public static boolean exitSplitMode() {
        return splitAction(SPLIT_EXIT, 0, null, null);
    }

    /** Switches a task between floating freeform window (1) and fullscreen (0); -1 toggles. */
    public static int toggleTaskFreeform(int taskId, String pkg, int enableFreeform) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(taskId);
            d.writeString(pkg != null ? pkg : "");
            d.writeInt(enableFreeform);
            b.transact(36, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static final int MIRROR_SIDE_OFF = 0;
    public static final int MIRROR_SIDE_ALIGN_LEFT = 1;
    public static final int MIRROR_SIDE_ALIGN_RIGHT = 2;
    public static final int MIRROR_SIDE_CENTER_DUAL = 3;

    public static final String DEFAULT_MIRROR_PRESETS = "clock,battery,wifi,bt,audio,media,screendim";

    public static final String[] MIRROR_THEME_KEYS = {
            "night", "oled", "frost", "cyber", "emerald", "amber", "custom"
    };
    public static final String[] MIRROR_THEME_TITLES = {
            "极夜深蓝", "OLED纯黑", "晨雾浅白", "赛博紫青", "翡翠墨绿", "暖暮琥珀", "自定义颜色"
    };

    public static final String[] MIRROR_SPEED_LABELS = {
            "0.9x 精细", "1.4x 标准", "1.9x 流畅", "2.6x 极速", "3.4x 飞快"
    };
    public static final float[] MIRROR_SPEED_MULTS = {0.9f, 1.4f, 1.9f, 2.6f, 3.4f};

    public static final String[] MIRROR_WIDGET_RATIO_LABELS = {
            "自动原比例", "方形 1:1", "竖屏 3:4", "宽屏 16:9", "铺满槽位"
    };

    public static final class MirrorTheme {
        public final String id;
        public final String name;
        public final int bgColor;
        public final int headerBgColor;
        public final int cardBgColor;
        public final int cardStrokeColor;
        public final int widgetBgColor;
        public final int pillBgColor;
        public final int accentBtnColor;
        public final int titleColor;
        public final int subColor;
        public final int accentColor;
        public final boolean lightMode;

        public MirrorTheme(String id, String name, int bgColor, int headerBgColor, int cardBgColor,
                int cardStrokeColor, int widgetBgColor, int pillBgColor, int accentBtnColor,
                int titleColor, int subColor, int accentColor, boolean lightMode) {
            this.id = id;
            this.name = name;
            this.bgColor = bgColor;
            this.headerBgColor = headerBgColor;
            this.cardBgColor = cardBgColor;
            this.cardStrokeColor = cardStrokeColor;
            this.widgetBgColor = widgetBgColor;
            this.pillBgColor = pillBgColor;
            this.accentBtnColor = accentBtnColor;
            this.titleColor = titleColor;
            this.subColor = subColor;
            this.accentColor = accentColor;
            this.lightMode = lightMode;
        }
    }

    public static MirrorTheme resolveMirrorTheme(String id, int customBg) {
        if ("oled".equals(id)) {
            return new MirrorTheme("oled", "OLED纯黑",
                    0xFF000000, 0xFF0A0D14, 0xE60D111A, 0x38475569, 0xB3090D14,
                    0xFF181E29, 0xFF1D4ED8, 0xFFFFFFFF, 0xFF94A3B8, 0xFF60A5FA, false);
        }
        if ("frost".equals(id)) {
            return new MirrorTheme("frost", "晨雾浅白",
                    0xFFF1F5F9, 0xFFE2E8F0, 0xF2FFFFFF, 0x3864748B, 0xE6FFFFFF,
                    0xFFE2E8F0, 0xFF2563EB, 0xFF0F172A, 0xFF475569, 0xFF0284C7, true);
        }
        if ("cyber".equals(id)) {
            return new MirrorTheme("cyber", "赛博紫青",
                    0xFF110722, 0xFF190C33, 0xDE1E1038, 0x4CA855F7, 0x99190D30,
                    0xFF2E1065, 0xFF7C3AED, 0xFFFDF4FF, 0xFFD8B4FE, 0xFF22D3EE, false);
        }
        if ("emerald".equals(id)) {
            return new MirrorTheme("emerald", "翡翠墨绿",
                    0xFF031410, 0xFF062019, 0xDE0A241E, 0x4C10B981, 0x99071C17,
                    0xFF134E4A, 0xFF059669, 0xFFECFDF5, 0xFFA7F3D0, 0xFF34D399, false);
        }
        if ("amber".equals(id)) {
            return new MirrorTheme("amber", "暖暮琥珀",
                    0xFF180E06, 0xFF241509, 0xDE29180B, 0x4CF59E0B, 0x99201207,
                    0xFF451A03, 0xFFD97706, 0xFFFFFBEB, 0xFFFDE68A, 0xFFFBBF24, false);
        }
        if ("custom".equals(id)) {
            int opaqueBg = 0xFF000000 | (customBg & 0x00FFFFFF);
            int r = (opaqueBg >> 16) & 0xFF;
            int g = (opaqueBg >> 8) & 0xFF;
            int b = opaqueBg & 0xFF;
            float luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
            boolean light = luma > 0.58f;
            if (light) {
                int hdr = blendColor(opaqueBg, 0xFFCBD5E1, 0.22f);
                int card = 0xEAFFFFFF;
                return new MirrorTheme("custom", "自定义浅色",
                        opaqueBg, hdr, card, 0x3864748B, 0xDEFFFFFF,
                        0xFFE2E8F0, 0xFF2563EB, 0xFF0F172A, 0xFF475569, 0xFF0284C7, true);
            } else {
                int hdr = blendColor(opaqueBg, 0xFFFFFFFF, 0.08f);
                int cardRgb = blendColor(opaqueBg, 0xFFFFFFFF, 0.11f) & 0x00FFFFFF;
                int wRgb = blendColor(opaqueBg, 0xFFFFFFFF, 0.07f) & 0x00FFFFFF;
                int pill = blendColor(opaqueBg, 0xFFFFFFFF, 0.18f);
                return new MirrorTheme("custom", "自定义深色",
                        opaqueBg, hdr, 0xDC000000 | cardRgb, 0x44FFFFFF, 0x9E000000 | wRgb,
                        pill, 0xFF2563EB, 0xFFF8FAFC, 0xFFCBD5E1, 0xFF38BDF8, false);
            }
        }
        return new MirrorTheme("night", "极夜深蓝",
                0xFF060A14, 0xFF0D1526, 0xDE0F172A, 0x4294A3B8, 0x990F172A,
                0xFF1E293B, 0xFF312E81, 0xFFF8FAFC, 0xFFCBD5E1, 0xFF38BDF8, false);
    }

    private static int blendColor(int c1, int c2, float ratio) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int r = Math.max(0, Math.min(255, Math.round(r1 + (r2 - r1) * ratio)));
        int g = Math.max(0, Math.min(255, Math.round(g1 + (g2 - g1) * ratio)));
        int b = Math.max(0, Math.min(255, Math.round(b1 + (b2 - b1) * ratio)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static final String[] MIRROR_PRESET_KEYS = {
            "clock", "battery", "wifi", "bt", "audio", "media", "calendar",
            "perf", "netspeed", "storage", "pomodoro", "memo", "quickctrl", "screendim"
    };

    public static final String[] MIRROR_PRESET_TITLES = {
            "🕒 大数字时钟",
            "🔋 电池温压详情",
            "📶 Wi-Fi 与信号",
            "🎧 蓝牙与外设",
            "🔊 音频通道与音量",
            "🎵 媒体播放与切歌",
            "📅 日历与近期日程",
            "⚡ 内存与系统负载",
            "🌐 实时网速监控",
            "💾 存储与亮度信息",
            "⏱ 专注番茄钟",
            "📝 快捷桌面便签",
            "🎛 镜像快捷控制台",
            "🌙 主屏最低亮度"
    };

    public static final String[] MIRROR_PRESET_DESCS = {
            "超大时间 + 日期星期（高对比醒目字号）",
            "电量百分比、快充功耗、电池温度与电压",
            "当前 Wi-Fi 名称、连接速率与信号强度",
            "蓝牙开关状态与已连接耳机/键鼠名称",
            "实时输出通道 + 可触控点按 ➖/🔇/➕",
            "当前媒体曲目/App + 可点按 ⏮/⏯/⏭ 切歌",
            "读取系统日历近两日日程提醒",
            "实时可用 RAM 内存占比条 + 系统运行负载",
            "每秒实时下行 ↓ / 上行 ↑ 网络吞吐速率",
            "机身存储可用空间 GB + 系统当前亮度状态",
            "25 分钟倒计时专注钟，支持边栏直接启停/重置",
            "自定义文字待办/会议备忘，随时在控制台修改",
            "边栏直达快捷按钮：切回DeX / 切换对齐方位 / 切换键鼠 / 极暗",
            "一键把平板/手机主屏亮度压至硬件最低（省电护眼），再点恢复"
    };

    public static int mirrorPresetIndex(String key) {
        if (key == null) return -1;
        for (int i = 0; i < MIRROR_PRESET_KEYS.length; i++) {
            if (MIRROR_PRESET_KEYS[i].equals(key)) return i;
        }
        return -1;
    }

    public static String mirrorPresetTitle(String key) {
        int idx = mirrorPresetIndex(key);
        return idx >= 0 ? MIRROR_PRESET_TITLES[idx] : String.valueOf(key);
    }

    public static String mirrorSideModeLabel(int sideMode) {
        if (sideMode == MIRROR_SIDE_OFF) return "纯黑居中";
        if (sideMode == MIRROR_SIDE_ALIGN_RIGHT) return "靠右/靠下 · 融合大边栏";
        if (sideMode == MIRROR_SIDE_CENTER_DUAL) return "主屏居中 · 双信息条";
        return "靠左/靠上 · 融合大边栏";
    }

    public static int setMirrorSideMode(int mode) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(mode);
            b.transact(38, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static MirrorTelemetry getMirrorTelemetry() {
        MirrorTelemetry mt = new MirrorTelemetry();
        IBinder b = getService();
        if (b == null) return mt;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            if (b.transact(39, d, r, 0)) {
                mt.valid = true;
                String ar = r.readString();
                String ad = r.readString();
                String mp = r.readString();
                int ms = r.readInt();
                String ttl = r.readString();
                String sub = r.readString();
                String cal = r.readString();
                String bat = r.readString();
                String wif = r.readString();
                if (ar != null && !ar.isEmpty()) mt.audioRoute = ar;
                if (ad != null) mt.audioDetail = ad;
                if (mp != null) mt.mediaPkg = mp;
                mt.mediaState = ms;
                if (ttl != null) mt.mediaTitle = ttl;
                if (sub != null) mt.mediaSubtitle = sub;
                if (cal != null) mt.calendarText = cal;
                if (bat != null) mt.battDetail = bat;
                if (wif != null) mt.wifiDetail = wif;
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return mt;
    }

    public static boolean ensureAppWidgetPermission() {
        return simpleCall(40) == 1;
    }

    public static void sendSideTouch(int actionMasked, float sx, float sy) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(actionMasked);
            d.writeFloat(sx);
            d.writeFloat(sy);
            b.transact(41, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static int[] sendCapturedMirrorMouse(int actionType, float a1, float a2, float a3) {
        IBinder b = getService();
        if (b == null) return new int[]{0, 0, 0};
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(actionType);
            d.writeFloat(a1);
            d.writeFloat(a2);
            d.writeFloat(a3);
            if (b.transact(42, d, r, 0)) {
                return new int[]{r.readInt(), r.readInt(), r.readInt()};
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return new int[]{0, 0, 0};
    }

    public static int[] notifyMirrorEdgeHover(float px, float py) {
        IBinder b = getService();
        if (b == null) return new int[]{0, 0, 0};
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeFloat(px);
            d.writeFloat(py);
            if (b.transact(43, d, r, 0)) {
                return new int[]{r.readInt(), r.readInt(), r.readInt()};
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return new int[]{0, 0, 0};
    }

    private static int simpleCall(int code) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            b.transact(code, d, r, 0);
            return r.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static void simpleOneWay(int code) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        try {
            b.transact(code, d, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
        }
    }
}

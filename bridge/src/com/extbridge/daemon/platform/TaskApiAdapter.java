package com.extbridge.daemon.platform;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.extbridge.daemon.BridgeBuildConfig;
import com.extbridge.daemon.DaemonConfig;
import com.extbridge.daemon.util.ShellCommand;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Reflection adapter over Android {@code IActivityTaskManager} (ATM) and {@code ITaskSnapshotManager}.
 * Centralizes task queries, TaskInfo field readers, WindowContainerTransaction (WCT) mutations,
 * task moves/resizes/removals, and TaskSnapshot captures while preserving exact MIUI/HyperOS
 * freeform escape and 2px SurfaceFlinger leash-nudge sequences.
 */
public final class TaskApiAdapter {
    public static final int WINDOWING_MODE_FULLSCREEN = 1;
    public static final int WINDOWING_MODE_FREEFORM = 5;
    public static final int WINDOWING_MODE_MULTI_WINDOW = 6;
    private static final int DEFAULT_MAX_TASKS = 25;
    private static final long FULLSCREEN_SETTLE_DELAY_MS = 55L;
    private static final int SNAPSHOT_RESOLUTION_ANY = 3;

    private final Object atm;
    private volatile Object taskSnapshotMgr;
    private volatile boolean taskSnapshotMgrChecked;

    public TaskApiAdapter(Object iActivityTaskManager) {
        this.atm = iActivityTaskManager;
    }

    // ---- TaskInfo field readers ----

    public static int readIntField(Object obj, String name, int def) {
        if (obj == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException | IllegalArgumentException e) {
                return def;
            }
        }
        return def;
    }

    public static boolean readBoolField(Object obj, String name, boolean def) {
        if (obj == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException | IllegalArgumentException e) {
                return def;
            }
        }
        return def;
    }

    public static Object readObjField(Object obj, String name) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    public static int extractTaskId(Object taskInfo) {
        return readIntField(taskInfo, "taskId", readIntField(taskInfo, "id", -1));
    }

    public static int extractRecentTaskId(Object taskInfo) {
        return readIntField(taskInfo, "taskId",
                readIntField(taskInfo, "persistentId", readIntField(taskInfo, "id", -1)));
    }

    public static String extractTaskPkg(Object info) {
        String topPkg = readComponentPkg(info, "topActivity");
        if (topPkg != null && !topPkg.isEmpty()) {
            // Keep Helper tasks attributed to Helper, and normal user apps attributed to topActivity.
            // If a user app task temporarily hosts an in-task system permission/privacy/chooser dialog
            // (e.g. com.miui.securitycenter or permissioncontroller), attribute the task to its owning
            // root app so DeX window headers, bounds, and taskbar focus stay attached.
            if (BridgeBuildConfig.HELPER_PACKAGE.equals(topPkg)
                    || !DaemonConfig.PackagePolicy.isProtectedSystemTaskPkg(topPkg)) {
                return topPkg;
            }
            String rootPkg = extractRootTaskPkg(info);
            if (rootPkg != null && !rootPkg.isEmpty()
                    && !DaemonConfig.PackagePolicy.isProtectedSystemTaskPkg(rootPkg)) {
                return rootPkg;
            }
            return topPkg;
        }
        return extractRootTaskPkg(info);
    }

    private static String extractRootTaskPkg(Object info) {
        String real = readComponentPkg(info, "realActivity");
        if (real != null && !real.isEmpty()) return real;
        Object bi = readObjField(info, "baseIntent");
        if (bi instanceof Intent) {
            Intent intent = (Intent) bi;
            if (intent.getComponent() != null) {
                String p = intent.getComponent().getPackageName();
                if (p != null && !p.isEmpty()) return p;
            }
            String p = intent.getPackage();
            if (p != null && !p.isEmpty()) return p;
        }
        return readComponentPkg(info, "baseActivity");
    }

    private static String readComponentPkg(Object info, String fieldName) {
        Object comp = readObjField(info, fieldName);
        if (comp instanceof ComponentName) {
            String p = ((ComponentName) comp).getPackageName();
            if (p != null && !p.isEmpty()) return p;
        }
        return null;
    }

    public static int extractTaskWindowingMode(Object taskInfo) {
        if (taskInfo == null) return 0;
        int wm = readIntField(taskInfo, "windowingMode", 0);
        if (wm != 0) return wm;
        try {
            Object cfg = taskInfo.getClass().getField("configuration").get(taskInfo);
            if (cfg != null) {
                Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                if (wc != null) {
                    Object r = wc.getClass().getMethod("getWindowingMode").invoke(wc);
                    if (r instanceof Integer) return (Integer) r;
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // TaskInfo variant lacks configuration.windowConfiguration.
        }
        return 0;
    }

    // ---- ATM Task queries ----

    public List<?> getTasks() {
        return invokeTaskQuery("getTasks", DEFAULT_MAX_TASKS);
    }

    public List<?> getRecentTasks() {
        return invokeTaskQuery("getRecentTasks", DEFAULT_MAX_TASKS);
    }

    public List<?> invokeTaskQuery(String name, int maxNum) {
        if (atm == null) return null;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pt = m.getParameterTypes();
                Object res = null;
                if ("getTasks".equals(name)) {
                    if (pt.length == 1) res = m.invoke(atm, maxNum);
                    else if (pt.length == 2) res = m.invoke(atm, maxNum, false);
                    else if (pt.length == 3) res = m.invoke(atm, maxNum, false, true);
                    else if (pt.length == 4) res = m.invoke(atm, maxNum, false, true, -1);
                } else if ("getRecentTasks".equals(name) && pt.length == 3) {
                    res = m.invoke(atm, maxNum, 0, 0);
                }
                if (res instanceof List<?>) return (List<?>) res;
                if (res != null) {
                    try {
                        Object list = res.getClass().getMethod("getList").invoke(res);
                        if (list instanceof List<?>) return (List<?>) list;
                    } catch (NoSuchMethodException ignored) {
                        // Not a ParceledListSlice.
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_TASK_QUERY_ERR method=" + name + ": " + e.getMessage());
        }
        return null;
    }

    public List<?> getAllRootTaskInfosOnDisplay(int displayId) {
        if (atm == null) return null;
        try {
            if (displayId > 0) {
                return (List<?>) atm.getClass()
                        .getMethod("getAllRootTaskInfosOnDisplay", int.class)
                        .invoke(atm, displayId);
            }
            return (List<?>) atm.getClass().getMethod("getAllRootTaskInfos").invoke(atm);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_ROOT_TASK_QUERY_ERR displayId=" + displayId + ": " + e.getMessage());
            return null;
        }
    }

    public Object findRootTaskInfo(int taskId, int preferredDisplayId) {
        if (taskId <= 0 || atm == null) return null;
        if (preferredDisplayId > 0) {
            List<?> infos = getAllRootTaskInfosOnDisplay(preferredDisplayId);
            Object found = matchRootTaskById(infos, taskId);
            if (found != null) return found;
        }
        List<?> all = getAllRootTaskInfosOnDisplay(-1);
        return matchRootTaskById(all, taskId);
    }

    public Object findRootTaskToken(int taskId, int preferredDisplayId) {
        Object info = findRootTaskInfo(taskId, preferredDisplayId);
        if (info == null) return null;
        try {
            return info.getClass().getField("token").get(info);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public int getTaskWindowingMode(int taskId) {
        if (taskId <= 0 || atm == null) return 0;
        List<?> running = getTasks();
        if (running == null) return 0;
        for (Object t : running) {
            if (t == null) continue;
            if (extractTaskId(t) == taskId) {
                return extractTaskWindowingMode(t);
            }
        }
        return 0;
    }

    public Rect getTaskBounds(int taskId, int preferredDisplayId) {
        Object info = findRootTaskInfo(taskId, preferredDisplayId);
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
        } catch (ReflectiveOperationException ignored) {
            // Bounds unavailable on this RootTaskInfo.
        }
        return null;
    }

    // ---- ATM Task mutations ----

    public boolean moveRootTaskToDisplay(int taskId, int targetDisplayId) {
        if (taskId <= 0 || targetDisplayId < 0 || atm == null) return false;
        Object existingOnTarget = matchRootTaskById(getAllRootTaskInfosOnDisplay(targetDisplayId), taskId);
        if (existingOnTarget != null) return true;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if ("moveRootTaskToDisplay".equals(m.getName()) && m.getParameterCount() == 2) {
                    m.invoke(atm, taskId, targetDisplayId);
                    return true;
                }
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
            System.err.println("EXTBRIDGE_MOVE_TASK_ERR tid=" + taskId
                    + " dst=" + targetDisplayId + ": " + cause.getMessage());
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_MOVE_TASK_ERR tid=" + taskId
                    + " dst=" + targetDisplayId + ": " + e.getMessage());
        }
        return false;
    }

    /** Reorders a root task to the top ({@code toTop=true}) or bottom ({@code toTop=false}) of its display. */
    public boolean reorderTask(int taskId, int preferredDisplayId, boolean toTop) {
        if (taskId <= 0 || atm == null) return false;
        try {
            Object tok = findRootTaskToken(taskId, preferredDisplayId);
            if (tok == null) return false;
            Object wo = atm.getClass().getMethod("getWindowOrganizerController").invoke(atm);
            if (wo == null) return false;
            Class<?> wctCls = Class.forName("android.window.WindowContainerTransaction");
            Class<?> tokCls = Class.forName("android.window.WindowContainerToken");
            Object wct = wctCls.getConstructor().newInstance();
            wctCls.getMethod("reorder", tokCls, boolean.class).invoke(wct, tok, toTop);
            wo.getClass().getMethod("applyTransaction", wctCls).invoke(wo, wct);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_REORDER_TASK_ERR tid=" + taskId + " toTop=" + toTop + ": " + e.getMessage());
            return false;
        }
    }

    public boolean startActivityFromRecents(int taskId, Bundle options) {
        if (taskId <= 0 || atm == null) return false;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if ("startActivityFromRecents".equals(m.getName()) && m.getParameterCount() == 2) {
                    Object rc = m.invoke(atm, taskId, options);
                    return !(rc instanceof Integer) || (Integer) rc >= 0;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_START_RECENTS_ERR tid=" + taskId + ": " + e.getMessage());
        }
        return false;
    }

    /** Atomically updates alwaysOnTop / windowingMode / bounds via WindowContainerTransaction. */
    public boolean applyWctForTask(
            int taskId, int preferredDisplayId, Boolean alwaysOnTop, int windowingMode, Rect bounds) {
        if (taskId <= 0 || atm == null) return false;
        try {
            Object tok = findRootTaskToken(taskId, preferredDisplayId);
            if (tok == null) return false;
            Object wo = atm.getClass().getMethod("getWindowOrganizerController").invoke(atm);
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
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_WCT_ERR tid=" + taskId + ": " + e.getMessage());
            return false;
        }
    }

    public void clearTaskAlwaysOnTop(int taskId, int preferredDisplayId) {
        if (taskId <= 0 || atm == null) return;
        if (applyWctForTask(taskId, preferredDisplayId, Boolean.FALSE, -1, null)) return;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if ("setTaskAlwaysOnTop".equals(m.getName()) && m.getParameterCount() == 2) {
                    m.invoke(atm, taskId, false);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_CLEAR_AOT_ERR tid=" + taskId + ": " + e.getMessage());
        }
    }

    public void forceTaskWindowingMode(int taskId, int preferredDisplayId, int windowingMode) {
        if (taskId <= 0 || atm == null) return;
        if (applyWctForTask(taskId, preferredDisplayId, null, windowingMode, null)) return;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if (!"setTaskWindowingMode".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                    m.invoke(atm, taskId, windowingMode);
                    return;
                } else if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class && pt[2] == boolean.class) {
                    m.invoke(atm, taskId, windowingMode, false);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_FORCE_WM_ERR tid=" + taskId + " mode=" + windowingMode + ": " + e.getMessage());
        }
    }

    public void setTaskResizeable(int taskId, int mode) {
        if (taskId <= 0 || atm == null) return;
        boolean ok = false;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if ("setTaskResizeable".equals(m.getName()) && m.getParameterCount() == 2) {
                    m.invoke(atm, taskId, mode);
                    ok = true;
                    break;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_RESIZEABLE_ERR tid=" + taskId + ": " + e.getMessage());
        }
        if (!ok) {
            ShellCommand.runOutput("cmd", "activity", "task", "resizeable",
                    String.valueOf(taskId), String.valueOf(mode));
        }
    }

    public static final int RESIZE_MODE_SYSTEM = 0;
    /** Preserves app window surface during interactive drag without triggering system relayout transitions. */
    public static final int RESIZE_MODE_USER = 1;

    private volatile Method cachedResizeTaskMethod;
    private volatile int cachedResizeTaskArgCount;
    private volatile boolean resizeTaskResolved;

    public void resizeTask(int taskId, Rect bounds) {
        resizeTask(taskId, bounds, RESIZE_MODE_SYSTEM);
    }

    public void resizeTask(int taskId, Rect bounds, int resizeMode) {
        if (taskId <= 0 || bounds == null || atm == null) return;
        Method m = resolveResizeTaskMethod();
        boolean resized = false;
        if (m != null) {
            try {
                if (cachedResizeTaskArgCount == 3) {
                    m.invoke(atm, taskId, bounds, resizeMode);
                } else {
                    m.invoke(atm, taskId, bounds);
                }
                resized = true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("EXTBRIDGE_RESIZE_TASK_ERR tid=" + taskId + ": " + e.getMessage());
            }
        }
        if (!resized) {
            ShellCommand.runOutput("cmd", "activity", "task", "resize", String.valueOf(taskId),
                    String.valueOf(bounds.left), String.valueOf(bounds.top),
                    String.valueOf(bounds.right), String.valueOf(bounds.bottom));
        }
    }

    private Method resolveResizeTaskMethod() {
        if (resizeTaskResolved) return cachedResizeTaskMethod;
        Method found = null;
        int argCount = 0;
        if (atm != null) {
            for (Method m : atm.getClass().getMethods()) {
                if (!"resizeTask".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == int.class && pt[1] == Rect.class && pt[2] == int.class) {
                    found = m;
                    argCount = 3;
                    break;
                } else if (pt.length == 2 && pt[0] == int.class && pt[1] == Rect.class && found == null) {
                    found = m;
                    argCount = 2;
                }
            }
        }
        cachedResizeTaskMethod = found;
        cachedResizeTaskArgCount = argCount;
        resizeTaskResolved = true;
        return found;
    }

    /**
     * Resizes task bounds via ATM {@code resizeTask} so both WM {@code mBounds} and SurfaceFlinger
     * layer position update. When {@code forceSyncIfEqual} is true and WM bounds already match
     * {@code bounds}, nudges 2px first because ATM ignores no-op identical-bounds resizes.
     */
    public void resizeTaskBoundsOnly(int taskId, int preferredDisplayId, Rect bounds, boolean forceSyncIfEqual) {
        if (taskId <= 0 || bounds == null || atm == null) return;
        if (forceSyncIfEqual) {
            Rect cur = getTaskBounds(taskId, preferredDisplayId);
            if (cur != null && cur.equals(bounds)) {
                Rect nudge = new Rect(bounds);
                if (nudge.left >= 2) nudge.left -= 2;
                else if (nudge.right - nudge.left > 64) nudge.right -= 2;
                else nudge.bottom = Math.max(nudge.top + 64, nudge.bottom - 2);
                resizeTask(taskId, nudge);
            }
        }
        resizeTask(taskId, bounds);
    }

    /**
     * Places a task onto {@code displayId} in native {@code MULTI_WINDOW (6)} with {@code bounds},
     * cleanly escaping MIUI {@code FREEFORM (5)} scaling without spurious focus yanks.
     */
    public boolean ensureTaskResizeableAndMultiWindow(
            int taskId, int displayId, Rect bounds, boolean forceSyncIfEqual) {
        if (taskId <= 0 || displayId <= 0 || atm == null) return false;
        moveRootTaskToDisplay(taskId, displayId);
        setTaskResizeable(taskId, 2);

        int curWm = getTaskWindowingMode(taskId);
        boolean modeChanged = (curWm != WINDOWING_MODE_MULTI_WINDOW);
        Rect initBounds = (bounds != null && !bounds.isEmpty()) ? bounds : null;
        if (curWm == WINDOWING_MODE_FREEFORM) {
            // Atomically hand off FREEFORM (5) -> MULTI_WINDOW (6) at the exact same bounds in ONE WCT
            // so the task never passes through an intermediate FULLSCREEN state or flashes full-screen.
            if (applyWctForTask(taskId, displayId, Boolean.FALSE, WINDOWING_MODE_MULTI_WINDOW, initBounds)) {
                curWm = WINDOWING_MODE_MULTI_WINDOW;
            } else {
                clearTaskAlwaysOnTop(taskId, displayId);
                forceTaskWindowingMode(taskId, displayId, WINDOWING_MODE_MULTI_WINDOW);
                curWm = WINDOWING_MODE_MULTI_WINDOW;
            }
        }
        if (curWm != WINDOWING_MODE_MULTI_WINDOW) {
            if (!applyWctForTask(taskId, displayId, null, WINDOWING_MODE_MULTI_WINDOW, initBounds)) {
                forceTaskWindowingMode(taskId, displayId, WINDOWING_MODE_MULTI_WINDOW);
            }
        }
        if (bounds != null && !bounds.isEmpty()) {
            resizeTaskBoundsOnly(taskId, displayId, bounds, forceSyncIfEqual || modeChanged);
        }
        return true;
    }

    /**
     * Escapes MIUI {@code FREEFORM (5)} or {@code MULTI_WINDOW (6)} back to {@code FULLSCREEN (1)},
     * resetting SurfaceFlinger Task leash position via a full-screen resize + 55ms commit settle
     * before applying empty fullscreen bounds.
     */
    public void detachFreeformAndEnsureFullscreen(
            int taskId, int preferredDisplayId, int dispW, int dispH, boolean syncFullBounds, Handler mainHandler) {
        if (taskId <= 0 || atm == null) return;
        int curWm = getTaskWindowingMode(taskId);
        // Only clear alwaysOnTop when leaving MIUI FREEFORM (5); calling setAlwaysOnTop(false)
        // on non-freeform tasks triggers spurious moveTaskToFront on Android 16+.
        Boolean clearAot = (curWm == WINDOWING_MODE_FREEFORM) ? Boolean.FALSE : null;
        if (syncFullBounds && curWm != WINDOWING_MODE_FULLSCREEN) {
            Rect fullBounds = new Rect(0, 0, Math.max(480, dispW), Math.max(480, dispH));
            if (curWm != WINDOWING_MODE_MULTI_WINDOW) {
                if (!applyWctForTask(taskId, preferredDisplayId, clearAot, WINDOWING_MODE_MULTI_WINDOW, null)) {
                    if (curWm == WINDOWING_MODE_FREEFORM) clearTaskAlwaysOnTop(taskId, preferredDisplayId);
                    forceTaskWindowingMode(taskId, preferredDisplayId, WINDOWING_MODE_MULTI_WINDOW);
                }
            }
            resizeTaskBoundsOnly(taskId, preferredDisplayId, fullBounds, true);
            Runnable finishFullscreen = () -> {
                if (!applyWctForTask(taskId, preferredDisplayId, null, WINDOWING_MODE_FULLSCREEN, new Rect())) {
                    forceTaskWindowingMode(taskId, preferredDisplayId, WINDOWING_MODE_FULLSCREEN);
                }
            };
            if (mainHandler != null && Looper.myLooper() == Looper.getMainLooper()) {
                mainHandler.postDelayed(finishFullscreen, FULLSCREEN_SETTLE_DELAY_MS);
            } else {
                SystemClock.sleep(FULLSCREEN_SETTLE_DELAY_MS);
                finishFullscreen.run();
            }
            return;
        }
        if (curWm != WINDOWING_MODE_FULLSCREEN) {
            if (!applyWctForTask(taskId, preferredDisplayId, clearAot, WINDOWING_MODE_FULLSCREEN, new Rect())) {
                if (curWm == WINDOWING_MODE_FREEFORM) clearTaskAlwaysOnTop(taskId, preferredDisplayId);
                forceTaskWindowingMode(taskId, preferredDisplayId, WINDOWING_MODE_FULLSCREEN);
            }
        }
    }

    public boolean removeTask(int taskId) {
        if (taskId <= 0 || atm == null) return false;
        try {
            for (Method m : atm.getClass().getMethods()) {
                if ("removeTask".equals(m.getName()) && m.getParameterCount() == 1) {
                    Object r = m.invoke(atm, taskId);
                    return !(r instanceof Boolean) || (Boolean) r;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_REMOVE_TASK_ERR tid=" + taskId + ": " + e.getMessage());
        }
        return false;
    }

    // ---- TaskSnapshot capture ----

    public Object getTaskSnapshotManager() {
        if (taskSnapshotMgr != null) return taskSnapshotMgr;
        if (taskSnapshotMgrChecked || atm == null) return null;
        taskSnapshotMgrChecked = true;
        try {
            Method m = atm.getClass().getMethod("getTaskSnapshotManager");
            taskSnapshotMgr = m.invoke(atm);
        } catch (NoSuchMethodException ignored) {
            // Pre-Android 17: snapshot APIs live directly on IActivityTaskManager.
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_SNAPSHOT_MGR_ERR: " + e.getMessage());
        }
        return taskSnapshotMgr;
    }

    public Object captureTaskSnapshotRaw(int taskId, boolean live) {
        if (taskId <= 0 || atm == null) return null;
        Object mgr = getTaskSnapshotManager();
        if (mgr != null) {
            Object snap = invokeSnapshotOnTarget(mgr, taskId, live);
            if (snap != null) return snap;
        }
        return invokeSnapshotOnTarget(atm, taskId, live);
    }

    public static HardwareBuffer extractSnapshotBuffer(Object snap) {
        if (snap == null) return null;
        try {
            return (HardwareBuffer) snap.getClass().getMethod("getHardwareBuffer").invoke(snap);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_SNAPSHOT_HB_ERR: " + e.getMessage());
            return null;
        }
    }

    public static ColorSpace extractSnapshotColorSpace(Object snap) {
        if (snap == null) return null;
        try {
            return (ColorSpace) snap.getClass().getMethod("getColorSpace").invoke(snap);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_SNAPSHOT_CS_ERR: " + e.getMessage());
            return null;
        }
    }

    private static Object invokeSnapshotOnTarget(Object target, int tid, boolean live) {
        if (target == null || tid <= 0) return null;
        if (live) {
            try {
                for (Method m : target.getClass().getMethods()) {
                    if (!"takeTaskSnapshot".equals(m.getName())) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    Object s = null;
                    if (pt.length == 4 && pt[0] == int.class && pt[1] == boolean.class
                            && pt[2] == boolean.class && pt[3] == boolean.class) {
                        s = m.invoke(target, tid, true, true, false);
                    } else if (pt.length == 3 && pt[0] == int.class && pt[1] == boolean.class
                            && pt[2] == boolean.class) {
                        s = m.invoke(target, tid, true, true);
                    } else if (pt.length == 2 && pt[0] == int.class && pt[1] == boolean.class) {
                        s = m.invoke(target, tid, true);
                    }
                    if (s != null) return s;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("EXTBRIDGE_TAKE_SNAPSHOT_ERR tid=" + tid + ": " + e.getMessage());
            }
        }
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!"getTaskSnapshot".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                Object s = null;
                if (pt.length == 3 && pt[0] == int.class && pt[1] == long.class && pt[2] == int.class) {
                    // Android 17 ITaskSnapshotManager.getTaskSnapshot(taskId, latestCaptureTime=0, RESOLUTION_ANY=3)
                    s = m.invoke(target, tid, 0L, SNAPSHOT_RESOLUTION_ANY);
                } else if (pt.length == 2 && pt[0] == int.class && pt[1] == boolean.class) {
                    s = m.invoke(target, tid, true);
                } else if (pt.length == 3 && pt[0] == int.class && pt[1] == boolean.class
                        && pt[2] == boolean.class) {
                    s = m.invoke(target, tid, true, live);
                } else if (pt.length == 3 && pt[0] == int.class && pt[1] == boolean.class
                        && pt[2] == int.class) {
                    s = m.invoke(target, tid, true, SNAPSHOT_RESOLUTION_ANY);
                }
                if (s != null) return s;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_GET_SNAPSHOT_ERR tid=" + tid + ": " + e.getMessage());
        }
        return null;
    }

    private static Object matchRootTaskById(List<?> infos, int taskId) {
        if (infos == null) return null;
        for (Object info : infos) {
            if (info != null && readIntField(info, "taskId", -1) == taskId) {
                return info;
            }
        }
        return null;
    }
}

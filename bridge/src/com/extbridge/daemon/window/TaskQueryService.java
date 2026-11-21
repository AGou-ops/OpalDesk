package com.extbridge.daemon.window;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.os.Parcel;
import android.util.Base64;

import com.extbridge.daemon.BridgeBuildConfig;
import com.extbridge.daemon.DaemonConfig;
import com.extbridge.daemon.DaemonConfig.PackagePolicy;
import com.extbridge.daemon.ipc.BridgeProtocol;
import com.extbridge.daemon.ipc.HelperClientBridge;
import com.extbridge.daemon.platform.AndroidServices;
import com.extbridge.daemon.platform.TaskApiAdapter;
import com.extbridge.daemon.stream.ExtCastProtocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only task/package inspection, security allowlist validation, recent-tasks parcel encoding,
 * TaskSnapshot capture encoding, and installed launcher app list collection.
 */
final class TaskQueryService {
    private static final int MAX_RECENT_TASKS_PARCEL = 10;
    private static final int MAX_APP_LABEL_CACHE = 256;
    private static final int MAX_APP_ICON_CACHE = 300;
    private static final int APP_ICON_SIZE_PX = 48;
    private static final int APP_ICON_PNG_QUALITY = 100;

    private static final class TaskSnap {
        int taskId, flags;
        String pkg;
    }

    private final AndroidServices services;
    private final DaemonConfig config;
    private final TaskApiAdapter taskApi;
    private final DesktopWindowManager windowManager;
    private final HelperClientBridge helperBridge;
    private final ConcurrentHashMap<String, String> appLabelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> appIconCache = new ConcurrentHashMap<>();

    TaskQueryService(
            AndroidServices services, DaemonConfig config, TaskApiAdapter taskApi,
            DesktopWindowManager windowManager, HelperClientBridge helperBridge) {
        this.services = services;
        this.config = config;
        this.taskApi = taskApi;
        this.windowManager = windowManager;
        this.helperBridge = helperBridge;
    }

    boolean isAllowedLaunchTarget(String pkg, String cls) {
        if (!PackagePolicy.isValidPackageName(pkg) || !PackagePolicy.isValidClassName(cls)) return false;
        if (BridgeBuildConfig.HELPER_PACKAGE.equals(pkg)) {
            return PackagePolicy.isHelperLauncherComponent(pkg, cls) && helperBridge.getVerifiedHelperUid() > 0;
        }
        if (PackagePolicy.isBlockedLaunchPkg(pkg) || services.context == null) return false;
        try {
            PackageManager pm = services.context.getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN, null)
                    .addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(pkg, cls));
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            if (ris != null && !ris.isEmpty()) return true;
            ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, cls), 0);
            return ai != null && ai.enabled && ai.exported && (ai.permission == null || ai.permission.isEmpty());
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    boolean isTaskAllowedForExtControl(int taskId, String expectedPkg, boolean blockDisplay0Settings) {
        if (taskId <= 0) return false;
        for (int pass = 0; pass < 2; pass++) {
            boolean isRecent = (pass == 1);
            List<?> list = isRecent ? taskApi.getRecentTasks() : taskApi.getTasks();
            if (list == null) continue;
            for (Object t : list) {
                if (t == null) continue;
                int tid = isRecent ? TaskApiAdapter.extractRecentTaskId(t) : TaskApiAdapter.extractTaskId(t);
                if (tid != taskId) continue;
                String tPkg = TaskApiAdapter.extractTaskPkg(t);
                int did = TaskApiAdapter.readIntField(t, "displayId", -1);
                if (PackagePolicy.isProtectedSystemTaskPkg(tPkg)) return false;
                if (expectedPkg != null && !expectedPkg.isEmpty() && !expectedPkg.equals(tPkg)) return false;
                if (config.vdId > 0 && did == config.vdId) return true;
                if (blockDisplay0Settings && "com.android.settings".equals(tPkg)) return false;
                return services.context != null && services.context.getPackageManager().getLaunchIntentForPackage(tPkg) != null;
            }
        }
        return false;
    }

    Intent resolveSafeLaunchIntent(String pkg, String cls) {
        if (!PackagePolicy.isValidPackageName(pkg) || PackagePolicy.isProtectedSystemTaskPkg(pkg)
                || BridgeBuildConfig.HELPER_PACKAGE.equals(pkg) || services.context == null) return null;
        try {
            if (cls != null && !cls.trim().isEmpty()) {
                String cleanCls = cls.trim();
                if (!isAllowedLaunchTarget(pkg, cleanCls)) return null;
                return new Intent().setComponent(new ComponentName(pkg, cleanCls))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            }
            Intent li = services.context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (li != null) li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return li;
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_RESOLVE_INTENT_WARN pkg=" + pkg + ": " + e.getMessage());
            return null;
        }
    }

    int findTaskIdForPkg(String pkg, boolean includeRecents) {
        if (pkg == null || pkg.isEmpty()) return -1;
        List<?> running = taskApi.getTasks();
        if (running != null) {
            int fallback = -1;
            for (Object t : running) {
                if (t == null || !pkg.equals(TaskApiAdapter.extractTaskPkg(t))) continue;
                int tid = TaskApiAdapter.extractTaskId(t);
                if (tid <= 0) continue;
                if (TaskApiAdapter.readIntField(t, "displayId", -1) == config.vdId) return tid;
                if (fallback <= 0) fallback = tid;
            }
            if (fallback > 0) return fallback;
        }
        if (!includeRecents) return -1;
        List<?> recent = taskApi.getRecentTasks();
        if (recent != null) {
            for (Object t : recent) {
                if (t == null || !pkg.equals(TaskApiAdapter.extractTaskPkg(t))) continue;
                int tid = TaskApiAdapter.extractRecentTaskId(t);
                if (tid > 0) return tid;
            }
        }
        return -1;
    }

    int getRunningTaskDisplayId(int taskId) {
        if (taskId <= 0) return -1;
        List<?> running = taskApi.getTasks();
        if (running == null) return -1;
        for (Object t : running) {
            if (t != null && TaskApiAdapter.extractTaskId(t) == taskId) {
                return TaskApiAdapter.readIntField(t, "displayId", -1);
            }
        }
        return -1;
    }

    int findExtLauncherTaskId() {
        int vdId = config.vdId;
        if (vdId <= 0) return -1;
        List<?> running = taskApi.getTasks();
        if (running != null) {
            for (Object t : running) {
                if (t != null && TaskApiAdapter.readIntField(t, "displayId", -1) == vdId && isLauncherTaskInfo(t)) {
                    int tid = TaskApiAdapter.extractTaskId(t);
                    if (tid > 0) return tid;
                }
            }
        }
        List<?> infos = taskApi.getAllRootTaskInfosOnDisplay(vdId);
        if (infos != null) {
            for (Object info : infos) {
                Object top = (info != null) ? TaskApiAdapter.readObjField(info, "topActivity") : null;
                if (top instanceof ComponentName) {
                    ComponentName cn = (ComponentName) top;
                    if (BridgeBuildConfig.HELPER_PACKAGE.equals(cn.getPackageName()) && cn.getClassName().contains("LauncherActivity")) {
                        int tid = TaskApiAdapter.readIntField(info, "taskId", -1);
                        if (tid > 0) return tid;
                    }
                }
            }
        }
        return -1;
    }

    static boolean isLauncherTaskInfo(Object t) {
        if (t == null || !BridgeBuildConfig.HELPER_PACKAGE.equals(TaskApiAdapter.extractTaskPkg(t))) return false;
        Object top = TaskApiAdapter.readObjField(t, "topActivity");
        if (top == null) top = TaskApiAdapter.readObjField(t, "baseActivity");
        return (top instanceof ComponentName) && ((ComponentName) top).getClassName().contains("LauncherActivity");
    }

    ArrayList<Integer> collectExtUserTaskIds(int displayId) {
        ArrayList<Integer> tids = new ArrayList<>();
        if (displayId <= 0) return tids;
        List<?> running = taskApi.getTasks();
        if (running == null) return tids;
        for (int i = running.size() - 1; i >= 0; i--) {
            Object t = running.get(i);
            if (t == null || TaskApiAdapter.readIntField(t, "displayId", -1) != displayId) continue;
            String pkg = TaskApiAdapter.extractTaskPkg(t);
            if (BridgeBuildConfig.HELPER_PACKAGE.equals(pkg) || PackagePolicy.isProtectedSystemTaskPkg(pkg)) continue;
            int tid = TaskApiAdapter.extractTaskId(t);
            if (tid > 0 && !tids.contains(tid)) tids.add(tid);
        }
        return tids;
    }

    String resolveAppLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = appLabelCache.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        if (services.context != null) {
            try {
                PackageManager pm = services.context.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence cs = (ai != null) ? pm.getApplicationLabel(ai) : null;
                if (cs != null && cs.length() > 0) label = cs.toString();
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {}
        }
        if (appLabelCache.size() < MAX_APP_LABEL_CACHE) appLabelCache.put(pkg, label);
        return label;
    }

    String[] findTopUserAppOnDisplay(int displayId) {
        if (displayId <= 0) return new String[]{"", ""};
        List<?> running = taskApi.getTasks();
        if (running == null) return new String[]{"", ""};
        for (Object t : running) {
            if (t == null || TaskApiAdapter.readIntField(t, "displayId", -1) != displayId) continue;
            if (!TaskApiAdapter.readBoolField(t, "isVisible", false)) continue;
            String pkg = TaskApiAdapter.extractTaskPkg(t);
            if (PackagePolicy.shouldSkipTaskPkg(pkg) || PackagePolicy.isProtectedSystemTaskPkg(pkg)) continue;
            return new String[]{pkg, resolveAppLabel(pkg)};
        }
        return new String[]{"", ""};
    }

    ArrayList<String> pickTilingApps(List<String> reqPkgs, int slotCount) {
        return pickTilingApps(reqPkgs, slotCount, false);
    }

    ArrayList<String> pickTilingApps(List<String> reqPkgs, int slotCount, boolean preserveEmptySlots) {
        if (slotCount <= 0) return new ArrayList<>();
        String[] existingSlots = windowManager.isTilingActive() ? windowManager.getTilingSlotPkgs() : null;
        if (preserveEmptySlots && reqPkgs != null && !reqPkgs.isEmpty()) {
            return TilingLayoutEngine.resolveSlotPackages(
                    reqPkgs, existingSlots, Collections.emptyList(), slotCount, TaskQueryService::isEligibleTilePkg, false);
        }
        ArrayList<String> resolved = TilingLayoutEngine.resolveSlotPackages(
                reqPkgs, existingSlots, Collections.emptyList(), slotCount, TaskQueryService::isEligibleTilePkg);
        if (!hasEmptySlot(resolved, slotCount)) {
            return resolved;
        }
        ArrayList<String> fallbackPkgs = new ArrayList<>();
        List<?> running = taskApi.getTasks();
        if (running != null) {
            collectTaskPkgs(running, true, fallbackPkgs);
            collectTaskPkgs(running, false, fallbackPkgs);
        }
        collectTaskPkgs(taskApi.getRecentTasks(), false, fallbackPkgs);
        return TilingLayoutEngine.resolveSlotPackages(
                reqPkgs, existingSlots, fallbackPkgs, slotCount, TaskQueryService::isEligibleTilePkg);
    }

    void writeTasksToParcel(Parcel reply) {
        if (reply == null) return;
        int vdId = config.vdId;
        LinkedHashMap<String, TaskSnap> map = new LinkedHashMap<>();
        collectTasksIntoMap(taskApi.getTasks(), false, vdId, map);
        collectTasksIntoMap(taskApi.getRecentTasks(), true, vdId, map);

        HashMap<Integer, Integer> liveWinModes = new HashMap<>();
        List<?> rootInfos = taskApi.getAllRootTaskInfosOnDisplay(vdId > 0 ? vdId : -1);
        if (rootInfos != null) {
            for (Object info : rootInfos) {
                int rTid = (info != null) ? TaskApiAdapter.readIntField(info, "taskId", -1) : -1;
                if (rTid > 0) liveWinModes.put(rTid, TaskApiAdapter.extractTaskWindowingMode(info));
            }
        }
        ArrayList<TaskSnap> extList = new ArrayList<>(), otherList = new ArrayList<>();
        for (TaskSnap ts : map.values()) {
            Integer wm = liveWinModes.get(ts.taskId);
            if (wm != null && wm == BridgeProtocol.WINDOWING_MODE_FREEFORM) ts.flags |= 8;
            if (windowManager.isPkgInActiveTiling(ts.pkg)) ts.flags |= 16;
            if ((ts.flags & 1) != 0) extList.add(ts); else otherList.add(ts);
        }
        ArrayList<TaskSnap> out = new ArrayList<>(MAX_RECENT_TASKS_PARCEL);
        for (TaskSnap ts : extList) if (out.size() < MAX_RECENT_TASKS_PARCEL) out.add(ts);
        for (TaskSnap ts : otherList) if (out.size() < MAX_RECENT_TASKS_PARCEL) out.add(ts);
        reply.writeInt(out.size());
        for (TaskSnap ts : out) { reply.writeInt(ts.taskId); reply.writeString(ts.pkg); reply.writeInt(ts.flags); }
    }

    void captureTaskSnapshotToParcel(int reqTid, String pkg, int modeFlag, Parcel reply) {
        if (reply == null) return;
        String validPkg = PackagePolicy.isValidPackageName(pkg) ? pkg : null;
        int tid = (reqTid > 0) ? reqTid : findTaskIdForPkg(validPkg, true);
        if (tid > 0 && !isTaskAllowedForExtControl(tid, validPkg, true)) tid = -1;

        Rect b = (tid > 0) ? taskApi.getTaskBounds(tid, config.vdId) : null;
        if (b != null && b.width() > 0 && b.height() > 0) {
            reply.writeInt(1); reply.writeInt(b.left); reply.writeInt(b.top); reply.writeInt(b.right); reply.writeInt(b.bottom);
        } else {
            reply.writeInt(0);
        }
        if (tid <= 0) { reply.writeInt(0); return; }

        HardwareBuffer hb = null;
        Bitmap hwBmp = null, swBmp = null;
        try {
            Object snap = taskApi.captureTaskSnapshotRaw(tid, (modeFlag & 1) != 0);
            hb = TaskApiAdapter.extractSnapshotBuffer(snap);
            if (hb == null || hb.isClosed() || hb.getWidth() <= 0 || hb.getHeight() <= 0) { reply.writeInt(0); return; }
            ColorSpace cs = TaskApiAdapter.extractSnapshotColorSpace(snap);
            if ((modeFlag & 2) == 0) {
                int csOrd = (cs != null && cs.getId() >= 0 && cs.getId() < ColorSpace.Named.values().length)
                        ? cs.getId() : ColorSpace.Named.SRGB.ordinal();
                reply.writeInt(1); reply.writeInt(csOrd); hb.writeToParcel(reply, 0);
                return;
            }
            hwBmp = Bitmap.wrapHardwareBuffer(hb, cs != null ? cs : ColorSpace.get(ColorSpace.Named.SRGB));
            swBmp = (hwBmp != null) ? hwBmp.copy(Bitmap.Config.ARGB_8888, false) : null;
            if (swBmp == null) { reply.writeInt(0); return; }
            int w = swBmp.getWidth(), h = swBmp.getHeight();
            if (Math.max(w, h) > 600) {
                float sc = 600f / Math.max(w, h);
                Bitmap scaled = Bitmap.createScaledBitmap(swBmp, Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)), true);
                if (scaled != swBmp) { swBmp.recycle(); swBmp = scaled; }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
            swBmp.compress(Bitmap.CompressFormat.JPEG, 78, baos);
            reply.writeInt(2);
            reply.writeByteArray(baos.toByteArray());
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_SNAPSHOT_PARCEL_ERR tid=" + tid + ": " + e.getMessage());
            reply.writeInt(0);
        } finally {
            if (swBmp != null && !swBmp.isRecycled()) swBmp.recycle();
            if (hwBmp != null && !hwBmp.isRecycled()) hwBmp.recycle();
            if (hb != null && !hb.isClosed()) hb.close();
        }
    }

    ExtCastProtocol.AppListMsg collectAppList(Set<Integer> extDisplayIds) {
        HashMap<String, Integer> extRunning = new HashMap<>();
        List<?> running = taskApi.getTasks();
        if (running != null && extDisplayIds != null && !extDisplayIds.isEmpty()) {
            for (Object t : running) {
                if (t == null || !extDisplayIds.contains(TaskApiAdapter.readIntField(t, "displayId", -1))) continue;
                String pkg = TaskApiAdapter.extractTaskPkg(t);
                if (!PackagePolicy.shouldSkipTaskPkg(pkg)) extRunning.putIfAbsent(pkg, TaskApiAdapter.extractTaskId(t));
            }
        }
        LinkedHashMap<String, ExtCastProtocol.AppEntry> dedup = new LinkedHashMap<>();
        if (services != null && services.context != null) {
            PackageManager pm = services.context.getPackageManager();
            List<ResolveInfo> ris = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            if (ris != null) {
                for (ResolveInfo ri : ris) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName, cls = ri.activityInfo.name;
                    if (pkg == null || pkg.isEmpty() || BridgeBuildConfig.HELPER_PACKAGE.equals(pkg)
                            || PackagePolicy.isProtectedSystemTaskPkg(pkg) || dedup.containsKey(pkg)) continue;
                    String label = pkg;
                    try {
                        CharSequence cs = ri.loadLabel(pm);
                        if (cs != null && cs.length() > 0) label = cs.toString().trim();
                    } catch (RuntimeException ignored) {}
                    if (appLabelCache.size() < MAX_APP_LABEL_CACHE) appLabelCache.put(pkg, label);
                    String iconPngBase64 = resolveAppIconBase64(ri, pm, pkg);
                    Integer tid = extRunning.get(pkg);
                    dedup.put(pkg, new ExtCastProtocol.AppEntry(
                            pkg, cls != null ? cls : "", label, tid != null, tid != null ? tid : -1, iconPngBase64));
                }
            }
        }
        ArrayList<ExtCastProtocol.AppEntry> list = new ArrayList<>(dedup.values());
        list.sort((a, b) -> (a.runningOnExt != b.runningOnExt) ? (a.runningOnExt ? -1 : 1) : a.label.compareToIgnoreCase(b.label));
        return new ExtCastProtocol.AppListMsg(list);
    }

    private String resolveAppIconBase64(ResolveInfo ri, PackageManager pm, String pkg) {
        if (ri == null || pm == null || pkg == null || pkg.isEmpty()) return "";
        String cached = appIconCache.get(pkg);
        if (cached != null) return cached;
        Bitmap bmp = null;
        try {
            Drawable drawable = ri.loadIcon(pm);
            if (drawable == null) return "";
            bmp = Bitmap.createBitmap(APP_ICON_SIZE_PX, APP_ICON_SIZE_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            drawable.setBounds(0, 0, APP_ICON_SIZE_PX, APP_ICON_SIZE_PX);
            drawable.draw(canvas);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            if (!bmp.compress(Bitmap.CompressFormat.PNG, APP_ICON_PNG_QUALITY, baos)) return "";
            byte[] pngBytes = baos.toByteArray();
            if (pngBytes.length == 0) return "";
            String encoded = Base64.encodeToString(pngBytes, Base64.NO_WRAP);
            if (encoded == null || encoded.isEmpty()) return "";
            if (appIconCache.size() < MAX_APP_ICON_CACHE) {
                appIconCache.put(pkg, encoded);
            }
            return encoded;
        } catch (RuntimeException ignored) {
            return "";
        } finally {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
    }

    private static boolean isEligibleTilePkg(String p) {
        return PackagePolicy.isValidPackageName(p) && !PackagePolicy.isProtectedSystemTaskPkg(p)
                && !BridgeBuildConfig.HELPER_PACKAGE.equals(p);
    }

    private static boolean hasEmptySlot(List<String> slots, int slotCount) {
        if (slots == null || slots.size() < slotCount) return true;
        for (int i = 0; i < slotCount; i++) {
            String p = slots.get(i);
            if (p == null || p.isEmpty()) return true;
        }
        return false;
    }

    private void collectTaskPkgs(List<?> list, boolean onlyExt, ArrayList<String> out) {
        if (list == null) return;
        for (Object t : list) {
            if (t == null) continue;
            if (onlyExt && TaskApiAdapter.readIntField(t, "displayId", -1) != config.vdId) continue;
            String pkg = TaskApiAdapter.extractTaskPkg(t);
            if (isEligibleTilePkg(pkg)) out.add(pkg);
        }
    }

    private static void collectTasksIntoMap(List<?> list, boolean isRecent, int vdId, LinkedHashMap<String, TaskSnap> map) {
        if (list == null) return;
        boolean topMarked = false;
        for (Object t : list) {
            if (t == null) continue;
            String pkg = TaskApiAdapter.extractTaskPkg(t);
            if (PackagePolicy.shouldSkipTaskPkg(pkg)) continue;
            int tid = TaskApiAdapter.extractTaskId(t);
            boolean onExt = (vdId > 0 && TaskApiAdapter.readIntField(t, "displayId", -1) == vdId);
            int fl;
            if (!isRecent) {
                fl = 2 | (onExt ? 1 : 0);
                if (onExt && !topMarked && TaskApiAdapter.readBoolField(t, "isVisible", false)) { fl |= 4; topMarked = true; }
            } else {
                fl = (onExt ? 1 : 0) | (TaskApiAdapter.readBoolField(t, "isRunning", false) ? 2 : 0);
            }
            TaskSnap ts = map.get(pkg);
            if (ts == null) {
                ts = new TaskSnap(); ts.taskId = tid; ts.pkg = pkg; ts.flags = fl; map.put(pkg, ts);
            } else {
                ts.flags |= fl;
                if (ts.taskId <= 0 && tid > 0) ts.taskId = tid;
            }
        }
    }
}

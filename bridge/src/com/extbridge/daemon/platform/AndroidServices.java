package com.extbridge.daemon.platform;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * Centralized Android hidden-API service lookup and cached reflection handles for
 * {@code ServiceManager}, {@code IWindowManager}, {@code IInputManager}, {@code IActivityTaskManager},
 * {@code IDisplayManager}, {@code IActivityManager}, {@code IAppWidgetService}, {@code IClipboard},
 * and {@code MotionEvent.setActionButton}.
 */
public final class AndroidServices {
    private static final String SHELL_PACKAGE = "com.android.shell";

    // Cached static reflection handles shared across daemon components
    private static volatile Method sGetServiceMethod;
    private static volatile boolean sGetServiceChecked;

    private static volatile Method sSetActionButtonMethod;
    private static volatile boolean sSetActionButtonChecked;

    private static volatile Object sIClipboard;
    private static volatile Method sSetPrimaryClipMethod;
    private static volatile int sSetPrimaryClipArgCount;
    private static volatile Method sGetPrimaryClipMethod;
    private static volatile int sGetPrimaryClipArgCount;
    private static volatile boolean sClipboardInitAttempted;
    private static final Object CLIPBOARD_LOCK = new Object();

    // Instance service references & cached Method handles
    public final Context context;
    public final Context systemContext;
    public final DisplayManager displayManager;
    public final InputManager inputManager;
    public final Object iWindowManager;
    public final Object iInputManager;
    public final Object iActivityTaskManager;
    public final Object iDisplayManager;
    public final Object iActivityManager;

    public final Method injectInputEventMethod;
    public final Method setDisplayIdMethod;
    public final Method getCursorPositionMethod;
    public final Method addInputAssocMethod;
    public final Method removeInputAssocMethod;
    public final Method replaceContentOnDisplayMethod;
    public final Method mirrorDisplayMethod;
    public final Method moveDisplayToTopMethod;
    public final Method startActivityMethod;
    public final Method focusTopTaskMethod;
    public final Method getDisplayInfoMethod;
    public final Method getDisplayTypeMethod;

    public final Method setUserPreferredModeMethod;
    public final Object setUserPreferredModeTarget;
    public final int setUserPreferredModeArgCount;

    public AndroidServices(
            Context context,
            DisplayManager displayManager,
            InputManager inputManager,
            Object iWindowManager,
            Object iInputManager,
            Object iActivityTaskManager,
            Object iDisplayManager,
            Object iActivityManager) {
        this(context, context, displayManager, inputManager, iWindowManager,
                iInputManager, iActivityTaskManager, iDisplayManager, iActivityManager);
    }

    public AndroidServices(
            Context context,
            Context systemContext,
            DisplayManager displayManager,
            InputManager inputManager,
            Object iWindowManager,
            Object iInputManager,
            Object iActivityTaskManager,
            Object iDisplayManager,
            Object iActivityManager) {
        this.context = context;
        this.systemContext = systemContext;
        this.displayManager = displayManager;
        this.inputManager = inputManager;
        this.iWindowManager = iWindowManager;
        this.iInputManager = iInputManager;
        this.iActivityTaskManager = iActivityTaskManager;
        this.iDisplayManager = iDisplayManager;
        this.iActivityManager = iActivityManager;

        this.injectInputEventMethod = resolveInjectInputEventMethod(iInputManager);
        this.setDisplayIdMethod = findMethodOrNull(InputEvent.class, "setDisplayId", int.class);
        this.getCursorPositionMethod = (iInputManager != null)
                ? findMethodOrNull(iInputManager.getClass(), "getCursorPositionInLogicalDisplay", int.class)
                : null;
        this.addInputAssocMethod = findMethodOrNull(
                InputManager.class, "addUniqueIdAssociationByDescriptor", String.class, String.class);
        this.removeInputAssocMethod = findMethodOrNull(
                InputManager.class, "removeUniqueIdAssociationByDescriptor", String.class);

        this.replaceContentOnDisplayMethod = findMethodByName(iWindowManager, "replaceContentOnDisplay", -1);
        this.mirrorDisplayMethod = findMethodByName(iWindowManager, "mirrorDisplay", -1);
        this.moveDisplayToTopMethod = findMethodByName(iWindowManager, "moveDisplayToTop", 2);

        this.startActivityMethod = findMethodByName(iActivityTaskManager, "startActivity", 11);
        this.focusTopTaskMethod = findMethodByName(iActivityTaskManager, "focusTopTask", 1);
        this.getDisplayInfoMethod = (iDisplayManager != null)
                ? findMethodOrNull(iDisplayManager.getClass(), "getDisplayInfo", int.class)
                : null;
        this.getDisplayTypeMethod = findMethodOrNull(Display.class, "getType");

        PreferredModeBinding pref = resolveUserPreferredModeBinding(displayManager, iDisplayManager);
        this.setUserPreferredModeMethod = pref.method;
        this.setUserPreferredModeTarget = pref.target;
        this.setUserPreferredModeArgCount = pref.argCount;
    }

    /** Creates the base Android system {@link Context} via {@code ActivityThread.systemMain()}. */
    public static Context createSystemContext() throws ReflectiveOperationException {
        Class<?> atCls = Class.forName("android.app.ActivityThread");
        Object at = atCls.getMethod("systemMain").invoke(null);
        return (Context) atCls.getMethod("getSystemContext").invoke(at);
    }

    /**
     * Initializes the daemon's shell system Context and binds all required system services.
     */
    public static AndroidServices createForDaemon() throws ReflectiveOperationException {
        Context sysCtx = createSystemContext();
        Context shellCtx = sysCtx;
        try {
            shellCtx = sysCtx.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_SHELL_CTX_WARN createPackageContext failed: " + t.getMessage());
        }
        shellCtx = fixShellContext(shellCtx);
        DisplayManager dm = (DisplayManager) shellCtx.getSystemService(Context.DISPLAY_SERVICE);
        InputManager im = (InputManager) shellCtx.getSystemService(Context.INPUT_SERVICE);

        Object wm = getServiceInterface("window", "android.view.IWindowManager$Stub");
        Object iim = getServiceInterface("input", "android.hardware.input.IInputManager$Stub");
        Object atm = getServiceInterface("activity_task", "android.app.IActivityTaskManager$Stub");
        Object idm = getServiceInterface("display", "android.hardware.display.IDisplayManager$Stub");
        Object iam = getServiceInterface("activity", "android.app.IActivityManager$Stub");
        disableLocalDisplayInfoCaches();
        return new AndroidServices(shellCtx, sysCtx, dm, im, wm, iim, atm, idm, iam);
    }

    public static IBinder getServiceBinder(String name) {
        if (name == null || name.isEmpty()) return null;
        Method m = getServiceMethod();
        if (m == null) return null;
        try {
            return (IBinder) m.invoke(null, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_SVC_LOOKUP_ERR name=" + name + ": " + unwrapMessage(e));
            return null;
        }
    }

    public static Object getServiceInterface(String serviceName, String stubClassName) {
        IBinder binder = getServiceBinder(serviceName);
        if (binder == null) return null;
        try {
            Class<?> stubCls = Class.forName(stubClassName);
            return stubCls.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_SVC_STUB_ERR name=" + serviceName + " stub=" + stubClassName + ": " + unwrapMessage(e));
            return null;
        }
    }

    /**
     * Patches ContextImpl package & AttributionSource fields to {@code com.android.shell} so
     * uid=2000 {@code app_process} Binder calls pass AppOps / AttributionSource verification.
     */
    public static Context fixShellContext(Context base) {
        if (base == null) return null;
        setStringFieldQuietly(base, "mOpPackageName", SHELL_PACKAGE);
        setStringFieldQuietly(base, "mBasePackageName", SHELL_PACKAGE);
        try {
            Object attrSrc = buildShellAttributionSource();
            if (attrSrc != null) {
                Field fAttr = base.getClass().getDeclaredField("mAttributionSource");
                fAttr.setAccessible(true);
                fAttr.set(base, attrSrc);
            }
        } catch (NoSuchFieldException ignored) {
            // Field only exists on Android 12+ ContextImpl.
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_SHELL_ATTR_WARN: " + unwrapMessage(e));
        }
        return base;
    }

    /**
     * Invokes hidden {@code MotionEvent.setActionButton(int)} using a cached {@link Method} handle.
     */
    public static void setActionButton(MotionEvent ev, int actionButton) {
        if (ev == null || actionButton == 0) return;
        if (!sSetActionButtonChecked) {
            sSetActionButtonMethod = findMethodOrNull(MotionEvent.class, "setActionButton", int.class);
            sSetActionButtonChecked = true;
        }
        Method m = sSetActionButtonMethod;
        if (m == null) return;
        try {
            m.invoke(ev, actionButton);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_SET_ACTION_BTN_ERR: " + unwrapMessage(e));
        }
    }

    public static void disableLocalDisplayInfoCaches() {
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method m = dmgCls.getMethod("disableLocalDisplayInfoCaches");
            if (Modifier.isStatic(m.getModifiers())) {
                m.invoke(null);
            } else {
                // Newer Android / HyperOS exposes disableLocalDisplayInfoCaches() as an instance method.
                Object dmg = dmgCls.getMethod("getInstance").invoke(null);
                if (dmg != null) {
                    m.invoke(dmg);
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Not present on all API levels.
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_DMG_CACHE_WARN: " + unwrapMessage(e));
        }
    }

    // ---- IClipboard cached helpers ----

    public static boolean setPrimaryClip(ClipData clip) {
        if (clip == null) return false;
        ensureClipboardInit();
        Object cb = sIClipboard;
        Method m = sSetPrimaryClipMethod;
        if (cb == null || m == null) return false;
        try {
            switch (sSetPrimaryClipArgCount) {
                case 5:
                    m.invoke(cb, clip, SHELL_PACKAGE, null, 0, 0);
                    return true;
                case 4:
                    m.invoke(cb, clip, SHELL_PACKAGE, null, 0);
                    return true;
                case 3:
                    m.invoke(cb, clip, SHELL_PACKAGE, 0);
                    return true;
                case 2:
                    m.invoke(cb, clip, SHELL_PACKAGE);
                    return true;
                default:
                    return false;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_CLIPBOARD_SET_ERR: " + unwrapMessage(e));
            return false;
        }
    }

    public static ClipData getPrimaryClip() {
        ensureClipboardInit();
        Object cb = sIClipboard;
        Method m = sGetPrimaryClipMethod;
        if (cb == null || m == null) return null;
        try {
            Object res;
            switch (sGetPrimaryClipArgCount) {
                case 4:
                    res = m.invoke(cb, SHELL_PACKAGE, null, 0, 0);
                    break;
                case 3:
                    res = m.invoke(cb, SHELL_PACKAGE, null, 0);
                    break;
                case 2:
                    res = m.invoke(cb, SHELL_PACKAGE, 0);
                    break;
                case 1:
                    res = m.invoke(cb, SHELL_PACKAGE);
                    break;
                default:
                    return null;
            }
            return (res instanceof ClipData) ? (ClipData) res : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_CLIPBOARD_GET_ERR: " + unwrapMessage(e));
            return null;
        }
    }

    // ---- IAppWidgetService & IActivityManager helpers ----

    public static boolean grantBindAppWidgetPermission(String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) return false;
        Object aw = getServiceInterface("appwidget", "com.android.internal.appwidget.IAppWidgetService$Stub");
        if (aw == null) return false;
        try {
            aw.getClass().getMethod("setBindAppWidgetPermission", String.class, int.class, boolean.class)
                    .invoke(aw, pkg, userId, true);
            Object has = aw.getClass().getMethod("hasBindAppWidgetPermission", String.class, int.class)
                    .invoke(aw, pkg, userId);
            return (has instanceof Boolean) && (Boolean) has;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_APPWIDGET_GRANT_ERR pkg=" + pkg + ": " + unwrapMessage(e));
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
        Object am = (iActivityManager != null)
                ? iActivityManager
                : getServiceInterface("activity", "android.app.IActivityManager$Stub");
        if (am == null) return null;
        try {
            return (List<ActivityManager.RunningAppProcessInfo>)
                    am.getClass().getMethod("getRunningAppProcesses").invoke(am);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_AM_PROCS_ERR: " + unwrapMessage(e));
            return null;
        }
    }

    public int getDisplayType(Display display) {
        if (display == null || getDisplayTypeMethod == null) return -1;
        try {
            return (Integer) getDisplayTypeMethod.invoke(display);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    @SuppressWarnings("deprecation")
    public Point readDisplayRealSize(int displayId) {
        Point p = new Point(0, 0);
        if (iDisplayManager != null && getDisplayInfoMethod != null) {
            try {
                Object di = getDisplayInfoMethod.invoke(iDisplayManager, displayId);
                if (di != null) {
                    int lw = TaskApiAdapter.readIntField(di, "logicalWidth", 0);
                    int lh = TaskApiAdapter.readIntField(di, "logicalHeight", 0);
                    if (lw > 0 && lh > 0) {
                        p.set(lw, lh);
                        return p;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("EXTBRIDGE_DISP_INFO_WARN displayId=" + displayId + ": " + unwrapMessage(e));
            }
        }
        if (displayManager != null) {
            try {
                Display d = displayManager.getDisplay(displayId);
                if (d != null) {
                    d.getRealSize(p);
                }
            } catch (RuntimeException e) {
                System.err.println("EXTBRIDGE_DISP_SIZE_WARN displayId=" + displayId + ": " + e.getMessage());
            }
        }
        return p;
    }

    public Object mirrorDisplay(int displayId) {
        if (mirrorDisplayMethod == null || iWindowManager == null) return null;
        try {
            if (mirrorDisplayMethod.getParameterCount() == 2) {
                Object outSc = SurfaceLayerHelper.surfaceControlClass().getConstructor().newInstance();
                Object ok = mirrorDisplayMethod.invoke(iWindowManager, displayId, outSc);
                return (ok instanceof Boolean && (Boolean) ok) ? outSc : null;
            }
            return mirrorDisplayMethod.invoke(iWindowManager, displayId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_MIRROR_DISPLAY_ERR id=" + displayId + ": " + unwrapMessage(e));
            return null;
        }
    }

    public boolean replaceContentOnDisplay(int displayId, Object rootSurfaceControl) {
        if (displayId <= 0 || iWindowManager == null || replaceContentOnDisplayMethod == null) return false;
        try {
            replaceContentOnDisplayMethod.invoke(iWindowManager, displayId, rootSurfaceControl);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_REPLACE_CONTENT_ERR id=" + displayId + ": " + unwrapMessage(e));
            return false;
        }
    }

    public void moveDisplayToTop(int displayId, String reason) {
        if (iWindowManager == null || moveDisplayToTopMethod == null) return;
        try {
            moveDisplayToTopMethod.invoke(iWindowManager, displayId, reason);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_MOVE_DISPLAY_TOP_WARN id=" + displayId + ": " + unwrapMessage(e));
        }
    }

    public void focusTopTask(int displayId) {
        if (displayId <= 0 || iActivityTaskManager == null || focusTopTaskMethod == null) return;
        try {
            focusTopTaskMethod.invoke(iActivityTaskManager, displayId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_FOCUS_TOP_TASK_WARN id=" + displayId + ": " + unwrapMessage(e));
        }
    }

    public boolean startActivityAsShell(Intent intent, Bundle options) {
        if (intent == null || iActivityTaskManager == null || startActivityMethod == null) return false;
        try {
            Object rc = startActivityMethod.invoke(
                    iActivityTaskManager, null, SHELL_PACKAGE, null, intent,
                    null, null, null, 0, 0, null, options);
            return !(rc instanceof Integer) || (Integer) rc >= 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_START_ACTIVITY_ERR intent=" + intent + ": " + unwrapMessage(e));
            return false;
        }
    }

    public static Bundle callContentProviderExternal(String authority, String method, String arg, Bundle extras) {
        IBinder tok = new Binder();
        Object am = getServiceInterface("activity", "android.app.IActivityManager$Stub");
        if (am == null) return null;
        try {
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
                if (!"call".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                sigs.append(Arrays.toString(pts)).append("; ");
                if (pts.length == 5 && "android.content.AttributionSource".equals(pts[0].getName())) callAttr5 = m;
                else if (pts.length == 5 && pts[0] == String.class) callStr5 = m;
                else if (pts.length == 6 && pts[0] == String.class) callStr6 = m;
            }
            if (callAttr5 != null) {
                Object attrSrc = buildShellAttributionSource();
                return (Bundle) callAttr5.invoke(prov, attrSrc, authority, method, arg, extras);
            } else if (callStr5 != null) {
                return (Bundle) callStr5.invoke(prov, SHELL_PACKAGE, authority, method, arg, extras);
            } else if (callStr6 != null) {
                return (Bundle) callStr6.invoke(prov, SHELL_PACKAGE, null, authority, method, arg, extras);
            } else {
                System.err.println("EXTBRIDGE_PROVIDER_CALL_NO_SIG authority=" + authority + " sigs=" + sigs);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_PROVIDER_CALL_ERR authority=" + authority + ": " + unwrapMessage(e));
        } finally {
            removeContentProviderExternalQuietly(am, authority, tok);
        }
        return null;
    }

    // ---- Internal reflection resolution helpers ----

    private static Method getServiceMethod() {
        if (!sGetServiceChecked) {
            try {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                sGetServiceMethod = smCls.getMethod("getService", String.class);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                System.err.println("EXTBRIDGE_SM_INIT_ERR: " + e.getMessage());
            } finally {
                sGetServiceChecked = true;
            }
        }
        return sGetServiceMethod;
    }

    private static void ensureClipboardInit() {
        if (sClipboardInitAttempted) return;
        synchronized (CLIPBOARD_LOCK) {
            if (sClipboardInitAttempted) return;
            sClipboardInitAttempted = true;
            Object cb = getServiceInterface("clipboard", "android.content.IClipboard$Stub");
            if (cb == null) return;
            sIClipboard = cb;
            for (Method m : cb.getClass().getMethods()) {
                String name = m.getName();
                Class<?>[] pt = m.getParameterTypes();
                if ("setPrimaryClip".equals(name) && sSetPrimaryClipMethod == null) {
                    if (pt.length == 5 && pt[0] == ClipData.class && pt[1] == String.class
                            && pt[2] == String.class && pt[3] == int.class && pt[4] == int.class) {
                        sSetPrimaryClipMethod = m;
                        sSetPrimaryClipArgCount = 5;
                    } else if (pt.length == 4 && pt[0] == ClipData.class && pt[1] == String.class
                            && pt[2] == String.class && pt[3] == int.class) {
                        sSetPrimaryClipMethod = m;
                        sSetPrimaryClipArgCount = 4;
                    } else if (pt.length == 3 && pt[0] == ClipData.class && pt[1] == String.class
                            && pt[2] == int.class) {
                        sSetPrimaryClipMethod = m;
                        sSetPrimaryClipArgCount = 3;
                    } else if (pt.length == 2 && pt[0] == ClipData.class && pt[1] == String.class) {
                        sSetPrimaryClipMethod = m;
                        sSetPrimaryClipArgCount = 2;
                    }
                } else if ("getPrimaryClip".equals(name) && sGetPrimaryClipMethod == null) {
                    if (pt.length == 4 && pt[0] == String.class && pt[1] == String.class
                            && pt[2] == int.class && pt[3] == int.class) {
                        sGetPrimaryClipMethod = m;
                        sGetPrimaryClipArgCount = 4;
                    } else if (pt.length == 3 && pt[0] == String.class && pt[1] == String.class
                            && pt[2] == int.class) {
                        sGetPrimaryClipMethod = m;
                        sGetPrimaryClipArgCount = 3;
                    } else if (pt.length == 2 && pt[0] == String.class && pt[1] == int.class) {
                        sGetPrimaryClipMethod = m;
                        sGetPrimaryClipArgCount = 2;
                    } else if (pt.length == 1 && pt[0] == String.class) {
                        sGetPrimaryClipMethod = m;
                        sGetPrimaryClipArgCount = 1;
                    }
                }
            }
        }
    }

    private static Object buildShellAttributionSource() throws ReflectiveOperationException {
        Class<?> asBuilderCls = Class.forName("android.content.AttributionSource$Builder");
        Object builder = asBuilderCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
        asBuilderCls.getMethod("setPackageName", String.class).invoke(builder, SHELL_PACKAGE);
        return asBuilderCls.getMethod("build").invoke(builder);
    }

    private static void removeContentProviderExternalQuietly(Object am, String authority, IBinder tok) {
        try {
            am.getClass().getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(am, authority, tok, 0);
            return;
        } catch (NoSuchMethodException ignored) {
            // Try older 2-arg signature below.
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_PROVIDER_REMOVE_WARN: " + unwrapMessage(e));
            return;
        }
        try {
            am.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class)
                    .invoke(am, authority, tok);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_PROVIDER_REMOVE_WARN: " + unwrapMessage(e));
        }
    }

    private static void setStringFieldQuietly(Object target, String fieldName, String value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (NoSuchFieldException ignored) {
            // Field name differs on some Android builds.
        } catch (IllegalAccessException e) {
            System.err.println("EXTBRIDGE_SHELL_FIELD_WARN field=" + fieldName + ": " + e.getMessage());
        }
    }

    private static Method resolveInjectInputEventMethod(Object iim) {
        if (iim == null) return null;
        Method exact = findMethodOrNull(iim.getClass(), "injectInputEvent", InputEvent.class, int.class);
        if (exact != null) return exact;
        return findMethodByName(iim, "injectInputEvent", -1);
    }

    private static Method findMethodOrNull(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) return null;
        try { return cls.getMethod(name, params); } catch (NoSuchMethodException ignored) { return null; }
    }

    private static Method findMethodByName(Object target, String name, int expectedParamCount) {
        if (target == null) return null;
        for (Method m : target.getClass().getMethods()) {
            if (name.equals(m.getName()) && (expectedParamCount < 0 || m.getParameterCount() == expectedParamCount)) return m;
        }
        return null;
    }

    private static final class PreferredModeBinding {
        final Method method;
        final Object target;
        final int argCount;

        PreferredModeBinding(Method method, Object target, int argCount) {
            this.method = method; this.target = target; this.argCount = argCount;
        }
    }

    private static PreferredModeBinding resolveUserPreferredModeBinding(DisplayManager dm, Object idm) {
        Method m = findMethodOrNull(DisplayManager.class, "setUserPreferredDisplayMode", int.class, Display.Mode.class, boolean.class);
        if (m != null && dm != null) return new PreferredModeBinding(m, dm, 3);
        m = findMethodOrNull(DisplayManager.class, "setUserPreferredDisplayMode", int.class, Display.Mode.class);
        if (m != null && dm != null) return new PreferredModeBinding(m, dm, 2);
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgCls.getMethod("getInstance").invoke(null);
            if (dmg != null) {
                m = findMethodOrNull(dmgCls, "setUserPreferredDisplayMode", int.class, Display.Mode.class);
                if (m != null) return new PreferredModeBinding(m, dmg, 2);
                m = findMethodOrNull(dmgCls, "setUserPreferredDisplayMode", int.class, Display.Mode.class, boolean.class);
                if (m != null) return new PreferredModeBinding(m, dmg, 3);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { /* Fall through to IDisplayManager. */ }
        if (idm != null) {
            m = findMethodOrNull(idm.getClass(), "setUserPreferredDisplayMode", int.class, Display.Mode.class);
            if (m != null) return new PreferredModeBinding(m, idm, 2);
            m = findMethodOrNull(idm.getClass(), "setUserPreferredDisplayMode", int.class, Display.Mode.class, boolean.class);
            if (m != null) return new PreferredModeBinding(m, idm, 3);
        }
        return new PreferredModeBinding(null, null, 0);
    }

    private static String unwrapMessage(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) return String.valueOf(t.getCause());
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}

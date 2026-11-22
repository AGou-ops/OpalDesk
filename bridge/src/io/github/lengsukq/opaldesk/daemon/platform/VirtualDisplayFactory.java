package io.github.lengsukq.opaldesk.daemon.platform;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Display;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Single source of truth for creating Android {@link VirtualDisplay} instances via hidden
 * {@code VirtualDisplayConfig$Builder} reflection (supporting optional {@code setHomeSupported}
 * and {@code setRequestedRefreshRate} flags across API 30-37+).
 */
public final class VirtualDisplayFactory {
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean sInitialized;
    // Remember the highest-capability flag mask accepted by this ROM so subsequent windows skip rejected masks.
    private static volatile int sLastAcceptedFlags = -1;

    private static Constructor<?> sBuilderCtor;
    private static Method sSetFlags;
    private static Method sSetHomeSupported;
    private static Method sSetRequestedRefreshRate;
    private static Method sSetSurface;
    private static Method sBuild;
    private static Method sCreateVirtualDisplay;
    private static Method sGetUniqueId;

    private VirtualDisplayFactory() {}

    private static void ensureInitialized() throws ReflectiveOperationException {
        if (sInitialized) return;
        synchronized (INIT_LOCK) {
            if (sInitialized) return;
            Class<?> builderCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Class<?> configCls = Class.forName("android.hardware.display.VirtualDisplayConfig");
            sBuilderCtor = builderCls.getConstructor(String.class, int.class, int.class, int.class);
            sSetFlags = builderCls.getMethod("setFlags", int.class);
            sSetHomeSupported = findOptionalMethod(builderCls, "setHomeSupported", boolean.class);
            sSetRequestedRefreshRate = findOptionalMethod(builderCls, "setRequestedRefreshRate", float.class);
            sSetSurface = builderCls.getMethod("setSurface", Surface.class);
            sBuild = builderCls.getMethod("build");
            sCreateVirtualDisplay = DisplayManager.class.getMethod("createVirtualDisplay", configCls);
            sGetUniqueId = findOptionalMethod(Display.class, "getUniqueId");
            sInitialized = true;
        }
    }

    /**
     * Creates a VirtualDisplay configured with the exact flags, refresh rate, and output Surface.
     */
    public static VirtualDisplay create(
            DisplayManager dm,
            String name,
            int width,
            int height,
            int dpi,
            int flags,
            float refreshRate,
            Surface surface) throws ReflectiveOperationException {
        if (dm == null) {
            throw new IllegalArgumentException("DisplayManager must not be null");
        }
        ensureInitialized();
        Object builder = sBuilderCtor.newInstance(name, width, height, dpi);
        sSetFlags.invoke(builder, flags);
        if (sSetHomeSupported != null) {
            try {
                sSetHomeSupported.invoke(builder, false);
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_VD_HOME_FLAG_WARN: " + e.getMessage());
            }
        }
        if (sSetRequestedRefreshRate != null && Float.isFinite(refreshRate) && refreshRate > 0f) {
            try {
                sSetRequestedRefreshRate.invoke(builder, refreshRate);
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("OPALDESK_VD_REFRESH_FLAG_WARN: " + e.getMessage());
            }
        }
        sSetSurface.invoke(builder, surface);
        Object config = sBuild.invoke(builder);
        return (VirtualDisplay) sCreateVirtualDisplay.invoke(dm, config);
    }

    /**
     * Attempts to create a VirtualDisplay using an ordered list of fallback flag bitmasks
     * (e.g. trying {@code STEAL_TOP_FOCUS_DISABLED} or {@code SECURE} first, then degrading).
     */
    public static VirtualDisplay createWithFlagFallback(
            DisplayManager dm,
            String name,
            int width,
            int height,
            int dpi,
            int[] candidateFlags,
            float refreshRate,
            Surface surface,
            int[] chosenFlagsOut) {
        if (dm == null || candidateFlags == null || candidateFlags.length == 0) {
            return null;
        }
        int cached = sLastAcceptedFlags;
        if (cached != -1 && containsFlag(candidateFlags, cached)) {
            VirtualDisplay fast = tryCreateOnce(dm, name, width, height, dpi, cached, refreshRate, surface);
            if (fast != null) {
                if (chosenFlagsOut != null && chosenFlagsOut.length > 0) {
                    chosenFlagsOut[0] = cached;
                }
                return fast;
            }
            sLastAcceptedFlags = -1;
        }
        for (int flags : candidateFlags) {
            if (flags == cached) continue;
            VirtualDisplay vd = tryCreateOnce(dm, name, width, height, dpi, flags, refreshRate, surface);
            if (vd != null) {
                sLastAcceptedFlags = flags;
                if (chosenFlagsOut != null && chosenFlagsOut.length > 0) {
                    chosenFlagsOut[0] = flags;
                }
                return vd;
            }
        }
        return null;
    }

    private static VirtualDisplay tryCreateOnce(
            DisplayManager dm,
            String name,
            int width,
            int height,
            int dpi,
            int flags,
            float refreshRate,
            Surface surface) {
        try {
            VirtualDisplay vd = create(dm, name, width, height, dpi, flags, refreshRate, surface);
            if (vd != null && vd.getDisplay() != null) {
                return vd;
            }
            if (vd != null) {
                vd.release();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable root = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            System.err.println("OPALDESK_VD_FLAG_RETRY name=" + name
                    + " flags=0x" + Integer.toHexString(flags) + ": " + root);
        }
        return null;
    }

    private static boolean containsFlag(int[] arr, int target) {
        for (int v : arr) {
            if (v == target) return true;
        }
        return false;
    }

    public static String getUniqueId(Display display) {
        if (display == null) return null;
        try {
            ensureInitialized();
            if (sGetUniqueId != null) {
                return (String) sGetUniqueId.invoke(display);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_VD_UNIQUE_ID_WARN: " + e.getMessage());
        }
        return null;
    }

    private static Method findOptionalMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}

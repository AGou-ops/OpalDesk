package io.github.lengsukq.opaldesk.daemon.platform;

import android.graphics.Rect;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Cached reflection facade for Android {@code SurfaceControl}, {@code SurfaceControl$Builder},
 * {@code SurfaceControl$Transaction} (wrapped as {@link AutoCloseable}), and {@code BLASTBufferQueue}.
 */
public final class SurfaceLayerHelper {
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean sInitialized;

    private static Class<?> sScCls;
    private static Constructor<?> sBuilderCtor;
    private static Method sBuilderSetName;
    private static Method sBuilderSetContainerLayer;
    private static Method sBuilderSetColorLayer;
    private static Method sBuilderSetBlastLayer;
    private static Method sBuilderSetParent;
    private static Method sBuilderSetHidden;
    private static Method sBuilderBuild;
    private static Method sScRelease;

    private static Constructor<?> sTxCtor;
    private static Method sTxSetLayer;
    private static Method sTxSetColor;
    private static Method sTxSetAlpha;
    private static Method sTxSetWindowCrop;
    private static Method sTxSetCrop;
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

    private static Constructor<?> sBbqCtor;
    private static Method sBbqUpdate;
    private static Method sBbqCreateSurface;
    private static Method sBbqDestroy;

    private SurfaceLayerHelper() {}

    public static void ensureInitialized() throws ReflectiveOperationException {
        if (sInitialized) return;
        synchronized (INIT_LOCK) {
            if (sInitialized) return;
            sScCls = Class.forName("android.view.SurfaceControl");
            Class<?> builderCls = Class.forName("android.view.SurfaceControl$Builder");
            sBuilderCtor = builderCls.getConstructor();
            sBuilderSetName = builderCls.getMethod("setName", String.class);
            sBuilderSetContainerLayer = builderCls.getMethod("setContainerLayer");
            sBuilderSetColorLayer = builderCls.getMethod("setColorLayer");
            sBuilderSetBlastLayer = builderCls.getMethod("setBLASTLayer");
            sBuilderSetParent = builderCls.getMethod("setParent", sScCls);
            sBuilderSetHidden = builderCls.getMethod("setHidden", boolean.class);
            sBuilderBuild = builderCls.getMethod("build");
            sScRelease = sScCls.getMethod("release");

            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            sTxCtor = txCls.getConstructor();
            sTxSetLayer = txCls.getMethod("setLayer", sScCls, int.class);
            sTxSetColor = txCls.getMethod("setColor", sScCls, float[].class);
            sTxSetAlpha = txCls.getMethod("setAlpha", sScCls, float.class);
            sTxSetWindowCrop = txCls.getMethod("setWindowCrop", sScCls, int.class, int.class);
            sTxSetCrop = findOptionalMethod(txCls, "setCrop", sScCls, Rect.class);
            sTxSetPosition = txCls.getMethod("setPosition", sScCls, float.class, float.class);
            sTxSetScale = txCls.getMethod("setScale", sScCls, float.class, float.class);
            sTxSetMatrix = txCls.getMethod("setMatrix", sScCls, float.class, float.class, float.class, float.class);
            sTxShow = txCls.getMethod("show", sScCls);
            sTxHide = txCls.getMethod("hide", sScCls);
            sTxReparent = txCls.getMethod("reparent", sScCls, sScCls);
            sTxRemove = txCls.getMethod("remove", sScCls);
            sTxApply = txCls.getMethod("apply");
            sTxApplySync = findOptionalMethod(txCls, "apply", boolean.class);
            sTxClose = findOptionalMethod(txCls, "close");

            try {
                Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
                sBbqCtor = bbqCls.getConstructor(String.class, boolean.class);
                sBbqUpdate = bbqCls.getMethod("update", sScCls, int.class, int.class, int.class);
                sBbqCreateSurface = bbqCls.getMethod("createSurface");
                sBbqDestroy = findOptionalMethod(bbqCls, "destroy");
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                // BLASTBufferQueue unavailable on older ROMs; caller falls back to mirrorDisplay.
            }

            sInitialized = true;
        }
    }

    public static Class<?> surfaceControlClass() throws ReflectiveOperationException {
        ensureInitialized();
        return sScCls;
    }

    public static Object buildContainerLayer(String name, Object parent, boolean hidden)
            throws ReflectiveOperationException {
        ensureInitialized();
        return buildLayer(name, sBuilderSetContainerLayer, parent, hidden);
    }

    public static Object buildColorLayer(String name, Object parent, boolean hidden)
            throws ReflectiveOperationException {
        ensureInitialized();
        return buildLayer(name, sBuilderSetColorLayer, parent, hidden);
    }

    public static Object buildBlastLayer(String name, Object parent, boolean hidden)
            throws ReflectiveOperationException {
        ensureInitialized();
        return buildLayer(name, sBuilderSetBlastLayer, parent, hidden);
    }

    private static Object buildLayer(String name, Method kindMethod, Object parent, boolean hidden)
            throws ReflectiveOperationException {
        if (kindMethod == null) {
            throw new IllegalStateException("SurfaceControl.Builder layer kind method not initialized");
        }
        Object b = sBuilderCtor.newInstance();
        sBuilderSetName.invoke(b, name);
        kindMethod.invoke(b);
        if (parent != null) {
            sBuilderSetParent.invoke(b, parent);
        }
        sBuilderSetHidden.invoke(b, hidden);
        return sBuilderBuild.invoke(b);
    }

    public static void releaseSurfaceControl(Object sc) {
        if (sc == null || sScRelease == null) return;
        try {
            sScRelease.invoke(sc);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SC_RELEASE_ERR: " + e.getMessage());
        }
    }

    public static void removeAndReleaseLayer(Object sc) {
        if (sc == null) return;
        try (Transaction tx = openTransaction()) {
            tx.remove(sc);
            tx.apply();
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SC_REMOVE_ERR: " + e.getMessage());
        } finally {
            releaseSurfaceControl(sc);
        }
    }

    public static Transaction openTransaction() throws ReflectiveOperationException {
        ensureInitialized();
        return new Transaction(sTxCtor.newInstance());
    }

    /**
     * AutoCloseable wrapper around hidden {@code android.view.SurfaceControl$Transaction}.
     */
    public static final class Transaction implements AutoCloseable {
        private final Object raw;
        private boolean closed;

        Transaction(Object raw) {
            this.raw = raw;
        }

        public Transaction setLayer(Object sc, int z) throws ReflectiveOperationException {
            sTxSetLayer.invoke(raw, sc, z);
            return this;
        }

        public Transaction setColor(Object sc, float[] rgb) throws ReflectiveOperationException {
            sTxSetColor.invoke(raw, sc, rgb);
            return this;
        }

        public Transaction setAlpha(Object sc, float alpha) throws ReflectiveOperationException {
            sTxSetAlpha.invoke(raw, sc, alpha);
            return this;
        }

        public Transaction setWindowCrop(Object sc, int width, int height) throws ReflectiveOperationException {
            sTxSetWindowCrop.invoke(raw, sc, width, height);
            return this;
        }

        public Transaction setCrop(Object sc, Rect crop) throws ReflectiveOperationException {
            if (sTxSetCrop != null) {
                sTxSetCrop.invoke(raw, sc, crop);
            }
            return this;
        }

        public Transaction setPosition(Object sc, float x, float y) throws ReflectiveOperationException {
            sTxSetPosition.invoke(raw, sc, x, y);
            return this;
        }

        public Transaction setScale(Object sc, float sx, float sy) throws ReflectiveOperationException {
            sTxSetScale.invoke(raw, sc, sx, sy);
            return this;
        }

        public Transaction setMatrix(Object sc, float dsdx, float dtdx, float dtdy, float dsdy)
                throws ReflectiveOperationException {
            sTxSetMatrix.invoke(raw, sc, dsdx, dtdx, dtdy, dsdy);
            return this;
        }

        public Transaction show(Object sc) throws ReflectiveOperationException {
            sTxShow.invoke(raw, sc);
            return this;
        }

        public Transaction hide(Object sc) throws ReflectiveOperationException {
            sTxHide.invoke(raw, sc);
            return this;
        }

        public Transaction reparent(Object sc, Object newParent) throws ReflectiveOperationException {
            sTxReparent.invoke(raw, sc, newParent);
            return this;
        }

        public Transaction remove(Object sc) throws ReflectiveOperationException {
            sTxRemove.invoke(raw, sc);
            return this;
        }

        public void apply() throws ReflectiveOperationException {
            sTxApply.invoke(raw);
        }

        public void apply(boolean sync) throws ReflectiveOperationException {
            if (sync && sTxApplySync != null) {
                sTxApplySync.invoke(raw, true);
            } else {
                sTxApply.invoke(raw);
            }
        }

        @Override
        public void close() {
            if (closed || raw == null) return;
            closed = true;
            if (sTxClose != null) {
                try {
                    sTxClose.invoke(raw);
                    return;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    System.err.println("OPALDESK_SC_TX_CLOSE_ERR: " + e.getMessage());
                    return;
                }
            }
            if (raw instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) raw).close();
                } catch (Exception e) {
                    System.err.println("OPALDESK_SC_TX_CLOSE_ERR: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Owns a {@code BLASTBufferQueue} and its produced {@link Surface}.
     */
    public static final class BlastSurface implements AutoCloseable {
        public final Object queue;
        public final Surface surface;

        BlastSurface(Object queue, Surface surface) {
            this.queue = queue;
            this.surface = surface;
        }

        public void update(Object surfaceControl, int width, int height, int format)
                throws ReflectiveOperationException {
            updateBlastBufferQueue(queue, surfaceControl, width, height, format);
        }

        @Override
        public void close() {
            if (surface != null) {
                try {
                    surface.release();
                } catch (RuntimeException e) {
                    System.err.println("OPALDESK_BBQ_SURF_RELEASE_ERR: " + e.getMessage());
                }
            }
            destroyBlastBufferQueue(queue);
        }
    }

    public static BlastSurface createBlastSurface(
            String name, Object surfaceControl, int width, int height, int format)
            throws ReflectiveOperationException {
        ensureInitialized();
        if (sBbqCtor == null || sBbqUpdate == null || sBbqCreateSurface == null) {
            throw new NoSuchMethodException("BLASTBufferQueue unavailable on this Android build");
        }
        Object bbq = sBbqCtor.newInstance(name, true);
        try {
            sBbqUpdate.invoke(bbq, surfaceControl, width, height, format);
            Surface surf = (Surface) sBbqCreateSurface.invoke(bbq);
            return new BlastSurface(bbq, surf);
        } catch (ReflectiveOperationException | RuntimeException e) {
            destroyBlastBufferQueue(bbq);
            throw e;
        }
    }

    public static void updateBlastBufferQueue(
            Object bbq, Object surfaceControl, int width, int height, int format)
            throws ReflectiveOperationException {
        ensureInitialized();
        if (bbq == null || sBbqUpdate == null) return;
        sBbqUpdate.invoke(bbq, surfaceControl, width, height, format);
    }

    public static void destroyBlastBufferQueue(Object bbq) {
        if (bbq == null) return;
        try {
            if (sBbqDestroy != null) {
                sBbqDestroy.invoke(bbq);
            } else {
                bbq.getClass().getMethod("destroy").invoke(bbq);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_BBQ_DESTROY_ERR: " + e.getMessage());
        }
    }

    private static Method findOptionalMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}

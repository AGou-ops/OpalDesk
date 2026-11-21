package com.extbridge.daemon.platform;

import android.os.Binder;
import android.os.Handler;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time {@code IActivityTaskManager.registerTaskStackListener} observer that operates
 * purely against public Android SDK APIs without compile-time hidden stubs.
 *
 * <p>All {@code android.app.ITaskStackListener} binder methods are {@code oneway void}.
 * Rather than depending on fragile per-Android-version transaction code ordinals, the raw
 * {@link Binder#onTransact} receiver accepts any interface transaction and triggers a
 * debounced reconciliation callback in under 30ms.
 */
public final class TaskStackObserver {
    private static final String I_TASK_STACK_LISTENER = "android.app.ITaskStackListener";
    private static final long DEBOUNCE_MS = 25L;

    private final Object atm;
    private final Handler handler;
    private final Runnable onTaskStackDirty;
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicBoolean pendingDispatch = new AtomicBoolean(false);

    private volatile Object registeredProxy;

    private final Runnable dispatchTask = this::runDispatch;

    public TaskStackObserver(Object iActivityTaskManager, Handler handler, Runnable onTaskStackDirty) {
        this.atm = iActivityTaskManager;
        this.handler = handler;
        this.onTaskStackDirty = onTaskStackDirty;
    }

    private void runDispatch() {
        pendingDispatch.set(false);
        try {
            if (onTaskStackDirty != null) onTaskStackDirty.run();
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_TASK_STACK_CB_ERR: " + e.getMessage());
        }
    }

    public boolean start() {
        if (atm == null || onTaskStackDirty == null) return false;
        if (registered.get()) return true;
        try {
            Class<?> listenerIface = Class.forName(I_TASK_STACK_LISTENER);
            RawTaskStackBinder rawBinder = new RawTaskStackBinder();
            Object proxy = Proxy.newProxyInstance(
                    listenerIface.getClassLoader(),
                    new Class<?>[]{listenerIface},
                    (p, method, args) -> {
                        String name = method.getName();
                        if ("asBinder".equals(name)) return rawBinder;
                        if ("toString".equals(name)) return "ExtBridgeTaskStackListener";
                        if ("hashCode".equals(name)) return System.identityHashCode(p);
                        if ("equals".equals(name)) return args != null && args.length == 1 && args[0] == p;
                        scheduleDebouncedSync();
                        return null;
                    });
            Method regMethod = atm.getClass().getMethod("registerTaskStackListener", listenerIface);
            regMethod.invoke(atm, proxy);
            registeredProxy = proxy;
            registered.set(true);
            System.out.println("EXTBRIDGE_TASK_STACK_OBSERVER_ACTIVE");
            return true;
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_TASK_STACK_OBSERVER_FALLBACK: " + t.getMessage());
            return false;
        }
    }

    public void stop() {
        Object proxy = registeredProxy;
        registeredProxy = null;
        if (handler != null) {
            handler.removeCallbacks(dispatchTask);
        }
        pendingDispatch.set(false);
        if (!registered.getAndSet(false) || proxy == null || atm == null) return;
        try {
            Class<?> listenerIface = Class.forName(I_TASK_STACK_LISTENER);
            Method unregMethod = atm.getClass().getMethod("unregisterTaskStackListener", listenerIface);
            unregMethod.invoke(atm, proxy);
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_TASK_STACK_UNREG_WARN: " + t.getMessage());
        }
    }

    public boolean isRegistered() {
        return registered.get();
    }

    private void scheduleDebouncedSync() {
        if (!registered.get()) return;
        if (handler == null) {
            onTaskStackDirty.run();
            return;
        }
        if (pendingDispatch.compareAndSet(false, true)) {
            handler.postDelayed(dispatchTask, DEBOUNCE_MS);
        }
    }

    private final class RawTaskStackBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(I_TASK_STACK_LISTENER);
                return true;
            }
            if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                if (data != null) {
                    try {
                        data.enforceInterface(I_TASK_STACK_LISTENER);
                    } catch (SecurityException ignored) {
                        // Accept variant descriptors across OEM ROMs without crashing system_server.
                    }
                }
                scheduleDebouncedSync();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}

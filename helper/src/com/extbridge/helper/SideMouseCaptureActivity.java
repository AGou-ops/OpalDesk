package com.extbridge.helper;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zero-UI transparent fallback Activity used on Display 0 only if a ROM restricts
 * {@code View.requestPointerCapture()} on overlay windows. Captures physical mouse
 * motion/clicks inside the mirror sidebar and finishes immediately when cursor crosses back
 * or if the user touches Display 0 with a finger / loses window focus.
 */
public class SideMouseCaptureActivity extends Activity {

    private static volatile SideMouseCaptureActivity sActive;

    private View mCaptureView;
    private ExecutorService mExec;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mClosing = false;
    private Object mBackCallback;

    private final Runnable mCaptureWatchdog = () -> {
        if (!mClosing && !isFinishing()) {
            boolean ok = Build.VERSION.SDK_INT >= 26
                    && mCaptureView != null
                    && mCaptureView.hasPointerCapture();
            if (!ok) {
                exitDaemonCaptureAsync();
                finishSilently();
            }
        }
    };

    public static void finishActiveIfAny() {
        SideMouseCaptureActivity act = sActive;
        if (act != null && !act.mClosing) {
            act.mUiHandler.post(() -> {
                act.exitDaemonCaptureAsync();
                act.finishSilently();
            });
        }
    }

    public static void launch(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(ctx, SideMouseCaptureActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (Build.VERSION.SDK_INT >= 26) {
                ActivityOptions ao = ActivityOptions.makeBasic();
                ao.setLaunchDisplayId(0);
                try {
                    ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(ao, 1);
                } catch (Throwable ignored) {}
                ctx.startActivity(i, ao.toBundle());
            } else {
                ctx.startActivity(i);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sActive = this;
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        mExec = Executors.newSingleThreadExecutor();

        mCaptureView = new View(this);
        mCaptureView.setBackgroundColor(Color.TRANSPARENT);
        mCaptureView.setFocusable(true);
        mCaptureView.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 26) {
            mCaptureView.setOnCapturedPointerListener((v, ev) ->
                    BridgeService.handleCapturedPointerEventStatic(ev, mExec, () -> runOnUiThread(this::finishSilently)));
        }
        setContentView(mCaptureView);

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                OnBackInvokedCallback cb = () -> {
                    exitDaemonCaptureAsync();
                    finishSilently();
                };
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
                mBackCallback = cb;
            } catch (Throwable ignored) {}
        }
        mUiHandler.postDelayed(mCaptureWatchdog, 600L);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Never trap finger / stylus touches on Display 0: immediately release capture & finish
        if (ev != null) {
            boolean isTouchScreen = (ev.getSource() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN;
            boolean nonMouse = ev.getPointerCount() > 0 && ev.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE;
            if (isTouchScreen || nonMouse) {
                exitDaemonCaptureAsync();
                finishSilently();
                return false;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mClosing) return;
        if (!hasFocus) {
            // Allow transient AppWidget trampoline Activities (e.g. ~30ms AppwidgetPlayerRedirectActivity)
            // to finish without tearing down sidebar mouse capture; watchdog exits if focus stays lost.
            mUiHandler.removeCallbacks(mCaptureWatchdog);
            mUiHandler.postDelayed(mCaptureWatchdog, 320L);
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && mCaptureView != null) {
            try {
                mCaptureView.requestFocus();
                mCaptureView.requestPointerCapture();
            } catch (Throwable ignored) {}
            mUiHandler.removeCallbacks(mCaptureWatchdog);
            mUiHandler.postDelayed(mCaptureWatchdog, 280L);
        }
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
        if (hasCapture) {
            mUiHandler.removeCallbacks(mCaptureWatchdog);
        } else if (!mClosing && !isFinishing()) {
            if (Build.VERSION.SDK_INT >= 26 && mCaptureView != null && hasWindowFocus()) {
                mCaptureView.post(() -> {
                    if (!mClosing && mCaptureView != null && hasWindowFocus()) {
                        try { mCaptureView.requestPointerCapture(); } catch (Throwable ignored) {}
                    }
                });
            }
            mUiHandler.removeCallbacks(mCaptureWatchdog);
            mUiHandler.postDelayed(mCaptureWatchdog, 320L);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!mClosing) {
            exitDaemonCaptureAsync();
            finishSilently();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        exitDaemonCaptureAsync();
        finishSilently();
    }

    private void exitDaemonCaptureAsync() {
        BridgeService.resetCapturedMouseMoveState();
        ExecutorService exec = mExec;
        if (exec != null && !exec.isShutdown()) {
            try {
                exec.execute(() -> BridgeClient.sendCapturedMirrorMouse(5, 0f, 0f, 0f));
                return;
            } catch (Throwable ignored) {}
        }
        new Thread(() -> BridgeClient.sendCapturedMirrorMouse(5, 0f, 0f, 0f)).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            exitDaemonCaptureAsync();
            finishSilently();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void finishSilently() {
        if (mClosing) return;
        mClosing = true;
        mUiHandler.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 26 && mCaptureView != null) {
            try { mCaptureView.releasePointerCapture(); } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 21) {
            finishAndRemoveTask();
        } else {
            finish();
        }
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (sActive == this) sActive = null;
        mUiHandler.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 33 && mBackCallback instanceof OnBackInvokedCallback) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) mBackCallback);
            } catch (Throwable ignored) {}
            mBackCallback = null;
        }
        if (!mClosing) {
            mClosing = true;
            new Thread(() -> BridgeClient.sendCapturedMirrorMouse(5, 0f, 0f, 0f)).start();
        }
        if (mExec != null) {
            mExec.shutdownNow();
            mExec = null;
        }
        BridgeService.resetCapturedMouseMoveState();
        super.onDestroy();
    }
}

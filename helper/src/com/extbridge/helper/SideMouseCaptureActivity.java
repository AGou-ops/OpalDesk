package com.extbridge.helper;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zero-UI transparent fallback Activity used on Display 0 only if a ROM restricts
 * {@code View.requestPointerCapture()} on overlay windows. Captures physical mouse
 * motion/clicks inside the mirror sidebar and finishes immediately when cursor crosses back.
 */
public class SideMouseCaptureActivity extends Activity {

    private View mCaptureView;
    private ExecutorService mExec;
    private volatile boolean mClosing = false;

    public static void launch(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(ctx, SideMouseCaptureActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
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
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
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
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && Build.VERSION.SDK_INT >= 26 && mCaptureView != null && !mClosing) {
            try {
                mCaptureView.requestFocus();
                mCaptureView.requestPointerCapture();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
        if (!hasCapture && !mClosing && !isFinishing()) {
            exitDaemonCaptureAsync();
            finishSilently();
        }
    }

    private void exitDaemonCaptureAsync() {
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
        if (!mClosing) {
            mClosing = true;
            new Thread(() -> BridgeClient.sendCapturedMirrorMouse(5, 0f, 0f, 0f)).start();
        }
        if (mExec != null) {
            mExec.shutdownNow();
            mExec = null;
        }
        super.onDestroy();
    }
}

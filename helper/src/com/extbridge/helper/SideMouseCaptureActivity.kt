package com.extbridge.helper

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Zero-UI transparent fallback Activity used on Display 0 only if a ROM restricts
 * `View.requestPointerCapture()` on overlay windows. Captures physical mouse
 * motion/clicks inside the mirror sidebar and finishes immediately when cursor crosses back
 * or if the user touches Display 0 with a finger / loses window focus.
 */
class SideMouseCaptureActivity : Activity() {

    companion object {
        @Volatile
        private var sActive: SideMouseCaptureActivity? = null

        @JvmStatic
        fun finishActiveIfAny() {
            val act = sActive
            if (act != null && !act.mClosing) {
                act.mUiHandler.post {
                    act.exitDaemonCaptureAsync()
                    act.finishSilently()
                }
            }
        }

        @JvmStatic
        fun launch(ctx: Context?) {
            if (ctx == null) return
            try {
                val i = Intent(ctx, SideMouseCaptureActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    )
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    val ao = ActivityOptions.makeBasic()
                    ao.setLaunchDisplayId(0)
                    try {
                        ActivityOptions::class.java
                            .getMethod("setLaunchWindowingMode", Int::class.java)
                            .invoke(ao, 1)
                    } catch (_: Throwable) {}
                    ctx.startActivity(i, ao.toBundle())
                } else {
                    ctx.startActivity(i)
                }
            } catch (_: Throwable) {}
        }
    }

    private var mCaptureView: View? = null
    private var mExec: ExecutorService? = null
    private val mUiHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var mClosing = false
    private var mBackCallback: Any? = null

    private val mCaptureWatchdog = Runnable {
        if (!mClosing && !isFinishing) {
            val ok = Build.VERSION.SDK_INT >= 26 && mCaptureView?.hasPointerCapture() == true
            if (!ok) {
                exitDaemonCaptureAsync()
                finishSilently()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sActive = this
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        mExec = Executors.newSingleThreadExecutor()

        val captureView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= 26) {
                setOnCapturedPointerListener { _, ev ->
                    BridgeService.handleCapturedPointerEventStatic(
                        ev,
                        mExec,
                        Runnable { runOnUiThread { finishSilently() } },
                    )
                }
            }
        }
        mCaptureView = captureView
        setContentView(captureView)

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val cb = OnBackInvokedCallback {
                    exitDaemonCaptureAsync()
                    finishSilently()
                }
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    cb,
                )
                mBackCallback = cb
            } catch (_: Throwable) {}
        }
        mUiHandler.postDelayed(mCaptureWatchdog, 600L)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Never trap finger / stylus touches on Display 0: immediately release capture & finish
        if (ev != null) {
            val isTouchScreen = (ev.source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
            val nonMouse = ev.pointerCount > 0 && ev.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE
            if (isTouchScreen || nonMouse) {
                exitDaemonCaptureAsync()
                finishSilently()
                return false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (mClosing) return
        if (!hasFocus) {
            // Allow transient AppWidget trampoline Activities (e.g. ~30ms AppwidgetPlayerRedirectActivity)
            // to finish without tearing down sidebar mouse capture; watchdog exits if focus stays lost.
            mUiHandler.removeCallbacks(mCaptureWatchdog)
            mUiHandler.postDelayed(mCaptureWatchdog, 320L)
            return
        }
        val captureView = mCaptureView
        if (Build.VERSION.SDK_INT >= 26 && captureView != null) {
            try {
                captureView.requestFocus()
                captureView.requestPointerCapture()
            } catch (_: Throwable) {}
            mUiHandler.removeCallbacks(mCaptureWatchdog)
            mUiHandler.postDelayed(mCaptureWatchdog, 280L)
        }
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        if (hasCapture) {
            mUiHandler.removeCallbacks(mCaptureWatchdog)
        } else if (!mClosing && !isFinishing) {
            val captureView = mCaptureView
            if (Build.VERSION.SDK_INT >= 26 && captureView != null && hasWindowFocus()) {
                captureView.post {
                    val v = mCaptureView
                    if (!mClosing && v != null && hasWindowFocus()) {
                        try { v.requestPointerCapture() } catch (_: Throwable) {}
                    }
                }
            }
            mUiHandler.removeCallbacks(mCaptureWatchdog)
            mUiHandler.postDelayed(mCaptureWatchdog, 320L)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!mClosing) {
            exitDaemonCaptureAsync()
            finishSilently()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        exitDaemonCaptureAsync()
        finishSilently()
    }

    private fun exitDaemonCaptureAsync() {
        BridgeService.resetCapturedMouseMoveState()
        val exec = mExec
        if (exec != null && !exec.isShutdown) {
            try {
                exec.execute { BridgeClient.sendCapturedMirrorMouse(5, 0f, 0f, 0f) }
                return
            } catch (_: Throwable) {}
        }
        Thread { BridgeClient.sendCapturedMirrorMouse(5, 0f, 0f, 0f) }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            exitDaemonCaptureAsync()
            finishSilently()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    private fun finishSilently() {
        if (mClosing) return
        mClosing = true
        mUiHandler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 26) {
            try { mCaptureView?.releasePointerCapture() } catch (_: Throwable) {}
        }
        if (Build.VERSION.SDK_INT >= 21) {
            finishAndRemoveTask()
        } else {
            finish()
        }
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (sActive === this) sActive = null
        mUiHandler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 33 && mBackCallback is OnBackInvokedCallback) {
            try {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(mBackCallback as OnBackInvokedCallback)
            } catch (_: Throwable) {}
            mBackCallback = null
        }
        if (!mClosing) {
            mClosing = true
            Thread { BridgeClient.sendCapturedMirrorMouse(5, 0f, 0f, 0f) }.start()
        }
        mExec?.shutdownNow()
        mExec = null
        BridgeService.resetCapturedMouseMoveState()
        super.onDestroy()
    }
}

package io.github.lengsukq.opaldesk.compositor

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.WindowManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standard public-API Control Center helpers for Local/External DeX:
 * - Activity window brightness control (zero extra permissions)
 * - Media playback transport via [AudioManager.dispatchMediaKeyEvent]
 * - Orientation lock (Auto / Landscape / Portrait)
 * - Full-screen ([PixelCopy]) & focused-window ([LiveWindowPreviewRegistry]) screenshots saved to
 *   MediaStore Gallery (`Pictures/OpalDesk`) and copied to clipboard.
 */
object ControlCenterActions {
    private val mainHandler = Handler(Looper.getMainLooper())

    tailrec fun findActivity(ctx: Context?): Activity? = when (ctx) {
        is Activity -> ctx
        is ContextWrapper -> findActivity(ctx.baseContext)
        else -> null
    }

    fun readInitialBrightness(ctx: Context): Float {
        val act = findActivity(ctx)
        val overrideVal = act?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (overrideVal in 0f..1f) return overrideVal
        val sys = runCatching {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        }.getOrDefault(128)
        return (sys / 255f).coerceIn(0.08f, 1f)
    }

    fun applyWindowBrightness(ctx: Context, brightness: Float) {
        val win = findActivity(ctx)?.window ?: return
        val lp = win.attributes
        lp.screenBrightness = if (brightness < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            brightness.coerceIn(0.05f, 1.0f)
        }
        win.attributes = lp
    }

    fun readOrientationMode(ctx: Context): Int {
        return when (findActivity(ctx)?.requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> 1
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> 2
            else -> 0
        }
    }

    fun applyOrientationMode(ctx: Context, mode: Int) {
        val act = findActivity(ctx) ?: return
        act.requestedOrientation = when (mode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun dispatchMediaKey(audioMgr: AudioManager?, keyCode: Int) {
        if (audioMgr == null) return
        val now = SystemClock.uptimeMillis()
        runCatching {
            audioMgr.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioMgr.dispatchMediaKeyEvent(KeyEvent(now, now + 15L, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    fun captureFocusedWindowScreenshot(ctx: Context, windowId: Int) {
        if (windowId <= 0) {
            Toast.makeText(ctx, "当前没有可截取的焦点窗口", Toast.LENGTH_SHORT).show()
            return
        }
        val bmp = LiveWindowPreviewRegistry.captureLivePreview(windowId, maxLongEdge = 2400)
        if (bmp == null || bmp.isRecycled) {
            Toast.makeText(ctx, "无法获取当前窗口画面", Toast.LENGTH_SHORT).show()
            return
        }
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
        saveBitmapAsync(ctx.applicationContext, copy, prefix = "OpalDesk_Win")
    }

    fun captureDesktopScreenshot(ctx: Context) {
        val act = findActivity(ctx)
        val win = act?.window
        val decor = win?.decorView
        if (win == null || decor == null || decor.width <= 0 || decor.height <= 0) {
            Toast.makeText(ctx, "当前桌面画布尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val appCtx = ctx.applicationContext
        val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                PixelCopy.request(
                    win,
                    bmp,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            saveBitmapAsync(appCtx, bmp, prefix = "OpalDesk_Desktop")
                        } else {
                            bmp.recycle()
                            mainHandler.post {
                                Toast.makeText(appCtx, "截屏失败 (PixelCopy=$result)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    mainHandler,
                )
            }.onFailure {
                bmp.recycle()
                Toast.makeText(appCtx, "截屏失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            val canvas = android.graphics.Canvas(bmp)
            decor.draw(canvas)
            saveBitmapAsync(appCtx, bmp, prefix = "OpalDesk_Desktop")
        }
    }

    private fun saveBitmapAsync(appCtx: Context, bmp: Bitmap, prefix: String) {
        Thread({
            val uri = saveToGallery(appCtx, bmp, prefix)
            if (!bmp.isRecycled) {
                runCatching { bmp.recycle() }
            }
            mainHandler.post {
                if (uri != null) {
                    runCatching {
                        val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newUri(appCtx.contentResolver, "OpalDesk Screenshot", uri))
                    }
                    Toast.makeText(appCtx, "截图已保存到相册 (Pictures/OpalDesk) 并复制", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(appCtx, "保存截图到相册失败", Toast.LENGTH_SHORT).show()
                }
            }
        }, "OpalScreenshotSave").start()
    }

    private fun saveToGallery(appCtx: Context, bmp: Bitmap, prefix: String): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${prefix}_$stamp.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OpalDesk")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = appCtx.contentResolver
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            uri
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}

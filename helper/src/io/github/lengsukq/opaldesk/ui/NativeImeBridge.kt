package io.github.lengsukq.opaldesk.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.compositor.applyWindowTilingLayout
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout

/**
 * Tracks whether an in-process Compose text field (such as App Drawer search or Trackpad IME bar)
 * currently holds focus so physical keyboards & remote keystreams never lose Space/Enter/Arrow keys
 * to accidental Compose button focus traversal, yet still type cleanly into OpalDesk search boxes.
 */
object OpalImeFocusTracker {
    @Volatile
    var localComposeTextFieldFocused: Boolean = false
        private set

    fun setLocalTextFieldFocused(focused: Boolean) {
        if (localComposeTextFieldFocused == focused) return
        localComposeTextFieldFocused = focused
        BridgeClient.setLocalTextFieldFocusedOneway(focused)
    }
}

/**
 * Shared physical/remote keyboard dispatcher across [io.github.lengsukq.opaldesk.DesktopActivity],
 * [io.github.lengsukq.opaldesk.ExternalDesktopActivity], and
 * [io.github.lengsukq.opaldesk.external.ExternalMonitorPresentation].
 */
object WorkstationKeyDispatcher {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun handleGlobalShortcut(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val scene = BridgeClient.sceneFlow.value
        val focused = scene.focusedWindow

        if (event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_TAB) {
            val candidates = scene.windows.filter { !it.isMinimized }
            if (candidates.size >= 2) {
                val idx = candidates.indexOfFirst { it.focused }
                val next = candidates[(idx + 1) % candidates.size]
                BridgeClient.sendWindowActionAsync(next.windowId, BridgeProtocol.WinAction.FOCUS)
            }
            return true
        }
        if (event.isAltPressed) {
            val layout = when (event.keyCode) {
                KeyEvent.KEYCODE_2 -> WindowTilingLayout.SPLIT_2_COLS
                KeyEvent.KEYCODE_3 -> WindowTilingLayout.SPLIT_3_COLS
                KeyEvent.KEYCODE_4 -> WindowTilingLayout.SPLIT_4_COLS
                KeyEvent.KEYCODE_G -> WindowTilingLayout.SPLIT_4_GRID
                else -> null
            }
            if (layout != null) {
                val st = BridgeClient.statusFlow.value
                applyWindowTilingLayout(
                    layout = layout,
                    scene = scene,
                    canvasW = st.canvasWidth,
                    canvasH = st.canvasHeight,
                    density = (st.canvasDpi / 160f).coerceIn(0.8f, 3.5f),
                )
                return true
            }
        }
        if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_W && focused != null) {
            BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.CLOSE)
            return true
        }
        if (event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_ENTER && focused != null) {
            BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.TOGGLE_FULLSCREEN)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && focused != null && focused.isFullscreen) {
            BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.TOGGLE_FULLSCREEN)
            return true
        }
        return false
    }

    fun isForwardableKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH -> false
        else -> true
    }

    fun forwardToFocusedWindow(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (!isForwardableKey(event.keyCode)) return false
        val focused = BridgeClient.sceneFlow.value.focusedWindow ?: return false
        if (focused.isMinimized) return false
        BridgeClient.injectWindowKeyAsync(
            windowId = focused.windowId,
            action = event.action,
            keyCode = event.keyCode,
            metaState = event.metaState,
        )
        return true
    }

    fun commitTextToFocusedWindow(ctx: Context, text: String) {
        if (text.isEmpty()) return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk", text))
        } catch (_: Throwable) {
        }
        val focused = BridgeClient.sceneFlow.value.focusedWindow ?: return
        if (focused.isMinimized) return
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        mainHandler.postDelayed({
            BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl)
            BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl)
        }, 40L)
    }
}

/**
 * Imperative controller for summoning / hiding the host device's native system IME (Xiaomi / Sogou /
 * Gboard / Baidu / handwriting / voice) directly onto a SurfaceView cast stream or VirtualDisplay canvas.
 */
class NativeImeHostController {
    internal var bridgeView: NativeImeBridgeView? = null

    fun showKeyboard(inputType: Int = 0) {
        bridgeView?.showIme(inputType)
    }

    fun hideKeyboard() {
        bridgeView?.hideIme()
    }

    fun toggleKeyboard(currentlyVisible: Boolean) {
        if (currentlyVisible) hideKeyboard() else showKeyboard()
    }
}

@Composable
fun rememberNativeImeHostController(): NativeImeHostController = remember { NativeImeHostController() }

/**
 * Invisible 1dp native [InputConnection] host that summons the device's built-in IME and routes
 * ASCII keys, Chinese/Unicode phrases, backspace, and Enter/Search actions seamlessly.
 */
@Composable
fun NativeImeBridgeHost(
    controller: NativeImeHostController,
    onSendKey: (action: Int, keyCode: Int, metaState: Int) -> Unit,
    onCommitText: (String) -> Unit,
    onComposingChanged: (String) -> Unit = {},
    onImeVisibilityChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val curSendKey by rememberUpdatedState(onSendKey)
    val curCommitText by rememberUpdatedState(onCommitText)
    val curComposing by rememberUpdatedState(onComposingChanged)
    val curVisibility by rememberUpdatedState(onImeVisibilityChanged)

    DisposableEffect(controller) {
        onDispose {
            controller.hideKeyboard()
            controller.bridgeView = null
        }
    }

    AndroidView(
        modifier = modifier.size(1.dp),
        factory = { ctx ->
            NativeImeBridgeView(
                ctx = ctx,
                onSendKey = { a, k, m -> curSendKey(a, k, m) },
                onCommitText = { txt -> curCommitText(txt) },
                onComposingChanged = { comp -> curComposing(comp) },
                onImeVisibilityChanged = { vis -> curVisibility(vis) },
            ).also { v ->
                controller.bridgeView = v
            }
        },
        update = { v ->
            controller.bridgeView = v
        },
    )
}

@SuppressLint("ViewConstructor")
internal class NativeImeBridgeView(
    ctx: Context,
    private val onSendKey: (Int, Int, Int) -> Unit,
    private val onCommitText: (String) -> Unit,
    private val onComposingChanged: (String) -> Unit,
    private val onImeVisibilityChanged: (Boolean) -> Unit,
) : View(ctx) {

    companion object {
        private const val DEFAULT_INPUT_TYPE =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private val keyCharMap: KeyCharacterMap? by lazy {
        try {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        } catch (_: Throwable) {
            null
        }
    }
    private var lastImeVisible = false
    private var pendingShowAfterFocus = false
    private var activeInputType = DEFAULT_INPUT_TYPE

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (visible != lastImeVisible) {
                lastImeVisible = visible
                onImeVisibilityChanged(visible)
                if (!visible) {
                    onComposingChanged("")
                    if (!pendingShowAfterFocus) {
                        clearFocus()
                        BridgeClient.setLocalTextFieldFocusedOneway(false)
                    }
                }
            }
            insets
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && pendingShowAfterFocus && isAttachedToWindow) {
            pendingShowAfterFocus = false
            triggerShowNow()
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = activeInputType
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_SEND
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                onComposingChanged("")
                val str = text?.toString().orEmpty()
                if (str.isNotEmpty()) {
                    dispatchCommittedString(str)
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                onComposingChanged(text?.toString().orEmpty())
                return true
            }

            override fun finishComposingText(): Boolean {
                onComposingChanged("")
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 16)) {
                    sendTap(KeyEvent.KEYCODE_DEL)
                }
                repeat(afterLength.coerceIn(0, 16)) {
                    sendTap(KeyEvent.KEYCODE_FORWARD_DEL)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                    onSendKey(event.action, event.keyCode, event.metaState)
                    return true
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                onComposingChanged("")
                sendTap(KeyEvent.KEYCODE_ENTER)
                return true
            }
        }
    }

    @Suppress("DEPRECATION")
    fun showIme(inputType: Int = 0) {
        activeInputType = if (inputType != 0) inputType else DEFAULT_INPUT_TYPE
        // Tell the daemon to keep Display 0 top-focused so InputMethodManagerService accepts showSoftInput
        // and subsequent forwarded keystrokes do not steal top display focus away from the host Activity.
        BridgeClient.setLocalTextFieldFocusedOneway(true)
        pendingShowAfterFocus = true
        triggerShowNow()
        postDelayed({
            if (!isAttachedToWindow) return@postDelayed
            pendingShowAfterFocus = false
            if (!lastImeVisible) {
                triggerShowNow()
            }
        }, 120L)
    }

    @Suppress("DEPRECATION")
    private fun triggerShowNow() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
        findHostActivity(context)?.window?.let { win ->
            WindowCompat.getInsetsController(win, this).show(WindowInsetsCompat.Type.ime())
        }
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideIme() {
        pendingShowAfterFocus = false
        onComposingChanged("")
        BridgeClient.setLocalTextFieldFocusedOneway(false)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        findHostActivity(context)?.window?.let { win ->
            WindowCompat.getInsetsController(win, this).hide(WindowInsetsCompat.Type.ime())
        }
        clearFocus()
    }

    private fun dispatchCommittedString(raw: String) {
        val lines = raw.split('\n')
        lines.forEachIndexed { idx, segment ->
            if (segment.isNotEmpty()) {
                if (segment.length <= 64 && segment.all { it.code in 0x20..0x7E }) {
                    val events = keyCharMap?.getEvents(segment.toCharArray())
                    if (events != null && events.isNotEmpty()) {
                        for (ev in events) {
                            onSendKey(ev.action, ev.keyCode, ev.metaState)
                        }
                    } else {
                        onCommitText(segment)
                    }
                } else {
                    onCommitText(segment)
                }
            }
            if (idx < lines.lastIndex) {
                sendTap(KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    private fun sendTap(keyCode: Int, metaState: Int = 0) {
        onSendKey(KeyEvent.ACTION_DOWN, keyCode, metaState)
        onSendKey(KeyEvent.ACTION_UP, keyCode, metaState)
    }

    private tailrec fun findHostActivity(ctx: Context?): Activity? = when (ctx) {
        is Activity -> ctx
        is ContextWrapper -> findHostActivity(ctx.baseContext)
        else -> null
    }
}

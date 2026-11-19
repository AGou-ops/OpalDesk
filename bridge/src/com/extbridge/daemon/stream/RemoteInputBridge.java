package com.extbridge.daemon.stream;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.extbridge.daemon.DisplayGeometryUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Translates ExtCast v1.1 uplink control messages (mouse, multi-touch, keyboard, IME/clipboard
 * text & PNG image, system actions, and drag-and-drop file transfer chunks) into Android system
 * injections and bidirectional clipboard sync.
 */
public final class RemoteInputBridge {
    public static final String DEFAULT_DOWNLOAD_DIR = "/sdcard/Download/ExtBridge";
    public static final String DEFAULT_CLIPBOARD_IMAGE_DIR = "/sdcard/Pictures/ExtBridge";
    public static final int MAX_CONCURRENT_TRANSFERS = 4;
    public static final long MAX_TRANSFER_FILE_BYTES = 4L * 1024L * 1024L * 1024L; // 4 GiB
    private static final long CLIPBOARD_POLL_INTERVAL_MS = 750L;
    private static final int MAX_CACHED_CLIP_IMAGES = 20;
    private static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    public interface HostBridge {
        Context getContext();
        int getTargetDisplayId();
        boolean isMirrorMode();
        int getStreamWidth();
        int getStreamHeight();
        int getTargetDisplayWidth();
        int getTargetDisplayHeight();
        boolean injectInputEvent(InputEvent ev, int targetDisplayId, int mode);
        void focusDisplay(int displayId);
        void updateCursorPosition(float x, float y);
        void performHome();
        void toggleDisplayMode(int arg);
        void toggleBlackout(int arg);
        void rotateDisplay(int arg);
        void setAudioEnabled(boolean enabled);
        void requestDisconnect();
        void sendClipboardPush(long seq, String text);
        void sendClipboardImagePush(long seq, byte[] pngData);
        void sendFileTransferAck(int transferId, int status, String pathOrError);
    }

    private static final class ActiveTransfer {
        final int transferId;
        final File tempFile;
        final File finalFile;
        final long expectedSize;
        final FileOutputStream out;
        long writtenBytes = 0L;

        ActiveTransfer(int transferId, File tempFile, File finalFile, long expectedSize, FileOutputStream out) {
            this.transferId = transferId;
            this.tempFile = tempFile;
            this.finalFile = finalFile;
            this.expectedSize = expectedSize;
            this.out = out;
        }
    }

    private final HostBridge host;
    private final File downloadDir;
    private final File clipboardImageDir;
    private final boolean enableClipboardPolling;
    private final Object inputLock = new Object();
    private final Object clipboardLock = new Object();
    private final Object transferLock = new Object();

    // Pointer / key state
    private int activeMouseButtons = 0;
    private long mouseDownTime = 0L;
    private float lastMouseX = 0f;
    private float lastMouseY = 0f;
    private boolean mouseHovering = false;
    private long touchDownTime = 0L;
    private final Map<Integer, Long> keyDownTimes = new HashMap<>();

    // Clipboard reflection & bidirectional sync state
    private Object iClipboard;
    private boolean clipboardInitAttempted = false;
    private volatile boolean clipboardPolling = false;
    private Thread clipboardPollThread;
    private String lastKnownClipboard = "";
    private String lastKnownClipboardImageHash = "";
    private String lastKnownClipboardImageUri = "";
    private final AtomicLong clipboardSeq = new AtomicLong(1L);

    // File transfers
    private final Map<Integer, ActiveTransfer> transfers = new HashMap<>();

    public RemoteInputBridge(HostBridge host) {
        this(host, new File(DEFAULT_DOWNLOAD_DIR), new File(DEFAULT_CLIPBOARD_IMAGE_DIR), true);
    }

    public RemoteInputBridge(HostBridge host, File downloadDir) {
        this(host, downloadDir, new File(DEFAULT_CLIPBOARD_IMAGE_DIR), true);
    }

    public RemoteInputBridge(HostBridge host, File downloadDir, boolean enableClipboardPolling) {
        this(host, downloadDir, new File(DEFAULT_CLIPBOARD_IMAGE_DIR), enableClipboardPolling);
    }

    public RemoteInputBridge(HostBridge host, File downloadDir, File clipboardImageDir, boolean enableClipboardPolling) {
        this.host = host;
        this.downloadDir = (downloadDir != null) ? downloadDir : new File(DEFAULT_DOWNLOAD_DIR);
        this.clipboardImageDir = (clipboardImageDir != null) ? clipboardImageDir : new File(DEFAULT_CLIPBOARD_IMAGE_DIR);
        this.enableClipboardPolling = enableClipboardPolling;
    }

    public void onSessionStarted() {
        resetInputState();
        if (enableClipboardPolling) {
            startClipboardPoller();
        }
    }

    public void onSessionEnded() {
        stopClipboardPoller();
        releaseStuckInputAndReset();
        abortAllTransfers();
    }

    // ---- Mouse ----

    public void handleMouse(ExtCastProtocol.MouseEventMsg msg) {
        if (msg == null || !Float.isFinite(msg.x) || !Float.isFinite(msg.y)) return;
        synchronized (inputLock) {
            int dispId = host.getTargetDisplayId();
            if (dispId < 0) return;

            float[] mapped = mapCoords(msg.x, msg.y);
            float tx = mapped[0];
            float ty = mapped[1];
            lastMouseX = tx;
            lastMouseY = ty;
            host.updateCursorPosition(tx, ty);

            long now = SystemClock.uptimeMillis();
            int buttons = msg.buttons & (ExtCastProtocol.BUTTON_PRIMARY
                    | ExtCastProtocol.BUTTON_SECONDARY
                    | ExtCastProtocol.BUTTON_TERTIARY
                    | ExtCastProtocol.BUTTON_BACK
                    | ExtCastProtocol.BUTTON_FORWARD);
            int actBtn = msg.actionButton;

            switch (msg.action) {
                case ExtCastProtocol.MOUSE_MOVE_ABS: {
                    if (buttons != 0 || activeMouseButtons != 0) {
                        int effButtons = (buttons != 0) ? buttons : activeMouseButtons;
                        if (mouseDownTime <= 0L) mouseDownTime = now;
                        dispatchMouse(MotionEvent.ACTION_MOVE, mouseDownTime, now, tx, ty, effButtons, 0, 0f, 0f, dispId);
                    } else {
                        if (!mouseHovering) {
                            mouseHovering = true;
                            dispatchMouse(MotionEvent.ACTION_HOVER_ENTER, now, now, tx, ty, 0, 0, 0f, 0f, dispId);
                        }
                        dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now, now, tx, ty, 0, 0, 0f, 0f, dispId);
                    }
                    break;
                }
                case ExtCastProtocol.MOUSE_BUTTON_DOWN: {
                    if (dispId > 0) host.focusDisplay(dispId);
                    int pressBtn = (actBtn != 0) ? actBtn : ExtCastProtocol.BUTTON_PRIMARY;
                    int nextButtons = buttons | pressBtn;
                    boolean firstButton = (activeMouseButtons == 0);
                    activeMouseButtons = nextButtons;
                    if (firstButton) {
                        mouseDownTime = now;
                        if (!mouseHovering) {
                            mouseHovering = true;
                            dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now, now, tx, ty, 0, 0, 0f, 0f, dispId);
                        }
                        dispatchMouse(MotionEvent.ACTION_DOWN, mouseDownTime, now, tx, ty, nextButtons, 0, 0f, 0f, dispId);
                    }
                    dispatchMouse(MotionEvent.ACTION_BUTTON_PRESS, mouseDownTime, now, tx, ty, nextButtons, pressBtn, 0f, 0f, dispId);
                    break;
                }
                case ExtCastProtocol.MOUSE_BUTTON_UP: {
                    int relBtn = (actBtn != 0) ? actBtn : (activeMouseButtons != 0 ? activeMouseButtons : ExtCastProtocol.BUTTON_PRIMARY);
                    int nextButtons = buttons & ~relBtn;
                    long downT = (mouseDownTime > 0L) ? mouseDownTime : now;
                    dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, downT, now, tx, ty, nextButtons, relBtn, 0f, 0f, dispId);
                    activeMouseButtons = nextButtons;
                    if (activeMouseButtons == 0) {
                        dispatchMouse(MotionEvent.ACTION_UP, downT, now, tx, ty, 0, 0, 0f, 0f, dispId);
                        mouseDownTime = 0L;
                        dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now, now, tx, ty, 0, 0, 0f, 0f, dispId);
                    }
                    break;
                }
                case ExtCastProtocol.MOUSE_SCROLL: {
                    float hs = Float.isFinite(msg.hScroll) ? msg.hScroll : 0f;
                    float vs = Float.isFinite(msg.vScroll) ? msg.vScroll : 0f;
                    dispatchMouse(MotionEvent.ACTION_SCROLL, now, now, tx, ty, activeMouseButtons, 0, hs, vs, dispId);
                    break;
                }
                case ExtCastProtocol.MOUSE_HOVER_EXIT: {
                    if (activeMouseButtons != 0) {
                        int prev = activeMouseButtons;
                        long downT = (mouseDownTime > 0L) ? mouseDownTime : now;
                        activeMouseButtons = 0;
                        mouseDownTime = 0L;
                        dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, downT, now, tx, ty, 0, prev, 0f, 0f, dispId);
                        dispatchMouse(MotionEvent.ACTION_UP, downT, now, tx, ty, 0, 0, 0f, 0f, dispId);
                    }
                    if (mouseHovering) {
                        mouseHovering = false;
                        dispatchMouse(MotionEvent.ACTION_HOVER_EXIT, now, now, tx, ty, 0, 0, 0f, 0f, dispId);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    private void dispatchMouse(
            int action, long downTime, long eventTime,
            float x, float y, int buttonState, int actionButton,
            float hScroll, float vScroll, int dispId) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = (buttonState != 0) ? 1.0f : 0.0f;
        coords[0].size = 1.0f;
        if (action == MotionEvent.ACTION_SCROLL) {
            coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        }

        MotionEvent ev = MotionEvent.obtain(
                downTime, eventTime, action, 1, props, coords,
                0, buttonState, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        if (actionButton != 0) {
            try {
                MotionEvent.class.getMethod("setActionButton", int.class).invoke(ev, actionButton);
            } catch (Throwable ignored) {}
        }
        try {
            host.injectInputEvent(ev, dispId, 0);
        } finally {
            ev.recycle();
        }
    }

    // ---- Multi-touch ----

    public void handleTouch(ExtCastProtocol.TouchEventMsg msg) {
        if (msg == null || msg.pointers == null || msg.pointers.length == 0) return;
        synchronized (inputLock) {
            int dispId = host.getTargetDisplayId();
            if (dispId < 0) return;

            int count = Math.min(msg.pointers.length, ExtCastProtocol.MAX_TOUCH_POINTERS);
            int actionIndex = Math.max(0, Math.min(count - 1, msg.actionIndex));
            long now = SystemClock.uptimeMillis();

            int androidAction;
            switch (msg.action) {
                case ExtCastProtocol.TOUCH_DOWN:
                    touchDownTime = now;
                    if (dispId > 0) host.focusDisplay(dispId);
                    androidAction = MotionEvent.ACTION_DOWN;
                    break;
                case ExtCastProtocol.TOUCH_UP:
                    androidAction = MotionEvent.ACTION_UP;
                    break;
                case ExtCastProtocol.TOUCH_MOVE:
                    androidAction = MotionEvent.ACTION_MOVE;
                    break;
                case ExtCastProtocol.TOUCH_CANCEL:
                    androidAction = MotionEvent.ACTION_CANCEL;
                    break;
                case ExtCastProtocol.TOUCH_POINTER_DOWN:
                    androidAction = MotionEvent.ACTION_POINTER_DOWN
                            | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                    break;
                case ExtCastProtocol.TOUCH_POINTER_UP:
                    androidAction = MotionEvent.ACTION_POINTER_UP
                            | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                    break;
                default:
                    return;
            }
            if (touchDownTime <= 0L) {
                touchDownTime = now;
            }

            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
            for (int i = 0; i < count; i++) {
                ExtCastProtocol.TouchPointer p = msg.pointers[i];
                float[] mapped = mapCoords(p.x, p.y);
                props[i] = new MotionEvent.PointerProperties();
                props[i].id = p.pointerId;
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;

                coords[i] = new MotionEvent.PointerCoords();
                coords[i].x = mapped[0];
                coords[i].y = mapped[1];
                float pressure = Float.isFinite(p.pressure) ? Math.max(0f, Math.min(1f, p.pressure)) : 1f;
                if (msg.action == ExtCastProtocol.TOUCH_UP || msg.action == ExtCastProtocol.TOUCH_CANCEL) {
                    pressure = 0f;
                } else if (pressure <= 0f) {
                    pressure = 1f;
                }
                coords[i].pressure = pressure;
                coords[i].size = 1.0f;
            }

            MotionEvent ev = MotionEvent.obtain(
                    touchDownTime, now, androidAction,
                    count, props, coords,
                    0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try {
                host.injectInputEvent(ev, dispId, 0);
            } finally {
                ev.recycle();
            }

            if (msg.action == ExtCastProtocol.TOUCH_UP || msg.action == ExtCastProtocol.TOUCH_CANCEL) {
                touchDownTime = 0L;
            }
        }
    }

    // ---- Keyboard ----

    public void handleKey(ExtCastProtocol.KeyEventMsg msg) {
        if (msg == null || msg.androidKeyCode <= 0) return;
        synchronized (inputLock) {
            int dispId = host.getTargetDisplayId();
            if (dispId < 0) return;
            long now = SystemClock.uptimeMillis();
            boolean isDown = (msg.action == ExtCastProtocol.KEY_DOWN);
            int androidAction = isDown ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;

            long downTime;
            if (isDown) {
                if (dispId > 0 && msg.repeatCount == 0) {
                    host.focusDisplay(dispId);
                }
                Long existing = keyDownTimes.get(msg.androidKeyCode);
                if (msg.repeatCount > 0 && existing != null) {
                    downTime = existing;
                } else {
                    downTime = now;
                    keyDownTimes.put(msg.androidKeyCode, now);
                }
            } else {
                Long existing = keyDownTimes.remove(msg.androidKeyCode);
                downTime = (existing != null) ? existing : now;
            }

            KeyEvent ev = new KeyEvent(
                    downTime, now, androidAction,
                    msg.androidKeyCode,
                    Math.max(0, msg.repeatCount),
                    msg.metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0,
                    KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD);
            host.injectInputEvent(ev, dispId, 0);
        }
    }

    private void injectKeyTap(int keyCode, int metaState) {
        int dispId = host.getTargetDisplayId();
        if (dispId < 0) return;
        if (dispId > 0) host.focusDisplay(dispId);
        long tDown = SystemClock.uptimeMillis();
        KeyEvent kd = new KeyEvent(tDown, tDown, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        boolean ok = host.injectInputEvent(kd, dispId, 2);
        long tUp = Math.max(tDown, SystemClock.uptimeMillis());
        KeyEvent ku = new KeyEvent(tDown, tUp, KeyEvent.ACTION_UP, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        host.injectInputEvent(ku, dispId, 2);
        if (!ok && keyCode == KeyEvent.KEYCODE_PASTE) {
            // Fallback to Ctrl+V when KEYCODE_PASTE is not handled
            long t2 = SystemClock.uptimeMillis();
            int ctrlMeta = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            KeyEvent cd = new KeyEvent(t2, t2, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, ctrlMeta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            KeyEvent cu = new KeyEvent(t2, t2 + 5, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0, ctrlMeta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            host.injectInputEvent(cd, dispId, 2);
            host.injectInputEvent(cu, dispId, 2);
        }
    }

    // ---- Text & PNG Image Clipboard ----

    public static boolean isValidPngHeader(byte[] data) {
        return ExtCastProtocol.isValidPngHeader(data);
    }

    public void handleText(ExtCastProtocol.TextEventMsg msg) {
        if (msg == null) return;
        String text = (msg.text != null) ? msg.text : "";
        synchronized (clipboardLock) {
            lastKnownClipboard = text;
            if (msg.seq > clipboardSeq.get()) {
                clipboardSeq.set(msg.seq);
            }
            setAndroidClipboardText(text);
        }
        if (msg.mode == ExtCastProtocol.TEXT_MODE_COMMIT_PASTE && !text.isEmpty()) {
            SystemClock.sleep(30L);
            injectKeyTap(KeyEvent.KEYCODE_PASTE, 0);
        }
    }

    public void handleImageClipboard(ExtCastProtocol.ImageClipboardEventMsg msg) {
        if (msg == null || msg.pngData == null) return;
        byte[] png = msg.pngData;
        if (png.length < PNG_MAGIC.length || png.length > ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
            System.err.println("EXTBRIDGE_CLIP_IMG_REJECT size=" + png.length);
            return;
        }
        if (!isValidPngHeader(png)) {
            System.err.println("EXTBRIDGE_CLIP_IMG_REJECT invalid PNG header");
            return;
        }
        String hash = sha256Hex(png);
        long safeSeq = (msg.seq > 0) ? msg.seq : clipboardSeq.incrementAndGet();
        Uri imageUri = saveClipboardPngAndResolveUri(safeSeq, png);
        if (imageUri == null) {
            System.err.println("EXTBRIDGE_CLIP_IMG_SAVE_FAILED seq=" + safeSeq);
            return;
        }

        synchronized (clipboardLock) {
            lastKnownClipboardImageHash = hash;
            lastKnownClipboardImageUri = imageUri.toString();
            if (safeSeq > clipboardSeq.get()) {
                clipboardSeq.set(safeSeq);
            }
            // Note: app_process (uid=2000) is not a registered AMS application, so never call
            // ClipData.newUri(ContentResolver, ...) which acquires the 'media' content provider.
            ClipData clip = new ClipData(
                    "ExtBridge Image",
                    new String[]{"image/png"},
                    new ClipData.Item(imageUri));
            setAndroidPrimaryClip(clip);
        }

        if (msg.mode == ExtCastProtocol.TEXT_MODE_COMMIT_PASTE) {
            SystemClock.sleep(40L);
            injectKeyTap(KeyEvent.KEYCODE_PASTE, 0);
        }
    }

    private Uri saveClipboardPngAndResolveUri(long seq, byte[] png) {
        String fileName = "clip_" + Long.toUnsignedString(seq) + ".png";
        // app_process (uid=2000) cannot acquire MediaStore / MediaScannerConnection content providers
        // (MediaScannerConnection.scanFile crashes the process on android.bg with SecurityException).
        // Always write atomically into /sdcard/Pictures/ExtBridge and trigger media scan via `am broadcast`.
        try {
            if (!clipboardImageDir.exists() && !clipboardImageDir.mkdirs()) {
                return null;
            }
            File canonicalBase = clipboardImageDir.getCanonicalFile();
            String safeName = FileTransferPathGuard.sanitizeFileName(fileName);
            if (safeName == null) return null;
            File target = new File(canonicalBase, safeName);
            File tmp = new File(canonicalBase, ".clip_" + Long.toUnsignedString(seq) + "_" + System.nanoTime() + ".tmp");
            FileTransferPathGuard.verifyInsideBaseDir(canonicalBase, target);
            FileTransferPathGuard.verifyInsideBaseDir(canonicalBase, tmp);

            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                fos.write(png);
                fos.flush();
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            }
            if (target.exists()) target.delete();
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return null;
            }
            pruneOldClipboardImages(canonicalBase);
            notifyMediaScanner(target);
            return Uri.fromFile(target);
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_CLIP_IMG_DISK_ERR: " + t.getMessage());
            return null;
        }
    }

    private static void pruneOldClipboardImages(File dir) {
        try {
            File[] files = dir.listFiles((d, name) -> name != null && name.startsWith("clip_") && name.endsWith(".png"));
            if (files == null || files.length <= MAX_CACHED_CLIP_IMAGES) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            int toDelete = files.length - MAX_CACHED_CLIP_IMAGES;
            for (int i = 0; i < toDelete; i++) {
                try { files[i].delete(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void startClipboardPoller() {
        stopClipboardPoller();
        synchronized (clipboardLock) {
            ClipData initialClip = getAndroidPrimaryClip();
            if (initialClip != null && initialClip.getItemCount() > 0) {
                ClipData.Item item = initialClip.getItemAt(0);
                if (item != null) {
                    if (item.getText() != null) {
                        lastKnownClipboard = item.getText().toString();
                    }
                    if (item.getUri() != null) {
                        lastKnownClipboardImageUri = item.getUri().toString();
                    }
                }
            }
        }
        clipboardPolling = true;
        Thread t = new Thread(() -> {
            while (clipboardPolling) {
                SystemClock.sleep(CLIPBOARD_POLL_INTERVAL_MS);
                if (!clipboardPolling) break;
                try {
                    pollAndroidClipboardOnce();
                } catch (Throwable ignored) {}
            }
        }, "ExtClipboardSync");
        t.setDaemon(true);
        clipboardPollThread = t;
        t.start();
    }

    private void pollAndroidClipboardOnce() {
        ClipData clip = getAndroidPrimaryClip();
        if (clip == null || clip.getItemCount() <= 0) return;
        ClipData.Item item = clip.getItemAt(0);
        if (item == null) return;

        // 1. Check image URI clipboard first
        Uri uri = item.getUri();
        if (uri != null && isImageClip(clip, host.getContext(), uri)) {
            String uriStr = uri.toString();
            boolean alreadySeenUri;
            synchronized (clipboardLock) {
                alreadySeenUri = uriStr.equals(lastKnownClipboardImageUri);
            }
            if (!alreadySeenUri) {
                byte[] pngBytes = readClipboardImageAsPng(host.getContext(), uri);
                if (pngBytes != null && pngBytes.length >= PNG_MAGIC.length
                        && pngBytes.length <= ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
                    String hash = sha256Hex(pngBytes);
                    boolean changed = false;
                    synchronized (clipboardLock) {
                        lastKnownClipboardImageUri = uriStr;
                        if (!hash.equals(lastKnownClipboardImageHash)) {
                            lastKnownClipboardImageHash = hash;
                            changed = true;
                        }
                    }
                    if (changed) {
                        long seq = clipboardSeq.incrementAndGet();
                        host.sendClipboardImagePush(seq, pngBytes);
                    }
                    return;
                }
                synchronized (clipboardLock) {
                    lastKnownClipboardImageUri = uriStr;
                }
            }
            return;
        }

        // 2. Plain text clipboard (100% compatible with v1.0)
        CharSequence cs = item.getText();
        if (cs == null) return;
        String current = cs.toString();
        if (current.isEmpty()) return;
        boolean changed = false;
        synchronized (clipboardLock) {
            if (!current.equals(lastKnownClipboard)) {
                lastKnownClipboard = current;
                changed = true;
            }
        }
        if (changed) {
            long seq = clipboardSeq.incrementAndGet();
            host.sendClipboardPush(seq, current);
        }
    }

    private static boolean isImageClip(ClipData clip, Context ctx, Uri uri) {
        ClipDescription desc = clip.getDescription();
        if (desc != null) {
            for (int i = 0; i < desc.getMimeTypeCount(); i++) {
                String mt = desc.getMimeType(i);
                if (mt != null && mt.startsWith("image/")) {
                    return true;
                }
            }
        }
        String path = uri.getPath();
        if (path != null) {
            String lower = path.toLowerCase(java.util.Locale.US);
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
        }
        return false;
    }

    private static byte[] readClipboardImageAsPng(Context ctx, Uri uri) {
        if (uri == null) return null;
        byte[] raw = null;
        // Never acquire ContentResolver providers from app_process (uid=2000); read file:// URIs directly.
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) return null;
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
            byte[] buf = new byte[16384];
            int total = 0;
            int n;
            while ((n = is.read(buf)) >= 0) {
                total += n;
                if (total > ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
                    // Too large to read raw directly; bail safely to avoid OOM
                    return null;
                }
                baos.write(buf, 0, n);
            }
            raw = baos.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
        if (raw.length < PNG_MAGIC.length) return null;
        if (isValidPngHeader(raw)) {
            return raw;
        }
        // Convert non-PNG image (JPEG/WebP) to PNG within 8 MiB cap
        Bitmap bmp = null;
        try {
            bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bmp == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(raw.length * 2, 262144));
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            byte[] png = out.toByteArray();
            if (png.length <= ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
                return png;
            }
            // Scale down once if uncompressed PNG exceeded 8 MiB
            int sw = Math.max(1, bmp.getWidth() / 2);
            int sh = Math.max(1, bmp.getHeight() / 2);
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true);
            try {
                out.reset();
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
                byte[] smallPng = out.toByteArray();
                return (smallPng.length <= ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) ? smallPng : null;
            } finally {
                if (scaled != bmp && !scaled.isRecycled()) scaled.recycle();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (bmp != null && !bmp.isRecycled()) {
                try { bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0x0F, 16));
                sb.append(Character.forDigit(b & 0x0F, 16));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return String.valueOf(Arrays.hashCode(data));
        }
    }

    private void stopClipboardPoller() {
        clipboardPolling = false;
        Thread t = clipboardPollThread;
        clipboardPollThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    private Object getIClipboard() {
        if (iClipboard != null) return iClipboard;
        if (clipboardInitAttempted) return null;
        clipboardInitAttempted = true;
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) smCls.getMethod("getService", String.class).invoke(null, "clipboard");
            if (b != null) {
                iClipboard = Class.forName("android.content.IClipboard$Stub")
                        .getMethod("asInterface", IBinder.class)
                        .invoke(null, b);
            }
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_CLIPBOARD_INIT_ERR: " + t.getMessage());
        }
        return iClipboard;
    }

    private boolean setAndroidClipboardText(String text) {
        return setAndroidPrimaryClip(ClipData.newPlainText("ExtBridge", text));
    }

    private boolean setAndroidPrimaryClip(ClipData clip) {
        Object cb = getIClipboard();
        if (cb == null || clip == null) return false;
        try {
            for (Method m : cb.getClass().getMethods()) {
                if (!"setPrimaryClip".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 5 && pt[0] == ClipData.class && pt[1] == String.class
                        && pt[2] == String.class && pt[3] == int.class && pt[4] == int.class) {
                    m.invoke(cb, clip, "com.android.shell", null, 0, 0);
                    return true;
                }
                if (pt.length == 4 && pt[0] == ClipData.class && pt[1] == String.class
                        && pt[2] == String.class && pt[3] == int.class) {
                    m.invoke(cb, clip, "com.android.shell", null, 0);
                    return true;
                }
                if (pt.length == 3 && pt[0] == ClipData.class && pt[1] == String.class
                        && pt[2] == int.class) {
                    m.invoke(cb, clip, "com.android.shell", 0);
                    return true;
                }
                if (pt.length == 2 && pt[0] == ClipData.class && pt[1] == String.class) {
                    m.invoke(cb, clip, "com.android.shell");
                    return true;
                }
            }
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_CLIPBOARD_SET_ERR: " + t.getMessage());
        }
        return false;
    }

    private ClipData getAndroidPrimaryClip() {
        Object cb = getIClipboard();
        if (cb == null) return null;
        try {
            for (Method m : cb.getClass().getMethods()) {
                if (!"getPrimaryClip".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                Object res;
                if (pt.length == 4 && pt[0] == String.class && pt[1] == String.class
                        && pt[2] == int.class && pt[3] == int.class) {
                    res = m.invoke(cb, "com.android.shell", null, 0, 0);
                } else if (pt.length == 3 && pt[0] == String.class && pt[1] == String.class
                        && pt[2] == int.class) {
                    res = m.invoke(cb, "com.android.shell", null, 0);
                } else if (pt.length == 2 && pt[0] == String.class && pt[1] == int.class) {
                    res = m.invoke(cb, "com.android.shell", 0);
                } else if (pt.length == 1 && pt[0] == String.class) {
                    res = m.invoke(cb, "com.android.shell");
                } else {
                    continue;
                }
                if (res instanceof ClipData) {
                    return (ClipData) res;
                }
                break;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ---- System Actions ----

    public void handleSystemAction(ExtCastProtocol.SystemActionMsg msg) {
        if (msg == null) return;
        switch (msg.action) {
            case ExtCastProtocol.SYS_HOME:
                host.performHome();
                break;
            case ExtCastProtocol.SYS_BACK:
                injectKeyTap(KeyEvent.KEYCODE_BACK, 0);
                break;
            case ExtCastProtocol.SYS_RECENTS:
                injectKeyTap(KeyEvent.KEYCODE_APP_SWITCH, 0);
                break;
            case ExtCastProtocol.SYS_TOGGLE_DISPLAY_MODE:
                host.toggleDisplayMode(msg.arg);
                break;
            case ExtCastProtocol.SYS_TOGGLE_BLACKOUT:
                host.toggleBlackout(msg.arg);
                break;
            case ExtCastProtocol.SYS_ROTATE_CW:
                host.rotateDisplay(msg.arg);
                break;
            case ExtCastProtocol.SYS_SET_AUDIO_ENABLED:
                host.setAudioEnabled(msg.arg != 0);
                break;
            case ExtCastProtocol.SYS_DISCONNECT:
                host.requestDisconnect();
                break;
            default:
                break;
        }
    }

    // ---- File Transfer (Path-traversal hardened) ----

    public void handleFileTransferChunk(ExtCastProtocol.FileTransferChunkMsg msg) {
        if (msg == null) return;
        int tid = msg.transferId;
        int flags = msg.chunkFlags;

        synchronized (transferLock) {
            if ((flags & ExtCastProtocol.FILE_FLAG_ABORT) != 0) {
                ActiveTransfer tr = transfers.remove(tid);
                cleanupTransferFiles(tr);
                host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Transfer aborted");
                return;
            }

            if ((flags & ExtCastProtocol.FILE_FLAG_START) != 0) {
                ActiveTransfer prev = transfers.remove(tid);
                cleanupTransferFiles(prev);

                if (transfers.size() >= MAX_CONCURRENT_TRANSFERS) {
                    host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Too many concurrent file transfers");
                    return;
                }
                if (msg.totalSize < 0 || msg.totalSize > MAX_TRANSFER_FILE_BYTES) {
                    host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Invalid or oversized file");
                    return;
                }
                String safeName = sanitizeFileName(msg.fileName);
                if (safeName == null) {
                    host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Rejected unsafe file name");
                    return;
                }
                try {
                    if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                        throw new IOException("Cannot create destination directory");
                    }
                    File canonicalBase = downloadDir.getCanonicalFile();
                    File finalFile = resolveUniqueSafeFile(canonicalBase, safeName);
                    File tempFile = new File(canonicalBase, ".extbridge_" + Integer.toUnsignedString(tid) + "_" + System.nanoTime() + ".part");
                    verifyInsideBaseDir(canonicalBase, finalFile);
                    verifyInsideBaseDir(canonicalBase, tempFile);

                    FileOutputStream fos = new FileOutputStream(tempFile, false);
                    ActiveTransfer tr = new ActiveTransfer(tid, tempFile, finalFile, msg.totalSize, fos);
                    transfers.put(tid, tr);
                } catch (Throwable t) {
                    host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Failed to open destination: " + t.getMessage());
                    return;
                }
            }

            if ((flags & ExtCastProtocol.FILE_FLAG_DATA) != 0) {
                ActiveTransfer tr = transfers.get(tid);
                if (tr == null) {
                    host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Unknown transferId");
                    return;
                }
                if (msg.data != null && msg.data.length > 0) {
                    long nextSize = tr.writtenBytes + msg.data.length;
                    if (nextSize > MAX_TRANSFER_FILE_BYTES
                            || (tr.expectedSize > 0 && nextSize > tr.expectedSize)) {
                        transfers.remove(tid);
                        cleanupTransferFiles(tr);
                        host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Transfer exceeded declared size");
                        return;
                    }
                    try {
                        tr.out.write(msg.data);
                        tr.writtenBytes = nextSize;
                    } catch (Throwable t) {
                        transfers.remove(tid);
                        cleanupTransferFiles(tr);
                        host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Write error: " + t.getMessage());
                        return;
                    }
                }
            }

            if ((flags & ExtCastProtocol.FILE_FLAG_END) != 0) {
                ActiveTransfer tr = transfers.remove(tid);
                if (tr == null) {
                    host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Unknown transferId on END");
                    return;
                }
                try {
                    tr.out.flush();
                    try { tr.out.getFD().sync(); } catch (Throwable ignored) {}
                    tr.out.close();

                    if (tr.expectedSize > 0 && tr.writtenBytes != tr.expectedSize) {
                        tr.tempFile.delete();
                        host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR,
                                "Size mismatch: expected " + tr.expectedSize + ", got " + tr.writtenBytes);
                        return;
                    }
                    if (!tr.tempFile.renameTo(tr.finalFile)) {
                        tr.tempFile.delete();
                        host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Failed to finalize file");
                        return;
                    }
                    notifyMediaScanner(tr.finalFile);
                    host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_OK, tr.finalFile.getAbsolutePath());
                } catch (Throwable t) {
                    cleanupTransferFiles(tr);
                    host.sendFileTransferAck(tid, ExtCastProtocol.FILE_ACK_ERR, "Finalize error: " + t.getMessage());
                }
            }
        }
    }

    public static String sanitizeFileName(String raw) {
        return FileTransferPathGuard.sanitizeFileName(raw);
    }

    public static File resolveUniqueSafeFile(File canonicalBase, String safeName) throws IOException {
        return FileTransferPathGuard.resolveUniqueSafeFile(canonicalBase, safeName);
    }

    public static void verifyInsideBaseDir(File canonicalBase, File target) throws IOException {
        FileTransferPathGuard.verifyInsideBaseDir(canonicalBase, target);
    }

    private void notifyMediaScanner(File savedFile) {
        if (savedFile == null) return;
        final String uriStr = "file://" + savedFile.getAbsolutePath();
        Thread t = new Thread(() -> {
            try {
                Process p = new ProcessBuilder(
                        "am", "broadcast",
                        "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                        "-d", uriStr)
                        .redirectErrorStream(true)
                        .start();
                p.waitFor();
            } catch (Throwable ignored) {}
        }, "ExtMediaScan");
        t.setDaemon(true);
        t.start();
    }

    private void abortAllTransfers() {
        synchronized (transferLock) {
            Iterator<ActiveTransfer> it = transfers.values().iterator();
            while (it.hasNext()) {
                cleanupTransferFiles(it.next());
                it.remove();
            }
        }
    }

    private static void cleanupTransferFiles(ActiveTransfer tr) {
        if (tr == null) return;
        try { tr.out.close(); } catch (Throwable ignored) {}
        try { if (tr.tempFile != null && tr.tempFile.exists()) tr.tempFile.delete(); } catch (Throwable ignored) {}
    }

    // ---- Helpers ----

    private float[] mapCoords(float streamX, float streamY) {
        return DisplayGeometryUtil.mapStreamPointToDisplay(
                streamX, streamY,
                host.getStreamWidth(), host.getStreamHeight(),
                host.getTargetDisplayWidth(), host.getTargetDisplayHeight(),
                host.isMirrorMode());
    }

    private void resetInputState() {
        synchronized (inputLock) {
            activeMouseButtons = 0;
            mouseDownTime = 0L;
            mouseHovering = false;
            touchDownTime = 0L;
            keyDownTimes.clear();
        }
    }

    private void releaseStuckInputAndReset() {
        synchronized (inputLock) {
            int dispId = host.getTargetDisplayId();
            long now = SystemClock.uptimeMillis();
            if (dispId >= 0) {
                if (activeMouseButtons != 0) {
                    int prev = activeMouseButtons;
                    long downT = (mouseDownTime > 0L) ? mouseDownTime : now;
                    try {
                        dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, downT, now, lastMouseX, lastMouseY, 0, prev, 0f, 0f, dispId);
                        dispatchMouse(MotionEvent.ACTION_UP, downT, now, lastMouseX, lastMouseY, 0, 0, 0f, 0f, dispId);
                    } catch (Throwable ignored) {}
                }
                for (Map.Entry<Integer, Long> e : keyDownTimes.entrySet()) {
                    try {
                        KeyEvent ku = new KeyEvent(e.getValue(), now, KeyEvent.ACTION_UP,
                                e.getKey(), 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                                KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
                        host.injectInputEvent(ku, dispId, 0);
                    } catch (Throwable ignored) {}
                }
            }
            activeMouseButtons = 0;
            mouseDownTime = 0L;
            mouseHovering = false;
            touchDownTime = 0L;
            keyDownTimes.clear();
        }
    }
}

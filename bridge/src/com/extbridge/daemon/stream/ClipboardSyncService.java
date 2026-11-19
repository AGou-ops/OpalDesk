package com.extbridge.daemon.stream;

import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;

import com.extbridge.daemon.platform.AndroidServices;
import com.extbridge.daemon.util.AtomicFiles;
import com.extbridge.daemon.util.CryptoHex;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bidirectional Android <-> Mac clipboard synchronization service supporting plain UTF-8 text
 * and PNG images (up to 8 MiB) via {@code IClipboard} polling and atomic disk image caching.
 * Never acquires ContentResolver media providers from uid=2000 {@code app_process}.
 */
public final class ClipboardSyncService {
    public static final String DEFAULT_CLIPBOARD_IMAGE_DIR = "/sdcard/Pictures/ExtBridge";
    private static final long CLIPBOARD_POLL_INTERVAL_MS = 750L;
    private static final int MAX_CACHED_CLIP_IMAGES = 20;

    public interface ClipboardHost {
        void sendClipboardPush(long seq, String text);
        void sendClipboardImagePush(long seq, byte[] pngData);
        void triggerPasteKey();
    }

    private final ClipboardHost host;
    private final File clipboardImageDir;
    private final boolean enablePolling;
    private final Object clipboardLock = new Object();

    private volatile boolean polling = false;
    private Thread pollThread;
    private String lastKnownText = "";
    private String lastKnownImageHash = "";
    private String lastKnownImageUri = "";
    private final AtomicLong clipboardSeq = new AtomicLong(1L);

    public ClipboardSyncService(ClipboardHost host) {
        this(host, new File(DEFAULT_CLIPBOARD_IMAGE_DIR), true);
    }

    public ClipboardSyncService(ClipboardHost host, File clipboardImageDir, boolean enablePolling) {
        this.host = host;
        this.clipboardImageDir = (clipboardImageDir != null)
                ? clipboardImageDir : new File(DEFAULT_CLIPBOARD_IMAGE_DIR);
        this.enablePolling = enablePolling;
    }

    public void onSessionStarted() {
        if (enablePolling) {
            startPoller();
        }
    }

    public void onSessionEnded() {
        stopPoller();
    }

    public void handleText(ExtCastProtocol.TextEventMsg msg) {
        if (msg == null) return;
        String text = (msg.text != null) ? msg.text : "";
        synchronized (clipboardLock) {
            lastKnownText = text;
            if (msg.seq > clipboardSeq.get()) {
                clipboardSeq.set(msg.seq);
            }
            AndroidServices.setPrimaryClip(ClipData.newPlainText("ExtBridge", text));
        }
        if (msg.mode == ExtCastProtocol.TEXT_MODE_COMMIT_PASTE && !text.isEmpty() && host != null) {
            SystemClock.sleep(30L);
            host.triggerPasteKey();
        }
    }

    public void handleImageClipboard(ExtCastProtocol.ImageClipboardEventMsg msg) {
        if (msg == null || msg.pngData == null) return;
        byte[] png = msg.pngData;
        if (png.length < ExtCastProtocol.PNG_MAGIC.length
                || png.length > ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
            System.err.println("EXTBRIDGE_CLIP_IMG_REJECT size=" + png.length);
            return;
        }
        if (!ExtCastProtocol.isValidPngHeader(png)) {
            System.err.println("EXTBRIDGE_CLIP_IMG_REJECT invalid PNG header");
            return;
        }
        String hash = CryptoHex.sha256Hex(png);
        long safeSeq = (msg.seq > 0) ? msg.seq : clipboardSeq.incrementAndGet();
        Uri imageUri = saveClipboardPngAndResolveUri(safeSeq, png);
        if (imageUri == null) {
            System.err.println("EXTBRIDGE_CLIP_IMG_SAVE_FAILED seq=" + safeSeq);
            return;
        }

        synchronized (clipboardLock) {
            lastKnownImageHash = hash;
            lastKnownImageUri = imageUri.toString();
            if (safeSeq > clipboardSeq.get()) {
                clipboardSeq.set(safeSeq);
            }
            // app_process (uid=2000) is not a registered AMS application; never call
            // ClipData.newUri(ContentResolver, ...) which acquires the 'media' content provider.
            ClipData clip = new ClipData(
                    "ExtBridge Image",
                    new String[]{"image/png"},
                    new ClipData.Item(imageUri));
            AndroidServices.setPrimaryClip(clip);
        }

        if (msg.mode == ExtCastProtocol.TEXT_MODE_COMMIT_PASTE && host != null) {
            SystemClock.sleep(40L);
            host.triggerPasteKey();
        }
    }

    private Uri saveClipboardPngAndResolveUri(long seq, byte[] png) {
        String fileName = "clip_" + Long.toUnsignedString(seq) + ".png";
        // Always write atomically into /sdcard/Pictures/ExtBridge and trigger timeout-guarded
        // media scan via `am broadcast` instead of MediaStore / MediaScannerConnection.
        try {
            if (!clipboardImageDir.exists() && !clipboardImageDir.mkdirs()) {
                System.err.println("EXTBRIDGE_CLIP_IMG_MKDIR_ERR dir=" + clipboardImageDir.getAbsolutePath());
                return null;
            }
            File canonicalBase = clipboardImageDir.getCanonicalFile();
            String safeName = FileTransferPathGuard.sanitizeFileName(fileName);
            if (safeName == null) return null;
            File target = new File(canonicalBase, safeName);
            FileTransferPathGuard.verifyInsideBaseDir(canonicalBase, target);

            AtomicFiles.writeAtomic(target, png, AtomicFiles.MODE_DEFAULT);
            pruneOldClipboardImages(canonicalBase);
            FileTransferReceiver.triggerMediaScanAsync(target);
            return Uri.fromFile(target);
        } catch (IOException | RuntimeException t) {
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
                if (!files[i].delete() && files[i].exists()) {
                    System.err.println("EXTBRIDGE_CLIP_IMG_PRUNE_WARN failed to delete " + files[i].getName());
                }
            }
        } catch (SecurityException e) {
            System.err.println("EXTBRIDGE_CLIP_IMG_PRUNE_ERR: " + e.getMessage());
        }
    }

    private void startPoller() {
        stopPoller();
        synchronized (clipboardLock) {
            ClipData initialClip = AndroidServices.getPrimaryClip();
            if (initialClip != null && initialClip.getItemCount() > 0) {
                ClipData.Item item = initialClip.getItemAt(0);
                if (item != null) {
                    if (item.getText() != null) {
                        lastKnownText = item.getText().toString();
                    }
                    if (item.getUri() != null) {
                        lastKnownImageUri = item.getUri().toString();
                    }
                }
            }
        }
        polling = true;
        Thread t = new Thread(() -> {
            while (polling) {
                SystemClock.sleep(CLIPBOARD_POLL_INTERVAL_MS);
                if (!polling) break;
                try {
                    pollAndroidClipboardOnce();
                } catch (Throwable err) {
                    System.err.println("EXTBRIDGE_CLIPBOARD_POLL_ERR: " + err.getMessage());
                }
            }
        }, "ExtClipboardSync");
        t.setDaemon(true);
        pollThread = t;
        t.start();
    }

    private void stopPoller() {
        polling = false;
        Thread t = pollThread;
        pollThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    private void pollAndroidClipboardOnce() {
        ClipData clip = AndroidServices.getPrimaryClip();
        if (clip == null || clip.getItemCount() <= 0) return;
        ClipData.Item item = clip.getItemAt(0);
        if (item == null) return;

        // 1. Check image URI clipboard first
        Uri uri = item.getUri();
        if (uri != null && isImageClip(clip, uri)) {
            String uriStr = uri.toString();
            boolean alreadySeenUri;
            synchronized (clipboardLock) {
                alreadySeenUri = uriStr.equals(lastKnownImageUri);
            }
            if (!alreadySeenUri) {
                byte[] pngBytes = readClipboardImageAsPng(uri);
                if (pngBytes != null && pngBytes.length >= ExtCastProtocol.PNG_MAGIC.length
                        && pngBytes.length <= ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
                    String hash = CryptoHex.sha256Hex(pngBytes);
                    boolean changed = false;
                    synchronized (clipboardLock) {
                        lastKnownImageUri = uriStr;
                        if (!hash.equals(lastKnownImageHash)) {
                            lastKnownImageHash = hash;
                            changed = true;
                        }
                    }
                    if (changed && host != null) {
                        long seq = clipboardSeq.incrementAndGet();
                        host.sendClipboardImagePush(seq, pngBytes);
                    }
                    return;
                }
                synchronized (clipboardLock) {
                    lastKnownImageUri = uriStr;
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
            if (!current.equals(lastKnownText)) {
                lastKnownText = current;
                changed = true;
            }
        }
        if (changed && host != null) {
            long seq = clipboardSeq.incrementAndGet();
            host.sendClipboardPush(seq, current);
        }
    }

    private static boolean isImageClip(ClipData clip, Uri uri) {
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
            String lower = path.toLowerCase(Locale.US);
            return lower.endsWith(".png") || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg") || lower.endsWith(".webp");
        }
        return false;
    }

    private static byte[] readClipboardImageAsPng(Uri uri) {
        if (uri == null) return null;
        // Never acquire ContentResolver providers from app_process (uid=2000); read file:// URIs directly.
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) return null;

        byte[] raw;
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
            byte[] buf = new byte[16384];
            int total = 0;
            int n;
            while ((n = is.read(buf)) >= 0) {
                total += n;
                if (total > ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
                    return null;
                }
                baos.write(buf, 0, n);
            }
            raw = baos.toByteArray();
        } catch (IOException e) {
            System.err.println("EXTBRIDGE_CLIP_IMG_READ_ERR path=" + path + ": " + e.getMessage());
            return null;
        }

        if (raw.length < ExtCastProtocol.PNG_MAGIC.length) return null;
        if (ExtCastProtocol.isValidPngHeader(raw)) {
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
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_CLIP_IMG_CONVERT_ERR path=" + path + ": " + t.getMessage());
            return null;
        } finally {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
    }
}

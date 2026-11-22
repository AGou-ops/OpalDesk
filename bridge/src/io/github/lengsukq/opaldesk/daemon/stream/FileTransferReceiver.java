package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.SyncFailedException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Path-traversal-hardened chunked file transfer receiver for OpalCast v1.1 (0x28 / 0x13).
 * Writes chunks into sibling {@code .part} files, fsyncs and renames on completion,
 * and triggers timeout-bounded {@code am broadcast} media scanning without ever acquiring
 * MediaStore / MediaScannerConnection ContentProviders from uid=2000 {@code app_process}.
 */
public final class FileTransferReceiver {
    public static final String DEFAULT_DOWNLOAD_DIR = "/sdcard/Download/OpalDesk";
    public static final int MAX_CONCURRENT_TRANSFERS = 4;
    public static final long MAX_TRANSFER_FILE_BYTES = 4L * 1024L * 1024L * 1024L; // 4 GiB
    private static final long MEDIA_SCAN_TIMEOUT_MS = 5000L;

    private static final ExecutorService MEDIA_SCAN_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExtMediaScan");
        t.setDaemon(true);
        return t;
    });

    public interface AckListener {
        void sendFileTransferAck(int transferId, int status, String pathOrError);
    }

    private static final class ActiveTransfer {
        final File tempFile;
        final File finalFile;
        final long expectedSize;
        final FileOutputStream out;
        long writtenBytes = 0L;

        ActiveTransfer(File tempFile, File finalFile, long expectedSize, FileOutputStream out) {
            this.tempFile = tempFile;
            this.finalFile = finalFile;
            this.expectedSize = expectedSize;
            this.out = out;
        }
    }

    private final AckListener ackListener;
    private final File downloadDir;
    private final boolean triggerMediaScan;
    private final Object transferLock = new Object();
    private final Map<Integer, ActiveTransfer> transfers = new HashMap<>();

    public FileTransferReceiver(AckListener ackListener) {
        this(ackListener, new File(DEFAULT_DOWNLOAD_DIR), true);
    }

    public FileTransferReceiver(AckListener ackListener, File downloadDir) {
        this(ackListener, downloadDir, true);
    }

    public FileTransferReceiver(AckListener ackListener, File downloadDir, boolean triggerMediaScan) {
        this.ackListener = ackListener;
        this.downloadDir = (downloadDir != null) ? downloadDir : new File(DEFAULT_DOWNLOAD_DIR);
        this.triggerMediaScan = triggerMediaScan;
    }

    public void onSessionEnded() {
        abortAllTransfers();
    }

    public void handleFileTransferChunk(OpalCastProtocol.FileTransferChunkMsg msg) {
        if (msg == null) return;
        int tid = msg.transferId;
        int flags = msg.chunkFlags;

        synchronized (transferLock) {
            if ((flags & OpalCastProtocol.FILE_FLAG_ABORT) != 0) {
                ActiveTransfer tr = transfers.remove(tid);
                cleanupTransferFiles(tr);
                sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Transfer aborted");
                return;
            }

            if ((flags & OpalCastProtocol.FILE_FLAG_START) != 0) {
                if (!handleStartLocked(tid, msg)) {
                    return;
                }
            }

            if ((flags & OpalCastProtocol.FILE_FLAG_DATA) != 0) {
                if (!handleDataLocked(tid, msg)) {
                    return;
                }
            }

            if ((flags & OpalCastProtocol.FILE_FLAG_END) != 0) {
                handleEndLocked(tid);
            }
        }
    }

    public void abortAllTransfers() {
        synchronized (transferLock) {
            Iterator<ActiveTransfer> it = transfers.values().iterator();
            while (it.hasNext()) {
                cleanupTransferFiles(it.next());
                it.remove();
            }
        }
    }

    /**
     * Triggers an Android media scan asynchronously via timeout-bounded {@code am broadcast}
     * on a single bounded daemon worker (never via MediaScannerConnection / ContentResolver).
     */
    public static void triggerMediaScanAsync(File savedFile) {
        if (savedFile == null) return;
        final String uriStr = "file://" + savedFile.getAbsolutePath();
        MEDIA_SCAN_EXEC.execute(() -> {
            ShellCommand.Result res = ShellCommand.runWithTimeout(
                    new String[]{
                            "am", "broadcast",
                            "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                            "-d", uriStr
                    },
                    MEDIA_SCAN_TIMEOUT_MS);
            if (!res.isSuccess()) {
                System.err.println("OPALDESK_MEDIA_SCAN_WARN uri=" + uriStr
                        + " exit=" + res.exitCode + " timedOut=" + res.timedOut);
            }
        });
    }

    private boolean handleStartLocked(int tid, OpalCastProtocol.FileTransferChunkMsg msg) {
        ActiveTransfer prev = transfers.remove(tid);
        cleanupTransferFiles(prev);

        if (transfers.size() >= MAX_CONCURRENT_TRANSFERS) {
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Too many concurrent file transfers");
            return false;
        }
        if (msg.totalSize < 0 || msg.totalSize > MAX_TRANSFER_FILE_BYTES) {
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Invalid or oversized file");
            return false;
        }
        String safeName = FileTransferPathGuard.sanitizeFileName(msg.fileName);
        if (safeName == null) {
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Rejected unsafe file name");
            return false;
        }
        try {
            if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                throw new IOException("Cannot create destination directory");
            }
            File canonicalBase = downloadDir.getCanonicalFile();
            File finalFile = FileTransferPathGuard.resolveUniqueSafeFile(canonicalBase, safeName);
            File tempFile = new File(canonicalBase,
                    ".opaldesk_" + Integer.toUnsignedString(tid) + "_" + System.nanoTime() + ".part");
            FileTransferPathGuard.verifyInsideBaseDir(canonicalBase, finalFile);
            FileTransferPathGuard.verifyInsideBaseDir(canonicalBase, tempFile);

            FileOutputStream fos = new FileOutputStream(tempFile, false);
            transfers.put(tid, new ActiveTransfer(tempFile, finalFile, msg.totalSize, fos));
            return true;
        } catch (IOException | RuntimeException t) {
            System.err.println("OPALDESK_XFER_START_ERR tid=" + tid + ": " + t.getMessage());
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Failed to open destination: " + t.getMessage());
            return false;
        }
    }

    private boolean handleDataLocked(int tid, OpalCastProtocol.FileTransferChunkMsg msg) {
        ActiveTransfer tr = transfers.get(tid);
        if (tr == null) {
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Unknown transferId");
            return false;
        }
        if (msg.data != null && msg.data.length > 0) {
            long nextSize = tr.writtenBytes + msg.data.length;
            if (nextSize > MAX_TRANSFER_FILE_BYTES
                    || (tr.expectedSize > 0 && nextSize > tr.expectedSize)) {
                transfers.remove(tid);
                cleanupTransferFiles(tr);
                sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Transfer exceeded declared size");
                return false;
            }
            try {
                tr.out.write(msg.data);
                tr.writtenBytes = nextSize;
            } catch (IOException e) {
                transfers.remove(tid);
                cleanupTransferFiles(tr);
                System.err.println("OPALDESK_XFER_WRITE_ERR tid=" + tid + ": " + e.getMessage());
                sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Write error: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    private void handleEndLocked(int tid) {
        ActiveTransfer tr = transfers.remove(tid);
        if (tr == null) {
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Unknown transferId on END");
            return;
        }
        try {
            tr.out.flush();
            try {
                tr.out.getFD().sync();
            } catch (SyncFailedException sfe) {
                System.err.println("OPALDESK_XFER_FSYNC_WARN file=" + tr.finalFile.getName()
                        + ": " + sfe.getMessage());
            }
            tr.out.close();

            if (tr.expectedSize > 0 && tr.writtenBytes != tr.expectedSize) {
                deleteQuietly(tr.tempFile);
                sendAck(tid, OpalCastProtocol.FILE_ACK_ERR,
                        "Size mismatch: expected " + tr.expectedSize + ", got " + tr.writtenBytes);
                return;
            }
            if (!tr.tempFile.renameTo(tr.finalFile)) {
                deleteQuietly(tr.tempFile);
                sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Failed to finalize file");
                return;
            }
            if (triggerMediaScan) {
                triggerMediaScanAsync(tr.finalFile);
            }
            sendAck(tid, OpalCastProtocol.FILE_ACK_OK, tr.finalFile.getAbsolutePath());
        } catch (IOException | RuntimeException t) {
            cleanupTransferFiles(tr);
            System.err.println("OPALDESK_XFER_FINALIZE_ERR tid=" + tid + ": " + t.getMessage());
            sendAck(tid, OpalCastProtocol.FILE_ACK_ERR, "Finalize error: " + t.getMessage());
        }
    }

    private void sendAck(int transferId, int status, String pathOrError) {
        if (ackListener != null) {
            ackListener.sendFileTransferAck(transferId, status, pathOrError);
        }
    }

    private static void cleanupTransferFiles(ActiveTransfer tr) {
        if (tr == null) return;
        try {
            tr.out.close();
        } catch (IOException e) {
            System.err.println("OPALDESK_XFER_CLOSE_WARN: " + e.getMessage());
        }
        deleteQuietly(tr.tempFile);
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            System.err.println("OPALDESK_XFER_DELETE_WARN failed to delete " + f.getAbsolutePath());
        }
    }
}

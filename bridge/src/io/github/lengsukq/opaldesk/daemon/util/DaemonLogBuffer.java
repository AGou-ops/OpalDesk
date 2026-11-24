package io.github.lengsukq.opaldesk.daemon.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, thread-safe in-memory ring buffer capturing daemon stdout/stderr lines so the Helper
 * client can pull live daemon diagnostics over cert-pinned Binder IPC without direct filesystem
 * access to {@code /data/local/tmp/opaldesk.log}.
 */
public final class DaemonLogBuffer {
    public static final int DEFAULT_MAX_LINES = 500;
    public static final int MAX_LINE_CHARS = 1024;
    public static final int MAX_SNAPSHOT_CHARS = 96 * 1024;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> RING = new ArrayDeque<>(DEFAULT_MAX_LINES);
    private static final AtomicBoolean TEE_INSTALLED = new AtomicBoolean(false);
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DaemonLogBuffer() {}

    /** Installs stdout/stderr tees once so all daemon prints are both persisted and buffered. */
    public static void installSystemStreamTee() {
        if (!TEE_INSTALLED.compareAndSet(false, true)) return;
        System.setOut(createTeePrintStream(System.out, false));
        System.setErr(createTeePrintStream(System.err, true));
    }

    /** Creates a UTF-8 {@link PrintStream} that mirrors completed lines into the ring buffer. */
    public static PrintStream createTeePrintStream(PrintStream delegate, boolean isError) {
        try {
            // Use String charset overload (API 1+) instead of Charset overload (API 33+ / Android 13+)
            return new PrintStream(new TeeOutputStream(delegate, isError), true, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new PrintStream(new TeeOutputStream(delegate, isError), true);
        }
    }

    /** Records a single log line with wall-clock timestamp and severity marker. */
    public static void recordLine(boolean isError, String rawLine) {
        if (rawLine == null) return;
        String clean = stripTrailingCr(rawLine);
        if (clean.isEmpty()) return;
        if (clean.length() > MAX_LINE_CHARS) {
            clean = clean.substring(0, MAX_LINE_CHARS) + "...";
        }
        String level = isError ? "E" : "I";
        synchronized (LOCK) {
            String ts = TIME_FMT.format(new Date());
            String entry = ts + " " + level + "/Daemon: " + clean;
            while (RING.size() >= DEFAULT_MAX_LINES) {
                RING.pollFirst();
            }
            RING.addLast(entry);
        }
    }

    /** Returns up to {@code requestedLines} most recent lines joined by newlines. */
    public static String snapshotTail(int requestedLines) {
        int limit = (requestedLines <= 0) ? DEFAULT_MAX_LINES : Math.min(requestedLines, DEFAULT_MAX_LINES);
        String[] lines;
        synchronized (LOCK) {
            int total = RING.size();
            if (total == 0) return "";
            int skip = Math.max(0, total - limit);
            lines = new String[total - skip];
            int idx = 0;
            int pos = 0;
            for (String s : RING) {
                if (idx++ >= skip) {
                    lines[pos++] = s;
                }
            }
        }
        StringBuilder sb = new StringBuilder(Math.min(MAX_SNAPSHOT_CHARS, lines.length * 80));
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (ln == null) continue;
            if (sb.length() + ln.length() + 1 > MAX_SNAPSHOT_CHARS) {
                // Keep the newest lines when hitting IPC parcel safety cap
                int trimTarget = Math.min(sb.length(), ln.length() + 256);
                int cut = sb.indexOf("\n", trimTarget);
                if (cut > 0 && cut + 1 < sb.length()) {
                    sb.delete(0, cut + 1);
                } else {
                    sb.setLength(0);
                }
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(ln);
        }
        return sb.toString();
    }

    public static int size() {
        synchronized (LOCK) {
            return RING.size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            RING.clear();
        }
    }

    private static String stripTrailingCr(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\r' || s.charAt(end - 1) == '\n')) {
            end--;
        }
        return (end == s.length()) ? s : s.substring(0, end);
    }

    private static final class TeeOutputStream extends OutputStream {
        private final PrintStream delegate;
        private final boolean isError;
        private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);

        TeeOutputStream(PrintStream delegate, boolean isError) {
            this.delegate = delegate;
            this.isError = isError;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (delegate != null) delegate.write(b);
            if (b == '\n') {
                flushBufferedLine();
            } else if (lineBuf.size() < MAX_LINE_CHARS * 2) {
                lineBuf.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (b == null || len <= 0) return;
            if (delegate != null) delegate.write(b, off, len);
            int start = off;
            int end = off + len;
            for (int i = off; i < end; i++) {
                if (b[i] == '\n') {
                    appendChunk(b, start, i - start);
                    flushBufferedLine();
                    start = i + 1;
                }
            }
            if (start < end) {
                appendChunk(b, start, end - start);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (delegate != null) delegate.flush();
        }

        private void appendChunk(byte[] b, int off, int len) {
            if (len <= 0) return;
            int remaining = (MAX_LINE_CHARS * 2) - lineBuf.size();
            if (remaining <= 0) return;
            lineBuf.write(b, off, Math.min(len, remaining));
        }

        private void flushBufferedLine() {
            if (lineBuf.size() == 0) return;
            String line = new String(lineBuf.toByteArray(), StandardCharsets.UTF_8);
            lineBuf.reset();
            recordLine(isError, line);
        }
    }
}

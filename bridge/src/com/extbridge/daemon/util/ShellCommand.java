package com.extbridge.daemon.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Timeout-bounded subprocess runner that drains combined stdout/stderr cleanly,
 * kills runaway children on timeout, and logs failures honestly to {@code System.err}.
 * Pure Java 11 with zero Android runtime dependencies.
 */
public final class ShellCommand {
    public static final long DEFAULT_TIMEOUT_MS = 3000L;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 262_144;
    private static final long READER_JOIN_MS = 250L;

    public static final class Result {
        public final int exitCode;
        public final String output;
        public final boolean timedOut;

        public Result(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = (output != null) ? output : "";
            this.timedOut = timedOut;
        }

        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }
    }

    private ShellCommand() {}

    public static Result runWithTimeout(String[] cmd, long timeoutMs) {
        if (cmd == null || cmd.length == 0) {
            return new Result(-1, "", false);
        }
        long effectiveTimeoutMs = Math.max(50L, timeoutMs);
        final StringBuilder sb = new StringBuilder();
        Process proc = null;
        InputStream in = null;
        Thread reader = null;
        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try {
                proc.getOutputStream().close();
            } catch (IOException ignored) {
                // Child may have already closed stdin.
            }
            in = proc.getInputStream();
            final InputStream pipe = in;
            reader = new Thread(() -> drainPipe(pipe, sb), "ExtCmdReader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = proc.waitFor(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                System.err.println("EXTBRIDGE_CMD_TIMEOUT ms=" + effectiveTimeoutMs
                        + " cmd=" + summarizeCmd(cmd));
                destroyQuietly(proc);
                closeQuietly(in);
                joinReader(reader);
                return new Result(-1, snapshotOutput(sb), true);
            }

            joinReader(reader);
            return new Result(proc.exitValue(), snapshotOutput(sb), false);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            System.err.println("EXTBRIDGE_CMD_INTERRUPTED cmd=" + summarizeCmd(cmd));
            destroyQuietly(proc);
            closeQuietly(in);
            joinReader(reader);
            return new Result(-1, snapshotOutput(sb), true);
        } catch (IOException ioe) {
            System.err.println("EXTBRIDGE_CMD_ERR cmd=" + summarizeCmd(cmd) + ": " + ioe.getMessage());
            return new Result(-1, snapshotOutput(sb), false);
        } finally {
            if (proc != null && proc.isAlive()) {
                destroyQuietly(proc);
            }
        }
    }

    public static Result runWithTimeout(long timeoutMs, String... cmd) {
        return runWithTimeout(cmd, timeoutMs);
    }

    public static String runOutput(String... cmd) {
        return runWithTimeout(cmd, DEFAULT_TIMEOUT_MS).output;
    }

    public static String runOutput(long timeoutMs, String... cmd) {
        return runWithTimeout(cmd, timeoutMs).output;
    }

    private static void drainPipe(InputStream in, StringBuilder sb) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                synchronized (sb) {
                    if (sb.length() < DEFAULT_MAX_OUTPUT_CHARS) {
                        sb.append(line).append('\n');
                    }
                }
            }
        } catch (IOException ignored) {
            // Expected when process is destroyed on timeout and pipe closes.
        }
    }

    private static String snapshotOutput(StringBuilder sb) {
        synchronized (sb) {
            return sb.toString();
        }
    }

    private static void joinReader(Thread reader) {
        if (reader == null) return;
        try {
            reader.join(READER_JOIN_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void destroyQuietly(Process proc) {
        if (proc == null) return;
        try {
            proc.destroyForcibly();
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_CMD_KILL_ERR: " + e.getMessage());
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // Stream teardown during timeout cleanup.
        }
    }

    private static String summarizeCmd(String[] cmd) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(cmd.length, 4);
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append(' ');
            sb.append(cmd[i]);
        }
        if (cmd.length > max) sb.append(" ...");
        return sb.toString();
    }
}

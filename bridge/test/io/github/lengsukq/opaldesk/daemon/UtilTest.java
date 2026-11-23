package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;
import io.github.lengsukq.opaldesk.daemon.util.CryptoHex;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UtilTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testCryptoHexVectors() {
        assertEquals("", CryptoHex.toHex(null));
        assertEquals("", CryptoHex.toHex(new byte[0]));
        assertEquals("000f10ff", CryptoHex.toHex(new byte[]{0x00, 0x0F, 0x10, (byte) 0xFF}));

        // NIST FIPS 180-4 SHA-256 test vectors
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                CryptoHex.sha256Hex(""));
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CryptoHex.sha256Hex("abc"));
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CryptoHex.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testAtomicFilesWriteOverwriteAndPermissions() throws Exception {
        File dir = tmpDir.newFolder("atomic");
        File target = new File(new File(dir, "nested"), "secret.conf");

        AtomicFiles.writeAtomicUtf8(target, "line1\n", AtomicFiles.MODE_OWNER_RW);
        assertTrue(target.isFile());
        assertEquals("line1\n", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target.toPath());
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));

        // Overwrite existing atomically and leave zero temp files behind
        AtomicFiles.writeAtomicUtf8(target, "line2-updated\n", AtomicFiles.MODE_OWNER_RW);
        assertEquals("line2-updated\n", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        File[] remaining = target.getParentFile().listFiles();
        assertEquals(1, remaining != null ? remaining.length : 0);
    }

    @Test
    public void testAtomicFilesPropagatesIoFailure() throws Exception {
        File dir = tmpDir.newFolder("atomic_err");
        // Attempting to write to an existing non-empty directory must fail honestly
        File nonEmptyDir = new File(dir, "occupied");
        assertTrue(nonEmptyDir.mkdirs());
        assertTrue(new File(nonEmptyDir, "child.txt").createNewFile());

        try {
            AtomicFiles.writeAtomicUtf8(nonEmptyDir, "data", AtomicFiles.MODE_OWNER_RW);
            fail("Expected IOException when target is a non-empty directory");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
        // Temp file must be cleaned up on failure
        File[] files = dir.listFiles((d, name) -> name.endsWith(".tmp"));
        assertEquals(0, files != null ? files.length : 0);
    }

    @Test
    public void testShellCommandSuccessNonZeroAndTimeout() {
        ShellCommand.Result ok = ShellCommand.runWithTimeout(new String[]{"sh", "-c", "echo hello-opaldesk"}, 2000L);
        assertTrue(ok.isSuccess());
        assertEquals(0, ok.exitCode);
        assertFalse(ok.timedOut);
        assertEquals("hello-opaldesk", ok.output.trim());

        ShellCommand.Result failRes = ShellCommand.runWithTimeout(new String[]{"sh", "-c", "exit 7"}, 2000L);
        assertFalse(failRes.isSuccess());
        assertEquals(7, failRes.exitCode);
        assertFalse(failRes.timedOut);

        long startMs = System.currentTimeMillis();
        ShellCommand.Result timedOut = ShellCommand.runWithTimeout(new String[]{"sh", "-c", "sleep 5"}, 150L);
        long elapsedMs = System.currentTimeMillis() - startMs;
        assertFalse(timedOut.isSuccess());
        assertTrue("Command should report timedOut=true", timedOut.timedOut);
        assertTrue("Timeout should kill child promptly (was " + elapsedMs + "ms)", elapsedMs < 2000L);
    }

    @Test
    public void testPortraitPhoneWorkAreaAndInitialWindowFrame() {
        int canvasW = 1080;
        int canvasH = 2400;
        int dpi = 440;
        DisplayGeometryUtil.IntRect work = DisplayGeometryUtil.computeWorkArea(canvasW, canvasH, dpi);
        assertTrue(work.left >= 0 && work.right <= canvasW);
        assertTrue(work.bottom > work.top);
        assertTrue(work.height() > work.width());

        DisplayGeometryUtil.IntRect frame = DisplayGeometryUtil.computeInitialWindowFrame(
                WindowProfile.DESKTOP, 0, canvasW, canvasH, dpi);
        int fw = frame.width();
        int fh = frame.height();
        assertEquals(0, fw % 2);
        assertEquals(0, fh % 2);
        assertTrue("Portrait initial frame must fit inside phone width", frame.left >= 0 && frame.right <= canvasW);
        assertTrue("Portrait initial frame must fit inside work height", frame.top >= work.top && frame.bottom <= work.bottom);
    }

    @Test
    public void testStandaloneAppDpiAdaptsToHostDeviceDpi() {
        // Phone host: Xiaomi 14 Pro 1440x3200 @ 560dpi -> logical short edge = 411.4dp
        // Floating portrait stream 744x1280 should keep ~411dp width (~289dpi)
        int phonePortraitDpi = DisplayGeometryUtil.computeStandaloneAppDpi(744, 1280, 1440, 3200, 560, 100);
        assertTrue("Expected ~289dpi for 744px wide on 411dp phone, got " + phonePortraitDpi,
                phonePortraitDpi >= 280 && phonePortraitDpi <= 298);

        // Resizing narrower to 560x1280 on same phone host scales DPI proportionally down (~218dpi)
        int phoneNarrowDpi = DisplayGeometryUtil.computeStandaloneAppDpi(560, 1280, 1440, 3200, 560, 100);
        assertTrue("Narrower stream must reduce DPI proportionally, got " + phoneNarrowDpi,
                phoneNarrowDpi >= 210 && phoneNarrowDpi <= 226);

        // Tablet host: Xiaomi Pad 8 Pro 3040x1904 @ 440dpi -> logical short edge = 692.4dp
        // Landscape floating window 1280x792 should produce ~183dpi (much lower than phone landscape!)
        int tabletLandDpi = DisplayGeometryUtil.computeStandaloneAppDpi(1280, 792, 3040, 1904, 440, 100);
        int phoneLandDpi = DisplayGeometryUtil.computeStandaloneAppDpi(1280, 792, 1440, 3200, 560, 100);
        assertTrue("Tablet host landscape DPI (" + tabletLandDpi + ") must be lower than phone host (" + phoneLandDpi + ")",
                tabletLandDpi < phoneLandDpi - 80);
    }

    @Test
    public void testDaemonConfigPersistsAppLaunchCounts() throws Exception {
        File dir = tmpDir.newFolder("cfg_counts");
        File conf = new File(dir, "main.conf");
        File prof = new File(dir, "profiles.conf");
        DaemonConfig cfg1 = new DaemonConfig(conf, prof);
        assertEquals(0, cfg1.getAppLaunchCount("com.tencent.mm"));
        assertEquals(1, cfg1.recordAppLaunch("com.tencent.mm"));
        assertEquals(2, cfg1.recordAppLaunch("com.tencent.mm"));
        assertEquals(1, cfg1.recordAppLaunch("tv.danmaku.bili"));

        DaemonConfig cfg2 = new DaemonConfig(conf, prof);
        cfg2.loadAll();
        assertEquals(2, cfg2.getAppLaunchCount("com.tencent.mm"));
        assertEquals(1, cfg2.getAppLaunchCount("tv.danmaku.bili"));
    }
}

package com.extbridge.helper.adb

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Single source of truth for extbridge.jar asset SHA-256 verification/staging and
 * fail-closed daemon bootstrap shell scripts (PC ADB, Wireless ADB, and Root-to-Shell).
 */
object DaemonBootstrapScripts {
    private const val TAG = "ExtBootstrapScript"
    private const val ASSET_JAR_NAME = "extbridge.jar"
    private const val HELPER_PKG = "com.extbridge.helper"
    private const val DAEMON_CLASS = "com.extbridge.daemon.ExtBridge"
    private const val DST_JAR = "/data/local/tmp/extbridge.jar"
    private const val DST_LOG = "/data/local/tmp/extbridge.log"
    private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")

    private val APPOPS_BEST_EFFORT = listOf(
        "appops set $HELPER_PKG 10021 allow",
        "appops set $HELPER_PKG ACCESS_RESTRICTED_SETTINGS allow",
        "pm grant $HELPER_PKG android.permission.POST_NOTIFICATIONS",
        "pm grant $HELPER_PKG android.permission.ACCESS_LOCAL_NETWORK",
        "dumpsys deviceidle whitelist +$HELPER_PKG",
    )

    fun computeAssetJarSha256(ctx: Context?): String =
        ctx?.let { hashAssetJar(it, copyTo = null) }.orEmpty()

    fun stageBridgeJarWithSha256(ctx: Context): Pair<String, String>? {
        val outFile = File(ctx.filesDir, "extbridge_stage.jar")
        if (outFile.exists()) {
            runCatching { outFile.setWritable(true, true); outFile.delete() }
        }
        val sha = hashAssetJar(ctx, copyTo = outFile) ?: return null
        outFile.setReadable(false, false)
        outFile.setReadable(true, true)
        // Android 17 (API 37) enforces read-only permissions on staged DEX/code archives.
        outFile.setWritable(false, false)
        return outFile.absolutePath to sha
    }

    private fun hashAssetJar(ctx: Context, copyTo: File?): String? = try {
        ctx.assets.open(ASSET_JAR_NAME).use { input ->
            val out = copyTo?.let { FileOutputStream(it, false) }
            out.use { fileOut ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    md.update(buf, 0, n)
                    fileOut?.write(buf, 0, n)
                }
                fileOut?.flush()
                md.digest().joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read/stage $ASSET_JAR_NAME", t)
        null
    }

    /**
     * One-line PC ADB activation command.
     * Zero '$', zero backticks, zero escaped inner quotes: runs identically in Bash, Zsh, sh, PowerShell, and cmd.exe!
     */
    fun buildPcAdbCommand(): String {
        val newJar = "$DST_JAR.new"
        val srcApk = "/data/local/tmp/extb_src.apk"
        val grants = buildList {
            add("appops set $HELPER_PKG SYSTEM_ALERT_WINDOW allow")
            add("appops set $HELPER_PKG RUN_ANY_IN_BACKGROUND allow")
            APPOPS_BEST_EFFORT.forEach { cmd ->
                val redir = if (cmd.startsWith("dumpsys")) ">/dev/null 2>&1" else "2>/dev/null"
                add("($cmd $redir || true)")
            }
        }.joinToString(" && ")

        return "adb shell \"" +
            "rm -f $srcApk $newJar && " +
            "pm path $HELPER_PKG | cut -d: -f2 | head -n1 | xargs cat > $srcApk && " +
            "test -s $srcApk && " +
            "unzip -p $srcApk assets/$ASSET_JAR_NAME > $newJar && " +
            "rm -f $srcApk && " +
            "test -s $newJar && " +
            "unzip -tq $newJar >/dev/null 2>&1 && " +
            "chmod 644 $newJar && " +
            "timeout 6 env CLASSPATH=$newJar app_process /data/local/tmp $DAEMON_CLASS --verify-helper >/dev/null 2>&1 && " +
            "(timeout 8 env CLASSPATH=$newJar app_process /data/local/tmp $DAEMON_CLASS --stop >/dev/null 2>&1 || true) && " +
            "(pgrep -f '^app_process.*ExtBridge' >/dev/null 2>&1 && (pkill -f '^app_process.*ExtBridge' 2>/dev/null || true) && sleep 1 && (pkill -f -l KILL '^app_process.*ExtBridge' 2>/dev/null || true) || true) && " +
            "rm -f $DST_JAR && " +
            "mv -f $newJar $DST_JAR && " +
            "test -s $DST_JAR && " +
            "$grants && " +
            "(CLASSPATH=$DST_JAR nohup app_process /data/local/tmp $DAEMON_CLASS >$DST_LOG 2>&1 </dev/null &)\""
    }

    /**
     * Fail-closed wireless ADB shell bootstrap script that extracts extbridge.jar from the installed APK
     * into a temporary file, verifies integrity BEFORE stopping any existing daemon, swaps atomically, and launches.
     */
    fun buildWirelessAdbBootstrapScript(expectedSha256: String = ""): String {
        val extractLogic =
            "APK=\$(pm path $HELPER_PKG | head -n1 | cut -d: -f2); " +
                "[ -n \"\$APK\" ] && unzip -p \"\$APK\" assets/$ASSET_JAR_NAME > \"\$TMP_JAR\""
        return "TMP_JAR=\$(mktemp /data/local/tmp/extbridge.XXXXXX 2>/dev/null || echo $DST_JAR.tmp.\$\$); " +
            "if [ -L \"\$TMP_JAR\" ]; then rm -f \"\$TMP_JAR\"; echo ERR_TMP_SYMLINK; exit 21; fi; " +
            "if ! ( $extractLogic ); then rm -f \"\$TMP_JAR\"; echo ERR_STAGE_JAR; exit 21; fi; " +
            buildShellSwapAndLaunchScript("\"\$TMP_JAR\"", expectedSha256)
    }

    /**
     * Root-to-shell bootstrap script: Root ONLY copies & SHA-256 verifies a nonce temp jar,
     * chowns it 2000:2000, and drops privileges to uid 2000 (`u:r:shell:s0`) before running app_process.
     */
    fun buildRootBootstrapScript(stagedPath: String, expectedSha256: String): String {
        val qStage = stagedPath.replace("'", "")
        val expSha = expectedSha256.lowercase(Locale.US)
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
        val rootTmp = "/data/local/tmp/extb_${nonce}.jar"
        val qShellInner = buildShellSwapAndLaunchScript("'$rootTmp'", expSha).replace("'", "'\\''")

        return "UID_NOW=\$(id -u 2>/dev/null || echo -1); " +
            "if [ \"\$UID_NOW\" != \"0\" ]; then echo NO_ROOT:\$UID_NOW; exit 12; fi; " +
            "if ! (umask 022; set -C; : > '$rootTmp') 2>/dev/null || [ -L '$rootTmp' ]; then " +
            "rm -f '$rootTmp' 2>/dev/null; echo ERR_MKTEMP; exit 21; fi; " +
            "if [ -f '$qStage' ] && [ ! -L '$qStage' ] && [ -s '$qStage' ]; then " +
            "cat '$qStage' > '$rootTmp' 2>/dev/null; fi; " +
            "SRC_SHA=\$(sha256sum '$rootTmp' 2>/dev/null | cut -d' ' -f1); " +
            "if [ \"\$SRC_SHA\" != '$expSha' ]; then " +
            "APK=\$(pm path $HELPER_PKG 2>/dev/null | head -n1 | cut -d: -f2); " +
            "if [ -n \"\$APK\" ] && [ -f \"\$APK\" ] && [ ! -L \"\$APK\" ]; then " +
            "unzip -p \"\$APK\" assets/$ASSET_JAR_NAME > '$rootTmp' 2>/dev/null; fi; " +
            "SRC_SHA=\$(sha256sum '$rootTmp' 2>/dev/null | cut -d' ' -f1); " +
            "fi; " +
            "if [ \"\$SRC_SHA\" != '$expSha' ] || ! unzip -tq '$rootTmp' >/dev/null 2>&1; then " +
            "rm -f '$rootTmp' 2>/dev/null; echo ERR_STAGE_HASH:\$SRC_SHA; exit 21; fi; " +
            "chmod 644 '$rootTmp' && chown -h 2000:2000 '$rootTmp' " +
            "|| { rm -f '$rootTmp' 2>/dev/null; echo ERR_STAGE_CHOWN; exit 21; }; " +
            "run_as_shell() { " +
            "su 2000 -c \"\$1\" && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
            "su shell -c \"\$1\" 2>/dev/null && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
            "su 2000 /system/bin/sh -c \"\$1\" 2>/dev/null && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
            "/system/bin/sh -c \"\$1\"; " +
            "}; " +
            "run_as_shell '$qShellInner'; RC=\$?; " +
            "rm -f '$rootTmp' 2>/dev/null; exit \$RC"
    }

    internal fun buildShellSwapAndLaunchScript(tmpJarExpr: String, expectedSha256: String? = null): String {
        val cleanSha = expectedSha256?.trim()?.lowercase(Locale.US).orEmpty()
        val hasSha = SHA256_HEX_REGEX.matches(cleanSha)
        val shaGuard = if (hasSha) {
            "ACT_SHA=\$(sha256sum $tmpJarExpr 2>/dev/null | cut -d' ' -f1); " +
                "if [ -n \"\$ACT_SHA\" ] && [ \"\$ACT_SHA\" != '$cleanSha' ]; then " +
                "rm -f $tmpJarExpr; echo ERR_JAR_SHA256; exit 21; fi; "
        } else {
            ""
        }
        val postSwapShaGuard = if (hasSha) {
            "FINAL_SHA=\$(sha256sum $DST_JAR 2>/dev/null | cut -d' ' -f1); " +
                "if [ -n \"\$FINAL_SHA\" ] && [ \"\$FINAL_SHA\" != '$cleanSha' ]; then " +
                "echo ERR_FINAL_SHA256; exit 22; fi; "
        } else {
            ""
        }
        val shellGrants = buildList {
            add("appops set $HELPER_PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null")
            add("appops set $HELPER_PKG POST_NOTIFICATION allow 2>/dev/null")
            add("appops set $HELPER_PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null")
            APPOPS_BEST_EFFORT.forEach { cmd ->
                val redir = if (cmd.startsWith("dumpsys")) ">/dev/null 2>&1" else "2>/dev/null"
                add("$cmd $redir")
            }
        }.joinToString("; ")

        val procPat = "'^(/.*/)?app_process.*ExtBridge'"
        return "if [ ! -f $tmpJarExpr ] || [ -L $tmpJarExpr ] || [ ! -s $tmpJarExpr ] " +
            "|| ! unzip -tq $tmpJarExpr >/dev/null 2>&1; then " +
            "rm -f $tmpJarExpr; echo ERR_STAGE_JAR; exit 21; fi; " +
            shaGuard +
            "chmod 644 $tmpJarExpr || { rm -f $tmpJarExpr; echo ERR_CHMOD_JAR; exit 21; }; " +
            "(CLASSPATH=$tmpJarExpr /system/bin/app_process /data/local/tmp $DAEMON_CLASS --stop >/dev/null 2>&1 || true); " +
            "if pgrep -f $procPat >/dev/null 2>&1; then " +
            "(pkill -f $procPat 2>/dev/null || true); sleep 1.0; " +
            "(pkill -f -l KILL $procPat 2>/dev/null || true); fi; " +
            "rm -f $DST_JAR; " +
            "if ! mv -f $tmpJarExpr $DST_JAR || [ -L $DST_JAR ] || [ ! -s $DST_JAR ]; then " +
            "rm -f $tmpJarExpr; echo ERR_SWAP_JAR; exit 22; fi; " +
            postSwapShaGuard +
            "chown -h 2000:2000 $DST_JAR 2>/dev/null; " +
            "LOG_DST=/dev/null; rm -f $DST_LOG 2>/dev/null; " +
            "if (umask 022; set -C; : > $DST_LOG) 2>/dev/null && [ -f $DST_LOG ] && [ ! -L $DST_LOG ]; then " +
            "chown -h 2000:2000 $DST_LOG 2>/dev/null; LOG_DST=$DST_LOG; fi; " +
            "$shellGrants; " +
            "CUR_CTX=\$(id -Z 2>/dev/null || echo ''); " +
            "case \"\$CUR_CTX\" in " +
            "*:shell:*) " +
            "(CLASSPATH=$DST_JAR setsid /system/bin/app_process /data/local/tmp $DAEMON_CLASS </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.5 ;; " +
            "*) " +
            "(CLASSPATH=$DST_JAR setsid runcon u:r:shell:s0 /system/bin/app_process /data/local/tmp $DAEMON_CLASS </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.5; " +
            "if ! pgrep -f $procPat >/dev/null 2>&1; then " +
            "(CLASSPATH=$DST_JAR setsid /system/bin/app_process /data/local/tmp $DAEMON_CLASS </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.5; fi ;; " +
            "esac; " +
            "if ! pgrep -f $procPat >/dev/null 2>&1; then " +
            "(CLASSPATH=$DST_JAR nohup app_process /data/local/tmp $DAEMON_CLASS </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.6; fi; " +
            "if pgrep -f $procPat >/dev/null 2>&1; then echo LAUNCH_DONE; else echo ERR_DAEMON_DIED; [ \"\$LOG_DST\" != \"/dev/null\" ] && cat \"\$LOG_DST\" 2>/dev/null; exit 24; fi"
    }
}

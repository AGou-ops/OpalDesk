import Foundation

/// Bootstraps the Android OpalDesk daemon over ADB shell, aligned 1:1 with `start_opaldesk.sh`.
enum AdbDaemonBootstrapper {
    static let helperPackage = "io.github.lengsukq.opaldesk"
    static let daemonClass = "io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon"
    static let daemonPatternShort = "^app_process.*OpalDeskDaemon"
    static let daemonPatternFull = "^/system/bin/app_process.*OpalDeskDaemon"

    private static let exitCodeNoJar: Int32 = 23
    private static let exitCodeUntrustedApk: Int32 = 24
    private static let checkTimeoutSeconds: Double = 6.0
    private static let bootstrapTimeoutSeconds: Double = 20.0
    private static let socketBindDelayNs: UInt64 = 450_000_000

    static func ensureDaemonRunning(adbPath: String, serial: String) async throws {
        if try await isDaemonRunning(adbPath: adbPath, serial: serial) {
            return
        }

        let helperInstalled = try await isHelperInstalled(adbPath: adbPath, serial: serial)
        let remoteScript = buildRemoteBootstrapScript(expectHelperInstalled: helperInstalled)
        let bootResult = try await ProcessRunner.run(
            executable: adbPath,
            arguments: ["-s", serial, "shell", remoteScript],
            timeout: bootstrapTimeoutSeconds
        )

        if bootResult.exitCode == exitCodeNoJar {
            OpalDeskLog.adb.error("Bootstrap exit 23 (no helper APK or jar) on \(serial, privacy: .public)")
            throw AdbError.helperNotInstalled(serial: serial)
        }
        if bootResult.exitCode == exitCodeUntrustedApk {
            OpalDeskLog.adb.error("Bootstrap exit 24 (untrusted helper APK signature) on \(serial, privacy: .public)")
            throw AdbError.untrustedApk(serial: serial)
        }
        if bootResult.exitCode != 0 {
            let detail = [bootResult.stderr, bootResult.stdout]
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .joined(separator: "\n")
            OpalDeskLog.adb.error("Bootstrap failed on \(serial, privacy: .public) with exit \(bootResult.exitCode): \(detail, privacy: .public)")
            throw AdbError.daemonStartFailed(detail: detail.isEmpty ? "exit \(bootResult.exitCode)" : detail)
        }

        // Wait briefly for the abstract socket listener to bind.
        try await Task.sleep(nanoseconds: socketBindDelayNs)
        let aliveAfterBoot = try await isDaemonRunning(adbPath: adbPath, serial: serial)
        if !aliveAfterBoot {
            OpalDeskLog.adb.error("Daemon process not detected after bootstrap on \(serial, privacy: .public)")
            throw AdbError.daemonStartFailed(detail: "启动命令已执行但未检测到 OpalDesk 进程存活")
        }
    }

    static func isHelperInstalled(adbPath: String, serial: String) async throws -> Bool {
        let res = try await ProcessRunner.run(
            executable: adbPath,
            arguments: ["-s", serial, "shell", "pm", "path", helperPackage],
            timeout: checkTimeoutSeconds
        )
        return res.exitCode == 0 && res.stdout.contains("package:")
    }

    static func isDaemonRunning(adbPath: String, serial: String) async throws -> Bool {
        let res = try await ProcessRunner.run(
            executable: adbPath,
            arguments: ["-s", serial, "shell", "pgrep -f '\(daemonPatternShort)' || pgrep -f '\(daemonPatternFull)'"],
            timeout: checkTimeoutSeconds
        )
        let trimmed = res.stdout.trimmingCharacters(in: .whitespacesAndNewlines)
        return res.exitCode == 0 && !trimmed.isEmpty
    }

    /// Generates the remote POSIX shell script mirroring `start_opaldesk.sh` safely under ADB shell uid=2000.
    /// Avoids Toybox-incompatible `unzip -tq` (Android 11-14 / early 15) and scans multi-split APK paths cleanly.
    static func buildRemoteBootstrapScript(expectHelperInstalled: Bool) -> String {
        let aliveExpr = "(pgrep -f '\(daemonPatternShort)' >/dev/null 2>&1 || pgrep -f '\(daemonPatternFull)' >/dev/null 2>&1)"
        let stopExisting = """
        if \(aliveExpr); then
          (pkill -f '\(daemonPatternShort)' 2>/dev/null || true)
          (pkill -f '\(daemonPatternFull)' 2>/dev/null || true)
          sleep 1.0
          (pkill -KILL -f '\(daemonPatternShort)' 2>/dev/null || pkill -9 -f '\(daemonPatternShort)' 2>/dev/null || true)
          (pkill -KILL -f '\(daemonPatternFull)' 2>/dev/null || pkill -9 -f '\(daemonPatternFull)' 2>/dev/null || true)
        fi
        """

        let stageStep: String
        if expectHelperInstalled {
            stageStep = """
            APK_PATHS=$(pm path \(helperPackage) 2>/dev/null | sed -n 's/^package://p' | tr -d '\\r')
            if [ -n "$APK_PATHS" ]; then
              TMP_JAR=$(mktemp /data/local/tmp/opaldesk.XXXXXX 2>/dev/null)
              if [ -z "$TMP_JAR" ] || [ -L "$TMP_JAR" ]; then
                rm -f "$TMP_JAR" 2>/dev/null
                echo "ERR_STAGE_JAR: failed to create exclusive temp file" >&2
                exit 21
              fi
              for p in $APK_PATHS; do
                if [ -n "$p" ] && [ -f "$p" ] && [ ! -L "$p" ]; then
                  unzip -p "$p" assets/opaldesk.jar > "$TMP_JAR" 2>/dev/null
                  if [ -s "$TMP_JAR" ] && [ ! -L "$TMP_JAR" ] && [ "$(dd if="$TMP_JAR" bs=1 count=2 2>/dev/null)" = "PK" ]; then
                    break
                  fi
                fi
              done
              if [ ! -s "$TMP_JAR" ] \
                || [ -L "$TMP_JAR" ] \
                || [ "$(dd if="$TMP_JAR" bs=1 count=2 2>/dev/null)" != "PK" ]; then
                rm -f "$TMP_JAR" 2>/dev/null
                echo "ERR_STAGE_JAR: failed to extract valid assets/opaldesk.jar" >&2
                exit 21
              fi
              chmod 644 "$TMP_JAR" || { rm -f "$TMP_JAR" 2>/dev/null; exit 21; }
              if ! timeout 6 env CLASSPATH="$TMP_JAR" app_process /data/local/tmp \(daemonClass) --verify-helper >/dev/null 2>&1; then
                rm -f "$TMP_JAR" 2>/dev/null
                echo "ERR_UNTRUSTED_APK: extracted daemon rejected \(helperPackage) signing certificate" >&2
                exit 24
              fi
              (timeout 8 env CLASSPATH="$TMP_JAR" /system/bin/app_process /data/local/tmp \(daemonClass) --stop >/dev/null 2>&1 || true)
              \(stopExisting)
              rm -f /data/local/tmp/opaldesk.jar 2>/dev/null
              if ! mv -f "$TMP_JAR" /data/local/tmp/opaldesk.jar \
                || [ -L /data/local/tmp/opaldesk.jar ] \
                || [ ! -s /data/local/tmp/opaldesk.jar ]; then
                rm -f "$TMP_JAR" 2>/dev/null
                echo "ERR_SWAP_JAR: failed to replace /data/local/tmp/opaldesk.jar" >&2
                exit 22
              fi
              appops set \(helperPackage) SYSTEM_ALERT_WINDOW allow 2>/dev/null
              appops set \(helperPackage) POST_NOTIFICATION allow 2>/dev/null
              appops set \(helperPackage) RUN_ANY_IN_BACKGROUND allow 2>/dev/null
              appops set \(helperPackage) 10021 allow 2>/dev/null
              appops set \(helperPackage) ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
              pm grant \(helperPackage) android.permission.POST_NOTIFICATIONS 2>/dev/null
              pm grant \(helperPackage) android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null
              dumpsys deviceidle whitelist +\(helperPackage) >/dev/null 2>&1
            fi
            """
        } else {
            stageStep = """
            if [ -L /data/local/tmp/opaldesk.jar ] || [ ! -s /data/local/tmp/opaldesk.jar ] || [ "$(dd if=/data/local/tmp/opaldesk.jar bs=1 count=2 2>/dev/null)" != "PK" ]; then
              echo "ERR_NO_JAR: \(helperPackage) not installed and /data/local/tmp/opaldesk.jar missing or invalid" >&2
              exit 23
            fi
            (CLASSPATH=/data/local/tmp/opaldesk.jar /system/bin/app_process /data/local/tmp \(daemonClass) --stop >/dev/null 2>&1 || true)
            \(stopExisting)
            """
        }

        return """
        \(stageStep)
        LOG_DST=/dev/null
        rm -f /data/local/tmp/opaldesk.log 2>/dev/null
        if (umask 022; set -C; : > /data/local/tmp/opaldesk.log) 2>/dev/null && [ -f /data/local/tmp/opaldesk.log ] && [ ! -L /data/local/tmp/opaldesk.log ]; then
          chown -h 2000:2000 /data/local/tmp/opaldesk.log 2>/dev/null
          LOG_DST=/data/local/tmp/opaldesk.log
        fi
        chown -h 2000:2000 /data/local/tmp/opaldesk.jar /data/local/tmp/opaldesk.conf /data/local/tmp/opaldesk_apps.conf /data/local/tmp/opaldesk_healed_v2 2>/dev/null
        export CLASSPATH=/data/local/tmp/opaldesk.jar
        CUR_CTX=$(id -Z 2>/dev/null || echo "")
        case "$CUR_CTX" in
          *:shell:*)
            (setsid /system/bin/app_process /data/local/tmp \(daemonClass) </dev/null >>"$LOG_DST" 2>&1 &)
            sleep 0.5
            ;;
          *)
            (setsid runcon u:r:shell:s0 /system/bin/app_process /data/local/tmp \(daemonClass) </dev/null >>"$LOG_DST" 2>&1 &)
            sleep 0.5
            if ! \(aliveExpr); then
              (setsid /system/bin/app_process /data/local/tmp \(daemonClass) </dev/null >>"$LOG_DST" 2>&1 &)
              sleep 0.5
            fi
            ;;
        esac
        if ! \(aliveExpr); then
          nohup app_process /data/local/tmp \(daemonClass) >>"$LOG_DST" 2>&1 </dev/null &
          sleep 0.5
        fi
        """
    }
}

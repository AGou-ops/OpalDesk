import Foundation

/// Bootstraps the Android ExtBridge daemon over ADB shell, aligned 1:1 with `start_extbridge.sh`.
enum AdbDaemonBootstrapper {
    static let helperPackage = "com.extbridge.helper"
    static let daemonProcessPattern = "^(/.*/)?app_process.*ExtBridge"

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
            ExtBridgeLog.adb.error("Bootstrap exit 23 (no helper APK or jar) on \(serial, privacy: .public)")
            throw AdbError.helperNotInstalled(serial: serial)
        }
        if bootResult.exitCode == exitCodeUntrustedApk {
            ExtBridgeLog.adb.error("Bootstrap exit 24 (untrusted helper APK signature) on \(serial, privacy: .public)")
            throw AdbError.untrustedApk(serial: serial)
        }
        if bootResult.exitCode != 0 {
            let detail = [bootResult.stderr, bootResult.stdout]
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .joined(separator: "\n")
            ExtBridgeLog.adb.error("Bootstrap failed on \(serial, privacy: .public) with exit \(bootResult.exitCode): \(detail, privacy: .public)")
            throw AdbError.daemonStartFailed(detail: detail.isEmpty ? "exit \(bootResult.exitCode)" : detail)
        }

        // Wait briefly for the abstract socket listener to bind.
        try await Task.sleep(nanoseconds: socketBindDelayNs)
        let aliveAfterBoot = try await isDaemonRunning(adbPath: adbPath, serial: serial)
        if !aliveAfterBoot {
            ExtBridgeLog.adb.error("Daemon process not detected after bootstrap on \(serial, privacy: .public)")
            throw AdbError.daemonStartFailed(detail: "启动命令已执行但未检测到 ExtBridge 进程存活")
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
            arguments: ["-s", serial, "shell", "pgrep -f '\(daemonProcessPattern)'"],
            timeout: checkTimeoutSeconds
        )
        let trimmed = res.stdout.trimmingCharacters(in: .whitespacesAndNewlines)
        return res.exitCode == 0 && !trimmed.isEmpty
    }

    /// Generates the remote POSIX shell script mirroring `start_extbridge.sh` safely under ADB shell uid=2000.
    static func buildRemoteBootstrapScript(expectHelperInstalled: Bool) -> String {
        let stopExisting = """
        if pgrep -f '\(daemonProcessPattern)' >/dev/null 2>&1; then
          (pkill -f '\(daemonProcessPattern)' 2>/dev/null || true)
          sleep 1.0
          (pkill -KILL -f '\(daemonProcessPattern)' 2>/dev/null || pkill -9 -f '\(daemonProcessPattern)' 2>/dev/null || true)
        fi
        """

        let stageStep: String
        if expectHelperInstalled {
            stageStep = """
            APK_PATH=$(pm path \(helperPackage) 2>/dev/null | head -n1 | cut -d: -f2)
            if [ -n "$APK_PATH" ]; then
              TMP_JAR=$(mktemp /data/local/tmp/extbridge.XXXXXX 2>/dev/null)
              if [ -z "$TMP_JAR" ] || [ -L "$TMP_JAR" ]; then
                rm -f "$TMP_JAR" 2>/dev/null
                echo "ERR_STAGE_JAR: failed to create exclusive temp file" >&2
                exit 21
              fi
              if ! unzip -p "$APK_PATH" assets/extbridge.jar > "$TMP_JAR" 2>/dev/null \
                || [ ! -s "$TMP_JAR" ] \
                || [ -L "$TMP_JAR" ] \
                || ! unzip -tq "$TMP_JAR" >/dev/null 2>&1; then
                rm -f "$TMP_JAR" 2>/dev/null
                echo "ERR_STAGE_JAR: failed to extract valid assets/extbridge.jar" >&2
                exit 21
              fi
              chmod 644 "$TMP_JAR" || { rm -f "$TMP_JAR" 2>/dev/null; exit 21; }
              if ! timeout 6 env CLASSPATH="$TMP_JAR" app_process /data/local/tmp com.extbridge.daemon.ExtBridge --verify-helper >/dev/null 2>&1; then
                rm -f "$TMP_JAR" 2>/dev/null
                echo "ERR_UNTRUSTED_APK: extracted daemon rejected \(helperPackage) signing certificate" >&2
                exit 24
              fi
              (timeout 8 env CLASSPATH="$TMP_JAR" /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge --stop >/dev/null 2>&1 || true)
              \(stopExisting)
              rm -f /data/local/tmp/extbridge.jar 2>/dev/null
              if ! mv -f "$TMP_JAR" /data/local/tmp/extbridge.jar \
                || [ -L /data/local/tmp/extbridge.jar ] \
                || [ ! -s /data/local/tmp/extbridge.jar ]; then
                rm -f "$TMP_JAR" 2>/dev/null
                echo "ERR_SWAP_JAR: failed to replace /data/local/tmp/extbridge.jar" >&2
                exit 22
              fi
              appops set \(helperPackage) SYSTEM_ALERT_WINDOW allow 2>/dev/null
              appops set \(helperPackage) POST_NOTIFICATION allow 2>/dev/null
              appops set \(helperPackage) RUN_ANY_IN_BACKGROUND allow 2>/dev/null
              appops set \(helperPackage) 10021 allow 2>/dev/null
              appops set \(helperPackage) ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
              pm grant \(helperPackage) android.permission.POST_NOTIFICATIONS 2>/dev/null
              dumpsys deviceidle whitelist +\(helperPackage) >/dev/null 2>&1
            fi
            """
        } else {
            stageStep = """
            if [ -L /data/local/tmp/extbridge.jar ] || [ ! -s /data/local/tmp/extbridge.jar ] || ! unzip -tq /data/local/tmp/extbridge.jar >/dev/null 2>&1; then
              echo "ERR_NO_JAR: \(helperPackage) not installed and /data/local/tmp/extbridge.jar missing or invalid" >&2
              exit 23
            fi
            (CLASSPATH=/data/local/tmp/extbridge.jar /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge --stop >/dev/null 2>&1 || true)
            \(stopExisting)
            """
        }

        return """
        \(stageStep)
        LOG_DST=/dev/null
        rm -f /data/local/tmp/extbridge.log 2>/dev/null
        if (umask 022; set -C; : > /data/local/tmp/extbridge.log) 2>/dev/null && [ -f /data/local/tmp/extbridge.log ] && [ ! -L /data/local/tmp/extbridge.log ]; then
          chown -h 2000:2000 /data/local/tmp/extbridge.log 2>/dev/null
          LOG_DST=/data/local/tmp/extbridge.log
        fi
        chown -h 2000:2000 /data/local/tmp/extbridge.jar /data/local/tmp/extbridge.conf /data/local/tmp/extbridge_apps.conf /data/local/tmp/extbridge_healed_v2 2>/dev/null
        export CLASSPATH=/data/local/tmp/extbridge.jar
        CUR_CTX=$(id -Z 2>/dev/null || echo "")
        case "$CUR_CTX" in
          *:shell:*)
            (setsid /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>"$LOG_DST" 2>&1 &)
            sleep 0.5
            ;;
          *)
            (setsid runcon u:r:shell:s0 /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>"$LOG_DST" 2>&1 &)
            sleep 0.5
            if ! pgrep -f '\(daemonProcessPattern)' >/dev/null 2>&1; then
              (setsid /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>"$LOG_DST" 2>&1 &)
              sleep 0.5
            fi
            ;;
        esac
        if ! pgrep -f '\(daemonProcessPattern)' >/dev/null 2>&1; then
          nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge >>"$LOG_DST" 2>&1 </dev/null &
          sleep 0.5
        fi
        """
    }
}

import Foundation
import os

public enum AdbDeviceState: String, Sendable, Equatable {
    case device
    case unauthorized
    case offline
    case unknown

    public var displayLabel: String {
        switch self {
        case .device: return "就绪"
        case .unauthorized: return "未授权 USB 调试"
        case .offline: return "离线"
        case .unknown: return "未知状态"
        }
    }
}

public struct AdbDevice: Sendable, Equatable, Identifiable, Hashable {
    public var id: String { serial }
    public let serial: String
    public let state: AdbDeviceState
    public let product: String
    public let model: String
    public let deviceCode: String
    public let transportId: String

    public init(
        serial: String,
        state: AdbDeviceState,
        product: String = "",
        model: String = "",
        deviceCode: String = "",
        transportId: String = ""
    ) {
        self.serial = serial
        self.state = state
        self.product = product
        self.model = model
        self.deviceCode = deviceCode
        self.transportId = transportId
    }

    public var displayName: String {
        if !model.isEmpty {
            return model.replacingOccurrences(of: "_", with: " ")
        }
        if !deviceCode.isEmpty {
            return deviceCode
        }
        return serial
    }

    public var isReady: Bool {
        state == .device
    }

    public var isWirelessAdb: Bool {
        serial.contains(":")
            || serial.contains("_adb-tls-connect")
            || serial.contains("_adb._tcp")
    }
}

public struct AdbForwardHandle: Sendable, Equatable {
    public let serial: String
    public let localPort: UInt16
    public let remoteAbstractSocket: String

    public init(serial: String, localPort: UInt16, remoteAbstractSocket: String = ExtCastProtocol.abstractSocketName) {
        self.serial = serial
        self.localPort = localPort
        self.remoteAbstractSocket = remoteAbstractSocket
    }
}

public enum AdbError: Error, Sendable, Equatable, LocalizedError {
    case adbNotFound
    case commandTimedOut(command: String)
    case commandFailed(command: String, exitCode: Int32, stderr: String)
    case helperNotInstalled(serial: String)
    case daemonStartFailed(detail: String)
    case forwardPortParseFailed(output: String)

    public var errorDescription: String? {
        switch self {
        case .adbNotFound:
            return "未找到 adb 可执行文件，请在「设置 → ADB」中指定路径或安装 Android Platform Tools"
        case .commandTimedOut(let cmd):
            return "ADB 命令执行超时：\(cmd)"
        case let .commandFailed(cmd, code, stderr):
            let detail = stderr.trimmingCharacters(in: .whitespacesAndNewlines)
            return detail.isEmpty
                ? "ADB 命令失败 (退出码 \(code))：\(cmd)"
                : "ADB 命令失败 (退出码 \(code))：\(detail)"
        case .helperNotInstalled(let serial):
            return "设备 \(serial) 未安装 ExtBridge Helper（com.extbridge.helper），且不存在 /data/local/tmp/extbridge.jar"
        case .daemonStartFailed(let detail):
            return "拉起手机端 ExtBridge 守护进程失败：\(detail)"
        case .forwardPortParseFailed(let output):
            return "无法解析 ADB 端口转发分配结果：\(output)"
        }
    }
}

/// Manages `adb` discovery, USB device enumeration, remote daemon bootstrap (equivalent to `start_extbridge.sh`),
/// and `adb forward` lifecycle cleanup.
public final class AdbManager: Sendable {
    private static let helperPackage = "com.extbridge.helper"
    private static let defaultCommandTimeoutSeconds: Double = 12.0

    private struct MutableState {
        var customAdbPath: String?
        var activeForwards: [AdbForwardHandle] = []
    }

    private let state: OSAllocatedUnfairLock<MutableState>

    public init(customAdbPath: String? = nil) {
        self.state = OSAllocatedUnfairLock(initialState: MutableState(customAdbPath: customAdbPath))
    }

    public func setCustomAdbPath(_ path: String?) {
        let trimmed = path?.trimmingCharacters(in: .whitespacesAndNewlines)
        state.withLock {
            $0.customAdbPath = (trimmed?.isEmpty == false) ? trimmed : nil
        }
    }

    /// Locates `adb` in priority order: custom override -> app bundle -> ANDROID_HOME/SDK_ROOT ->
    /// Homebrew Android commandlinetools -> Homebrew bin -> ~/Library/Android/sdk -> PATH.
    public func locateAdb() -> String? {
        let customPath = state.withLock { $0.customAdbPath }
        let fm = FileManager.default
        let env = ProcessInfo.processInfo.environment

        var candidates: [String] = []
        if let customPath {
            candidates.append((customPath as NSString).expandingTildeInPath)
        }
        if let resourceURL = Bundle.main.resourceURL {
            candidates.append(resourceURL.appendingPathComponent("adb").path)
            candidates.append(resourceURL.appendingPathComponent("platform-tools/adb").path)
        }
        if let execDir = Bundle.main.executableURL?.deletingLastPathComponent() {
            candidates.append(execDir.appendingPathComponent("adb").path)
        }
        if let androidHome = env["ANDROID_HOME"], !androidHome.isEmpty {
            candidates.append("\(androidHome)/platform-tools/adb")
        }
        if let androidSdkRoot = env["ANDROID_SDK_ROOT"], !androidSdkRoot.isEmpty {
            candidates.append("\(androidSdkRoot)/platform-tools/adb")
        }
        candidates.append("/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
        candidates.append("/opt/homebrew/bin/adb")
        candidates.append("/usr/local/bin/adb")
        let home = NSHomeDirectory()
        candidates.append("\(home)/Library/Android/sdk/platform-tools/adb")

        if let pathEnv = env["PATH"] {
            for dir in pathEnv.split(separator: ":") {
                candidates.append("\(dir)/adb")
            }
        }

        for path in candidates where fm.isExecutableFile(atPath: path) {
            return path
        }
        return nil
    }

    /// Scans USB-connected Android devices via `adb devices -l`.
    public func listDevices() async throws -> [AdbDevice] {
        guard let adbPath = locateAdb() else {
            throw AdbError.adbNotFound
        }
        let result = try await runProcess(executable: adbPath, arguments: ["devices", "-l"], timeout: 6.0)
        guard result.exitCode == 0 else {
            throw AdbError.commandFailed(command: "adb devices -l", exitCode: result.exitCode, stderr: result.stderr)
        }
        return Self.parseDevicesOutput(result.stdout)
    }

    /// Pure parser for `adb devices -l` output (unit-testable without hardware).
    public static func parseDevicesOutput(_ output: String) -> [AdbDevice] {
        var devices: [AdbDevice] = []
        for rawLine in output.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty
                || line.hasPrefix("List of devices")
                || line.hasPrefix("*")
            {
                continue
            }
            let tokens = line.split(whereSeparator: \.isWhitespace).map(String.init)
            guard tokens.count >= 2 else { continue }
            let serial = tokens[0]
            let rawState = tokens[1]
            let deviceState = AdbDeviceState(rawValue: rawState) ?? .unknown

            var product = ""
            var model = ""
            var deviceCode = ""
            var transportId = ""

            for token in tokens.dropFirst(2) {
                guard let eq = token.firstIndex(of: ":") else { continue }
                let key = String(token[..<eq])
                let value = String(token[token.index(after: eq)...])
                switch key {
                case "product": product = value
                case "model": model = value
                case "device": deviceCode = value
                case "transport_id": transportId = value
                default: break
                }
            }

            devices.append(AdbDevice(
                serial: serial,
                state: deviceState,
                product: product,
                model: model,
                deviceCode: deviceCode,
                transportId: transportId
            ))
        }
        return devices
    }

    /// Ensures the Android ExtBridge daemon is alive on `serial` (mirroring `start_extbridge.sh` semantics)
    /// and sets up `adb -s <serial> forward tcp:<localPort> localabstract:extbridge_stream`.
    public func bootstrapDaemon(serial: String, preferredLocalPort: UInt16? = nil) async throws -> AdbForwardHandle {
        guard let adbPath = locateAdb() else {
            throw AdbError.adbNotFound
        }

        let isRunning = try await isDaemonRunning(adbPath: adbPath, serial: serial)
        if !isRunning {
            let helperInstalled = try await isHelperInstalled(adbPath: adbPath, serial: serial)
            let remoteBootstrap = Self.buildRemoteBootstrapScript(expectHelperInstalled: helperInstalled)
            let bootResult = try await runProcess(
                executable: adbPath,
                arguments: ["-s", serial, "shell", remoteBootstrap],
                timeout: 18.0
            )
            if bootResult.exitCode == 23 {
                throw AdbError.helperNotInstalled(serial: serial)
            }
            if bootResult.exitCode != 0 {
                let detail = [bootResult.stderr, bootResult.stdout]
                    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
                    .joined(separator: "\n")
                throw AdbError.daemonStartFailed(detail: detail.isEmpty ? "exit \(bootResult.exitCode)" : detail)
            }

            // Wait briefly for the abstract socket listener to bind.
            try await Task.sleep(nanoseconds: 450_000_000)
            let aliveAfterBoot = try await isDaemonRunning(adbPath: adbPath, serial: serial)
            if !aliveAfterBoot {
                throw AdbError.daemonStartFailed(detail: "启动命令已执行但未检测到 ExtBridge 进程存活")
            }
        }

        return try await createForward(adbPath: adbPath, serial: serial, preferredLocalPort: preferredLocalPort)
    }

    /// Removes a previously created ADB port forward.
    public func removeForward(_ handle: AdbForwardHandle) async {
        state.withLock {
            $0.activeForwards.removeAll { $0 == handle }
        }
        guard let adbPath = locateAdb() else { return }
        _ = try? await runProcess(
            executable: adbPath,
            arguments: ["-s", handle.serial, "forward", "--remove", "tcp:\(handle.localPort)"],
            timeout: 5.0
        )
    }

    /// Removes all ADB port forwards registered by this manager.
    public func removeAllForwards() async {
        let handles = state.withLock { s -> [AdbForwardHandle] in
            let current = s.activeForwards
            s.activeForwards.removeAll()
            return current
        }
        for handle in handles {
            await removeForward(handle)
        }
    }

    // MARK: - Internal Helpers

    private func isHelperInstalled(adbPath: String, serial: String) async throws -> Bool {
        let res = try await runProcess(
            executable: adbPath,
            arguments: ["-s", serial, "shell", "pm", "path", Self.helperPackage],
            timeout: 6.0
        )
        return res.exitCode == 0 && res.stdout.contains("package:")
    }

    private func isDaemonRunning(adbPath: String, serial: String) async throws -> Bool {
        let res = try await runProcess(
            executable: adbPath,
            arguments: ["-s", serial, "shell", "pgrep -f '^(/.*/)?app_process.*ExtBridge'"],
            timeout: 6.0
        )
        let trimmed = res.stdout.trimmingCharacters(in: .whitespacesAndNewlines)
        return res.exitCode == 0 && !trimmed.isEmpty
    }

    private func createForward(
        adbPath: String,
        serial: String,
        preferredLocalPort: UInt16?
    ) async throws -> AdbForwardHandle {
        let localSpec = preferredLocalPort.map { "tcp:\($0)" } ?? "tcp:0"
        let remoteSpec = "localabstract:\(ExtCastProtocol.abstractSocketName)"
        let res = try await runProcess(
            executable: adbPath,
            arguments: ["-s", serial, "forward", localSpec, remoteSpec],
            timeout: 6.0
        )
        guard res.exitCode == 0 else {
            throw AdbError.commandFailed(
                command: "adb forward \(localSpec) \(remoteSpec)",
                exitCode: res.exitCode,
                stderr: res.stderr
            )
        }

        let resolvedPort: UInt16
        if let preferredLocalPort {
            resolvedPort = preferredLocalPort
        } else {
            let trimmed = res.stdout.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let parsed = UInt16(trimmed), parsed > 0 else {
                throw AdbError.forwardPortParseFailed(output: trimmed)
            }
            resolvedPort = parsed
        }

        let handle = AdbForwardHandle(serial: serial, localPort: resolvedPort)
        state.withLock { $0.activeForwards.append(handle) }
        return handle
    }

    /// Mirrors `start_extbridge.sh` safely under ADB shell uid=2000.
    private static func buildRemoteBootstrapScript(expectHelperInstalled: Bool) -> String {
        let stageStep: String
        if expectHelperInstalled {
            stageStep = """
            APK_PATH=$(pm path com.extbridge.helper 2>/dev/null | head -n1 | cut -d: -f2); \
            if [ -n "$APK_PATH" ]; then \
              TMP_JAR=$(mktemp /data/local/tmp/extbridge.XXXXXX 2>/dev/null); \
              if [ -z "$TMP_JAR" ] || [ -L "$TMP_JAR" ]; then rm -f "$TMP_JAR" 2>/dev/null; exit 21; fi; \
              if ! unzip -p "$APK_PATH" assets/extbridge.jar > "$TMP_JAR" 2>/dev/null || [ ! -s "$TMP_JAR" ] || ! unzip -tq "$TMP_JAR" >/dev/null 2>&1; then \
                rm -f "$TMP_JAR" 2>/dev/null; echo "ERR_STAGE_JAR" >&2; exit 21; \
              fi; \
              chmod 644 "$TMP_JAR" || { rm -f "$TMP_JAR" 2>/dev/null; exit 21; }; \
              if ! timeout 6 env CLASSPATH="$TMP_JAR" app_process /data/local/tmp com.extbridge.daemon.ExtBridge --verify-helper >/dev/null 2>&1; then \
                rm -f "$TMP_JAR" 2>/dev/null; echo "ERR_UNTRUSTED_APK" >&2; exit 24; \
              fi; \
              rm -f /data/local/tmp/extbridge.jar 2>/dev/null; \
              mv -f "$TMP_JAR" /data/local/tmp/extbridge.jar || { rm -f "$TMP_JAR" 2>/dev/null; exit 22; }; \
              appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null; \
              appops set com.extbridge.helper POST_NOTIFICATION allow 2>/dev/null; \
              appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null; \
              appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; \
              pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null; \
              dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1; \
            fi;
            """
        } else {
            stageStep = """
            if [ -L /data/local/tmp/extbridge.jar ] || [ ! -s /data/local/tmp/extbridge.jar ] || ! unzip -tq /data/local/tmp/extbridge.jar >/dev/null 2>&1; then \
              echo "ERR_NO_JAR" >&2; exit 23; \
            fi;
            """
        }

        return """
        \(stageStep) \
        LOG_DST=/data/local/tmp/extbridge.log; \
        rm -f "$LOG_DST" 2>/dev/null; \
        export CLASSPATH=/data/local/tmp/extbridge.jar; \
        (setsid /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>"$LOG_DST" 2>&1 &); \
        sleep 0.5; \
        if ! pgrep -f '^(/.*/)?app_process.*ExtBridge' >/dev/null 2>&1; then \
          nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge >>"$LOG_DST" 2>&1 </dev/null & \
          sleep 0.5; \
        fi
        """
    }

    private struct ProcessResult: Sendable {
        let exitCode: Int32
        let stdout: String
        let stderr: String
    }

    private func runProcess(
        executable: String,
        arguments: [String],
        timeout: Double = AdbManager.defaultCommandTimeoutSeconds
    ) async throws -> ProcessResult {
        final class PipeAccumulator: Sendable {
            let lock = OSAllocatedUnfairLock<Data>(initialState: Data())
            func append(_ data: Data) {
                guard !data.isEmpty else { return }
                lock.withLock { $0.append(data) }
            }
            func string() -> String {
                let d = lock.withLock { $0 }
                return String(data: d, encoding: .utf8) ?? ""
            }
        }

        final class ResumeGate: Sendable {
            let lock = OSAllocatedUnfairLock<Bool>(initialState: false)
            func claim() -> Bool {
                lock.withLock { done in
                    if done { return false }
                    done = true
                    return true
                }
            }
        }

        let commandDesc = ([executable] + arguments).joined(separator: " ")

        return try await withCheckedThrowingContinuation { continuation in
            let process = Process()
            process.executableURL = URL(fileURLWithPath: executable)
            process.arguments = arguments

            let stdoutPipe = Pipe()
            let stderrPipe = Pipe()
            process.standardOutput = stdoutPipe
            process.standardError = stderrPipe

            let stdoutAcc = PipeAccumulator()
            let stderrAcc = PipeAccumulator()
            let gate = ResumeGate()

            stdoutPipe.fileHandleForReading.readabilityHandler = { handle in
                let chunk = handle.availableData
                if chunk.isEmpty {
                    handle.readabilityHandler = nil
                } else {
                    stdoutAcc.append(chunk)
                }
            }

            stderrPipe.fileHandleForReading.readabilityHandler = { handle in
                let chunk = handle.availableData
                if chunk.isEmpty {
                    handle.readabilityHandler = nil
                } else {
                    stderrAcc.append(chunk)
                }
            }

            let timeoutTask = Task.detached(priority: .utility) {
                let ns = UInt64(max(timeout, 0.5) * 1_000_000_000)
                try? await Task.sleep(nanoseconds: ns)
                guard !Task.isCancelled, gate.claim() else { return }
                stdoutPipe.fileHandleForReading.readabilityHandler = nil
                stderrPipe.fileHandleForReading.readabilityHandler = nil
                if process.isRunning {
                    process.terminate()
                }
                continuation.resume(throwing: AdbError.commandTimedOut(command: commandDesc))
            }

            process.terminationHandler = { proc in
                timeoutTask.cancel()
                stdoutPipe.fileHandleForReading.readabilityHandler = nil
                stderrPipe.fileHandleForReading.readabilityHandler = nil
                // Drain any remaining buffered bytes after EOF.
                if let tailOut = try? stdoutPipe.fileHandleForReading.readToEnd() {
                    stdoutAcc.append(tailOut)
                }
                if let tailErr = try? stderrPipe.fileHandleForReading.readToEnd() {
                    stderrAcc.append(tailErr)
                }
                guard gate.claim() else { return }
                continuation.resume(returning: ProcessResult(
                    exitCode: proc.terminationStatus,
                    stdout: stdoutAcc.string(),
                    stderr: stderrAcc.string()
                ))
            }

            do {
                try process.run()
            } catch {
                timeoutTask.cancel()
                stdoutPipe.fileHandleForReading.readabilityHandler = nil
                stderrPipe.fileHandleForReading.readabilityHandler = nil
                if gate.claim() {
                    continuation.resume(throwing: AdbError.commandFailed(
                        command: commandDesc,
                        exitCode: -1,
                        stderr: error.localizedDescription
                    ))
                }
            }
        }
    }
}

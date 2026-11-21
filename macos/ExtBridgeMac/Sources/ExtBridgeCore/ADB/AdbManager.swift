import Foundation
import os

/// Manages `adb` discovery, USB/wireless ADB device enumeration, remote daemon bootstrap,
/// and `adb forward` lifecycle cleanup.
public final class AdbManager: Sendable {
    private static let quickCommandTimeoutSeconds: Double = 6.0
    private static let removeForwardTimeoutSeconds: Double = 5.0

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

    private static let externalVolumeRelativeSDKPaths: [String] = [
        "dependencies/android-sdk/platform-tools/adb",
        "Android/sdk/platform-tools/adb",
        "android-sdk/platform-tools/adb"
    ]

    /// Locates `adb` in priority order: custom override -> app bundle -> ANDROID_HOME/SDK_ROOT ->
    /// Homebrew Android commandlinetools -> Homebrew bin -> ~/Library/Android/sdk -> PATH ->
    /// external volumes (`/Volumes/*/dependencies/android-sdk/platform-tools/adb`, etc.).
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
        candidates.append("\(NSHomeDirectory())/Library/Android/sdk/platform-tools/adb")

        if let pathEnv = env["PATH"] {
            for dir in pathEnv.split(separator: ":") {
                candidates.append("\(dir)/adb")
            }
        }

        if let localMatch = candidates.first(where: { fm.isExecutableFile(atPath: $0) }) {
            return localMatch
        }

        return Self.externalVolumeAdbCandidates(fileManager: fm)
            .first { fm.isExecutableFile(atPath: $0) }
    }

    /// Scans mounted external volumes for Android SDK `platform-tools/adb` binaries.
    public static func externalVolumeAdbCandidates(
        volumesRoot: String = "/Volumes",
        fileManager: FileManager = .default
    ) -> [String] {
        let entries: [String]
        do {
            entries = try fileManager.contentsOfDirectory(atPath: volumesRoot).sorted()
        } catch {
            ExtBridgeLog.adb.debug(
                "Unable to scan \(volumesRoot, privacy: .public) for Android SDK adb: \(error.localizedDescription, privacy: .public)"
            )
            return []
        }

        var paths: [String] = []
        for volume in entries where !volume.hasPrefix(".") {
            let volumePath = (volumesRoot as NSString).appendingPathComponent(volume)
            for relativePath in externalVolumeRelativeSDKPaths {
                paths.append((volumePath as NSString).appendingPathComponent(relativePath))
            }
        }
        return paths
    }

    /// Scans USB and wireless-ADB Android devices via `adb devices -l`.
    public func listDevices() async throws -> [AdbDevice] {
        guard let adbPath = locateAdb() else {
            throw AdbError.adbNotFound
        }
        let result = try await ProcessRunner.run(
            executable: adbPath,
            arguments: ["devices", "-l"],
            timeout: Self.quickCommandTimeoutSeconds
        )
        guard result.exitCode == 0 else {
            throw AdbError.commandFailed(command: "adb devices -l", exitCode: result.exitCode, stderr: result.stderr)
        }
        return Self.parseDevicesOutput(result.stdout)
    }

    private static let knownAdbStateTokens: Set<String> = [
        "device", "unauthorized", "offline", "authorizing",
        "recovery", "sideload", "bootloader", "connecting"
    ]

    /// Pure parser for `adb devices -l` output (unit-testable without hardware).
    public static func parseDevicesOutput(_ output: String) -> [AdbDevice] {
        var devices: [AdbDevice] = []
        for rawLine in output.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("List of devices") || line.hasPrefix("*") {
                continue
            }
            let slices = line.split(omittingEmptySubsequences: true, whereSeparator: \.isWhitespace)
            guard slices.count >= 2 else { continue }

            let (serial, deviceState, attrSlices) = splitDeviceLine(line: line, slices: slices)
            guard !serial.isEmpty else { continue }

            var product = ""
            var model = ""
            var deviceCode = ""
            var transportId = ""

            for tokenSlice in attrSlices {
                let token = String(tokenSlice)
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

    private static func splitDeviceLine(
        line: String,
        slices: [Substring]
    ) -> (serial: String, state: AdbDeviceState, attributes: ArraySlice<Substring>) {
        // Wireless ADB mDNS serials can include spaces (e.g. "adb-xxxx (3)._adb-tls-connect._tcp"),
        // so locate the ADB state column rather than splitting blindly at the first whitespace.
        for idx in 1..<slices.count {
            let token = String(slices[idx])
            if knownAdbStateTokens.contains(token),
               slices.dropFirst(idx + 1).allSatisfy({ $0.contains(":") })
            {
                let serial = String(line[line.startIndex..<slices[idx].startIndex])
                    .trimmingCharacters(in: .whitespaces)
                return (serial, AdbDeviceState(rawValue: token) ?? .unknown, slices.dropFirst(idx + 1))
            }
            if token == "no", idx + 1 < slices.count, slices[idx + 1] == "permissions" {
                let serial = String(line[line.startIndex..<slices[idx].startIndex])
                    .trimmingCharacters(in: .whitespaces)
                return (serial, .unknown, slices.dropFirst(idx + 2))
            }
        }
        return (String(slices[0]), AdbDeviceState(rawValue: String(slices[1])) ?? .unknown, slices.dropFirst(2))
    }

    /// Ensures the Android ExtBridge daemon is alive on `serial` (mirroring `start_extbridge.sh` semantics)
    /// and sets up `adb -s <serial> forward tcp:<localPort> localabstract:extbridge_stream`.
    public func bootstrapDaemon(serial: String, preferredLocalPort: UInt16? = nil) async throws -> AdbForwardHandle {
        guard let adbPath = locateAdb() else {
            throw AdbError.adbNotFound
        }
        try await AdbDaemonBootstrapper.ensureDaemonRunning(adbPath: adbPath, serial: serial)
        return try await createForward(adbPath: adbPath, serial: serial, preferredLocalPort: preferredLocalPort)
    }

    /// Removes a previously created ADB port forward.
    public func removeForward(_ handle: AdbForwardHandle) async {
        state.withLock {
            $0.activeForwards.removeAll { $0 == handle }
        }
        guard let adbPath = locateAdb() else { return }
        do {
            let res = try await ProcessRunner.run(
                executable: adbPath,
                arguments: ["-s", handle.serial, "forward", "--remove", "tcp:\(handle.localPort)"],
                timeout: Self.removeForwardTimeoutSeconds
            )
            if res.exitCode != 0 {
                let stderr = res.stderr.trimmingCharacters(in: .whitespacesAndNewlines)
                ExtBridgeLog.adb.warning(
                    "adb forward --remove tcp:\(handle.localPort) failed on \(handle.serial, privacy: .public) (exit \(res.exitCode)): \(stderr, privacy: .public)"
                )
            }
        } catch {
            ExtBridgeLog.adb.warning(
                "Failed to remove adb forward tcp:\(handle.localPort) on \(handle.serial, privacy: .public): \(error.localizedDescription, privacy: .public)"
            )
        }
    }

    // MARK: - Private Forward Helper

    private func createForward(
        adbPath: String,
        serial: String,
        preferredLocalPort: UInt16?
    ) async throws -> AdbForwardHandle {
        let localSpec = preferredLocalPort.map { "tcp:\($0)" } ?? "tcp:0"
        let remoteSpec = "localabstract:\(ExtCastProtocol.abstractSocketName)"
        let res = try await ProcessRunner.run(
            executable: adbPath,
            arguments: ["-s", serial, "forward", localSpec, remoteSpec],
            timeout: Self.quickCommandTimeoutSeconds
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
}

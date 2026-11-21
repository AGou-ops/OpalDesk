import Foundation
import os

struct ProcessResult: Sendable {
    let exitCode: Int32
    let stdout: String
    let stderr: String
}

/// Timeout-guarded asynchronous runner for external CLI executables (e.g., `adb`).
enum ProcessRunner {
    static let defaultTimeoutSeconds: Double = 12.0
    private static let minimumTimeoutSeconds: Double = 0.5

    private final class PipeAccumulator: Sendable {
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

    private final class ResumeGate: Sendable {
        let lock = OSAllocatedUnfairLock<Bool>(initialState: false)

        func claim() -> Bool {
            lock.withLock { done in
                if done { return false }
                done = true
                return true
            }
        }
    }

    static func run(
        executable: String,
        arguments: [String],
        timeout: Double = defaultTimeoutSeconds
    ) async throws -> ProcessResult {
        let commandDesc = ([executable] + arguments).joined(separator: " ")

        return try await withCheckedThrowingContinuation { continuation in
            let process = Process()
            process.executableURL = URL(fileURLWithPath: executable)
            process.arguments = arguments
            var env = ProcessInfo.processInfo.environment
            if env["ADB_MDNS"] == nil { env["ADB_MDNS"] = "0" }
            if env["ADB_MDNS_OPENSCREEN"] == nil { env["ADB_MDNS_OPENSCREEN"] = "0" }
            if env["ADB_MDNS_AUTO_CONNECT"] == nil { env["ADB_MDNS_AUTO_CONNECT"] = "" }
            process.environment = env

            let stdoutPipe = Pipe()
            let stderrPipe = Pipe()
            process.standardOutput = stdoutPipe
            process.standardError = stderrPipe

            let stdoutAcc = PipeAccumulator()
            let stderrAcc = PipeAccumulator()
            let gate = ResumeGate()

            attachReader(stdoutPipe.fileHandleForReading, into: stdoutAcc)
            attachReader(stderrPipe.fileHandleForReading, into: stderrAcc)

            let timeoutTask = Task.detached(priority: .utility) {
                let ns = UInt64(max(timeout, minimumTimeoutSeconds) * 1_000_000_000)
                do {
                    try await Task.sleep(nanoseconds: ns)
                } catch {
                    return
                }
                guard !Task.isCancelled, gate.claim() else { return }
                stdoutPipe.fileHandleForReading.readabilityHandler = nil
                stderrPipe.fileHandleForReading.readabilityHandler = nil
                if process.isRunning {
                    process.terminate()
                }
                ExtBridgeLog.adb.warning("Process timed out after \(timeout, privacy: .public)s: \(commandDesc, privacy: .public)")
                continuation.resume(throwing: AdbError.commandTimedOut(command: commandDesc))
            }

            process.terminationHandler = { proc in
                timeoutTask.cancel()
                stdoutPipe.fileHandleForReading.readabilityHandler = nil
                stderrPipe.fileHandleForReading.readabilityHandler = nil
                drainRemaining(stdoutPipe.fileHandleForReading, into: stdoutAcc, label: "stdout")
                drainRemaining(stderrPipe.fileHandleForReading, into: stderrAcc, label: "stderr")
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
                    ExtBridgeLog.adb.error("Failed to launch process \(executable, privacy: .public): \(error.localizedDescription, privacy: .public)")
                    continuation.resume(throwing: AdbError.commandFailed(
                        command: commandDesc,
                        exitCode: -1,
                        stderr: error.localizedDescription
                    ))
                }
            }
        }
    }

    private static func attachReader(_ handle: FileHandle, into accumulator: PipeAccumulator) {
        handle.readabilityHandler = { fh in
            let chunk = fh.availableData
            if chunk.isEmpty {
                fh.readabilityHandler = nil
            } else {
                accumulator.append(chunk)
            }
        }
    }

    private static func drainRemaining(_ handle: FileHandle, into accumulator: PipeAccumulator, label: String) {
        do {
            if let tail = try handle.readToEnd() {
                accumulator.append(tail)
            }
        } catch {
            ExtBridgeLog.adb.debug("Pipe tail drain (\(label, privacy: .public)) ended with error: \(error.localizedDescription, privacy: .public)")
        }
    }
}

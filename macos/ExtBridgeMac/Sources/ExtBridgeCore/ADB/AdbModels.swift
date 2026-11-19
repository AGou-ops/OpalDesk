import Foundation

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

    public init(
        serial: String,
        localPort: UInt16,
        remoteAbstractSocket: String = ExtCastProtocol.abstractSocketName
    ) {
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
    case untrustedApk(serial: String)
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
        case .untrustedApk(let serial):
            return "设备 \(serial) 上的 com.extbridge.helper 签名证书未通过校验，请重新安装官方签名的 ExtBridge Helper"
        case .daemonStartFailed(let detail):
            return "拉起手机端 ExtBridge 守护进程失败：\(detail)"
        case .forwardPortParseFailed(let output):
            return "无法解析 ADB 端口转发分配结果：\(output)"
        }
    }
}

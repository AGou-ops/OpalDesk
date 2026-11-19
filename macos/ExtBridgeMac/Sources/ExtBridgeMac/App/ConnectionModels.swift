import ExtBridgeCore
import Foundation

/// Active transport backing the main ExtCast session.
public enum ActiveTransportKind: Sendable, Equatable {
    case wiredUSB(serial: String, localPort: UInt16)
    case wirelessADB(serial: String, localPort: UInt16)
    case wirelessWiFi(displayTarget: String, host: String?, port: UInt16?)

    public var shortBadge: String {
        switch self {
        case .wiredUSB: return "USB 有线"
        case .wirelessADB: return "无线 ADB"
        case .wirelessWiFi: return "Wi-Fi 直连"
        }
    }

    public var systemImage: String {
        switch self {
        case .wiredUSB: return "cable.connector"
        case .wirelessADB, .wirelessWiFi: return "wifi"
        }
    }

    public var transportHint: ExtCastTransportHint {
        switch self {
        case .wiredUSB: return .usb
        case .wirelessADB: return .wifiAdb
        case .wirelessWiFi: return .wifiTls
        }
    }
}

/// Metadata describing the currently connected Android host.
public struct ActiveConnectionInfo: Sendable, Equatable {
    public var deviceName: String
    public var model: String
    public var transport: ActiveTransportKind
    public var fingerprint: String
    public var connectedAt: Date
}

/// Context driving the TOFU certificate fingerprint + 6-digit PIN pairing sheet.
public struct PairingPromptContext: Identifiable, Sendable, Equatable {
    public enum Source: Sendable, Equatable {
        case bonjour(DiscoveredBonjourDevice)
        case manualHostPort(host: String, port: UInt16)
    }

    public let id = UUID()
    public let source: Source
    public let displayName: String
    public let model: String
    public let expectedFingerprint: String
    public var errorMessage: String?
    public var isSubmitting: Bool = false

    public var shortFingerprint: String {
        FingerprintUtil.formatShort(expectedFingerprint)
    }
}

/// Snapshot of the active authenticated connection shared by standalone popout app windows.
public struct PopoutConnectionContext: Sendable, Equatable {
    public let endpoint: ExtCastEndpoint
    public let transportHint: ExtCastTransportHint
    public let clientId: String
    public let authToken: String
    public let preferredCodec: ExtCastVideoCodec
    public let targetFPS: Int
    public let bitrateKbps: Int
    public let mapCommandToCtrl: Bool

    public init(
        endpoint: ExtCastEndpoint,
        transportHint: ExtCastTransportHint,
        clientId: String,
        authToken: String,
        preferredCodec: ExtCastVideoCodec,
        targetFPS: Int,
        bitrateKbps: Int,
        mapCommandToCtrl: Bool
    ) {
        self.endpoint = endpoint
        self.transportHint = transportHint
        self.clientId = clientId
        self.authToken = authToken
        self.preferredCodec = preferredCodec
        self.targetFPS = targetFPS
        self.bitrateKbps = bitrateKbps
        self.mapCommandToCtrl = mapCommandToCtrl
    }
}

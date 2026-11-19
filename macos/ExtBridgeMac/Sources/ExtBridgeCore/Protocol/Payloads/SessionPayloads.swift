import Foundation

// MARK: - Downlink Session Payloads (Android -> Mac)

/// Downlink `0x10 DEVICE_STATE` UTF-8 JSON payload.
public struct DeviceStatePayload: Decodable, Sendable, Equatable {
    public var deviceName: String
    public var sinkType: Int
    public var displayMode: Int
    public var width: Int
    public var height: Int
    public var dpi: Int
    public var fps: Double
    public var rotation: Int
    public var bitrateKbps: Int
    public var batteryPct: Int
    public var blackout: Bool
    public var audioEnabled: Bool
    public var injectStatus: Int
    public var adaptiveSingleEnabled: Bool
    public var defaultSingleWidthDp: Int
    public var activeLayoutMode: String
    public var topAppPackage: String
    public var topAppLabel: String
    public var tilingSlots: [String]
    public var tilingRatios: [Int]

    public var layoutMode: ExtCastLayoutMode? {
        ExtCastLayoutMode(rawValue: activeLayoutMode)
    }

    public init(
        deviceName: String = "",
        sinkType: Int = 0,
        displayMode: Int = Int(ExtCastDisplayMode.dex.rawValue),
        width: Int = ExtCastProtocol.defaultWidth,
        height: Int = ExtCastProtocol.defaultHeight,
        dpi: Int = DisplayPresets.defaultDPI,
        fps: Double = Double(DisplayPresets.defaultFPS),
        rotation: Int = 0,
        bitrateKbps: Int = ExtCastProtocol.defaultDeviceStateBitrateKbps,
        batteryPct: Int = -1,
        blackout: Bool = false,
        audioEnabled: Bool = false,
        injectStatus: Int = ExtCastInjectStatus.unknown.rawValue,
        adaptiveSingleEnabled: Bool = true,
        defaultSingleWidthDp: Int = DisplayPresets.defaultSingleWidthDp,
        activeLayoutMode: String = ExtCastLayoutMode.fullscreen.rawValue,
        topAppPackage: String = "",
        topAppLabel: String = "",
        tilingSlots: [String] = [],
        tilingRatios: [Int] = []
    ) {
        self.deviceName = deviceName
        self.sinkType = sinkType
        self.displayMode = displayMode
        self.width = width
        self.height = height
        self.dpi = dpi
        self.fps = fps
        self.rotation = rotation
        self.bitrateKbps = bitrateKbps
        self.batteryPct = batteryPct
        self.blackout = blackout
        self.audioEnabled = audioEnabled
        self.injectStatus = injectStatus
        self.adaptiveSingleEnabled = adaptiveSingleEnabled
        self.defaultSingleWidthDp = DisplayPresets.clampSingleWidthDp(defaultSingleWidthDp)
        self.activeLayoutMode = activeLayoutMode.isEmpty ? ExtCastLayoutMode.fullscreen.rawValue : activeLayoutMode
        self.topAppPackage = topAppPackage
        self.topAppLabel = topAppLabel
        self.tilingSlots = tilingSlots
        self.tilingRatios = tilingRatios
    }

    private enum CodingKeys: String, CodingKey {
        case deviceName, sinkType, displayMode, width, height, dpi, fps, rotation
        case bitrateKbps, batteryPct, blackout, audioEnabled, injectStatus
        case adaptiveSingleEnabled, defaultSingleWidthDp, activeLayoutMode
        case topAppPackage, topAppLabel, tilingSlots, tilingRatios
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let rawWidthDp = try c.decodeIfPresent(Int.self, forKey: .defaultSingleWidthDp) ?? DisplayPresets.defaultSingleWidthDp
        let rawLayout = try c.decodeIfPresent(String.self, forKey: .activeLayoutMode) ?? ""
        self.init(
            deviceName: try c.decodeIfPresent(String.self, forKey: .deviceName) ?? "",
            sinkType: try c.decodeIfPresent(Int.self, forKey: .sinkType) ?? 0,
            displayMode: try c.decodeIfPresent(Int.self, forKey: .displayMode) ?? Int(ExtCastDisplayMode.dex.rawValue),
            width: try c.decodeIfPresent(Int.self, forKey: .width) ?? ExtCastProtocol.defaultWidth,
            height: try c.decodeIfPresent(Int.self, forKey: .height) ?? ExtCastProtocol.defaultHeight,
            dpi: try c.decodeIfPresent(Int.self, forKey: .dpi) ?? DisplayPresets.defaultDPI,
            fps: try c.decodeIfPresent(Double.self, forKey: .fps) ?? Double(DisplayPresets.defaultFPS),
            rotation: try c.decodeIfPresent(Int.self, forKey: .rotation) ?? 0,
            bitrateKbps: try c.decodeIfPresent(Int.self, forKey: .bitrateKbps) ?? ExtCastProtocol.defaultDeviceStateBitrateKbps,
            batteryPct: try c.decodeIfPresent(Int.self, forKey: .batteryPct) ?? -1,
            blackout: try c.decodeIfPresent(Bool.self, forKey: .blackout) ?? false,
            audioEnabled: try c.decodeIfPresent(Bool.self, forKey: .audioEnabled) ?? false,
            injectStatus: try c.decodeIfPresent(Int.self, forKey: .injectStatus) ?? ExtCastInjectStatus.unknown.rawValue,
            adaptiveSingleEnabled: try c.decodeIfPresent(Bool.self, forKey: .adaptiveSingleEnabled) ?? true,
            defaultSingleWidthDp: rawWidthDp,
            activeLayoutMode: rawLayout,
            topAppPackage: try c.decodeIfPresent(String.self, forKey: .topAppPackage) ?? "",
            topAppLabel: try c.decodeIfPresent(String.self, forKey: .topAppLabel) ?? "",
            tilingSlots: try c.decodeIfPresent([String].self, forKey: .tilingSlots) ?? [],
            tilingRatios: try c.decodeIfPresent([Int].self, forKey: .tilingRatios) ?? []
        )
    }

    public static func decode(from data: Data) throws -> DeviceStatePayload {
        try ExtCastJSONCodec.decode(DeviceStatePayload.self, from: data, context: "DEVICE_STATE")
    }
}

/// Downlink `0x12 AUTH_RESULT` payload: 1-byte `ok` prefix followed by UTF-8 JSON metadata.
public struct AuthResultPayload: Decodable, Sendable, Equatable {
    public var ok: Bool
    public var authToken: String
    public var message: String
    public var deviceName: String
    public var certFingerprint: String

    public init(
        ok: Bool,
        authToken: String = "",
        message: String = "",
        deviceName: String = "",
        certFingerprint: String = ""
    ) {
        self.ok = ok
        self.authToken = authToken
        self.message = message
        self.deviceName = deviceName
        self.certFingerprint = certFingerprint
    }

    private enum CodingKeys: String, CodingKey {
        case ok, authToken, message, deviceName, certFingerprint
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = try c.decodeIfPresent(Bool.self, forKey: .ok) ?? false
        authToken = try c.decodeIfPresent(String.self, forKey: .authToken) ?? ""
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
        deviceName = try c.decodeIfPresent(String.self, forKey: .deviceName) ?? ""
        certFingerprint = try c.decodeIfPresent(String.self, forKey: .certFingerprint) ?? ""
    }

    public static func decode(from data: Data) throws -> AuthResultPayload {
        var reader = BinaryReader(data)
        let okByte = try reader.readUInt8()
        let headerOk = okByte != 0
        let jsonBytes = reader.readRemainingBytes()
        if jsonBytes.isEmpty {
            return AuthResultPayload(ok: headerOk)
        }
        var decoded = try ExtCastJSONCodec.decode(AuthResultPayload.self, from: jsonBytes, context: "AUTH_RESULT")
        decoded.ok = headerOk && decoded.ok
        return decoded
    }
}

/// Downlink `0x14 WIFI_UPGRADE_READY` UTF-8 JSON payload.
public struct WifiUpgradeReadyPayload: Decodable, Sendable, Equatable {
    public var ok: Bool
    public var host: String
    public var port: Int
    public var certFingerprint: String
    public var authToken: String
    public var message: String

    public init(
        ok: Bool,
        host: String = "",
        port: Int = Int(ExtCastProtocol.defaultWifiPort),
        certFingerprint: String = "",
        authToken: String = "",
        message: String = ""
    ) {
        self.ok = ok
        self.host = host
        self.port = port
        self.certFingerprint = certFingerprint
        self.authToken = authToken
        self.message = message
    }

    private enum CodingKeys: String, CodingKey {
        case ok, host, port, certFingerprint, authToken, message
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = try c.decodeIfPresent(Bool.self, forKey: .ok) ?? false
        host = try c.decodeIfPresent(String.self, forKey: .host) ?? ""
        port = try c.decodeIfPresent(Int.self, forKey: .port) ?? Int(ExtCastProtocol.defaultWifiPort)
        certFingerprint = try c.decodeIfPresent(String.self, forKey: .certFingerprint) ?? ""
        authToken = try c.decodeIfPresent(String.self, forKey: .authToken) ?? ""
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
    }

    public var validPort: UInt16? {
        guard port > 0 && port <= Int(UInt16.max) else { return nil }
        return UInt16(port)
    }

    public static func decode(from data: Data) throws -> WifiUpgradeReadyPayload {
        try ExtCastJSONCodec.decode(WifiUpgradeReadyPayload.self, from: data, context: "WIFI_UPGRADE_READY")
    }
}

// MARK: - Bidirectional Keepalive Payload

/// Bidirectional `0x2F PING` / `0x1F PONG` 8-byte Big-Endian sequence payload.
public struct PingPongPayload: Sendable, Equatable {
    public var pingId: UInt64

    public init(pingId: UInt64) {
        self.pingId = pingId
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 8)
        writer.writeUInt64(pingId)
        return writer.data
    }

    public static func decode(from data: Data) throws -> PingPongPayload {
        var reader = BinaryReader(data)
        return PingPongPayload(pingId: try reader.readUInt64())
    }
}

// MARK: - Uplink Session Payloads (Mac -> Android)

/// Uplink `0x20 HELLO` UTF-8 JSON handshake payload.
public struct HelloPayload: Encodable, Sendable, Equatable {
    public var protocolVersion: Int
    public var clientName: String
    public var clientId: String
    public var preferredCodec: String
    public var width: Int
    public var height: Int
    public var dpi: Int
    public var fps: Int
    public var bitrateKbps: Int
    public var displayMode: Int
    public var audioEnabled: Bool
    public var authToken: String
    public var pinCode: String
    public var transportHint: String
    public var sessionRole: String
    public var appPackage: String
    public var appActivity: String

    public init(
        protocolVersion: Int = ExtCastProtocol.version,
        clientName: String,
        clientId: String,
        preferredCodec: String = ExtCastVideoCodec.hevc.preferredName,
        width: Int = ExtCastProtocol.defaultWidth,
        height: Int = ExtCastProtocol.defaultHeight,
        dpi: Int = DisplayPresets.defaultDPI,
        fps: Int = DisplayPresets.defaultFPS,
        bitrateKbps: Int = DisplayPresets.defaultBitrateKbps,
        displayMode: Int = Int(ExtCastDisplayMode.dex.rawValue),
        audioEnabled: Bool = true,
        authToken: String = "",
        pinCode: String = "",
        transportHint: String = ExtCastTransportHint.usb.rawValue,
        sessionRole: String = ExtCastSessionRole.desktop.rawValue,
        appPackage: String = "",
        appActivity: String = ""
    ) {
        self.protocolVersion = protocolVersion
        self.clientName = clientName
        self.clientId = clientId
        self.preferredCodec = preferredCodec
        self.width = width
        self.height = height
        self.dpi = dpi
        self.fps = fps
        self.bitrateKbps = bitrateKbps
        self.displayMode = displayMode
        self.audioEnabled = audioEnabled
        self.authToken = authToken
        self.pinCode = pinCode
        self.transportHint = transportHint
        self.sessionRole = sessionRole
        self.appPackage = appPackage
        self.appActivity = appActivity
    }

    public func encode() throws -> Data {
        try ExtCastJSONCodec.encode(self, context: "HELLO")
    }
}

/// Uplink `0x21 RESIZE_CONFIG` fixed 14-byte Big-Endian payload.
public struct ResizeConfigPayload: Sendable, Equatable {
    public static let unchangedByte: UInt8 = 0xFF

    public var width: UInt16
    public var height: UInt16
    public var dpi: UInt16
    public var fpsX100: UInt16
    public var bitrateKbps: UInt32
    public var displayMode: UInt8
    public var rotation: UInt8

    public init(
        width: UInt16,
        height: UInt16,
        dpi: UInt16,
        fpsX100: UInt16,
        bitrateKbps: UInt32,
        displayMode: UInt8 = ResizeConfigPayload.unchangedByte,
        rotation: UInt8 = ResizeConfigPayload.unchangedByte
    ) {
        self.width = width
        self.height = height
        self.dpi = dpi
        self.fpsX100 = fpsX100
        self.bitrateKbps = bitrateKbps
        self.displayMode = displayMode
        self.rotation = rotation
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 14)
        writer.writeUInt16(width)
        writer.writeUInt16(height)
        writer.writeUInt16(dpi)
        writer.writeUInt16(fpsX100)
        writer.writeUInt32(bitrateKbps)
        writer.writeUInt8(displayMode)
        writer.writeUInt8(rotation)
        return writer.data
    }
}

/// Uplink `0x27 SYSTEM_ACTION` fixed 5-byte Big-Endian payload.
public struct SystemActionPayload: Sendable, Equatable {
    public var action: ExtCastSystemActionKind
    public var arg: Int32

    public init(action: ExtCastSystemActionKind, arg: Int32 = 0) {
        self.action = action
        self.arg = arg
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 5)
        writer.writeUInt8(action.rawValue)
        writer.writeInt32(arg)
        return writer.data
    }
}

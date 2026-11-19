import Foundation

// MARK: - Big-Endian Binary Helpers

struct BinaryWriter {
    private(set) var data: Data

    init(capacity: Int = 64) {
        self.data = Data(capacity: capacity)
    }

    mutating func writeUInt8(_ value: UInt8) {
        data.append(value)
    }

    mutating func writeUInt16(_ value: UInt16) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }

    mutating func writeUInt32(_ value: UInt32) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }

    mutating func writeInt32(_ value: Int32) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }

    mutating func writeUInt64(_ value: UInt64) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }

    mutating func writeInt64(_ value: Int64) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }

    mutating func writeFloat32(_ value: Float) {
        writeUInt32(value.bitPattern)
    }

    mutating func writeBytes(_ bytes: Data) {
        data.append(bytes)
    }

    mutating func writeUTF8(_ string: String) {
        data.append(Data(string.utf8))
    }
}

struct BinaryReader {
    private let data: Data
    private var offset: Int

    var remainingCount: Int {
        data.endIndex - offset
    }

    init(_ data: Data) {
        self.data = data
        self.offset = data.startIndex
    }

    private mutating func ensureAvailable(_ count: Int) throws {
        guard remainingCount >= count else {
            throw ExtCastCodecError.truncatedPayload(
                expected: (offset - data.startIndex) + count,
                actual: data.count
            )
        }
    }

    mutating func readUInt8() throws -> UInt8 {
        try ensureAvailable(1)
        let value = data[offset]
        offset += 1
        return value
    }

    mutating func readUInt16() throws -> UInt16 {
        try ensureAvailable(2)
        let b0 = UInt16(data[offset])
        let b1 = UInt16(data[offset + 1])
        offset += 2
        return (b0 << 8) | b1
    }

    mutating func readUInt32() throws -> UInt32 {
        try ensureAvailable(4)
        let b0 = UInt32(data[offset])
        let b1 = UInt32(data[offset + 1])
        let b2 = UInt32(data[offset + 2])
        let b3 = UInt32(data[offset + 3])
        offset += 4
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
    }

    mutating func readInt32() throws -> Int32 {
        Int32(bitPattern: try readUInt32())
    }

    mutating func readUInt64() throws -> UInt64 {
        let high = UInt64(try readUInt32())
        let low = UInt64(try readUInt32())
        return (high << 32) | low
    }

    mutating func readInt64() throws -> Int64 {
        Int64(bitPattern: try readUInt64())
    }

    mutating func readFloat32() throws -> Float {
        Float(bitPattern: try readUInt32())
    }

    mutating func readBytes(count: Int) throws -> Data {
        try ensureAvailable(count)
        let slice = data[offset..<(offset + count)]
        offset += count
        return Data(slice)
    }

    mutating func readRemainingBytes() -> Data {
        guard offset < data.endIndex else { return Data() }
        let slice = data[offset..<data.endIndex]
        offset = data.endIndex
        return Data(slice)
    }

    mutating func readRemainingUTF8(field: String) throws -> String {
        let bytes = readRemainingBytes()
        guard let str = String(data: bytes, encoding: .utf8) else {
            throw ExtCastCodecError.invalidUTF8(field: field)
        }
        return str
    }
}

// MARK: - Downlink Payloads (Android -> Mac)

public struct VideoConfigPayload: Sendable, Equatable {
    public var codec: ExtCastVideoCodec
    public var width: UInt16
    public var height: UInt16
    public var dpi: UInt16
    public var rotation: UInt8
    public var fpsX100: UInt16
    public var displayMode: ExtCastDisplayMode
    public var csd: Data

    public init(
        codec: ExtCastVideoCodec,
        width: UInt16,
        height: UInt16,
        dpi: UInt16,
        rotation: UInt8,
        fpsX100: UInt16,
        displayMode: ExtCastDisplayMode,
        csd: Data
    ) {
        self.codec = codec
        self.width = width
        self.height = height
        self.dpi = dpi
        self.rotation = rotation
        self.fpsX100 = fpsX100
        self.displayMode = displayMode
        self.csd = csd
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 11 + csd.count)
        writer.writeUInt8(codec.rawValue)
        writer.writeUInt16(width)
        writer.writeUInt16(height)
        writer.writeUInt16(dpi)
        writer.writeUInt8(rotation)
        writer.writeUInt16(fpsX100)
        writer.writeUInt8(displayMode.rawValue)
        writer.writeBytes(csd)
        return writer.data
    }

    public static func decode(from data: Data) throws -> VideoConfigPayload {
        var reader = BinaryReader(data)
        let codecRaw = try reader.readUInt8()
        guard let codec = ExtCastVideoCodec(rawValue: codecRaw) else {
            throw ExtCastCodecError.invalidEnumValue(field: "videoCodec", rawValue: UInt32(codecRaw))
        }
        let width = try reader.readUInt16()
        let height = try reader.readUInt16()
        let dpi = try reader.readUInt16()
        let rotation = try reader.readUInt8()
        let fpsX100 = try reader.readUInt16()
        let displayModeRaw = try reader.readUInt8()
        guard let displayMode = ExtCastDisplayMode(rawValue: displayModeRaw) else {
            throw ExtCastCodecError.invalidEnumValue(field: "displayMode", rawValue: UInt32(displayModeRaw))
        }
        let csd = reader.readRemainingBytes()
        return VideoConfigPayload(
            codec: codec,
            width: width,
            height: height,
            dpi: dpi,
            rotation: rotation,
            fpsX100: fpsX100,
            displayMode: displayMode,
            csd: csd
        )
    }
}

public struct AudioConfigPayload: Sendable, Equatable {
    public var codec: ExtCastAudioCodec
    public var sampleRate: UInt32
    public var channels: UInt8
    public var codecSpecificData: Data

    public init(
        codec: ExtCastAudioCodec,
        sampleRate: UInt32,
        channels: UInt8,
        codecSpecificData: Data
    ) {
        self.codec = codec
        self.sampleRate = sampleRate
        self.channels = channels
        self.codecSpecificData = codecSpecificData
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 6 + codecSpecificData.count)
        writer.writeUInt8(codec.rawValue)
        writer.writeUInt32(sampleRate)
        writer.writeUInt8(channels)
        writer.writeBytes(codecSpecificData)
        return writer.data
    }

    public static func decode(from data: Data) throws -> AudioConfigPayload {
        var reader = BinaryReader(data)
        let codecRaw = try reader.readUInt8()
        guard let codec = ExtCastAudioCodec(rawValue: codecRaw) else {
            throw ExtCastCodecError.invalidEnumValue(field: "audioCodec", rawValue: UInt32(codecRaw))
        }
        let sampleRate = try reader.readUInt32()
        let channels = try reader.readUInt8()
        let csd = reader.readRemainingBytes()
        return AudioConfigPayload(
            codec: codec,
            sampleRate: sampleRate,
            channels: channels,
            codecSpecificData: csd
        )
    }
}

public struct DeviceStatePayload: Codable, Sendable, Equatable {
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

    public init(
        deviceName: String = "",
        sinkType: Int = ExtCastSinkType.none.rawValue,
        displayMode: Int = Int(ExtCastDisplayMode.dex.rawValue),
        width: Int = 1920,
        height: Int = 1080,
        dpi: Int = 240,
        fps: Double = 60.0,
        rotation: Int = 0,
        bitrateKbps: Int = 12000,
        batteryPct: Int = -1,
        blackout: Bool = false,
        audioEnabled: Bool = true,
        injectStatus: Int = 0,
        adaptiveSingleEnabled: Bool = true,
        defaultSingleWidthDp: Int = 460,
        activeLayoutMode: String = "",
        topAppPackage: String = "",
        topAppLabel: String = ""
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
        self.defaultSingleWidthDp = defaultSingleWidthDp
        self.activeLayoutMode = activeLayoutMode
        self.topAppPackage = topAppPackage
        self.topAppLabel = topAppLabel
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        deviceName = try c.decodeIfPresent(String.self, forKey: .deviceName) ?? ""
        sinkType = try c.decodeIfPresent(Int.self, forKey: .sinkType) ?? 0
        displayMode = try c.decodeIfPresent(Int.self, forKey: .displayMode) ?? 0
        width = try c.decodeIfPresent(Int.self, forKey: .width) ?? 0
        height = try c.decodeIfPresent(Int.self, forKey: .height) ?? 0
        dpi = try c.decodeIfPresent(Int.self, forKey: .dpi) ?? 240
        fps = try c.decodeIfPresent(Double.self, forKey: .fps) ?? 60.0
        rotation = try c.decodeIfPresent(Int.self, forKey: .rotation) ?? 0
        bitrateKbps = try c.decodeIfPresent(Int.self, forKey: .bitrateKbps) ?? 0
        batteryPct = try c.decodeIfPresent(Int.self, forKey: .batteryPct) ?? -1
        blackout = try c.decodeIfPresent(Bool.self, forKey: .blackout) ?? false
        audioEnabled = try c.decodeIfPresent(Bool.self, forKey: .audioEnabled) ?? true
        injectStatus = try c.decodeIfPresent(Int.self, forKey: .injectStatus) ?? 0
        adaptiveSingleEnabled = try c.decodeIfPresent(Bool.self, forKey: .adaptiveSingleEnabled) ?? true
        defaultSingleWidthDp = try c.decodeIfPresent(Int.self, forKey: .defaultSingleWidthDp) ?? 460
        activeLayoutMode = try c.decodeIfPresent(String.self, forKey: .activeLayoutMode) ?? ""
        topAppPackage = try c.decodeIfPresent(String.self, forKey: .topAppPackage) ?? ""
        topAppLabel = try c.decodeIfPresent(String.self, forKey: .topAppLabel) ?? ""
    }

    public func encode() throws -> Data {
        try JSONEncoder().encode(self)
    }

    public static func decode(from data: Data) throws -> DeviceStatePayload {
        do {
            return try JSONDecoder().decode(DeviceStatePayload.self, from: data)
        } catch {
            throw ExtCastCodecError.invalidJSON("DEVICE_STATE: \(error.localizedDescription)")
        }
    }
}

public struct ClipboardPushPayload: Sendable, Equatable {
    public var seq: Int64
    public var text: String

    public init(seq: Int64, text: String) {
        self.seq = seq
        self.text = text
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 8 + text.utf8.count)
        writer.writeInt64(seq)
        writer.writeUTF8(text)
        return writer.data
    }

    public static func decode(from data: Data) throws -> ClipboardPushPayload {
        var reader = BinaryReader(data)
        let seq = try reader.readInt64()
        let text = try reader.readRemainingUTF8(field: "clipboardPush.text")
        return ClipboardPushPayload(seq: seq, text: text)
    }
}

/// Downlink `0x11 CLIPBOARD_PUSH` with `flags = 0x01` (`FLAG_CLIPBOARD_IMAGE_PNG`):
/// `int64 seq` (8 bytes BE) + raw PNG bytes (<= 8 MiB).
public struct ClipboardImagePushPayload: Sendable, Equatable {
    public var seq: Int64
    public var pngData: Data

    public init(seq: Int64, pngData: Data) {
        self.seq = seq
        self.pngData = pngData
    }

    public func encode() throws -> Data {
        guard pngData.count <= ExtCastProtocol.maxClipboardImageBytes else {
            throw ExtCastCodecError.payloadTooLarge(UInt32(clamping: pngData.count))
        }
        var writer = BinaryWriter(capacity: 8 + pngData.count)
        writer.writeInt64(seq)
        writer.writeBytes(pngData)
        return writer.data
    }

    public static func decode(from data: Data) throws -> ClipboardImagePushPayload {
        var reader = BinaryReader(data)
        let seq = try reader.readInt64()
        let pngData = reader.readRemainingBytes()
        guard pngData.count <= ExtCastProtocol.maxClipboardImageBytes else {
            throw ExtCastCodecError.payloadTooLarge(UInt32(clamping: pngData.count))
        }
        return ClipboardImagePushPayload(seq: seq, pngData: pngData)
    }
}

public struct AuthResultPayload: Codable, Sendable, Equatable {
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

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = try c.decodeIfPresent(Bool.self, forKey: .ok) ?? false
        authToken = try c.decodeIfPresent(String.self, forKey: .authToken) ?? ""
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
        deviceName = try c.decodeIfPresent(String.self, forKey: .deviceName) ?? ""
        certFingerprint = try c.decodeIfPresent(String.self, forKey: .certFingerprint) ?? ""
    }

    public func encode() throws -> Data {
        let jsonData = try JSONEncoder().encode(self)
        var writer = BinaryWriter(capacity: 1 + jsonData.count)
        writer.writeUInt8(ok ? 1 : 0)
        writer.writeBytes(jsonData)
        return writer.data
    }

    public static func decode(from data: Data) throws -> AuthResultPayload {
        var reader = BinaryReader(data)
        let okByte = try reader.readUInt8()
        let headerOk = okByte != 0
        let jsonBytes = reader.readRemainingBytes()
        if jsonBytes.isEmpty {
            return AuthResultPayload(ok: headerOk)
        }
        do {
            var decoded = try JSONDecoder().decode(AuthResultPayload.self, from: jsonBytes)
            decoded.ok = headerOk && decoded.ok
            return decoded
        } catch {
            throw ExtCastCodecError.invalidJSON("AUTH_RESULT: \(error.localizedDescription)")
        }
    }
}

public struct FileTransferAckPayload: Sendable, Equatable {
    public var transferId: UInt32
    public var status: ExtCastFileAckStatus
    public var pathOrError: String

    public init(transferId: UInt32, status: ExtCastFileAckStatus, pathOrError: String) {
        self.transferId = transferId
        self.status = status
        self.pathOrError = pathOrError
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 5 + pathOrError.utf8.count)
        writer.writeUInt32(transferId)
        writer.writeUInt8(status.rawValue)
        writer.writeUTF8(pathOrError)
        return writer.data
    }

    public static func decode(from data: Data) throws -> FileTransferAckPayload {
        var reader = BinaryReader(data)
        let transferId = try reader.readUInt32()
        let statusRaw = try reader.readUInt8()
        guard let status = ExtCastFileAckStatus(rawValue: statusRaw) else {
            throw ExtCastCodecError.invalidEnumValue(field: "fileTransferAck.status", rawValue: UInt32(statusRaw))
        }
        let pathOrError = try reader.readRemainingUTF8(field: "fileTransferAck.pathOrError")
        return FileTransferAckPayload(transferId: transferId, status: status, pathOrError: pathOrError)
    }
}

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
        let pingId = try reader.readUInt64()
        return PingPongPayload(pingId: pingId)
    }
}

/// Downlink `0x14 WIFI_UPGRADE_READY` UTF-8 JSON payload.
public struct WifiUpgradeReadyPayload: Codable, Sendable, Equatable {
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

    public func encode() throws -> Data {
        try JSONEncoder().encode(self)
    }

    public static func decode(from data: Data) throws -> WifiUpgradeReadyPayload {
        do {
            return try JSONDecoder().decode(WifiUpgradeReadyPayload.self, from: data)
        } catch {
            throw ExtCastCodecError.invalidJSON("WIFI_UPGRADE_READY: \(error.localizedDescription)")
        }
    }
}

/// A launchable or active Android app entry reported inside `0x15 APP_LIST`.
public struct RemoteAppInfo: Codable, Sendable, Equatable, Identifiable, Hashable {
    public var pkg: String
    public var cls: String
    public var label: String
    public var runningOnExt: Bool
    public var taskId: Int

    public var id: String {
        cls.isEmpty ? pkg : "\(pkg)/\(cls)"
    }

    public var displayLabel: String {
        label.isEmpty ? pkg : label
    }

    public init(
        pkg: String,
        cls: String = "",
        label: String = "",
        runningOnExt: Bool = false,
        taskId: Int = -1
    ) {
        self.pkg = pkg
        self.cls = cls
        self.label = label
        self.runningOnExt = runningOnExt
        self.taskId = taskId
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        pkg = try c.decodeIfPresent(String.self, forKey: .pkg) ?? ""
        cls = try c.decodeIfPresent(String.self, forKey: .cls) ?? ""
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
        runningOnExt = try c.decodeIfPresent(Bool.self, forKey: .runningOnExt) ?? false
        taskId = try c.decodeIfPresent(Int.self, forKey: .taskId) ?? -1
    }
}

/// Downlink `0x15 APP_LIST` UTF-8 JSON payload.
public struct AppListPayload: Codable, Sendable, Equatable {
    public var apps: [RemoteAppInfo]

    public init(apps: [RemoteAppInfo] = []) {
        self.apps = apps
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        apps = try c.decodeIfPresent([RemoteAppInfo].self, forKey: .apps) ?? []
    }

    public func encode() throws -> Data {
        try JSONEncoder().encode(self)
    }

    public static func decode(from data: Data) throws -> AppListPayload {
        do {
            return try JSONDecoder().decode(AppListPayload.self, from: data)
        } catch {
            throw ExtCastCodecError.invalidJSON("APP_LIST: \(error.localizedDescription)")
        }
    }
}

// MARK: - Uplink Payloads (Mac -> Android)

public struct HelloPayload: Codable, Sendable, Equatable {
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
        width: Int = 1920,
        height: Int = 1080,
        dpi: Int = 240,
        fps: Int = 60,
        bitrateKbps: Int = 16000,
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

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        protocolVersion = try c.decodeIfPresent(Int.self, forKey: .protocolVersion) ?? ExtCastProtocol.version
        clientName = try c.decodeIfPresent(String.self, forKey: .clientName) ?? ""
        clientId = try c.decodeIfPresent(String.self, forKey: .clientId) ?? ""
        preferredCodec = try c.decodeIfPresent(String.self, forKey: .preferredCodec) ?? ExtCastVideoCodec.hevc.preferredName
        width = try c.decodeIfPresent(Int.self, forKey: .width) ?? 1920
        height = try c.decodeIfPresent(Int.self, forKey: .height) ?? 1080
        dpi = try c.decodeIfPresent(Int.self, forKey: .dpi) ?? 240
        fps = try c.decodeIfPresent(Int.self, forKey: .fps) ?? 60
        bitrateKbps = try c.decodeIfPresent(Int.self, forKey: .bitrateKbps) ?? 16000
        displayMode = try c.decodeIfPresent(Int.self, forKey: .displayMode) ?? Int(ExtCastDisplayMode.dex.rawValue)
        audioEnabled = try c.decodeIfPresent(Bool.self, forKey: .audioEnabled) ?? true
        authToken = try c.decodeIfPresent(String.self, forKey: .authToken) ?? ""
        pinCode = try c.decodeIfPresent(String.self, forKey: .pinCode) ?? ""
        transportHint = try c.decodeIfPresent(String.self, forKey: .transportHint) ?? ExtCastTransportHint.usb.rawValue
        sessionRole = try c.decodeIfPresent(String.self, forKey: .sessionRole) ?? ExtCastSessionRole.desktop.rawValue
        appPackage = try c.decodeIfPresent(String.self, forKey: .appPackage) ?? ""
        appActivity = try c.decodeIfPresent(String.self, forKey: .appActivity) ?? ""
    }

    public func encode() throws -> Data {
        try JSONEncoder().encode(self)
    }

    public static func decode(from data: Data) throws -> HelloPayload {
        do {
            return try JSONDecoder().decode(HelloPayload.self, from: data)
        } catch {
            throw ExtCastCodecError.invalidJSON("HELLO: \(error.localizedDescription)")
        }
    }
}

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

    public static func decode(from data: Data) throws -> ResizeConfigPayload {
        var reader = BinaryReader(data)
        return ResizeConfigPayload(
            width: try reader.readUInt16(),
            height: try reader.readUInt16(),
            dpi: try reader.readUInt16(),
            fpsX100: try reader.readUInt16(),
            bitrateKbps: try reader.readUInt32(),
            displayMode: try reader.readUInt8(),
            rotation: try reader.readUInt8()
        )
    }
}

public struct InputMousePayload: Sendable, Equatable {
    public var action: ExtCastMouseAction
    public var x: Float
    public var y: Float
    public var buttons: ExtCastMouseButtons
    public var actionButton: UInt32
    public var hScroll: Float
    public var vScroll: Float

    public init(
        action: ExtCastMouseAction,
        x: Float,
        y: Float,
        buttons: ExtCastMouseButtons,
        actionButton: UInt32 = 0,
        hScroll: Float = 0,
        vScroll: Float = 0
    ) {
        self.action = action
        self.x = x
        self.y = y
        self.buttons = buttons
        self.actionButton = actionButton
        self.hScroll = hScroll
        self.vScroll = vScroll
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 25)
        writer.writeUInt8(action.rawValue)
        writer.writeFloat32(x)
        writer.writeFloat32(y)
        writer.writeUInt32(buttons.rawValue)
        writer.writeUInt32(actionButton)
        writer.writeFloat32(hScroll)
        writer.writeFloat32(vScroll)
        return writer.data
    }

    public static func decode(from data: Data) throws -> InputMousePayload {
        var reader = BinaryReader(data)
        let actionRaw = try reader.readUInt8()
        guard let action = ExtCastMouseAction(rawValue: actionRaw) else {
            throw ExtCastCodecError.invalidEnumValue(field: "mouseAction", rawValue: UInt32(actionRaw))
        }
        return InputMousePayload(
            action: action,
            x: try reader.readFloat32(),
            y: try reader.readFloat32(),
            buttons: ExtCastMouseButtons(rawValue: try reader.readUInt32()),
            actionButton: try reader.readUInt32(),
            hScroll: try reader.readFloat32(),
            vScroll: try reader.readFloat32()
        )
    }
}

public struct TouchPointer: Sendable, Equatable {
    public var pointerId: Int32
    public var x: Float
    public var y: Float
    public var pressure: Float

    public init(pointerId: Int32, x: Float, y: Float, pressure: Float = 1.0) {
        self.pointerId = pointerId
        self.x = x
        self.y = y
        self.pressure = pressure
    }
}

public struct InputTouchPayload: Sendable, Equatable {
    public var action: ExtCastTouchAction
    public var actionIndex: UInt8
    public var pointers: [TouchPointer]

    public init(action: ExtCastTouchAction, actionIndex: UInt8, pointers: [TouchPointer]) {
        self.action = action
        self.actionIndex = actionIndex
        self.pointers = pointers
    }

    public func encode() -> Data {
        let count = min(pointers.count, ExtCastProtocol.maxTouchPointers)
        var writer = BinaryWriter(capacity: 3 + count * 16)
        writer.writeUInt8(action.rawValue)
        writer.writeUInt8(actionIndex)
        writer.writeUInt8(UInt8(count))
        for pointer in pointers.prefix(count) {
            writer.writeInt32(pointer.pointerId)
            writer.writeFloat32(pointer.x)
            writer.writeFloat32(pointer.y)
            writer.writeFloat32(pointer.pressure)
        }
        return writer.data
    }

    public static func decode(from data: Data) throws -> InputTouchPayload {
        var reader = BinaryReader(data)
        let actionRaw = try reader.readUInt8()
        guard let action = ExtCastTouchAction(rawValue: actionRaw) else {
            throw ExtCastCodecError.invalidEnumValue(field: "touchAction", rawValue: UInt32(actionRaw))
        }
        let actionIndex = try reader.readUInt8()
        let pointerCount = Int(try reader.readUInt8())
        guard pointerCount <= ExtCastProtocol.maxTouchPointers else {
            throw ExtCastCodecError.invalidEnumValue(field: "touchPointerCount", rawValue: UInt32(pointerCount))
        }
        var pointers: [TouchPointer] = []
        pointers.reserveCapacity(pointerCount)
        for _ in 0..<pointerCount {
            pointers.append(TouchPointer(
                pointerId: try reader.readInt32(),
                x: try reader.readFloat32(),
                y: try reader.readFloat32(),
                pressure: try reader.readFloat32()
            ))
        }
        return InputTouchPayload(action: action, actionIndex: actionIndex, pointers: pointers)
    }
}

public struct InputKeyPayload: Sendable, Equatable {
    public var action: ExtCastKeyAction
    public var androidKeyCode: Int32
    public var metaState: Int32
    public var repeatCount: Int32

    public init(
        action: ExtCastKeyAction,
        androidKeyCode: Int32,
        metaState: Int32 = 0,
        repeatCount: Int32 = 0
    ) {
        self.action = action
        self.androidKeyCode = androidKeyCode
        self.metaState = metaState
        self.repeatCount = repeatCount
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 13)
        writer.writeUInt8(action.rawValue)
        writer.writeInt32(androidKeyCode)
        writer.writeInt32(metaState)
        writer.writeInt32(repeatCount)
        return writer.data
    }

    public static func decode(from data: Data) throws -> InputKeyPayload {
        var reader = BinaryReader(data)
        let actionRaw = try reader.readUInt8()
        guard let action = ExtCastKeyAction(rawValue: actionRaw) else {
            throw ExtCastCodecError.invalidEnumValue(field: "keyAction", rawValue: UInt32(actionRaw))
        }
        return InputKeyPayload(
            action: action,
            androidKeyCode: try reader.readInt32(),
            metaState: try reader.readInt32(),
            repeatCount: try reader.readInt32()
        )
    }
}

public struct InputTextPayload: Sendable, Equatable {
    public var mode: ExtCastInputTextMode
    public var seq: Int64
    public var text: String

    public init(mode: ExtCastInputTextMode, seq: Int64, text: String) {
        self.mode = mode
        self.seq = seq
        self.text = text
    }

    public func encode() -> Data {
        var writer = BinaryWriter(capacity: 9 + text.utf8.count)
        writer.writeUInt8(mode.rawValue)
        writer.writeInt64(seq)
        writer.writeUTF8(text)
        return writer.data
    }

    public static func decode(from data: Data) throws -> InputTextPayload {
        var reader = BinaryReader(data)
        let modeRaw = try reader.readUInt8()
        guard let mode = ExtCastInputTextMode(rawValue: modeRaw) else {
            throw ExtCastCodecError.invalidEnumValue(field: "inputTextMode", rawValue: UInt32(modeRaw))
        }
        let seq = try reader.readInt64()
        let text = try reader.readRemainingUTF8(field: "inputText.text")
        return InputTextPayload(mode: mode, seq: seq, text: text)
    }
}

/// Uplink `0x26 INPUT_TEXT` with `flags = 0x01` (`FLAG_CLIPBOARD_IMAGE_PNG`):
/// `uint8 mode (0=PASTE_IMAGE, 1=SET_CLIPBOARD_IMAGE_ONLY)` + `int64 seq` (8 bytes BE) + raw PNG bytes (<= 8 MiB).
public struct InputImagePayload: Sendable, Equatable {
    public var mode: ExtCastInputTextMode
    public var seq: Int64
    public var pngData: Data

    public init(mode: ExtCastInputTextMode = .setClipboardImageOnly, seq: Int64, pngData: Data) {
        self.mode = mode
        self.seq = seq
        self.pngData = pngData
    }

    public func encode() throws -> Data {
        guard pngData.count <= ExtCastProtocol.maxClipboardImageBytes else {
            throw ExtCastCodecError.payloadTooLarge(UInt32(clamping: pngData.count))
        }
        var writer = BinaryWriter(capacity: 9 + pngData.count)
        writer.writeUInt8(mode.rawValue)
        writer.writeInt64(seq)
        writer.writeBytes(pngData)
        return writer.data
    }

    public static func decode(from data: Data) throws -> InputImagePayload {
        var reader = BinaryReader(data)
        let modeRaw = try reader.readUInt8()
        guard let mode = ExtCastInputTextMode(rawValue: modeRaw) else {
            throw ExtCastCodecError.invalidEnumValue(field: "inputImageMode", rawValue: UInt32(modeRaw))
        }
        let seq = try reader.readInt64()
        let pngData = reader.readRemainingBytes()
        guard pngData.count <= ExtCastProtocol.maxClipboardImageBytes else {
            throw ExtCastCodecError.payloadTooLarge(UInt32(clamping: pngData.count))
        }
        return InputImagePayload(mode: mode, seq: seq, pngData: pngData)
    }
}

/// Uplink `0x2B WINDOW_ACTION` UTF-8 JSON payload covering adaptive single-window sizing,
/// app launching, freeform bounds, tiling topologies, DPI reload, return-to-desktop, and input backend selection.
public struct WindowActionPayload: Codable, Sendable, Equatable {
    public var action: String
    public var enabled: Bool?
    public var defaultWidthDp: Int?
    public var pkg: String?
    public var cls: String?
    public var widthPct: Int?
    public var remember: Bool?
    public var mode: String?
    public var slot: Int?
    public var taskId: Int?
    public var left: Int?
    public var top: Int?
    public var right: Int?
    public var bottom: Int?
    public var topology: String?
    public var pkgs: [String]?
    public var ratios: [Int]?
    public var backend: String?

    public init(
        action: String,
        enabled: Bool? = nil,
        defaultWidthDp: Int? = nil,
        pkg: String? = nil,
        cls: String? = nil,
        widthPct: Int? = nil,
        remember: Bool? = nil,
        mode: String? = nil,
        slot: Int? = nil,
        taskId: Int? = nil,
        left: Int? = nil,
        top: Int? = nil,
        right: Int? = nil,
        bottom: Int? = nil,
        topology: String? = nil,
        pkgs: [String]? = nil,
        ratios: [Int]? = nil,
        backend: String? = nil
    ) {
        self.action = action
        self.enabled = enabled
        self.defaultWidthDp = defaultWidthDp
        self.pkg = pkg
        self.cls = cls
        self.widthPct = widthPct
        self.remember = remember
        self.mode = mode
        self.slot = slot
        self.taskId = taskId
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
        self.topology = topology
        self.pkgs = pkgs
        self.ratios = ratios
        self.backend = backend
    }

    public static func setAdaptiveSingle(enabled: Bool, defaultWidthDp: Int) -> WindowActionPayload {
        WindowActionPayload(
            action: "set_adaptive_single",
            enabled: enabled,
            defaultWidthDp: min(max(defaultWidthDp, 320), 1200)
        )
    }

    public static func setSingleWidth(pkg: String, widthPct: Int, remember: Bool = true) -> WindowActionPayload {
        WindowActionPayload(
            action: "set_single_width",
            pkg: pkg,
            widthPct: min(max(widthPct, 28), 100),
            remember: remember
        )
    }

    public static func launchApp(
        pkg: String,
        cls: String = "",
        mode: ExtCastLaunchMode = .adaptive,
        slot: Int = 0
    ) -> WindowActionPayload {
        WindowActionPayload(
            action: "launch_app",
            pkg: pkg,
            cls: cls,
            mode: mode.rawValue,
            slot: slot
        )
    }

    public static func updateWindow(
        taskId: Int,
        pkg: String,
        mode: ExtCastLaunchMode,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        remember: Bool = true
    ) -> WindowActionPayload {
        WindowActionPayload(
            action: "update_window",
            pkg: pkg,
            remember: remember,
            mode: mode.rawValue,
            taskId: taskId,
            left: left,
            top: top,
            right: right,
            bottom: bottom
        )
    }

    public static func focusWindow(taskId: Int) -> WindowActionPayload {
        WindowActionPayload(action: "focus_window", taskId: taskId)
    }

    public static func closeWindow(taskId: Int) -> WindowActionPayload {
        WindowActionPayload(action: "close_window", taskId: taskId)
    }

    public static func minimizeWindow(taskId: Int) -> WindowActionPayload {
        WindowActionPayload(action: "minimize_window", taskId: taskId)
    }

    public static func setTiling(
        topology: ExtCastTilingTopology,
        pkgs: [String] = [],
        ratios: [Int]? = nil
    ) -> WindowActionPayload {
        WindowActionPayload(
            action: "set_tiling",
            topology: topology.rawValue,
            pkgs: pkgs,
            ratios: ratios ?? topology.defaultRatios
        )
    }

    public static func reloadAppDpi(pkg: String) -> WindowActionPayload {
        WindowActionPayload(action: "reload_app_dpi", pkg: pkg)
    }

    public static func returnToDesktop(pkg: String, taskId: Int = -1) -> WindowActionPayload {
        WindowActionPayload(action: "return_to_desktop", pkg: pkg, taskId: taskId)
    }

    public static func setInputBackend(_ backend: ExtCastInputBackend) -> WindowActionPayload {
        WindowActionPayload(action: "set_input_backend", backend: backend.rawValue)
    }

    public func encode() throws -> Data {
        try JSONEncoder().encode(self)
    }

    public static func decode(from data: Data) throws -> WindowActionPayload {
        do {
            return try JSONDecoder().decode(WindowActionPayload.self, from: data)
        } catch {
            throw ExtCastCodecError.invalidJSON("WINDOW_ACTION: \(error.localizedDescription)")
        }
    }
}

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

    public static func decode(from data: Data) throws -> SystemActionPayload {
        var reader = BinaryReader(data)
        let actionRaw = try reader.readUInt8()
        guard let action = ExtCastSystemActionKind(rawValue: actionRaw) else {
            throw ExtCastCodecError.invalidEnumValue(field: "systemAction", rawValue: UInt32(actionRaw))
        }
        let arg = try reader.readInt32()
        return SystemActionPayload(action: action, arg: arg)
    }
}

public struct FileTransferChunkPayload: Sendable, Equatable {
    public var transferId: UInt32
    public var chunkFlags: ExtCastFileChunkFlags
    public var fileName: String?
    public var totalSize: Int64?
    public var data: Data

    public init(
        transferId: UInt32,
        chunkFlags: ExtCastFileChunkFlags,
        fileName: String? = nil,
        totalSize: Int64? = nil,
        data: Data = Data()
    ) {
        self.transferId = transferId
        self.chunkFlags = chunkFlags
        self.fileName = fileName
        self.totalSize = totalSize
        self.data = data
    }

    public func encode() -> Data {
        let nameBytes = fileName.map { Data($0.utf8) } ?? Data()
        let clampedNameLength = min(nameBytes.count, Int(UInt16.max))
        let startHeaderBytes = chunkFlags.contains(.start) ? (2 + clampedNameLength + 8) : 0
        var writer = BinaryWriter(capacity: 5 + startHeaderBytes + data.count)
        writer.writeUInt32(transferId)
        writer.writeUInt8(chunkFlags.rawValue)
        if chunkFlags.contains(.start) {
            writer.writeUInt16(UInt16(clampedNameLength))
            writer.writeBytes(nameBytes.prefix(clampedNameLength))
            writer.writeInt64(totalSize ?? 0)
        }
        if chunkFlags.contains(.data) && !data.isEmpty {
            writer.writeBytes(data)
        }
        return writer.data
    }

    public static func decode(from raw: Data) throws -> FileTransferChunkPayload {
        var reader = BinaryReader(raw)
        let transferId = try reader.readUInt32()
        let chunkFlags = ExtCastFileChunkFlags(rawValue: try reader.readUInt8())
        var fileName: String?
        var totalSize: Int64?
        if chunkFlags.contains(.start) {
            let nameLen = Int(try reader.readUInt16())
            let nameData = try reader.readBytes(count: nameLen)
            guard let decodedName = String(data: nameData, encoding: .utf8) else {
                throw ExtCastCodecError.invalidUTF8(field: "fileTransferChunk.fileName")
            }
            fileName = decodedName
            totalSize = try reader.readInt64()
        }
        let chunkData = chunkFlags.contains(.data) ? reader.readRemainingBytes() : Data()
        return FileTransferChunkPayload(
            transferId: transferId,
            chunkFlags: chunkFlags,
            fileName: fileName,
            totalSize: totalSize,
            data: chunkData
        )
    }
}

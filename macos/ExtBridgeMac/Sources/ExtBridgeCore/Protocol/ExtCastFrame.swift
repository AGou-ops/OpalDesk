import Foundation

/// A single ExtCast v1 wire frame with fixed 18-byte header and variable payload.
public struct ExtCastFrame: Sendable, Equatable {
    public var type: ExtCastFrameType
    public var flags: UInt8
    public var timestampUs: Int64
    public var payload: Data

    public init(
        type: ExtCastFrameType,
        flags: UInt8 = 0,
        timestampUs: Int64 = ExtCastFrame.currentTimestampUs(),
        payload: Data = Data()
    ) {
        self.type = type
        self.flags = flags
        self.timestampUs = timestampUs
        self.payload = payload
    }

    public static func currentTimestampUs() -> Int64 {
        Int64(DispatchTime.now().uptimeNanoseconds / 1_000)
    }

    /// Serializes this frame into the exact 18-byte Big-Endian header + payload format.
    public func encode() throws -> Data {
        guard payload.count <= Int(ExtCastProtocol.maxPayloadLength) else {
            throw ExtCastCodecError.payloadTooLarge(UInt32(clamping: payload.count))
        }
        var writer = BinaryWriter(capacity: ExtCastProtocol.headerSize + payload.count)
        for byte in ExtCastProtocol.magicBytes {
            writer.writeUInt8(byte)
        }
        writer.writeUInt8(type.rawValue)
        writer.writeUInt8(flags)
        writer.writeUInt32(UInt32(payload.count))
        writer.writeInt64(timestampUs)
        writer.writeBytes(payload)
        return writer.data
    }

    // MARK: - Convenience Frame Builders

    public static func hello(_ payload: HelloPayload) throws -> ExtCastFrame {
        ExtCastFrame(type: .hello, payload: try payload.encode())
    }

    public static func resizeConfig(_ payload: ResizeConfigPayload) -> ExtCastFrame {
        ExtCastFrame(type: .resizeConfig, payload: payload.encode())
    }

    public static func requestIdr() -> ExtCastFrame {
        ExtCastFrame(type: .requestIdr)
    }

    public static func inputMouse(_ payload: InputMousePayload) -> ExtCastFrame {
        ExtCastFrame(type: .inputMouse, payload: payload.encode())
    }

    public static func inputTouch(_ payload: InputTouchPayload) -> ExtCastFrame {
        ExtCastFrame(type: .inputTouch, payload: payload.encode())
    }

    public static func inputKey(_ payload: InputKeyPayload) -> ExtCastFrame {
        ExtCastFrame(type: .inputKey, payload: payload.encode())
    }

    public static func inputText(_ payload: InputTextPayload) -> ExtCastFrame {
        ExtCastFrame(type: .inputText, payload: payload.encode())
    }

    public static func inputImage(_ payload: InputImagePayload) throws -> ExtCastFrame {
        ExtCastFrame(
            type: .inputText,
            flags: ExtCastProtocol.flagClipboardImagePng,
            payload: try payload.encode()
        )
    }

    public static func clipboardImagePush(_ payload: ClipboardImagePushPayload) throws -> ExtCastFrame {
        ExtCastFrame(
            type: .clipboardPush,
            flags: ExtCastProtocol.flagClipboardImagePng,
            payload: try payload.encode()
        )
    }

    public static func systemAction(_ payload: SystemActionPayload) -> ExtCastFrame {
        ExtCastFrame(type: .systemAction, payload: payload.encode())
    }

    public static func fileTransferChunk(_ payload: FileTransferChunkPayload) -> ExtCastFrame {
        ExtCastFrame(type: .fileTransferChunk, payload: payload.encode())
    }

    public static func requestWifiUpgrade() -> ExtCastFrame {
        ExtCastFrame(type: .requestWifiUpgrade)
    }

    public static func listApps() -> ExtCastFrame {
        ExtCastFrame(type: .listApps)
    }

    public static func windowAction(_ payload: WindowActionPayload) throws -> ExtCastFrame {
        ExtCastFrame(type: .windowAction, payload: try payload.encode())
    }

    public static func wifiUpgradeReady(_ payload: WifiUpgradeReadyPayload) throws -> ExtCastFrame {
        ExtCastFrame(type: .wifiUpgradeReady, payload: try payload.encode())
    }

    public static func appList(_ payload: AppListPayload) throws -> ExtCastFrame {
        ExtCastFrame(type: .appList, payload: try payload.encode())
    }

    public static func ping(_ pingId: UInt64) -> ExtCastFrame {
        ExtCastFrame(type: .ping, payload: PingPongPayload(pingId: pingId).encode())
    }
}

/// Streaming ring-buffered decoder for TCP byte streams that handles arbitrary
/// packet fragmentation (half-packets) and coalescing (sticky packets).
public struct ExtCastStreamDecoder: Sendable {
    private var storage: [UInt8]
    private var readIndex: Int = 0
    private var writeIndex: Int = 0

    public var bufferedByteCount: Int {
        writeIndex - readIndex
    }

    public init(initialCapacity: Int = 256 * 1024) {
        self.storage = []
        self.storage.reserveCapacity(max(initialCapacity, ExtCastProtocol.headerSize))
    }

    public mutating func reset() {
        storage.removeAll(keepingCapacity: true)
        readIndex = 0
        writeIndex = 0
    }

    public mutating func append(_ chunk: Data) {
        guard !chunk.isEmpty else { return }
        compactIfNeeded(incomingCount: chunk.count)
        let start = writeIndex
        let newEnd = start + chunk.count
        if newEnd > storage.count {
            storage.append(contentsOf: repeatElement(0, count: newEnd - storage.count))
        }
        storage.withUnsafeMutableBufferPointer { buffer in
            guard let baseAddress = buffer.baseAddress else { return }
            let destination = UnsafeMutableRawBufferPointer(
                start: baseAddress.advanced(by: start),
                count: chunk.count
            )
            chunk.copyBytes(to: destination)
        }
        writeIndex = newEnd
    }

    /// Extracts all complete frames currently buffered.
    /// Throws immediately on corrupted magic, unknown frame type, or oversized payload.
    public mutating func decodeAvailableFrames() throws -> [ExtCastFrame] {
        var frames: [ExtCastFrame] = []
        while let frame = try decodeNextFrame() {
            frames.append(frame)
        }
        return frames
    }

    /// Decodes a single complete frame if available; returns nil when waiting for more bytes.
    public mutating func decodeNextFrame() throws -> ExtCastFrame? {
        guard bufferedByteCount >= ExtCastProtocol.headerSize else {
            return nil
        }

        let base = readIndex
        let magic = ExtCastProtocol.magicBytes
        if storage[base] != magic[0]
            || storage[base + 1] != magic[1]
            || storage[base + 2] != magic[2]
            || storage[base + 3] != magic[3]
        {
            let bad = Array(storage[base..<(base + 4)])
            reset()
            throw ExtCastCodecError.invalidMagic(bad)
        }

        let rawType = storage[base + 4]
        guard let frameType = ExtCastFrameType(rawValue: rawType) else {
            reset()
            throw ExtCastCodecError.unknownFrameType(rawType)
        }

        let flags = storage[base + 5]
        let payloadLen =
            (UInt32(storage[base + 6]) << 24)
            | (UInt32(storage[base + 7]) << 16)
            | (UInt32(storage[base + 8]) << 8)
            | UInt32(storage[base + 9])

        if payloadLen > ExtCastProtocol.maxPayloadLength {
            reset()
            throw ExtCastCodecError.payloadTooLarge(payloadLen)
        }

        let totalFrameLen = ExtCastProtocol.headerSize + Int(payloadLen)
        guard bufferedByteCount >= totalFrameLen else {
            return nil
        }

        let tsHigh =
            (UInt64(storage[base + 10]) << 24)
            | (UInt64(storage[base + 11]) << 16)
            | (UInt64(storage[base + 12]) << 8)
            | UInt64(storage[base + 13])
        let tsLow =
            (UInt64(storage[base + 14]) << 24)
            | (UInt64(storage[base + 15]) << 16)
            | (UInt64(storage[base + 16]) << 8)
            | UInt64(storage[base + 17])
        let timestampUs = Int64(bitPattern: (tsHigh << 32) | tsLow)

        let payloadStart = base + ExtCastProtocol.headerSize
        let payloadEnd = payloadStart + Int(payloadLen)
        let payload = payloadLen == 0
            ? Data()
            : Data(storage[payloadStart..<payloadEnd])

        readIndex = payloadEnd
        if readIndex == writeIndex {
            readIndex = 0
            writeIndex = 0
        }

        return ExtCastFrame(
            type: frameType,
            flags: flags,
            timestampUs: timestampUs,
            payload: payload
        )
    }

    private mutating func compactIfNeeded(incomingCount: Int) {
        if readIndex == writeIndex {
            readIndex = 0
            writeIndex = 0
            return
        }
        // Compact once consumed prefix exceeds 64 KiB and at least half of written span.
        let compactionThreshold = 64 * 1024
        if readIndex >= compactionThreshold && readIndex * 2 >= writeIndex {
            let unread = writeIndex - readIndex
            storage.replaceSubrange(0..<unread, with: storage[readIndex..<writeIndex])
            readIndex = 0
            writeIndex = unread
            return
        }
        // Also compact if appending would force growth while dead head space exists.
        if readIndex > 0 && writeIndex + incomingCount > storage.count {
            let unread = writeIndex - readIndex
            storage.replaceSubrange(0..<unread, with: storage[readIndex..<writeIndex])
            readIndex = 0
            writeIndex = unread
        }
    }
}

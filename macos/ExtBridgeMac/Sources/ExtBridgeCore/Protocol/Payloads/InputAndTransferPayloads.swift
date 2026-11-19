import Foundation

// MARK: - Uplink Input Payloads (Mac -> Android)

/// Uplink `0x23 INPUT_MOUSE` fixed 25-byte Big-Endian payload.
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

/// Uplink `0x24 INPUT_TOUCH` multi-touch pointer payload.
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
}

/// Uplink `0x25 INPUT_KEY` fixed 13-byte Big-Endian payload.
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
}

/// Uplink `0x26 INPUT_TEXT` (`flags = 0`) text commit / clipboard push payload.
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
}

/// Uplink `0x26 INPUT_TEXT` with `flags = 0x01` (`FLAG_CLIPBOARD_IMAGE_PNG`):
/// `uint8 mode` + `int64 seq` (8 bytes BE) + raw PNG bytes (<= 8 MiB).
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
}

// MARK: - Downlink Clipboard Payloads (Android -> Mac)

/// Downlink `0x11 CLIPBOARD_PUSH` (`flags = 0`) text payload.
public struct ClipboardPushPayload: Sendable, Equatable {
    public var seq: Int64
    public var text: String

    public init(seq: Int64, text: String) {
        self.seq = seq
        self.text = text
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

// MARK: - File Transfer Payloads

/// Uplink `0x28 FILE_TRANSFER_CHUNK` streaming file transfer payload.
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

    public func encode() throws -> Data {
        let nameBytes = fileName.map { Data($0.utf8) } ?? Data()
        if chunkFlags.contains(.start), nameBytes.count > ExtCastProtocol.maxFileNameUTF8Bytes {
            throw ExtCastCodecError.fieldTooLong(
                field: "fileTransferChunk.fileName",
                length: nameBytes.count,
                maxLength: ExtCastProtocol.maxFileNameUTF8Bytes
            )
        }
        let startHeaderBytes = chunkFlags.contains(.start) ? (2 + nameBytes.count + 8) : 0
        var writer = BinaryWriter(capacity: 5 + startHeaderBytes + data.count)
        writer.writeUInt32(transferId)
        writer.writeUInt8(chunkFlags.rawValue)
        if chunkFlags.contains(.start) {
            writer.writeUInt16(UInt16(nameBytes.count))
            writer.writeBytes(nameBytes)
            writer.writeInt64(totalSize ?? 0)
        }
        if chunkFlags.contains(.data) && !data.isEmpty {
            writer.writeBytes(data)
        }
        return writer.data
    }
}

/// Downlink `0x13 FILE_TRANSFER_ACK` completion/failure acknowledgement payload.
public struct FileTransferAckPayload: Sendable, Equatable {
    public var transferId: UInt32
    public var status: ExtCastFileAckStatus
    public var pathOrError: String

    public init(transferId: UInt32, status: ExtCastFileAckStatus, pathOrError: String) {
        self.transferId = transferId
        self.status = status
        self.pathOrError = pathOrError
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

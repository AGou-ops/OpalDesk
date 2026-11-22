import Foundation
import os

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
            throw OpalCastCodecError.truncatedPayload(
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
            throw OpalCastCodecError.invalidUTF8(field: field)
        }
        return str
    }
}

// MARK: - Shared JSON Codec

/// Thread-safe shared `JSONEncoder`/`JSONDecoder` for OpalCast JSON control payloads.
enum OpalCastJSONCodec {
    private static let encoderLock = OSAllocatedUnfairLock(uncheckedState: JSONEncoder())
    private static let decoderLock = OSAllocatedUnfairLock(uncheckedState: JSONDecoder())

    static func encode<T: Encodable>(_ value: T, context: String) throws -> Data {
        do {
            return try encoderLock.withLockUnchecked { try $0.encode(value) }
        } catch {
            throw OpalCastCodecError.invalidJSON("\(context): \(error.localizedDescription)")
        }
    }

    static func decode<T: Decodable>(_ type: T.Type, from data: Data, context: String) throws -> T {
        do {
            return try decoderLock.withLockUnchecked { try $0.decode(type, from: data) }
        } catch {
            throw OpalCastCodecError.invalidJSON("\(context): \(error.localizedDescription)")
        }
    }
}

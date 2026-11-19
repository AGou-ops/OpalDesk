import CoreMedia
import Foundation

/// A single NAL unit stripped of its Annex-B start code (`00 00 01` or `00 00 00 01`).
public struct AnnexBNALUnit: Sendable, Equatable {
    public let data: Data
    public let nalType: UInt8

    public init(data: Data, nalType: UInt8) {
        self.data = data
        self.nalType = nalType
    }
}

public struct ExtractedParameterSets: Sendable, Equatable {
    public var vps: [Data] = []
    public var sps: [Data] = []
    public var pps: [Data] = []

    public init(vps: [Data] = [], sps: [Data] = [], pps: [Data] = []) {
        self.vps = vps
        self.sps = sps
        self.pps = pps
    }

    public func isComplete(for codec: ExtCastVideoCodec) -> Bool {
        let validSPS = !sps.isEmpty && sps.allSatisfy { !$0.isEmpty }
        let validPPS = !pps.isEmpty && pps.allSatisfy { !$0.isEmpty }
        switch codec {
        case .h264:
            return validSPS && validPPS
        case .hevc:
            let validVPS = !vps.isEmpty && vps.allSatisfy { !$0.isEmpty }
            return validVPS && validSPS && validPPS
        }
    }
}

public enum AnnexBError: Error, Sendable, Equatable, LocalizedError {
    case incompleteParameterSets(ExtCastVideoCodec)
    case formatDescriptionCreationFailed(OSStatus)

    public var errorDescription: String? {
        switch self {
        case .incompleteParameterSets(let codec):
            return "Incomplete \(codec.displayName) parameter sets for CMVideoFormatDescription"
        case .formatDescriptionCreationFailed(let status):
            return "Failed to create CMVideoFormatDescription (OSStatus: \(status))"
        }
    }
}

/// Parses Android MediaCodec Annex-B H.264 / HEVC byte streams into NAL units,
/// extracts SPS/PPS/VPS parameter sets, builds `CMVideoFormatDescription`,
/// and converts VCL slices into 4-byte Big-Endian AVCC/HVCC length-prefixed buffers.
public enum AnnexBParser {
    // H.264 NAL unit types (5-bit header: byte0 & 0x1F)
    public static let h264IDR: UInt8 = 5
    public static let h264SPS: UInt8 = 7
    public static let h264PPS: UInt8 = 8
    public static let h264AUD: UInt8 = 9

    // HEVC NAL unit types (6-bit header: (byte0 >> 1) & 0x3F)
    public static let hevcIDRW: UInt8 = 19
    public static let hevcIDRN: UInt8 = 20
    public static let hevcCRA: UInt8 = 21
    public static let hevcVPS: UInt8 = 32
    public static let hevcSPS: UInt8 = 33
    public static let hevcPPS: UInt8 = 34
    public static let hevcAUD: UInt8 = 35

    /// Splits an Annex-B byte buffer into individual NAL units (supporting both 3-byte and 4-byte start codes)
    /// using a zero-copy raw buffer scan to minimize per-frame allocations at 60/120fps.
    public static func splitNALUnits(from data: Data, codec: ExtCastVideoCodec) -> [AnnexBNALUnit] {
        let count = data.count
        guard count >= 4 else { return [] }

        return data.withUnsafeBytes { rawBuf -> [AnnexBNALUnit] in
            guard let base = rawBuf.bindMemory(to: UInt8.self).baseAddress else { return [] }
            var units: [AnnexBNALUnit] = []
            units.reserveCapacity(6)
            var cursor = 0
            var nalStart: Int?

            while cursor < count {
                // Fast-path skip: a start code always starts with 0x00 and has 0x01 at +2 or +3.
                if base[cursor] == 0 {
                    let rem = count - cursor
                    var startCodeLen = 0
                    if rem >= 4 && base[cursor + 1] == 0 && base[cursor + 2] == 0 && base[cursor + 3] == 1 {
                        startCodeLen = 4
                    } else if rem >= 3 && base[cursor + 1] == 0 && base[cursor + 2] == 1 {
                        startCodeLen = 3
                    }
                    if startCodeLen > 0 {
                        if let currentStart = nalStart, currentStart < cursor {
                            appendNAL(base: base, start: currentStart, endExclusive: cursor, codec: codec, into: &units)
                        }
                        cursor += startCodeLen
                        nalStart = cursor
                        continue
                    }
                }
                cursor += 1
            }

            if let currentStart = nalStart, currentStart < count {
                appendNAL(base: base, start: currentStart, endExclusive: count, codec: codec, into: &units)
            }

            return units
        }
    }

    public static func extractParameterSets(
        from units: [AnnexBNALUnit],
        codec: ExtCastVideoCodec
    ) -> ExtractedParameterSets {
        var result = ExtractedParameterSets()
        for unit in units {
            switch codec {
            case .h264:
                if unit.nalType == h264SPS {
                    result.sps.append(unit.data)
                } else if unit.nalType == h264PPS {
                    result.pps.append(unit.data)
                }
            case .hevc:
                if unit.nalType == hevcVPS {
                    result.vps.append(unit.data)
                } else if unit.nalType == hevcSPS {
                    result.sps.append(unit.data)
                } else if unit.nalType == hevcPPS {
                    result.pps.append(unit.data)
                }
            }
        }
        return result
    }

    public static func isParameterSetOrDelimiter(_ unit: AnnexBNALUnit, codec: ExtCastVideoCodec) -> Bool {
        switch codec {
        case .h264:
            return unit.nalType == h264SPS || unit.nalType == h264PPS || unit.nalType == h264AUD
        case .hevc:
            return unit.nalType == hevcVPS || unit.nalType == hevcSPS || unit.nalType == hevcPPS || unit.nalType == hevcAUD
        }
    }

    public static func isKeyframeNAL(_ unit: AnnexBNALUnit, codec: ExtCastVideoCodec) -> Bool {
        switch codec {
        case .h264:
            return unit.nalType == h264IDR
        case .hevc:
            return unit.nalType == hevcIDRW || unit.nalType == hevcIDRN || unit.nalType == hevcCRA
        }
    }

    /// Converts VCL NAL units into AVCC/HVCC 4-byte Big-Endian length-prefixed format ready for VideoToolbox.
    /// Parameter-set NALs (VPS/SPS/PPS) and AUDs are stripped because VideoToolbox reads them from `CMVideoFormatDescription`.
    public static func convertToLengthPrefixed(
        units: [AnnexBNALUnit],
        codec: ExtCastVideoCodec
    ) -> (data: Data, containsKeyframe: Bool) {
        var capacity = 0
        for unit in units where !isParameterSetOrDelimiter(unit, codec: codec) {
            capacity += 4 + unit.data.count
        }
        var output = Data(capacity: capacity)
        var hasKeyframe = false

        for unit in units {
            if isParameterSetOrDelimiter(unit, codec: codec) {
                continue
            }
            if isKeyframeNAL(unit, codec: codec) {
                hasKeyframe = true
            }
            var lengthBE = UInt32(unit.data.count).bigEndian
            withUnsafeBytes(of: &lengthBE) { output.append(contentsOf: $0) }
            output.append(unit.data)
        }
        return (output, hasKeyframe)
    }

    /// Builds a `CMVideoFormatDescription` from extracted H.264 (SPS+PPS) or HEVC (VPS+SPS+PPS) parameter sets.
    public static func makeFormatDescription(
        from parameterSets: ExtractedParameterSets,
        codec: ExtCastVideoCodec
    ) throws -> CMVideoFormatDescription {
        guard parameterSets.isComplete(for: codec) else {
            throw AnnexBError.incompleteParameterSets(codec)
        }

        let orderedSets: [Data]
        switch codec {
        case .h264:
            orderedSets = parameterSets.sps + parameterSets.pps
        case .hevc:
            orderedSets = parameterSets.vps + parameterSets.sps + parameterSets.pps
        }
        guard orderedSets.allSatisfy({ !$0.isEmpty }) else {
            throw AnnexBError.incompleteParameterSets(codec)
        }

        let sizes = orderedSets.map { $0.count }
        var formatDescription: CMVideoFormatDescription?

        let status: OSStatus = try withUnsafeBytePointers(to: orderedSets, codec: codec) { pointers in
            pointers.withUnsafeBufferPointer { ptrBuf in
                sizes.withUnsafeBufferPointer { sizeBuf in
                    guard let ptrBase = ptrBuf.baseAddress, let sizeBase = sizeBuf.baseAddress else {
                        return kCMFormatDescriptionError_InvalidParameter
                    }
                    switch codec {
                    case .h264:
                        return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: orderedSets.count,
                            parameterSetPointers: ptrBase,
                            parameterSetSizes: sizeBase,
                            nalUnitHeaderLength: 4,
                            formatDescriptionOut: &formatDescription
                        )
                    case .hevc:
                        return CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: orderedSets.count,
                            parameterSetPointers: ptrBase,
                            parameterSetSizes: sizeBase,
                            nalUnitHeaderLength: 4,
                            extensions: nil,
                            formatDescriptionOut: &formatDescription
                        )
                    }
                }
            }
        }

        guard status == noErr, let created = formatDescription else {
            throw AnnexBError.formatDescriptionCreationFailed(status)
        }
        return created
    }

    // MARK: - Private Helpers

    private static func appendNAL(
        base: UnsafePointer<UInt8>,
        start: Int,
        endExclusive: Int,
        codec: ExtCastVideoCodec,
        into units: inout [AnnexBNALUnit]
    ) {
        // Trim any trailing zero padding immediately preceding the next 4-byte start code.
        var end = endExclusive
        while end > start && base[end - 1] == 0 {
            end -= 1
        }
        guard end > start else { return }

        let headerByte = base[start]
        let nalType: UInt8
        switch codec {
        case .h264:
            nalType = headerByte & 0x1F
        case .hevc:
            nalType = (headerByte >> 1) & 0x3F
        }
        units.append(AnnexBNALUnit(data: Data(bytes: base.advanced(by: start), count: end - start), nalType: nalType))
    }

    /// Recursively pins each non-empty `Data` buffer so every base pointer remains valid for the duration of `body`.
    private static func withUnsafeBytePointers<R>(
        to buffers: [Data],
        codec: ExtCastVideoCodec,
        index: Int = 0,
        accumulated: [UnsafePointer<UInt8>] = [],
        _ body: ([UnsafePointer<UInt8>]) -> R
    ) throws -> R {
        if index == buffers.count {
            return body(accumulated)
        }
        return try buffers[index].withUnsafeBytes { rawBuffer in
            guard let ptr = rawBuffer.bindMemory(to: UInt8.self).baseAddress else {
                throw AnnexBError.incompleteParameterSets(codec)
            }
            var next = accumulated
            next.append(ptr)
            return try withUnsafeBytePointers(to: buffers, codec: codec, index: index + 1, accumulated: next, body)
        }
    }
}

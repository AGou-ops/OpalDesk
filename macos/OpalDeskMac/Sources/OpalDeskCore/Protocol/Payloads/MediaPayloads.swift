import Foundation

/// Downlink `0x01 VIDEO_CONFIG` payload carrying codec metadata and Annex-B CSD.
public struct VideoConfigPayload: Sendable, Equatable {
    public var codec: OpalCastVideoCodec
    public var width: UInt16
    public var height: UInt16
    public var dpi: UInt16
    public var rotation: UInt8
    public var fpsX100: UInt16
    public var displayMode: OpalCastDisplayMode
    public var csd: Data

    public init(
        codec: OpalCastVideoCodec,
        width: UInt16,
        height: UInt16,
        dpi: UInt16,
        rotation: UInt8,
        fpsX100: UInt16,
        displayMode: OpalCastDisplayMode,
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

    public static func decode(from data: Data) throws -> VideoConfigPayload {
        var reader = BinaryReader(data)
        let codecRaw = try reader.readUInt8()
        guard let codec = OpalCastVideoCodec(rawValue: codecRaw) else {
            throw OpalCastCodecError.invalidEnumValue(field: "videoCodec", rawValue: UInt32(codecRaw))
        }
        let width = try reader.readUInt16()
        let height = try reader.readUInt16()
        let dpi = try reader.readUInt16()
        let rotation = try reader.readUInt8()
        let fpsX100 = try reader.readUInt16()
        let displayModeRaw = try reader.readUInt8()
        guard let displayMode = OpalCastDisplayMode(rawValue: displayModeRaw) else {
            throw OpalCastCodecError.invalidEnumValue(field: "displayMode", rawValue: UInt32(displayModeRaw))
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

/// Downlink `0x03 AUDIO_CONFIG` payload carrying codec ID, sample rate, channels, and optional ASC.
public struct AudioConfigPayload: Sendable, Equatable {
    public var codec: OpalCastAudioCodec
    public var sampleRate: UInt32
    public var channels: UInt8
    public var codecSpecificData: Data

    public init(
        codec: OpalCastAudioCodec,
        sampleRate: UInt32,
        channels: UInt8,
        codecSpecificData: Data
    ) {
        self.codec = codec
        self.sampleRate = sampleRate
        self.channels = channels
        self.codecSpecificData = codecSpecificData
    }

    public static func decode(from data: Data) throws -> AudioConfigPayload {
        var reader = BinaryReader(data)
        let codecRaw = try reader.readUInt8()
        guard let codec = OpalCastAudioCodec(rawValue: codecRaw) else {
            throw OpalCastCodecError.invalidEnumValue(field: "audioCodec", rawValue: UInt32(codecRaw))
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

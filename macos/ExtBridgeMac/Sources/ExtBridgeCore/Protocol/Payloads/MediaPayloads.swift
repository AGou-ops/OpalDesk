import Foundation

/// Downlink `0x01 VIDEO_CONFIG` payload carrying codec metadata and Annex-B CSD.
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

/// Downlink `0x03 AUDIO_CONFIG` payload carrying codec ID, sample rate, channels, and optional ASC.
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

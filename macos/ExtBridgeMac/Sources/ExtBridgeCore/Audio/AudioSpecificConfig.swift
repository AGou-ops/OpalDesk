import Foundation

/// Parsed parameters from a 2-byte MPEG-4 AudioSpecificConfig (ISO/IEC 14496-3).
public struct AudioSpecificConfigInfo: Sendable, Equatable {
    public let audioObjectType: UInt8
    public let sampleRate: UInt32
    public let channels: UInt8

    private static let sampleRateTable: [UInt32] = [
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350
    ]

    public static func parse(_ data: Data) -> AudioSpecificConfigInfo? {
        guard data.count >= 2 else { return nil }
        let b0 = data[data.startIndex]
        let b1 = data[data.startIndex + 1]
        let objectType = (b0 >> 3) & 0x1F
        let freqIndex = Int(((b0 & 0x07) << 1) | ((b1 >> 7) & 0x01))
        let channelConfig = (b1 >> 3) & 0x0F

        guard freqIndex >= 0 && freqIndex < sampleRateTable.count else { return nil }
        let sr = sampleRateTable[freqIndex]
        let ch: UInt8 = channelConfig == 7 ? 8 : max(1, channelConfig)
        return AudioSpecificConfigInfo(audioObjectType: objectType, sampleRate: sr, channels: ch)
    }

    /// Wraps raw AudioSpecificConfig bytes inside an MPEG-4 ES_Descriptor (`esds` payload)
    /// suitable for CoreAudio `kAudioFormatMPEG4AAC` magicCookie.
    public static func wrapInESDS(_ asc: Data) -> Data {
        guard !asc.isEmpty && asc.count <= 127 else { return Data() }
        // DecoderSpecificInfo (tag 0x05)
        var dsi: [UInt8] = [0x05, UInt8(asc.count)]
        dsi.append(contentsOf: asc)

        // DecoderConfigDescriptor (tag 0x04):
        // objectTypeIndication=0x40 (MPEG-4 Audio), streamType=0x15 (Audio upstream=0 reserved=1),
        // bufferSizeDB=0x001800, maxBitrate=256000, avgBitrate=128000
        var dcdBody: [UInt8] = [
            0x40,
            0x15,
            0x00, 0x18, 0x00,
            0x00, 0x03, 0xE8, 0x00,
            0x00, 0x01, 0xF4, 0x00
        ]
        dcdBody.append(contentsOf: dsi)
        var dcd: [UInt8] = [0x04, UInt8(dcdBody.count)]
        dcd.append(contentsOf: dcdBody)

        // SLConfigDescriptor (tag 0x06): predefined=2
        let slc: [UInt8] = [0x06, 0x01, 0x02]

        // ES_Descriptor (tag 0x03): ES_ID=0, flags=0
        var esdBody: [UInt8] = [0x00, 0x00, 0x00]
        esdBody.append(contentsOf: dcd)
        esdBody.append(contentsOf: slc)
        var esds: [UInt8] = [0x03, UInt8(esdBody.count)]
        esds.append(contentsOf: esdBody)
        return Data(esds)
    }
}

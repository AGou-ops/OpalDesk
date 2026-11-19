import AVFoundation
import AudioToolbox
import Foundation
import os

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
        // bufferSizeDB=0, maxBitrate=256000, avgBitrate=128000
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

/// Real-time low-latency audio decoder and playback engine backed by `AVAudioEngine` + `AVAudioConverter`.
/// Decodes Android ExtCast AAC-LC (48kHz stereo), Opus, or PCM-16 audio streams with mute support
/// and bounded jitter-buffer overflow protection.
public final class AudioStreamPlayer: @unchecked Sendable {
    private static let maxScheduledBuffers = 8

    private struct AtomicMetrics: Sendable {
        var isMuted: Bool = false
        var pendingBufferCount: Int = 0
        var droppedPackets: UInt64 = 0
        var decodedPackets: UInt64 = 0
    }

    private let audioQueue = DispatchQueue(label: "com.extbridge.mac.audio-decode", qos: .userInteractive)
    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let metrics = OSAllocatedUnfairLock(initialState: AtomicMetrics())

    // Queue-confined properties (accessed exclusively on `audioQueue`).
    private var isConfigured: Bool = false
    private var currentConfig: AudioConfigPayload?
    private var inputFormat: AVAudioFormat?
    private var outputFormat: AVAudioFormat?
    private var converter: AVAudioConverter?

    public init() {
        engine.attach(playerNode)
    }

    deinit {
        stop()
    }

    public var isMuted: Bool {
        metrics.withLock { $0.isMuted }
    }

    public func setMuted(_ muted: Bool) {
        metrics.withLock { $0.isMuted = muted }
        audioQueue.async { [weak self] in
            guard let self else { return }
            self.playerNode.volume = muted ? 0.0 : 1.0
        }
    }

    public func configure(_ config: AudioConfigPayload) {
        audioQueue.async { [weak self] in
            self?.configureOnQueue(config)
        }
    }

    public func enqueuePacket(_ payload: Data) {
        guard !payload.isEmpty else { return }
        audioQueue.async { [weak self] in
            self?.decodeAndScheduleOnQueue(payload)
        }
    }

    public func stop() {
        audioQueue.sync {
            playerNode.stop()
            if engine.isRunning {
                engine.stop()
            }
            isConfigured = false
            converter = nil
            inputFormat = nil
            outputFormat = nil
            metrics.withLock { $0.pendingBufferCount = 0 }
        }
    }

    /// Standalone synchronous decode helper (also used by unit tests to verify AAC-LC / PCM-16 decoding without opening audio hardware).
    public static func makeConverter(
        for config: AudioConfigPayload
    ) -> (inputFormat: AVAudioFormat, outputFormat: AVAudioFormat, converter: AVAudioConverter)? {
        let ascInfo = config.codec == .aacLC ? AudioSpecificConfigInfo.parse(config.codecSpecificData) : nil
        let sampleRate = Double(ascInfo?.sampleRate ?? max(config.sampleRate, 8000))
        let channels = AVAudioChannelCount(ascInfo?.channels ?? max(config.channels, 1))

        guard let outputFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: channels,
            interleaved: false
        ) else {
            return nil
        }

        let inputFormat: AVAudioFormat?
        switch config.codec {
        case .aacLC:
            var asbd = AudioStreamBasicDescription(
                mSampleRate: sampleRate,
                mFormatID: kAudioFormatMPEG4AAC,
                mFormatFlags: UInt32(MPEG4ObjectID.AAC_LC.rawValue),
                mBytesPerPacket: 0,
                mFramesPerPacket: 1024,
                mBytesPerFrame: 0,
                mChannelsPerFrame: channels,
                mBitsPerChannel: 0,
                mReserved: 0
            )
            let fmt = AVAudioFormat(streamDescription: &asbd)
            if !config.codecSpecificData.isEmpty {
                let esds = AudioSpecificConfigInfo.wrapInESDS(config.codecSpecificData)
                if !esds.isEmpty {
                    fmt?.magicCookie = esds
                }
            }
            inputFormat = fmt

        case .opus:
            var asbd = AudioStreamBasicDescription(
                mSampleRate: sampleRate,
                mFormatID: kAudioFormatOpus,
                mFormatFlags: 0,
                mBytesPerPacket: 0,
                mFramesPerPacket: 960,
                mBytesPerFrame: 0,
                mChannelsPerFrame: channels,
                mBitsPerChannel: 0,
                mReserved: 0
            )
            inputFormat = AVAudioFormat(streamDescription: &asbd)

        case .pcm16:
            inputFormat = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: sampleRate,
                channels: channels,
                interleaved: true
            )
        }

        guard let inFmt = inputFormat,
              let converter = AVAudioConverter(from: inFmt, to: outputFormat)
        else {
            return nil
        }

        if config.codec == .aacLC && !config.codecSpecificData.isEmpty {
            let esds = AudioSpecificConfigInfo.wrapInESDS(config.codecSpecificData)
            if !esds.isEmpty {
                converter.magicCookie = esds
            }
        }

        return (inFmt, outputFormat, converter)
    }

    public static func decodePacket(
        _ payload: Data,
        codec: ExtCastAudioCodec,
        inputFormat: AVAudioFormat,
        outputFormat: AVAudioFormat,
        converter: AVAudioConverter
    ) -> AVAudioPCMBuffer? {
        switch codec {
        case .pcm16:
            let bytesPerFrame = Int(inputFormat.streamDescription.pointee.mBytesPerFrame)
            guard bytesPerFrame > 0 else { return nil }
            let frameCount = AVAudioFrameCount(payload.count / bytesPerFrame)
            guard frameCount > 0,
                  let inputBuffer = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: frameCount),
                  let outputBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: frameCount)
            else {
                return nil
            }
            inputBuffer.frameLength = frameCount
            if let dst = inputBuffer.int16ChannelData?[0] {
                payload.withUnsafeBytes { raw in
                    if let base = raw.baseAddress {
                        memcpy(dst, base, Int(frameCount) * bytesPerFrame)
                    }
                }
            }
            var error: NSError?
            var didProvide = false
            let status = converter.convert(to: outputBuffer, error: &error) { _, outStatus in
                if didProvide {
                    outStatus.pointee = .noDataNow
                    return nil
                }
                didProvide = true
                outStatus.pointee = .haveData
                return inputBuffer
            }
            guard status != .error && error == nil && outputBuffer.frameLength > 0 else {
                return nil
            }
            return outputBuffer

        case .aacLC, .opus:
            let frameCapacity: AVAudioFrameCount = (codec == .aacLC) ? 1024 : 960
            let compressed = AVAudioCompressedBuffer(
                format: inputFormat,
                packetCapacity: 1,
                maximumPacketSize: payload.count
            )
            compressed.byteLength = UInt32(payload.count)
            compressed.packetCount = 1
            payload.withUnsafeBytes { raw in
                if let base = raw.baseAddress {
                    memcpy(compressed.data, base, payload.count)
                }
            }
            compressed.packetDescriptions?.pointee = AudioStreamPacketDescription(
                mStartOffset: 0,
                mVariableFramesInPacket: 0,
                mDataByteSize: UInt32(payload.count)
            )

            guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: frameCapacity) else {
                return nil
            }

            var error: NSError?
            var supplied = false
            let status = converter.convert(to: outputBuffer, error: &error) { _, outStatus in
                if supplied {
                    outStatus.pointee = .noDataNow
                    return nil
                }
                supplied = true
                outStatus.pointee = .haveData
                return compressed
            }
            guard status != .error && error == nil && outputBuffer.frameLength > 0 else {
                return nil
            }
            return outputBuffer
        }
    }

    // MARK: - Private Playback Pipeline (runs on `audioQueue`)

    private func configureOnQueue(_ config: AudioConfigPayload) {
        playerNode.stop()
        if engine.isRunning {
            engine.stop()
        }

        guard let pipeline = Self.makeConverter(for: config) else {
            isConfigured = false
            return
        }

        engine.disconnectNodeOutput(playerNode)
        engine.connect(playerNode, to: engine.mainMixerNode, format: pipeline.outputFormat)

        let muted = metrics.withLock { $0.isMuted }
        playerNode.volume = muted ? 0.0 : 1.0

        do {
            engine.prepare()
            try engine.start()
            playerNode.play()
        } catch {
            isConfigured = false
            return
        }

        currentConfig = config
        inputFormat = pipeline.inputFormat
        outputFormat = pipeline.outputFormat
        converter = pipeline.converter
        isConfigured = true
        metrics.withLock { $0.pendingBufferCount = 0 }
    }

    private func decodeAndScheduleOnQueue(_ payload: Data) {
        guard isConfigured,
              let config = currentConfig,
              let inFmt = inputFormat,
              let outFmt = outputFormat,
              let conv = converter
        else {
            return
        }

        let (pending, muted) = metrics.withLock { ($0.pendingBufferCount, $0.isMuted) }

        // Overflow guard: drop excess packets when queue builds up so audio stays synced with live video.
        if pending >= Self.maxScheduledBuffers {
            metrics.withLock { $0.droppedPackets += 1 }
            return
        }

        guard let pcmBuffer = Self.decodePacket(
            payload,
            codec: config.codec,
            inputFormat: inFmt,
            outputFormat: outFmt,
            converter: conv
        ) else {
            return
        }

        if muted {
            return
        }

        if !engine.isRunning {
            try? engine.start()
        }
        if !playerNode.isPlaying {
            playerNode.play()
        }

        metrics.withLock { s in
            s.pendingBufferCount += 1
            s.decodedPackets += 1
        }

        playerNode.scheduleBuffer(pcmBuffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            self?.metrics.withLock { s in
                s.pendingBufferCount = max(0, s.pendingBufferCount - 1)
            }
        }
    }
}

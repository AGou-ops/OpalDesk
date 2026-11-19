import AVFoundation
import AudioToolbox
import Foundation
import os

/// Real-time low-latency audio decoder and playback engine backed by `AVAudioEngine` + `AVAudioConverter`.
/// Decodes Android ExtCast AAC-LC (48kHz stereo) or PCM-16 audio streams with mute support
/// and bounded jitter-buffer overflow protection.
public final class AudioStreamPlayer: @unchecked Sendable {
    private static let maxScheduledBuffers = 8
    private static let aacFramesPerPacket: AVAudioFrameCount = 1024
    private static let minimumSampleRate: UInt32 = 8000

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
        // Never dispatch synchronously onto `audioQueue` in `deinit`: if the last strong
        // reference drops inside an `audioQueue` block, `audioQueue.sync` would deadlock.
        // At `deinit`, no other reference can touch `engine` or `playerNode`.
        playerNode.stop()
        if engine.isRunning {
            engine.stop()
        }
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
        metrics.withLock { $0.pendingBufferCount = 0 }
        audioQueue.async { [weak self] in
            self?.stopOnQueue()
        }
    }

    /// Standalone synchronous converter builder (also used by unit tests to verify AAC-LC / PCM-16 decoding without opening audio hardware).
    public static func makeConverter(
        for config: AudioConfigPayload
    ) -> (inputFormat: AVAudioFormat, outputFormat: AVAudioFormat, converter: AVAudioConverter)? {
        guard config.codec == .aacLC || config.codec == .pcm16 else {
            ExtBridgeLog.audio.warning("Unsupported ExtCast audio codec rawValue=\(config.codec.rawValue)")
            return nil
        }

        let ascInfo = config.codec == .aacLC ? AudioSpecificConfigInfo.parse(config.codecSpecificData) : nil
        let sampleRate = Double(ascInfo?.sampleRate ?? max(config.sampleRate, minimumSampleRate))
        let channels = AVAudioChannelCount(ascInfo?.channels ?? max(config.channels, 1))

        guard let outputFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: channels,
            interleaved: false
        ) else {
            ExtBridgeLog.audio.error("Failed to create PCM Float32 output format (sr=\(sampleRate), ch=\(channels))")
            return nil
        }

        let esdsCookie: Data = (config.codec == .aacLC && !config.codecSpecificData.isEmpty)
            ? AudioSpecificConfigInfo.wrapInESDS(config.codecSpecificData)
            : Data()

        let inputFormat: AVAudioFormat?
        switch config.codec {
        case .aacLC:
            var asbd = AudioStreamBasicDescription(
                mSampleRate: sampleRate,
                mFormatID: kAudioFormatMPEG4AAC,
                mFormatFlags: UInt32(MPEG4ObjectID.AAC_LC.rawValue),
                mBytesPerPacket: 0,
                mFramesPerPacket: aacFramesPerPacket,
                mBytesPerFrame: 0,
                mChannelsPerFrame: channels,
                mBitsPerChannel: 0,
                mReserved: 0
            )
            let fmt = AVAudioFormat(streamDescription: &asbd)
            if !esdsCookie.isEmpty {
                fmt?.magicCookie = esdsCookie
            }
            inputFormat = fmt

        case .pcm16:
            inputFormat = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: sampleRate,
                channels: channels,
                interleaved: true
            )

        case .opus:
            return nil
        }

        guard let inFmt = inputFormat,
              let converter = AVAudioConverter(from: inFmt, to: outputFormat)
        else {
            ExtBridgeLog.audio.error("Failed to create AVAudioConverter for codec rawValue=\(config.codec.rawValue)")
            return nil
        }

        if !esdsCookie.isEmpty {
            converter.magicCookie = esdsCookie
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
                  let inputBuffer = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: frameCount)
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
            return convertSingleBuffer(inputBuffer, using: converter, outputFormat: outputFormat, frameCapacity: frameCount)

        case .aacLC:
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
            return convertSingleBuffer(
                compressed,
                using: converter,
                outputFormat: outputFormat,
                frameCapacity: aacFramesPerPacket
            )

        case .opus:
            ExtBridgeLog.audio.warning("Ignoring audio packet for unsupported codec: opus")
            return nil
        }
    }

    // MARK: - Private Decode & Playback Pipeline

    private static func convertSingleBuffer(
        _ inputBuffer: AVAudioBuffer,
        using converter: AVAudioConverter,
        outputFormat: AVAudioFormat,
        frameCapacity: AVAudioFrameCount
    ) -> AVAudioPCMBuffer? {
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
            return inputBuffer
        }
        if status == .error || error != nil {
            ExtBridgeLog.audio.warning("AVAudioConverter failed: \(error?.localizedDescription ?? "status=\(status.rawValue)", privacy: .public)")
            return nil
        }
        guard outputBuffer.frameLength > 0 else {
            return nil
        }
        return outputBuffer
    }

    private func stopOnQueue() {
        playerNode.stop()
        if engine.isRunning {
            engine.stop()
        }
        isConfigured = false
        currentConfig = nil
        converter = nil
        inputFormat = nil
        outputFormat = nil
        metrics.withLock { $0.pendingBufferCount = 0 }
    }

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
            ExtBridgeLog.audio.error("Failed to start AVAudioEngine during configure: \(error.localizedDescription, privacy: .public)")
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
            do {
                try engine.start()
            } catch {
                ExtBridgeLog.audio.error("Failed to restart AVAudioEngine for playback: \(error.localizedDescription, privacy: .public)")
                return
            }
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

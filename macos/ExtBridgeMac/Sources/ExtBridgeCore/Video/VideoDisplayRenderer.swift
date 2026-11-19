import AVFoundation
import CoreMedia
import Foundation
import QuartzCore
import os

public struct VideoRendererMetrics: Sendable, Equatable {
    public var width: Int = 0
    public var height: Int = 0
    public var rotation: Int = 0
    public var codec: ExtCastVideoCodec?
    public var currentFPS: Double = 0
    public var bitrateKbps: Double = 0
    public var totalFramesDecoded: UInt64 = 0
    public var droppedFrames: UInt64 = 0

    public init() {}
}

/// Zero-copy hardware VideoToolbox decoder & renderer backed by `AVSampleBufferDisplayLayer`.
/// Accepts Annex-B `VIDEO_CONFIG` and `VIDEO_FRAME` payloads, automatically handles dynamic
/// resolution/parameter-set changes, and requests IDR keyframes on decoder recovery.
public final class VideoDisplayRenderer: @unchecked Sendable {
    private struct DecoderState {
        var codec: ExtCastVideoCodec = .hevc
        var parameterSets = ExtractedParameterSets()
        var formatDescription: CMVideoFormatDescription?
        var awaitingKeyframe: Bool = true
        var pendingDecodeFrames: Int = 0
        var lastIDRRequestNs: UInt64 = 0
        var metrics = VideoRendererMetrics()
        var windowFrameCount: Int = 0
        var windowByteCount: Int = 0
        var windowStartNs: UInt64 = DispatchTime.now().uptimeNanoseconds
    }

    public let displayLayer: AVSampleBufferDisplayLayer
    private let sampleRenderer: AVSampleBufferVideoRenderer
    private let decodeQueue = DispatchQueue(label: "com.extbridge.mac.video-decode", qos: .userInteractive)
    private let state = OSAllocatedUnfairLock(initialState: DecoderState())
    private let callbacks = OSAllocatedUnfairLock<(
        onRequestIDR: (@Sendable () -> Void)?,
        onMetricsUpdate: (@Sendable (VideoRendererMetrics) -> Void)?
    )>(initialState: (nil, nil))

    @MainActor
    public init() {
        let layer = AVSampleBufferDisplayLayer()
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = CGColor.black
        self.displayLayer = layer
        self.sampleRenderer = layer.sampleBufferRenderer
    }

    public func setCallbacks(
        onRequestIDR: (@Sendable () -> Void)?,
        onMetricsUpdate: (@Sendable (VideoRendererMetrics) -> Void)?
    ) {
        callbacks.withLock {
            $0.onRequestIDR = onRequestIDR
            $0.onMetricsUpdate = onMetricsUpdate
        }
    }

    public func currentMetrics() -> VideoRendererMetrics {
        state.withLock { $0.metrics }
    }

    public func reset() {
        decodeQueue.async { [weak self] in
            guard let self else { return }
            self.state.withLock { s in
                s.parameterSets = ExtractedParameterSets()
                s.formatDescription = nil
                s.awaitingKeyframe = true
                s.windowFrameCount = 0
                s.windowByteCount = 0
                s.windowStartNs = DispatchTime.now().uptimeNanoseconds
                s.metrics = VideoRendererMetrics()
            }
            self.sampleRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
        }
    }

    /// Processes an incoming `0x01 VIDEO_CONFIG` payload containing codec metadata and Annex-B CSD.
    public func configure(_ config: VideoConfigPayload) {
        decodeQueue.async { [weak self] in
            guard let self else { return }
            let units = AnnexBParser.splitNALUnits(from: config.csd, codec: config.codec)
            let extracted = AnnexBParser.extractParameterSets(from: units, codec: config.codec)

            let createdFormat: CMVideoFormatDescription? = extracted.isComplete(for: config.codec)
                ? try? AnnexBParser.makeFormatDescription(from: extracted, codec: config.codec)
                : nil

            let metricsSnapshot: VideoRendererMetrics = self.state.withLock { s in
                s.codec = config.codec
                s.parameterSets = extracted
                s.formatDescription = createdFormat
                s.awaitingKeyframe = true
                s.metrics.codec = config.codec
                s.metrics.width = Int(config.width)
                s.metrics.height = Int(config.height)
                s.metrics.rotation = Int(config.rotation)
                return s.metrics
            }

            // Flush previous stream geometry cleanly so VideoToolbox accepts the new format description.
            self.sampleRenderer.flush()
            self.emitMetrics(metricsSnapshot)
        }
    }

    /// Processes an incoming `0x02 VIDEO_FRAME` Annex-B payload and submits it immediately to VideoToolbox.
    /// Applies low-latency backlog shedding so `decodeQueue` never builds up multi-frame lag.
    public func enqueueFrame(payload: Data, flags: ExtCastVideoFrameFlags, timestampUs: Int64) {
        let isMarkedKey = flags.contains(.keyframe)
        let shouldDropBacklog: Bool = state.withLock { s in
            if s.pendingDecodeFrames >= 4 && !isMarkedKey {
                s.metrics.droppedFrames += 1
                s.awaitingKeyframe = true
                return true
            }
            s.pendingDecodeFrames += 1
            return false
        }
        if shouldDropBacklog {
            requestIDRThrottled()
            return
        }
        decodeQueue.async { [weak self] in
            guard let self else { return }
            defer {
                self.state.withLock { s in
                    s.pendingDecodeFrames = max(0, s.pendingDecodeFrames - 1)
                }
            }
            self.processVideoFrame(payload: payload, flags: flags, timestampUs: timestampUs)
        }
    }

    // MARK: - Decode Pipeline (runs on `decodeQueue`)

    private func processVideoFrame(payload: Data, flags: ExtCastVideoFrameFlags, timestampUs: Int64) {
        guard !payload.isEmpty else { return }

        let renderer = sampleRenderer
        if renderer.status == .failed {
            renderer.flush()
            state.withLock { $0.awaitingKeyframe = true }
            requestIDRThrottled()
        }

        let codec = state.withLock { $0.codec }
        let units = AnnexBParser.splitNALUnits(from: payload, codec: codec)
        guard !units.isEmpty else { return }

        // Handle inline SPS/PPS/VPS updates (dynamic resolution switch or periodic keyframe headers).
        let inlineParams = AnnexBParser.extractParameterSets(from: units, codec: codec)
        if flags.contains(.configInline) || !inlineParams.sps.isEmpty || !inlineParams.pps.isEmpty || !inlineParams.vps.isEmpty {
            updateParameterSetsIfChanged(inlineParams, codec: codec)
        }

        let (avccData, containsKeyframeNAL) = AnnexBParser.convertToLengthPrefixed(units: units, codec: codec)
        guard !avccData.isEmpty else { return }

        let isKeyframe = flags.contains(.keyframe) || containsKeyframeNAL

        let (formatDesc, shouldDropForIDR): (CMVideoFormatDescription?, Bool) = state.withLock { s in
            guard let desc = s.formatDescription else {
                s.metrics.droppedFrames += 1
                return (nil, true)
            }
            if s.awaitingKeyframe && !isKeyframe {
                s.metrics.droppedFrames += 1
                return (nil, true)
            }
            if isKeyframe {
                s.awaitingKeyframe = false
            }
            return (desc, false)
        }

        guard let formatDescription = formatDesc, !shouldDropForIDR else {
            requestIDRThrottled()
            return
        }

        guard let sampleBuffer = makeSampleBuffer(
            avccData: avccData,
            formatDescription: formatDescription,
            timestampUs: timestampUs,
            isKeyframe: isKeyframe
        ) else {
            state.withLock { $0.metrics.droppedFrames += 1 }
            return
        }

        // If display layer is temporarily saturated on a non-keyframe, drop cleanly and request IDR
        // so subsequent P-frames never decode against a broken reference chain.
        if !renderer.isReadyForMoreMediaData && !isKeyframe {
            state.withLock { s in
                s.metrics.droppedFrames += 1
                s.awaitingKeyframe = true
            }
            requestIDRThrottled()
            return
        }

        renderer.enqueue(sampleBuffer)
        recordDecodedFrame(byteCount: payload.count, formatDescription: formatDescription)
    }

    private func updateParameterSetsIfChanged(_ incoming: ExtractedParameterSets, codec: ExtCastVideoCodec) {
        var merged = state.withLock { $0.parameterSets }
        var changed = false

        if !incoming.vps.isEmpty && incoming.vps != merged.vps {
            merged.vps = incoming.vps
            changed = true
        }
        if !incoming.sps.isEmpty && incoming.sps != merged.sps {
            merged.sps = incoming.sps
            changed = true
        }
        if !incoming.pps.isEmpty && incoming.pps != merged.pps {
            merged.pps = incoming.pps
            changed = true
        }

        let needsCreation = state.withLock { $0.formatDescription == nil }
        guard (changed || needsCreation), merged.isComplete(for: codec) else {
            return
        }

        let finalSets = merged
        if let newFormat = try? AnnexBParser.makeFormatDescription(from: finalSets, codec: codec) {
            let dims = CMVideoFormatDescriptionGetDimensions(newFormat)
            state.withLock { s in
                s.parameterSets = finalSets
                s.formatDescription = newFormat
                if dims.width > 0 && dims.height > 0 {
                    s.metrics.width = Int(dims.width)
                    s.metrics.height = Int(dims.height)
                }
            }
        }
    }

    private func makeSampleBuffer(
        avccData: Data,
        formatDescription: CMVideoFormatDescription,
        timestampUs: Int64,
        isKeyframe: Bool
    ) -> CMSampleBuffer? {
        let dataLength = avccData.count
        var blockBuffer: CMBlockBuffer?

        let allocStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: dataLength,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: dataLength,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard allocStatus == kCMBlockBufferNoErr, let blockBuffer else {
            return nil
        }

        let copyStatus = avccData.withUnsafeBytes { rawBuffer -> OSStatus in
            guard let baseAddress = rawBuffer.baseAddress else { return kCMBlockBufferBadLengthParameterErr }
            return CMBlockBufferReplaceDataBytes(
                with: baseAddress,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: dataLength
            )
        }
        guard copyStatus == kCMBlockBufferNoErr else {
            return nil
        }

        // Use `.invalid` PTS together with `DisplayImmediately` so AVSampleBufferDisplayLayer renders
        // each decoded frame immediately without stalling against mismatched Android monotonic clocks.
        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: .invalid,
            decodeTimeStamp: .invalid
        )
        var sampleSize = dataLength
        var sampleBuffer: CMSampleBuffer?

        let sampleStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else {
            return nil
        }

        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0
        {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dict,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
            if !isKeyframe {
                CFDictionarySetValue(
                    dict,
                    Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                    Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
                )
            }
        }

        return sampleBuffer
    }

    private func recordDecodedFrame(byteCount: Int, formatDescription: CMVideoFormatDescription) {
        let nowNs = DispatchTime.now().uptimeNanoseconds
        let dims = CMVideoFormatDescriptionGetDimensions(formatDescription)

        let updatedMetrics: VideoRendererMetrics? = state.withLock { s in
            s.metrics.totalFramesDecoded += 1
            if dims.width > 0 && dims.height > 0 {
                s.metrics.width = Int(dims.width)
                s.metrics.height = Int(dims.height)
            }
            s.windowFrameCount += 1
            s.windowByteCount += byteCount

            let elapsedNs = nowNs > s.windowStartNs ? nowNs - s.windowStartNs : 0
            if elapsedNs >= 500_000_000 { // Update HUD stats every 0.5s
                let seconds = Double(elapsedNs) / 1_000_000_000.0
                s.metrics.currentFPS = Double(s.windowFrameCount) / seconds
                s.metrics.bitrateKbps = (Double(s.windowByteCount) * 8.0) / (seconds * 1000.0)
                s.windowFrameCount = 0
                s.windowByteCount = 0
                s.windowStartNs = nowNs
                return s.metrics
            }
            return nil
        }

        if let updatedMetrics {
            emitMetrics(updatedMetrics)
        }
    }

    private func requestIDRThrottled() {
        let nowNs = DispatchTime.now().uptimeNanoseconds
        let shouldFire: Bool = state.withLock { s in
            if nowNs > s.lastIDRRequestNs && (nowNs - s.lastIDRRequestNs) < 500_000_000 {
                return false
            }
            s.lastIDRRequestNs = nowNs
            return true
        }
        if shouldFire {
            let cb = callbacks.withLock { $0.onRequestIDR }
            cb?()
        }
    }

    private func emitMetrics(_ metrics: VideoRendererMetrics) {
        let cb = callbacks.withLock { $0.onMetricsUpdate }
        cb?(metrics)
    }
}

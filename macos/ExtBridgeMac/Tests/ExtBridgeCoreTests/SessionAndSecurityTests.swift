import AVFoundation
import Foundation
import Network
import os
import Testing
@testable import ExtBridgeCore

@Suite("ExtCastSession Loopback, TOFU Pin Guard & Real AAC-LC Decode")
struct SessionAndSecurityTests {

    @Test("Rejects TLS connection immediately when expected fingerprint is missing or too short")
    func rejectsUnpinnedTLS() async throws {
        let receivedError = OSAllocatedUnfairLock<ExtCastSessionError?>(initialState: nil)
        let session = ExtCastSession()

        let endpoint = ExtCastEndpoint(
            target: .hostPort(host: "127.0.0.1", port: ExtCastProtocol.defaultWifiPort),
            security: .tlsPinned(expectedFingerprint: "1234"), // < 16 hex chars
            autoReconnect: false
        )
        session.connect(to: endpoint) { event in
            if case .error(let err) = event {
                receivedError.withLock { $0 = err }
            }
        }

        for _ in 0..<50 {
            if receivedError.withLock({ $0 != nil }) { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }

        #expect(receivedError.withLock { $0 } == .missingExpectedFingerprint)
        session.disconnect()
    }

    @Test("Loopback ExtCastSession exchanges HELLO/AUTH_RESULT and PING/PONG RTT over TCP")
    func loopbackSessionFrameExchange() async throws {
        let listenerQueue = DispatchQueue(label: "com.extbridge.test.listener")
        let listener = try NWListener(using: .tcp, on: .any)
        let serverConnBox = OSAllocatedUnfairLock<NWConnection?>(initialState: nil)

        let boundPort: UInt16 = try await withCheckedThrowingContinuation { continuation in
            let resumed = OSAllocatedUnfairLock<Bool>(initialState: false)
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    guard resumed.withLock({ r in if r { return false }; r = true; return true }) else { return }
                    continuation.resume(returning: listener.port?.rawValue ?? 0)
                case .failed(let err):
                    guard resumed.withLock({ r in if r { return false }; r = true; return true }) else { return }
                    continuation.resume(throwing: err)
                default:
                    break
                }
            }
            listener.newConnectionHandler = { conn in
                serverConnBox.withLock { $0 = conn }
                conn.start(queue: listenerQueue)

                let decoderBox = OSAllocatedUnfairLock<ExtCastStreamDecoder>(initialState: ExtCastStreamDecoder())
                @Sendable func readLoop() {
                    conn.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, isComplete, _ in
                        if let data, !data.isEmpty {
                            let frames = decoderBox.withLock { dec -> [ExtCastFrame] in
                                dec.append(data)
                                return (try? dec.decodeAvailableFrames()) ?? []
                            }
                            for frame in frames {
                                if frame.type == .hello {
                                    let auth = AuthResultPayload(ok: true, authToken: "loop-token", message: "ok", deviceName: "TestPhone")
                                    if let authData = try? auth.encode(),
                                       let encoded = try? ExtCastFrame(type: .authResult, payload: authData).encode()
                                    {
                                        conn.send(content: encoded, completion: .idempotent)
                                    }
                                } else if frame.type == .ping {
                                    if let encoded = try? ExtCastFrame(type: .pong, payload: frame.payload).encode() {
                                        conn.send(content: encoded, completion: .idempotent)
                                    }
                                }
                            }
                        }
                        if !isComplete {
                            readLoop()
                        }
                    }
                }
                readLoop()
            }
            listener.start(queue: listenerQueue)
        }
        defer {
            serverConnBox.withLock { $0?.cancel() }
            listener.cancel()
        }
        #expect(boundPort > 0)

        let stateBox = OSAllocatedUnfairLock<(connected: Bool, authOk: Bool, rttMs: Double?)>(
            initialState: (false, false, nil)
        )
        let session = ExtCastSession()
        defer { session.disconnect() }

        let endpoint = ExtCastEndpoint(
            target: .hostPort(host: "127.0.0.1", port: boundPort),
            security: .wiredLocalhostPlaintext,
            autoReconnect: false
        )
        session.connect(to: endpoint) { event in
            switch event {
            case .stateChanged(.connected):
                stateBox.withLock { $0.connected = true }
                if let helloFrame = try? ExtCastFrame.hello(HelloPayload(clientName: "TestMac", clientId: "cid-1")) {
                    session.send(helloFrame)
                }
                session.send(.ping(777))
            case .frame(let frame) where frame.type == .authResult:
                if let auth = try? AuthResultPayload.decode(from: frame.payload), auth.ok {
                    stateBox.withLock { $0.authOk = true }
                }
            case .rttUpdated(let rtt):
                stateBox.withLock { $0.rttMs = rtt }
            default:
                break
            }
        }

        for _ in 0..<200 {
            let s = stateBox.withLock { $0 }
            if s.connected && s.authOk {
                break
            }
            try await Task.sleep(nanoseconds: 10_000_000)
        }

        let finalState = stateBox.withLock { $0 }
        #expect(finalState.connected == true)
        #expect(finalState.authOk == true)
    }

    @Test("Real AAC-LC encode -> AudioStreamPlayer decode roundtrip succeeds at 48kHz stereo")
    func realAACDecodeRoundtrip() throws {
        // Step 1: Encode a 1024-frame 48kHz stereo sine wave into a real raw AAC-LC AU using Apple AudioToolbox.
        guard let pcmFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: 48_000,
            channels: 2,
            interleaved: false
        ) else {
            Issue.record("Failed to create PCM format")
            return
        }

        var aacASBD = AudioStreamBasicDescription(
            mSampleRate: 48_000,
            mFormatID: kAudioFormatMPEG4AAC,
            mFormatFlags: UInt32(MPEG4ObjectID.AAC_LC.rawValue),
            mBytesPerPacket: 0,
            mFramesPerPacket: 1024,
            mBytesPerFrame: 0,
            mChannelsPerFrame: 2,
            mBitsPerChannel: 0,
            mReserved: 0
        )
        guard let aacFormat = AVAudioFormat(streamDescription: &aacASBD),
              let encoder = AVAudioConverter(from: pcmFormat, to: aacFormat)
        else {
            Issue.record("Failed to initialize AAC encoder")
            return
        }

        guard let pcmIn = AVAudioPCMBuffer(pcmFormat: pcmFormat, frameCapacity: 1024) else {
            Issue.record("Failed to allocate PCM input buffer")
            return
        }
        pcmIn.frameLength = 1024
        if let ch0 = pcmIn.floatChannelData?[0], let ch1 = pcmIn.floatChannelData?[1] {
            for i in 0..<1024 {
                let sample = Float(sin(2.0 * Double.pi * 440.0 * Double(i) / 48_000.0) * 0.25)
                ch0[i] = sample
                ch1[i] = sample
            }
        }

        let compressed = AVAudioCompressedBuffer(format: aacFormat, packetCapacity: 1, maximumPacketSize: 2048)
        var encodeErr: NSError?
        var fed = false
        let encStatus = encoder.convert(to: compressed, error: &encodeErr) { _, outStatus in
            if fed {
                outStatus.pointee = .noDataNow
                return nil
            }
            fed = true
            outStatus.pointee = .haveData
            return pcmIn
        }
        #expect(encStatus != .error)
        #expect(encodeErr == nil)
        #expect(compressed.byteLength > 0)

        let rawAACPacket = Data(bytes: compressed.data, count: Int(compressed.byteLength))

        // Step 2: Decode that raw AAC-LC packet with AudioStreamPlayer's 2-byte ASC (0x11 0x90) pipeline.
        let cfg = AudioConfigPayload(
            codec: .aacLC,
            sampleRate: 48_000,
            channels: 2,
            codecSpecificData: Data([0x11, 0x90])
        )
        let pipeline = try #require(AudioStreamPlayer.makeConverter(for: cfg))
        let decoded = AudioStreamPlayer.decodePacket(
            rawAACPacket,
            codec: .aacLC,
            inputFormat: pipeline.inputFormat,
            outputFormat: pipeline.outputFormat,
            converter: pipeline.converter
        )
        #expect(decoded != nil)
        #expect(decoded?.frameLength == 1024)
    }
}

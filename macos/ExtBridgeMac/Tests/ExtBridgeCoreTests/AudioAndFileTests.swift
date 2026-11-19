import AVFoundation
import Foundation
import os
import Testing
@testable import ExtBridgeCore

@Suite("Audio Codec, ADB Device Parser & FileSender")
struct AudioAndFileTests {

    @Test("Parses 2-byte MPEG-4 AudioSpecificConfig for 48kHz stereo AAC-LC and builds ESDS")
    func parseAudioSpecificConfig() {
        // AAC-LC (objectType=2 -> 00010), 48000 Hz (index=3 -> 0011), stereo (channels=2 -> 0010)
        // Binary: 00010_001 1_0010_000 = 0x11 0x90
        let asc = Data([0x11, 0x90])
        let info = AudioSpecificConfigInfo.parse(asc)
        #expect(info == AudioSpecificConfigInfo(audioObjectType: 2, sampleRate: 48_000, channels: 2))

        let esds = AudioSpecificConfigInfo.wrapInESDS(asc)
        #expect(esds.first == 0x03) // ES_Descriptor tag
        #expect(esds.contains(0x05)) // DecoderSpecificInfo tag
        #expect(esds.suffix(5).prefix(2) == asc) // ASC sits right before SLConfigDescriptor (0x06 0x01 0x02)
    }

    @Test("AudioStreamPlayer converter decodes PCM-16 packets cleanly")
    func decodePCM16Packet() {
        let cfg = AudioConfigPayload(codec: .pcm16, sampleRate: 48_000, channels: 2, codecSpecificData: Data())
        let pipeline = AudioStreamPlayer.makeConverter(for: cfg)
        #expect(pipeline != nil)

        if let pipeline {
            // 256 stereo Int16 frames = 256 * 2 * 2 = 1024 bytes
            var pcmBytes = Data(capacity: 1024)
            for i in 0..<512 {
                var sample: Int16 = (i % 2 == 0) ? 1000 : -1000
                withUnsafeBytes(of: &sample) { pcmBytes.append(contentsOf: $0) }
            }
            let decoded = AudioStreamPlayer.decodePacket(
                pcmBytes,
                codec: .pcm16,
                inputFormat: pipeline.inputFormat,
                outputFormat: pipeline.outputFormat,
                converter: pipeline.converter
            )
            #expect(decoded != nil)
            #expect(decoded?.frameLength == 256)
        }
    }

    @Test("AudioStreamPlayer converter creates valid AAC-LC decoder with 2-byte ASC")
    func createAACConverter() {
        let cfg = AudioConfigPayload(
            codec: .aacLC,
            sampleRate: 48_000,
            channels: 2,
            codecSpecificData: Data([0x11, 0x90])
        )
        let pipeline = AudioStreamPlayer.makeConverter(for: cfg)
        #expect(pipeline != nil)
        #expect(pipeline?.outputFormat.sampleRate == 48_000)
        #expect(pipeline?.outputFormat.channelCount == 2)
    }

    @Test("Parses adb devices -l output across ready, unauthorized, and offline devices")
    func parseAdbDevicesList() {
        let sampleOutput = """
        * daemon not running; starting now at tcp:5037
        * daemon started successfully
        List of devices attached
        4a8c1234               device usb:1-1 product:dada model:24129PN74C device:dada transport_id:4
        emulator-5554          offline transport_id:5
        9876543210             unauthorized usb:1-2 transport_id:6

        """
        let devices = AdbManager.parseDevicesOutput(sampleOutput)
        #expect(devices.count == 3)

        #expect(devices[0].serial == "4a8c1234")
        #expect(devices[0].state == .device)
        #expect(devices[0].isReady == true)
        #expect(devices[0].model == "24129PN74C")
        #expect(devices[0].transportId == "4")

        #expect(devices[1].serial == "emulator-5554")
        #expect(devices[1].state == .offline)
        #expect(devices[1].isReady == false)

        #expect(devices[2].serial == "9876543210")
        #expect(devices[2].state == .unauthorized)
        #expect(devices[2].isReady == false)
    }

    @Test("FileSender streams file into START, DATA chunks, END and processes ACK")
    func fileSenderStreamingRoundtrip() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("extbridge-file-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let fileURL = tempDir.appendingPathComponent("sample-payload.bin")
        let originalData = Data((0..<12_345).map { UInt8($0 & 0xFF) })
        try originalData.write(to: fileURL)

        let collectedFrames = OSAllocatedUnfairLock<[ExtCastFrame]>(initialState: [])
        let latestProgress = OSAllocatedUnfairLock<FileTransferProgress?>(initialState: nil)

        let sender = FileSender(chunkSize: 4096)
        let transferId = try sender.sendFile(
            at: fileURL,
            sendFrame: { frame in
                collectedFrames.withLock { $0.append(frame) }
            },
            onProgress: { progress in
                latestProgress.withLock { $0 = progress }
            }
        )

        // Wait up to 2 seconds for streaming task to emit END
        for _ in 0..<200 {
            let status = latestProgress.withLock { $0?.status }
            if status == .awaitingAck {
                break
            }
            try await Task.sleep(nanoseconds: 10_000_000)
        }

        let frames = collectedFrames.withLock { $0 }
        // Expected: 1 START + 4 DATA chunks (4096 + 4096 + 4096 + 57) + 1 END = 6 frames
        #expect(frames.count == 6)

        let startChunk = try FileTransferChunkPayload.decode(from: frames[0].payload)
        #expect(startChunk.transferId == transferId)
        #expect(startChunk.chunkFlags == .start)
        #expect(startChunk.fileName == "sample-payload.bin")
        #expect(startChunk.totalSize == Int64(originalData.count))

        var reconstructed = Data()
        for frame in frames[1..<(frames.count - 1)] {
            let chunk = try FileTransferChunkPayload.decode(from: frame.payload)
            #expect(chunk.transferId == transferId)
            #expect(chunk.chunkFlags == .data)
            reconstructed.append(chunk.data)
        }
        #expect(reconstructed == originalData)

        let endChunk = try FileTransferChunkPayload.decode(from: frames[frames.count - 1].payload)
        #expect(endChunk.transferId == transferId)
        #expect(endChunk.chunkFlags == .end)

        // Deliver simulated Android ACK
        let ack = FileTransferAckPayload(
            transferId: transferId,
            status: .ok,
            pathOrError: "/sdcard/Download/ExtBridge/sample-payload.bin"
        )
        sender.handleAck(ack) { progress in
            latestProgress.withLock { $0 = progress }
        }

        let finalProgress = latestProgress.withLock { $0 }
        #expect(finalProgress?.status == .completed(remotePath: "/sdcard/Download/ExtBridge/sample-payload.bin"))
        #expect(finalProgress?.fractionCompleted == 1.0)
    }
}

import CoreMedia
import Foundation
import Testing
@testable import ExtBridgeCore

@Suite("Annex-B H.264 / HEVC Parser & AVCC/HVCC Conversion")
struct AnnexBParserTests {

    // Valid Baseline 1920x1080 H.264 SPS & PPS NAL payloads (without start codes)
    private let sampleH264SPS = Data([
        0x67, 0x42, 0xC0, 0x28, 0xD9, 0x00, 0x78, 0x02,
        0x27, 0xE5, 0xC0, 0x44, 0x00, 0x00, 0x03, 0x00,
        0x04, 0x00, 0x00, 0x03, 0x00, 0xF0, 0x3C, 0x60,
        0xC6, 0x58
    ])
    private let sampleH264PPS = Data([0x68, 0xCE, 0x38, 0x80])

    @Test("Splits mixed 4-byte and 3-byte Annex-B H.264 start codes and extracts SPS/PPS")
    func splitH264AndExtractParameterSets() {
        var stream = Data()
        stream.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        stream.append(sampleH264SPS)
        stream.append(contentsOf: [0x00, 0x00, 0x01]) // 3-byte start code
        stream.append(sampleH264PPS)
        stream.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        let idr = Data([0x65, 0x88, 0x84, 0x21, 0xA0])
        stream.append(idr)

        let units = AnnexBParser.splitNALUnits(from: stream, codec: .h264)
        #expect(units.count == 3)
        #expect(units[0].nalType == AnnexBParser.h264SPS)
        #expect(units[0].data == sampleH264SPS)
        #expect(units[1].nalType == AnnexBParser.h264PPS)
        #expect(units[1].data == sampleH264PPS)
        #expect(units[2].nalType == AnnexBParser.h264IDR)
        #expect(units[2].data == idr)

        let params = AnnexBParser.extractParameterSets(from: units, codec: .h264)
        #expect(params.isComplete(for: .h264))
        #expect(params.sps == [sampleH264SPS])
        #expect(params.pps == [sampleH264PPS])

        let (avcc, isKey) = AnnexBParser.convertToLengthPrefixed(units: units, codec: .h264)
        #expect(isKey == true)
        // Only the IDR NAL should remain, prefixed with 4-byte BE length (5)
        #expect(avcc.count == 4 + idr.count)
        #expect(Array(avcc.prefix(4)) == [0x00, 0x00, 0x00, 0x05])
        #expect(avcc.dropFirst(4) == idr)
    }

    @Test("Creates valid CMVideoFormatDescription from H.264 SPS/PPS")
    func createH264FormatDescription() throws {
        let params = ExtractedParameterSets(sps: [sampleH264SPS], pps: [sampleH264PPS])
        let desc = try AnnexBParser.makeFormatDescription(from: params, codec: .h264)
        let dims = CMVideoFormatDescriptionGetDimensions(desc)
        #expect(dims.width == 1920)
        #expect(dims.height == 1080)
    }

    @Test("Parses HEVC VPS/SPS/PPS and converts IDR slice to 4-byte HVCC length prefix")
    func parseHEVCAndConvertToHVCC() {
        // HEVC NAL header: type = (byte0 >> 1) & 0x3F
        // VPS=32 -> 0x40 0x01; SPS=33 -> 0x42 0x01; PPS=34 -> 0x44 0x01; IDR_W_RADL=19 -> 0x26 0x01
        let vps = Data([0x40, 0x01, 0x0C, 0x01])
        let sps = Data([0x42, 0x01, 0x01, 0x60])
        let pps = Data([0x44, 0x01, 0xC1, 0x72])
        let slice = Data([0x26, 0x01, 0xAF, 0x10, 0x20, 0x30])

        var stream = Data()
        for nal in [vps, sps, pps, slice] {
            stream.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
            stream.append(nal)
        }

        let units = AnnexBParser.splitNALUnits(from: stream, codec: .hevc)
        #expect(units.count == 4)
        #expect(units.map(\.nalType) == [32, 33, 34, 19])

        let params = AnnexBParser.extractParameterSets(from: units, codec: .hevc)
        #expect(params.isComplete(for: .hevc))
        #expect(params.vps == [vps])
        #expect(params.sps == [sps])
        #expect(params.pps == [pps])

        let (hvcc, isKey) = AnnexBParser.convertToLengthPrefixed(units: units, codec: .hevc)
        #expect(isKey == true)
        #expect(hvcc.count == 4 + slice.count)
        #expect(Array(hvcc.prefix(4)) == [0x00, 0x00, 0x00, 0x06])
        #expect(hvcc.dropFirst(4) == slice)
    }

    @Test("Incomplete parameter sets throw AnnexBError.incompleteParameterSets")
    func incompleteParameterSetsThrow() {
        let incompleteH264 = ExtractedParameterSets(sps: [sampleH264SPS], pps: [])
        #expect(throws: AnnexBError.incompleteParameterSets(.h264)) {
            _ = try AnnexBParser.makeFormatDescription(from: incompleteH264, codec: .h264)
        }

        let incompleteHEVC = ExtractedParameterSets(vps: [], sps: [Data([0x42, 0x01])], pps: [Data([0x44, 0x01])])
        #expect(throws: AnnexBError.incompleteParameterSets(.hevc)) {
            _ = try AnnexBParser.makeFormatDescription(from: incompleteHEVC, codec: .hevc)
        }
    }
}

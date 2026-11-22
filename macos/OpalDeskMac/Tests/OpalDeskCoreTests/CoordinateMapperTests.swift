import CoreGraphics
import Testing
@testable import OpalDeskCore

@Suite("CoordinateMapper & Adaptive 16-Pixel Alignment")
struct CoordinateMapperTests {

    @Test("Aspect-fit computes centered letterbox bars for 16:9 video in taller container")
    func aspectFitLetterbox() {
        let rect = CoordinateMapper.aspectFitRect(
            videoWidth: 1920,
            videoHeight: 1080,
            in: CGSize(width: 1000, height: 1000)
        )
        #expect(abs(rect.origin.x - 0) < 0.01)
        #expect(abs(rect.width - 1000) < 0.01)
        #expect(abs(rect.height - 562.5) < 0.01)
        #expect(abs(rect.origin.y - 218.75) < 0.01)
    }

    @Test("Aspect-fit computes centered pillarbox bars for portrait phone video in widescreen window")
    func aspectFitPillarbox() {
        let rect = CoordinateMapper.aspectFitRect(
            videoWidth: 1080,
            videoHeight: 1920,
            in: CGSize(width: 1600, height: 800)
        )
        #expect(abs(rect.height - 800) < 0.01)
        #expect(abs(rect.width - 450) < 0.01)
        #expect(abs(rect.origin.x - 575) < 0.01)
        #expect(abs(rect.origin.y - 0) < 0.01)
    }

    @Test("View-to-display point mapping handles center, corners, clamping, and flipped coordinates")
    func pointMappingAccuracy() {
        let viewSize = CGSize(width: 1600, height: 800) // pillarboxed 1080x1920 => content x in [575, 1025]

        // Center maps to exact center of 1080x1920
        let center = CoordinateMapper.mapViewPointToDisplay(
            viewPoint: CGPoint(x: 800, y: 400),
            viewSize: viewSize,
            videoWidth: 1080,
            videoHeight: 1920,
            isViewFlipped: true
        )
        #expect(center != nil)
        #expect(abs((center?.x ?? -1) - 539.5) < 0.5)
        #expect(abs((center?.y ?? -1) - 959.5) < 0.5)

        // Point inside left pillarbox returns nil when clampToBounds == false, or x=0 when clamped
        let unclamped = CoordinateMapper.mapViewPointToDisplay(
            viewPoint: CGPoint(x: 100, y: 400),
            viewSize: viewSize,
            videoWidth: 1080,
            videoHeight: 1920,
            isViewFlipped: true,
            clampToBounds: false
        )
        #expect(unclamped == nil)

        let clamped = CoordinateMapper.mapViewPointToDisplay(
            viewPoint: CGPoint(x: 100, y: 400),
            viewSize: viewSize,
            videoWidth: 1080,
            videoHeight: 1920,
            isViewFlipped: true,
            clampToBounds: true
        )
        #expect(clamped?.x == 0)

        // Unflipped AppKit view (y=0 at bottom) maps bottom-left to Android bottom-left (y=1919)
        let bottomLeftUnflipped = CoordinateMapper.mapViewPointToDisplay(
            viewPoint: CGPoint(x: 575, y: 0),
            viewSize: viewSize,
            videoWidth: 1080,
            videoHeight: 1920,
            isViewFlipped: false
        )
        #expect(bottomLeftUnflipped != nil)
        #expect(abs((bottomLeftUnflipped?.x ?? -1) - 0) < 0.5)
        #expect(abs((bottomLeftUnflipped?.y ?? -1) - 1919) < 0.5)
    }

    @Test("Adaptive resolution aligns strictly to multiples of 16 and honors Retina cap")
    func alignedAdaptiveResolution() {
        let res = CoordinateMapper.alignedAdaptiveResolution(
            viewSize: CGSize(width: 1437, height: 811),
            backingScale: 2.0,
            maxLongEdge: 2560
        )
        #expect(res.width % 16 == 0)
        #expect(res.height % 16 == 0)
        #expect(res.width == 2560)
        #expect(res.height >= 1408 && res.height <= 1456)

        // Minimum clamp check
        #expect(CoordinateMapper.alignTo16(123) == CoordinateMapper.minimumDimension)
        // Maximum clamp check
        #expect(CoordinateMapper.alignTo16(9999) == CoordinateMapper.maximumDimension)
    }

    @Test("Adapted window content size preserves 21:9 ultrawide aspect above taskbar within screen bounds")
    func adaptedWindowContentSizeUltrawide() {
        let content = CoordinateMapper.adaptedWindowContentSize(
            currentCanvasSize: CGSize(width: 1242, height: 668),
            targetVideoWidth: 2560,
            targetVideoHeight: 1080,
            taskbarHeight: 46,
            maxContentSize: CGSize(width: 2400, height: 1320),
            minContentSize: CGSize(width: 680, height: 480)
        )
        let canvasHeight = content.height - 46
        let canvasAspect = content.width / canvasHeight
        let targetAspect: CGFloat = 2560.0 / 1080.0
        #expect(abs(canvasAspect - targetAspect) < 0.02)
        #expect(content.width >= 680 && content.width <= 2400)
        #expect(content.height >= 480 && content.height <= 1320)
    }

    @Test("Adapted window content size respects maximum bounds while maintaining 16:9 aspect")
    func adaptedWindowContentSizeClamped() {
        let content = CoordinateMapper.adaptedWindowContentSize(
            currentCanvasSize: CGSize(width: 2400, height: 1400),
            targetVideoWidth: 1920,
            targetVideoHeight: 1080,
            taskbarHeight: 46,
            maxContentSize: CGSize(width: 1280, height: 766),
            minContentSize: CGSize(width: 680, height: 480)
        )
        let canvasHeight = content.height - 46
        #expect(content.width <= 1280)
        #expect(content.height <= 766)
        #expect(abs((content.width / canvasHeight) - (1920.0 / 1080.0)) < 0.02)
    }

    @Test("8-pixel encoder alignment preserves exact 1920x1080 and 2560x1600 without 1088 aspect distortion")
    func exactEightPixelAlignmentPreserves1080p() {
        #expect(CoordinateMapper.alignTo8(1920) == 1920)
        #expect(CoordinateMapper.alignTo8(1080) == 1080)
        #expect(CoordinateMapper.alignTo8(2560) == 2560)
        #expect(CoordinateMapper.alignTo8(1600) == 1600)

        let exactFhd = CoordinateMapper.aspectExactAdaptiveResolution(
            viewSize: CGSize(width: 960, height: 540),
            backingScale: 2.0,
            maxLongEdge: 2560
        )
        #expect(exactFhd.width == 1920)
        #expect(exactFhd.height == 1080)
    }
}

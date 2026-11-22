import CoreGraphics

/// Pure coordinate & resolution mapper between macOS view points and Android virtual display pixels.
public enum CoordinateMapper {
    public static let alignmentPixels: Int = 16
    public static let encoderAlignmentPixels: Int = 8
    public static let minimumDimension: Int = 480
    public static let maximumDimension: Int = 3840
    public static let popoutMinDPI: Int = 160
    public static let popoutMaxDPI: Int = 420
    public static let popoutDefaultMaxLongEdge: Int = 1680

    /// Computes the Aspect-Fit display rectangle (in view point coordinates) where the video frame renders.
    public static func aspectFitRect(
        videoWidth: Int,
        videoHeight: Int,
        in containerSize: CGSize
    ) -> CGRect {
        guard videoWidth > 0, videoHeight > 0, containerSize.width > 0, containerSize.height > 0 else {
            return CGRect(origin: .zero, size: containerSize)
        }

        let videoAspect = CGFloat(videoWidth) / CGFloat(videoHeight)
        let containerAspect = containerSize.width / containerSize.height

        let renderWidth: CGFloat
        let renderHeight: CGFloat
        if videoAspect > containerAspect {
            renderWidth = containerSize.width
            renderHeight = containerSize.width / videoAspect
        } else {
            renderHeight = containerSize.height
            renderWidth = containerSize.height * videoAspect
        }

        let originX = (containerSize.width - renderWidth) * 0.5
        let originY = (containerSize.height - renderHeight) * 0.5
        return CGRect(x: originX, y: originY, width: renderWidth, height: renderHeight)
    }

    /// Maps a point in macOS view coordinates to Android display pixel coordinates.
    /// - Parameters:
    ///   - viewPoint: Pointer location inside the display NSView.
    ///   - viewSize: Current bounds size of the display NSView (in points).
    ///   - videoWidth: Active remote Android display width in pixels.
    ///   - videoHeight: Active remote Android display height in pixels.
    ///   - isViewFlipped: `true` when origin (0,0) is top-left (matching Android); `false` for bottom-left AppKit default.
    ///   - clampToBounds: When `true`, clamps points outside letterbox bars to the nearest edge.
    /// - Returns: Mapped `(x, y)` in Android pixel coordinates, or `nil` if invalid dimensions or outside bounds when `clampToBounds == false`.
    public static func mapViewPointToDisplay(
        viewPoint: CGPoint,
        viewSize: CGSize,
        videoWidth: Int,
        videoHeight: Int,
        isViewFlipped: Bool = true,
        clampToBounds: Bool = true
    ) -> CGPoint? {
        guard videoWidth > 0, videoHeight > 0, viewSize.width > 0, viewSize.height > 0 else {
            return nil
        }

        let topLeftY = isViewFlipped ? viewPoint.y : (viewSize.height - viewPoint.y)
        let contentRect = aspectFitRect(videoWidth: videoWidth, videoHeight: videoHeight, in: viewSize)
        guard contentRect.width > 0, contentRect.height > 0 else { return nil }

        let normX = (viewPoint.x - contentRect.minX) / contentRect.width
        let normY = (topLeftY - contentRect.minY) / contentRect.height

        if !clampToBounds && (normX < 0 || normX > 1 || normY < 0 || normY > 1) {
            return nil
        }

        let clampedX = min(max(normX, 0.0), 1.0)
        let clampedY = min(max(normY, 0.0), 1.0)

        let maxX = CGFloat(max(videoWidth - 1, 0))
        let maxY = CGFloat(max(videoHeight - 1, 0))
        return CGPoint(x: clampedX * maxX, y: clampedY * maxY)
    }

    /// Computes a 16-pixel aligned virtual display resolution suitable for Android MediaCodec hardware encoders.
    /// Honors Retina `backingScale` while capping long edge to `maxLongEdge` so bandwidth and GPU load stay optimal.
    public static func alignedAdaptiveResolution(
        viewSize: CGSize,
        backingScale: CGFloat = 1.0,
        maxLongEdge: Int = 2560
    ) -> (width: Int, height: Int) {
        let effectiveScale = max(backingScale, 1.0)
        var rawWidth = Double(viewSize.width * effectiveScale)
        var rawHeight = Double(viewSize.height * effectiveScale)

        guard rawWidth > 0, rawHeight > 0 else {
            return (1920, 1080)
        }

        let clampedMax = Double(min(max(maxLongEdge, minimumDimension), maximumDimension))
        let longEdge = max(rawWidth, rawHeight)
        if longEdge > clampedMax {
            let scaleDown = clampedMax / longEdge
            rawWidth *= scaleDown
            rawHeight *= scaleDown
        }

        let width = alignTo16(Int(rawWidth.rounded()))
        let height = alignTo16(Int(rawHeight.rounded()))
        return (width, height)
    }

    /// Computes an 8-pixel aligned virtual display resolution matching Android `DisplayGeometryUtil.alignForEncoder(..., 8)`
    /// so standard 16:9 (`1920×1080`), 16:10 (`2560×1600`), and 21:9 (`2560×1080`) canvases preserve their exact aspect
    /// ratio without distorting `1080` into `1088`.
    public static func aspectExactAdaptiveResolution(
        viewSize: CGSize,
        backingScale: CGFloat = 1.0,
        maxLongEdge: Int = 2560
    ) -> (width: Int, height: Int) {
        let effectiveScale = max(backingScale, 1.0)
        var rawWidth = Double(viewSize.width * effectiveScale)
        var rawHeight = Double(viewSize.height * effectiveScale)

        guard rawWidth > 0, rawHeight > 0 else {
            return (1920, 1080)
        }

        let clampedMax = Double(min(max(maxLongEdge, minimumDimension), maximumDimension))
        let longEdge = max(rawWidth, rawHeight)
        if longEdge > clampedMax {
            let scaleDown = clampedMax / longEdge
            rawWidth *= scaleDown
            rawHeight *= scaleDown
        }

        let width = alignTo8(Int(rawWidth.rounded()))
        let height = alignTo8(Int(rawHeight.rounded()))
        return (width, height)
    }

    /// Rounds a pixel dimension to the nearest multiple of 16, clamped to encoder-safe bounds `[480, 3840]`.
    public static func alignTo16(_ value: Int) -> Int {
        let clamped = min(max(value, minimumDimension), maximumDimension)
        let rounded = ((clamped + (alignmentPixels / 2)) / alignmentPixels) * alignmentPixels
        return min(max(rounded, minimumDimension), maximumDimension)
    }

    /// Rounds a pixel dimension to the nearest multiple of 8, preserving `1080` (`135 * 8`) exactly.
    public static func alignTo8(_ value: Int) -> Int {
        let clamped = min(max(value, minimumDimension), maximumDimension)
        let rounded = ((clamped + (encoderAlignmentPixels / 2)) / encoderAlignmentPixels) * encoderAlignmentPixels
        return min(max(rounded, minimumDimension), maximumDimension)
    }

    /// Computes crisp 16-pixel-aligned resolution and matching phone/foldable/tablet Android DPI
    /// for a standalone popout window of `viewSize` (in macOS points).
    ///
    /// Keeps 1 macOS pt ≈ 1 Android dp so dragging a window from phone (`420×840`) to foldable (`640×820`)
    /// or tablet (`920×640`) triggers authentic responsive app layouts while maintaining consistent typography.
    public static func popoutStreamGeometry(
        viewSize: CGSize,
        backingScale: CGFloat = 2.0,
        maxLongEdge: Int = popoutDefaultMaxLongEdge
    ) -> PopoutStreamGeometry {
        let safeW = max(Double(viewSize.width), 300.0)
        let safeH = max(Double(viewSize.height), 420.0)

        // Ensure crisp rendering even on non-Retina 1x monitors by using at least 1.5x pixel density.
        let effectiveScale = max(Double(backingScale), 1.5)
        var rawWidth = safeW * effectiveScale
        var rawHeight = safeH * effectiveScale

        let clampedMax = Double(min(max(maxLongEdge, minimumDimension), maximumDimension))
        let longEdge = max(rawWidth, rawHeight)
        if longEdge > clampedMax {
            let ratio = clampedMax / longEdge
            rawWidth *= ratio
            rawHeight *= ratio
        }

        let width = alignTo16(Int(rawWidth.rounded()))
        let height = alignTo16(Int(rawHeight.rounded()))

        // Compute DPI from short edge so Android smallestWidthDp matches window short edge (clamped >= 360dp).
        let shortPx = Double(min(width, height))
        let shortPt = min(safeW, safeH)
        let targetShortDp = min(max(shortPt, 360.0), 900.0)
        let rawDpi = shortPx * 160.0 / targetShortDp
        let steppedDpi = Int((rawDpi / 20.0).rounded()) * 20
        let dpi = min(max(steppedDpi, popoutMinDPI), popoutMaxDPI)

        return PopoutStreamGeometry(width: width, height: height, dpi: dpi)
    }

    /// Computes a macOS window `contentView` size whose canvas region (above `taskbarHeight`)
    /// matches `targetVideoWidth : targetVideoHeight` with zero letterbox/pillarbox bars,
    /// preserving approximate canvas area while clamping to screen bounds and window minimums.
    public static func adaptedWindowContentSize(
        currentCanvasSize: CGSize,
        targetVideoWidth: Int,
        targetVideoHeight: Int,
        taskbarHeight: CGFloat,
        maxContentSize: CGSize,
        minContentSize: CGSize
    ) -> CGSize {
        let safeTaskbar = max(taskbarHeight, 0)
        let minW = max(minContentSize.width, 200)
        let minH = max(minContentSize.height - safeTaskbar, 120)
        let maxW = max(maxContentSize.width, minW)
        let maxH = max(maxContentSize.height - safeTaskbar, minH)

        guard targetVideoWidth > 0, targetVideoHeight > 0 else {
            let w = min(max(currentCanvasSize.width.rounded(), minW), maxW)
            let h = min(max(currentCanvasSize.height.rounded(), minH), maxH)
            return CGSize(width: w, height: h + safeTaskbar)
        }

        let aspect = CGFloat(targetVideoWidth) / CGFloat(targetVideoHeight)
        let fallbackArea: CGFloat = 1120 * 630
        let curW = max(currentCanvasSize.width, 0)
        let curH = max(currentCanvasSize.height, 0)
        let targetArea = max(curW * curH, fallbackArea)

        var canvasW = sqrt(targetArea * aspect)
        var canvasH = canvasW / aspect

        let downScale = min(1.0, min(maxW / max(canvasW, 1), maxH / max(canvasH, 1)))
        canvasW *= downScale
        canvasH *= downScale

        let upScale = max(1.0, max(minW / max(canvasW, 1), minH / max(canvasH, 1)))
        if upScale > 1.0 {
            let maxAllowedScale = min(maxW / max(canvasW, 1), maxH / max(canvasH, 1))
            let appliedScale = min(upScale, max(1.0, maxAllowedScale))
            canvasW *= appliedScale
            canvasH *= appliedScale
        }

        let finalW = min(max(canvasW.rounded(), minW), maxW)
        let finalH = min(max(canvasH.rounded(), minH), maxH)
        return CGSize(width: finalW, height: finalH + safeTaskbar)
    }
}

import AppKit
import ExtBridgeCore
import SwiftUI

/// Mac-native 120Hz magnetic split-screen seam, live drag-to-resize controller, quick seam toolbar,
/// and targeted in-slot App Picker rendered cleanly above the Android video canvas.
struct DeXSplitDividerOverlay: View {
    private enum Metrics {
        static let seamThickness: CGFloat = 10
        static let hitThickness: CGFloat = 18
        static let handleLength: CGFloat = 42
        static let handleThickness: CGFloat = 4
        static let minFraction: CGFloat = 0.25
        static let maxFraction: CGFloat = 0.75
        static let magneticSnapThreshold: CGFloat = 0.02
        static let magneticSnapPoints: [CGFloat] = [1.0 / 3.0, 0.5, 2.0 / 3.0]
        static let coordinateSpaceName = "DeXSplitOverlaySpace"
    }

    @Bindable var model: AppModel

    @State private var draggedPrimaryFraction: CGFloat? = nil
    @State private var isSnappedToAnchor: Bool = false
    @State private var isHoveringSeam: Bool = false
    @State private var isSeamToolbarVisible: Bool = false

    private var shouldRender: Bool {
        model.isTilingActive || model.pendingSplitPickSlot != nil
    }

    var body: some View {
        if shouldRender {
            GeometryReader { geo in
                let contentRect = CoordinateMapper.aspectFitRect(
                    videoWidth: model.currentVideoWidth,
                    videoHeight: model.currentVideoHeight,
                    in: geo.size
                )
                if contentRect.width > 40, contentRect.height > 40 {
                    overlayBody(in: contentRect)
                }
            }
            .coordinateSpace(name: Metrics.coordinateSpaceName)
            .onChange(of: model.activeTilingTopology) { _, _ in
                draggedPrimaryFraction = nil
                isSeamToolbarVisible = false
            }
            .onChange(of: model.pendingSplitPickSlot) { _, newSlot in
                if newSlot != nil {
                    isSeamToolbarVisible = false
                }
            }
            .onChange(of: model.activeDeXPanel) { _, panel in
                if panel != .none {
                    isSeamToolbarVisible = false
                }
            }
            .onDisappear {
                if isHoveringSeam {
                    NSCursor.arrow.set()
                    isHoveringSeam = false
                }
            }
        }
    }

    // MARK: - Main Overlay Composition

    @ViewBuilder
    private func overlayBody(in contentRect: CGRect) -> some View {
        let topology = model.effectiveSplitTopology
        let primaryFraction = resolvedPrimaryFraction(for: topology)
        let secondaryFraction = resolvedSecondaryFraction(for: topology)

        ZStack(alignment: .topLeading) {
            if model.isTilingActive || model.pendingSplitPickSlot != nil {
                seamLayer(
                    topology: topology,
                    contentRect: contentRect,
                    primaryFraction: primaryFraction,
                    secondaryFraction: secondaryFraction
                )
            }

            if let pickSlot = model.pendingSplitPickSlot {
                let slotRect = rectForSlot(
                    pickSlot,
                    topology: topology,
                    contentRect: contentRect,
                    primaryFraction: primaryFraction,
                    secondaryFraction: secondaryFraction
                )
                InSlotAppPickerCard(
                    model: model,
                    slotIndex: pickSlot,
                    slotTitle: topology.slotDisplayName(for: pickSlot)
                )
                .frame(width: max(slotRect.width - 14, 180), height: max(slotRect.height - 14, 200))
                .position(x: slotRect.midX, y: slotRect.midY)
                .transition(.opacity.combined(with: .scale(scale: 0.97)))
            }
        }
    }

    // MARK: - Seam Rendering & Drag Handling

    @ViewBuilder
    private func seamLayer(
        topology: ExtCastTilingTopology,
        contentRect: CGRect,
        primaryFraction: CGFloat,
        secondaryFraction: CGFloat
    ) -> some View {
        switch topology {
        case .split2H:
            let seamX = contentRect.minX + contentRect.width * primaryFraction
            interactivePrimarySeam(
                isVertical: true,
                position: CGPoint(x: seamX, y: contentRect.midY),
                length: contentRect.height,
                contentRect: contentRect,
                topology: topology,
                currentFraction: primaryFraction
            )

        case .split2V:
            let seamY = contentRect.minY + contentRect.height * primaryFraction
            interactivePrimarySeam(
                isVertical: false,
                position: CGPoint(x: contentRect.midX, y: seamY),
                length: contentRect.width,
                contentRect: contentRect,
                topology: topology,
                currentFraction: primaryFraction
            )

        case .split3Master:
            let seamX = contentRect.minX + contentRect.width * primaryFraction
            let rightWidth = max(contentRect.maxX - seamX, 0)
            let rightSeamY = contentRect.minY + contentRect.height * secondaryFraction

            // Passive right-pane horizontal charcoal seam covering Android's 8px gap
            Rectangle()
                .fill(DeXTheme.canvasBackground)
                .frame(width: rightWidth, height: Metrics.seamThickness)
                .position(x: seamX + rightWidth * 0.5, y: rightSeamY)
                .allowsHitTesting(false)

            interactivePrimarySeam(
                isVertical: true,
                position: CGPoint(x: seamX, y: contentRect.midY),
                length: contentRect.height,
                contentRect: contentRect,
                topology: topology,
                currentFraction: primaryFraction
            )

        case .split3Cols:
            let seam1X = contentRect.minX + contentRect.width * primaryFraction
            let seam2X = contentRect.minX + contentRect.width * secondaryFraction

            Rectangle()
                .fill(DeXTheme.canvasBackground)
                .frame(width: Metrics.seamThickness, height: contentRect.height)
                .position(x: seam2X, y: contentRect.midY)
                .allowsHitTesting(false)

            interactivePrimarySeam(
                isVertical: true,
                position: CGPoint(x: seam1X, y: contentRect.midY),
                length: contentRect.height,
                contentRect: contentRect,
                topology: topology,
                currentFraction: primaryFraction
            )

        case .grid2x2:
            let seamX = contentRect.minX + contentRect.width * primaryFraction
            let seamY = contentRect.minY + contentRect.height * secondaryFraction

            Rectangle()
                .fill(DeXTheme.canvasBackground)
                .frame(width: contentRect.width, height: Metrics.seamThickness)
                .position(x: contentRect.midX, y: seamY)
                .allowsHitTesting(false)

            interactivePrimarySeam(
                isVertical: true,
                position: CGPoint(x: seamX, y: contentRect.midY),
                length: contentRect.height,
                contentRect: contentRect,
                topology: topology,
                currentFraction: primaryFraction
            )

        case .exit:
            EmptyView()
        }
    }

    private func interactivePrimarySeam(
        isVertical: Bool,
        position: CGPoint,
        length: CGFloat,
        contentRect: CGRect,
        topology: ExtCastTilingTopology,
        currentFraction: CGFloat
    ) -> some View {
        let isDragging = draggedPrimaryFraction != nil
        let isInteractive = model.isTilingActive

        return ZStack {
            // Solid charcoal seam bar masking Android's 8px gap completely
            Rectangle()
                .fill(DeXTheme.canvasBackground)
                .frame(
                    width: isVertical ? Metrics.seamThickness : length,
                    height: isVertical ? length : Metrics.seamThickness
                )
                .allowsHitTesting(false)

            // Subtle 120Hz accent drag preview guide line
            if isDragging {
                Rectangle()
                    .fill(isSnappedToAnchor ? Color.accentColor : Color.white.opacity(0.45))
                    .frame(
                        width: isVertical ? 2 : length,
                        height: isVertical ? length : 2
                    )
                    .allowsHitTesting(false)
            }

            // Interactive seam strip & pill grabber handle
            ZStack {
                Color.clear
                    .contentShape(Rectangle())
                    .frame(
                        width: isVertical ? Metrics.hitThickness : length,
                        height: isVertical ? length : Metrics.hitThickness
                    )

                Capsule()
                    .fill(handleFillColor(isDragging: isDragging))
                    .frame(
                        width: isVertical
                            ? (isHoveringSeam || isDragging ? 5 : Metrics.handleThickness)
                            : (isHoveringSeam || isDragging ? 52 : Metrics.handleLength),
                        height: isVertical
                            ? (isHoveringSeam || isDragging ? 52 : Metrics.handleLength)
                            : (isHoveringSeam || isDragging ? 5 : Metrics.handleThickness)
                    )
                    .shadow(color: Color.black.opacity(0.45), radius: 4, y: 1)
                    .animation(.easeOut(duration: 0.14), value: isHoveringSeam)
                    .animation(.easeOut(duration: 0.14), value: isDragging)
            }
            .allowsHitTesting(isInteractive)
            .onHover { hovering in
                guard isInteractive else { return }
                isHoveringSeam = hovering
                if hovering {
                    (isVertical ? NSCursor.resizeLeftRight : NSCursor.resizeUpDown).set()
                } else {
                    NSCursor.arrow.set()
                }
            }
            .onTapGesture(count: 2) {
                guard isInteractive else { return }
                isSeamToolbarVisible = false
                model.updateSplitRatios(topology.defaultRatios)
                model.showToast("已重置分屏比例为默认均衡")
            }
            .onTapGesture(count: 1) {
                guard isInteractive else { return }
                withAnimation(.easeOut(duration: 0.16)) {
                    isSeamToolbarVisible.toggle()
                }
            }
            .gesture(
                DragGesture(minimumDistance: 4, coordinateSpace: .named(Metrics.coordinateSpaceName))
                    .onChanged { value in
                        guard isInteractive else { return }
                        if isSeamToolbarVisible { isSeamToolbarVisible = false }
                        let rawFraction: CGFloat
                        if isVertical {
                            rawFraction = (value.location.x - contentRect.minX) / contentRect.width
                        } else {
                            rawFraction = (value.location.y - contentRect.minY) / contentRect.height
                        }
                        let (snapped, didSnap) = clampAndSnap(rawFraction, for: topology)
                        draggedPrimaryFraction = snapped
                        isSnappedToAnchor = didSnap
                    }
                    .onEnded { value in
                        guard isInteractive else { return }
                        let rawFraction: CGFloat
                        if isVertical {
                            rawFraction = (value.location.x - contentRect.minX) / contentRect.width
                        } else {
                            rawFraction = (value.location.y - contentRect.minY) / contentRect.height
                        }
                        let (snapped, _) = clampAndSnap(rawFraction, for: topology)
                        draggedPrimaryFraction = nil
                        isSnappedToAnchor = false
                        commitPrimaryRatio(snapped, topology: topology)
                    }
            )

            // Live translucent ratio badge while dragging
            if isDragging {
                let leftPct = Int((currentFraction * 100).rounded())
                let rightPct = max(100 - leftPct, 0)
                Text("\(leftPct) : \(rightPct)")
                    .font(.system(size: 12, weight: .bold).monospacedDigit())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.regularMaterial, in: Capsule())
                    .overlay(
                        Capsule().strokeBorder(
                            isSnappedToAnchor ? Color.accentColor : Color.white.opacity(0.22),
                            lineWidth: isSnappedToAnchor ? 1.4 : 0.8
                        )
                    )
                    .shadow(color: Color.black.opacity(0.45), radius: 12, y: 4)
                    .offset(x: 0, y: isVertical ? -48 : -34)
                    .allowsHitTesting(false)
            }

            // Single-tap floating mini glass bar beside handle
            if isSeamToolbarVisible && !isDragging {
                seamMiniToolbar(topology: topology)
                    .offset(
                        x: isVertical ? 0 : 0,
                        y: isVertical ? -52 : -36
                    )
                    .transition(.opacity.combined(with: .scale(scale: 0.94)))
            }
        }
        .position(position)
    }

    private func handleFillColor(isDragging: Bool) -> Color {
        if isDragging && isSnappedToAnchor { return Color.accentColor }
        if isHoveringSeam || isDragging { return Color.white.opacity(0.95) }
        return Color.white.opacity(0.55)
    }

    private func seamMiniToolbar(topology: ExtCastTilingTopology) -> some View {
        let firstLabel = (topology == .split2V) ? "换上屏" : "换左屏"
        let secondLabel = (topology == .split2V) ? "换下屏" : "换右屏"

        return HStack(spacing: 4) {
            seamBarButton(firstLabel, icon: "square.grid.2x2") {
                isSeamToolbarVisible = false
                model.beginPickingApp(forSplitSlot: 0, topology: topology)
            }
            seamBarButton("互换", icon: "arrow.left.arrow.right") {
                isSeamToolbarVisible = false
                model.swapSplitSlots()
            }
            seamBarButton(secondLabel, icon: "square.grid.2x2") {
                isSeamToolbarVisible = false
                model.beginPickingApp(forSplitSlot: 1, topology: topology)
            }

            Rectangle()
                .fill(Color.white.opacity(0.16))
                .frame(width: 1, height: 14)
                .padding(.horizontal, 2)

            seamBarButton("退出", icon: "xmark", isDestructive: true) {
                isSeamToolbarVisible = false
                model.setWindowLayoutMode(.maximized)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background {
            ZStack {
                Capsule().fill(.regularMaterial)
                Capsule().fill(DeXTheme.popoverTint)
            }
        }
        .overlay(Capsule().strokeBorder(DeXTheme.hairlineBorder, lineWidth: 0.9))
        .shadow(color: Color.black.opacity(0.5), radius: 16, y: 6)
    }

    private func seamBarButton(
        _ title: String,
        icon: String,
        isDestructive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 10.5, weight: .semibold))
                Text(title).font(.system(size: 11, weight: .medium))
            }
            .foregroundStyle(isDestructive ? Color(red: 1.0, green: 0.45, blue: 0.43) : Color.white.opacity(0.92))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.white.opacity(0.08), in: Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Ratio Math & Slot Rectangles

    private func resolvedPrimaryFraction(for topology: ExtCastTilingTopology) -> CGFloat {
        if let dragged = draggedPrimaryFraction { return dragged }
        let ratios = model.deviceState.tilingRatios.isEmpty ? topology.defaultRatios : model.deviceState.tilingRatios
        let pct = ratios.first ?? 50
        return min(max(CGFloat(pct) / 100.0, 0.20), 0.80)
    }

    private func resolvedSecondaryFraction(for topology: ExtCastTilingTopology) -> CGFloat {
        let ratios = model.deviceState.tilingRatios.isEmpty ? topology.defaultRatios : model.deviceState.tilingRatios
        switch topology {
        case .split3Cols:
            let col0 = CGFloat(ratios.indices.contains(0) ? ratios[0] : 33) / 100.0
            let col1 = CGFloat(ratios.indices.contains(1) ? ratios[1] : 34) / 100.0
            return min(max(col0 + col1, 0.45), 0.85)
        case .grid2x2:
            let yPct = ratios.indices.contains(1) ? ratios[1] : 50
            return min(max(CGFloat(yPct) / 100.0, 0.25), 0.75)
        default:
            return 0.50
        }
    }

    private func clampAndSnap(_ raw: CGFloat, for topology: ExtCastTilingTopology) -> (CGFloat, Bool) {
        let lower: CGFloat = (topology == .split3Cols) ? 0.20 : Metrics.minFraction
        let upper: CGFloat = (topology == .split3Cols) ? 0.55 : Metrics.maxFraction
        let clamped = min(max(raw, lower), upper)
        for anchor in Metrics.magneticSnapPoints where abs(clamped - anchor) <= Metrics.magneticSnapThreshold {
            return (anchor, true)
        }
        return (clamped, false)
    }

    private func commitPrimaryRatio(_ fraction: CGFloat, topology: ExtCastTilingTopology) {
        let firstPct = Int((fraction * 100).rounded())
        switch topology {
        case .split2H, .split2V, .split3Master:
            let clamped = min(max(firstPct, 25), 75)
            model.updateSplitRatios([clamped, 100 - clamped])
        case .grid2x2:
            let clamped = min(max(firstPct, 25), 75)
            let yPct = model.deviceState.tilingRatios.indices.contains(1) ? model.deviceState.tilingRatios[1] : 50
            model.updateSplitRatios([clamped, yPct])
        case .split3Cols:
            let clamped0 = min(max(firstPct, 20), 55)
            let remaining = max(100 - clamped0, 40)
            let col1 = remaining / 2
            let col2 = remaining - col1
            model.updateSplitRatios([clamped0, col1, col2])
        case .exit:
            break
        }
    }

    private func rectForSlot(
        _ index: Int,
        topology: ExtCastTilingTopology,
        contentRect: CGRect,
        primaryFraction: CGFloat,
        secondaryFraction: CGFloat
    ) -> CGRect {
        switch topology {
        case .split2H, .exit:
            let leftW = contentRect.width * primaryFraction
            return index == 0
                ? CGRect(x: contentRect.minX, y: contentRect.minY, width: leftW, height: contentRect.height)
                : CGRect(x: contentRect.minX + leftW, y: contentRect.minY, width: contentRect.width - leftW, height: contentRect.height)

        case .split2V:
            let topH = contentRect.height * primaryFraction
            return index == 0
                ? CGRect(x: contentRect.minX, y: contentRect.minY, width: contentRect.width, height: topH)
                : CGRect(x: contentRect.minX, y: contentRect.minY + topH, width: contentRect.width, height: contentRect.height - topH)

        case .split3Master:
            let leftW = contentRect.width * primaryFraction
            let rightW = contentRect.width - leftW
            let rightTopH = contentRect.height * secondaryFraction
            switch index {
            case 0:
                return CGRect(x: contentRect.minX, y: contentRect.minY, width: leftW, height: contentRect.height)
            case 1:
                return CGRect(x: contentRect.minX + leftW, y: contentRect.minY, width: rightW, height: rightTopH)
            default:
                return CGRect(x: contentRect.minX + leftW, y: contentRect.minY + rightTopH, width: rightW, height: contentRect.height - rightTopH)
            }

        case .split3Cols:
            let w0 = contentRect.width * primaryFraction
            let x2 = contentRect.width * secondaryFraction
            let w1 = max(x2 - w0, 0)
            let w2 = max(contentRect.width - x2, 0)
            switch index {
            case 0:
                return CGRect(x: contentRect.minX, y: contentRect.minY, width: w0, height: contentRect.height)
            case 1:
                return CGRect(x: contentRect.minX + w0, y: contentRect.minY, width: w1, height: contentRect.height)
            default:
                return CGRect(x: contentRect.minX + x2, y: contentRect.minY, width: w2, height: contentRect.height)
            }

        case .grid2x2:
            let leftW = contentRect.width * primaryFraction
            let rightW = contentRect.width - leftW
            let topH = contentRect.height * secondaryFraction
            let bottomH = contentRect.height - topH
            switch index {
            case 0: return CGRect(x: contentRect.minX, y: contentRect.minY, width: leftW, height: topH)
            case 1: return CGRect(x: contentRect.minX + leftW, y: contentRect.minY, width: rightW, height: topH)
            case 2: return CGRect(x: contentRect.minX, y: contentRect.minY + topH, width: leftW, height: bottomH)
            default: return CGRect(x: contentRect.minX + leftW, y: contentRect.minY + topH, width: rightW, height: bottomH)
            }
        }
    }
}

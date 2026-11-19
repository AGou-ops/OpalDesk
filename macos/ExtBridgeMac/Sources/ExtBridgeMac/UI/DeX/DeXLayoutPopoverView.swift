import ExtBridgeCore
import SwiftUI

/// Visual Snap Layout card picker & active split-screen controller anchored above the DeX taskbar.
/// Draws clean geometric layout schematics (`铺满`, `居中手机`, `左右分屏`, `1+2 三屏`, plus compact
/// secondary topologies), slot app pickers with one-click swap, and quick ratio chips.
struct DeXLayoutPopoverView: View {
    private static let twoWayRatioPresets: [(label: String, ratios: [Int])] = [
        ("3:7", [30, 70]),
        ("4:6", [40, 60]),
        ("5:5", [50, 50]),
        ("6:4", [60, 40]),
        ("7:3", [70, 30])
    ]

    private static let threeColRatioPresets: [(label: String, ratios: [Int])] = [
        ("均分", [33, 34, 33]),
        ("中宽", [25, 50, 25]),
        ("左宽", [40, 30, 30])
    ]

    private static let centeredWidthPresets: [(label: String, pct: Int)] = [
        ("34% 窄手机", 34),
        ("42% 黄金", 42),
        ("52% 大屏", 52),
        ("70% 平板", 70)
    ]

    @Bindable var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            headerSection
            primaryLayoutGrid
            secondaryTopologiesRow

            if let topology = model.activeTilingTopology {
                Divider().overlay(Color.white.opacity(0.10))
                activeSplitSection(topology: topology)
            } else if model.deviceState.adaptiveSingleEnabled {
                Divider().overlay(Color.white.opacity(0.10))
                centeredPhoneWidthSection
            }
        }
        .padding(16)
        .frame(width: 356)
        .dexPopoverSurface()
    }

    // MARK: - Header

    private var headerSection: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("窗口布局与分屏")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(.white)
                Text(currentModeSubtitle)
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.60))
            }
            Spacer()
            if !model.isTilingActive {
                Button {
                    model.startQuickTwoSplit()
                } label: {
                    Label("快捷分屏", systemImage: "rectangle.split.2x1")
                        .font(.system(size: 11, weight: .semibold))
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background(Color.accentColor.opacity(0.22), in: Capsule())
                        .foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.plain)
                .help("一键将当前应用进入左右分屏 (⌘⇧S)")
            }
        }
    }

    private var currentModeSubtitle: String {
        switch model.currentDeXWindowMode {
        case .maximized: return "当前：全屏铺满工作区"
        case .centeredPhone: return "当前：居中手机宽幅 (\(model.deviceState.defaultSingleWidthDp)dp)"
        case .tiling(let topology): return "当前：\(topology.displayName)"
        }
    }

    // MARK: - 4 Visual Geometric Snap Layout Cards

    private var primaryLayoutGrid: some View {
        let columns = [
            GridItem(.flexible(), spacing: 10),
            GridItem(.flexible(), spacing: 10)
        ]

        return LazyVGrid(columns: columns, spacing: 10) {
            LayoutSchematicCard(
                title: "铺满",
                isSelected: model.currentDeXWindowMode == .maximized,
                schematic: .maximized
            ) {
                model.setWindowLayoutMode(.maximized)
            }

            LayoutSchematicCard(
                title: "居中手机",
                isSelected: model.currentDeXWindowMode == .centeredPhone,
                schematic: .centeredPhone
            ) {
                model.setWindowLayoutMode(.centeredPhone)
            }

            LayoutSchematicCard(
                title: "左右分屏",
                isSelected: model.currentDeXWindowMode == .tiling(.split2H),
                schematic: .split2H
            ) {
                model.setWindowLayoutMode(.tiling(.split2H))
            }

            LayoutSchematicCard(
                title: "1+2 三屏",
                isSelected: model.currentDeXWindowMode == .tiling(.split3Master),
                schematic: .split3Master
            ) {
                model.setWindowLayoutMode(.tiling(.split3Master))
            }
        }
    }

    // MARK: - Secondary Topologies Row

    private var secondaryTopologiesRow: some View {
        HStack(spacing: 8) {
            secondaryTopologyChip(title: "上下分屏", icon: "rectangle.split.1x2", topology: .split2V)
            secondaryTopologyChip(title: "三列分屏", icon: "rectangle.split.3x1", topology: .split3Cols)
            secondaryTopologyChip(title: "2×2 四宫格", icon: "square.grid.2x2", topology: .grid2x2)
        }
    }

    private func secondaryTopologyChip(
        title: String,
        icon: String,
        topology: ExtCastTilingTopology
    ) -> some View {
        let selected = model.activeTilingTopology == topology
        return Button {
            model.setWindowLayoutMode(.tiling(topology))
        } label: {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 11, weight: .semibold))
                Text(title)
                    .font(.system(size: 11, weight: .medium))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 7)
            .foregroundStyle(selected ? .white : .white.opacity(0.80))
            .background(
                selected ? Color.accentColor.opacity(0.75) : DeXTheme.subtleSurface,
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(selected ? Color.white.opacity(0.22) : Color.white.opacity(0.08), lineWidth: 0.8)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Active Split Slot Strip & Ratio Controls

    private func activeSplitSection(topology: ExtCastTilingTopology) -> some View {
        let slots = model.effectiveTilingSlots
        let slotCount = topology.slotCount

        return VStack(alignment: .leading, spacing: 11) {
            Text("当前分屏应用（点击槽位切换）")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.white.opacity(0.62))

            if slotCount == 2 {
                HStack(spacing: 6) {
                    slotPickerPill(index: 0, topology: topology, slots: slots)
                    swapButton
                    slotPickerPill(index: 1, topology: topology, slots: slots)
                }
            } else {
                VStack(spacing: 6) {
                    HStack(spacing: 6) {
                        slotPickerPill(index: 0, topology: topology, slots: slots)
                        swapButton
                        slotPickerPill(index: 1, topology: topology, slots: slots)
                    }
                    HStack(spacing: 6) {
                        ForEach(2..<slotCount, id: \.self) { idx in
                            slotPickerPill(index: idx, topology: topology, slots: slots)
                        }
                    }
                }
            }

            HStack(spacing: 6) {
                let presets = (topology == .split3Cols) ? Self.threeColRatioPresets : Self.twoWayRatioPresets
                ForEach(presets, id: \.label) { preset in
                    let isSelected = isActiveRatio(preset.ratios, topology: topology)
                    Button(preset.label) {
                        model.updateSplitRatios(preset.ratios)
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 11, weight: isSelected ? .semibold : .medium).monospacedDigit())
                    .foregroundStyle(isSelected ? .white : .white.opacity(0.78))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .background(
                        isSelected ? Color.accentColor.opacity(0.78) : DeXTheme.subtleSurface,
                        in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                    )
                }
            }

            Button(role: .destructive) {
                model.setWindowLayoutMode(.maximized)
            } label: {
                Label("退出分屏，恢复铺满桌面", systemImage: "rectangle.arrowtriangle.2.outward")
                    .font(.system(size: 11.5, weight: .semibold))
                    .foregroundStyle(Color(red: 1.0, green: 0.45, blue: 0.43))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 7)
                    .background(Color.red.opacity(0.14), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            .buttonStyle(.plain)
        }
    }

    private func slotPickerPill(
        index: Int,
        topology: ExtCastTilingTopology,
        slots: [String]
    ) -> some View {
        let pkg = slots.indices.contains(index) ? slots[index] : ""
        let app = model.appInfo(forPackage: pkg)

        return Button {
            model.beginPickingApp(forSplitSlot: index, topology: topology)
        } label: {
            HStack(spacing: 6) {
                if let app {
                    DeXAppIconView(app: app, size: 18, cornerRadius: 5)
                    Text(app.displayLabel)
                        .font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(.white.opacity(0.92))
                        .lineLimit(1)
                } else {
                    Image(systemName: "plus.app")
                        .font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.6))
                    Text(topology.slotDisplayName(for: index))
                        .font(.system(size: 11.5))
                        .foregroundStyle(.white.opacity(0.62))
                        .lineLimit(1)
                }
                Spacer(minLength: 2)
                Image(systemName: "chevron.down")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.5))
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity)
            .background(DeXTheme.subtleSurface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.10), lineWidth: 0.8)
            )
        }
        .buttonStyle(.plain)
        .help("点击替换\(topology.slotDisplayName(for: index))应用")
    }

    private var swapButton: some View {
        Button {
            model.swapSplitSlots()
        } label: {
            Image(systemName: "arrow.left.arrow.right")
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(.white.opacity(0.88))
                .frame(width: 28, height: 28)
                .background(Color.white.opacity(0.10), in: Circle())
        }
        .buttonStyle(.plain)
        .help("互换分屏应用位置")
        .accessibilityLabel("互换分屏应用")
    }

    private func isActiveRatio(_ candidate: [Int], topology: ExtCastTilingTopology) -> Bool {
        let current = model.deviceState.tilingRatios.isEmpty ? topology.defaultRatios : model.deviceState.tilingRatios
        guard current.count == candidate.count else { return false }
        return zip(current, candidate).allSatisfy { abs($0 - $1) <= 3 }
    }

    // MARK: - Centered Phone Width Presets

    private var centeredPhoneWidthSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("居中手机窗宽幅快选")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.white.opacity(0.62))

            let columns = [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)]
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(Self.centeredWidthPresets, id: \.pct) { item in
                    Button(item.label) {
                        model.setSingleWidthPreset(widthPct: item.pct, remember: true)
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(.white.opacity(0.88))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 7)
                    .background(DeXTheme.subtleSurface, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.08), lineWidth: 0.8)
                    )
                }
            }
        }
    }
}

// MARK: - Geometric Snap Layout Card

private struct LayoutSchematicCard: View {
    enum Schematic {
        case maximized
        case centeredPhone
        case split2H
        case split3Master
    }

    let title: String
    let isSelected: Bool
    let schematic: Schematic
    let action: () -> Void

    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                schematicDiagram
                    .frame(height: 44)
                    .padding(6)
                    .background(Color.black.opacity(0.28), in: RoundedRectangle(cornerRadius: 8, style: .continuous))

                Text(title)
                    .font(.system(size: 12, weight: isSelected ? .semibold : .medium))
                    .foregroundStyle(isSelected ? .white : .white.opacity(0.84))
            }
            .padding(10)
            .frame(maxWidth: .infinity)
            .background(cardFill, in: RoundedRectangle(cornerRadius: DeXTheme.tileCornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: DeXTheme.tileCornerRadius, style: .continuous)
                    .strokeBorder(
                        isSelected ? Color.accentColor : Color.white.opacity(0.10),
                        lineWidth: isSelected ? 1.5 : 0.8
                    )
            )
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
    }

    private var cardFill: Color {
        if isSelected {
            return Color.accentColor.opacity(0.20)
        }
        return isHovered ? DeXTheme.hoverSurface : DeXTheme.subtleSurface
    }

    private var paneFill: Color {
        isSelected ? Color.accentColor.opacity(0.85) : Color.white.opacity(0.32)
    }

    private var secondaryPaneFill: Color {
        isSelected ? Color.accentColor.opacity(0.55) : Color.white.opacity(0.22)
    }

    @ViewBuilder
    private var schematicDiagram: some View {
        switch schematic {
        case .maximized:
            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(paneFill)

        case .centeredPhone:
            GeometryReader { geo in
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(paneFill)
                    .frame(width: geo.size.width * 0.44, height: geo.size.height)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

        case .split2H:
            HStack(spacing: 3.5) {
                RoundedRectangle(cornerRadius: 4, style: .continuous).fill(paneFill)
                RoundedRectangle(cornerRadius: 4, style: .continuous).fill(secondaryPaneFill)
            }

        case .split3Master:
            HStack(spacing: 3.5) {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(paneFill)
                    .frame(maxWidth: .infinity)
                VStack(spacing: 3.5) {
                    RoundedRectangle(cornerRadius: 3.5, style: .continuous).fill(secondaryPaneFill)
                    RoundedRectangle(cornerRadius: 3.5, style: .continuous).fill(secondaryPaneFill)
                }
                .frame(maxWidth: .infinity)
            }
        }
    }
}

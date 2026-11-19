import ExtBridgeCore
import SwiftUI

/// One UI / Samsung DeX Quick Settings popover anchored above the bottom-right system tray.
/// Groups connection status, 2×2 tactile quick-toggle tiles, display parameters (resolution, DPI, FPS,
/// DPI reload, HUD toggle), preferences shortcut, and disconnect action.
struct DeXQuickSettingsPanel: View {
    @Bindable var model: AppModel

    var body: some View {
        @Bindable var prefs = model.preferences

        return VStack(alignment: .leading, spacing: 14) {
            deviceHeader
            quickToggleGrid
            displaySettingsCard(prefs: prefs)
            bottomActionsRow
        }
        .padding(16)
        .frame(width: 320)
        .dexPopoverSurface()
    }

    // MARK: - Header

    private var deviceHeader: some View {
        HStack(alignment: .center, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .fill(Color.accentColor.opacity(0.18))
                    .frame(width: 38, height: 38)
                Image(systemName: model.activeConnection?.transport.systemImage ?? "iphone")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color.accentColor)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(model.activeConnection?.deviceName ?? "Android 设备")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)

                HStack(spacing: 6) {
                    if let transport = model.activeConnection?.transport {
                        StatusBadgeView(
                            text: transport.shortBadge,
                            color: transport.badgeColor,
                            font: .system(size: 10, weight: .semibold),
                            horizontalPadding: 6,
                            verticalPadding: 1.5,
                            backgroundOpacity: 0.20
                        )
                    }
                    if model.deviceState.batteryPct >= 0 {
                        Text("\(model.deviceState.batteryPct)%")
                            .font(.system(size: 11).monospacedDigit())
                            .foregroundStyle(.white.opacity(0.72))
                    }
                    Text("\(Int(model.rttMs.rounded())) ms")
                        .font(.system(size: 11).monospacedDigit())
                        .foregroundStyle(.white.opacity(0.62))
                }
            }

            Spacer(minLength: 4)
        }
    }

    // MARK: - 2x2 One UI Quick Toggle Tiles

    private var quickToggleGrid: some View {
        let columns = [
            GridItem(.flexible(), spacing: 10),
            GridItem(.flexible(), spacing: 10)
        ]

        return LazyVGrid(columns: columns, spacing: 10) {
            OneUIQuickTile(
                icon: model.isBlackoutActive ? "iphone.slash" : "iphone",
                title: "熄灭手机屏",
                subtitle: model.isBlackoutActive ? "已熄屏防烧屏" : "保持亮屏",
                isActive: model.isBlackoutActive
            ) {
                model.toggleBlackout()
            }

            OneUIQuickTile(
                icon: model.isAudioMuted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                title: "静音音频",
                subtitle: model.isAudioMuted ? "已静音" : "回传已开启",
                isActive: model.isAudioMuted
            ) {
                model.toggleAudio()
            }

            OneUIQuickTile(
                icon: model.displayMode == .dex ? "macwindow" : "rectangle.on.rectangle",
                title: "DeX · 镜像",
                subtitle: model.displayMode == .dex ? "扩展桌面" : "屏幕镜像",
                isActive: model.displayMode == .dex
            ) {
                model.toggleDisplayMode()
            }

            OneUIQuickTile(
                icon: "rotate.right",
                title: "旋转 90°",
                subtitle: "当前 \(model.deviceState.rotation * 90)°",
                isActive: false
            ) {
                model.rotateClockwise()
            }
        }
    }

    // MARK: - Compact Display Settings Card

    private func displaySettingsCard(prefs: PreferencesStore) -> some View {
        @Bindable var bindablePrefs = prefs

        return VStack(spacing: 10) {
            settingRow(title: "画面分辨率") {
                Picker("画面分辨率", selection: Binding(
                    get: { model.selectedPreset },
                    set: { model.selectResolutionPreset($0) }
                )) {
                    ForEach(DisplayResolutionPreset.allCases) { preset in
                        Text(shortTitle(for: preset)).tag(preset)
                    }
                }
                .labelsHidden()
                .pickerStyle(.menu)
                .controlSize(.small)
                .frame(maxWidth: 144, alignment: .trailing)
            }

            Divider().overlay(Color.white.opacity(0.08))

            settingRow(title: "显示密度") {
                Picker("显示密度", selection: Binding(
                    get: { model.selectedDPI },
                    set: { model.selectDPI($0) }
                )) {
                    ForEach(DisplayPresets.dpiOptions, id: \.self) { dpi in
                        Text("\(dpi) DPI").tag(dpi)
                    }
                }
                .labelsHidden()
                .pickerStyle(.menu)
                .controlSize(.small)
                .frame(maxWidth: 110, alignment: .trailing)
            }

            Divider().overlay(Color.white.opacity(0.08))

            settingRow(title: "刷新率") {
                HStack(spacing: 4) {
                    ForEach(DisplayPresets.fpsOptions, id: \.self) { fps in
                        let selected = prefs.targetFPS == fps
                        Button("\(fps)") {
                            model.applyTargetFPS(fps)
                        }
                        .buttonStyle(.plain)
                        .font(.system(size: 11, weight: selected ? .semibold : .medium).monospacedDigit())
                        .foregroundStyle(selected ? .white : .white.opacity(0.72))
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(
                            selected ? Color.accentColor : Color.white.opacity(0.08),
                            in: Capsule()
                        )
                    }
                }
            }

            Divider().overlay(Color.white.opacity(0.08))

            HStack {
                Button {
                    model.reloadCurrentAppDPI()
                    model.activeDeXPanel = .none
                } label: {
                    Label("重载当前 App DPI", systemImage: "textformat.size")
                        .font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(
                            model.deviceState.topAppPackage.isEmpty
                                ? Color.white.opacity(0.35)
                                : Color.white.opacity(0.90)
                        )
                }
                .buttonStyle(.plain)
                .disabled(model.deviceState.topAppPackage.isEmpty)

                Spacer()

                if !model.deviceState.topAppLabel.isEmpty {
                    Text(model.deviceState.topAppLabel)
                        .font(.system(size: 10.5))
                        .foregroundStyle(.white.opacity(0.5))
                        .lineLimit(1)
                }
            }

            Divider().overlay(Color.white.opacity(0.08))

            Toggle(isOn: $bindablePrefs.showHUDStats) {
                Text("性能浮标 (HUD)")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white.opacity(0.88))
            }
            .toggleStyle(.switch)
            .controlSize(.small)
        }
        .padding(12)
        .background(DeXTheme.subtleSurface, in: RoundedRectangle(cornerRadius: DeXTheme.tileCornerRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: DeXTheme.tileCornerRadius, style: .continuous)
                .strokeBorder(Color.white.opacity(0.08), lineWidth: 0.8)
        )
    }

    private func settingRow<Control: View>(title: String, @ViewBuilder control: () -> Control) -> some View {
        HStack {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.white.opacity(0.84))
            Spacer()
            control()
        }
    }

    private func shortTitle(for preset: DisplayResolutionPreset) -> String {
        switch preset {
        case .matchWindow: return "匹配窗口"
        case .fhd1080p: return "1080p"
        case .qhd2K: return "2K (1440p)"
        case .ultrawide21x9: return "21:9 带鱼屏"
        case .portrait: return "竖屏 1080p"
        }
    }

    // MARK: - Bottom Action Row

    private var bottomActionsRow: some View {
        HStack(spacing: 10) {
            SettingsLink {
                Label("偏好设置 (⌘,)", systemImage: "gearshape")
                    .font(.system(size: 11.5, weight: .medium))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 7)
                    .background(DeXTheme.subtleSurface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white.opacity(0.88))
            .simultaneousGesture(TapGesture().onEnded {
                model.activeDeXPanel = .none
            })

            Button(role: .destructive) {
                model.activeDeXPanel = .none
                model.disconnect()
            } label: {
                Label("断开连接", systemImage: "power")
                    .font(.system(size: 11.5, weight: .semibold))
                    .foregroundStyle(Color(red: 1.0, green: 0.42, blue: 0.40))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(Color.red.opacity(0.16), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - One UI Tactile Toggle Tile

private struct OneUIQuickTile: View {
    let icon: String
    let title: String
    let subtitle: String
    let isActive: Bool
    let action: () -> Void

    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Image(systemName: icon)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(isActive ? .white : .white.opacity(0.86))
                    Spacer()
                }
                VStack(alignment: .leading, spacing: 1.5) {
                    Text(title)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.system(size: 10.5))
                        .foregroundStyle(isActive ? .white.opacity(0.82) : .white.opacity(0.56))
                        .lineLimit(1)
                }
            }
            .padding(11)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tileFill, in: RoundedRectangle(cornerRadius: DeXTheme.tileCornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: DeXTheme.tileCornerRadius, style: .continuous)
                    .strokeBorder(
                        isActive ? Color.white.opacity(0.20) : Color.white.opacity(0.08),
                        lineWidth: 0.8
                    )
            )
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
    }

    private var tileFill: Color {
        if isActive {
            return isHovered ? Color.accentColor.opacity(0.92) : Color.accentColor.opacity(0.80)
        }
        return isHovered ? DeXTheme.hoverSurface : DeXTheme.subtleSurface
    }
}

import AppKit
import ExtBridgeCore
import SwiftUI

/// Samsung DeX frosted 5-column real-icon App Drawer (`⌘K` or 9-dot taskbar trigger).
/// Supports live fuzzy search, keyboard navigation, single-click launch in DeX, `⌘+click`
/// standalone popout, and right-click split/window placement actions.
struct DeXAppDrawerView: View {
    private static let columnCount: Int = 5

    @Bindable var model: AppModel
    @State private var query: String = ""
    @State private var selectedIndex: Int = 0
    @FocusState private var isSearchFocused: Bool

    private var filteredApps: [RemoteAppInfo] {
        AppFuzzyMatcher.filter(model.remoteApps, query: query)
    }

    var body: some View {
        VStack(spacing: 0) {
            searchHeader
            Divider().overlay(Color.white.opacity(0.08))
            drawerGridBody
            Divider().overlay(Color.white.opacity(0.08))
            footerBar
        }
        .frame(maxWidth: 660)
        .frame(maxHeight: 480)
        .dexPopoverSurface()
        .onAppear {
            selectedIndex = 0
            isSearchFocused = true
        }
        .onChange(of: query) { _, _ in selectedIndex = 0 }
        .onKeyPress(.escape) {
            model.activeDeXPanel = .none
            return .handled
        }
        .onKeyPress(.leftArrow) {
            moveSelection(by: -1)
            return .handled
        }
        .onKeyPress(.rightArrow) {
            moveSelection(by: 1)
            return .handled
        }
        .onKeyPress(.upArrow) {
            moveSelection(by: -Self.columnCount)
            return .handled
        }
        .onKeyPress(.downArrow) {
            moveSelection(by: Self.columnCount)
            return .handled
        }
        .onKeyPress(.return) {
            launchSelectedApp()
            return .handled
        }
    }

    // MARK: - Search Header

    private var searchHeader: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white.opacity(0.62))

                TextField("搜索手机应用名称或包名...", text: $query)
                    .textFieldStyle(.plain)
                    .font(.system(size: 13.5))
                    .foregroundStyle(.white)
                    .focused($isSearchFocused)

                if !query.isEmpty {
                    Button { query = "" } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 13))
                            .foregroundStyle(.white.opacity(0.55))
                    }
                    .buttonStyle(.plain)
                    .help("清除搜索")
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.white.opacity(0.08), in: Capsule())
            .overlay(Capsule().strokeBorder(Color.white.opacity(0.10), lineWidth: 0.8))

            if model.isLoadingApps {
                ProgressView()
                    .controlSize(.small)
                    .frame(width: 28, height: 28)
            } else {
                Button { model.requestAppList(force: true) } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 12.5, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.75))
                        .frame(width: 30, height: 30)
                        .background(Color.white.opacity(0.07), in: Circle())
                }
                .buttonStyle(.plain)
                .help("刷新已安装应用")
                .accessibilityLabel("刷新已安装应用")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    // MARK: - 5-Column Grid Body

    @ViewBuilder
    private var drawerGridBody: some View {
        let apps = filteredApps
        if apps.isEmpty {
            emptyStateView
        } else {
            let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: Self.columnCount)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(Array(apps.enumerated()), id: \.element.id) { index, app in
                            DeXAppGridTile(
                                app: app,
                                isSelected: index == selectedIndex,
                                onPrimaryClick: { handleTileClick(app) },
                                onOpenInDeX: { model.launchAppInDeX(app) },
                                onOpenCentered: { model.launchApp(app, mode: .adaptive) },
                                onPlaceLeft: { model.placeAppInSplitSlot(app, slot: 0) },
                                onPlaceRight: { model.placeAppInSplitSlot(app, slot: 1) },
                                onPopout: { model.popoutApp(app) }
                            )
                            .id(app.id)
                        }
                    }
                    .padding(16)
                }
                .frame(minHeight: 200, maxHeight: 372)
                .onChange(of: selectedIndex) { _, newIdx in
                    guard apps.indices.contains(newIdx) else { return }
                    withAnimation(.easeOut(duration: 0.12)) {
                        proxy.scrollTo(apps[newIdx].id, anchor: .center)
                    }
                }
            }
        }
    }

    private var emptyStateView: some View {
        VStack(spacing: 10) {
            if model.isLoadingApps {
                ProgressView().controlSize(.regular).tint(.white)
                Text("正在从手机加载应用列表...")
                    .font(.callout)
                    .foregroundStyle(.white.opacity(0.72))
            } else if model.remoteApps.isEmpty {
                Image(systemName: "square.grid.3x3")
                    .font(.system(size: 28))
                    .foregroundStyle(.white.opacity(0.5))
                Text("暂未获取到手机应用")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white.opacity(0.88))
                Button("重新加载") { model.requestAppList(force: true) }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
            } else {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 26))
                    .foregroundStyle(.white.opacity(0.5))
                Text("未找到与「\(query)」匹配的应用")
                    .font(.callout)
                    .foregroundStyle(.white.opacity(0.72))
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 260)
    }

    // MARK: - Footer

    private var footerBar: some View {
        HStack {
            Text("单击打开 · 右键分屏或弹出独立窗 · ⌘K 收起")
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.58))
            Spacer()
            Text("共 \(filteredApps.count) 个应用")
                .font(.system(size: 11).monospacedDigit())
                .foregroundStyle(.white.opacity(0.52))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 9)
    }

    // MARK: - Actions

    private func handleTileClick(_ app: RemoteAppInfo) {
        if NSEvent.modifierFlags.contains(.command) {
            model.popoutApp(app)
        } else {
            model.launchAppInDeX(app)
        }
    }

    private func moveSelection(by delta: Int) {
        let count = filteredApps.count
        guard count > 0 else { return }
        selectedIndex = min(max(selectedIndex + delta, 0), count - 1)
    }

    private func launchSelectedApp() {
        let apps = filteredApps
        guard !apps.isEmpty else { return }
        let safeIndex = min(max(selectedIndex, 0), apps.count - 1)
        model.launchAppInDeX(apps[safeIndex])
    }
}

// MARK: - Individual App Tile

private struct DeXAppGridTile: View {
    let app: RemoteAppInfo
    let isSelected: Bool
    let onPrimaryClick: () -> Void
    let onOpenInDeX: () -> Void
    let onOpenCentered: () -> Void
    let onPlaceLeft: () -> Void
    let onPlaceRight: () -> Void
    let onPopout: () -> Void

    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: onPrimaryClick) {
            VStack(spacing: 7) {
                ZStack(alignment: .bottomTrailing) {
                    DeXAppIconView(app: app, size: 48, cornerRadius: 12)
                    if app.runningOnExt {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 8, height: 8)
                            .overlay(Circle().stroke(DeXTheme.canvasBackground, lineWidth: 1.5))
                            .offset(x: 2, y: 2)
                    }
                }

                Text(app.displayLabel)
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(.white.opacity(0.92))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .padding(.horizontal, 6)
            .background(tileBackground, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .strokeBorder(isSelected ? Color.accentColor.opacity(0.65) : Color.clear, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .help("\(app.displayLabel)（⌘+单击弹出独立窗口）")
        .contextMenu {
            Button("在当前桌面打开", systemImage: "macwindow", action: onOpenInDeX)
            Button("居中手机窗打开", systemImage: "iphone", action: onOpenCentered)
            Divider()
            Button("放到左侧分屏", systemImage: "rectangle.lefthalf.filled", action: onPlaceLeft)
            Button("放到右侧分屏", systemImage: "rectangle.righthalf.filled", action: onPlaceRight)
            Divider()
            Button("弹出独立小窗", systemImage: "macwindow.badge.plus", action: onPopout)
        }
    }

    private var tileBackground: Color {
        if isSelected {
            return Color.accentColor.opacity(0.22)
        }
        return isHovered ? DeXTheme.hoverSurface : Color.clear
    }
}

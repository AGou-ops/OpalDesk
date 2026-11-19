import ExtBridgeCore
import SwiftUI

/// Spotlight / Raycast-style translucent phone app quick launcher (`⌘K`).
/// Queries `0x2A LIST_APPS`, filters installed & running Android apps via `AppFuzzyMatcher`,
/// and offers one-click "弹出独立窗（推荐）", "居中宽幅打开", and "自由窗口打开".
public struct QuickLauncherView: View {
    @Bindable public var model: AppModel

    @State private var query: String = ""
    @State private var selectedIndex: Int = 0
    @FocusState private var isSearchFocused: Bool

    public init(model: AppModel) {
        self.model = model
    }

    private var filteredApps: [RemoteAppInfo] {
        AppFuzzyMatcher.filter(model.remoteApps, query: query)
    }

    public var body: some View {
        ZStack {
            Color.black.opacity(0.38)
                .ignoresSafeArea()
                .onTapGesture(perform: dismiss)

            VStack(spacing: 0) {
                searchHeader
                Divider().opacity(0.6)
                resultsBody
                Divider().opacity(0.6)
                footerBar
            }
            .frame(width: 640)
            .frame(maxHeight: 460)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.18), lineWidth: 1)
            )
            .shadow(color: Color.black.opacity(0.45), radius: 30, y: 14)
        }
        .onAppear {
            selectedIndex = 0
            isSearchFocused = true
        }
        .onChange(of: query) { _, _ in selectedIndex = 0 }
        .onKeyPress(.escape) { dismiss(); return .handled }
        .onKeyPress(.downArrow) { moveSelection(by: 1); return .handled }
        .onKeyPress(.upArrow) { moveSelection(by: -1); return .handled }
        .onKeyPress(.return) {
            let apps = filteredApps
            guard !apps.isEmpty else { return .ignored }
            model.popoutApp(apps[min(max(selectedIndex, 0), apps.count - 1)])
            return .handled
        }
    }

    // MARK: - Header

    private var searchHeader: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(.secondary)

            TextField("搜索手机应用名称或包名（回车默认弹出独立窗）...", text: $query)
                .textFieldStyle(.plain)
                .font(.system(size: 15))
                .focused($isSearchFocused)

            if !query.isEmpty {
                Button { query = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help("清除搜索")
            }

            if model.isLoadingApps {
                ProgressView().controlSize(.small)
            } else {
                Button { model.requestAppList() } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help("重新扫描手机已装应用")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }

    // MARK: - Results List

    @ViewBuilder
    private var resultsBody: some View {
        let apps = filteredApps
        if apps.isEmpty {
            VStack(spacing: 10) {
                if model.isLoadingApps {
                    ProgressView().controlSize(.regular)
                    Text("正在从手机获取应用列表...").font(.callout).foregroundStyle(.secondary)
                } else if model.remoteApps.isEmpty {
                    Image(systemName: "app.dashed").font(.system(size: 30)).foregroundStyle(.secondary)
                    Text("暂未获取到手机应用列表").font(.subheadline.weight(.medium))
                    Button("重新加载") { model.requestAppList() }.buttonStyle(.bordered)
                } else {
                    Image(systemName: "magnifyingglass").font(.system(size: 26)).foregroundStyle(.secondary)
                    Text("未找到与「\(query)」匹配的手机应用").font(.callout).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 220)
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 4) {
                        ForEach(Array(apps.enumerated()), id: \.element.id) { index, app in
                            appRow(app: app, isSelected: index == selectedIndex)
                                .id(app.id)
                                .onTapGesture { selectedIndex = index }
                        }
                    }
                    .padding(10)
                }
                .frame(minHeight: 220, maxHeight: 340)
                .onChange(of: selectedIndex) { _, newIdx in
                    guard apps.indices.contains(newIdx) else { return }
                    withAnimation(.easeOut(duration: 0.12)) { proxy.scrollTo(apps[newIdx].id, anchor: .center) }
                }
            }
        }
    }

    private func appRow(app: RemoteAppInfo, isSelected: Bool) -> some View {
        HStack(spacing: 10) {
            appMonogramIcon(for: app)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(app.displayLabel).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                    if app.runningOnExt {
                        StatusBadgeView(
                            text: "运行中",
                            color: .green,
                            font: .system(size: 10, weight: .semibold),
                            horizontalPadding: 6,
                            verticalPadding: 1,
                            backgroundOpacity: 0.18
                        )
                    }
                }
                Text(app.pkg).font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary).lineLimit(1)
            }

            Spacer(minLength: 8)

            HStack(spacing: 6) {
                Button { model.popoutApp(app) } label: {
                    Label("弹出独立窗", systemImage: "macwindow.badge.plus").font(.system(size: 11, weight: .semibold))
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .help("把该应用独立弹出到主窗口外部（推荐）")

                Button("居中宽幅") { model.launchApp(app, mode: .adaptive) }
                    .font(.system(size: 11, weight: .medium))
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .help("在主大屏以黄金单应用宽幅打开")

                Button("自由窗口") { model.launchApp(app, mode: .freeform) }
                    .font(.system(size: 11, weight: .medium))
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .help("在主大屏以自由缩放窗口打开")
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(
            isSelected ? Color.accentColor.opacity(0.16) : Color.clear,
            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(isSelected ? Color.accentColor.opacity(0.4) : Color.clear, lineWidth: 1)
        )
    }

    private func appMonogramIcon(for app: RemoteAppInfo) -> some View {
        let initial = String(app.displayLabel.prefix(1)).uppercased()
        let tint = Self.deterministicColor(for: app.pkg)
        return ZStack {
            RoundedRectangle(cornerRadius: 8, style: .continuous).fill(tint.opacity(0.2)).frame(width: 32, height: 32)
            Text(initial.isEmpty ? "A" : initial).font(.system(size: 14, weight: .bold)).foregroundStyle(tint)
        }
    }

    // MARK: - Footer

    private var footerBar: some View {
        HStack(spacing: 14) {
            Text("共 \(filteredApps.count) 个应用 · 独立窗 \(model.popoutManager.controllers.count)/\(PopoutWindowManager.maxConcurrentWindows)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            HStack(spacing: 10) {
                keyHint("↑↓", label: "选择")
                keyHint("↩", label: "弹出独立窗（推荐）")
                keyHint("Esc", label: "关闭")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 9)
    }

    private func keyHint(_ key: String, label: String) -> some View {
        HStack(spacing: 4) {
            Text(key)
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(Color.primary.opacity(0.1), in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func moveSelection(by delta: Int) {
        let count = filteredApps.count
        guard count > 0 else { return }
        selectedIndex = (selectedIndex + delta + count) % count
    }

    private func dismiss() { model.isQuickLauncherPresented = false }

    private static func deterministicColor(for key: String) -> Color {
        let palette: [Color] = [.blue, .teal, .indigo, .green, .orange, .purple, .pink, .cyan]
        let hash = key.unicodeScalars.reduce(0) { ($0 &* 31) &+ Int($1.value) }
        return palette[abs(hash) % palette.count]
    }
}

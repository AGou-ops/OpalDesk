import ExtBridgeCore
import SwiftUI

/// Targeted frosted DeX app picker card overlaid strictly over a single split-screen slot,
/// allowing 1-click app placement or swap without exposing background wallpaper.
struct InSlotAppPickerCard: View {
    @Bindable var model: AppModel
    let slotIndex: Int
    let slotTitle: String

    @State private var query: String = ""
    @FocusState private var isSearchFocused: Bool

    private var filteredApps: [RemoteAppInfo] {
        AppFuzzyMatcher.filter(model.remoteApps, query: query)
    }

    var body: some View {
        VStack(spacing: 0) {
            headerRow
            searchBar
            Divider().overlay(Color.white.opacity(0.08))
            appsGrid
        }
        .background {
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.regularMaterial)
                RoundedRectangle(cornerRadius: 18, style: .continuous).fill(DeXTheme.canvasBackground.opacity(0.86))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(Color.white.opacity(0.16), lineWidth: 1)
        )
        .shadow(color: Color.black.opacity(0.45), radius: 22, y: 10)
        .onAppear { isSearchFocused = true }
        .onKeyPress(.escape) {
            model.cancelSplitSlotPicking()
            return .handled
        }
    }

    private var headerRow: some View {
        HStack {
            Text("为\(slotTitle)选择应用")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(.white)
            Spacer()
            Button("取消") {
                model.cancelSplitSlotPicking()
            }
            .buttonStyle(.plain)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(.white.opacity(0.72))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Color.white.opacity(0.10), in: Capsule())
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 10)
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13))
                .foregroundStyle(.white.opacity(0.58))
            TextField("搜索要放入的应用...", text: $query)
                .textFieldStyle(.plain)
                .font(.system(size: 13))
                .foregroundStyle(.white)
                .focused($isSearchFocused)
            if !query.isEmpty {
                Button { query = "" } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.55))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 7)
        .background(Color.white.opacity(0.09), in: Capsule())
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }

    @ViewBuilder
    private var appsGrid: some View {
        let apps = filteredApps
        if apps.isEmpty {
            VStack(spacing: 8) {
                if model.isLoadingApps {
                    ProgressView().controlSize(.regular).tint(.white)
                    Text("正在加载应用...").font(.caption).foregroundStyle(.white.opacity(0.68))
                } else {
                    Text(query.isEmpty ? "暂无可选应用" : "未找到「\(query)」")
                        .font(.callout)
                        .foregroundStyle(.white.opacity(0.68))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)
            ScrollView {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(apps) { app in
                        InSlotAppTile(app: app) {
                            model.replaceSplitSlot(index: slotIndex, with: app)
                        }
                    }
                }
                .padding(14)
            }
        }
    }
}

private struct InSlotAppTile: View {
    let app: RemoteAppInfo
    let onSelect: () -> Void

    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: onSelect) {
            VStack(spacing: 6) {
                ZStack(alignment: .bottomTrailing) {
                    DeXAppIconView(app: app, size: 44, cornerRadius: 11)
                    if app.runningOnExt {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 7, height: 7)
                            .overlay(Circle().stroke(DeXTheme.canvasBackground, lineWidth: 1.4))
                            .offset(x: 2, y: 2)
                    }
                }
                Text(app.displayLabel)
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(.white.opacity(0.92))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 9)
            .padding(.horizontal, 6)
            .background(
                isHovered ? DeXTheme.hoverSurface : Color.clear,
                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
            )
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
    }
}

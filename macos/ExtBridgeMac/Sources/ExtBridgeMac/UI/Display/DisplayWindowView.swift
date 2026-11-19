import ExtBridgeCore
import SwiftUI

/// Immersive Android display canvas with hover-revealed translucent control bar,
/// ⌘K Quick Launcher overlay, inject-status smart banner, and file drag-drop feedback.
public struct DisplayWindowView: View {
    @Bindable public var model: AppModel

    @State private var toolbar = HoverToolbarController()
    @State private var isDragOverCanvas: Bool = false
    @State private var isInjectWarningDismissed: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    private var shouldShowToolbar: Bool {
        toolbar.isVisible(forceVisible: !model.isAuthenticated)
    }

    public var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            InteractiveDisplayNSView(
                videoRenderer: model.videoRenderer,
                config: DisplayCanvasConfig(
                    videoWidth: model.currentVideoWidth,
                    videoHeight: model.currentVideoHeight,
                    mapCommandToCtrl: model.preferences.mapCommandToCtrl
                ),
                callbacks: model.streamBridge.makeCanvasCallbacks(
                    isAuthenticated: { model.isAuthenticated },
                    toolbar: toolbar,
                    onFilesDropped: { model.sendFiles($0) },
                    onDragHoverChange: { hovering in
                        withAnimation(.easeInOut(duration: 0.15)) { isDragOverCanvas = hovering }
                    },
                    onResize: { size, scale in model.handleCanvasResize(viewSize: size, backingScale: scale) },
                    onQuickLauncher: { model.toggleQuickLauncher() }
                )
            )
            .ignoresSafeArea()

            if !model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0 {
                connectionOverlay
            }

            if isDragOverCanvas {
                fileDropOverlay
            }

            VStack {
                if shouldShowToolbar {
                    DisplayControlBar(model: model, toolbar: toolbar)
                        .padding(.top, 10)
                        .padding(.horizontal, 16)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .onHover { toolbar.handleToolbarHover($0) }
                }
                Spacer()

                VStack(spacing: 10) {
                    if let toast = model.toastNotice {
                        ToastPillView(message: toast)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }

                    if model.isAuthenticated && model.injectStatus == .denied && !isInjectWarningDismissed {
                        InjectDeniedSmartBanner(
                            onSwitchToUHID: { model.setInputBackend(.uhid) },
                            onDismiss: { isInjectWarningDismissed = true }
                        )
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }

                    if !model.activeTransfers.isEmpty {
                        HStack {
                            Spacer()
                            FileTransferToastStack(
                                transfers: model.activeTransfers,
                                onCancel: { model.cancelFileTransfer($0) },
                                onDismiss: { model.dismissTransfer($0) }
                            )
                        }
                    }
                }
                .padding(16)
            }

            if model.isQuickLauncherPresented {
                QuickLauncherView(model: model)
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
            }
        }
        .preferredColorScheme(.dark)
        .background(WindowChromeConfigurator(isImmersiveCanvas: true))
        .animation(.easeOut(duration: 0.18), value: shouldShowToolbar)
        .animation(.easeOut(duration: 0.18), value: model.isQuickLauncherPresented)
        .animation(.easeOut(duration: 0.2), value: model.toastNotice)
        .animation(.easeOut(duration: 0.2), value: model.injectStatus)
        .onChange(of: model.injectStatus) { _, newStatus in
            if newStatus != .denied { isInjectWarningDismissed = false }
        }
        .onAppear { toolbar.onAppear() }
        .onDisappear { toolbar.onDisappear() }
    }

    // MARK: - Overlays

    private var connectionOverlay: some View {
        VStack(spacing: 12) {
            ProgressView().controlSize(.large).tint(.white)
            Text(connectionStatusCaption)
                .font(.headline)
                .foregroundStyle(.white)
            if let conn = model.activeConnection {
                Text("\(conn.deviceName) · \(conn.transport.shortBadge)")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
        .padding(24)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var connectionStatusCaption: String {
        switch model.sessionState {
        case .connecting: return "正在建立低延迟连接..."
        case .reconnecting(let attempt): return "连接中断，正在尝试第 \(attempt) 次重连..."
        case .connected: return model.isAuthenticated ? "正在等待 Android 首个关键帧..." : "正在进行握手与身份校验..."
        default: return "准备显示画面..."
        }
    }

    private var fileDropOverlay: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 3, dash: [10, 6]))
            .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                VStack(spacing: 10) {
                    Image(systemName: "arrow.down.doc.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(Color.accentColor)
                    Text("松开鼠标发送文件到 Android 手机")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)
                    Text("默认保存到手机 Download/ExtBridge 目录")
                        .font(.callout)
                        .foregroundStyle(.white.opacity(0.8))
                }
                .padding(24)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .padding(24)
            .allowsHitTesting(false)
    }
}

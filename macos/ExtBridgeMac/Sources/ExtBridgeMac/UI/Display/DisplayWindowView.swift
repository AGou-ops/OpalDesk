import ExtBridgeCore
import SwiftUI

/// Samsung DeX for Mac main desktop shell: edge-to-edge Android canvas above a permanent 46pt
/// frosted dark DeX taskbar, with native magnetic split seams, optional top-right telemetry HUD,
/// and mutually exclusive frosted popovers (App Drawer, Layout Picker, Quick Settings).
public struct DisplayWindowView: View {
    @Bindable public var model: AppModel

    @State private var isDragOverCanvas: Bool = false
    @State private var isInjectWarningDismissed: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        VStack(spacing: 0) {
            canvasStage
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()

            DeXTaskbarView(model: model)
        }
        .background(DeXTheme.canvasBackground)
        .preferredColorScheme(.dark)
        .background(WindowChromeConfigurator(isImmersiveCanvas: true))
        .animation(.easeOut(duration: 0.16), value: model.activeDeXPanel)
        .animation(.easeOut(duration: 0.16), value: model.pendingSplitPickSlot)
        .animation(.easeOut(duration: 0.2), value: model.toastNotice)
        .animation(.easeOut(duration: 0.2), value: model.injectStatus)
        .onChange(of: model.injectStatus) { _, newStatus in
            if newStatus != .denied { isInjectWarningDismissed = false }
        }
        .onKeyPress(.escape) {
            if model.activeDeXPanel != .none || model.pendingSplitPickSlot != nil {
                model.dismissDeXPanels()
                return .handled
            }
            return .ignored
        }
    }

    // MARK: - Canvas Stage

    private var canvasStage: some View {
        ZStack {
            DeXTheme.canvasBackground

            InteractiveDisplayNSView(
                videoRenderer: model.videoRenderer,
                config: DisplayCanvasConfig(
                    videoWidth: model.currentVideoWidth,
                    videoHeight: model.currentVideoHeight,
                    mapCommandToCtrl: model.preferences.mapCommandToCtrl
                ),
                callbacks: model.streamBridge.makeCanvasCallbacks(
                    isAuthenticated: { model.isAuthenticated },
                    toolbar: nil,
                    onFilesDropped: { model.sendFiles($0) },
                    onDragHoverChange: { hovering in
                        withAnimation(.easeInOut(duration: 0.15)) { isDragOverCanvas = hovering }
                    },
                    onResize: { size, scale in model.handleCanvasResize(viewSize: size, backingScale: scale) },
                    onCanvasInteract: {
                        if model.activeDeXPanel != .none {
                            model.activeDeXPanel = .none
                        }
                    },
                    onQuickLauncher: { model.toggleQuickLauncher() }
                )
            )

            DeXSplitDividerOverlay(model: model)

            if model.preferences.showHUDStats && model.isAuthenticated {
                VStack {
                    HStack {
                        Spacer()
                        compactCornerHUD
                    }
                    Spacer()
                }
                .padding(12)
                .allowsHitTesting(false)
            }

            if !model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0 {
                connectionOverlay
            }

            if isDragOverCanvas {
                fileDropOverlay
            }

            notificationsOverlay

            if model.activeDeXPanel != .none {
                dexPopoverOverlay
            }
        }
    }

    // MARK: - Mutually Exclusive DeX Popovers

    private var dexPopoverOverlay: some View {
        ZStack {
            Color.black.opacity(0.001)
                .contentShape(Rectangle())
                .onTapGesture { model.activeDeXPanel = .none }

            switch model.activeDeXPanel {
            case .appDrawer:
                VStack {
                    Spacer()
                    HStack {
                        DeXAppDrawerView(model: model)
                        Spacer()
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 10)
                .transition(.move(edge: .bottom).combined(with: .opacity))

            case .quickSettings:
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        DeXQuickSettingsPanel(model: model)
                    }
                }
                .padding(.trailing, 12)
                .padding(.bottom, 10)
                .transition(.move(edge: .bottom).combined(with: .opacity))

            case .layoutPicker:
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        DeXLayoutPopoverView(model: model)
                    }
                }
                .padding(.trailing, 76)
                .padding(.bottom, 10)
                .transition(.move(edge: .bottom).combined(with: .opacity))

            case .none:
                EmptyView()
            }
        }
    }

    // MARK: - Optional Top-Right Performance HUD

    private var compactCornerHUD: some View {
        let fps = Int(model.videoMetrics.currentFPS.rounded())
        let rtt = Int(model.rttMs.rounded())
        let mbps = model.videoMetrics.bitrateKbps / 1000.0
        let codecName = model.videoMetrics.codec?.displayName ?? model.preferences.preferredCodec.displayName

        return HStack(spacing: 6) {
            Text("\(fps) fps")
            Text("·").foregroundStyle(.white.opacity(0.4))
            Text("\(rtt) ms")
            Text("·").foregroundStyle(.white.opacity(0.4))
            Text(String(format: "%.1f Mbps", mbps))
            Text("·").foregroundStyle(.white.opacity(0.4))
            Text("\(model.currentVideoWidth)×\(model.currentVideoHeight) \(codecName)")

            if model.injectStatus == .uhid {
                StatusBadgeView(
                    text: "UHID",
                    color: .green,
                    font: .system(size: 9.5, weight: .bold, design: .rounded),
                    horizontalPadding: 5,
                    verticalPadding: 1,
                    backgroundOpacity: 0.24
                )
            }
        }
        .font(.system(size: 10.5, weight: .medium, design: .monospaced))
        .foregroundStyle(.white.opacity(0.84))
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .floatingCapsuleChrome(shadowRadius: 8, shadowY: 3, shadowOpacity: 0.30)
    }

    // MARK: - Bottom Notifications & Status Overlays

    private var notificationsOverlay: some View {
        VStack(spacing: 10) {
            Spacer()

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
        .padding(14)
    }

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

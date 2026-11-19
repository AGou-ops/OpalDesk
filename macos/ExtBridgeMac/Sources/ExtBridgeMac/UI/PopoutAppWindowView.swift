import AppKit
import ExtBridgeCore
import SwiftUI

/// Frameless-style standalone Android app window view.
/// Shows a pure, zero-clutter rounded phone screen when the cursor moves away, and smoothly reveals
/// a translucent hover control bar near the top edge with pin, opacity, aspect presets, DPI reload,
/// Back, return-to-desktop, and close controls.
public struct PopoutAppWindowView: View {
    @Bindable public var controller: PopoutAppController
    public let onFilesDropped: ([URL]) -> Void

    @State private var isTopBarRevealed: Bool = true
    @State private var isHoveringToolbar: Bool = false
    @State private var isOpacityPopoverShown: Bool = false
    @State private var isDragOverCanvas: Bool = false
    @State private var autoHideTask: Task<Void, Never>?

    public init(
        controller: PopoutAppController,
        onFilesDropped: @escaping ([URL]) -> Void
    ) {
        self.controller = controller
        self.onFilesDropped = onFilesDropped
    }

    public var body: some View {
        ZStack {
            Color.black
                .ignoresSafeArea()

            InteractiveDisplayNSView(
                videoRenderer: controller.videoRenderer,
                videoWidth: controller.currentVideoWidth,
                videoHeight: controller.currentVideoHeight,
                mapCommandToCtrl: controller.mapCommandToCtrl,
                onMouse: { controller.sendMouse($0) },
                onTouch: { controller.sendTouch($0) },
                onKey: { controller.sendKey($0) },
                onCommitText: { controller.sendCommittedText($0) },
                onFilesDropped: onFilesDropped,
                onDragHoverChange: { hovering in
                    withAnimation(.easeInOut(duration: 0.15)) {
                        isDragOverCanvas = hovering
                    }
                },
                onResize: { size, scale in
                    controller.handleCanvasResize(viewSize: size, backingScale: scale)
                },
                onTopEdgeHover: { nearTop in
                    if nearTop {
                        revealToolbarTemporarily(hideAfter: 1.6)
                    } else if !isHoveringToolbar && !isOpacityPopoverShown {
                        scheduleToolbarHide(delaySeconds: 0.35)
                    }
                },
                onCursorClearedTopZone: {
                    if !isOpacityPopoverShown && (isTopBarRevealed || isHoveringToolbar) {
                        isHoveringToolbar = false
                        scheduleToolbarHide(delaySeconds: 0.25)
                    }
                },
                onCanvasInteract: {
                    if !isOpacityPopoverShown && (isTopBarRevealed || isHoveringToolbar) {
                        autoHideTask?.cancel()
                        isHoveringToolbar = false
                        isTopBarRevealed = false
                    }
                },
                onTogglePin: {
                    controller.togglePinnedOnTop()
                    revealToolbarTemporarily(hideAfter: 1.4)
                },
                onThreeFingerSwipe: { direction in
                    controller.handleThreeFingerSwipe(direction)
                }
            )
            .ignoresSafeArea()

            if !controller.isAuthenticated || controller.videoMetrics.totalFramesDecoded == 0 {
                loadingOverlay
            }

            if isDragOverCanvas {
                dropHighlightOverlay
            }

            VStack {
                if shouldShowToolbar {
                    hoverControlBar
                        .padding(.top, 10)
                        .padding(.horizontal, 10)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .onHover { hovering in
                            isHoveringToolbar = hovering
                            if hovering {
                                autoHideTask?.cancel()
                            } else if !isOpacityPopoverShown {
                                scheduleToolbarHide(delaySeconds: 0.35)
                            }
                        }
                }
                Spacer()
            }
        }
        .preferredColorScheme(.dark)
        .animation(.easeOut(duration: 0.16), value: shouldShowToolbar)
        .onAppear {
            revealToolbarTemporarily(hideAfter: 1.8)
        }
        .onDisappear {
            autoHideTask?.cancel()
        }
    }

    private var shouldShowToolbar: Bool {
        isTopBarRevealed || isHoveringToolbar || isOpacityPopoverShown || !controller.isAuthenticated
    }

    // MARK: - Translucent Hover Control Bar

    private var hoverControlBar: some View {
        HStack(spacing: 5) {
            // Draggable app title handle
            HStack(spacing: 5) {
                Circle()
                    .fill(controller.isAuthenticated ? Color.green : Color.orange)
                    .frame(width: 6, height: 6)
                Text(controller.appLabel)
                    .font(.system(size: 11, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .padding(.leading, 4)
            .padding(.trailing, 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay {
                PopoutWindowDragArea()
            }
            .help("按住拖动移动弹出窗")

            // Pin Always-on-Top (⌘⇧T)
            compactIconButton(
                icon: controller.isPinnedOnTop ? "pin.fill" : "pin",
                tint: controller.isPinnedOnTop ? .orange : .primary,
                isHighlighted: controller.isPinnedOnTop,
                help: controller.isPinnedOnTop ? "取消置顶 (⌘⇧T)" : "窗口置顶 (⌘⇧T)"
            ) {
                controller.togglePinnedOnTop()
            }

            // Opacity slider popover (40% ~ 100%)
            Button {
                isOpacityPopoverShown.toggle()
            } label: {
                Image(systemName: "circle.lefthalf.filled")
                    .font(.system(size: 11, weight: .medium))
                    .frame(width: 24, height: 22)
                    .background(
                        controller.windowOpacity < 0.99 ? Color.accentColor.opacity(0.24) : Color.clear,
                        in: Capsule()
                    )
                    .foregroundStyle(controller.windowOpacity < 0.99 ? Color.accentColor : Color.primary)
            }
            .buttonStyle(.plain)
            .help("调节窗口透明度 (\(Int((controller.windowOpacity * 100).rounded()))%)")
            .popover(isPresented: $isOpacityPopoverShown, arrowEdge: .bottom) {
                opacityPopoverContent
            }

            // Aspect presets: Phone / Foldable / Tablet
            Menu {
                Section("窗口形态比例") {
                    ForEach(PopoutAspectPreset.allCases) { preset in
                        Button {
                            controller.applyAspectPreset(preset)
                        } label: {
                            HStack {
                                Label(preset.title, systemImage: preset.systemImage)
                                if controller.activePreset == preset {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                    }
                }
                Divider()
                Button {
                    controller.requestIDR()
                } label: {
                    Label("刷新关键帧 (IDR)", systemImage: "arrow.triangle.2.circlepath")
                }
            } label: {
                HStack(spacing: 2) {
                    Image(systemName: controller.activePreset?.systemImage ?? "aspectratio")
                        .font(.system(size: 10, weight: .semibold))
                    Text(controller.activePreset?.title ?? "自由")
                        .font(.system(size: 10, weight: .medium))
                }
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(Color.primary.opacity(0.08), in: Capsule())
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .help("快选手机 / 折叠 / 平板比例")

            // Reload DPI
            compactIconButton(
                icon: "textformat.size",
                tint: .primary,
                isHighlighted: false,
                help: "重载 App DPI（修复字体过大或缩放异常）"
            ) {
                controller.reloadAppDPI()
            }

            // Android Back
            compactIconButton(
                icon: "chevron.backward",
                tint: .primary,
                isHighlighted: false,
                help: "Android 返回"
            ) {
                controller.sendAndroidBack()
            }

            // Return to Main Desktop
            compactIconButton(
                icon: "macwindow.on.rectangle",
                tint: .accentColor,
                isHighlighted: false,
                help: "收回主大屏并关闭弹出窗"
            ) {
                controller.returnToDesktop()
            }

            // Close popout window
            compactIconButton(
                icon: "xmark.circle.fill",
                tint: .red,
                isHighlighted: false,
                help: "关闭弹出窗"
            ) {
                controller.close(sendDisconnect: true)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(
            Capsule()
                .strokeBorder(
                    controller.isPinnedOnTop ? Color.orange.opacity(0.55) : Color.white.opacity(0.18),
                    lineWidth: controller.isPinnedOnTop ? 1.2 : 0.8
                )
        )
        .shadow(color: Color.black.opacity(0.38), radius: 10, y: 4)
    }

    private var opacityPopoverContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("窗口透明度")
                    .font(.caption.weight(.semibold))
                Spacer()
                Text("\(Int((controller.windowOpacity * 100).rounded()))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Slider(
                value: Binding(
                    get: { controller.windowOpacity },
                    set: { controller.setWindowOpacity($0) }
                ),
                in: 0.4...1.0,
                step: 0.05
            )
            .frame(width: 170)
        }
        .padding(12)
    }

    private func compactIconButton(
        icon: String,
        tint: Color,
        isHighlighted: Bool,
        help: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 11, weight: .semibold))
                .frame(width: 24, height: 22)
                .background(
                    isHighlighted ? tint.opacity(0.24) : Color.clear,
                    in: Capsule()
                )
                .foregroundStyle(tint)
        }
        .buttonStyle(.plain)
        .help(help)
        .accessibilityLabel(help)
    }

    // MARK: - Loading & Drop Overlays

    private var loadingOverlay: some View {
        VStack(spacing: 10) {
            if let error = controller.errorMessage {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(.orange)
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 240)
                Button("重试启动") {
                    controller.retryStart()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            } else {
                ProgressView()
                    .controlSize(.regular)
                    .tint(.white)
                Text("正在独立启动 \(controller.appLabel)...")
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.white)
                    .lineLimit(1)
            }
        }
        .padding(18)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var dropHighlightOverlay: some View {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 2.5, dash: [8, 5]))
            .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .padding(10)
            .allowsHitTesting(false)
    }

    // MARK: - Hover Auto-Hide

    private func revealToolbarTemporarily(hideAfter seconds: Double = 2.0) {
        autoHideTask?.cancel()
        isTopBarRevealed = true
        if !isOpacityPopoverShown {
            scheduleToolbarHide(delaySeconds: seconds)
        }
    }

    private func scheduleToolbarHide(delaySeconds: Double) {
        autoHideTask?.cancel()
        autoHideTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(delaySeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            if !isHoveringToolbar && !isOpacityPopoverShown {
                isTopBarRevealed = false
            }
        }
    }
}

/// Native AppKit drag handle enabling smooth window dragging from the popout hover bar.
private struct PopoutWindowDragArea: NSViewRepresentable {
    func makeNSView(context: Context) -> DragHandleNSView {
        DragHandleNSView()
    }

    func updateNSView(_ nsView: DragHandleNSView, context: Context) {}

    final class DragHandleNSView: NSView {
        override func mouseDown(with event: NSEvent) {
            window?.performDrag(with: event)
        }
    }
}

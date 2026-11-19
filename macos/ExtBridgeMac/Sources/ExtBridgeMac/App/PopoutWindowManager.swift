import AppKit
import ExtBridgeCore
import Foundation
import Observation
import SwiftUI

public enum PopoutOpenResult: Sendable, Equatable {
    case opened
    case focusedExisting
    case invalidPackage
    case limitReached(maxCount: Int)
}

/// Native AppKit `NSWindow` coordinator hosting up to 3 independent standalone Android app windows.
/// Popout windows stay alive even if the main `ExtBridgeMac` window is minimized or closed.
@MainActor
@Observable
public final class PopoutWindowManager {
    private enum Layout {
        static let minWindowSize = NSSize(width: 320, height: 480)
        static let fallbackOriginX: CGFloat = 160
        static let fallbackOriginY: CGFloat = 120
        static let fallbackCascadeStep: CGFloat = 40
        static let screenEdgeInset: CGFloat = 20
        static let rightMargin: CGFloat = 36
        static let cascadeXStep: CGFloat = 38
        static let cascadeYStep: CGFloat = 18
    }

    public static let maxConcurrentWindows: Int = 3

    public private(set) var controllers: [PopoutAppController] = []

    @ObservationIgnored
    public var onControllersChanged: (() -> Void)?

    @ObservationIgnored
    private var hosts: [UUID: PopoutWindowHost] = [:]

    public init() {}

    public func openPopout(
        appPackage: String,
        appActivity: String = "",
        appLabel: String = "",
        taskId: Int = -1,
        context: PopoutConnectionContext,
        trustStore: TrustStore,
        onFilesDropped: @escaping ([URL]) -> Void
    ) -> PopoutOpenResult {
        let normalizedPkg = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedPkg.isEmpty else {
            return .invalidPackage
        }

        // Bring existing popout window for the same package to front rather than spawning duplicates.
        if let existing = controllers.first(where: { $0.appPackage == normalizedPkg }) {
            existing.hostWindow?.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return .focusedExisting
        }

        guard controllers.count < Self.maxConcurrentWindows else {
            return .limitReached(maxCount: Self.maxConcurrentWindows)
        }

        let controller = PopoutAppController(
            appPackage: normalizedPkg,
            appActivity: appActivity,
            appLabel: appLabel.isEmpty ? normalizedPkg : appLabel,
            taskId: taskId,
            connectionContext: context,
            trustStore: trustStore
        )

        let window = makePopoutWindow(for: controller, cascadeIndex: controllers.count, onFilesDropped: onFilesDropped)
        controller.hostWindow = window

        let host = PopoutWindowHost(window: window) { [weak self, id = controller.id] in
            self?.handleHostWindowClosed(id: id)
        }
        window.delegate = host

        controller.onRequestClose = { [weak self, id = controller.id] in
            self?.closeWindow(id: id, sendDisconnect: false)
        }

        hosts[controller.id] = host
        controllers.append(controller)
        onControllersChanged?()

        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        controller.start()
        return .opened
    }

    /// Migrates all open standalone popout windows to a newly upgraded transport context
    /// (for example, after Wireless ADB upgrades to native Wi-Fi TLS).
    public func migrateConnectionContext(to context: PopoutConnectionContext) {
        for controller in controllers {
            controller.migrateConnectionContext(to: context)
        }
    }

    /// Returns `true` if any active popout window still points at `127.0.0.1:<port>`.
    public func hasActiveController(usingLocalPort port: UInt16) -> Bool {
        controllers.contains { $0.isUsingLocalPort(port) }
    }

    public func focusWindow(id: UUID) {
        guard let host = hosts[id] else { return }
        host.window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    public func closeWindow(id: UUID, sendDisconnect: Bool = true) {
        guard let idx = controllers.firstIndex(where: { $0.id == id }) else {
            hosts.removeValue(forKey: id)?.window.close()
            return
        }
        let controller = controllers.remove(at: idx)
        let host = hosts.removeValue(forKey: id)
        controller.onRequestClose = nil
        controller.close(sendDisconnect: sendDisconnect)
        if let host {
            host.window.delegate = nil
            host.window.orderOut(nil)
            host.window.close()
        }
        onControllersChanged?()
    }

    public func closeAll(sendDisconnect: Bool = true) {
        let ids = controllers.map(\.id)
        for id in ids {
            closeWindow(id: id, sendDisconnect: sendDisconnect)
        }
    }

    // MARK: - Private Window Factory

    private func handleHostWindowClosed(id: UUID) {
        guard let idx = controllers.firstIndex(where: { $0.id == id }) else {
            hosts.removeValue(forKey: id)
            return
        }
        let controller = controllers.remove(at: idx)
        hosts.removeValue(forKey: id)
        controller.onRequestClose = nil
        controller.close(sendDisconnect: true)
        onControllersChanged?()
    }

    private func makePopoutWindow(
        for controller: PopoutAppController,
        cascadeIndex: Int,
        onFilesDropped: @escaping ([URL]) -> Void
    ) -> NSWindow {
        let initialSize = PopoutAspectPreset.phone.windowSize
        let origin = initialOrigin(for: initialSize, cascadeIndex: cascadeIndex)
        let rect = NSRect(origin: origin, size: initialSize)

        let window = NSWindow(
            contentRect: rect,
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = controller.appLabel
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = false
        window.isReleasedWhenClosed = false
        window.backgroundColor = .black
        window.appearance = NSAppearance(named: .darkAqua)
        window.minSize = Layout.minWindowSize
        window.hasShadow = true
        window.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]

        // Hide macOS traffic lights so the popout becomes a clean borderless phone screen when idle.
        window.standardWindowButton(.closeButton)?.isHidden = true
        window.standardWindowButton(.miniaturizeButton)?.isHidden = true
        window.standardWindowButton(.zoomButton)?.isHidden = true

        let rootView = PopoutAppWindowView(
            controller: controller,
            onFilesDropped: onFilesDropped
        )
        let hostingView = NSHostingView(rootView: rootView)
        hostingView.frame = NSRect(origin: .zero, size: initialSize)
        hostingView.autoresizingMask = [.width, .height]
        window.contentView = hostingView
        return window
    }

    private func initialOrigin(for size: CGSize, cascadeIndex: Int) -> CGPoint {
        guard let visible = NSScreen.main?.visibleFrame else {
            return CGPoint(
                x: Layout.fallbackOriginX + CGFloat(cascadeIndex) * Layout.fallbackCascadeStep,
                y: Layout.fallbackOriginY
            )
        }
        let xOffset = CGFloat(cascadeIndex) * Layout.cascadeXStep
        let yOffset = CGFloat(cascadeIndex) * Layout.cascadeYStep
        let x = max(visible.minX + Layout.screenEdgeInset, visible.maxX - size.width - Layout.rightMargin - xOffset)
        let y = max(visible.minY + Layout.screenEdgeInset, visible.midY - size.height * 0.5 - yOffset)
        return CGPoint(x: x, y: y)
    }
}

@MainActor
private final class PopoutWindowHost: NSObject, NSWindowDelegate {
    let window: NSWindow
    private var onClose: (() -> Void)?

    init(window: NSWindow, onClose: @escaping () -> Void) {
        self.window = window
        self.onClose = onClose
        super.init()
    }

    func windowWillClose(_ notification: Notification) {
        let cb = onClose
        onClose = nil
        cb?()
    }
}

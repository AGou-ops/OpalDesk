import Observation

/// Shared `@MainActor @Observable` auto-hide hover timer controller used by both
/// `DisplayWindowView` and `PopoutAppWindowView` floating control bars.
@MainActor
@Observable
public final class HoverToolbarController {
    public var isRevealed: Bool = true
    public var isPinned: Bool = false
    public var isHovering: Bool = false
    public var isLockedOpen: Bool = false

    private let initialRevealSeconds: Double
    private let topHoverRevealSeconds: Double
    private let leaveHideDelaySeconds: Double
    private let clearZoneHideDelaySeconds: Double

    @ObservationIgnored
    private var autoHideTask: Task<Void, Never>?

    public init(
        initialRevealSeconds: Double = 2.0,
        topHoverRevealSeconds: Double = 1.8,
        leaveHideDelaySeconds: Double = 0.4,
        clearZoneHideDelaySeconds: Double = 0.28
    ) {
        self.initialRevealSeconds = initialRevealSeconds
        self.topHoverRevealSeconds = topHoverRevealSeconds
        self.leaveHideDelaySeconds = leaveHideDelaySeconds
        self.clearZoneHideDelaySeconds = clearZoneHideDelaySeconds
    }

    private var isKeptOpen: Bool { isPinned || isLockedOpen }

    public func isVisible(forceVisible: Bool) -> Bool {
        isKeptOpen || isRevealed || isHovering || forceVisible
    }

    public func onAppear() { revealTemporarily(hideAfter: initialRevealSeconds) }

    public func onDisappear() {
        autoHideTask?.cancel()
        autoHideTask = nil
    }

    public func togglePinned() {
        isPinned.toggle()
        syncRetentionState()
    }

    public func setLockedOpen(_ locked: Bool) {
        isLockedOpen = locked
        syncRetentionState()
    }

    public func revealTemporarily(hideAfter seconds: Double? = nil) {
        autoHideTask?.cancel()
        isRevealed = true
        if !isKeptOpen { scheduleHide(after: seconds ?? topHoverRevealSeconds) }
    }

    public func handleTopEdgeHover(_ nearTop: Bool) {
        if nearTop {
            revealTemporarily(hideAfter: topHoverRevealSeconds)
        } else if !isHovering && !isKeptOpen {
            scheduleHide(after: leaveHideDelaySeconds)
        }
    }

    public func handleToolbarHover(_ hovering: Bool) {
        isHovering = hovering
        if hovering {
            autoHideTask?.cancel()
        } else if !isKeptOpen {
            scheduleHide(after: leaveHideDelaySeconds)
        }
    }

    public func handleCursorClearedTopZone() {
        guard !isKeptOpen && (isRevealed || isHovering) else { return }
        isHovering = false
        scheduleHide(after: clearZoneHideDelaySeconds)
    }

    public func handleCanvasInteract() {
        guard !isKeptOpen && (isRevealed || isHovering) else { return }
        autoHideTask?.cancel()
        isHovering = false
        isRevealed = false
    }

    private func syncRetentionState() {
        if isKeptOpen {
            autoHideTask?.cancel()
            isRevealed = true
        } else if !isHovering {
            scheduleHide(after: leaveHideDelaySeconds)
        }
    }

    private func scheduleHide(after seconds: Double) {
        autoHideTask?.cancel()
        autoHideTask = Task { [weak self] in
            guard await sleepCancellable(nanoseconds: UInt64(seconds * 1_000_000_000)), let self else { return }
            if !self.isKeptOpen && !self.isHovering {
                self.isRevealed = false
            }
        }
    }
}

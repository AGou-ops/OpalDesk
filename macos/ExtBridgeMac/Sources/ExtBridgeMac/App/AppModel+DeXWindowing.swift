import ExtBridgeCore
import Foundation

// MARK: - Samsung DeX Windowing State, App Drawer Sync, Taskbar & Split-Screen Actions

extension AppModel {
    // MARK: - Derived DeX Layout & Taskbar State

    public var activeTilingTopology: ExtCastTilingTopology? {
        switch deviceState.layoutMode {
        case .split2H: return .split2H
        case .split2V: return .split2V
        case .split3Master: return .split3Master
        case .split3Cols: return .split3Cols
        case .grid2x2: return .grid2x2
        default: return nil
        }
    }

    public var isTilingActive: Bool {
        activeTilingTopology != nil
    }

    public var currentDeXWindowMode: DeXWindowMode {
        if let topology = activeTilingTopology {
            return .tiling(topology)
        }
        return deviceState.adaptiveSingleEnabled ? .centeredPhone : .maximized
    }

    public var effectiveSplitTopology: ExtCastTilingTopology {
        activeTilingTopology ?? pendingSplitTargetTopology ?? .split2H
    }

    public var effectiveTilingSlots: [String] {
        if !deviceState.tilingSlots.isEmpty {
            return deviceState.tilingSlots
        }
        if !pendingSplitSeedSlots.isEmpty {
            return pendingSplitSeedSlots
        }
        return []
    }

    /// Running apps displayed on the DeX taskbar, promoting or synthesizing the foreground
    /// app and active split slots so icons appear immediately before the next `APP_LIST` reply.
    public var taskbarApps: [RemoteAppInfo] {
        var result: [RemoteAppInfo] = []
        var seenPackages: Set<String> = []

        for app in remoteApps where app.runningOnExt {
            if seenPackages.insert(app.pkg).inserted {
                result.append(app)
            }
        }

        let topPkg = deviceState.topAppPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        if !topPkg.isEmpty, seenPackages.insert(topPkg).inserted {
            result.insert(resolvedRunningApp(forPackage: topPkg, fallbackLabel: deviceState.topAppLabel), at: 0)
        }

        for rawSlotPkg in deviceState.tilingSlots {
            let slotPkg = rawSlotPkg.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !slotPkg.isEmpty, seenPackages.insert(slotPkg).inserted else { continue }
            result.append(resolvedRunningApp(forPackage: slotPkg, fallbackLabel: slotPkg))
        }

        return result
    }

    /// Resolves metadata & icon for an Android package name, synthesizing a lightweight fallback if needed.
    public func appInfo(forPackage pkg: String) -> RemoteAppInfo? {
        let trimmed = pkg.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let existing = remoteApps.first(where: { $0.pkg == trimmed }) {
            return existing
        }
        let label = (trimmed == deviceState.topAppPackage && !deviceState.topAppLabel.isEmpty)
            ? deviceState.topAppLabel
            : trimmed
        return RemoteAppInfo(pkg: trimmed, label: label, runningOnExt: true)
    }

    private func resolvedRunningApp(forPackage pkg: String, fallbackLabel: String) -> RemoteAppInfo {
        if let existing = remoteApps.first(where: { $0.pkg == pkg }) {
            var promoted = existing
            promoted.runningOnExt = true
            return promoted
        }
        return RemoteAppInfo(
            pkg: pkg,
            cls: "",
            label: fallbackLabel.isEmpty ? pkg : fallbackLabel,
            runningOnExt: true,
            taskId: -1
        )
    }

    // MARK: - DeX Overlay Panels & App List Sync

    public func toggleDeXPanel(_ panel: DeXOverlayPanel) {
        guard isAuthenticated else { return }
        if activeDeXPanel == panel {
            activeDeXPanel = .none
            return
        }
        pendingSplitPickSlot = nil
        activeDeXPanel = panel
        if panel == .appDrawer {
            requestAppListIfNeeded()
        }
    }

    public func dismissDeXPanels() {
        activeDeXPanel = .none
        cancelSplitSlotPicking()
    }

    public func openQuickLauncher() {
        guard isAuthenticated else { return }
        pendingSplitPickSlot = nil
        activeDeXPanel = .appDrawer
        requestAppList(force: true)
    }

    public func toggleQuickLauncher() {
        toggleDeXPanel(.appDrawer)
    }

    public func requestAppList(force: Bool = true) {
        guard isAuthenticated else { return }
        if !force {
            requestAppListIfNeeded()
            return
        }
        lastAppListRequestNs = DispatchTime.now().uptimeNanoseconds
        isLoadingApps = true
        session.send(.listApps())
    }

    public func requestAppListIfNeeded(minIntervalNs: UInt64? = nil) {
        guard isAuthenticated else { return }
        let thresholdNs = minIntervalNs ?? Tuning.appListRefreshThrottleNs
        let nowNs = DispatchTime.now().uptimeNanoseconds
        if !remoteApps.isEmpty,
           lastAppListRequestNs > 0,
           nowNs >= lastAppListRequestNs,
           (nowNs - lastAppListRequestNs) < thresholdNs
        {
            return
        }
        lastAppListRequestNs = nowNs
        isLoadingApps = true
        session.send(.listApps())
    }

    public func scheduleDelayedAppListRefresh(delayNs: UInt64? = nil) {
        let effectiveDelayNs = delayNs ?? Tuning.postWindowActionRefreshDelayNs
        delayedAppListRefreshTask?.cancel()
        delayedAppListRefreshTask = Task { [weak self] in
            guard await sleepCancellable(nanoseconds: effectiveDelayNs), let self, self.isAuthenticated else { return }
            self.requestAppList(force: true)
        }
    }

    // MARK: - Taskbar & App Lifecycle Actions

    public func launchAppInDeX(_ app: RemoteAppInfo) {
        let mode: ExtCastLaunchMode = deviceState.adaptiveSingleEnabled ? .adaptive : .fullscreen
        launchApp(app, mode: mode)
        scheduleDelayedAppListRefresh()
    }

    public func focusRemoteApp(_ app: RemoteAppInfo) {
        activeDeXPanel = .none
        if app.taskId > 0 {
            streamBridge.sendWindowAction(.focusWindow(taskId: app.taskId, pkg: app.pkg), isAuthenticated: isAuthenticated)
        } else {
            launchAppInDeX(app)
        }
    }

    public func focusOrToggleTaskbarApp(_ app: RemoteAppInfo) {
        activeDeXPanel = .none
        if app.pkg == deviceState.topAppPackage && !isTilingActive {
            streamBridge.sendWindowAction(.minimizeWindow(taskId: app.taskId, pkg: app.pkg), isAuthenticated: isAuthenticated)
            scheduleDelayedAppListRefresh()
        } else {
            focusRemoteApp(app)
        }
    }

    public func closeRemoteApp(_ app: RemoteAppInfo) {
        activeDeXPanel = .none
        streamBridge.sendWindowAction(.closeWindow(taskId: app.taskId, pkg: app.pkg), isAuthenticated: isAuthenticated)
        if let idx = remoteApps.firstIndex(where: { $0.pkg == app.pkg }) {
            remoteApps[idx].runningOnExt = false
            remoteApps[idx].taskId = -1
        }
        if deviceState.topAppPackage == app.pkg {
            deviceState.topAppPackage = ""
            deviceState.topAppLabel = ""
        }
        scheduleDelayedAppListRefresh()
    }

    // MARK: - Split-Screen & Snap Layout Actions

    public func beginPickingApp(forSplitSlot slot: Int, topology: ExtCastTilingTopology? = nil) {
        guard isAuthenticated else { return }
        activeDeXPanel = .none
        pendingSplitTargetTopology = topology ?? activeTilingTopology ?? .split2H
        if pendingSplitSeedSlots.isEmpty && !deviceState.tilingSlots.isEmpty {
            pendingSplitSeedSlots = deviceState.tilingSlots
        }
        pendingSplitPickSlot = slot
        requestAppListIfNeeded()
    }

    public func cancelSplitSlotPicking() {
        pendingSplitPickSlot = nil
        pendingSplitSeedSlots = []
        pendingSplitTargetTopology = nil
    }

    public func placeAppInSplitSlot(_ app: RemoteAppInfo, slot: Int) {
        guard isAuthenticated else { return }
        let clampedSlot = (slot == 0) ? 0 : 1
        var leftPkg = ""
        var rightPkg = ""

        if isTilingActive {
            let current = effectiveTilingSlots
            if current.indices.contains(0) { leftPkg = current[0] }
            if current.indices.contains(1) { rightPkg = current[1] }
        } else {
            let topPkg = deviceState.topAppPackage.trimmingCharacters(in: .whitespacesAndNewlines)
            if !topPkg.isEmpty && topPkg != app.pkg {
                if clampedSlot == 0 {
                    rightPkg = topPkg
                } else {
                    leftPkg = topPkg
                }
            }
        }

        if clampedSlot == 0 {
            if rightPkg == app.pkg { rightPkg = leftPkg }
            leftPkg = app.pkg
        } else {
            if leftPkg == app.pkg { leftPkg = rightPkg }
            rightPkg = app.pkg
        }

        let slots = [leftPkg, rightPkg]
        let ratios = (activeTilingTopology == .split2H && deviceState.tilingRatios.count >= 2)
            ? deviceState.tilingRatios
            : ExtCastTilingTopology.split2H.defaultRatios

        commitSplitLayout(topology: .split2H, slots: slots, ratios: ratios, completionToast: "已建立左右分屏")
    }

    public func startQuickTwoSplit() {
        guard isAuthenticated else { return }
        if isTilingActive {
            pendingSplitPickSlot = nil
            activeDeXPanel = .layoutPicker
            return
        }

        let running = taskbarApps
        if running.count >= 2 {
            commitSplitLayout(
                topology: .split2H,
                slots: [running[0].pkg, running[1].pkg],
                ratios: ExtCastTilingTopology.split2H.defaultRatios,
                completionToast: "已切换为「\(ExtCastTilingTopology.split2H.displayName)」"
            )
            return
        }

        if let onlyApp = running.first {
            commitSplitLayout(
                topology: .split2H,
                slots: [onlyApp.pkg, ""],
                ratios: ExtCastTilingTopology.split2H.defaultRatios,
                completionToast: nil
            )
            return
        }

        openQuickLauncher()
        showToast("请先打开一个应用或右键应用选择左/右分屏")
    }

    public func swapSplitSlots() {
        guard isAuthenticated, let topology = activeTilingTopology else { return }
        var slots = effectiveTilingSlots
        guard slots.count >= 2 else { return }
        if slots.count == 2 {
            slots.reverse()
        } else {
            slots.swapAt(0, 1)
        }
        let ratios = deviceState.tilingRatios.isEmpty ? topology.defaultRatios : deviceState.tilingRatios
        deviceState.tilingSlots = slots
        streamBridge.sendWindowAction(.setTiling(topology: topology, pkgs: slots, ratios: ratios), isAuthenticated: isAuthenticated)
        showToast("已互换分屏应用位置")
    }

    public func replaceSplitSlot(index: Int, with app: RemoteAppInfo) {
        guard isAuthenticated, index >= 0 else { return }
        let topology = effectiveSplitTopology
        let targetCount = max(topology.slotCount, index + 1)
        var slots = effectiveTilingSlots
        while slots.count < targetCount {
            slots.append("")
        }
        if let existingIndex = slots.firstIndex(of: app.pkg), existingIndex != index {
            slots[existingIndex] = slots[index]
        }
        slots[index] = app.pkg

        let ratios = (activeTilingTopology == topology && !deviceState.tilingRatios.isEmpty)
            ? deviceState.tilingRatios
            : topology.defaultRatios

        commitSplitLayout(
            topology: topology,
            slots: slots,
            ratios: ratios,
            completionToast: "已将「\(app.displayLabel)」放入\(topology.slotDisplayName(for: index))"
        )
    }

    public func updateSplitRatios(_ ratios: [Int]) {
        guard isAuthenticated, let topology = activeTilingTopology, !ratios.isEmpty else { return }
        let slots = effectiveTilingSlots
        deviceState.tilingRatios = ratios
        streamBridge.sendWindowAction(.setTiling(topology: topology, pkgs: slots, ratios: ratios), isAuthenticated: isAuthenticated)
    }

    public func setWindowLayoutMode(_ mode: DeXWindowMode) {
        guard isAuthenticated else { return }
        switch mode {
        case .maximized:
            cancelSplitSlotPicking()
            exitTilingIfNeeded()
            setAdaptiveSingle(enabled: false, defaultWidthDp: preferences.defaultSingleWidthDp)
            activeDeXPanel = .none
            showToast("已切换为全屏铺满模式")

        case .centeredPhone:
            cancelSplitSlotPicking()
            exitTilingIfNeeded()
            setAdaptiveSingle(enabled: true, defaultWidthDp: DisplayPresets.defaultSingleWidthDp)
            deviceState.activeLayoutMode = ExtCastLayoutMode.adaptiveSingle.rawValue
            activeDeXPanel = .none
            showToast("已切换为居中手机窗 (\(DisplayPresets.defaultSingleWidthDp)dp)")

        case .tiling(let topology):
            if topology == .exit {
                setWindowLayoutMode(.maximized)
                return
            }
            applyTilingTopologyWithSeededApps(topology)
        }
    }

    // MARK: - Private Split Layout Helpers

    private func exitTilingIfNeeded() {
        guard isTilingActive else { return }
        streamBridge.sendWindowAction(.setTiling(topology: .exit), isAuthenticated: true)
        deviceState.activeLayoutMode = ExtCastLayoutMode.fullscreen.rawValue
        deviceState.tilingSlots = []
        deviceState.tilingRatios = []
    }

    private func applyTilingTopologyWithSeededApps(_ topology: ExtCastTilingTopology) {
        let requiredSlots = topology.slotCount
        var seeded: [String] = []
        for slotPkg in effectiveTilingSlots where !slotPkg.isEmpty && !seeded.contains(slotPkg) {
            seeded.append(slotPkg)
        }
        for app in taskbarApps where !seeded.contains(app.pkg) {
            seeded.append(app.pkg)
        }

        if seeded.count > requiredSlots {
            seeded = Array(seeded.prefix(requiredSlots))
        }
        while seeded.count < requiredSlots {
            seeded.append("")
        }

        commitSplitLayout(
            topology: topology,
            slots: seeded,
            ratios: topology.defaultRatios,
            completionToast: "已切换为「\(topology.displayName)」"
        )
    }

    private func commitSplitLayout(
        topology: ExtCastTilingTopology,
        slots: [String],
        ratios: [Int],
        completionToast: String?
    ) {
        activeDeXPanel = .none
        deviceState.tilingSlots = slots
        deviceState.tilingRatios = ratios
        streamBridge.sendWindowAction(.setTiling(topology: topology, pkgs: slots, ratios: ratios), isAuthenticated: isAuthenticated)

        if let nextEmptySlot = slots.firstIndex(where: \.isEmpty) {
            pendingSplitTargetTopology = topology
            pendingSplitSeedSlots = slots
            pendingSplitPickSlot = nextEmptySlot
            requestAppListIfNeeded()
        } else {
            cancelSplitSlotPicking()
            if let completionToast {
                showToast(completionToast)
            }
        }
        scheduleDelayedAppListRefresh()
    }
}

import ExtBridgeCore
import Foundation
import os

// MARK: - Quick Launcher, Popout Windows, Layout Actions, Display Controls & File Transfers

extension AppModel {
    // MARK: - Standalone Popout Windows & Quick Launcher

    public func openQuickLauncher() {
        guard isAuthenticated else { return }
        isQuickLauncherPresented = true
        requestAppList()
    }

    public func toggleQuickLauncher() {
        guard isAuthenticated else { return }
        if isQuickLauncherPresented { isQuickLauncherPresented = false } else { openQuickLauncher() }
    }

    public func requestAppList() {
        guard isAuthenticated else { return }
        isLoadingApps = true
        session.send(.listApps())
    }

    public func popoutCurrentTopApp() {
        guard isAuthenticated else { return }
        let pkg = deviceState.topAppPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !pkg.isEmpty else {
            openQuickLauncher()
            return
        }
        let label = deviceState.topAppLabel.isEmpty ? pkg : deviceState.topAppLabel
        let matching = remoteApps.first(where: { $0.pkg == pkg })
        popoutApp(package: pkg, activity: matching?.cls ?? "", label: label, taskId: matching?.taskId ?? -1)
    }

    public func popoutApp(_ app: RemoteAppInfo) {
        popoutApp(package: app.pkg, activity: app.cls, label: app.displayLabel, taskId: app.taskId)
    }

    public func popoutApp(package: String, activity: String = "", label: String = "", taskId: Int = -1) {
        guard let context = buildPopoutConnectionContext() else {
            showToast("请先连接并完成手机鉴权后再弹出独立窗口")
            return
        }
        let result = popoutManager.openPopout(
            appPackage: package,
            appActivity: activity,
            appLabel: label.isEmpty ? package : label,
            taskId: taskId,
            context: context,
            trustStore: discovery.trustStore,
            onFilesDropped: { [weak self] urls in self?.sendFiles(urls) }
        )
        switch result {
        case .opened, .focusedExisting: isQuickLauncherPresented = false
        case .invalidPackage: showToast("应用包名无效，无法弹出独立窗口")
        case .limitReached(let maxCount): showToast("最多同时支持 \(maxCount) 个独立弹出 App 窗口，请先关闭或收回其中一个")
        }
    }

    // MARK: - Window Layout & Input Engine Controls (`0x2B WINDOW_ACTION`)

    public func setAdaptiveSingle(enabled: Bool, defaultWidthDp: Int) {
        let clampedDp = DisplayPresets.clampSingleWidthDp(defaultWidthDp)
        preferences.adaptiveSingleEnabled = enabled
        preferences.defaultSingleWidthDp = clampedDp
        deviceState.adaptiveSingleEnabled = enabled
        deviceState.defaultSingleWidthDp = clampedDp
        streamBridge.sendWindowAction(.setAdaptiveSingle(enabled: enabled, defaultWidthDp: clampedDp), isAuthenticated: isAuthenticated)
    }

    public func setSingleWidthPreset(widthPct: Int, remember: Bool = true) {
        let clampedPct = DisplayPresets.clampSingleWidthPct(widthPct)
        streamBridge.sendWindowAction(
            .setSingleWidth(pkg: deviceState.topAppPackage, widthPct: clampedPct, remember: remember),
            isAuthenticated: isAuthenticated
        )
        showToast("已调整单应用宽度为 \(clampedPct)%")
    }

    public func launchApp(_ app: RemoteAppInfo, mode: ExtCastLaunchMode = .adaptive, slot: Int = 0) {
        streamBridge.sendWindowAction(.launchApp(pkg: app.pkg, cls: app.cls, mode: mode, slot: slot), isAuthenticated: isAuthenticated)
        isQuickLauncherPresented = false
    }

    public func setTilingTopology(_ topology: ExtCastTilingTopology, pkgs: [String] = [], ratios: [Int]? = nil) {
        streamBridge.sendWindowAction(.setTiling(topology: topology, pkgs: pkgs, ratios: ratios), isAuthenticated: isAuthenticated)
        showToast(topology == .exit ? "已退出分屏布局" : "已切换为「\(topology.displayName)」")
    }

    public func reloadCurrentAppDPI() {
        let pkg = deviceState.topAppPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !pkg.isEmpty else {
            showToast("当前未检测到可重载 DPI 的前台应用")
            return
        }
        streamBridge.sendWindowAction(.reloadAppDpi(pkg: pkg), isAuthenticated: isAuthenticated)
        let name = deviceState.topAppLabel.isEmpty ? pkg : deviceState.topAppLabel
        showToast("正在重载「\(name)」的显示 DPI...")
    }

    public func setInputBackend(_ backend: ExtCastInputBackend) {
        preferences.inputBackend = backend
        streamBridge.sendWindowAction(.setInputBackend(backend), isAuthenticated: isAuthenticated)
        if isAuthenticated { showToast("输入引擎已切换为：\(backend.displayName)") }
    }

    // MARK: - Display & System Controls

    public func handleCanvasResize(viewSize: CGSize, backingScale: CGFloat) {
        guard viewSize.width >= Tuning.minCanvasDimension, viewSize.height >= Tuning.minCanvasDimension else { return }
        lastCanvasSize = viewSize
        lastBackingScale = backingScale

        guard isAuthenticated, selectedPreset == .matchWindow else { return }
        resizeDebounceTask?.cancel()
        resizeDebounceTask = Task { [weak self] in
            guard await sleepCancellable(nanoseconds: Tuning.resizeDebounceNs), let self else { return }
            let target = CoordinateMapper.alignedAdaptiveResolution(
                viewSize: viewSize,
                backingScale: backingScale,
                maxLongEdge: self.effectiveAdaptiveMaxLongEdge()
            )
            self.sendResizeIfNeeded(width: target.width, height: target.height, dpi: self.selectedDPI)
        }
    }

    public func selectResolutionPreset(_ preset: DisplayResolutionPreset) {
        selectedPreset = preset
        preferences.resolutionPreset = preset
        guard isAuthenticated else { return }
        let target = resolveCurrentTargetDimensions()
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: selectedDPI, force: true)
    }

    public func selectDPI(_ dpi: Int) {
        selectedDPI = dpi
        preferences.defaultDPI = dpi
        guard isAuthenticated else { return }
        let target = resolveCurrentTargetDimensions()
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: dpi, force: false)
    }

    public func applyTargetFPS(_ fps: Int) {
        preferences.targetFPS = fps
        guard isAuthenticated else { return }
        let target = resolveCurrentTargetDimensions()
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: selectedDPI, force: false)
    }

    public func toggleDisplayMode() {
        let nextMode: ExtCastDisplayMode = (displayMode == .dex) ? .mirror : .dex
        displayMode = nextMode
        streamBridge.sendSystemAction(.toggleDisplayMode, arg: Int32(nextMode.rawValue), isAuthenticated: isAuthenticated)
    }

    public func toggleBlackout() {
        isBlackoutActive.toggle()
        streamBridge.sendSystemAction(.toggleBlackout, arg: isBlackoutActive ? 1 : 0, isAuthenticated: isAuthenticated)
    }

    public func toggleAudio() {
        isAudioMuted.toggle()
        audioPlayer.setMuted(isAudioMuted)
        streamBridge.sendSystemAction(.setAudioEnabled, arg: isAudioMuted ? 0 : 1, isAuthenticated: isAuthenticated)
    }

    public func rotateClockwise() { streamBridge.sendSystemAction(.rotateCW, isAuthenticated: isAuthenticated) }
    public func sendAndroidHome() { streamBridge.sendSystemAction(.home, isAuthenticated: isAuthenticated) }
    public func sendAndroidBack() { streamBridge.sendSystemAction(.back, isAuthenticated: isAuthenticated) }
    public func sendAndroidRecents() { streamBridge.sendSystemAction(.recents, isAuthenticated: isAuthenticated) }
    public func requestIDR() { streamBridge.sendRequestIDR() }

    // MARK: - File Transfers

    public func sendFiles(_ urls: [URL]) {
        guard isAuthenticated else { return }
        for url in urls {
            do {
                let activeSession = session
                try fileSender.sendFile(
                    at: url,
                    sendFrame: { frame in await activeSession.sendPaced(frame) },
                    onProgress: { [weak self] progress in
                        Task { @MainActor [weak self] in self?.upsertTransferProgress(progress) }
                    }
                )
            } catch {
                ExtBridgeLog.file.error("Failed to start file transfer for \(url.lastPathComponent, privacy: .public): \(error.localizedDescription, privacy: .public)")
                bannerError = error.localizedDescription
            }
        }
    }

    public func cancelFileTransfer(_ transferId: UInt32) {
        fileSender.cancel(
            transferId: transferId,
            sendFrame: { [weak self] frame in self?.session.send(frame) },
            onProgress: { [weak self] progress in
                Task { @MainActor [weak self] in self?.upsertTransferProgress(progress) }
            }
        )
    }

    public func dismissTransfer(_ transferId: UInt32) {
        activeTransfers.removeAll { $0.transferId == transferId }
    }

    // MARK: - Internal Geometry & Sync Helpers

    func syncInitialDesktopPreferences() {
        streamBridge.sendWindowAction(
            .setAdaptiveSingle(enabled: preferences.adaptiveSingleEnabled, defaultWidthDp: preferences.defaultSingleWidthDp),
            isAuthenticated: isAuthenticated
        )
        if preferences.inputBackend != .auto {
            streamBridge.sendWindowAction(.setInputBackend(preferences.inputBackend), isAuthenticated: isAuthenticated)
        }
    }

    func syncCanvasSizeAfterAuth() {
        guard isAuthenticated, selectedPreset == .matchWindow else { return }
        guard lastCanvasSize.width >= Tuning.minCanvasDimension, lastCanvasSize.height >= Tuning.minCanvasDimension else { return }
        let target = CoordinateMapper.alignedAdaptiveResolution(
            viewSize: lastCanvasSize,
            backingScale: lastBackingScale,
            maxLongEdge: effectiveAdaptiveMaxLongEdge()
        )
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: selectedDPI, force: false)
    }

    func effectiveAdaptiveMaxLongEdge() -> Int {
        if case .wirelessADB = activeConnection?.transport { return Tuning.wirelessAdbMaxLongEdge }
        return Tuning.defaultMaxLongEdge
    }

    func effectiveBitrateKbps() -> Int {
        if case .wirelessADB = activeConnection?.transport {
            return min(preferences.bitrateKbps, Tuning.wirelessAdbMaxBitrateKbps)
        }
        return preferences.bitrateKbps
    }

    func resolveCurrentTargetDimensions() -> (width: Int, height: Int) {
        if let fixed = selectedPreset.fixedSize { return fixed }
        return CoordinateMapper.alignedAdaptiveResolution(
            viewSize: lastCanvasSize,
            backingScale: lastBackingScale,
            maxLongEdge: effectiveAdaptiveMaxLongEdge()
        )
    }

    func sendResizeIfNeeded(width: Int, height: Int, dpi: Int, force: Bool = false) {
        let alignedW = CoordinateMapper.alignTo16(width)
        let alignedH = CoordinateMapper.alignTo16(height)
        let fps = preferences.targetFPS
        if !force, let last = lastRequestedConfig,
           last.width == alignedW && last.height == alignedH && last.dpi == dpi && last.fps == fps
        {
            return
        }
        lastRequestedConfig = (alignedW, alignedH, dpi, fps)
        streamBridge.sendResize(width: alignedW, height: alignedH, dpi: dpi, fps: fps, bitrateKbps: effectiveBitrateKbps())
    }

    func upsertTransferProgress(_ progress: FileTransferProgress) {
        if let idx = activeTransfers.firstIndex(where: { $0.transferId == progress.transferId }) {
            activeTransfers[idx] = progress
        } else {
            activeTransfers.insert(progress, at: 0)
        }
        if activeTransfers.count > Tuning.maxVisibleTransfers {
            activeTransfers = Array(activeTransfers.prefix(Tuning.maxVisibleTransfers))
        }
    }
}

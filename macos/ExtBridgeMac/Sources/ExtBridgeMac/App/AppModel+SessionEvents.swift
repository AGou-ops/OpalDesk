import ExtBridgeCore
import Foundation
import os

// MARK: - Session Event Loop, Control Frame Dispatch & Wi-Fi Upgrade Handoff

extension AppModel {
    func handleSessionEvent(_ event: ExtCastSessionEvent) {
        switch event {
        case .stateChanged(let newState):
            sessionState = newState
            switch newState {
            case .connected:
                sendHelloHandshake()
            case .disconnected:
                guard !wifiUpgrade.isUpgradingToWifiTls else { return }
                isConnectingFlow = false
                isAuthenticated = false
                clipboardSync.stop()
                audioPlayer.stop()
            case .failed(let message):
                if wifiUpgrade.isUpgradingToWifiTls {
                    revertFailedWifiUpgradeSilently()
                    return
                }
                isConnectingFlow = false
                isAuthenticated = false
                if var ctx = pairingContext {
                    ctx.isSubmitting = false
                    ctx.errorMessage = message
                    pairingContext = ctx
                } else {
                    bannerError = message
                    activeConnection = nil
                }
            default:
                break
            }

        case .verifiedPeerFingerprint(let fullHex):
            activeConnection?.fingerprint = fullHex
        case .rttUpdated(let rtt):
            rttMs = rtt
        case .error(let sessionError):
            if !wifiUpgrade.isUpgradingToWifiTls, case .certificateFingerprintMismatch = sessionError {
                bannerError = sessionError.localizedDescription
            }
        case .frame(let frame):
            handleControlFrame(frame)
        }
    }

    func buildPopoutConnectionContext() -> PopoutConnectionContext? {
        guard isAuthenticated, let conn = activeConnection, let endpoint = activeSessionEndpoint else { return nil }
        let token: String
        if case let .wirelessWiFi(_, host, port) = conn.transport {
            if !pendingAuth.authToken.isEmpty {
                token = pendingAuth.authToken
            } else if !conn.fingerprint.isEmpty, let trusted = discovery.trustStore.find(byFingerprint: conn.fingerprint) {
                token = trusted.authToken
            } else if let host, let port, let trusted = discovery.trustStore.find(byHost: host, port: port) {
                token = trusted.authToken
            } else {
                token = ""
            }
        } else {
            token = ""
        }
        return PopoutConnectionContext(
            endpoint: endpoint,
            transportHint: conn.transport.transportHint,
            clientId: discovery.trustStore.clientId,
            authToken: token,
            preferredCodec: preferences.preferredCodec,
            targetFPS: preferences.targetFPS,
            bitrateKbps: effectiveBitrateKbps(),
            mapCommandToCtrl: preferences.mapCommandToCtrl
        )
    }

    private func sendHelloHandshake() {
        let initialSize = resolveCurrentTargetDimensions()
        lastRequestedConfig = (initialSize.width, initialSize.height, selectedDPI, preferences.targetFPS)
        streamBridge.sendHello(HelloPayload(
            clientName: StreamSessionBridge.localClientName,
            clientId: discovery.trustStore.clientId,
            preferredCodec: preferences.preferredCodec.preferredName,
            width: initialSize.width,
            height: initialSize.height,
            dpi: selectedDPI,
            fps: preferences.targetFPS,
            bitrateKbps: effectiveBitrateKbps(),
            displayMode: Int(displayMode.rawValue),
            audioEnabled: !isAudioMuted,
            authToken: pendingAuth.authToken,
            pinCode: pendingAuth.pinCode,
            transportHint: (activeConnection?.transport.transportHint ?? .usb).rawValue,
            sessionRole: ExtCastSessionRole.desktop.rawValue
        ))
    }

    /// Handles control-plane downlink frames on `@MainActor`.
    /// High-frequency media frames (`.videoConfig`, `.videoFrame`, `.audioConfig`, `.audioFrame`, `.pong`)
    /// are routed synchronously on `networkQueue` by `StreamSessionBridge.makeEventHandler`.
    private func handleControlFrame(_ frame: ExtCastFrame) {
        switch frame.type {
        case .deviceState:
            guard let state = StreamSessionBridge.decodePayload("DEVICE_STATE", logger: ExtBridgeLog.app, {
                try DeviceStatePayload.decode(from: frame.payload)
            }) else { return }
            let previousTopPkg = deviceState.topAppPackage
            deviceState = state
            isBlackoutActive = state.blackout
            if let mode = ExtCastDisplayMode(rawValue: UInt8(clamping: state.displayMode)), mode != .unchanged {
                displayMode = mode
            }
            if !state.deviceName.isEmpty { activeConnection?.deviceName = state.deviceName }
            refreshAppListIfForegroundChanged(previousTopPackage: previousTopPkg, newState: state)

        case .clipboardPush:
            if (frame.flags & ExtCastProtocol.flagClipboardImagePng) != 0 {
                if let img = StreamSessionBridge.decodePayload("image CLIPBOARD_PUSH", logger: ExtBridgeLog.clipboard, {
                    try ClipboardImagePushPayload.decode(from: frame.payload)
                }) {
                    clipboardSync.applyRemoteClipboardImage(seq: img.seq, pngData: img.pngData)
                }
            } else if let clip = StreamSessionBridge.decodePayload("text CLIPBOARD_PUSH", logger: ExtBridgeLog.clipboard, {
                try ClipboardPushPayload.decode(from: frame.payload)
            }) {
                clipboardSync.applyRemoteClipboard(seq: clip.seq, text: clip.text)
            }

        case .authResult:
            do {
                handleAuthResult(try AuthResultPayload.decode(from: frame.payload))
            } catch {
                ExtBridgeLog.app.error("Failed to decode AUTH_RESULT: \(error.localizedDescription, privacy: .public)")
                bannerError = "认证响应解析失败：\(error.localizedDescription)"
            }

        case .fileTransferAck:
            guard let ack = StreamSessionBridge.decodePayload("FILE_TRANSFER_ACK", logger: ExtBridgeLog.file, {
                try FileTransferAckPayload.decode(from: frame.payload)
            }) else { return }
            fileSender.handleAck(ack) { [weak self] progress in
                Task { @MainActor [weak self] in self?.upsertTransferProgress(progress) }
            }

        case .wifiUpgradeReady:
            if let ready = StreamSessionBridge.decodePayload("WIFI_UPGRADE_READY", logger: ExtBridgeLog.app, {
                try WifiUpgradeReadyPayload.decode(from: frame.payload)
            }) {
                handleWifiUpgradeReady(ready)
            }

        case .appList:
            isLoadingApps = false
            guard let list = StreamSessionBridge.decodePayload("APP_LIST", logger: ExtBridgeLog.app, {
                try AppListPayload.decode(from: frame.payload)
            }) else {
                showToast("获取手机应用列表失败，请重试")
                return
            }
            remoteApps = list.apps.sorted { lhs, rhs in
                if lhs.runningOnExt != rhs.runningOnExt { return lhs.runningOnExt && !rhs.runningOnExt }
                return lhs.displayLabel.localizedCaseInsensitiveCompare(rhs.displayLabel) == .orderedAscending
            }

        default:
            break
        }
    }

    private func handleAuthResult(_ auth: AuthResultPayload) {
        isConnectingFlow = false
        guard auth.ok else {
            handleAuthFailure(auth)
            return
        }

        isAuthenticated = true
        pairingContext = nil
        bannerError = nil

        let resolvedName = auth.deviceName.isEmpty ? pendingAuth.deviceName : auth.deviceName
        if !resolvedName.isEmpty { activeConnection?.deviceName = resolvedName }

        let resolvedFp = FingerprintUtil.normalize(
            session.verifiedPeerFingerprint ?? (auth.certFingerprint.isEmpty ? (activeConnection?.fingerprint ?? "") : auth.certFingerprint)
        )
        discovery.upsertTrustedDevice(
            deviceName: resolvedName,
            model: pendingAuth.deviceModel,
            certFingerprint: resolvedFp,
            authToken: auth.authToken,
            lastHost: pendingAuth.hostForTrust,
            lastPort: pendingAuth.portForTrust
        )

        pendingAuth.pinCode = ""
        if !auth.authToken.isEmpty { pendingAuth.authToken = auth.authToken }

        if wifiUpgrade.finalizeUpgradeIfInProgress(
            adbManager: discovery.adbManager,
            popoutManager: popoutManager,
            newPopoutContext: buildPopoutConnectionContext()
        ) {
            activeAdbForward = nil
            showToast("已自动无缝切换至原生 Wi-Fi TLS 直连")
        } else {
            wifiUpgrade.requestUpgradeIfEligible(transport: activeConnection?.transport, session: session)
        }

        applyPostAuthBootstrap()
    }

    private func applyPostAuthBootstrap() {
        startClipboardSyncIfEnabled()
        syncInitialDesktopPreferences()
        syncCanvasSizeAfterAuth()
        requestAppList(force: true)
    }

    private func refreshAppListIfForegroundChanged(previousTopPackage: String, newState: DeviceStatePayload) {
        let newTopPkg = newState.topAppPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        let topChangedToUnknownApp = !newTopPkg.isEmpty
            && newTopPkg != previousTopPackage
            && !remoteApps.contains(where: { $0.pkg == newTopPkg && $0.runningOnExt })
        let hasUnknownSplitSlot = newState.tilingSlots.contains { slotPkg in
            let trimmed = slotPkg.trimmingCharacters(in: .whitespacesAndNewlines)
            return !trimmed.isEmpty && !remoteApps.contains(where: { $0.pkg == trimmed })
        }
        if topChangedToUnknownApp || hasUnknownSplitSlot {
            requestAppListIfNeeded()
        }
    }

    private func handleAuthFailure(_ auth: AuthResultPayload) {
        if wifiUpgrade.isUpgradingToWifiTls {
            revertFailedWifiUpgradeSilently()
            return
        }

        isAuthenticated = false
        let failureText = auth.message.isEmpty ? "身份认证被手机端拒绝，请核对 PIN 码后重试" : auth.message

        if var ctx = pairingContext {
            ctx.isSubmitting = false
            ctx.errorMessage = failureText
            pairingContext = ctx
            session.disconnect()
        } else if let source = pendingAuth.pairingSource {
            let fp = session.verifiedPeerFingerprint ?? (activeConnection?.fingerprint ?? "")
            session.disconnect()
            activeConnection = nil
            pairingContext = PairingPromptContext(
                source: source,
                displayName: pendingAuth.deviceName,
                model: pendingAuth.deviceModel,
                expectedFingerprint: fp,
                errorMessage: "原有授权已失效，请输入手机端新显示的 \(DisplayPresets.pairingPinLength) 位配对 PIN 码"
            )
        } else {
            bannerError = failureText
            Task { await teardownCurrentSession(sendRemoteDisconnect: false) }
        }
    }

    private func handleWifiUpgradeReady(_ ready: WifiUpgradeReadyPayload) {
        wifiUpgrade.handleUpgradeReady(
            ready,
            currentConnection: activeConnection,
            fallbackDeviceName: pendingAuth.deviceName,
            fallbackModel: pendingAuth.deviceModel,
            isEligibleForHandoff: { [weak self] in
                guard let self, case .wirelessADB = self.activeConnection?.transport else { return false }
                return self.isAuthenticated
            },
            currentAdbForward: { [weak self] in self?.activeAdbForward },
            discovery: discovery,
            onStageHandoff: { [weak self] host, port, verifiedFp, authToken, tlsEndpoint in
                guard let self else { return }
                self.pendingAuth.authToken = authToken
                self.pendingAuth.pinCode = ""
                self.pendingAuth.hostForTrust = host
                self.pendingAuth.portForTrust = port
                self.activeConnection?.transport = .wirelessWiFi(displayTarget: "\(host):\(port)", host: host, port: port)
                self.activeConnection?.fingerprint = verifiedFp
                self.connectSession(to: tlsEndpoint)
            }
        )
    }

    private func revertFailedWifiUpgradeSilently() {
        let (adbForward, adbTransport) = wifiUpgrade.consumeFallbackForSilentRevert()
        pendingAuth.authToken = ""
        pendingAuth.hostForTrust = nil
        pendingAuth.portForTrust = nil

        if let adbTransport { activeConnection?.transport = adbTransport }
        if let adbForward {
            activeAdbForward = adbForward
            connectSession(to: ExtCastEndpoint(
                target: .hostPort(host: "127.0.0.1", port: adbForward.localPort),
                security: .wiredLocalhostPlaintext,
                autoReconnect: true
            ))
        }
    }

    private func startClipboardSyncIfEnabled() {
        clipboardSync.isEnabled = preferences.clipboardSyncEnabled
        clipboardSync.start(
            onLocalChange: { [weak self] seq, text in
                guard let self, self.isAuthenticated else { return }
                self.session.send(.inputText(InputTextPayload(mode: .setClipboardOnly, seq: seq, text: text)))
            },
            onLocalImageChange: { [weak self] seq, pngData in
                guard let self, self.isAuthenticated else { return }
                let payload = InputImagePayload(mode: .setClipboardImageOnly, seq: seq, pngData: pngData)
                do {
                    self.session.send(try ExtCastFrame.inputImage(payload))
                } catch {
                    ExtBridgeLog.clipboard.error("Failed to encode clipboard image frame: \(error.localizedDescription, privacy: .public)")
                }
            }
        )
    }

    func teardownCurrentSession(sendRemoteDisconnect: Bool) async {
        resizeDebounceTask?.cancel()
        delayedAppListRefreshTask?.cancel()
        activeDeXPanel = .none
        pendingSplitPickSlot = nil
        pendingSplitSeedSlots = []
        pendingSplitTargetTopology = nil
        lastAppListRequestNs = 0
        remoteApps.removeAll()
        isLoadingApps = false
        popoutManager.closeAll(sendDisconnect: sendRemoteDisconnect)

        if sendRemoteDisconnect && isAuthenticated {
            streamBridge.sendSystemAction(.disconnect, isAuthenticated: true)
            _ = await sleepCancellable(nanoseconds: Tuning.remoteDisconnectFlushNs)
        }

        clipboardSync.stop()
        audioPlayer.stop()
        videoRenderer.reset()
        session.disconnect()

        var forwards = wifiUpgrade.reset()
        if let active = activeAdbForward {
            forwards.append(active)
            activeAdbForward = nil
        }
        for forward in Self.uniqueForwards(forwards) {
            await discovery.adbManager.removeForward(forward)
        }

        activeSessionEndpoint = nil
        isConnectingFlow = false
        isAuthenticated = false
        activeConnection = nil
        sessionState = .idle
        rttMs = 0
        videoMetrics = VideoRendererMetrics()
        deviceState = DeviceStatePayload(
            adaptiveSingleEnabled: preferences.adaptiveSingleEnabled,
            defaultSingleWidthDp: preferences.defaultSingleWidthDp
        )
        lastRequestedConfig = nil
        activeTransfers.removeAll()
    }

    static func uniqueForwards(_ forwards: [AdbForwardHandle]) -> [AdbForwardHandle] {
        var result: [AdbForwardHandle] = []
        for forward in forwards where !result.contains(forward) { result.append(forward) }
        return result
    }

    /// Launches non-blocking detached `adb forward --remove` processes during app termination
    /// so `@MainActor` never blocks on a semaphore while dangling forwards are still cleaned up.
    static func detachAdbForwardRemoval(_ forwards: [AdbForwardHandle], adbPath: String?) {
        let unique = uniqueForwards(forwards)
        guard !unique.isEmpty, let adbPath else { return }
        let executableURL = URL(fileURLWithPath: adbPath)
        for forward in unique {
            let process = Process()
            process.executableURL = executableURL
            process.arguments = ["-s", forward.serial, "forward", "--remove", "tcp:\(forward.localPort)"]
            process.standardOutput = FileHandle.nullDevice
            process.standardError = FileHandle.nullDevice
            do {
                try process.run()
            } catch {
                ExtBridgeLog.adb.warning(
                    "Failed to detach adb forward cleanup for tcp:\(forward.localPort): \(error.localizedDescription, privacy: .public)"
                )
            }
        }
    }
}

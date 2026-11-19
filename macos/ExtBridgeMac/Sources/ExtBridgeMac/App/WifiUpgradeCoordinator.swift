import ExtBridgeCore
import Foundation
import Observation
import os

/// Coordinates seamless Wireless ADB -> native Wi-Fi TLS 1.3 auto-upgrade:
/// probes LAN TLS reachability before touching the live ADB tunnel, stages safe fallback,
/// migrates open popout windows BEFORE removing obsolete ADB forwards, and silently reverts on failure.
@MainActor
@Observable
public final class WifiUpgradeCoordinator {
    private static let tlsProbeTimeoutSeconds: Double = 3.5

    public private(set) var isUpgradingToWifiTls: Bool = false

    @ObservationIgnored
    private var hasRequestedWifiUpgrade: Bool = false
    @ObservationIgnored
    private var upgradeProbeTask: Task<Void, Never>?
    @ObservationIgnored
    private var fallbackAdbForward: AdbForwardHandle?
    @ObservationIgnored
    private var fallbackAdbTransport: ActiveTransportKind?
    @ObservationIgnored
    private var deferredForwards: [AdbForwardHandle] = []

    public init() {}

    /// Resets upgrade state and returns any fallback or deferred ADB forwards that still need removal.
    public func reset() -> [AdbForwardHandle] {
        upgradeProbeTask?.cancel()
        upgradeProbeTask = nil
        isUpgradingToWifiTls = false
        hasRequestedWifiUpgrade = false

        var remaining = deferredForwards
        deferredForwards.removeAll()
        if let fallback = fallbackAdbForward {
            remaining.append(fallback)
            fallbackAdbForward = nil
        }
        fallbackAdbTransport = nil
        return remaining
    }

    /// Initiates a one-time `REQUEST_WIFI_UPGRADE` from an authenticated wireless ADB session.
    public func requestUpgradeIfEligible(
        transport: ActiveTransportKind?,
        session: ExtCastSession
    ) {
        guard case .wirelessADB = transport, !hasRequestedWifiUpgrade else { return }
        hasRequestedWifiUpgrade = true
        ExtBridgeLog.app.info("Requesting native Wi-Fi TLS upgrade from wireless ADB session")
        session.send(.requestWifiUpgrade())
    }

    /// Probes the advertised Wi-Fi TLS endpoint and stages handoff only after verifying the TLS pin.
    public func handleUpgradeReady(
        _ ready: WifiUpgradeReadyPayload,
        currentConnection: ActiveConnectionInfo?,
        fallbackDeviceName: String,
        fallbackModel: String,
        isEligibleForHandoff: @escaping @MainActor () -> Bool,
        currentAdbForward: @escaping @MainActor () -> AdbForwardHandle?,
        discovery: DiscoveryCoordinator,
        onStageHandoff: @escaping @MainActor (
            _ host: String,
            _ port: UInt16,
            _ verifiedFingerprint: String,
            _ authToken: String,
            _ endpoint: ExtCastEndpoint
        ) -> Void
    ) {
        guard case .wirelessADB = currentConnection?.transport else { return }
        guard ready.ok,
              !ready.host.isEmpty,
              let port = ready.validPort,
              !ready.authToken.isEmpty
        else {
            ExtBridgeLog.app.info("Ignoring WIFI_UPGRADE_READY (ok=\(ready.ok), message=\(ready.message, privacy: .public))")
            return
        }

        let normalizedExpectedFp = FingerprintUtil.normalize(ready.certFingerprint)
        guard normalizedExpectedFp.count >= FingerprintUtil.minimumPrefixHexLength else {
            ExtBridgeLog.app.warning("Ignoring WIFI_UPGRADE_READY with invalid certificate fingerprint")
            return
        }

        let deviceName = currentConnection?.deviceName ?? fallbackDeviceName
        let deviceModel = currentConnection?.model ?? fallbackModel

        // Persist trust credentials immediately so subsequent wireless connections require zero PIN.
        discovery.upsertTrustedDevice(
            deviceName: deviceName,
            model: deviceModel,
            certFingerprint: normalizedExpectedFp,
            authToken: ready.authToken,
            lastHost: ready.host,
            lastPort: port
        )

        // Probe LAN TLS reachability & verify certificate pin BEFORE touching the live wireless ADB session.
        upgradeProbeTask?.cancel()
        upgradeProbeTask = Task { [weak self] in
            guard let self else { return }
            do {
                let actualFullFp = try await ExtCastSession.probeCertificateFingerprint(
                    host: ready.host,
                    port: port,
                    timeoutSeconds: Self.tlsProbeTimeoutSeconds
                )
                guard !Task.isCancelled else { return }
                guard FingerprintUtil.matches(actualFullHex: actualFullFp, expected: normalizedExpectedFp) else {
                    ExtBridgeLog.app.warning(
                        "Wi-Fi TLS probe fingerprint mismatch for \(ready.host, privacy: .public):\(port); keeping wireless ADB"
                    )
                    return
                }
                guard isEligibleForHandoff() else { return }

                // Update TrustStore with the verified full 64-char SHA-256 fingerprint.
                discovery.upsertTrustedDevice(
                    deviceName: deviceName,
                    model: deviceModel,
                    certFingerprint: actualFullFp,
                    authToken: ready.authToken,
                    lastHost: ready.host,
                    lastPort: port
                )

                // Retain the live ADB forward as a silent fallback until TLS AUTH_RESULT succeeds.
                self.fallbackAdbForward = currentAdbForward()
                self.fallbackAdbTransport = currentConnection?.transport
                self.isUpgradingToWifiTls = true

                let tlsEndpoint = ExtCastEndpoint(
                    target: .hostPort(host: ready.host, port: port),
                    security: .tlsPinned(expectedFingerprint: actualFullFp),
                    autoReconnect: true
                )
                ExtBridgeLog.app.info(
                    "Wi-Fi TLS probe verified for \(ready.host, privacy: .public):\(port); staging seamless handoff"
                )
                onStageHandoff(ready.host, port, actualFullFp, ready.authToken, tlsEndpoint)
            } catch {
                // LAN direct TLS unreachable (AP isolation / firewall): stay on live wireless ADB cleanly.
                ExtBridgeLog.app.info(
                    "Wi-Fi TLS probe unreachable for \(ready.host, privacy: .public):\(port) (\(error.localizedDescription, privacy: .public)); remaining on wireless ADB"
                )
            }
        }
    }

    /// Finalizes a verified Wi-Fi TLS upgrade: migrates any open popout windows to the new TLS context
    /// BEFORE removing the obsolete ADB forward (or defers forward removal while any popout still uses it).
    /// Returns `true` when an in-progress Wi-Fi TLS upgrade was finalized.
    public func finalizeUpgradeIfInProgress(
        adbManager: AdbManager,
        popoutManager: PopoutWindowManager,
        newPopoutContext: PopoutConnectionContext?
    ) -> Bool {
        guard isUpgradingToWifiTls else { return false }
        isUpgradingToWifiTls = false

        let oldForward = fallbackAdbForward
        fallbackAdbForward = nil
        fallbackAdbTransport = nil

        if let newPopoutContext, !popoutManager.controllers.isEmpty {
            ExtBridgeLog.app.info(
                "Migrating \(popoutManager.controllers.count) open popout window(s) to upgraded Wi-Fi TLS endpoint"
            )
            popoutManager.migrateConnectionContext(to: newPopoutContext)
        }

        if let oldForward {
            if popoutManager.hasActiveController(usingLocalPort: oldForward.localPort) {
                ExtBridgeLog.app.info(
                    "Deferring removal of ADB forward tcp:\(oldForward.localPort) while popout window still references it"
                )
                deferredForwards.append(oldForward)
            } else {
                Task { [adbManager] in
                    await adbManager.removeForward(oldForward)
                }
            }
        }
        return true
    }

    /// Releases any deferred ADB forwards whose local port is no longer referenced by open popout windows.
    public func releaseUnusedDeferredForwards(
        adbManager: AdbManager,
        popoutManager: PopoutWindowManager
    ) {
        guard !deferredForwards.isEmpty else { return }
        var stillNeeded: [AdbForwardHandle] = []
        var releasable: [AdbForwardHandle] = []

        for forward in deferredForwards {
            if popoutManager.hasActiveController(usingLocalPort: forward.localPort) {
                stillNeeded.append(forward)
            } else {
                releasable.append(forward)
            }
        }
        deferredForwards = stillNeeded

        for forward in releasable {
            Task { [adbManager] in
                await adbManager.removeForward(forward)
            }
        }
    }

    /// Reverts a failed Wi-Fi TLS handoff back to the preserved wireless ADB forward tunnel.
    public func consumeFallbackForSilentRevert() -> (forward: AdbForwardHandle?, transport: ActiveTransportKind?) {
        isUpgradingToWifiTls = false
        let forward = fallbackAdbForward
        let transport = fallbackAdbTransport
        fallbackAdbForward = nil
        fallbackAdbTransport = nil
        ExtBridgeLog.app.warning("Wi-Fi TLS handoff failed; silently reverting to wireless ADB tunnel")
        return (forward, transport)
    }
}

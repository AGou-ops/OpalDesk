import CryptoKit
import Foundation
import Network
import Security
import os

public enum ExtCastTransportSecurity: Sendable, Equatable {
    /// Localhost plaintext TCP over ADB USB port forwarding (`127.0.0.1:<port>`).
    case wiredLocalhostPlaintext
    /// Wireless TLS 1.2/1.3 with strict leaf-certificate SHA-256 TOFU pinning.
    /// `expectedFingerprint` must be a 64-char SHA-256 hex digest or a >=16-char Bonjour prefix.
    case tlsPinned(expectedFingerprint: String)
}

public struct ExtCastEndpoint: Sendable, Equatable {
    public enum Target: Sendable, Equatable {
        case hostPort(host: String, port: UInt16)
        case bonjour(serviceName: String, domain: String)
    }

    public var target: Target
    public var security: ExtCastTransportSecurity
    public var autoReconnect: Bool

    public init(
        target: Target,
        security: ExtCastTransportSecurity,
        autoReconnect: Bool = true
    ) {
        self.target = target
        self.security = security
        self.autoReconnect = autoReconnect
    }

    public var nwEndpoint: NWEndpoint {
        switch target {
        case let .hostPort(host, port):
            return .hostPort(
                host: NWEndpoint.Host(host),
                port: NWEndpoint.Port(rawValue: port) ?? NWEndpoint.Port(integerLiteral: ExtCastProtocol.defaultWifiPort)
            )
        case let .bonjour(serviceName, domain):
            return .service(
                name: serviceName,
                type: ExtCastProtocol.bonjourServiceType,
                domain: domain,
                interface: nil
            )
        }
    }
}

public enum ExtCastSessionState: Sendable, Equatable {
    case idle
    case connecting
    case connected
    case reconnecting(attempt: Int)
    case failed(String)
    case disconnected
}

public enum ExtCastSessionError: Error, Sendable, Equatable, LocalizedError {
    case missingExpectedFingerprint
    case missingPeerCertificate
    case certificateFingerprintMismatch(expected: String, actual: String)
    case connectionFailed(String)
    case protocolError(String)
    case probeTimedOut
    case sendBackpressureDropped

    public var errorDescription: String? {
        switch self {
        case .missingExpectedFingerprint:
            return "拒绝未固定指纹的 TLS 连接：请先校验 Android 设备的证书短指纹"
        case .missingPeerCertificate:
            return "对端未提供有效的 TLS 叶证书"
        case let .certificateFingerprintMismatch(expected, actual):
            let expShort = FingerprintUtil.formatShort(expected)
            let actShort = FingerprintUtil.formatShort(actual)
            return "TLS 证书指纹不匹配（预期 \(expShort)，实际 \(actShort)），已中止连接以防中间人攻击"
        case .connectionFailed(let reason):
            return "网络连接失败：\(reason)"
        case .protocolError(let reason):
            return "协议解析错误：\(reason)"
        case .probeTimedOut:
            return "探测设备 TLS 证书超时，请确认手机端已开启无线大屏服务且处于同一局域网"
        case .sendBackpressureDropped:
            return "发送缓冲区拥塞，已丢弃低优先级帧"
        }
    }
}

public enum ExtCastSessionEvent: Sendable {
    case stateChanged(ExtCastSessionState)
    case verifiedPeerFingerprint(String)
    case frame(ExtCastFrame)
    case rttUpdated(Double)
    case error(ExtCastSessionError)
}

/// Manages an asynchronous ExtCast v1 transport connection over `Network.framework`.
/// Handles wired plaintext localhost and wireless TLS 1.2/1.3 with strict TOFU SHA-256 pinning,
/// streaming frame reassembly, ping/pong RTT telemetry, send backpressure, and auto-reconnect.
public final class ExtCastSession: Sendable {
    private static let heartbeatIntervalNs: UInt64 = 2_000_000_000 // 2 seconds
    private static let maxInFlightBytes: Int = 4 * 1024 * 1024 // 4 MiB backpressure limit
    private static let maxReconnectAttempts: Int = 5

    private struct MutableState {
        var endpoint: ExtCastEndpoint?
        var connection: NWConnection?
        var decoder = ExtCastStreamDecoder()
        var sessionState: ExtCastSessionState = .idle
        var eventHandler: (@Sendable (ExtCastSessionEvent) -> Void)?
        var inFlightBytes: Int = 0
        var nextPingId: UInt64 = 1
        var pendingPings: [UInt64: UInt64] = [:]
        var verifiedFingerprint: String?
        var tlsVerificationError: ExtCastSessionError?
        var reconnectAttempt: Int = 0
        var isExplicitDisconnect: Bool = false
        var heartbeatTask: Task<Void, Never>?
        var reconnectTask: Task<Void, Never>?
    }

    private let trustStore: TrustStore?
    private let networkQueue = DispatchQueue(label: "com.extbridge.mac.session", qos: .userInteractive)
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    public init(trustStore: TrustStore? = nil) {
        self.trustStore = trustStore
    }

    deinit {
        disconnect()
    }

    public var currentState: ExtCastSessionState {
        state.withLock { $0.sessionState }
    }

    public var verifiedPeerFingerprint: String? {
        state.withLock { $0.verifiedFingerprint }
    }

    public func connect(
        to endpoint: ExtCastEndpoint,
        onEvent: @escaping @Sendable (ExtCastSessionEvent) -> Void
    ) {
        disconnectInternal(emitDisconnected: false)

        state.withLock {
            $0.endpoint = endpoint
            $0.eventHandler = onEvent
            $0.isExplicitDisconnect = false
            $0.reconnectAttempt = 0
            $0.tlsVerificationError = nil
            $0.verifiedFingerprint = nil
        }

        startConnection(endpoint: endpoint)
    }

    public func disconnect() {
        disconnectInternal(emitDisconnected: true)
    }

    /// Sends an ExtCast frame. Set `allowDropOnCongestion: true` for high-frequency pointer moves
    /// so backpressure never stalls interactive latency.
    public func send(_ frame: ExtCastFrame, allowDropOnCongestion: Bool = false) {
        let encoded: Data
        do {
            encoded = try frame.encode()
        } catch {
            emit(.error(.protocolError(error.localizedDescription)))
            return
        }

        let (connection, accepted): (NWConnection?, Bool) = state.withLock { s in
            guard s.sessionState == .connected, let conn = s.connection else {
                return (nil, false)
            }
            if allowDropOnCongestion && (s.inFlightBytes + encoded.count > Self.maxInFlightBytes) {
                return (nil, false)
            }
            s.inFlightBytes += encoded.count
            return (conn, true)
        }

        guard accepted, let connection else {
            return
        }

        let byteCount = encoded.count
        connection.send(content: encoded, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            let isCurrent: Bool = self.state.withLock { s in
                guard s.connection === connection else { return false }
                s.inFlightBytes = max(0, s.inFlightBytes - byteCount)
                return !s.isExplicitDisconnect
            }
            if isCurrent, let error {
                self.handleConnectionFailure(error: error)
            }
        })
    }

    /// Connects briefly to a TLS endpoint, extracts the server leaf certificate SHA-256 fingerprint,
    /// and immediately aborts the handshake before any application payload is sent.
    /// Used for safe TOFU verification on manual IP:Port connections.
    public static func probeCertificateFingerprint(
        host: String,
        port: UInt16,
        timeoutSeconds: Double = 5.0
    ) async throws -> String {
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port) ?? NWEndpoint.Port(integerLiteral: ExtCastProtocol.defaultWifiPort)
        )
        return try await probeCertificateFingerprint(nwEndpoint: endpoint, timeoutSeconds: timeoutSeconds)
    }

    public static func probeCertificateFingerprint(
        nwEndpoint: NWEndpoint,
        timeoutSeconds: Double = 5.0
    ) async throws -> String {
        final class ProbeBox: Sendable {
            let lock = OSAllocatedUnfairLock<(resumed: Bool, connection: NWConnection?, timeoutTask: Task<Void, Never>?)>(
                initialState: (false, nil, nil)
            )
            let queue = DispatchQueue(label: "com.extbridge.mac.tls-probe", qos: .userInitiated)
        }

        let box = ProbeBox()
        return try await withCheckedThrowingContinuation { continuation in
            let finish: @Sendable (Result<String, Error>) -> Void = { result in
                let resources: (NWConnection?, Task<Void, Never>?)? = box.lock.withLock { s in
                    guard !s.resumed else { return nil }
                    s.resumed = true
                    let conn = s.connection
                    let task = s.timeoutTask
                    s.connection = nil
                    s.timeoutTask = nil
                    return (conn, task)
                }
                guard let (conn, task) = resources else { return }
                task?.cancel()
                conn?.cancel()
                continuation.resume(with: result)
            }

            let tlsOptions = NWProtocolTLS.Options()
            sec_protocol_options_set_min_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv12)
            sec_protocol_options_set_verify_block(
                tlsOptions.securityProtocolOptions,
                { _, secTrust, verifyComplete in
                    defer {
                        // Always reject probe handshake so zero ExtCast bytes leave the host before user approval.
                        verifyComplete(false)
                    }
                    guard let fingerprint = ExtCastSession.extractLeafCertificateSHA256(from: secTrust) else {
                        finish(.failure(ExtCastSessionError.missingPeerCertificate))
                        return
                    }
                    finish(.success(fingerprint))
                },
                box.queue
            )

            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.connectionTimeout = max(1, Int(ceil(timeoutSeconds)))
            let params = NWParameters(tls: tlsOptions, tcp: tcpOptions)
            let connection = NWConnection(to: nwEndpoint, using: params)

            let timeoutTask = Task.detached(priority: .utility) {
                let ns = UInt64(max(timeoutSeconds, 0.5) * 1_000_000_000)
                try? await Task.sleep(nanoseconds: ns)
                guard !Task.isCancelled else { return }
                finish(.failure(ExtCastSessionError.probeTimedOut))
            }

            box.lock.withLock {
                $0.connection = connection
                $0.timeoutTask = timeoutTask
            }

            connection.stateUpdateHandler = { state in
                switch state {
                case .failed(let error):
                    finish(.failure(ExtCastSessionError.connectionFailed(error.localizedDescription)))
                case .waiting(let error):
                    finish(.failure(ExtCastSessionError.connectionFailed(error.localizedDescription)))
                default:
                    break
                }
            }

            connection.start(queue: box.queue)
        }
    }

    // MARK: - Internal Connection Lifecycle

    private func startConnection(endpoint: ExtCastEndpoint) {
        if case let .tlsPinned(expected) = endpoint.security {
            let normalized = FingerprintUtil.normalize(expected)
            guard normalized.count >= FingerprintUtil.minimumPrefixHexLength else {
                transition(to: .failed(ExtCastSessionError.missingExpectedFingerprint.localizedDescription))
                emit(.error(.missingExpectedFingerprint))
                return
            }
        }

        let params = makeParameters(for: endpoint)
        let connection = NWConnection(to: endpoint.nwEndpoint, using: params)

        state.withLock {
            $0.connection = connection
            $0.decoder.reset()
            $0.inFlightBytes = 0
            $0.pendingPings.removeAll()
            $0.tlsVerificationError = nil
        }

        transition(to: .connecting)

        connection.stateUpdateHandler = { [weak self] newState in
            guard let self else { return }
            self.handleConnectionState(newState, connection: connection)
        }

        connection.start(queue: networkQueue)
    }

    private func makeParameters(for endpoint: ExtCastEndpoint) -> NWParameters {
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        tcpOptions.enableKeepalive = true
        tcpOptions.keepaliveIdle = 10
        tcpOptions.connectionTimeout = 8

        switch endpoint.security {
        case .wiredLocalhostPlaintext:
            return NWParameters(tls: nil, tcp: tcpOptions)

        case .tlsPinned(let expectedFingerprint):
            let tlsOptions = NWProtocolTLS.Options()
            sec_protocol_options_set_min_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv12)
            sec_protocol_options_set_max_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv13)

            // Resolve strongest pin: if TrustStore already has a full 64-char pin matching this prefix/host,
            // enforce the full 64-char pin so a shorter Bonjour prefix cannot downgrade an existing trust entry.
            let effectiveExpected = resolveStrongestPin(expectedFingerprint: expectedFingerprint, endpoint: endpoint)

            sec_protocol_options_set_verify_block(
                tlsOptions.securityProtocolOptions,
                { [weak self] _, secTrust, verifyComplete in
                    guard let self else {
                        verifyComplete(false)
                        return
                    }
                    guard let actualHex = ExtCastSession.extractLeafCertificateSHA256(from: secTrust) else {
                        self.state.withLock { $0.tlsVerificationError = .missingPeerCertificate }
                        verifyComplete(false)
                        return
                    }

                    guard FingerprintUtil.matches(actualFullHex: actualHex, expected: effectiveExpected) else {
                        self.state.withLock {
                            $0.tlsVerificationError = .certificateFingerprintMismatch(
                                expected: effectiveExpected,
                                actual: actualHex
                            )
                        }
                        verifyComplete(false)
                        return
                    }

                    self.state.withLock {
                        $0.verifiedFingerprint = actualHex
                    }
                    self.emit(.verifiedPeerFingerprint(actualHex))
                    verifyComplete(true)
                },
                networkQueue
            )

            return NWParameters(tls: tlsOptions, tcp: tcpOptions)
        }
    }

    private func resolveStrongestPin(expectedFingerprint: String, endpoint: ExtCastEndpoint) -> String {
        let normalized = FingerprintUtil.normalize(expectedFingerprint)
        if normalized.count == FingerprintUtil.fullHexLength {
            return normalized
        }
        guard let trustStore else {
            return normalized
        }
        if let knownByFp = trustStore.find(byFingerprint: normalized),
           FingerprintUtil.isValidFullHex(knownByFp.certFingerprint)
        {
            return knownByFp.certFingerprint
        }
        if case let .hostPort(host, port) = endpoint.target,
           let knownByHost = trustStore.find(byHost: host, port: port),
           FingerprintUtil.isValidFullHex(knownByHost.certFingerprint)
        {
            return knownByHost.certFingerprint
        }
        return normalized
    }

    private static func extractLeafCertificateSHA256(from secTrust: sec_trust_t) -> String? {
        let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first
        else {
            return nil
        }
        let derData = SecCertificateCopyData(leaf) as Data
        return FingerprintUtil.sha256Hex(of: derData)
    }

    private func handleConnectionState(_ newState: NWConnection.State, connection: NWConnection) {
        let isCurrent: Bool = state.withLock { $0.connection === connection }
        guard isCurrent else { return }

        switch newState {
        case .ready:
            state.withLock { $0.reconnectAttempt = 0 }
            transition(to: .connected)
            startHeartbeat()
            receiveNextChunk(from: connection)

        case .failed(let error):
            handleConnectionFailure(error: error)

        case .waiting(let error):
            // Surface TLS certificate verification failures immediately without retrying.
            if let tlsError = state.withLock({ $0.tlsVerificationError }) {
                abortWithFatalError(tlsError)
            } else if case .posix(.ECONNREFUSED) = error {
                handleConnectionFailure(error: error)
            }

        case .cancelled:
            let wasExplicit = state.withLock { $0.isExplicitDisconnect }
            if wasExplicit {
                transition(to: .disconnected)
            }

        default:
            break
        }
    }

    private func receiveNextChunk(from connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] content, _, isComplete, error in
            guard let self else { return }

            let isStillCurrent: Bool = self.state.withLock {
                $0.connection === connection && !$0.isExplicitDisconnect
            }
            guard isStillCurrent else { return }

            if let data = content, !data.isEmpty {
                self.processIncomingData(data)
            }

            if let error {
                self.handleConnectionFailure(error: error)
                return
            }

            if isComplete {
                self.handleRemoteEOF()
                return
            }

            let shouldContinue: Bool = self.state.withLock {
                $0.connection === connection && !$0.isExplicitDisconnect
            }
            if shouldContinue {
                self.receiveNextChunk(from: connection)
            }
        }
    }

    private func processIncomingData(_ data: Data) {
        let result: Result<[ExtCastFrame], Error> = state.withLock { s in
            s.decoder.append(data)
            return Result { try s.decoder.decodeAvailableFrames() }
        }

        switch result {
        case .success(let frames):
            for frame in frames {
                if frame.type == .pong {
                    handlePongFrame(frame)
                }
                emit(.frame(frame))
            }

        case .failure(let error):
            abortWithFatalError(.protocolError(error.localizedDescription))
        }
    }

    private func handlePongFrame(_ frame: ExtCastFrame) {
        guard let pong = try? PingPongPayload.decode(from: frame.payload) else { return }
        let nowNs = DispatchTime.now().uptimeNanoseconds
        let sentNs: UInt64? = state.withLock { s in
            let sent = s.pendingPings.removeValue(forKey: pong.pingId)
            // Prune stale pings older than 15s to keep the map tiny.
            let cutoff = nowNs > 15_000_000_000 ? nowNs - 15_000_000_000 : 0
            s.pendingPings = s.pendingPings.filter { $0.value >= cutoff }
            return sent
        }
        if let sentNs, nowNs >= sentNs {
            let rttMs = Double(nowNs - sentNs) / 1_000_000.0
            emit(.rttUpdated(rttMs))
        }
    }

    private func startHeartbeat() {
        let task = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.heartbeatIntervalNs)
                guard !Task.isCancelled, let self else { break }
                let pingId: UInt64? = self.state.withLock { s in
                    guard s.sessionState == .connected else { return nil }
                    let id = s.nextPingId
                    s.nextPingId &+= 1
                    s.pendingPings[id] = DispatchTime.now().uptimeNanoseconds
                    return id
                }
                if let pingId {
                    self.send(.ping(pingId))
                }
            }
        }
        let previous = state.withLock { s -> Task<Void, Never>? in
            let prev = s.heartbeatTask
            s.heartbeatTask = task
            return prev
        }
        previous?.cancel()
    }

    private func handleRemoteEOF() {
        let shouldReconnect = shouldAttemptReconnect()
        cleanupActiveConnection()
        if shouldReconnect {
            scheduleReconnect()
        } else {
            transition(to: .disconnected)
        }
    }

    private func handleConnectionFailure(error: NWError) {
        if let tlsError = state.withLock({ $0.tlsVerificationError }) {
            abortWithFatalError(tlsError)
            return
        }

        let wasExplicit = state.withLock { $0.isExplicitDisconnect }
        guard !wasExplicit else { return }

        let sessionError = ExtCastSessionError.connectionFailed(error.localizedDescription)
        emit(.error(sessionError))

        let shouldReconnect = shouldAttemptReconnect()
        cleanupActiveConnection()

        if shouldReconnect {
            scheduleReconnect()
        } else {
            transition(to: .failed(sessionError.localizedDescription))
        }
    }

    private func abortWithFatalError(_ error: ExtCastSessionError) {
        cleanupActiveConnection()
        state.withLock { $0.isExplicitDisconnect = true }
        emit(.error(error))
        transition(to: .failed(error.localizedDescription))
    }

    private func shouldAttemptReconnect() -> Bool {
        state.withLock { s in
            guard !s.isExplicitDisconnect,
                  s.tlsVerificationError == nil,
                  let endpoint = s.endpoint,
                  endpoint.autoReconnect,
                  s.reconnectAttempt < Self.maxReconnectAttempts
            else {
                return false
            }
            return true
        }
    }

    private func scheduleReconnect() {
        let (attempt, endpoint): (Int, ExtCastEndpoint?) = state.withLock { s in
            s.reconnectAttempt += 1
            return (s.reconnectAttempt, s.endpoint)
        }
        guard let endpoint else { return }

        transition(to: .reconnecting(attempt: attempt))
        let delaySeconds = min(Double(1 << (attempt - 1)), 8.0)

        let task = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delaySeconds * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            let stillActive = self.state.withLock { !$0.isExplicitDisconnect }
            guard stillActive else { return }
            self.startConnection(endpoint: endpoint)
        }

        let previous = state.withLock { s -> Task<Void, Never>? in
            let prev = s.reconnectTask
            s.reconnectTask = task
            return prev
        }
        previous?.cancel()
    }

    private func cleanupActiveConnection() {
        let (conn, heartbeat) = state.withLock { s -> (NWConnection?, Task<Void, Never>?) in
            let c = s.connection
            let h = s.heartbeatTask
            s.connection = nil
            s.heartbeatTask = nil
            s.inFlightBytes = 0
            s.pendingPings.removeAll()
            s.decoder.reset()
            return (c, h)
        }
        heartbeat?.cancel()
        conn?.stateUpdateHandler = nil
        conn?.cancel()
    }

    private func disconnectInternal(emitDisconnected: Bool) {
        let (conn, heartbeat, reconnect, handler) = state.withLock {
            s -> (NWConnection?, Task<Void, Never>?, Task<Void, Never>?, (@Sendable (ExtCastSessionEvent) -> Void)?) in
            s.isExplicitDisconnect = true
            let c = s.connection
            let h = s.heartbeatTask
            let r = s.reconnectTask
            let ev = s.eventHandler
            s.connection = nil
            s.heartbeatTask = nil
            s.reconnectTask = nil
            s.inFlightBytes = 0
            s.pendingPings.removeAll()
            s.decoder.reset()
            s.sessionState = .disconnected
            return (c, h, r, ev)
        }
        heartbeat?.cancel()
        reconnect?.cancel()
        conn?.stateUpdateHandler = nil
        conn?.cancel()
        if emitDisconnected {
            handler?(.stateChanged(.disconnected))
        }
    }

    private func transition(to newState: ExtCastSessionState) {
        state.withLock { $0.sessionState = newState }
        emit(.stateChanged(newState))
    }

    private func emit(_ event: ExtCastSessionEvent) {
        let handler = state.withLock { $0.eventHandler }
        handler?(event)
    }
}

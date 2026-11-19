import Foundation
import Network
import os

public enum ExtCastSessionState: Sendable, Equatable {
    case idle
    case connecting
    case connected
    case reconnecting(attempt: Int)
    case failed(String)
    case disconnected
}

public enum ExtCastSessionEvent: Sendable {
    case stateChanged(ExtCastSessionState)
    case verifiedPeerFingerprint(String)
    case frame(ExtCastFrame)
    case rttUpdated(Double)
    case error(ExtCastSessionError)
}

/// Manages an asynchronous ExtCast v1.1 transport connection over `Network.framework`.
/// Handles wired plaintext localhost and wireless TLS 1.2/1.3 with strict TOFU SHA-256 pinning,
/// streaming frame reassembly, ping/pong RTT telemetry, bounded send backpressure, and auto-reconnect.
public final class ExtCastSession: Sendable {
    private static let heartbeatIntervalNs: UInt64 = 2_000_000_000 // 2 seconds
    private static let stalePingCutoffNs: UInt64 = 15_000_000_000 // 15 seconds
    private static let receiveChunkSize: Int = 256 * 1024 // 256 KiB
    private static let maxDroppableInFlightBytes: Int = 4 * 1024 * 1024 // 4 MiB
    private static let pacedInFlightLimitBytes: Int = 2 * 1024 * 1024 // 2 MiB
    private static let maxHardInFlightBytes: Int = 16 * 1024 * 1024 // 16 MiB OOM guard
    private static let maxReconnectAttempts: Int = 5
    private static let maxReconnectDelaySeconds: Double = 8.0

    private struct CapacityWaiter {
        let limit: Int
        let continuation: CheckedContinuation<Void, Never>
    }

    private struct MutableState {
        var endpoint: ExtCastEndpoint?
        var connection: NWConnection?
        var decoder = ExtCastStreamDecoder()
        var sessionState: ExtCastSessionState = .idle
        var eventHandler: (@Sendable (ExtCastSessionEvent) -> Void)?
        var inFlightBytes: Int = 0
        var nextWaiterId: UInt64 = 1
        var capacityWaiters: [UInt64: CapacityWaiter] = [:]
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

    public var inFlightByteCount: Int {
        state.withLock { $0.inFlightBytes }
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
    /// so backpressure never stalls interactive latency. Non-droppable frames are guarded by a hard
    /// 16 MiB in-flight memory cap.
    public func send(_ frame: ExtCastFrame, allowDropOnCongestion: Bool = false) {
        let encoded: Data
        do {
            encoded = try frame.encode()
        } catch {
            ExtBridgeLog.session.error("Failed to encode frame type=\(frame.type.rawValue): \(error.localizedDescription, privacy: .public)")
            emit(.error(.protocolError(error.localizedDescription)))
            return
        }

        enum EnqueueDecision {
            case send(NWConnection)
            case droppedLowPriority
            case bufferOverflow(currentBytes: Int)
            case notConnected
        }

        let decision: EnqueueDecision = state.withLock { s in
            guard s.sessionState == .connected, let conn = s.connection else {
                return .notConnected
            }
            let projected = s.inFlightBytes + encoded.count
            if allowDropOnCongestion && projected > Self.maxDroppableInFlightBytes {
                return .droppedLowPriority
            }
            if !allowDropOnCongestion && projected > Self.maxHardInFlightBytes {
                return .bufferOverflow(currentBytes: s.inFlightBytes)
            }
            s.inFlightBytes = projected
            return .send(conn)
        }

        switch decision {
        case .notConnected, .droppedLowPriority:
            return
        case .bufferOverflow(let currentBytes):
            ExtBridgeLog.session.error("Send buffer overflow: inFlight=\(currentBytes) incoming=\(encoded.count) limit=\(Self.maxHardInFlightBytes)")
            emit(.error(.sendBufferOverflow))
            return
        case .send(let connection):
            dispatchSend(encoded, over: connection)
        }
    }

    /// Sends a bulk frame (e.g. file transfer chunk) after awaiting in-flight buffer capacity,
    /// preventing unbounded memory growth on slow networks.
    public func sendPaced(_ frame: ExtCastFrame) async {
        await waitForSendCapacity(limit: Self.pacedInFlightLimitBytes)
        guard !Task.isCancelled else { return }
        send(frame, allowDropOnCongestion: false)
    }

    /// Suspends until `inFlightBytes < limit`, the session leaves `.connected`, or the task is cancelled.
    public func waitForSendCapacity(limit: Int = 2 * 1024 * 1024) async {
        let clampedLimit = max(limit, 1)
        let waiterId: UInt64 = state.withLock { s in
            let id = s.nextWaiterId
            s.nextWaiterId &+= 1
            return id
        }

        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                let shouldResumeImmediately: Bool = state.withLock { s in
                    if Task.isCancelled || s.sessionState != .connected || s.inFlightBytes < clampedLimit {
                        return true
                    }
                    s.capacityWaiters[waiterId] = CapacityWaiter(limit: clampedLimit, continuation: continuation)
                    return false
                }
                if shouldResumeImmediately {
                    continuation.resume()
                }
            }
        } onCancel: { [weak self] in
            guard let self else { return }
            let waiter = self.state.withLock { $0.capacityWaiters.removeValue(forKey: waiterId) }
            waiter?.continuation.resume()
        }
    }

    // MARK: - Internal Connection Lifecycle

    private func dispatchSend(_ encoded: Data, over connection: NWConnection) {
        let byteCount = encoded.count
        connection.send(content: encoded, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            let (isCurrent, readyWaiters): (Bool, [CheckedContinuation<Void, Never>]) = self.state.withLock { s in
                guard s.connection === connection else { return (false, []) }
                s.inFlightBytes = max(0, s.inFlightBytes - byteCount)
                let ready = Self.popSatisfiedWaiters(from: &s)
                return (!s.isExplicitDisconnect, ready)
            }
            for continuation in readyWaiters {
                continuation.resume()
            }
            if isCurrent, let error {
                self.handleConnectionFailure(error: error)
            }
        })
    }

    private func startConnection(endpoint: ExtCastEndpoint) {
        if case let .tlsPinned(expected) = endpoint.security {
            let normalized = FingerprintUtil.normalize(expected)
            guard normalized.count >= FingerprintUtil.minimumPrefixHexLength else {
                ExtBridgeLog.session.error("Rejected TLS connect without valid fingerprint pin")
                transition(to: .failed(ExtCastSessionError.missingExpectedFingerprint.localizedDescription))
                emit(.error(.missingExpectedFingerprint))
                return
            }
        }

        let params = ExtCastTLSConfiguration.makeParameters(
            for: endpoint,
            trustStore: trustStore,
            verifyQueue: networkQueue,
            onVerified: { [weak self] actualHex in
                guard let self else { return }
                self.state.withLock { $0.verifiedFingerprint = actualHex }
                self.emit(.verifiedPeerFingerprint(actualHex))
            },
            onVerificationError: { [weak self] tlsError in
                self?.state.withLock { $0.tlsVerificationError = tlsError }
            }
        )
        let connection = NWConnection(to: endpoint.nwEndpoint, using: params)

        let staleWaiters: [CheckedContinuation<Void, Never>] = state.withLock { s in
            s.connection = connection
            s.decoder.reset()
            s.inFlightBytes = 0
            s.pendingPings.removeAll()
            s.tlsVerificationError = nil
            return Self.popAllWaiters(from: &s)
        }
        for continuation in staleWaiters {
            continuation.resume()
        }

        transition(to: .connecting)

        connection.stateUpdateHandler = { [weak self] newState in
            guard let self else { return }
            self.handleConnectionState(newState, connection: connection)
        }

        connection.start(queue: networkQueue)
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
        connection.receive(minimumIncompleteLength: 1, maximumLength: Self.receiveChunkSize) { [weak self] content, _, isComplete, error in
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
            ExtBridgeLog.session.error("Protocol decode error: \(error.localizedDescription, privacy: .public)")
            abortWithFatalError(.protocolError(error.localizedDescription))
        }
    }

    private func handlePongFrame(_ frame: ExtCastFrame) {
        let pong: PingPongPayload
        do {
            pong = try PingPongPayload.decode(from: frame.payload)
        } catch {
            ExtBridgeLog.session.warning("Failed to decode PONG payload: \(error.localizedDescription, privacy: .public)")
            return
        }

        let nowNs = DispatchTime.now().uptimeNanoseconds
        let sentNs: UInt64? = state.withLock { s in
            let sent = s.pendingPings.removeValue(forKey: pong.pingId)
            let cutoff = nowNs > Self.stalePingCutoffNs ? nowNs - Self.stalePingCutoffNs : 0
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
                do {
                    try await Task.sleep(nanoseconds: Self.heartbeatIntervalNs)
                } catch {
                    break
                }
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
        ExtBridgeLog.session.info("Remote ExtCast peer closed connection (EOF)")
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

        ExtBridgeLog.session.warning("ExtCast connection error: \(error.localizedDescription, privacy: .public)")
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
        let delaySeconds = min(Double(1 << (attempt - 1)), Self.maxReconnectDelaySeconds)
        ExtBridgeLog.session.info("Scheduling reconnect attempt \(attempt) in \(delaySeconds, privacy: .public)s")

        let task = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: UInt64(delaySeconds * 1_000_000_000))
            } catch {
                return
            }
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
        teardownConnection(explicitDisconnect: false, emitDisconnected: false)
    }

    private func disconnectInternal(emitDisconnected: Bool) {
        teardownConnection(explicitDisconnect: true, emitDisconnected: emitDisconnected)
    }

    private func teardownConnection(explicitDisconnect: Bool, emitDisconnected: Bool) {
        struct TornDownResources {
            let connection: NWConnection?
            let heartbeat: Task<Void, Never>?
            let reconnect: Task<Void, Never>?
            let handler: (@Sendable (ExtCastSessionEvent) -> Void)?
            let waiters: [CheckedContinuation<Void, Never>]
        }

        let resources: TornDownResources = state.withLock { s in
            let conn = s.connection
            let hb = s.heartbeatTask
            let rc = explicitDisconnect ? s.reconnectTask : nil
            let ev = emitDisconnected ? s.eventHandler : nil
            s.connection = nil
            s.heartbeatTask = nil
            s.inFlightBytes = 0
            s.pendingPings.removeAll()
            s.decoder.reset()
            if explicitDisconnect {
                s.isExplicitDisconnect = true
                s.reconnectTask = nil
                s.sessionState = .disconnected
            }
            return TornDownResources(
                connection: conn,
                heartbeat: hb,
                reconnect: rc,
                handler: ev,
                waiters: Self.popAllWaiters(from: &s)
            )
        }
        for continuation in resources.waiters {
            continuation.resume()
        }
        resources.heartbeat?.cancel()
        resources.reconnect?.cancel()
        resources.connection?.stateUpdateHandler = nil
        resources.connection?.cancel()
        resources.handler?(.stateChanged(.disconnected))
    }

    private func transition(to newState: ExtCastSessionState) {
        state.withLock { $0.sessionState = newState }
        emit(.stateChanged(newState))
    }

    private func emit(_ event: ExtCastSessionEvent) {
        let handler = state.withLock { $0.eventHandler }
        handler?(event)
    }

    private static func popSatisfiedWaiters(from state: inout MutableState) -> [CheckedContinuation<Void, Never>] {
        guard !state.capacityWaiters.isEmpty else { return [] }
        var ready: [CheckedContinuation<Void, Never>] = []
        let keys = state.capacityWaiters.compactMap { id, waiter in
            state.inFlightBytes < waiter.limit ? id : nil
        }
        for key in keys {
            if let removed = state.capacityWaiters.removeValue(forKey: key) {
                ready.append(removed.continuation)
            }
        }
        return ready
    }

    private static func popAllWaiters(from state: inout MutableState) -> [CheckedContinuation<Void, Never>] {
        guard !state.capacityWaiters.isEmpty else { return [] }
        let continuations = state.capacityWaiters.values.map(\.continuation)
        state.capacityWaiters.removeAll()
        return continuations
    }
}

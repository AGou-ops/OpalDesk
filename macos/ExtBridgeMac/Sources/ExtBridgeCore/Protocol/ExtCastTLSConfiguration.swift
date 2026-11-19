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

public enum ExtCastSessionError: Error, Sendable, Equatable, LocalizedError {
    case missingExpectedFingerprint
    case missingPeerCertificate
    case certificateFingerprintMismatch(expected: String, actual: String)
    case connectionFailed(String)
    case protocolError(String)
    case probeTimedOut
    case sendBufferOverflow

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
        case .sendBufferOverflow:
            return "发送缓冲区超过安全上限，已阻止继续入队以防止内存溢出"
        }
    }
}

/// TLS 1.2/1.3 transport configuration, TOFU SHA-256 certificate pinning, and safe leaf-certificate probing.
public enum ExtCastTLSConfiguration {
    private static let tcpKeepaliveIdleSeconds: Int = 10
    private static let tcpConnectionTimeoutSeconds: Int = 8
    private static let minProbeTimeoutSeconds: Double = 0.5

    /// Builds `NWParameters` for either wired localhost plaintext or wireless TLS with strict leaf SHA-256 pinning.
    static func makeParameters(
        for endpoint: ExtCastEndpoint,
        trustStore: TrustStore?,
        verifyQueue: DispatchQueue,
        onVerified: @escaping @Sendable (String) -> Void,
        onVerificationError: @escaping @Sendable (ExtCastSessionError) -> Void
    ) -> NWParameters {
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        tcpOptions.enableKeepalive = true
        tcpOptions.keepaliveIdle = tcpKeepaliveIdleSeconds
        tcpOptions.connectionTimeout = tcpConnectionTimeoutSeconds

        switch endpoint.security {
        case .wiredLocalhostPlaintext:
            return NWParameters(tls: nil, tcp: tcpOptions)

        case .tlsPinned(let expectedFingerprint):
            let tlsOptions = NWProtocolTLS.Options()
            sec_protocol_options_set_min_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv12)
            sec_protocol_options_set_max_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv13)

            // Enforce the strongest known pin so a shorter Bonjour TXT prefix cannot downgrade an existing 64-char trust entry.
            let effectiveExpected = resolveStrongestPin(
                expectedFingerprint: expectedFingerprint,
                endpoint: endpoint,
                trustStore: trustStore
            )

            sec_protocol_options_set_verify_block(
                tlsOptions.securityProtocolOptions,
                { _, secTrust, verifyComplete in
                    guard let actualHex = extractLeafCertificateSHA256(from: secTrust) else {
                        ExtBridgeLog.session.error("TLS verification failed: peer presented no leaf certificate")
                        onVerificationError(.missingPeerCertificate)
                        verifyComplete(false)
                        return
                    }

                    guard FingerprintUtil.matches(actualFullHex: actualHex, expected: effectiveExpected) else {
                        ExtBridgeLog.session.error(
                            "TLS fingerprint mismatch: expected=\(FingerprintUtil.formatShort(effectiveExpected), privacy: .public) actual=\(FingerprintUtil.formatShort(actualHex), privacy: .public)"
                        )
                        onVerificationError(.certificateFingerprintMismatch(expected: effectiveExpected, actual: actualHex))
                        verifyComplete(false)
                        return
                    }

                    onVerified(actualHex)
                    verifyComplete(true)
                },
                verifyQueue
            )

            return NWParameters(tls: tlsOptions, tcp: tcpOptions)
        }
    }

    static func resolveStrongestPin(
        expectedFingerprint: String,
        endpoint: ExtCastEndpoint,
        trustStore: TrustStore?
    ) -> String {
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

    static func extractLeafCertificateSHA256(from secTrust: sec_trust_t) -> String? {
        let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first
        else {
            return nil
        }
        let derData = SecCertificateCopyData(leaf) as Data
        return FingerprintUtil.sha256Hex(of: derData)
    }

    /// Connects briefly to a TLS endpoint, extracts the server leaf certificate SHA-256 fingerprint,
    /// and immediately aborts the handshake before any application payload is sent.
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
                    guard let fingerprint = extractLeafCertificateSHA256(from: secTrust) else {
                        ExtBridgeLog.session.error("TLS probe failed: peer presented no leaf certificate")
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
                let ns = UInt64(max(timeoutSeconds, minProbeTimeoutSeconds) * 1_000_000_000)
                do {
                    try await Task.sleep(nanoseconds: ns)
                } catch {
                    return
                }
                guard !Task.isCancelled else { return }
                ExtBridgeLog.session.warning("TLS certificate probe timed out after \(timeoutSeconds, privacy: .public)s")
                finish(.failure(ExtCastSessionError.probeTimedOut))
            }

            box.lock.withLock {
                $0.connection = connection
                $0.timeoutTask = timeoutTask
            }

            connection.stateUpdateHandler = { state in
                switch state {
                case .failed(let error):
                    ExtBridgeLog.session.warning("TLS probe connection failed: \(error.localizedDescription, privacy: .public)")
                    finish(.failure(ExtCastSessionError.connectionFailed(error.localizedDescription)))
                case .waiting(let error):
                    ExtBridgeLog.session.warning("TLS probe waiting on error: \(error.localizedDescription, privacy: .public)")
                    finish(.failure(ExtCastSessionError.connectionFailed(error.localizedDescription)))
                default:
                    break
                }
            }

            connection.start(queue: box.queue)
        }
    }
}

public extension ExtCastSession {
    static func probeCertificateFingerprint(
        host: String,
        port: UInt16,
        timeoutSeconds: Double = 5.0
    ) async throws -> String {
        try await ExtCastTLSConfiguration.probeCertificateFingerprint(
            host: host,
            port: port,
            timeoutSeconds: timeoutSeconds
        )
    }

    static func probeCertificateFingerprint(
        nwEndpoint: NWEndpoint,
        timeoutSeconds: Double = 5.0
    ) async throws -> String {
        try await ExtCastTLSConfiguration.probeCertificateFingerprint(
            nwEndpoint: nwEndpoint,
            timeoutSeconds: timeoutSeconds
        )
    }
}

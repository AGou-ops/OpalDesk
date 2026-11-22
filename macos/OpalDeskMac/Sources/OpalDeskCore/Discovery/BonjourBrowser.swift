import Foundation
import Network
import os

/// An Android OpalDesk host discovered via Bonjour (`_opaldesk._tcp`).
public struct DiscoveredBonjourDevice: Sendable, Equatable, Identifiable, Hashable {
    public let id: String
    public let serviceName: String
    public let domain: String
    public let displayName: String
    public let model: String
    public let fingerprintPrefix: String
    public let protocolVersion: String

    public init(
        serviceName: String,
        domain: String,
        displayName: String,
        model: String,
        fingerprintPrefix: String,
        protocolVersion: String
    ) {
        self.id = "\(serviceName).\(domain)"
        self.serviceName = serviceName
        self.domain = domain
        self.displayName = displayName.isEmpty ? serviceName : displayName
        self.model = model
        self.fingerprintPrefix = FingerprintUtil.normalize(fingerprintPrefix)
        self.protocolVersion = protocolVersion
    }

    public var shortFingerprint: String {
        FingerprintUtil.formatShort(fingerprintPrefix)
    }

    public var nwEndpoint: NWEndpoint {
        .service(
            name: serviceName,
            type: OpalCastProtocol.bonjourServiceType,
            domain: domain,
            interface: nil
        )
    }
}

/// Real-time Bonjour browser discovering `_opaldesk._tcp` Android hosts on the local network.
public final class BonjourBrowser: Sendable {
    enum TXTRecordKey {
        static let name = "name"
        static let model = "model"
        static let fingerprint = "fp"
        static let version = "ver"
        static let allKeys = [name, model, fingerprint, version]
    }

    private struct MutableState {
        var browser: NWBrowser?
        var devices: [DiscoveredBonjourDevice] = []
        var onUpdate: (@Sendable ([DiscoveredBonjourDevice]) -> Void)?
        var onStateChange: (@Sendable (NWBrowser.State) -> Void)?
    }

    private let queue = DispatchQueue(label: "io.github.lengsukq.opaldesk.mac.bonjour", qos: .userInitiated)
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    public init() {}

    public func start(
        onUpdate: @escaping @Sendable ([DiscoveredBonjourDevice]) -> Void,
        onStateChange: (@Sendable (NWBrowser.State) -> Void)? = nil
    ) {
        stop()

        let params = NWParameters()
        params.includePeerToPeer = true
        let descriptor = NWBrowser.Descriptor.bonjourWithTXTRecord(
            type: OpalCastProtocol.bonjourServiceType,
            domain: nil
        )
        let browser = NWBrowser(for: descriptor, using: params)

        state.withLock {
            $0.browser = browser
            $0.onUpdate = onUpdate
            $0.onStateChange = onStateChange
        }

        browser.stateUpdateHandler = { [weak self] newState in
            guard let self else { return }
            if case .failed(let error) = newState {
                OpalDeskLog.session.error("Bonjour browser failed: \(error.localizedDescription, privacy: .public)")
            }
            let callback = self.state.withLock { $0.onStateChange }
            callback?(newState)
        }

        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            let parsed = BonjourBrowser.parseResults(results)
            let callback: (@Sendable ([DiscoveredBonjourDevice]) -> Void)? = self.state.withLock {
                $0.devices = parsed
                return $0.onUpdate
            }
            callback?(parsed)
        }

        browser.start(queue: queue)
    }

    public func stop() {
        let existing: NWBrowser? = state.withLock {
            let b = $0.browser
            $0.browser = nil
            $0.devices = []
            $0.onUpdate = nil
            $0.onStateChange = nil
            return b
        }
        existing?.cancel()
    }

    private static func parseResults(_ results: Set<NWBrowser.Result>) -> [DiscoveredBonjourDevice] {
        var list: [DiscoveredBonjourDevice] = []
        list.reserveCapacity(results.count)
        for result in results {
            guard case let .service(name, _, domain, _) = result.endpoint else {
                continue
            }
            let txt = extractTXTRecord(from: result.metadata)
            list.append(makeDevice(serviceName: name, domain: domain, txt: txt))
        }
        return list.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }

    static func makeDevice(
        serviceName: String,
        domain: String,
        txt: [String: String]
    ) -> DiscoveredBonjourDevice {
        DiscoveredBonjourDevice(
            serviceName: serviceName,
            domain: domain,
            displayName: txt[TXTRecordKey.name] ?? serviceName,
            model: txt[TXTRecordKey.model] ?? "",
            fingerprintPrefix: txt[TXTRecordKey.fingerprint] ?? "",
            protocolVersion: txt[TXTRecordKey.version] ?? String(OpalCastProtocol.version)
        )
    }

    private static func extractTXTRecord(from metadata: NWBrowser.Result.Metadata) -> [String: String] {
        guard case let .bonjour(record) = metadata else {
            return [:]
        }
        return parseTXTRecord(record)
    }

    static func parseTXTRecord(_ record: NWTXTRecord) -> [String: String] {
        var dict: [String: String] = [:]
        for key in TXTRecordKey.allKeys {
            guard let entry = record.getEntry(for: key) else { continue }
            switch entry {
            case .string(let value):
                dict[key] = value
            case .data(let data):
                if let str = String(data: data, encoding: .utf8) {
                    dict[key] = str
                }
            case .none, .empty:
                break
            @unknown default:
                break
            }
        }
        return dict
    }
}

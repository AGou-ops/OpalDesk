import Foundation
import Network
import os

/// An Android ExtBridge host discovered via Bonjour (`_extbridge._tcp`).
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
            type: ExtCastProtocol.bonjourServiceType,
            domain: domain,
            interface: nil
        )
    }
}

/// Real-time Bonjour browser discovering `_extbridge._tcp` Android hosts on the local network.
public final class BonjourBrowser: Sendable {
    private struct MutableState {
        var browser: NWBrowser?
        var devices: [DiscoveredBonjourDevice] = []
        var onUpdate: (@Sendable ([DiscoveredBonjourDevice]) -> Void)?
        var onStateChange: (@Sendable (NWBrowser.State) -> Void)?
    }

    private let queue = DispatchQueue(label: "com.extbridge.mac.bonjour", qos: .userInitiated)
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    public init() {}

    public func currentDevices() -> [DiscoveredBonjourDevice] {
        state.withLock { $0.devices }
    }

    public func start(
        onUpdate: @escaping @Sendable ([DiscoveredBonjourDevice]) -> Void,
        onStateChange: (@Sendable (NWBrowser.State) -> Void)? = nil
    ) {
        stop()

        let params = NWParameters()
        params.includePeerToPeer = true
        let descriptor = NWBrowser.Descriptor.bonjourWithTXTRecord(
            type: ExtCastProtocol.bonjourServiceType,
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
            let device = DiscoveredBonjourDevice(
                serviceName: name,
                domain: domain,
                displayName: txt["name"] ?? name,
                model: txt["model"] ?? "",
                fingerprintPrefix: txt["fp"] ?? "",
                protocolVersion: txt["ver"] ?? "1"
            )
            list.append(device)
        }
        return list.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }

    private static func extractTXTRecord(from metadata: NWBrowser.Result.Metadata) -> [String: String] {
        guard case let .bonjour(record) = metadata else {
            return [:]
        }
        var dict: [String: String] = [:]
        for key in ["name", "model", "fp", "ver"] {
            if let entry = record.getEntry(for: key) {
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
        }
        return dict
    }
}

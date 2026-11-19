import CryptoKit
import Foundation
import os

/// Persisted trust record for a paired Android ExtBridge host.
public struct TrustedDevice: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var deviceName: String
    public var model: String
    public var certFingerprint: String
    public var authToken: String
    public var lastHost: String?
    public var lastPort: UInt16?
    public var trustedAt: Date
    public var lastConnectedAt: Date

    public init(
        id: String,
        deviceName: String,
        model: String = "",
        certFingerprint: String,
        authToken: String,
        lastHost: String? = nil,
        lastPort: UInt16? = nil,
        trustedAt: Date = Date(),
        lastConnectedAt: Date = Date()
    ) {
        self.id = id
        self.deviceName = deviceName
        self.model = model
        self.certFingerprint = FingerprintUtil.normalize(certFingerprint)
        self.authToken = authToken
        self.lastHost = lastHost
        self.lastPort = lastPort
        self.trustedAt = trustedAt
        self.lastConnectedAt = lastConnectedAt
    }

    public var shortFingerprint: String {
        FingerprintUtil.formatShort(certFingerprint)
    }
}

/// Pure utilities for SHA-256 certificate fingerprint normalization, formatting, and TOFU matching.
public enum FingerprintUtil {
    public static let fullHexLength = 64
    public static let minimumPrefixHexLength = 16

    public static func sha256Hex(of derData: Data) -> String {
        let digest = SHA256.hash(data: derData)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    public static func normalize(_ raw: String) -> String {
        raw.lowercased().filter { $0.isASCII && $0.isHexDigit }
    }

    public static func isValidFullHex(_ raw: String) -> Bool {
        normalize(raw).count == fullHexLength
    }

    /// Formats the leading 16 hex characters into a human-verifiable `XXXX-XXXX-XXXX-XXXX` code.
    public static func formatShort(_ raw: String) -> String {
        let hex = normalize(raw).uppercased()
        let prefix = String(hex.prefix(minimumPrefixHexLength))
        guard !prefix.isEmpty else { return "—" }
        var groups: [String] = []
        var index = prefix.startIndex
        while index < prefix.endIndex {
            let next = prefix.index(index, offsetBy: 4, limitedBy: prefix.endIndex) ?? prefix.endIndex
            groups.append(String(prefix[index..<next]))
            index = next
        }
        return groups.joined(separator: "-")
    }

    /// Verifies a leaf certificate's full 64-char SHA-256 hex digest against an expected pin.
    /// Supports exact 64-char pins as well as >= 16-char Bonjour TXT prefixes (64+ bits).
    public static func matches(actualFullHex: String, expected: String) -> Bool {
        let actual = normalize(actualFullHex)
        let target = normalize(expected)
        guard actual.count == fullHexLength else { return false }
        guard target.count >= minimumPrefixHexLength && target.count <= fullHexLength else { return false }
        if target.count == fullHexLength {
            return constantTimeEqual(actual, target)
        }
        let actualPrefix = String(actual.prefix(target.count))
        return constantTimeEqual(actualPrefix, target)
    }

    private static func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8)
        let b = Array(rhs.utf8)
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[i] ^ b[i]
        }
        return diff == 0
    }
}

private struct TrustStoreSnapshot: Codable, Sendable {
    var clientId: String
    var devices: [TrustedDevice]
}

/// Thread-safe persistent store for this Mac's clientId and paired Android device trust records.
public final class TrustStore: Sendable {
    private let storageURL: URL
    private let state: OSAllocatedUnfairLock<TrustStoreSnapshot>

    public static func defaultStorageURL() -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support", isDirectory: true)
        return appSupport
            .appendingPathComponent("ExtBridgeMac", isDirectory: true)
            .appendingPathComponent("trust-store.json")
    }

    public init(storageURL: URL = TrustStore.defaultStorageURL()) {
        self.storageURL = storageURL
        let loaded = TrustStore.loadFromDisk(url: storageURL)
        self.state = OSAllocatedUnfairLock(initialState: loaded)
        if !FileManager.default.fileExists(atPath: storageURL.path) {
            try? persist(loaded)
        }
    }

    public var clientId: String {
        state.withLock { $0.clientId }
    }

    public func allDevices() -> [TrustedDevice] {
        state.withLock {
            $0.devices.sorted { $0.lastConnectedAt > $1.lastConnectedAt }
        }
    }

    public func find(byFingerprint fingerprint: String) -> TrustedDevice? {
        let normalized = FingerprintUtil.normalize(fingerprint)
        guard normalized.count >= FingerprintUtil.minimumPrefixHexLength else { return nil }
        return state.withLock { snapshot in
            snapshot.devices.first { device in
                FingerprintUtil.matches(actualFullHex: device.certFingerprint, expected: normalized)
            }
        }
    }

    public func find(byHost host: String, port: UInt16) -> TrustedDevice? {
        state.withLock { snapshot in
            snapshot.devices.first { $0.lastHost == host && $0.lastPort == port }
        }
    }

    public func find(byId id: String) -> TrustedDevice? {
        state.withLock { snapshot in
            snapshot.devices.first { $0.id == id }
        }
    }

    @discardableResult
    public func upsert(_ device: TrustedDevice) throws -> TrustedDevice {
        var prepared = device
        prepared.certFingerprint = FingerprintUtil.normalize(device.certFingerprint)
        prepared.lastConnectedAt = Date()
        let incoming = prepared

        let (updatedSnapshot, resolvedDevice): (TrustStoreSnapshot, TrustedDevice) = state.withLock { snapshot in
            if let index = snapshot.devices.firstIndex(where: {
                $0.id == incoming.id
                    || (!incoming.certFingerprint.isEmpty && $0.certFingerprint == incoming.certFingerprint)
            }) {
                var existing = snapshot.devices[index]
                existing.deviceName = incoming.deviceName.isEmpty ? existing.deviceName : incoming.deviceName
                existing.model = incoming.model.isEmpty ? existing.model : incoming.model
                if !incoming.certFingerprint.isEmpty {
                    existing.certFingerprint = incoming.certFingerprint
                }
                if !incoming.authToken.isEmpty {
                    existing.authToken = incoming.authToken
                }
                if let host = incoming.lastHost {
                    existing.lastHost = host
                }
                if let port = incoming.lastPort {
                    existing.lastPort = port
                }
                existing.lastConnectedAt = incoming.lastConnectedAt
                snapshot.devices[index] = existing
                return (snapshot, existing)
            } else {
                snapshot.devices.append(incoming)
                return (snapshot, incoming)
            }
        }

        try persist(updatedSnapshot)
        return resolvedDevice
    }

    public func revoke(id: String) throws {
        let updatedSnapshot: TrustStoreSnapshot = state.withLock { snapshot in
            snapshot.devices.removeAll { $0.id == id }
            return snapshot
        }
        try persist(updatedSnapshot)
    }

    public func revokeAll() throws {
        let updatedSnapshot: TrustStoreSnapshot = state.withLock { snapshot in
            snapshot.devices.removeAll()
            return snapshot
        }
        try persist(updatedSnapshot)
    }

    private static func loadFromDisk(url: URL) -> TrustStoreSnapshot {
        guard let data = try? Data(contentsOf: url) else {
            return TrustStoreSnapshot(clientId: UUID().uuidString.lowercased(), devices: [])
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        if var decoded = try? decoder.decode(TrustStoreSnapshot.self, from: data) {
            if decoded.clientId.isEmpty {
                decoded.clientId = UUID().uuidString.lowercased()
            }
            return decoded
        }
        return TrustStoreSnapshot(clientId: UUID().uuidString.lowercased(), devices: [])
    }

    private func persist(_ snapshot: TrustStoreSnapshot) throws {
        let directory = storageURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(snapshot)
        try data.write(to: storageURL, options: [.atomic])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: storageURL.path)
    }
}

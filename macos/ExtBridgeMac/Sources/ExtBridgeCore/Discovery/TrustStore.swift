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
/// Persists to disk atomically BEFORE committing in-memory updates so RAM and disk never diverge.
public final class TrustStore: Sendable {
    private let storageURL: URL
    private let state: OSAllocatedUnfairLock<TrustStoreSnapshot>
    private let writeLock = NSLock()

    public static func defaultStorageURL() -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support", isDirectory: true)
        return appSupport
            .appendingPathComponent("ExtBridgeMac", isDirectory: true)
            .appendingPathComponent("trust-store.json")
    }

    public init(storageURL: URL = TrustStore.defaultStorageURL()) {
        self.storageURL = storageURL
        let (loaded, needsPersist) = TrustStore.loadFromDisk(url: storageURL)
        self.state = OSAllocatedUnfairLock(initialState: loaded)
        if needsPersist {
            do {
                try persist(loaded)
            } catch {
                ExtBridgeLog.trust.warning(
                    "Failed to initialize trust store at \(storageURL.path, privacy: .public): \(error.localizedDescription, privacy: .public)"
                )
            }
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

    @discardableResult
    public func upsert(_ device: TrustedDevice) throws -> TrustedDevice {
        var incoming = device
        incoming.certFingerprint = FingerprintUtil.normalize(device.certFingerprint)
        incoming.lastConnectedAt = Date()

        return try commitMutation { snapshot in
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
                return existing
            } else {
                snapshot.devices.append(incoming)
                return incoming
            }
        }
    }

    public func revoke(id: String) throws {
        try commitMutation { snapshot in
            snapshot.devices.removeAll { $0.id == id }
        }
    }

    public func revokeAll() throws {
        try commitMutation { snapshot in
            snapshot.devices.removeAll()
        }
    }

    // MARK: - Persistence

    /// Computes a mutated snapshot, persists it atomically to disk FIRST, and only commits
    /// to in-memory state if persistence succeeds — preventing RAM/disk divergence.
    private func commitMutation<R>(_ mutate: (inout TrustStoreSnapshot) -> R) throws -> R {
        try writeLock.withLock {
            var candidate = state.withLock { $0 }
            let result = mutate(&candidate)
            try persist(candidate)
            let committed = candidate
            state.withLock { $0 = committed }
            return result
        }
    }

    private static func loadFromDisk(url: URL) -> (snapshot: TrustStoreSnapshot, needsPersist: Bool) {
        let fm = FileManager.default
        guard fm.fileExists(atPath: url.path) else {
            return (TrustStoreSnapshot(clientId: UUID().uuidString.lowercased(), devices: []), true)
        }

        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            ExtBridgeLog.trust.error(
                "Failed to read trust store at \(url.path, privacy: .public): \(error.localizedDescription, privacy: .public)"
            )
            return (TrustStoreSnapshot(clientId: UUID().uuidString.lowercased(), devices: []), false)
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        do {
            var decoded = try decoder.decode(TrustStoreSnapshot.self, from: data)
            if decoded.clientId.isEmpty {
                decoded.clientId = UUID().uuidString.lowercased()
                return (decoded, true)
            }
            return (decoded, false)
        } catch {
            ExtBridgeLog.trust.error(
                "Corrupt trust store JSON at \(url.path, privacy: .public): \(error.localizedDescription, privacy: .public); backing up before reset"
            )
            backupCorruptFile(at: url)
            return (TrustStoreSnapshot(clientId: UUID().uuidString.lowercased(), devices: []), true)
        }
    }

    private static func backupCorruptFile(at url: URL) {
        let fm = FileManager.default
        let backupURL = url.appendingPathExtension("corrupt")
        do {
            if fm.fileExists(atPath: backupURL.path) {
                try fm.removeItem(at: backupURL)
            }
            try fm.moveItem(at: url, to: backupURL)
        } catch {
            ExtBridgeLog.trust.error(
                "Failed to back up corrupt trust store to \(backupURL.path, privacy: .public): \(error.localizedDescription, privacy: .public)"
            )
        }
    }

    private func persist(_ snapshot: TrustStoreSnapshot) throws {
        let directory = storageURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(snapshot)
        try data.write(to: storageURL, options: [.atomic])
        let path = storageURL.path
        do {
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path)
        } catch {
            ExtBridgeLog.trust.warning(
                "Failed to set 0600 permissions on trust store \(path, privacy: .public): \(error.localizedDescription, privacy: .public)"
            )
        }
    }
}

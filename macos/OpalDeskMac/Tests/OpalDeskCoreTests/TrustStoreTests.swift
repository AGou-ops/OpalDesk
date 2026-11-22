import Foundation
import Testing
@testable import OpalDeskCore

@Suite("TrustStore Persistence & TOFU Fingerprint Matching")
struct TrustStoreTests {

    private func makeTempStoreURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-trust-test-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("trust-store.json")
    }

    @Test("Fingerprint normalization, formatting, full match, prefix match, and short-prefix rejection")
    func fingerprintVerificationRules() {
        let fullHex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        let formatted = FingerprintUtil.formatShort(fullHex)
        #expect(formatted == "A1B2-C3D4-E5F6-0718")

        // Full 64-char match (case & separator insensitive)
        #expect(FingerprintUtil.matches(actualFullHex: fullHex, expected: fullHex.uppercased()))
        // 16-char Bonjour TXT prefix match
        #expect(FingerprintUtil.matches(actualFullHex: fullHex, expected: "A1B2-C3D4-E5F6-0718"))
        // Mismatch rejected
        let altered = "b" + fullHex.dropFirst()
        #expect(!FingerprintUtil.matches(actualFullHex: altered, expected: fullHex))
        #expect(!FingerprintUtil.matches(actualFullHex: altered, expected: "a1b2c3d4e5f60718"))
        // Prefix shorter than 16 hex chars (64 bits) is rejected for security
        #expect(!FingerprintUtil.matches(actualFullHex: fullHex, expected: "a1b2c3d4"))
    }

    @Test("TrustStore persists clientId, devices, lookup, and revocation across reloads")
    func persistenceAndRevocation() throws {
        let url = makeTempStoreURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }

        let store1 = TrustStore(storageURL: url)
        let originalClientId = store1.clientId
        #expect(!originalClientId.isEmpty)

        let fp = String(repeating: "ab", count: 32)
        let device = TrustedDevice(
            id: "dev-xiaomi-15",
            deviceName: "Xiaomi 15",
            model: "24129PN74C",
            certFingerprint: fp.uppercased(),
            authToken: "secret-token-xyz",
            lastHost: "192.168.31.105",
            lastPort: 27849
        )
        try store1.upsert(device)
        #expect(store1.allDevices().count == 1)

        // Reload fresh instance from same JSON file
        let store2 = TrustStore(storageURL: url)
        #expect(store2.clientId == originalClientId)
        #expect(store2.allDevices().count == 1)

        // Lookup by 16-char prefix, full fingerprint, and host/port
        #expect(store2.find(byFingerprint: String(fp.prefix(16)))?.authToken == "secret-token-xyz")
        #expect(store2.find(byFingerprint: fp)?.deviceName == "Xiaomi 15")
        #expect(store2.find(byHost: "192.168.31.105", port: 27849)?.id == "dev-xiaomi-15")

        // Revoke single device and verify persistence
        try store2.revoke(id: "dev-xiaomi-15")
        #expect(store2.allDevices().isEmpty)

        let store3 = TrustStore(storageURL: url)
        #expect(store3.allDevices().isEmpty)
        #expect(store3.clientId == originalClientId)

        // Add two devices and revokeAll
        try store3.upsert(device)
        try store3.upsert(TrustedDevice(
            id: "dev-2",
            deviceName: "Pixel",
            certFingerprint: String(repeating: "cd", count: 32),
            authToken: "tok2"
        ))
        #expect(store3.allDevices().count == 2)
        try store3.revokeAll()
        #expect(store3.allDevices().isEmpty)
    }

    @Test("TrustStore backs up corrupt JSON to .corrupt before resetting")
    func corruptJSONCreatesBackupAndResets() throws {
        let url = makeTempStoreURL()
        let dir = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let garbage = Data("{ not valid json !!!".utf8)
        try garbage.write(to: url)

        let store = TrustStore(storageURL: url)
        #expect(!store.clientId.isEmpty)
        #expect(store.allDevices().isEmpty)

        let backupURL = url.appendingPathExtension("corrupt")
        #expect(FileManager.default.fileExists(atPath: backupURL.path))
        #expect(try Data(contentsOf: backupURL) == garbage)
    }

    @Test("TrustStore does not mutate in-memory state when disk persistence fails")
    func persistBeforeCommitPreventsRAMDiskDivergence() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaldesk-trust-fail-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        // Place a regular file where a parent directory must be so persist() fails cleanly on write.
        let blockingFile = dir.appendingPathComponent("not-a-directory")
        try Data("block".utf8).write(to: blockingFile)
        let impossibleStoreURL = blockingFile.appendingPathComponent("trust-store.json")

        let store = TrustStore(storageURL: impossibleStoreURL)
        let device = TrustedDevice(
            id: "dev-unwritable",
            deviceName: "Pixel",
            certFingerprint: String(repeating: "ee", count: 32),
            authToken: "tok"
        )

        #expect(throws: Error.self) {
            try store.upsert(device)
        }
        #expect(store.allDevices().isEmpty)
    }

    @Test("TrustStore corrupt recovery persists clean store, allows new writes, and replaces prior .corrupt backup")
    func corruptJSONRecoveryAndRePersistence() throws {
        let url = makeTempStoreURL()
        let dir = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let firstGarbage = Data("corrupt-v1".utf8)
        try firstGarbage.write(to: url)

        let recovered = TrustStore(storageURL: url)
        let recoveredClientId = recovered.clientId
        #expect(!recoveredClientId.isEmpty)
        #expect(recovered.allDevices().isEmpty)

        // Recovered store persisted valid JSON and accepts new devices.
        let fp = String(repeating: "12", count: 32)
        try recovered.upsert(TrustedDevice(
            id: "dev-recovered",
            deviceName: "Recovered Phone",
            certFingerprint: fp,
            authToken: "tok-1"
        ))

        let reloaded = TrustStore(storageURL: url)
        #expect(reloaded.clientId == recoveredClientId)
        #expect(reloaded.find(byFingerprint: fp)?.authToken == "tok-1")

        // Verify persisted trust store enforces owner-only 0600 POSIX permissions.
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        let perms = attrs[.posixPermissions] as? Int
        #expect(perms == 0o600)

        // A second corruption cleanly replaces the previous .corrupt backup.
        let secondGarbage = Data("corrupt-v2-truncated{".utf8)
        try secondGarbage.write(to: url)
        let recoveredAgain = TrustStore(storageURL: url)
        #expect(recoveredAgain.allDevices().isEmpty)
        let backupURL = url.appendingPathExtension("corrupt")
        #expect(try Data(contentsOf: backupURL) == secondGarbage)
    }

    @Test("TrustStore upsert merges by fingerprint and atomic revoke failure keeps RAM consistent")
    func upsertMergeAndRevokeRollbackConsistency() throws {
        let url = makeTempStoreURL()
        let dir = url.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: dir) }

        let store = TrustStore(storageURL: url)
        let fp = String(repeating: "34", count: 32)
        try store.upsert(TrustedDevice(
            id: "dev-original",
            deviceName: "Xiaomi 15",
            model: "24129PN74C",
            certFingerprint: fp,
            authToken: "initial-token",
            lastHost: "192.168.1.10",
            lastPort: 19878
        ))

        // Upserting with same fingerprint and empty optional metadata merges without wiping existing fields.
        let merged = try store.upsert(TrustedDevice(
            id: "dev-different-id",
            deviceName: "",
            model: "",
            certFingerprint: fp.uppercased(),
            authToken: "",
            lastHost: "192.168.1.25",
            lastPort: 19877
        ))
        #expect(store.allDevices().count == 1)
        #expect(merged.id == "dev-original")
        #expect(merged.deviceName == "Xiaomi 15")
        #expect(merged.model == "24129PN74C")
        #expect(merged.authToken == "initial-token")
        #expect(merged.lastHost == "192.168.1.25")
        #expect(merged.lastPort == 19877)

        // Replace storage file with a non-empty directory so atomic write fails deterministically.
        try FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        try Data("lock".utf8).write(to: url.appendingPathComponent("occupied"))

        #expect(throws: Error.self) {
            try store.revoke(id: "dev-original")
        }
        #expect(store.allDevices().count == 1)

        #expect(throws: Error.self) {
            try store.revokeAll()
        }
        #expect(store.allDevices().count == 1)
    }

    @Test("FingerprintUtil SHA-256 digest, formatting, and boundary guards match spec")
    func fingerprintUtilEdgeCases() {
        // Standard NIST SHA-256 vector for "abc"
        let abcHash = FingerprintUtil.sha256Hex(of: Data("abc".utf8))
        #expect(abcHash == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        #expect(FingerprintUtil.isValidFullHex(abcHash))
        #expect(FingerprintUtil.isValidFullHex(abcHash.uppercased() + "::--"))
        #expect(!FingerprintUtil.isValidFullHex(String(abcHash.dropLast())))
        #expect(!FingerprintUtil.isValidFullHex(abcHash + "0"))

        #expect(FingerprintUtil.formatShort("") == "—")
        #expect(FingerprintUtil.formatShort(":::---") == "—")
        #expect(FingerprintUtil.formatShort("ab12cd") == "AB12-CD")

        // Reject malformed actualFullHex or overlong expected strings
        #expect(!FingerprintUtil.matches(actualFullHex: "short", expected: "short"))
        #expect(!FingerprintUtil.matches(actualFullHex: abcHash, expected: abcHash + "00"))
    }
}

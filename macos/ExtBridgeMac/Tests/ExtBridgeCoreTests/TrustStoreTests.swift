import Foundation
import Testing
@testable import ExtBridgeCore

@Suite("TrustStore Persistence & TOFU Fingerprint Matching")
struct TrustStoreTests {

    private func makeTempStoreURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("extbridge-trust-test-\(UUID().uuidString)", isDirectory: true)
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
}

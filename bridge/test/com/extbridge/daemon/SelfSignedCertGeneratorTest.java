package com.extbridge.daemon;

import com.extbridge.daemon.security.SelfSignedCertGenerator;
import com.extbridge.daemon.stream.ExtCastProtocol;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SelfSignedCertGeneratorTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testGenerateValidX509CertAndPersistPkcs12() throws Exception {
        File p12File = new File(tmpDir.newFolder(), "extbridge_tls.p12");
        SelfSignedCertGenerator.ServerIdentity id = SelfSignedCertGenerator.loadOrCreate(p12File, "ExtBridge-Test");

        assertNotNull(id);
        assertNotNull(id.certificate);
        assertNotNull(id.privateKey);
        assertNotNull(id.keyStore);
        assertTrue("Fingerprint must be 64-char hex", id.certFingerprintSha256.matches("[0-9a-f]{64}"));

        X509Certificate cert = id.certificate;
        assertEquals(3, cert.getVersion());
        assertEquals("SHA256withECDSA", cert.getSigAlgName());
        assertTrue(cert.getSubjectX500Principal().getName().contains("CN=ExtBridge-Test"));
        cert.checkValidity();
        cert.verify(cert.getPublicKey());

        // Must re-parse cleanly through standard X.509 CertificateFactory
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate parsed = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(cert.getEncoded()));
        parsed.verify(parsed.getPublicKey());
        assertEquals(id.certFingerprintSha256, SelfSignedCertGenerator.sha256Hex(parsed.getEncoded()));

        // Verify EKU contains serverAuth (1.3.6.1.5.5.7.3.1)
        List<String> ekus = parsed.getExtendedKeyUsage();
        assertNotNull(ekus);
        assertTrue(ekus.contains("1.3.6.1.5.5.7.3.1"));

        // Verify reload from PKCS#12 file keeps identical certificate & fingerprint
        assertTrue(p12File.isFile() && p12File.length() > 0);
        SelfSignedCertGenerator.ServerIdentity reloaded = SelfSignedCertGenerator.loadOrCreate(p12File, "ExtBridge-Test");
        assertEquals(id.certFingerprintSha256, reloaded.certFingerprintSha256);
    }

    @Test
    public void testRealSslContextTlsHandshakeAndTofuFingerprint() throws Exception {
        SelfSignedCertGenerator.ServerIdentity id = SelfSignedCertGenerator.generate("ExtBridge-TLS");
        SSLContext serverCtx = SelfSignedCertGenerator.createServerSSLContext(id);

        final AtomicReference<String> seenFingerprint = new AtomicReference<>();
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                try {
                    if (chain != null && chain.length > 0) {
                        seenFingerprint.set(SelfSignedCertGenerator.sha256Hex(chain[0].getEncoded()));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());

        try (SSLServerSocket server = (SSLServerSocket) serverCtx.getServerSocketFactory()
                .createServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            AtomicReference<Throwable> serverErr = new AtomicReference<>();

            Thread serverThread = new Thread(() -> {
                try (SSLSocket s = (SSLSocket) server.accept()) {
                    s.startHandshake();
                    ExtCastProtocol.Frame ping = ExtCastProtocol.readFrame(s.getInputStream());
                    assertNotNull(ping);
                    assertEquals(ExtCastProtocol.TYPE_PING, ping.type);
                    long pingId = ExtCastProtocol.decodePingPong(ping.payload);
                    byte[] pong = ExtCastProtocol.encodePingPong(pingId);
                    ExtCastProtocol.writeFrame(s.getOutputStream(), ExtCastProtocol.TYPE_PONG, 0, 1L, pong, 0, pong.length);
                    s.getOutputStream().flush();
                } catch (Throwable t) {
                    serverErr.set(t);
                }
            });
            serverThread.start();

            try (SSLSocket client = (SSLSocket) clientCtx.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), port)) {
                client.setSoTimeout(5000);
                client.startHandshake();
                byte[] pingPayload = ExtCastProtocol.encodePingPong(0xCAFEBABEL);
                ExtCastProtocol.writeFrame(client.getOutputStream(), ExtCastProtocol.TYPE_PING, 0, 1L,
                        pingPayload, 0, pingPayload.length);
                client.getOutputStream().flush();

                ExtCastProtocol.Frame pong = ExtCastProtocol.readFrame(client.getInputStream());
                assertNotNull(pong);
                assertEquals(ExtCastProtocol.TYPE_PONG, pong.type);
                assertEquals(0xCAFEBABEL, ExtCastProtocol.decodePingPong(pong.payload));
            }
            serverThread.join(5000);
            if (serverErr.get() != null) {
                throw new AssertionError("Server TLS error", serverErr.get());
            }
        }

        assertEquals(id.certFingerprintSha256, seenFingerprint.get());
    }
}

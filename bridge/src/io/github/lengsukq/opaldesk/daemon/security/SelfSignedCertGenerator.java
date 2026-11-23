package io.github.lengsukq.opaldesk.daemon.security;

import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;
import io.github.lengsukq.opaldesk.daemon.util.CryptoHex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Pure-Java zero-dependency ECDSA P-256 self-signed X.509v3 certificate generator
 * using hand-written ASN.1 DER encoding + java.security.Signature("SHA256withECDSA").
 * Works identically on Android ART (API 30+) and standard JVM without sun.security or BouncyCastle.
 */
public final class SelfSignedCertGenerator {
    public static final String DEFAULT_KEYSTORE_PATH = "/data/local/tmp/opaldesk_tls.p12";
    private static final String KEY_ALIAS = "opaldesk-tls";
    private static final char[] DEFAULT_PASSWORD = "opaldesk-tls-p12".toCharArray();
    private static final long ONE_DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long MIN_REMAINING_VALIDITY_MS = 30L * ONE_DAY_MS;
    private static final int VALIDITY_YEARS = 10;

    // Standard OIDs
    private static final int[] OID_ECDSA_WITH_SHA256 = {1, 2, 840, 10045, 4, 3, 2};
    private static final int[] OID_COMMON_NAME = {2, 5, 4, 3};
    private static final int[] OID_ORGANIZATION = {2, 5, 4, 10};
    private static final int[] OID_KEY_USAGE = {2, 5, 29, 15};
    private static final int[] OID_SUBJECT_ALT_NAME = {2, 5, 29, 17};
    private static final int[] OID_BASIC_CONSTRAINTS = {2, 5, 29, 19};
    private static final int[] OID_EXT_KEY_USAGE = {2, 5, 29, 37};
    private static final int[] OID_EKU_SERVER_AUTH = {1, 3, 6, 1, 5, 5, 7, 3, 1};

    private SelfSignedCertGenerator() {}

    public static final class ServerIdentity {
        public final KeyStore keyStore;
        public final char[] password;
        public final X509Certificate certificate;
        public final PrivateKey privateKey;
        public final String certFingerprintSha256;

        public ServerIdentity(
                KeyStore keyStore,
                char[] password,
                X509Certificate certificate,
                PrivateKey privateKey,
                String certFingerprintSha256) {
            this.keyStore = keyStore;
            this.password = password.clone();
            this.certificate = certificate;
            this.privateKey = privateKey;
            this.certFingerprintSha256 = certFingerprintSha256;
        }
    }

    /**
     * Loads an existing valid PKCS#12 server identity from {@code storeFile}, or generates
     * a fresh ECDSA P-256 self-signed identity and saves it atomically with 0600 permissions.
     */
    public static ServerIdentity loadOrCreate(File storeFile, String commonName) throws Exception {
        return loadOrCreate(storeFile, commonName, DEFAULT_PASSWORD, null);
    }

    public static ServerIdentity loadOrCreate(File storeFile, String commonName, List<String> extraSanIps) throws Exception {
        return loadOrCreate(storeFile, commonName, DEFAULT_PASSWORD, extraSanIps);
    }

    public static ServerIdentity loadOrCreate(File storeFile, String commonName, char[] password) throws Exception {
        return loadOrCreate(storeFile, commonName, password, null);
    }

    public static ServerIdentity loadOrCreate(
            File storeFile, String commonName, char[] password, List<String> extraSanIps) throws Exception {
        char[] pass = (password != null && password.length > 0) ? password : DEFAULT_PASSWORD;
        if (storeFile != null && storeFile.isFile() && storeFile.length() > 0) {
            try {
                ServerIdentity loaded = loadFromFile(storeFile, pass);
                if (loaded != null && isCertificateUsable(loaded.certificate)) {
                    return loaded;
                }
                System.err.println("OPALDESK_TLS_STORE_ROTATE path=" + storeFile.getAbsolutePath()
                        + " reason=certificate expired or missing entry");
            } catch (Throwable t) {
                System.err.println("OPALDESK_TLS_STORE_LOAD_ERR path=" + storeFile.getAbsolutePath()
                        + " regenerating: " + t.getMessage());
            }
        }
        ServerIdentity fresh = generate(commonName, pass, extraSanIps);
        if (storeFile != null) {
            saveAtomic(fresh.keyStore, storeFile, pass);
        }
        return fresh;
    }

    /**
     * Generates a fresh ECDSA P-256 keypair, ASN.1 DER X.509v3 self-signed certificate,
     * and wraps it in an in-memory PKCS#12 KeyStore.
     */
    public static ServerIdentity generate(String commonName) throws Exception {
        return generate(commonName, DEFAULT_PASSWORD, null);
    }

    public static ServerIdentity generate(String commonName, char[] password, List<String> extraSanIps) throws Exception {
        String cn = (commonName == null || commonName.trim().isEmpty()) ? "OpalDesk" : commonName.trim();
        char[] pass = (password != null && password.length > 0) ? password : DEFAULT_PASSWORD;

        SecureRandom rng = new SecureRandom();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), rng);
        KeyPair keyPair = kpg.generateKeyPair();

        Date now = new Date();
        Date notBefore = new Date(now.getTime() - ONE_DAY_MS);
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        cal.setTime(now);
        cal.add(Calendar.YEAR, VALIDITY_YEARS);
        Date notAfter = cal.getTime();

        byte[] certDer = buildSelfSignedX509Der(keyPair, cn, notBefore, notAfter, extraSanIps, rng);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
        cert.verify(keyPair.getPublic());

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(KEY_ALIAS, keyPair.getPrivate(), pass, new Certificate[]{cert});

        String fp = sha256Hex(cert.getEncoded());
        return new ServerIdentity(ks, pass, cert, keyPair.getPrivate(), fp);
    }

    public static SSLContext createServerSSLContext(ServerIdentity identity) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(identity.keyStore, identity.password);
        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLSv1.3");
        } catch (Throwable ignored) {
            ctx = SSLContext.getInstance("TLS");
        }
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    public static String sha256Hex(byte[] data) {
        return CryptoHex.sha256Hex(data);
    }

    private static ServerIdentity loadFromFile(File storeFile, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(storeFile)) {
            ks.load(fis, password);
        }
        Certificate certObj = ks.getCertificate(KEY_ALIAS);
        java.security.Key keyObj = ks.getKey(KEY_ALIAS, password);
        if (!(certObj instanceof X509Certificate) || !(keyObj instanceof PrivateKey)) {
            return null;
        }
        X509Certificate cert = (X509Certificate) certObj;
        String fp = sha256Hex(cert.getEncoded());
        return new ServerIdentity(ks, password, cert, (PrivateKey) keyObj, fp);
    }

    private static boolean isCertificateUsable(X509Certificate cert) {
        if (cert == null) return false;
        long now = System.currentTimeMillis();
        Date notBefore = cert.getNotBefore();
        Date notAfter = cert.getNotAfter();
        return notBefore != null && notAfter != null
                && now >= notBefore.getTime()
                && (notAfter.getTime() - now) >= MIN_REMAINING_VALIDITY_MS;
    }

    private static void saveAtomic(KeyStore ks, File target, char[] password) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            ks.store(baos, password);
            AtomicFiles.writeAtomic(target, baos.toByteArray(), AtomicFiles.MODE_OWNER_RW);
        } catch (Exception e) {
            System.err.println("OPALDESK_TLS_STORE_SAVE_ERR path=" + target.getAbsolutePath()
                    + ": " + e.getMessage());
        }
    }

    // ---- Hand-written minimal ASN.1 DER X.509v3 encoder ----

    static byte[] buildSelfSignedX509Der(
            KeyPair keyPair,
            String commonName,
            Date notBefore,
            Date notAfter,
            List<String> extraSanIps,
            SecureRandom rng) throws Exception {
        // Positive 127-bit random serial number (MSB cleared, guaranteed >= 1)
        byte[] serialBytes = new byte[16];
        rng.nextBytes(serialBytes);
        serialBytes[0] = (byte) ((serialBytes[0] & 0x7F) | 0x01);
        BigInteger serial = new BigInteger(1, serialBytes);

        // RFC 5758 §3.2: AlgorithmIdentifier for ecdsa-with-SHA256 omits parameters (no NULL).
        byte[] sigAlgId = derSequence(derOid(OID_ECDSA_WITH_SHA256));
        byte[] name = derX500Name(commonName, "OpalDesk");
        byte[] validity = derSequence(derTime(notBefore), derTime(notAfter));
        byte[] spki = keyPair.getPublic().getEncoded();
        byte[] extensions = buildExtensions(extraSanIps);

        byte[] tbsCert = derSequence(
                derExplicitTag(0, derInteger(BigInteger.valueOf(2))), // v3
                derInteger(serial),
                sigAlgId,
                name,
                validity,
                name,
                spki,
                derExplicitTag(3, extensions)
        );

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate(), rng);
        signer.update(tbsCert);
        byte[] signatureBytes = signer.sign();

        return derSequence(
                tbsCert,
                sigAlgId,
                derBitString(signatureBytes, 0)
        );
    }

    private static byte[] buildExtensions(List<String> extraSanIps) throws Exception {
        // BasicConstraints: cA = FALSE (empty SEQUENCE per DER default rule)
        byte[] extBasicConstraints = derExtension(OID_BASIC_CONSTRAINTS, true, derSequence());

        // KeyUsage: digitalSignature (bit 0 -> 0x80 with 7 unused trailing bits)
        byte[] extKeyUsage = derExtension(OID_KEY_USAGE, true, derBitString(new byte[]{(byte) 0x80}, 7));

        // ExtendedKeyUsage: id-kp-serverAuth
        byte[] extEku = derExtension(OID_EXT_KEY_USAGE, false, derSequence(derOid(OID_EKU_SERVER_AUTH)));

        // SubjectAlternativeName: DNS:opaldesk.local, IP:127.0.0.1 (+ optional SAN IPs)
        List<byte[]> sanEntries = new ArrayList<>();
        sanEntries.add(derPrimitiveContextTag(2, "opaldesk.local".getBytes(StandardCharsets.US_ASCII)));
        sanEntries.add(derPrimitiveContextTag(7, new byte[]{127, 0, 0, 1}));
        if (extraSanIps != null) {
            for (String ip : extraSanIps) {
                if (ip == null || ip.trim().isEmpty()) continue;
                try {
                    byte[] rawIp = InetAddress.getByName(ip.trim()).getAddress();
                    sanEntries.add(derPrimitiveContextTag(7, rawIp));
                } catch (IOException | SecurityException e) {
                    System.err.println("OPALDESK_TLS_SAN_SKIP ip=" + ip + ": " + e.getMessage());
                }
            }
        }
        byte[] extSan = derExtension(OID_SUBJECT_ALT_NAME, false, derSequenceList(sanEntries));

        return derSequence(extBasicConstraints, extKeyUsage, extEku, extSan);
    }

    private static byte[] derExtension(int[] oid, boolean critical, byte[] extnValueDer) throws Exception {
        if (critical) {
            return derSequence(
                    derOid(oid),
                    derBoolean(true),
                    derOctetString(extnValueDer)
            );
        }
        return derSequence(
                derOid(oid),
                derOctetString(extnValueDer)
        );
    }

    private static byte[] derX500Name(String cn, String org) throws Exception {
        byte[] rdnCn = derSet(derSequence(derOid(OID_COMMON_NAME), derUtf8String(cn)));
        byte[] rdnOrg = derSet(derSequence(derOid(OID_ORGANIZATION), derUtf8String(org)));
        return derSequence(rdnCn, rdnOrg);
    }

    private static byte[] derTime(Date date) throws Exception {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        cal.setTime(date);
        int year = cal.get(Calendar.YEAR);
        if (year >= 1950 && year <= 2049) {
            SimpleDateFormat fmt = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return derTag(0x17, fmt.format(date).getBytes(StandardCharsets.US_ASCII));
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return derTag(0x18, fmt.format(date).getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static byte[] derBoolean(boolean val) {
        return derTag(0x01, new byte[]{val ? (byte) 0xFF : 0x00});
    }

    private static byte[] derInteger(BigInteger value) {
        return derTag(0x02, value.toByteArray());
    }

    private static byte[] derBitString(byte[] bytes, int unusedBits) {
        byte[] out = new byte[bytes.length + 1];
        out[0] = (byte) (unusedBits & 0x07);
        System.arraycopy(bytes, 0, out, 1, bytes.length);
        return derTag(0x03, out);
    }

    private static byte[] derOctetString(byte[] bytes) {
        return derTag(0x04, bytes);
    }

    private static byte[] derUtf8String(String str) {
        return derTag(0x0C, str.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] derOid(int[] arcs) {
        if (arcs == null || arcs.length < 2) {
            throw new IllegalArgumentException("OID must have at least 2 arcs");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            writeBase128(baos, arcs[i]);
        }
        return derTag(0x06, baos.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream out, int value) {
        if (value == 0) {
            out.write(0);
            return;
        }
        byte[] tmp = new byte[5];
        int pos = 5;
        int v = value;
        while (v > 0) {
            tmp[--pos] = (byte) (v & 0x7F);
            v >>>= 7;
        }
        for (int i = pos; i < 4; i++) {
            out.write((tmp[i] & 0x7F) | 0x80);
        }
        out.write(tmp[4] & 0x7F);
    }

    private static byte[] derSequence(byte[]... elements) {
        return derTag(0x30, concat(elements));
    }

    private static byte[] derSequenceList(List<byte[]> elements) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (byte[] el : elements) {
            baos.write(el, 0, el.length);
        }
        return derTag(0x30, baos.toByteArray());
    }

    private static byte[] derSet(byte[]... elements) {
        return derTag(0x31, concat(elements));
    }

    private static byte[] derExplicitTag(int tagNum, byte[] content) {
        return derTag(0xA0 | (tagNum & 0x1F), content);
    }

    private static byte[] derPrimitiveContextTag(int tagNum, byte[] content) {
        return derTag(0x80 | (tagNum & 0x1F), content);
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        byte[] res = new byte[total];
        int off = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, res, off, a.length);
            off += a.length;
        }
        return res;
    }

    private static byte[] derTag(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int len = content.length;
        if (len < 128) {
            out.write(len);
        } else {
            byte[] lenBytes = new byte[4];
            int count = 0;
            int tmp = len;
            while (tmp > 0) {
                lenBytes[3 - count] = (byte) (tmp & 0xFF);
                tmp >>>= 8;
                count++;
            }
            out.write(0x80 | count);
            out.write(lenBytes, 4 - count, count);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }
}

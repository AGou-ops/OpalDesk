package io.github.lengsukq.opaldesk.web

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Pure-Kotlin zero-dependency ECDSA P-256 self-signed X.509v3 certificate generator for
 * the on-demand helper-side [WebActivationService].
 *
 * Uses a 365-day validity window (within modern browser <=398-day caps) and embeds the device's
 * current LAN IPv4 address in the certificate SAN extension.
 */
object LocalTlsCert {
    private const val KEY_ALIAS = "opaldesk-web-act"
    private val STORE_PASSWORD = "opaldesk-web-act-p12".toCharArray()
    private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
    private const val MIN_REMAINING_MS = 14L * ONE_DAY_MS
    private const val VALIDITY_DAYS = 365

    private val OID_ECDSA_WITH_SHA256 = intArrayOf(1, 2, 840, 10045, 4, 3, 2)
    private val OID_COMMON_NAME = intArrayOf(2, 5, 4, 3)
    private val OID_ORGANIZATION = intArrayOf(2, 5, 4, 10)
    private val OID_KEY_USAGE = intArrayOf(2, 5, 29, 15)
    private val OID_SUBJECT_ALT_NAME = intArrayOf(2, 5, 29, 17)
    private val OID_BASIC_CONSTRAINTS = intArrayOf(2, 5, 29, 19)
    private val OID_EXT_KEY_USAGE = intArrayOf(2, 5, 29, 37)
    private val OID_EKU_SERVER_AUTH = intArrayOf(1, 3, 6, 1, 5, 5, 7, 3, 1)

    data class Identity(
        val keyStore: KeyStore,
        val certificate: X509Certificate,
        val privateKey: PrivateKey,
        val sha256Fingerprint: String,
    ) {
        val shortFingerprint: String
            get() = sha256Fingerprint.take(12)
    }

    fun loadOrCreate(storeFile: File?, commonName: String, lanIp: String?): Identity {
        if (storeFile != null && storeFile.isFile && storeFile.length() > 0L) {
            runCatching { load(storeFile) }.getOrNull()?.let { existing ->
                if (isUsable(existing.certificate, lanIp)) {
                    return existing
                }
            }
        }
        val fresh = generate(commonName, lanIp)
        if (storeFile != null) {
            savePrivate(fresh.keyStore, storeFile)
        }
        return fresh
    }

    fun generate(commonName: String, lanIp: String?): Identity {
        val cn = commonName.trim().ifEmpty { "OpalDesk" }
        val rng = SecureRandom()
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rng)
        val kp = kpg.generateKeyPair()

        val now = Date()
        val notBefore = Date(now.time - ONE_DAY_MS)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            time = now
            add(Calendar.DAY_OF_YEAR, VALIDITY_DAYS)
        }
        val der = buildX509Der(kp, cn, notBefore, cal.time, lanIp, rng)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(kp.public)

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(KEY_ALIAS, kp.private, STORE_PASSWORD, arrayOf<Certificate>(cert))
        return Identity(ks, cert, kp.private, sha256Hex(cert.encoded))
    }

    fun createServerSslContext(identity: Identity): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(identity.keyStore, STORE_PASSWORD)
        val ctx = runCatching { SSLContext.getInstance("TLSv1.3") }
            .getOrElse { SSLContext.getInstance("TLS") }
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx
    }

    private fun load(storeFile: File): Identity? {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(storeFile).use { ks.load(it, STORE_PASSWORD) }
        val cert = ks.getCertificate(KEY_ALIAS) as? X509Certificate ?: return null
        val key = ks.getKey(KEY_ALIAS, STORE_PASSWORD) as? PrivateKey ?: return null
        return Identity(ks, cert, key, sha256Hex(cert.encoded))
    }

    private fun isUsable(cert: X509Certificate, requiredLanIp: String?): Boolean {
        val now = System.currentTimeMillis()
        if (now < cert.notBefore.time || (cert.notAfter.time - now) < MIN_REMAINING_MS) {
            return false
        }
        val ip = requiredLanIp?.trim().orEmpty()
        if (ip.isEmpty() || ip == "127.0.0.1") return true
        val sans = runCatching { cert.subjectAlternativeNames }.getOrNull() ?: return false
        return sans.any { entry ->
            entry.size >= 2 && (entry[0] as? Int) == 7 && (entry[1] as? String) == ip
        }
    }

    private fun savePrivate(ks: KeyStore, target: File) {
        runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tmp, false).use { ks.store(it, STORE_PASSWORD) }
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target.setReadable(false, false)
            target.setReadable(true, true)
            target.setWritable(false, false)
            target.setWritable(true, true)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }

    // ---- Minimal ASN.1 DER X.509v3 encoder ----

    private fun buildX509Der(
        kp: KeyPair,
        cn: String,
        notBefore: Date,
        notAfter: Date,
        lanIp: String?,
        rng: SecureRandom,
    ): ByteArray {
        val serialBytes = ByteArray(16).also { rng.nextBytes(it) }
        serialBytes[0] = ((serialBytes[0].toInt() and 0x7F) or 0x01).toByte()
        val sigAlgId = derSequence(derOid(OID_ECDSA_WITH_SHA256))
        val name = derX500Name(cn, "OpalDesk")
        val validity = derSequence(derTime(notBefore), derTime(notAfter))
        val spki = kp.public.encoded
        val extensions = buildExtensions(lanIp)

        val tbs = derSequence(
            derExplicitTag(0, derInteger(BigInteger.valueOf(2))),
            derInteger(BigInteger(1, serialBytes)),
            sigAlgId,
            name,
            validity,
            name,
            spki,
            derExplicitTag(3, extensions),
        )
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(kp.private, rng)
            update(tbs)
        }.sign()
        return derSequence(tbs, sigAlgId, derBitString(sig, 0))
    }

    private fun buildExtensions(lanIp: String?): ByteArray {
        val extBc = derExtension(OID_BASIC_CONSTRAINTS, true, derSequence())
        val extKu = derExtension(OID_KEY_USAGE, true, derBitString(byteArrayOf(0x80.toByte()), 7))
        val extEku = derExtension(OID_EXT_KEY_USAGE, false, derSequence(derOid(OID_EKU_SERVER_AUTH)))

        val sans = mutableListOf<ByteArray>()
        sans.add(derPrimitiveContextTag(2, "opaldesk.local".toByteArray(StandardCharsets.US_ASCII)))
        sans.add(derPrimitiveContextTag(7, byteArrayOf(127, 0, 0, 1)))
        val ipStr = lanIp?.trim().orEmpty()
        if (ipStr.isNotEmpty() && ipStr != "127.0.0.1") {
            runCatching { InetAddress.getByName(ipStr).address }.getOrNull()?.let { raw ->
                sans.add(derPrimitiveContextTag(7, raw))
            }
        }
        val extSan = derExtension(OID_SUBJECT_ALT_NAME, false, derSequence(*sans.toTypedArray()))
        return derSequence(extBc, extKu, extEku, extSan)
    }

    private fun derExtension(oid: IntArray, critical: Boolean, valueDer: ByteArray): ByteArray =
        if (critical) derSequence(derOid(oid), derTag(0x01, byteArrayOf(0xFF.toByte())), derTag(0x04, valueDer))
        else derSequence(derOid(oid), derTag(0x04, valueDer))

    private fun derX500Name(cn: String, org: String): ByteArray {
        val rdnCn = derTag(0x31, derSequence(derOid(OID_COMMON_NAME), derTag(0x0C, cn.toByteArray(StandardCharsets.UTF_8))))
        val rdnOrg = derTag(0x31, derSequence(derOid(OID_ORGANIZATION), derTag(0x0C, org.toByteArray(StandardCharsets.UTF_8))))
        return derSequence(rdnCn, rdnOrg)
    }

    private fun derTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return derTag(0x17, fmt.format(date).toByteArray(StandardCharsets.US_ASCII))
    }

    private fun derInteger(v: BigInteger): ByteArray = derTag(0x02, v.toByteArray())

    private fun derBitString(bytes: ByteArray, unusedBits: Int): ByteArray {
        val out = ByteArray(bytes.size + 1)
        out[0] = (unusedBits and 0x07).toByte()
        System.arraycopy(bytes, 0, out, 1, bytes.size)
        return derTag(0x03, out)
    }

    private fun derOid(arcs: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var v = arcs[i]
            if (v == 0) {
                out.write(0)
            } else {
                val tmp = ByteArray(5)
                var pos = 5
                while (v > 0) {
                    tmp[--pos] = (v and 0x7F).toByte()
                    v = v ushr 7
                }
                for (j in pos until 4) out.write((tmp[j].toInt() and 0x7F) or 0x80)
                out.write(tmp[4].toInt() and 0x7F)
            }
        }
        return derTag(0x06, out.toByteArray())
    }

    private fun derSequence(vararg elements: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (el in elements) out.write(el)
        return derTag(0x30, out.toByteArray())
    }

    private fun derExplicitTag(tagNum: Int, content: ByteArray): ByteArray =
        derTag(0xA0 or (tagNum and 0x1F), content)

    private fun derPrimitiveContextTag(tagNum: Int, content: ByteArray): ByteArray =
        derTag(0x80 or (tagNum and 0x1F), content)

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val len = content.size
        if (len < 128) {
            out.write(len)
        } else {
            val lenBytes = ByteArray(4)
            var count = 0
            var tmp = len
            while (tmp > 0) {
                lenBytes[3 - count] = (tmp and 0xFF).toByte()
                tmp = tmp ushr 8
                count++
            }
            out.write(0x80 or count)
            out.write(lenBytes, 4 - count, count)
        }
        out.write(content)
        return out.toByteArray()
    }
}

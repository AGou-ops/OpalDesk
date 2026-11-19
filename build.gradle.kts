import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

// ---------------------------------------------------------------------------
// Shared Android SDK & Signing Certificate Resolution (:bridge & :helper)
// ---------------------------------------------------------------------------

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { b -> "%02x".format(b) }

fun resolveAndroidSdkDir(rootDir: File): File? {
    val localPropsFile = File(rootDir, "local.properties")
    if (localPropsFile.isFile) {
        val props = Properties()
        localPropsFile.inputStream().use { props.load(it) }
        val sdkProp = props.getProperty("sdk.dir")?.trim()
        if (!sdkProp.isNullOrEmpty()) {
            val candidate = File(sdkProp)
            if (candidate.isDirectory) return candidate
        }
    }
    val userHome = System.getProperty("user.home").orEmpty()
    val candidates = listOfNotNull(
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let(::File),
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let(::File),
        File(userHome, "Library/Android/sdk"),
        File(userHome, "Android/Sdk"),
        System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "Android/Sdk") },
    )
    return candidates.firstOrNull { it.isDirectory }
}

val resolvedSdkDir = resolveAndroidSdkDir(rootProject.projectDir)
extra["extBridgeAndroidSdkDir"] = resolvedSdkDir?.absolutePath.orEmpty()

val expectedCertOverride = (
    project.findProperty("expectedReleaseCertSha256")?.toString()?.takeIf { it.isNotBlank() }
        ?: System.getenv("EXTBRIDGE_EXPECTED_CERT_SHA256")?.takeIf { it.isNotBlank() }
    )?.trim()?.lowercase()
val allowCustomReleaseCert =
    project.findProperty("allowCustomReleaseCert")?.toString()?.equals("true", ignoreCase = true) == true

val envKsPath = System.getenv("EXTBRIDGE_KEYSTORE")?.takeIf { it.isNotBlank() }
val envKsFile = envKsPath?.let { rootProject.file(it) }
if (envKsFile != null && !envKsFile.exists()) {
    throw GradleException("EXTBRIDGE_KEYSTORE points to non-existent file: ${envKsFile.absolutePath}")
}
val localP12File = rootProject.file("exthelper.p12")
val envPass = (
    System.getenv("EXTBRIDGE_KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
        ?: System.getenv("EXTBRIDGE")?.takeIf { it.isNotBlank() }
    )
if (envKsFile != null && envPass == null) {
    throw GradleException(
        "EXTBRIDGE_KEYSTORE_PASS (or EXTBRIDGE) must be explicitly set when EXTBRIDGE_KEYSTORE is provided."
    )
}

val activeReleaseKs: File? = when {
    envKsFile != null -> envKsFile
    localP12File.exists() -> localP12File
    else -> null
}

var resolvedSigningPass = ""
var resolvedSigningAlias = "extbridge"
var resolvedSigningSource = "placeholder"
var resolvedCertSha256 = "0000000000000000000000000000000000000000000000000000000000000000"

if (activeReleaseKs != null) {
    val candidatePasswords = listOfNotNull(envPass, "extbridge").distinct()
    var loadedKs: KeyStore? = null
    var unlockedPass: String? = null
    var lastErr: Throwable? = null
    for (cand in candidatePasswords) {
        try {
            val candKs = KeyStore.getInstance("PKCS12")
            activeReleaseKs.inputStream().use { stream -> candKs.load(stream, cand.toCharArray()) }
            if (candKs.getKey("extbridge", cand.toCharArray()) != null) {
                loadedKs = candKs
                unlockedPass = cand
                break
            }
        } catch (t: Throwable) {
            lastErr = t
        }
    }
    val ks = loadedKs ?: throw GradleException(
        "Failed to unlock release keystore (${activeReleaseKs.name}) with configured keystore password.",
        lastErr,
    )
    val cert = ks.getCertificate("extbridge")
        ?: throw GradleException("Alias 'extbridge' not found in release keystore: ${activeReleaseKs.absolutePath}")
    val actualSha256 = sha256Hex(cert.encoded)
    if (expectedCertOverride != null && !allowCustomReleaseCert && !actualSha256.equals(expectedCertOverride, ignoreCase = true)) {
        throw GradleException(
            "Release keystore certificate SHA-256 mismatch! Expected $expectedCertOverride, got $actualSha256"
        )
    }
    resolvedSigningPass = unlockedPass ?: envPass ?: "extbridge"
    resolvedSigningAlias = "extbridge"
    resolvedSigningSource = if (envKsFile != null) "EXTBRIDGE_KEYSTORE" else "exthelper.p12"
    resolvedCertSha256 = actualSha256
} else {
    val userHome = System.getProperty("user.home").orEmpty()
    val debugKeystoreCandidates = listOfNotNull(
        System.getenv("ANDROID_USER_HOME")?.takeIf { it.isNotBlank() }?.let { File(it, "debug.keystore") },
        File(userHome, ".android/debug.keystore"),
    )
    val debugKsFile = debugKeystoreCandidates.firstOrNull { it.isFile }
    var debugCertSha: String? = null
    if (debugKsFile != null) {
        try {
            val debugKs = KeyStore.getInstance(debugKsFile, "android".toCharArray())
            val cert = debugKs.getCertificate("androiddebugkey")
                ?: debugKs.aliases().toList().firstOrNull()?.let { debugKs.getCertificate(it) }
            if (cert != null) {
                debugCertSha = sha256Hex(cert.encoded)
            }
        } catch (t: Throwable) {
            logger.warn("ExtBridge: could not read debug keystore ${debugKsFile.absolutePath}: ${t.message}")
        }
    }
    when {
        debugCertSha != null -> {
            resolvedSigningSource = "debug.keystore"
            resolvedCertSha256 = debugCertSha
        }
        expectedCertOverride != null -> {
            resolvedSigningSource = "expectedReleaseCertSha256"
            resolvedCertSha256 = expectedCertOverride
        }
        else -> {
            resolvedSigningSource = "placeholder"
        }
    }
}

extra["extBridgeSigningKeystorePath"] = activeReleaseKs?.absolutePath.orEmpty()
extra["extBridgeSigningPassword"] = resolvedSigningPass
extra["extBridgeSigningAlias"] = resolvedSigningAlias
extra["extBridgeSigningSource"] = resolvedSigningSource
extra["extBridgeCertSha256"] = resolvedCertSha256
extra["extBridgeExpectedCertSha256"] = expectedCertOverride.orEmpty()

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

fun loadLocalProperties(rootDir: File): Properties {
    val props = Properties()
    val localPropsFile = File(rootDir, "local.properties")
    if (localPropsFile.isFile) {
        localPropsFile.inputStream().use { props.load(it) }
    }
    return props
}

val localProps = loadLocalProperties(rootProject.projectDir)

fun resolveAndroidSdkDir(rootDir: File): File? {
    val sdkProp = localProps.getProperty("sdk.dir")?.trim()
    if (!sdkProp.isNullOrEmpty()) {
        val candidate = File(sdkProp)
        if (candidate.isDirectory) return candidate
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
extra["opalDeskAndroidSdkDir"] = resolvedSdkDir?.absolutePath.orEmpty()

val expectedCertOverride = (
    project.findProperty("expectedReleaseCertSha256")?.toString()?.takeIf { it.isNotBlank() }
        ?: System.getenv("OPALDESK_EXPECTED_CERT_SHA256")?.takeIf { it.isNotBlank() }
    )?.trim()?.lowercase()
val allowCustomReleaseCert =
    project.findProperty("allowCustomReleaseCert")?.toString()?.equals("true", ignoreCase = true) == true

val envKsPath = System.getenv("OPALDESK_KEYSTORE")?.takeIf { it.isNotBlank() }
val envKsFile = envKsPath?.let { rootProject.file(it) }
if (envKsFile != null && !envKsFile.exists()) {
    throw GradleException("OPALDESK_KEYSTORE points to non-existent file: ${envKsFile.absolutePath}")
}
val localP12File = rootProject.file("opaldesk.p12")
val envPass = System.getenv("OPALDESK_KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
val localPropPass = localProps.getProperty("opaldesk.keystore.password")?.trim()?.takeIf { it.isNotBlank() }
if (envKsFile != null && envPass == null) {
    throw GradleException(
        "OPALDESK_KEYSTORE_PASS must be explicitly set when OPALDESK_KEYSTORE is provided."
    )
}

val activeReleaseKs: File? = when {
    envKsFile != null -> envKsFile
    localP12File.exists() -> localP12File
    else -> null
}

var resolvedSigningPass = ""
var resolvedSigningAlias = "opaldesk"
var resolvedSigningSource = "placeholder"
var resolvedCertSha256 = "0000000000000000000000000000000000000000000000000000000000000000"

if (activeReleaseKs != null) {
    val candidatePasswords = listOfNotNull(envPass, localPropPass, "opaldesk").distinct()
    var loadedKs: KeyStore? = null
    var unlockedPass: String? = null
    var lastErr: Throwable? = null
    for (cand in candidatePasswords) {
        try {
            val candKs = KeyStore.getInstance("PKCS12")
            activeReleaseKs.inputStream().use { stream -> candKs.load(stream, cand.toCharArray()) }
            if (candKs.getKey("opaldesk", cand.toCharArray()) != null) {
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
    val cert = ks.getCertificate("opaldesk")
        ?: throw GradleException("Alias 'opaldesk' not found in release keystore: ${activeReleaseKs.absolutePath}")
    val actualSha256 = sha256Hex(cert.encoded)
    if (expectedCertOverride != null && !allowCustomReleaseCert && !actualSha256.equals(expectedCertOverride, ignoreCase = true)) {
        throw GradleException(
            "Release keystore certificate SHA-256 mismatch! Expected $expectedCertOverride, got $actualSha256"
        )
    }
    resolvedSigningPass = unlockedPass ?: envPass ?: "opaldesk"
    resolvedSigningAlias = "opaldesk"
    resolvedSigningSource = if (envKsFile != null) "OPALDESK_KEYSTORE" else "opaldesk.p12"
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
            logger.warn("OpalDesk: could not read debug keystore ${debugKsFile.absolutePath}: ${t.message}")
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

extra["opalDeskSigningKeystorePath"] = activeReleaseKs?.absolutePath.orEmpty()
extra["opalDeskSigningPassword"] = resolvedSigningPass
extra["opalDeskSigningAlias"] = resolvedSigningAlias
extra["opalDeskSigningSource"] = resolvedSigningSource
extra["opalDeskCertSha256"] = resolvedCertSha256
extra["opalDeskExpectedCertSha256"] = expectedCertOverride.orEmpty()

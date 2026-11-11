package io.github.purplepalm.custommaps.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The certificate subject of the key Android Studio and the Android build tools make on their own
 * for debug builds, as X.500 names it (RFC 2253): `CN=Android Debug,O=Android,C=US`.
 */
private const val DEFAULT_DEBUG_COMMON_NAME = "Android Debug"

/**
 * Whether [subject], a certificate's subject as X.500 names it (RFC 2253, `CN=...,O=...`), is that
 * of the default Android debug key: its common name is "Android Debug". A build without the shared
 * debug key is signed with such a key, made new on each CI run, so the next build cannot be
 * installed over it.
 */
internal fun isDefaultDebugSubject(subject: String): Boolean =
    attributes(subject).any { (type, value) -> type.equals("CN", ignoreCase = true) && value == DEFAULT_DEBUG_COMMON_NAME }

/** The attributes of an RFC 2253 name in order, type and value, with escaped characters kept as they are meant. */
private fun attributes(subject: String): List<Pair<String, String>> {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    var escaped = false
    for (char in subject) {
        when {
            escaped -> {
                current.append(char)
                escaped = false
            }
            char == '\\' -> escaped = true
            char == ',' || char == '+' -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
    }
    parts += current.toString()
    return parts.mapNotNull { part ->
        val type = part.substringBefore('=', "").trim()
        if (type.isEmpty()) null else type to part.substringAfter('=').trim()
    }
}

/**
 * Whether this app is signed with the default Android debug key ([isDefaultDebugSubject]). Reads
 * the app's own signing certificates from Android; false when it cannot.
 */
fun Context.signedWithDefaultDebugKey(): Boolean =
    try {
        signingCertificates().any { signature ->
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(signature.toByteArray())) as? X509Certificate
            certificate != null && isDefaultDebugSubject(certificate.subjectX500Principal.name)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: CertificateException) {
        false
    }

private fun Context.signingCertificates(): List<Signature> {
    val manager = packageManager
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> manager
            .getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            .signingInfo?.apkContentsSigners.orEmpty().toList()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners.orEmpty().toList()
        }
        else -> {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty().toList()
        }
    }
}

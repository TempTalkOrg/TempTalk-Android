package com.difft.android.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal object SignatureVerifier {

    fun checkApkSign(context: Context, expectedSha256List: Set<String>): Boolean {
        val certificate = getInstalledApkCertificate(context) ?: return false
        val currentSha256 = sha256Hex(certificate.encoded)
        return checkSign(currentSha256, expectedSha256List)
    }

    private fun checkSign(sign: String?, expectedSha256List: Set<String>): Boolean {
        return !sign.isNullOrEmpty() && expectedSha256List.contains(sign)
    }

    private fun getInstalledApkCertificate(context: Context): X509Certificate? {
        return runCatching {
            val packageInfo = context.packageManager.getPackageInfoCompat(context.packageName)
            val signatureBytes = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    val info = packageInfo.signingInfo ?: return null
                    val signatures = if (info.hasMultipleSigners()) {
                        info.apkContentsSigners
                    } else {
                        info.signingCertificateHistory
                    }
                    signatures.firstOrNull()?.toByteArray() ?: return null
                }

                else -> {
                    @Suppress("DEPRECATION")
                    packageInfo.signatures?.firstOrNull()?.toByteArray() ?: return null
                }
            }

            val certificateFactory = CertificateFactory.getInstance("X.509")
            certificateFactory
                .generateCertificate(signatureBytes.inputStream()) as X509Certificate
        }.onFailure { e ->
            L.w(e) { "[SignatureVerifier] getInstalledApkCertificate error" }
        }.getOrNull()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString(separator = "") { each -> "%02x".format(each) }
    }

    private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }
}

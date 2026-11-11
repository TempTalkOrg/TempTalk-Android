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
        val currentSha256 = getApkSignSha256(context) ?: return false
        return checkSign(currentSha256, expectedSha256List)
    }

    fun getApkSignSha256(context: Context): String? {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfoCompat(context.packageName)
        }.onFailure { e ->
            L.w(e) { "[SignatureVerifier] getApkSignSha256 getPackageInfo error" }
        }.getOrNull()
        val certificate = extractCertificate(packageInfo) ?: return null
        return sha256Hex(certificate.encoded)
    }

    





    fun checkApkFileSign(
        context: Context,
        apkPath: String,
        expectedSha256List: Set<String>,
        expectedPackageName: String? = null,
    ): Boolean {
        val packageInfo = runCatching {
            context.packageManager.getPackageArchiveInfoCompat(apkPath)
        }.onFailure { e ->
            L.w(e) { "[SignatureVerifier] getPackageArchiveInfo error" }
        }.getOrNull() ?: run {
            L.w { "[SignatureVerifier] getPackageArchiveInfo returned null, apkPath=$apkPath" }
            return false
        }

        
        if (!expectedPackageName.isNullOrEmpty() && packageInfo.packageName != expectedPackageName) {
            L.w { "[SignatureVerifier] apk packageName mismatch: actual=${packageInfo.packageName}, expected=$expectedPackageName" }
            return false
        }

        val certificate = extractCertificate(packageInfo) ?: run {
            L.w { "[SignatureVerifier] cannot extract certificate from apk (signingInfo & signatures both empty)" }
            return false
        }
        val actualSha256 = sha256Hex(certificate.encoded)
        val matched = checkSign(actualSha256, expectedSha256List)
        if (!matched) {
            
            L.w { "[SignatureVerifier] apk cert sha256=$actualSha256 not in whitelist." }
        }
        return matched
    }

    private fun checkSign(sign: String?, expectedSha256List: Set<String>): Boolean {
        return !sign.isNullOrEmpty() && expectedSha256List.contains(sign)
    }

    private fun extractCertificate(packageInfo: PackageInfo?): X509Certificate? {
        packageInfo ?: return null
        return runCatching {
            val signatureBytes = extractSignatureBytes(packageInfo) ?: return null
            val certificateFactory = CertificateFactory.getInstance("X.509")
            certificateFactory
                .generateCertificate(signatureBytes.inputStream()) as X509Certificate
        }.onFailure { e ->
            L.w(e) { "[SignatureVerifier] extractCertificate error" }
        }.getOrNull()
    }

    





    private fun extractSignatureBytes(packageInfo: PackageInfo): ByteArray? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageInfo.signingInfo
            if (info != null) {
                info.apkContentsSigners.firstOrNull()?.toByteArray()?.let { return it }
            }
        }
        @Suppress("DEPRECATION")
        return packageInfo.signatures?.firstOrNull()?.toByteArray()
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

    @Suppress("DEPRECATION")
    private fun PackageManager.getPackageArchiveInfoCompat(apkPath: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            
            val flags = (PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES).toLong()
            getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
        } else {
            getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
        }
    }
}

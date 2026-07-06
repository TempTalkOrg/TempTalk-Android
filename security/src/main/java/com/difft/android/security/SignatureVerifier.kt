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

    /**
     * 校验"待安装的 APK 文件"（尚未安装）的签名证书是否在白名单内。
     *
     * 用于应用内升级安装前的强校验：即使升级服务器/CDN 被攻破、下发了"hash 自匹配"
     * 的恶意 APK，只要其签名证书不在硬编码白名单内即拒绝安装，从而避免 RCE。
     *
     * @param apkPath 已下载到本地的 APK 文件绝对路径
     */
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

        // 防止被替换成"另一个合法签名但不同包名"的 APK
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
            // 打印实际证书指纹便于定位：debug 包/其它密钥 → 指纹与白名单不同;白名单遗漏 → 指纹正确但未命中。
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

    /**
     * 取签名证书 DER 字节。优先用 API≥P 的 signingInfo.apkContentsSigners（当前 APK 内容签名者，
     * 不用 signingCertificateHistory：V3 轮换时 index 0 是最旧密钥）。
     *
     * 已知问题：getPackageArchiveInfo + GET_SIGNING_CERTIFICATES 在部分场景会返回 signingInfo == null，
     * 此时回退到 deprecated 的 packageInfo.signatures（需查询时带 GET_SIGNATURES，见 getPackageArchiveInfoCompat）。
     */
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
            // 同时请求 GET_SIGNATURES，作为 signingInfo 为 null 时的回退来源（v1/旧设备兼容）。
            val flags = (PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES).toLong()
            getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
        } else {
            getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
        }
    }
}

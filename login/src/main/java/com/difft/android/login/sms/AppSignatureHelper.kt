package com.difft.android.login.sms

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Arrays

/**
 * Helper class to generate the 11-character app hash required for SMS Retriever API.
 *
 * NOTE: This is a DEBUG/UTILITY tool. The app hashes have already been calculated:
 *
 * The app hash is derived from the app's package name and signing certificate.
 * It must be included at the end of the SMS message for the SMS Retriever API to work.
 *
 * Usage (for debugging or when signing certificate changes):
 * ```
 * val helper = AppSignatureHelper(context)
 * val hash = helper.getAppSignatures().firstOrNull()
 * Log.d("AppHash", "Hash for SMS: $hash")
 * ```
 *
 * Note: Different signing certificates (debug vs release) will produce different hashes.
 * Make sure to generate the hash for your release certificate when deploying to production.
 */
class AppSignatureHelper(private val context: Context) {

    companion object {
        private const val HASH_TYPE = "SHA-256"
        private const val NUM_HASHED_BYTES = 9
        private const val NUM_BASE64_CHAR = 11
    }

    /**
     * Get the app signatures (hashes) for SMS Retriever.
     * Returns a list because an app can be signed with multiple certificates.
     */
    fun getAppSignatures(): List<String> {
        val appSignatures = mutableListOf<String>()

        try {
            val packageName = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo
                if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures
            }

            signatures?.forEach { signature ->
                val hash = hash(packageName, signature.toCharsString())
                if (hash != null) {
                    appSignatures.add(hash)
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            L.e { "[AppSignatureHelper] Package not found: ${e.message}" }
        }

        L.d { "[AppSignatureHelper] App signatures: $appSignatures" }
        return appSignatures
    }

    /**
     * Generate the hash from package name and signature.
     */
    private fun hash(packageName: String, signature: String): String? {
        val appInfo = "$packageName $signature"
        return try {
            val messageDigest = MessageDigest.getInstance(HASH_TYPE)
            messageDigest.update(appInfo.toByteArray(StandardCharsets.UTF_8))
            var hashSignature = messageDigest.digest()

            // Truncate to first 9 bytes
            hashSignature = Arrays.copyOfRange(hashSignature, 0, NUM_HASHED_BYTES)

            // Base64 encode and take first 11 characters
            var base64Hash = Base64.encodeToString(hashSignature, Base64.NO_PADDING or Base64.NO_WRAP)
            base64Hash = base64Hash.substring(0, NUM_BASE64_CHAR)

            L.d { "[AppSignatureHelper] Generated hash: $base64Hash" }
            base64Hash
        } catch (e: NoSuchAlgorithmException) {
            L.e { "[AppSignatureHelper] Hash algorithm not found: ${e.message}" }
            null
        }
    }
}
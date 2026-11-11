package com.difft.android.base.migration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64












object PeerApps {

    const val LEGACY_FAMILY = "org.difft.chative"
    const val CURRENT_FAMILY = "org.difft.temptalk"
    private const val TEST_SUFFIX = ".test"

    
    const val HANDOFF_ACTIVITY = "com.difft.android.migration.v2.transfer.handoff.MigrationHandoffActivity"
    const val SEND_ACTIVITY = "com.difft.android.migration.v2.transfer.send.MigrationP2pSendActivity"
    const val RECEIVE_ACTIVITY = "com.difft.android.migration.v2.transfer.login.MigrationP2pReceiveActivity"
    const val LEGACY_GUIDE_ACTIVITY = "com.difft.android.migration.legacy.LegacyMigrationGuideActivity"

    
    const val HANDOFF_ENTRY_ACTIVITY = "com.difft.android.migration.v2.handoff.MigrationHandoffEntryActivity"

    



    const val STATUS_AUTHORITY_SUFFIX = ".migration.status"

    
    const val LINK_LOGIN_ACTIVITY = "com.difft.android.migration.v2.transfer.link.LinkLoginActivity"

    
    const val EXTRA_LINK_EPHEMERAL_ID = "extra_link_ephemeral_id"
    const val EXTRA_LINK_PUBLIC_KEY = "extra_link_public_key"
    const val EXTRA_LINK_MIGRATION_STATE = "extra_link_migration_state"

    
    const val EXTRA_AUTO_START = "extra_auto_start"

    
    const val EXTRA_INCLUDE_ATTACHMENTS = "extra_include_attachments"

    
    const val EXTRA_AWAIT_PEER_CONFIRM = "extra_await_peer_confirm"

    
    const val ACTION_HANDOFF_SEND = "com.difft.android.migration.HANDOFF_SEND"

    
    const val ACTION_HANDOFF_RESUME_RECEIVE = "com.difft.android.migration.HANDOFF_RESUME_RECEIVE"

    




    const val ACTION_HANDOFF_START_RECEIVE = "com.difft.android.migration.HANDOFF_START_RECEIVE"

    




    const val EXTRA_CREDENTIAL_HANDOFF = "extra_credential_handoff"

    
    const val EXTRA_REJECT_REASON = "extra_reject_reason"

    
    const val EXTRA_QR_PAYLOAD = "extra_qr_payload"

    



    const val EXTRA_FROM_LINK = "extra_from_link"

    
    const val LOOPBACK_IP = "127.0.0.1"

    
    private val TRUSTED_CERT_SHA256 = setOf(
        
        "b4e071def9a09fbdab690d0aa0583a2f62e7cdaa96a2d167e3fa8ceeb4853e3e",
        
        "53c73dd8561d756ad3c162a12ab079357de76f5c6956a2d7e701597e3c4282d5",
        
        "7cbe61a919125803c900310cbe5fa9169b1920fbe1a6742a7f6d67708a6e213c",
    )

    
    fun isLegacy(context: Context): Boolean = context.packageName.startsWith(LEGACY_FAMILY)

    
    fun peerPackage(context: Context): String {
        val family = if (isLegacy(context)) CURRENT_FAMILY else LEGACY_FAMILY
        return if (context.packageName.endsWith(TEST_SUFFIX)) family + TEST_SUFFIX else family
    }

    sealed interface PeerStatus {
        data object NotInstalled : PeerStatus

        
        data object Trusted : PeerStatus

        
        data class Untrusted(val fingerprints: List<String>) : PeerStatus
    }

    fun peerStatus(context: Context): PeerStatus = peerStatus(context, peerPackage(context))

    
    fun peerStatus(context: Context, packageName: String): PeerStatus {
        val pm = context.packageManager
        val signatures = runCatching { signaturesOf(pm, packageName) }
            .onFailure { e ->
                if (e !is PackageManager.NameNotFoundException) {
                    L.w { "[PeerApps] signature read failed for $packageName: ${e.message}" }
                }
            }
            .getOrNull() ?: return PeerStatus.NotInstalled
        val fingerprints = signatures.map { sha256Hex(it.toByteArray()) }
        if (fingerprints.any { it in TRUSTED_CERT_SHA256 }) return PeerStatus.Trusted
        if (isDebuggable(context) && pm.checkSignatures(context.packageName, packageName) == PackageManager.SIGNATURE_MATCH) {
            L.i { "[PeerApps] $packageName trusted via shared debug key" }
            return PeerStatus.Trusted
        }
        L.w { "[PeerApps] $packageName carries an unknown signing certificate" }
        return PeerStatus.Untrusted(fingerprints)
    }

    
    fun peerStatusAuthority(context: Context): String = peerPackage(context) + STATUS_AUTHORITY_SUFFIX

    








    fun trustedCaller(context: Context, callingUid: Int): Boolean {
        if (callingUid == android.os.Process.myUid()) return true
        val names = runCatching { context.packageManager.getPackagesForUid(callingUid) }.getOrNull()
        if (names.isNullOrEmpty()) {
            L.w { "[PeerApps] caller uid resolves to no package — refusing" }
            return false
        }
        if (names.size != 1) {
            L.w { "[PeerApps] caller uid maps to ${names.size} packages (shared uid) — refusing" }
            return false
        }
        if (names[0] != peerPackage(context)) {
            L.w { "[PeerApps] caller ${names[0]} is not the peer package — refusing" }
            return false
        }
        return peerStatus(context, names[0]) == PeerStatus.Trusted
    }

    








    fun looksLikeMigrationQr(raw: String): Boolean =
        MigrationQrWire.looksLikeBarePayload(raw) || looksLikeCompatibleEnvelope(raw)

    





    fun isComputerExportQr(raw: String): Boolean {
        val payload = if (looksLikeCompatibleEnvelope(raw)) {
            envelopePayloadBytes(raw)
        } else {
            MigrationQrWire.stripLineBreaks(raw).takeIf(MigrationQrWire::looksLikeBarePayload)?.let(Base45::decode)
        }
        return payload?.let(MigrationQrWire::isDownloadPayload) == true
    }

    










    fun envelopePayloadBytes(raw: String): ByteArray? {
        if (!looksLikeCompatibleEnvelope(raw)) return null
        val value = raw.trim().substringBefore('#').substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("$ENVELOPE_PAYLOAD_QUERY=") }
            ?.substringAfter('=')
            ?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull() }
            ?.takeIf { v -> v.isNotEmpty() && v.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' } }
            ?: return null
        return runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
            ?.takeIf { it.size <= MigrationQrWire.MAX_PAYLOAD_BYTES }
    }

    




    private fun looksLikeCompatibleEnvelope(raw: String): Boolean {
        val head = raw.trimStart().take(ENVELOPE_PREFIX_SCAN)
        val prefixed = ENVELOPE_SCHEMES.any { head.startsWith("$it://$ENVELOPE_HOST?", ignoreCase = true) }
        return prefixed && raw.contains("$ENVELOPE_PAYLOAD_QUERY=")
    }

    
    val ENVELOPE_SCHEMES = listOf("chative", "temptalk")

    
    const val ENVELOPE_HOST = "transfer"

    
    const val ENVELOPE_PAYLOAD_QUERY = "migrationV2"

    
    private const val ENVELOPE_PREFIX_SCAN = 32

    private fun signaturesOf(pm: PackageManager, packageName: String): List<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return emptyList()
            
            (if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory).toList()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.toList() ?: emptyList()
        }
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

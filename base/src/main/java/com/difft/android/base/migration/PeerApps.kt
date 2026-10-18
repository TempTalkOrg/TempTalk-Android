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

/**
 * The two package families this app ships as — the legacy `org.difft.chative` and the current
 * `org.difft.temptalk`, each with a `.test` twin — and what the same-device migration needs to know
 * about the other one: whether it is installed, whether it is genuinely ours, and how to address it.
 *
 * "Genuinely ours" is decided by the pinned certificates in [TRUSTED_CERT_SHA256], not by
 * `checkSignatures`: the two families are signed with different keys and the F-Droid build is signed
 * by F-Droid, so "signed like me" proves nothing across channels. A peer signed with anything else is
 * a look-alike and is never handed user data; debuggable builds additionally trust a peer sharing
 * their own debug key. Both families are declared in the manifest `<queries>` block.
 */
object PeerApps {

    const val LEGACY_FAMILY = "org.difft.chative"
    const val CURRENT_FAMILY = "org.difft.temptalk"
    private const val TEST_SUFFIX = ".test"

    /** Exported trampoline present in both families; the only cross-package entry point. */
    const val HANDOFF_ACTIVITY = "com.difft.android.migration.v2.transfer.handoff.MigrationHandoffActivity"
    const val SEND_ACTIVITY = "com.difft.android.migration.v2.transfer.send.MigrationP2pSendActivity"
    const val RECEIVE_ACTIVITY = "com.difft.android.migration.v2.transfer.login.MigrationP2pReceiveActivity"
    const val LEGACY_GUIDE_ACTIVITY = "com.difft.android.migration.legacy.LegacyMigrationGuideActivity"

    /** The no-login migration entry page the trampoline opens for [ACTION_HANDOFF_START_RECEIVE]. */
    const val HANDOFF_ENTRY_ACTIVITY = "com.difft.android.migration.v2.handoff.MigrationHandoffEntryActivity"

    /**
     * Suffix of the read-only migration-status provider authority both families export
     * (`<applicationId>` + this). Single owner of the string; the `:app` handoff contract re-exports it.
     */
    const val STATUS_AUTHORITY_SUFFIX = ".migration.status"

    /** The "sign in to Quicall for Desktop" confirmation page a scanned link QR opens (in :app; :chat launches it by name). */
    const val LINK_LOGIN_ACTIVITY = "com.difft.android.migration.v2.transfer.link.LinkLoginActivity"

    /** Link QR fields handed from the scanner to [LINK_LOGIN_ACTIVITY]: the provisioning id and key, and the raw migration query values. */
    const val EXTRA_LINK_EPHEMERAL_ID = "extra_link_ephemeral_id"
    const val EXTRA_LINK_PUBLIC_KEY = "extra_link_public_key"
    const val EXTRA_LINK_MIGRATION_STATE = "extra_link_migration_state"

    /** The send screen was opened with a decision already made: start the transfer as soon as the payload is in. */
    const val EXTRA_AUTO_START = "extra_auto_start"

    /** Whether attachment bytes travel; absent → true. Read by the send screen when it auto-starts. */
    const val EXTRA_INCLUDE_ATTACHMENTS = "extra_include_attachments"

    /** Boolean: the Desktop holds `prepare-upload` until its user confirms an account switch. */
    const val EXTRA_AWAIT_PEER_CONFIRM = "extra_await_peer_confirm"

    /** The current app asks the legacy one to start sending to the loopback receiver it just opened. */
    const val ACTION_HANDOFF_SEND = "com.difft.android.migration.HANDOFF_SEND"

    /** The legacy app asks the current one to come back to the front once the transfer is done. */
    const val ACTION_HANDOFF_RESUME_RECEIVE = "com.difft.android.migration.HANDOFF_RESUME_RECEIVE"

    /**
     * The legacy app asks the current one to open its no-login migration entry page, so the user can
     * start the same-device handoff from the old app's home screen. The caller is vetted exactly like
     * [ACTION_HANDOFF_SEND] before anything opens.
     */
    const val ACTION_HANDOFF_START_RECEIVE = "com.difft.android.migration.HANDOFF_START_RECEIVE"

    /**
     * The no-login credential handoff is in play: the receiver is a fresh install with no account and
     * the container will carry the sender's session credential. Only ever set on the loopback
     * same-device path, by the trampoline, after the caller's package + certificate checked out.
     */
    const val EXTRA_CREDENTIAL_HANDOFF = "extra_credential_handoff"

    /** Typed reason a handoff screen was blocked, carried between the trampoline and the screens. */
    const val EXTRA_REJECT_REASON = "extra_reject_reason"

    /** Raw pairing payload JSON — the same string a QR scan yields — riding a handoff or scan intent. */
    const val EXTRA_QR_PAYLOAD = "extra_qr_payload"

    /**
     * The pairing payload came out of a Desktop link-login QR: the send screen was opened right after
     * the link succeeded, so a failure must say the login itself is done and there is no QR to re-scan.
     */
    const val EXTRA_FROM_LINK = "extra_from_link"

    /** A same-device receiver listens here; a handoff payload naming any other address is refused. */
    const val LOOPBACK_IP = "127.0.0.1"

    /** SHA-256 of the whole signing certificate, lowercase hex, as `apksigner --print-certs` prints it. */
    private val TRUSTED_CERT_SHA256 = setOf(
        // Legacy family — official site and historical Play releases (key "difft").
        "b4e071def9a09fbdab690d0aa0583a2f62e7cdaa96a2d167e3fa8ceeb4853e3e",
        // Current family — Google Play and the official site share this release key (key "TT").
        "53c73dd8561d756ad3c162a12ab079357de76f5c6956a2d7e701597e3c4282d5",
        // Current family — the F-Droid build, signed by F-Droid.
        "7cbe61a919125803c900310cbe5fa9169b1920fbe1a6742a7f6d67708a6e213c",
    )

    /** True for the retiring `org.difft.chative` family (and its `.test` twin). */
    fun isLegacy(context: Context): Boolean = context.packageName.startsWith(LEGACY_FAMILY)

    /** The other family's package for the same environment: `.test` stays with `.test`. */
    fun peerPackage(context: Context): String {
        val family = if (isLegacy(context)) CURRENT_FAMILY else LEGACY_FAMILY
        return if (context.packageName.endsWith(TEST_SUFFIX)) family + TEST_SUFFIX else family
    }

    sealed interface PeerStatus {
        data object NotInstalled : PeerStatus

        /** Installed, signed with one of our certificates (or a debug pair's shared key). */
        data object Trusted : PeerStatus

        /** Installed under our package name but signed with a key we do not know — never talk to it. */
        data class Untrusted(val fingerprints: List<String>) : PeerStatus
    }

    fun peerStatus(context: Context): PeerStatus = peerStatus(context, peerPackage(context))

    /** [status] for an explicit [packageName]; the trampoline uses it to vet its caller. */
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

    /** Authority of the peer's read-only migration-status provider (`content://<peer>.migration.status`). */
    fun peerStatusAuthority(context: Context): String = peerPackage(context) + STATUS_AUTHORITY_SUFFIX

    /**
     * Whether a binder caller identified by [callingUid] is the trusted peer app — the security
     * boundary of the exported migration-status provider.
     *
     * Fail-closed on every ambiguity: a uid resolving to nothing, to an empty list, or to more than
     * one package (a `sharedUserId` group cannot attribute the call to any one member) is refused, as
     * is a package that is not the expected peer or whose certificate is not in [TRUSTED_CERT_SHA256].
     * Our own uid is trusted so the provider can answer a self-call.
     */
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

    /**
     * Cheap shape test for a scanned string before the real decode. Two shapes count: the bare payload
     * (Base45 text of the v2 binary structure, see [MigrationQrWire]), and the legacy device-transfer
     * URL (`chative://transfer?version=1&…`) with the payload appended as one more query parameter,
     * which is how iOS renders its pairing code.
     *
     * Shape only — whoever acts on it still decodes and validates the payload. The Desktop login link
     * (`tsdevice:/…`) is not a migration code; the device-linking flow owns it.
     */
    fun looksLikeMigrationQr(raw: String): Boolean =
        MigrationQrWire.looksLikeBarePayload(raw) || looksLikeCompatibleEnvelope(raw)

    /**
     * Whether [raw] is a pairing code whose displayer holds the data: a computer's export code. Only
     * a phone that is signing in can take that data (the sign-in migration page), so an account-holding
     * scanner must decline it rather than start a send the computer will refuse. Reads the flags byte
     * only; whoever acts on a code still decodes and validates it.
     */
    fun isComputerExportQr(raw: String): Boolean {
        val payload = if (looksLikeCompatibleEnvelope(raw)) {
            envelopePayloadBytes(raw)
        } else {
            MigrationQrWire.stripLineBreaks(raw).takeIf(MigrationQrWire::looksLikeBarePayload)?.let(Base45::decode)
        }
        return payload?.let(MigrationQrWire::isDownloadPayload) == true
    }

    /**
     * The pairing payload carried inside the legacy device-transfer link, or null when [raw] is not
     * such a link or its payload is not acceptable. The one reader of the envelope: the codec and the
     * scanner's [isComputerExportQr] both use it, so they always agree on what the link holds.
     *
     * iOS publishes its pairing code as `chative://transfer?version=1&…&migrationV2=<base64url(bytes)>`:
     * the legacy link an old iOS still understands, with the payload appended. The value is read
     * without a URL parser: fragment dropped, percent-decoded, unpadded base64url alphabet only.
     *
     * Never logs the decoded bytes: the payload carries the transfer key.
     */
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

    /**
     * The envelope's fingerprint, tested on the string so no URL parser is needed: our transfer
     * scheme, the `transfer` host, and the payload parameter. The parameter's presence is what makes
     * this ours — a legacy link without it belongs to the old transfer flow and must fall through.
     */
    private fun looksLikeCompatibleEnvelope(raw: String): Boolean {
        val head = raw.trimStart().take(ENVELOPE_PREFIX_SCAN)
        val prefixed = ENVELOPE_SCHEMES.any { head.startsWith("$it://$ENVELOPE_HOST?", ignoreCase = true) }
        return prefixed && raw.contains("$ENVELOPE_PAYLOAD_QUERY=")
    }

    /** Schemes the legacy device-transfer link is published under. */
    val ENVELOPE_SCHEMES = listOf("chative", "temptalk")

    /** The legacy link's host; anything else under the same scheme is a different feature (e.g. device linking). */
    const val ENVELOPE_HOST = "transfer"

    /** Query parameter carrying base64url(v2 pairing payload) inside the legacy link. */
    const val ENVELOPE_PAYLOAD_QUERY = "migrationV2"

    /** Enough of the string to see `<scheme>://transfer?`; the payload itself can be hundreds of chars. */
    private const val ENVELOPE_PREFIX_SCAN = 32

    private fun signaturesOf(pm: PackageManager, packageName: String): List<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return emptyList()
            // A rotated key keeps its history; any certificate in it identifies the app.
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

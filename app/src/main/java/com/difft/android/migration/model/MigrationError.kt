package com.difft.android.migration.model

import com.difft.android.base.log.WCDBKeyUnavailableException
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Stable error envelope surfaced to UI / notifications when a migration operation fails.
 *
 * Carries a stable `code` (`MIG_*`) for telemetry; the localized message is resolved at the UI
 * boundary so this model layer stays free of `R.string.*`.
 *
 * Translate caught exceptions via [from]; do not branch on `Throwable` type at the UI layer.
 */
sealed class MigrationError(val code: String) {
    // ── Backup-side errors ─────────────────────────────────────────────
    /** WCDB snapshot step exhausted retries. */
    object SnapshotFailed : MigrationError("MIG_SNAPSHOT_FAILED")

    /** SAF write failed with an out-of-space indication. */
    object DiskSpaceInsufficient : MigrationError("MIG_DISK_SPACE_INSUFFICIENT")

    /** SAF write failed for an unspecified IO reason. */
    object SafWriteFailed : MigrationError("MIG_SAF_WRITE_FAILED")

    /** SAF permission was revoked between picking the target URI and writing to it. */
    object SafPermissionLost : MigrationError("MIG_SAF_PERMISSION_LOST")

    /** User cancelled the backup (or system cancelled the coroutine). */
    object Cancelled : MigrationError("MIG_CANCELLED")

    /** The peer closed the stream before its end: it went offline, quit, or cancelled. */
    object SourceUnavailable : MigrationError("MIG_SOURCE_UNAVAILABLE")

    /** The link stopped delivering bytes before the stream's end. */
    object ConnectionLost : MigrationError("MIG_CONNECTION_LOST")

    /** Encryption pipeline failed (cipher init / stream write). */
    object EncryptionFailed : MigrationError("MIG_ENCRYPT_FAILED")

    // ── Restore-side errors ─────────────────────────────────────────────
    object PassphraseInvalid : MigrationError("MIG_PASSPHRASE_INVALID")
    object FileFormatInvalid : MigrationError("MIG_FILE_FORMAT_INVALID")
    object FileCorrupted : MigrationError("MIG_FILE_CORRUPTED")
    object HmacMismatch : MigrationError("MIG_HMAC_MISMATCH")
    object UnsupportedVersion : MigrationError("MIG_UNSUPPORTED_VERSION")

    /**
     * The container's format MAJOR is newer than this build supports. Distinct from
     * [UnsupportedVersion] because it is user-actionable: the UI asks the user to upgrade the app
     * on this device before migrating.
     */
    object VersionTooNew : MigrationError("MIG_VERSION_TOO_NEW")

    object AccountMismatch : MigrationError("MIG_ACCOUNT_MISMATCH")
    object SignInFailed : MigrationError("MIG_SIGN_IN_FAILED")
    object KeystoreUnavailable : MigrationError("MIG_KEYSTORE_UNAVAILABLE")
    object AtomicWriteFailed : MigrationError("MIG_ATOMIC_WRITE_FAILED")

    /**
     * Promote was unrecoverable and safely abandoned back to the clean net-install state (marker
     * cleared, staging discarded). Unlike [AtomicWriteFailed] this is not retryable — the UI
     * re-prompts restore from scratch.
     */
    object RestoreAbortedCleanState : MigrationError("MIG_RESTORE_ABORTED_CLEAN_STATE")

    /**
     * The restore target is not a fresh install — an account is already established here, so the
     * net-install-only import is rejected up-front with no staging, promote or DB write. A
     * validation rejection, raised by both the file and P2P paths.
     */
    object TargetNotEmpty : MigrationError("MIG_TARGET_NOT_EMPTY")

    /**
     * The container's `identity` record cannot be turned into a usable key pair (public key =
     * 0x05 ‖ 32 bytes, private key = 32 bytes). Raised before promote, so nothing is committed and
     * the restore never reaches sign-in — whose first request would revoke the old device.
     */
    object IdentityInvalid : MigrationError("MIG_IDENTITY_INVALID")

    /**
     * A same-device no-login handoff restore imported successfully but the container carried no
     * session-credential record. Raised before promote: the receiver must come up signed in, and
     * the sender wipes its own credential once it sees success.
     */
    object CredentialMissing : MigrationError("MIG_CREDENTIAL_MISSING")

    /** Catch-all for unanticipated failures (`L.e` should also log the original cause). */
    object Unknown : MigrationError("MIG_UNKNOWN")

    /** Convenience for backup-side IO failures bucketed as "device-level write problem". */
    val isIoFailure: Boolean
        get() = this === DiskSpaceInsufficient || this === SafWriteFailed ||
                this === SafPermissionLost || this === AtomicWriteFailed

    companion object {
        /**
         * Translate a thrown exception into a stable [MigrationError]. The order of the
         * `when` matters: more specific subtypes BEFORE general ones (e.g.
         * [MigrationException.HmacMismatch] before [GeneralSecurityException]).
         */
        fun from(throwable: Throwable): MigrationError = when (throwable) {
            // — domain-specific MigrationException subtypes —
            is MigrationException.SnapshotFailed -> SnapshotFailed
            is MigrationException.HmacMismatch -> HmacMismatch
            is MigrationException.DecryptionFailed -> FileCorrupted
            // A stream that ends early — peer hung up, link dropped, read timed out — is a
            // connection failure. A peer that left deliberately announces that separately.
            is MigrationException.StreamInterrupted -> ConnectionLost
            is MigrationException.SinkInterrupted -> ConnectionLost
            is MigrationException.EncryptionFailed -> EncryptionFailed
            // More specific RestoreAbortedCleanState BEFORE the broader RestoreFailed bucket.
            is MigrationException.RestoreAbortedCleanState -> RestoreAbortedCleanState
            is MigrationException.RestoreFailed -> AtomicWriteFailed
            is MigrationException.SignInFailed -> SignInFailed
            // — platform / cross-module exceptions —
            is WCDBKeyUnavailableException -> KeystoreUnavailable
            is CancellationException -> Cancelled
            is GeneralSecurityException -> EncryptionFailed
            is SecurityException -> SafPermissionLost
            is IOException -> when {
                throwable.message?.contains("space", ignoreCase = true) == true -> DiskSpaceInsufficient
                throwable.message?.contains("permission", ignoreCase = true) == true -> SafPermissionLost
                else -> SafWriteFailed
            }
            else -> Unknown
        }
    }
}

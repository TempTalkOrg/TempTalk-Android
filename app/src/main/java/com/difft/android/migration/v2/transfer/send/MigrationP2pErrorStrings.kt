package com.difft.android.migration.v2.transfer.send

import com.difft.android.R
import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.ui.component.MigrationResultTone
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.engine.upload.MigrationRejectReason

/**
 * What the failure page says, derived from the raw reason a transfer ended with. The user-facing
 * vocabulary is fixed: one situation per kind, each with a title, one sentence, and — for the blocks
 * that happen before anything moved — a warning rather than an error mark. Everything the engine can
 * report collapses onto this list; anything unrecognised gets the "unknown" sentence, never the raw
 * token.
 */
enum class MigrationFailureKind {
    CONNECTION, FOREGROUND, DEVICE, INTEGRITY, READ, SOURCE, SWITCH, UNKNOWN,
    /** Traffic-light blocks: nothing was transferred, so the page says "can't", not "failed". */
    TARGET_NOT_EMPTY, HANDOFF_DISABLED, LOCKED, PEER_CANCELLED,
    /** The pairing code went stale between scan and start. */
    EXPIRED,
    /** The Desktop never confirmed the account switch. */
    PEER_TIMEOUT,
    /** One end is too old for the other's protocol; the fix is an update, not a retry. */
    UPDATE_NEEDED,
    ;

    val tone: MigrationResultTone
        get() = when (this) {
            TARGET_NOT_EMPTY, HANDOFF_DISABLED, LOCKED, PEER_CANCELLED, UPDATE_NEEDED -> MigrationResultTone.WARNING
            else -> MigrationResultTone.ERROR
        }

    val titleRes: Int
        get() = when (this) {
            UPDATE_NEEDED -> R.string.migration_m3_title
            TARGET_NOT_EMPTY, HANDOFF_DISABLED, LOCKED, PEER_CANCELLED -> R.string.migration_p3_blocked_title
            else -> R.string.migration_p3_failed_title
        }

    /** The one sentence under "what happened". Kinds naming an app take the legacy app's name as `%1$s`. */
    val reasonRes: Int
        get() = when (this) {
            CONNECTION -> R.string.migration_p3_reason_conn
            FOREGROUND -> R.string.migration_p3_reason_fg
            DEVICE -> R.string.migration_p3_reason_device
            INTEGRITY -> R.string.migration_p3_reason_integrity
            READ -> R.string.migration_p3_reason_read
            SOURCE -> R.string.migration_p3_reason_source
            SWITCH -> R.string.migration_p3_reason_switch
            UNKNOWN -> R.string.migration_p3_reason_unknown
            TARGET_NOT_EMPTY -> R.string.migration_p3_reason_target_not_empty
            HANDOFF_DISABLED -> R.string.migration_p3_reason_handoff_disabled
            LOCKED -> R.string.migration_r2_locked
            PEER_CANCELLED -> R.string.migration_r2_desktop_cancelled
            EXPIRED -> R.string.migration_p3_reason_expired
            PEER_TIMEOUT -> R.string.migration_p3_reason_peer_timeout
            UPDATE_NEEDED -> R.string.migration_m3_body
        }

    /** Whether the sentence carries the app-name placeholder. */
    val namesApp: Boolean get() = this == TARGET_NOT_EMPTY
}

/**
 * Single owner of the mapping from a transfer failure reason to a [MigrationFailureKind], shared by
 * the receive screen, the send screen and the Desktop-login page. The two roles see a reject through
 * different transports — a `ServerEvent.Error` detail on the receive side, a thrown
 * `PrepareUploadRejectedException` message on the send side — but the same wire token is embedded as
 * a substring in both, so `contains` resolves on either path.
 */
internal fun classifyFailure(reason: String): MigrationFailureKind = when {
    // Local precondition, not a peer reject: no LAN address to advertise, so no peer could reach us.
    reason.contains(com.difft.android.migration.v2.transfer.login.NO_LAN_REASON) -> MigrationFailureKind.CONNECTION
    reason.contains(PrepareUploadReasons.PEER_UNREACHABLE) -> MigrationFailureKind.CONNECTION
    reason.contains(PAIRING_TIMEOUT) -> MigrationFailureKind.SOURCE
    // Identity: the two ends do not belong to the same account / environment, or the identity
    // material that travels with the account cannot be used.
    reason.contains(MigrationRejectReason.ENV_MISMATCH.wire) ||
    reason.contains(MigrationRejectReason.ACCOUNT_MISMATCH.wire) ||
    reason.contains(MigrationRejectReason.IDENTITY_MISMATCH.wire) ||
    reason.contains(MigrationError.AccountMismatch.code) ||
    reason.contains(MigrationError.IdentityInvalid.code) ||
    reason.contains(MigrationError.CredentialMissing.code) -> MigrationFailureKind.DEVICE
    // Capability: one end predates the other's protocol (version, handoff mode, session direction).
    reason.contains(MigrationRejectReason.VERSION_INCOMPATIBLE.wire) ||
    reason.contains(MigrationRejectReason.HANDOFF_UNSUPPORTED.wire) ||
    reason.contains(MigrationError.UnsupportedVersion.code) ||
    reason.contains(MigrationError.VersionTooNew.code) ||
    reason.contains(PrepareUploadReasons.INVALID_BODY) ||
    reason.contains(DIRECTION_MISMATCH) -> MigrationFailureKind.UPDATE_NEEDED
    reason.contains(MigrationRejectReason.HANDOFF_DISABLED.wire) -> MigrationFailureKind.HANDOFF_DISABLED
    reason.contains(MigrationRejectReason.TARGET_NOT_EMPTY.wire) ||
    reason.contains(MigrationError.TargetNotEmpty.code) -> MigrationFailureKind.TARGET_NOT_EMPTY
    // Pairing-code lifecycle.
    reason.contains(PrepareUploadReasons.PIN_REQUIRED) -> MigrationFailureKind.EXPIRED
    reason.contains(PrepareUploadReasons.TOO_MANY_SESSIONS) -> MigrationFailureKind.LOCKED
    // Desktop link, account switch.
    reason.contains(PrepareUploadReasons.PEER_CANCELLED) -> MigrationFailureKind.PEER_CANCELLED
    reason.contains(PrepareUploadReasons.PEER_CONFIRM_TIMEOUT) -> MigrationFailureKind.PEER_TIMEOUT
    // The peer said it left (its `/cancel`, or nobody claimed the code); a stream that stops before
    // its end is the link — iOS draws the same line. Neither is a verdict on the data.
    reason.contains(MigrationError.SourceUnavailable.code) -> MigrationFailureKind.SOURCE
    reason.contains(MigrationError.ConnectionLost.code) -> MigrationFailureKind.CONNECTION
    // The container itself: verification and writing on the receiving end, reading on the source.
    reason.contains(MigrationError.HmacMismatch.code) ||
    reason.contains(MigrationError.FileCorrupted.code) ||
    reason.contains(MigrationError.FileFormatInvalid.code) ||
    reason.contains(MigrationError.PassphraseInvalid.code) ||
    reason.contains(MigrationError.AtomicWriteFailed.code) ||
    reason.contains(MigrationError.KeystoreUnavailable.code) -> MigrationFailureKind.INTEGRITY
    reason.contains(MigrationError.SnapshotFailed.code) ||
    reason.contains(MigrationError.EncryptionFailed.code) -> MigrationFailureKind.READ
    reason.contains(MigrationError.DiskSpaceInsufficient.code) -> MigrationFailureKind.INTEGRITY
    reason.contains(MigrationError.SignInFailed.code) ||
    reason.contains(HANDOFF_NOT_ACKNOWLEDGED) -> MigrationFailureKind.SWITCH
    reason.contains(MigrationError.Cancelled.code) -> MigrationFailureKind.SOURCE
    else -> MigrationFailureKind.UNKNOWN
}

/** Wire token the Desktop returns (403, plain text) when the called endpoint does not match its session direction. */
internal const val DIRECTION_MISMATCH = "direction-mismatch"

/**
 * Sender-side: the handoff upload was answered 200 without the receiver saying it installed the
 * credential. A receiver that never understood the handoff answers exactly that, so the sender keeps
 * its session and reports the switch as not done rather than wiping itself.
 */
internal const val HANDOFF_NOT_ACKNOWLEDGED = "handoff-not-acknowledged"

/** The receiver gave up waiting for a peer to connect (`MigrationTransferService.REASON_PAIRING_TIMEOUT`). */
private const val PAIRING_TIMEOUT = "pairing timeout"

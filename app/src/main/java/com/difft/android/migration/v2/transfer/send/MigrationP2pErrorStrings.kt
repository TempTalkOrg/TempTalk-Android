package com.difft.android.migration.v2.transfer.send

import com.difft.android.R
import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.v2.pause.SenderPauseSession
import com.difft.android.migration.v2.transfer.ReceiverCapabilityMissingException
import com.difft.android.migration.ui.component.MigrationResultTone
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.engine.upload.MigrationRejectReason








enum class MigrationFailureKind {
    CONNECTION, FOREGROUND, DEVICE, INTEGRITY, READ, SOURCE, SWITCH, UNKNOWN,
    
    TARGET_NOT_EMPTY, HANDOFF_DISABLED, LOCKED, PEER_CANCELLED,
    
    EXPIRED,
    
    PEER_TIMEOUT,
    
    UPDATE_NEEDED,

    
    PEER_UPDATE_NEEDED,

    
    DESKTOP_NOT_CONNECTED,

    
    PAIR_TOKEN,

    
    STORAGE_FULL,

    



    RECEIVER_UPDATE_NEEDED,
    ;

    val tone: MigrationResultTone
        get() = when (this) {
            TARGET_NOT_EMPTY, HANDOFF_DISABLED, LOCKED, PEER_CANCELLED, UPDATE_NEEDED,
            PEER_UPDATE_NEEDED, DESKTOP_NOT_CONNECTED, PAIR_TOKEN, RECEIVER_UPDATE_NEEDED -> MigrationResultTone.WARNING
            else -> MigrationResultTone.ERROR
        }

    val titleRes: Int
        get() = when (this) {
            UPDATE_NEEDED, PEER_UPDATE_NEEDED, RECEIVER_UPDATE_NEEDED -> R.string.migration_m3_title
            TARGET_NOT_EMPTY, HANDOFF_DISABLED, LOCKED, PEER_CANCELLED,
            DESKTOP_NOT_CONNECTED, PAIR_TOKEN -> R.string.migration_p3_blocked_title
            else -> R.string.migration_p3_failed_title
        }

    
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
            PEER_UPDATE_NEEDED -> R.string.migration_p3_reason_update_desktop
            DESKTOP_NOT_CONNECTED -> R.string.migration_p3_reason_desktop_not_connected
            PAIR_TOKEN -> R.string.migration_p3_reason_pair_token
            STORAGE_FULL -> R.string.migration_p3_reason_storage_full
            RECEIVER_UPDATE_NEEDED -> R.string.migration_m3_body_new_device
        }

    
    val namesApp: Boolean get() = this == TARGET_NOT_EMPTY
}








internal fun classifyFailure(reason: String): MigrationFailureKind = when {
    
    
    MIGRATION_PAUSE_REASONS.any { reason.contains(it) } -> MigrationFailureKind.UNKNOWN
    
    reason.contains(ReceiverCapabilityMissingException.RECEIVER_UPDATE_REQUIRED) -> MigrationFailureKind.RECEIVER_UPDATE_NEEDED
    
    reason.contains(com.difft.android.migration.v2.transfer.login.NO_LAN_REASON) -> MigrationFailureKind.CONNECTION
    reason.contains(PrepareUploadReasons.PEER_UNREACHABLE) -> MigrationFailureKind.CONNECTION
    reason.contains(PAIRING_TIMEOUT) -> MigrationFailureKind.SOURCE
    
    
    reason.contains(MigrationRejectReason.ENV_MISMATCH.wire) ||
    reason.contains(MigrationRejectReason.ACCOUNT_MISMATCH.wire) ||
    reason.contains(MigrationRejectReason.IDENTITY_MISMATCH.wire) ||
    reason.contains(MigrationError.AccountMismatch.code) ||
    reason.contains(MigrationError.IdentityInvalid.code) ||
    reason.contains(MigrationError.CredentialMissing.code) -> MigrationFailureKind.DEVICE
    
    reason.contains(MigrationRejectReason.VERSION_INCOMPATIBLE.wire) ||
    reason.contains(MigrationRejectReason.HANDOFF_UNSUPPORTED.wire) ||
    reason.contains(MigrationError.UnsupportedVersion.code) ||
    reason.contains(MigrationError.VersionTooNew.code) ||
    reason.contains(PrepareUploadReasons.INVALID_BODY) ||
    reason.contains(DIRECTION_MISMATCH) -> MigrationFailureKind.UPDATE_NEEDED
    reason.contains(MigrationRejectReason.HANDOFF_DISABLED.wire) -> MigrationFailureKind.HANDOFF_DISABLED
    reason.contains(MigrationRejectReason.TARGET_NOT_EMPTY.wire) ||
    reason.contains(MigrationError.TargetNotEmpty.code) -> MigrationFailureKind.TARGET_NOT_EMPTY
    
    reason.contains(PrepareUploadReasons.PIN_REQUIRED) -> MigrationFailureKind.EXPIRED
    reason.contains(PrepareUploadReasons.TOO_MANY_SESSIONS) -> MigrationFailureKind.LOCKED
    
    reason.contains(PrepareUploadReasons.PEER_CANCELLED) -> MigrationFailureKind.PEER_CANCELLED
    reason.contains(PrepareUploadReasons.PEER_CONFIRM_TIMEOUT) -> MigrationFailureKind.PEER_TIMEOUT
    
    
    reason.contains(PrepareUploadReasons.UNSUPPORTED_PROTOCOL) -> MigrationFailureKind.PEER_UPDATE_NEEDED
    reason.contains(PrepareUploadReasons.DESKTOP_CANCELLED) -> MigrationFailureKind.PEER_CANCELLED
    reason.contains(PrepareUploadReasons.DESKTOP_NOT_CONNECTED) -> MigrationFailureKind.DESKTOP_NOT_CONNECTED
    reason.contains(MigrationRejectReason.PAIR_TOKEN.wire) -> MigrationFailureKind.PAIR_TOKEN
    reason.contains(MigrationRejectReason.INSUFFICIENT_STORAGE.wire) -> MigrationFailureKind.STORAGE_FULL
    
    
    reason.contains(MigrationError.SourceUnavailable.code) -> MigrationFailureKind.SOURCE
    reason.contains(MigrationError.ConnectionLost.code) -> MigrationFailureKind.CONNECTION
    
    reason.contains(MigrationError.HmacMismatch.code) ||
    reason.contains(MigrationError.FileCorrupted.code) ||
    reason.contains(MigrationError.FileFormatInvalid.code) ||
    reason.contains(MigrationError.PassphraseInvalid.code) ||
    reason.contains(MigrationError.AtomicWriteFailed.code) ||
    reason.contains(MigrationError.KeystoreUnavailable.code) -> MigrationFailureKind.INTEGRITY
    reason.contains(MigrationError.SnapshotFailed.code) ||
    reason.contains(MigrationError.EncryptionFailed.code) -> MigrationFailureKind.READ
    reason.contains(MigrationError.DiskSpaceInsufficient.code) -> MigrationFailureKind.STORAGE_FULL
    reason.contains(MigrationError.SignInFailed.code) ||
    reason.contains(HANDOFF_NOT_ACKNOWLEDGED) -> MigrationFailureKind.SWITCH
    reason.contains(MigrationError.Cancelled.code) -> MigrationFailureKind.SOURCE
    else -> MigrationFailureKind.UNKNOWN
}

private val MIGRATION_PAUSE_REASONS = listOf(
    SenderPauseSession.REASON_NO_LOGIN,
    SenderPauseSession.REASON_UNAVAILABLE,
    SenderPauseSession.REASON_QUIESCENCE_TIMEOUT,
    SenderPauseSession.REASON_COMMIT_FAILED,
    SenderPauseSession.REASON_RESIDUAL,
)


internal const val DIRECTION_MISMATCH = "direction-mismatch"






internal const val HANDOFF_NOT_ACKNOWLEDGED = "handoff-not-acknowledged"


private const val PAIRING_TIMEOUT = "pairing timeout"

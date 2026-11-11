package com.difft.android.migration.model

import com.difft.android.base.log.WCDBKeyUnavailableException
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException









sealed class MigrationError(val code: String) {
    
    
    object SnapshotFailed : MigrationError("MIG_SNAPSHOT_FAILED")

    
    object DiskSpaceInsufficient : MigrationError("MIG_DISK_SPACE_INSUFFICIENT")

    
    object SafWriteFailed : MigrationError("MIG_SAF_WRITE_FAILED")

    
    object SafPermissionLost : MigrationError("MIG_SAF_PERMISSION_LOST")

    
    object Cancelled : MigrationError("MIG_CANCELLED")

    
    object SourceUnavailable : MigrationError("MIG_SOURCE_UNAVAILABLE")

    
    object ConnectionLost : MigrationError("MIG_CONNECTION_LOST")

    
    object EncryptionFailed : MigrationError("MIG_ENCRYPT_FAILED")

    
    object PassphraseInvalid : MigrationError("MIG_PASSPHRASE_INVALID")
    object FileFormatInvalid : MigrationError("MIG_FILE_FORMAT_INVALID")
    object FileCorrupted : MigrationError("MIG_FILE_CORRUPTED")
    object HmacMismatch : MigrationError("MIG_HMAC_MISMATCH")
    object UnsupportedVersion : MigrationError("MIG_UNSUPPORTED_VERSION")

    




    object VersionTooNew : MigrationError("MIG_VERSION_TOO_NEW")

    object AccountMismatch : MigrationError("MIG_ACCOUNT_MISMATCH")
    object SignInFailed : MigrationError("MIG_SIGN_IN_FAILED")
    object KeystoreUnavailable : MigrationError("MIG_KEYSTORE_UNAVAILABLE")
    object AtomicWriteFailed : MigrationError("MIG_ATOMIC_WRITE_FAILED")

    




    object RestoreAbortedCleanState : MigrationError("MIG_RESTORE_ABORTED_CLEAN_STATE")

    




    object TargetNotEmpty : MigrationError("MIG_TARGET_NOT_EMPTY")

    




    object IdentityInvalid : MigrationError("MIG_IDENTITY_INVALID")

    




    object CredentialMissing : MigrationError("MIG_CREDENTIAL_MISSING")

    
    object Unknown : MigrationError("MIG_UNKNOWN")

    
    val isIoFailure: Boolean
        get() = this === DiskSpaceInsufficient || this === SafWriteFailed ||
                this === SafPermissionLost || this === AtomicWriteFailed

    companion object {
        




        fun from(throwable: Throwable): MigrationError = when (throwable) {
            
            is MigrationException.SnapshotFailed -> SnapshotFailed
            is MigrationException.HmacMismatch -> HmacMismatch
            is MigrationException.DecryptionFailed -> FileCorrupted
            
            
            is MigrationException.StreamInterrupted -> ConnectionLost
            is MigrationException.SinkInterrupted -> ConnectionLost
            is MigrationException.EncryptionFailed -> EncryptionFailed
            
            is MigrationException.OutOfSpace -> DiskSpaceInsufficient
            is MigrationException.RestoreAbortedCleanState -> RestoreAbortedCleanState
            is MigrationException.RestoreFailed -> AtomicWriteFailed
            is MigrationException.SignInFailed -> SignInFailed
            
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

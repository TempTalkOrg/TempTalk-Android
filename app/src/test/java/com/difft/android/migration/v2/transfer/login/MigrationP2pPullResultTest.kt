package com.difft.android.migration.v2.transfer.login

import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.v2.restore.RestoreResult
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `download-result` error token is the only way the Desktop learns WHY a pull failed, and it
 * must come from the wire set the three platforms share (统一由手机扫码 §4.4) — in particular
 * `identity-mismatch`, not our internal `MIG_IDENTITY_INVALID`. Anything the set has no name for is
 * reported as a plain failure (no token), never as a made-up one.
 */
class MigrationP2pPullResultTest {

    @Test
    fun `a promoted import carries no error`() {
        assertNull(downloadResultError(RestoreResult.ReadyForSignIn))
    }

    @Test
    fun `identity rejection maps to the shared identity-mismatch token`() {
        assertEquals("identity-mismatch", downloadResultError(RestoreResult.Rejected(MigrationError.IdentityInvalid)))
        assertEquals(DOWNLOAD_RESULT_IDENTITY_MISMATCH, downloadResultError(RestoreResult.Failed(MigrationError.IdentityInvalid)))
    }

    @Test
    fun `account and version failures reuse the gate tokens`() {
        assertEquals(
            MigrationRejectReason.ACCOUNT_MISMATCH.wire,
            downloadResultError(RestoreResult.Failed(MigrationError.AccountMismatch)),
        )
        assertEquals(
            MigrationRejectReason.VERSION_INCOMPATIBLE.wire,
            downloadResultError(RestoreResult.Failed(MigrationError.UnsupportedVersion)),
        )
        assertEquals(
            MigrationRejectReason.VERSION_INCOMPATIBLE.wire,
            downloadResultError(RestoreResult.Failed(MigrationError.VersionTooNew)),
        )
    }

    @Test
    fun `failures outside the shared set report no token`() {
        assertNull(downloadResultError(RestoreResult.Failed(MigrationError.HmacMismatch)))
        assertNull(downloadResultError(RestoreResult.Rejected(MigrationError.TargetNotEmpty)))
    }
}

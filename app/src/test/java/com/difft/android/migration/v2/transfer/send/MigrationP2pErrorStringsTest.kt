package com.difft.android.migration.v2.transfer.send

import com.difft.android.R
import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.ui.component.MigrationResultTone
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.android.client.PrepareUploadRejectedException
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The shared [classifyFailure] resolves every reason a transfer can end with to one of the twelve
 * situations the failure page knows (产品方案 §8.6 / 附录 A.7). The CRITICAL symmetry: on the OLD
 * device a gate reject arrives as a thrown [PrepareUploadRejectedException] whose message is
 * `"prepare-upload rejected: <wire>"`, on the NEW device as `"403 forbidden: <wire>"` — both carry
 * the wire token, so both resolve to the same kind.
 */
class MigrationP2pErrorStringsTest {

    @Test
    fun `gate rejects resolve to the same kind on both ends`() {
        listOf(MigrationRejectReason.ENV_MISMATCH, MigrationRejectReason.ACCOUNT_MISMATCH).forEach { reject ->
            assertEquals(MigrationFailureKind.DEVICE, classifyFailure("403 forbidden: ${reject.wire}"))
            assertEquals(MigrationFailureKind.DEVICE, classifyFailure(PrepareUploadRejectedException(reject.wire).message.orEmpty()))
        }
        assertEquals(
            MigrationFailureKind.UPDATE_NEEDED,
            classifyFailure(PrepareUploadRejectedException(MigrationRejectReason.VERSION_INCOMPATIBLE.wire).message.orEmpty()),
        )
        assertEquals(MigrationFailureKind.UPDATE_NEEDED, classifyFailure("403 forbidden: ${MigrationRejectReason.HANDOFF_UNSUPPORTED.wire}"))
    }

    @Test
    fun `pairing-code and desktop-confirmation outcomes keep their own kinds`() {
        assertEquals(MigrationFailureKind.EXPIRED, classifyFailure(PrepareUploadRejectedException(PrepareUploadReasons.PIN_REQUIRED).message.orEmpty()))
        assertEquals(MigrationFailureKind.LOCKED, classifyFailure(PrepareUploadRejectedException(PrepareUploadReasons.TOO_MANY_SESSIONS).message.orEmpty()))
        assertEquals(MigrationFailureKind.UPDATE_NEEDED, classifyFailure(PrepareUploadRejectedException(PrepareUploadReasons.INVALID_BODY).message.orEmpty()))
        assertEquals(MigrationFailureKind.CONNECTION, classifyFailure(PrepareUploadReasons.PEER_UNREACHABLE))
        assertEquals(MigrationFailureKind.PEER_CANCELLED, classifyFailure(PrepareUploadRejectedException(PrepareUploadReasons.PEER_CANCELLED).message.orEmpty()))
        assertEquals(MigrationFailureKind.PEER_TIMEOUT, classifyFailure(PrepareUploadRejectedException(PrepareUploadReasons.PEER_CONFIRM_TIMEOUT).message.orEmpty()))
        assertEquals(MigrationFailureKind.UPDATE_NEEDED, classifyFailure("403 $DIRECTION_MISMATCH"))
    }

    @Test
    fun `same-device handoff rejects are blocks, not failures`() {
        assertEquals(MigrationFailureKind.HANDOFF_DISABLED, classifyFailure("403 forbidden: ${MigrationRejectReason.HANDOFF_DISABLED.wire}"))
        assertEquals(MigrationFailureKind.TARGET_NOT_EMPTY, classifyFailure("403 forbidden: ${MigrationRejectReason.TARGET_NOT_EMPTY.wire}"))
        assertEquals(MigrationFailureKind.DEVICE, classifyFailure("rejected: ${MigrationError.CredentialMissing.code}"))
        assertEquals(MigrationResultTone.WARNING, MigrationFailureKind.HANDOFF_DISABLED.tone)
        assertEquals(R.string.migration_p3_blocked_title, MigrationFailureKind.TARGET_NOT_EMPTY.titleRes)
    }

    @Test
    fun `container and restore errors map to integrity read or switch`() {
        assertEquals(MigrationFailureKind.INTEGRITY, classifyFailure("failed: ${MigrationError.HmacMismatch.code}"))
        assertEquals(MigrationFailureKind.INTEGRITY, classifyFailure("rejected: ${MigrationError.FileCorrupted.code}"))
        assertEquals(MigrationFailureKind.DEVICE, classifyFailure("rejected: ${MigrationError.IdentityInvalid.code}"))
        assertEquals(MigrationFailureKind.READ, classifyFailure("failed: ${MigrationError.SnapshotFailed.code}"))
        assertEquals(MigrationFailureKind.SWITCH, classifyFailure("failed: ${MigrationError.SignInFailed.code}"))
        // A handoff answered 200 without the receiver's "installed" word: the switch did not happen.
        assertEquals(MigrationFailureKind.SWITCH, classifyFailure(HANDOFF_NOT_ACKNOWLEDGED))
        assertEquals(MigrationFailureKind.SOURCE, classifyFailure("pairing timeout"))
        // A stream that stops before its end is the peer or the link, never the data.
        assertEquals(MigrationFailureKind.SOURCE, classifyFailure("failed: ${MigrationError.SourceUnavailable.code}"))
        assertEquals(MigrationFailureKind.CONNECTION, classifyFailure("failed: ${MigrationError.ConnectionLost.code}"))
    }

    @Test
    fun `anything unrecognised is the unknown sentence with the failed title`() {
        val kind = classifyFailure("java.net.SocketException: Connection reset")
        assertEquals(MigrationFailureKind.UNKNOWN, kind)
        assertEquals(R.string.migration_p3_failed_title, kind.titleRes)
        assertEquals(R.string.migration_p3_reason_unknown, kind.reasonRes)
        assertEquals(MigrationResultTone.ERROR, kind.tone)
    }
}

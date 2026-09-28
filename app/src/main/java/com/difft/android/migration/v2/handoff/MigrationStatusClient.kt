package com.difft.android.migration.v2.handoff

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the peer package's [MigrationStatusProvider] the two questions of the same-device migration.
 *
 * Every failure mode — peer not installed, peer too old to ship the provider, provider crash,
 * timeout, malformed reply — collapses into [PeerStatusReply.Unavailable] or [PeerStatusReply.TooOld],
 * which every caller treats as "do not offer the no-login path"; no unclear answer is read
 * optimistically. The provider client is acquired unstable on purpose, so a crash inside the peer's
 * provider process cannot take this process down with it.
 */
@Singleton
class MigrationStatusClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** What the peer answered to [MigrationHandoffContract.QUERY_STATUS]. */
    sealed interface PeerStatusReply {
        /** The peer answered with the account material it holds. */
        data class Ok(val state: HandoffState) : PeerStatusReply

        /** Installed and trusted, but its protocol is below what this build can work with. */
        data object TooOld : PeerStatusReply

        /** Not installed, not trusted, unreachable, or it answered something unusable. */
        data object Unavailable : PeerStatusReply
    }

    /** Ask the peer what account material it holds. */
    suspend fun queryStatus(): PeerStatusReply = withContext(Dispatchers.IO) {
        if (peerShipsNoProvider()) return@withContext PeerStatusReply.TooOld
        val reply = call(
            Bundle().apply {
                putString(MigrationHandoffContract.KEY_QUERY, MigrationHandoffContract.QUERY_STATUS)
            },
        ) ?: return@withContext PeerStatusReply.Unavailable
        val protocol = reply.getInt(MigrationHandoffContract.KEY_PROTOCOL, 0)
        if (protocol < MigrationHandoffContract.MIN_SUPPORTED_PEER_PROTOCOL) {
            L.w { "[MigrationV2][Status] peer protocol=$protocol is too old" }
            return@withContext PeerStatusReply.TooOld
        }
        val state = reply.getString(MigrationHandoffContract.KEY_STATE)
            ?.let { name -> HandoffState.entries.firstOrNull { it.name == name } }
            ?: return@withContext PeerStatusReply.Unavailable
        L.i { "[MigrationV2][Status] peer state=$state" }
        PeerStatusReply.Ok(state)
    }

    /**
     * Ask the peer whether it adopted [handoffId]. Anything but an explicit COMPLETED is unknown, and
     * the caller decides whether that means clearing the id or keeping it to ask again.
     */
    suspend fun queryHandoff(handoffId: String): HandoffResult? = withContext(Dispatchers.IO) {
        if (handoffId.isBlank()) return@withContext HandoffResult.UNKNOWN
        val reply = call(
            Bundle().apply {
                putString(MigrationHandoffContract.KEY_QUERY, MigrationHandoffContract.QUERY_HANDOFF)
                putString(MigrationHandoffContract.KEY_HANDOFF_ID, handoffId)
            },
        ) ?: return@withContext null
        val protocol = reply.getInt(MigrationHandoffContract.KEY_PROTOCOL, 0)
        if (protocol < MigrationHandoffContract.MIN_SUPPORTED_PEER_PROTOCOL) return@withContext null
        val result = reply.getString(MigrationHandoffContract.KEY_HANDOFF_RESULT)
            ?.let { name -> HandoffResult.entries.firstOrNull { it.name == name } }
        L.i { "[MigrationV2][Status] peer handoff result=$result" }
        result
    }

    /** Tell the peer to drop the receiver it opened for us; nothing to read back. */
    suspend fun cancelReceive() = withContext(Dispatchers.IO) {
        call(Bundle().apply { putString(MigrationHandoffContract.KEY_QUERY, MigrationHandoffContract.QUERY_CANCEL_RECEIVE) })
        L.i { "[MigrationV2][Status] asked the peer to drop its idle receiver" }
    }

    /**
     * A trusted peer that registers no status provider is a build from before the same-device
     * migration existed — "too old", not "absent", since the user can update it. Resolved through the
     * package manager so a slow or crashing provider is never mistaken for an old one.
     */
    private fun peerShipsNoProvider(): Boolean {
        if (PeerApps.peerStatus(context) != PeerApps.PeerStatus.Trusted) return false
        val owner = runCatching {
            context.packageManager.resolveContentProvider(PeerApps.peerStatusAuthority(context), 0)?.packageName
        }.getOrNull()
        val missing = owner != PeerApps.peerPackage(context)
        if (missing) L.w { "[MigrationV2][Status] peer is installed and trusted but ships no status provider → too old" }
        return missing
    }

    /**
     * One bounded, exception-proof `call()` against the peer's provider. `null` means no usable
     * answer: no provider, a dead peer process, or a timeout.
     */
    private suspend fun call(extras: Bundle): Bundle? = withTimeoutOrNull(CALL_TIMEOUT_MS) {
        runCatching {
            val uri = Uri.parse("content://" + PeerApps.peerStatusAuthority(context))
            context.contentResolver.acquireUnstableContentProviderClient(uri)?.use { client ->
                client.call(MigrationHandoffContract.METHOD, null, extras)
            }
        }.getOrElse { e ->
            L.w { "[MigrationV2][Status] peer query failed: ${e.javaClass.simpleName}" }
            null
        }
    }

    private companion object {
        /** A local binder round trip; a peer that cannot answer in this long is treated as absent. */
        const val CALL_TIMEOUT_MS = 2_000L
    }
}

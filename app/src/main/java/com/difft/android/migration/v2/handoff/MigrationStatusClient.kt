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










@Singleton
class MigrationStatusClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    
    sealed interface PeerStatusReply {
        
        data class Ok(val state: HandoffState) : PeerStatusReply

        
        data object TooOld : PeerStatusReply

        
        data object Unavailable : PeerStatusReply
    }

    
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

    
    suspend fun cancelReceive() = withContext(Dispatchers.IO) {
        call(Bundle().apply { putString(MigrationHandoffContract.KEY_QUERY, MigrationHandoffContract.QUERY_CANCEL_RECEIVE) })
        L.i { "[MigrationV2][Status] asked the peer to drop its idle receiver" }
    }

    




    private fun peerShipsNoProvider(): Boolean {
        if (PeerApps.peerStatus(context) != PeerApps.PeerStatus.Trusted) return false
        val owner = runCatching {
            context.packageManager.resolveContentProvider(PeerApps.peerStatusAuthority(context), 0)?.packageName
        }.getOrNull()
        val missing = owner != PeerApps.peerPackage(context)
        if (missing) L.w { "[MigrationV2][Status] peer is installed and trusted but ships no status provider → too old" }
        return missing
    }

    



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
        
        const val CALL_TIMEOUT_MS = 2_000L
    }
}

package com.difft.android.migration.v2.handoff

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.storage.user.StorageBoundUserManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.difft.android.migration.v2.transfer.MigrationTransferService
import com.difft.android.migration.v2.transfer.MigrationTransferStateHolder
import com.difft.android.migration.v2.transfer.TransferState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
















class MigrationStatusProvider : ContentProvider() {

    



    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StatusEntryPoint {
        fun storageBoundUserManager(): StorageBoundUserManager
        fun handoffGate(): MigrationHandoffGate
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != MigrationHandoffContract.METHOD) {
            L.w { "[MigrationV2][Status] call refused: unknown method" }
            return null
        }
        val ctx = context ?: return null
        if (!PeerApps.trustedCaller(ctx, Binder.getCallingUid())) {
            L.w { "[MigrationV2][Status] call refused: caller is not the trusted peer" }
            return null
        }
        val entryPoint = runCatching {
            EntryPointAccessors.fromApplication(ctx.applicationContext, StatusEntryPoint::class.java)
        }.getOrElse { e ->
            L.w { "[MigrationV2][Status] dependency graph unavailable: ${e.message}" }
            return null
        }
        return when (extras?.getString(MigrationHandoffContract.KEY_QUERY)) {
            MigrationHandoffContract.QUERY_STATUS -> statusReply(entryPoint)
            MigrationHandoffContract.QUERY_HANDOFF ->
                handoffReply(entryPoint, extras.getString(MigrationHandoffContract.KEY_HANDOFF_ID).orEmpty())
            MigrationHandoffContract.QUERY_CANCEL_RECEIVE -> cancelIdleReceiver(ctx)
            else -> {
                L.w { "[MigrationV2][Status] call refused: unknown query" }
                null
            }
        }
    }

    private fun statusReply(entryPoint: StatusEntryPoint): Bundle {
        val gate = entryPoint.handoffGate()
        val warm = warmUp(entryPoint)
        val state = if (warm) gate.localState() else HandoffState.NONE
        L.i { "[MigrationV2][Status] status reply state=$state warm=$warm" }
        return Bundle().apply {
            putInt(MigrationHandoffContract.KEY_PROTOCOL, MigrationHandoffContract.PROTOCOL_VERSION)
            putString(MigrationHandoffContract.KEY_STATE, state.name)
        }
    }

    
    private fun cancelIdleReceiver(ctx: Context): Bundle {
        val state = MigrationTransferStateHolder.state.value
        val idle = state is TransferState.WaitingForPeer || state == TransferState.Starting
        L.i { "[MigrationV2][Status] cancel-receive from the peer: receiver ${state::class.simpleName} → ${if (idle) "stopping" else "left alone"}" }
        if (idle) MigrationTransferService.cancel(ctx)
        return Bundle().apply { putInt(MigrationHandoffContract.KEY_PROTOCOL, MigrationHandoffContract.PROTOCOL_VERSION) }
    }

    private fun handoffReply(entryPoint: StatusEntryPoint, handoffId: String): Bundle {
        val warm = warmUp(entryPoint)
        val adopted = entryPoint.storageBoundUserManager().getUserData()?.migrationHandoffCompletedId.orEmpty()
        
        
        val result = if (warm && handoffId.isNotBlank() && handoffId == adopted) {
            HandoffResult.COMPLETED
        } else {
            HandoffResult.UNKNOWN
        }
        L.i { "[MigrationV2][Status] handoff reply result=$result warm=$warm" }
        return Bundle().apply {
            putInt(MigrationHandoffContract.KEY_PROTOCOL, MigrationHandoffContract.PROTOCOL_VERSION)
            putString(MigrationHandoffContract.KEY_HANDOFF_RESULT, result.name)
        }
    }

    



    private fun warmUp(entryPoint: StatusEntryPoint): Boolean {
        val userManager = entryPoint.storageBoundUserManager()
        if (userManager.getUserData() != null) return true
        @Suppress("BanRunBlockingOutsideTests")
        val warmed = runBlocking {
            withTimeoutOrNull(WARM_UP_TIMEOUT_MS) {
                runCatching { userManager.warmUp() }.isSuccess
            }
        }
        if (warmed != true) {
            L.w { "[MigrationV2][Status] user snapshot not warm within ${WARM_UP_TIMEOUT_MS}ms — answering fail-closed" }
            return false
        }
        return userManager.getUserData() != null
    }

    private companion object {
        
        const val WARM_UP_TIMEOUT_MS = 2_000L
    }
}

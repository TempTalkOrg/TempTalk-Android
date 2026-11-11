package com.difft.android.migration.v2.login

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.migration.ui.component.MigrationGap
import com.difft.android.migration.ui.component.MigrationProgressBlock
import com.difft.android.migration.ui.component.MigrationResultBody
import com.difft.android.migration.ui.component.MigrationResultPage
import com.difft.android.migration.ui.component.MigrationResultTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.updateRoomNameAndAvatar
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton









@Singleton
class PostRestoreWarmUp @Inject constructor(
    private val groupUtil: GroupUtil,
    private val conversationSettingsManager: ConversationSettingsManager,
) {
    @VisibleForTesting
    internal var awaitContacts: suspend () -> Boolean = { ContactorUtil.awaitFirstSync() }

    
    @VisibleForTesting
    internal var nameRooms: suspend () -> Int = ::nameRoomsFromDirectory

    @VisibleForTesting
    internal var scope: CoroutineScope = appScope

    
    suspend fun run(timeoutMs: Long = TIMEOUT_MS): Boolean {
        val startedAt = System.nanoTime()
        val contacts = scope.async { runCatching { awaitContacts() }.getOrElse { false } }
        val groups = scope.async {
            runCatching { groupUtil.syncAllGroupAndAllGroupMembers(forceFetch = false) }.isSuccess
        }
        
        conversationSettingsManager.syncConversationSettings()
        val ready = withTimeoutOrNull(timeoutMs) { contacts.await() && groups.await() } ?: false
        
        
        val named = if (ready) {
            val remainingMs = timeoutMs - (System.nanoTime() - startedAt) / NANOS_PER_MILLI
            withTimeoutOrNull(remainingMs.coerceAtLeast(1)) { runCatching { nameRooms() }.getOrElse { -1 } }
        } else {
            null
        }
        L.i {
            "[MigrationV2][WarmUp] ready=$ready contacts=${contacts.peek()} groups=${groups.peek()} " +
                "namedRooms=${named ?: "skipped"} elapsedMs=${(System.nanoTime() - startedAt) / NANOS_PER_MILLI}"
        }
        return ready
    }

    private fun kotlinx.coroutines.Deferred<Boolean>.peek(): String =
        if (isCompleted && !isCancelled) getCompleted().toString() else "pending"

    companion object {
        const val TIMEOUT_MS = 5_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        
        private suspend fun nameRoomsFromDirectory(): Int = withContext(Dispatchers.IO) {
            val nameless = wcdb.room.getAllObjects(DBRoomModel.roomName.isNull().or(DBRoomModel.roomName.eq("")))
            nameless.forEach { it.updateRoomNameAndAvatar() }
            nameless.size
        }
    }
}


@Composable
fun PostRestoreWarmUpPage() {
    MigrationResultPage(
        tone = MigrationResultTone.INFO,
        title = stringResource(R.string.migration_warm_up_title),
    ) {
        MigrationProgressBlock(progress = null, metric = null)
        MigrationGap()
        MigrationResultBody(stringResource(R.string.migration_warm_up_body))
    }
}

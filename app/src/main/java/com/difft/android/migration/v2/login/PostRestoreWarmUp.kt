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

/**
 * The stop between a restore's first cold start and the chat list: the first contact and group
 * sync, awaited. A migrated install has the conversations but not the contactor or group tables —
 * the container carries neither, the server directory is their source — yet every friend check,
 * name and avatar reads them, so opening the list first shows a friend as a stranger. Bounded by
 * [TIMEOUT_MS]: whatever has not landed is left to finish in [appScope], never the caller's scope.
 * The syncs are the ones the chat list would start, guarded so its trigger joins the running one.
 */
@Singleton
class PostRestoreWarmUp @Inject constructor(
    private val groupUtil: GroupUtil,
    private val conversationSettingsManager: ConversationSettingsManager,
) {
    @VisibleForTesting
    internal var awaitContacts: suspend () -> Boolean = { ContactorUtil.awaitFirstSync() }

    /** Names the rooms the import left nameless; returns how many it visited. */
    @VisibleForTesting
    internal var nameRooms: suspend () -> Int = ::nameRoomsFromDirectory

    @VisibleForTesting
    internal var scope: CoroutineScope = appScope

    /** True when contacts and groups both landed in time; false when the chat list opens ahead of them. */
    suspend fun run(timeoutMs: Long = TIMEOUT_MS): Boolean {
        val startedAt = System.nanoTime()
        val contacts = scope.async { runCatching { awaitContacts() }.getOrElse { false } }
        val groups = scope.async {
            runCatching { groupUtil.syncAllGroupAndAllGroupMembers(forceFetch = false, syncMembers = true) }.isSuccess
        }
        // Fire-and-forget by design; the imported room rows already carry these values as the fallback.
        conversationSettingsManager.syncConversationSettings()
        val ready = withTimeoutOrNull(timeoutMs) { contacts.await() && groups.await() } ?: false
        // Imported rooms carry no names (directory data); the chat list hides nameless rooms, so
        // name them here once contacts and groups are in, within the same bound.
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

        /** Names every still-nameless room, as the chat list's listener would. */
        private suspend fun nameRoomsFromDirectory(): Int = withContext(Dispatchers.IO) {
            val nameless = wcdb.room.getAllObjects(DBRoomModel.roomName.isNull().or(DBRoomModel.roomName.eq("")))
            nameless.forEach { it.updateRoomNameAndAvatar() }
            nameless.size
        }
    }
}

/** What the user sees while [PostRestoreWarmUp] runs; same copy as the iOS reconciliation page. */
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

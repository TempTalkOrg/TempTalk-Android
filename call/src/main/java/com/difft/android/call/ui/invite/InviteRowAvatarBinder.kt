package com.difft.android.call.ui.invite

import android.view.ViewGroup
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.AvatarViewBinder
import com.difft.android.call.LCallManager
import com.difft.android.call.data.AvatarData
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Avatar slot of a meeting-invite row. Owns the single reusable avatar View added to the row
 * container once at holder creation, binds an immediate value synchronously on every bind, and
 * replaces it with the participant cache's record when that arrives.
 *
 * Collaborators are constructor parameters so the recycling rules can be tested without Hilt;
 * production rows use [create].
 */
internal class InviteRowAvatarBinder(
    container: ViewGroup,
    private val scope: CoroutineScope,
    private val logTag: String,
    private val avatarBinder: AvatarViewBinder,
    private val fetchAvatarData: suspend (String) -> AvatarData?,
) {

    init {
        container.addView(
            avatarBinder.view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private var job: Job? = null
    private var boundUid: String? = null

    /**
     * Binds the row to [uid]. [immediate] renders synchronously from the data the row already
     * holds; the cached record replaces it when it arrives, unless the holder was rebound to
     * another participant meanwhile. On failure the immediate value stays on screen.
     */
    fun bind(uid: String, immediate: AvatarData) {
        job?.cancel()
        boundUid = uid
        avatarBinder.bind(immediate)
        job = scope.launch {
            val data = try {
                fetchAvatarData(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[$logTag] loadAvatar failed uid=$uid: ${e.stackTraceToString()}" }
                null
            } ?: return@launch
            withContext(Dispatchers.Main) {
                // An equal record would only restart the load the immediate bind already started
                // (a duplicate download while the first is still in flight); skip it here only.
                if (boundUid == uid && data != immediate) avatarBinder.bind(data)
            }
        }
    }

    /** Call from `onViewRecycled`: stops the pending fetch and forgets the bound uid so a late result can never land on a pooled row. */
    fun cancel() {
        job?.cancel()
        job = null
        boundUid = null
    }

    companion object {
        /** Wires the binder to the app's avatar chain and the participant cache. */
        fun create(container: ViewGroup, scope: CoroutineScope, logTag: String): InviteRowAvatarBinder {
            val entryPoint = EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(
                ApplicationHelper.instance,
            )
            return InviteRowAvatarBinder(
                container = container,
                scope = scope,
                logTag = logTag,
                avatarBinder = entryPoint.callToChatController.createAvatarViewBinder(container.context),
                fetchAvatarData = { uid ->
                    entryPoint.contactorCacheManager.getParticipantDisplayInfo(uid).avatarData
                },
            )
        }
    }
}

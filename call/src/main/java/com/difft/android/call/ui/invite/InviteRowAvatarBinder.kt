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
                
                
                if (boundUid == uid && data != immediate) avatarBinder.bind(data)
            }
        }
    }

    
    fun cancel() {
        job?.cancel()
        job = null
        boundUid = null
    }

    companion object {
        
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

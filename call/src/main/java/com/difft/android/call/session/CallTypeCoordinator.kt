package com.difft.android.call.session

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.call.CallIntent
import com.difft.android.call.R
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.RoomMetadataPatch
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import io.livekit.android.room.Room
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json



internal class CallTypeCoordinator(
    private val scope: CoroutineScope,
    
    
    
    
    
    
    
    
    
    private val roomProvider: () -> Room?,
    private val roomCtl: CallRoomController,
    private val callDataManager: CallDataManager,
    private val contactorCacheManager: ContactorCacheManager,
    private val callIntent: CallIntent,
    private val callRole: CallRole,
    private val mySelfId: String,
    private val json: Json,
    private val roomIdGetter: () -> String?,
) {

    
    var currentRoomName: String = callIntent.roomName
        private set

    




    private var mySelfDisplayName: String? = null

    




    private var renamedToInstant = false

    







    private var prefetchJob: Job? = null
    private var metadataJob: Job? = null

    



    fun start() {
        if (callRole == CallRole.CALLER) prefetchJob = scope.launch { prefetchMyDisplayName() }
        val room = roomProvider() ?: return
        metadataJob = scope.launch {
            room::metadata.flow
                .filterNotNull()
                .collect { raw -> if (raw.isNotBlank()) applyMetadata(raw) }
        }
    }

    





    fun cancelJobs() {
        prefetchJob?.cancel()
        prefetchJob = null
        metadataJob?.cancel()
        metadataJob = null
    }

    









    fun resolveNow() {
        val room = roomProvider() ?: return
        val raw = room.metadata?.takeIf { it.isNotBlank() }
        if (raw != null) applyMetadata(raw) else applyCallType(null)
    }

    













    fun applyInviteUpgrade() = land(CallType.INSTANT)

    private fun applyMetadata(raw: String) {
        val patch = runCatching { json.decodeFromString<RoomMetadataPatch>(raw) }
            .onFailure { e -> L.e(e) { "[Call] CallTypeCoordinator metadata parse failed" } }
            .getOrNull()
        
        
        if (patch != null) roomCtl.updateRoomMetadata(patch.mergeInto(roomCtl.roomMetadata.value))
        
        
        applyCallType(patch?.callType)
    }

    private fun applyCallType(serverCallType: String?) {
        
        
        val room = roomProvider() ?: return
        val resolved = CallTypeResolver.resolve(
            serverCallType = serverCallType,
            
            
            
            participantCount = room.remoteParticipants.size + 1,
            localCallType = callIntent.callType,
            currentCallType = roomCtl.callType.value,
        ) ?: return
        land(resolved)
    }

    private fun land(resolved: CallType) {
        val typeChanged = roomCtl.callType.value != resolved.type
        val becameInstant = typeChanged && resolved == CallType.INSTANT
        
        
        
        if (becameInstant) {
            renamedToInstant = true
            currentRoomName = instantRoomName()
        }
        if (typeChanged) {
            L.i { "[Call] CallTypeCoordinator callType ${roomCtl.callType.value} -> ${resolved.type}" }
            roomCtl.updateCallType(resolved.type)
        }
        
        
        
        writeBackCallData(resolved)
    }

    












    private fun writeBackCallData(resolved: CallType) = editCallData { data ->
        data.copy(
            type = resolved.type,
            
            
            callName = if (renamedToInstant) currentRoomName else data.callName,
        )
    }

    









    private fun editCallData(edit: (CallData) -> CallData) {
        val roomId = roomIdGetter() ?: return
        val callList = callDataManager.getCallListData()
        val callData = callList[roomId] ?: return
        val updated = edit(callData)
        if (updated == callData) return
        callDataManager.updateCallingListData(HashMap(callList).apply { put(roomId, updated) })
    }

    private suspend fun prefetchMyDisplayName() {
        mySelfDisplayName = contactorCacheManager.getDisplayNameById(mySelfId)
        
        
        if (renamedToInstant) {
            currentRoomName = instantRoomName()
            propagateRoomName(currentRoomName)
        }
    }

    




    private fun instantRoomName(): String {
        val suffix = getString(R.string.call_instant_call_title)
        return if (callRole == CallRole.CALLER) {
            mySelfDisplayName?.let { "$it$suffix" } ?: getString(R.string.call_instant_call_title_default)
        } else {
            "${callIntent.roomName}$suffix"
        }
    }

    




    private fun propagateRoomName(name: String) = editCallData { it.copy(callName = name) }
}

package com.difft.android.messageserialization.db.store

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.sampleAfterFirst
import org.difft.app.database.wcdb
import difft.android.messageserialization.model.Draft
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBDraftModel
import org.difft.app.database.models.DraftModel
import javax.inject.Inject
import javax.inject.Singleton

data class DraftWithTime(val draft: Draft, val updatedAt: Long)

@Singleton
class DraftRepository @Inject constructor(
    private val gson: Gson
) {
    
    private val _draftUpdates = MutableSharedFlow<Unit>(
        replay = 1,  
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ).apply { tryEmit(Unit) }

    val allDraftsFlow: Flow<Map<String, DraftWithTime>> = _draftUpdates
        .map {
            withContext(Dispatchers.IO) {
                wcdb.draft.allObjects.mapNotNull { entity ->
                    try {
                        val roomId = entity.roomId ?: return@mapNotNull null
                        val draft = gson.fromJson(entity.draftJson, Draft::class.java)
                        roomId to DraftWithTime(draft, entity.updatedAt)
                    } catch (e: Exception) {
                        L.e { "[DraftRepository] Error parsing draft: ${e.message}" }
                        null
                    }
                }.toMap().also {
                    L.d { "[DraftRepository] Queried ${it.size} drafts" }
                }
            }
        }
        .sampleAfterFirst(500)

    


    fun getDraft(roomId: String): Draft? {
        val entity = wcdb.draft.getFirstObject(DBDraftModel.roomId.eq(roomId)) ?: return null

        val json = entity.draftJson ?: return null
        return try {
            gson.fromJson(json, Draft::class.java)
        } catch (e: Exception) {
            L.w { "[DraftRepository] error: ${e.stackTraceToString()}" }
            null
        }
    }

    


    fun upsertDraft(roomId: String, draft: Draft) {
        if (draft.content.isNullOrEmpty()) {
            clearDraft(roomId)
            return
        }
        val draftJson = gson.toJson(draft)
        val newDraft = DraftModel().apply {
            this.roomId = roomId
            this.draftJson = draftJson
        }
        val currentDraft = wcdb.draft.getFirstObject(DBDraftModel.roomId.eq(roomId))?.draftJson
        if (currentDraft == draftJson) {
            L.i { "[DraftRepository] The same draft content, no need to update" }
            return
        }
        L.i { "[DraftRepository] Update draft for room:${roomId}" }
        newDraft.updatedAt = System.currentTimeMillis()
        wcdb.draft.insertOrReplaceObject(newDraft)

        _draftUpdates.tryEmit(Unit)
        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
        L.d { "[DraftRepository] Draft updated, emitted notifications" }
    }

    


    fun clearDraft(roomId: String) {
        wcdb.draft.deleteObjects(DBDraftModel.roomId.eq(roomId))

        _draftUpdates.tryEmit(Unit)
        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
        L.d { "[DraftRepository] Draft cleared for $roomId, emitted notifications" }
    }
}

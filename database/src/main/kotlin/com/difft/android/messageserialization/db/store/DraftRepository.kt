package com.difft.android.messageserialization.db.store

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.sampleAfterFirst
import org.difft.app.database.wcdb
import difft.android.messageserialization.model.Draft
import com.google.gson.Gson
import com.tencent.wcdb.base.Value
import kotlinx.coroutines.flow.Flow
import org.difft.app.database.models.DBDraftModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.DraftModel
import org.difft.app.database.observeTable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftRepository @Inject constructor(
    private val gson: Gson
) {
    val allDraftsFlow: Flow<Map<String, Draft>> = observeTable({ wcdb.draft }) {
        allObjects.mapNotNull { entity ->
            try {
                entity.roomId to gson.fromJson(entity.draftJson, Draft::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }.toMap()
    }.sampleAfterFirst(500)

    /**
     * Get the draft for a room (synchronously).
     */
    fun getDraft(roomId: String): Draft? {
        val entity = wcdb.draft.getFirstObject(DBDraftModel.roomId.eq(roomId)) ?: return null

        val json = entity.draftJson ?: return null
        return try {
            gson.fromJson(json, Draft::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Upsert (create or update) the Draft for a room.
     */
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
        val currentDraft = wcdb.draft.getFirstObject(DBRoomModel.roomId.eq(roomId))?.draftJson
        if (currentDraft == draftJson) {// 如果草稿内容没有变化，则不更新
            L.i { "[DraftRepository] The same draft content, no need to update" }
            return
        }
        L.i { "[DraftRepository] Update draft for room:${roomId}" }
        wcdb.draft.insertOrReplaceObject(newDraft)
        //同时更新会话的lastActiveTime，以便能排在前面
        wcdb.room.updateValue(
            Value(System.currentTimeMillis()),
            DBRoomModel.lastActiveTime,
            DBRoomModel.roomId.eq(roomId)
        )
    }

    /**
     * Clear the draft for a particular room.
     */
    fun clearDraft(roomId: String) {
        wcdb.draft.deleteObjects(DBDraftModel.roomId.eq(roomId))
    }
}
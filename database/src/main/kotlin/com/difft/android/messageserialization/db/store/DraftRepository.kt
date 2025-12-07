package com.difft.android.messageserialization.db.store

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.sampleAfterFirst
import org.difft.app.database.wcdb
import difft.android.messageserialization.model.Draft
import com.google.gson.Gson
import com.tencent.wcdb.base.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBDraftModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.DraftModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftRepository @Inject constructor(
    private val gson: Gson
) {
    // ✅ 独立的draft更新通知，避免每次room变化都查询draft
    private val _draftUpdates = MutableSharedFlow<Unit>(
        replay = 1,  // replay=1 确保新订阅者能立即收到
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ).apply { tryEmit(Unit) }  // 初始emit

    val allDraftsFlow: Flow<Map<String, Draft>> = _draftUpdates
        .map {
            withContext(Dispatchers.IO) {
                wcdb.draft.allObjects.mapNotNull { entity ->
                    try {
                        entity.roomId to gson.fromJson(entity.draftJson, Draft::class.java)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        L.e { "[DraftRepository] Error parsing draft: ${e.message}" }
                        null
                    }
                }.toMap().also {
                    L.d { "[DraftRepository] Queried ${it.size} drafts" }
                }
            }
        }
        .sampleAfterFirst(500)

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

        // ✅ 触发draft查询（独立）
        _draftUpdates.tryEmit(Unit)
        // ✅ 触发room刷新（因为更新了 lastActiveTime）
        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
        L.d { "[DraftRepository] Draft updated, emitted notifications" }
    }

    /**
     * Clear the draft for a particular room.
     */
    fun clearDraft(roomId: String) {
        wcdb.draft.deleteObjects(DBDraftModel.roomId.eq(roomId))

        // ✅ 触发draft查询（独立）
        _draftUpdates.tryEmit(Unit)
        // ✅ 触发room刷新
        RoomChangeTracker.trackRoom(roomId, RoomChangeType.REFRESH)
        L.d { "[DraftRepository] Draft cleared for $roomId, emitted notifications" }
    }
}
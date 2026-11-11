package com.difft.android.chat.attachment.videoprobe

import com.difft.android.chat.media.findAttachmentRowByUriKey
import com.tencent.wcdb.base.Value
import difft.android.messageserialization.model.VideoProbeState
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.wcdb
import javax.inject.Inject


data class VideoProbeRow(
    val databaseId: Int,
    val width: Int,
    val height: Int,
    val videoProbeState: Int,
)





interface VideoProbeStore {
    
    fun resolve(key: String, fileName: String): VideoProbeRow?

    fun read(databaseId: Int): VideoProbeRow?

    fun writeState(databaseId: Int, state: Int)

    



    fun writeFailedIfState(databaseId: Int, expectedState: Int)

    
    fun writeSize(databaseId: Int, width: Int, height: Int)
}

class WcdbVideoProbeStore @Inject constructor() : VideoProbeStore {

    override fun resolve(key: String, fileName: String): VideoProbeRow? =
        findAttachmentRowByUriKey(key, fileName)?.toProbeRow()

    override fun read(databaseId: Int): VideoProbeRow? =
        wcdb.attachment.getFirstObject(DBAttachmentModel.databaseId.eq(databaseId))?.toProbeRow()

    override fun writeState(databaseId: Int, state: Int) {
        wcdb.attachment.updateValue(state, DBAttachmentModel.videoProbeState, DBAttachmentModel.databaseId.eq(databaseId))
    }

    override fun writeFailedIfState(databaseId: Int, expectedState: Int) {
        wcdb.attachment.updateValue(
            VideoProbeState.FAILED,
            DBAttachmentModel.videoProbeState,
            DBAttachmentModel.databaseId.eq(databaseId).and(DBAttachmentModel.videoProbeState.eq(expectedState))
        )
    }

    override fun writeSize(databaseId: Int, width: Int, height: Int) {
        wcdb.attachment.updateRow(
            arrayOf(Value(width), Value(height), Value(VideoProbeState.NONE)),
            arrayOf(DBAttachmentModel.width, DBAttachmentModel.height, DBAttachmentModel.videoProbeState),
            DBAttachmentModel.databaseId.eq(databaseId)
        )
    }

    private fun AttachmentModel.toProbeRow() = VideoProbeRow(databaseId, width, height, videoProbeState)
}

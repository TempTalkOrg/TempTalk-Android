package org.difft.app.database

import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.VideoProbeState
import org.difft.app.database.models.AttachmentModel
import org.junit.Assert.assertEquals
import org.junit.Test





class AttachmentVideoProbeMapperTest {

    @Test
    fun `read maps the stored probe state`() {
        val row = AttachmentModel().also {
            it.localId = "local-1"
            it.contentType = "video/quicktime"
            it.videoProbeState = VideoProbeState.FAILED
        }

        assertEquals(VideoProbeState.FAILED, row.toAttachment().videoProbeState)
    }

    @Test
    fun `write leaves the column at its default for both owners`() {
        val failed = Attachment(
            id = "",
            authorityId = 0L,
            contentType = "video/quicktime",
            key = null,
            size = 0,
            thumbnail = null,
            digest = null,
            fileName = "clip.mov",
            flags = 0,
            width = 0,
            height = 0,
            path = null,
            status = 3,
            videoProbeState = VideoProbeState.FAILED,
        )

        assertEquals(VideoProbeState.NONE, failed.toAttachmentModel("msg-1").videoProbeState)
        assertEquals(VideoProbeState.NONE, failed.toAttachmentModel(forwardDatabaseId = 7L).videoProbeState)
    }
}

package com.difft.android.chat.jobs

import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test





class DownloadAttachmentJobSerializationTest {

    private val fileKey = ByteArray(64) { it.toByte() }

    private fun newJob() = DownloadAttachmentJob(
        localId = "local-copy-1",
        messageId = "msg-1",
        attachmentId = "server-id",
        filePath = "/root/attachment/local-copy-1/photo.jpg",
        authorizedId = 777L,
        fileKey = fileKey,
        autoSave = true
    )

    private fun legacyData(): Data = Data.Builder()
        .putString("message_id", "msg-1")
        .putString("attachment_id", "server-id")
        .putString("file_path", "/root/attachment/999/photo.jpg")
        .putLong("authorized_id", 777L)
        .putByteArray("file_key", fileKey)
        
        .putBoolean("should_decrypt", true)
        .putBoolean("auto_save", false)
        .build()

    private fun recreate(data: Data): DownloadAttachmentJob =
        DownloadAttachmentJob.Factory().create(Job.Parameters.Builder().build(), data)

    @Test
    fun `a new job round-trips every field including the localId`() {
        val data = recreate(newJob().serialize()).serialize()

        assertEquals("local-copy-1", data.getString("local_id"))
        assertEquals("msg-1", data.getString("message_id"))
        assertEquals("server-id", data.getString("attachment_id"))
        assertEquals("/root/attachment/local-copy-1/photo.jpg", data.getString("file_path"))
        assertEquals(777L, data.getLong("authorized_id"))
        assertArrayEquals(fileKey, data.getByteArray("file_key"))
        assertEquals(true, data.getBoolean("auto_save"))
    }

    @Test
    fun `a job persisted before the localId key deserializes and keeps its remaining fields`() {
        val data = recreate(legacyData()).serialize()

        assertFalse(data.hasString("local_id"))
        assertEquals("msg-1", data.getString("message_id"))
        assertEquals("server-id", data.getString("attachment_id"))
        
        assertEquals("/root/attachment/999/photo.jpg", data.getString("file_path"))
        assertEquals(777L, data.getLong("authorized_id"))
        assertArrayEquals(fileKey, data.getByteArray("file_key"))
        
        assertFalse(data.hasBoolean("should_decrypt"))
        assertEquals(false, data.getBoolean("auto_save"))
    }

    @Test
    fun `an empty localId is not persisted as a key`() {
        val data = DownloadAttachmentJob(
            localId = "",
            messageId = "msg-1",
            attachmentId = "server-id",
            filePath = "/root/attachment/msg-1/photo.jpg",
            authorizedId = 777L,
            fileKey = fileKey,
            autoSave = false
        ).serialize()

        assertFalse(data.hasString("local_id"))
        assertTrue(data.hasString("message_id"))
    }
}

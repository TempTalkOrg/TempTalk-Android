package com.difft.android.chat.jobs

import difft.android.messageserialization.model.Attachment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.difft.android.chat.attachment.AttachmentPathResolver
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.difft.android.chat.messages.TestScopeApplication

/**
 * The resend action asks [AttachmentSendIdentity.hasUploadSource] before queueing a send, and the
 * send job uses [AttachmentSendIdentity.uploadSourcePath] itself, so the two cannot disagree. Only an
 * attachment that never uploaded and has no file left is unsendable (issue #1378).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [34])
class AttachmentSendIdentityUploadSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val identity = AttachmentSendIdentity("m-1")

    private fun attachment(authorityId: Long, path: String? = null, fileName: String? = null) = Attachment(
        id = "",
        authorityId = authorityId,
        contentType = "audio/aac",
        key = null,
        size = 10,
        thumbnail = null,
        digest = null,
        fileName = fileName,
        flags = 1,
        width = 0,
        height = 0,
        path = path,
        status = 0,
    )

    @Test
    fun `an uploaded attachment needs no source and can be resent`() {
        assertTrue(identity.hasUploadSource(attachment(authorityId = 42L)))
    }

    @Test
    fun `a never-uploaded attachment whose file is still on disk can be resent`() {
        val file = tmp.newFile("voice.m4a").apply { writeBytes(ByteArray(8)) }
        assertTrue(identity.hasUploadSource(attachment(authorityId = 0L, path = file.path)))
    }

    @Test
    fun `a never-uploaded attachment with no file left cannot be resent`() {
        assertFalse(identity.hasUploadSource(attachment(authorityId = 0L)))
        assertFalse(identity.hasUploadSource(attachment(authorityId = 0L, path = tmp.root.path + "/gone.m4a")))
    }

    @Test
    fun `a never-uploaded attachment whose staged file is still at its address can be resent`() {
        // The ordinary failed send: a resend re-reads the row (no transient path), so the staged
        // file is found at the copy's own address.
        val staged = attachment(authorityId = 0L, fileName = "voice.m4a")
        File(AttachmentPathResolver.fileFor(staged)).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }
        assertTrue(identity.hasUploadSource(staged))
    }

    @Test
    fun `a never-uploaded attachment whose staged file is gone cannot be resent`() {
        // The #1378 shape: a migrated row that names its file, with nothing at the copy's address.
        assertFalse(identity.hasUploadSource(attachment(authorityId = 0L, fileName = "voice.m4a")))
    }
}

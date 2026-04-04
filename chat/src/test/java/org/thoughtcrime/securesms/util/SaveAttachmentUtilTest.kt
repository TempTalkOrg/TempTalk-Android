package org.thoughtcrime.securesms.util

import android.net.Uri
import com.difft.android.base.utils.FileUtil
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.mms.PartAuthority

/**
 * Unit tests for [SaveAttachmentUtil].
 *
 * Tests focus on public API behavior — permission checks, null stream handling,
 * and batch logic. Full MediaStore integration (ContentProvider insert/query)
 * is not covered here as it requires Android instrumented tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SaveAttachmentUtilTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        mockkStatic(FileUtil::class)
        mockkStatic(PartAuthority::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // region saveAttachment - permission check

    @Test
    fun `saveAttachment returns WriteAccessFailure when no write permission`() = runTest {
        every { FileUtil.canWriteToMediaStore() } returns false

        val result = SaveAttachmentUtil.saveAttachment(context, buildAttachment())

        assertTrue(result is SaveAttachmentUtil.SaveResult.WriteAccessFailure)
    }

    @Test
    fun `saveAttachments returns WriteAccessFailure when no write permission`() = runTest {
        every { FileUtil.canWriteToMediaStore() } returns false

        val result = SaveAttachmentUtil.saveAttachments(context, listOf(buildAttachment(), buildAttachment()))

        assertTrue(result is SaveAttachmentUtil.SaveResult.WriteAccessFailure)
    }

    // endregion

    // region saveAttachment - null stream

    @Test
    fun `saveAttachment returns Failure when input stream is null`() = runTest {
        every { FileUtil.canWriteToMediaStore() } returns true
        every { PartAuthority.getAttachmentStream(any(), any()) } returns null

        val result = SaveAttachmentUtil.saveAttachment(context, buildAttachment(contentType = "image/jpeg"))

        assertTrue(result is SaveAttachmentUtil.SaveResult.Failure)
    }

    // endregion

    // region saveAttachments - batch failure propagation

    @Test
    fun `saveAttachments returns Failure when all streams are null`() = runTest {
        every { FileUtil.canWriteToMediaStore() } returns true
        every { PartAuthority.getAttachmentStream(any(), any()) } returns null

        val result = SaveAttachmentUtil.saveAttachments(context, listOf(
            buildAttachment(contentType = "image/jpeg"),
            buildAttachment(contentType = "image/png")
        ))

        assertTrue(result is SaveAttachmentUtil.SaveResult.Failure)
    }

    @Test
    fun `saveAttachments returns Failure on empty list treated as success with null`() = runTest {
        every { FileUtil.canWriteToMediaStore() } returns true

        val result = SaveAttachmentUtil.saveAttachments(context, emptyList())

        // Empty list: loop doesn't execute, returns Success(null) since size != 1
        assertTrue(result is SaveAttachmentUtil.SaveResult.Success)
        assertEquals(null, (result as SaveAttachmentUtil.SaveResult.Success).attachment)
    }

    // endregion

    // region Attachment data class

    @Test
    fun `Attachment defaults are correct`() {
        val attachment = SaveAttachmentUtil.Attachment(
            uri = Uri.parse("content://test/1"),
            contentType = "image/jpeg",
            date = 1000L
        )

        assertEquals(null, attachment.fileName)
        assertEquals(false, attachment.shouldDeleteOriginalFile)
        assertEquals(true, attachment.shouldShowToast)
    }

    @Test
    fun `Attachment preserves all fields`() {
        val uri = Uri.parse("content://test/2")
        val attachment = SaveAttachmentUtil.Attachment(
            uri = uri,
            contentType = "video/mp4",
            date = 2000L,
            fileName = "video.mp4",
            shouldDeleteOriginalFile = true,
            shouldShowToast = false
        )

        assertEquals(uri, attachment.uri)
        assertEquals("video/mp4", attachment.contentType)
        assertEquals(2000L, attachment.date)
        assertEquals("video.mp4", attachment.fileName)
        assertEquals(true, attachment.shouldDeleteOriginalFile)
        assertEquals(false, attachment.shouldShowToast)
    }

    // endregion

    // region SaveResult sealed class

    @Test
    fun `SaveResult Success holds attachment`() {
        val attachment = buildAttachment()
        val result = SaveAttachmentUtil.SaveResult.Success(attachment)
        assertEquals(attachment, result.attachment)
    }

    @Test
    fun `SaveResult Success can hold null attachment for batch`() {
        val result = SaveAttachmentUtil.SaveResult.Success(null)
        assertEquals(null, result.attachment)
    }

    // endregion

    // region helpers

    private fun buildAttachment(
        uri: Uri = Uri.parse("content://test/attachment/${System.nanoTime()}"),
        contentType: String = "image/jpeg",
        date: Long = System.currentTimeMillis(),
        fileName: String? = null,
        shouldDeleteOriginalFile: Boolean = false,
        shouldShowToast: Boolean = false
    ) = SaveAttachmentUtil.Attachment(
        uri = uri,
        contentType = contentType,
        date = date,
        fileName = fileName,
        shouldDeleteOriginalFile = shouldDeleteOriginalFile,
        shouldShowToast = shouldShowToast
    )

    // endregion
}

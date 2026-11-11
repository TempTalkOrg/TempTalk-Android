package difft.android.messageserialization.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test








class AttachmentVideoTest {

    private fun attachment(
        contentType: String,
        width: Int = 0,
        height: Int = 0,
        videoProbeState: Int = VideoProbeState.NONE,
    ) = Attachment(
        id = "",
        authorityId = 0L,
        contentType = contentType,
        key = null,
        size = 0,
        thumbnail = null,
        digest = null,
        fileName = "file",
        flags = 0,
        width = width,
        height = height,
        path = null,
        status = 0,
        videoProbeState = videoProbeState,
    )

    @Test
    fun `whitelist holds exactly the shared five types`() {
        listOf("video/3gpp", "video/3gpp2", "video/mp4", "video/quicktime", "video/x-m4v").forEach {
            assertTrue(it, isWhitelistedVideoType(it))
        }
    }

    @Test
    fun `T4 T5 mpeg webm ogg and matroska are not whitelisted although they contain video`() {
        listOf("video/mpeg", "video/webm", "video/ogg", "video/x-matroska", "video/avi").forEach {
            assertTrue("old contains(\"video\") rule matched $it", it.contains("video"))
            assertFalse(it, isWhitelistedVideoType(it))
        }
    }

    @Test
    fun `T6 parameters are dropped`() {
        assertTrue(isWhitelistedVideoType("video/mp4; codecs=avc1"))
        assertTrue(isWhitelistedVideoType("video/quicktime;charset=binary"))
        assertEquals("video/mp4", normalizeContentType("video/mp4; codecs=\"avc1.42E01E, mp4a.40.2\""))
    }

    @Test
    fun `T28 padding and case are normalized`() {
        assertTrue(isWhitelistedVideoType(" VIDEO/MP4 "))
        assertEquals("video/mp4", normalizeContentType(" VIDEO/MP4 "))
        assertTrue(attachment(" VIDEO/MP4 ", width = 640, height = 360).isPlayableVideo())
    }

    @Test
    fun `non-video and empty types are not whitelisted`() {
        listOf("", "image/jpeg", "audio/mp4", "application/mp4", "text/x-signal-plain").forEach {
            assertFalse(it, isWhitelistedVideoType(it))
        }
        assertFalse(isWhitelistedVideoType(null))
    }

    @Test
    fun `T1 sized whitelisted video is playable`() {
        assertTrue(attachment("video/mp4", width = 1280, height = 720).isPlayableVideo())
    }

    @Test
    fun `unsized whitelisted video is not playable in any non-failed state`() {
        listOf(VideoProbeState.NONE, VideoProbeState.IN_PROGRESS_1, VideoProbeState.IN_PROGRESS_2).forEach {
            assertFalse(attachment("video/quicktime", videoProbeState = it).isPlayableVideo())
            assertFalse(attachment("video/quicktime", width = 640, videoProbeState = it).isPlayableVideo())
            assertFalse(attachment("video/quicktime", height = 640, videoProbeState = it).isPlayableVideo())
        }
    }

    @Test
    fun `probe failure wins over a size`() {
        assertFalse(attachment("video/mp4", width = 640, height = 360, videoProbeState = VideoProbeState.FAILED).isPlayableVideo())
    }

    @Test
    fun `T4 sized mpeg is not playable`() {
        assertFalse(attachment("video/mpeg", width = 640, height = 360).isPlayableVideo())
    }

    @Test
    fun `a video type rendered as a file takes the file save path`() {
        assertTrue(attachment("video/quicktime", videoProbeState = VideoProbeState.FAILED).isVideoShownAsFile())
        assertTrue(attachment("video/webm", width = 640, height = 360).isVideoShownAsFile())
        assertFalse(attachment("video/mp4", width = 640, height = 360).isVideoShownAsFile())
        assertFalse(attachment("application/pdf").isVideoShownAsFile())
    }

    @Test
    fun `probe state takes part in equality`() {
        assertFalse(attachment("video/mp4") == attachment("video/mp4", videoProbeState = VideoProbeState.FAILED))
    }
}

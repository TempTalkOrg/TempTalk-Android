package com.difft.android.chat.mediasend

import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.net.toUri
import com.difft.android.base.android.permission.MediaReadDenialKind
import com.difft.android.base.utils.AppPrivateStorage
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.harness.AppPrivateRoots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/**
 * T56-T58 — the one structured failure line stays diagnosable without leaking user content.
 *
 * T56 is the row that pins the reason the line carries a cause-type chain instead of a stack dump:
 * a real `FileNotFoundException` message IS the absolute path, so the normally-whitelisted stack
 * would put the user's file name into the log file.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaAccessLogTest {

    // ---------------------------------------------------------------- T56

    @Test
    fun `denial line keeps the diagnosis and drops the file name`() {
        val fileName = "VID_20260729135409873"
        val uri = Uri.parse("file:///storage/emulated/0/Movies/$fileName.mp4")
        // Exactly the shape a denied open has on a device: the message is the path.
        val cause = FileNotFoundException("/storage/emulated/0/Movies/$fileName.mp4: open failed: EACCES")
            .apply { initCause(ErrnoException("open", OsConstants.EACCES)) }
        val failure = MediaFailure(
            position = 3,
            displayName = "$fileName.mp4",
            reason = MediaFailureReason.SOURCE_UNREADABLE,
            denialKind = MediaReadDenialKind.GRANTED_BUT_UNREADABLE,
            cause = cause,
        )

        val line = MediaFailureClassifier.denialLogLine(uri, "video/mp4", failure)

        assertTrue(line, line.contains("code=MSND-01"))
        assertTrue(line, line.contains("kind=GRANTED_BUT_UNREADABLE"))
        assertTrue(line, line.contains("dir=Movies"))
        assertTrue(line, line.contains("errno=EACCES"))
        assertTrue(line, line.contains("cause=FileNotFoundException<-ErrnoException"))
        assertFalse(line, line.contains(fileName))
        assertFalse(line, line.contains(".mp4"))
        assertFalse(line, line.contains("/storage/"))
    }

    // ---------------------------------------------------------------- T57

    @Test
    fun `content uri contributes its authority and leading segments but not the row id`() {
        val uri = Uri.parse("content://media/external/video/media/12345")
        val failure = MediaFailure(
            position = 1,
            displayName = null,
            reason = MediaFailureReason.SOURCE_UNREADABLE,
            denialKind = MediaReadDenialKind.PERMISSION_MISSING,
            cause = null,
        )

        val line = MediaFailureClassifier.denialLogLine(uri, "video/mp4", failure)

        assertTrue(line, line.contains("scheme=content"))
        assertTrue(line, line.contains("authority=media"))
        assertTrue(line, line.contains("dir=external/video"))
        assertTrue(line, line.contains("errno=n/a"))
        assertFalse(line, line.contains("12345"))
    }

    // ---------------------------------------------------------------- T58

    @Test
    fun `sandbox path is reported as private storage without its file name`() {
        val uuid = UUID.randomUUID().toString()
        val sandboxFile = File(AppPrivateRoots.first(), "files/draft_blobs/$uuid.jpg")
        // Reported first so a wrong fixture path fails here rather than inside the log assertion.
        assertTrue(sandboxFile.path, AppPrivateStorage.isAppPrivate(sandboxFile.path))
        val failure = MediaFailure(
            position = 2,
            displayName = "$uuid.jpg",
            reason = MediaFailureReason.SOURCE_UNREADABLE,
            denialKind = null,
            cause = null,
        )

        val line = MediaFailureClassifier.denialLogLine(sandboxFile.toUri(), "image/jpeg", failure)

        assertTrue(line, line.contains("dir=private"))
        assertTrue(line, line.contains("kind=n/a"))
        assertFalse(line, line.contains(uuid))
    }
}

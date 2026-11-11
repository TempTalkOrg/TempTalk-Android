package com.difft.android.chat.mediasend

import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.test.builders.LocalMediaBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaUriResolverTest {

    
    @Test
    fun `content path yields the MediaStore content URI`() {
        val media = LocalMediaBuilder.gallery(
            id = 12345L,
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/12345",
            realPath = "/storage/emulated/0/Movies/a.mp4",
        )

        val uri = media.readableUri()

        assertEquals(Uri.parse("content://media/external/video/media/12345"), uri)
        assertEquals("content", uri.scheme)
    }

    




    @Test
    fun `blank content path yields a file URI for the sandbox path`() {
        val media = LocalMediaBuilder.sandbox(realPath = "/data/user/0/pkg/files/attachment/x.jpg")

        val uri = media.readableUri()

        assertEquals(Uri.parse("file:///data/user/0/pkg/files/attachment/x.jpg"), uri)
        assertEquals("file", uri.scheme)
        assertEquals("file:///data/user/0/pkg/files/attachment/x.jpg", uri.toString())
    }

    
    @Test
    fun `bare absolute path in path field yields a file URI`() {
        val media = LocalMediaBuilder.legacyBarePath(barePath = "/storage/emulated/0/DCIM/a.jpg")

        val uri = media.readableUri()

        assertEquals("file", uri.scheme)
        assertEquals("file:///storage/emulated/0/DCIM/a.jpg", uri.toString())
    }

    





    @Test
    fun `blank path and realPath yield EMPTY and log without path plaintext`() {
        val captured = CopyOnWriteArrayList<String>()
        val logged = CountDownLatch(1)
        
        
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                captured += message
                logged.countDown()
            }
        }
        L.plant(tree)
        try {
            val media = LocalMedia(path = "", realPath = "", id = 77L, mimeType = "image/png")

            val uri = media.readableUri()

            assertEquals(Uri.EMPTY, uri)
            
            assertTrue("no log line emitted", logged.await(10, TimeUnit.SECONDS))
            val message = captured.single()
            assertTrue(message, message.contains("[MediaAccess]"))
            assertTrue(message, message.contains("id=77"))
            assertTrue(message, message.contains("mime=image/png"))
            assertFalse(message, message.contains("/data"))
            assertFalse(message, message.contains("/storage"))
        } finally {
            L.uproot(tree)
        }
    }

    





    @Test
    fun `focused key from the pager argument equals the media key`() {
        val media = LocalMediaBuilder.gallery(
            mime = "video/mp4",
            contentUri = "content://media/external/video/media/9",
            realPath = "/storage/emulated/0/Movies/v.mp4",
        )
        
        val argUri = media.readableUri()

        assertEquals(media.mediaKey(), MediaKey(argUri))
        assertNotEquals(media.mediaKey(), MediaKey(Uri.parse(media.realPath)))
    }
}

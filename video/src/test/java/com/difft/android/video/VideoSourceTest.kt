package com.difft.android.video

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import timber.log.Timber
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit





@RunWith(RobolectricTestRunner::class)
class VideoSourceTest {

    private lateinit var context: Context
    private val temps = mutableListOf<File>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        
        
        FakeFileProvider.reset()
        ShadowContentResolver.reset()
        temps.forEach { it.delete() }
        temps.clear()
    }

    private fun tempVideo(bytes: Int): File =
        File.createTempFile("videosource", ".mp4").also {
            it.writeBytes(ByteArray(bytes))
            temps += it
        }

    
    @Test
    fun `file uri resolves to the native file input without touching ContentResolver`() {
        val file = tempVideo(1_024)
        val failingResolverContext = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver =
                throw AssertionError("file:// source must not go through ContentResolver")
        }

        val source = VideoSource.of(failingResolverContext, Uri.fromFile(file))

        assertTrue(source.mediaInput is FileMediaInput)
        assertEquals("file", source.scheme)
        assertEquals(file.length(), source.sizeBytes)
    }

    
    @Test
    fun `content uri is sized from the provider file descriptor`() {
        val file = tempVideo(1_500_000)
        val uri = Uri.parse("content://${FakeFileProvider.AUTHORITY}/video/1")
        FakeFileProvider.files[uri] = file
        Robolectric.setupContentProvider(FakeFileProvider::class.java, FakeFileProvider.AUTHORITY)

        val source = VideoSource.of(context, uri)

        assertTrue(source.mediaInput is UriMediaInput)
        assertEquals("content", source.scheme)
        assertEquals(1_500_000L, source.sizeBytes)
        assertEquals(1, FakeFileProvider.openFileCalls)
    }

    
    @Test
    fun `failed descriptor open yields unknown size without throwing`() {
        val uri = Uri.parse("content://${FakeFileProvider.AUTHORITY}/video/missing")
        Robolectric.setupContentProvider(FakeFileProvider::class.java, FakeFileProvider.AUTHORITY)
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
            val source = VideoSource.of(context, uri)

            assertEquals(VideoSource.UNKNOWN_SIZE, source.sizeBytes)
            assertTrue(source.mediaInput is UriMediaInput)
            
            assertTrue("no log line emitted", logged.await(10, TimeUnit.SECONDS))
            val message = captured.single()
            assertTrue(message, message.contains("[MediaAccess]"))
            assertTrue(message, message.contains("scheme=content"))
        } finally {
            L.uproot(tree)
        }
    }

    
    @Test
    fun `File entry point and file uri produce equivalent sources`() {
        val file = tempVideo(2_048)

        val fromFile = VideoSource.of(file)
        val fromUri = VideoSource.of(context, Uri.fromFile(file))

        assertEquals(fromFile.mediaInput.javaClass, fromUri.mediaInput.javaClass)
        assertEquals(fromFile.sizeBytes, fromUri.sizeBytes)
        assertEquals(fromFile.scheme, fromUri.scheme)
        assertTrue(fromFile.mediaInput.hasSameInput(fromUri.mediaInput))
    }
}

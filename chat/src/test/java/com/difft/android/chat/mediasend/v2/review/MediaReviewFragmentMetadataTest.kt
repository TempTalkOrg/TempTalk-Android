package com.difft.android.chat.mediasend.v2.review

import android.content.ContextWrapper
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.mediasend.v2.MediaSelectionRepository
import com.difft.android.chat.mediasend.v2.MediaSelectionState
import com.difft.android.chat.mediasend.v2.MediaSelectionViewModel
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.util.MemoryUnitFormat
import com.difft.android.chat.video.videoconverter.VideoThumbnailsRangeSelectorView
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.test.builders.LocalMediaBuilder
import com.difft.android.video.TranscodingQuality
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
















@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaReviewFragmentMetadataTest {

    private lateinit var controller: ActivityController<MetadataHostActivity>
    private lateinit var activity: MetadataHostActivity
    private lateinit var fragment: MediaReviewFragment
    private lateinit var videoSizeHint: TextView
    private lateinit var videoTimeLine: VideoThumbnailsRangeSelectorView

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(MetadataHostActivity::class.java).setup()
        activity = controller.get()

        
        
        
        val anchor = Fragment()
        activity.supportFragmentManager.beginTransaction().add(anchor, "anchor").commitNow()

        fragment = MediaReviewFragment()
        hostFieldOf(Fragment::class.java, "mHost").set(fragment, hostFieldOf(Fragment::class.java, "mHost").get(anchor))
        
        
        declaredFieldOf(fragment, "componentContext").set(fragment, ContextWrapper(activity))

        videoSizeHint = TextView(activity)
        videoTimeLine = mockk(relaxed = true)
        declaredFieldOf(fragment, "videoSizeHint").set(fragment, videoSizeHint)
        declaredFieldOf(fragment, "videoTimeLine").set(fragment, videoTimeLine)
    }

    @After
    fun tearDown() {
        unmockkAll()
        runCatching { controller.destroy() }
    }

    



    @Test
    fun `size hint uses store metadata when the timeline reported no duration`() {
        presentVideoSizeHint(stateFor(videoMedia(size = 1_500_000L, durationMs = 8_000L)))

        val text = requireNotNull(videoSizeHint.text) { "hint was hidden" }.toString()
        assertTrue(text, text.contains("0:08"))
        
        
        assertTrue(text, text.contains(MemoryUnitFormat.formatBytes(1_500_000L, MemoryUnitFormat.MEGA_BYTES, true)))
        assertFalse(text, text.contains("0:00"))
        assertFalse(text, text.contains(MemoryUnitFormat.formatBytes(0L, MemoryUnitFormat.MEGA_BYTES, true)))
    }

    
    @Test
    fun `size hint is hidden and logged when metadata is truly unknown`() {
        val log = LogCapture()

        log.around {
            presentVideoSizeHint(stateFor(videoMedia(size = 0L, durationMs = 0L)))
        }

        assertNull(videoSizeHint.text?.toString()?.ifEmpty { null })
        assertTrue(log.messages.toString(), log.messages.any { it.contains("[MediaAccess]") && it.contains("size hint unavailable") })
    }

    




    @Test
    fun `timeline input failure is contained and does not stop the following presenter`() {
        val media = videoMedia(size = 1_500_000L, durationMs = 8_000L)
        val state = stateFor(media)
        every { videoTimeLine.setInput(any()) } throws IOException("cannot open source")
        val log = LogCapture()

        log.around {
            presentVideoTimeline(state)
            presentVideoSizeHint(state)
        }

        assertTrue(log.messages.toString(), log.messages.any { it.contains("[MediaAccess]") && it.contains("timeline input rejected") })
        val text = requireNotNull(videoSizeHint.text) { "the following presenter did not run" }.toString()
        assertTrue(text, text.contains("0:08"))
    }

    






    @Test
    fun `size hint survives the compression estimate when the timeline reported no duration`() {
        
        
        val state = stateFor(videoMedia(size = 60L * 1024 * 1024, durationMs = 8_000L))
        val expected = TranscodingQuality.createFromPreset(state.transcodingPreset, 8_000L).byteCountEstimate
        assertTrue("preset yields no estimate, the row proves nothing", expected > 0)

        presentVideoSizeHint(state)

        val text = requireNotNull(videoSizeHint.text) { "hint was hidden" }.toString()
        assertTrue(text, text.contains("0:08"))
        assertTrue(text, text.contains(MemoryUnitFormat.formatBytes(expected, MemoryUnitFormat.MEGA_BYTES, true)))
    }

    private fun videoMedia(size: Long, durationMs: Long): LocalMedia = LocalMediaBuilder.gallery(
        id = 3L,
        mime = "video/mp4",
        contentUri = "content://media/external/video/media/3",
        realPath = "/storage/emulated/0/Movies/c.mp4",
        size = size,
        durationMs = durationMs,
    )

    private fun stateFor(media: LocalMedia) =
        MediaSelectionState(selectedMedia = listOf(media), focusedMedia = media)

    private fun presentVideoSizeHint(state: MediaSelectionState) {
        MediaReviewFragment::class.java
            .getDeclaredMethod("presentVideoSizeHint", MediaSelectionState::class.java)
            .apply { isAccessible = true }
            .invoke(fragment, state)
    }

    private fun presentVideoTimeline(state: MediaSelectionState) {
        MediaReviewFragment::class.java
            .getDeclaredMethod("presentVideoTimeline", MediaSelectionState::class.java)
            .apply { isAccessible = true }
            .invoke(fragment, state)
    }

    private fun hostFieldOf(owner: Class<*>, name: String) =
        owner.getDeclaredField(name).apply { isAccessible = true }

    
    private fun declaredFieldOf(instance: Any, name: String): java.lang.reflect.Field {
        var klass: Class<*>? = instance.javaClass
        while (klass != null) {
            klass.declaredFields.firstOrNull { it.name == name }?.let { return it.apply { isAccessible = true } }
            klass = klass.superclass
        }
        throw NoSuchFieldException(name)
    }

    




    private class LogCapture {
        val messages: MutableList<String> = CopyOnWriteArrayList()
        private val logged = CountDownLatch(1)

        fun around(block: () -> Unit) {
            val tree = object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    messages += message
                    logged.countDown()
                }
            }
            L.plant(tree)
            try {
                block()
                assertTrue("no log line emitted", logged.await(10, TimeUnit.SECONDS))
            } finally {
                L.uproot(tree)
            }
        }
    }
}





class MetadataHostActivity : FragmentActivity() {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MediaSelectionViewModel(emptyList(), MediaSelectionRepository(applicationContext)) as T
        }
}

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

/**
 * T42 / T43 / T44 / T45 — the review screen's video hint must never dress a failed read up as a fact.
 *
 * T42 is the reported symptom pinned as a regression: the timeline never reports a duration for a
 * gallery item whose bare path cannot be opened, and the old code rendered that as "0:00 • 0.0MB".
 * T43 is the honest-unknown case, T44 the observer-safety case, T45 the same fallback one branch
 * deeper — inside the compression estimate.
 *
 * The two presenters are driven directly instead of through a launched fragment: the fragment's
 * `onViewCreated` needs the whole DI graph, a Callback host and a dozen custom views, none of which
 * this contract is about. A real activity supplies the context and the shared view model, so the
 * presenters run against real framework objects.
 *
 * Run: :chat:testDebugUnitTest
 */
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

        // An anchor fragment is the only supported way to obtain a live FragmentHostCallback; the
        // fragment under test is then given the same host so requireContext() / requireActivity()
        // resolve without running its onViewCreated.
        val anchor = Fragment()
        activity.supportFragmentManager.beginTransaction().add(anchor, "anchor").commitNow()

        fragment = MediaReviewFragment()
        hostFieldOf(Fragment::class.java, "mHost").set(fragment, hostFieldOf(Fragment::class.java, "mHost").get(anchor))
        // Pre-seeding Hilt's component context short-circuits its lazy initialisation, which would
        // otherwise demand a Hilt application just to answer getContext().
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

    /**
     * T42 — MediaStore duration and size carry the hint even when the timeline reported nothing.
     * The two negative assertions are the reported symptom itself.
     */
    @Test
    fun `size hint uses store metadata when the timeline reported no duration`() {
        presentVideoSizeHint(stateFor(videoMedia(size = 1_500_000L, durationMs = 8_000L)))

        val text = requireNotNull(videoSizeHint.text) { "hint was hidden" }.toString()
        assertTrue(text, text.contains("0:08"))
        // Expected strings come from the same formatter so the assertion cannot fail on a locale
        // that renders decimals differently — while still pinning the exact reported symptom.
        assertTrue(text, text.contains(MemoryUnitFormat.formatBytes(1_500_000L, MemoryUnitFormat.MEGA_BYTES, true)))
        assertFalse(text, text.contains("0:00"))
        assertFalse(text, text.contains(MemoryUnitFormat.formatBytes(0L, MemoryUnitFormat.MEGA_BYTES, true)))
    }

    /** T43 — genuinely unknown metadata hides the hint rather than printing a fabricated zero. */
    @Test
    fun `size hint is hidden and logged when metadata is truly unknown`() {
        val log = LogCapture()

        log.around {
            presentVideoSizeHint(stateFor(videoMedia(size = 0L, durationMs = 0L)))
        }

        assertNull(videoSizeHint.text?.toString()?.ifEmpty { null })
        assertTrue(log.messages.toString(), log.messages.any { it.contains("[MediaAccess]") && it.contains("size hint unavailable") })
    }

    /**
     * T44 — `setInput` is declared @Throws(IOException) and the presenters run inside a LiveData
     * observer, so an escape would abort the whole observer dispatch. The size hint that follows in
     * the same observer must still run.
     */
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

    /**
     * T45 — the compression estimate uses the same fallback duration the hint displays.
     *
     * The timeline reports no duration until it has read the source, so the estimate was taken over
     * 0ms — which is 0 bytes — and hid the hint for an item whose duration the MediaStore row knew
     * all along. This is the same defect as T42, one branch deeper.
     */
    @Test
    fun `size hint survives the compression estimate when the timeline reported no duration`() {
        // Over getCompressedVideoMaxSize, so the estimate branch runs instead of returning the
        // original size — that branch is the one that consumed the timeline's zero.
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

    /** Walks up to whichever class in the hierarchy declares [name] — Hilt owns some of them. */
    private fun declaredFieldOf(instance: Any, name: String): java.lang.reflect.Field {
        var klass: Class<*>? = instance.javaClass
        while (klass != null) {
            klass.declaredFields.firstOrNull { it.name == name }?.let { return it.apply { isAccessible = true } }
            klass = klass.superclass
        }
        throw NoSuchFieldException(name)
    }

    /**
     * L's warn entry point is a @JvmStatic bridge, so a call site is dispatched statically and an
     * object mock never sees it; a real planted tree is the only way to observe the line. Delivery
     * is asynchronous over L's own channel, hence the bounded wait rather than a bare assertion.
     */
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

/**
 * Supplies the shared [MediaSelectionViewModel]: the fragment resolves it from the activity, and
 * the real one has no no-argument constructor.
 */
class MetadataHostActivity : FragmentActivity() {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MediaSelectionViewModel(emptyList(), MediaSelectionRepository(applicationContext)) as T
        }
}

package com.difft.android.chat.mediasend.v2.review

import android.net.Uri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.mediasend.readableUri
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.test.builders.LocalMediaBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import util.getParcelableCompat

/**
 * T24 — [MediaReviewFragmentPagerAdapter] identity and ARG_URI contract.
 *
 * The compiler cannot catch the failure this pins. If the adapter still derived its URIs from the
 * bare `realPath` while the view model derived its keys from the normalized URI, both sides would
 * still hold perfectly legal keys of the same type — the crop / drawing / trim would simply be
 * written under one key and looked up under another, and be discarded with no error at all.
 *
 * Uses a real `FragmentStateAdapter` with a real host fragment, a real `Bundle` parcel round trip
 * and a real `Uri.hashCode()`, because the ViewPager2 stable-id contract lives in that framework
 * behaviour rather than in our code.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaReviewFragmentPagerAdapterTest {

    /** Mirrors the private constant in each page fragment; the parcel key is the contract. */
    private val argUriKey = "arg.uri"

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var host: Fragment
    private lateinit var adapter: MediaReviewFragmentPagerAdapter

    private lateinit var media: List<LocalMedia>

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        host = Fragment()
        controller.get().supportFragmentManager
            .beginTransaction()
            .add(host, "host")
            .commitNow()
        adapter = MediaReviewFragmentPagerAdapter(host)

        media = listOf(
            LocalMediaBuilder.gallery(
                id = 1L,
                mime = "image/jpeg",
                contentUri = "content://media/external/images/media/1",
                realPath = "/storage/emulated/0/DCIM/Camera/a.jpg",
            ),
            LocalMediaBuilder.gallery(
                id = 2L,
                mime = "video/mp4",
                contentUri = "content://media/external/video/media/2",
                realPath = "/storage/emulated/0/Movies/b.mp4",
            ),
            LocalMediaBuilder.sandbox(
                realPath = "/data/user/0/com.difft.android/files/attachment/c.jpg",
            ),
        )
        adapter.submitMedia(media)
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
    }

    /**
     * T24 ① — every page fragment is handed the normalized URI, not the bare `realPath`. This is
     * the root of the derivation chain: ARG_URI feeds both the editor-state keys and the preview
     * sinks of all three page fragment types.
     */
    @Test
    fun `each page fragment argument carries the normalized media uri`() {
        media.indices.forEach { index ->
            val arguments = requireNotNull(adapter.createFragment(index).arguments) {
                "page $index has no arguments"
            }
            val argUri = arguments.getParcelableCompat(argUriKey, Uri::class.java)

            assertEquals("page $index", media[index].readableUri(), argUri)
        }

        // Shape check on top of equality: the two gallery items must reach the pages as content
        // URIs, and the sandbox item must keep its file URI (its scoped-storage immunity).
        assertEquals("content", argUriOf(0).scheme)
        assertEquals("content", argUriOf(1).scheme)
        assertEquals("file", argUriOf(2).scheme)
    }

    /** T24 ② / ③ — getItemId and containsItem share one derivation, so they cannot drift apart. */
    @Test
    fun `item ids come from the normalized uri and are recognized by containsItem`() {
        media.indices.forEach { index ->
            val expected = media[index].readableUri().hashCode().toLong()

            assertEquals("page $index", expected, adapter.getItemId(index))
            assertTrue("page $index", adapter.containsItem(expected))
        }
    }

    /**
     * T24 ④ — ViewPager2 requires a stable id per logical item across adapter submissions.
     * `readableUri()` reads only `path` / `realPath`, and no transform rewrites `path`, so
     * resubmitting the same list must not renumber anything.
     */
    @Test
    fun `item ids are unchanged after resubmitting the same list`() {
        val before = media.indices.map { adapter.getItemId(it) }

        adapter.submitMedia(media)

        assertEquals(before, media.indices.map { adapter.getItemId(it) })
        assertTrue(before.toSet().size == before.size)
    }

    private fun argUriOf(index: Int): Uri = requireNotNull(
        adapter.createFragment(index).arguments?.getParcelableCompat(argUriKey, Uri::class.java)
    )
}

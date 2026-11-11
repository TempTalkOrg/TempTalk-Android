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















@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaReviewFragmentPagerAdapterTest {

    
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

    




    @Test
    fun `each page fragment argument carries the normalized media uri`() {
        media.indices.forEach { index ->
            val arguments = requireNotNull(adapter.createFragment(index).arguments) {
                "page $index has no arguments"
            }
            val argUri = arguments.getParcelableCompat(argUriKey, Uri::class.java)

            assertEquals("page $index", media[index].readableUri(), argUri)
        }

        
        
        assertEquals("content", argUriOf(0).scheme)
        assertEquals("content", argUriOf(1).scheme)
        assertEquals("file", argUriOf(2).scheme)
    }

    
    @Test
    fun `item ids come from the normalized uri and are recognized by containsItem`() {
        media.indices.forEach { index ->
            val expected = media[index].readableUri().hashCode().toLong()

            assertEquals("page $index", expected, adapter.getItemId(index))
            assertTrue("page $index", adapter.containsItem(expected))
        }
    }

    




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

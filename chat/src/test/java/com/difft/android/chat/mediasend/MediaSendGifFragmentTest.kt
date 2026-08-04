package com.difft.android.chat.mediasend

import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.difft.android.chat.messages.TestScopeApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * T17 — [MediaSendGifFragment] preview source.
 *
 * The old form was `File(uri.path!!)`: it threw NPE for a URI carrying no path and resolved to a
 * non-existent path for a MediaStore one. Both are covered here — the `!!` is only visibly gone if
 * a path-less URI is exercised.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaSendGifFragmentTest {

    private lateinit var requestManager: RequestManager
    private lateinit var requestBuilder: RequestBuilder<Drawable>
    private val loaded = slot<Uri>()

    @Before
    fun setUp() {
        mockkStatic(Glide::class)
        requestManager = mockk()
        requestBuilder = mockk()
        every { Glide.with(any<Fragment>()) } returns requestManager
        every { requestManager.load(capture(loaded)) } returns requestBuilder
        every { requestBuilder.fitCenter() } returns requestBuilder
        every { requestBuilder.into(any<ImageView>()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** T17 — a content URI reaches Glide as a URI, never as a bare-path `File`. */
    @Test
    fun `content uri is previewed through the uri loader`() {
        val source = Uri.parse("content://media/external/images/media/2")

        showWith(source)

        assertEquals(source, loaded.captured)
        verify(exactly = 0) { requestManager.load(any<File>()) }
    }

    /** The `!!` regression: a URI with no path must not crash the preview. */
    @Test
    fun `path less uri does not throw`() {
        val source = Uri.parse("content://com.difft.android.provider")

        showWith(source)

        assertEquals(source, loaded.captured)
    }

    private fun showWith(source: Uri) {
        val fragment = MediaSendGifFragment.newInstance(source)
        val imageView = ImageView(RuntimeEnvironment.getApplication())

        fragment.onViewCreated(imageView, null)
    }
}

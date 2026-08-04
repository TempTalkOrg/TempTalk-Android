package com.difft.android.chat.mediasend.v2.review

import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.difft.android.chat.R
import com.difft.android.chat.mediasend.readableUri
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.builders.LocalMediaBuilder
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

/**
 * T45 — the selected-media strip binds the normalized URI.
 *
 * The strip is the first thing the user sees after picking, so a bare gallery `realPath` here shows
 * an empty row even when the rest of the chain is correct.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaReviewSelectedItemTest {

    private lateinit var requestManager: RequestManager
    private lateinit var requestBuilder: RequestBuilder<Drawable>
    private val loaded = slot<Uri>()

    @Before
    fun setUp() {
        mockkStatic(Glide::class)
        requestManager = mockk()
        requestBuilder = mockk()
        every { Glide.with(any<View>()) } returns requestManager
        every { requestManager.load(capture(loaded)) } returns requestBuilder
        every { requestBuilder.centerCrop() } returns requestBuilder
        every { requestBuilder.into(any<ImageView>()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `thumbnail strip loads the normalized media uri`() {
        val media = LocalMediaBuilder.gallery(
            id = 9L,
            mime = "image/jpeg",
            contentUri = "content://media/external/images/media/9",
            realPath = "/storage/emulated/0/DCIM/Camera/img_9.jpg",
        )
        val context = RuntimeEnvironment.getApplication()
        val itemView = LayoutInflater.from(context).inflate(R.layout.v2_media_review_selected_item, null)

        MediaReviewSelectedItem.ViewHolder(itemView) { _, _ -> }
            .bind(MediaReviewSelectedItem.Model(media, false))

        assertEquals(media.readableUri(), loaded.captured)
        assertEquals("content", loaded.captured.scheme)
        verify(exactly = 0) { requestManager.load(any<String>()) }
    }
}

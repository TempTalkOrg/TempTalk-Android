package com.difft.android.chat.scribbles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.request.FutureTarget
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ExecutionException

/**
 * T15 / T16 / T41 — how [UriGlideRenderer] hands a source to Glide.
 *
 * T15 is the hard blocker of the image-editor preview: `File(uri.path)` for a MediaStore URI is
 * "/external/images/media/1", a path that never exists, so every gallery image failed to decode.
 * T16 pins the other side of the branch — a sandbox `file://` source must keep the `File` model so
 * the loads that work today stay byte-identical.
 *
 * T15 / T16 read the real `RequestBuilder`'s model (Glide dispatches on the model's runtime type),
 * so they exercise the real Glide registry rather than a stub. T41 needs a load failure at an exact
 * moment, so it stubs the Glide chain instead of racing Glide's executors.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class UriGlideRendererTest {

    private lateinit var context: Context

    private val getGlideRequestBuilder = UriGlideRenderer::class.java
        .getDeclaredMethod("getGlideRequestBuilder", Context::class.java, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }

    private val getModel = RequestBuilder::class.java
        .getDeclaredMethod("getModel")
        .apply { isAccessible = true }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** T15 — a content URI must reach Glide as a `Uri` so the load goes through ContentResolver. */
    @Test
    fun `content uri is handed to glide as a uri`() {
        val source = Uri.parse("content://media/external/images/media/1")

        val model = modelFor(source)

        assertTrue("model was ${model?.javaClass}", model is Uri)
        assertEquals(source, model)
    }

    /** T16 — the sandbox / SAF branch keeps the `File` model: no behaviour change where it works. */
    @Test
    fun `file uri keeps the file model`() {
        val path = "/data/user/0/com.difft.android/files/attachment/x.jpg"

        val model = modelFor(Uri.parse("file://$path"))

        assertTrue("model was ${model?.javaClass}", model is File)
        assertEquals(path, (model as File).path)
    }

    /** A URI with no path at all still resolves to something loadable rather than `File("")`. */
    @Test
    fun `path less uri is handed to glide as a uri`() {
        val source = Uri.parse("content://com.difft.android.provider")

        val model = modelFor(source)

        assertTrue("model was ${model?.javaClass}", model is Uri)
        assertEquals(source, model)
    }

    /**
     * T41 — the blocking branch runs during the final render, where a swallowed failure would ship
     * a blacked-out image. The throw is what lets the caller decide, so it must not be absorbed.
     */
    @Test
    fun `blocking load failure is rethrown rather than swallowed`() {
        val cause = ExecutionException(RuntimeException("glide load failed"))
        stubGlideChainThrowingOnSubmit(cause)

        val renderer = UriGlideRenderer(Uri.parse("content://media/external/images/media/1"), false, MAX_DIMEN, MAX_DIMEN)
        val rendererContext = blockingRendererContext()

        try {
            renderer.render(rendererContext)
            fail("expected the blocking load failure to propagate")
        } catch (e: RuntimeException) {
            assertNotNull(e.cause)
            assertEquals(cause, e.cause)
        }
    }

    private fun modelFor(uri: Uri): Any? {
        val renderer = UriGlideRenderer(uri, false, MAX_DIMEN, MAX_DIMEN)
        val builder = getGlideRequestBuilder.invoke(renderer, context, true)
        return getModel.invoke(builder)
    }

    private fun stubGlideChainThrowingOnSubmit(failure: ExecutionException) {
        mockkStatic(Glide::class)
        val requestManager = mockk<RequestManager>()
        val builder = mockk<RequestBuilder<Bitmap>>()
        val future = mockk<FutureTarget<Bitmap>>()

        every { Glide.with(any<Context>()) } returns requestManager
        every { requestManager.asBitmap() } returns builder
        every { builder.override(any(), any()) } returns builder
        every { builder.centerInside() } returns builder
        every { builder.addListener(any()) } returns builder
        every { builder.load(any<Uri>()) } returns builder
        every { builder.submit() } returns future
        every { future.get() } throws failure
    }

    private fun blockingRendererContext(): RendererContext {
        val target = Bitmap.createBitmap(MAX_DIMEN, MAX_DIMEN, Bitmap.Config.ARGB_8888)
        return RendererContext(
            context,
            Canvas(target),
            RendererContext.Ready.NULL,
            RendererContext.Invalidate.NULL,
            object : RendererContext.TypefaceProvider {
                override fun getSelectedTypeface(context: Context, renderer: Renderer, invalidate: RendererContext.Invalidate): Typeface =
                    Typeface.DEFAULT
            },
        ).apply { setBlockingLoad(true) }
    }

    private companion object {
        const val MAX_DIMEN = 64
    }
}

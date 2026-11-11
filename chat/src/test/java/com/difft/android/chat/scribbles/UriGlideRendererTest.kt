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

    
    @Test
    fun `content uri is handed to glide as a uri`() {
        val source = Uri.parse("content://media/external/images/media/1")

        val model = modelFor(source)

        assertTrue("model was ${model?.javaClass}", model is Uri)
        assertEquals(source, model)
    }

    
    @Test
    fun `file uri keeps the file model`() {
        val path = "/data/user/0/com.difft.android/files/attachment/x.jpg"

        val model = modelFor(Uri.parse("file://$path"))

        assertTrue("model was ${model?.javaClass}", model is File)
        assertEquals(path, (model as File).path)
    }

    
    @Test
    fun `path less uri is handed to glide as a uri`() {
        val source = Uri.parse("content://com.difft.android.provider")

        val model = modelFor(source)

        assertTrue("model was ${model?.javaClass}", model is Uri)
        assertEquals(source, model)
    }

    



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

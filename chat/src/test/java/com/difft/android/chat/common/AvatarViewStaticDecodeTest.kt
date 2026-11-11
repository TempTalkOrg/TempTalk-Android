package com.difft.android.chat.common

import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.difft.android.base.glide.GlideCacheKeyManager
import com.difft.android.chat.media.AvatarEncryptedProvider
import com.difft.android.chat.messages.TestScopeApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit


@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarViewStaticDecodeTest {
    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var avatar: AvatarView
    private lateinit var gif: File
    private lateinit var corrupt: File

    @Before
    fun setUp() {
        mockkObject(GlideCacheKeyManager)
        every { GlideCacheKeyManager.getKeyOrNull(any()) } returns null
        every { GlideCacheKeyManager.isCacheKeyReady(any()) } returns false
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        val activity = controller.get()
        avatar = AvatarView(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(avatar, 48, 48) })
        gif = File.createTempFile("static-avatar", ".gif", activity.cacheDir).apply { writeBytes(twoFrameGif()) }
        corrupt = File.createTempFile("corrupt-avatar", ".gif", activity.cacheDir).apply { writeText("invalid image") }
        mockkObject(AvatarUtil)
        mockkObject(AvatarEncryptedProvider.Companion)
        
        
        every { AvatarEncryptedProvider.contentUri(any(), any()) } answers {
            Uri.fromFile(if (secondArg<String>() == corrupt.name) corrupt else gif)
        }
    }

    @After
    fun tearDown() {
        Glide.with(avatar).clear(avatar.binding.ivAvatar)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        gif.delete()
        corrupt.delete()
        unmockkAll()
    }

    @Test
    fun `local animated avatar decodes only its displayed first frame`() {
        assertAnimatedFixture()
        avatar.setAvatar(gif.path)
        assertStaticFirstFrame()
    }

    @Test
    fun `warm encrypted cache target is static with explicit size`() {
        every { AvatarUtil.getCacheFile(URL) } returns gif
        avatar.setAvatar(URL, "key", "A", "+123", targetSizePx = 24)
        assertStaticFirstFrame()
        assertEquals(24, (avatar.binding.ivAvatar.drawable as BitmapDrawable).bitmap.width)
    }

    @Test
    fun `downloaded animated avatar uses the same static decoder`() {
        every { AvatarUtil.getCacheFile(URL) } returns null
        coEvery { AvatarUtil.ensureCached(any(), URL, any()) } returns gif
        avatar.setAvatar(URL, "key", "A", "+123")
        assertStaticFirstFrame()
        coVerify(exactly = 1) { AvatarUtil.ensureCached(any(), URL, any()) }
    }

    @Test
    fun `corrupt cache redownload also decodes a static first frame`() {
        every { AvatarUtil.getCacheFile(URL) } returns corrupt
        coEvery { AvatarUtil.ensureCached(any(), URL, any()) } returns gif
        avatar.setAvatar(URL, "key", "A", "+123")
        assertStaticFirstFrame()
        coVerify(exactly = 1) { AvatarUtil.ensureCached(any(), URL, any()) }
        assertFalse(corrupt.exists())
    }

    private fun assertStaticFirstFrame() {
        
        val exact = View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY)
        avatar.measure(exact, exact)
        avatar.layout(0, 0, 48, 48)
        avatar.viewTreeObserver.dispatchOnPreDraw()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (avatar.binding.ivAvatar.drawable == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val drawable = avatar.binding.ivAvatar.drawable
        assertTrue("The real avatar request must finish with a bitmap", drawable is BitmapDrawable)
        assertFalse("A static avatar must have no frame animation to schedule", drawable is Animatable)
        assertEquals(Color.RED, (drawable as BitmapDrawable).bitmap.getPixel(0, 0))
        assertEquals(View.VISIBLE, avatar.binding.ivAvatar.visibility)
    }

    private fun assertAnimatedFixture() {
        val parser = GifHeaderParser()
        val header = parser.setData(gif.readBytes()).parseHeader()
        assertEquals(0, header.status)
        assertEquals(2, header.numFrames)
        parser.clear()
    }

    
    private fun twoFrameGif(): ByteArray = (
        "47494638396101000100800000ff00000000ff" +
            "21ff0b4e45545343415045322e300301000000" +
            "21f90400070000002c0000000001000100000202440100" +
            "21f90400070000002c00000000010001000002024c01003b"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val URL = "https://avatar.invalid/static-frame"
    }
}

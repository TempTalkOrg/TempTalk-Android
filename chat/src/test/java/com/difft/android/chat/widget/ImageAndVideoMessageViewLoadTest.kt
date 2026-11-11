package com.difft.android.chat.widget

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.request.Request
import com.difft.android.base.glide.GlideCacheKeyManager
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.AttachmentStatusRepair
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File


@RunWith(RobolectricTestRunner::class)
@Config(
    application = TestScopeApplication::class,
    sdk = [33],
    instrumentedPackages = ["com.difft.android.base.utils"]
)
@LooperMode(LooperMode.Mode.PAUSED)
class ImageAndVideoMessageViewLoadTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity
    private lateinit var parent: FrameLayout
    private lateinit var view: ImageAndVideoMessageView
    private lateinit var readyAttachment: Attachment
    private lateinit var imageFile: File

    @Before
    fun setUp() {
        mockkObject(GlideCacheKeyManager)
        every { GlideCacheKeyManager.isCacheKeyReady(any()) } returns false
        mockkObject(AttachmentStatusRepair)
        every { AttachmentStatusRepair.markSuccessIfStale(any()) } just Runs
        mockkObject(FileUtil)
        every { FileUtil.getProgress(any()) } returns null

        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
        parent = FrameLayout(activity)
        activity.setContentView(parent)
        shadowOf(Looper.getMainLooper()).idle()

        readyAttachment = attachment(AttachmentStatus.SUCCESS.code)
        imageFile = File(AttachmentPathResolver.fileFor(readyAttachment))
        imageFile.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            imageFile.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        readyAttachment.size = imageFile.length().toInt()

        view = ImageAndVideoMessageView(activity)
        parent.addView(view)
    }

    @After
    fun tearDown() {
        try {
            if (::view.isInitialized) Glide.with(activity).clear(view.binding.imageView)
            if (::controller.isInitialized) controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            if (::imageFile.isInitialized) {
                FileUtil.invalidateFileValidity(imageFile.path)
                imageFile.parentFile!!.deleteRecursively()
            }
            unmockkAll()
        }
    }

    @Test
    fun readyImage_detachedAndReattachedBeforeMainIdle_keepsGlideRequest() {
        assertTrue(view.isAttachedToWindow)
        view.setupImageView(message(readyAttachment))

        
        parent.removeView(view)
        assertTrue(!view.isAttachedToWindow)
        parent.addView(view)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(view.isAttachedToWindow)
        assertNotNull("The ready image must retain a Glide request across detach", request())
        verify(exactly = 1) { AttachmentStatusRepair.markSuccessIfStale(readyAttachment) }
    }

    @Test
    fun readyImage_reboundToDownloadingAttachment_clearsPreviousRequest() {
        view.setupImageView(message(readyAttachment))
        val firstRequest = request()
        assertNotNull("Binding must install the ready image request synchronously", firstRequest)

        val downloading = attachment(AttachmentStatus.LOADING.code)
        every { FileUtil.getProgress(downloading.localId) } returns 35
        assertTrue(!File(AttachmentPathResolver.fileFor(downloading)).exists())
        view.setupImageView(message(downloading))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, view.binding.downloadStatusView.visibility)
        assertEquals(View.GONE, view.binding.uploadProgressView.visibility)
        assertNull("A downloading attachment must not retain the ready image request", request())
        assertTrue("Rebinding must clear the previous request", firstRequest!!.isCleared)
        assertNull(view.binding.imageView.drawable)
    }

    private fun request(): Request? =
        view.binding.imageView.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag) as? Request

    private fun message(attachment: Attachment) = TextChatMessage().apply {
        id = "message-${attachment.localId}"
        this.attachment = attachment
    }

    private fun attachment(status: Int) = Attachment(
        id = "server-id",
        authorityId = 1L,
        contentType = "image/png",
        key = null,
        size = 1,
        thumbnail = null,
        digest = null,
        fileName = "image.png",
        flags = 0,
        width = 16,
        height = 16,
        path = null,
        status = status
    )
}

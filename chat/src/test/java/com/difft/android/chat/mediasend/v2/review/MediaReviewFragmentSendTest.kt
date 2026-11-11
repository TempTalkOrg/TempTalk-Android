package com.difft.android.chat.mediasend.v2.review

import android.content.ContextWrapper
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.difft.android.chat.mediasend.MediaFailure
import com.difft.android.chat.mediasend.MediaFailureReason
import com.difft.android.chat.mediasend.MediaSendActivityResult
import com.difft.android.chat.mediasend.MediaSendFailureNotice
import com.difft.android.chat.mediasend.SendableMedia
import com.difft.android.chat.mediasend.readableUri
import com.difft.android.chat.mediasend.v2.MediaSelectionRepository
import com.difft.android.chat.mediasend.v2.MediaSendOutcome
import com.difft.android.chat.mediasend.v2.MediaSelectionViewModel
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.util.views.TouchInterceptingFrameLayout
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.test.builders.LocalMediaBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
















@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaReviewFragmentSendTest {

    private lateinit var controller: ActivityController<SendHostActivity>
    private lateinit var activity: SendHostActivity
    private lateinit var fragment: MediaReviewFragment
    private lateinit var progressWrapper: TouchInterceptingFrameLayout
    private lateinit var callback: MediaReviewFragment.Callback
    private lateinit var repository: MediaSelectionRepository

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        SendHostActivity.repository = repository
        controller = Robolectric.buildActivity(SendHostActivity::class.java).setup()
        activity = controller.get()

        val anchor = Fragment()
        activity.supportFragmentManager.beginTransaction().add(anchor, "anchor").commitNow()

        fragment = MediaReviewFragment()
        fieldOf(Fragment::class.java, "mHost").set(fragment, fieldOf(Fragment::class.java, "mHost").get(anchor))
        declaredFieldOf(fragment, "componentContext").set(fragment, ContextWrapper(activity))
        attachViewLifecycleOwner()

        progressWrapper = TouchInterceptingFrameLayout(activity)
        callback = mockk(relaxed = true)
        declaredFieldOf(fragment, "progressWrapper").set(fragment, progressWrapper)
        declaredFieldOf(fragment, "callback").set(fragment, callback)

        mockkObject(MediaSendFailureNotice)
    }

    @After
    fun tearDown() {
        unmockkAll()
        runCatching { controller.destroy() }
    }

    

    
    @Test
    fun `an all failed outcome releases the overlay and keeps the screen`() {
        val onRetry = slot<() -> Unit>()
        every { MediaSendFailureNotice.showAllFailed(any(), any(), capture(onRetry)) } just Runs
        givenSendReturns(MediaSendOutcome(resultOf(emptyList(), "caption"), listOf(failure(1))))
        viewModel().setMessage("caption")
        awaitMessage("caption")

        performSend()

        verify(exactly = 0) { callback.onSentWithResult(any()) }
        verify(exactly = 0) { callback.onSendError(any()) }
        assertEquals(View.GONE, progressWrapper.visibility)
        assertFalse(activity.isFinishing)
        assertEquals("caption", viewModel().state.value?.message)
        assertTrue("no retry entry was offered", onRetry.isCaptured)
    }

    

    
    @Test
    fun `a partial outcome waits for confirmation before sending the rest`() {
        val onProceed = slot<() -> Unit>()
        every { MediaSendFailureNotice.showPartial(any(), any(), any(), capture(onProceed)) } just Runs
        val sendable = listOf(sendableMedia(1L), sendableMedia(2L))
        val outcome = MediaSendOutcome(resultOf(sendable, "caption"), listOf(failure(3)))
        givenSendReturns(outcome)

        performSend()

        verify(exactly = 0) { callback.onSentWithResult(any()) }
        assertEquals(View.GONE, progressWrapper.visibility)

        onProceed.captured.invoke()

        val delivered = slot<MediaSendOutcome>()
        verify(exactly = 1) { callback.onSentWithResult(capture(delivered)) }
        assertSame(outcome, delivered.captured)
        assertEquals(2, delivered.captured.result.media.size)
        assertEquals("caption", delivered.captured.result.body)
    }

    

    
    @Test
    fun `a fully successful outcome is delivered without any failure surface`() {
        val sendable = listOf(sendableMedia(1L), sendableMedia(2L), sendableMedia(3L))
        val outcome = MediaSendOutcome(resultOf(sendable, "caption"), emptyList())
        givenSendReturns(outcome)

        performSend()

        val delivered = slot<MediaSendOutcome>()
        verify(exactly = 1) { callback.onSentWithResult(capture(delivered)) }
        assertSame(outcome, delivered.captured)
        verify(exactly = 0) { MediaSendFailureNotice.showAllFailed(any(), any(), any()) }
        verify(exactly = 0) { MediaSendFailureNotice.showPartial(any(), any(), any(), any()) }
        assertEquals(View.VISIBLE, progressWrapper.visibility)
    }

    

    private fun givenSendReturns(outcome: MediaSendOutcome) {
        coEvery { repository.send(any(), any(), any(), any(), any()) } returns outcome
    }

    private fun performSend() {
        MediaReviewFragment::class.java
            .getDeclaredMethod("performSend")
            .apply { isAccessible = true }
            .invoke(fragment)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun viewModel(): MediaSelectionViewModel =
        ViewModelProvider(activity, activity.defaultViewModelProviderFactory)[MediaSelectionViewModel::class.java]

    private fun failure(position: Int) = MediaFailure(
        position = position,
        displayName = null,
        reason = MediaFailureReason.SOURCE_UNREADABLE,
        denialKind = null,
        cause = null,
    )

    private fun sendableMedia(id: Long): SendableMedia {
        val media: LocalMedia = LocalMediaBuilder.gallery(id = id)
        return SendableMedia(media, media.readableUri())
    }

    private fun resultOf(media: List<SendableMedia>, body: String) =
        MediaSendActivityResult(media = media, body = body)

    
    private fun awaitMessage(expected: String, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel().state.value?.message == expected) return
            Thread.sleep(10)
        }
        fail("state message never became \"$expected\"")
    }

    



    private fun attachViewLifecycleOwner() {
        val ownerClass = Class.forName("androidx.fragment.app.FragmentViewLifecycleOwner")
        val owner = ownerClass
            .getDeclaredConstructor(Fragment::class.java, ViewModelStore::class.java, Runnable::class.java)
            .apply { isAccessible = true }
            .newInstance(fragment, ViewModelStore(), Runnable {})
        ownerClass.getDeclaredMethod("initialize").apply { isAccessible = true }.invoke(owner)
        
        ownerClass.getDeclaredMethod("performRestore", Bundle::class.java)
            .apply { isAccessible = true }
            .invoke(owner, null)
        ownerClass.getDeclaredMethod("setCurrentState", Lifecycle.State::class.java)
            .apply { isAccessible = true }
            .invoke(owner, Lifecycle.State.RESUMED)
        fieldOf(Fragment::class.java, "mViewLifecycleOwner").set(fragment, owner)
    }

    private fun fieldOf(owner: Class<*>, name: String) =
        owner.getDeclaredField(name).apply { isAccessible = true }

    
    private fun declaredFieldOf(instance: Any, name: String): java.lang.reflect.Field {
        var klass: Class<*>? = instance.javaClass
        while (klass != null) {
            klass.declaredFields.firstOrNull { it.name == name }?.let { return it.apply { isAccessible = true } }
            klass = klass.superclass
        }
        throw NoSuchFieldException(name)
    }
}


class SendHostActivity : FragmentActivity() {

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MediaSelectionViewModel(emptyList(), requireNotNull(repository)) as T
        }

    companion object {
        
        var repository: MediaSelectionRepository? = null
    }
}

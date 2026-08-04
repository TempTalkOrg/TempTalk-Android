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

/**
 * T70 / T71 / T73 — what the review screen does with a send outcome.
 *
 * T70 is the freeze row: the progress overlay intercepts every touch, so a failure path that leaves
 * it up locks the whole screen with no way out but killing the process. It is also the row that pins
 * "do not finish on failure" — finishing is what discarded the typed caption and the entire
 * selection just to report that one item could not be read.
 *
 * The fragment is driven without running `onViewCreated`: that needs the full DI graph, a Callback
 * host and a dozen custom views, none of which this contract is about. A real activity supplies the
 * context and the shared view model, and the two views the send path touches are injected directly,
 * so the code under test needed no seam added for the test.
 *
 * Run: :chat:testDebugUnitTest
 */
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

    // ---------------------------------------------------------------- T70

    /** T70 — nothing could be sent: stay on the screen, release the overlay, keep the caption. */
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

    // ---------------------------------------------------------------- T71

    /** T71 — a partial failure sends nothing until the user says so, then sends exactly the rest. */
    @Test
    fun `a partial outcome waits for confirmation before sending the rest`() {
        val onProceed = slot<() -> Unit>()
        every { MediaSendFailureNotice.showPartial(any(), any(), any(), capture(onProceed)) } just Runs
        val sendable = listOf(sendableMedia(1L), sendableMedia(2L))
        givenSendReturns(MediaSendOutcome(resultOf(sendable, "caption"), listOf(failure(3))))

        performSend()

        verify(exactly = 0) { callback.onSentWithResult(any()) }
        assertEquals(View.GONE, progressWrapper.visibility)

        onProceed.captured.invoke()

        val delivered = slot<MediaSendActivityResult>()
        verify(exactly = 1) { callback.onSentWithResult(capture(delivered)) }
        assertEquals(2, delivered.captured.media.size)
        assertEquals("caption", delivered.captured.body)
    }

    // ---------------------------------------------------------------- T73

    /** T73 — the all-succeeded path is unchanged: hand the result over, no dialog, overlay stays. */
    @Test
    fun `a fully successful outcome is delivered without any failure surface`() {
        val sendable = listOf(sendableMedia(1L), sendableMedia(2L), sendableMedia(3L))
        givenSendReturns(MediaSendOutcome(resultOf(sendable, "caption"), emptyList()))

        performSend()

        verify(exactly = 1) { callback.onSentWithResult(any()) }
        verify(exactly = 0) { MediaSendFailureNotice.showAllFailed(any(), any(), any()) }
        verify(exactly = 0) { MediaSendFailureNotice.showPartial(any(), any(), any(), any()) }
        assertEquals(View.VISIBLE, progressWrapper.visibility)
    }

    // ---------------------------------------------------------------- helpers

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

    /** `Store.update` runs off the main thread, so the caption has to be waited for. */
    private fun awaitMessage(expected: String, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel().state.value?.message == expected) return
            Thread.sleep(10)
        }
        fail("state message never became \"$expected\"")
    }

    /**
     * Builds the view-lifecycle owner `performSend`'s coroutine is scoped to. Only `performViewCreated`
     * creates it in production, and that requires the whole inflated view tree.
     */
    private fun attachViewLifecycleOwner() {
        val ownerClass = Class.forName("androidx.fragment.app.FragmentViewLifecycleOwner")
        val owner = ownerClass
            .getDeclaredConstructor(Fragment::class.java, ViewModelStore::class.java, Runnable::class.java)
            .apply { isAccessible = true }
            .newInstance(fragment, ViewModelStore(), Runnable {})
        ownerClass.getDeclaredMethod("initialize").apply { isAccessible = true }.invoke(owner)
        // The saved-state registry has to be restored before the lifecycle may advance to CREATED.
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

    /** Walks up to whichever class in the hierarchy declares [name] — Hilt owns some of them. */
    private fun declaredFieldOf(instance: Any, name: String): java.lang.reflect.Field {
        var klass: Class<*>? = instance.javaClass
        while (klass != null) {
            klass.declaredFields.firstOrNull { it.name == name }?.let { return it.apply { isAccessible = true } }
            klass = klass.superclass
        }
        throw NoSuchFieldException(name)
    }
}

/** Supplies the shared view model over a stubbed repository, which is what the send outcome comes from. */
class SendHostActivity : FragmentActivity() {

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MediaSelectionViewModel(emptyList(), requireNotNull(repository)) as T
        }

    companion object {
        /** Robolectric constructs the activity, so the stub has to be handed over statically. */
        var repository: MediaSelectionRepository? = null
    }
}

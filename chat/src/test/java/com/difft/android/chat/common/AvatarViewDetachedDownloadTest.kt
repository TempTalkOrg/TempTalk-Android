package com.difft.android.chat.common

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.glide.GlideCacheKeyManager
import com.difft.android.chat.R
import com.difft.android.chat.messages.TestScopeApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression guard for [AvatarView]: an avatar download that finishes while the view is DETACHED
 * (RecyclerView view cache / off-screen row) must not be dropped.
 *
 * The cache-miss branch of `loadAvatarInternal` downloads on the lifecycle scope and can only apply
 * the bitmap while attached; `onAttachedToWindow` replays only `pendingAvatarParams`, which
 * `setAvatar` already cleared for an attached view. Without the fix the view stays on the letter
 * avatar until some later `setAvatar`. The fix re-arms `pendingAvatarParams` so the re-attach hits
 * the (now warm) disk cache.
 *
 * FragmentActivity + AppCompat theme so `Glide.with(view)` resolves a host (project precedent:
 * ChatMessageViewHolderQuoteThumbnailTest). Glide's own load may not complete under Robolectric, so
 * assertions are on `iv_avatar` visibility — which `loadAvatarInternal` sets synchronously — never
 * on the drawable.
 *
 * Verify: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class AvatarViewDetachedDownloadTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity
    private lateinit var parent: FrameLayout

    private lateinit var cacheFile: File
    private val deferred = CompletableDeferred<File?>()

    private lateinit var fallbackCacheFile: File
    private val fallbackDeferred = CompletableDeferred<File?>()

    /** Flipped by the ensureCached stub once the download "completes", so getCacheFile turns warm. */
    @Volatile
    private var cacheWarm = false

    @Volatile
    private var fallbackCacheWarm = false

    /** Set on the IO hop right after the download resumes — the signal the coroutine got past await. */
    @Volatile
    private var downloadResumed = false

    @Volatile
    private var fallbackDownloadResumed = false

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
        parent = FrameLayout(activity)
        activity.setContentView(parent)

        cacheFile = File.createTempFile("avatar_a1", null).apply { deleteOnExit() }
        fallbackCacheFile = File.createTempFile("avatar_a2", null).apply { deleteOnExit() }

        // Keep the encrypted-cache readiness probe off the Keystore in unit tests.
        mockkObject(GlideCacheKeyManager)
        every { GlideCacheKeyManager.isCacheKeyReady(any()) } returns false

        mockkObject(AvatarUtil)
        every { AvatarUtil.getCacheFile(URL) } answers { if (cacheWarm) cacheFile else null }
        coEvery { AvatarUtil.ensureCached(any(), URL, any()) } coAnswers {
            val file = deferred.await()
            cacheWarm = file != null
            downloadResumed = true
            file
        }
        every { AvatarUtil.getCacheFile(FALLBACK_URL) } answers { if (fallbackCacheWarm) fallbackCacheFile else null }
        coEvery { AvatarUtil.ensureCached(any(), FALLBACK_URL, any()) } coAnswers {
            val file = fallbackDeferred.await()
            fallbackCacheWarm = file != null
            fallbackDownloadResumed = true
            file
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
        runCatching { controller.destroy() }
    }

    @Test
    fun downloadFinishingWhileDetached_isReplayedOnReattach() {
        val avatarView = AvatarView(activity)
        parent.addView(avatarView, SIZE_PX, SIZE_PX)
        // setAvatar first: the ViewBinding delegate inflates the <merge> children on first access.
        avatarView.setAvatar(url = URL, key = "k", firstLetter = "A", id = "+123")
        idle()

        val letterCard = avatarView.findViewById<View>(R.id.cv_avatar)
        val letterText = avatarView.findViewById<android.widget.TextView>(R.id.tv_avatar)
        val image = avatarView.findViewById<View>(R.id.iv_avatar)

        // Cache miss: letter avatar shown, image hidden, download in flight.
        assertEquals(View.VISIBLE, letterCard.visibility)
        assertEquals("A", letterText.text.toString())
        assertEquals(View.GONE, image.visibility)

        // Detach (recycled off-screen), THEN let the download finish.
        parent.removeView(avatarView)
        deferred.complete(cacheFile)
        idleUntil { downloadResumed }
        idle()

        // Nothing may be applied while detached.
        assertEquals(View.GONE, image.visibility)

        // Re-attach: the replayed params hit the warm disk cache and the image is applied.
        parent.addView(avatarView, SIZE_PX, SIZE_PX)
        idle()

        assertEquals(View.VISIBLE, image.visibility)
    }

    /**
     * Same detached-replay path, but the PRIMARY download fails and the FALLBACK is what lands on
     * disk. The replay must target the fallback file already cached — not re-run the failing primary.
     */
    @Test
    fun fallbackDownloadFinishingWhileDetached_replaysFromFallbackCache() {
        val avatarView = AvatarView(activity)
        parent.addView(avatarView, SIZE_PX, SIZE_PX)
        avatarView.setAvatar(
            url = URL,
            key = "k1",
            firstLetter = "A",
            id = "+123",
            fallbackUrl = FALLBACK_URL,
            fallbackKey = "k2",
        )
        idle()

        val image = avatarView.findViewById<View>(R.id.iv_avatar)
        assertEquals(View.GONE, image.visibility)

        // Detach, then fail the primary and let the fallback land.
        parent.removeView(avatarView)
        deferred.complete(null)
        fallbackDeferred.complete(fallbackCacheFile)
        idleUntil { fallbackDownloadResumed }
        idle()

        assertEquals(View.GONE, image.visibility)

        // Re-attach: the fallback cache is warm, the primary is still cold.
        parent.addView(avatarView, SIZE_PX, SIZE_PX)
        idle()

        assertEquals(View.VISIBLE, image.visibility)
        coVerify(exactly = 1) { AvatarUtil.ensureCached(any(), URL, any()) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Drains the main looper until [condition] holds (the download resumes on a real IO thread). */
    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(WAIT_STEP_MS)
        }
    }

    private companion object {
        const val URL = "https://x/avatar/a1"
        const val FALLBACK_URL = "https://x/avatar/a2"
        const val SIZE_PX = 100
        const val WAIT_TIMEOUT_MS = 5_000L
        const val WAIT_STEP_MS = 5L
    }
}

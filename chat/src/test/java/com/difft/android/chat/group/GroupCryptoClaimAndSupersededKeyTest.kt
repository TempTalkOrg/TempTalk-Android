package com.difft.android.chat.group

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.R
import com.difft.android.chat.crypto.shouldApplyInitiatorRotation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Group-info crypto operations: the operation claim is released on
 * every path, and a rotation whose key was superseded meanwhile distributes nothing. (The page's
 * wait-dialog claim is covered by PageWaitTest.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupCryptoClaimAndSupersededKeyTest {

    class Page : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)
    }

    @After
    fun tearDown() = GroupCryptoOperations.end("g1")

    private fun page(): Fragment {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        return Page().also { activity.supportFragmentManager.beginTransaction().add(android.R.id.content, it).commitNow() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the claim is held while the operation runs and released when it ends`() {
        val page = page()
        val server = CompletableDeferred<Unit>()

        assertTrue(GroupCryptoOperations.launch(page, "g1") { server.await() })
        idle()
        assertTrue("g1" in GroupCryptoOperations.running.value)
        assertFalse("a second one is refused", GroupCryptoOperations.launch(page, "g1") {})

        server.complete(Unit)
        idle()
        assertFalse("g1" in GroupCryptoOperations.running.value)
    }

    @Test
    fun `a superseded rotation key distributes nothing`() = runTest {
        var distributed = 0
        assertEquals(
            InitiatorRotation.SUPERSEDED,
            applyInitiatorRotation(setRotated = { false }, distribute = { distributed++ }),
        )
        assertEquals(0, distributed)

        assertEquals(
            InitiatorRotation.APPLIED,
            applyInitiatorRotation(setRotated = { true }, distribute = { distributed++ }),
        )
        assertEquals(1, distributed)
    }

    @Test
    fun `a superseded rotation still reports the reset as done`() {
        // The server accepted our rotation; a newer key on top of it does not undo the reset.
        assertEquals(R.string.group_crypto_reset_success, rotationResetMessage(InitiatorRotation.SUPERSEDED))
        assertEquals(R.string.group_crypto_reset_success, rotationResetMessage(InitiatorRotation.APPLIED))
    }

    @Test
    fun `a rotation takes the server version, else one step past its request base`() {
        assertEquals(7, rotatedKeyVersion(serverVersion = 7, requestBase = 3))
        // A newer key stored meanwhile (say v9) must not lift the fallback above it: base 3 → 4,
        // which the stale-version check then refuses.
        assertEquals(4, rotatedKeyVersion(serverVersion = null, requestBase = 3))
        assertFalse(shouldApplyInitiatorRotation(storedVersion = 9, version = rotatedKeyVersion(null, 3)))
    }
}

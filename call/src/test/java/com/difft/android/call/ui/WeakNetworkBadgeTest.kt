package com.difft.android.call.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.difft.android.call.core.CallUiController
import com.difft.android.call.network.NetworkQualityLevel
import com.difft.android.call.network.NetworkQualityView
import com.difft.android.call.service.TestScopeApplication
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rendering cases for [ParticipantWeakNetworkBadge]. Needs no ViewModel, Hilt or harness: the badge
 * takes plain values plus a bare [CallUiController], which is the direct benefit of keeping the whole
 * render decision in `resolveBadge`.
 *
 * The badge is the ONLY surface for a bad remote in a multi-party call, so what these cases pin is
 * the full visibility matrix — including the four states that must render nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [30])
class WeakNetworkBadgeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: CallUiController

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        controller = CallUiController()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun setBadge(
        view: NetworkQualityView,
        identity: String,
        participantCount: Int = GROUP_COUNT,
        localIdentity: String = LOCAL_ID,
    ) {
        controller.setNetworkQuality(view)
        composeTestRule.setContent {
            // The call screen is a forced-dark subtree, matching CallContent.
            DifftTheme(darkTheme = true) {
                ParticipantWeakNetworkBadge(
                    controller = controller,
                    localIdentity = localIdentity,
                    identity = identity,
                    participantCount = participantCount,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun assertBadgeShown(message: String) {
        assertEquals(message, 1, composeTestRule.onAllNodesWithTag(BADGE_TAG).fetchSemanticsNodes().size)
    }

    private fun assertBadgeAbsent(message: String) {
        assertEquals(message, 0, composeTestRule.onAllNodesWithTag(BADGE_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun `T21a - the local tile shows no badge even when the local link is bad`() {
        setBadge(NetworkQualityView(local = NetworkQualityLevel.BAD), identity = LOCAL_ID)

        assertBadgeAbsent("a bad local link is the top banner's job — the local tile stays clean")
    }

    @Test
    fun `T21b - a bad remote tile shows the badge`() {
        setBadge(NetworkQualityView(remote = mapOf(PEER_ID to NetworkQualityLevel.BAD)), identity = PEER_ID)

        assertBadgeShown("the badge is the only surface for a bad peer in a multi-party call")
    }

    @Test
    fun `T21c - only the bad participant is badged`() {
        setBadge(NetworkQualityView(remote = mapOf(PEER_ID to NetworkQualityLevel.BAD)), identity = "bob")

        assertBadgeAbsent("a healthy peer must stay clean while another peer is bad")
    }

    @Test
    fun `T21d - two people in the call hand the badge to the banner`() {
        setBadge(
            NetworkQualityView(remote = mapOf(PEER_ID to NetworkQualityLevel.BAD)),
            identity = PEER_ID,
            participantCount = 2,
        )

        assertBadgeAbsent("with one peer the banner names them; the tile must not double-report it")
    }

    @Test
    fun `T21e - a non-bad remote tier renders nothing`() {
        setBadge(NetworkQualityView(remote = mapOf(PEER_ID to NetworkQualityLevel.GOOD)), identity = PEER_ID)

        assertBadgeAbsent("the design draws no state below bad")
    }

    @Test
    fun `T21f - a suppressed snapshot renders nothing`() {
        setBadge(NetworkQualityView(suppressed = true), identity = PEER_ID)

        assertBadgeAbsent("a suppressed snapshot is emptied upstream — the badge never reads the flag")
    }

    @Test
    fun `T21g - an identity absent from the snapshot renders nothing`() {
        setBadge(NetworkQualityView(local = NetworkQualityLevel.BAD), identity = "ghost")

        assertBadgeAbsent("an unknown identity is healthy, not a fault")
    }

    @Test
    fun `T21h - the badge announces the peer-side tip to screen readers`() {
        setBadge(NetworkQualityView(remote = mapOf(PEER_ID to NetworkQualityLevel.BAD)), identity = PEER_ID)

        assertBadgeShown("precondition: the description can only be read off a rendered badge")
        // Resolved through resources, never a literal: a wording change must fail the string-parity
        // case that owns the copy, not this one.
        val expected = ResUtils.getString(R.string.call_other_network_poor_tip)
        assertEquals(
            "the glyph carries no text — a null or wrong description leaves the badge silent",
            1,
            composeTestRule
                .onAllNodesWithContentDescription(expected, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
    }

    private companion object {
        const val BADGE_TAG = "call_weak_network_badge"
        const val LOCAL_ID = "self"
        const val PEER_ID = "alice"

        /** Three people: above the two-person hand-off, so the badge is the active surface. */
        const val GROUP_COUNT = 3
    }
}

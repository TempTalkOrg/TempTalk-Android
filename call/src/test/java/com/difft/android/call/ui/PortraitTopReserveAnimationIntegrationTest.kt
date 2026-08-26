package com.difft.android.call.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.difft.android.call.core.CallUiController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Status-bar inset the harnesses feed in place of real window insets. */
private val HARNESS_TOP_INSET = 24.dp

/** Mirrors production's private `PORTRAIT_HORIZONTAL_PADDING`. */
private val HARNESS_HORIZONTAL_PADDING = 16.dp

/** Mirrors production's private `PORTRAIT_CELL_GAP_DP`. */
private val HARNESS_CELL_GAP = 8.dp

/** Mirrors production's private `PORTRAIT_PIP_ZERO_PADDING`. */
private val HARNESS_PIP_ZERO_PADDING = PaddingValues(0.dp)

/** Mirrors the PiP call site's literal outer bottom padding. */
private val HARNESS_PIP_BOTTOM_PADDING = 4.dp

/** Mirrors production's private `PORTRAIT_SCROLL_FROM`. */
private const val HARNESS_SCROLL_FROM = 7

/**
 * Participant count used by every scrolling-gallery row (4 rows of 2 at `w360dp`). Chosen so
 * that **all** items are composed in both the shown and the hidden state, which is what lets
 * TC11 read the composition counter as an invariant.
 */
private const val GALLERY_COUNT = 8

/**
 * Participant count for TC10 and every bottom-inset row, TC12 / TC29 / TC30 (6 rows of 2).
 *
 * A `LazyVerticalGrid` wraps its content height when the content is shorter than the viewport, so
 * the container-invariance contract is only observable when the content overflows in **both**
 * states: at [GALLERY_COUNT] the hidden state fits inside `h740dp` and the container legitimately
 * shrinks with its content. The bottom-inset rows need the same overflow for a different reason —
 * a gallery that cannot scroll has no scroll end, so its bottom gap would measure leftover
 * viewport instead of the reserve under test.
 */
private const val GALLERY_OVERFLOW_COUNT = 12

/** Participant count for the top-aligned fixed grid (3 rows of 2). */
private const val FIXED_GRID_COUNT = 6

/** Participant count for the centred branch that deliberately does not respond. */
private const val CENTRED_GRID_COUNT = 4

/** dp tolerance for every bounds comparison. */
private const val TOLERANCE = 1f

/** Frames TC15 allows the 6 → 7 branch swap to compose and lay out before asserting. */
private const val SWAP_FRAME_BUDGET = 10

/**
 * Integration tests for the responsive portrait chrome reserves (TC7–TC19, TC29–TC30).
 *
 * These rows assert the **rendered** consequence of the production mechanisms behind the
 * responsive gallery — [rememberTopBarRevealProgress], [DeferredTopPaddingValues] and
 * [rememberGalleryContentPadding] — plus the performance contract that makes them worth having
 * (no item recomposition per animation frame).
 *
 * Commit 3 extends the gallery's content padding with a responsive **bottom** edge off the same
 * reveal progress (TC12 amended, TC29, TC30), and switches the fixed ≤6 grid to full-height
 * centring via [portraitCenteredTop], which makes that branch inert across a chrome toggle
 * (TC16, TC18) — the placement-only `Modifier.offset { }` it used to animate is gone.
 *
 * The fixed grid's own bottom contract is the mirror of the gallery's: TC16 covers the height-bound
 * regime where [portraitCenteredTop]'s bottom clamp binds, TC18 the slack-present regime where it
 * is inert, and both assert the last row clears [PORTRAIT_BOTTOM_RESERVED] just as TC12 / TC29 do
 * for the gallery at its scroll end.
 *
 * ## What the harness owns and what production owns
 *
 * `MultiParticipantItem` cannot be composed under Robolectric (Hilt `EntryPointAccessors`,
 * a LiveKit `Room`, an `AndroidView` renderer), so each leaf is a plain [Box] carrying the
 * production `call_render_participant_$index` tag. Every **decision** still comes from
 * production code:
 *
 * | Element | Source |
 * |---|---|
 * | Which reserve for a count/state | [portraitTopReserved] |
 * | Whether a branch responds at all | [portraitTopFollowsTitleBar] |
 * | Duration, easing, mount-snap semantics | [rememberTopBarRevealProgress] + [TOP_REVEAL_ANIM_MS] |
 * | Measure-time padding resolution | [DeferredTopPaddingValues] |
 * | Cell sizing | [computePortraitCells] |
 * | State source | a real [CallUiController] — never a mock, or the rows pass vacuously |
 *
 * Only container boilerplate (grid columns/arrangement, `BoxWithConstraints`) and the leaf
 * `Box` are harness-side; neither carries a decision.
 *
 * ## Expected positions
 *
 * Never hardcoded. Every expectation is `HARNESS_TOP_INSET + portraitTopReserved(count, state)`,
 * so a change to the production reserve moves the test with it instead of failing spuriously.
 *
 * ## Time control
 *
 * `mainClock.autoAdvance = false` throughout — mid-animation samples (TC9, TC13) and the
 * frame-by-frame counter (TC11) are otherwise non-deterministic.
 *
 * **No Hilt.** The subjects are pure composables plus a no-arg [CallUiController]; there is no
 * DI graph, no repository and no ViewModel to inject, so a single `createComposeRule()` is the
 * whole rule set.
 *
 * [LargeClass] is suppressed deliberately: the rows form one inventory-bound suite sharing the
 * three harness composables above — splitting by group would either duplicate the harnesses or
 * hoist them into a shared file that no longer documents which decisions are production-owned.
 */
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w360dp-h740dp-port-xhdpi")
class PortraitTopReserveAnimationIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // =================================================================================
    // Harnesses
    // =================================================================================

    /** Leaf cell — substitutes the un-composable `MultiParticipantItem`, carries no logic. */
    @Composable
    private fun ParticipantCell(
        index: Int,
        modifier: Modifier,
        compositions: AtomicInteger?,
    ) {
        if (compositions != null) {
            SideEffect { compositions.incrementAndGet() }
        }
        Box(modifier = modifier.testTag("call_render_participant_$index"))
    }

    /**
     * Grid container — pure scaffolding, mirrors `PortraitScrollGallery`'s shape.
     *
     * @param stateSink when non-null, receives the grid's scroll state so a row can assert it has
     *   genuinely reached the scroll end. Hoisting the state is behaviour-neutral: `LazyVerticalGrid`
     *   defaults to the same `rememberLazyGridState()`.
     */
    @Composable
    private fun ParticipantGrid(
        participantCount: Int,
        gridModifier: Modifier,
        contentPadding: PaddingValues,
        compositions: AtomicInteger?,
        stateSink: AtomicReference<LazyGridState>? = null,
    ) {
        val gridState = rememberLazyGridState()
        stateSink?.set(gridState)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            verticalArrangement = Arrangement.spacedBy(HARNESS_CELL_GAP),
            horizontalArrangement = Arrangement.spacedBy(HARNESS_CELL_GAP),
            contentPadding = contentPadding,
            modifier = Modifier
                .testTag("call_render_multi_grid")
                .then(gridModifier),
        ) {
            items(count = participantCount, key = { index -> index }) { index ->
                ParticipantCell(
                    index = index,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    compositions = compositions,
                )
            }
        }
    }

    /**
     * Harness 1 — the `useScrollGrid` branch, including production's PiP / 7+ call-site split.
     * `forceScrollGrid = true` reproduces the PiP shape: static padding, zero content padding,
     * and no reveal subscription at all.
     */
    @Composable
    private fun ScrollGalleryHarness(
        controller: CallUiController,
        participantCount: Int,
        forceScrollGrid: Boolean = false,
        compositions: AtomicInteger? = null,
        stateSink: AtomicReference<LazyGridState>? = null,
    ) {
        if (!portraitTopFollowsTitleBar(participantCount, forceScrollGrid)) {
            ParticipantGrid(
                participantCount = participantCount,
                gridModifier = Modifier.padding(
                    start = HARNESS_HORIZONTAL_PADDING,
                    top = HARNESS_TOP_INSET + HARNESS_HORIZONTAL_PADDING,
                    end = HARNESS_HORIZONTAL_PADDING,
                    bottom = HARNESS_PIP_BOTTOM_PADDING,
                ),
                contentPadding = HARNESS_PIP_ZERO_PADDING,
                compositions = compositions,
                stateSink = stateSink,
            )
        } else {
            // Production owns the whole padding decision — the harness only supplies the inset.
            val reveal = rememberTopBarRevealProgress(controller)
            val contentPadding =
                rememberGalleryContentPadding(HARNESS_TOP_INSET, participantCount, reveal)
            ParticipantGrid(
                participantCount = participantCount,
                gridModifier = Modifier,
                contentPadding = contentPadding,
                compositions = compositions,
                stateSink = stateSink,
            )
        }
    }

    /**
     * Harness 2 — the ≤6 fixed grid, mirroring `FixedPortraitGrid`: the chrome reserve bounds the
     * tile size, and the block is then centred on the **full** height via production's
     * [portraitCenteredTop]. It takes no [CallUiController] because production's fixed grid reads
     * no chrome state — that inertness is structural, not animated.
     */
    @Composable
    private fun FixedGridHarness(participantCount: Int) {
        val topChrome = HARNESS_TOP_INSET + portraitTopReserved(participantCount, topVisible = true)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .testTag("call_render_multi_grid")
                .padding(horizontal = HARNESS_HORIZONTAL_PADDING),
            contentAlignment = Alignment.TopCenter,
        ) {
            val layout = computePortraitCells(
                count = participantCount,
                availableWidthDp = maxWidth.value,
                availableHeightDp = (maxHeight - topChrome - PORTRAIT_BOTTOM_RESERVED).value,
                gapDp = HARNESS_CELL_GAP.value,
            )
            val cellWidth = layout.cellWidthDp.dp
            val cellHeight = layout.cellHeightDp.dp
            val rows = (0 until participantCount).chunked(layout.columns)
            val contentHeight = cellHeight * layout.rows + HARNESS_CELL_GAP * (layout.rows - 1)

            Column(
                modifier = Modifier.padding(
                    top = portraitCenteredTop(
                        availableHeight = maxHeight,
                        contentHeight = contentHeight,
                        minTop = topChrome,
                        bottomReserve = PORTRAIT_BOTTOM_RESERVED,
                    )
                ),
                verticalArrangement = Arrangement.spacedBy(HARNESS_CELL_GAP),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                rows.forEach { rowIndices ->
                    Row(horizontalArrangement = Arrangement.spacedBy(HARNESS_CELL_GAP)) {
                        rowIndices.forEach { index ->
                            ParticipantCell(
                                index = index,
                                modifier = Modifier.width(cellWidth).height(cellHeight),
                                compositions = null,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Harness 3 — the top-level branch of `PortraitParticipantLayout`, so a participant count
     * change can cross the fixed-grid → scrolling-gallery boundary and remount the subtree.
     */
    @Composable
    private fun SwitchingPortraitHarness(
        controller: CallUiController,
        participantCount: Int,
    ) {
        if (participantCount >= HARNESS_SCROLL_FROM) {
            ScrollGalleryHarness(controller, participantCount)
        } else {
            FixedGridHarness(participantCount)
        }
    }

    // =================================================================================
    // Helpers
    // =================================================================================

    private fun itemBounds(index: Int): DpRect =
        composeTestRule.onNodeWithTag("call_render_participant_$index").getUnclippedBoundsInRoot()

    private fun itemTop(index: Int): Float = itemBounds(index).top.value

    // DpRect's own `width` / `height` extensions are shadowed here by the Modifier ones.
    private fun DpRect.widthDp(): Float = (right - left).value

    private fun DpRect.heightDp(): Float = (bottom - top).value

    private fun gridBounds(): DpRect =
        composeTestRule.onNodeWithTag("call_render_multi_grid").getUnclippedBoundsInRoot()

    private fun rootBottom(): Float =
        composeTestRule.onRoot().getUnclippedBoundsInRoot().bottom.value

    /**
     * Scrolls the gallery to its last item and returns the gap left between that item's bottom
     * edge and the bottom of the viewport — i.e. the rendered consequence of the gallery's bottom
     * content inset.
     *
     * [state] guards against a vacuous pass: a gallery whose content fits the viewport has no
     * scroll end, and the gap measured would be leftover viewport rather than the reserve under
     * test. Asserting the grid can no longer scroll forward pins the measurement to true maximum
     * scroll, which is the only offset at which the bottom inset is fully exposed.
     */
    private fun scrollToEndAndMeasureBottomGap(count: Int, state: LazyGridState): Float {
        composeTestRule.onNodeWithTag("call_render_multi_grid").performScrollToIndex(count - 1)
        composeTestRule.mainClock.advanceTimeBy(100L)
        assertTrue(
            "the gallery must be at its scroll end, or this is not a scroll-end gap — the " +
                "content has to overflow the viewport for the bottom reserve to be observable",
            !state.canScrollForward,
        )
        return rootBottom() - itemBounds(count - 1).bottom.value
    }

    /** The only source of expected positions: production's reserve plus the harness inset. */
    private fun expectedTop(count: Int, topVisible: Boolean): Float =
        (HARNESS_TOP_INSET + portraitTopReserved(count, topVisible)).value

    /**
     * Expected top of the centred fixed grid, derived entirely from production
     * ([computePortraitCells] for the tile size, [portraitCenteredTop] for the placement) so a
     * change to either moves this expectation with it instead of failing spuriously.
     */
    private fun expectedCenteredTop(count: Int): Float {
        // `DpRect.height` is shadowed by the Modifier extension imported here.
        val rootBounds = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val rootHeight = rootBounds.bottom - rootBounds.top
        val topChrome = HARNESS_TOP_INSET + portraitTopReserved(count, topVisible = true)
        val layout = computePortraitCells(
            count = count,
            availableWidthDp = (360.dp - HARNESS_HORIZONTAL_PADDING * 2).value,
            availableHeightDp = (rootHeight - topChrome - PORTRAIT_BOTTOM_RESERVED).value,
            gapDp = HARNESS_CELL_GAP.value,
        )
        val contentHeight =
            layout.cellHeightDp.dp * layout.rows + HARNESS_CELL_GAP * (layout.rows - 1)
        return portraitCenteredTop(
            availableHeight = rootHeight,
            contentHeight = contentHeight,
            minTop = topChrome,
            bottomReserve = PORTRAIT_BOTTOM_RESERVED,
        ).value
    }

    /**
     * Asserts the fixed grid's **last** cell keeps the whole [PORTRAIT_BOTTOM_RESERVED] band clear,
     * i.e. it renders above the floating control bar and barrage entry rather than under them.
     *
     * The gallery's equivalent contract is measured at its scroll end (TC12 / TC29); the fixed grid
     * does not scroll, so its last row's bottom edge is directly observable.
     */
    private fun assertLastCellClearsBottomReserve(count: Int) {
        val lastBottom = itemBounds(count - 1).bottom.value
        val limit = rootBottom() - PORTRAIT_BOTTOM_RESERVED.value
        assertTrue(
            "the last row must stay at least the control-bar + barrage-entry reserve " +
                "(${PORTRAIT_BOTTOM_RESERVED.value}dp) above the viewport bottom, otherwise it " +
                "renders under the floating controls — bottom ${lastBottom}dp vs limit ${limit}dp",
            lastBottom <= limit + TOLERANCE,
        )
    }

    private fun assertItemTop(message: String, expected: Float, index: Int = 0) =
        assertEquals(message, expected, itemTop(index), TOLERANCE)

    /** Asserts item [index] sits where production's reserve for ([count], [topVisible]) puts it. */
    private fun assertItemAtReserve(
        message: String,
        count: Int,
        topVisible: Boolean,
        index: Int = 0,
    ) = assertItemTop(message, expectedTop(count, topVisible), index)

    private fun startHarness(content: @Composable () -> Unit) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { content() }
        composeTestRule.mainClock.advanceTimeByFrame()
    }

    private fun settleAnimation() {
        composeTestRule.mainClock.advanceTimeBy((TOP_REVEAL_ANIM_MS + 50).toLong())
    }

    // =================================================================================
    // Group B — M1, the 7+ scrolling gallery
    // =================================================================================

    /** TC7 — hide path, settled. The #1128 regression row: the black band is gone. */
    @Test
    fun `gallery first row rises to the status bar when the title hides`() {
        val controller = CallUiController()
        startHarness { ScrollGalleryHarness(controller, GALLERY_COUNT) }

        assertItemAtReserve("title shown baseline", GALLERY_COUNT, topVisible = true)

        controller.setShowTopStatusViewEnabled(false)
        settleAnimation()

        assertItemAtReserve(
            "with the title hidden the grid must keep only the status-bar inset",
            GALLERY_COUNT,
            topVisible = false,
        )
    }

    /** TC8 — show path: the reserve is restored exactly, with no drift. */
    @Test
    fun `gallery first row returns to the reserved position when the title shows again`() {
        val controller = CallUiController()
        startHarness { ScrollGalleryHarness(controller, GALLERY_COUNT) }

        controller.setShowTopStatusViewEnabled(false)
        settleAnimation()
        assertItemAtReserve("hidden state must settle first", GALLERY_COUNT, topVisible = false)

        controller.setShowTopStatusViewEnabled(true)
        settleAnimation()

        assertItemAtReserve(
            "showing the title again must restore the exact reserve",
            GALLERY_COUNT,
            topVisible = true,
        )
    }

    /**
     * TC9 — **framework assumption**. compose-foundation must evaluate
     * `PaddingValues.calculateTopPadding()` inside the LazyGrid's *measure* lambda; that is the
     * whole premise of [DeferredTopPaddingValues]. If a Compose BOM upgrade ever hoists the read
     * into composition, the animation silently freezes at an end state and this row fails loudly.
     */
    @Test
    fun `deferred content padding is resolved at measure time so the grid animates`() {
        val controller = CallUiController()
        startHarness { ScrollGalleryHarness(controller, GALLERY_COUNT) }

        controller.setShowTopStatusViewEnabled(false)
        composeTestRule.mainClock.advanceTimeBy((TOP_REVEAL_ANIM_MS / 2).toLong())

        val mid = itemTop(0)
        val shown = expectedTop(GALLERY_COUNT, topVisible = true)
        val hidden = expectedTop(GALLERY_COUNT, topVisible = false)

        assertTrue(
            "mid-flight top ($mid) must be clear of the hidden end state ($hidden) — " +
                "a frozen animation would sit exactly on one end",
            mid > hidden + 4f,
        )
        assertTrue(
            "mid-flight top ($mid) must be clear of the shown end state ($shown)",
            mid < shown - 4f,
        )
    }

    /**
     * TC10 — the M1 viewport invariant. `contentPadding` keeps the grid container full-screen;
     * the cells move inside it. Movement is asserted on `call_render_participant_0`, never here.
     *
     * Uses [GALLERY_OVERFLOW_COUNT] rather than [GALLERY_COUNT]: a lazy grid whose content is
     * shorter than the viewport wraps to its content height, and at 8 participants the hidden
     * state fits — the container would then shrink for a reason unrelated to this contract.
     */
    @Test
    fun `gallery container bounds are invariant across the whole reveal cycle`() {
        val controller = CallUiController()
        startHarness { ScrollGalleryHarness(controller, GALLERY_OVERFLOW_COUNT) }

        val before = gridBounds()
        val itemTopBefore = itemTop(0)

        controller.setShowTopStatusViewEnabled(false)
        composeTestRule.mainClock.advanceTimeBy((TOP_REVEAL_ANIM_MS / 2).toLong())
        val midFlight = gridBounds()

        settleAnimation()
        val after = gridBounds()

        assertEquals("container must not move mid-flight", before, midFlight)
        assertEquals("container must not move once settled", before, after)
        assertTrue(
            "the cells must actually have moved — otherwise the invariance check is vacuous",
            itemTopBefore - itemTop(0) > 4f,
        )
    }

    /**
     * TC11 — **the performance red line**. Zero item recomposition per animation frame. This is
     * the machine-checkable proxy for "the toggle never invalidates the `items { }` lambda
     * scope"; a `collectAsState` in the enclosing scope would recompose every participant cell
     * on every one of the ~16 frames and fail this row.
     */
    @Test
    fun `no participant cell recomposes during the reveal animation`() {
        val controller = CallUiController()
        val compositions = AtomicInteger(0)
        startHarness { ScrollGalleryHarness(controller, GALLERY_COUNT, compositions = compositions) }

        composeTestRule.waitForIdle()
        val before = compositions.get()
        assertTrue("harness must have composed some cells to count", before > 0)

        controller.setShowTopStatusViewEnabled(false)
        // Frame by frame — a single 300 ms jump can coalesce frames and hide per-frame work.
        repeat(20) { composeTestRule.mainClock.advanceTimeBy(16L) }

        assertEquals(
            "participant cells must not recompose while the top reserve animates",
            before,
            compositions.get(),
        )
        assertItemAtReserve(
            "…and the animation must actually have run, or the counter check is vacuous",
            GALLERY_COUNT,
            topVisible = false,
        )
    }

    /**
     * TC12 — scroll-state coverage for the **bottom** inset, and the #1128 bottom-half regression
     * row. Scrolled to the end with the chrome visible, the last card must come to rest clear of
     * the floating controls, not underneath them.
     *
     * **This row's expectation was inverted on purpose.** It previously asserted the opposite —
     * that the last card reached the root's bottom edge with "no reserved dead band" — which is
     * exactly the reported defect: that band is not dead, it is where the control bar and the
     * barrage entry float. iOS reserves the same space as a scroll content inset
     * (`RoomContextView`'s `visibleToolbarInset + bulletReservedInset`), and the user ruling of
     * 2026-08-06 adopted that behaviour.
     */
    @Test
    fun `gallery last card rests above the floating controls when scrolled to the end`() {
        val controller = CallUiController()
        val stateSink = AtomicReference<LazyGridState>()
        startHarness { ScrollGalleryHarness(controller, GALLERY_OVERFLOW_COUNT, stateSink = stateSink) }

        assertItemAtReserve("scroll-top baseline", GALLERY_OVERFLOW_COUNT, topVisible = true)

        val gap = scrollToEndAndMeasureBottomGap(GALLERY_OVERFLOW_COUNT, stateSink.get())

        assertTrue(
            "with the chrome visible the last card must stay at least the control-bar + " +
                "barrage-entry reserve (${PORTRAIT_BOTTOM_RESERVED.value}dp) above the viewport " +
                "bottom, otherwise it renders under the floating controls — got ${gap}dp",
            gap >= PORTRAIT_BOTTOM_RESERVED.value - TOLERANCE,
        )
    }

    /**
     * TC29 — the bottom edge is responsive, and it is resolved at **measure** time. Hiding the
     * chrome must collapse the reserve to [PORTRAIT_BOTTOM_RESIDUAL] so the gallery reclaims the
     * band the now-invisible controls no longer need.
     *
     * This row doubles as the bottom edge's framework-assumption check, the counterpart of TC9 for
     * the top: [DeferredTopPaddingValues]'s `bottom` lambda is only honoured if compose-foundation
     * evaluates `calculateBottomPadding()` inside the LazyGrid's measure lambda. Were it hoisted
     * into composition, the inset would stay frozen at its mount value of
     * [PORTRAIT_BOTTOM_RESERVED] and the collapse assertion below fails loudly.
     *
     * [GALLERY_OVERFLOW_COUNT] is load-bearing: the content must overflow in **both** chrome
     * states, or the "scrolled to the end" gap in the hidden state would just be leftover
     * viewport rather than the reserve under test.
     */
    @Test
    fun `gallery bottom inset collapses to the residual when the chrome hides`() {
        val controller = CallUiController()
        val stateSink = AtomicReference<LazyGridState>()
        startHarness { ScrollGalleryHarness(controller, GALLERY_OVERFLOW_COUNT, stateSink = stateSink) }

        val shownGap = scrollToEndAndMeasureBottomGap(GALLERY_OVERFLOW_COUNT, stateSink.get())

        controller.setShowTopStatusViewEnabled(false)
        settleAnimation()
        val hiddenGap = scrollToEndAndMeasureBottomGap(GALLERY_OVERFLOW_COUNT, stateSink.get())

        assertEquals(
            "with the chrome hidden the gallery must keep only the residual breathing gap",
            PORTRAIT_BOTTOM_RESIDUAL.value,
            hiddenGap,
            TOLERANCE,
        )
        assertTrue(
            "…and that must be strictly less than the chrome-visible reserve (${shownGap}dp), " +
                "or the inset is not responding to the toggle at all",
            hiddenGap < shownGap - TOLERANCE,
        )
    }

    /**
     * TC30 — PiP keeps its current bottom behaviour byte for byte. Its call site passes a static
     * zero content padding, so its cards still tile flush to the bottom of its own viewport (the
     * outer [HARNESS_PIP_BOTTOM_PADDING] margin), and the chrome toggle must not perturb that:
     * PiP renders no control bar and no barrage entry, so it has nothing to reserve for.
     */
    @Test
    fun `the pip gallery bottom is unreserved and unaffected by the chrome toggle`() {
        val controller = CallUiController()
        val stateSink = AtomicReference<LazyGridState>()
        startHarness {
            ScrollGalleryHarness(
                controller,
                GALLERY_OVERFLOW_COUNT,
                forceScrollGrid = true,
                stateSink = stateSink,
            )
        }

        val expectedGap = HARNESS_PIP_BOTTOM_PADDING.value
        val before = scrollToEndAndMeasureBottomGap(GALLERY_OVERFLOW_COUNT, stateSink.get())
        assertEquals(
            "PiP reserves nothing below its last card beyond its outer margin",
            expectedGap,
            before,
            TOLERANCE,
        )

        controller.setShowTopStatusViewEnabled(false)
        settleAnimation()

        val after = rootBottom() - itemBounds(GALLERY_OVERFLOW_COUNT - 1).bottom.value
        assertEquals("the PiP gallery's bottom must not move when the chrome hides", before, after, TOLERANCE)
    }

    /**
     * TC13 — interrupting the hide mid-flight must settle back on the exact shown position,
     * never stick at an intermediate offset.
     *
     * The clock budget is deliberately **two** durations, not one. `rememberTopBarRevealProgress`
     * collects with `collectLatest`, so a mid-flight reversal cancels the in-flight animation and
     * reverses from the current value — settling well within one duration. The two-duration budget
     * is kept so this row also holds under serialized-`collect` semantics (where the reversal only
     * starts after the first animation completes); settling on the exact shown position is what
     * this row asserts, and it is invariant across both semantics.
     */
    @Test
    fun `reversing the toggle mid animation settles on the shown position`() {
        val controller = CallUiController()
        startHarness { ScrollGalleryHarness(controller, GALLERY_COUNT) }

        controller.setShowTopStatusViewEnabled(false)
        composeTestRule.mainClock.advanceTimeBy(100L)

        controller.setShowTopStatusViewEnabled(true)
        composeTestRule.mainClock.advanceTimeBy((2 * TOP_REVEAL_ANIM_MS + 50).toLong())

        assertItemAtReserve(
            "a reversed animation must not leave the grid at an intermediate offset",
            GALLERY_COUNT,
            topVisible = true,
        )
    }

    /**
     * TC14 — mount must `snapTo`, not `animateTo`. Entering the branch with the title already
     * hidden (call start, PiP exit) must not produce a 250 ms slide.
     */
    @Test
    fun `mounting with the title already hidden snaps instead of animating`() {
        val controller = CallUiController()
        // `showTopStatusViewEnabled` defaults to true — seed the hidden state before composing.
        controller.setShowTopStatusViewEnabled(false)
        startHarness { ScrollGalleryHarness(controller, GALLERY_COUNT) }

        val expected = expectedTop(GALLERY_COUNT, topVisible = false)
        assertItemTop("first frame must already be at the hidden position", expected)

        repeat(5) {
            composeTestRule.mainClock.advanceTimeByFrame()
            assertItemTop("no intermediate position may appear on any early frame", expected)
        }
    }

    /**
     * TC15 — the most likely real-world regression: a participant joins while the title is
     * hidden, crossing 6 → 7 and remounting fixed grid → scrolling gallery. The new subtree must
     * mount already at the hidden position, with no replay and no flash of the reserved one.
     */
    @Test
    fun `crossing six to seven with the title hidden does not replay the animation`() {
        val controller = CallUiController()
        val participantCount = mutableStateOf(FIXED_GRID_COUNT)
        startHarness { SwitchingPortraitHarness(controller, participantCount.value) }

        controller.setShowTopStatusViewEnabled(false)
        settleAnimation()
        // The six-person grid is centred and chrome-independent, so hiding the chrome leaves it
        // exactly where it was — TC16 owns that contract. What matters here is the remount below.
        val fixedTopWhileHidden = itemTop(0)
        assertEquals(
            "the centred six-person grid must not move when the chrome hides",
            expectedCenteredTop(FIXED_GRID_COUNT),
            fixedTopWhileHidden,
            TOLERANCE,
        )

        composeTestRule.runOnIdle { participantCount.value = HARNESS_SCROLL_FROM }

        // Pump frames until the branch swap has landed: before it the tree still holds the centred
        // six-person grid, which sits elsewhere by design. Bounded, so a stuck swap still fails.
        var frames = 0
        while (frames < SWAP_FRAME_BUDGET && abs(itemTop(0) - fixedTopWhileHidden) < TOLERANCE) {
            composeTestRule.mainClock.advanceTimeByFrame()
            frames++
        }
        assertTrue("the branch swap must land within $SWAP_FRAME_BUDGET frames", frames < SWAP_FRAME_BUDGET)

        // Any replay would show intermediate positions across these frames.
        val expected = expectedTop(HARNESS_SCROLL_FROM, topVisible = false)
        repeat(4) {
            composeTestRule.mainClock.advanceTimeByFrame()
            assertItemTop("the remounted gallery must appear at the hidden position on every frame", expected)
        }
    }

    // =================================================================================
    // Group C — M2 (the 5–6 fixed grid) and the non-responders
    // =================================================================================

    /**
     * TC16 — **the 5–6 placement contract in the height-bound regime**, and the row that would fail
     * under the top-aligned layout this commit replaces. A six-person block must land on
     * production's [portraitCenteredTop], keep its last row clear of the floating controls, and not
     * move when the chrome toggles.
     *
     * `@Config` is load-bearing twice over. At `w360dp-h740dp` a 6-person cell is width-bound
     * (160 dp) regardless of how the vertical space is apportioned, so a regression that fed the
     * tile-size computation a chrome-dependent height would still pass; at `h520dp` the cell is
     * height-bound, so any such regression changes the cell size and this row catches it. The
     * width-bound `assertTrue` fails fast if anyone moves the row to a taller qualifier.
     *
     * Height-bound is also precisely the regime where [portraitCenteredTop]'s **bottom** clamp
     * binds: the block already fills the band between the bars, so there is no slack for the
     * full-height centre to claim and the placement degrades to the top-chrome floor. That is why
     * this row asserts the floor rather than "slack above the first row" — the unclamped
     * full-height centre it used to expect is what slid the last row under the controls. TC18 owns
     * the complementary slack-present regime, where the centring is visible and the clamp inert.
     */
    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h520dp-port-xhdpi")
    fun `six person fixed grid is centred on the full height and never moves`() {
        val controller = CallUiController()
        startHarness { FixedGridHarness(FIXED_GRID_COUNT) }

        val before = itemBounds(0)
        val widthBoundSide = (360f - 2 * HARNESS_HORIZONTAL_PADDING.value - HARNESS_CELL_GAP.value) / 2
        assertTrue(
            "this row is only discriminating where the cell is height-bound; at " +
                "${before.widthDp()}dp it is width-bound, which means the qualifier is too tall",
            before.widthDp() < widthBoundSide - TOLERANCE,
        )
        assertEquals(
            "the block must sit where portraitCenteredTop puts it",
            expectedCenteredTop(FIXED_GRID_COUNT),
            before.top.value,
            TOLERANCE,
        )
        assertEquals(
            "a height-bound block has no slack, so the bottom clamp must pin it to the top-chrome " +
                "floor — anything lower pushes the last row into the control-bar band",
            expectedTop(FIXED_GRID_COUNT, topVisible = true),
            before.top.value,
            TOLERANCE,
        )
        assertLastCellClearsBottomReserve(FIXED_GRID_COUNT)

        controller.setShowTopStatusViewEnabled(false)
        settleAnimation()
        val after = itemBounds(0)
        assertLastCellClearsBottomReserve(FIXED_GRID_COUNT)

        assertEquals("a centred block must not move when the chrome hides", before.top.value, after.top.value, TOLERANCE)
        assertEquals(
            "cell width must not change — a resize would rescale the video surface",
            before.widthDp(),
            after.widthDp(),
            TOLERANCE,
        )
        assertEquals(
            "cell height must not change — a resize would rescale the video surface",
            before.heightDp(),
            after.heightDp(),
            TOLERANCE,
        )
    }

    /**
     * TC18 — the fixed grid is centred at every supported count, so the ≤4 branch follows the same
     * rule as 5–6. Its block must land on production's [portraitCenteredTop] and stay put across a
     * chrome toggle.
     *
     * This is the **slack-present** regime, complementary to TC16's height-bound one: at the
     * default `h740dp` a 4-person block is width-bound, so real slack survives below it, the
     * full-height centre is strictly below the top-chrome floor, and [portraitCenteredTop]'s bottom
     * clamp is inert. Both facts are asserted — the slack proves the centring is visible here, and
     * the last-row assertion proves centring never buys that visibility by pushing the block into
     * the control-bar band.
     */
    @Test
    fun `the four person grid is centred on the full height and never moves`() {
        val controller = CallUiController()
        startHarness { FixedGridHarness(CENTRED_GRID_COUNT) }

        val before = itemTop(0)
        assertEquals(
            "the centred block must land on the full-height centre",
            expectedCenteredTop(CENTRED_GRID_COUNT),
            before,
            TOLERANCE,
        )
        assertTrue(
            "this must be the centred branch — the first row should sit below the reserve",
            before > expectedTop(CENTRED_GRID_COUNT, topVisible = true) + TOLERANCE,
        )
        assertLastCellClearsBottomReserve(CENTRED_GRID_COUNT)

        controller.setShowTopStatusViewEnabled(false)
        settleAnimation()

        assertItemTop("the centred grid must stay exactly where it was", before)
        assertLastCellClearsBottomReserve(CENTRED_GRID_COUNT)
    }

    /**
     * TC19 — PiP renders no title bar, so its gallery must be completely inert. Its call site
     * keeps the static padding it has today and never subscribes to the reveal at all.
     */
    @Test
    fun `the pip gallery does not move when the title hides`() {
        val controller = CallUiController()
        startHarness { ScrollGalleryHarness(controller, GALLERY_COUNT, forceScrollGrid = true) }

        val before = itemTop(0)
        assertItemTop(
            "PiP keeps its static inset-plus-margin top padding",
            (HARNESS_TOP_INSET + HARNESS_HORIZONTAL_PADDING).value,
        )

        controller.setShowTopStatusViewEnabled(false)
        settleAnimation()

        assertItemTop("the PiP gallery must stay exactly where it was", before)
    }
}

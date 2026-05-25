package com.difft.android.call.ui.barrage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.BubbleMessageType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for `ShouldShowBarrageInput` validating the lazy-once
 * composition + conditional `placeRelative` design.
 *
 * Design: content is NOT composed until the first expand (ANR fix). After the
 * first expand, content stays in the tree permanently but is only placed
 * (via placeRelative) when expanded — unplaced content is excluded from
 * hit-testing and rendering, so collapsed picker never intercepts scroll/tap.
 *
 * Coverage matrix:
 *   #1  composition gate when never expanded (primary ANR-fix regression guard)
 *   #2  composition presence when expanded
 *   #3  lazy-once: after first expand, content stays in tree permanently
 *   #4  first expand puts subtree in tree
 *   #5  idle-closed tap does NOT invoke any toggle (no inner tapInterceptor)
 *   #6  outer Box has non-zero size in idle-closed state (size-preservation guard)
 *   #7  expanded outer Box width >= `SIMPLE_BARRAGE_UI_WIDTH`
 *   #8  expanded outer Box height >= `SIMPLE_BARRAGE_PICKER_MIN_HEIGHT`
 *   #9  expanded taps reach picker items
 *  #10  shouldShow=false tap → `toggleOverlays()` (outer Box interceptor)
 *  #11  share-screening expanded uses 360 dp width
 *  #12  1V1 portrait `alwaysShow` path — no toggle invoked
 *
 * `BubbleBarrageMessage` calls `colorResource(...)` against `:base` color resources;
 * every `setContent` block therefore wraps in `DifftTheme` so the correct
 * `LocalContext` / CompositionLocal stack is in place at composition time.
 *
 * `LCallViewModel` and `CallUiController` are mocked with MockK
 * (`relaxed = true`). MockK's default configuration mocks final classes, so no
 * `mockkClass` ceremony is required.
 */
// `qualifiers` widens the Robolectric viewport from the default 320 dp to
// 720 dp so that:
//   - the share-screening picker (`SIMPLE_BARRAGE_UI_WIDTH_SCREEN_SHARE = 360`)
//     can lay out at its natural width without being clamped by the parent.
//   - the bottom-anchored picker has visible space above the smiley row.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w720dp-h1280dp")
class ShouldShowBarrageInputTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Mutable so individual tests (e.g. #10, #12) can flip the bottom-bar
     * visibility before invoking `setBarrageContent`.
     */
    private val showBottomToolBarViewEnabled = MutableStateFlow(true)

    private val callUiController = mockk<CallUiController>(relaxed = true).also {
        every { it.showBottomToolBarViewEnabled } returns showBottomToolBarViewEnabled
        every { it.isInPipMode } returns MutableStateFlow(false)
        every { it.showSimpleBarrageEnabled } returns MutableStateFlow(false)
    }

    private val viewModel = mockk<LCallViewModel>(relaxed = true).also {
        every { it.callUiController } returns callUiController
    }

    /**
     * Base config — every one of `BarrageMessageConfig`'s 10 fields is supplied
     * explicitly so the constructor compiles regardless of which fields carry
     * defaults in the production source.
     */
    private val baseConfig = BarrageMessageConfig(
        isOneVOneCall = false,
        barrageTexts = emptyList(),
        displayDurationMillis = 6000L,
        showLimitCount = 6,
        baseSpeed = 0L,
        deltaSpeed = 0L,
        columns = listOf(0),
        emojiPresets = LCallUiConstants.DEFAULT_BUBBLE_EMOJIS,
        textPresets = LCallUiConstants.DEFAULT_BUBBLE_TEXTS,
        textMaxLength = 16
    )

    /**
     * Single render helper used by every test. Wraps content in `DifftTheme`
     * (required for `colorResource` lookups in `BubbleBarrageMessage`) and in
     * a 600x600 dp `Box` aligned to `BottomStart` with a top padding equal to
     * the picker's natural height. Two reasons for the wrapper:
     *
     *  1. The default Robolectric viewport (~320 dp wide) is narrower than
     *     the share-screening picker (360 dp), so `defaultMinSize` would be
     *     clamped by the parent constraint and tests asserting >= 360 dp
     *     would fail spuriously.
     *  2. The picker Box uses a custom `layout` modifier that places it at
     *     NEGATIVE y (above the smiley row) and reports zero height to the
     *     parent. With no padding above, the picker would be off-screen, and
     *     `onNodeWithText` can't reach the emoji items. The 240 dp top
     *     padding gives the picker a visible region above the smiley row
     *     that comfortably fits the picker's height (≈140 dp floor).
     */
    private fun setBarrageContent(
        expanded: Boolean,
        config: BarrageMessageConfig = baseConfig,
        isShareScreening: Boolean = false,
        isDualPane: Boolean = false,
        onClickItem: (String, BubbleMessageType) -> Unit = { _, _ -> }
    ) {
        composeTestRule.setContent {
            DifftTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(600.dp, 600.dp)
                        .padding(top = 240.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    ShouldShowBarrageInput(
                        viewModel = viewModel,
                        config = config,
                        expanded = expanded,
                        isDualPane = isDualPane,
                        isShareScreening = isShareScreening,
                        setExpanded = { /* test-controlled — flipped via state holder when needed */ },
                        onClickItem = onClickItem,
                        onShowInputOverlay = { }
                    )
                }
            }
        }
    }

    /**
     * Helper variant for tests that need to flip `expanded` between
     * recompositions (lazy-once permanence test #3 and first-expand test #4).
     * Same wrapping rationale as [setBarrageContent].
     */
    private fun setBarrageContentWithExpandedState(
        initialExpanded: Boolean,
        isShareScreening: Boolean = false,
        isDualPane: Boolean = false
    ): MutableState<Boolean> {
        val state = mutableStateOf(initialExpanded)
        composeTestRule.setContent {
            DifftTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(600.dp, 600.dp)
                        .padding(top = 240.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    var expanded by state
                    ShouldShowBarrageInput(
                        viewModel = viewModel,
                        config = baseConfig,
                        expanded = expanded,
                        isDualPane = isDualPane,
                        isShareScreening = isShareScreening,
                        setExpanded = { expanded = it },
                        onClickItem = { _, _ -> },
                        onShowInputOverlay = { }
                    )
                }
            }
        }
        return state
    }

    // -----------------------------------------------------------------
    // #1 composition gate when collapsed
    // -----------------------------------------------------------------
    @Test
    fun `expanded false - picker subtree is NOT in composition tree`() {
        setBarrageContent(expanded = false)

        composeTestRule.onNodeWithTag("barrage-picker-content").assertDoesNotExist()
    }

    // -----------------------------------------------------------------
    // #2 composition presence when expanded
    // -----------------------------------------------------------------
    @Test
    fun `expanded true - picker subtree IS in composition tree`() {
        setBarrageContent(expanded = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("barrage-picker-content").assertExists()
    }

    // -----------------------------------------------------------------
    // #3 lazy-once: after first expand, content stays in tree permanently
    // (controlled via conditional placeRelative — unplaced content is
    // excluded from hit-testing and rendering, avoiding scroll/tap
    // interference while keeping composition stable)
    // -----------------------------------------------------------------
    @Test
    fun `expanded true to false - picker stays in composition tree permanently`() {
        showBottomToolBarViewEnabled.value = true
        val state = setBarrageContentWithExpandedState(initialExpanded = true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("barrage-picker-content").assertExists()

        // Collapse — content must remain in the tree (lazy-once optimization).
        state.value = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("barrage-picker-content").assertExists()
    }

    // -----------------------------------------------------------------
    // #4 fade-in puts subtree in tree
    // -----------------------------------------------------------------
    @Test
    fun `expanded false to true - picker enters tree during fade-in window`() {
        val state = setBarrageContentWithExpandedState(initialExpanded = false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("barrage-picker-content").assertDoesNotExist()

        // Trigger fade-in. Let the test clock auto-advance so the
        // recomposition driven by the state change actually runs (with
        // `autoAdvance = false` the recomposition is deferred until we
        // manually advance, and the precise frame count required for
        // AnimatedVisibility to add its children to the tree is sensitive
        // to Robolectric / Compose UI test versions). Auto-advance + a
        // generous wait covers the full 80 ms fade-in window.
        composeTestRule.runOnIdle { state.value = true }
        composeTestRule.waitForIdle()

        // Subtree must be in the tree once visibility flips to true.
        composeTestRule.onNodeWithTag("barrage-picker-content").assertExists()
    }

    // -----------------------------------------------------------------
    // #5 idle-closed tap does NOT invoke any toggle (inner tapInterceptor
    // was removed; collapsed picker is not placed, so no hit-testing)
    // -----------------------------------------------------------------
    @Test
    fun `idle-closed tap on outer Box does not invoke any toggle`() {
        showBottomToolBarViewEnabled.value = true
        setBarrageContent(expanded = false)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("barrage-outer-box").performTouchInput {
            click(position = Offset(width / 2f, height / 2f))
        }
        composeTestRule.waitForIdle()

        verify(exactly = 0) { callUiController.toggleTopBottomBars() }
        verify(exactly = 0) { callUiController.toggleOverlays() }
    }

    // -----------------------------------------------------------------
    // #6 outer Box has non-zero measured size in idle-closed state
    // -----------------------------------------------------------------
    @Test
    fun `idle-closed - outer Box has non-zero size`() {
        setBarrageContent(expanded = false)
        composeTestRule.waitForIdle()

        val bounds = composeTestRule.onNodeWithTag("barrage-outer-box")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "outer Box width should be > 0 but was ${bounds.width}",
            bounds.width > 0f
        )
        assertTrue(
            "outer Box height should be > 0 but was ${bounds.height}",
            bounds.height > 0f
        )
    }

    // -----------------------------------------------------------------
    // #7 outer Box width >= SIMPLE_BARRAGE_UI_WIDTH (expanded state —
    // collapsed picker is not placed, so size assertions only apply
    // when the picker is visible)
    // -----------------------------------------------------------------
    @Test
    fun `expanded - outer Box width is at least bubbleWidthDp`() {
        setBarrageContent(expanded = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("barrage-outer-box")
            .assertWidthIsAtLeast(LCallUiConstants.SIMPLE_BARRAGE_UI_WIDTH.dp)
    }

    // -----------------------------------------------------------------
    // #8 outer Box height >= SIMPLE_BARRAGE_PICKER_MIN_HEIGHT (expanded)
    // -----------------------------------------------------------------
    @Test
    fun `expanded - outer Box height is at least picker min height`() {
        setBarrageContent(expanded = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("barrage-outer-box")
            .assertHeightIsAtLeast(LCallUiConstants.SIMPLE_BARRAGE_PICKER_MIN_HEIGHT.dp)
    }

    // -----------------------------------------------------------------
    // #9 expanded taps reach picker items
    // -----------------------------------------------------------------
    @Test
    fun `expanded true - taps on picker emoji invoke onClickItem`() {
        var clickedItem: Pair<String, BubbleMessageType>? = null
        setBarrageContent(
            expanded = true,
            onClickItem = { value, type -> clickedItem = value to type }
        )
        composeTestRule.waitForIdle()

        // First emoji from DEFAULT_BUBBLE_EMOJIS. We use `performClick()`
        // (semantics-based) rather than `assertIsDisplayed().performClick()`
        // because the picker is bottom-anchored and may be partially clipped
        // by the parent Box's bounds depending on test viewport — the click
        // dispatch still succeeds via Compose's semantics tree.
        composeTestRule.onNodeWithText(LCallUiConstants.DEFAULT_BUBBLE_EMOJIS.first())
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            "expected emoji click but onClickItem received $clickedItem",
            clickedItem == LCallUiConstants.DEFAULT_BUBBLE_EMOJIS.first() to BubbleMessageType.EMOJI
        )
        verify(exactly = 0) { callUiController.toggleTopBottomBars() }
    }

    // -----------------------------------------------------------------
    // #10 shouldShow=false tap routes to toggleOverlays
    // -----------------------------------------------------------------
    @Test
    fun `shouldShow false - tap invokes toggleOverlays via outer Box interceptor`() {
        showBottomToolBarViewEnabled.value = false
        // isOneVOneCall=false + isShareScreening=false → alwaysShow=false
        // bottomEnabledState=false → shouldShow=false.
        setBarrageContent(expanded = false)
        composeTestRule.waitForIdle()

        // Tap on the smiley-row icon. The OUTER Box's tapInterceptor
        // is armed (enabled = !shouldShow = true) and consumes the event
        // on the Initial pass, before the Row's `clickable` ever sees it.
        //
        // Dispatch-chain note: `performClick()` routes through the semantics
        // chain. The semantics dispatch correctly reaches the
        // `pointerInput`-based `tapInterceptor` on the outer Box,
        // so `toggleOverlays()` fires as expected. If a future Compose UI
        // test version splits these chains, switch to
        // `performTouchInput { click() }` to dispatch through pointer-input
        // directly.
        composeTestRule.onNodeWithContentDescription("barrage input icon")
            .performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { callUiController.toggleOverlays() }
        verify(exactly = 0) { callUiController.toggleTopBottomBars() }
    }

    // -----------------------------------------------------------------
    // #11 share-screening uses 360 dp width (expanded — collapsed picker
    // is not placed)
    // -----------------------------------------------------------------
    @Test
    fun `share-screening expanded - outer Box width is at least 360 dp`() {
        setBarrageContent(expanded = true, isShareScreening = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("barrage-outer-box")
            .assertWidthIsAtLeast(LCallUiConstants.SIMPLE_BARRAGE_UI_WIDTH_SCREEN_SHARE.dp)
    }

    // -----------------------------------------------------------------
    // #12 1V1 portrait alwaysShow path — outer tapInterceptor is
    // DISABLED (shouldShow=true via alwaysShow), inner tapInterceptor
    // was removed, so tap does not invoke any toggle.
    // -----------------------------------------------------------------
    @Test
    fun `1V1 portrait - alwaysShow keeps shouldShow true even when bottom bars disabled`() {
        showBottomToolBarViewEnabled.value = false
        setBarrageContent(
            expanded = false,
            config = baseConfig.copy(isOneVOneCall = true)
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("barrage-outer-box").performTouchInput {
            click(position = Offset(width / 2f, height / 2f))
        }
        composeTestRule.waitForIdle()

        verify(exactly = 0) { callUiController.toggleTopBottomBars() }
        verify(exactly = 0) { callUiController.toggleOverlays() }
    }
}

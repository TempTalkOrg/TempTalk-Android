package com.difft.android.call.ui.barrage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.BubbleMessageType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rendered-wiring test for the barrage entry (smiley) button's geometry.
 *
 * `LCallUiConstantsChromeTest` pins that `BARRAGE_ENTRY_ICON_SIZE_DP == 20`,
 * `BARRAGE_ENTRY_PADDING_DP == 12` and that they sum to 44. None of those rows pins that
 * `BarrageMessageView` feeds each constant into the *right* modifier slot. Transposing the
 * two — a 12dp icon inside 20dp padding — keeps every constant-value row green while
 * shipping a visibly wrong button. This test closes that hole at the rendered node.
 *
 * Both assertions are required, and each fails independently under a transposition:
 *  (i)  the icon is exactly `BARRAGE_ENTRY_ICON_SIZE_DP` square (transposed: 12dp -> fails);
 *  (ii) the icon's left edge sits exactly `BARRAGE_ENTRY_PADDING_DP` from the entry
 *       button's own start edge (transposed: 20dp -> fails).
 *
 * ## Why the icon query needs `useUnmergedTree = true`
 *
 * The entry `Row` carries `.clickable { }`, whose semantics set `mergeDescendants = true`.
 * The icon's `TestTag` is folded into the `Row`'s merged configuration, so in the default
 * merged tree `onNodeWithTag("barrage-entry-icon")` matches zero nodes and the query throws
 * "no matching node" before it can measure anything.
 *
 * ## Why (ii) pins the padding positionally rather than asserting a 44dp button
 *
 * `Modifier.clickable` contributes its own merging semantics node, and in production's
 * chain that node sits *inside* `.padding(...)`. `SemanticsNode` bounds resolve from that
 * node's coordinator, so `barrage-entry-button` reports the Row's 20dp **content** box in
 * both the merged and the unmerged tree, whatever the tag's position in the chain. A 44dp
 * size assertion on it is therefore unlandable; the only way to make it report 44dp is to
 * hoist `.clickable` above `.padding(...)`, which would enlarge the real tap target — a
 * behavioural change this consolidation is not allowed to make. Pinning the padding as a
 * position keeps the same transposition sensitivity with no production change.
 *
 * The position reference is the root: the harness anchors `ShouldShowBarrageInput` at
 * `Alignment.BottomStart` of a `Box` whose left edge is the root's left edge, and the entry
 * `Row` is start-aligned inside `ShouldShowBarrageInput`'s own `Box`, so the icon's
 * root-relative x *is* its offset from the entry button's start edge.
 *
 * Harness mirrors `ShouldShowBarrageInputTest` (same runner, qualifiers, mocks, config and
 * wrapper) so both files exercise the same composable under the same conditions; that file
 * is intentionally left unedited.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w720dp-h1280dp")
class BarrageEntryGeometryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
     * Every one of `BarrageMessageConfig`'s fields is supplied explicitly so the
     * constructor compiles regardless of which fields carry defaults in production.
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
     * Same wrapper as `ShouldShowBarrageInputTest`: `DifftTheme` for the `colorResource`
     * lookups inside the picker, a 600x600 dp `Box` aligned to `BottomStart`, and a top
     * padding that leaves the negatively-placed picker a visible region above the entry row.
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
                        setExpanded = { },
                        onClickItem = onClickItem,
                        onShowInputOverlay = { }
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // TC28 — collapsed entry button: exact icon size AND exact icon inset.
    // -----------------------------------------------------------------------------------
    @Test
    fun `collapsed entry button renders the icon at its constant size inside its constant padding`() {
        setBarrageContent(expanded = false)
        composeTestRule.waitForIdle()

        val icon = composeTestRule.onNodeWithTag("barrage-entry-icon", useUnmergedTree = true)

        // (i) exact size — a lower bound would pass a too-large icon.
        icon.assertWidthIsEqualTo(LCallUiConstants.BARRAGE_ENTRY_ICON_SIZE_DP.dp)
        icon.assertHeightIsEqualTo(LCallUiConstants.BARRAGE_ENTRY_ICON_SIZE_DP.dp)

        // (ii) exact inset from the entry button's start edge.
        icon.assertLeftPositionInRootIsEqualTo(LCallUiConstants.BARRAGE_ENTRY_PADDING_DP.dp)
    }
}

package com.difft.android.chat.gif.compose

import android.graphics.drawable.ShapeDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.gif.GifPage
import com.difft.android.chat.gif.GifPanelContract
import com.difft.android.chat.gif.GifPanelViewModel
import com.difft.android.chat.gif.GifRepository
import com.difft.android.chat.gif.GifSendUseCase
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository
import com.difft.android.chat.gif.favorite.FavoriteViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertSame

/**
 * M12 (issue #1127, family J): mounts the real [GifInlinePanel] composable directly on a bare
 * [ComponentActivity] (not any of the 5 real `@AndroidEntryPoint` hosts
 * `ChatActivity`/`GroupChatContentActivity`/`ChatPopupActivity`/`GroupChatPopupActivity`/
 * `IndexActivity` — this codebase has no Hilt-Robolectric harness for constructing Hilt
 * fragments/activities directly in unit tests) with the exact literal `DifftTheme` call shape
 * `ChatMessageInputFragment.setupGifPanel()` uses post-migration. A single generic test closes
 * the claim for all 5 hosts because `applyWindowBackground = false`'s behavior is independent of
 * host type — the call site passes the same literal argument regardless of which host mounts it.
 *
 * `GifPanelViewModel`/`FavoriteViewModel` are constructed directly with mocked dependencies (same
 * pattern as the existing `GifPanelViewModelTest`) -- no Hilt component needed, both are plain
 * `@Inject constructor` classes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GifInlinePanelWindowBackgroundTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `M12 recompositions via the real state flow never write the host window background`() {
        val gifRepository: GifRepository = mockk()
        val gifSendUseCase: GifSendUseCase = mockk()
        coEvery { gifRepository.fetch(any(), any(), any(), any()) } returns
            GifPage(items = emptyList(), hasMore = false, next = null, pageCount = 0)
        val gifPanelViewModel = GifPanelViewModel(gifRepository, gifSendUseCase)

        val syncRepo: FavoriteSyncRepository = mockk()
        every { syncRepo.observeFavorites() } returns MutableStateFlow(emptyList())
        val favoriteViewModel = FavoriteViewModel(
            syncRepo = syncRepo,
            writeRepo = mockk(relaxed = true),
            optimisticWriter = mockk(relaxed = true),
            gifLoader = mockk(relaxed = true),
        )

        // Any non-ColorDrawable stands in for a real host's window background (ChatBackgroundDrawable /
        // manifest-transparent / @color/bg) -- its concrete type is irrelevant to the assertion (design
        // §7 exception B). Forcing a ColorDrawable cast against a real ChatActivity/
        // GroupChatContentActivity host would throw ClassCastException, since neither uses ColorDrawable.
        val sentinel = ShapeDrawable()
        composeTestRule.activity.window.setBackgroundDrawable(sentinel)

        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                GifInlinePanel(
                    viewModel = gifPanelViewModel,
                    favoriteViewModel = favoriteViewModel,
                    onOpenSearch = {},
                    onPickFavorite = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // Force >= 2 recompositions via GifPanelViewModel's own real state flow (tab switches),
        // not an artificial recomposition trigger.
        gifPanelViewModel.dispatch(GifPanelContract.Intent.SelectTab(GifPanelContract.GifTab.SEARCH))
        composeTestRule.waitForIdle()

        gifPanelViewModel.dispatch(GifPanelContract.Intent.SelectTab(GifPanelContract.GifTab.TRENDING))
        composeTestRule.waitForIdle()

        assertSame(
            sentinel,
            composeTestRule.activity.window.decorView.background,
            "GifInlinePanel's own recompositions must never replace the host window background drawable",
        )
    }
}

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

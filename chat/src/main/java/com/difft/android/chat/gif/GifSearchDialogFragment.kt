package com.difft.android.chat.gif

import android.app.Dialog
import com.difft.android.chat.gif.favorite.collectFavoriteEffects
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.chat.R
import com.difft.android.chat.gif.compose.GifSearchScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

/**
 * Full-screen GIF search dialog. Extends [BaseBottomSheetDialogFragment] using the DEFAULT
 * container (Issue 3a), so it renders as a rounded bottom sheet with a drag handle (R.id.drag_handle
 * + 16dp rounded top + max-width), below the status bar — matching [ChatSelectBottomSheetFragment].
 *
 * - Uses its OWN [GifPanelViewModel] instance via `by viewModels()` (scoped to this dialog's
 *   ViewModelStore) so its search results never leak into the inline panel's VM (§A5/#13).
 * - Hosts the Compose [GifSearchScreen] (search box + grid) in a ComposeView. The search box style
 *   matches the project's canonical XML search boxes (bg2 / accent caret / standard icons), so it
 *   looks consistent while staying in Compose (Issue 3). close-X (onClose) dismisses the sheet.
 * - On open, kicks off the initial trending load (onPanelShown) so an empty query already shows
 *   trending results (Issue 4a).
 * - On pick, delivers the resolved gif via setFragmentResult and dismisses.
 */
@AndroidEntryPoint
class GifSearchDialogFragment : BaseBottomSheetDialogFragment() {

    private val searchVm: GifPanelViewModel by viewModels()

    // Own FavoriteViewModel so long-press add-to-fav inside the search sheet has proper toast/cap
    // handling (Issue 5), independent of the inline panel's VM.
    private val favoriteVm: com.difft.android.chat.gif.favorite.FavoriteViewModel by viewModels()

    @Parcelize
    data class GifPick(val uri: String, val width: Int, val height: Int) : Parcelable

    // Default container (drag handle + rounded top): host GifSearchScreen via a content layout.
    override fun getContentLayoutResId(): Int = R.layout.chat_layout_gif_search

    // Near-full-height browse surface: expand to full screen, kept below the status bar by the base.
    override fun isFullScreen(): Boolean = true

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        // Resize for the IME and auto-show the keyboard so the search field is focused on open.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        return dialog
    }

    override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onContentViewCreated(view, savedInstanceState)
        // Trending-on-open (Issue 4a): the search VM is fresh, so trigger the deferred trending load
        // here. Idempotent across re-opens. Typing filters to search; clearing returns to trending.
        searchVm.onPanelShown()

        view.findViewById<ComposeView>(R.id.gif_search_compose)?.apply {
            // A dialog's window detach timing differs from the fragment lifecycle, so the ViewTree
            // strategy is safer than the default (§9 MINOR-3).
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme {
                    GifSearchScreen(
                        viewModel = searchVm,
                        onPick = { item -> searchVm.dispatch(GifPanelContract.Intent.PickGif(item)) },
                        onClose = { dismissAllowingStateLoss() }
                    )
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchVm.effect.collect { effect ->
                    when (effect) {
                        is GifPanelContract.Effect.SendGif -> {
                            deliverPick(effect.uri.toString(), effect.width, effect.height)
                        }
                        is GifPanelContract.Effect.ShowError -> {
                            L.w { "[GifSearch] effect error" }
                        }
                        is GifPanelContract.Effect.FavoriteRemote -> {
                            // Long-press add-to-fav: forward the raw item to our FavoriteVM as
                            // FromRemote (instant placeholder + toast; background upload). (Issue 5)
                            favoriteVm.dispatch(
                                com.difft.android.chat.gif.favorite.FavoriteContract.Intent.Favorite(
                                    com.difft.android.chat.gif.favorite.FavoriteSource.FromRemote(
                                        effect.giphyId, effect.previewUrl, effect.width, effect.height
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }

        // FavoriteVM effects: toast (resolved by the host) / cap dialog (evict-oldest then favorite).
        collectFavoriteEffects(favoriteVm)
    }

    private fun deliverPick(uri: String, width: Int, height: Int) {
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            bundleOf(RESULT_PICK to GifPick(uri, width, height))
        )
        dismissAllowingStateLoss()
    }

    companion object {
        const val RESULT_KEY = "gif_search_result"
        const val RESULT_PICK = "gif_pick"
        const val TAG = "GifSearchDialogFragment"

        fun newInstance(): GifSearchDialogFragment = GifSearchDialogFragment()
    }
}

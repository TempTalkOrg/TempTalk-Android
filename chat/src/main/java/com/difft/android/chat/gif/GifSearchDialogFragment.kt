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















@AndroidEntryPoint
class GifSearchDialogFragment : BaseBottomSheetDialogFragment() {

    private val searchVm: GifPanelViewModel by viewModels()

    
    
    private val favoriteVm: com.difft.android.chat.gif.favorite.FavoriteViewModel by viewModels()

    @Parcelize
    data class GifPick(val uri: String, val width: Int, val height: Int) : Parcelable

    
    override fun getContentLayoutResId(): Int = R.layout.chat_layout_gif_search

    
    override fun isFullScreen(): Boolean = true

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        return dialog
    }

    override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onContentViewCreated(view, savedInstanceState)
        
        
        searchVm.onPanelShown()

        view.findViewById<ComposeView>(R.id.gif_search_compose)?.apply {
            
            
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

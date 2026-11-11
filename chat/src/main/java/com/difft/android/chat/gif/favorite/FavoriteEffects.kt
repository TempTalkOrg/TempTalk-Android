package com.difft.android.chat.gif.favorite

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import kotlinx.coroutines.launch



fun Fragment.collectFavoriteEffects(favoriteViewModel: FavoriteViewModel) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            favoriteViewModel.effect.collect { effect ->
                when (effect) {
                    is FavoriteContract.Effect.ShowToast ->
                        ToastUtil.show(getString(effect.messageRes))
                    is FavoriteContract.Effect.ShowCapDialog ->
                        
                        
                        dismissWithView(ComposeDialogManager.showMessageDialog(
                            context = requireActivity(),
                            title = getString(R.string.gif_favorites_cap_title),
                            message = getString(R.string.gif_favorites_cap_message),
                            confirmText = getString(R.string.gif_favorites_cap_confirm),
                            cancelText = getString(R.string.gif_favorites_cap_cancel),
                            onConfirm = {
                                favoriteViewModel.dispatch(
                                    FavoriteContract.Intent.EvictOldestThenFavorite(effect.onEvictOldest)
                                )
                            }
                        ))
                }
            }
        }
    }
}

package com.difft.android.chat.gif.favorite

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import kotlinx.coroutines.launch

/**
 * Collect [FavoriteViewModel] effects on this fragment's view lifecycle: a toast, or the confirm-first
 * cap dialog ("Replace oldest" evicts + re-favorites; the secondary button just dismisses). Shared by
 * every favorite entry point (GIF panel, GIF search dialog, forward detail) so the cap-dialog wiring
 * lives in ONE place instead of being copied per fragment.
 */
fun Fragment.collectFavoriteEffects(favoriteViewModel: FavoriteViewModel) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            favoriteViewModel.effect.collect { effect ->
                when (effect) {
                    is FavoriteContract.Effect.ShowToast ->
                        ToastUtil.show(getString(effect.messageRes))
                    is FavoriteContract.Effect.ShowCapDialog ->
                        // Confirm-first cap dialog: "Replace oldest" evicts + adds; the secondary
                        // button ("Cancel") just dismisses; the current GIF is not added.
                        ComposeDialogManager.showMessageDialog(
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
                        )
                }
            }
        }
    }
}

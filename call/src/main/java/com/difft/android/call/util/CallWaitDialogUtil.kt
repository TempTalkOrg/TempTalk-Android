package com.difft.android.call.util

import android.content.Context
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.R
import com.difft.android.call.databinding.LayoutCallWaitDialogBinding

object CallWaitDialogUtil {
    private const val LOADING_ANIM_DARK = "tt_loading_dark.json"
    private const val LOADING_ANIM_LIGHT = "tt_loading_light.json"

    /** No dialog claimed: releasing it closes nothing. */
    const val NO_CLAIM = ComposeDialogManager.NO_CLAIM

    /**
     * Shows the call wait dialog over [context]'s Activity and returns the claim to pass to
     * [dismiss]; [NO_CLAIM] when [context] has no Activity. Claims on one Activity share its dialog.
     */
    fun show(context: Context): Int = ComposeDialogManager.showWait(
        context = context,
        message = "",
        cancelable = false,
        layoutId = R.layout.layout_call_wait_dialog
    ) { view ->
        val binding = LayoutCallWaitDialogBinding.bind(view)
        val loadingAnimFile = if (ThemeUtil.isSystemInDarkTheme(context)) {
            LOADING_ANIM_DARK
        } else {
            LOADING_ANIM_LIGHT
        }
        binding.callLoadingProcess.setAnimation(loadingAnimFile)
        binding.callLoadingProcess.playAnimation()
    }

    /** Releases [token]'s share of the wait dialog; the dialog closes once no claim is left. */
    fun dismiss(token: Int) {
        ComposeDialogManager.dismissWait(token)
    }
}
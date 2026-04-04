package com.difft.android.call.util

import android.content.Context
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.R
import com.difft.android.call.databinding.LayoutCallWaitDialogBinding

object CallWaitDialogUtil {
    private const val LOADING_ANIM_DARK = "tt_loading_dark.json"
    private const val LOADING_ANIM_LIGHT = "tt_loading_light.json"

    fun show(context: Context) {
        ComposeDialogManager.showWait(
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
    }

    fun dismiss() {
        ComposeDialogManager.dismissWait()
    }
}
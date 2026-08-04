package com.difft.android.selector.interfaces

import android.content.Context

import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.entity.LocalMedia

interface OnSelectLimitTipsListener {
    /**
     * @return true if the caller provides a custom limit tip; otherwise the system default tip is used.
     */
    fun onSelectLimitTips(
        context: Context,
        media: LocalMedia?,
        config: SelectorConfig,
        limitType: Int
    ): Boolean
}

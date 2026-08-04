package com.difft.android.selector.style

import androidx.annotation.AnimRes
import com.difft.android.selector.R

class PictureWindowAnimationStyle {

    /** Fields are accessed bare from remaining Java (fragments/activities) → @JvmField. */
    @JvmField
    @AnimRes
    var activityEnterAnimation: Int = 0

    @JvmField
    @AnimRes
    var activityExitAnimation: Int = 0

    @JvmField
    @AnimRes
    var activityPreviewEnterAnimation: Int = 0

    @JvmField
    @AnimRes
    var activityPreviewExitAnimation: Int = 0

    constructor()

    constructor(@AnimRes activityEnterAnimation: Int, @AnimRes activityExitAnimation: Int) {
        this.activityEnterAnimation = activityEnterAnimation
        this.activityExitAnimation = activityExitAnimation
        this.activityPreviewEnterAnimation = activityEnterAnimation
        this.activityPreviewExitAnimation = activityExitAnimation
    }

    companion object {
        @JvmStatic
        fun ofDefaultWindowAnimationStyle(): PictureWindowAnimationStyle =
            PictureWindowAnimationStyle(R.anim.ps_anim_enter, R.anim.ps_anim_exit)
    }
}

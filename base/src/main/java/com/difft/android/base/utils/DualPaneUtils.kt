package com.difft.android.base.utils

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.ui.sidechrome.sideChromeBar
import com.difft.android.base.utils.pane.detailNavigation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach





object DualPaneUtils {

    


    fun Fragment.isInDualPaneMode(): Boolean {
        return (activity as? DualPaneHost)?.isDualPaneMode == true
    }

    



    fun Fragment.showsDetailBackButton(): Boolean =
        !isInDualPaneMode() || detailNavigation().canPop(this)

    






    fun Fragment.setupBackButton(
        backButton: View,
        onBackClick: (() -> Unit)? = null
    ) {
        val back: () -> Unit = { if (onBackClick != null) onBackClick() else detailNavigation().pop(this) }
        
        sideChromeBar().setBack(backButton, back.takeIf { showsDetailBackButton() })
    }

    











    fun Fragment.setupDynamicBackButton(
        backButton: ImageView,
        isEditMode: Boolean,
        backIconRes: Int,
        closeIconRes: Int,
        onExitEditMode: () -> Unit,
        onBack: () -> Unit = { detailNavigation().pop(this) }
    ) {
        val action: (() -> Unit)? = when {
            isEditMode -> onExitEditMode
            showsDetailBackButton() -> onBack
            else -> null
        }
        
        val icon = if (isEditMode) closeIconRes else backIconRes
        if (action != null) backButton.setImageResource(icon)
        sideChromeBar().setBack(backButton, action, icon)
    }
}





interface DualPaneHost {
    


    val isDualPaneMode: Boolean

    






    fun showDetailFragment(fragment: Fragment, tag: String? = null)

    





    val homeTabBarObstructionPx: StateFlow<Int>
}








fun View.padBottomForHomeTabBar(owner: LifecycleOwner, host: DualPaneHost?, extraPx: Int = 0) {
    host ?: return
    val basePaddingBottom = paddingBottom
    (this as? ViewGroup)?.clipToPadding = false
    host.homeTabBarObstructionPx
        .onEach { obstruction -> updatePadding(bottom = basePaddingBottom + obstruction + extraPx) }
        .launchIn(owner.lifecycleScope)
}






fun View.marginBottomForHomeTabBar(owner: LifecycleOwner, host: DualPaneHost?, extraPx: Int = 0) {
    host ?: return
    val baseMarginBottom = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: return
    host.homeTabBarObstructionPx
        .onEach { obstruction ->
            val params = layoutParams as ViewGroup.MarginLayoutParams
            val target = baseMarginBottom + obstruction + extraPx
            if (params.bottomMargin != target) {
                params.bottomMargin = target
                layoutParams = params
            }
        }
        .launchIn(owner.lifecycleScope)
}


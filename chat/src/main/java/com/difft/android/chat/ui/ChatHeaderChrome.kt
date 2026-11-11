package com.difft.android.chat.ui

import com.difft.android.base.utils.avoidCornerCutoutInRow
import android.view.View
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import com.difft.android.base.ui.sidechrome.sideChromeBar
import com.difft.android.base.widget.applyFloatingSurface
import com.difft.android.base.widget.clearFloatingSurface
import com.difft.android.chat.R










internal object ChatHeaderChrome {

    



    fun apply(
        fragment: Fragment,
        back: View,
        title: LinearLayout,
        avatar: View,
        titleText: LinearLayout,
        nameRow: View,
        timerRow: View,
        actions: View,
    ) {
        val pillRadius = title.resources.getDimension(R.dimen.chat_header_control_size) / 2
        val glass = fragment.usesConversationGlass()
        actions.applyFloatingSurface(cornerRadiusPx = pillRadius, glass = glass)
        
        back.avoidCornerCutoutInRow()
        
        
        fragment.sideChromeBar().onPlacement { onBar, shortWindow ->
            val pill = onBar || shortWindow
            styleTitle(title, avatar, titleText, nameRow, timerRow, pill = pill, glass = glass)
            placeTitle(title, back.id, actions.id, centred = onBar, bias = if (pill) CENTRE else 0f)
        }
    }

    
    fun applyCard(card: View, glass: Boolean = false) {
        card.applyFloatingSurface(cornerRadiusPx = card.resources.getDimension(com.difft.android.base.R.dimen.radius_card), glass = glass, clipsContent = true)
        card.clipToOutline = true
    }

    





    internal fun styleTitle(
        title: LinearLayout,
        avatar: View,
        titleText: LinearLayout,
        nameRow: View,
        timerRow: View,
        pill: Boolean,
        glass: Boolean = false,
    ) {
        val density = title.resources.displayMetrics.density
        titleText.orientation = if (pill) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val avatarSize = if (pill) (PILL_AVATAR_DP * density).toInt() else title.resources.getDimensionPixelSize(R.dimen.chat_header_avatar_size)
        avatar.updateLayoutParams<LinearLayout.LayoutParams> {
            width = avatarSize
            height = avatarSize
        }
        val start = if (pill) (PILL_AVATAR_INSET_DP * density).toInt() else 0
        val end = if (pill) (PILL_PADDING_DP * density).toInt() else 0
        title.updatePaddingRelative(start = start, end = end)
        nameRow.updateLayoutParams<LinearLayout.LayoutParams> { weight = if (pill) 1f else 0f }
        timerRow.updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = if (pill) (PILL_TIMER_GAP_DP * density).toInt() else 0
        }
        if (pill) {
            title.applyFloatingSurface(cornerRadiusPx = title.resources.getDimension(R.dimen.chat_header_control_size) / 2, glass = glass)
        } else {
            title.clearFloatingSurface()
        }
    }

    



    internal fun placeTitle(title: View, back: Int, actions: Int, centred: Boolean, bias: Float = 0f) {
        val params = title.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if ((params.startToStart == ConstraintLayout.LayoutParams.PARENT_ID) == centred &&
            (centred || params.horizontalBias == bias)
        ) return
        title.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (centred) {
                startToEnd = ConstraintLayout.LayoutParams.UNSET
                endToStart = ConstraintLayout.LayoutParams.UNSET
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                horizontalBias = CENTRE
            } else {
                startToStart = ConstraintLayout.LayoutParams.UNSET
                endToEnd = ConstraintLayout.LayoutParams.UNSET
                startToEnd = back
                endToStart = actions
                horizontalBias = bias
            }
        }
    }

    private const val CENTRE = 0.5f
    private const val PILL_PADDING_DP = 20f
    private const val PILL_TIMER_GAP_DP = 8f
    private const val PILL_AVATAR_DP = 32f
    private const val PILL_AVATAR_INSET_DP = 6f
}

package com.difft.android.chat.group

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.databinding.ChatFragmentGroupChatBinding
import com.difft.android.chat.group.mvi.GroupDissolveBannerContract.Intent
import com.difft.android.chat.group.mvi.GroupDissolveBannerViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal fun installGroupDissolveBanner(
    owner: LifecycleOwner,
    binding: ChatFragmentGroupChatBinding,
    model: GroupDissolveBannerViewModel,
    chatData: StateFlow<ChatUIData>,
) {
    val overlay = ComposeView(binding.root.context).apply {
        id = View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        layoutParams = ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topToBottom = binding.fragmentContainerViewHeader.id
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        setContent {
            DifftTheme(applyWindowBackground = false) {
                val state by model.state.collectAsStateWithLifecycle()
                GroupDissolveBanner(state.daysLeft, { model.dispatch(Intent.Dismiss) })
            }
        }
    }
    
    binding.root.addView(overlay, binding.root.indexOfChild(binding.fragmentContainerViewInput))
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.dispatch(Intent.Refresh)
            chatData.collect { model.dispatch(Intent.UpdateGroup(it.group)) }
        }
    }
}

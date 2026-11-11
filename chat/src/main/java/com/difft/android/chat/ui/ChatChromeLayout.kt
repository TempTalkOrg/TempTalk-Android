package com.difft.android.chat.ui

import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.ui.glass.trackOverlayChrome
import com.difft.android.chat.ui.messageaction.ChatChromeInsets


internal sealed interface ListReanchor {
    
    data class ToPosition(val position: Int) : ListReanchor

    
    data class ScrollBy(val dy: Int) : ListReanchor

    




    data class ScrollByKeeping(val dy: Int, val position: Int) : ListReanchor

    data object None : ListReanchor
}


internal fun RecyclerView.reanchor(reanchor: ListReanchor) {
    when (reanchor) {
        
        is ListReanchor.ToPosition -> layoutManager?.scrollToPosition(reanchor.position)
        is ListReanchor.ScrollBy -> scrollBy(0, reanchor.dy)
        is ListReanchor.ScrollByKeeping -> {
            scrollBy(0, reanchor.dy)
            layoutManager?.scrollToPosition(reanchor.position)
        }
        ListReanchor.None -> Unit
    }
}







internal fun JumpTargetAnchor.reanchorForBottomChange(delta: Int): ListReanchor {
    val target = reanchorOnLayout(delta)
    return when {
        target != null && delta > 0 -> ListReanchor.ScrollByKeeping(delta, target)
        target != null -> ListReanchor.ToPosition(target)
        delta > 0 -> ListReanchor.ScrollBy(delta)
        else -> ListReanchor.None
    }
}






internal fun JumpTargetAnchor.reanchorForChrome(old: ChatChromeInsets, new: ChatChromeInsets): ListReanchor {
    val bottomDelta = new.bottom - old.bottom
    if (bottomDelta != 0) return reanchorForBottomChange(bottomDelta)
    val target = reanchorOnLayout(new.top - old.top) ?: return ListReanchor.None
    return ListReanchor.ToPosition(target)
}








internal fun trackChatChrome(owner: LifecycleOwner, list: View, header: View, input: View, onChange: (ChatChromeInsets) -> Unit) {
    trackOverlayChrome(owner, list, header, input) {
        onChange(ChatChromeInsets(it.top, it.bottom))
    }
}

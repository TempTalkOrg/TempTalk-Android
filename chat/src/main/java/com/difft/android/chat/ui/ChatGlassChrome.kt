package com.difft.android.chat.ui

import android.os.Bundle
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.ui.glass.GlassBackdrop
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.glass.installGlassBackdrop
import com.difft.android.base.ui.glass.trackSystemBarUnderlap
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.difft.android.chat.R
import com.difft.android.chat.group.GroupChatFragment


internal fun Fragment.usesConversationGlass(): Boolean =
    (parentFragment is ChatFragment || parentFragment is GroupChatFragment) && GlassMode.isLive(requireContext())






internal fun installConversationGlass(
    fragment: Fragment,
    root: InsetAwareConstraintLayout,
    content: View,
) {
    if (!GlassMode.isLive(root.context)) return
    val owner = fragment.viewLifecycleOwner
    val children = fragment.childFragmentManager
    var backdrop: GlassBackdrop? = null
    fun install(rows: RecyclerView) {
        backdrop = root.installGlassBackdrop(rows) { canvas ->
            var ancestor: View? = root
            while (ancestor != null) {
                val background = ancestor.background
                if (background is ChatBackgroundDrawable) {
                    background.drawWallpaperFor(canvas, root)
                    break
                }
                ancestor = ancestor.parent as? View
            }
        }
    }
    
    
    val callback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, child: Fragment, view: View, savedInstanceState: Bundle?) {
            if (child !is ChatMessageListFragment || child.id != content.id) return
            install(view.requireConversationMessageRows())
            children.unregisterFragmentLifecycleCallbacks(this)
        }
    }
    val rows = content.findViewById<RecyclerView>(R.id.recyclerViewMessage)
    if (rows != null) install(content.requireConversationMessageRows()) else children.registerFragmentLifecycleCallbacks(callback, false)
    trackConversationUnderlap(owner, root, content)
    owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) {
            children.unregisterFragmentLifecycleCallbacks(callback)
            backdrop?.close()
        }
    })
}


internal fun View.requireConversationMessageRows(): RecyclerView =
    checkNotNull(findViewById<RecyclerView>(R.id.recyclerViewMessage)) { "Conversation message rows must exist before installing glass" }


internal fun trackConversationUnderlap(owner: LifecycleOwner, root: InsetAwareConstraintLayout, content: View) {
    trackSystemBarUnderlap(owner, root) { applyConversationUnderlap(content, it.bottom) }
}


internal fun applyConversationUnderlap(content: View, bottom: Int): Boolean {
    val params = content.layoutParams as ConstraintLayout.LayoutParams
    if (params.bottomMargin == -bottom) return true
    content.updateLayoutParams<ConstraintLayout.LayoutParams> { bottomMargin = -bottom }
    return false
}

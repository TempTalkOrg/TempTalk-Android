package com.difft.android.dualpane

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L


























internal class PaneImeRouter(private val root: View) {

    private val listPane: View? = root.findViewById(R.id.list_pane)
    private val detailPane: View? = root.findViewById(R.id.detail_pane)
    private val panes: List<View> get() = listOfNotNull(listPane, detailPane)

    
    var owner: View? = null
        private set

    private var attached = false
    
    
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, _ -> onFocusChanged(moved = oldFocus != null) }

    
    
    private var appliedImeShown = false
    private val keyboardUp: Boolean
        get() = appliedImeShown || ViewCompat.getRootWindowInsets(root)?.imeShown() == true

    
    private val stopImeAnimation = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
        override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>): WindowInsetsCompat = insets
    }

    
    
    
    private var imeAnimations = 0
    private val imeAnimating: Boolean get() = imeAnimations > 0
    private var paneCallbacksStale = false
    private val animationWatch = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
            if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
            
            
            
            val hiding = keyboardUp
            val first = !imeAnimating
            imeAnimations++
            
            if (!hiding) setOwner(ownerForKeyboard(root.findFocus()), "ime_prepare", swapNow = first)
        }

        override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>): WindowInsetsCompat = insets

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
            if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
            imeAnimations = (imeAnimations - 1).coerceAtLeast(0)
            
            
            root.post {
                if (imeAnimating) return@post
                if (!keyboardUp && editorPane(root.findFocus()) == null && setOwner(null, "ime_hidden", swapNow = true)) redispatch()
                if (paneCallbacksStale) applyPaneCallbacks()
            }
        }
    }

    
    fun attach() {
        if (attached || listPane == null || detailPane == null) return
        attached = true
        panes.forEach { pane ->
            ViewCompat.setOnApplyWindowInsetsListener(pane) { view, insets ->
                
                
                if (!redispatching) {
                    val shown = insets.imeShown()
                    appliedImeShown = shown
                    if (shown) {
                        
                        
                        if (owner == null) setOwner(ownerForKeyboard(root.findFocus()), "ime_apply", swapNow = !imeAnimating)
                    } else if (!imeAnimating && editorPane(root.findFocus()) == null) {
                        
                        setOwner(null, "ime_hidden", swapNow = true)
                    }
                }
                val current = owner
                if (current == null || view === current) insets else insets.withoutIme()
            }
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ViewCompat.setWindowInsetsAnimationCallback(root, animationWatch)
        L.i { "[PaneIme] attached" }
        onFocusChanged(moved = true)
    }

    fun detach() {
        if (!attached) return
        attached = false
        root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        panes.forEach { pane ->
            ViewCompat.setOnApplyWindowInsetsListener(pane, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ViewCompat.setWindowInsetsAnimationCallback(pane, null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ViewCompat.setWindowInsetsAnimationCallback(root, null)
        imeAnimations = 0
        paneCallbacksStale = false
        appliedImeShown = false
        owner = null
    }

    
    fun paneOf(view: View?): View? {
        var current: View? = view
        while (current != null && current !== root) {
            if (current === listPane || current === detailPane) return current
            current = current.parent as? View
        }
        return null
    }

    
    private fun editorPane(focused: View?): View? =
        if (focused != null && focused.onCheckIsTextEditor()) paneOf(focused) else null

    




    private fun ownerForKeyboard(focused: View?): View? = paneOf(focused) ?: owner

    





    private fun onFocusChanged(moved: Boolean) {
        val focused = root.findFocus()
        val busy = keyboardUp || imeAnimating
        val next = editorPane(focused) ?: when {
            !busy -> null
            moved -> ownerForKeyboard(focused)
            else -> owner
        }
        if (setOwner(next, "focus", swapNow = !imeAnimating)) redispatch()
    }

    
    private var redispatching = false

    



    private fun redispatch() {
        ViewCompat.getRootWindowInsets(root)?.let {
            val outer = redispatching
            redispatching = true
            try {
                ViewCompat.dispatchApplyWindowInsets(root, it)
            } finally {
                redispatching = outer
            }
        }
        ViewCompat.requestApplyInsets(root)
    }

    
    private fun setOwner(next: View?, reason: String, swapNow: Boolean): Boolean {
        if (next === owner) return false
        owner = next
        L.i { "[PaneIme] owner=${ownerName()} reason=$reason animating=$imeAnimating" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (swapNow) applyPaneCallbacks() else paneCallbacksStale = true
        }
        return true
    }

    private fun applyPaneCallbacks() {
        paneCallbacksStale = false
        val current = owner
        panes.forEach { pane ->
            ViewCompat.setWindowInsetsAnimationCallback(pane, if (current != null && pane !== current) stopImeAnimation else null)
        }
    }

    private fun ownerName(): String = when (owner) {
        null -> "none"
        listPane -> "list"
        else -> "detail"
    }

    private fun WindowInsetsCompat.imeShown(): Boolean =
        isVisible(WindowInsetsCompat.Type.ime()) && getInsets(WindowInsetsCompat.Type.ime()).bottom > 0

    private fun WindowInsetsCompat.withoutIme(): WindowInsetsCompat =
        WindowInsetsCompat.Builder(this)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .setVisible(WindowInsetsCompat.Type.ime(), false)
            .build()
}

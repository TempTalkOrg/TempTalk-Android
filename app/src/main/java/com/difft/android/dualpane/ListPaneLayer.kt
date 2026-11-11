package com.difft.android.dualpane

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.pane.UncoverableSearchPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


enum class ListPaneLayerRoot { SEARCH, FOLDED_GROUPS, CREATE_GROUP }











class ListPaneLayer internal constructor(
    private val activity: FragmentActivity,
    private val chrome: PaneChrome,
    internal val pages: ListPaneLayerHostFragment.Pages,
    private val detailCoversList: StateFlow<Boolean>,
    private val onActiveChanged: () -> Unit,
) {
    
    interface Owner {
        val listLayer: ListPaneLayer
    }

    private val fm: FragmentManager get() = activity.supportFragmentManager
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<() -> Unit>()
    private var drainPosted = false
    private var destroyed = false
    private val drainRunnable = Runnable {
        drainPosted = false
        drain()
    }

    private val activeFlow = MutableStateFlow(false)
    private val coversListFlow = MutableStateFlow(false)

    
    private var revealOrigin: Rect? = null

    
    private var revealPending = false
    private val afterReveal = ArrayList<() -> Unit>()

    
    val activeState: StateFlow<Boolean> = activeFlow.asStateFlow()
    val active: Boolean get() = activeFlow.value

    
    val coversList: StateFlow<Boolean> = coversListFlow.asStateFlow()

    
    val depth: Int get() = if (active) host?.depth ?: 0 else 0

    
    val root: ListPaneLayerRoot? get() = host?.root

    internal val host: ListPaneLayerHostFragment?
        get() = fm.findFragmentByTag(ListPaneLayerHostFragment.TAG) as? ListPaneLayerHostFragment

    
    fun attach(savedInstanceState: Bundle?) {
        activeFlow.value = savedInstanceState?.getBoolean(STATE_LAYER_ACTIVE) ?: false
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> if (pending.isNotEmpty()) scheduleDrain()
                    Lifecycle.Event.ON_DESTROY -> {
                        destroyed = true
                        afterReveal.clear()
                        pending.clear()
                        handler.removeCallbacks(drainRunnable)
                    }
                    else -> Unit
                }
            }
        )
        val restored = host
        if (restored != null && !active) {
            L.w { "[ListLayer] restore_mismatch host=present active=false" }
            fm.beginTransaction().remove(restored).commitNow()
        } else if (restored == null && active) {
            L.w { "[ListLayer] restore_mismatch host=absent active=true" }
            activeFlow.value = false
        }
        applyPresentation(animate = false)
        if (savedInstanceState != null) L.i { "[ListLayer] restored active=$active root=$root depth=$depth" }
        observeUncover()
    }

    






    private fun observeUncover() {
        var covered = detailCoversList.value
        activity.lifecycleScope.launch {
            detailCoversList.collect { now ->
                val was = covered
                covered = now
                if (was == now || !active) return@collect
                val top = host?.topPage() as? UncoverableSearchPage ?: return@collect
                if (now) top.onCovered() else top.onUncovered()
            }
        }
    }

    fun saveState(outState: Bundle) {
        outState.putBoolean(STATE_LAYER_ACTIVE, active)
    }

    






    fun open(root: ListPaneLayerRoot, revealFrom: Rect? = null, args: Bundle? = null) {
        if (destroyed) return
        if (!active) {
            activeFlow.value = true
            setPresentationCovers(true)
            onActiveChanged()
            revealOrigin = revealFrom?.takeIf { root == ListPaneLayerRoot.SEARCH }
            revealPending = revealOrigin != null
            L.i { "[ListLayer] open root=$root dualPane=${chromeIsDualPane()} reveal=${revealOrigin != null}" }
        }
        submit {
            val current = host
            if (current?.root != root || !current.hasRootArgs(args)) {
                if (current != null) L.i { "[ListLayer] replace from=${current.root} to=$root" }
                fm.beginTransaction()
                    .apply { if (current != null) remove(current) }
                    .add(R.id.list_layer, ListPaneLayerHostFragment.newInstance(root, args), ListPaneLayerHostFragment.TAG)
                    .commitNow()
            }
            applyPresentation()
        }
    }

    



    fun close() {
        if (destroyed || !active) return
        activeFlow.value = false
        onActiveChanged()
        
        revealPending = false
        afterReveal.clear()
        L.i { "[ListLayer] close root=$root depth=${host?.depth ?: 0}" }
        submit { applyPresentation() }
    }

    



    fun runAfterReveal(action: () -> Unit) {
        if (revealPending && !destroyed) afterReveal += action else action()
    }

    private fun finishReveal() {
        revealPending = false
        if (afterReveal.isEmpty()) return
        val actions = afterReveal.toList()
        afterReveal.clear()
        actions.forEach { it() }
    }

    
    fun pop(): Boolean {
        val current = host ?: return false
        if (!active || current.depth < 2) return false
        val top = current.topPage()
        submit { host?.takeIf { it.topPage() === top }?.popTop() }
        return true
    }

    





    private fun applyPresentation(animate: Boolean = true) {
        if (destroyed || activity.isDestroyed) return
        val layer = activity.findViewById<View>(R.id.list_layer) ?: return
        val origin = revealOrigin?.takeIf { animate && SearchReveal.enabled() }
        if (active) {
            setPresentationCovers(true)
            chrome.cancelListLayerAnimation()
            freezeInsets(layer, frozen = false)
            var revealing = false
            if (layer.visibility != View.VISIBLE) {
                layer.visibility = View.VISIBLE
                when {
                    origin != null -> {
                        layer.translationX = 0f
                        revealing = true
                        chrome.runListLayerAnimation(SearchReveal.open(layer, origin)) { finishReveal() }
                    }
                    animate -> chrome.runListLayerAnimation(PaneMotion.cover(layer, underLayer())) {}
                    else -> layer.translationX = 0f
                }
            } else {
                
                
                layer.translationX = 0f
                layer.alpha = 1f
            }
            if (!revealing) finishReveal()
        } else if (animate && layer.visibility == View.VISIBLE) {
            
            
            freezeInsets(layer, frozen = true)
            WindowInsetsControllerCompat(activity.window, layer).hide(WindowInsetsCompat.Type.ime())
            val animator = if (origin != null) SearchReveal.close(layer, origin) else PaneMotion.uncover(layer, underLayer())
            chrome.runListLayerAnimation(animator) { cancelled ->
                
                
                if (!cancelled && !active) {
                    layer.visibility = View.INVISIBLE
                    setPresentationCovers(false)
                }
                submit { takeDown() }
            }
        } else {
            takeDown()
        }
    }

    
    private fun takeDown() {
        if (active || destroyed || activity.isDestroyed) return
        revealOrigin = null
        host?.let { fm.beginTransaction().remove(it).commitNow() }
        activity.findViewById<View>(R.id.list_layer)?.let {
            freezeInsets(it, frozen = false)
            it.translationX = 0f
            it.visibility = View.GONE
        }
        setPresentationCovers(false)
    }

    private fun setPresentationCovers(covers: Boolean) {
        coversListFlow.value = covers
        chrome.setLayerCovers(covers)
    }

    private var insetsFrozen = false

    




    private fun freezeInsets(layer: View, frozen: Boolean) {
        if (insetsFrozen == frozen) return
        insetsFrozen = frozen
        ViewCompat.setOnApplyWindowInsetsListener(layer) { _, insets -> if (insetsFrozen) WindowInsetsCompat.CONSUMED else insets }
        if (!frozen) ViewCompat.requestApplyInsets(layer)
    }

    private fun underLayer(): List<View> = listOfNotNull(activity.findViewById(R.id.list_content))

    private fun chromeIsDualPane(): Boolean = activity.findViewById<View>(R.id.list_pane) != null

    private fun submit(op: () -> Unit) {
        if (destroyed) return
        pending.addLast(op)
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (drainPosted) return
        drainPosted = true
        handler.post(drainRunnable)
    }

    private fun canCommit(): Boolean = !fm.isStateSaved && !fm.isDestroyed

    private fun drain() {
        if (destroyed || pending.isEmpty()) return
        if (!canCommit()) {
            L.i { "[ListLayer] queued_until_start pending=${pending.size}" }
            return
        }
        while (pending.isNotEmpty() && canCommit()) pending.removeFirst().invoke()
    }

    private companion object {
        const val STATE_LAYER_ACTIVE = "list_layer_active"
    }
}

package com.difft.android.dualpane

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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

/** What the list pane's layer shows at its root. */
enum class ListPaneLayerRoot { SEARCH, FOLDED_GROUPS }

/**
 * The list pane's layer (global search, folded groups): one [ListPaneLayerHostFragment] in
 * `list_layer` (tag [ListPaneLayerHostFragment.TAG]) while [active], its root kind carried in the
 * host's arguments. Owned by IndexActivity next to [PaneNavigator]; `active` is the single truth,
 * flipped when a request is made and saved with the Activity, and every FragmentManager change runs
 * through one FIFO queue drained on the main thread with `commitNow` while state is not saved
 * (requests made after `onSaveInstanceState` wait for the next start). Each queued op inspects the
 * host it finds when it runs: a second `open()` of the same root is a no-op, an `open()` of another
 * root replaces the host, and `close()` then `open()` queued together leave one host and `active` true.
 */
class ListPaneLayer internal constructor(
    private val activity: FragmentActivity,
    private val chrome: PaneChrome,
    internal val pages: ListPaneLayerHostFragment.Pages,
    private val detailCoversList: StateFlow<Boolean>,
    private val onActiveChanged: () -> Unit,
) {
    /** Implemented by the Activity that owns the layer; the host fragment finds it through this. */
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

    /** The layer is up: it covers the tab content (both layouts). */
    val activeState: StateFlow<Boolean> = activeFlow.asStateFlow()
    val active: Boolean get() = activeFlow.value

    /** Pages in the layer's stack; 0 when the layer is down. */
    val depth: Int get() = if (active) host?.depth ?: 0 else 0

    /** The root kind of the host in place; null while no host is added. */
    val root: ListPaneLayerRoot? get() = host?.root

    internal val host: ListPaneLayerHostFragment?
        get() = fm.findFragmentByTag(ListPaneLayerHostFragment.TAG) as? ListPaneLayerHostFragment

    /** Call right after `setContentView`, before any request: restores `active` and reconciles it with the restored host. */
    fun attach(savedInstanceState: Bundle?) {
        activeFlow.value = savedInstanceState?.getBoolean(STATE_LAYER_ACTIVE) ?: false
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> if (pending.isNotEmpty()) scheduleDrain()
                    Lifecycle.Event.ON_DESTROY -> {
                        destroyed = true
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
        applyPresentation()
        if (savedInstanceState != null) L.i { "[ListLayer] restored active=$active root=$root depth=$depth" }
        observeUncover()
    }

    /**
     * One-pane edges of the conversation layer over an active layer (never the restored value):
     * when it pops (true to false) the layer's visible top page takes the keyboard back; when it
     * covers (false to true: a notification, a link or a brought-home conversation as well as a
     * result tap) that page lets the keyboard go, so it does not stay over the conversation. Only
     * search pages take part ([UncoverableSearchPage]); hidden pages under a pushed one are not told.
     */
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

    /**
     * Shows the layer with [root]. Inactive: adds a host of [root]. Active: the queued op replaces
     * the host it finds when that host has another root, so the last request wins.
     */
    fun open(root: ListPaneLayerRoot) {
        if (destroyed) return
        if (!active) {
            activeFlow.value = true
            onActiveChanged()
            L.i { "[ListLayer] open root=$root dualPane=${chromeIsDualPane()}" }
        }
        submit {
            val current = host
            if (current?.root != root) {
                if (current != null) L.i { "[ListLayer] replace from=${current.root} to=$root" }
                fm.beginTransaction()
                    .apply { if (current != null) remove(current) }
                    .add(R.id.list_layer, ListPaneLayerHostFragment.newInstance(root), ListPaneLayerHostFragment.TAG)
                    .commitNow()
            }
            applyPresentation()
        }
    }

    /** Takes the layer down, dropping its pages and their state; idempotent while inactive. */
    fun close() {
        if (destroyed || !active) return
        activeFlow.value = false
        onActiveChanged()
        L.i { "[ListLayer] close root=$root depth=${host?.depth ?: 0}" }
        submit {
            host?.let { fm.beginTransaction().remove(it).commitNow() }
            applyPresentation()
        }
    }

    /** Back on a pushed layer page: pops it, unless the stack changed before the pop ran. */
    fun pop(): Boolean {
        val current = host ?: return false
        if (!active || current.depth < 2) return false
        val top = current.topPage()
        submit { host?.takeIf { it.topPage() === top }?.popTop() }
        return true
    }

    private fun applyPresentation() {
        if (destroyed || activity.isDestroyed) return
        activity.findViewById<View>(R.id.list_layer)?.visibility = if (active) View.VISIBLE else View.GONE
        chrome.setLayerCovers(active)
    }

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

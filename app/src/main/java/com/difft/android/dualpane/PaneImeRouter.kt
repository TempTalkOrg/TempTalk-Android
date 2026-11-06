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

/**
 * Two-pane: the keyboard belongs to the pane that holds the focused field. Window insets are
 * dispatched window-wide, so without this the search field's keyboard on the left would also
 * resize the conversation on the right (its input rises, its list grows and a message jump
 * drifts when the keyboard closes later). Both panes get an insets listener: the pane that
 * contains the current focus passes the insets through unchanged; the other one passes them on
 * with the IME removed (every API level) and, on API 30+, stops IME animation callbacks from
 * reaching its subtree.
 *
 * The owner is decided from the keyboard itself: when an IME animation prepares to show, or IME
 * insets arrive visible, the pane holding the focused view owns them (a keyboard implies that view
 * is being typed into, whatever `onCheckIsTextEditor()` says at that instant: a Compose field
 * opens its input session only after focus moved). While the keyboard is up or an IME animation
 * runs the owner is never dropped: the focus leaving both panes (a closing search layer, the
 * rail, a dialog) must not hand the hiding keyboard's insets and animation to the other pane,
 * while a focus moved into the other pane hands the keyboard over even before that view reports
 * itself as an editor. The framework's own refocus after the focused field left the tree (a
 * conversation switch replaces the chat input; the first focusable view is the list) is no such
 * move and keeps the owner. The owner is cleared once the keyboard has hidden
 * and no text editor has focus. A plain focus change with the keyboard hidden routes only for a
 * text editor, so a focused list (D-pad, a restored screen) routes nothing. One-pane is not
 * routed (there is one pane). Below API 30 the conversation's `SoftInputModeLease`
 * (ADJUST_RESIZE) still shrinks the whole window for the keyboard; the routing only decides
 * which pane also pads for it.
 */
internal class PaneImeRouter(private val root: View) {

    private val listPane: View? = root.findViewById(R.id.list_pane)
    private val detailPane: View? = root.findViewById(R.id.detail_pane)
    private val panes: List<View> get() = listOfNotNull(listPane, detailPane)

    /** The pane whose field holds the focus; null = no routing (both panes get the real insets). */
    var owner: View? = null
        private set

    private var attached = false
    // A deliberate focus move reports the view that had the focus; the framework's fallback after
    // the focused view left the tree (`rootViewRequestFocus`) reports none.
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, _ -> onFocusChanged(moved = oldFocus != null) }

    // What the last applied insets said about the keyboard; with the window's own insets it tells
    // whether the keyboard is up (a hide animation starts with it up and ends with it down).
    private var appliedImeShown = false
    private val keyboardUp: Boolean
        get() = appliedImeShown || ViewCompat.getRootWindowInsets(root)?.imeShown() == true

    // Children of the non-owning pane see no IME animation; the owning pane's children animate as usual.
    private val stopImeAnimation = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
        override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>): WindowInsetsCompat = insets
    }

    // API 30+: the pane callbacks are swapped only while no IME animation is being dispatched.
    // Installing a STOP callback above a page while its own animation runs would withhold that
    // animation's onEnd from it, and a page that never sees onEnd keeps deferring its keyboard padding.
    private var imeAnimations = 0
    private val imeAnimating: Boolean get() = imeAnimations > 0
    private var paneCallbacksStale = false
    private val animationWatch = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
            if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
            // Up now: the keyboard is hiding, the owner stays for the whole hide. Down now: it is
            // opening, and the root prepares before any pane's subtree does, so the owner decided
            // here shields the other pane for this very animation (unless another one still runs).
            val hiding = keyboardUp
            val first = !imeAnimating
            imeAnimations++
            // A hide never recomputes: a focus that moved meanwhile already went through onFocusChanged.
            if (!hiding) setOwner(ownerForKeyboard(root.findFocus()), "ime_prepare", swapNow = first)
        }

        override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>): WindowInsetsCompat = insets

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
            if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
            imeAnimations = (imeAnimations - 1).coerceAtLeast(0)
            // The root's onEnd runs before the panes' subtrees get theirs: a pane turned STOP here
            // would withhold this animation's onEnd from its page. Swap after the dispatch is over.
            root.post {
                if (imeAnimating) return@post
                if (!keyboardUp && editorPane(root.findFocus()) == null && setOwner(null, "ime_hidden", swapNow = true)) redispatch()
                if (paneCallbacksStale) applyPaneCallbacks()
            }
        }
    }

    /** Installs the routing; a no-op without both panes (one-pane tree). Call after `setContentView`. */
    fun attach() {
        if (attached || listPane == null || detailPane == null) return
        attached = true
        panes.forEach { pane ->
            ViewCompat.setOnApplyWindowInsetsListener(pane) { view, insets ->
                // Only the window's own dispatches say something about the keyboard; our re-dispatch
                // of the current insets after an owner change merely re-routes them.
                if (!redispatching) {
                    val shown = insets.imeShown()
                    appliedImeShown = shown
                    if (shown) {
                        // Visible IME insets with no owner yet (API < 30, or a keyboard outside an animation):
                        // the focused view's pane takes them; decided once, the second pane sees the result.
                        if (owner == null) setOwner(ownerForKeyboard(root.findFocus()), "ime_apply", swapNow = !imeAnimating)
                    } else if (!imeAnimating && editorPane(root.findFocus()) == null) {
                        // The keyboard is down and nothing is typed into: nothing to route any more.
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

    /** The pane that contains [view], or null when it sits in neither (the rail, a dialog). */
    fun paneOf(view: View?): View? {
        var current: View? = view
        while (current != null && current !== root) {
            if (current === listPane || current === detailPane) return current
            current = current.parent as? View
        }
        return null
    }

    /** The pane of [focused] when it is a text editor (a real hand-over of the keyboard), else null. */
    private fun editorPane(focused: View?): View? =
        if (focused != null && focused.onCheckIsTextEditor()) paneOf(focused) else null

    /**
     * The owner for a keyboard that is opening or already up: the focused view's pane (a text
     * editor or, like a Compose field at the focus-change moment, not one yet), else the current
     * owner (focus wandering off both panes, to the rail or a dialog, does not move the keyboard).
     */
    private fun ownerForKeyboard(focused: View?): View? = paneOf(focused) ?: owner

    /**
     * A focus change: a focused text editor takes the keyboard over; otherwise, while the keyboard
     * is up or animating, a focus [moved] into the other pane hands it over, while the framework's
     * fallback focus and a focus leaving both panes keep the owner; with the keyboard down a
     * focused non-editor (a list under D-pad focus, a restored screen) routes nothing.
     */
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

    // True while the router itself dispatches insets; the pane listeners then only re-route.
    private var redispatching = false

    /**
     * Re-dispatches the window's insets so both panes pick up the new routing at once: the
     * current ones now (same frame), and the window's own pass on the next traversal.
     */
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

    /** Records [next] as the owner; returns whether it changed. [swapNow] applies the pane callbacks, else they wait for the running animation's end. */
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

package com.difft.android.base.utils.pane

import android.content.Context
import android.content.Intent
import android.os.Parcelable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import kotlinx.parcelize.Parcelize

/**
 * Navigation a detail page uses instead of finishing or starting Activities directly.
 *
 * Under IndexActivity's per-tab detail host the page is one entry of that tab's stack; anywhere
 * else (a full-window shell Activity, a popup, a bottom sheet) [ShellNavigation] keeps the
 * Activity behaviour. Pages obtain it through [Fragment.detailNavigation].
 */
interface DetailNavigation {
    /**
     * Opens [destination] above [from]'s page. Under a host only the visible top of the current,
     * shown tab may push (a covered page or a hidden tab asking is a stale callback); a shell
     * always starts the destination's Activity. Returns whether the push was accepted.
     */
    fun push(from: Fragment, destination: DetailDestination): Boolean

    /** Leaves the page [from] belongs to. Returns false when nothing was popped. */
    fun pop(from: Fragment): Boolean

    /** Pops [count] pages from this exact visible top; a two-pane stack keeps its root. */
    fun pop(from: Fragment, count: Int): Boolean = if (count == 1) pop(from) else false

    /** Opens a conversation; under a host it becomes the root of that host's tab stack. */
    fun openConversation(target: ConversationTarget)

    /**
     * Closes the subject of [from]'s page (the contact or group it is about, see
     * [DetailDestination.subjectId]): the lowest page about it and every page above. When that is
     * the stack's root, or [from]'s page is about nothing, the whole stack closes.
     */
    fun closeConversation(from: Fragment)

    /** Whether [from]'s page has a page under it to go back to (fixed for the page's life); drives back-button visibility. */
    fun canPop(from: Fragment): Boolean
}

/** A secondary page that can be shown either inside a detail stack or in its own Activity. */
interface DetailDestination {
    fun newFragment(): Fragment
    fun shellIntent(context: Context): Intent

    /**
     * Contact or group id the page is about: removing that contact or group closes the page
     * ([DetailNavigation.closeConversation]). Null for pages about nothing removable.
     */
    val subjectId: String? get() = null

    /**
     * Identity for duplicate pushes: a push whose key equals the top page's is dropped (double
     * tap). Destinations with variants of one page put them in the key.
     */
    val pageKey: String get() = javaClass.name + ":" + subjectId
}

/** The conversation a navigation request opens. */
@Parcelize
data class ConversationTarget(
    val kind: Kind,
    val id: String,
    val jumpTimestamp: Long? = null,
    val sourceType: String? = null,
    val source: String? = null,
) : Parcelable {
    enum class Kind { ONE_ON_ONE, GROUP }

    /** A jump is consumed when the conversation's ViewModel is created, so it needs a fresh page. */
    val hasJump: Boolean get() = (jumpTimestamp ?: 0L) > 0L
}

/**
 * Implemented by the fragment that owns a detail stack (IndexActivity's per-tab host). Pages
 * never reference it directly; they go through [detailNavigation] and [detailBackOwner].
 */
interface DetailStackHost {
    val detailNavigation: DetailNavigation

    /**
     * A lifecycle that is STARTED only while [fragment]'s page is the visible top of the current
     * tab, so Activity-dispatcher back callbacks registered with it never fire for covered or
     * hidden pages.
     */
    fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner
}

/**
 * Opens a conversation from outside a detail host. Implemented by the Application once the
 * conversation router exists; until then [ShellNavigation.openConversation] only logs.
 */
fun interface ConversationOpener {
    fun openConversation(context: Context, target: ConversationTarget)

    /**
     * The conversation [conversationId] no longer exists (friend removed): close it wherever the
     * home screen shows it, even when the page that removed it is gone. Main thread.
     */
    fun closeConversation(conversationId: String) = Unit
}

/** The host's navigation when this fragment is a page of a detail stack, else [ShellNavigation]. */
fun Fragment.detailNavigation(): DetailNavigation =
    findDetailStackHost()?.detailNavigation ?: ShellNavigation(this)

/**
 * Lifecycle owner for Activity-dispatcher back callbacks: the host's per-page owner under a
 * detail stack, else [Fragment.getViewLifecycleOwner] (shells and popups keep today's behaviour).
 */
fun Fragment.detailBackOwner(): LifecycleOwner =
    findDetailStackHost()?.detailBackOwnerFor(this) ?: viewLifecycleOwner

/**
 * A conversation root page. Opening the conversation that is already a stack's root pops back to
 * that page instead of building a new one; the page is told, so it can redo what a fresh open does
 * (clear a manual unread mark).
 */
interface ReopenableConversationPage {
    fun onConversationReopened()
}

/** True when this fragment belongs to a page of a detail stack (not a shell, popup or sheet). */
fun Fragment.isInDetailStack(): Boolean = findDetailStackHost() != null

/**
 * For content inside a dialog (a contact sheet): the navigation of the detail page the dialog
 * was shown from (the dialog's parent fragment), or null when the dialog has no parent page
 * (shown on the Activity's FragmentManager) or is not inside a detail stack. Pushes pass the
 * dialog content as `from`; its page is the dialog's parent page.
 */
fun Fragment.detailNavigationUnderDialog(): DetailNavigation? {
    var current: Fragment? = this
    while (current != null && current !is DialogFragment) current = current.parentFragment
    val dialog = current ?: return null
    return dialog.parentFragment?.findDetailStackHost()?.detailNavigation
}

/**
 * Implemented by the home screen: pushes onto the current tab's shown stack for a sheet shown on
 * the Activity (no parent page). Only when [overConversation], the conversation the sheet was
 * shown over (recorded in its arguments when shown), is still that stack's root; null (a sheet
 * shown from the list) never pushes. Returns whether the push was accepted.
 */
interface VisibleDetailStack {
    fun pushOnVisibleStack(destination: DetailDestination, overConversation: String?): Boolean
}

/**
 * Nearest enclosing [DetailStackHost]. Content inside a dialog is not a page of the stack under
 * it, so the walk stops at a [DialogFragment] (a contact sheet closes itself, not that page).
 */
internal fun Fragment.findDetailStackHost(): DetailStackHost? {
    var current: Fragment? = this
    while (current != null) {
        if (current is DialogFragment) return null
        val parent = current.parentFragment
        if (parent is DetailStackHost) return parent
        current = parent
    }
    return null
}

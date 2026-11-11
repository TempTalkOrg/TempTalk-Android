package com.difft.android.base.utils.pane

import android.content.Context
import android.content.Intent
import android.os.Parcelable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import kotlinx.parcelize.Parcelize








interface DetailNavigation {
    




    fun push(from: Fragment, destination: DetailDestination): Boolean

    
    fun pop(from: Fragment): Boolean

    
    fun pop(from: Fragment, count: Int): Boolean = if (count == 1) pop(from) else false

    
    fun openConversation(target: ConversationTarget)

    




    fun closeConversation(from: Fragment)

    
    fun canPop(from: Fragment): Boolean
}


interface DetailDestination {
    fun newFragment(): Fragment
    fun shellIntent(context: Context): Intent

    



    val subjectId: String? get() = null

    



    val pageKey: String get() = javaClass.name + ":" + subjectId
}


@Parcelize
data class ConversationTarget(
    val kind: Kind,
    val id: String,
    val jumpTimestamp: Long? = null,
    val sourceType: String? = null,
    val source: String? = null,
) : Parcelable {
    enum class Kind { ONE_ON_ONE, GROUP }

    
    val hasJump: Boolean get() = (jumpTimestamp ?: 0L) > 0L
}





interface DetailStackHost {
    val detailNavigation: DetailNavigation

    




    fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner
}





fun interface ConversationOpener {
    fun openConversation(context: Context, target: ConversationTarget)

    



    fun closeConversation(conversationId: String) = Unit

    



    fun opensInDetailPane(context: Context): Boolean = false
}


fun Fragment.detailNavigation(): DetailNavigation =
    findDetailStackHost()?.detailNavigation ?: ShellNavigation(this)





fun Fragment.detailBackOwner(): LifecycleOwner =
    findDetailStackHost()?.detailBackOwnerFor(this) ?: viewLifecycleOwner






interface ReopenableConversationPage {
    fun onConversationReopened()
}


fun Fragment.isInDetailStack(): Boolean = findDetailStackHost() != null







fun Fragment.detailNavigationUnderDialog(): DetailNavigation? {
    var current: Fragment? = this
    while (current != null && current !is DialogFragment) current = current.parentFragment
    val dialog = current ?: return null
    return dialog.parentFragment?.findDetailStackHost()?.detailNavigation
}







interface VisibleDetailStack {
    fun pushOnVisibleStack(destination: DetailDestination, overConversation: String?): Boolean
}





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

package com.difft.android.dualpane

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle







internal object PaneSubjects {

    private const val ARG_SUBJECT_ID = "pane_nav_subject_id"
    private const val ARG_PAGE_KEY = "pane_nav_page_key"

    fun subjectOf(page: Fragment): String? = page.arguments?.getString(ARG_SUBJECT_ID)

    fun pageKeyOf(page: Fragment): String? = page.arguments?.getString(ARG_PAGE_KEY)

    fun setSubject(page: Fragment, subjectId: String?) {
        page.arguments = (page.arguments ?: Bundle()).apply { putString(ARG_SUBJECT_ID, subjectId) }
    }

    fun setIdentity(page: Fragment, subjectId: String?, pageKey: String?) {
        page.arguments = (page.arguments ?: Bundle()).apply {
            putString(ARG_SUBJECT_ID, subjectId)
            putString(ARG_PAGE_KEY, pageKey)
        }
    }

    
    fun lowestIndex(host: DetailTabHostFragment, subjectId: String): Int =
        host.pages().indexOfFirst { subjectOf(it) == subjectId }

    fun shows(host: DetailTabHostFragment?, subjectId: String): Boolean =
        host != null && lowestIndex(host, subjectId) >= 0

    



    fun closeFromNow(host: DetailTabHostFragment, index: Int) {
        val stack = host.pages()
        if (index <= 0 || index >= stack.size) return
        val revealed = stack[index - 1]
        PageSlide.clear(stack)
        val tx = host.childFragmentManager.beginTransaction().setReorderingAllowed(true)
        stack.drop(index).forEach { tx.remove(it) }
        tx.show(revealed).setMaxLifecycle(revealed, Lifecycle.State.RESUMED).commitNow()
        host.refreshBackOwners()
    }
}

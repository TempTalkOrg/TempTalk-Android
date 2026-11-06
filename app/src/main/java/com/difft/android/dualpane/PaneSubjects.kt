package com.difft.android.dualpane

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle

/**
 * What a page of a detail stack is about: the contact or group id its destination names
 * (`DetailDestination.subjectId`), or a root's selection id. Closing a subject (a removed friend, a
 * gone group) closes the lowest page about it and every page above, so pages about something else
 * below them stay. A pushed page also carries its destination's page key, for duplicate pushes.
 */
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

    /** Index (root = 0) of the lowest page of [host] about [subjectId], or -1. */
    fun lowestIndex(host: DetailTabHostFragment, subjectId: String): Int =
        host.pages().indexOfFirst { subjectOf(it) == subjectId }

    fun shows(host: DetailTabHostFragment?, subjectId: String): Boolean =
        host != null && lowestIndex(host, subjectId) >= 0

    /**
     * Removes the pages from [index] (> 0) to the top in one instant transaction; the page under
     * them is shown again at its full lifecycle.
     */
    fun closeFromNow(host: DetailTabHostFragment, index: Int) {
        val stack = host.pages()
        if (index <= 0 || index >= stack.size) return
        val revealed = stack[index - 1]
        val tx = host.childFragmentManager.beginTransaction().setReorderingAllowed(true)
        stack.drop(index).forEach { tx.remove(it) }
        tx.show(revealed).setMaxLifecycle(revealed, Lifecycle.State.RESUMED).commitNow()
        host.refreshBackOwners()
    }
}

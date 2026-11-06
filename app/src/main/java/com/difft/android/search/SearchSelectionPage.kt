package com.difft.android.search

/**
 * A page of the home screen's search layer that highlights the result whose conversation or
 * contact is open in the detail pane (two-pane). The layer host forwards the selection to every
 * page it holds, the pushed More page included, so the visible page always shows it.
 */
interface SearchSelectionPage {
    fun showSelection(selectedId: String?)
}

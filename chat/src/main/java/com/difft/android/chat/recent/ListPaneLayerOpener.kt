package com.difft.android.chat.recent

/**
 * Implemented by the home screen: opens a page as the layer over its list pane (in the list pane
 * in two-pane, full-window in one-pane). Each call returns false when the host declines.
 */
interface ListPaneLayerOpener {
    /** The global search; its entry falls back to the full-window search Activity on false. */
    fun openSearchLayer(): Boolean

    /** The groups the group focus folds (the chat list's folded-groups row). */
    fun openFoldedGroupsLayer(): Boolean
}

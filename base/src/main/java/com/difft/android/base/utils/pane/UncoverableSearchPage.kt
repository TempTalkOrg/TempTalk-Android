package com.difft.android.base.utils.pane

/**
 * A search page of the home screen's search layer. One-pane: when the conversation layer that
 * covered the search layer pops, the layer's visible top page is told, and it re-focuses its
 * field so the keyboard opens again (as the search screen re-activates on reappearance). When a
 * conversation layer covers the search layer without a result tap (a notification, a link, a
 * conversation brought home), the page is told too, so its field lets the keyboard go instead of
 * keeping it over the conversation. A recreation is neither.
 */
interface UncoverableSearchPage {
    fun onUncovered()
    fun onCovered()
}

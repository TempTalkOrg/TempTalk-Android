package com.difft.android.selector.interfaces

interface OnRecyclerViewScrollListener {
    /**
     * @param dx horizontal distance scrolled in pixels
     * @param dy vertical distance scrolled in pixels
     */
    fun onScrolled(dx: Int, dy: Int)

    /**
     * @param state new scroll state: SCROLL_STATE_IDLE, SCROLL_STATE_DRAGGING or SCROLL_STATE_SETTLING
     */
    fun onScrollStateChanged(state: Int)
}

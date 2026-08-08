package com.difft.android.selector.widget

class SlideSelectionHandler(
    private val mSelectionHandler: ISelectionHandler
) : SlideSelectTouchListener.OnAdvancedSlideSelectListener {

    private var mOriginalSelection: HashSet<Int>? = null

    override fun onSelectionStarted(start: Int) {
        val original = HashSet<Int>()
        mOriginalSelection = original
        val selected = mSelectionHandler.getSelection()
        if (selected != null) {
            original.addAll(selected)
        }
        mSelectionHandler.changeSelection(start, start, !original.contains(start), true)
    }

    override fun onSelectionFinished(end: Int) {
        mOriginalSelection = null
    }

    override fun onSelectChange(start: Int, end: Int, isSelected: Boolean) {
        for (i in start..end) {
            checkedChangeSelection(i, i, isSelected != mOriginalSelection!!.contains(i))
        }
    }

    private fun checkedChangeSelection(start: Int, end: Int, newSelectionState: Boolean) {
        mSelectionHandler.changeSelection(start, end, newSelectionState, false)
    }

    interface ISelectionHandler {
        fun getSelection(): Set<Int>?

        fun changeSelection(start: Int, end: Int, isSelected: Boolean, calledFromOnStart: Boolean)
    }
}

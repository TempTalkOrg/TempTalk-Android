package com.difft.android.imageeditor.core

fun interface UndoRedoStackListener {

    fun onAvailabilityChanged(undoAvailable: Boolean, redoAvailable: Boolean)
}

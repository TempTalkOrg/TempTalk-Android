package com.difft.android.imageeditor.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class UndoRedoStacks private constructor(
    val undoStack: ElementStack,
    val redoStack: ElementStack,
    private var unchangedState: ByteArray
) : Parcelable {

    constructor(limit: Int) : this(ElementStack(limit), ElementStack(limit), ByteArray(0))

    fun pushState(element: EditorElement) {
        if (undoStack.tryPush(element)) {
            redoStack.clear()
        }
    }

    fun clear(element: EditorElement) {
        undoStack.clear()
        redoStack.clear()
        unchangedState = ElementStack.getBytes(element)
    }

    fun isChanged(element: EditorElement): Boolean =
        !ElementStack.getBytes(element).contentEquals(unchangedState)

    /**
     * As long as there is something different in the stack somewhere, then we can undo.
     */
    fun canUndo(currentState: EditorElement): Boolean =
        undoStack.stackContainsStateDifferentFrom(currentState)

    /**
     * As long as there is something different in the stack somewhere, then we can redo.
     */
    fun canRedo(currentState: EditorElement): Boolean =
        redoStack.stackContainsStateDifferentFrom(currentState)
}

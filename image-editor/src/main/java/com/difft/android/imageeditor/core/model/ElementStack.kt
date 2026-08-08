package com.difft.android.imageeditor.core.model

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import java.util.Stack

/**
 * Contains a stack of elements for undo and redo stacks.
 *
 * Elements are mutable, so this stack serializes the element and keeps a stack of serialized data.
 *
 * The stack has a [limit] and if it exceeds that limit during a push the second to earliest item
 * is removed so that it can always go back to the first state. Effectively collapsing the history for
 * the start of the stack.
 */
@Parcelize
@TypeParceler<Stack<ByteArray>, StackParceler>()
class ElementStack private constructor(
    private val limit: Int,
    private val stack: Stack<ByteArray>
) : Parcelable {

    constructor(limit: Int) : this(limit, Stack())

    /**
     * Pushes an element to the stack iff the element's serialized value is different to any found at
     * the top of the stack.
     *
     * Removes the second to earliest item if it is overflowing.
     *
     * @param element new editor element state.
     * @return true iff the pushed item was different to the top item.
     */
    fun tryPush(element: EditorElement): Boolean {
        val bytes = getBytes(element)
        val push = stack.isEmpty() || !bytes.contentEquals(stack.peek())

        if (push) {
            stack.push(bytes)
            if (stack.size > limit) {
                stack.removeAt(1)
            }
        }
        return push
    }

    /**
     * Pops the first different state from the supplied element.
     */
    @Suppress("DEPRECATION")
    fun pop(element: EditorElement): EditorElement? {
        if (stack.empty()) return null

        val elementBytes = getBytes(element)
        var stackData: ByteArray? = null

        while (!stack.empty() && stackData == null) {
            val topData = stack.pop()

            if (!topData.contentEquals(elementBytes)) {
                stackData = topData
            }
        }

        if (stackData == null) return null

        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(stackData, 0, stackData.size)
            parcel.setDataPosition(0)
            parcel.readParcelable(EditorElement::class.java.classLoader)
        } finally {
            parcel.recycle()
        }
    }

    fun clear() {
        stack.clear()
    }

    fun stackContainsStateDifferentFrom(element: EditorElement): Boolean {
        if (stack.isEmpty()) return false

        val currentStateBytes = getBytes(element)

        for (item in stack) {
            if (!item.contentEquals(currentStateBytes)) {
                return true
            }
        }

        return false
    }

    companion object {
        @JvmStatic
        fun getBytes(parcelable: Parcelable): ByteArray {
            val parcel = Parcel.obtain()
            val bytes: ByteArray
            try {
                parcel.writeParcelable(parcelable, 0)
                bytes = parcel.marshall()
            } finally {
                parcel.recycle()
            }
            return bytes
        }
    }
}

package com.difft.android.imageeditor.core.model

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import java.util.Stack










@Parcelize
@TypeParceler<Stack<ByteArray>, StackParceler>()
class ElementStack private constructor(
    private val limit: Int,
    private val stack: Stack<ByteArray>
) : Parcelable {

    constructor(limit: Int) : this(limit, Stack())

    








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

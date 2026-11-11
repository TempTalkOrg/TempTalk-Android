package com.difft.android.imageeditor.core.model

import android.graphics.Matrix
import android.os.Parcel
import kotlinx.parcelize.Parceler
import java.util.Stack
import java.util.UUID







object MatrixParceler : Parceler<Matrix> {
    override fun create(parcel: Parcel): Matrix {
        val values = FloatArray(9)
        parcel.readFloatArray(values)
        return Matrix().apply { setValues(values) }
    }

    override fun Matrix.write(parcel: Parcel, flags: Int) {
        val values = FloatArray(9)
        getValues(values)
        parcel.writeFloatArray(values)
    }
}


object UuidParceler : Parceler<UUID> {
    override fun create(parcel: Parcel): UUID = UUID(parcel.readLong(), parcel.readLong())

    override fun UUID.write(parcel: Parcel, flags: Int) {
        parcel.writeLong(mostSignificantBits)
        parcel.writeLong(leastSignificantBits)
    }
}


object FlagsParceler : Parceler<EditorFlags> {
    override fun create(parcel: Parcel): EditorFlags = EditorFlags(parcel.readInt())

    override fun EditorFlags.write(parcel: Parcel, flags: Int) {
        parcel.writeInt(asInt())
    }
}




object StackParceler : Parceler<Stack<ByteArray>> {
    override fun create(parcel: Parcel): Stack<ByteArray> {
        val count = parcel.readInt()
        val stack = Stack<ByteArray>()
        for (i in 0 until count) {
            stack.add(i, parcel.createByteArray()!!)
        }
        return stack
    }

    override fun Stack<ByteArray>.write(parcel: Parcel, flags: Int) {
        val count = size
        parcel.writeInt(count)
        for (i in 0 until count) {
            parcel.writeByteArray(get(i))
        }
    }
}

package com.difft.android.imageeditor.core.model

import android.graphics.Matrix
import android.os.Parcel
import kotlinx.parcelize.Parceler
import java.util.Stack
import java.util.UUID

/**
 * @Parcelize [Parceler]s that replace the hand-rolled ParcelUtils serialization. Each reproduces the
 * original wire form 1:1 (parcel bytes are transient, same-process only). #1093
 */

/** 9-float [Matrix] serialization (getValues/setValues), matching the former ParcelUtils.writeMatrix/readMatrix. */
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

/** Two-long [UUID] serialization (most-significant, least-significant), matching the former ParcelUtils.writeUUID/readUUID. */
object UuidParceler : Parceler<UUID> {
    override fun create(parcel: Parcel): UUID = UUID(parcel.readLong(), parcel.readLong())

    override fun UUID.write(parcel: Parcel, flags: Int) {
        parcel.writeLong(mostSignificantBits)
        parcel.writeLong(leastSignificantBits)
    }
}

/** Single-int [EditorFlags] serialization via [EditorFlags.asInt], matching the former EditorElement parcel contract. */
object FlagsParceler : Parceler<EditorFlags> {
    override fun create(parcel: Parcel): EditorFlags = EditorFlags(parcel.readInt())

    override fun EditorFlags.write(parcel: Parcel, flags: Int) {
        parcel.writeInt(asInt())
    }
}

/**
 * [Stack] of byte arrays serialization (count then each entry), matching the former ElementStack parcel contract.
 */
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

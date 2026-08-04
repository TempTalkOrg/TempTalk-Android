package com.difft.android.chat.util

import android.os.Parcel
import android.os.Parcelable

object ParcelUtil {

    @JvmStatic
    fun serialize(parceable: Parcelable): ByteArray {
        val parcel = Parcel.obtain()
        parceable.writeToParcel(parcel, 0)
        val bytes = parcel.marshall()
        parcel.recycle()
        return bytes
    }

    @JvmStatic
    fun deserialize(bytes: ByteArray): Parcel {
        val parcel = Parcel.obtain()
        parcel.unmarshall(bytes, 0, bytes.size)
        parcel.setDataPosition(0)
        return parcel
    }

    @JvmStatic
    fun <T> deserialize(bytes: ByteArray, creator: Parcelable.Creator<T>): T {
        val parcel = deserialize(bytes)
        return creator.createFromParcel(parcel)
    }

    @JvmStatic
    fun <E> readParcelableCollection(input: Parcel, clazz: Class<E>): Collection<E> {
        @Suppress("UNCHECKED_CAST")
        return listOf(*(input.readParcelableArray(clazz.classLoader) as Array<E>))
    }

    @JvmStatic
    fun writeByteArray(dest: Parcel, data: ByteArray?) {
        if (data == null) {
            dest.writeInt(-1)
        } else {
            dest.writeInt(data.size)
            dest.writeByteArray(data)
        }
    }

    @JvmStatic
    fun readByteArray(input: Parcel): ByteArray? {
        val length = input.readInt()
        if (length == -1) {
            return null
        }
        val data = ByteArray(length)
        input.readByteArray(data)
        return data
    }
}

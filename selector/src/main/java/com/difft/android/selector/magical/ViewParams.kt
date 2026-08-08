package com.difft.android.selector.magical

import android.os.Parcel
import android.os.Parcelable

class ViewParams : Parcelable {
    @JvmField
    var left: Int = 0

    @JvmField
    var top: Int = 0

    @JvmField
    var width: Int = 0

    @JvmField
    var height: Int = 0

    constructor()

    private constructor(parcel: Parcel) {
        this.left = parcel.readInt()
        this.top = parcel.readInt()
        this.width = parcel.readInt()
        this.height = parcel.readInt()
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(this.left)
        dest.writeInt(this.top)
        dest.writeInt(this.width)
        dest.writeInt(this.height)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ViewParams> = object : Parcelable.Creator<ViewParams> {
            override fun createFromParcel(source: Parcel): ViewParams {
                return ViewParams(source)
            }

            override fun newArray(size: Int): Array<ViewParams?> {
                return arrayOfNulls(size)
            }
        }
    }
}

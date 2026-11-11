package util

import androidx.annotation.Px




@get:Px
val Float.dp: Float get() = DimensionUnit.DP.toPixels(this)




@get:Px
val Int.dp: Int get() = this.toFloat().dp.toInt()
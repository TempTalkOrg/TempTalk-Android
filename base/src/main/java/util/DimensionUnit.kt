package util

import android.content.res.Resources
import androidx.annotation.Dimension
import androidx.annotation.Px
import com.difft.android.base.utils.density.UiDensity




enum class DimensionUnit {
    PIXELS {
        @Px
        override fun toPixels(@Px value: Float): Float = value

        @Dimension(unit = Dimension.DP)
        override fun toDp(@Px value: Float): Float =
            value / UiDensity.density

        @Dimension(unit = Dimension.SP)
        override fun toSp(@Px value: Float): Float =
            value / (Resources.getSystem().displayMetrics.scaledDensity * UiDensity.scale)
    },
    DP {
        @Px
        override fun toPixels(@Dimension(unit = Dimension.DP) value: Float): Float =
            value * UiDensity.density

        @Dimension(unit = Dimension.DP)
        override fun toDp(@Dimension(unit = Dimension.DP) value: Float): Float = value

        @Dimension(unit = Dimension.SP)
        override fun toSp(@Dimension(unit = Dimension.DP) value: Float): Float =
            PIXELS.toSp(toPixels(value))
    },
    SP {
        @Px
        override fun toPixels(@Dimension(unit = Dimension.SP) value: Float): Float =
            value * Resources.getSystem().displayMetrics.scaledDensity * UiDensity.scale

        @Dimension(unit = Dimension.DP)
        override fun toDp(@Dimension(unit = Dimension.SP) value: Float): Float =
            PIXELS.toDp(toPixels(value))

        @Dimension(unit = Dimension.SP)
        override fun toSp(@Dimension(unit = Dimension.SP) value: Float): Float = value
    };

    abstract fun toPixels(value: Float): Float
    abstract fun toDp(value: Float): Float
    abstract fun toSp(value: Float): Float
}

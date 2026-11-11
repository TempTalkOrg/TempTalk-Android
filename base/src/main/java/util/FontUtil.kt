package util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import kotlin.math.abs

object FontUtil {
  private const val SAMPLE_EMOJI = "\uD83C\uDF0D" 

  







  @JvmStatic
  fun canRenderEmojiAtFontSize(size: Float): Boolean {
    val bitmap: Bitmap = createBitmap(1, 1)
    val canvas = Canvas(bitmap)
    val paint = Paint()

    paint.textSize = size
    paint.textAlign = Paint.Align.CENTER

    val ascent: Float = abs(paint.ascent())
    val descent: Float = abs(paint.descent())
    val halfHeight = (ascent + descent) / 2.0f

    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    canvas.drawText(SAMPLE_EMOJI, 0.5f, 0.5f + halfHeight - descent, paint)

    return bitmap[0, 0] != 0
  }
}

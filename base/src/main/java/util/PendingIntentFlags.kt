package util

import android.app.PendingIntent
import android.os.Build









object PendingIntentFlags {

  @JvmStatic
  fun updateCurrent(): Int {
    return mutable() or PendingIntent.FLAG_UPDATE_CURRENT
  }

  @JvmStatic
  fun cancelCurrent(): Int {
    return mutable() or PendingIntent.FLAG_CANCEL_CURRENT
  }

  



  @JvmStatic
  fun oneShot(): Int {
    return immutable() or PendingIntent.FLAG_ONE_SHOT
  }

  


  @JvmStatic
  fun mutable(): Int {
    return if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
  }

  @JvmStatic
  fun immutable(): Int {
    return PendingIntent.FLAG_IMMUTABLE
  }
}

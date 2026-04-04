package org.thoughtcrime.securesms.util;

import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.difft.android.base.log.lumberjack.L;

import java.io.ByteArrayOutputStream;

public class BitmapUtil {

  private static final String TAG = "BitmapUtil";


  @WorkerThread
  public static Bitmap createScaledBitmap(Bitmap bitmap, int maxWidth, int maxHeight) {
    if (bitmap.getWidth() <= maxWidth && bitmap.getHeight() <= maxHeight) {
      return bitmap;
    }

    if (maxWidth <= 0 || maxHeight <= 0) {
      return bitmap;
    }

    int newWidth  = maxWidth;
    int newHeight = maxHeight;

    float widthRatio  = bitmap.getWidth()  / (float) maxWidth;
    float heightRatio = bitmap.getHeight() / (float) maxHeight;

    if (widthRatio > heightRatio) {
      newHeight = (int) (bitmap.getHeight() / widthRatio);
    } else {
      newWidth = (int) (bitmap.getWidth() / heightRatio);
    }

    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
  }


  public static @Nullable byte[] toByteArray(@Nullable Bitmap bitmap) {
    if (bitmap == null) return null;
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    bitmap.compress(CompressFormat.PNG, 100, stream);
    return stream.toByteArray();
  }

}

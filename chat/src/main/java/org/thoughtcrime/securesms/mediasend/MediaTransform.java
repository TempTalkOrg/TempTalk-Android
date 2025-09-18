package org.thoughtcrime.securesms.mediasend;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.luck.picture.lib.entity.LocalMedia;

public interface MediaTransform {

  @WorkerThread
  @NonNull
  LocalMedia transform(@NonNull Context context, @NonNull LocalMedia media);
}

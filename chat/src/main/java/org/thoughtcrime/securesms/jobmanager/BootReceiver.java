package org.thoughtcrime.securesms.jobmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.difft.android.base.log.lumberjack.L;

public class BootReceiver extends BroadcastReceiver {

  private static final String TAG = L.INSTANCE.tag(BootReceiver.class);

  @Override
  public void onReceive(Context context, Intent intent) {
    L.i(() -> "Boot received. Application is created, kickstarting JobManager.");
  }
}

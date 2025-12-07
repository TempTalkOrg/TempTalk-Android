package org.thoughtcrime.securesms.jobmanager.impl;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.difft.android.base.log.lumberjack.L;

import org.thoughtcrime.securesms.jobmanager.ConstraintObserver;

import util.concurrent.TTExecutors;

/**
 * Observes the charging state of the device and notifies the JobManager system when appropriate.
 */
public class ChargingConstraintObserver implements ConstraintObserver {

    private static final String REASON = L.INSTANCE.tag(ChargingConstraintObserver.class);
    private static final int STATUS_BATTERY = 0;

    private final Application application;

    private static volatile boolean charging;

    public ChargingConstraintObserver(@NonNull Application application) {
        this.application = application;
    }

    @Override
    public void register(@NonNull Notifier notifier) {
        Intent intent = application.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 使用 goAsync() 避免主线程阻塞
                // 将整个执行器调用也放到异步处理中，避免在主线程上调用 execute()
                final PendingResult pendingResult = goAsync();

                // 使用一个专用的后台线程来调度任务，避免在主线程上调用 ThreadPoolExecutor.execute()
                new Thread(() -> {
                    try {
                        boolean wasCharging = charging;

                        charging = isCharging(intent);

                        if (charging && !wasCharging) {
                            notifier.onConstraintMet(REASON);
                        }
                    } catch (Exception e) {
                        L.e(() -> REASON + " Error processing charging constraint: " + e.getMessage());
                    } finally {
                        pendingResult.finish();
                    }
                }, "ChargingObserver-Worker").start();
            }
        }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        charging = isCharging(intent);
    }

    public static boolean isCharging() {
        return charging;
    }

    private static boolean isCharging(@Nullable Intent intent) {
        if (intent == null) {
            return false;
        }

        int status = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, STATUS_BATTERY);
        return status != STATUS_BATTERY;
    }
}

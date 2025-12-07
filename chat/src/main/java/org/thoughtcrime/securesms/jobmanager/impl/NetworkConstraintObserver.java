package org.thoughtcrime.securesms.jobmanager.impl;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;

import androidx.annotation.NonNull;

import com.difft.android.base.log.lumberjack.L;

import org.thoughtcrime.securesms.jobmanager.ConstraintObserver;

import util.concurrent.TTExecutors;

public class NetworkConstraintObserver implements ConstraintObserver {

    private static final String REASON = L.INSTANCE.tag(NetworkConstraintObserver.class);

    private final Application application;

    public NetworkConstraintObserver(Application application) {
        this.application = application;
    }

    @Override
    public void register(@NonNull Notifier notifier) {
        application.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 使用 goAsync() 避免主线程阻塞
                // 将整个执行器调用也放到异步处理中，避免在主线程上调用 execute()
                final PendingResult pendingResult = goAsync();

                // 使用一个专用的后台线程来调度任务，避免在主线程上调用 ThreadPoolExecutor.execute()
                new Thread(() -> {
                    try {
                        NetworkConstraint constraint = new NetworkConstraint.Factory(application).create();

                        if (constraint.isMet()) {
                            notifier.onConstraintMet(REASON);
                        }
                    } catch (Exception e) {
                        L.e(() -> REASON + " Error processing network constraint: " + e.getMessage());
                    } finally {
                        pendingResult.finish();
                    }
                }, "NetworkObserver-Worker").start();
            }
        }, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }
}

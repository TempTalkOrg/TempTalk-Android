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
                // 使用 goAsync() 和 TTExecutors.UNBOUNDED 避免主线程 Binder IPC 阻塞
                final PendingResult pendingResult = goAsync();
                TTExecutors.UNBOUNDED.execute(() -> {
                    try {
                        NetworkConstraint constraint = new NetworkConstraint.Factory(application).create();

                        if (constraint.isMet()) {
                            notifier.onConstraintMet(REASON);
                        }
                    } finally {
                        pendingResult.finish();
                    }
                });
            }
        }, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }
}

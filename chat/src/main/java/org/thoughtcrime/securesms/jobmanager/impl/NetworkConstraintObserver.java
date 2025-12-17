package org.thoughtcrime.securesms.jobmanager.impl;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.jobmanager.ConstraintObserver;

public class NetworkConstraintObserver implements ConstraintObserver {

    // 使用字符串常量避免类加载时触发 L.INSTANCE 初始化导致ANR
    private static final String REASON = "NetworkConstraint";

    private final Application application;

    public NetworkConstraintObserver(Application application) {
        this.application = application;
    }

    @Override
    public void register(@NonNull Notifier notifier) {
        application.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                NetworkConstraint constraint = new NetworkConstraint.Factory(application).create();

                if (constraint.isMet()) {
                    notifier.onConstraintMet(REASON);
                }
            }
        }, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }
}

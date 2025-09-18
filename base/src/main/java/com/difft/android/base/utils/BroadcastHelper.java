package com.difft.android.base.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.HashMap;
import java.util.Map;

public class BroadcastHelper {

    public interface IBroadcastReceiverCallback {

        void onReceive(Context context, Intent intent);
    }

    private Context context;

    private Map<String, BroadcastReceiver> receiverHashMap = new HashMap<>();

    public BroadcastHelper(Context context) {
        this.context = context != null ? context : ApplicationHelper.INSTANCE.getInstance();
    }

    public void register(final IBroadcastReceiverCallback receiverCallback, String... filters) {
        if (filters == null) {
            return;
        }
        for (String filter : filters) {
            register(filter, receiverCallback);
        }
    }

    public void register(String filter, final IBroadcastReceiverCallback receiverCallback) {
        BroadcastReceiver oldR = receiverHashMap.remove(filter);
        if (oldR != null) {
            unregister(oldR);
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                receiverCallback.onReceive(context, intent);
            }
        };
        receiverHashMap.put(filter, receiver);
        LocalBroadcastManager.getInstance(context)
                .registerReceiver(receiver, new IntentFilter(filter));
    }

    public void unregister(BroadcastReceiver receiver) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver);
    }

    public void unregisterAll() {
        for (BroadcastReceiver receiver : receiverHashMap.values()) {
            unregister(receiver);
        }
        receiverHashMap.clear();
    }

    public static void sendBroadcast(String action) {
        sendBroadcast(new Intent(action));
    }

    public static void sendBroadcast(Intent intent) {
        LocalBroadcastManager.getInstance(ApplicationHelper.INSTANCE.getInstance()).sendBroadcast(intent);
    }
}

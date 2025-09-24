/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.service;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.difft.android.base.log.lumberjack.L;
import com.difft.android.chat.R;

import org.thoughtcrime.securesms.DummyActivity;
import org.thoughtcrime.securesms.crypto.InvalidPassphraseException;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.crypto.MasterSecretUtil;
import org.thoughtcrime.securesms.util.AppForegroundObserver;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.util.concurrent.TimeUnit;

/**
 * Small service that stays running to keep a key cached in memory.
 *
 * @author Moxie Marlinspike
 */

public class KeyCachingService extends Service {

    private static final String TAG = L.INSTANCE.tag(KeyCachingService.class);

    public static final int SERVICE_RUNNING_ID = 4141;

    private static final String APPLICATION_ID = "org.difft.chative";
    public static final String KEY_PERMISSION = APPLICATION_ID + ".ACCESS_SECRETS";
    public static final String NEW_KEY_EVENT = APPLICATION_ID + ".service.action.NEW_KEY_EVENT";
    public static final String CLEAR_KEY_EVENT = APPLICATION_ID + ".service.action.CLEAR_KEY_EVENT";
    public static final String LOCK_TOGGLED_EVENT = APPLICATION_ID + ".service.action.LOCK_ENABLED_EVENT";
    private static final String PASSPHRASE_EXPIRED_EVENT = APPLICATION_ID + ".service.action.PASSPHRASE_EXPIRED_EVENT";
    public static final String CLEAR_KEY_ACTION = APPLICATION_ID + ".service.action.CLEAR_KEY";
    public static final String DISABLE_ACTION = APPLICATION_ID + ".service.action.DISABLE";
    public static final String LOCALE_CHANGE_EVENT = APPLICATION_ID + ".service.action.LOCALE_CHANGE_EVENT";

    private DynamicLanguage dynamicLanguage = new DynamicLanguage();

    private final IBinder binder = new KeySetBinder();

    private static MasterSecret masterSecret;

    public KeyCachingService() {
    }

    public static synchronized boolean isLocked(Context context) {
        boolean locked = masterSecret == null && (!TextSecurePreferences.isPasswordDisabled(context) || TextSecurePreferences.isScreenLockEnabled(context));

        if (locked) {
            L.d(() -> "Locked! PasswordDisabled: " + TextSecurePreferences.isPasswordDisabled(context) + ", ScreenLock: " + TextSecurePreferences.isScreenLockEnabled(context));
        }

        return locked;
    }

    public static synchronized @Nullable MasterSecret getMasterSecret(Context context) {
        if (masterSecret == null && (TextSecurePreferences.isPasswordDisabled(context) && !TextSecurePreferences.isScreenLockEnabled(context))) {
            try {
                return MasterSecretUtil.getMasterSecret(context, MasterSecretUtil.UNENCRYPTED_PASSPHRASE);
            } catch (InvalidPassphraseException e) {
                L.w(e::toString);
            }
        }

        return masterSecret;
    }

    public static void onAppForegrounded(@NonNull Context context) {
        if (TextSecurePreferences.isScreenLockEnabled(context) || !TextSecurePreferences.isPasswordDisabled(context)) {
            ServiceUtil.getAlarmManager(context).cancel(buildExpirationPendingIntent(context));
        }
    }

    public static void onAppBackgrounded(@NonNull Context context) {
        startTimeoutIfAppropriate(context);
    }

    @SuppressLint("StaticFieldLeak")
    public void setMasterSecret(final MasterSecret masterSecret) {
        synchronized (KeyCachingService.class) {
            KeyCachingService.masterSecret = masterSecret;

            foregroundService();
            broadcastNewSecret();
            startTimeoutIfAppropriate(this);

            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
//          if (!ApplicationMigrations.isUpdate(KeyCachingService.this)) {
//            ApplicationDependencies.getMessageNotifier().updateNotification(KeyCachingService.this);
//          }
                    return null;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        L.d(() -> "onStartCommand, " + intent.getAction());

        if (intent.getAction() != null) {
            switch (intent.getAction()) {
                case CLEAR_KEY_ACTION:
                    handleClearKey();
                    break;
                case PASSPHRASE_EXPIRED_EVENT:
                    handleClearKey();
                    break;
                case DISABLE_ACTION:
                    handleDisableService();
                    break;
                case LOCALE_CHANGE_EVENT:
                    handleLocaleChanged();
                    break;
                case LOCK_TOGGLED_EVENT:
                    handleLockToggled();
                    break;
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        L.i(() -> "onCreate()");
        super.onCreate();

        if (TextSecurePreferences.isPasswordDisabled(this) && !TextSecurePreferences.isScreenLockEnabled(this)) {
            try {
                MasterSecret masterSecret = MasterSecretUtil.getMasterSecret(this, MasterSecretUtil.UNENCRYPTED_PASSPHRASE);
                setMasterSecret(masterSecret);
            } catch (InvalidPassphraseException e) {
                L.w(e::toString);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        L.w(() -> "KCS Is Being Destroyed!");
        handleClearKey();
    }

    /**
     * Workaround for Android bug:
     * https://code.google.com/p/android/issues/detail?id=53313
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent intent = new Intent(this, DummyActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @SuppressLint("StaticFieldLeak")
    private void handleClearKey() {
        L.i(() -> "handleClearKey()");
        KeyCachingService.masterSecret = null;
        stopForeground(true);

        Intent intent = new Intent(CLEAR_KEY_EVENT);
        intent.setPackage(getApplicationContext().getPackageName());

        sendBroadcast(intent, KEY_PERMISSION);

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
//        ApplicationDependencies.getMessageNotifier().updateNotification(KeyCachingService.this);
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void handleLockToggled() {
        stopForeground(true);

        try {
            MasterSecret masterSecret = MasterSecretUtil.getMasterSecret(this, MasterSecretUtil.UNENCRYPTED_PASSPHRASE);
            setMasterSecret(masterSecret);
        } catch (InvalidPassphraseException e) {
            L.w(e::toString);
        }
    }

    private void handleDisableService() {
        if (TextSecurePreferences.isPasswordDisabled(this) &&
                !TextSecurePreferences.isScreenLockEnabled(this)) {
            stopForeground(true);
        }
    }

    private void handleLocaleChanged() {
        dynamicLanguage.updateServiceLocale(this);
        foregroundService();
    }

    private static void startTimeoutIfAppropriate(@NonNull Context context) {
        boolean appVisible = AppForegroundObserver.isForegrounded();
        boolean secretSet = KeyCachingService.masterSecret != null;

        boolean timeoutEnabled = TextSecurePreferences.isPassphraseTimeoutEnabled(context);
        boolean passLockActive = timeoutEnabled && !TextSecurePreferences.isPasswordDisabled(context);

        long screenTimeout = TextSecurePreferences.getScreenLockTimeout(context);
        boolean screenLockActive = screenTimeout >= 60 && TextSecurePreferences.isScreenLockEnabled(context);

        if (!appVisible && secretSet && (passLockActive || screenLockActive)) {
            long passphraseTimeoutMinutes = TextSecurePreferences.getPassphraseTimeoutInterval(context);
            long screenLockTimeoutSeconds = TextSecurePreferences.getScreenLockTimeout(context);

            long timeoutMillis;

            if (!TextSecurePreferences.isPasswordDisabled(context)) timeoutMillis = TimeUnit.MINUTES.toMillis(passphraseTimeoutMinutes);
            else timeoutMillis = TimeUnit.SECONDS.toMillis(screenLockTimeoutSeconds);

            L.i(() -> "Starting timeout: " + timeoutMillis);

            AlarmManager alarmManager = ServiceUtil.getAlarmManager(context);
            PendingIntent expirationIntent = buildExpirationPendingIntent(context);

            alarmManager.cancel(expirationIntent);
            alarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + timeoutMillis, expirationIntent);
        }
    }

    private void foregroundService() {
        if (TextSecurePreferences.isPasswordDisabled(this) && !TextSecurePreferences.isScreenLockEnabled(this)) {
            stopForeground(true);
            return;
        }

        L.i(() -> "foregrounding KCS");
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "locked_status_v2");

        builder.setContentTitle(getString(R.string.KeyCachingService_passphrase_cached));
        builder.setContentText(getString(R.string.KeyCachingService_tt_passphrase_cached));
        builder.setSmallIcon(R.drawable.chat_icon_cached);
        builder.setWhen(0);
        builder.setPriority(Notification.PRIORITY_MIN);

        builder.addAction(R.drawable.chat_ic_menu_lock_dark, getString(R.string.KeyCachingService_lock), buildLockIntent());
        builder.setContentIntent(buildLaunchIntent());

        stopForeground(true);
        startForeground(SERVICE_RUNNING_ID, builder.build());
    }

    private void broadcastNewSecret() {
        L.i(() -> "Broadcasting new secret...");

        Intent intent = new Intent(NEW_KEY_EVENT);
        intent.setPackage(getApplicationContext().getPackageName());

        sendBroadcast(intent, KEY_PERMISSION);
    }

    private PendingIntent buildLockIntent() {
        Intent intent = new Intent(this, KeyCachingService.class);
        intent.setAction(PASSPHRASE_EXPIRED_EVENT);
        return PendingIntent.getService(getApplicationContext(), 0, intent, getPendingIntentFlags());
    }

    private PendingIntent buildLaunchIntent() {
        // TODO [greyson] Navigation
        return PendingIntent.getActivity(getApplicationContext(), 0, new Intent(), getPendingIntentFlags());
    }

    private static PendingIntent buildExpirationPendingIntent(@NonNull Context context) {
        Intent expirationIntent = new Intent(PASSPHRASE_EXPIRED_EVENT, null, context, KeyCachingService.class);
        return PendingIntent.getService(context, 0, expirationIntent, getPendingIntentFlags());
    }

    private static int getPendingIntentFlags() {
        if (Build.VERSION.SDK_INT >= 23) {
            return PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            return PendingIntent.FLAG_UPDATE_CURRENT;
        }
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return binder;
    }

    public class KeySetBinder extends Binder {
        public KeyCachingService getService() {
            return KeyCachingService.this;
        }
    }
}

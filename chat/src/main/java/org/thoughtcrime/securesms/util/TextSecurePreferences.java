package org.thoughtcrime.securesms.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

/**
 * SharedPreferences 工具类
 * 已清理 Signal 历史残留代码，只保留实际使用的方法
 */
public class TextSecurePreferences {

    private static final String DATABASE_ENCRYPTED_SECRET = "pref_database_encrypted_secret";
    private static final String DATABASE_UNENCRYPTED_SECRET = "pref_database_unencrypted_secret";
    private static final String MULTI_DEVICE_PROVISIONED_PREF = "pref_multi_device";
    public static final String INCOGNITO_KEYBORAD_PREF = "pref_incognito_keyboard";
    public static final String SCREEN_SECURITY_PREF = "pref_screen_security";
    private static final String JOB_MANAGER_VERSION = "pref_job_manager_version";

    private static volatile SharedPreferences preferences = null;

    // Database Secret
    public static void setDatabaseEncryptedSecret(@NonNull Context context, @NonNull String secret) {
        setStringPreference(context, DATABASE_ENCRYPTED_SECRET, secret);
    }

    public static void setDatabaseUnencryptedSecret(@NonNull Context context, @Nullable String secret) {
        setStringPreference(context, DATABASE_UNENCRYPTED_SECRET, secret);
    }

    public static @Nullable String getDatabaseUnencryptedSecret(@NonNull Context context) {
        return getStringPreference(context, DATABASE_UNENCRYPTED_SECRET, null);
    }

    public static @Nullable String getDatabaseEncryptedSecret(@NonNull Context context) {
        return getStringPreference(context, DATABASE_ENCRYPTED_SECRET, null);
    }

    // Multi Device
    public static void setMultiDevice(Context context, boolean value) {
        setBooleanPreference(context, MULTI_DEVICE_PROVISIONED_PREF, value);
    }

    // Incognito Keyboard
    public static boolean isIncognitoKeyboardEnabled(Context context) {
        return getBooleanPreference(context, INCOGNITO_KEYBORAD_PREF, false);
    }

    // Screen Security
    public static boolean isScreenSecurityEnabled(Context context) {
        return getBooleanPreference(context, SCREEN_SECURITY_PREF, false);
    }

    // Job Manager
    public static void setJobManagerVersion(Context context, int version) {
        setIntegerPrefrence(context, JOB_MANAGER_VERSION, version);
    }

    public static int getJobManagerVersion(Context context) {
        return getIntegerPreference(context, JOB_MANAGER_VERSION, 1);
    }

    // Base preference methods
    public static void setBooleanPreference(Context context, String key, boolean value) {
        getSharedPreferences(context).edit().putBoolean(key, value).apply();
    }

    public static boolean getBooleanPreference(Context context, String key, boolean defaultValue) {
        return getSharedPreferences(context).getBoolean(key, defaultValue);
    }

    public static void setStringPreference(Context context, String key, String value) {
        getSharedPreferences(context).edit().putString(key, value).apply();
    }

    public static String getStringPreference(Context context, String key, String defaultValue) {
        return getSharedPreferences(context).getString(key, defaultValue);
    }

    public static int getIntegerPreference(Context context, String key, int defaultValue) {
        return getSharedPreferences(context).getInt(key, defaultValue);
    }

    private static void setIntegerPrefrence(Context context, String key, int value) {
        getSharedPreferences(context).edit().putInt(key, value).apply();
    }

    public static long getLongPreference(Context context, String key, long defaultValue) {
        return getSharedPreferences(context).getLong(key, defaultValue);
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        if (preferences == null) {
            preferences = PreferenceManager.getDefaultSharedPreferences(context);
        }
        return preferences;
    }
}
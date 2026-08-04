package com.difft.android.chat.util

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.UserManager
import android.os.Vibrator
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager

object ServiceUtil {

    @JvmStatic
    fun getInputMethodManager(context: Context?): InputMethodManager =
        context!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    @JvmStatic
    fun getWindowManager(context: Context): WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @JvmStatic
    fun getConnectivityManager(context: Context): ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @JvmStatic
    fun getNotificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @JvmStatic
    fun getAudioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @JvmStatic
    fun getSensorManager(context: Context): SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @JvmStatic
    fun getPowerManager(context: Context): PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @JvmStatic
    fun getAlarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @JvmStatic
    fun getVibrator(context: Context): Vibrator =
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    @JvmStatic
    fun getClipboardManager(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @JvmStatic
    fun getActivityManager(context: Context): ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    @JvmStatic
    fun getUserManager(context: Context): UserManager =
        context.getSystemService(Context.USER_SERVICE) as UserManager
}

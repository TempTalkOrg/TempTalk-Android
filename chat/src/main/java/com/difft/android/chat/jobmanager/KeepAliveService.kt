package com.difft.android.chat.jobmanager

import android.app.Service
import android.content.Intent
import android.os.IBinder






class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}

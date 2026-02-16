package org.thoughtcrime.securesms.messages

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.websocket.WebSocketManager
import javax.inject.Inject

@AndroidEntryPoint
class MessageForegroundService : Service() {

    companion object {
        const val FOREGROUND_ID = 313399

        @Volatile
        var isRunning = false
            private set
    }

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var webSocketManager: WebSocketManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        L.i { "[MessageForegroundService] onCreate()" }
        postForegroundNotification()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        L.i { "[MessageForegroundService] onStartCommand()" }

        postForegroundNotification()
        webSocketManager.start()

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        L.i { "[MessageForegroundService] onDestroy()" }
    }

    private fun postForegroundNotification() {
        val notification = messageNotificationUtil.createMessageForegroundNotification()
        startForeground(FOREGROUND_ID, notification)
    }
}
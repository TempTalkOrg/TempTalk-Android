package com.difft.android.chat.recent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.difft.android.base.call.CallActionType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallActivity
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ActivityInviteParticipantsBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class InviteParticipantsActivity : AppCompatActivity() {

    companion object {
        const val TAG = "InviteParticipantsActivity"
        const val EXTRA_IS_LIVE_STREAM = "EXTRA_IS_LIVE_STREAM"
        const val EXTRA_EID = "EXTRA_EID_FOR_LIVE_STREAM"
        const val EXTRA_CHANNEL_NAME = "EXTRA_CHANNEL_NAME"
        const val EXTRA_MEETING_NAME = "EXTRA_MEETING_NAME"
        const val EXTRA_MEETING_ID = "EXTRA_MEETING_ID"
        const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"
        const val EXTRA_CONVERSATION_ID = "EXTRA_CONVERSATION_ID"
        const val EXTRA_E2EE_KEY = "EXTRA_E2EE_KEY"
        const val EXTRA_CALL_TYPE = "EXTRA_CALL_TYPE"
        const val EXTRA_CALL_MKEY = "EXTRA_CALL_MKEY"
        const val EXTRA_CALL_NAME = "EXTRA_CALL_NAME"
        const val EXTRA_EXCLUDE_IDS = "EXTRA_EXCLUDE_IDS"
        const val EXTRA_SHOULD_BRING_CALL_SCREEN_BACK =
            "EXTRA_SHOULD_BRING_CALL_SCREEN_BACK"
        const val EXTRA_ACTION_TYPE = "EXTRA_ACTION_TYPE"
        const val NEW_REQUEST_ACTION_TYPE_INVITE = 3
    }

    private lateinit var binding: ActivityInviteParticipantsBinding

    private var isReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInviteParticipantsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
        val meetingName = intent.getStringExtra(EXTRA_MEETING_NAME)
        val meetingId = intent.getIntExtra(EXTRA_MEETING_ID, 0)
        val shouldBringCallScreenBack =
            intent.getBooleanExtra(EXTRA_SHOULD_BRING_CALL_SCREEN_BACK, false)
        val isLiveStream = intent.getBooleanExtra(EXTRA_IS_LIVE_STREAM, false)
        val eid = intent.getStringExtra(EXTRA_EID)
        val excludedIds = intent.getStringArrayListExtra(EXTRA_EXCLUDE_IDS)
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        val callName = intent.getStringExtra(EXTRA_CALL_NAME)
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        val callType = intent.getStringExtra(EXTRA_CALL_TYPE)
        val actionType = intent.getIntExtra(EXTRA_ACTION_TYPE, NEW_REQUEST_ACTION_TYPE_INVITE)
        val e2eeKey = intent.getByteArrayExtra(EXTRA_E2EE_KEY)

        if (savedInstanceState == null) {
            if (actionType == NEW_REQUEST_ACTION_TYPE_INVITE){
                supportFragmentManager.beginTransaction().replace(
                    R.id.container, InviteParticipantsFragment.newCallInstance(
                        callType = callType,
                        actionType = actionType,
                        roomId = roomId,
                        mKey = e2eeKey,
                        conversationId = conversationId,
                        excludedIds = excludedIds,
                        callName = callName,
                    )
                ).commitNow()
            } else {
                supportFragmentManager.beginTransaction().replace(
                    R.id.container, InviteParticipantsFragment.newInstance(
                        actionType,
                        channelName,
                        meetingName,
                        meetingId,
                        shouldBringCallScreenBack,
                        isLiveStream,
                        eid,
                        excludedIds = excludedIds
                    )
                ).commitNow()
            }
        }

        ContextCompat.registerReceiver(
            this, broadcastReceiver, IntentFilter(
                LCallActivity.ACTION_IN_CALLING_CONTROL
            ), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            unregisterReceiver(broadcastReceiver)
            isReceiverRegistered = false
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.`package` != packageName || intent.action != LCallActivity.ACTION_IN_CALLING_CONTROL) {
                return
            }
            when (intent.getStringExtra(LCallActivity.EXTRA_CONTROL_TYPE)) {
                CallActionType.DECLINE.type -> {
                    L.i { "[Call] InviteParticipantsActivity CONTROL_TYPE_DECLINE" }
                    finish()
                }
            }
        }
    }
}
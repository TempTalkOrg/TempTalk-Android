package com.difft.android.chat.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.LanguageUtils
import org.difft.app.database.attachment
import org.difft.app.database.getContactorsFromAllTable
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import org.difft.app.database.getReadInfoList
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.databinding.ActivityMessageDetailBinding
import com.difft.android.chat.message.parseReadInfo
import com.difft.android.chat.message.parseReceiverIds
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MessageModel
import util.TimeFormatter
import util.TimeUtils
import org.thoughtcrime.securesms.util.Util
import org.thoughtcrime.securesms.util.shareFile
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.io.File

@AndroidEntryPoint
class MessageDetailActivity : BaseActivity() {

    companion object {
        private const val BUNDLE_KEY_MESSAGE_ID = "BUNDLE_KEY_MESSAGE_ID"
        fun startActivity(activity: Activity, messageId: String) {
            val intent = Intent(activity, MessageDetailActivity::class.java)
            intent.putExtra(BUNDLE_KEY_MESSAGE_ID, messageId)
            activity.startActivity(intent)
        }
    }

    private val binding: ActivityMessageDetailBinding by viewbind()

    private val messageId: String by lazy {
        intent.getStringExtra(BUNDLE_KEY_MESSAGE_ID) ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.ibBack.setOnClickListener { finish() }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val messageModel = wcdb.message.getAllObjects(DBMessageModel.id.eq(messageId)).firstOrNull()
                messageModel ?: return@launch

                withContext(Dispatchers.Main) {
                    initView(messageModel)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        MessageDetailBitmapHolder.clear()
        super.onDestroy()
    }

    private fun initView(message: MessageModel) {
        if (message.fromWho == globalServices.myId) {
            MessageDetailBitmapHolder.getBitmap()?.let {
                binding.ivMessage.visibility = View.VISIBLE
                binding.ivMessage.setImageBitmap(it)
            } ?: run {
                binding.ivMessage.visibility = View.GONE
            }

            binding.llSend.visibility = View.VISIBLE

            binding.tvSendStatus.text = when (message.sendType) {
                SendType.Sending.rawValue -> getString(R.string.chat_status_sending)
                SendType.Sent.rawValue -> getString(R.string.chat_status_sent)
                SendType.SentFailed.rawValue -> getString(R.string.chat_status_fail)
                else -> getString(R.string.chat_status_sent)
            }

            binding.tvSendTime.text = TimeFormatter.formatMessageTime(LanguageUtils.getLanguage(this).language, message.systemShowTimestamp)

            setTime(message.timeStamp, binding.tvTime1)
            setTime(message.systemShowTimestamp, binding.tvTime3)
            setTime(message.readTime, binding.tvTime4)
            binding.tvExpiresInSeconds.text = message.expiresInSeconds.toString()

            if (message.roomId != globalServices.myId) {
                initMessageReadInfo(message)
            }
        } else {
            MessageDetailBitmapHolder.getBitmap()?.let {
                binding.ivReceivedMessage.visibility = View.VISIBLE
                binding.ivReceivedMessage.setImageBitmap(it)
            } ?: run {
                binding.ivReceivedMessage.visibility = View.GONE
            }

            binding.llReceived.visibility = View.VISIBLE
            binding.llRead.visibility = View.GONE
            binding.llUnread.visibility = View.GONE

            lifecycleScope.launch {
                val sender = withContext(Dispatchers.IO) {
                    wcdb.getContactorsFromAllTable(listOf(message.fromWho)).firstOrNull()
                }
                binding.tvSenderName.text = sender?.getDisplayNameForUI()
            }

            binding.tvReceivedTime.text = TimeFormatter.formatMessageTime(LanguageUtils.getLanguage(this).language, message.systemShowTimestamp)
            setTime(message.timeStamp, binding.tvReceivedTime1)
            setTime(message.receivedTimeStamp, binding.tvReceivedTime2)
            setTime(message.systemShowTimestamp, binding.tvReceivedTime3)
            setTime(message.readTime, binding.tvReceivedTime4)
            binding.tvReceivedExpiresInSeconds.text = message.expiresInSeconds.toString()
        }

        binding.llShare.visibility = View.GONE

        message.attachment()?.let { attachment ->
            if (attachment.isAudioMessage() || attachment.isAudioFile()) {
                binding.llFileName.visibility = View.GONE
                binding.llShare.visibility = View.GONE
            } else {
                binding.llFileName.visibility = View.VISIBLE
                binding.tvFileName.text = attachment.fileName

                val attachmentPath = FileUtil.getMessageAttachmentFilePath(message.id) + attachment.fileName
                if (File(attachmentPath).exists()) {
                    binding.llShare.visibility = View.VISIBLE
                    binding.ivShare.setOnClickListener {
                        this.shareFile(attachmentPath)
                    }
                } else {
                    binding.llShare.visibility = View.GONE
                }
            }
        } ?: run {
            binding.llFileName.visibility = View.GONE
            binding.llShare.visibility = View.GONE
        }
    }

    private fun initMessageReadInfo(message: MessageModel) {
        lifecycleScope.launch(Dispatchers.IO) {

            val receiverIds = if (message.roomType == 1) {
                parseReceiverIds(message.receiverIds) ?: emptyList()
            } else {
                listOf(message.roomId)
            }

            var receivers = wcdb.getContactorsFromAllTable(receiverIds)

            val readInfoList = wcdb.getReadInfoList(message.roomId).filter { it.readPosition >= message.systemShowTimestamp }

            // 普通群消息：如果 receiverIds 缺失但有 readInfoList，直接用 readInfoList 中的 uid 获取联系人（排除自己）
            if (message.roomType == 1 && message.mode != SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
                if (receivers.isEmpty() && readInfoList.isNotEmpty()) {
                    L.w { "Group message receiverIds is null, messageId: ${message.id}, systemShowTimestamp: ${message.systemShowTimestamp}" }
                    val readUserIds = readInfoList.map { it.uid }.filter { it != globalServices.myId }
                    receivers = wcdb.getContactorsFromAllTable(readUserIds)
                }
            }

            withContext(Dispatchers.Main) {
                var readList: List<ContactorModel>? = null
                var unreadList: List<ContactorModel>? = null

                //group message
                if (message.roomType == 1) {
                    if (message.mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
                        val readInfos = parseReadInfo(message.readInfo)

                        if (readInfos.isNullOrEmpty()) {
                            unreadList = receivers
                        } else {
                            val readUserIds = readInfos.keys.toSet()
                            readList = receivers.filter { readUserIds.contains(it.id) }
                            unreadList = receivers.filter { !readUserIds.contains(it.id) }
                        }
                    } else {
                        val readUserIds = readInfoList.map { it.uid }.toSet()
                        readList = receivers.filter { readUserIds.contains(it.id) }
                        unreadList = receivers.filter { !readUserIds.contains(it.id) }
                    }
                } else {
                    if (message.mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
                        unreadList = receivers
                    } else {
                        if (readInfoList.isEmpty()) {
                            unreadList = receivers
                        } else {
                            readList = receivers
                        }
                    }
                }

                binding.llRead.visibility = View.GONE
                binding.llUnread.visibility = View.GONE

                if (!readList.isNullOrEmpty()) {
                    binding.llRead.visibility = View.VISIBLE
                    binding.rvRead.apply {
                        this.adapter = messageReadInfoAdapter
                        this.layoutManager = LinearLayoutManager(this@MessageDetailActivity)
                        itemAnimator = null
                    }
                    messageReadInfoAdapter.submitList(readList)
                }

                if (!unreadList.isNullOrEmpty()) {
                    binding.llUnread.visibility = View.VISIBLE
                    binding.rvUnread.apply {
                        this.adapter = messageUnReadInfoAdapter
                        this.layoutManager = LinearLayoutManager(this@MessageDetailActivity)
                        itemAnimator = null
                    }
                    messageUnReadInfoAdapter.submitList(unreadList)
                }
            }
        }
    }

    private fun setTime(timeStamp: Long, textView: TextView) {
        if (timeStamp == 0L) {
            textView.visibility = View.GONE
        } else {
            textView.visibility = View.VISIBLE

            val timeStr = TimeUtils.millis2String(timeStamp, "yyyy/MM/dd HH:mm:ss") + "  (" + timeStamp + ")"
            textView.text = timeStr
            textView.setOnLongClickListener {
                Util.copyToClipboard(this, timeStamp.toString())
                true
            }
        }
    }

    private val messageReadInfoAdapter: MessageReadInfoAdapter by lazy {
        object : MessageReadInfoAdapter() {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                // ChatActivity.startActivity(this@MessageDetailActivity, contact.id)
            }
        }
    }

    private val messageUnReadInfoAdapter: MessageReadInfoAdapter by lazy {
        object : MessageReadInfoAdapter() {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                // ChatActivity.startActivity(this@MessageDetailActivity, contact.id)
            }
        }
    }

//    private fun calculateRemainingTime(readTime: Long, expiresInSeconds: Int): String {
//        val currentTimeMillis = System.currentTimeMillis()
//
//        if (readTime <= 0 || expiresInSeconds <= 0) {
//            L.i { "calculateRemainingTime:$readTime $expiresInSeconds" }
//            return "Never expires"
//        }
//
//        val expiryTime = readTime + expiresInSeconds * 1000L
//        val remainingTimeMillis = expiryTime - currentTimeMillis
//
//        return buildString {
//            append(TimeUtils.millis2FitTimeSpan(remainingTimeMillis, 4, true))
//            append("    ")
//            append(TimeUtils.millis2FitTimeSpan(expiresInSeconds * 1000L, 4, true))
//            append("(${expiresInSeconds * 1000L})")
//        }
//    }
}

object MessageDetailBitmapHolder {
    private var bitmap: Bitmap? = null

    fun setBitmap(bitmap: Bitmap) {
        clear()
        this.bitmap = bitmap
    }

    fun getBitmap(): Bitmap? {
        return bitmap
    }

    fun clear() {
        bitmap?.recycle() // 释放 Bitmap 资源
        bitmap = null
    }
}
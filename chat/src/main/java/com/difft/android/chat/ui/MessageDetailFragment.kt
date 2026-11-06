package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.LanguageUtils
import org.difft.app.database.attachment
import org.difft.app.database.getContactorsFromAllTable
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import org.difft.app.database.getReadInfoList
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.chat.attachment.AttachmentPathResolver
import difft.android.messageserialization.model.Attachment
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.databinding.ActivityMessageDetailBinding
import com.difft.android.chat.message.parseReceiverIds
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MessageModel
import util.TimeFormatter
import util.TimeUtils
import com.difft.android.chat.util.Util
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.util.shareFile
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.util.UUID

/**
 * Details of one message (times, read state, file). A page of the home detail stack or of its
 * shell [MessageDetailActivity]. The message snapshot comes from [MessageDetailBitmapHolder] by
 * the key in the arguments; the key is released when the page closes for good.
 */
class MessageDetailFragment : Fragment() {

    companion object {
        const val ARG_MESSAGE_ID = "BUNDLE_KEY_MESSAGE_ID"
        const val ARG_BITMAP_KEY = "BUNDLE_KEY_BITMAP_KEY"

        fun newInstance(messageId: String, bitmapKey: String?): MessageDetailFragment =
            MessageDetailFragment().apply { arguments = arguments(messageId, bitmapKey) }

        fun arguments(messageId: String, bitmapKey: String?): Bundle = Bundle().apply {
            putString(ARG_MESSAGE_ID, messageId)
            putString(ARG_BITMAP_KEY, bitmapKey)
        }
    }

    private var _binding: ActivityMessageDetailBinding? = null
    private val binding: ActivityMessageDetailBinding get() = _binding!!

    private val messageId: String by lazy { arguments?.getString(ARG_MESSAGE_ID).orEmpty() }
    private val bitmapKey: String? by lazy { arguments?.getString(ARG_BITMAP_KEY) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityMessageDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        // A fold recreates the page with the same arguments: keep the snapshot for it.
        if (activity?.isChangingConfigurations != true) MessageDetailBitmapHolder.release(bitmapKey)
        super.onDestroy()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (messageId.isEmpty()) {
            L.w { "[MessageDetail] page without a message id" }
            detailNavigation().pop(this)
            return
        }
        setupBackButton(binding.ibBack)
        applyDetailPageInsets(binding.root)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val messageModel = wcdb.message.getAllObjects(DBMessageModel.id.eq(messageId)).firstOrNull()
                messageModel ?: return@launch

                // Resolved here, not in initView: the share gate is not a download gate — a file
                // still at its pre-per-copy owner-message address would simply make the Share entry
                // disappear — so the read has to be the migrating one, which is blocking IO.
                val attachment = messageModel.attachment()
                val attachmentPath = attachment?.let {
                    AttachmentPathResolver.materializedFileFor(it, messageModel.id)
                }

                withContext(Dispatchers.Main) {
                    if (_binding != null) initView(messageModel, attachment, attachmentPath)
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[MessageDetail] loadMessage error: ${e.stackTraceToString()}" }
            }
        }
    }

    // Numeric-only display (expires-in seconds); no English text to translate.
    @SuppressLint("SetTextI18n")
    private fun initView(message: MessageModel, attachment: Attachment?, attachmentPath: String?) {
        if (message.fromWho == globalServices.myId) {
            MessageDetailBitmapHolder.get(bitmapKey)?.let {
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

            binding.tvSendTime.text = TimeFormatter.formatMessageTime(LanguageUtils.getLanguage(requireContext()).language, message.systemShowTimestamp)

            setTime(message.timeStamp, binding.tvTime1)
            setTime(message.systemShowTimestamp, binding.tvTime3)
            // A migrated "not started" sentinel is negative; setTime hides the row on 0, so map it there.
            setTime(message.readTime.takeIf { it > 0 } ?: 0L, binding.tvTime4)
            binding.tvExpiresInSeconds.text = message.expiresInSeconds.toString()

            if (message.roomId != globalServices.myId) {
                initMessageReadInfo(message)
            }
        } else {
            MessageDetailBitmapHolder.get(bitmapKey)?.let {
                binding.ivReceivedMessage.visibility = View.VISIBLE
                binding.ivReceivedMessage.setImageBitmap(it)
            } ?: run {
                binding.ivReceivedMessage.visibility = View.GONE
            }

            binding.llReceived.visibility = View.VISIBLE
            binding.llRead.visibility = View.GONE
            binding.llUnread.visibility = View.GONE

            viewLifecycleOwner.lifecycleScope.launch {
                val sender = withContext(Dispatchers.IO) {
                    wcdb.getContactorsFromAllTable(listOfNotNull(message.fromWho)).firstOrNull()
                }
                binding.tvSenderName.text = sender?.getDisplayNameForUI()
            }

            binding.tvReceivedTime.text = TimeFormatter.formatMessageTime(LanguageUtils.getLanguage(requireContext()).language, message.systemShowTimestamp)
            setTime(message.timeStamp, binding.tvReceivedTime1)
            setTime(message.receivedTimeStamp, binding.tvReceivedTime2)
            setTime(message.systemShowTimestamp, binding.tvReceivedTime3)
            setTime(message.readTime.takeIf { it > 0 } ?: 0L, binding.tvReceivedTime4)
            binding.tvReceivedExpiresInSeconds.text = message.expiresInSeconds.toString()
        }

        binding.llShare.visibility = View.GONE

        attachment?.let {
            if (attachment.isAudioMessage() || attachment.isAudioFile()) {
                binding.llFileName.visibility = View.GONE
                binding.llShare.visibility = View.GONE
            } else {
                binding.llFileName.visibility = View.VISIBLE
                binding.tvFileName.text = attachment.fileName

                // A confidential attachment must not be shared out of the app — hide the share entry
                // (consistent with confidential messages getting no copy/forward/save elsewhere).
                val isConfidential = message.mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE
                if (attachmentPath != null && EncryptedAttachmentAccess.isReadable(attachmentPath) && !isConfidential) {
                    binding.llShare.visibility = View.VISIBLE
                    binding.ivShare.setOnClickListener {
                        // shareFile is the single chokepoint: it routes encrypted-at-rest attachments
                        // (image/audio) through the decrypting content provider and falls back to a
                        // FileProvider uri only for plaintext files — so no plaintext leaves the
                        // sandbox and a transient/missing plaintext file can't cause ENOENT.
                        requireContext().shareFile(attachmentPath)
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
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {

            val receiverIds = if (message.roomType == 1) {
                parseReceiverIds(message.receiverIds) ?: emptyList()
            } else {
                listOfNotNull(message.roomId)
            }

            var receivers = wcdb.getContactorsFromAllTable(receiverIds)

            val readInfoList = wcdb.getReadInfoList(message.roomId ?: "").filter { it.readPosition >= message.systemShowTimestamp }

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

                //group message（机密消息与普通消息使用相同的已读逻辑）
                if (message.roomType == 1) {
                    val readUserIds = readInfoList.map { it.uid }.toSet()
                    readList = receivers.filter { readUserIds.contains(it.id) }
                    unreadList = receivers.filter { !readUserIds.contains(it.id) }
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
                        this.layoutManager = LinearLayoutManager(requireContext())
                        itemAnimator = null
                    }
                    messageReadInfoAdapter.submitList(readList)
                }

                if (!unreadList.isNullOrEmpty()) {
                    binding.llUnread.visibility = View.VISIBLE
                    binding.rvUnread.apply {
                        this.adapter = messageUnReadInfoAdapter
                        this.layoutManager = LinearLayoutManager(requireContext())
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
                Util.copyToClipboard(requireContext(), timeStamp.toString())
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

/**
 * Message snapshots for open message-detail pages, by key. Several pages can be open at once
 * (one per home tab, views kept while covered), so each has its own entry; at most
 * [MAX_ENTRIES] are kept (oldest dropped). Bitmaps are never recycled here: a page that still
 * draws one may hold it, and the GC frees it once nothing does. Lost with the process.
 */
object MessageDetailBitmapHolder {
    private const val MAX_ENTRIES = 4
    private val bitmaps = LinkedHashMap<String, Bitmap>()

    /** The key is unique across processes: a page restored after process death finds nothing. */
    @Synchronized
    fun put(bitmap: Bitmap): String {
        val key = "message-detail-${UUID.randomUUID()}"
        bitmaps[key] = bitmap
        while (bitmaps.size > MAX_ENTRIES) bitmaps.remove(bitmaps.keys.first())
        return key
    }

    @Synchronized
    fun get(key: String?): Bitmap? = key?.let { bitmaps[it] }

    @Synchronized
    fun release(key: String?) {
        key?.let { bitmaps.remove(it) }
    }
}

/** Message details as a detail destination (home detail stack, or [MessageDetailActivity]). */
class MessageDetailDestination(
    private val messageId: String,
    private val bitmapKey: String?,
    private val conversationId: String,
) : DetailDestination {
    override fun newFragment(): Fragment = MessageDetailFragment.newInstance(messageId, bitmapKey)
    override fun shellIntent(context: Context): Intent = MessageDetailActivity.intent(context, messageId, bitmapKey)
    override val subjectId: String get() = conversationId
}

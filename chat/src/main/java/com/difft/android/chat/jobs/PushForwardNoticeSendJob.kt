package com.difft.android.chat.jobs

import com.difft.android.PushForwardNoticeSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.util.toProtoEnum
import com.difft.android.websocket.api.util.transformGroupIdFromLocalToServer
import com.google.gson.Gson
import com.google.protobuf.ByteString
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardNoticeData
import org.whispersystems.signalservice.internal.push.conversationId
import org.whispersystems.signalservice.internal.push.forwardNoticeMessage
import java.util.concurrent.TimeUnit

/**
 * Sends a ForwardNoticeMessage after a forward operation completes.
 *
 * "Visible but silent": never carries a notification payload, never counts toward unread.
 * On primary send success, inserts the same notice into the sender device's local DB
 * via [LocalMessageCreator.createForwardNoticeMessage] so the originating device also
 * shows the system message (server does not echo the envelope back to the sender).
 *
 * Follows the minimal style of [PushReadReceiptSendJob]:
 *   - no notification payload
 *   - separate per-target queues (no head-of-line blocking across conversations)
 *   - fixed [Parameters.Builder.setMaxAttempts] of 3 (decorative, not a "must deliver")
 *   - retry only on Sender failure; DB-insert failures are logged + reported to
 *     Crashlytics but NOT rethrown (peer already has the notice; a retry would
 *     produce duplicate envelopes).
 *
 * `sendTs` (= System.currentTimeMillis()) is generated once per onPushSend call and
 * passed to BOTH the Sender (`sendTimestamp = sendTs`) AND the LocalMessageCreator
 * (`timestamp = sendTs`). This three-way alignment keeps the generated `messageId`
 * identical across sender-local DB insert, primary envelope receivers, and
 * SyncMessage receivers on other devices.
 */
class PushForwardNoticeSendJob @AssistedInject constructor(
    @Assisted parameters: Parameters?,
    @Assisted private val target: For,
    @Assisted private val noticeData: ForwardNoticeData,
    private val messageSender: NewSignalServiceMessageSender,
    private val localMessageCreator: LocalMessageCreator,
    private val gson: Gson,
) : PushSendJob(parameters ?: buildParameters(target)) {

    override fun serialize(): Data = Data.Builder()
        .putString(KEY_TARGET_ID, target.id)
        .putBoolean(KEY_TARGET_IS_GROUP, target is For.Group)
        .putString(KEY_NOTICE_DATA_JSON, gson.toJson(noticeData))
        .build()

    override fun getFactoryKey(): String = KEY

    override fun onAdded() {}

    public override suspend fun onPushSend() {
        // §4.11 invariant: sendTs is the SINGLE source of truth for Envelope.timestamp
        // AND the local DB insert's NotifyMessage.timestamp. Never regenerate in the Sender.
        val sendTs = System.currentTimeMillis()

        // Self-carried source conversation (payload.conversation) — receiver resolves
        // notice's owning conversation WITHOUT relying on server-populated
        // envelope.msgExtra.conversationId (that field was custom-notify-specific).
        //
        // Sender fills ONE payload from the SENDER's perspective; same payload is sent
        // to both primary recipient and self-sync (no per-recipient variation):
        //   - Group source          → conversation.groupId (server-format bytes)
        //   - 1v1 source (peer=B)   → conversation.number = B (peer's id from my view)
        //   - Note-to-Self source   → conversation.number = myId (self)
        //
        // Receiver rule:
        //   hasGroupId                         → For.Group(groupId)
        //   envelope.source == myId (self-sync) → For.Account(payload.number)
        //   else (primary, envelope.source is the peer) → For.Account(envelope.source)
        //
        // Same pattern as SyncMessage.MarkAsUnread (proto:668), which also carries
        // ConversationId inline rather than relying on msgExtra.
        val proto = forwardNoticeMessage {
            scene = noticeData.scene.toProtoEnum()
            sourceAuthorIds.addAll(noticeData.sourceAuthorIds)
            messageCount = noticeData.messageCount
            combinedForwardMode = noticeData.combinedForwardMode.toProtoEnum()
            conversation = conversationId {
                when (val src = target) {
                    is For.Group -> groupId = ByteString.copyFrom(src.id.transformGroupIdFromLocalToServer())
                    is For.Account -> number = src.id   // peer uid from sender's view (= myId for NTS)
                }
            }
        }

        // sendSyncToSelf: true only for 1v1-to-other. Sender reuses the SAME Content
        // (payload already identifies source conversation); self-sync gets it via
        // recipient=self and receiver resolves via envelope.source == myId branch.
        //
        // Groups: server fanouts to all members (including my-as-member). No sync.
        // Note-to-Self source: recipient=self already reaches all my devices naturally.
        val sendSyncToSelf = target is For.Account && target.id != globalServices.myId

        L.i {
            "[ForwardNotice][PushForwardNoticeSendJob] send sourceConversation=${target.id}, " +
                "isGroup=${target is For.Group}, scene=${noticeData.scene}, " +
                "count=${noticeData.messageCount}, authors=${noticeData.sourceAuthorIds.size}, " +
                "sendSyncToSelf=$sendSyncToSelf, sendTs=$sendTs, attempt=$runAttempt"
        }

        val result = messageSender.sendForwardNoticeMessage(
            recipient = target,
            room = target,
            message = proto,
            sendSyncToSelf = sendSyncToSelf,
            sendTimestamp = sendTs
        )

        val success = result.success
        if (success != null) {
            // Primary send succeeded → insert local DB record on THIS device.
            // DB failure is isolated — do NOT rethrow. Rethrowing would trigger Job retry,
            // which re-sends the notice to peers (who already have it), producing duplicates.
            try {
                localMessageCreator.createForwardNoticeMessage(
                    operatorId = globalServices.myId,
                    forWhat = target,
                    noticeData = noticeData,
                    systemShowTimestamp = success.systemShowTimestamp.takeIf { it > 0 } ?: sendTs,
                    timestamp = sendTs,
                    sourceDevice = DEFAULT_DEVICE_ID
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Preserve structured concurrency: let cancellation propagate so the
                // enclosing coroutine / BaseJob sees it. Swallowing here would break
                // Job cancellation semantics and also spam Crashlytics with false positives.
                throw ce
            } catch (dbE: Exception) {
                L.e {
                    "[ForwardNotice][PushForwardNoticeSendJob] local DB insert failed, " +
                        "notice already sent to peers: ${dbE.stackTraceToString()}"
                }
                // swallow — peer already has it; retry would dup
            }
        }
        // Sender failure path: `sendForwardNoticeMessage` throws after exhausting internal
        // retries (IOException, NonSuccessfulResponseCodeException, etc.). The thrown
        // exception propagates up naturally — no explicit rethrow here. Job retry is
        // safe because the Sender's "primary success → then sync" pattern guarantees
        // no partial-success leak (if primary fails, no sync was sent).
    }

    override fun onFailure() {
        L.w {
            "[ForwardNotice][PushForwardNoticeSendJob] Job failed — sourceConversation=${target.id}, " +
                "isGroup=${target is For.Group}, scene=${noticeData.scene}, " +
                "attempts=$runAttempt, JobID=$id"
        }
    }

    class Factory : Job.Factory<PushForwardNoticeSendJob> {
        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface FactoryEntryPoint {
            fun pushForwardNoticeSendJobFactory(): PushForwardNoticeSendJobFactory
            val gson: Gson
        }

        override fun create(parameters: Parameters, data: Data): PushForwardNoticeSendJob {
            val entryPoint = EntryPointAccessors.fromApplication(
                ApplicationDependencies.getApplication(),
                FactoryEntryPoint::class.java
            )
            val targetId = data.getString(KEY_TARGET_ID)!!
            val isGroup = data.getBooleanOrDefault(KEY_TARGET_IS_GROUP, false)
            val target: For = if (isGroup) For.Group(targetId) else For.Account(targetId)

            val noticeDataJson = data.getString(KEY_NOTICE_DATA_JSON)!!
            val noticeData = entryPoint.gson.fromJson(
                noticeDataJson,
                ForwardNoticeData::class.java
            )

            return entryPoint.pushForwardNoticeSendJobFactory().create(
                parameters,
                target,
                noticeData
            )
        }
    }

    companion object {
        const val KEY = "PushForwardNoticeSendJob"
        private const val KEY_TARGET_ID = "target_id"
        private const val KEY_TARGET_IS_GROUP = "target_is_group"
        private const val KEY_NOTICE_DATA_JSON = "notice_data_json"

        private fun buildParameters(target: For): Parameters = Parameters.Builder()
            .setQueue("[$KEY::${target.id}]")
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .setMaxAttempts(3)
            .addConstraint(NetworkConstraint.KEY)
            .build()
    }
}

package com.difft.android.chat.jobs

import com.difft.android.PushActivityNoticeSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.util.toProtoTypeDataBuilder
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
import difft.android.messageserialization.model.MessageActivityNoticeData
import org.whispersystems.signalservice.internal.push.conversationId
import java.util.concurrent.TimeUnit











class PushActivityNoticeSendJob @AssistedInject constructor(
    @Assisted parameters: Parameters?,
    @Assisted private val target: For,
    @Assisted private val noticeData: MessageActivityNoticeData,
    private val messageSender: NewSignalServiceMessageSender,
    private val localMessageCreator: LocalMessageCreator,
    private val gson: Gson,
    messagingGate: MessagingGate,
) : PushSendJob(parameters ?: buildParameters(target), messagingGate) {

    override fun serialize(): Data = Data.Builder()
        .putString(KEY_TARGET_ID, target.id)
        .putBoolean(KEY_TARGET_IS_GROUP, target is For.Group)
        .putString(KEY_NOTICE_DATA_JSON, gson.toJson(noticeData))
        .build()

    override fun getFactoryKey(): String = KEY

    override fun onAdded() {}

    public override suspend fun onPushSend() {
        val sendTs = System.currentTimeMillis()

        val proto = noticeData.toProtoTypeDataBuilder().apply {
            conversation = conversationId {
                when (val src = target) {
                    is For.Group -> groupId = ByteString.copyFrom(src.id.transformGroupIdFromLocalToServer())
                    is For.Account -> number = src.id   
                }
            }
        }.build()

        
        
        
        val sendSyncToSelf = target is For.Account && target.id != globalServices.myId

        L.i {
            "[ActivityNotice][PushActivityNoticeSendJob] send sourceConversation=${target.id}, " +
                "isGroup=${target is For.Group}, type=${noticeData.type}, " +
                "count=${noticeData.messageCount}, authors=${noticeData.sourceAuthorIds.size}, " +
                "sendSyncToSelf=$sendSyncToSelf, sendTs=$sendTs, attempt=$runAttempt"
        }

        val admission = admitSend() ?: return
        val result = messageSender.sendActivityNoticeMessage(
            recipient = target,
            room = target,
            message = proto,
            sendSyncToSelf = sendSyncToSelf,
            sendTimestamp = sendTs,
            admission = admission
        )

        val success = result.success
        if (success != null) {
            
            
            
            try {
                localMessageCreator.createActivityNoticeMessage(
                    operatorId = globalServices.myId,
                    forWhat = target,
                    noticeData = noticeData,
                    systemShowTimestamp = success.systemShowTimestamp.takeIf { it > 0 } ?: sendTs,
                    timestamp = sendTs,
                    sourceDevice = DEFAULT_DEVICE_ID
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (dbE: Exception) {
                L.e {
                    "[ActivityNotice][PushActivityNoticeSendJob] local DB insert failed, " +
                        "notice already sent to peers: ${dbE.stackTraceToString()}"
                }
            }
        }
    }

    override fun onFailure() {
        L.w {
            "[ActivityNotice][PushActivityNoticeSendJob] Job failed — sourceConversation=${target.id}, " +
                "isGroup=${target is For.Group}, type=${noticeData.type}, " +
                "attempts=$runAttempt, JobID=$id"
        }
    }

    class Factory : Job.Factory<PushActivityNoticeSendJob> {
        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface FactoryEntryPoint {
            fun pushActivityNoticeSendJobFactory(): PushActivityNoticeSendJobFactory
            val gson: Gson
        }

        override fun create(parameters: Parameters, data: Data): PushActivityNoticeSendJob {
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
                MessageActivityNoticeData::class.java
            )

            return entryPoint.pushActivityNoticeSendJobFactory().create(
                parameters,
                target,
                noticeData
            )
        }
    }

    companion object {
        const val KEY = "PushActivityNoticeSendJob"
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

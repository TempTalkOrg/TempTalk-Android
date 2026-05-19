package com.difft.android.chat.jobs

import com.difft.android.PushGroupKeySendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.util.transformGroupIdFromLocalToServer
import com.google.protobuf.ByteString
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import org.whispersystems.signalservice.internal.push.groupKeyMessage
import java.util.concurrent.TimeUnit

/**
 * Distributes R_group to group members via the Job system, mirroring the reliability
 * guarantees of [PushTextSendJob]: network constraint, retries on IOException, and
 * persistent queue across app restarts.
 *
 * Two shapes:
 *  - Broadcast: [isBroadcast]=true, [recipientId]=gid — sends to For.Group(gid).
 *  - Targeted: [isBroadcast]=false, [recipientId]=memberUid — sends 1v1 to For.Account(memberUid).
 *
 * The R_group bytes are looked up from [GroupCryptoRepo] at send time (not serialized
 * into Data) so that the repo stays the single source of truth.
 */
class PushGroupKeySendJob @AssistedInject constructor(
    @Assisted parameters: Parameters?,
    @Assisted("gid") private val gid: String,
    @Assisted("recipientId") private val recipientId: String,
    @Assisted("isBroadcast") private val isBroadcast: Boolean,
    private val messageSender: NewSignalServiceMessageSender,
    private val groupCryptoRepo: GroupCryptoRepo,
) : PushSendJob(parameters ?: buildParameters()) {

    private val recipient: For
        get() = if (isBroadcast) For.Group(gid) else For.Account(recipientId)

    override fun serialize(): Data = Data.Builder()
        .putString(KEY_GID, gid)
        .putString(KEY_RECIPIENT_ID, recipientId)
        .putBoolean(KEY_IS_BROADCAST, isBroadcast)
        .build()

    override fun getFactoryKey(): String = KEY

    override fun onAdded() {}

    public override suspend fun onPushSend() {
        val rGroupBytes = groupCryptoRepo.getRGroupBytes(gid) ?: run {
            L.w { "[GE][PushGroupKeySendJob] No R_group for $gid, drop" }
            return
        }

        val target = recipient

        val message = groupKeyMessage {
            groupId = ByteString.copyFrom(gid.transformGroupIdFromLocalToServer())
            groupRootKey = ByteString.copyFrom(rGroupBytes)
        }

        L.i {
            "[GE][PushGroupKeySendJob] Sending group key: gid=$gid, recipient=$recipientId," +
                " broadcast=$isBroadcast, attempt=$runAttempt"
        }
        messageSender.sendGroupKeyMessage(target, target, message)
    }

    override fun onFailure() {
        L.w {
            "[GE][PushGroupKeySendJob] Job failed - gid=$gid, recipient=$recipientId," +
                " broadcast=$isBroadcast, attempts=$runAttempt"
        }
    }

    class Factory : Job.Factory<PushGroupKeySendJob> {
        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface FactoryEntryPoint {
            fun pushGroupKeySendJobFactory(): PushGroupKeySendJobFactory
        }

        override fun create(parameters: Parameters, data: Data): PushGroupKeySendJob {
            return EntryPointAccessors.fromApplication(
                ApplicationDependencies.getApplication(),
                FactoryEntryPoint::class.java
            ).pushGroupKeySendJobFactory().create(
                parameters,
                data.getString(KEY_GID)!!,
                data.getString(KEY_RECIPIENT_ID)!!,
                data.getBooleanOrDefault(KEY_IS_BROADCAST, false)
            )
        }
    }

    companion object {
        const val KEY = "PushGroupKeySendJob"
        private const val KEY_GID = "gid"
        private const val KEY_RECIPIENT_ID = "recipient_id"
        private const val KEY_IS_BROADCAST = "is_broadcast"

        private fun buildParameters(): Parameters = Parameters.Builder()
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .setMaxAttempts(3)
            .addConstraint(NetworkConstraint.KEY)
            .build()
    }
}

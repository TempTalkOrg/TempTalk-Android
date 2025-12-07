package com.difft.android.websocket.api.util

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallEncryptResult
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.CipherMessage
import com.difft.android.base.call.EMPTY_CALL_ENCRYPT_RESULT
import com.difft.android.base.call.EncInfo
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import difft.android.messageserialization.For
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteStringUtf8
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import com.difft.android.websocket.api.ConversationManager
import com.difft.android.websocket.api.messages.PublicKeyInfo
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_CURRENT_VERSION
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_MINIMUM_SUPPORTED_VERSION
import org.whispersystems.signalservice.internal.push.CallMessageKt.calling
import org.whispersystems.signalservice.internal.push.CallMessageKt.cancel
import org.whispersystems.signalservice.internal.push.CallMessageKt.hangup
import org.whispersystems.signalservice.internal.push.CallMessageKt.joined
import org.whispersystems.signalservice.internal.push.CallMessageKt.reject
import org.whispersystems.signalservice.internal.push.callMessage
import org.whispersystems.signalservice.internal.push.conversationId
import org.whispersystems.signalservice.internal.push.encryptContent
import com.difft.android.websocket.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class CallMessageCreator @Inject constructor(
    @Named("message_sender_max_envelope_size")
    private val maxEnvelopeSize: Long,
    private val messageEncryptor: INewMessageContentEncryptor,
    private val conversationManager: ConversationManager,
) {
    private val contentCreator: SignalServiceContentCreator = SignalServiceContentCreator(maxEnvelopeSize)

    private val myUid: String by lazy {
        globalServices.myId
    }

    fun createCallMessage(
        forWhat: For?,
        callType: CallType,
        callRole: CallRole?,
        callActionType: CallActionType?,
        conversationId: String?, //临时会议时为null
        members: List<String>?, //临时会议时的成员
        roomId: List<String>?, //reject场景下可能会同时reject多个
        roomName: String?,
        caller: String,
        mKey: ByteArray?,
        callUidList: List<String> = emptyList(),
        createCallMsg: Boolean = false,
        createdAt: Long = 0L,
    ): Single<CallEncryptResult> = Single.fromCallable {
        var publicKeyInfos: List<PublicKeyInfo>? = null
        if ((callType.isGroup() || callType.isOneOnOne())) {
            if (callActionType?.isJoined() == true && forWhat != null) {
                conversationManager.updatePublicKeyInfoData(forWhat)
                publicKeyInfos = conversationManager.getPublicKeyInfos(listOf(forWhat.id))
            } else if (callActionType?.isHangUp() == true && forWhat != null) {
                conversationManager.updatePublicKeyInfoData(forWhat)
                publicKeyInfos =
                    if (callType.isGroup()) {
                        // 当前群成员的PublicKeyInfo和当前参会人的PublicKeyInfo取合集
                        var groupCallUserPublicKeyInfos = conversationManager.getPublicKeyInfos(forWhat)
                        val callUserPublicKeyInfos = conversationManager.getPublicKeyInfos(callUidList)
                        callUserPublicKeyInfos?.let {
                            groupCallUserPublicKeyInfos = (groupCallUserPublicKeyInfos + it).distinctBy { publicKeyInfo ->
                                publicKeyInfo.uid
                            }
                        }
                        groupCallUserPublicKeyInfos
                    } else{
                        conversationManager.getPublicKeyInfos(listOf(forWhat.id))
                    }
            }
            else if (callActionType?.isInvite() == true && members?.isNotEmpty() == true) {
                publicKeyInfos = conversationManager.getPublicKeyInfos(members + listOf(myUid))
            }
            else {
                if (forWhat != null) {
                    conversationManager.updatePublicKeyInfoData(forWhat)
                    publicKeyInfos = conversationManager.getPublicKeyInfos(forWhat)
                }
            }
        } else if (callType.isInstant()) {
            publicKeyInfos = if(!members.isNullOrEmpty() || callUidList.isNotEmpty()){
                if(callActionType?.isHangUp() == true) conversationManager.getPublicKeyInfos(callUidList) else conversationManager.getPublicKeyInfos(members?.let { members + listOf(myUid) } )
            }else {
                forWhat?.let {
                    conversationManager.updatePublicKeyInfoData(forWhat)
                    conversationManager.getPublicKeyInfos(forWhat)
                }
            }
        }

        if (publicKeyInfos.isNullOrEmpty()) {
            L.w { "[Call] publicKeyInfos is null" }
            return@fromCallable EMPTY_CALL_ENCRYPT_RESULT
        }

        // Filter out PublicKeyInfo with empty identityKey to prevent rust encryption exception
        publicKeyInfos = publicKeyInfos.filter { info ->
            val isValid = info.identityKey.isNotBlank()
            if (!isValid) {
                L.w { "[Call] Filtering out PublicKeyInfo with empty identityKey for uid: ${info.uid}" }
            }
            isValid
        }

        if (publicKeyInfos.isEmpty()) {
            L.e { "[Call] No valid public key info available after filtering (all identityKeys were empty)" }
            return@fromCallable EMPTY_CALL_ENCRYPT_RESULT
        }

        val publishKeys: Map<String, String> = publicKeyInfos.associate { it.uid to it.identityKey }
        val encryptCallKeyResult = messageEncryptor.encryptCallKey(publishKeys, mKey)
        val encInfos = encryptCallKeyResult.eMKeys?.map {
            EncInfo(Base64.encodeBytes(it.value), it.key)
        }

        if (encInfos.isNullOrEmpty()) {
            L.w { "[Call] call encInfos is null" }
            return@fromCallable EMPTY_CALL_ENCRYPT_RESULT
        }

        var cipherMessages: MutableList<CipherMessage> = publicKeyInfos.map { publicKeyInfo ->
            val emkBytes = encryptCallKeyResult.eMKeys[publicKeyInfo.uid]
            val emk = ByteString.copyFrom(emkBytes)
            val callMessage = callMessage {

                when (callActionType) {
                    CallActionType.JOINED -> {
                        roomId?.firstOrNull()?.let {
                            this.joined = joined { this.roomId = it }
                        }
                    }

                    CallActionType.CANCEL -> {
                        roomId?.firstOrNull()?.let {
                            this.cancel = cancel { this.roomId = it }
                        }
                    }

                    CallActionType.REJECT -> {
                        roomId?.forEach {
                            this.reject = reject { this.roomId = it }
                        }
                    }

                    CallActionType.HANGUP -> {
                        roomId?.firstOrNull()?.let {
                            if (myUid != publicKeyInfo.uid) {
                                this.hangup = hangup { this.roomId = it }
                            }
                        }
                    }

                    CallActionType.INVITE -> {
                        roomId?.firstOrNull()?.let {
                            this.calling = calling {
                                conversationId?.let {
                                    this.conversationId = conversationId {
                                        val isGroundId = !conversationId.startsWith("+")
                                        L.d { "[Call][createCallMessage] call caller isGroundId:$isGroundId" }
                                        this.groupId = conversationId.toByteStringUtf8()
                                    }
                                }
                                roomId.firstOrNull()?.let { this.roomId = it }
                                roomName?.let { this.roomName = it }
                                this.caller = caller
                                this.emk = emk
                                this.publicKey = ByteString.copyFrom(encryptCallKeyResult.eKey)
                                this.createCallMsg = createCallMsg
                                this.controlType = CallActionType.INVITE.type
                                this.timestamp = createdAt
                                members?.let {
                                    this.callees.addAll(members)
                                }
                            }
                        }
                    }

                    CallActionType.START -> {
                        when (callType) {
                            CallType.ONE_ON_ONE -> {
                                this.calling = calling {
                                    conversationId?.let {
                                        this.conversationId = conversationId {
                                            this.number = if(publicKeyInfo.uid == myUid) it else publicKeyInfo.uid
                                        }
                                    }
                                    roomId?.firstOrNull()?.let { this.roomId = it }
                                    roomName?.let { this.roomName = it }
                                    this.caller = caller
                                    this.emk = emk
                                    this.publicKey = ByteString.copyFrom(encryptCallKeyResult.eKey)
                                    this.createCallMsg = createCallMsg
                                    this.controlType = CallActionType.START.type
                                    this.timestamp = createdAt
                                }
                            }
                            else -> {
                                this.calling = calling {
                                    conversationId?.let {
                                        this.conversationId = conversationId {
                                            val isGroundId = !conversationId.startsWith("+")
                                            L.d { "[Call][createCallMessage] call caller isGroundId:$isGroundId" }
                                            this.groupId = conversationId.toByteStringUtf8()
                                        }
                                    }
                                    roomId?.firstOrNull()?.let { this.roomId = it }
                                    roomName?.let { this.roomName = it }
                                    this.caller = caller
                                    this.emk = emk
                                    this.publicKey = ByteString.copyFrom(encryptCallKeyResult.eKey)
                                    this.createCallMsg = createCallMsg
                                    this.controlType = CallActionType.START.type
                                    this.timestamp = createdAt
                                }
                            }
                        }
                    }

                    else -> { L.w { "[Call][createCallMessage] unknown callActionType:$callActionType" } }
                }
            }

            val content = contentCreator.createFrom(callMessage)
            val encryptedMessage = messageEncryptor.encryptOneToOneMessage(
                content.toByteArray(),
                publicKeyInfo.identityKey
            )

            val encryptedMessageContent = encryptContent {
                cipherText = ByteString.copyFrom(encryptedMessage.cipherText)
                eKey = ByteString.copyFrom(encryptedMessage.eKey)
                identityKey = ByteString.copyFrom(encryptedMessage.identityKey)
                signedEKey = ByteString.copyFrom(encryptedMessage.signedEKey)
            }
            val encryptContentString = Base64.encodeBytes(
                byteArrayOf(
                    intsToByteHigh(
                        MESSAGE_CURRENT_VERSION,
                        MESSAGE_MINIMUM_SUPPORTED_VERSION
                    )
                ) + encryptedMessageContent.toByteArray()
            )
            CipherMessage(encryptContentString, publicKeyInfo.registrationId, publicKeyInfo.uid)
        }.toMutableList()

        if(callActionType == CallActionType.INVITE){
            cipherMessages = cipherMessages.distinctBy { it.uid }.toMutableList()
        }

        if (!createCallMsg && callActionType == CallActionType.START && callRole == CallRole.CALLER && callType.isOneOnOne()) {
            cipherMessages.removeIf { it.uid == myUid }
        }

        if (callActionType == CallActionType.REJECT && callRole == CallRole.CALLEE && !callType.isOneOnOne()) {
            cipherMessages.removeIf { it.uid != myUid }
        }

        CallEncryptResult(cipherMessages, encInfos, Base64.encodeBytes(encryptCallKeyResult.eKey))
    }.subscribeOn(Schedulers.io())

    private fun intsToByteHigh(highValue: Int, lowValue: Int): Byte {
        return ((highValue shl 4 or lowValue) and 0xFF).toByte()
    }
}
package com.difft.android.call.session

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallToChatController
import com.difft.android.call.data.RtmMessage
import com.difft.android.call.handler.RtmMessageHandler
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

/**
 * Creates an [RtmMessageHandler] wired with project-specific E2EE encryptor
 * and decryptor closures. Factored out of the ViewModel so the crypto
 * plumbing doesn't inflate VM line count.
 */
internal fun createRtmHandler(
    room: Room,
    scope: CoroutineScope,
    callToChatController: LCallToChatController,
    messageEncryptor: INewMessageContentEncryptor,
    e2eeKeyProvider: () -> ByteArray?,
): RtmMessageHandler = RtmMessageHandler(
    room = room,
    scope = scope,
    encryptor = { plain, timestamp ->
        val localPrivateKey = callToChatController.getLocalPrivateKey() ?: return@RtmMessageHandler null
        try {
            messageEncryptor.encryptRtmMessage(
                plain,
                localPrivateKey,
                e2eeKeyProvider() ?: error("E2EE key not found"),
                timestamp,
            )
        } catch (e: Exception) {
            L.e { "[Call] CallRtmFactory rtm encrypt error = ${e.message}" }
            null
        }
    },
    decryptor = { participant, data ->
        val uid = participant.identity?.value ?: return@RtmMessageHandler null
        val pub = callToChatController.getTheirPublicKey(uid) ?: return@RtmMessageHandler null
        try {
            val plain = messageEncryptor.decryptRtmMessage(
                data,
                pub,
                e2eeKeyProvider() ?: error("E2EE key not found"),
            )
            Json.decodeFromString<RtmMessage>(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            L.e { "[Call] CallRtmFactory rtm decrypt error = ${e.message}" }
            null
        }
    },
)

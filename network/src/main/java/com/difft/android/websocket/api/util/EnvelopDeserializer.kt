package com.difft.android.websocket.api.util

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import org.signal.libsignal.protocol.InvalidVersionException
import com.difft.android.websocket.api.util.PipeDecryptTool.getCipherKey
import com.difft.android.websocket.api.util.PipeDecryptTool.getMacKey
import com.difft.android.websocket.api.util.PipeDecryptTool.getPlaintext
import com.difft.android.websocket.api.util.PipeDecryptTool.verifyMac
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import java.io.IOException

object EnvelopDeserializer {
    private const val VERSION_LENGTH: Int = 1
    private const val SUPPORTED_VERSION: Int = 1

    private const val VERSION_OFFSET: Int = 0

    fun deserializeFrom(byteArray: ByteArray): Envelope? {
        return parseSignalServiceEnvelop(byteArray)
    }

    private fun parseSignalServiceEnvelop(body: ByteArray): Envelope? {
        val envelope: Envelope? = try {
            val signalingKey = globalServices.userManager.getUserData()?.signalingKey ?: ""
            
            val input = body
            if (input.size < VERSION_LENGTH || input[VERSION_OFFSET].toInt() != SUPPORTED_VERSION) {
                throw InvalidVersionException("Unsupported version!");
            }
            val cipherKey = getCipherKey(signalingKey)
            val macKey = getMacKey(signalingKey)

            verifyMac(input, macKey)

            Envelope.parseFrom(getPlaintext(input, cipherKey)).also {
                L.d { "[EnvelopDeserializer] parse envelope success:${it.timestamp}" }
            }
        } catch (e: InvalidVersionException) {
            L.w(e) { "[EnvelopDeserializer] parse envelope exception:" }
            null
        } catch (e: IOException) {
            L.w(e) { "[EnvelopDeserializer] parse envelope exception:" }
            null
        }
        return envelope
    }


}
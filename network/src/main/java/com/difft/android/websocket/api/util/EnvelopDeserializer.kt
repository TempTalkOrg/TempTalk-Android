package com.difft.android.websocket.api.util

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil.getSignalingKey
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
            val signalingKey = getSignalingKey()
            // 该过程包含对 envelop 数据通道加密的解密过程，不包含端上加密的解密过程
            val input = body
            if (input.size < VERSION_LENGTH || input[VERSION_OFFSET].toInt() != SUPPORTED_VERSION) {
                throw InvalidVersionException("Unsupported version!");
            }
            val cipherKey = getCipherKey(signalingKey)
            val macKey = getMacKey(signalingKey)

            verifyMac(input, macKey)

            Envelope.parseFrom(getPlaintext(input, cipherKey)).also {
                L.i { "[EnvelopDeserializer] parse envelope success:${it.timestamp}" }
            }
        } catch (e: InvalidVersionException) {
            // 解析 proto 数据可能出现异常，可以在这里处理
            L.w(e) { "[EnvelopDeserializer] parse envelope exception:" }
            null
        } catch (e: IOException) {
            L.w(e) { "[EnvelopDeserializer] parse envelope exception:" }
            null
        }
        return envelope
    }


}
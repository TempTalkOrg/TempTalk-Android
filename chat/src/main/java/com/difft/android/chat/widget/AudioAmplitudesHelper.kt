package com.difft.android.chat.widget

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.chat.message.TextChatMessage
import com.tencent.wcdb.base.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.difft.app.database.models.DBAttachmentModel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object AudioAmplitudesHelper {
    private val processingMessages = ConcurrentHashMap<String, Job>()
    private val _amplitudeExtractionComplete = MutableSharedFlow<TextChatMessage>()
    val amplitudeExtractionComplete: SharedFlow<TextChatMessage> = _amplitudeExtractionComplete

    fun extractAmplitudesFromAacFile(filePath: String, message: TextChatMessage) {
        val attachmentId = message.attachment?.id ?: return
        if (processingMessages.containsKey(attachmentId)) {
            L.d { "[AudioAmplitudesHelper] Message $attachmentId is already being processed" }
            return
        }

        val job = appScope.launch(Dispatchers.IO) {
            try {
                AudioMessageManager.decryptIfNeeded(filePath, message)

                if (!File(filePath).exists()) {
                    L.e { "[AudioAmplitudesHelper] File does not exist: $filePath" }
                    return@launch
                }

                val extractor = MediaExtractor()
                extractor.setDataSource(filePath)

                // 获取音频轨道
                val trackIndex = getAudioTrackIndex(extractor)
                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val type = format.getString(MediaFormat.KEY_MIME) ?: return@launch

                // 获取音频时长（单位：微秒）
                val duration = format.getLong(MediaFormat.KEY_DURATION)
                // 限制只处理前30秒的音频（30秒 = 30,000,000微秒）
                val maxProcessDuration = 30_000_000L
                val processDuration = minOf(duration, maxProcessDuration)

                // 使用 MediaCodec 解码音频
                val codec = MediaCodec.createDecoderByType(type)
                codec.configure(format, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                val rawAmplitudes = mutableListOf<Float>()
                val targetSampleCount = 100 // 采样数量

                // 从解码器获取解码后的 PCM 数据
                while (isActive) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return@launch
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            break  // 音频解码完成
                        }

                        // 检查是否超过30秒
                        val sampleTime = extractor.sampleTime
                        if (sampleTime > processDuration) {
                            break
                        }

                        codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                        extractor.advance()
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        val audioData = ByteArray(bufferInfo.size)
                        outputBuffer?.get(audioData)

                        val pcmData = ShortArray(audioData.size / 2)
                        for (i in pcmData.indices) {
                            pcmData[i] = ((audioData[i * 2 + 1].toInt() shl 8) or (audioData[i * 2].toInt() and 0xFF)).toShort()
                        }

                        val amplitude = calculateAmplitude(pcmData)
                        rawAmplitudes.add(amplitude)

                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                codec.stop()
                codec.release()
                extractor.release()

                // 对原始振幅数据进行采样，确保均匀分布
                val sampledAmplitudes = if (rawAmplitudes.size > targetSampleCount) {
                    val step = rawAmplitudes.size.toFloat() / targetSampleCount
                    (0 until targetSampleCount).map { i ->
                        val index = (i * step).toInt().coerceAtMost(rawAmplitudes.size - 1)
                        rawAmplitudes[index]
                    }
                } else {
                    rawAmplitudes
                }

                // 将振幅值转换为整数，但保持Float类型
                val integerAmplitudes = sampledAmplitudes.map { amplitude ->
                    amplitude.toInt().toFloat()
                }

                wcdb.attachment.updateRow(
                    arrayOf(Value(duration / 1000), Value(globalServices.gson.toJson(integerAmplitudes))),
                    arrayOf(DBAttachmentModel.totalTime, DBAttachmentModel.amplitudes),
                    DBAttachmentModel.id.eq(attachmentId)
                )

                AudioMessageManager.deleteCurrentFile(message)

                message.attachment?.amplitudes = integerAmplitudes
                message.attachment?.totalTime = duration / 1000

                // Emit completion event
                _amplitudeExtractionComplete.emit(message)

                L.i { "[AudioAmplitudesHelper] extractAmplitudesFromAacFile success: ${message.id} ${integerAmplitudes}  ${duration / 1000} ${attachmentId}" }
            } catch (e: Exception) {
                e.printStackTrace()
                L.e { "[AudioAmplitudesHelper] extractAmplitudesFromAacFile error:" + e.stackTraceToString() }
            } finally {
                processingMessages.remove(attachmentId)
            }
        }

        processingMessages[attachmentId] = job
    }

    fun release() {
        processingMessages.forEach { (_, job) ->
            job.cancel()
        }
        processingMessages.clear()
    }

    private fun getAudioTrackIndex(extractor: MediaExtractor): Int {
        val trackCount = extractor.trackCount
        for (i in 0 until trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        throw IllegalArgumentException("No audio track found.")
    }

    private fun calculateAmplitude(pcmData: ShortArray): Float {
        var sum = 0f
        for (sample in pcmData) {
            sum += abs(sample.toFloat())
        }
        return sum / pcmData.size
    }
}
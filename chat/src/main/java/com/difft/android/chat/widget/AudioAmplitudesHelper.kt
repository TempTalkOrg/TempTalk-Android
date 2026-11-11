package com.difft.android.chat.widget

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.chat.message.TextChatMessage
import com.tencent.wcdb.base.Value
import kotlinx.coroutines.CoroutineStart
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
        
        
        
        val localId = message.attachment?.localId?.takeIf { it.isNotEmpty() } ?: return
        if (processingMessages.containsKey(localId)) {
            L.d { "[AudioAmplitudesHelper] Attachment $localId is already being processed" }
            return
        }

        val job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val source = WaveformSource.open(
                    filePath,
                    message.attachment?.key,
                    File(application.cacheDir, WaveformSource.TEMP_DIR_NAME)
                )
                if (source == null) {
                    L.e { "[AudioAmplitudesHelper] file does not exist messageId=${message.id}" }
                    return@launch
                }

                val (duration, integerAmplitudes) = WaveformSource.use(source) { file ->
                    decodeAmplitudes(file) { isActive }
                } ?: return@launch

                
                
                
                
                
                val serverId = message.attachment?.id
                val rowCondition = if (serverId.isNullOrEmpty()) {
                    DBAttachmentModel.localId.eq(localId)
                } else {
                    DBAttachmentModel.localId.eq(localId).or(
                        DBAttachmentModel.localId.isNull().or(DBAttachmentModel.localId.eq(""))
                            .and(DBAttachmentModel.id.eq(serverId))
                            .and(DBAttachmentModel.messageId.eq(message.id))
                    )
                }
                wcdb.attachment.updateRow(
                    arrayOf(Value(duration / 1000), Value(globalServices.gson.toJson(integerAmplitudes))),
                    arrayOf(DBAttachmentModel.totalTime, DBAttachmentModel.amplitudes),
                    rowCondition
                )

                message.attachment?.amplitudes = integerAmplitudes
                message.attachment?.totalTime = duration / 1000

                
                _amplitudeExtractionComplete.emit(message)

                L.i { "[AudioAmplitudesHelper] extractAmplitudesFromAacFile success: ${message.id} ${integerAmplitudes}  ${duration / 1000} ${localId}" }
            } catch (e: Exception) {
                L.e(e) { "[AudioAmplitudesHelper] extractAmplitudesFromAacFile error:" }
            } finally {
                processingMessages.remove(localId)
            }
        }

        startIfUnclaimed(localId, job)
    }

    
    private fun decodeAmplitudes(file: File, isActive: () -> Boolean): Pair<Long, List<Float>>? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        val trackIndex = getAudioTrackIndex(extractor)
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val type = format.getString(MediaFormat.KEY_MIME) ?: return null

        
        val duration = format.getLong(MediaFormat.KEY_DURATION)
        
        val maxProcessDuration = 30_000_000L
        val processDuration = minOf(duration, maxProcessDuration)

        val codec = MediaCodec.createDecoderByType(type)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val rawAmplitudes = mutableListOf<Float>()
        val targetSampleCount = 100

        while (isActive()) {
            val inputIndex = codec.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return null
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    break
                }

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

        
        val sampledAmplitudes = if (rawAmplitudes.size > targetSampleCount) {
            val step = rawAmplitudes.size.toFloat() / targetSampleCount
            (0 until targetSampleCount).map { i ->
                val index = (i * step).toInt().coerceAtMost(rawAmplitudes.size - 1)
                rawAmplitudes[index]
            }
        } else {
            rawAmplitudes
        }

        val integerAmplitudes = sampledAmplitudes.map { amplitude ->
            amplitude.toInt().toFloat()
        }
        return duration to integerAmplitudes
    }

    




    private fun startIfUnclaimed(localId: String, job: Job) {
        if (processingMessages.putIfAbsent(localId, job) != null) {
            job.cancel()
            return
        }
        job.start()
    }

    
    fun clearStaleTemps() {
        val processStartWallMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        val deleted = WaveformSource.clearStale(File(application.cacheDir, WaveformSource.TEMP_DIR_NAME), processStartWallMs)
        if (deleted > 0) L.i { "[AudioAmplitudesHelper] stale waveform temps deleted=$deleted" }
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
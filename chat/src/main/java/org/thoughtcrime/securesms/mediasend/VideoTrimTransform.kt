package org.thoughtcrime.securesms.mediasend

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.FileUtil.getFilePath
import com.luck.picture.lib.entity.LocalMedia
import org.thoughtcrime.securesms.mediasend.v2.videos.VideoTrimData
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.providers.MyBlobProvider
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.video.FileMediaDataSource
import org.thoughtcrime.securesms.video.StreamingTranscoder
import org.thoughtcrime.securesms.video.TranscoderOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class VideoTrimTransform(private val data: VideoTrimData, private val sentMediaQuality: SentMediaQuality) : MediaTransform {

    @RequiresApi(Build.VERSION_CODES.O)
    @WorkerThread
    override fun transform(context: Context, media: LocalMedia): LocalMedia {
        var outputStream: FileOutputStream? = null
        var dataSource: FileMediaDataSource? = null

        try {
            val transformProperties = TransformProperties(false, data.isDurationEdited, data.startTimeUs, data.endTimeUs, sentMediaQuality.code, false)
            val constraints = MediaConstraints.getPushMediaConstraints(SentMediaQuality.fromCode(transformProperties.sentMediaQuality))
            dataSource = FileMediaDataSource(File(media.realPath))
            val options = if (transformProperties.videoTrim) {
                TranscoderOptions(transformProperties.videoTrimStartTimeUs, transformProperties.videoTrimEndTimeUs)
            } else {
                null
            }

            val transcoder = StreamingTranscoder(dataSource, options, constraints.videoTranscodingSettings, constraints.getCompressedVideoMaxSize(context), false)

            if (transcoder.isTranscodeRequired) {
                val uri = MyBlobProvider.getInstance()
                    .forData(File(media.realPath).inputStream())
                    .withMimeType(MediaUtil.VIDEO_MP4)
                    .withFileName(media.fileName)
                    .createForDraftAttachmentAsync(context)
                    .get()

                outputStream = FileOutputStream(uri.path)
                transcoder.transcode({ percent ->
                    L.d { "video transcode percent: $percent" }
                }, outputStream, { false })
                L.i { "video transcode success" }
                media.realPath = uri.path
            } else {
                L.i { "Transcode was not required" }
            }
        } catch (e: Exception) {
            L.w { "video transcode fail: ${e.stackTraceToString()}" }
            throw e
        } finally {
            // Ensure `FileOutputStream` is closed
            try {
                outputStream?.close()
            } catch (e: IOException) {
                L.w { "Failed to close output stream: ${e.stackTraceToString()}" }
            }

            // Ensure `FileMediaDataSource` is closed
            try {
                dataSource?.close()
            } catch (e: IOException) {
                L.w { "Failed to close data source: ${e.stackTraceToString()}" }
            }
        }
        return media
    }
}

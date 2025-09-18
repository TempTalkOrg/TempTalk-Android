package org.thoughtcrime.securesms.mediasend

import android.os.Parcelable
import com.difft.android.base.log.lumberjack.L
import com.fasterxml.jackson.annotation.JsonProperty
import com.luck.picture.lib.entity.LocalMedia
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.thoughtcrime.securesms.mms.SentMediaQuality
import com.difft.android.websocket.internal.util.JsonUtil
import java.io.IOException
import java.util.Optional

object LocalMediaExtensions {
    private val transformPropertiesMap = mutableMapOf<LocalMedia, TransformProperties?>()

    var LocalMedia.transformProperties: TransformProperties?
        get() = transformPropertiesMap[this]
        set(value) {
            transformPropertiesMap[this] = value
        }
}

@Parcelize
data class TransformProperties(
    @JsonProperty("skipTransform")
    @JvmField
    val skipTransform: Boolean = false,

    @JsonProperty("videoTrim")
    @JvmField
    val videoTrim: Boolean = false,

    @JsonProperty("videoTrimStartTimeUs")
    @JvmField
    val videoTrimStartTimeUs: Long = 0,

    @JsonProperty("videoTrimEndTimeUs")
    @JvmField
    val videoTrimEndTimeUs: Long = 0,

    @JsonProperty("sentMediaQuality")
    @JvmField
    val sentMediaQuality: Int = SentMediaQuality.STANDARD.code,

    @JsonProperty("mp4Faststart")
    @JvmField
    val mp4FastStart: Boolean = false
) : Parcelable {
    fun shouldSkipTransform(): Boolean {
        return skipTransform
    }

    @IgnoredOnParcel
    @JsonProperty("videoEdited")
    val videoEdited: Boolean = videoTrim

    fun withSkipTransform(): TransformProperties {
        return this.copy(
            skipTransform = true
        )
    }

    fun withMp4FastStart(): TransformProperties {
        return this.copy(mp4FastStart = true)
    }

    fun serialize(): String {
        return JsonUtil.toJson(this)
    }

    companion object {
        private val DEFAULT_MEDIA_QUALITY = SentMediaQuality.STANDARD.code

        @JvmStatic
        fun empty(): TransformProperties {
            return TransformProperties(
                skipTransform = false,
                videoTrim = false,
                videoTrimStartTimeUs = 0,
                videoTrimEndTimeUs = 0,
                sentMediaQuality = DEFAULT_MEDIA_QUALITY,
                mp4FastStart = false
            )
        }

        fun forSkipTransform(): TransformProperties {
            return TransformProperties(
                skipTransform = true,
                videoTrim = false,
                videoTrimStartTimeUs = 0,
                videoTrimEndTimeUs = 0,
                sentMediaQuality = DEFAULT_MEDIA_QUALITY,
                mp4FastStart = false
            )
        }

        fun forVideoTrim(videoTrimStartTimeUs: Long, videoTrimEndTimeUs: Long): TransformProperties {
            return TransformProperties(
                skipTransform = false,
                videoTrim = true,
                videoTrimStartTimeUs = videoTrimStartTimeUs,
                videoTrimEndTimeUs = videoTrimEndTimeUs,
                sentMediaQuality = DEFAULT_MEDIA_QUALITY,
                mp4FastStart = false
            )
        }

        @JvmStatic
        fun forSentMediaQuality(currentProperties: Optional<TransformProperties>, sentMediaQuality: SentMediaQuality): TransformProperties {
            val existing = currentProperties.orElse(empty())
            return existing.copy(sentMediaQuality = sentMediaQuality.code)
        }

        @JvmStatic
        fun forSentMediaQuality(sentMediaQuality: Int): TransformProperties {
            return TransformProperties(sentMediaQuality = sentMediaQuality)
        }

        @JvmStatic
        fun parse(serialized: String?): TransformProperties {
            return if (serialized == null) {
                empty()
            } else {
                try {
                    JsonUtil.fromJson(serialized, TransformProperties::class.java)
                } catch (e: IOException) {
                    L.w { "Failed to parse TransformProperties!$e" }
                    empty()
                }
            }
        }
    }
}

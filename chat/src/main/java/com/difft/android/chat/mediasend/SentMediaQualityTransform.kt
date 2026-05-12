package com.difft.android.chat.mediasend

import android.content.Context
import androidx.annotation.WorkerThread
import com.luck.picture.lib.entity.LocalMedia
import com.difft.android.chat.mediasend.LocalMediaExtensions.transformProperties
import com.difft.android.chat.mms.SentMediaQuality
import java.util.Optional

/**
 * Add a [SentMediaQuality] value for [TransformProperties] on the
 * transformed media. Safe to use in a pipeline with other transforms.
 */
class SentMediaQualityTransform(private val sentMediaQuality: SentMediaQuality) : MediaTransform {
    @WorkerThread
    override fun transform(context: Context, media: LocalMedia): LocalMedia {
        media.transformProperties = TransformProperties.forSentMediaQuality(Optional.ofNullable(media.transformProperties), sentMediaQuality)
        return media
    }
}

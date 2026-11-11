package com.difft.android.chat.mediasend

import android.content.Context
import androidx.annotation.WorkerThread
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.chat.mediasend.LocalMediaExtensions.transformProperties
import com.difft.android.chat.mms.SentMediaQuality
import java.util.Optional





class SentMediaQualityTransform(private val sentMediaQuality: SentMediaQuality) : MediaTransform {
    @WorkerThread
    override fun transform(context: Context, media: LocalMedia): LocalMedia {
        media.transformProperties = TransformProperties.forSentMediaQuality(Optional.ofNullable(media.transformProperties), sentMediaQuality)
        return media
    }
}

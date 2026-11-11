package com.difft.android.chat.mediasend

import android.content.Context
import androidx.annotation.WorkerThread
import com.difft.android.selector.entity.LocalMedia

interface MediaTransform {

    










    @WorkerThread
    fun transform(context: Context, media: LocalMedia): LocalMedia
}

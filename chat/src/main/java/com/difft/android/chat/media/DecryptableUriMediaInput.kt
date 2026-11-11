package com.difft.android.chat.media

import android.content.Context
import android.net.Uri
import com.difft.android.video.UriMediaInput
import com.difft.android.video.interfaces.MediaInput
import java.io.IOException

object DecryptableUriMediaInput {
    @JvmStatic
    @Throws(IOException::class)
    fun createForUri(context: Context, uri: Uri): MediaInput {
        return UriMediaInput(context, uri)
    }
}

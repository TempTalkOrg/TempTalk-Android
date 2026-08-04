package com.difft.android.chat.media

import android.content.Context
import android.net.Uri
import com.difft.android.video.UriMediaInput
import com.difft.android.video.interfaces.MediaInput
import java.io.IOException

/**
 * A media input source that is decrypted on the fly.
 */
object DecryptableUriMediaInput {
    @JvmStatic
    @Throws(IOException::class)
    fun createForUri(context: Context, uri: Uri): MediaInput {
//        if (BlobProvider.isAuthority(uri)) {
//            return MediaDataSourceMediaInput(BlobProvider.getInstance().getMediaDataSource(context, uri))
//        }
//    return if (PartAuthority.isLocalUri(uri)) {
//      createForAttachmentUri(uri)
//    } else {
//      UriMediaInput(context, uri)
//    }
        return UriMediaInput(context, uri)
    }
//
//  private fun createForAttachmentUri(uri: Uri): MediaInput {
//    val partId = PartUriParser(uri).partId
//    if (!partId.isValid) {
//      throw AssertionError()
//    }
//    val mediaDataSource = attachments.mediaDataSourceFor(partId, true) ?: throw AssertionError()
//    return MediaDataSourceMediaInput(mediaDataSource)
//  }
}

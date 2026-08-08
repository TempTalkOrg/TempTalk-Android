package com.difft.android.selector.basic

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.text.TextUtils

class PictureMediaScannerConnection(
    context: Context,
    private val mPath: String?
) : MediaScannerConnection.MediaScannerConnectionClient {

    private val mMs: MediaScannerConnection =
        MediaScannerConnection(context.applicationContext, this)

    init {
        mMs.connect()
    }

    override fun onMediaScannerConnected() {
        if (!TextUtils.isEmpty(mPath)) {
            mMs.scanFile(mPath, null)
        }
    }

    override fun onScanCompleted(path: String?, uri: Uri?) {
        mMs.disconnect()
    }
}

package com.difft.android.video.videoconverter

class TranscodingException : Exception {
    internal constructor(message: String?) : super(message)
    internal constructor(inner: Throwable?) : super(inner)
    internal constructor(message: String?, inner: Throwable?) : super(message, inner)
}

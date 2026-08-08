package com.difft.android.video.videoconverter.muxer

class MuxingException : RuntimeException {
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
}

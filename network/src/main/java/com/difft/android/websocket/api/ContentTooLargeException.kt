package com.difft.android.websocket.api

class ContentTooLargeException(size: Long) : IllegalStateException("Too large! Size: $size bytes")

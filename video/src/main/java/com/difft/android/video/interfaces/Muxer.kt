/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.difft.android.video.interfaces

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.IOException
import java.nio.ByteBuffer

interface Muxer {

    @Throws(IOException::class)
    fun start()

    @Throws(IOException::class)
    fun stop()

    @Throws(IOException::class)
    fun addTrack(format: MediaFormat): Int

    @Throws(IOException::class)
    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo)

    fun release()

    fun supportsAudioRemux(): Boolean
}

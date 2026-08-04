/*
 * Copyright 2008-2019 JCodecProject
 *
 * Redistribution  and  use  in   source  and   binary   forms,  with  or  without
 * modification, are permitted provided  that the following  conditions  are  met:
 *
 * Redistributions of  source code  must  retain the above  copyright notice, this
 * list of conditions and the following disclaimer. Redistributions in binary form
 * must  reproduce  the above  copyright notice, this  list of conditions  and the
 * following disclaimer in the documentation and/or other  materials provided with
 * the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,  BUT NOT LIMITED TO, THE  IMPLIED
 * WARRANTIES  OF  MERCHANTABILITY  AND  FITNESS  FOR  A  PARTICULAR  PURPOSE  ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY,  OR CONSEQUENTIAL DAMAGES
 * (INCLUDING,  BUT NOT LIMITED TO,  PROCUREMENT OF SUBSTITUTE GOODS  OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS;  OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY  THEORY  OF  LIABILITY,  WHETHER  IN  CONTRACT,  STRICT LIABILITY,  OR TORT
 * (INCLUDING  NEGLIGENCE OR OTHERWISE)  ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * https://github.com/jcodec/jcodec/blob/master/src/main/java/org/jcodec/codecs/h264/H264Utils.java
 *
 * This file has been modified by Signal.
 */
package com.difft.android.video.videoconverter.muxer

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object H264Utils {

    fun getNals(buffer: ByteBuffer): List<ByteBuffer> {
        val nals = ArrayList<ByteBuffer>()
        while (true) {
            val nal = nextNALUnit(buffer) ?: break
            nals.add(nal)
        }
        return nals
    }

    fun nextNALUnit(buf: ByteBuffer): ByteBuffer? {
        skipToNALUnit(buf)
        return gotoNALUnit(buf)
    }

    fun skipToNALUnit(buf: ByteBuffer) {
        if (!buf.hasRemaining())
            return

        var value = -1
        while (buf.hasRemaining()) {
            value = value shl 8
            value = value or (buf.get().toInt() and 0xff)
            if ((value and 0xffffff) == 1) {
                buf.position(buf.position())
                break
            }
        }
    }

    /**
     * Finds next Nth H.264 bitstream NAL unit (0x00000001) and returns the data
     * that preceeds it as a ByteBuffer slice
     *
     *
     * Segment byte order is always little endian
     *
     *
     * TODO: emulation prevention
     */
    fun gotoNALUnit(buf: ByteBuffer): ByteBuffer? {

        if (!buf.hasRemaining())
            return null

        val from = buf.position()
        val result = buf.slice()
        result.order(ByteOrder.BIG_ENDIAN)

        var value = -1
        while (buf.hasRemaining()) {
            value = value shl 8
            value = value or (buf.get().toInt() and 0xff)
            if ((value and 0xffffff) == 1) {
                buf.position(buf.position() - (if (value == 1) 4 else 3))
                result.limit(buf.position() - from)
                break
            }
        }
        return result
    }
}

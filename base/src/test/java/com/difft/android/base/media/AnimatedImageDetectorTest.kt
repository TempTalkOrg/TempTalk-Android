package com.difft.android.base.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedImageDetectorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun animatedAndStaticWebpHeadersAreDistinguished() {
        val animated = temporaryFolder.newFile("animated.webp").apply { writeBytes(webpHeader(animated = true)) }
        val static = temporaryFolder.newFile("static.webp").apply { writeBytes(webpHeader(animated = false)) }

        assertTrue(AnimatedImageDetector.isAnimated(animated, "image/webp"))
        assertFalse(AnimatedImageDetector.isAnimated(static, "image/webp"))
    }

    @Test
    fun contentProviderShortReadsFillTheHeaderAndCloseTheStream() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.difft.android.base.media.test/animated/1")
        var closed = false
        val stream = object : ByteArrayInputStream(webpHeader(animated = true)) {
            private var returnZero = true

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return super.read(buffer, offset, minOf(1, length))
            }

            override fun close() {
                closed = true
                super.close()
            }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, stream)

        assertTrue(AnimatedImageDetector.isAnimated(context, uri, "image/webp"))
        assertTrue(closed)
    }

    @Test
    fun gifMimeIsAnimatedWithoutOpeningTheSource() {
        assertTrue(AnimatedImageDetector.isAnimated(File(temporaryFolder.root, "missing.gif"), "image/gif"))
    }

    @Test
    fun unreadableAndTruncatedSourcesDefaultToStatic() {
        val truncated = temporaryFolder.newFile("short.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertFalse(AnimatedImageDetector.isAnimated(truncated, "image/webp"))
        assertFalse(AnimatedImageDetector.isAnimated(File(temporaryFolder.root, "missing.webp"), "image/webp"))
    }

    
    private fun webpHeader(animated: Boolean): ByteArray = ByteArray(30).apply {
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(this, 0)
        this[4] = 22
        "WEBPVP8X".toByteArray(Charsets.US_ASCII).copyInto(this, 8)
        this[16] = 10
        this[20] = if (animated) 2 else 0
    }
}

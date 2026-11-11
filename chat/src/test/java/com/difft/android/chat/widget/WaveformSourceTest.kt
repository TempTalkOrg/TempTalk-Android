package com.difft.android.chat.widget

import com.difft.android.chat.util.FileEncryptionUtil
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


class WaveformSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val audio = Random(7).nextBytes(10_000)
    private val key = Random(11).nextBytes(64)

    private val attachmentDir by lazy { tmp.newFolder("attachment", "copy1") }
    private val tempDir by lazy { File(tmp.root, "cache/${WaveformSource.TEMP_DIR_NAME}") }
    private val basePath by lazy { File(attachmentDir, "voice.m4a").absolutePath }

    private fun writeCiphertext() {
        val plain = tmp.newFile("source.m4a").apply { writeBytes(audio) }
        FileEncryptionUtil.encryptFile(plain, File("$basePath.encrypt"), key)
    }

    @Test
    fun `ciphertext is decrypted into the internal temp dir, never beside it`() {
        writeCiphertext()

        val source = assertNotNull(WaveformSource.open(basePath, key, tempDir))

        assertTrue(source.isTemp)
        assertEquals(tempDir.canonicalPath, source.file.parentFile!!.canonicalPath)
        assertContentEquals(audio, source.file.readBytes())
        assertFalse(File(basePath).exists(), "no plaintext may appear in the attachment dir")
    }

    @Test
    fun `a decrypted copy left by an older failed extraction is removed`() {
        writeCiphertext()
        File(basePath).writeBytes(audio)

        val source = assertNotNull(WaveformSource.open(basePath, key, tempDir))

        assertTrue(source.isTemp)
        assertFalse(File(basePath).exists())
    }

    @Test
    fun `self-sent voice still uploading is read in place and kept`() {
        
        writeCiphertext()
        File(basePath).writeBytes(audio)

        val source = assertNotNull(WaveformSource.open(basePath, ByteArray(0), tempDir))

        assertFalse(source.isTemp)
        assertEquals(File(basePath), source.file)
        assertTrue(File(basePath).exists())
    }

    @Test
    fun `legacy plaintext without a key is read in place`() {
        File(basePath).writeBytes(audio)

        val source = assertNotNull(WaveformSource.open(basePath, null, tempDir))

        assertFalse(source.isTemp)
        assertTrue(File(basePath).exists())
    }

    @Test
    fun `nothing to read returns null`() {
        assertEquals(null, WaveformSource.open(basePath, key, tempDir))
    }

    @Test
    fun `tampered ciphertext fails without leaving a temp`() {
        writeCiphertext()
        RandomAccessFile(File("$basePath.encrypt"), "rw").use { raf ->
            raf.seek(0)
            val b = raf.read()
            raf.seek(0)
            raf.write(b xor 0x01)
        }

        assertFailsWith<Exception> { WaveformSource.open(basePath, key, tempDir) }

        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
        assertFalse(File(basePath).exists())
    }

    @Test
    fun `a leftover copy is kept when the ciphertext fails its MAC`() {
        writeCiphertext()
        File(basePath).writeBytes(audio)
        RandomAccessFile(File("$basePath.encrypt"), "rw").use { raf ->
            raf.seek(20)
            val b = raf.read()
            raf.seek(20)
            raf.write(b xor 0x01)
        }

        assertFailsWith<Exception> { WaveformSource.open(basePath, key, tempDir) }

        assertTrue(File(basePath).exists(), "the only good copy must survive a damaged ciphertext")
    }

    @Test
    fun `clearStale removes temps older than the cutoff and keeps newer ones`() {
        tempDir.mkdirs()
        val old = File(tempDir, "old.tmp").apply { writeText("x"); setLastModified(1_000_000L) }
        val fresh = File(tempDir, "fresh.tmp").apply { writeText("x"); setLastModified(3_000_000L) }

        val deleted = WaveformSource.clearStale(tempDir, staleBeforeMs = 2_000_000L)

        assertEquals(1, deleted)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `use deletes the decrypted temp even when extraction throws`() {
        writeCiphertext()
        val source = assertNotNull(WaveformSource.open(basePath, key, tempDir))

        assertFailsWith<IllegalStateException> {
            WaveformSource.use(source) { error("extraction failed") }
        }

        assertFalse(source.file.exists())
    }

    @Test
    fun `use never deletes a plaintext read in place`() {
        File(basePath).writeBytes(audio)
        val source = assertNotNull(WaveformSource.open(basePath, null, tempDir))

        WaveformSource.use(source) { }

        assertTrue(File(basePath).exists())
    }
}

package com.difft.android.migration.v2.export

import android.app.Application
import com.difft.android.migration.v2.model.AttachmentOwner
import io.mockk.mockk
import org.difft.app.database.models.AttachmentModel
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue








@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class NeutralExporterForwardAttachmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    
    private fun attachmentDir(ownerKey: String): String =
        File(tmp.root, "attachment/$ownerKey").apply { mkdirs() }.absolutePath + File.separator

    private fun newExporter(): NeutralExporterImpl =
        NeutralExporterImpl(readSource = mockk(relaxed = true)).apply {
            attachmentDir = ::attachmentDir
        }

    




    





    private fun atRestBytes(approxSize: Int, gen: (Int) -> Byte): ByteArray {
        val body = (((approxSize - 48).coerceAtLeast(16) + 15) / 16) * 16
        return ByteArray(48 + body, gen)
    }

    private fun seedFile(ownerKey: String, fileName: String, bytes: ByteArray): File =
        File(attachmentDir(ownerKey) + fileName + ".encrypt").apply { parentFile?.mkdirs(); writeBytes(bytes) }

    private fun attModel(
        id: String?,
        authorityId: Long?,
        fileName: String,
        localId: String? = null,
        contentType: String = "image/jpeg",
        size: Int = 0,
        key: ByteArray = ByteArray(64) { 1 },
    ) = AttachmentModel().apply {
        this.key = key
        this.id = id
        this.authorityId = authorityId
        this.fileName = fileName
        this.localId = localId
        this.contentType = contentType
        this.size = size
    }

    private fun owned(am: AttachmentModel, owner: AttachmentOwner = AttachmentOwner.FORWARD) =
        NeutralProjections.OwnedAttachment(am, owner, ownerAuthor = "u-author", ownerSentAt = 1000L, hostMessageId = "m-host")

    private fun bytesFor(exporter: NeutralExporterImpl, ref: String): ByteArray? =
        exporter.attachmentByteSource().open(ref)?.stream?.use { it.readBytes() }

    

    @Test
    fun `with attachments excluded a normal attachment travels pointer-only even though its file exists`() {
        val exporter = newExporter().apply { includeAttachments = false }
        seedFile("loc-img", "photo.jpg", atRestBytes(300) { it.toByte() })
        val am = attModel(id = "att-img", authorityId = 1L, fileName = "photo.jpg", localId = "loc-img")

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-1")

        assertTrue(neutral.pointerOnly, "the bytes stay behind; the pointer still travels")
        assertEquals(1L, neutral.serverAttachmentId, "the re-download pointer is what makes leaving bytes behind safe")
        assertNull(bytesFor(exporter, "att-img"), "no byte frame is offered for it")
    }

    @Test
    fun `with attachments excluded a voice note still carries its bytes`() {
        val exporter = newExporter().apply { includeAttachments = false }
        val bytes = atRestBytes(300) { (it * 3).toByte() }
        seedFile("loc-voice", "voice.aac", bytes)
        val am = attModel(id = "att-voice", authorityId = 2L, fileName = "voice.aac", localId = "loc-voice", contentType = "audio/aac")
            .apply { flags = 1 }

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-2")

        assertFalse(neutral.pointerOnly, "a voice note is the message itself, not a file attached to one")
        assertBytesEqual(bytes, bytesFor(exporter, "att-voice"))
    }

    @Test
    fun `with attachments excluded an audio FILE without the voice flag is left behind like any other`() {
        val exporter = newExporter().apply { includeAttachments = false }
        seedFile("loc-audio", "song.mp3", atRestBytes(300) { it.toByte() })
        val am = attModel(id = "att-audio", authorityId = 3L, fileName = "song.mp3", localId = "loc-audio", contentType = "audio/mpeg")

        assertTrue(exporter.projectNormalAttachment(am, messageId = "m-3").pointerOnly)
    }

    

    @Test
    fun `an at-rest file without a recorded key travels pointer-only`() {
        val exporter = newExporter()
        seedFile("loc-failed", "voice.aac", atRestBytes(300) { it.toByte() })
        val am = attModel(
            id = "att-failed", authorityId = 0L, fileName = "voice.aac", localId = "loc-failed",
            contentType = "audio/aac", key = ByteArray(0),
        ).apply { flags = 1 }

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-failed")

        assertTrue(neutral.pointerOnly, "a file the destination could never decrypt must not travel")
        assertNull(bytesFor(exporter, "att-failed"))
    }

    @Test
    fun `an at-rest file whose recorded key is too short to read travels pointer-only`() {
        val exporter = newExporter()
        seedFile("loc-short", "voice.aac", atRestBytes(300) { it.toByte() })
        val am = attModel(
            id = "att-short", authorityId = 7L, fileName = "voice.aac", localId = "loc-short",
            contentType = "audio/aac", key = ByteArray(32) { 1 },
        ).apply { flags = 1 }

        assertTrue(exporter.projectNormalAttachment(am, messageId = "m-short").pointerOnly)
    }

    @Test
    fun `the same file with its key recorded carries its bytes`() {
        val exporter = newExporter()
        val bytes = atRestBytes(300) { (it * 5).toByte() }
        seedFile("loc-sent", "voice.aac", bytes)
        val am = attModel(id = "att-sent", authorityId = 9L, fileName = "voice.aac", localId = "loc-sent", contentType = "audio/aac")
            .apply { flags = 1 }

        assertFalse(exporter.projectNormalAttachment(am, messageId = "m-sent").pointerOnly)
        assertBytesEqual(bytes, bytesFor(exporter, "att-sent"))
    }

    

    @Test
    fun `single-forward attachment carries bytes resolved under its authorityId`() {
        val exporter = newExporter()
        val bytes = atRestBytes(321) { (it * 7 + 1).toByte() }
        
        
        seedFile("700100", "fwd.jpg", bytes)
        val am = attModel(id = "att-fwd", authorityId = 700100L, fileName = "fwd.jpg")

        val neutral = exporter.projectOwnedAttachment(owned(am))
        
        assertEquals("m-host", neutral.messageRef)

        assertFalse(neutral.pointerOnly, "forward attachment with a real file must carry bytes")
        assertEquals("att-fwd", neutral.stableId, "stableId == am.id when id is present (byte-frame key)")
        assertEquals(700100L, neutral.serverAttachmentId, "authorityId travels as serverAttachmentId")
        assertBytesEqual(bytes, bytesFor(exporter, "att-fwd"))
    }

    

    @Test
    fun `merged-forward children each carry bytes under their own authorityId`() {
        val exporter = newExporter()
        val bytesA = atRestBytes(200) { (it * 3 + 5).toByte() }
        val bytesB = atRestBytes(260) { (it * 5 + 9).toByte() }
        seedFile("800200", "a.jpg", bytesA)
        seedFile("800201", "b.jpg", bytesB)
        val childA = attModel(id = "att-a", authorityId = 800200L, fileName = "a.jpg")
        val childB = attModel(id = "att-b", authorityId = 800201L, fileName = "b.jpg")

        val nA = exporter.projectOwnedAttachment(owned(childA))
        val nB = exporter.projectOwnedAttachment(owned(childB))

        assertEquals("att-a", nA.stableId)
        assertEquals("att-b", nB.stableId)
        
        assertBytesEqual(bytesA, bytesFor(exporter, "att-a"))
        assertBytesEqual(bytesB, bytesFor(exporter, "att-b"))
    }

    

    @Test
    fun `quote attachments are not collected as owned attachment records`() {
        
        
        val msg = org.difft.app.database.models.MessageModel().apply {
            id = "m-quote-host"
            quoteDatabaseId = 77L
        }
        val owned = com.difft.android.migration.v2.export.NeutralProjections
            .subStructureAttachments(mockk(relaxed = true), msg)
        assertTrue(owned.isEmpty(), "quote-owned attachment rows must not be collected (C12 meta-only)")
    }

    

    @Test
    fun `null-id forward attachment carries bytes with stableId equal to authorityId`() {
        val exporter = newExporter()
        val bytes = atRestBytes(444) { (it * 13 + 7).toByte() }
        seedFile("123456", "noid.jpg", bytes)
        
        val am = attModel(id = null, authorityId = 123456L, fileName = "noid.jpg")

        val neutral = exporter.projectOwnedAttachment(owned(am))

        assertFalse(neutral.pointerOnly, "null-id forward row with a real file must still carry bytes")
        assertEquals("123456", neutral.stableId, "stableId falls back to authorityId.toString()")
        
        assertEquals(neutral.stableId, NeutralProjections.attachmentMeta(am).stableId, "meta stableId == record stableId")
        
        assertBytesEqual(bytes, bytesFor(exporter, "123456"))
    }

    

    @Test
    fun `attachment with no file at either address is pointer-only`() {
        val exporter = newExporter()
        
        
        File(tmp.root, "unrelated.jpg").apply { writeBytes(ByteArray(10)) }
        val am = attModel(id = "att-missing", authorityId = 999000L, fileName = "missing.jpg", localId = "lid-missing")

        val neutral = exporter.projectOwnedAttachment(owned(am))

        assertTrue(neutral.pointerOnly, "no file at any known address → pointer-only (no byte frame)")
        assertNull(bytesFor(exporter, "att-missing"), "byte-source has no plan for a pointer-only ref")
    }

    

    @Test
    fun `attachment resolves via its own localId address`() {
        val exporter = newExporter()
        val bytes = atRestBytes(512) { (it * 17 + 3).toByte() }
        
        seedFile("lid-normal", "photo.jpg", bytes)
        val am = attModel(id = "att-normal", authorityId = 555L, fileName = "photo.jpg", localId = "lid-normal")

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-normal")

        assertFalse(neutral.pointerOnly, "attachment addressed by localId carries bytes")
        assertEquals("att-normal", neutral.stableId, "normal stableId stays am.id (NOT authorityId)")
        assertBytesEqual(bytes, bytesFor(exporter, "att-normal"))
    }

    

    @Test
    fun `localId address takes precedence over the legacy one`() {
        val exporter = newExporter()
        val current = ByteArray(64) { 1 }
        val stale = ByteArray(64) { 9 }
        seedFile("lid-both", "dup.jpg", current)   
        seedFile("m-both", "dup.jpg", stale)       
        val am = attModel(id = "att-both", authorityId = 42L, fileName = "dup.jpg", localId = "lid-both")

        exporter.projectNormalAttachment(am, messageId = "m-both")

        assertBytesEqual(current, bytesFor(exporter, "att-both"))
    }

    

    @Test
    fun `normal attachment falls back to the legacy messageId address`() {
        val exporter = newExporter()
        val bytes = atRestBytes(512) { (it * 17 + 3).toByte() }
        
        seedFile("m-normal", "photo.jpg", bytes)
        val am = attModel(id = "att-normal", authorityId = 555L, fileName = "photo.jpg")

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-normal")

        assertFalse(neutral.pointerOnly, "legacy-addressed attachment still carries bytes")
        assertBytesEqual(bytes, bytesFor(exporter, "att-normal"))
    }

    

    
    

    @Test
    fun `quote-preview row with null id and null authorityId is emitted pointer-only NOT dropped`() {
        val exporter = newExporter()
        
        val am = attModel(id = null, authorityId = null, fileName = "qpreview.jpg").apply {
            quoteModelDatabaseId = 4242L
        }

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-host")

        
        assertTrue(neutral.pointerOnly, "no local file → pointer-only record (no byte frame), but still EMITTED")
    }

    @Test
    fun `quote-preview row with empty id and zero authorityId is emitted pointer-only NOT dropped`() {
        val exporter = newExporter()
        val am = attModel(id = "", authorityId = 0L, fileName = "qpreview.jpg").apply {
            quoteModelDatabaseId = 4243L
        }

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-host")

        assertTrue(neutral.pointerOnly, "empty id + 0 authorityId + no file → pointer-only, still EMITTED")
    }

    @Test
    fun `quote-preview row with a local file still carries bytes`() {
        
        val exporter = newExporter()
        val bytes = atRestBytes(180) { (it * 19 + 4).toByte() }
        seedFile("m-host", "qfile.jpg", bytes)
        val am = attModel(id = "att-q-local", authorityId = null, fileName = "qfile.jpg").apply {
            quoteModelDatabaseId = 4244L
        }

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-host")

        assertFalse(neutral.pointerOnly, "quote-preview row with a real file carries bytes")
        assertEquals("att-q-local", neutral.stableId)
        assertBytesEqual(bytes, bytesFor(exporter, "att-q-local"))
    }

    private fun assertBytesEqual(expected: ByteArray, actual: ByteArray?) {
        assertNotNull(actual, "byte-source returned no bytes")
        assertTrue(expected.contentEquals(actual), "resolved bytes differ from seeded file")
    }
}

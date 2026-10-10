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

/**
 * Unit coverage for the forward/quote attachment EXPORT byte-resolution fix
 * (`feature/migration-v2-android-prod`, forward-attachment-bug.md Fix 1 + Fix 2 + Fix 3).
 *
 * The full [com.difft.android.migration.v2.NeutralRoundTripTest] seeds NEUTRAL records directly, so
 * it never exercises [NeutralExporterImpl]'s byte resolution against [AttachmentModel] rows + a real
 * filesystem (the live WCDB `Table` loads native lib off-device, and the production
 * `FileUtil.getMessageAttachmentFilePath` needs `getExternalFilesDir`). This test drives the exporter
 * helpers DIRECTLY:
 *  - [NeutralExporterImpl.projectNormalAttachment] (normal message attachment)
 *  - [NeutralExporterImpl.projectOwnedAttachment] (forward/quote sub-structure attachment)
 *
 * with the injectable [NeutralExporterImpl.attachmentDir] pointed at a temp dir and real bytes seeded
 * on disk, then asserts:
 *  1. pointerOnly is false (a byte frame travels) for forward/quote rows whose file exists, and the
 *     [NeutralExporterImpl.attachmentByteSource] resolves the SAME bytes by that stableId.
 *  2. Each merged-forward child resolves under its OWN authorityId dir.
 *  3. A null-`id` forward row still carries bytes with stableId == authorityId.toString().
 *  4. Regression: a forward row whose constructed file is MISSING is pointer-only (no frame), and a
 *     NORMAL attachment still resolves via the constructed `attachment/<messageId>/` path.
 *  5. Regression (#935): a quote-preview row with null `id` + null/0 `authorityId` (owned by
 *     `quoteModelDatabaseId`, inline thumbnail, no local file) is NOT dropped — it is emitted as a
 *     pointer-only record so the target can rebuild `quote.attachments`. (A prior "phantom" filter
 *     wrongly dropped these rows and broke quote media previews on migrated data.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class NeutralExporterForwardAttachmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Canonical-dir fake: `<root>/attachment/<ownerKey>/` (trailing separator; caller appends fileName). */
    private fun attachmentDir(ownerKey: String): String =
        File(tmp.root, "attachment/$ownerKey").apply { mkdirs() }.absolutePath + File.separator

    private fun newExporter(): NeutralExporterImpl =
        NeutralExporterImpl(readSource = mockk(relaxed = true)).apply {
            attachmentDir = ::attachmentDir
        }

    /**
     * Seed the at-rest ciphertext at `attachment/<ownerKey>/<fileName>.encrypt` — the exact file the
     * exporter probes (`planForPath` keys off `<constructed>.encrypt`) and carries verbatim
     * (`attachmentByteSource` streams `plan.encryptPath`). [bytes] is that carried at-rest content.
     */
    /**
     * Bytes shaped like a real at-rest ciphertext: `[IV16][AES-CBC 16*n][HMAC32]`. The exporter only
     * carries a STRUCTURALLY VALID at-rest file (an arbitrary length can't be decrypted, so carrying
     * it would migrate an attachment that never renders and burn the pointer that would re-download
     * it), so a fixture of arbitrary length is correctly skipped and would not exercise byte carry.
     */
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
    ) = AttachmentModel().apply {
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

    // ── attachments left behind by choice: only voice notes keep their bytes ─────────────────

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

    // ── (1) single-forward: bytes carried + resolvable + placed at authorityId dir ────────────

    @Test
    fun `single-forward attachment carries bytes resolved under its authorityId`() {
        val exporter = newExporter()
        val bytes = atRestBytes(321) { (it * 7 + 1).toByte() }
        // Row with no localId (relocation has not run): the file still sits at its LEGACY address,
        // attachment/<authorityId>/ — the exporter must fall back to it rather than give up.
        seedFile("700100", "fwd.jpg", bytes)
        val am = attModel(id = "att-fwd", authorityId = 700100L, fileName = "fwd.jpg")

        val neutral = exporter.projectOwnedAttachment(owned(am))
        // The row itself has no messageId; the host message still travels in messageRef (iOS needs it).
        assertEquals("m-host", neutral.messageRef)

        assertFalse(neutral.pointerOnly, "forward attachment with a real file must carry bytes")
        assertEquals("att-fwd", neutral.stableId, "stableId == am.id when id is present (byte-frame key)")
        assertEquals(700100L, neutral.serverAttachmentId, "authorityId travels as serverAttachmentId")
        assertBytesEqual(bytes, bytesFor(exporter, "att-fwd"))
    }

    // ── (2) merged-forward: EACH child resolves under its OWN authorityId ─────────────────────

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
        // Each child's bytes resolve from its OWN authorityId dir — not a shared/host dir.
        assertBytesEqual(bytesA, bytesFor(exporter, "att-a"))
        assertBytesEqual(bytesB, bytesFor(exporter, "att-b"))
    }

    // ── quote attachments: meta-only, never collected as owned records (contract C12) ─────────

    @Test
    fun `quote attachments are not collected as owned attachment records`() {
        // subStructureAttachments walks ONLY the forward tree now — a message with a quote FK must
        // yield no QUOTE-owned records (quotes travel meta-only via attachmentsMeta, contract C12).
        val msg = org.difft.app.database.models.MessageModel().apply {
            id = "m-quote-host"
            quoteDatabaseId = 77L
        }
        val owned = com.difft.android.migration.v2.export.NeutralProjections
            .subStructureAttachments(mockk(relaxed = true), msg)
        assertTrue(owned.isEmpty(), "quote-owned attachment rows must not be collected (C12 meta-only)")
    }

    // ── (4 / Fix 2) null-id forward row: stable stableId == authorityId ───────────────────────

    @Test
    fun `null-id forward attachment carries bytes with stableId equal to authorityId`() {
        val exporter = newExporter()
        val bytes = atRestBytes(444) { (it * 13 + 7).toByte() }
        seedFile("123456", "noid.jpg", bytes)
        // id == null (the Category A bug): previously the byte-frame key was null → no byte frame even
        // though the file exists. Fix 2 makes stableId = authorityId.toString().
        val am = attModel(id = null, authorityId = 123456L, fileName = "noid.jpg")

        val neutral = exporter.projectOwnedAttachment(owned(am))

        assertFalse(neutral.pointerOnly, "null-id forward row with a real file must still carry bytes")
        assertEquals("123456", neutral.stableId, "stableId falls back to authorityId.toString()")
        // The forward node's preview meta must name this same record, or the receiver cannot pair them.
        assertEquals(neutral.stableId, NeutralProjections.attachmentMeta(am).stableId, "meta stableId == record stableId")
        // The byte-source key MATCHES the record stableId → bytes resolve.
        assertBytesEqual(bytes, bytesFor(exporter, "123456"))
    }

    // ── regression: no file at EITHER address → pointer-only (no frame) ───────────────────────

    @Test
    fun `attachment with no file at either address is pointer-only`() {
        val exporter = newExporter()
        // Nothing seeded at the localId address nor at the legacy authorityId one. A file exists
        // elsewhere on disk — an address the resolver must never consider.
        File(tmp.root, "unrelated.jpg").apply { writeBytes(ByteArray(10)) }
        val am = attModel(id = "att-missing", authorityId = 999000L, fileName = "missing.jpg", localId = "lid-missing")

        val neutral = exporter.projectOwnedAttachment(owned(am))

        assertTrue(neutral.pointerOnly, "no file at any known address → pointer-only (no byte frame)")
        assertNull(bytesFor(exporter, "att-missing"), "byte-source has no plan for a pointer-only ref")
    }

    // ── current addressing rule: bytes resolve under the row's own localId ────────────────────

    @Test
    fun `attachment resolves via its own localId address`() {
        val exporter = newExporter()
        val bytes = atRestBytes(512) { (it * 17 + 3).toByte() }
        // Post-relocation layout: every copy lives under attachment/<localId>/ regardless of owner.
        seedFile("lid-normal", "photo.jpg", bytes)
        val am = attModel(id = "att-normal", authorityId = 555L, fileName = "photo.jpg", localId = "lid-normal")

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-normal")

        assertFalse(neutral.pointerOnly, "attachment addressed by localId carries bytes")
        assertEquals("att-normal", neutral.stableId, "normal stableId stays am.id (NOT authorityId)")
        assertBytesEqual(bytes, bytesFor(exporter, "att-normal"))
    }

    // ── localId wins over the legacy address when BOTH exist ──────────────────────────────────

    @Test
    fun `localId address takes precedence over the legacy one`() {
        val exporter = newExporter()
        val current = ByteArray(64) { 1 }
        val stale = ByteArray(64) { 9 }
        seedFile("lid-both", "dup.jpg", current)   // current address
        seedFile("m-both", "dup.jpg", stale)       // legacy address, left behind by the relocation
        val am = attModel(id = "att-both", authorityId = 42L, fileName = "dup.jpg", localId = "lid-both")

        exporter.projectNormalAttachment(am, messageId = "m-both")

        assertBytesEqual(current, bytesFor(exporter, "att-both"))
    }

    // ── legacy fallback: no localId → the pre-relocation messageId address still resolves ─────

    @Test
    fun `normal attachment falls back to the legacy messageId address`() {
        val exporter = newExporter()
        val bytes = atRestBytes(512) { (it * 17 + 3).toByte() }
        // Row predating the localId column, file not yet relocated: attachment/<messageId>/.
        seedFile("m-normal", "photo.jpg", bytes)
        val am = attModel(id = "att-normal", authorityId = 555L, fileName = "photo.jpg")

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-normal")

        assertFalse(neutral.pointerOnly, "legacy-addressed attachment still carries bytes")
        assertBytesEqual(bytes, bytesFor(exporter, "att-normal"))
    }

    // (The old "falls back to the persisted `path` column" case is gone with the column itself:
    //  addressing is now derived from localId, with the legacy directory as the only fallback.)

    // ── (#935) REGRESSION: quote-preview row (null id + null/0 authorityId) is NOT dropped ────────
    //
    // #935 deep analysis proved there are NO truly orphan attachment rows; the rows that look like
    // "phantoms" (null id, null/0 authorityId, no local file) are LEGITIMATE quote-preview thumbnail
    // rows owned by `quoteModelDatabaseId` with an inline thumbnail BLOB. A prior defensive "phantom"
    // filter dropped exactly these → the migrated QuoteModel ended up with empty `quote.attachments`
    // → no quote media preview on the target. These tests assert the OPPOSITE of the old filter:
    // such a row IS emitted (pointer-only, no bytes), never skipped.

    @Test
    fun `quote-preview row with null id and null authorityId is emitted pointer-only NOT dropped`() {
        val exporter = newExporter()
        // No local file (inline thumbnail BLOB only; path null). Owned by a quoteModelDatabaseId.
        val am = attModel(id = null, authorityId = null, fileName = "qpreview.jpg").apply {
            quoteModelDatabaseId = 4242L
        }

        val neutral = exporter.projectNormalAttachment(am, messageId = "m-host")

        // The record IS produced (the loop no longer skips it) — just without carried bytes.
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
        // A quote-preview row that DOES have a reconstructable local file must carry bytes — proving
        // the revert does not regress the carried path for owned-by-quoteModelDatabaseId rows that
        // happen to have an addressable id.
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

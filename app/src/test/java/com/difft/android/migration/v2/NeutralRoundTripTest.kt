package com.difft.android.migration.v2

import android.app.Application
import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.container.AttachmentByteSource
import com.difft.android.migration.v2.container.AttachmentByteTarget
import com.difft.android.migration.v2.container.AttachmentBytes
import com.difft.android.migration.v2.container.ContainerFormat
import com.difft.android.migration.v2.container.ContainerReaderImpl
import com.difft.android.migration.v2.container.ContainerWriterImpl
import com.difft.android.migration.v2.container.FileArchiveSink
import com.difft.android.migration.v2.export.NeutralProjections
import com.difft.android.migration.v2.importer.NeutralImporter
import com.difft.android.migration.v2.importer.RoomStateRecomputer
import com.difft.android.chat.common.SendType
import com.difft.android.migration.v2.model.AttachmentOwner
import com.difft.android.migration.v2.model.SendState
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralAttachmentMeta
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralForward
import com.difft.android.migration.v2.model.NeutralForwardContext
import com.difft.android.migration.v2.model.NeutralGroupKey
import com.difft.android.migration.v2.model.NeutralIdentity
import com.difft.android.migration.v2.model.MENTIONS_ALL_UID
import com.difft.android.migration.v2.model.NeutralMention
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralParticipantReadPosition
import com.difft.android.migration.v2.model.NeutralQuote
import com.difft.android.migration.v2.model.NeutralReadPosition
import com.difft.android.migration.v2.model.NeutralReaction
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.model.NeutralSharedContact
import com.difft.android.migration.v2.model.NeutralSharedContactPhone
import com.difft.android.migration.v2.verify.RoundTripVerifier
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import org.difft.app.database.models.MessageModel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * THE round-trip validation — the POC's core deliverable (plan §4.A, the D-UNIFY P0 red line).
 *
 * Runs the FULL engine on the only test tier that actually executes in this project
 * (JVM/Robolectric): the real [ContainerWriterImpl] (frame + AES-CTR + HMAC) → file →
 * real [ContainerReaderImpl] (verify-then-decrypt) → real [NeutralImporter] (ID remap + FK
 * reconnection) writing into an [InMemoryWcdbWriteTarget] → re-project via [TargetReProjector] →
 * [RoundTripVerifier].
 *
 * ### Why the WCDB write surface is faked (and only that)
 *
 * The Tencent WCDB ORM calls `System.loadLibrary("WCDB")` in its `CppObject` static init, which
 * throws `UnsatisfiedLinkError` on the JVM test classpath — the project's own
 * `DBPublicKeyInfoStoreTest` is `@Ignore`-d for this exact reason, and there is no androidTest
 * source set / device wired into CI. So the SOURCE rows and the TARGET DB cannot be a live WCDB
 * here. We therefore:
 *  - seed the SOURCE side as canonical [NeutralRecord]s directly (the projection from WCDB rows to
 *    these shapes is unit-covered by the pure `NeutralProjections`); and
 *  - import into the [InMemoryWcdbWriteTarget] seam, which reproduces WCDB autoincrement-PK
 *    semantics so the importer's FK reconnection is genuinely exercised.
 *
 * Everything between the two endpoints — encryption envelope, framing, HMAC verify, JSONL codec,
 * importer remap/FK-reconnect, verifier — is the REAL production code. The native-WCDB end-to-end
 * lives in the on-device POC Activity (plan §4.B / task 7).
 */
// Test code, length is inherent (net-new feature tests; splitting adds no value).
@Suppress("LargeClass", "LongMethod")
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class NeutralRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val crypto = BackupCryptoImpl()
    private val writer = ContainerWriterImpl(crypto)
    private val reader = ContainerReaderImpl(crypto)

    private val passphrase = "correct horse battery staple"

    // schema v2 §0.5 manifest identity inputs (synthetic; real values come from the A3 service —
    // account from the session, version from BuildConfig). sourcePlatform + formatVersion are
    // constants the writer fills.
    private val sourceAccount = "u_roundtrip"

    /** The account these fixtures treat as the local user. */
    private val SELF_UID = "u-self"
    private val sourceAppVersion = "0.0.0-roundtrip"

    // A real attachment payload (image) carried through the byte frames; bytes hashed end-to-end.
    private val imageBytes = ByteArray(5000) { (it * 31 + 7).toByte() }
    private val voiceBytes = ByteArray(1200) { (it * 13 + 3).toByte() }
    // Forward-internal + quote-preview attachment payloads (schema v2 §3, the 3-owner rule).
    private val forwardImageBytes = ByteArray(3300) { (it * 17 + 5).toByte() }
    // Merged-forward root[1]'s forward-owned attachment (the multi-root regression fixture).
    private val mergedForwardImageBytes = ByteArray(2700) { (it * 19 + 13).toByte() }

    // ── seed: the 8 POC entity types ────────────────────────────────────────────────────────

    private fun seedSource(): List<NeutralRecord> {
        val conv = "room-1"
        val records = ArrayList<NeutralRecord>()

        // §6 GroupKey
        records += NeutralRecord.GroupKey(NeutralGroupKey(groupId = "gid-1", rGroup = "rGroupKeyMaterialB64=="))
        // §5 ConversationLocal — pinned + read watermark
        records += NeutralRecord.ConversationLocal(
            NeutralConversationLocal(
                conversationRef = conv,
                pinnedTime = 1_700_000_000_000L,
                readPosition = NeutralReadPosition(readAt = 0L, maxServerTime = 1_700_000_500_000L, maxNotifySequenceId = 0L, maxSequenceId = 0L),
            )
        )

        // (1) plain text message
        records += msg("m-text", conv, body = "hello world", kind = ContentKind.TEXT, sentAt = 1000)

        // (2) image message + attachment with real bytes. A byte-carrying attachment still carries
        // its identity/credentials (serverAttachmentId/encryptionKey ALWAYS travel) because the
        // destination reads them unconditionally (AttachmentModel.authorityId!!). These non-null
        // values are the regression guard: if a future change re-introduces the
        // "byte-carrying → null serverAttachmentId/encryptionKey" bug, the lossless round-trip fails.
        records += msg("m-image", conv, body = null, kind = ContentKind.FILE, sentAt = 2000, attachmentRefs = listOf("att-image"))
        records += NeutralRecord.Attachment(
            NeutralAttachment(
                stableId = "att-image", messageRef = "m-image", contentType = "image/jpeg",
                size = imageBytes.size.toLong(), width = 640, height = 480, durationMs = null,
                flags = 0, fileName = "photo.jpg",
                serverAttachmentId = 9876543210L, encryptionKey = "aW1hZ2Uta2V5LWJhc2U2NA==",
                // digest ALWAYS travels even for a byte-carrying attachment (identity/credential):
                // the destination's download/decrypt gate requires it. Regression guard for a future
                // change that drops digest on byte-carrying rows.
                digestBase64 = "aW1hZ2UtZGlnZXN0LWJhc2U2NA==",
            )
        )

        // (3) voice message + attachment (duration / voice flag)
        records += msg("m-voice", conv, body = null, kind = ContentKind.FILE, sentAt = 3000, attachmentRefs = listOf("att-voice"))
        records += NeutralRecord.Attachment(
            NeutralAttachment(
                stableId = "att-voice", messageRef = "m-voice", contentType = "audio/aac",
                size = voiceBytes.size.toLong(), width = null, height = null, durationMs = 4200L,
                // A byte-carrying attachment must have a fileName: the canonical on-disk path is
                // attachment/<ownerKey>/<fileName>, and the exporter only carries bytes when a
                // fileName is present (NeutralExporter.constructPath). Voice flag + duration are still
                // exercised; fileName makes the byte-carrying voice attachment restorable canonically.
                flags = 1, fileName = "voice.aac",
            )
        )

        // (4) quoted message + the quote that references it (FK reconnect by {author,sentAt})
        records += msg("m-quoted", conv, body = "original", kind = ContentKind.TEXT, sentAt = 4000, sender = "u-alice")
        records += msg(
            "m-reply", conv, body = "agreed", kind = ContentKind.TEXT, sentAt = 5000,
            quote = NeutralQuote(author = "u-alice", sentAt = 4000, textPreview = "original"),
        )

        // (5) message with reactions
        records += msg(
            "m-react", conv, body = "nice", kind = ContentKind.TEXT, sentAt = 6000,
            reactions = listOf(
                NeutralReaction(emoji = "👍", fromUid = "u-bob", removed = false, sentAt = 6100),
                NeutralReaction(emoji = "🎉", fromUid = "u-carol", removed = false, sentAt = 6200),
            ),
        )

        // (6) merged-forward (合并转发) — TWO PARALLEL top-level forwards (roots), the exact bug this
        //     fix closes. Previously the second root was folded into root[0].nested and collapsed on
        //     round-trip (read-back saw forwards.size==1). Now both roots must survive as PARALLEL
        //     top-level forwards. root[0] also carries a nested child + grandchild (depth preserved);
        //     root[1] owns a forward attachment (att-mf — bytes travel, re-link by {author,sentAt}).
        //     isFromGroup=true must round-trip (was lost when the importer made an empty context).
        records += msg(
            "m-forward", conv, body = null, kind = ContentKind.FORWARD, sentAt = 7000,
            forwardContext = NeutralForwardContext(
                isFromGroup = true,
                roots = listOf(
                    NeutralForward(
                        author = "u-dave", sentAt = 6500, body = "forwarded root 0",
                        nested = listOf(
                            NeutralForward(author = "u-eve", sentAt = 6400, body = "child A"),
                            NeutralForward(
                                author = "u-frank", sentAt = 6300, body = "child B",
                                nested = listOf(NeutralForward(author = "u-grace", sentAt = 6200, body = "grandchild")),
                            ),
                        ),
                    ),
                    NeutralForward(
                        author = "u-heidi", sentAt = 6100, body = "forwarded root 1 (parallel)",
                        attachmentsMeta = listOf(
                            NeutralAttachmentMeta(stableId = "att-mf", contentType = "image/jpeg", fileName = "mf.jpg"),
                        ),
                    ),
                ),
            ),
        )
        // root[1]'s forward-owned attachment bytes (schema v2 §3): re-link to the {u-heidi, 6100}
        // forward node (forwardModelDatabaseId), NOT the message.
        records += NeutralRecord.Attachment(
            NeutralAttachment(
                stableId = "att-mf", messageRef = "m-forward", contentType = "image/jpeg",
                size = mergedForwardImageBytes.size.toLong(), width = 128, height = 96, durationMs = null,
                flags = 0, fileName = "mf.jpg",
                serverAttachmentId = 9911882277L,
                owner = AttachmentOwner.FORWARD, ownerAuthor = "u-heidi", ownerSentAt = 6100,
            )
        )

        // (7) pointer-only attachment (Fix 4): bytes legitimately absent (lazy-download), so only
        // metadata + the server pointer (serverAttachmentId + encryptionKey) travel — NO byte frame.
        records += msg("m-pointer", conv, body = null, kind = ContentKind.FILE, sentAt = 7500, attachmentRefs = listOf("att-pointer"))
        records += NeutralRecord.Attachment(
            NeutralAttachment(
                stableId = "att-pointer", messageRef = "m-pointer", contentType = "image/png",
                size = 99_999L, width = 800, height = 600, durationMs = null,
                flags = 0, fileName = "not-downloaded.png",
                serverAttachmentId = 1234567890L,
                encryptionKey = "a2V5LWJhc2U2NA==",
                // digest MUST travel for a pointer-only attachment: without it the re-downloaded
                // blob cannot be integrity-verified/decrypted (download/decrypt gate requires both
                // key AND digest). Regression guard for the digest-drop bug this fix closes.
                digestBase64 = "ZGlnZXN0LWJhc2U2NA==", pointerOnly = true,
            )
        )

        // (8) forward-WITH-attachment (schema v2 §3): a forwarded message whose forward node carries
        //     an image attachment. The forward attachment is owned by the forward node identified by
        //     {author, sentAt}={u-dave, 6700}; its bytes travel as a byte-carrying record and must
        //     re-link to that forward (forwardModelDatabaseId) on import, NOT to the message.
        records += msg(
            "m-forward-att", conv, body = null, kind = ContentKind.FORWARD, sentAt = 7700,
            forwardContext = NeutralForwardContext(
                roots = listOf(
                    NeutralForward(
                        author = "u-dave", sentAt = 6700, body = "forwarded with image",
                        attachmentsMeta = listOf(
                            NeutralAttachmentMeta(stableId = "att-fwd", contentType = "image/jpeg", fileName = "fwd.jpg"),
                        ),
                    ),
                ),
            ),
        )
        records += NeutralRecord.Attachment(
            NeutralAttachment(
                stableId = "att-fwd", messageRef = "m-forward-att", contentType = "image/jpeg",
                size = forwardImageBytes.size.toLong(), width = 320, height = 240, durationMs = null,
                flags = 0, fileName = "fwd.jpg",
                // serverAttachmentId (== source authorityId) is the canonical on-disk dir key for a
                // forward attachment: Record2MessageFactory builds the forward ChatMessage.id as
                // authorityId.toString(), so bytes restore under attachment/<authorityId>/<fileName>.
                serverAttachmentId = 5566778899L,
                owner = AttachmentOwner.FORWARD, ownerAuthor = "u-dave", ownerSentAt = 6700,
            )
        )

        // (9) quote-with-preview-META only (contract C12): a reply quoting a message. Quote
        //     attachments travel ONLY as NeutralQuote.attachmentsMeta — no attachment record, no
        //     byte frame (thumbnails are a post-import backfill concern).
        records += msg("m-quoted-att", conv, body = "look at this", kind = ContentKind.FILE, sentAt = 8500, sender = "u-alice")
        records += msg(
            "m-reply-att", conv, body = "nice pic", kind = ContentKind.TEXT, sentAt = 8600,
            quote = NeutralQuote(
                author = "u-alice", sentAt = 8500, textPreview = "look at this",
                attachmentsMeta = listOf(
                    NeutralAttachmentMeta(stableId = "att-quote", contentType = "image/png", fileName = "q.png"),
                ),
            ),
        )

        // a message with @mentions to exercise that sub-structure too
        records += msg(
            "m-mention", conv, body = "hey @all and @you", kind = ContentKind.TEXT, sentAt = 8000,
            mentions = listOf(
                NeutralMention(uid = "u-all", start = 4, length = 4, type = null),
                NeutralMention(uid = "u-me", start = 13, length = 4, type = null),
            ),
            // No extras: the @-list column is a pure derivation of `mentions`, so it is rebuilt on
            // import rather than carried — the round trip proves `mentions` alone is sufficient.
        )

        return records
    }

    private fun msg(
        id: String, conv: String, body: String?, kind: ContentKind, sentAt: Long,
        // A counterpart, not the local user: these fixtures are received messages, and sender is
        // what makes them so. Naming SELF_UID here while declaring INCOMING is a contradiction the
        // re-projection now catches.
        sender: String = "u-alice",
        quote: NeutralQuote? = null,
        forwardContext: NeutralForwardContext? = null,
        mentions: List<NeutralMention> = emptyList(),
        reactions: List<NeutralReaction> = emptyList(),
        attachmentRefs: List<String> = emptyList(),
        extras: Map<String, String> = emptyMap(),
        sendState: SendState? = null,
    ): NeutralRecord.Message = NeutralRecord.Message(
        NeutralMessage(
            stableId = id, conversationRef = conv, sender = sender,
            direction = if (sender == SELF_UID) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            body = body, sentAt = sentAt,
            serverAt = sentAt + 10, receivedAt = sentAt + 20, expireTimer = 0,
            // An explicit exception anchor, which the ladder expansion must leave untouched.
            expireStartedAt = sentAt + 30,
            contentKind = kind, systemType = null, confidential = false,
            quote = quote, forwardContext = forwardContext, mentions = mentions, reactions = reactions,
            attachmentRefs = attachmentRefs,
            // readAt is pure "when read" — OUTGOING never carries it (contract §7.6.1).
            sendState = sendState,
            platformExtras = extras,
        )
    )

    /** Byte source for the writer: serves the real image/voice bytes by stableId. */
    private fun byteSource(): AttachmentByteSource = AttachmentByteSource { ref ->
        when (ref) {
            "att-image" -> AttachmentBytes(imageBytes.size.toLong(), ByteArrayInputStream(imageBytes))
            "att-voice" -> AttachmentBytes(voiceBytes.size.toLong(), ByteArrayInputStream(voiceBytes))
            "att-fwd" -> AttachmentBytes(forwardImageBytes.size.toLong(), ByteArrayInputStream(forwardImageBytes))
            "att-mf" -> AttachmentBytes(mergedForwardImageBytes.size.toLong(), ByteArrayInputStream(mergedForwardImageBytes))
            else -> null
        }
    }

    // ── THE round-trip ────────────────────────────────────────────────────────────────────

    @Test
    fun `8 entity types round-trip losslessly through encrypt-decrypt-import`() = runTest {
        val source = seedSource()
        val container = File(tmp.root, "roundtrip.ttmig")

        // export stream → ContainerWriter → encrypted file
        val writeManifest = writer.write(source.asFlow(), FileArchiveSink(container), passphrase, byteSource(), sourceAccount, sourceAppVersion)
        assertEquals(source.size, writeManifest.recordCount, "writer should frame every record")
        // image + voice + forward-attachment + merged-forward-attachment = 4 byte frames
        // (att-pointer carries none; quote attachments are meta-only per contract C12).
        assertEquals(4, writeManifest.attachmentCount, "four attachment byte frames")
        assertTrue(container.length() > 0, "container file written")

        // schema v2 §0.5 — writer recorded the 4 identity fields (caller-supplied + constants).
        assertEquals(sourceAccount, writeManifest.sourceAccount, "writer recorded sourceAccount")
        assertEquals("android", writeManifest.sourcePlatform, "writer recorded sourcePlatform constant")
        assertEquals(sourceAppVersion, writeManifest.sourceAppVersion, "writer recorded sourceAppVersion")
        assertEquals(ContainerFormat.FORMAT_VERSION, writeManifest.formatVersion, "writer recorded formatVersion constant")

        // ContainerReader → import into target DB (the in-memory seam)
        val target = InMemoryWcdbWriteTarget()
        val importer = NeutralImporter(target, messageAttachmentDir = ::fakeAttachmentDir, previewStrings = FakePreviewStrings)
        val restoredDir = File(tmp.root, "restored").apply { mkdirs() }
        val restoredPaths = HashMap<String, String>()

        val readManifest = reader.read(
            file = container,
            passphrase = passphrase,
            attachmentTarget = fileTarget(restoredDir, restoredPaths, importer),
            onRecord = { record ->
                importer.onRecord(record)
            },
        )
        importer.finish()
        assertEquals(source.size, readManifest.recordCount, "reader should see every record")
        assertEquals(4, readManifest.attachmentCount)

        // schema v2 §0.5 — the 4 identity fields round-trip writer→reader (reader exposes them on
        // the returned manifest; the importer's account-match check reads sourceAccount from here).
        assertEquals(writeManifest.sourceAccount, readManifest.sourceAccount, "sourceAccount round-trips")
        assertEquals(writeManifest.sourcePlatform, readManifest.sourcePlatform, "sourcePlatform round-trips")
        assertEquals(writeManifest.sourceAppVersion, readManifest.sourceAppVersion, "sourceAppVersion round-trips")
        assertEquals(writeManifest.formatVersion, readManifest.formatVersion, "formatVersion round-trips")

        // re-project target rows back to neutral and verify field-by-field (PK-agnostic)
        val imported = TargetReProjector.reProject(target, SELF_UID)
        val result = RoundTripVerifier.verify(source, imported)
        assertTrue(result.pass, "round-trip not lossless:\n${result.report()}")

        // digest (SHA-256, required alongside key to re-download+decrypt) ALWAYS round-trips — for
        // a byte-carrying attachment AND a pointer-only one. Assert against the re-projected neutral
        // shape (not just the verifier's pass) so a future digest-drop regression is pinned to the
        // exact field. The importer decoded n.digestBase64 → AttachmentModel.digest, and the
        // re-projector re-encoded it; a dropped digest would surface here as null.
        val importedImageAtt = imported.filterIsInstance<NeutralRecord.Attachment>()
            .first { it.value.stableId == "att-image" }.value
        assertEquals(
            "aW1hZ2UtZGlnZXN0LWJhc2U2NA==", importedImageAtt.digestBase64,
            "byte-carrying attachment digest must survive the round-trip",
        )
        val importedPointerAtt = imported.filterIsInstance<NeutralRecord.Attachment>()
            .first { it.value.stableId == "att-pointer" }.value
        assertTrue(importedPointerAtt.pointerOnly, "att-pointer should re-import as pointer-only")
        assertEquals(
            "ZGlnZXN0LWJhc2U2NA==", importedPointerAtt.digestBase64,
            "pointer-only attachment digest must survive the round-trip (needed to decrypt+verify a re-download)",
        )

        // Open-vs-download signal: the neutral schema no longer carries `status` (pointerOnly is the
        // signal, contract C5) — the IMPORTER derives it: bytes present ⇒ AttachmentModel.status ==
        // SUCCESS(3). Regression guard for the bug where imported attachments defaulted to status=0
        // and rendered as "needs download" despite the bytes being present.
        val importedImageModel = target.attachments.first { it.id == "att-image" }
        assertEquals(
            AttachmentStatus.SUCCESS.code, importedImageModel.status,
            "a downloaded (byte-carrying) attachment must import as SUCCESS(3) so it shows open, not download",
        )

        // attachment bytes hash-equal end-to-end (message, voice, forward-internal, quote-preview)
        assertContentHashEquals(imageBytes, File(restoredPaths.getValue("att-image")))
        assertContentHashEquals(voiceBytes, File(restoredPaths.getValue("att-voice")))
        assertContentHashEquals(forwardImageBytes, File(restoredPaths.getValue("att-fwd")))

        // ── ADDRESSING REGRESSION GUARD ─────────────────────────────────────────────────────────
        // Every restored attachment must be addressable by the app's ONE rule: the row names its own
        // `localId`, and the bytes must have landed under exactly that directory. A row whose bytes
        // sit anywhere else is invisible to the app (it re-derives the path at load time, it never
        // reads a stored absolute string), so assert row-address ↔ byte-location agreement rather
        // than any hardcoded key.
        listOf("att-image" to "photo.jpg", "att-fwd" to "fwd.jpg", "att-voice" to "voice.aac").forEach { (ref, name) ->
            val row = target.attachments.first { it.id == ref }
            val localId = row.localId
            assertTrue(!localId.isNullOrEmpty(), "$ref must carry a freshly minted localId")
            // Compare the RELATIVE address (the invariant): the byte target writes under a staging
            // root that the promote dir-move relocates, so only `<localId>/<fileName>` is portable.
            assertTrue(
                restoredPaths.getValue(ref).endsWith("$localId/$name"),
                "$ref bytes must land under <its own localId>/$name, was ${restoredPaths.getValue(ref)}",
            )
        }
        // Distinct copies get distinct addresses (per-copy directories, never shared).
        val mintedIds = target.attachments.mapNotNull { it.localId }
        assertEquals(mintedIds.size, mintedIds.toSet().size, "each restored attachment owns a unique localId")

        // FK reconnection sanity: target PKs are fresh + linked, not the (absent) source PKs
        val reply = target.messages.first { it.id == "m-reply" }
        assertTrue(reply.quoteDatabaseId != null && reply.quoteDatabaseId!! > 0, "quote FK remapped")
        val fwd = target.messages.first { it.id == "m-forward" }
        assertTrue(fwd.forwardContextDatabaseId != null, "forward context FK remapped")
        // 6 forward rows: m-forward (root0 + childA + childB + grandchild + root1 = 5) + m-forward-att (1 root).
        assertEquals(6, target.forwards.size, "all forward nodes re-exploded")
        assertTrue(target.transactionCount >= 1, "writes were batched in a transaction")

        // ── REGRESSION GUARD (this fix): a merged-forward with N PARALLEL top-level forwards must
        //    NOT collapse to 1 root on round-trip. Previously the second root was folded into
        //    root[0].nested; read-back then saw forwards.size==1 and the card rendered wrong. ──
        // The m-forward context now has exactly 2 top-level (parent-null) forward rows in the target.
        val fwdCtxPk = fwd.forwardContextDatabaseId
        val mfTopLevel = target.forwards.filter {
            it.forwardContextDatabaseId == fwdCtxPk && (it.parentForwardModelDatabaseId == null || it.parentForwardModelDatabaseId == 0L)
        }
        assertEquals(2, mfTopLevel.size, "merged-forward must keep its 2 PARALLEL top-level forwards (not collapse to 1)")
        assertEquals(setOf("u-dave", "u-heidi"), mfTopLevel.map { it.author }.toSet(), "both parallel roots preserved as top-level")
        // isFromGroup must round-trip onto the ForwardContextModel (was lost via empty-context import).
        val mfCtx = target.forwardContexts.first { it.databaseId.toLong() == fwdCtxPk }
        assertTrue(mfCtx.isFromGroup, "forwardContext.isFromGroup must round-trip")

        // The re-projected neutral merged-forward keeps roots.size==2, nesting depth, AND the
        // forward-owned attachment re-links to the correct node via {author,sentAt}.
        val reMf = imported.filterIsInstance<NeutralRecord.Message>().first { it.value.stableId == "m-forward" }.value
        val reFc = reMf.forwardContext!!
        assertEquals(2, reFc.roots.size, "re-projected merged-forward keeps 2 parallel roots")
        assertTrue(reFc.isFromGroup, "re-projected forwardContext.isFromGroup preserved")
        val root0 = reFc.roots.first { it.author == "u-dave" }
        // depth preserved: root0 → child B → grandchild
        val childB = root0.nested.first { it.author == "u-frank" }
        assertEquals("u-grace", childB.nested.single().author, "nesting depth preserved (grandchild under child B)")
        // root1 owns the forward attachment meta (re-linked by {u-heidi, 6100}).
        val root1 = reFc.roots.first { it.author == "u-heidi" }
        assertEquals("att-mf", root1.attachmentsMeta.single().stableId, "forward attachment re-links to the correct parallel root")
        // The merged-forward attachment row links to the u-heidi forward node, not the message.
        val mfAtt = target.attachments.first { it.id == "att-mf" }
        assertTrue(mfAtt.forwardModelDatabaseId != null && mfAtt.forwardModelDatabaseId!! > 0, "merged-forward attachment FK linked")
        assertTrue(mfAtt.messageId.isNullOrEmpty(), "merged-forward attachment has no messageId owner")
        val mfAttOwner = target.forwards.first { it.databaseId.toLong() == mfAtt.forwardModelDatabaseId }
        assertEquals("u-heidi", mfAttOwner.author, "merged-forward attachment linked to root[1]")
        assertEquals(6100L, mfAttOwner.serverTimestamp, "merged-forward attachment owner sentAt matches")
        assertContentHashEquals(mergedForwardImageBytes, File(restoredPaths.getValue("att-mf")))

        // schema v2 §3 — forward/quote attachment FK reconnection: the byte-carrying rows must link
        // to the reconstructed forward/quote (by forwardModelDatabaseId / quoteModelDatabaseId),
        // NOT to a message (messageId null), so they re-render under the right sub-structure.
        val fwdAtt = target.attachments.first { it.id == "att-fwd" }
        assertTrue(fwdAtt.forwardModelDatabaseId != null && fwdAtt.forwardModelDatabaseId!! > 0, "forward attachment FK linked")
        assertTrue(fwdAtt.messageId.isNullOrEmpty(), "forward attachment has no messageId owner")
        // databaseId is Int post-#913; the FK is the portable Long — widen the PK to compare.
        val fwdAttOwner = target.forwards.first { it.databaseId.toLong() == fwdAtt.forwardModelDatabaseId }
        assertEquals("u-dave", fwdAttOwner.author, "forward attachment linked to the right forward node")
        assertEquals(6700L, fwdAttOwner.serverTimestamp, "forward owner sentAt matches")

        // Contract C12: quote attachments travel meta-only (inside NeutralQuote.attachmentsMeta) —
        // the importer materializes a BYTE-LESS local meta row (quote FK set, no path/bytes);
        // thumbnails are the post-import backfill's job.
        val quoteMetaRow = target.attachments.first { it.id == "att-quote" }
        assertTrue(quoteMetaRow.quoteModelDatabaseId != null && quoteMetaRow.quoteModelDatabaseId!! > 0, "meta row linked to its quote")
        assertTrue(quoteMetaRow.status != AttachmentStatus.SUCCESS.code, "quote meta row carries no bytes")
        assertTrue(!restoredPaths.containsKey("att-quote"), "no byte frame restored for a quote attachment")
    }

    // ── schema v2 §6 — SYSTEM message displayText round-trip ─────────────────────────────────

    @Test
    fun `SYSTEM message displayText round-trips with rawSystemType in platformExtras`() = runTest {
        val conv = "room-sys"
        // Exactly what NeutralProjections.message() emits for a notify message: body=null,
        // displayText = showContent, systemType = notifyType, raw notify JSON in platformExtras.
        val rawNotify = """{"notifyType":17,"showContent":"Alice ended the call","display":1}"""
        val sysMsg = NeutralRecord.Message(
            NeutralMessage(
                stableId = "m-system", conversationRef = conv, sender = "u-alice",                 direction = MessageDirection.INCOMING, body = null, sentAt = 9000,
                serverAt = 9010, receivedAt = 9020, expireTimer = 0,
                contentKind = ContentKind.SYSTEM,
                systemType = "17",
                // Contract §7.6.1: the notify JSON travels VERBATIM as first-class systemPayload
                // (notify-class systemPayload rule) — no longer via platformExtras.
                systemPayload = rawNotify,
                displayText = "Alice ended the call",
                confidential = false,
                // playStatus has no neutral-null encoding on Android (0/1 only); set played=true so the
                // read-back (playStatus != 1 → true) round-trips — same convention as the msg() helper.
                // An explicit exception anchor, which the ladder expansion must leave untouched.
                expireStartedAt = 9030,
            )
        )
        val source = listOf(
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = conv)),
            sysMsg,
        )

        val target = roundTrip(source)

        // The importer must reconstruct the notify JSON into messageText so Android same-platform
        // rebuilds the exact notify (read-back path WCDBExtensions.previewContent re-derives showContent).
        val row = target.messages.first { it.id == "m-system" }
        assertEquals(MessageModel.TYPE_NOTIFY, row.type, "system message persisted as TYPE_NOTIFY")
        assertEquals(rawNotify, row.messageText, "raw notify JSON reconstructed into messageText")

        // Field-by-field round-trip: displayText + systemType (rawSystemType) + platformExtras survive.
        val imported = TargetReProjector.reProject(target, SELF_UID)
        val result = RoundTripVerifier.verify(source, imported)
        assertTrue(result.pass, "SYSTEM message round-trip not lossless:\n${result.report()}")

        val reImported = imported.filterIsInstance<NeutralRecord.Message>().first { it.value.stableId == "m-system" }
        assertEquals("Alice ended the call", reImported.value.displayText, "displayText round-trips")
        assertEquals("17", reImported.value.systemType, "rawSystemType round-trips")
        assertEquals(rawNotify, reImported.value.systemPayload, "verbatim notify payload round-trips as systemPayload")
    }

    // ── schema v2 §8 #2 — Android skips removed=true reactions on import ──────────────────────

    @Test
    fun `importer skips removed-true reactions writing only active ones`() = runTest {
        val conv = "room-react"
        // A cross-end stream that includes a removed=true reaction tombstone (iOS/Desktop can carry
        // these; Android has no tombstone). The importer must drop it and persist only active rows.
        val msg = NeutralRecord.Message(
            NeutralMessage(
                stableId = "m-react-mix", conversationRef = conv, sender = "u-self",                 direction = MessageDirection.INCOMING, body = "mixed", sentAt = 1000,
                serverAt = 1010, receivedAt = 1020, expireTimer = 0,
                contentKind = ContentKind.TEXT, systemType = null, confidential = false,
                reactions = listOf(
                    NeutralReaction(emoji = "👍", fromUid = "u-bob", removed = false, sentAt = 1100),
                    NeutralReaction(emoji = "👎", fromUid = "u-carol", removed = true, sentAt = 1200),
                    NeutralReaction(emoji = "🎉", fromUid = "u-dave", removed = false, sentAt = 1300),
                ),
            )
        )
        val source = listOf(
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = conv)),
            msg,
        )

        val target = roundTrip(source)

        // Only the two active reactions persist; the removed=true tombstone is dropped (no row).
        val rows = target.reactions.filter { it.messageId == "m-react-mix" }
        assertEquals(2, rows.size, "only active reactions written")
        assertEquals(setOf("👍", "🎉"), rows.map { it.emoji }.toSet(), "removed reaction not persisted")
        assertTrue(rows.none { it.emoji == "👎" }, "removed=true reaction skipped")

        // The re-projected (active-only) end state matches the active reactions verbatim.
        val imported = TargetReProjector.reProject(target, SELF_UID)
        val reMsg = imported.filterIsInstance<NeutralRecord.Message>().first { it.value.stableId == "m-react-mix" }
        assertEquals(
            setOf(
                NeutralReaction(emoji = "👍", fromUid = "u-bob", removed = false, sentAt = 1100),
                NeutralReaction(emoji = "🎉", fromUid = "u-dave", removed = false, sentAt = 1300),
            ),
            reMsg.value.reactions.toSet(),
            "imported reactions are active-only",
        )
    }

    /** Run the full encrypt → file → decrypt → import pipeline for [source]; return the target DB. */
    /**
     * [selfUid] defaults to the account the fixtures treat as "me". A real device always has one,
     * and both the importer and [TargetReProjector] need it: direction is decided by author
     * identity on the way in and on the way back out.
     */
    private suspend fun roundTrip(source: List<NeutralRecord>, selfUid: String = SELF_UID): InMemoryWcdbWriteTarget {
        val container = File(tmp.root, "rt-${System.nanoTime()}.ttmig")
        writer.write(source.asFlow(), FileArchiveSink(container), passphrase, byteSource(), sourceAccount, sourceAppVersion)
        val target = InMemoryWcdbWriteTarget()
        val importer = NeutralImporter(target, messageAttachmentDir = ::fakeAttachmentDir, selfUid = selfUid, previewStrings = FakePreviewStrings)
        val restoredDir = File(tmp.root, "restored-${System.nanoTime()}").apply { mkdirs() }
        val restoredPaths = HashMap<String, String>()
        reader.read(
            file = container,
            passphrase = passphrase,
            attachmentTarget = fileTarget(restoredDir, restoredPaths, importer),
            onRecord = { record -> importer.onRecord(record) },
        )
        importer.finish()
        return target
    }

    // TODO(A6 on-device): snapshot-consistency test — export while concurrently writing the main DB,
    // assert the exported content == the snapshot point (excludes the concurrent writes). This needs
    // the native WCDB lib + BackupSnapshotter.takeSnapshot() (a real second SQLCipher handle on the
    // byte-copied snapshot file), which `System.loadLibrary("WCDB")` cannot load on the JVM/
    // Robolectric classpath (same constraint that fakes the WCDB write surface here — see class
    // kdoc). It belongs to on-device A6 hardening, NOT this JVM tier; do not fake it.

    // ── negative tests ──────────────────────────────────────────────────────────────────────

    @Test
    fun `wrong passphrase throws HmacMismatch and surfaces no records`() = runTest {
        val source = seedSource()
        val container = File(tmp.root, "wrongpass.ttmig")
        writer.write(source.asFlow(), FileArchiveSink(container), passphrase, byteSource(), sourceAccount, sourceAppVersion)

        var seen = 0
        assertFailsWith<MigrationException.HmacMismatch> {
            reader.read(container, "WRONG passphrase", discardTarget()) { seen++ }
        }
        assertEquals(0, seen, "no plaintext record may be surfaced before HMAC verification")
    }

    @Test
    fun `tampered byte throws HmacMismatch`() = runTest {
        val source = seedSource()
        val container = File(tmp.root, "tampered.ttmig")
        writer.write(source.asFlow(), FileArchiveSink(container), passphrase, byteSource(), sourceAccount, sourceAppVersion)

        // Flip one byte inside the encrypted body (after the 42-byte header, before trailer).
        val bytes = container.readBytes()
        val idx = 100.coerceIn(0, bytes.size - 1)
        bytes[idx] = (bytes[idx].toInt() xor 0x7F).toByte()
        container.writeBytes(bytes)

        var seen = 0
        assertFailsWith<MigrationException.HmacMismatch> {
            reader.read(container, passphrase, discardTarget()) { seen++ }
        }
        assertEquals(0, seen)
    }

    // ── Fix A — room-level derived-state recompute on import ──────────────────────────────────

    @Test
    fun `room with messages gets non-empty lastDisplayContent correct roomType and unread after import`() = runTest {
        val group = "group-abc"
        val self = "u-self"
        val source = listOf(
            // A GROUP conversation (roomType=1) with a read watermark before the latest message.
            NeutralRecord.ConversationLocal(
                NeutralConversationLocal(
                    conversationRef = group,
                    roomType = 1,
                    readPosition = NeutralReadPosition(readAt = 0L, maxServerTime = 1500L, maxNotifySequenceId = 0L, maxSequenceId = 0L),
                )
            ),
            // The self / Note-to-Self room — the exact room that stayed empty + filtered out before Fix A.
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = self, roomType = 0)),
            // Group messages: one own-sent (must NOT count as unread), two incoming after the watermark.
            msgTs("g-own", group, "my own message", sentAt = 2000, sender = self),
            msgTs("g-in1", group, "incoming after read", sentAt = 3000, sender = "u-bob"),
            msgTs("g-in2", group, "latest incoming", sentAt = 4000, sender = "u-carol"),
            // Self-room message (the latest there).
            msgTs("s-1", self, "note to self", sentAt = 5000, sender = self),
        )

        val target = roundTrip(source, selfUid = self)

        // roomType carried + set: the group renders as a group (read-back keys on roomType at :270).
        val groupRoom = target.rooms.first { it.roomId == group }
        assertEquals(1, groupRoom.roomType, "group roomType carried + set on import")
        // lastDisplayContent recomputed = the latest message's preview (NOT empty → not list-filtered).
        assertEquals("latest incoming", groupRoom.lastDisplayContent, "group lastDisplayContent recomputed from latest message")
        assertEquals(4000L, groupRoom.lastActiveTime, "group lastActiveTime = latest message systemShowTimestamp")
        // Unread = the 2 incoming messages after the 1500 watermark; the own-sent g-own is excluded.
        assertEquals(2, groupRoom.unreadMessageNum, "unread excludes own-sent, counts incoming-after-watermark")

        // The self / Note-to-Self room is PRESENT and has a non-empty preview (the Fix A bug it closes).
        val selfRoom = target.rooms.first { it.roomId == self }
        assertEquals(0, selfRoom.roomType, "self room is a single chat")
        assertEquals("note to self", selfRoom.lastDisplayContent, "self room preview recomputed (not empty → not filtered out)")
        assertEquals(5000L, selfRoom.lastActiveTime, "self room lastActiveTime set")
        // Own-sent self message → not unread.
        assertEquals(0, selfRoom.unreadMessageNum, "self-sent message is not unread")
    }

    /**
     * The list tags the live path writes only from its own message pipeline must come out of the
     * recompute for migrated rooms: the send-failed tag from a failed outgoing row (aggregate — a later
     * success does not mask it), `[@All]` / `[@You]` from unread mentions (the @all sentinel is the
     * literal `MENTIONS_ALL`, @you wins), and the critical-alert tag from an unread alert row. Mention
     * rows keep the wire type so a re-sent migrated message stays inside the proto's domain.
     */
    @Test
    fun `recompute derives the send-failed, mention and critical-alert room tags`() = runTest {
        val failed = "conv-failed"
        val all = "conv-all"
        val me = "conv-me"
        val alert = "conv-alert"
        val quiet = "conv-quiet"
        val source = listOf(failed, all, me, alert, quiet).map {
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = it, roomType = 1))
        } + listOf(
            msg("f-1", failed, "never left", ContentKind.TEXT, sentAt = 1000, sender = SELF_UID, sendState = SendState.FAILED),
            msg("f-2", failed, "went out fine", ContentKind.TEXT, sentAt = 2000, sender = SELF_UID),
            msg("a-1", all, "@all hello", ContentKind.TEXT, sentAt = 1000, mentions = listOf(NeutralMention(MENTIONS_ALL_UID, 0, 4))),
            msg(
                "m-1", me, "@all @you", ContentKind.TEXT, sentAt = 1000,
                mentions = listOf(NeutralMention(MENTIONS_ALL_UID, 0, 4), NeutralMention(SELF_UID, 5, 4, type = 1)),
            ),
            NeutralRecord.Message(msg("c-1", alert, "urgent", ContentKind.TEXT, sentAt = 1000).value.copy(criticalAlertType = CRITICAL_ALERT_TYPE_ALERT)),
            msg("q-1", quiet, "all good", ContentKind.TEXT, sentAt = 1000),
        )

        val target = roundTrip(source)
        fun room(id: String) = target.rooms.first { it.roomId == id }

        assertEquals(ROOM_SEND_STATUS_FAILED, room(failed).sendStatus, "one failed outgoing row tags the room; the later success does not mask it")
        assertEquals(ROOM_SEND_STATUS_NONE, room(quiet).sendStatus, "no failed row, no tag")
        assertEquals(MENTIONS_TYPE_ALL, room(all).mentionType, "unread @all → [@All]")
        assertEquals(MENTIONS_TYPE_ME, room(me).mentionType, "@you wins over @all")
        assertEquals(MENTIONS_TYPE_NONE, room(quiet).mentionType, "unread without mentions → no tag")
        assertEquals(CRITICAL_ALERT_TYPE_ALERT, room(alert).criticalAlertType, "unread alert row → critical-alert tag")
        assertEquals(CRITICAL_ALERT_TYPE_NONE, room(quiet).criticalAlertType, "no alert row, no tag")
        // Wire type carried (1 = EXTERNAL), absent read as INTERNAL (0) — never a value outside the proto enum.
        assertEquals(listOf(0, 0, 1), target.mentions.filter { it.messageId in setOf("a-1", "m-1") }.map { it.type }.sorted())
    }

    // ── sendState → sendType (send-failed-tag regression guard) ──────────────────────────────

    /**
     * Every conversation migrated from Mac came in tagged "send failed". `sendState` is OUTGOING-only,
     * and the importer read its absence on an INCOMING message as a zero value — Android's
     * `SendType.Sending(0)`. The cold-start sweep then flips every lingering `sendType == 0` real
     * message to FAILED, and one such row tags its whole room (`RoomSendStatusQueries`). Android-to-
     * Android never showed it: the exporter mislabelled its own received rows as OUTGOING, so they
     * came back as Sent.
     */
    @Test
    fun `incoming messages import as sent so no conversation earns a send-failed tag`() = runTest {
        val conv = "room-sendtype"
        val source = listOf(
            msg("m-in", conv, "received", ContentKind.TEXT, sentAt = 1000, sender = "u-alice"),
            msg("m-out-ok", conv, "sent fine", ContentKind.TEXT, sentAt = 2000, sender = SELF_UID),
            msg("m-out-failed", conv, "never left", ContentKind.TEXT, sentAt = 3000, sender = SELF_UID, sendState = SendState.FAILED),
        )

        val target = roundTrip(source)

        fun sendTypeOf(id: String) = target.messages.first { it.id == id }.sendType
        assertEquals(SendType.Sent.rawValue, sendTypeOf("m-in"), "INCOMING lands in the terminal received state, never Sending(0)")
        assertEquals(SendType.Sent.rawValue, sendTypeOf("m-out-ok"), "OUTGOING with no sendState means sent")
        assertEquals(SendType.SentFailed.rawValue, sendTypeOf("m-out-failed"), "only an explicit FAILED survives as a failure")
        assertTrue(target.messages.none { it.sendType == SendType.Sending.rawValue }, "no row is left mid-send for the sweep to flip")
    }

    // ── Per-message roomType round-trips (group-empty regression guard) ───────────────────────

    @Test
    fun `per-message roomType round-trips so a group message imports with roomType 1`() = runTest {
        val group = "group-rt"
        val single = "single-rt"
        // A GROUP message (roomType=1) and a single-chat message (roomType=0). The chat message-query
        // filters on roomType ALONGSIDE roomId; if the group message imports at the default 0 it never
        // matches the group (roomType==1) query and the group conversation opens empty.
        val groupMsg = NeutralRecord.Message(
            NeutralMessage(
                stableId = "m-group", conversationRef = group, roomType = 1, sender = "u-bob",
                direction = MessageDirection.INCOMING, body = "hello group",
                sentAt = 1000, serverAt = 1000, receivedAt = 1000, expireTimer = 0,
                contentKind = ContentKind.TEXT, systemType = null, confidential = false,
                // An explicit exception anchor, which the ladder expansion must leave untouched.
                expireStartedAt = 1010,
            )
        )
        val singleMsg = NeutralRecord.Message(
            NeutralMessage(
                stableId = "m-single", conversationRef = single, roomType = 0, sender = "u-alice",
                direction = MessageDirection.INCOMING, body = "hello single",
                sentAt = 2000, serverAt = 2000, receivedAt = 2000, expireTimer = 0,
                contentKind = ContentKind.TEXT, systemType = null, confidential = false,
                // An explicit exception anchor, which the ladder expansion must leave untouched.
                expireStartedAt = 2010,
            )
        )
        val source = listOf(
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = group, roomType = 1)),
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = single, roomType = 0)),
            groupMsg,
            singleMsg,
        )

        val target = roundTrip(source)

        // Regression guard: the imported MessageModel.roomType MUST match the source per message, so
        // the group conversation query (roomType==1 AND roomId==group) finds the group message.
        val importedGroupMsg = target.messages.first { it.id == "m-group" }
        assertEquals(1, importedGroupMsg.roomType, "group message imports with roomType=1 (else group opens empty)")
        val importedSingleMsg = target.messages.first { it.id == "m-single" }
        assertEquals(0, importedSingleMsg.roomType, "single-chat message imports with roomType=0")

        // Field-by-field lossless (now includes roomType in the message diff).
        val imported = TargetReProjector.reProject(target, SELF_UID)
        val result = RoundTripVerifier.verify(source, imported)
        assertTrue(result.pass, "round-trip not lossless:\n${result.report()}")
    }

    // ── Fix B — shared-contact (名片) content round-trips ─────────────────────────────────────

    @Test
    fun `message with shared contacts round-trips name and phones`() = runTest {
        val conv = "room-card"
        val card = NeutralRecord.Message(
            NeutralMessage(
                stableId = "m-card", conversationRef = conv, sender = "u-alice",                 direction = MessageDirection.INCOMING, body = null, sentAt = 1000,
                serverAt = 1010, receivedAt = 1020, expireTimer = 0,
                contentKind = ContentKind.TEXT, systemType = null, confidential = false,
                sharedContacts = listOf(
                    NeutralSharedContact(
                        givenName = "Jane", familyName = "Doe", displayName = "Jane Doe",
                        phones = listOf(
                            NeutralSharedContactPhone(value = "+12025550111", type = 1, label = "mobile"),
                            NeutralSharedContactPhone(value = "+12025550112", type = 2, label = "work"),
                        ),
                    ),
                ),
                // An explicit exception anchor, which the ladder expansion must leave untouched.
                expireStartedAt = 1030,
            )
        )
        val source = listOf(
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = conv)),
            card,
        )

        val target = roundTrip(source)

        // The card content landed in the shared-contact tables, FK'd by messageId + the contact PK.
        val sc = target.sharedContacts.first { it.messageId == "m-card" }
        assertEquals("Jane", sc.givenName)
        assertEquals("Doe", sc.familyName)
        assertEquals("Jane Doe", sc.displayName)
        val phones = target.sharedContactPhones.filter { it.sharedContactDatabaseId == sc.databaseId.toLong() }
        assertEquals(2, phones.size, "both phones FK'd to the contact's target-local PK")
        assertEquals(setOf("+12025550111", "+12025550112"), phones.map { it.phoneNumber }.toSet())
        assertEquals(setOf(1, 2), phones.map { it.phoneNumberType }.toSet())

        // Field-by-field round-trip: the re-projected card == the source card (name + phones lossless).
        val imported = TargetReProjector.reProject(target, SELF_UID)
        val reCard = imported.filterIsInstance<NeutralRecord.Message>().first { it.value.stableId == "m-card" }.value
        assertEquals(1, reCard.sharedContacts.size, "one shared contact round-trips")
        val reSc = reCard.sharedContacts.single()
        assertEquals("Jane", reSc.givenName)
        assertEquals("Jane Doe", reSc.displayName)
        assertEquals(
            setOf(
                NeutralSharedContactPhone(value = "+12025550111", type = 1, label = "mobile"),
                NeutralSharedContactPhone(value = "+12025550112", type = 2, label = "work"),
            ),
            reSc.phones.toSet(),
            "phones round-trip (value + type + label)",
        )

        // The conversation-list preview for a card message is the canonical "[Contact Card]" label, NOT
        // the (null) body — proves the recompute reuses previewContent's contact-card branch.
        val cardRoom = target.rooms.first { it.roomId == conv }
        assertEquals(FakePreviewStrings.contactCard(), cardRoom.lastDisplayContent, "card preview = contact-card label")
    }

    // ── Migration v2 — others' read positions (read_info) round-trip ─────────────────────────

    @Test
    fun `conversation with participant read positions round-trips own and others independently`() = runTest {
        val conv = "room-readby"
        // A conversation that carries BOTH the migrating account's OWN read watermark (RoomModel
        // .readPosition) AND two OTHER participants' read positions (read_info). The own watermark and
        // the others' watermarks must restore INDEPENDENTLY (own → RoomModel.readPosition; others →
        // read_info rows) so the OUTGOING "read by" indicator renders after restore.
        val source = listOf(
            NeutralRecord.ConversationLocal(
                NeutralConversationLocal(
                    conversationRef = conv,
                    roomType = 1,
                    readPosition = NeutralReadPosition(readAt = 0L, maxServerTime = 5000L, maxNotifySequenceId = 0L, maxSequenceId = 0L),
                    participantReadPositions = listOf(
                        NeutralParticipantReadPosition(
                            uid = "u-bob",
                            readPosition = NeutralReadPosition(readAt = 0L, maxServerTime = 4200L, maxNotifySequenceId = 0L, maxSequenceId = 0L),
                        ),
                        NeutralParticipantReadPosition(
                            uid = "u-carol",
                            readPosition = NeutralReadPosition(readAt = 0L, maxServerTime = 4800L, maxNotifySequenceId = 0L, maxSequenceId = 0L),
                        ),
                    ),
                )
            ),
        )

        val target = roundTrip(source)

        // Own read position restored to RoomModel.readPosition (unchanged behavior, independent of others).
        val room = target.rooms.first { it.roomId == conv }
        assertEquals(5000L, room.readPosition, "own read watermark restored to RoomModel.readPosition")

        // Others' read positions landed as read_info rows, keyed {roomId, uid}, with the right maxServerTime.
        val rows = target.readInfos.filter { it.roomId == conv }
        assertEquals(2, rows.size, "both participant read positions persisted as read_info rows")
        assertEquals(setOf("u-bob", "u-carol"), rows.map { it.uid }.toSet(), "both participant uids present")
        assertEquals(4200L, rows.first { it.uid == "u-bob" }.readPosition, "u-bob watermark = maxServerTime")
        assertEquals(4800L, rows.first { it.uid == "u-carol" }.readPosition, "u-carol watermark = maxServerTime")

        // Field-by-field round-trip: participantReadPositions (uid + maxServerTime) survive, AND own
        // readPosition is still independently round-tripped on the conversation record.
        val imported = TargetReProjector.reProject(target, SELF_UID)
        val result = RoundTripVerifier.verify(source, imported)
        assertTrue(result.pass, "participant read positions round-trip not lossless:\n${result.report()}")

        val reConv = imported.filterIsInstance<NeutralRecord.ConversationLocal>().first { it.value.conversationRef == conv }.value
        assertEquals(
            NeutralReadPosition(readAt = 0L, maxServerTime = 5000L, maxNotifySequenceId = 0L, maxSequenceId = 0L),
            reConv.readPosition,
            "own readPosition round-trips independently",
        )
        assertEquals(
            setOf(
                NeutralParticipantReadPosition("u-bob", NeutralReadPosition(0L, 4200L, 0L, 0L)),
                NeutralParticipantReadPosition("u-carol", NeutralReadPosition(0L, 4800L, 0L, 0L)),
            ),
            reConv.participantReadPositions.toSet(),
            "others' read positions round-trip (uid + maxServerTime)",
        )
    }

    // ── contract §8.4 — identity continuity: the identity record round-trips to the sink ──────

    /**
     * A fully-populated identity, including the fields whose LOSS is silent and unrecoverable:
     * the old key pair + its generation time (the old-key decrypt fallback is gated on that
     * timestamp — a 0 disables the fallback entirely) and the favourites key triple.
     */
    private fun fullIdentity() = NeutralIdentity(
        aciPublicKey = "cHVibGljLWtleS1iNjQ=",
        aciPrivateKey = "cHJpdmF0ZS1rZXktYjY0",
        oldAciPublicKey = "b2xkLXB1YmxpYy1iNjQ=",
        oldAciPrivateKey = "b2xkLXByaXZhdGUtYjY0",
        identityKeyGenTime = 1_733_000_000_000L,
        favKey = "ZmF2LWtleS1iNjQ=",
        favKeyId = "fav-fingerprint-1",
        favKeyVersion = 7,
        proxyShareLink = "ytp://config?d=abc",
        proxyEnabled = true,
        proxyProtectCallIp = true,
    )

    @Test
    fun `identity record round-trips field-for-field into the identity sink and never into the DB`() = runTest {
        val identity = fullIdentity()
        val conv = "room-identity"
        // Identity is emitted FIRST (immediately after the manifest), exactly as both export call
        // sites prepend it, so the importer sees it before any bulk data.
        val source = listOf<NeutralRecord>(
            NeutralRecord.Identity(identity),
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = conv)),
            msg("m-1", conv, body = "hello", kind = ContentKind.TEXT, sentAt = 1000),
        )

        val container = File(tmp.root, "identity.ttmig")
        writer.write(source.asFlow(), FileArchiveSink(container), passphrase, byteSource(), sourceAccount, sourceAppVersion)

        val target = InMemoryWcdbWriteTarget()
        val captured = ArrayList<NeutralIdentity>()
        val importer = NeutralImporter(
            target,
            messageAttachmentDir = ::fakeAttachmentDir,
            previewStrings = FakePreviewStrings,
            identitySink = { captured.add(it) },
        )
        reader.read(container, passphrase, discardTarget()) { importer.onRecord(it) }
        importer.finish()

        // Every field survives VERBATIM — data-class equality pins all 14, so a future field added
        // without a codec branch fails here rather than silently traveling as its default.
        assertEquals(listOf(identity), captured, "identity round-trips field-for-field into the sink")
        // The nullable slots must round-trip as null, not as "" / empty collections.
        assertEquals(null, captured.single().recoverySlotKeks, "absent recovery slot KEKs stay null")
        assertEquals(null, captured.single().recoveryStaleSlots, "absent stale-slot list stays null")
        // …and it is NOT a DB entity: nothing about it may reach the staging tables.
        assertEquals(1, target.messages.size, "only the real DB record was written")
        assertTrue(target.rooms.any { it.roomId == conv }, "the conversation still imported normally")
    }

    @Test
    fun `a container without an identity record leaves the sink untouched and imports normally`() = runTest {
        val conv = "room-no-identity"
        val source = listOf<NeutralRecord>(
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = conv)),
            msg("m-1", conv, body = "hello", kind = ContentKind.TEXT, sentAt = 1000),
        )

        val container = File(tmp.root, "no-identity.ttmig")
        writer.write(source.asFlow(), FileArchiveSink(container), passphrase, byteSource(), sourceAccount, sourceAppVersion)

        val target = InMemoryWcdbWriteTarget()
        val captured = ArrayList<NeutralIdentity>()
        val importer = NeutralImporter(
            target,
            messageAttachmentDir = ::fakeAttachmentDir,
            previewStrings = FakePreviewStrings,
            identitySink = { captured.add(it) },
        )
        reader.read(container, passphrase, discardTarget()) { importer.onRecord(it) }
        importer.finish()

        assertTrue(captured.isEmpty(), "no identity record → the sink never fires (pre-1.1 container)")
        assertEquals(1, target.messages.size, "the rest of the import is unaffected")
    }

    @Test
    fun `identity with only the required pair round-trips with defaulted optional slots`() = runTest {
        // The minimum a source device can carry: the ACI pair only (no rotation in flight, no favKey,
        // no proxy). Defaults must survive rather than turning into nulls/garbage.
        val minimal = NeutralIdentity(aciPublicKey = "cHViLW9ubHk=", aciPrivateKey = "cHJpdi1vbmx5")
        val source = listOf<NeutralRecord>(NeutralRecord.Identity(minimal))

        val container = File(tmp.root, "identity-minimal.ttmig")
        writer.write(source.asFlow(), FileArchiveSink(container), passphrase, byteSource(), sourceAccount, sourceAppVersion)

        val captured = ArrayList<NeutralIdentity>()
        val importer = NeutralImporter(
            InMemoryWcdbWriteTarget(),
            messageAttachmentDir = ::fakeAttachmentDir,
            previewStrings = FakePreviewStrings,
            identitySink = { captured.add(it) },
        )
        reader.read(container, passphrase, discardTarget()) { importer.onRecord(it) }
        importer.finish()

        assertEquals(listOf(minimal), captured, "minimal identity round-trips with its defaults intact")
        assertEquals(0L, captured.single().identityKeyGenTime, "an absent genTime stays 0, not `now`")
    }

    // ── attachment fileName: never blank, never a name the disk would rewrite ─────────────

    @Test
    fun `an attachment without a usable fileName is stored under the live receive path's fallback name`() = runTest {
        val conv = "room-voice-names"
        fun voice(id: String, msgId: String, fileName: String?, serverId: Long?) = NeutralRecord.Attachment(
            NeutralAttachment(
                stableId = id, messageRef = msgId, contentType = "audio/mp3", size = 10, width = null, height = null,
                durationMs = 4200, flags = 1, fileName = fileName, serverAttachmentId = serverId,
                encryptionKey = "a2V5", digestBase64 = "ZGlnZXN0", pointerOnly = true,
            )
        )
        val source = listOf<NeutralRecord>(
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = conv)),
            // Desktop voice notes: no fileName at all → the server attachment id, as a live receive does.
            msg("m-v1", conv, body = null, kind = ContentKind.FILE, sentAt = 1000, attachmentRefs = listOf("att-noname")),
            voice("att-noname", "m-v1", fileName = null, serverId = 5550001L),
            // A name the disk would rewrite (':' is not a usable path component) → same fallback, so the
            // row and the file agree; no server id → the portable id.
            msg("m-v2", conv, body = null, kind = ContentKind.FILE, sentAt = 2000, attachmentRefs = listOf("att-badname")),
            voice("att-badname", "m-v2", fileName = "14:26:17.mp3", serverId = null),
            // A usable carried name survives verbatim.
            msg("m-v3", conv, body = null, kind = ContentKind.FILE, sentAt = 3000, attachmentRefs = listOf("att-named")),
            voice("att-named", "m-v3", fileName = "voice note.mp3", serverId = 5550003L),
        )

        val target = roundTrip(source)

        assertEquals("5550001", target.attachments.first { it.id == "att-noname" }.fileName)
        assertEquals("att-badname", target.attachments.first { it.id == "att-badname" }.fileName)
        assertEquals("voice note.mp3", target.attachments.first { it.id == "att-named" }.fileName)
        // The duration travels as carried — the bubble shows it even before a waveform exists.
        assertEquals(4200L, target.attachments.first { it.id == "att-noname" }.totalTime)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    /**
     * A SYSTEM-message-free [msg] variant whose serverAt == sentAt, so the imported
     * `systemShowTimestamp` (the sort/active-time authority) equals [sentAt] exactly — keeps the Fix A
     * lastActiveTime assertions readable. (The default [msg] sets serverAt = sentAt+10.)
     */
    private fun msgTs(id: String, conv: String, body: String, sentAt: Long, sender: String): NeutralRecord.Message =
        NeutralRecord.Message(
            NeutralMessage(
                stableId = id, conversationRef = conv, sender = sender,                 direction = if (sender == "u-self") MessageDirection.OUTGOING else MessageDirection.INCOMING,
                body = body, sentAt = sentAt, serverAt = sentAt, receivedAt = sentAt, expireTimer = 0,
                contentKind = ContentKind.TEXT, systemType = null, confidential = false,
            )
        )

    /** Deterministic fake preview strings (Fix A): tests run off-device where `ResUtils` is uninitialized. */
    private object FakePreviewStrings : RoomStateRecomputer.PreviewStrings {
        override fun unsupported() = "[Unsupported]"
        override fun confidential() = "[Message]"
        override fun image() = "[Image]"
        override fun video() = "[Video]"
        override fun audio() = "[Audio]"
        override fun attachment() = "[Attachment]"
        override fun contactCard() = "[Contact Card]"
        override fun chatHistory() = "Chat History"
    }

    /**
     * Byte target mirroring the production [RestoreOrchestrator] one: write each attachment's bytes
     * into the canonical `<ownerKey>/<fileName>` subtree the [importer] resolved for the stableId
     * (the attachment record precedes its byte frame), falling back to a flat write when no placement
     * is known. Records the actual destination path per ref for the byte-integrity assertions.
     */
    private fun fileTarget(
        dir: File,
        paths: MutableMap<String, String>,
        importer: NeutralImporter,
    ): AttachmentByteTarget =
        AttachmentByteTarget { ref, _ ->
            val placement = importer.placementFor(ref)
            val f = if (placement != null) {
                File(dir, placement.ownerKey).apply { mkdirs() }.let { File(it, placement.fileName) }
            } else {
                File(dir, ref)
            }
            paths[ref] = f.absolutePath
            f.outputStream()
        }

    private fun discardTarget(): AttachmentByteTarget = AttachmentByteTarget { _, _ -> null }

    /**
     * Test double for [com.difft.android.base.utils.FileUtil.getMessageAttachmentFilePath]: returns
     * the canonical attachment DIRECTORY path `.../attachment/<ownerKey>/` (trailing separator, the
     * caller appends fileName) WITHOUT touching `FilePathManager`/`ApplicationHelper` (whose
     * `getExternalFilesDir` chain is unavailable off-device). The base prefix is arbitrary — the
     * assertions compare the stored path against THIS same function, so only the shape matters.
     */
    private fun fakeAttachmentDir(ownerKey: String): String =
        "/data/files/attachment/$ownerKey/"

    private fun assertContentHashEquals(expected: ByteArray, actual: File) {
        assertTrue(actual.exists(), "restored attachment file missing: ${actual.name}")
        assertEquals(sha256(expected), sha256(actual.readBytes()), "attachment bytes differ for ${actual.name}")
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

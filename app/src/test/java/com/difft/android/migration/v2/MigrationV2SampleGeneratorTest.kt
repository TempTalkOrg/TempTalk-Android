package com.difft.android.migration.v2

import android.app.Application
import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.v2.container.AttachmentByteSource
import com.difft.android.migration.v2.container.AttachmentByteTarget
import com.difft.android.migration.v2.container.AttachmentBytes
import com.difft.android.migration.v2.container.ContainerFormat
import com.difft.android.migration.v2.container.ContainerManifest
import com.difft.android.migration.v2.container.ContainerReaderImpl
import com.difft.android.migration.v2.container.ContainerWriterImpl
import com.difft.android.migration.v2.container.FileArchiveSink
import com.difft.android.migration.v2.container.RecordJsonCodec
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralForward
import com.difft.android.migration.v2.model.NeutralForwardContext
import com.difft.android.migration.v2.model.NeutralGroupKey
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralQuote
import com.difft.android.migration.v2.model.NeutralReadPosition
import com.difft.android.migration.v2.model.NeutralRecord
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue








@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class MigrationV2SampleGeneratorTest {

    private val crypto = BackupCryptoImpl()
    private val writer = ContainerWriterImpl(crypto)
    private val reader = ContainerReaderImpl(crypto)

    
    private val passphrase = "MIGRATION-V2-MINPOC-SAMPLE-PASSPHRASE-DONOTUSEINPROD-0001"

    private val conv = "room-cross-end-sample"

    




    private val attachmentPngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAhElEQVR42g3I0QAEMRAD0CAswiAsQhCKEIQiBKEI" +
            "QViEQTiEQbm+zwcAhYco4TUYrIYG+/ZTVaxXRddKqWtPGeDtl6S4TIW76eEB9JZuL0nWjtw6owBmedG3t+34tDP" +
            "+gKyKmK3cPkk636SBVvVmW33ct7/unv4Bs2vMOZp4vszt38z8Ae4/VAGkqKnOAAAAAElFTkSuQmCC"

    private val attachmentBytes: ByteArray = Base64.getDecoder().decode(attachmentPngBase64)
    private val attachmentRef = "att-img-0001"

    @Test
    fun `generate cross-end sample ttmig and expected-manifest`() = runTest {
        val records = buildSyntheticRecords()

        val outDir = sampleOutputDir()
        outDir.mkdirs()
        val sampleFile = File(outDir, "sample.ttmig")
        val manifestFile = File(outDir, "expected-manifest.md")

        
        val manifest = writer.write(
            records = records.asFlow(),
            sink = FileArchiveSink(sampleFile),
            passphrase = passphrase,
            attachmentBytes = byteSource(),
            sourceAccount = SAMPLE_SOURCE_ACCOUNT,
            sourceAppVersion = SAMPLE_SOURCE_APP_VERSION,
        )

        
        val readBack = roundTripAndVerify(sampleFile)

        assertEquals(manifest.recordCount, readBack.recordCount, "record count must round-trip")
        assertEquals(manifest.attachmentCount, readBack.attachmentCount, "attachment count must round-trip")
        assertEquals(EXPECTED_RECORD_COUNT, readBack.recordCount, "fixed record count must be stable")
        assertEquals(EXPECTED_ATTACHMENT_COUNT, readBack.attachmentCount, "fixed attachment count must be stable")

        
        assertEquals(SAMPLE_SOURCE_ACCOUNT, manifest.sourceAccount, "writer recorded sourceAccount")
        assertEquals("android", manifest.sourcePlatform, "writer recorded sourcePlatform constant")
        assertEquals(SAMPLE_SOURCE_APP_VERSION, manifest.sourceAppVersion, "writer recorded sourceAppVersion")
        assertEquals(ContainerFormat.FORMAT_VERSION, manifest.formatVersion, "writer recorded formatVersion constant")
        assertEquals(manifest.sourceAccount, readBack.sourceAccount, "sourceAccount round-trips through reader")
        assertEquals(manifest.sourcePlatform, readBack.sourcePlatform, "sourcePlatform round-trips through reader")
        assertEquals(manifest.sourceAppVersion, readBack.sourceAppVersion, "sourceAppVersion round-trips through reader")
        assertEquals(manifest.formatVersion, readBack.formatVersion, "formatVersion round-trips through reader")

        val attachmentSha = sha256Hex(attachmentBytes)

        
        
        
        val envelopeJson = captureEnvelopeJson(records)

        
        manifestFile.writeText(buildExpectedManifestMarkdown(manifest, attachmentSha, envelopeJson))

        assertTrue(sampleFile.exists() && sampleFile.length() > 0, "sample.ttmig must exist and be non-empty")
        assertTrue(manifestFile.exists() && manifestFile.length() > 0, "expected-manifest.md must exist")

        
        println("[MigrationV2][Sample] sample.ttmig      -> ${sampleFile.absolutePath} (${sampleFile.length()} bytes)")
        println("[MigrationV2][Sample] expected-manifest -> ${manifestFile.absolutePath} (${manifestFile.length()} bytes)")
        println("[MigrationV2][Sample] attachment SHA-256 = $attachmentSha")
        println("[MigrationV2][Sample] passphrase         = $passphrase")
    }

    

    @Suppress("LongMethod")
    private fun buildSyntheticRecords(): List<NeutralRecord> {
        val out = ArrayList<NeutralRecord>()

        
        out += NeutralRecord.GroupKey(
            NeutralGroupKey(groupId = "gid-cross-end-0001", rGroup = "R0dST1VQS0VZTUFURVJJQUxfQjY0PT0=")
        )

        
        
        
        
        out += NeutralRecord.ConversationLocal(
            NeutralConversationLocal(
                conversationRef = conv,
                pinnedTime = 1_780_000_000_000L,
                readPosition = NeutralReadPosition(
                    readAt = 1_780_400_009_000L,
                    maxServerTime = 1_780_400_009_500L,
                    maxNotifySequenceId = 42L,
                    maxSequenceId = 17L,
                ),
                readPositions = listOf(
                    ladderRow(maxServerTime = 1_700_000_000_000L, readAt = 1_700_000_100_000L),
                    ladderRow(maxServerTime = 1_700_000_200_000L, readAt = 1_700_000_300_000L),
                ),
            )
        )

        
        out += textMsg("m-text-0001", body = "Cross-end sample message one", sentAt = 1_780_400_001_000L, serverAt = 1_780_400_001_100L)
        out += textMsg("m-text-0002", body = "Second message with emoji 👍 and unicode café", sentAt = 1_780_400_002_000L, serverAt = 1_780_400_002_100L)
        out += textMsg("m-text-0003", body = "Third message", sentAt = 1_780_400_003_000L, serverAt = 1_780_400_003_100L)
        
        out += textMsg("m-text-0004-newline", body = "hello\nworld", sentAt = 1_780_400_004_000L, serverAt = 1_780_400_004_100L)

        
        
        
        out += NeutralRecord.Message(
            baseMessage(
                stableId = "m-anchor-0008-not-started",
                body = "Read, but the expiry countdown has not started",
                sentAt = 1_780_400_006_500L,
                serverAt = 1_780_400_006_600L,
                expireStartedAt = 0L,
            )
        )
        
        
        out += NeutralRecord.Message(
            baseMessage(
                stableId = "m-anchor-0009-explicit",
                body = "Expiry anchored earlier than the ladder would derive",
                sentAt = 1_700_000_050_000L,
                serverAt = 1_700_000_060_000L,
                expireStartedAt = 1_700_000_999_000L,
            )
        )

        

        
        out += NeutralRecord.Message(
            baseMessage(
                stableId = "m-image-0005",
                body = null,
                sentAt = 1_780_400_005_000L,
                serverAt = 1_780_400_005_100L,
            ).copy(
                contentKind = ContentKind.FILE,
                attachmentRefs = listOf(attachmentRef),
            )
        )
        
        
        
        
        
        out += NeutralRecord.Attachment(
            NeutralAttachment(
                stableId = attachmentRef,
                messageRef = "m-image-0005",
                contentType = "image/png",
                size = attachmentBytes.size.toLong(),
                width = 8,
                height = 8,
                durationMs = null,
                flags = 0,
                fileName = "sample.png",
                serverAttachmentId = 5_000_000_005L,
                encryptionKey = "c2FtcGxlLWtleS0wMDA1",
                pointerOnly = false,
            )
        )

        
        out += NeutralRecord.Message(
            baseMessage(
                stableId = "m-quote-0006",
                body = "Replying to the first message",
                sentAt = 1_780_400_006_000L,
                serverAt = 1_780_400_006_100L,
            ).copy(
                
                
                
                quote = NeutralQuote(
                    author = "u-sender-0001",
                    sentAt = 1_780_400_001_000L,
                    textPreview = "Cross-end sample message one",
                    attachmentsMeta = emptyList(),
                ),
            )
        )

        
        
        out += NeutralRecord.Message(
            baseMessage(
                stableId = "m-forward-0007",
                body = null,
                sentAt = 1_780_400_007_000L,
                serverAt = 1_780_400_007_100L,
            ).copy(
                
                
                
                
                contentKind = ContentKind.FORWARD,
                forwardContext = NeutralForwardContext(
                    isFromGroup = true,
                    roots = listOf(
                        NeutralForward(
                            author = "u-sender-0002",
                            sentAt = 1_780_399_900_000L,
                            body = "Outer forwarded message",
                            attachmentsMeta = emptyList(),
                            nested = listOf(
                                NeutralForward(
                                    author = "u-sender-0003",
                                    sentAt = 1_780_399_800_000L,
                                    body = "Inner forwarded message (level 2)",
                                    attachmentsMeta = emptyList(),
                                    nested = emptyList(),
                                ),
                            ),
                        ),
                        NeutralForward(
                            author = "u-sender-0004",
                            sentAt = 1_780_399_700_000L,
                            body = "Second forwarded message (parallel root)",
                            attachmentsMeta = emptyList(),
                            nested = emptyList(),
                        ),
                    ),
                ),
            )
        )

        return out
    }

    private fun textMsg(stableId: String, body: String, sentAt: Long, serverAt: Long): NeutralRecord.Message =
        NeutralRecord.Message(baseMessage(stableId, body, sentAt, serverAt))

    
    private fun ladderRow(maxServerTime: Long, readAt: Long): NeutralReadPosition =
        NeutralReadPosition(readAt = readAt, maxServerTime = maxServerTime, maxNotifySequenceId = 0L, maxSequenceId = 0L)

    




    private fun baseMessage(
        stableId: String,
        body: String?,
        sentAt: Long,
        serverAt: Long,
        expireStartedAt: Long? = null,
    ): NeutralMessage =
        NeutralMessage(
            stableId = stableId,
            conversationRef = conv,
            sender = "u-sender-0001",
                        direction = MessageDirection.INCOMING,
            body = body,
            sentAt = sentAt,
            serverAt = serverAt,
            receivedAt = serverAt,
            expireTimer = 0,
            expireStartedAt = expireStartedAt,
            contentKind = ContentKind.TEXT,
            systemType = null,
            confidential = false,
        )

    

    private suspend fun roundTripAndVerify(sampleFile: File): ContainerManifest {
        val recovered = ByteArrayOutputStream()
        val readManifest = reader.read(
            file = sampleFile,
            passphrase = passphrase,
            attachmentTarget = AttachmentByteTarget { ref, _ ->
                assertEquals(attachmentRef, ref, "only one attachment ref expected")
                recovered
            },
            onRecord = {  },
        )
        assertEquals(
            sha256Hex(attachmentBytes),
            sha256Hex(recovered.toByteArray()),
            "round-tripped attachment bytes SHA-256 must match the source bytes",
        )
        return readManifest
    }

    private fun byteSource(): AttachmentByteSource = AttachmentByteSource { ref ->
        if (ref == attachmentRef) {
            AttachmentBytes(attachmentBytes.size.toLong(), ByteArrayInputStream(attachmentBytes))
        } else {
            null
        }
    }

    




    private fun captureEnvelopeJson(records: List<NeutralRecord>): Map<String, String> {
        val codec = RecordJsonCodec()
        return records.associate { record ->
            val key = when (record) {
                is NeutralRecord.Message -> record.value.stableId
                is NeutralRecord.Attachment -> record.value.stableId
                is NeutralRecord.ConversationLocal -> record.value.conversationRef
                is NeutralRecord.GroupKey -> record.value.groupId
                is NeutralRecord.Identity -> "identity"
                is NeutralRecord.Credential -> "credential"
                is NeutralRecord.PendingEnvelope -> record.value.messageId
            }
            key to codec.encodeRecord(record)
        }
    }

    

    @Suppress("LongMethod")
    private fun buildExpectedManifestMarkdown(
        manifest: ContainerManifest,
        attachmentSha: String,
        envelopeJson: Map<String, String>,
    ): String = buildString {
        appendLine("# Migration v2 — Cross-End Sample: Expected Manifest")
        appendLine()
        appendLine("> Generated by `MigrationV2SampleGeneratorTest`. SYNTHETIC data only — no real user data / PII.")
        appendLine("> Pair this with `sample.ttmig` and `cross-end-minpoc-guide.md` for the iOS / Desktop reader POC.")
        appendLine()
        appendLine("## Decryption parameters")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| Passphrase (plaintext, POC) | `$passphrase` |")
        appendLine("| KDF | PBKDF2-HMAC-SHA512, 250000 iterations, 64-byte output (32 encKey ‖ 32 macKey) |")
        appendLine("| Cipher | AES-256-CTR / NoPadding |")
        appendLine("| Integrity | HMAC-SHA256 over (header ‖ ciphertext), trailing 32 bytes; verify BEFORE decrypt |")
        appendLine("| Header | 42 bytes: MAGIC `TTBK` ‖ VERSION 0x0001 ‖ SALT(16) ‖ KDF_ITER(4 BE=250000) ‖ IV(16) |")
        appendLine("| SALT / IV | random per export (read from header — NOT fixed) |")
        appendLine()
        appendLine("## Body format")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| bodyFormatVersion | ${manifest.bodyFormatVersion} |")
        appendLine("| encoding | `${manifest.encoding}` |")
        appendLine("| recordCount | ${manifest.recordCount} |")
        appendLine("| attachmentCount | ${manifest.attachmentCount} |")
        appendLine("| sourceAccount | `${manifest.sourceAccount}` (SYNTHETIC; real export = current account, masked per `L`) |")
        appendLine("| sourcePlatform | `${manifest.sourcePlatform}` |")
        appendLine("| sourceAppVersion | `${manifest.sourceAppVersion}` (SYNTHETIC; real export = `BuildConfig` version) |")
        appendLine("| formatVersion | `${manifest.formatVersion}` (semantic MAJOR.MINOR; compatibility-judgement field) |")
        appendLine()
        appendLine("> The 4 identity fields (`sourceAccount` / `sourcePlatform` / `sourceAppVersion` / `formatVersion`) are written into the FIRST manifest frame with real values up-front. Importers use them for the account-match check, cross-platform UX, upgrade prompts, and compatibility judgement (verify order: header → HMAC → decrypt → manifest version → `sourceAccount` match → import). Manifest keys are additive-only — ignore unknown keys.")
        appendLine()
        appendLine("Frame layout (inside the decrypted body): `[TAG(1)][LEN][PAYLOAD]`.")
        appendLine("- `0x01` MANIFEST (first frame, one) — UTF-8 JSON, 4-byte BE length.")
        appendLine("- `0x02` RECORD — one UTF-8 JSONL line, 4-byte BE length.")
        appendLine("- `0x03` ATTACHMENT — raw bytes, **8-byte BE length** (not 4).")
        appendLine("- `0x7F` END — zero-length sentinel.")
        appendLine()
        appendLine("Each RECORD line is a tagged ENVELOPE — **NOT a flat object**: `{\"type\":\"<tag>\",\"value\":{…entity…}}`.")
        appendLine("The entity fields live under `value`, not at the top level. Route by `type`, then parse `value`.")
        appendLine("Tags: `message` / `attachment` / `conversationLocal` / `groupKey`. A reader MUST skip an unrecognized tag and keep going rather than fail the import.")
        appendLine("Nulls are serialized explicitly (Gson `serializeNulls`), so optional fields appear as explicit `null`.")
        appendLine()
        appendLine("### Real envelope JSON examples (captured verbatim from the codec for THIS sample)")
        appendLine()
        appendLine("These are the exact JSONL lines emitted into the `0x02` RECORD frames (one line each).")
        appendLine("Parse the byte-for-byte shape below; do not assume a flat layout.")
        appendLine()
        appendLine("**message (text, with the `\\n` newline body):**")
        appendLine("```json")
        appendLine(envelopeJson["m-text-0004-newline"])
        appendLine("```")
        appendLine()
        appendLine("**message (image; contentKind=FILE, names the attachment ref):**")
        appendLine("```json")
        appendLine(envelopeJson["m-image-0005"])
        appendLine("```")
        appendLine()
        appendLine("**message (quote):**")
        appendLine("```json")
        appendLine(envelopeJson["m-quote-0006"])
        appendLine("```")
        appendLine()
        appendLine("**message (combined-forward, 2 levels):**")
        appendLine("```json")
        appendLine(envelopeJson["m-forward-0007"])
        appendLine("```")
        appendLine()
        appendLine("**message (tri-state anchor, explicit 0 — note the key IS present with value 0, which is NOT the same as omitting it):**")
        appendLine("```json")
        appendLine(envelopeJson["m-anchor-0008-not-started"])
        appendLine("```")
        appendLine()
        appendLine("**message (tri-state anchor, explicit value that overrides the ladder):**")
        appendLine("```json")
        appendLine(envelopeJson["m-anchor-0009-explicit"])
        appendLine("```")
        appendLine()
        appendLine("**attachment (byte-carrying; pointerOnly=false):**")
        appendLine("```json")
        appendLine(envelopeJson[attachmentRef])
        appendLine("```")
        appendLine()
        appendLine("**conversationLocal:**")
        appendLine("```json")
        appendLine(envelopeJson[conv])
        appendLine("```")
        appendLine()
        appendLine("**groupKey:**")
        appendLine("```json")
        appendLine(envelopeJson["gid-cross-end-0001"])
        appendLine("```")
        appendLine()
        appendLine("## Messages (9 total)")
        appendLine()
        appendLine("All messages mirror `NeutralProjections.message`: `conversationRef` = `$conv`, `sender` = `u-sender-0001`, **`senderDevice` = null** (Android does not persist a source device id), `direction` = `INCOMING`, `expireTimer` = 0, `confidential` = false. Optional sub-structures absent unless noted; `expireStartedAt` absent unless the table says otherwise, in which case the expiry anchor is derived from the conversation's read ladder; `platformExtras` empty.")
        appendLine()
        appendLine("> `contentKind` is DERIVED on Android (`deriveContentKind`): attachments → `FILE` (IMAGE/VOICE refinement is a documented POC gap), forwards → `FORWARD`, system → `SYSTEM`, else `TEXT`. The image message below is therefore `FILE`, not `IMAGE` — the `image/png` contentType on its attachment is what marks it as an image.")
        appendLine()
        appendLine("| stableId | contentKind | body | sentAt | serverAt | quote? | forward? | attachments | expireStartedAt |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        appendLine("| `m-text-0001` | TEXT | `Cross-end sample message one` | 1780400001000 | 1780400001100 | no | no | — | — (absent) |")
        appendLine("| `m-text-0002` | TEXT | `Second message with emoji 👍 and unicode café` | 1780400002000 | 1780400002100 | no | no | — | — (absent) |")
        appendLine("| `m-text-0003` | TEXT | `Third message` | 1780400003000 | 1780400003100 | no | no | — | — (absent) |")
        appendLine("| `m-text-0004-newline` | TEXT | `hello\\nworld` (real newline → escaped `\\n` in JSONL) | 1780400004000 | 1780400004100 | no | no | — | — (absent) |")
        appendLine("| `m-image-0005` | **FILE** | (null) | 1780400005000 | 1780400005100 | no | no | `$attachmentRef` | — (absent) |")
        appendLine("| `m-quote-0006` | TEXT | `Replying to the first message` | 1780400006000 | 1780400006100 | **yes** | no | — | — (absent) |")
        appendLine("| `m-forward-0007` | FORWARD | (null) | 1780400007000 | 1780400007100 | no | **yes** | — | — (absent) |")
        appendLine("| `m-anchor-0008-not-started` | TEXT | `Read, but the expiry countdown has not started` | 1780400006500 | 1780400006600 | no | no | — | **0** (explicit) |")
        appendLine("| `m-anchor-0009-explicit` | TEXT | `Expiry anchored earlier than the ladder would derive` | 1700000050000 | 1700000060000 | no | no | — | **1700000999000** |")
        appendLine()
        appendLine("> `expireStartedAt` is TRI-STATE. **absent** = derive the anchor from the read ladder below (outgoing → its own `serverAt`; incoming at or below the watermark → the smallest `readAt` among covering rows, falling back to the watermark's effective `readAt`; above the watermark → unread, no anchor). **0** = the source says the countdown has explicitly NOT started, and the importer MUST persist that distinguishably from absent and never let the ladder override it. **> 0** = the anchor itself, which wins over the ladder. `m-anchor-0009-explicit` is covered by the second ladder row (which would derive `1700000300000`), so a reader that ignores the carried anchor produces a visibly different expiry.")
        appendLine()
        appendLine("### m-text-0004-newline body (newline case)")
        appendLine()
        appendLine("Raw body string (2 lines):")
        appendLine("```")
        appendLine("hello")
        appendLine("world")
        appendLine("```")
        appendLine("In the JSONL line this MUST appear as the 2-character escape `\\n` inside the quoted string — a reader splitting on real newlines would be wrong; split frames by LENGTH.")
        appendLine()
        appendLine("### m-quote-0006 quote")
        appendLine()
        appendLine("- `quote.author` = `u-sender-0001`")
        appendLine("- `quote.sentAt` = 1780400001000 (composite identity → points at `m-text-0001`)")
        appendLine("- `quote.textPreview` = `Cross-end sample message one`")
        appendLine()
        appendLine("### m-forward-0007 merged-forward (2 parallel roots)")
        appendLine()
        appendLine("Mirrors `NeutralProjections.forwardContext` / `forwardOf`: `forwardContext` = `{isFromGroup, roots}` where `roots` are the N PARALLEL top-level forwards (a 合并转发 of N messages). Each forward = `{author, sentAt, body, attachmentsMeta, nested}`; `attachmentsMeta` is `[]` at every level (the real POC projection does not fold preview attachment meta).")
        appendLine()
        appendLine("- `forwardContext.isFromGroup` = true")
        appendLine("- roots[0] level 1: `author` = `u-sender-0002`, `sentAt` = 1780399900000, `body` = `Outer forwarded message`, `attachmentsMeta` = [] (empty)")
        appendLine("  - level 2 (nested[0]): `author` = `u-sender-0003`, `sentAt` = 1780399800000, `body` = `Inner forwarded message (level 2)`, `attachmentsMeta` = [], `nested` = []")
        appendLine("- roots[1] level 1: `author` = `u-sender-0004`, `sentAt` = 1780399700000, `body` = `Second forwarded message (parallel root)`, `attachmentsMeta` = [], `nested` = []")
        appendLine()
        appendLine("## Attachment (1 total) — REAL image")
        appendLine()
        appendLine("Populated like `NeutralProjections.attachment(bytesPresent=true)`: the byte frame is named by `stableId`, `serverAttachmentId` + `encryptionKey` ALWAYS travel (attachment identity/credentials the destination reads unconditionally), `pointerOnly` = false (bytes are plaintext in the container).")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| stableId | `$attachmentRef` |")
        appendLine("| messageRef | `m-image-0005` |")
        appendLine("| contentType | `image/png` |")
        appendLine("| size | ${attachmentBytes.size} |")
        appendLine("| width × height | 8 × 8 |")
        appendLine("| durationMs | null |")
        appendLine("| isVoice | false |")
        appendLine("| fileName | `sample.png` |")
        appendLine("| serverAttachmentId | 5000000005 |")
        appendLine("| encryptionKey | `c2FtcGxlLWtleS0wMDA1` |")
        appendLine("| pointerOnly | false |")
        appendLine("| **byte SHA-256 (hex)** | `$attachmentSha` |")
        appendLine()
        appendLine("The attachment bytes are a **REAL, decodable PNG** (not a synthetic byte pattern): an 8×8, 8-bit RGB gradient (PNG signature ‖ IHDR ‖ IDAT ‖ IEND, valid CRCs; verified decodable by macOS Image I/O). Decode the `0x03` ATTACHMENT frame, write the bytes to a `.png`, and it MUST open in any image viewer AND its SHA-256 MUST equal the value above. This proves end-to-end image round-trip across platforms.")
        appendLine()
        appendLine("For independent regeneration, the exact PNG is also embedded as base64 (decode → ${attachmentBytes.size} bytes → SHA-256 above):")
        appendLine("```")
        appendLine(attachmentPngBase64)
        appendLine("```")
        appendLine()
        appendLine("## ConversationLocal (1 total)")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| conversationRef | `$conv` |")
        appendLine("| pinnedTime | 1780000000000 |")
        appendLine("| readPosition.readAt | 1780400009000 |")
        appendLine("| readPosition.maxServerTime | 1780400009500 |")
        appendLine("| readPosition.maxNotifySequenceId | 42 |")
        appendLine("| readPosition.maxSequenceId | 17 |")
        appendLine("| readPositions[0] | maxServerTime 1700000000000, readAt 1700000100000 |")
        appendLine("| readPositions[1] | maxServerTime 1700000200000, readAt 1700000300000 |")
        appendLine()
        appendLine("`readPosition` is the AUTHORITATIVE watermark: its `maxServerTime` decides read / unread and the unread count, and its `readAt` may be 0, in which case `maxServerTime` stands in as the effective read moment. `readPositions` is this account's read ladder, merged across its devices, in canonical form: `maxServerTime` strictly increasing, every `readAt > 0`, `readAt` non-decreasing, and the last row at or below the watermark. It must NOT contain other participants — those travel in `participantReadPositions`, which in turn must NOT contain this account. An empty `readPositions` means no ladder rows travelled, which is NOT the same as unread.")
        appendLine()
        appendLine("## GroupKey (1 total)")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| groupId | `gid-cross-end-0001` |")
        appendLine("| rGroup | `R0dST1VQS0VZTUFURVJJQUxfQjY0PT0=` |")
        appendLine()
        appendLine("## Pass criteria")
        appendLine()
        appendLine("Your reader passes M1 when: it verifies the HMAC, decrypts, parses ${manifest.recordCount} records + ${manifest.attachmentCount} attachment byte-frame, and the printed field values match this table — especially the 4 text-message bodies (incl. the `\\n` case), the quote/forward structure, the attachment byte SHA-256, the 2-row `readPositions` ladder, and the two tri-state `expireStartedAt` cases (explicit 0 vs explicit value).")
    }

    

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> String.format("%02x", byte.toInt() and 0xFF) }

    



    private fun sampleOutputDir(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var dir: File? = File(userDir).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle").exists() || File(dir, "settings.gradle.kts").exists()) {
                return File(dir, "tmp/migration-v2-poc/sample")
            }
            dir = dir.parentFile
        }
        
        return File("tmp/migration-v2-poc/sample").absoluteFile
    }

    private companion object {
        const val EXPECTED_RECORD_COUNT = 12 
        const val EXPECTED_ATTACHMENT_COUNT = 1

        
        const val SAMPLE_SOURCE_ACCOUNT = "u_sample"
        const val SAMPLE_SOURCE_APP_VERSION = "0.0.0-sample"
    }
}

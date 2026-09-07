package com.difft.android.chat.media

import android.content.Context
import android.net.Uri
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.application
import java.io.File
import java.nio.charset.Charset

/**
 * Single source of truth for reading message attachments that are stored **encrypted at rest**
 * (`<basePath>.encrypt`, format `[IV16][AES-CBC ciphertext][HMAC32]`).
 *
 * **Every** attachment type is kept encrypted at rest — images, video, voice, audio files, long
 * text, and generic files (documents / archives / apk / octet-stream) — so no plaintext attachment
 * ever touches disk. Each type reads its ciphertext on demand: images and generic files through the
 * decrypting `content://` uri (whose seekable proxy fd is also how video is played and seeked), long
 * text read fully into memory, voice and audio files decrypted to memory bytes for playback.
 *
 * A plaintext file at `basePath` can therefore only be legacy data, written before that became
 * uniform. The read paths below stay backward compatible with it, and
 * [LegacyPlaintextAttachmentMigration] re-encrypts it in place — narrowing which types are kept
 * encrypted would require bumping that migration's version.
 *
 * Reading is funnelled through [EncryptedAttachmentProvider] so that callers obtain a `content://`
 * [Uri] whose bytes are decrypted lazily on a background thread.
 */
object EncryptedAttachmentAccess {

    /** ContentProvider authority. Must match the `android:authorities` in the merged manifest. */
    val authority: String get() = application.packageName + ".encryptedattachment"

    private const val PATH_PREFIX = "m"

    private const val IV_SIZE = 16
    private const val MAC_SIZE = 32

    /** The on-disk ciphertext file for a given plaintext base path. */
    fun encryptedFile(basePath: String): File = File("$basePath.encrypt")

    /** Legacy plaintext file (may still exist for attachments downloaded before the migration). */
    fun plaintextFile(basePath: String): File = File(basePath)

    /**
     * True when the ciphertext file is present and **structurally complete**.
     *
     * Format is `[IV16][AES-CBC ciphertext (16-byte blocks)][HMAC32]`, so a valid file length is
     * always `IV + 16*n + MAC` (> 48 and `(len - 48) % 16 == 0`). A truncated download (early EOF)
     * leaves a short/odd-sized file that would otherwise be fed to the decrypter and produce garbage
     * (BitmapFactory returns null → blank bubble). Rejecting it here makes [isReadable] report "not
     * ready" so the normal download path re-fetches a complete copy.
     */
    fun hasEncrypted(basePath: String): Boolean = isStructurallyCompleteCiphertext(encryptedFile(basePath))

    /**
     * True when [file] is a present, structurally complete ciphertext `[IV16][16*n AES-CBC][HMAC32]`
     * (len > 48 and `(len - 48) % 16 == 0`). Single source of truth for the at-rest layout so callers
     * (message attachments, favorites) never re-hardcode the IV/MAC/block sizes.
     */
    fun isStructurallyCompleteCiphertext(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val cipherLen = file.length() - IV_SIZE - MAC_SIZE
        return cipherLen > 0 && cipherLen % 16L == 0L
    }

    /** True when a legacy plaintext file is present and non-empty. */
    fun hasPlaintext(basePath: String): Boolean = FileUtil.isFileValid(basePath)

    /**
     * Whether the attachment is locally available for reading — either encrypted (new) or as a
     * legacy plaintext file (old). Use this in place of [FileUtil.isFileValid] for migrated types.
     */
    fun isReadable(basePath: String): Boolean = hasEncrypted(basePath) || hasPlaintext(basePath)

    /**
     * [isReadable] with the row's recorded plaintext size: the legacy plaintext arm additionally
     * requires the exact expected length (when known), so a truncated legacy plaintext — half-written
     * by an interrupted pre-encrypt-at-rest download — reports "not ready" and re-downloads instead
     * of rendering broken forever (and instead of having its row repaired to SUCCESS). The ciphertext
     * arm keeps the structural check: a persisted `.encrypt` is only ever written complete
     * (temp + rename + MAC in `DownloadAttachmentJob`).
     */
    fun isReadable(basePath: String, expectedPlainSize: Int): Boolean =
        hasEncrypted(basePath) || (hasPlaintext(basePath) &&
            (expectedPlainSize <= 0 || plaintextFile(basePath).length() == expectedPlainSize.toLong()))

    /**
     * "Fully downloaded" gate for long-text attachments (Read-more / hide-download-card).
     *
     * A persisted `<basePath>.encrypt` is guaranteed **complete and MAC-valid** by
     * `DownloadAttachmentJob`: a truncated stream (`totalBytesRead != contentLength`) throws and the
     * partial file is deleted, and the ciphertext MAC is verified before it is kept. So for
     * encrypted-at-rest long text, the mere presence of a structurally-valid ciphertext means "done".
     *
     * This matters most for **forwarded** long text: the forwarded attachment is embedded in the
     * message's serialized `forwardContext`, so `updateAttachmentStatus` (which writes
     * `DBAttachmentModel`) never flips the rendered `attachment.status` to SUCCESS, and the in-memory
     * download progress is transient (lost on rebind / app restart). Gating such messages on
     * status/progress left them stuck showing only the 2KB preview even after a full download.
     *
     * Legacy plaintext files cannot self-verify truncation, so they still require the caller's
     * [plaintextStatusReady] signal (status == SUCCESS || progress == 100 || own-device send).
     */
    fun isLongTextReady(basePath: String, plaintextStatusReady: Boolean): Boolean {
        if (hasEncrypted(basePath)) return true
        return hasPlaintext(basePath) && plaintextStatusReady
    }

    /**
     * Build a `content://` [Uri] that streams the decrypted attachment bytes.
     *
     * @param messageId the attachment's DIRECTORY key — what
     *   `AttachmentPathResolver.directoryKeyFor` returns, i.e. the copy's own local id (the
     *   parameter name is historical; older uris carry a message id here). Prefer
     *   [contentUriFromBasePath], which derives it from an already-resolved path and so cannot drift.
     *   The provider resolves this segment against every identity it has ever meant.
     * @param fileName  the attachment file name (last path segment of [basePath])
     */
    fun contentUri(messageId: String, fileName: String): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(PATH_PREFIX)
            .appendPath(messageId)
            .appendPath(fileName)
            .build()

    /**
     * Resolve a content uri straight from a base path of the form
     * `.../attachment/<directoryKey>/<fileName>`.
     */
    fun contentUri(context: Context, messageId: String, basePath: String): Uri =
        contentUri(messageId, File(basePath).name)

    /**
     * Build a content uri from a canonical attachment base path of the form
     * `.../attachment/<directoryKey>/<fileName>` (see `AttachmentPathResolver`). Preferred over
     * [contentUri]: the segment comes from the path itself, so it always names the directory the
     * file actually lives in.
     */
    fun contentUriFromBasePath(basePath: String): Uri {
        val f = File(basePath)
        val messageId = f.parentFile?.name.orEmpty()
        return contentUri(messageId, f.name)
    }

    /**
     * Best Glide model for an image at [basePath]: prefer the decrypting `content://` [Uri] whenever a
     * structurally-complete ciphertext exists, else the plaintext [File] (legacy). Ciphertext-first
     * (mirrors [exportContentUriIfEncrypted] / shareFile / viewFile) is race-free for self-sent
     * attachments: the sender deletes the plaintext right after upload, so preferring a still-present
     * plaintext [File] here would `ENOENT` the instant it is cleaned up even though the durable
     * `.encrypt` is ready. Callers loading a returned [Uri] should disable Glide's disk cache to avoid
     * persisting decrypted bytes.
     */
    fun imageGlideModel(basePath: String): Any =
        if (hasEncrypted(basePath)) contentUriFromBasePath(basePath) else File(basePath)

    /**
     * The decrypting `content://` [Uri] to use for a **one-shot export** (save-to-album / copy /
     * auto-save) when a structurally-complete ciphertext exists, otherwise `null` (the caller must
     * fall back to the plaintext file — a `file://` uri or a FileProvider uri as appropriate).
     *
     * The ciphertext is preferred **even when a plaintext copy currently exists**. This is what
     * makes exports race-free: for a self-sent attachment the sender deletes the plaintext right
     * after upload ([com.difft.android.chat.jobs.PushTextSendJob]), so a plaintext uri resolved at
     * click time can `ENOENT` by the time the asynchronous save/copy actually opens it. The
     * `.encrypt` copy is durable (never deleted for encrypted-at-rest types) and decrypts on demand,
     * so routing through it eliminates that window. Mirrors the `hasEncrypted`-first ordering already
     * used by `Context.shareFile` / `Context.viewFile`.
     *
     * @param messageId the attachment's directory key — the same value used to build [basePath].
     */
    fun exportContentUriIfEncrypted(messageId: String, basePath: String): Uri? =
        if (hasEncrypted(basePath)) contentUri(messageId, File(basePath).name) else null

    /**
     * Read a text attachment fully into a String, decrypting on demand **without writing plaintext to
     * disk**. Used for long-text attachments (`text/x-signal-plain`), which are consumed only as an
     * in-memory String (Read-more preview, copy, confidential expand) — never shared/exported.
     *
     * Resolution order:
     * - legacy plaintext still on disk (old data / sender before migration) → read it directly;
     * - otherwise stream-decrypt through the read-only [EncryptedAttachmentProvider] content uri
     *   (sequential pipe; the provider resolves the key/contentType from the DB and guards against
     *   path traversal).
     *
     * @return the decoded text, or `null` when neither a plaintext nor a structurally-valid
     *   ciphertext exists (or on IO/decryption failure). Callers should fall back to the message body
     *   preview on `null`.
     *
     * MUST be called on an IO dispatcher: performs blocking IO and may read up to ~10MB.
     */
    fun readDecryptedText(context: Context, basePath: String, charset: Charset = Charsets.UTF_8): String? {
        if (hasPlaintext(basePath)) {
            return runCatching { plaintextFile(basePath).readText(charset) }.getOrNull()
        }
        if (!hasEncrypted(basePath)) return null
        val uri = contentUriFromBasePath(basePath)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.reader(charset).readText()
            }
        }.getOrNull()
    }

    internal fun parse(uri: Uri): Pair<String, String>? {
        val segments = uri.pathSegments
        if (segments.size < 3 || segments[0] != PATH_PREFIX) return null
        val messageId = segments[1]
        // fileName may itself have been split if it contained '/', re-join the remainder defensively
        val fileName = segments.subList(2, segments.size).joinToString("/")
        if (messageId.isEmpty() || fileName.isEmpty()) return null
        return messageId to fileName
    }

    /**
     * Resolve the on-disk base path for a `(directoryKey, fileName)` pair, **rejecting any value that
     * would escape the attachment root directory** (path traversal). Returns `null` ⇒ the caller MUST
     * deny the request.
     *
     * Callers serving a uri must pass a key recomputed from the matched DB row, never the uri's
     * literal segment — that is what keeps an old uri resolving to the file's current location.
     *
     * Why this matters: [EncryptedAttachmentProvider] is not exported, but our own app resolves
     * inbound shared `content://` uris (e.g. `IndexActivity` share-in) under the same uid, which
     * bypasses the export check. Without this guard a crafted
     * `content://<authority>/m/../../<anything>` could make the provider's plaintext-fallback disclose
     * arbitrary app-readable files (confused-deputy). The canonical-path containment check is the
     * authoritative guard; the separator rejection is a cheap early-out for the multi-segment vector.
     */
    fun resolveContainedBasePath(messageId: String, fileName: String): String? {
        if (messageId.isEmpty() || fileName.isEmpty()) return null
        if (messageId.any { it == '/' || it == '\\' } || fileName.any { it == '/' || it == '\\' }) return null
        return try {
            val root = File(FileUtil.getFilePath(FileUtil.FILE_DIR_ATTACHMENT)).canonicalFile
            val candidate = File(FileUtil.getMessageAttachmentFilePath(messageId) + fileName).canonicalFile
            val rootPrefix = root.path + File.separator
            if (candidate.path == root.path) null // must be a file under root, not the root itself
            else if ((candidate.path + File.separator).startsWith(rootPrefix)) candidate.path else null
        } catch (e: Exception) {
            null
        }
    }
}

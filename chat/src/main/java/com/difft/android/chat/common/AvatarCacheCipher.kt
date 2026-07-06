package com.difft.android.chat.common

import com.difft.android.base.glide.EncryptedCacheCoder
import com.difft.android.base.glide.GlideCacheKeyManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Single choke-point for reading/writing the avatar disk cache (`avatar/avatar_<id>` and
 * `group_avatar/avatar_<id>`) as **encrypted-at-rest**.
 *
 * Avatars are a disposable, re-derivable cache (the server keeps the ciphertext and the `encKey`
 * lives in the DB), so — unlike message attachments (`.encrypt` + AES-CBC/HMAC + zero-residue
 * migration) — we reuse the **Glide encrypted-cache construction** ([EncryptedCacheCoder], AES/CTR +
 * a Keystore-sealed master key from [GlideCacheKeyManager], self-healing, no body MAC). Losing a
 * cache entry just triggers a re-download.
 *
 * On-disk layout (per [EncryptedCacheCoder]):
 * ```
 * [ MAGIC(16) ][ random(32) ] || AES/CTR( [ MAGIC(16) ] + avatar bytes )
 * ```
 *
 * Degrade policy: when the Keystore is unavailable ([GlideCacheKeyManager.getKeyOrNull] == null) we
 * transparently fall back to plaintext (today's behaviour) — never throw on the write path. Legacy
 * plaintext already on disk is served as-is by [openDecrypting] (foreign files carry no MAGIC), and
 * is converged to ciphertext by [com.difft.android.chat.media.LegacyPlaintextAvatarCleanup].
 */
object AvatarCacheCipher {

    private const val TAG = "[AvatarCacheCipher]"

    /**
     * Master-key source. Defaults to the Keystore-sealed Glide cache key; overridable **only in tests**
     * (pure-JVM crypto round-trip without a real Keystore / Android context). `null` models the
     * Keystore-unavailable degrade path (plaintext fallback on write, plaintext read).
     */
    @Volatile
    internal var masterKeyProvider: () -> ByteArray? = { GlideCacheKeyManager.getKeyOrNull(application) }

    /**
     * Encrypt [plainBytes] to [file] atomically (unique temp file → rename). Falls back to a plaintext
     * write when the Keystore key is unavailable. Throws only on unrecoverable IO (caller guards).
     *
     * The temp file name is **unique per write** (not `<name>.tmp`): two coroutines can race to cache
     * the same avatar (concurrent `ensureCached` misses), and a shared temp name would let them corrupt
     * each other's partial writes. With distinct temps, each produces a complete file and the last
     * rename wins (both are valid full copies) — no corruption.
     */
    @Throws(IOException::class)
    fun writeEncrypted(file: File, plainBytes: ByteArray) {
        val dir = file.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
        try {
            val masterKey = masterKeyProvider()
            if (masterKey != null) {
                EncryptedCacheCoder(masterKey).encryptedOutput(tmp).use { it.write(plainBytes) }
            } else {
                // Keystore unavailable: keep today's plaintext behaviour (no regression).
                tmp.outputStream().use { it.write(plainBytes); it.flush() }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("rename ${tmp.name} -> ${file.name} failed")
            }
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            throw if (e is IOException) e else IOException(e)
        }
    }

    /**
     * Open a decrypting [InputStream] for a cache [file].
     * - Our ciphertext (leading MAGIC) → decrypting stream.
     * - Legacy/foreign plaintext (no MAGIC) → raw stream (backward compatible).
     * @throws IOException when our ciphertext cannot be decrypted (key change / corruption) — the
     *   caller treats this as a cache miss and re-downloads.
     */
    @Throws(IOException::class)
    fun openDecrypting(file: File): InputStream {
        if (!EncryptedCacheCoder.hasMagic(file)) {
            return FileInputStream(file) // legacy plaintext or a foreign file (generated PNG etc.)
        }
        val masterKey = masterKeyProvider()
            ?: throw IOException("$TAG keystore unavailable for encrypted avatar ${file.name}")
        return EncryptedCacheCoder(masterKey).encryptedInput(file)
    }

    /**
     * Exact plaintext length of a cache [file]: `fileLen - HEADER_OVERHEAD` for our ciphertext
     * (AES/CTR is length-preserving, no padding), or the raw length for legacy plaintext. Returns a
     * non-negative value; used for `OpenableColumns.SIZE` so PictureSelector need not open the file.
     */
    fun plaintextLength(file: File): Long = try {
        if (EncryptedCacheCoder.hasMagic(file)) {
            (file.length() - EncryptedCacheCoder.HEADER_OVERHEAD).coerceAtLeast(0L)
        } else {
            file.length()
        }
    } catch (e: Exception) {
        L.w { "$TAG plaintextLength failed: ${e.message}" }
        0L
    }
}

package com.difft.android.chat.media

import android.content.Context
import com.difft.android.base.glide.EncryptedCacheCoder
import com.difft.android.base.glide.GlideCacheKeyManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FilePathManager
import com.difft.android.base.utils.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One-time startup purge of **legacy plaintext** avatar cache files (`avatar/avatar_<id>` and
 * `group_avatar/avatar_<id>`) written before encryption-at-rest (docs §15).
 *
 * Avatars are a disposable, re-derivable cache, so — unlike [LegacyPlaintextAttachmentMigration]
 * (which must never lose data and re-encrypts in place) — we simply **delete** the legacy plaintext;
 * it re-downloads as ciphertext on next display. This is simpler and race-free.
 *
 * Only files that (a) match the `avatar_` cache prefix and (b) carry no [EncryptedCacheCoder] MAGIC
 * are deleted — generated local PNGs (`<timestamp>.png`) and already-encrypted files are left alone.
 *
 * Guard: we only run when the Keystore key is available. Without it, `AvatarCacheCipher.writeEncrypted`
 * falls back to plaintext, so a plaintext file could be a *current* cache entry rather than legacy —
 * deleting it would loop. In that degraded case we skip and do not stamp, retrying on a later launch.
 */
object LegacyPlaintextAvatarCleanup {

    private const val TAG = "[LegacyPlaintextAvatarCleanup]"
    private const val PREFS = "avatar_cache_migration"
    private const val KEY_VERSION = "legacy_plaintext_avatar_purge_version"
    private const val CURRENT_VERSION = 1
    private const val CACHE_PREFIX = "avatar_"
    private const val TMP_SUFFIX = ".tmp"

    @Volatile
    private var running = false

    suspend fun runIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= CURRENT_VERSION) return@withContext
        if (running) return@withContext

        // Cannot distinguish legacy plaintext from a current plaintext-fallback entry without a key.
        if (!GlideCacheKeyManager.isAvailable(application)) {
            L.w { "$TAG keystore unavailable, skip purge (retry next launch)" }
            return@withContext
        }

        running = true
        try {
            var deleted = 0
            var failed = 0
            for (dir in listOf(FilePathManager.avatarDir, FilePathManager.groupAvatarDir)) {
                val files = dir.listFiles() ?: continue
                for (file in files) {
                    if (!file.isFile) continue
                    if (!file.name.startsWith(CACHE_PREFIX)) continue // skip generated PNGs etc.
                    if (file.name.endsWith(TMP_SUFFIX)) continue // skip in-flight writes (unique temp)
                    if (EncryptedCacheCoder.hasMagic(file)) continue // already encrypted
                    if (file.delete()) deleted++ else failed++
                }
            }
            if (failed == 0) {
                prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).apply()
                L.i { "$TAG done: deleted=$deleted legacy plaintext avatars" }
            } else {
                L.w { "$TAG incomplete: deleted=$deleted, failed=$failed, retry next launch" }
            }
        } catch (e: Exception) {
            L.w { "$TAG failed, will retry next launch: ${e.message}" }
        } finally {
            running = false
        }
    }
}

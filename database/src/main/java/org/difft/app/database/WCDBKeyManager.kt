@file:Suppress("DEPRECATION") // androidx.security.crypto.* is deprecated; we still read the
                              // legacy `wcdb_secure_prefs.xml` as a recovery backup (issue #725).

package org.difft.app.database

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Legacy SealedData JSON shape used by the deprecated [WCDBSecretKeyHelper] (now deleted)
 * and still present in `wcdb_secure_prefs.xml` for installs upgrading from older versions.
 *
 * Read-only from this point onward — only [WCDBKeyManager.tryMigrateFromLegacy] uses it.
 */
internal data class SealedData(
    val iv: ByteArray,
    val data: ByteArray
)

/**
 * Outcome of a Keystore-entry lookup, disambiguating the three states the old `SecretKey?`
 * return type fused into `null`: [Found] a usable entry exists; [Absent] the daemon answered
 * cleanly with no such alias; [Failed] the operation threw, with [cause] carrying the real
 * throwable for classification instead of swallowing it to `null`.
 *
 * `internal` so the same-module contract test ([KeystoreEntryClassificationTest]) can assert on
 * these variants; [getKeystoreEntry] is the only public-to-module entry point.
 */
internal sealed interface KeystoreEntryResult {
    data class Found(val key: SecretKey) : KeystoreEntryResult
    data object Absent : KeystoreEntryResult
    data class Failed(val cause: Throwable) : KeystoreEntryResult
}

/**
 * Synchronous WCDB cipher-key store.
 *
 * Replaces the legacy [WCDBSecretKeyHelper] which used a two-write `EncryptedSharedPreferences`
 * sequence and silently fell back to Base64 plaintext on Keystore failure (R6/R7/R6').
 *
 * Wire format (76 bytes, fixed) — single atomic file at `filesDir/wcdb_key.bin`:
 * ```
 * +----------------+--------------------------------+--------------------+
 * | 12 bytes       | 48 bytes                       | 16 bytes           |
 * | GCM IV (nonce) | ciphertext (encrypted key)     | GCM authentication |
 * +----------------+--------------------------------+--------------------+
 * 0               12                              60                   76
 * ```
 *
 * Plaintext key is 48 bytes (matches legacy [WCDBSecretKeyHelper] / WCDB cipher size).
 *
 * Atomicity:
 * - Write goes to `wcdb_key.bin.tmp` first; `renameTo` makes the swap atomic on POSIX FS.
 * - On rename failure, deletes the destination and retries once before giving up.
 *
 * No plaintext fallback. If Keystore is unavailable, [getOrCreateKey] throws
 * [WCDBKeyUnavailableException] and the caller routes through the DB recovery flow.
 *
 * Called from `WCDB.db` lazy initializer BEFORE Hilt is fully ready, so this is a
 * top-level `object` rather than an injected class.
 */
object WCDBKeyManager {

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"

    /** New alias for the wcdb_key.bin format. Distinct from the legacy alias on purpose. */
    private const val KEY_ALIAS = "WCDBKey"

    /** Legacy alias used by [WCDBSecretKeyHelper] for the SealedData JSON format. Read-only here. */
    private const val LEGACY_KEY_ALIAS = "WCDBSecret"

    /** Legacy SP file name (EncryptedSharedPreferences-backed). */
    private const val LEGACY_SP_NAME = "wcdb_secure_prefs"

    /** Legacy SP keys (kept verbatim from [WCDBSecretKeyHelper] for migration). */
    private const val LEGACY_KEY_DATA = "wcdb_secret_key"
    private const val LEGACY_KEY_TYPE = "wcdb_secret_type"
    private const val LEGACY_TYPE_OBJECT = "object"
    private const val LEGACY_TYPE_STRING = "string"

    /** Plaintext key length — must match legacy [WCDBSecretKeyHelper] / WCDB expectations. */
    private const val PLAINTEXT_KEY_SIZE = 48
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8 // 16
    private const val WIRE_FORMAT_SIZE = GCM_IV_SIZE + PLAINTEXT_KEY_SIZE + GCM_TAG_BYTES // 76

    private const val KEY_FILE_NAME = "wcdb_key.bin"
    private const val KEY_FILE_TMP_NAME = "wcdb_key.bin.tmp"

    /**
     * Returns the 48-byte plaintext WCDB cipher key.
     *
     * Order of operations:
     *  1. If `wcdb_key.bin` exists → decrypt and return.
     *  2. Else try migrating from legacy SP. Three outcomes:
     *      a. Legacy SP file/key absent (truly fresh install): [tryMigrateFromLegacy]
     *         returns `null` → fall through to (3) and generate a fresh key.
     *      b. Legacy SP exists and decodes cleanly → persist via [writeAtomic], delete
     *         the legacy SP, return the key.
     *      c. Legacy SP exists but the stored key cannot be decoded (Keystore alias
     *         invalidated, JSON/Base64 parse failure, wrong length): [tryMigrateFromLegacy]
     *         throws [WCDBKeyUnavailableException], propagated to the caller so the
     *         recovery flow runs. We do NOT generate a fresh random key in this case —
     *         that would silently desynchronize the cipher key from the existing
     *         WCDB file and make the database permanently unreadable without the
     *         user seeing the recovery dialog.
     *  3. Else generate a fresh 48-byte key, persist via [writeAtomic], return it.
     *
     * @throws WCDBKeyUnavailableException on any unrecoverable Keystore failure or
     *   on a legacy SP that exists but cannot be decoded. No plaintext fallback.
     */
    fun getOrCreateKey(context: Context): ByteArray {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        if (keyFile.exists()) {
            return readAndDecrypt(keyFile)
        }

        // tryMigrateFromLegacy returns null only on truly-absent legacy data.
        // Decode failures throw WCDBKeyUnavailableException and propagate out
        // (do NOT catch — the caller routes through the recovery flow).
        //
        // Legacy SP file is NOT deleted after migration — kept as a cold recovery
        // backup in case `wcdb_key.bin` is later corrupted/lost. Same policy as
        // `secure_prefs.xml` and other legacy SP files (issue #725).
        val migrated = tryMigrateFromLegacy(context)
        if (migrated != null) {
            writeAtomic(context, migrated)
            L.i { "[WCDBKeyManager] migrated WCDB key from legacy SP to wcdb_key.bin (legacy SP retained as backup)" }
            return migrated
        }

        val fresh = ByteArray(PLAINTEXT_KEY_SIZE).also { SecureRandom().nextBytes(it) }
        writeAtomic(context, fresh)
        L.i { "[WCDBKeyManager] generated and persisted fresh WCDB key" }
        return fresh
    }

    // --------------------------------------------------------------------- read

    private fun readAndDecrypt(keyFile: File): ByteArray {
        val blob = try {
            keyFile.readBytes()
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException(
                "Failed to read $KEY_FILE_NAME (size=${keyFile.length()})", e
            )
        }
        if (blob.size != WIRE_FORMAT_SIZE) {
            throw WCDBKeyUnavailableException(
                "Corrupt $KEY_FILE_NAME — expected $WIRE_FORMAT_SIZE bytes, got ${blob.size}"
            )
        }
        val iv = blob.copyOfRange(0, GCM_IV_SIZE)
        val ciphertextAndTag = blob.copyOfRange(GCM_IV_SIZE, WIRE_FORMAT_SIZE)

        // Split clean-absent (cause=null is CORRECT) from threw (preserve the real cause).
        val secretKey = when (val entry = getKeystoreEntry(KEY_ALIAS)) {
            is KeystoreEntryResult.Found -> entry.key
            KeystoreEntryResult.Absent -> throw WCDBKeyUnavailableException(
                "Keystore alias '$KEY_ALIAS' missing (clean-absent) while key file exists"
            )
            is KeystoreEntryResult.Failed -> throw WCDBKeyUnavailableException(
                "Keystore read failed for alias '$KEY_ALIAS' while key file exists", entry.cause
            )
        }

        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertextAndTag)
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException("Failed to decrypt $KEY_FILE_NAME", e)
        }
        if (plaintext.size != PLAINTEXT_KEY_SIZE) {
            throw WCDBKeyUnavailableException(
                "Decrypted key wrong length — expected $PLAINTEXT_KEY_SIZE, got ${plaintext.size}"
            )
        }
        return plaintext
    }

    // -------------------------------------------------------------------- write

    /**
     * Encrypt `plaintext` (48 bytes) and atomically replace `wcdb_key.bin`.
     *
     * Atomicity is via temp-file + `renameTo`. If the rename fails (Android sometimes
     * reports false even on success for cross-FS targets, though `filesDir` is single-FS),
     * we delete the destination once and retry. If that also fails, we throw.
     */
    private fun writeAtomic(context: Context, plaintext: ByteArray) {
        require(plaintext.size == PLAINTEXT_KEY_SIZE) {
            "plaintext must be $PLAINTEXT_KEY_SIZE bytes; got ${plaintext.size}"
        }
        val blob = encrypt(plaintext)
        val tempFile = File(context.filesDir, KEY_FILE_TMP_NAME)
        val finalFile = File(context.filesDir, KEY_FILE_NAME)
        try {
            tempFile.outputStream().use { os ->
                os.write(blob)
                os.flush()
                // Force the new bytes to durable storage before the rename so a crash
                // between `flush` and `renameTo` cannot leave an empty temp file.
                os.fd.sync()
            }
            if (!tempFile.renameTo(finalFile)) {
                // delete-and-retry once
                finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    throw WCDBKeyUnavailableException("Failed to rename $KEY_FILE_TMP_NAME → $KEY_FILE_NAME")
                }
            }
        } catch (e: Throwable) {
            tempFile.delete()
            if (e is WCDBKeyUnavailableException) throw e
            throw WCDBKeyUnavailableException("Atomic write of $KEY_FILE_NAME failed", e)
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val secretKey = when (val entry = getOrCreateKeystoreEntry(KEY_ALIAS)) {
            is KeystoreEntryResult.Found -> entry.key
            is KeystoreEntryResult.Failed -> throw WCDBKeyUnavailableException(
                "Could not get or create Keystore alias '$KEY_ALIAS'", entry.cause
            )
            KeystoreEntryResult.Absent -> throw WCDBKeyUnavailableException(
                "Keystore alias '$KEY_ALIAS' still absent after create attempt"
            )
        }
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey)
            }
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException("Failed to initialize AES/GCM cipher", e)
        }
        val iv = cipher.iv
        if (iv.size != GCM_IV_SIZE) {
            throw WCDBKeyUnavailableException("Unexpected IV size: ${iv.size} (expected $GCM_IV_SIZE)")
        }
        val ciphertextAndTag = try {
            cipher.doFinal(plaintext)
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException("AES/GCM encrypt failed", e)
        }
        if (ciphertextAndTag.size != PLAINTEXT_KEY_SIZE + GCM_TAG_BYTES) {
            throw WCDBKeyUnavailableException(
                "Unexpected ciphertext+tag size: ${ciphertextAndTag.size}"
            )
        }
        // Wire = iv || ciphertext || tag (AES/GCM already appends the tag to the ciphertext).
        return iv + ciphertextAndTag
    }

    // ---------------------------------------------------------------- migration

    /**
     * Reads the legacy `wcdb_secure_prefs.xml` (EncryptedSharedPreferences). Decodes
     * both supported formats discriminated by the legacy `wcdb_secret_type` field:
     *  - `type == "object"` → `SealedData{ iv, data }` JSON, unsealed via legacy
     *    Keystore alias `"WCDBSecret"`.
     *  - `type == "string"` → Base64 plaintext (silent-downgrade survivors from R7).
     *
     * Return semantics:
     *  - `null` → No legacy data on disk (fresh install or already-migrated and SP
     *    was deleted). The caller may safely generate a fresh random WCDB key.
     *  - non-null `ByteArray` → Decoded the legacy 48-byte WCDB cipher key.
     *  - throws [WCDBKeyUnavailableException] → Legacy data IS present on disk but
     *    cannot be decoded (Keystore alias invalidated, JSON/Base64 parse error,
     *    unseal failure, wrong length). The caller MUST NOT generate a fresh key;
     *    doing so would silently desynchronize the cipher key from the existing
     *    WCDB file and make the database permanently unreadable. Instead the
     *    caller routes through the database-recovery flow.
     */
    private fun tryMigrateFromLegacy(context: Context): ByteArray? {
        // Step 1: legacy SP file existence check — fresh install short-circuits here.
        // EncryptedSharedPreferences silently creates the underlying xml on first
        // access, so we must check existence BEFORE instantiating it.
        val legacySpFile = File(context.dataDir, "shared_prefs/$LEGACY_SP_NAME.xml")
        if (!legacySpFile.exists()) {
            return null
        }

        // Step 2: open the SP. Failure here means Keystore alias for the EncryptedSP
        // master key is broken — legacy data effectively unreadable.
        val prefs = try {
            openLegacyPrefs(context)
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException(
                "legacy SP file present but unreadable (Keystore/MasterKey failure?)", e
            )
        }

        // Step 3: data-key presence check. SP exists but has no key inside → treat as
        // "no legacy data" (fresh install where the file was created but never written).
        val serializedKey = readLegacyPrefValue(prefs, LEGACY_KEY_DATA)
        if (serializedKey.isNullOrEmpty()) return null

        val type = readLegacyPrefValue(prefs, LEGACY_KEY_TYPE)

        // Step 4: decode. Any failure here = legacy data exists but unreadable → throw.
        val plaintext: ByteArray = try {
            when {
                type.isNullOrEmpty() -> {
                    // Best-effort fallback for R7-silent-downgrade survivors with broken
                    // markers — Base64 plaintext only. If this also fails, throw.
                    decodeLegacyStringOrThrow(serializedKey)
                }
                type == LEGACY_TYPE_OBJECT -> {
                    val sealed = globalServices.gson.fromJson(serializedKey, SealedData::class.java)
                        ?: throw WCDBKeyUnavailableException("legacy SealedData JSON parse returned null")
                    unsealLegacy(sealed)
                        ?: throw WCDBKeyUnavailableException("legacy SealedData unseal failed (alias='$LEGACY_KEY_ALIAS')")
                }
                type == LEGACY_TYPE_STRING -> decodeLegacyStringOrThrow(serializedKey)
                else -> throw WCDBKeyUnavailableException("legacy SP: unknown type='$type'")
            }
        } catch (e: WCDBKeyUnavailableException) {
            L.e { "[WCDBKeyManager] legacy decode failed (type=$type): ${e.stackTraceToString()}" }
            throw e
        } catch (e: Throwable) {
            L.e { "[WCDBKeyManager] legacy decode failed (type=$type): ${e.stackTraceToString()}" }
            throw WCDBKeyUnavailableException("legacy WCDB key decode failed (type=$type)", e)
        }

        if (plaintext.size != PLAINTEXT_KEY_SIZE) {
            throw WCDBKeyUnavailableException(
                "legacy decoded key wrong length=${plaintext.size} (expected $PLAINTEXT_KEY_SIZE)"
            )
        }
        return plaintext
    }

    /**
     * Base64-decode a string. Throws [WCDBKeyUnavailableException] on failure rather
     * than returning null — caller relies on throw-on-failure semantics for the
     * "legacy data present but unreadable" branch.
     */
    private fun decodeLegacyStringOrThrow(serialized: String): ByteArray = try {
        Base64.decode(serialized, Base64.NO_PADDING)
    } catch (e: Throwable) {
        throw WCDBKeyUnavailableException("legacy Base64 decode failed", e)
    }

    /** Mirrors the legacy `WCDBSecretKeyHelper.unseal()` using the OLD alias `"WCDBSecret"`. */
    private fun unsealLegacy(sealed: SealedData?): ByteArray? {
        if (sealed == null) return null
        // Behavior-preserving: only a usable entry proceeds; Absent/Failed → null (same as before).
        val secretKey = (getKeystoreEntry(LEGACY_KEY_ALIAS) as? KeystoreEntryResult.Found)?.key ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, sealed.iv))
            cipher.doFinal(sealed.data)
        } catch (e: Throwable) {
            L.w { "[WCDBKeyManager] legacy unseal failed: ${e.javaClass.simpleName}" }
            null
        }
    }

    /**
     * Opens the legacy `wcdb_secure_prefs.xml` via [EncryptedSharedPreferences].
     * Throws if the AndroidX [MasterKey] cannot be reconstructed (Keystore reset, etc.).
     * Read-only — only called from [tryMigrateFromLegacy].
     */
    private fun openLegacyPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            LEGACY_SP_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Defensive read — returns null on contains-check miss OR internal decryption failure. */
    private fun readLegacyPrefValue(prefs: SharedPreferences, key: String): String? = try {
        if (prefs.contains(key)) prefs.getString(key, null) else null
    } catch (e: Throwable) {
        L.w { "[WCDBKeyManager] legacy SP read failed key=$key: ${e.javaClass.simpleName}" }
        null
    }

    // ----------------------------------------------------------------- keystore

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    // getKeystoreEntry stays `internal` for the same-module contract test; getOrCreateKeystoreEntry
    // is only used here, so it's private. Keystore access stays owned here.
    private fun getOrCreateKeystoreEntry(alias: String): KeystoreEntryResult {
        val existing = getKeystoreEntry(alias)
        if (existing is KeystoreEntryResult.Found) return existing
        return try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE
            )
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(spec)
            KeystoreEntryResult.Found(keyGenerator.generateKey())
        } catch (e: Throwable) {
            L.e { "[WCDBKeyManager] failed to create Keystore alias '$alias': ${e.stackTraceToString()}" }
            // If the read leg also threw, chain its cause so classification keeps both signals.
            (existing as? KeystoreEntryResult.Failed)?.cause?.let { e.addSuppressed(it) }
            KeystoreEntryResult.Failed(e)
        }
    }

    internal fun getKeystoreEntry(alias: String): KeystoreEntryResult = try {
        val ks = keyStore()
        if (!ks.containsAlias(alias)) return KeystoreEntryResult.Absent
        val entry = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            ?: return KeystoreEntryResult.Absent
        KeystoreEntryResult.Found(entry.secretKey)
    } catch (e: Throwable) {
        L.w { "[WCDBKeyManager] failed to load Keystore alias '$alias': ${e.javaClass.simpleName}" }
        KeystoreEntryResult.Failed(e)
    }
}

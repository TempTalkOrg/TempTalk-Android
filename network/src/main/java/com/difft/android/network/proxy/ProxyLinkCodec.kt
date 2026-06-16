package com.difft.android.network.proxy

import android.net.Uri
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encoder/decoder for the obfuscated proxy share link (format spec v1, see
 * `docs/claude/proxy-share-link-format.md`).
 *
 * ```
 * ytp://config?d=<base64url-nopad( envelope )>
 *
 * envelope = version(1B=0x01) | mode(1B) | body
 *   mode 0x00 (plain)     : body = jsonBytes
 *   mode 0x01 (encrypted) : body = salt(16) | iv(12) | AES-256-GCM(jsonBytes)
 * ```
 *
 * The 2-byte envelope is cleartext (carries no secret) so the client can decide
 * whether to prompt for a passphrase; it is also bound as GCM AAD so mode /
 * version cannot be flipped. Plain mode is **not** confidential — base64 only
 * removes keywords; anyone can decode it. Encrypted mode derives the key from a
 * user passphrase (PBKDF2-HMAC-SHA256) distributed out-of-band.
 */
object ProxyLinkCodec {

    enum class Mode { PLAIN, ENCRYPTED }

    sealed interface Decoded {
        data class Success(val config: ProxyConfig) : Decoded
        /** Passphrase did not authenticate (GCM tag mismatch) or link corrupted. */
        data object WrongPassphrase : Decoded
        /** Not a structurally valid encrypted link. */
        data object Invalid : Decoded
    }

    /** Returns the [Mode] when [link] is a structurally valid ytp link, else null. */
    fun inspect(link: String): Mode? {
        val blob = blobOf(link) ?: return null
        if (blob.size < ENVELOPE_LEN || blob[0].toInt() and 0xFF != VERSION) return null
        return when (blob[1].toInt() and 0xFF) {
            MODE_PLAIN -> Mode.PLAIN
            MODE_ENCRYPTED -> Mode.ENCRYPTED
            else -> null
        }
    }

    /** Decodes a plain (mode=0x00) link to a [ProxyConfig]; null if not plain/invalid. */
    fun decodePlain(link: String): ProxyConfig? {
        val blob = blobOf(link) ?: return null
        if (blob.size < ENVELOPE_LEN) return null
        if (blob[0].toInt() and 0xFF != VERSION) return null
        if (blob[1].toInt() and 0xFF != MODE_PLAIN) return null
        val json = blob.copyOfRange(ENVELOPE_LEN, blob.size)
        return configFromJson(json)
    }

    /** Decodes an encrypted (mode=0x01) link using [passphrase]. */
    fun decodeEncrypted(link: String, passphrase: String): Decoded {
        val blob = blobOf(link) ?: return Decoded.Invalid
        val minLen = ENVELOPE_LEN + SALT_LEN + IV_LEN + GCM_TAG_BYTES
        if (blob.size < minLen) return Decoded.Invalid
        if (blob[0].toInt() and 0xFF != VERSION) return Decoded.Invalid
        if (blob[1].toInt() and 0xFF != MODE_ENCRYPTED) return Decoded.Invalid

        val aad = blob.copyOfRange(0, ENVELOPE_LEN)
        val salt = blob.copyOfRange(ENVELOPE_LEN, ENVELOPE_LEN + SALT_LEN)
        val ivStart = ENVELOPE_LEN + SALT_LEN
        val iv = blob.copyOfRange(ivStart, ivStart + IV_LEN)
        val cipherText = blob.copyOfRange(ivStart + IV_LEN, blob.size)

        return try {
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(aad)
            }
            val json = cipher.doFinal(cipherText)
            configFromJson(json)?.let { Decoded.Success(it) } ?: Decoded.Invalid
        } catch (e: javax.crypto.BadPaddingException) {
            // GCM tag mismatch — surfaces as AEADBadTagException (a BadPaddingException
            // subclass) or, on some providers, a generic BadPaddingException.
            Decoded.WrongPassphrase
        } catch (e: Exception) {
            // Recoverable failures only — JVM Errors (OOM/StackOverflow) must propagate.
            L.w { "[Proxy] decodeEncrypted error: ${e.message}" }
            Decoded.Invalid
        }
    }

    /** Serializes [config] to a PLAIN ytp link (used for persistence and tests). */
    fun encodePlain(config: ProxyConfig): String {
        val json = jsonOf(config)
        val blob = byteArrayOf(VERSION.toByte(), MODE_PLAIN.toByte()) + json
        return SCHEME_PREFIX + b64uEncode(blob)
    }

    /** Serializes [config] to an ENCRYPTED ytp link (used by tests; server mirrors this). */
    fun encodeEncrypted(config: ProxyConfig, passphrase: String): String {
        val json = jsonOf(config)
        val salt = randomBytes(SALT_LEN)
        val iv = randomBytes(IV_LEN)
        val aad = byteArrayOf(VERSION.toByte(), MODE_ENCRYPTED.toByte())
        val key = deriveKey(passphrase, salt)
        val cipherText = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(aad)
        }.doFinal(json)
        val blob = aad + salt + iv + cipherText
        return SCHEME_PREFIX + b64uEncode(blob)
    }

    // --- internals ---

    /** Validates scheme/authority and returns the base64url-decoded envelope bytes. */
    private fun blobOf(link: String): ByteArray? {
        val uri = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(AUTHORITY, ignoreCase = true)) return null
        val d = uri.getQueryParameter(PARAM)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { b64uDecode(d) }.getOrNull()
    }

    private fun configFromJson(jsonBytes: ByteArray): ProxyConfig? = runCatching {
        val obj = JSONObject(String(jsonBytes, Charsets.UTF_8))
        val host = obj.optString("h").takeIf { it.isNotBlank() } ?: return null
        val fp = obj.optString("f").takeIf { it.isNotBlank() }
            ?.let { ProxyConfig.normalizeBase64Pin(it) } ?: return null
        val port = obj.optInt("p", ProxyConfig.DEFAULT_PORT).takeIf { it in 1..65535 }
            ?: ProxyConfig.DEFAULT_PORT
        // `tp` (legacy plain-3478 TURN port) is intentionally ignored: stealth mode
        // relays media via turns:<host>:<port> on the 443 front (design §9.4.1).
        val turnSecret = obj.optString("t").takeIf { it.isNotBlank() }
        val sni = obj.optString("sni").takeIf { it.isNotBlank() }
        ProxyConfig(
            host = host,
            port = port,
            spkiPinBase64 = fp,
            sni = sni,
            turnSecret = turnSecret,
        )
    }.getOrNull()

    private fun jsonOf(config: ProxyConfig): ByteArray {
        val obj = JSONObject()
        obj.put("v", PAYLOAD_VERSION)
        obj.put("h", config.host)
        obj.put("p", config.port)
        obj.put("f", config.spkiPinBase64)
        config.turnSecret?.takeIf { it.isNotBlank() }?.let { obj.put("t", it) }
        config.sni?.takeIf { it.isNotBlank() }?.let { obj.put("sni", it) }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec).encoded
    }

    private val RNG = SecureRandom()

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { RNG.nextBytes(it) }

    private fun b64uEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun b64uDecode(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private const val SCHEME = "ytp"
    private const val AUTHORITY = "config"
    private const val PARAM = "d"
    private const val SCHEME_PREFIX = "ytp://config?d="
    private const val VERSION = 0x01
    private const val PAYLOAD_VERSION = 1
    private const val MODE_PLAIN = 0x00
    private const val MODE_ENCRYPTED = 0x01
    private const val ENVELOPE_LEN = 2
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = 16
    private const val PBKDF2 = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val AES_GCM = "AES/GCM/NoPadding"
}

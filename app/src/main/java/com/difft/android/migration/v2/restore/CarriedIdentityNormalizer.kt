package com.difft.android.migration.v2.restore

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.Base64
import com.difft.android.migration.v2.model.NeutralIdentity
import java.io.IOException

/**
 * Checks the identity material a container carries before it is installed and brings it to the one
 * shape Android stores.
 *
 * This must happen before promote, not at login: the restore continuation signs in with the installed
 * identity, and sign-in's first request registers this device — revoking the old one — before its
 * second request decodes the identity. A key that cannot decode therefore leaves the user signed out on
 * both devices, and every retry meets the same key.
 *
 * The shape is libsignal's: a public key is the type byte `0x05` followed by the 32-byte Curve25519
 * point, and a private key is the bare 32 bytes. A bare 32-byte public key is normalized by prepending
 * the type byte, since the material is unambiguous and the alternative strands the user; anything else
 * is rejected.
 */
internal object CarriedIdentityNormalizer {

    /** The container's identity cannot be turned into a usable key pair; [message] names the field. */
    class InvalidIdentity(message: String) : Exception(message)

    fun normalize(identity: NeutralIdentity): NeutralIdentity {
        val publicKey = normalizePublic(identity.aciPublicKey, "aciPublicKey")
        val privateKey = requirePrivate(identity.aciPrivateKey, "aciPrivateKey")

        val oldPublic = identity.oldAciPublicKey?.takeIf { it.isNotEmpty() }
        val oldPrivate = identity.oldAciPrivateKey?.takeIf { it.isNotEmpty() }
        val (normalizedOldPublic, normalizedOldPrivate) = when {
            oldPublic == null && oldPrivate == null -> null to null
            oldPublic != null && oldPrivate != null ->
                normalizePublic(oldPublic, "oldAciPublicKey") to requirePrivate(oldPrivate, "oldAciPrivateKey")
            else -> {
                // Half a pair decrypts nothing; carrying it would only make the fallback window lie.
                L.w { "[MigrationV2][Restore] identity carried half an old key pair — dropping it" }
                null to null
            }
        }
        return identity.copy(
            aciPublicKey = publicKey,
            aciPrivateKey = privateKey,
            oldAciPublicKey = normalizedOldPublic,
            oldAciPrivateKey = normalizedOldPrivate,
        )
    }

    private fun normalizePublic(base64: String, field: String): String {
        val bytes = decode(base64, field)
        return when {
            bytes.size == SERIALIZED_PUBLIC_KEY_BYTES && bytes[0] == KEY_TYPE_DJB -> Base64.encodeBytes(bytes)
            bytes.size == RAW_KEY_BYTES -> {
                L.w { "[MigrationV2][Restore] $field carried as a bare 32-byte key — prepending the 0x05 type byte" }
                Base64.encodeBytes(byteArrayOf(KEY_TYPE_DJB) + bytes)
            }
            else -> throw InvalidIdentity(
                "$field: ${bytes.size} bytes, first byte 0x%02x — expected 33 bytes led by 0x05, or 32 bytes".format(bytes.firstOrNull() ?: 0),
            )
        }
    }

    private fun requirePrivate(base64: String, field: String): String {
        val bytes = decode(base64, field)
        if (bytes.size != RAW_KEY_BYTES) throw InvalidIdentity("$field: ${bytes.size} bytes — expected 32")
        return Base64.encodeBytes(bytes)
    }

    private fun decode(base64: String, field: String): ByteArray =
        try {
            Base64.decode(base64)
        } catch (e: IOException) {
            // The project's Base64 wraps the JDK decoder's IllegalArgumentException as IOException.
            throw InvalidIdentity("$field: not base64 (${e.message})")
        }

    /** libsignal's Curve25519 key-type marker, the first byte of a serialized public key. */
    const val KEY_TYPE_DJB: Byte = 0x05
    const val RAW_KEY_BYTES = 32
    const val SERIALIZED_PUBLIC_KEY_BYTES = 33
}

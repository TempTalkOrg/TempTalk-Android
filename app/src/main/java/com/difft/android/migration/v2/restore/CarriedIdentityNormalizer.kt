package com.difft.android.migration.v2.restore

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.Base64
import com.difft.android.migration.v2.model.NeutralIdentity
import java.io.IOException















internal object CarriedIdentityNormalizer {

    
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
            
            throw InvalidIdentity("$field: not base64 (${e.message})")
        }

    
    const val KEY_TYPE_DJB: Byte = 0x05
    const val RAW_KEY_BYTES = 32
    const val SERIALIZED_PUBLIC_KEY_BYTES = 33
}

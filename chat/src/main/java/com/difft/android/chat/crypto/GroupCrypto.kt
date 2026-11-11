package com.difft.android.chat.crypto

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import uniffi.dtproto.DtGroupCrypto






object GroupCrypto {

    private val gc = DtGroupCrypto(GroupCryptoConstants.BLOB_VERSION_V1)

    


    fun generateRGroup(): ByteArray {
        val rGroup = ByteArray(GroupCryptoConstants.R_GROUP_SIZE)
        java.security.SecureRandom().nextBytes(rGroup)
        return rGroup
    }

    



    fun deriveKGroup(rGroup: ByteArray): ByteArray {
        return gc.deriveKeys(rGroup.toUByteList()).kGroup.toByteArray()
    }

    


    fun deriveSkBind(rGroup: ByteArray): ByteArray {
        return gc.deriveKeys(rGroup.toUByteList()).skBind.toByteArray()
    }

    



    fun derivePkBind(rGroup: ByteArray): ByteArray {
        return gc.deriveKeys(rGroup.toUByteList()).pkBind.toByteArray()
    }

    



    fun encryptGroupName(kGroup: ByteArray, plainName: String): String {
        val blob = gc.encrypt(
            kGroup.toUByteList(),
            plainName.toByteArray(Charsets.UTF_8).toUByteList(),
            GroupCryptoConstants.GCM_AAD_GROUP_NAME.toByteArray(Charsets.UTF_8).toUByteList(),
        )
        return Base64.encodeToString(blob.toByteArray(), Base64.NO_WRAP)
    }

    


    fun decryptGroupName(kGroup: ByteArray, encryptedName: String): String? {
        return try {
            val blob = Base64.decode(encryptedName, Base64.DEFAULT)
            val plaintext = gc.decrypt(
                kGroup.toUByteList(),
                blob.toUByteList(),
                GroupCryptoConstants.GCM_AAD_GROUP_NAME.toByteArray(Charsets.UTF_8).toUByteList()
            )
            String(plaintext.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            L.e { "[GroupCrypto] decryptGroupName failed: ${e.message}" }
            null
        }
    }

    



    fun encryptGroupAvatar(kGroup: ByteArray, plainAvatar: String): String {
        val blob = gc.encrypt(
            kGroup.toUByteList(),
            plainAvatar.toByteArray(Charsets.UTF_8).toUByteList(),
            GroupCryptoConstants.GCM_AAD_GROUP_AVATAR.toByteArray(Charsets.UTF_8).toUByteList(),
        )
        return Base64.encodeToString(blob.toByteArray(), Base64.NO_WRAP)
    }

    


    fun decryptGroupAvatar(kGroup: ByteArray, encryptedAvatar: String): String? {
        return try {
            val blob = Base64.decode(encryptedAvatar, Base64.DEFAULT)
            val plaintext = gc.decrypt(
                kGroup.toUByteList(),
                blob.toUByteList(),
                GroupCryptoConstants.GCM_AAD_GROUP_AVATAR.toByteArray(Charsets.UTF_8).toUByteList()
            )
            String(plaintext.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            L.e { "[GroupCrypto] decryptGroupAvatar failed: ${e.message}" }
            null
        }
    }

    




    fun signUid(skBind: ByteArray, uid: String): String {
        val signature = gc.signUid(skBind.toUByteList(), uid)
        return Base64.encodeToString(signature.toByteArray(), Base64.NO_WRAP)
    }

    


    fun verifyUid(pkBind: ByteArray, uid: String, uidSignature: String): Boolean {
        return try {
            val signature = Base64.decode(uidSignature, Base64.DEFAULT)
            gc.verifyUid(pkBind.toUByteList(), uid, signature.toUByteList())
        } catch (e: Exception) {
            L.e { "[GroupCrypto] verifyUid failed: ${e.message}" }
            false
        }
    }

    


    fun pkBindToSpkiBase64(pkBind: ByteArray): String {
        require(pkBind.size == 32) { "Ed25519 public key must be 32 bytes" }
        val spki = GroupCryptoConstants.ED25519_SPKI_PREFIX + pkBind
        return Base64.encodeToString(spki, Base64.NO_WRAP)
    }

    

    private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }

    private fun List<UByte>.toByteArray(): ByteArray = map { it.toByte() }.toByteArray()
}

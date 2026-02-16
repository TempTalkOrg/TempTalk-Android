package org.thoughtcrime.securesms.cryptonew

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import com.difft.android.base.utils.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionDataManager @Inject constructor(
    private val userManager: UserManager
) {

    /**
     * 获取ACI身份密钥对，如果不存在则抛出异常
     */
    fun getAciIdentityKey(): IdentityKeyPair {
        val userData = userManager.getUserData() ?: throw IllegalStateException("User data not available")

        if (userData.aciIdentityPublicKey.isNullOrEmpty() || userData.aciIdentityPrivateKey.isNullOrEmpty()) {
            throw IllegalStateException("ACI identity key not found. Please ensure keys are registered with the server first.")
        }

        try {
            val publicKeyBytes = Base64.decode(userData.aciIdentityPublicKey!!)
            val privateKeyBytes = Base64.decode(userData.aciIdentityPrivateKey!!)

            val publicKey = IdentityKey(publicKeyBytes, 0)
            val privateKey = ECPrivateKey(privateKeyBytes)

            return IdentityKeyPair(publicKey, privateKey)
        } catch (e: Exception) {
            L.e { "[UserManagerKeyValue] Failed to get ACI identity key: ${e.message}" }
            throw IllegalStateException("Invalid ACI identity key format", e)
        }
    }

    /**
     * 如果需要，生成ACI身份密钥对
     */
    fun generateAciIdentityKeyIfNecessary() {
        val userData = userManager.getUserData() ?: throw IllegalStateException("User data not available")

        if (!userData.aciIdentityPublicKey.isNullOrEmpty() && !userData.aciIdentityPrivateKey.isNullOrEmpty()) {
            L.w { "[UserManagerKeyValue] Tried to generate an ACI identity, but one was already set!" }
            return
        }

        L.i { "[UserManagerKeyValue] Generating a new ACI identity key pair." }

        val key: IdentityKeyPair = IdentityKeyUtil.generateIdentityKeyPair()
        val publicKeyBase64 = Base64.encodeBytes(key.publicKey.serialize())
        val privateKeyBase64 = Base64.encodeBytes(key.privateKey.serialize())

        userManager.update {
            this.aciIdentityPublicKey = publicKeyBase64
            this.aciIdentityPrivateKey = privateKeyBase64
            this.aciIdentityKeyGenTime = System.currentTimeMillis()
        }

        L.i { "[UserManagerKeyValue] Generated new ACI identity key pair." }
    }

    /**
     * 获取旧ACI身份密钥对
     */
    fun getAciIdentityOldKey(): IdentityKeyPair {
        val userData = userManager.getUserData() ?: throw IllegalStateException("User data not available")

        val oldPublicKey = userData.aciIdentityOldPublicKey
        val oldPrivateKey = userData.aciIdentityOldPrivateKey

        if (oldPublicKey.isNullOrEmpty() || oldPrivateKey.isNullOrEmpty()) {
            throw IllegalStateException("Old ACI identity key not found in UserManager")
        }

        try {
            val publicKeyBytes = Base64.decode(oldPublicKey)
            val privateKeyBytes = Base64.decode(oldPrivateKey)

            val publicKey = IdentityKey(publicKeyBytes, 0)
            val privateKey = ECPrivateKey(privateKeyBytes)

            return IdentityKeyPair(publicKey, privateKey)
        } catch (e: Exception) {
            L.e { "[UserManagerKeyValue] Failed to get old ACI identity key: ${e.message}" }
            throw IllegalStateException("Invalid old ACI identity key format", e)
        }
    }

    /**
     * 更新ACI身份密钥对
     */
    fun updateAciIdentityKey(identityKeyPair: IdentityKeyPair) {
        val publicKeyBase64 = Base64.encodeBytes(identityKeyPair.publicKey.serialize())
        val privateKeyBase64 = Base64.encodeBytes(identityKeyPair.privateKey.serialize())

        userManager.update {
            this.aciIdentityPublicKey = publicKeyBase64
            this.aciIdentityPrivateKey = privateKeyBase64
            this.aciIdentityKeyGenTime = System.currentTimeMillis()
        }

        L.i { "[UserManagerKeyValue] Updated ACI identity key" }
    }

    /**
     * 设置旧ACI身份密钥对
     */
    fun setOldAciIdentityKey(identityKeyPair: IdentityKeyPair?) {
        if (identityKeyPair == null) {
            userManager.update {
                this.aciIdentityOldPublicKey = null
                this.aciIdentityOldPrivateKey = null
            }
        } else {
            val publicKeyBase64 = Base64.encodeBytes(identityKeyPair.publicKey.serialize())
            val privateKeyBase64 = Base64.encodeBytes(identityKeyPair.privateKey.serialize())

            userManager.update {
                this.aciIdentityOldPublicKey = publicKeyBase64
                this.aciIdentityOldPrivateKey = privateKeyBase64
            }
        }

        L.i { "[UserManagerKeyValue] Set old ACI identity key" }
    }

    /**
     * 检查是否有ACI身份密钥
     */
    fun hasAciIdentityKey(): Boolean {
        val userData = userManager.getUserData()
        return !userData?.aciIdentityPublicKey.isNullOrEmpty() &&
                !userData?.aciIdentityPrivateKey.isNullOrEmpty()
    }

    /**
     * 检查是否有旧ACI身份密钥
     */
    fun hasOldAciIdentityKey(): Boolean {
        val userData = userManager.getUserData() ?: return false
        return !userData.aciIdentityOldPublicKey.isNullOrEmpty() && !userData.aciIdentityOldPrivateKey.isNullOrEmpty()
    }

    /**
     * 检查旧ACI身份密钥是否过期（超过1天）
     */
    fun checkOldAciIdentityExpired(): Boolean {
        val userData = userManager.getUserData() ?: return true
        if (userData.aciIdentityKeyGenTime != 0L) {
            // 86400000 = 1 day in milliseconds
            return (System.currentTimeMillis() - userData.aciIdentityKeyGenTime) > 24 * 3600 * 1000
        }
        return true
    }
} 
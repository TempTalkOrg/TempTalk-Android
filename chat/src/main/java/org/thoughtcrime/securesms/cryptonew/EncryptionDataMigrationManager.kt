package org.thoughtcrime.securesms.cryptonew

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import org.thoughtcrime.securesms.keyvalue.SignalStore
import com.difft.android.base.utils.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionDataMigrationManager @Inject constructor(
    private val userManager: UserManager
) {

    /**
     * 执行密钥迁移（一次性）
     */
    fun migrateIfNeeded() {
        if (isMigrationCompleted()) {
            L.i { "[EncryptionDataMigrationManager] Key value migration already completed, skipping." }
            return
        }

        try {
            migrateIdentityKeys()
            cleanupSignalStoreData()

            L.i { "[EncryptionDataMigrationManager] Key value migration completed successfully" }
        } catch (e: Exception) {
            L.e { "[EncryptionDataMigrationManager] Key value migration failed: ${e.message}" }
            throw e
        }
    }

    /**
     * 迁移身份密钥
     */
    private fun migrateIdentityKeys() {
        val account = SignalStore.account()

        // 迁移当前ACI身份密钥
        if (account.hasAciIdentityKey()) {
            val identityKeyPair = account.aciIdentityKey
            val publicKeyBase64 = Base64.encodeBytes(identityKeyPair.publicKey.serialize())
            val privateKeyBase64 = Base64.encodeBytes(identityKeyPair.privateKey.serialize())

            userManager.update {
                this.aciIdentityPublicKey = publicKeyBase64
                this.aciIdentityPrivateKey = privateKeyBase64
                this.aciIdentityKeyGenTime = account.aciIdentityKeyGenTime
            }

            L.i { "[EncryptionDataMigrationManager] Migrated current ACI identity key" }
        }

        // 迁移旧ACI身份密钥
        if (account.hasOldAciIdentityKey()) {
            val oldIdentityKeyPair = account.aciIdentityOldKey
            val oldPublicKeyBase64 = Base64.encodeBytes(oldIdentityKeyPair.publicKey.serialize())
            val oldPrivateKeyBase64 = Base64.encodeBytes(oldIdentityKeyPair.privateKey.serialize())

            userManager.update {
                this.aciIdentityOldPublicKey = oldPublicKeyBase64
                this.aciIdentityOldPrivateKey = oldPrivateKeyBase64
            }

            L.i { "[EncryptionDataMigrationManager] Migrated old ACI identity key" }
        }
    }

    /**
     * 清理SignalStore中的数据
     */
    private fun cleanupSignalStoreData() {
        val account = SignalStore.account()

        // 清理身份密钥数据
        if (account.hasAciIdentityKey()) {
            account.updateAciIdentityKey(null)
            L.i { "[EncryptionDataMigrationManager] Cleaned up current ACI identity key from SignalStore" }
        }

        if (account.hasOldAciIdentityKey()) {
            account.setOldAciIdentityKey(null)
            L.i { "[EncryptionDataMigrationManager] Cleaned up old ACI identity key from SignalStore" }
        }
    }

    /**
     * 检查是否已完成迁移
     */
    private fun isMigrationCompleted(): Boolean {
        // 直接检查SignalStore中是否还有身份密钥数据
        // 如果SignalStore中没有数据，说明已经迁移完成并清理了
        return !SignalStore.account().hasAciIdentityKey()
    }
} 
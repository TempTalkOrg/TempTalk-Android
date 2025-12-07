package org.thoughtcrime.securesms.keyvalue

import com.difft.android.base.log.lumberjack.L
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil

class AccountValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {

    companion object {
        private val TAG = L.tag(AccountValues::class.java)
        private const val KEY_ACI_IDENTITY_PUBLIC_KEY = "account.aci_identity_public_key"
        private const val KEY_ACI_IDENTITY_PRIVATE_KEY = "account.aci_identity_private_key"
        private const val KEY_ACI_IDENTITY_KET_TIME = "account.aci_identity_key_time"
        private const val KEY_ACI_IDENTITY_OLD_PUBLIC_KEY = "account.aci_identity_old_public_key"
        private const val KEY_ACI_IDENTITY_OLD_PRIVATE_KEY = "account.aci_identity_old_private_key"
        private const val KEY_ACI_IDENTITY_OLD_STORE_TIME = "account.aci_identity_old_store_time"
    }


    public override fun onFirstEverAppLaunch() = Unit

    public override fun getKeysToIncludeInBackup(): List<String> {
        return listOf(
            KEY_ACI_IDENTITY_PUBLIC_KEY,
            KEY_ACI_IDENTITY_PRIVATE_KEY
        )
    }

    /** The identity key pair for the ACI identity. */
    val aciIdentityKey: IdentityKeyPair
        get() {
            require(store.containsKey(KEY_ACI_IDENTITY_PUBLIC_KEY)) { "Not yet set!" }
            // check the old identity, and removed once the old key is expired (more than 1 day).
            if (hasOldAciIdentityKey() && checkOldAciIdentityExpired()) {
                setOldAciIdentityKey(null)
            }
            return IdentityKeyPair(
                IdentityKey(getBlob(KEY_ACI_IDENTITY_PUBLIC_KEY, null)),
                Curve.decodePrivatePoint(getBlob(KEY_ACI_IDENTITY_PRIVATE_KEY, null))
            )
        }

    /** The Old identity key pair for the ACI identity. */
    val aciIdentityOldKey: IdentityKeyPair
        get() {
            return IdentityKeyPair(
                IdentityKey(getBlob(KEY_ACI_IDENTITY_OLD_PUBLIC_KEY, null)),
                Curve.decodePrivatePoint(getBlob(KEY_ACI_IDENTITY_OLD_PRIVATE_KEY, null))
            )
        }

    val aciIdentityKeyGenTime: Long
        get() = getLong(KEY_ACI_IDENTITY_KET_TIME, 0)

    private val oldAciIdentityStoreTime: Long
        get() = getLong(KEY_ACI_IDENTITY_OLD_STORE_TIME, 0)

    fun hasAciIdentityKey(): Boolean {
        return store.containsKey(KEY_ACI_IDENTITY_PUBLIC_KEY)
    }

    fun hasOldAciIdentityKey(): Boolean {
        return store.containsKey(KEY_ACI_IDENTITY_OLD_PUBLIC_KEY)
    }

    fun checkOldAciIdentityExpired(): Boolean {
        if (oldAciIdentityStoreTime != 0L) {
            //86400000 = 1 day in milliseconds
            return (System.currentTimeMillis() - oldAciIdentityStoreTime) > 24 * 3600 * 1000
        }
        return false
    }

    /** Generates and saves an identity key pair for the ACI identity. Should only be done once. */
    fun generateAciIdentityKeyIfNecessary() {
        synchronized(this) {
            if (store.containsKey(KEY_ACI_IDENTITY_PUBLIC_KEY)) {
                L.w { "Tried to generate an ANI identity, but one was already set!" + Throwable() }
                return
            }
            L.i { "Generating a new ACI identity key pair." }

            val key: IdentityKeyPair = IdentityKeyUtil.generateIdentityKeyPair()
            store
                .beginWrite()
                .putBlob(KEY_ACI_IDENTITY_PUBLIC_KEY, key.publicKey.serialize())
                .putBlob(KEY_ACI_IDENTITY_PRIVATE_KEY, key.privateKey.serialize())
                .commit()

            setAciIdentityKeyCreateTime(System.currentTimeMillis())
        }
    }

    /** update the ACI identity key pair. */
    fun updateAciIdentityKey(aciKeys: IdentityKeyPair?) {
        synchronized(this) {
            L.i { "Update the ACI identity key pair." }
            if (aciKeys != null) {
                store
                    .beginWrite()
                    .putBlob(KEY_ACI_IDENTITY_PUBLIC_KEY, aciKeys.publicKey.serialize())
                    .putBlob(KEY_ACI_IDENTITY_PRIVATE_KEY, aciKeys.privateKey.serialize())
                    .commit()
            } else if (store.containsKey(KEY_ACI_IDENTITY_PUBLIC_KEY)) {
                store
                    .beginWrite()
                    .remove(KEY_ACI_IDENTITY_PUBLIC_KEY)
                    .remove(KEY_ACI_IDENTITY_PRIVATE_KEY)
                    .commit()
            }

        }
    }

    /** set the Old ACI identity key pair. */
    fun setOldAciIdentityKey(aciKeys: IdentityKeyPair?) {
        synchronized(this) {
            L.i { "Set the Old ACI identity key pair." }
            if (aciKeys != null) {
                store
                    .beginWrite()
                    .putBlob(KEY_ACI_IDENTITY_OLD_PUBLIC_KEY, aciKeys.publicKey.serialize())
                    .putBlob(KEY_ACI_IDENTITY_OLD_PRIVATE_KEY, aciKeys.privateKey.serialize())
                    .commit()
            } else if (store.containsKey(KEY_ACI_IDENTITY_OLD_PUBLIC_KEY)) {
                store
                    .beginWrite()
                    .remove(KEY_ACI_IDENTITY_OLD_PUBLIC_KEY)
                    .remove(KEY_ACI_IDENTITY_OLD_PRIVATE_KEY)
                    .commit()
            }

        }
    }

    /** Set the time when the old ACI identity key pair was stored. */
    fun setOldAciIdentityStartTime(timestamp: Long) {
        synchronized(this) {
            L.i { "Set the old ACI identity key pair validate start time." }
            store
                .beginWrite()
                .putLong(KEY_ACI_IDENTITY_OLD_STORE_TIME, timestamp)
                .commit()
        }
    }

    /** Record the time when the ACI identity key pair was generated. */
    fun setAciIdentityKeyCreateTime(timestamp: Long) {
        synchronized(this) {
            L.i { "Record the ACI identity key pair create time." }
            store
                .beginWrite()
                .putLong(KEY_ACI_IDENTITY_KET_TIME, timestamp)
                .commit()
        }
    }
}

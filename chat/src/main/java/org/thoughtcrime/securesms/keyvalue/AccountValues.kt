package org.thoughtcrime.securesms.keyvalue

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.util.Medium
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.crypto.MasterCipher
import org.thoughtcrime.securesms.crypto.storage.PreKeyMetadataStore
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.Base64
import org.thoughtcrime.securesms.util.TextSecurePreferences
import com.difft.android.websocket.api.push.ACI
import java.security.SecureRandom

class AccountValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {

    companion object {
        private val TAG = L.tag(AccountValues::class.java)
        private const val KEY_SERVICE_PASSWORD = "account.service_password"
        private const val KEY_REGISTRATION_ID = "account.registration_id"
        private const val KEY_FCM_ENABLED = "account.fcm_enabled"
        private const val KEY_FCM_TOKEN = "account.fcm_token"
        private const val KEY_FCM_TOKEN_VERSION = "account.fcm_token_version"
        private const val KEY_FCM_TOKEN_LAST_SET_TIME = "account.fcm_token_last_set_time"
        private const val KEY_DEVICE_NAME = "account.device_name"
        private const val KEY_DEVICE_ID = "account.device_id"
        private const val KEY_PNI_REGISTRATION_ID = "account.pni_registration_id"

        private const val KEY_ACI_IDENTITY_PUBLIC_KEY = "account.aci_identity_public_key"
        private const val KEY_ACI_IDENTITY_PRIVATE_KEY = "account.aci_identity_private_key"
        private const val KEY_ACI_SIGNED_PREKEY_REGISTERED = "account.aci_signed_prekey_registered"
        private const val KEY_ACI_NEXT_SIGNED_PREKEY_ID = "account.aci_next_signed_prekey_id"
        private const val KEY_ACI_ACTIVE_SIGNED_PREKEY_ID = "account.aci_active_signed_prekey_id"
        private const val KEY_ACI_SIGNED_PREKEY_FAILURE_COUNT = "account.aci_signed_prekey_failure_count"
        private const val KEY_ACI_NEXT_ONE_TIME_PREKEY_ID = "account.aci_next_one_time_prekey_id"
        private const val KEY_ACI_IDENTITY_KET_TIME = "account.aci_identity_key_time"
        private const val KEY_ACI_IDENTITY_OLD_PUBLIC_KEY = "account.aci_identity_old_public_key"
        private const val KEY_ACI_IDENTITY_OLD_PRIVATE_KEY = "account.aci_identity_old_private_key"
        private const val KEY_ACI_IDENTITY_OLD_STORE_TIME = "account.aci_identity_old_store_time"

        @VisibleForTesting
        const val KEY_E164 = "account.e164"

        @VisibleForTesting
        const val KEY_ACI = "account.aci"

        @VisibleForTesting
        const val KEY_PNI = "account.pni"

        @VisibleForTesting
        const val KEY_IS_REGISTERED = "account.is_registered"
    }

    init {
        if (!store.containsKey(KEY_ACI)) {
            migrateFromSharedPrefsV1(ApplicationDependencies.getApplication())
        }

        if (!store.containsKey(KEY_ACI_IDENTITY_PUBLIC_KEY)) {
            migrateFromSharedPrefsV2(ApplicationDependencies.getApplication())
        }
    }

    public override fun onFirstEverAppLaunch() = Unit

    public override fun getKeysToIncludeInBackup(): List<String> {
        return listOf(
            KEY_ACI_IDENTITY_PUBLIC_KEY,
            KEY_ACI_IDENTITY_PRIVATE_KEY
        )
    }

    /** The local user's [ACI]. */
    val aci: ACI?
        get() = ACI.parseOrNull(getString(KEY_ACI, null))

    /** The local user's [ACI]. Will throw if not present. */
    fun requireAci(): ACI {
        return ACI.parseOrThrow(getString(KEY_ACI, null))
    }

    fun setAci(aci: ACI?) {
        putString(KEY_ACI, aci.toString())
    }

    /** The local user's E164. */
    val e164: String?
        get() = getString(KEY_E164, null)

    /** The local user's e164. Will throw if not present. */
    fun requireE164(): String {
        val e164: String? = getString(KEY_E164, null)
        return e164 ?: throw IllegalStateException("No e164!")
    }

    fun setE164(e164: String) {
        putString(KEY_E164, e164)
    }

    /** The password for communicating with the Signal service. */
    val servicePassword: String?
        get() = getString(KEY_SERVICE_PASSWORD, null)

    /** A randomly-generated value that represents this registration instance. Helps the server know if you reinstalled. */
    var registrationId: Int by integerValue(KEY_REGISTRATION_ID, 0)

    /** The identity key pair for the ACI identity. */
    val aciIdentityKey: IdentityKeyPair
        get() {
            require(store.containsKey(KEY_ACI_IDENTITY_PUBLIC_KEY)) { "Not yet set!" }
            // check the old identity, and removed once the old key is expired (more than 1 day).
            if(hasOldAciIdentityKey() && checkOldAciIdentityExpired()) {
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
        if (oldAciIdentityStoreTime != 0L ) {
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
    fun updateAciIdentityKey(aciKeys: IdentityKeyPair?){
        synchronized(this) {
            L.i { "Update the ACI identity key pair." }
            if(aciKeys != null){
                store
                    .beginWrite()
                    .putBlob(KEY_ACI_IDENTITY_PUBLIC_KEY, aciKeys.publicKey.serialize())
                    .putBlob(KEY_ACI_IDENTITY_PRIVATE_KEY, aciKeys.privateKey.serialize())
                    .commit()
            }else if (store.containsKey(KEY_ACI_IDENTITY_PUBLIC_KEY)){
                store
                    .beginWrite()
                    .remove(KEY_ACI_IDENTITY_PUBLIC_KEY)
                    .remove(KEY_ACI_IDENTITY_PRIVATE_KEY)
                    .commit()
            }

        }
    }

    /** set the Old ACI identity key pair. */
    fun setOldAciIdentityKey(aciKeys: IdentityKeyPair?){
        synchronized(this) {
            L.i { "Set the Old ACI identity key pair." }
            if(aciKeys != null){
                store
                    .beginWrite()
                    .putBlob(KEY_ACI_IDENTITY_OLD_PUBLIC_KEY, aciKeys.publicKey.serialize())
                    .putBlob(KEY_ACI_IDENTITY_OLD_PRIVATE_KEY, aciKeys.privateKey.serialize())
                    .commit()
            }else if (store.containsKey(KEY_ACI_IDENTITY_OLD_PUBLIC_KEY)) {
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

    @get:JvmName("aciPreKeys")
    val aciPreKeys: PreKeyMetadataStore = object : PreKeyMetadataStore {
        override var nextSignedPreKeyId: Int by integerValue(KEY_ACI_NEXT_SIGNED_PREKEY_ID, SecureRandom().nextInt(Medium.MAX_VALUE))
        override var activeSignedPreKeyId: Int by integerValue(KEY_ACI_ACTIVE_SIGNED_PREKEY_ID, -1)
        override var isSignedPreKeyRegistered: Boolean by booleanValue(KEY_ACI_SIGNED_PREKEY_REGISTERED, false)
        override var signedPreKeyFailureCount: Int by integerValue(KEY_ACI_SIGNED_PREKEY_FAILURE_COUNT, 0)
        override var nextOneTimePreKeyId: Int by integerValue(KEY_ACI_NEXT_ONE_TIME_PREKEY_ID, SecureRandom().nextInt(Medium.MAX_VALUE))
    }

    var deviceId: Int by integerValue(KEY_DEVICE_ID, 1)

    /** Do not alter. If you need to migrate more stuff, create a new method. */
    private fun migrateFromSharedPrefsV1(context: Context) {
        L.i { "[V1] Migrating account values from shared prefs." }

        putString(KEY_ACI, TextSecurePreferences.getStringPreference(context, "pref_local_uuid", null))
        putString(KEY_E164, TextSecurePreferences.getStringPreference(context, "pref_local_number", null))
        putString(KEY_SERVICE_PASSWORD, TextSecurePreferences.getStringPreference(context, "pref_gcm_password", null))
        putBoolean(KEY_IS_REGISTERED, TextSecurePreferences.getBooleanPreference(context, "pref_gcm_registered", false))
        putInteger(KEY_REGISTRATION_ID, TextSecurePreferences.getIntegerPreference(context, "pref_local_registration_id", 0))
        putBoolean(KEY_FCM_ENABLED, !TextSecurePreferences.getBooleanPreference(context, "pref_gcm_disabled", false))
        putString(KEY_FCM_TOKEN, TextSecurePreferences.getStringPreference(context, "pref_gcm_registration_id", null))
        putInteger(KEY_FCM_TOKEN_VERSION, TextSecurePreferences.getIntegerPreference(context, "pref_gcm_registration_id_version", 0))
        putLong(KEY_FCM_TOKEN_LAST_SET_TIME, TextSecurePreferences.getLongPreference(context, "pref_gcm_registration_id_last_set_time", 0))
    }

    /** Do not alter. If you need to migrate more stuff, create a new method. */
    private fun migrateFromSharedPrefsV2(context: Context) {
        L.i { "[V2] Migrating account values from shared prefs." }

        val masterSecretPrefs: SharedPreferences = context.getSharedPreferences("SecureSMS-Preferences", 0)
        val defaultPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        val storeWriter: KeyValueStore.Writer = store.beginWrite()

        if (masterSecretPrefs.hasStringData("pref_identity_public_v3")) {
            L.i { "Migrating modern identity key." }

            val identityPublic = Base64.decode(masterSecretPrefs.getString("pref_identity_public_v3", null)!!)
            val identityPrivate = Base64.decode(masterSecretPrefs.getString("pref_identity_private_v3", null)!!)

            storeWriter
                .putBlob(KEY_ACI_IDENTITY_PUBLIC_KEY, identityPublic)
                .putBlob(KEY_ACI_IDENTITY_PRIVATE_KEY, identityPrivate)
        } else if (masterSecretPrefs.hasStringData("pref_identity_public_curve25519")) {
            L.i { "Migrating legacy identity key." }

            val masterCipher = MasterCipher(KeyCachingService.getMasterSecret(context))
            val identityPublic = Base64.decode(masterSecretPrefs.getString("pref_identity_public_curve25519", null)!!)
            val identityPrivate = masterCipher.decryptKey(Base64.decode(masterSecretPrefs.getString("pref_identity_private_curve25519", null)!!)).serialize()

            storeWriter
                .putBlob(KEY_ACI_IDENTITY_PUBLIC_KEY, identityPublic)
                .putBlob(KEY_ACI_IDENTITY_PRIVATE_KEY, identityPrivate)
        } else {
            L.w { "No pre-existing identity key! No migration." }
        }

        storeWriter
            .putInteger(KEY_ACI_NEXT_SIGNED_PREKEY_ID, defaultPrefs.getInt("pref_next_signed_pre_key_id", SecureRandom().nextInt(Medium.MAX_VALUE)))
            .putInteger(KEY_ACI_ACTIVE_SIGNED_PREKEY_ID, defaultPrefs.getInt("pref_active_signed_pre_key_id", -1))
            .putInteger(KEY_ACI_NEXT_ONE_TIME_PREKEY_ID, defaultPrefs.getInt("pref_next_pre_key_id", SecureRandom().nextInt(Medium.MAX_VALUE)))
            .putInteger(KEY_ACI_SIGNED_PREKEY_FAILURE_COUNT, defaultPrefs.getInt("pref_signed_prekey_failure_count", 0))
            .putBoolean(KEY_ACI_SIGNED_PREKEY_REGISTERED, defaultPrefs.getBoolean("pref_signed_prekey_registered", false))
            .commit()

        masterSecretPrefs
            .edit()
            .remove("pref_identity_public_v3")
            .remove("pref_identity_private_v3")
            .remove("pref_identity_public_curve25519")
            .remove("pref_identity_private_curve25519")
            .commit()

        defaultPrefs
            .edit()
            .remove("pref_local_uuid")
            .remove("pref_identity_public_v3")
            .remove("pref_next_signed_pre_key_id")
            .remove("pref_active_signed_pre_key_id")
            .remove("pref_signed_prekey_failure_count")
            .remove("pref_signed_prekey_registered")
            .remove("pref_next_pre_key_id")
            .remove("pref_gcm_password")
            .remove("pref_gcm_registered")
            .remove("pref_local_registration_id")
            .remove("pref_gcm_disabled")
            .remove("pref_gcm_registration_id")
            .remove("pref_gcm_registration_id_version")
            .remove("pref_gcm_registration_id_last_set_time")
            .commit()
    }

    private fun SharedPreferences.hasStringData(key: String): Boolean {
        return this.getString(key, null) != null
    }
}

package com.difft.android.base.storage.user

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.schema.UserAuthData
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [StorageBoundUserManagerImpl] (issue #725 §13.1, Task 7).
 *
 * Verifies:
 *  - Concurrent [StorageBoundUserManagerImpl.update] calls are serialized by the
 *    internal `writeMutex` so both mutations land in the final snapshot without
 *    a lost-update race.
 *  - [StorageBoundUserManagerImpl.clearAll] clears the in-memory snapshot and
 *    both underlying DataStores back to their EMPTY defaults.
 *  - [StorageBoundUserManagerImpl.clearAuthOnly] clears only auth fields in
 *    `secure_user.pb`; UX fields in `app_state.preferences_pb` survive.
 *  - [StorageBoundUserManagerImpl.warmUp] composes the in-memory snapshot
 *    from both DataStores without reading from the legacy SP fallback path.
 *
 * Uses an in-memory [FakeDataStore] for the typed `secure_user` store (avoids
 * the kotlinx-serialization-protobuf nullable-field handling that doesn't
 * survive direct unit-test invocation) and a real Robolectric-backed
 * [PreferenceDataStoreFactory] for `app_state`. The legacy R3 SP path is not
 * exercised here — that requires Robolectric + AndroidX Keystore, which isn't
 * available in pure JVM unit tests. R3 recovery is tested at the integration
 * tier (issue #725 §13.2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StorageBoundUserManagerImplTest {

    private lateinit var scope: CoroutineScope
    private lateinit var secureUserStore: FakeDataStore<UserAuthData>
    private lateinit var appStateStore: DataStore<Preferences>
    private lateinit var appStateFile: File
    private lateinit var ctx: Context
    private lateinit var manager: StorageBoundUserManagerImpl

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ctx = ApplicationProvider.getApplicationContext()
        val stamp = System.nanoTime()
        appStateFile = File(ctx.cacheDir, "test_app_state_$stamp.preferences_pb")

        secureUserStore = FakeDataStore(initialValue = UserAuthData.EMPTY)
        appStateStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { appStateFile },
        )

        manager = StorageBoundUserManagerImpl(
            secureUserStore = secureUserStore,
            appStateStore = appStateStore,
            context = ctx,
            gson = Gson(),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        appStateFile.delete()
    }

    @Test
    fun `update concurrent calls are serialized by mutex no lost increments`() = runBlocking {
        // Each task reads the current snapshot's `account`, parses the number suffix,
        // and writes `account = "account-${n+1}"`. Without the writeMutex, two tasks
        // can read the same value and write the same n+1, losing increments. With the
        // mutex serializing the read-modify-write, after N tasks the final value must
        // be exactly "account-$N".
        //
        // This is a counter-style invariant that no copy-semantic or scheduling
        // artifact can satisfy by accident — a lost update produces a final number
        // strictly less than N.
        val n = 100
        manager.update(commit = true) {
            account = "account-0"
        }

        val tasks = (0 until n).map {
            async(Dispatchers.IO) {
                manager.update(commit = true) {
                    val previous = account ?: "account-0"
                    val previousCount = previous.removePrefix("account-").toIntOrNull() ?: 0
                    account = "account-${previousCount + 1}"
                }
            }
        }
        tasks.awaitAll()

        val finalSnapshot = manager.getUserData()
        assertNotNull(finalSnapshot, "snapshot must be present after writes")
        assertEquals(
            "account-$n",
            finalSnapshot.account,
            "Expected exactly $n increments. A lower number indicates lost updates " +
                "(read-modify-write race not serialized by writeMutex)."
        )

        // DataStore must mirror the final snapshot value.
        val storedAuth = secureUserStore.data.first()
        assertEquals("account-$n", storedAuth.account)
    }

    @Test
    fun `clearAll clears in-memory snapshot and both stores`() = runBlocking {
        // Seed both stores with non-default values, and seed the MIGRATION_VERSION marker
        // so we can verify clearAll preserves it (skipping a no-op migration re-run on next
        // cold start; keeps clearAll self-contained vs. callers' legacy-SP cleanup ordering).
        manager.update(commit = true) {
            account = "alice"
            baseAuth = "token-xyz"
            theme = 99
            lastUseTime = 12_345L
        }
        appStateStore.edit { it[AppStateKeys.MIGRATION_VERSION] = 1 }

        // Sanity: stores really hold the seeded values.
        assertEquals("alice", secureUserStore.data.first().account)
        assertEquals(99, appStateStore.data.first()[AppStateKeys.THEME])
        assertEquals(1, appStateStore.data.first()[AppStateKeys.MIGRATION_VERSION])

        manager.clearAll()

        val cleared = manager.getUserData()
        assertNotNull(cleared)
        assertNull(cleared.account)
        assertNull(cleared.baseAuth)
        assertEquals(0L, cleared.lastUseTime)

        // secure_user.pb cleared to empty payload, but migrationV1Completed must remain true
        // so SecureUserSpMigration is skipped on next cold start (avoids a no-op re-run).
        // Self-contained invariant — doesn't depend on whether legacy SP files were also wiped.
        val storedAuth = secureUserStore.data.first()
        assertEquals(UserAuthData(migrationV1Completed = true), storedAuth)
        assertEquals("", storedAuth.account)
        assertEquals("", storedAuth.baseAuth)

        // app_state cleared — typed keys absent — but MIGRATION_VERSION preserved
        // so AppStateMigrations 1-4 are skipped on next cold start (same reasoning as above).
        val appPrefs = appStateStore.data.first()
        assertNull(appPrefs[AppStateKeys.THEME])
        assertNull(appPrefs[AppStateKeys.LAST_USE_TIME])
        assertEquals(1, appPrefs[AppStateKeys.MIGRATION_VERSION])
    }

    @Test
    fun `clearAuthOnly clears session credentials, preserves identity and app_state`() = runBlocking {
        // Seed both halves with identity, session credentials, and UX state.
        // commit = true so the seed is persisted before clearAuthOnly() runs and
        // before the post-write disk assertions read back from the stores.
        manager.update(commit = true) {
            account = "bob"
            email = "bob@example.com"
            phoneNumber = "+1234567890"
            customUid = "bob-uid"
            baseAuth = "token-abc"
            microToken = "micro-1"
            signalingKey = "sig-1"
            passcode = "passcode-1"
            pattern = "pattern-1"
            aciIdentityPublicKey = "pubkey-1"
            aciIdentityPrivateKey = "privkey-1"
            aciIdentityKeyGenTime = 11_111L
            theme = 2
            lastUseTime = 7_777L
            textSize = 18
        }

        assertEquals("bob", secureUserStore.data.first().account)
        assertEquals(2, appStateStore.data.first()[AppStateKeys.THEME])

        manager.clearAuthOnly()

        val after = manager.getUserData()
        assertNotNull(after)
        // Identity fields preserved so re-login screen can show "logged out as <account>".
        assertEquals("bob", after.account)
        assertEquals("bob@example.com", after.email)
        assertEquals("+1234567890", after.phoneNumber)
        assertEquals("bob-uid", after.customUid)
        // Session credentials cleared.
        assertNull(after.baseAuth)
        assertNull(after.microToken)
        assertNull(after.signalingKey)
        assertNull(after.passcode)
        assertNull(after.pattern)
        assertNull(after.aciIdentityPublicKey)
        assertNull(after.aciIdentityPrivateKey)
        assertEquals(0L, after.aciIdentityKeyGenTime)
        // UX fields preserved.
        assertEquals(2, after.theme)
        assertEquals(7_777L, after.lastUseTime)
        assertEquals(18, after.textSize)

        // secure_user.pb: identity retained, session credentials cleared.
        // CRITICAL: the in-memory snapshot and the persisted store must agree on
        // identity. Writing UserAuthData.EMPTY here would lose `account` on the
        // next cold start when warmUp() re-reads the store.
        //
        // Note: UserAuthData uses non-nullable Strings with "" as the cleared sentinel
        // (kotlinx-serialization-protobuf does not support nullable properties). The
        // mapper converts "" ↔ null at the UserData boundary above.
        val storedAuth = secureUserStore.data.first()
        assertEquals("bob", storedAuth.account)
        assertEquals("bob@example.com", storedAuth.email)
        assertEquals("+1234567890", storedAuth.phoneNumber)
        assertEquals("bob-uid", storedAuth.customUid)
        assertEquals("", storedAuth.baseAuth)
        assertEquals("", storedAuth.microToken)
        assertEquals("", storedAuth.signalingKey)
        assertEquals("", storedAuth.passcode)
        assertEquals("", storedAuth.pattern)
        assertEquals("", storedAuth.aciIdentityPublicKey)
        assertEquals("", storedAuth.aciIdentityPrivateKey)
        assertEquals(0L, storedAuth.aciIdentityKeyGenTime)

        // app_state untouched.
        val appPrefs = appStateStore.data.first()
        assertEquals(2, appPrefs[AppStateKeys.THEME])
        assertEquals(7_777L, appPrefs[AppStateKeys.LAST_USE_TIME])
        assertEquals(18, appPrefs[AppStateKeys.TEXT_SIZE])
    }

    @Test
    fun `warmUp populates in-memory snapshot from DataStore without legacy SP`() = runBlocking {
        // Pre-populate the DataStores directly. No data goes through the manager,
        // so its snapshot is initially null until warmUp() composes it.
        secureUserStore.updateData {
            UserAuthData(
                account = "carol",
                baseAuth = "warm-token",
                microToken = "micro-warm",
            )
        }
        appStateStore.edit {
            it[AppStateKeys.THEME] = 5
            it[AppStateKeys.LAST_USE_TIME] = 42_000L
        }

        manager.warmUp()

        val composed = manager.getUserData()
        assertNotNull(composed)
        assertEquals("carol", composed.account)
        assertEquals("warm-token", composed.baseAuth)
        assertEquals("micro-warm", composed.microToken)
        assertEquals(5, composed.theme)
        assertEquals(42_000L, composed.lastUseTime)
    }

    @Test
    fun `updateAuth and updateAppState route correctly to their stores`() = runBlocking {
        // Verifies the suspend-aware API entry points wire to the right
        // DataStores without going through the legacy UserData blob façade.
        manager.updateAuth {
            copy(account = "dave", baseAuth = "auth-direct")
        }
        manager.updateAppState(AppStateKeys.THEME, 7)

        val auth = secureUserStore.data.first()
        assertEquals("dave", auth.account)
        assertEquals("auth-direct", auth.baseAuth)

        val appPrefs = appStateStore.data.first()
        assertEquals(7, appPrefs[AppStateKeys.THEME])

        // In-memory snapshot reflects both writes.
        val snapshot = manager.getUserData()
        assertNotNull(snapshot)
        assertEquals("dave", snapshot.account)
        assertEquals(7, snapshot.theme)
    }

    @Test
    fun `updateAuth preserves migrationV1Completed marker`() = runBlocking {
        // Regression guard for the marker-reset bug: UserAuthDataMapper.fromUserData()
        // always returns migrationV1Completed=false, so a naive `updateData { newAuth }`
        // would reset the marker on every auth write — which would re-trigger
        // SecureUserSpMigration on the next cold start and overwrite the just-written
        // credentials with stale legacy SP data.
        secureUserStore.updateData { UserAuthData(account = "alice", migrationV1Completed = true) }
        manager.warmUp()

        manager.updateAuth { copy(baseAuth = "new-token") }

        val auth = secureUserStore.data.first()
        assertEquals("alice", auth.account)
        assertEquals("new-token", auth.baseAuth)
        assertEquals(true, auth.migrationV1Completed)
    }

    @Test
    fun `update preserves migrationV1Completed marker on auth-field change`() = runBlocking {
        // Same marker-reset bug, via the legacy UserData blob façade. writeAll() routes
        // auth changes through UserDataFieldRouter.diff() whose newAuth always carries
        // migrationV1Completed=false.
        secureUserStore.updateData { UserAuthData(account = "bob", migrationV1Completed = true) }
        manager.warmUp()

        manager.update(commit = true) { baseAuth = "blob-token" }

        val auth = secureUserStore.data.first()
        assertEquals("bob", auth.account)
        assertEquals("blob-token", auth.baseAuth)
        assertEquals(true, auth.migrationV1Completed)
    }

}

/**
 * Minimal in-memory `DataStore<T>` for unit tests. Holds the latest value in a
 * [MutableStateFlow] and serializes [updateData] calls under a [Mutex] (matching
 * the real DataStore's update-actor semantics).
 *
 * Avoids the kotlinx-serialization-protobuf `'null' is not supported for
 * optional properties` error that comes up when round-tripping `UserAuthData`
 * (which has `String? = null` defaults) through `UserAuthDataSerializer` outside
 * the production `EncryptedSerializer` wrapper. The serializer is exercised by
 * its own dedicated test; this fake keeps the focus on
 * [StorageBoundUserManagerImpl]'s logic.
 */
private class FakeDataStore<T>(initialValue: T) : DataStore<T> {
    private val state = MutableStateFlow(initialValue)
    private val mutex = Mutex()

    override val data: Flow<T> get() = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
        val updated = transform(state.value)
        state.value = updated
        updated
    }
}

package com.difft.android.base.storage.user

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.UnavailableDataStore
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

        
        val storedAuth = secureUserStore.data.first()
        assertEquals("account-$n", storedAuth.account)
    }

    @Test
    fun `clearAll clears in-memory snapshot and both stores`() = runBlocking {
        
        
        
        manager.update(commit = true) {
            account = "alice"
            baseAuth = "token-xyz"
            theme = 99
            lastUseTime = 12_345L
        }
        appStateStore.edit { it[AppStateKeys.MIGRATION_VERSION] = 1 }

        
        assertEquals("alice", secureUserStore.data.first().account)
        assertEquals(99, appStateStore.data.first()[AppStateKeys.THEME])
        assertEquals(1, appStateStore.data.first()[AppStateKeys.MIGRATION_VERSION])

        manager.clearAll()

        val cleared = manager.getUserData()
        assertNotNull(cleared)
        assertNull(cleared.account)
        assertNull(cleared.baseAuth)
        assertEquals(0L, cleared.lastUseTime)

        
        
        
        val storedAuth = secureUserStore.data.first()
        assertEquals(UserAuthData(migrationV1Completed = true), storedAuth)
        assertEquals("", storedAuth.account)
        assertEquals("", storedAuth.baseAuth)

        
        
        val appPrefs = appStateStore.data.first()
        assertNull(appPrefs[AppStateKeys.THEME])
        assertNull(appPrefs[AppStateKeys.LAST_USE_TIME])
        assertEquals(1, appPrefs[AppStateKeys.MIGRATION_VERSION])
    }

    @Test
    fun `clearAuthOnly clears session credentials, preserves identity and app_state`() = runBlocking {
        
        
        
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
            
            
            
            proxyShareLink = "ytp://config?d=seeded-link"
            proxyEnabled = true
            theme = 2
            lastUseTime = 7_777L
            textSize = 18
            chatTheme = 1
        }

        assertEquals("bob", secureUserStore.data.first().account)
        assertEquals(2, appStateStore.data.first()[AppStateKeys.THEME])

        manager.clearAuthOnly()

        val after = manager.getUserData()
        assertNotNull(after)
        
        assertEquals("bob", after.account)
        assertEquals("bob@example.com", after.email)
        assertEquals("+1234567890", after.phoneNumber)
        assertEquals("bob-uid", after.customUid)
        
        assertNull(after.baseAuth)
        assertNull(after.microToken)
        assertNull(after.signalingKey)
        assertNull(after.passcode)
        assertNull(after.pattern)
        assertNull(after.aciIdentityPublicKey)
        assertNull(after.aciIdentityPrivateKey)
        assertEquals(0L, after.aciIdentityKeyGenTime)
        
        assertNull(after.proxyShareLink)
        assertEquals(false, after.proxyEnabled)
        
        assertEquals(2, after.theme)
        assertEquals(7_777L, after.lastUseTime)
        assertEquals(18, after.textSize)
        assertEquals(1, after.chatTheme)

        
        
        
        
        
        
        
        
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
        
        assertEquals("", storedAuth.proxyShareLink)
        assertEquals(false, storedAuth.proxyEnabled)

        
        val appPrefs = appStateStore.data.first()
        assertEquals(2, appPrefs[AppStateKeys.THEME])
        assertEquals(7_777L, appPrefs[AppStateKeys.LAST_USE_TIME])
        assertEquals(18, appPrefs[AppStateKeys.TEXT_SIZE])
        assertEquals(1, appPrefs[AppStateKeys.CHAT_THEME])
    }

    @Test
    fun `warmUp populates in-memory snapshot from DataStore without legacy SP`() = runBlocking {
        
        
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
        
        
        manager.updateAuth {
            copy(account = "dave", baseAuth = "auth-direct")
        }
        manager.updateAppState(AppStateKeys.THEME, 7)

        val auth = secureUserStore.data.first()
        assertEquals("dave", auth.account)
        assertEquals("auth-direct", auth.baseAuth)

        val appPrefs = appStateStore.data.first()
        assertEquals(7, appPrefs[AppStateKeys.THEME])

        
        val snapshot = manager.getUserData()
        assertNotNull(snapshot)
        assertEquals("dave", snapshot.account)
        assertEquals(7, snapshot.theme)
    }

    @Test
    fun `updateAuth preserves migrationV1Completed marker`() = runBlocking {
        
        
        
        
        
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
        
        
        
        secureUserStore.updateData { UserAuthData(account = "bob", migrationV1Completed = true) }
        manager.warmUp()

        manager.update(commit = true) { baseAuth = "blob-token" }

        val auth = secureUserStore.data.first()
        assertEquals("bob", auth.account)
        assertEquals("blob-token", auth.baseAuth)
        assertEquals(true, auth.migrationV1Completed)
    }

    
    @Test
    fun `warmUp with UnavailableDataStore yields not-logged-in snapshot`() = runBlocking {
        val degradedManager = StorageBoundUserManagerImpl(
            secureUserStore = UnavailableDataStore(UserAuthData.EMPTY),
            appStateStore = appStateStore,
            context = ctx,
            gson = Gson(),
        )

        degradedManager.warmUp()

        val composed = degradedManager.getUserData()
        assertNotNull(composed, "warmUp must complete and produce a snapshot")
        assertNull(composed.baseAuth, "baseAuth must be null → isLoggedIn=false")
        assertNull(composed.account)
    }

}





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

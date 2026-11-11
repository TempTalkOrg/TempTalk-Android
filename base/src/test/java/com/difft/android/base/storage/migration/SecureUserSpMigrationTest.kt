package com.difft.android.base.storage.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.user.UserData
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.base.utils.globalServices
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue







@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SecureUserSpMigrationTest {

    private lateinit var context: Context
    private lateinit var legacyPrefs: SharedPreferences

    private companion object {
        const val KEY_USERDATA =
            "com.difft.chative.base.user.SimpleUserManager\$Companion.SHARED_PREFERENCES_KEY_USERDATA"
        const val KEY_BASIC_AUTH = "basic_auth"
        const val KEY_MICRO_TOKEN = "micro_token"
        const val KEY_SIGNALING_KEY = "signaling_key"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        legacyPrefs = context.getSharedPreferences("test_secure_prefs", Context.MODE_PRIVATE)
        legacyPrefs.edit().clear().commit()

        
        
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        val entryPoint = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { entryPoint.gson } returns Gson()
        every { globalServices } returns entryPoint
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        legacyPrefs.edit().clear().commit()
    }

    
    private fun migrationWithPrefs() = SecureUserSpMigration(context) { legacyPrefs }

    
    private fun migrationFreshInstall() = SecureUserSpMigration(context) { null }

    private fun seedStandalone(
        basicAuth: String? = null,
        microToken: String? = null,
        signalingKey: String? = null,
    ) {
        legacyPrefs.edit().apply {
            basicAuth?.let { putString(KEY_BASIC_AUTH, it) }
            microToken?.let { putString(KEY_MICRO_TOKEN, it) }
            signalingKey?.let { putString(KEY_SIGNALING_KEY, it) }
        }.commit()
    }

    private fun seedBlob(userData: UserData) {
        legacyPrefs.edit().putString(KEY_USERDATA, Gson().toJson(userData)).commit()
    }

    
    @Test
    fun `case A standalone-only legacy populates auth from standalone keys`() = runBlocking {
        
        seedBlob(UserData(account = "+12312345678", baseAuth = null, microToken = null))
        seedStandalone(basicAuth = "basic-XYZ", microToken = "micro-XYZ")

        val result = migrationWithPrefs().migrate(UserAuthData.EMPTY)

        assertEquals("+12312345678", result.account)
        assertEquals("basic-XYZ", result.baseAuth)
        assertEquals("micro-XYZ", result.microToken)
        assertEquals("", result.signalingKey) 
        assertTrue(result.migrationV1Completed)
    }

    
    
    
    @Test
    fun `case A standalone keys read even when blob key absent`() = runBlocking {
        seedStandalone(basicAuth = "basic-NOBLOB", signalingKey = "sig-NOBLOB")

        val result = migrationWithPrefs().migrate(UserAuthData.EMPTY)

        assertEquals("basic-NOBLOB", result.baseAuth)
        assertEquals("sig-NOBLOB", result.signalingKey)
        assertEquals("", result.microToken) 
        assertEquals("", result.account)
        assertTrue(result.migrationV1Completed)
    }

    
    @Test
    fun `case B normal blob not clobbered by empty standalone keys`() = runBlocking {
        seedBlob(
            UserData(
                account = "+12312345678",
                baseAuth = "blob-auth",
                microToken = "blob-micro",
                signalingKey = "blob-sig",
            ),
        )
        
        val result = migrationWithPrefs().migrate(UserAuthData.EMPTY)

        assertEquals("blob-auth", result.baseAuth)
        assertEquals("blob-micro", result.microToken)
        assertEquals("blob-sig", result.signalingKey)
        assertTrue(result.migrationV1Completed)
    }

    
    
    @Test
    fun `case D no clobber preserves blob value over standalone`() = runBlocking {
        seedBlob(
            UserData(
                account = "+12312345678",
                baseAuth = "FRESH-blob",
                microToken = "FRESH-micro",
            ),
        )
        seedStandalone(basicAuth = "STALE-standalone", microToken = "STALE-micro")

        val result = migrationWithPrefs().migrate(UserAuthData.EMPTY)

        
        assertEquals("FRESH-blob", result.baseAuth)
        assertEquals("FRESH-micro", result.microToken)
        assertTrue(result.migrationV1Completed)
    }

    
    @Test
    fun `case E fresh install stamps marker and stays empty`() = runBlocking {
        val result = migrationFreshInstall().migrate(UserAuthData.EMPTY)

        assertEquals("", result.baseAuth)
        assertEquals("", result.microToken)
        assertEquals("", result.signalingKey)
        assertEquals("", result.account)
        assertTrue(result.migrationV1Completed)
    }

    
    
    

    @Test
    fun `case G unrecoverable when blob baseAuth empty and standalone also empty`() = runBlocking {
        
        seedBlob(UserData(account = "+12312345678", baseAuth = null, microToken = null))

        val result = migrationWithPrefs().migrate(UserAuthData.EMPTY)

        assertEquals("", result.baseAuth) 
        assertEquals("+12312345678", result.account) 
        assertTrue(result.migrationV1Completed)
    }

    
    @Test
    fun `case F shouldMigrate false when v1 already completed`() = runBlocking {
        val done = UserAuthData(migrationV1Completed = true)
        assertFalse(migrationWithPrefs().shouldMigrate(done))
    }

    
    @Test
    fun `shouldMigrate true when v1 not completed`() = runBlocking {
        assertTrue(migrationWithPrefs().shouldMigrate(UserAuthData.EMPTY))
    }
}

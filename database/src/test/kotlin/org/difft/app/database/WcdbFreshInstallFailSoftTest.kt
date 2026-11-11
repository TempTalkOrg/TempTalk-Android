package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.utils.appScope
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlin.test.assertNull













@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WcdbFreshInstallFailSoftTest {

    private lateinit var ctx: Context
    private val forwarded = AtomicReference<Throwable?>(null)
    private var previousDefault: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(WCDB.DATABASE_NAME) 

        mockkObject(WCDBKeyManager)
        
        every { WCDBKeyManager.getOrCreateKey(any()) } throws
            WCDBKeyUnavailableException("create failed", IllegalStateException("TEE dead"))

        
        previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> forwarded.set(throwable) }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousDefault)
        ctx.deleteDatabase(WCDB.DATABASE_NAME)
        unmockkAll()
    }

    @Test
    fun `T-E1 headless DB touch during fresh-install create-fail does not FATAL and leaves DB dir empty`() {
        val wcdb = WCDB(ctx, TestScope())
        val dbFile = ctx.getDatabasePath(WCDB.DATABASE_NAME)

        
        val job = appScope.launch { wcdb.resolveCipherKeyOnce() }
        runBlocking { job.join() }

        assertNull(forwarded.get(), "fresh-install key create-fail must NOT reach a FATAL uncaught handler")
        assertFalse(dbFile.exists(), "no partial/corrupt DB file may be written on the create-fail path")
    }
}

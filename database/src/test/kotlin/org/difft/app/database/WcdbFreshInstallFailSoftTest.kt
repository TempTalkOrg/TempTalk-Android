package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.utils.appScope
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
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

/**
 * Fresh-install cipher-key create-fail on a headless path: DB file absent, `wcdb_key.bin` absent,
 * `generateKey()` throws, and a headless `appScope.launch { … }` touches WCDB before MainActivity's
 * routing. Acceptance: no FATAL — the shared [com.difft.android.base.utils.dbKeyFailSoftExceptionHandler]
 * wired into the real [appScope] swallows the [WCDBKeyUnavailableException] and records a single
 * non-fatal Crashlytics breadcrumb; the DB directory stays empty so the next process retry can
 * re-attempt the key create.
 *
 * The headless touch is driven through the `resolveCipherKeyOnce()` choke point — the same
 * exception a real `appScope.launch { wcdb.<table>… }` surfaces once the `db` lazy runs
 * `setCipherKey` on a device (a DEBUG unit build skips `setCipherKey` and would need the native
 * WCDB lib, so this drives the equivalent choke point directly).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WcdbFreshInstallFailSoftTest {

    private lateinit var ctx: Context
    private lateinit var crashlytics: FirebaseCrashlytics
    private val forwarded = AtomicReference<Throwable?>(null)
    private var previousDefault: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(WCDB.DATABASE_NAME) // fresh install: ensure no DB file present

        mockkObject(WCDBKeyManager)
        // Fresh-install create-fail: generateKey() throws (dead TEE / daemon).
        every { WCDBKeyManager.getOrCreateKey(any()) } throws
            WCDBKeyUnavailableException("create failed", IllegalStateException("TEE dead"))

        crashlytics = mockk(relaxed = true)
        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns crashlytics

        // Capture any throwable that escapes to a FATAL (the CEH must swallow ours instead).
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

        // Headless coroutine on the REAL appScope (which carries dbKeyFailSoftExceptionHandler).
        val job = appScope.launch { wcdb.resolveCipherKeyOnce() }
        runBlocking { job.join() }

        assertNull(forwarded.get(), "fresh-install key create-fail must NOT reach a FATAL uncaught handler")
        verify(exactly = 1) { crashlytics.recordException(any()) } // single fail-soft breadcrumb
        assertFalse(dbFile.exists(), "no partial/corrupt DB file may be written on the create-fail path")
    }
}

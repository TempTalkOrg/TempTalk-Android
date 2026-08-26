package com.difft.android.microbenchmark

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.wcdb.core.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.difft.app.database.WCDB
import org.difft.app.database.models.MessageModel
import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * First-execution gate for the benchmark module (issue #1162 phase 1): proves the two
 * runtime unknowns before any benchmark is written.
 *
 * 1. The encrypted WCDB bootstrap works under instrumentation: the release test build
 *    runs [WCDB] with `BuildConfig.DEBUG = false`, so `setCipherKey` + the Keystore key
 *    path are on the line — any Keystore failure surfaces here as a loud
 *    WCDBKeyUnavailableException at first `db` touch, never silently mid-benchmark.
 * 2. `Database.traceSQL` fires per statement in the release native build, which is the
 *    mechanism the query-count benchmarks rely on (the production `[MessageHydrator]`
 *    log line never emits in a benchmark process).
 *
 * Isolation is the test APK's package sandbox: the fixed DATABASE_NAME resolves under
 * /data/data/com.difft.android.microbenchmark.test/databases/, untouched by the app.
 */
@RunWith(AndroidJUnit4::class)
class WcdbBootstrapSmokeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var wcdb: WCDB

    @Before
    fun setUp() {
        context.deleteDatabase(WCDB.DATABASE_NAME)
        wcdb = WCDB(context, CoroutineScope(SupervisorJob()))
    }

    @After
    fun tearDown() {
        // Close first — deleteDatabase cannot remove a database an open handle holds.
        // Delete directly rather than via wcdb.deleteDatabaseFile(): its catch block
        // calls FirebaseCrashlytics unguarded, which is not initialized in this process.
        wcdb.db.close()
        context.deleteDatabase(WCDB.DATABASE_NAME)
    }

    @Test
    fun encryptedDatabaseOpensAndRoundTrips() {
        // First real open is heavy (cipher/PBKDF + header I/O) and is exactly the path
        // under test: release BuildConfig -> setCipherKey -> WCDBKeyManager -> Keystore.
        val db = wcdb.db
        assertTrue("database did not report open", db.canOpen())

        val seeded = buildMessageSequence(count = 3)
        wcdb.message.insertObjects(seeded)
        val loaded: List<MessageModel> = wcdb.message.getAllObjects()
        assertEquals(3, loaded.size)
        assertEquals(seeded.map { it.id }.toSet(), loaded.map { it.id }.toSet())
    }

    @Test
    fun traceSqlFiresPerStatementInReleaseBuild() {
        val db = wcdb.db

        val statements = AtomicInteger(0)
        db.traceSQL { _, _, _, sql, _ ->
            if (sql.isNotBlank()) statements.incrementAndGet()
        }

        wcdb.message.insertObjects(buildMessageSequence(count = 2))
        wcdb.message.getAllObjects()

        val traced = statements.get()
        db.traceSQL(null)
        println("traceSQL spike: tracedStatements=$traced")
        assertTrue("traceSQL reported no statements in release native build", traced > 0)
    }
}

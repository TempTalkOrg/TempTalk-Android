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
        
        
        
        wcdb.db.close()
        context.deleteDatabase(WCDB.DATABASE_NAME)
    }

    @Test
    fun encryptedDatabaseOpensAndRoundTrips() {
        
        
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

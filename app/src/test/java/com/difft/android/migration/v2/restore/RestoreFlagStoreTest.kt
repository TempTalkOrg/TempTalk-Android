package com.difft.android.migration.v2.restore

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.migration.model.RestorePhase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the phase-aware [RestoreFlagStore] (A4 c4 — design §3/§5). No Keystore, no
 * native WCDB — just Gson + atomic file IO under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RestoreFlagStoreTest {

    private lateinit var context: Context
    private lateinit var store: RestoreFlagStore

    private companion object {
        const val ACCOUNT = "+12025550100"
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = RestoreFlagStore(context)
        store.clear()
    }

    @After
    fun teardown() {
        store.clear()
    }

    @Test
    fun `begin writes IMPORTING phase and the source uri`() {
        assertFalse(store.exists())
        store.begin("content://backup.ttmig", ACCOUNT)

        assertTrue(store.exists())
        val flag = store.read()!!
        assertEquals(RestoreFlagStore.FLAG_STATE_RESTORING, flag.state)
        assertEquals("content://backup.ttmig", flag.sourceBackupUri)
        assertEquals(RestorePhase.IMPORTING.name, flag.phase)
        assertEquals(RestorePhase.IMPORTING, store.currentPhase())
        assertEquals(ACCOUNT, flag.account, "the flag binds the restore to the account whose data it puts on disk")
        assertFalse(flag.identityPublishFailed)
    }

    @Test
    fun `advance moves the phase and preserves startedAt plus source`() {
        store.begin("content://x.ttmig", ACCOUNT)
        val started = store.read()!!.startedAt

        store.advance(RestorePhase.PROMOTING)
        store.read()!!.let {
            assertEquals(RestorePhase.PROMOTING.name, it.phase)
            assertEquals(started, it.startedAt, "advance must preserve startedAt")
            assertEquals("content://x.ttmig", it.sourceBackupUri)
        }

        store.advance(RestorePhase.SIGNING_IN)
        assertEquals(RestorePhase.SIGNING_IN, store.currentPhase())
        assertEquals(ACCOUNT, store.read()!!.account, "advance must preserve the account binding")
    }

    @Test
    fun `markIdentityPublishFailed flips only that field and survives a later advance`() {
        store.begin("content://x.ttmig", ACCOUNT)
        store.advance(RestorePhase.SIGNING_IN)

        store.markIdentityPublishFailed()

        store.read()!!.let {
            assertTrue(it.identityPublishFailed)
            assertEquals(RestorePhase.SIGNING_IN.name, it.phase, "the phase is untouched — the data is still on disk")
            assertEquals(ACCOUNT, it.account)
        }
        store.advance(RestorePhase.SIGNING_IN)
        assertTrue(store.read()!!.identityPublishFailed, "advance must carry the marker")
    }

    @Test
    fun `consumeCompleted is false for every phase but COMPLETED, then true exactly once`() {
        assertFalse(store.consumeCompleted(), "no flag")
        store.begin("content://x.ttmig", ACCOUNT)
        assertFalse(store.consumeCompleted(), "IMPORTING")
        store.advance(RestorePhase.SIGNING_IN)
        assertFalse(store.consumeCompleted(), "SIGNING_IN")
        assertTrue(store.exists(), "a non-COMPLETED flag is left untouched")

        store.advance(RestorePhase.COMPLETED)
        assertTrue(store.consumeCompleted())
        assertFalse(store.consumeCompleted(), "one-shot")
        assertFalse(store.exists())
    }

    @Test
    fun `markIdentityPublishFailed with no flag is a no-op`() {
        store.markIdentityPublishFailed()
        assertFalse(store.exists())
    }

    @Test
    fun `a flag written without the new keys reads back with account null and marker false`() {
        // Gson skips the constructor, so the account is NOT "" for an old flag — it is null. The
        // launcher treats a null account as "binds nobody"; this pins the shape it relies on.
        File(context.filesDir, RestoreFlagStore.FLAG_FILE_NAME).writeText(
            """{"state":"RESTORING","startedAt":1,"sourceBackupUri":"x","phase":"SIGNING_IN"}"""
        )
        val flag = store.read()!!
        assertNull(flag.account)
        assertFalse(flag.identityPublishFailed)
    }

    @Test
    fun `advance with no prior flag creates one (phase never lost)`() {
        assertFalse(store.exists())
        store.advance(RestorePhase.PROMOTING)
        assertTrue(store.exists())
        assertEquals(RestorePhase.PROMOTING, store.currentPhase())
    }

    @Test
    fun `clear removes the flag`() {
        store.begin("content://x.ttmig", ACCOUNT)
        assertTrue(store.exists())
        store.clear()
        assertFalse(store.exists())
        assertNull(store.read())
        assertNull(store.currentPhase())
    }

    @Test
    fun `write leaves no temp file behind`() {
        store.begin("content://x.ttmig", ACCOUNT)
        val tmp = File(context.filesDir, RestoreFlagStore.TMP_FILE_NAME)
        assertFalse(tmp.exists(), "atomic write must not leave a .tmp file after success")
    }

    @Test
    fun `unknown phase string falls back to IMPORTING`() {
        assertEquals(RestorePhase.IMPORTING, RestorePhase.fromName("WAT"))
        assertEquals(RestorePhase.IMPORTING, RestorePhase.fromName(null))
        assertEquals(RestorePhase.SIGNING_IN, RestorePhase.fromName("SIGNING_IN"))
    }
}

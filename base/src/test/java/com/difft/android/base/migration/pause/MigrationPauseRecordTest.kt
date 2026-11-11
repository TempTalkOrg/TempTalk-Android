package com.difft.android.base.migration.pause

import com.difft.android.base.migration.pause.MigrationPauseOutcome.FAILED
import com.difft.android.base.migration.pause.MigrationPauseOutcome.NOT_COMMITTED
import com.difft.android.base.migration.pause.MigrationPauseOutcome.SUCCESS
import com.difft.android.base.migration.pause.MigrationPauseOutcome.UNKNOWN
import com.difft.android.base.migration.pause.MigrationPausePhase.AWAITING_DECISION
import com.difft.android.base.migration.pause.MigrationPausePhase.AWAITING_LOGIN
import com.difft.android.base.migration.pause.MigrationPausePhase.COMMITTING
import com.difft.android.base.migration.pause.MigrationPausePhase.EXPORTING
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue


class MigrationPauseRecordTest {

    private val owner = "11111111-aaaa-bbbb-cccc-000000000001"
    private val other = "22222222-aaaa-bbbb-cccc-000000000002"

    private fun exporting() = MigrationPauseRecord(owner, EXPORTING, "scope", "cred", null, 1_000L)
    private fun committing() = exporting().committing(owner, "session").next()
    private fun awaitingLogin() = committing().finishing(owner, null, SUCCESS).next()
    private fun awaitingDecision() = committing().finishing(owner, null, UNKNOWN).next()

    private fun PauseStep.next(): MigrationPauseRecord = (this as PauseStep.Applied).next!!

    private fun PauseStep.cleared(): Boolean = this is PauseStep.Applied && next == null

    @Test
    fun `committing moves only an exporting record of the same owner`() {
        val committed = committing()
        assertEquals(COMMITTING, committed.phase)
        assertEquals("session", committed.sessionId)
        assertEquals(PauseStep.Applied(committed), committed.committing(owner, "session"))
        assertIs<PauseStep.Skipped>(committed.committing(owner, "later"))
        assertEquals(PauseStep.OtherExport, exporting().committing(other, "session"))
        assertIs<PauseStep.Skipped>(awaitingDecision().committing(owner, "session"))
    }

    @Test
    fun `finish success awaits login, failure clears, unknown awaits decision`() {
        assertEquals(AWAITING_LOGIN, awaitingLogin().phase)
        assertTrue(committing().finishing(owner, null, FAILED).cleared())
        assertEquals(AWAITING_DECISION, awaitingDecision().phase)
    }

    @Test
    fun `before the commit marker an unknown or never-committed end clears`() {
        assertTrue(exporting().finishing(owner, null, UNKNOWN).cleared())
        assertTrue(exporting().finishing(owner, null, NOT_COMMITTED).cleared())
        assertTrue(committing().finishing(owner, null, NOT_COMMITTED).cleared())
    }

    @Test
    fun `a decision pending is not cleared by a never-committed claim`() {
        assertIs<PauseStep.Skipped>(awaitingDecision().finishing(owner, null, NOT_COMMITTED))
    }

    @Test
    fun `a confirmed import is final`() {
        val imported = awaitingLogin()
        assertEquals(PauseStep.Applied(imported), imported.finishing(owner, null, SUCCESS))
        assertIs<PauseStep.Skipped>(imported.finishing(owner, null, FAILED))
        assertIs<PauseStep.Skipped>(imported.finishing(owner, null, UNKNOWN))
        assertIs<PauseStep.Skipped>(imported.finishing(owner, null, NOT_COMMITTED))
    }

    @Test
    fun `awaiting decision resolves only on a definite verdict`() {
        val undecided = awaitingDecision()
        assertEquals(AWAITING_LOGIN, undecided.finishing(owner, null, SUCCESS).next().phase)
        assertTrue(undecided.finishing(owner, null, FAILED).cleared())
        assertEquals(PauseStep.Applied(undecided), undecided.finishing(owner, null, UNKNOWN))
    }

    @Test
    fun `a settlement names the record by owner, or by session alone`() {
        val committed = committing()
        assertTrue(committed.matches(owner, null))
        assertTrue(committed.matches(owner, "session"))
        assertFalse(committed.matches(owner, "other-session"))
        assertTrue(committed.matches(null, "session"))
        assertFalse(committed.matches(null, "other-session"))
        assertFalse(committed.matches(null, null))
        assertEquals(PauseStep.OtherExport, committed.finishing(null, "other-session", FAILED))
        assertTrue(committed.finishing(null, "session", FAILED).cleared())
    }

    @Test
    fun `another owner cannot advance or release the pause`() {
        for (record in listOf(exporting(), committing(), awaitingDecision(), awaitingLogin())) {
            assertEquals(PauseStep.OtherExport, record.committing(other, "other"))
            for (outcome in MigrationPauseOutcome.entries) assertEquals(PauseStep.OtherExport, record.finishing(other, null, outcome))
        }
    }

    @Test
    fun `scope matches only the same account and credential`() {
        assertTrue(exporting().matchesScope("scope", "cred"))
        assertFalse(exporting().matchesScope("another", "cred"))
        assertFalse(exporting().matchesScope("scope", "new-cred"))
    }

    @Test
    fun `codec round-trips every phase`() {
        for (record in listOf(exporting(), committing(), awaitingLogin(), awaitingDecision())) {
            assertEquals(record, MigrationPauseRecordCodec.decode(MigrationPauseRecordCodec.encode(record)))
        }
    }

    @Test
    fun `codec pins json names and stores no raw account`() {
        val json = MigrationPauseRecordCodec.encode(committing())
        for (name in listOf("owner", "phase", "accountScope", "credentialHash", "sessionId", "startedAt")) {
            assertTrue(json.contains("\"$name\""), "missing $name in $json")
        }
    }

    @Test
    fun `codec rejects malformed or incomplete records`() {
        assertNull(MigrationPauseRecordCodec.decode("{not json"))
        assertNull(MigrationPauseRecordCodec.decode(""))
        assertNull(MigrationPauseRecordCodec.decode("""{"owner":"$owner"}"""))
        assertNull(
            MigrationPauseRecordCodec.decode(
                """{"owner":"$owner","phase":"LATER","accountScope":"s","credentialHash":"c","startedAt":1}"""
            )
        )
        assertNull(
            MigrationPauseRecordCodec.decode(
                """{"owner":"","phase":"EXPORTING","accountScope":"s","credentialHash":"c","startedAt":1}"""
            )
        )
    }

    @Test
    fun `scope hashes are stable, distinct and not the raw values`() {
        val scope = MigrationPauseScope.accountScope("prod", "+15550001")
        assertEquals(scope, MigrationPauseScope.accountScope("prod", " +15550001 "))
        assertNotEquals(scope, MigrationPauseScope.accountScope("test", "+15550001"))
        assertFalse(scope.contains("15550001"))
        
        assertEquals("K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=", MigrationPauseScope.credentialHash("secret"))
    }
}

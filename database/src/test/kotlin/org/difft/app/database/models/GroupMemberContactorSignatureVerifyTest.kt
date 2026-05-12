package org.difft.app.database.models

import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit + integration tests for [GroupMemberContactorModel.signatureVerify].
 *
 * Boxed [Boolean] is the contract: tri-state (null = unverified/failed, true =
 * verified, false = ❌ never set — see field Javadoc). Tests guard against future
 * regressions that switch to `boolean` primitive (would default to false and
 * silently bypass the Phase 3 `IS NULL` SQL filter) or to a non-null default.
 *
 * Round-trip persistence (T5–T7) is `@Ignore`d — WCDB native lib does not load
 * in JVM unit tests; matches the [JobModelRoundTripTest] precedent.
 */
class GroupMemberContactorSignatureVerifyTest {

    // ----- T1–T4: pure model-level (no WCDB native required) -----

    @Test
    fun signatureVerify_default_is_null_for_new_instance() {
        val m = GroupMemberContactorModel()
        assertNull(m.signatureVerify, "Default must be null per field contract; never primitive false")
    }

    @Test
    fun signatureVerify_set_true_reads_back_true() {
        val m = GroupMemberContactorModel().apply { signatureVerify = true }
        assertEquals(true, m.signatureVerify)
    }

    @Test
    fun signatureVerify_set_null_reads_back_null() {
        val m = GroupMemberContactorModel().apply {
            signatureVerify = true
            signatureVerify = null
        }
        assertNull(m.signatureVerify)
    }

    /** equals/hashCode must distinguish on signatureVerify so DB upserts don't collapse rows. */
    @Test
    fun equals_and_hashCode_include_signatureVerify() {
        val a = GroupMemberContactorModel().apply { id = "u1"; gid = "g1"; signatureVerify = true }
        val b = GroupMemberContactorModel().apply { id = "u1"; gid = "g1"; signatureVerify = null }
        val c = GroupMemberContactorModel().apply { id = "u1"; gid = "g1"; signatureVerify = true }

        assertNotEquals(a, b, "verify=true vs verify=null must NOT be equal")
        assertNotEquals(a.hashCode(), b.hashCode(), "hashCode must reflect verify state")
        assertEquals(a, c, "two instances with verify=true and same other fields must be equal")
        assertTrue(a.hashCode() == c.hashCode())
    }

    // ----- T5–T7: WCDB integration (ignored — native lib unavailable in JVM) -----

    /** Insert null → SQLite NULL → read back null. */
    @Test
    @Ignore("WCDB native lib not loadable in JVM unit tests; see JobModelRoundTripTest precedent")
    fun roundtrip_null_value_persisted_and_read_back_as_null() {
        // Intent doc: insert a model with signatureVerify=null, fetch it back, expect null.
    }

    /** Insert true → SQLite 1 → read back Boolean.TRUE. */
    @Test
    @Ignore("WCDB native lib not loadable in JVM unit tests; see JobModelRoundTripTest precedent")
    fun roundtrip_true_value_persisted_and_read_back_as_true() {
        // Intent doc: insert a model with signatureVerify=true, fetch it back, expect true.
    }

    /** Phase 3 SQL filter `signatureVerify IS NULL` must return only unverified rows. */
    @Test
    @Ignore("WCDB native lib not loadable in JVM unit tests; see JobModelRoundTripTest precedent")
    fun query_by_signatureVerify_isNull_returns_unverified_only() {
        // Intent doc: insert mixed null + true rows, query .isNull() filter, expect only the null rows.
    }
}

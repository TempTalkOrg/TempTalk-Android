package com.difft.android.chat.contacts

import org.difft.app.database.models.PublicAccountType
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Exhaustive pin for [PublicAccountType.resolve] — the carry-forward rule shared by the action=0
 * notify handler (ContactsUpdater) and the by-ids single-fetch upsert (ContactorUtil). Pure
 * function, no WCDB / Android — invokes production directly (not a re-implementation).
 *
 * Rule: present server value wins (incl. explicit demote); absent server value keeps prior (a
 * field-less update / refetch must not demote a known OFFICIAL); no prior → NORMAL.
 */
class ResolvePublicAccountTypeTest {

    /** server present OFFICIAL → OFFICIAL regardless of prior. */
    @Test
    fun `server OFFICIAL wins over any prior`() {
        assertEquals(PublicAccountType.OFFICIAL, PublicAccountType.resolve(serverValue = 1, priorValue = null))
        assertEquals(PublicAccountType.OFFICIAL, PublicAccountType.resolve(serverValue = 1, priorValue = PublicAccountType.NORMAL))
        assertEquals(PublicAccountType.OFFICIAL, PublicAccountType.resolve(serverValue = 1, priorValue = PublicAccountType.OFFICIAL))
    }

    /** server present NORMAL → explicit demote wins over a prior OFFICIAL. */
    @Test
    fun `server NORMAL is an explicit demote and wins over prior OFFICIAL`() {
        assertEquals(PublicAccountType.NORMAL, PublicAccountType.resolve(serverValue = 0, priorValue = PublicAccountType.OFFICIAL))
    }

    /** server absent + prior OFFICIAL → keep OFFICIAL (notify restore AND by-ids refetch pin). */
    @Test
    fun `absent server value keeps prior OFFICIAL`() {
        assertEquals(PublicAccountType.OFFICIAL, PublicAccountType.resolve(serverValue = null, priorValue = PublicAccountType.OFFICIAL))
    }

    /** server absent + prior NORMAL → NORMAL. */
    @Test
    fun `absent server value keeps prior NORMAL`() {
        assertEquals(PublicAccountType.NORMAL, PublicAccountType.resolve(serverValue = null, priorValue = PublicAccountType.NORMAL))
    }

    /** server absent + no prior row (genuinely-new id) → NORMAL default. */
    @Test
    fun `absent server value and no prior defaults to NORMAL`() {
        assertEquals(PublicAccountType.NORMAL, PublicAccountType.resolve(serverValue = null, priorValue = null))
    }

    /**
     * By-ids refetch scenario (FIX 2): a by-ids response omits publicAccountType while the local row
     * is OFFICIAL — the resolved value must stay OFFICIAL so delete+reinsert does not demote it.
     */
    @Test
    fun `by-ids refetch with absent field keeps OFFICIAL`() {
        val serverOmittedField: Int? = null
        assertEquals(
            PublicAccountType.OFFICIAL,
            PublicAccountType.resolve(serverOmittedField, PublicAccountType.OFFICIAL),
        )
    }
}

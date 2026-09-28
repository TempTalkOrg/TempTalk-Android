package com.difft.android.login.entry

import com.difft.android.login.entry.AccountSwitchPolicy.Decision as Switch
import com.difft.android.login.entry.LoginEntryPolicy.Decision as Entry
import org.junit.Assert.assertEquals
import org.junit.Test

/** The two decision tables, pinned case by case against the iOS policies they mirror. */
class LoginEntryPolicyTest {

    @Test
    fun `account switch - a pending promoted restore decides by the account it belongs to`() {
        assertEquals(Switch.RESUME_PENDING_RESTORE, AccountSwitchPolicy.decide("+1", localAccount = null, newAccount = "+1"))
        assertEquals(Switch.RESUME_PENDING_RESTORE, AccountSwitchPolicy.decide("+1", localAccount = "+1", newAccount = " +1 "))
        assertEquals(Switch.REQUIRE_REPLACEMENT, AccountSwitchPolicy.decide("+1", localAccount = null, newAccount = "+2"))
    }

    @Test
    fun `account switch - no local account is fresh, the same account comes back, another needs a wipe`() {
        assertEquals(Switch.PROCEED_FRESH, AccountSwitchPolicy.decide(null, localAccount = null, newAccount = "+2"))
        assertEquals(Switch.PROCEED_FRESH, AccountSwitchPolicy.decide("", localAccount = "", newAccount = "+2"))
        assertEquals(Switch.PROCEED_SAME_ACCOUNT, AccountSwitchPolicy.decide(null, localAccount = "+2", newAccount = "+2"))
        assertEquals(Switch.REQUIRE_REPLACEMENT, AccountSwitchPolicy.decide(null, localAccount = "+1", newAccount = "+2"))
    }

    @Test
    fun `login entry - no transfer on offer never shows migration, whatever the local state`() {
        for (pending in listOf(true, false)) for (fresh in listOf(true, false)) for (rereg in listOf(true, false)) {
            assertEquals(Entry.UNSUPPORTED, LoginEntryPolicy.decide(false, pending, fresh, rereg))
        }
    }

    @Test
    fun `login entry - with a transfer on offer the order is restore, re-registration, fresh, local state`() {
        assertEquals(Entry.RESUME_PENDING_RESTORE, LoginEntryPolicy.decide(true, hasPendingRestore = true, isFreshTarget = true, isReregistering = true))
        assertEquals(Entry.SKIP_REREGISTRATION, LoginEntryPolicy.decide(true, hasPendingRestore = false, isFreshTarget = true, isReregistering = true))
        assertEquals(Entry.OFFER_FRESH_INSTALL, LoginEntryPolicy.decide(true, hasPendingRestore = false, isFreshTarget = true, isReregistering = false))
        assertEquals(Entry.SKIP_LOCAL_STATE_EXISTS, LoginEntryPolicy.decide(true, hasPendingRestore = false, isFreshTarget = false, isReregistering = false))
    }
}

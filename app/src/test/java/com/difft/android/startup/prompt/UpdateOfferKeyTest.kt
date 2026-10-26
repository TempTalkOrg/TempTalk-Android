package com.difft.android.startup.prompt

import com.difft.android.setting.data.CheckUpdateResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Offer identity `apkHash ?: url`, and a new offer restarting the OPTIONAL_UPDATE record (#1355 §5). */
class UpdateOfferKeyTest {

    private val now = 1_000_000_000L

    private fun offer(hash: String?, url: String = "https://u") =
        UpdateCheckState.Optional(CheckUpdateResponse(update = true, force = false, url = url, notes = "", updateWithApk = false, apkHash = hash ?: ""))

    @Test
    fun `hash wins, url is the fallback, blanks are absent`() {
        assertEquals("h1", updateOfferKey("h1", "https://u"))
        assertEquals("https://u", updateOfferKey("", "https://u"))
        assertEquals("https://u", updateOfferKey(null, "https://u"))
        assertNull(updateOfferKey(" ", ""))
    }

    @Test
    fun `optional offer is eligible with its key, forced and unchecked are unknown`() {
        assertEquals(ProviderAnswer(Eligibility.ELIGIBLE, offerKey = "h1"), optionalUpdateAnswer(offer("h1")))
        assertEquals(ProviderAnswer(Eligibility.ELIGIBLE, offerKey = "https://store"), optionalUpdateAnswer(offer(null, "https://store")))
        assertEquals(Eligibility.NOT_ELIGIBLE, optionalUpdateAnswer(UpdateCheckState.NoUpdate).eligibility)
        assertEquals(Eligibility.UNKNOWN, optionalUpdateAnswer(UpdateCheckState.Unknown).eligibility)
        val forced = UpdateCheckState.Forced(offer("h").response)
        assertEquals(Eligibility.UNKNOWN, optionalUpdateAnswer(forced).eligibility)
    }

    @Test
    fun `a new key resets the record, the same key keeps the ladder`() {
        val declined = PromptRecord(declines = 3, snoozeUntil = now + 1_000, offerKey = "h1")
        val records = mapOf(PromptId.OPTIONAL_UPDATE to declined)

        val same = StartupPromptCoordinator.reconcile(records, mapOf(PromptId.OPTIONAL_UPDATE to optionalUpdateAnswer(offer("h1"))), now)
        assertEquals(declined, same.records[PromptId.OPTIONAL_UPDATE])

        val next = StartupPromptCoordinator.reconcile(records, mapOf(PromptId.OPTIONAL_UPDATE to optionalUpdateAnswer(offer("h2"))), now)
        assertEquals(PromptRecord(offerKey = "h2"), next.records[PromptId.OPTIONAL_UPDATE])
    }

    @Test
    fun `a record without a key never lends its snooze to an offer`() {
        val unkeyed = PromptRecord(shownCount = 1, declines = 1, snoozeUntil = now + 1_000)
        val result = StartupPromptCoordinator.reconcile(mapOf(PromptId.OPTIONAL_UPDATE to unkeyed), mapOf(PromptId.OPTIONAL_UPDATE to optionalUpdateAnswer(offer("h1"))), now)
        assertEquals(PromptRecord(offerKey = "h1"), result.records[PromptId.OPTIONAL_UPDATE])
    }
}

package com.difft.android.chat.crypto

import com.difft.android.network.group.GroupRepo
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [GroupCryptoRepo.verifyAllPendingForGroup],
 * [GroupCryptoRepo.verifyAndDisposeInvalidMembers], and
 * [GroupCryptoRepo.deleteKeys].
 *
 * `@Ignore`d at the class level — the production code constructs WCDB
 * [com.tencent.wcdb.winq.Expression] (`DBGroupMemberContactorModel.gid.eq(..)`
 * etc.) before the mock interceptor runs. Expression construction triggers
 * `CppObject.<clinit>` → `System.loadLibrary("WCDB")` → native methods that
 * are not present on the host JVM. Same constraint as
 * [com.difft.android.chat.common.ConversationManagerImplTest]'s ignored case
 * (line 135) and [org.difft.app.database.models.JobModelRoundTripTest].
 *
 * Only the two early-exit cases (T4/T5) that need no real DB are kept as `@Test`
 * bodies. The WCDB-touching scenarios (T6+ / TV1–TV6) are listed as a checklist at
 * the bottom for the instrumentation port — see the note there on why they are NOT
 * left as empty @Test shells. Design ref: §7.1.2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native lib not loadable in JVM unit tests (Expression class clinit). " +
        "See ConversationManagerImplTest:135 and JobModelRoundTripTest precedent.")
class GroupCryptoRepoTest {

    private lateinit var repo: GroupCryptoRepo
    private lateinit var groupRepo: GroupRepo

    @Before
    fun setUp() {
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        mockkObject(GroupCrypto)

        groupRepo = mockk(relaxed = true)
        repo = GroupCryptoRepo()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---------------------------------------------------------------------
    // T4–T5 — early exits
    //
    // NOTE: throttle-related cases (T1–T3, T14, T17) were removed when the
    // 5s ConcurrentHashMap.compute throttle was dropped. Caller-side fetch
    // dedup (`groupsInProgress` in GroupUtil) plus SQL-first ordering already
    // make repeat calls cheap; no time-based dedup is needed here.
    // ---------------------------------------------------------------------

    /** T4: A plain group (no R_group) must skip silently — no cryptoDispose call. */
    @Test
    fun plain_group_skips_no_dispose() = runBlocking {
        every { repo.getRGroupBytes("g1") } returns null
        repo.verifyAllPendingForGroup("g1", groupRepo)
        coVerify(exactly = 0) { groupRepo.cryptoDispose(any(), any()) }
    }

    /** T5: derivePkBind throwing must be caught; no SQL pending query, no dispose. */
    @Test
    fun derivePkBind_failure_skips_silently() = runBlocking {
        every { repo.getRGroupBytes("g1") } returns ByteArray(32) { 0 }
        every { GroupCrypto.derivePkBind(any()) } throws RuntimeException("boom")
        repo.verifyAllPendingForGroup("g1", groupRepo)
        coVerify(exactly = 0) { groupRepo.cryptoDispose(any(), any()) }
    }

    // ---------------------------------------------------------------------
    // Pending instrumentation coverage — port to the androidTest source set when a
    // device-backed WCDB harness exists (see the class-level @Ignore for why none of
    // these can run on the host JVM: each builds DBGroupCryptoKeysModel /
    // DBGroupMemberContactorModel Expressions, whose clinit needs the native lib).
    //
    // Deliberately documented as a checklist rather than empty @Test shells: an empty
    // body passes vacuously and would silently go green the moment the class-level
    // @Ignore is lifted — a false-green trap. Design ref: §7.1.2.
    //
    // verify pipeline (verifyAllPendingForGroup / verifyAndDisposeInvalidMembers):
    //   T6  0 pending rows        → early return, no UPDATE, no dispose
    //   T7  all verified          → one batched UPDATE, no dispose
    //   T8  all invalid           → no UPDATE, one dispose
    //   T9  mixed                 → one UPDATE (verified) + one dispose (invalid uids)
    //   T10 verifyUid throws       → caught, member counted invalid (lands in dispose req)
    //   T11 cryptoDispose throws   → caught; UPDATE durable, never sets verify=false
    //   T13 notify path           → verifyAndDisposeInvalidMembers writes verify=true for verified
    //   T15 1100 verified uids    → chunked into 3 UPDATEs of [500, 500, 100]
    //
    // saveOrRotateRGroup version gating (Phase 0 key rotation):
    //   TV1 no stored key         → insert incoming version, return true (no reset on first insert)
    //   TV2 higher version        → overwrite + resetSignatureVerify(gid) (signatureVerify=NULL), return true
    //   TV3 equal version         → skip, return false, no reset
    //   TV4 lower version         → skip, return false (blocks stale-key regression)
    //   TV5 getKeyVersion no row  → 0 (baseline / un-rotated)
    //   TV6 saveRGroupIfNeeded    → version 0; existing v0 row is a skip (0 > 0 is false → idempotent)
    //
    // Note: the version-gate decision logic itself (the part with no WCDB dependency)
    // IS covered on the JVM by GroupCryptoRGroupDecisionTest. The cases above are the
    // WCDB-touching side effects (INSERT / UPDATE / dispose) that need a real DB.
    //
    // Removed (no longer applicable): T1–T3/T14/T17 (5s throttle dropped — caller-side
    // fetch dedup in GroupUtil.groupsInProgress + SQL-first ordering already make repeat
    // calls cheap), T12 (defensive `uidSignature IS NOT NULL` predicate dropped — encrypted
    // members always carry a signature), T16 (per-call withTimeout dropped — HTTP timeout is
    // governed by ChativeHttpClient connect/read/write seconds).
    // ---------------------------------------------------------------------
}

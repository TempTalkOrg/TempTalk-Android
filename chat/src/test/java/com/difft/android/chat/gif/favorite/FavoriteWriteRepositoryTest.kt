package com.difft.android.chat.gif.favorite

import com.difft.android.base.user.UserManager
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.UrlManager
import com.difft.android.network.responses.FavoriteAction
import com.difft.android.network.responses.FavoritesPutRequest
import com.difft.android.network.responses.FavoritesResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.models.FavoriteGifModel
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.IdentityKeyPair
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [FavoriteWriteRepository] CAS / cap / key logic (v2).
 * Covers: CAS replay + exhaustion, cap FIFO + skip pending, optimistic backfill, and the v2 key
 * paths — ensureFavKey unwrap-first / first-create, and rewrap.
 *
 * Runs under Robolectric because FavoriteCrypto.encrypt uses android.util.Base64. keyRepo is a real
 * in-memory-ish mock; the HTTP layer is mocked, so PUT bodies are exercised without a real network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FavoriteWriteRepositoryTest {

    private val httpClient: ChativeHttpClient = mockk(relaxed = true)
    private val httpService: HttpService = mockk()
    private val urlManager: UrlManager = mockk()
    private val syncRepo: FavoriteSyncRepository = mockk(relaxed = true)
    private val keyRepo: FavoriteKeyRepo = mockk(relaxed = true)
    private val assetUploader: FavoriteAssetUploader = mockk()
    private val encryptionDataManager: EncryptionDataManager = mockk()
    private val userManager: UserManager = mockk(relaxed = true)
    // Optimistic writer is not exercised by these blocking-path tests (favorite()/putWithCas/key
    // lifecycle); relaxed so the writeRepo Lazy back-edge resolves without behavior.
    private val optimisticWriter: FavoriteOptimisticWriter = mockk(relaxed = true)
    private lateinit var repo: FavoriteWriteRepository
    private lateinit var keyLifecycle: FavoriteKeyLifecycle

    private val favKeyEntry = FavKeyEntry("kid", ByteArray(FavoriteCrypto.FAV_KEY_SIZE))

    @Before
    fun setUp() {
        every { httpClient.httpService } returns httpService
        every { urlManager.gifs } returns "https://host/gifs/"
        // keyLifecycle owns ensureFavKey / reset / rewrap / onPrimaryLogin (delegated to by writeRepo).
        // Its Lazy<writeRepo> back-edge resolves to `repo` at .get() time (assigned just below).
        keyLifecycle = FavoriteKeyLifecycle(
            syncRepo, keyRepo, encryptionDataManager, dagger.Lazy { repo }
        )
        repo = FavoriteWriteRepository(
            httpClient, urlManager, syncRepo, keyRepo, assetUploader, userManager,
            keyLifecycle, dagger.Lazy { optimisticWriter }
        )
    }

    /** A deterministic 32-byte aci private key so deriveKek() is stable. */
    private fun stubIdentity(privByte: Byte = 5) {
        val kp: IdentityKeyPair = mockk()
        val priv = mockk<org.signal.libsignal.protocol.ecc.ECPrivateKey>()
        every { priv.serialize() } returns ByteArray(32) { privByte }
        every { kp.privateKey } returns priv
        every { encryptionDataManager.getAciIdentityKey() } returns kp
    }

    private fun pointer(hash: String) = FavoriteAttachmentPointer(
        id = "att-$hash", authorizeId = 1L, key = ByteArray(0), digest = ByteArray(0),
        fileHash = hash, width = 100, height = 100
    )

    private fun resp(status: Int, listVersion: Long = 0L, wrappedFavKey: String? = null) =
        BaseResponse<FavoritesResponse>(
            ver = 1, status = status, reason = "",
            data = FavoritesResponse(
                encVersion = 1, listVersion = listVersion, keyId = "kid",
                blob = null, wrappedFavKey = wrappedFavKey
            )
        )

    // ---- ensureFavKey: unwrap-first / first-create ----

    @Test
    fun `ensureFavKey uses local key when present (no first-create)`() = runTest {
        coEvery { keyRepo.hasKey() } returns true
        val firstCreate = repo.ensureFavKey()
        assertEquals(false, firstCreate)
    }

    @Test
    fun `ensureFavKey unwraps server wrappedFavKey with current KEK`() = runTest {
        stubIdentity()
        coEvery { keyRepo.hasKey() } returns false
        // Produce a real wrappedFavKey the current KEK can unwrap.
        val kek = FavoriteCrypto.deriveKek(ByteArray(32) { 5 })
        val favKey = FavoriteCrypto.generateFavKey()
        val wrapped = FavoriteCrypto.wrapFavKey(kek, favKey)
        coEvery { syncRepo.getFavorites() } returns
            FavoritesResponse(encVersion = 1, listVersion = 3L, keyId = "kid", blob = null, wrappedFavKey = wrapped)

        val firstCreate = repo.ensureFavKey()

        assertEquals(false, firstCreate) // unwrap path, not first-create
        // The unwrapped favKey (with its deterministic keyId) is cached.
        io.mockk.coVerify { keyRepo.save(FavoriteCrypto.keyId(favKey), any()) }
    }

    @Test
    fun `ensureFavKey first-creates when server has no wrappedFavKey`() = runTest {
        coEvery { keyRepo.hasKey() } returns false
        coEvery { syncRepo.getFavorites() } returns
            FavoritesResponse(encVersion = 1, listVersion = 0L, keyId = null, blob = null, wrappedFavKey = null)

        val firstCreate = repo.ensureFavKey()

        assertEquals(true, firstCreate)
        io.mockk.coVerify { keyRepo.save(any(), any()) }
    }

    @Test
    fun `ensureFavKey leaves no key when current KEK cannot unwrap (identity changed)`() = runTest {
        stubIdentity(privByte = 5)
        coEvery { keyRepo.hasKey() } returns false
        // Wrap under a DIFFERENT identity so the current KEK fails to unwrap.
        val otherKek = FavoriteCrypto.deriveKek(ByteArray(32) { 9 })
        val wrapped = FavoriteCrypto.wrapFavKey(otherKek, FavoriteCrypto.generateFavKey())
        coEvery { syncRepo.getFavorites() } returns
            FavoritesResponse(encVersion = 1, listVersion = 3L, keyId = "kid", blob = null, wrappedFavKey = wrapped)

        val firstCreate = repo.ensureFavKey()

        assertEquals(false, firstCreate)
        io.mockk.coVerify(exactly = 0) { keyRepo.save(any(), any()) }
    }

    // ---- rewrap ----

    @Test
    fun `rewrap re-wraps favKey under the new identity and PUTs action rewrap`() = runTest {
        val oldPriv = ByteArray(32) { 1 }
        val newPriv = ByteArray(32) { 2 }
        val favKey = FavoriteCrypto.generateFavKey()
        val oldWrapped = FavoriteCrypto.wrapFavKey(FavoriteCrypto.deriveKek(oldPriv), favKey)
        coEvery { syncRepo.getFavorites() } returns
            FavoritesResponse(encVersion = 1, listVersion = 3L, keyId = "kid", blob = null, wrappedFavKey = oldWrapped)
        coEvery { keyRepo.getFavKey() } returns null // no local cache -> exercise the old-KEK unwrap fallback
        val captured = mutableListOf<FavoritesPutRequest>()
        coEvery { httpService.putFavorites(any(), any(), capture(captured)) } returns resp(status = 0)

        repo.rewrapOnMasterKeyRotation(oldPriv, newPriv)

        assertEquals(1, captured.size)
        assertEquals(FavoriteAction.REWRAP, captured[0].action)
        // rewrap carries ONLY wrappedFavKey — no listVersion, no items.
        assertEquals(null, captured[0].listVersion)
        assertEquals(null, captured[0].items)
        assertTrue(!captured[0].wrappedFavKey.isNullOrEmpty())
        // The re-wrapped key must unwrap under the NEW identity.
        val recovered = FavoriteCrypto.unwrapFavKey(FavoriteCrypto.deriveKek(newPriv), captured[0].wrappedFavKey!!)
        assertTrue(favKey.contentEquals(recovered))
    }

    @Test
    fun `rewrap is a no-op when server has no wrappedFavKey`() = runTest {
        coEvery { syncRepo.getFavorites() } returns
            FavoritesResponse(encVersion = 1, listVersion = 0L, keyId = null, blob = null, wrappedFavKey = null)

        repo.rewrapOnMasterKeyRotation(ByteArray(32) { 1 }, ByteArray(32) { 2 })

        io.mockk.coVerify(exactly = 0) { httpService.putFavorites(any(), any(), any()) }
    }

    // ---- onPrimaryLogin: §3.4 rewrap-vs-reset decision ----

    @Test
    fun `onPrimaryLogin does nothing when server has no favorites`() = runTest {
        coEvery { syncRepo.getFavorites() } returns
            FavoritesResponse(encVersion = 1, listVersion = 0L, keyId = null, blob = null, wrappedFavKey = null)

        repo.onPrimaryLogin(oldPriv = ByteArray(32) { 1 }, newPriv = ByteArray(32) { 2 })

        io.mockk.coVerify(exactly = 0) { httpService.putFavorites(any(), any(), any()) }
    }

    @Test
    fun `onPrimaryLogin does nothing when the new KEK already unwraps`() = runTest {
        val newPriv = ByteArray(32) { 2 }
        val wrapped = FavoriteCrypto.wrapFavKey(FavoriteCrypto.deriveKek(newPriv), FavoriteCrypto.generateFavKey())
        coEvery { syncRepo.getFavorites() } returns
            FavoritesResponse(encVersion = 1, listVersion = 3L, keyId = "kid", blob = null, wrappedFavKey = wrapped)

        repo.onPrimaryLogin(oldPriv = ByteArray(32) { 1 }, newPriv = newPriv)

        io.mockk.coVerify(exactly = 0) { httpService.putFavorites(any(), any(), any()) }
    }

    @Test
    fun `onPrimaryLogin rewraps when the old key survived and unwraps`() = runTest {
        val oldPriv = ByteArray(32) { 1 }
        val newPriv = ByteArray(32) { 2 }
        val favKey = FavoriteCrypto.generateFavKey()
        val wrapped = FavoriteCrypto.wrapFavKey(FavoriteCrypto.deriveKek(oldPriv), favKey)
        coEvery { syncRepo.getFavorites() } returns
            FavoritesResponse(encVersion = 1, listVersion = 3L, keyId = "kid", blob = null, wrappedFavKey = wrapped)
        coEvery { keyRepo.hasKey() } returns false // no local cache -> decision relies on old-key unwrap
        coEvery { keyRepo.getFavKey() } returns null // rewrap then falls back to old-KEK unwrap
        val captured = mutableListOf<FavoritesPutRequest>()
        coEvery { httpService.putFavorites(any(), any(), capture(captured)) } returns resp(status = 0)

        repo.onPrimaryLogin(oldPriv = oldPriv, newPriv = newPriv)

        assertEquals(1, captured.size)
        assertEquals(FavoriteAction.REWRAP, captured[0].action)
        // Re-wrapped key must unwrap under the NEW identity (list preserved).
        val recovered = FavoriteCrypto.unwrapFavKey(FavoriteCrypto.deriveKek(newPriv), captured[0].wrappedFavKey!!)
        assertTrue(favKey.contentEquals(recovered))
    }

    @Test
    fun `onPrimaryLogin resets when the favKey is unrecoverable`() = runTest {
        val newPriv = ByteArray(32) { 2 }
        // Server key wrapped under a THIRD identity neither old nor new can unwrap.
        val staleWrapped = FavoriteCrypto.wrapFavKey(FavoriteCrypto.deriveKek(ByteArray(32) { 9 }), FavoriteCrypto.generateFavKey())
        coEvery { syncRepo.getFavorites() } returns
            FavoritesResponse(encVersion = 1, listVersion = 3L, keyId = "kid", blob = null, wrappedFavKey = staleWrapped)
        coEvery { syncRepo.allCached() } returns emptyList()
        coEvery { keyRepo.hasKey() } returns false // no local cache -> unrecoverable
        // reset re-encrypts the (empty) list, so a valid 32-byte favKey must be available afterwards.
        coEvery { keyRepo.getFavKey() } returns favKeyEntry
        stubIdentity(privByte = 2) // deriveCurrentKek() for the new wrappedFavKey on the reset PUT
        val captured = mutableListOf<FavoritesPutRequest>()
        coEvery { httpService.putFavorites(any(), any(), capture(captured)) } returns resp(status = 0)

        // oldPriv = null models a fresh install / cleared storage (no old key captured at login).
        repo.onPrimaryLogin(oldPriv = null, newPriv = newPriv)

        assertEquals(1, captured.size)
        assertEquals(FavoriteAction.RESET, captured[0].action)
    }

    @Test
    fun `onPrimaryLogin rewraps from cached favKey when server key is stale (self-heal)`() = runTest {
        // Reproduces the previously-broken state: an earlier rotation left the server wrappedFavKey
        // under an even OLDER identity that neither the new nor the (gone) old key can unwrap — but the
        // favKey is still cached locally, so the primary can heal the server via rewrap.
        val newPriv = ByteArray(32) { 2 }
        val favKey = FavoriteCrypto.generateFavKey()
        val staleWrapped = FavoriteCrypto.wrapFavKey(FavoriteCrypto.deriveKek(ByteArray(32) { 9 }), favKey)
        coEvery { syncRepo.getFavorites() } returns
            FavoritesResponse(encVersion = 1, listVersion = 3L, keyId = "kid", blob = null, wrappedFavKey = staleWrapped)
        coEvery { keyRepo.hasKey() } returns true
        coEvery { keyRepo.getFavKey() } returns FavKeyEntry("kid", favKey)
        val captured = mutableListOf<FavoritesPutRequest>()
        coEvery { httpService.putFavorites(any(), any(), capture(captured)) } returns resp(status = 0)

        // oldPriv gone (identity chain broken), but the cached favKey heals it.
        repo.onPrimaryLogin(oldPriv = null, newPriv = newPriv)

        assertEquals(1, captured.size)
        assertEquals(FavoriteAction.REWRAP, captured[0].action)
        // Server key now unwraps under the NEW identity -> a re-linked device recovers the FULL list.
        val recovered = FavoriteCrypto.unwrapFavKey(FavoriteCrypto.deriveKek(newPriv), captured[0].wrappedFavKey!!)
        assertTrue(favKey.contentEquals(recovered))
    }

    // ---- putWithCas replay + exhaustion ----

    @Test
    fun `putWithCas succeeds first try returns server listVersion`() = runTest {
        coEvery { keyRepo.getFavKey() } returns favKeyEntry
        every { syncRepo.cachedListVersion } returns 10L
        coEvery { syncRepo.confirmedRecords() } returns emptyList()
        coEvery { httpService.putFavorites(any(), any(), any()) } returns resp(status = 0, listVersion = 11L)

        val newVersion = repo.putWithCas(FavoriteAction.FAVORITE, FavoriteRecord(pointer("a"), 10L), wrappedFavKey = null)
        assertEquals(11L, newVersion)
    }

    @Test
    fun `putWithCas replays on conflict then succeeds`() = runTest {
        coEvery { keyRepo.getFavKey() } returns favKeyEntry
        every { syncRepo.cachedListVersion } returnsMany listOf(10L, 20L)
        coEvery { syncRepo.confirmedRecords() } returnsMany listOf(emptyList(), emptyList())
        coEvery { syncRepo.pullAndDecrypt() } returns Unit
        val captured = mutableListOf<FavoritesPutRequest>()
        coEvery { httpService.putFavorites(any(), any(), capture(captured)) } returnsMany listOf(
            resp(status = FavoriteWriteRepository.FAVORITES_STATUS_CAS_CONFLICT),
            resp(status = 0, listVersion = 21L)
        )

        val newVersion = repo.putWithCas(FavoriteAction.FAVORITE, FavoriteRecord(pointer("a"), 10L), wrappedFavKey = null)
        assertEquals(21L, newVersion)
        assertEquals(2, captured.size)
        assertEquals(10L, captured[0].listVersion)
        assertEquals(20L, captured[1].listVersion)
        assertEquals(true, captured[1].items?.any { it.fileHash == "a" })
    }

    @Test
    fun `putWithCas exhausts after max retries`() = runTest {
        coEvery { keyRepo.getFavKey() } returns favKeyEntry
        every { syncRepo.cachedListVersion } returns 10L
        coEvery { syncRepo.confirmedRecords() } returns emptyList()
        coEvery { syncRepo.pullAndDecrypt() } returns Unit
        coEvery { httpService.putFavorites(any(), any(), any()) } returns
            resp(status = FavoriteWriteRepository.FAVORITES_STATUS_CAS_CONFLICT)

        assertFailsWith<CasExhaustedException> {
            repo.putWithCas(FavoriteAction.FAVORITE, FavoriteRecord(pointer("a"), 10L), wrappedFavKey = null)
        }
    }

    // ---- favorite() optimistic backfill ----

    @Test
    fun `favorite backfills optimistic row with server listVersion on success`() = runTest {
        val ptr = pointer("fav1")
        coEvery { assetUploader.transStore(any(), any(), any()) } returns ptr
        coEvery { keyRepo.hasKey() } returns true
        coEvery { keyRepo.getFavKey() } returns favKeyEntry
        every { syncRepo.cachedListVersion } returns 5L
        coEvery { syncRepo.confirmedRecords() } returns emptyList()
        coEvery { httpService.putFavorites(any(), any(), any()) } returns resp(status = 0, listVersion = 42L)
        val upserts = mutableListOf<FavoriteGifModel>()
        coEvery { syncRepo.upsert(capture(upserts)) } returns Unit

        val result = repo.favorite(java.io.File("/tmp/x.gif"), 100, 100)

        assertEquals(FavResult.Ok, result)
        assertEquals(true, upserts.first().pending)
        val confirmed = upserts.last()
        assertEquals(false, confirmed.pending)
        assertEquals(42L, confirmed.addedListVersion)
        assertEquals("fav1", confirmed.fileHash)
    }

    private fun model(fileHash: String, version: Long, pending: Boolean) = FavoriteGifModel().apply {
        this.fileHash = fileHash
        this.addedListVersion = version
        this.pending = pending
    }
}

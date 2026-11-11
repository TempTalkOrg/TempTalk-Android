package com.difft.android.chat.group.create

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.common.GroupAvatarUtil
import com.difft.android.chat.group.GroupWriteSession
import com.difft.android.chat.group.create.CreateGroupContract.Effect
import com.difft.android.chat.group.create.CreateGroupContract.Intent
import com.difft.android.chat.group.create.CreateGroupOperation.Outcome
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.difft.app.database.WCDB
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.search
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CreateGroupViewModelTest {
    @get:Rule(order = 0) val dispatcher = TestDispatcherRule()
    @get:Rule(order = 1) val globals = GlobalStaticMockRule("owner")
    @get:Rule(order = 2) val files = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wcdb: WCDB = mockk(relaxed = true)
    private val operation: CreateGroupOperation = mockk()
    private val configs: GlobalConfigsManager = mockk()
    private val models = mutableListOf<CreateGroupViewModel>()
    private var generated = 0

    @Before fun setUp() {
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        
        every { wcdb.getContactorsFromAllTable(any(), any()) } answers { secondArg<List<String>>().map { contact(it) } }
        every { wcdb.contactor.search(any()) } returns listOf(contact("owner"), contact("a"), contact("b"), contact("c"))
        mockkObject(GroupAvatarUtil)
        every { GroupAvatarUtil.generateAvatarFile(any(), any()) } answers { files.newFile("gen-${generated++}.png").absolutePath }
        every { configs.isGroupEncryptionEnabled() } returns false
        every { operation.currentSession() } returns GroupWriteSession("owner", "login")
    }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        unmockkAll()
    }

    private fun contact(id: String) = ContactorModel().apply { this.id = id; name = "Name-$id" }

    private fun model(handle: SavedStateHandle = SavedStateHandle()): CreateGroupViewModel =
        CreateGroupViewModel(handle, context, wcdb, operation, configs).also { models += it }

    private fun handle(vararg preselected: String) = SavedStateHandle(mapOf(CreateGroupViewModel.ARG_PRESELECTED to ArrayList(preselected.toList())))

    private suspend fun CreateGroupViewModel.awaitRows() = withTimeout(5_000) { state.first { it.rows.isNotEmpty() } }

    @Test fun `rows exclude self, lock the preselection, and the default name is seeded once with self first`() = runBlocking {
        val handle = handle("a")
        val vm = model(handle)
        val state = vm.awaitRows()
        assertEquals(listOf("a", "b", "c"), state.rows.map { it.uid })
        assertFalse(state.rows.first { it.uid == "a" }.checkBoxEnable)
        assertTrue(state.rows.first { it.uid == "b" }.checkBoxEnable)
        assertEquals("Name-owner, Name-a", withTimeout(5_000) { vm.state.first { it.name.isNotEmpty() }.name })

        vm.dispatch(Intent.NameChanged(""))
        val restored = model(handle)
        restored.awaitRows()
        assertEquals("", restored.state.value.name, "a cleared name is not re-seeded on restore")
    }

    @Test fun `toggles are saved, a preselected member cannot be toggled, and a submit uses the trimmed name or the default`() = runBlocking {
        val handle = handle("a")
        val vm = model(handle)
        vm.awaitRows()
        vm.dispatch(Intent.Toggle("b"))
        vm.dispatch(Intent.Toggle("a"))
        assertEquals(listOf("a", "b"), vm.state.value.selectedIds)
        assertEquals(listOf("a", "b"), model(handle).state.value.selectedIds)

        val draft = slot<CreateGroupOperation.Draft>()
        every { operation.submit(capture(draft), any()) } returns CompletableDeferred(Outcome.Created("g1"))
        vm.dispatch(Intent.NameChanged("   "))
        vm.dispatch(Intent.Submit)
        assertEquals(context.getString(R.string.new_group), draft.captured.name)
        assertEquals(listOf("a", "b"), draft.captured.memberIds)
        assertEquals("g1", withTimeout(5_000) { vm.state.first { it.completed != null }.completed })
        assertFalse(vm.state.value.busy)
    }

    @Test fun `a generated avatar follows the selection, a picked one survives toggles and is copied out of the pick temp`() = runBlocking {
        val vm = model(handle())
        vm.awaitRows()
        assertNull(vm.state.value.avatarPath, "no members, no generated avatar")
        vm.dispatch(Intent.Toggle("b"))
        val first = withTimeout(5_000) { vm.state.first { it.avatarPath != null }.avatarPath!! }
        vm.dispatch(Intent.Toggle("c"))
        val second = withTimeout(5_000) { vm.state.first { it.avatarPath != null && it.avatarPath != first }.avatarPath!! }
        assertFalse(File(first).exists(), "a superseded generated avatar is deleted")

        val picked = files.newFile("picked.jpg").apply { writeBytes(ByteArray(3)) }
        vm.dispatch(Intent.AvatarPicked(picked.absolutePath))
        val draft = withTimeout(5_000) { vm.state.first { it.avatarPicked }.avatarPath!! }
        assertTrue(File(draft).exists())
        assertTrue(draft.contains(CreateGroupViewModel.DRAFT_DIR))
        assertFalse(File(second).exists(), "the generated avatar gives way to the picked one")
        vm.dispatch(Intent.Toggle("b"))
        Thread.sleep(100)
        assertEquals(draft, vm.state.value.avatarPath, "toggling members keeps a picked avatar")
    }

    @Test fun `a submitted draft file is the operation's, a rejected one comes back, and a second submit while busy is ignored`() = runBlocking {
        val vm = model(handle("a"))
        vm.awaitRows()
        val draft = withTimeout(5_000) { vm.state.first { it.avatarPath != null }.avatarPath!! }
        val pending = CompletableDeferred<Outcome>()
        every { operation.submit(any(), any()) } returns pending
        vm.dispatch(Intent.Submit)
        vm.dispatch(Intent.Submit)
        assertTrue(vm.state.value.busy)
        vm.viewModelScope.cancel()
        vm.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm)
        assertTrue(File(draft).exists(), "the file handed to the operation is not deleted by the page")

        val again = model(handle("a"))
        again.awaitRows()
        val path = withTimeout(5_000) { again.state.first { it.avatarPath != null }.avatarPath!! }
        every { operation.submit(any(), any()) } returns CompletableDeferred(Outcome.Rejected(status = 500, reason = "boom"))
        again.dispatch(Intent.Submit)
        withTimeout(5_000) { again.state.first { !it.busy } }
        assertTrue(File(path).exists())
        assertEquals("boom", withTimeout(5_000) { (again.effect.first() as Effect.Toast).text })
    }

    @Test fun `a submit while a picked avatar is still being copied is ignored until the draft path is in place`() = runBlocking {
        val vm = model(handle())
        vm.awaitRows()
        val picked = files.newFile("slow.jpg").apply { writeBytes(ByteArray(3)) }
        val draft = slot<CreateGroupOperation.Draft>()
        every { operation.submit(capture(draft), any()) } returns CompletableDeferred(Outcome.Created("g1"))
        
        val copying = CountDownLatch(1)
        val release = CountDownLatch(1)
        mockkObject(AvatarPickTempCleaner)
        every { AvatarPickTempCleaner.deleteUploadedTemp(any(), any()) } answers {
            copying.countDown()
            release.await(5, TimeUnit.SECONDS)
            Unit
        }
        vm.dispatch(Intent.AvatarPicked(picked.absolutePath))
        assertTrue(copying.await(5, TimeUnit.SECONDS))
        vm.dispatch(Intent.Submit)
        assertFalse(draft.isCaptured, "the submit raced the copy and was dropped")
        release.countDown()
        val adopted = withTimeout(5_000) { vm.state.first { it.avatarPicked && !it.avatarAdopting }.avatarPath!! }
        vm.dispatch(Intent.Submit)
        assertEquals(adopted, draft.captured.avatarPath)
    }

    @Test fun `a page restored after process death with a submit in flight clears busy and says the result is unknown`() = runBlocking {
        val handle = SavedStateHandle(mapOf(CreateGroupViewModel.ARG_PRESELECTED to arrayListOf<String>(), "submitted" to true))
        val vm = model(handle)
        assertFalse(vm.state.value.busy)
        val toast = withTimeout(5_000) { vm.effect.first() as Effect.Toast }
        assertEquals(R.string.group_create_result_unknown, toast.messageRes)
        assertEquals(false, handle.get<Boolean>("submitted"))
    }

    @Test fun `the generated avatar holds self and the selected members, so one other member gives two letters`() = runBlocking {
        val letterCounts = mutableListOf<Int>()
        every { GroupAvatarUtil.generateAvatarFile(any(), any()) } answers {
            letterCounts += firstArg<List<*>>().size
            files.newFile("gen-${generated++}.png").absolutePath
        }
        val vm = model(handle("a"))
        vm.awaitRows()
        withTimeout(5_000) { vm.state.first { it.avatarPath != null } }
        assertEquals(listOf(2), letterCounts, "self and the preselected contact")

        vm.dispatch(Intent.Toggle("b"))
        withTimeout(5_000) { while (letterCounts.size < 2) delay(10) }
        assertEquals(3, letterCounts.last())
    }

    @Test fun `a created group is saved with the page, so a page restored after process death still enters it`() = runBlocking {
        val handle = handle("a")
        val vm = model(handle)
        vm.awaitRows()
        every { operation.submit(any(), any()) } returns CompletableDeferred(Outcome.Created("g1"))
        vm.dispatch(Intent.Submit)
        withTimeout(5_000) { vm.state.first { it.completed == "g1" } }

        val restored = model(handle)
        assertEquals("g1", restored.state.value.completed)
        assertFalse(restored.state.value.busy)
        restored.dispatch(Intent.Submit)
        assertFalse(restored.state.value.busy, "a completed page never submits again")
    }

    @Test fun `a generation still running at submit neither deletes nor replaces the submitted avatar`() = runBlocking {
        val vm = model(handle("a"))
        vm.awaitRows()
        val shown = withTimeout(5_000) { vm.state.first { it.avatarPath != null }.avatarPath!! }
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val written = CountDownLatch(1)
        val late = File(files.root, "late.png")
        every { GroupAvatarUtil.generateAvatarFile(any(), any()) } answers {
            entered.countDown()
            gate.await(5, TimeUnit.SECONDS)
            late.apply { writeBytes(ByteArray(1)) }.absolutePath.also { written.countDown() }
        }
        vm.dispatch(Intent.Toggle("b"))
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val draft = slot<CreateGroupOperation.Draft>()
        every { operation.submit(capture(draft), any()) } returns CompletableDeferred()
        vm.dispatch(Intent.Submit)
        gate.countDown()
        assertTrue(written.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { while (late.exists()) delay(10) }

        assertEquals(shown, draft.captured.avatarPath)
        assertTrue(File(shown).exists(), "the file handed to the operation survives the late generation")
        assertEquals(shown, vm.state.value.avatarPath)
    }

    @Test fun `a restored avatar draft whose file is gone falls back to a generated one`() = runBlocking {
        val gone = File(files.root, "swept.jpg").absolutePath
        val handle = SavedStateHandle(
            mapOf(CreateGroupViewModel.ARG_PRESELECTED to arrayListOf("a"), "avatar_path" to gone, "avatar_picked" to true)
        )
        val vm = model(handle)
        assertFalse(vm.state.value.avatarPicked)
        val generatedPath = withTimeout(5_000) { vm.state.first { it.avatarPath != null }.avatarPath!! }
        assertTrue(File(generatedPath).exists(), "the page submits a file that exists")
        assertFalse(vm.state.value.avatarPicked)

        val kept = files.newFile("kept.jpg").absolutePath
        val restored = model(SavedStateHandle(mapOf(CreateGroupViewModel.ARG_PRESELECTED to arrayListOf("a"), "avatar_path" to kept, "avatar_picked" to true)))
        assertEquals(kept, restored.state.value.avatarPath)
        assertTrue(restored.state.value.avatarPicked)
    }

    @Test fun `a draft file lost after it was shown is left out of the submit instead of failing it`() = runBlocking {
        val vm = model(handle("a"))
        vm.awaitRows()
        val shown = withTimeout(5_000) { vm.state.first { it.avatarPath != null }.avatarPath!! }
        assertTrue(File(shown).delete())
        val draft = slot<CreateGroupOperation.Draft>()
        every { operation.submit(capture(draft), any()) } returns CompletableDeferred(Outcome.Created("g1"))

        vm.dispatch(Intent.Submit)

        assertNull(draft.captured.avatarPath)
        assertEquals("g1", withTimeout(5_000) { vm.state.first { it.completed != null }.completed })
    }

    @Test fun `the cold-start sweep removes only avatar drafts older than a day`() {
        val dir = File(context.cacheDir, CreateGroupViewModel.DRAFT_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val stale = File(dir, "stale.jpg").apply { writeBytes(ByteArray(1)); setLastModified(now - 25 * 60 * 60 * 1000L) }
        val recent = File(dir, "recent.jpg").apply { writeBytes(ByteArray(1)); setLastModified(now - 60 * 60 * 1000L) }

        CreateGroupViewModel.sweepStaleDrafts(context, now)

        assertFalse(stale.exists())
        assertTrue(recent.exists(), "a restored page may still show a recent draft")
        recent.delete()
    }

    @Test fun `an unanswered send shows the result-unknown toast and keeps the form editable`() = runBlocking {
        val vm = model(handle())
        vm.awaitRows()
        every { operation.submit(any(), any()) } returns CompletableDeferred(Outcome.Rejected(unknown = true))
        vm.dispatch(Intent.Submit)
        val toast = withTimeout(5_000) { vm.effect.first() as Effect.Toast }
        assertEquals(R.string.group_create_result_unknown, toast.messageRes)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.completed)
    }
}

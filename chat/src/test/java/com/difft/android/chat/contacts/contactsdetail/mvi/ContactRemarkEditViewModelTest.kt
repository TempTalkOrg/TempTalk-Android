package com.difft.android.chat.contacts.contactsdetail.mvi

import app.cash.turbine.test
import com.difft.android.chat.common.upload.ContactAvatarUploader
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactRemarkEditContract.Effect
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactRemarkEditContract.Intent
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.requests.ConversationSetRequestBody
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.difft.app.database.models.ContactorModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Remark edit state machine: draft seeding from the remark chain, submit / discard, wire format
 * (`V1|` cipher, "" clears) and avatar-click routing (action sheet vs. picker).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactRemarkEditViewModelTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    private val dispatcher = StandardTestDispatcher()
    private val uploader: ContactAvatarUploader = mockk()
    private val httpService: HttpService = mockk()
    private val httpClient: ChativeHttpClient = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { httpClient.httpService } returns httpService
        mockkObject(ContactorUtil)
        every { ContactorUtil.updateRemark(any(), any()) } returns Unit
        every { ContactorUtil.updateRemarkAvatar(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(ContactorUtil)
        Dispatchers.resetMain()
    }

    private fun vm() = ContactRemarkEditViewModel(uploader, httpClient)

    private fun contact(remark: String? = null, remarkAvatar: String? = null, name: String? = null) = ContactorModel().apply {
        id = UID
        this.remark = remark
        this.remarkAvatar = remarkAvatar
        this.name = name
    }

    private fun ok() = BaseResponse(ver = 1, status = 0, reason = null, data = ConversationSetResponseBody(conversation = UID))
    private fun rejected(reason: String) = BaseResponse<ConversationSetResponseBody>(ver = 1, status = 1, reason = reason, data = null)

    @Test
    fun `enter edit seeds the draft with the remark, empty when there is none`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(remark = "Buddy", name = "Alice")))
        vm.dispatch(Intent.EnterEdit)
        assertEquals("Buddy", vm.state.value.editingName)
        assertEquals("Buddy", vm.state.value.editBaseline)

        vm.dispatch(Intent.DiscardEdit)
        vm.dispatch(Intent.Load(contact(remark = null, name = "Alice")))
        vm.dispatch(Intent.EnterEdit)
        // The real name is never prefilled — it is offered by the quick-fill row instead.
        assertEquals("", vm.state.value.editingName)
        assertEquals("", vm.state.value.editBaseline)
        assertEquals("Alice", vm.state.value.quickFillName)
    }

    @Test
    fun `quick fill into an empty draft, then retires for the rest of the edit`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(name = "Alice")))
        vm.dispatch(Intent.EnterEdit)
        assertTrue(vm.state.value.showQuickFill)

        vm.dispatch(Intent.QuickFillName)
        assertEquals("Alice", vm.state.value.editingName)
        assertFalse(vm.state.value.showQuickFill)
        assertTrue(vm.state.value.hasUnsavedChanges) // filling counts as a change

        // Deleting what was filled in does not bring the shortcut back within this edit.
        vm.dispatch(Intent.ChangeName(""))
        assertFalse(vm.state.value.showQuickFill)

        // Re-entering edit mode decides again.
        vm.dispatch(Intent.DiscardEdit)
        vm.dispatch(Intent.EnterEdit)
        assertTrue(vm.state.value.showQuickFill)
    }

    @Test
    fun `quick fill joins a non-empty draft with a single space`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(name = "Alice")))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("同事  "))

        vm.dispatch(Intent.QuickFillName)

        assertEquals("同事 Alice", vm.state.value.editingName)
    }

    @Test
    fun `quick fill leaves a draft that already equals the name untouched`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(name = "Alice")))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("Alice "))

        vm.dispatch(Intent.QuickFillName)

        assertEquals("Alice ", vm.state.value.editingName)
        assertFalse(vm.state.value.showQuickFill)
    }

    @Test
    fun `quick fill offer is decided by exact equality, not containment`() = runTest(dispatcher) {
        val vm = vm()
        // Remark contains the name but is not equal to it -> still offered.
        vm.dispatch(Intent.Load(contact(remark = "同事 Alice", name = "Alice")))
        vm.dispatch(Intent.EnterEdit)
        assertTrue(vm.state.value.showQuickFill)
        vm.dispatch(Intent.QuickFillName)
        assertEquals("同事 Alice Alice", vm.state.value.editingName)

        // Different case is not equal either -> still offered.
        vm.dispatch(Intent.DiscardEdit)
        vm.dispatch(Intent.Load(contact(remark = "alice", name = "Alice")))
        vm.dispatch(Intent.EnterEdit)
        assertTrue(vm.state.value.showQuickFill)

        // Exactly the name -> not offered.
        vm.dispatch(Intent.DiscardEdit)
        vm.dispatch(Intent.Load(contact(remark = "Alice", name = "Alice")))
        vm.dispatch(Intent.EnterEdit)
        assertFalse(vm.state.value.showQuickFill)
    }

    @Test
    fun `quick fill result is capped at the max remark length`() = runTest(dispatcher) {
        val vm = vm()
        val name = "Alice"
        vm.dispatch(Intent.Load(contact(name = name)))
        vm.dispatch(Intent.EnterEdit)
        val draft = "x".repeat(ContactRemarkEditViewModel.MAX_REMARK_LENGTH - 2)
        vm.dispatch(Intent.ChangeName(draft))

        vm.dispatch(Intent.QuickFillName)

        // "draft" + " " + "Alice" overflows; the shortcut truncates the same way typing/pasting does.
        assertEquals(ContactRemarkEditViewModel.MAX_REMARK_LENGTH, vm.state.value.editingName.length)
        assertEquals("$draft A", vm.state.value.editingName)
    }

    @Test
    fun `quick fill is not offered without a real name`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(remark = "Buddy", name = null)))
        vm.dispatch(Intent.EnterEdit)

        assertEquals("", vm.state.value.quickFillName)
        assertFalse(vm.state.value.showQuickFill)
    }

    @Test
    fun `baseline and draft survive a refresh while editing`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(remark = "Buddy")))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("Draft"))

        vm.dispatch(Intent.Load(contact(remark = "Changed elsewhere")))

        assertEquals("Draft", vm.state.value.editingName)
        assertEquals("Buddy", vm.state.value.editBaseline)
        assertTrue(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `opening and closing edit with no remark submits nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(name = "Alice")))
        vm.dispatch(Intent.EnterEdit)

        vm.dispatch(Intent.SubmitName)

        assertFalse(vm.state.value.isEditing)
        assertEquals("", vm.state.value.remarkName)
        coVerify(exactly = 0) { httpService.fetchConversationSet(any(), any()) }
    }

    @Test
    fun `a quick-filled name is saved as a remark`() = runTest(dispatcher) {
        val body = slot<ConversationSetRequestBody>()
        coEvery { httpService.fetchConversationSet(any(), capture(body)) } returns ok()
        val vm = vm()
        vm.dispatch(Intent.Load(contact(name = "Alice")))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.QuickFillName)

        vm.dispatch(Intent.SubmitName)
        vm.state.first { !it.isEditing && !it.isSubmitting }

        assertTrue(body.captured.remark!!.startsWith("V1|"))
        assertEquals("Alice", vm.state.value.remarkName)
    }

    @Test
    fun `request close - clean edit closes, dirty edit asks, discard closes`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(name = "Alice")))
        vm.dispatch(Intent.EnterEdit)

        vm.effect.test {
            // Nothing typed: the draft equals the (empty) baseline → close without asking.
            vm.dispatch(Intent.RequestClose)
            assertEquals(Effect.HideKeyboard, awaitItem())
            assertEquals(Effect.Close, awaitItem())
            assertFalse(vm.state.value.isEditing)

            vm.dispatch(Intent.EnterEdit)
            vm.dispatch(Intent.ChangeName("Draft"))
            vm.dispatch(Intent.RequestClose)
            assertEquals(Effect.ShowUnsavedDialog, awaitItem())
            assertTrue(vm.state.value.isEditing)

            vm.dispatch(Intent.DiscardAndClose)
            assertEquals(Effect.HideKeyboard, awaitItem())
            assertEquals(Effect.Close, awaitItem())
            assertFalse(vm.state.value.isEditing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edited then restored to baseline is not an unsaved change`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(remark = "Buddy")))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("Bud"))
        assertTrue(vm.state.value.hasUnsavedChanges)
        vm.dispatch(Intent.ChangeName("Buddy "))
        assertFalse(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `save and close submits then closes on success`() = runTest(dispatcher) {
        coEvery { httpService.fetchConversationSet(any(), any()) } returns ok()
        val vm = vm()
        vm.dispatch(Intent.Load(contact(remark = "Buddy")))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("New"))

        vm.effect.test {
            vm.dispatch(Intent.SaveAndClose)
            assertEquals(Effect.ShowWait, awaitItem())
            assertEquals(Effect.DismissWait, awaitItem())
            assertEquals(Effect.HideKeyboard, awaitItem())
            assertEquals(Effect.Close, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("New", vm.state.value.remarkName)
    }

    @Test
    fun `discard restores the draft and leaves edit without a request`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(remark = "Buddy")))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("Draft"))

        vm.dispatch(Intent.DiscardEdit)
        assertFalse(vm.state.value.isEditing)
        assertEquals("Buddy", vm.state.value.editingName)
        coVerify(exactly = 0) { httpService.fetchConversationSet(any(), any()) }
    }

    @Test
    fun `submit unchanged remark exits without a request`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact(remark = "Buddy")))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName(" Buddy "))

        vm.dispatch(Intent.SubmitName)

        assertFalse(vm.state.value.isEditing)
        coVerify(exactly = 0) { httpService.fetchConversationSet(any(), any()) }
    }

    @Test
    fun `submit new remark sends V1 cipher and commits locally`() = runTest(dispatcher) {
        val body = slot<ConversationSetRequestBody>()
        coEvery { httpService.fetchConversationSet(any(), capture(body)) } returns ok()
        val vm = vm()
        vm.dispatch(Intent.Load(contact()))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("Buddy"))

        vm.dispatch(Intent.SubmitName)
        vm.state.first { !it.isEditing && !it.isSubmitting }

        assertEquals(UID, body.captured.conversation)
        val sent = body.captured.remark
        assertTrue(sent != null && sent.startsWith("V1|") && sent.length > 3, "remark should be V1| cipher, was $sent")
        coVerify(exactly = 1) { ContactorUtil.updateRemark(UID, sent!!) }
        assertEquals("Buddy", vm.state.value.remarkName)
        assertFalse(vm.state.value.isEditing)
    }

    @Test
    fun `submit empty remark clears with an empty string`() = runTest(dispatcher) {
        val body = slot<ConversationSetRequestBody>()
        coEvery { httpService.fetchConversationSet(any(), capture(body)) } returns ok()
        val vm = vm()
        vm.dispatch(Intent.Load(contact(remark = "Buddy")))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName(""))

        vm.dispatch(Intent.SubmitName)
        vm.state.first { !it.isEditing && !it.isSubmitting }

        assertEquals("", body.captured.remark)
        coVerify(exactly = 1) { ContactorUtil.updateRemark(UID, "") }
        assertEquals("", vm.state.value.remarkName)
    }

    @Test
    fun `submit rejected by server toasts reason and stays in edit`() = runTest(dispatcher) {
        coEvery { httpService.fetchConversationSet(any(), any()) } returns rejected("denied")
        val vm = vm()
        vm.dispatch(Intent.Load(contact()))
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("Buddy"))

        vm.effect.test {
            vm.dispatch(Intent.SubmitName)
            assertEquals(Effect.ShowWait, awaitItem())
            assertEquals(Effect.DismissWait, awaitItem())
            assertEquals(Effect.ShowToast("denied"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(vm.state.value.isEditing)
        coVerify(exactly = 0) { ContactorUtil.updateRemark(any(), any()) }
    }

    @Test
    fun `avatar click routes by mode and remark avatar presence`() = runTest(dispatcher) {
        val vm = vm()
        vm.dispatch(Intent.Load(contact()))

        vm.effect.test {
            vm.dispatch(Intent.AvatarClick) // browse mode: preview is the host's job, no effect here
            expectNoEvents()

            vm.dispatch(Intent.EnterEdit)
            vm.dispatch(Intent.AvatarClick)
            assertIs<Effect.RequestPickAvatar>(awaitItem())

            vm.dispatch(Intent.Load(contact(remarkAvatar = "V1|xyz")))
            vm.dispatch(Intent.AvatarClick)
            assertIs<Effect.ShowAvatarActionSheet>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restore avatar clears with an empty string and keeps edit mode`() = runTest(dispatcher) {
        val body = slot<ConversationSetRequestBody>()
        coEvery { httpService.fetchConversationSet(any(), capture(body)) } returns ok()
        val vm = vm()
        vm.dispatch(Intent.Load(contact(remarkAvatar = "V1|xyz")))
        vm.dispatch(Intent.EnterEdit)

        vm.dispatch(Intent.RestoreAvatar)
        vm.state.first { !it.hasRemarkAvatar }

        assertEquals("", body.captured.remarkAvatar)
        coVerify(exactly = 1) { ContactorUtil.updateRemarkAvatar(UID, "") }
        assertFalse(vm.state.value.hasRemarkAvatar)
        assertTrue(vm.state.value.isEditing)
    }

    private companion object {
        const val UID = "+10000000001"
    }
}

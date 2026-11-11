package com.difft.android.chat.group

import com.difft.android.chat.R

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.base.storage.user.CredentialChangeSignal
import kotlinx.coroutines.flow.MutableStateFlow
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.crypto.GroupKeyDistributor
import com.difft.android.chat.crypto.GroupMemberBindingSigner
import com.difft.android.chat.group.mvi.GroupPageContract.Intent
import com.difft.android.chat.group.mvi.GroupPageContract.MemberAction
import com.difft.android.chat.group.mvi.GroupPageContract.Setting
import com.difft.android.chat.group.mvi.GroupPageContract.ModeratorAction
import com.difft.android.chat.group.mvi.GroupPageStore
import com.difft.android.chat.group.mvi.GroupPageViewModel
import com.difft.android.network.group.GroupRepo
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.difft.android.test.GroupTestApplication_Application
import io.mockk.coVerify
import io.mockk.mockkStatic
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import com.difft.android.base.utils.pane.DetailNavigation
import com.difft.android.base.utils.pane.DetailStackHost
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.utils.pane.detailNavigation
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.difft.app.database.models.GroupModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import javax.inject.Named
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@AndroidEntryPoint
class GroupPageTestActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = android.R.id.content })
    }
}


@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = GroupTestApplication_Application::class, sdk = [33])
class GroupPageFragmentTest {
    
    @BindValue @JvmField val logoutBoundary: com.difft.android.base.user.LogoutManager = mockk(relaxed = true)
    @BindValue @JvmField val environmentBoundary: com.difft.android.base.utils.EnvironmentHelper = mockk(relaxed = true)
    @BindValue @JvmField val activityBoundary: com.difft.android.base.activity.ActivityProvider = mockk(relaxed = true)
    @BindValue @JvmField val callToChatBoundary: com.difft.android.call.LCallToChatController = mockk(relaxed = true)
    @BindValue @JvmField val chatToCallBoundary: com.difft.android.chat.call.LChatToCallController = mockk(relaxed = true)
    @BindValue @JvmField val translationBoundary: com.difft.android.chat.translate.backend.TranslateBackend = mockk(relaxed = true)

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatcher = TestDispatcherRule(StandardTestDispatcher())
    @get:Rule(order = 2) val globals = GlobalStaticMockRule("owner")
    @BindValue @JvmField val groups: GroupRepo = mockk()
    @BindValue @JvmField val util: GroupUtil = mockk()
    @BindValue @JvmField val crypto: GroupCryptoRepo = mockk()
    @BindValue @JvmField val distributor: GroupKeyDistributor = mockk()
    @BindValue @JvmField val store: GroupPageStore = mockk()
    @BindValue @JvmField val signer: GroupMemberBindingSigner = mockk()
    @BindValue @JvmField val credentials: CredentialChangeSignal = mockk()
    private val login = MutableStateFlow<String?>("login-a")
    @javax.inject.Inject @Named("application") lateinit var applicationScope: CoroutineScope
    private val updates = MutableSharedFlow<GroupModel>()
    private val snapshot = GroupPageStore.Snapshot("group", 0, 2, 2, false, false, false, listOf(
        row("owner", GROUP_ROLE_OWNER), row("alice", GROUP_ROLE_MEMBER), row("bob", GROUP_ROLE_ADMIN), row("charlie", GROUP_ROLE_MEMBER)))

    @Before fun setup() {
        every { util.singleGroupsUpdate } returns updates
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns null
        every { store.snapshot("group") } returns snapshot
        every { util.fetchMark() } returns 7L
        every { credentials.credentialHash } returns login
        every { credentials.isLoaded } returns true
        every { credentials.firstLoad } returns CompletableDeferred("login-a")
        hilt.inject()
    }

    @After fun teardown() {
        applicationScope.cancel()
        pumpUntil("application-scope cancellation") { applicationScope.coroutineContext[kotlinx.coroutines.Job]?.children?.all { it.isCompleted } != false }
    }

    @Test fun `moderator add restores selected IDs and selected checkbox from the same VM truth`() {
        val controller = Robolectric.buildActivity(GroupPageTestActivity::class.java).setup()
        val activity = controller.get()
        val fragment = GroupModeratorsFragment.newInstance("group", ModeratorAction.ADD)
        activity.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        val vm = ViewModelProvider(fragment)[GroupPageViewModel::class.java]
        pumpUntil("initial page load") { vm.state.value.loaded }
        vm.dispatch(Intent.Select("alice"))
        pumpUntil("selected member rendered") { vm.state.value.selectedIds == setOf("alice") }
        assertEquals(setOf("alice"), vm.state.value.selectedIds)
        
        vm.dispatch(Intent.Search("nobody"))
        pumpUntil("empty search rendered") { vm.state.value.query == "nobody" }
        assertEquals(setOf("alice"), vm.state.value.selectedIds)
        vm.dispatch(Intent.Search("alice"))
        pumpUntil("selected search rendered") {
            fragment.requireView().findViewById<com.difft.android.base.widget.DifftClearableInputView>(R.id.search_input).query == "alice"
        }
        assertTrue(vm.state.value.rows.single { it.uid == "alice" }.isSelected)
        
        fragment.requireView().findViewById<com.difft.android.base.widget.DifftClearableInputView>(R.id.search_input).query = "stale hierarchy"
        assertEquals("alice", vm.state.value.query)
        controller.recreate()
        pumpUntil("restored query rendered") {
            controller.get().findViewById<com.difft.android.base.widget.DifftClearableInputView>(R.id.search_input)?.query == "alice"
        }
        
        val restoredCallbacksDrained = java.util.concurrent.atomic.AtomicBoolean(false)
        android.os.Handler(Looper.getMainLooper()).post { restoredCallbacksDrained.set(true) }
        pumpUntil("restored main callbacks drained") { restoredCallbacksDrained.get() }
        val restored = controller.get().supportFragmentManager.fragments.filterIsInstance<GroupModeratorsFragment>().single()
        val restoredVm = ViewModelProvider(restored)[GroupPageViewModel::class.java]
        assertEquals("alice", restoredVm.state.value.query)
        assertEquals("alice", restored.requireView().findViewById<com.difft.android.base.widget.DifftClearableInputView>(R.id.search_input).query)
        assertEquals(setOf("alice"), restoredVm.state.value.selectedIds)
        assertTrue(restoredVm.state.value.rows.single { it.uid == "alice" }.isSelected)
        controller.pause().stop().destroy()
    }

    @Test fun `busy guard rejects an eligible row tap and restores its real checkbox immediately`() {
        val release = CompletableDeferred<Unit>()
        coEvery { groups.changeMemberRole(any(), any(), any()) } coAnswers {
            release.await()
            com.difft.android.network.BaseResponse<Any>(1, 0, null, null)
        }
        val controller = Robolectric.buildActivity(GroupPageTestActivity::class.java).setup()
        val fragment = GroupModeratorsFragment.newInstance("group", ModeratorAction.ADD)
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        val vm = ViewModelProvider(fragment)[GroupPageViewModel::class.java]
        pumpUntil("initial page load") { vm.state.value.loaded }
        vm.dispatch(Intent.Select("bob"))
        assertTrue(vm.state.value.selectedIds.isEmpty())
        vm.dispatch(Intent.Select("alice"))
        vm.dispatch(Intent.Submit)
        assertTrue(vm.state.value.busy)
        val root = fragment.requireView()
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        val recycler = root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerview_contacts)
        val position = vm.state.value.rows.indexOfFirst { it.uid == "charlie" }
        pumpUntil("busy member checkbox rendered") {
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
            recycler.findViewHolderForAdapterPosition(position) != null
        }
        val holder = recycler.findViewHolderForAdapterPosition(position) as GroupMemberItemViewHolder
        assertFalse(holder.checkBox.isChecked)
        holder.checkBox.performClick()
        assertEquals(setOf("alice"), vm.state.value.selectedIds)
        assertFalse(holder.checkBox.isChecked)
        release.complete(Unit)
        pumpUntil("member submission completed") { !vm.state.value.busy }
        controller.pause().stop().destroy()
    }

    @Test fun `remove and transfer role pages retain only eligible single selection across reload`() {
        for ((action, expected) in listOf(ModeratorAction.REMOVE to setOf("bob"),
            ModeratorAction.TRANSFER to setOf("alice", "bob", "charlie"))) {
            val controller = Robolectric.buildActivity(GroupPageTestActivity::class.java).setup()
            val fragment = GroupModeratorsFragment.newInstance("group", action)
            controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
            val vm = ViewModelProvider(fragment)[GroupPageViewModel::class.java]
            pumpUntil("initial page load") { vm.state.value.loaded }
            assertEquals(expected, vm.state.value.eligibleIds)
            vm.dispatch(Intent.Select("bob"))
            vm.dispatch(Intent.Load)
            pumpUntil("eligible selection reloaded") { !vm.state.value.busy && vm.state.value.selectedIds == setOf("bob") }
            assertEquals(setOf("bob"), vm.state.value.rows.filter { it.isSelected }.map { it.uid }.toSet())
            controller.pause().stop().destroy()
        }
    }

    @Test fun `member add reads existing members from store and remove protects owner`() {
        every { store.contacts() } returns listOf(row("alice", GROUP_ROLE_MEMBER), row("new", GROUP_ROLE_MEMBER))
        for (action in MemberAction.entries) {
            val controller = Robolectric.buildActivity(GroupPageTestActivity::class.java).setup()
            val fragment = GroupSelectMemberFragment.newInstance("group", action)
            controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
            val vm = ViewModelProvider(fragment)[GroupPageViewModel::class.java]
            pumpUntil("initial page load") { vm.state.value.loaded }
            if (action == MemberAction.ADD) {
                vm.dispatch(Intent.Select("alice"))
                assertTrue(vm.state.value.selectedIds.isEmpty())
                assertTrue(vm.state.value.rows.single { it.uid == "alice" }.isSelected)
                assertFalse(vm.state.value.rows.single { it.uid == "alice" }.checkBoxEnable)
                vm.dispatch(Intent.Select("new"))
                assertEquals(setOf("new"), vm.state.value.selectedIds)
            } else {
                vm.dispatch(Intent.Select("owner"))
                assertTrue(vm.state.value.selectedIds.isEmpty())
                vm.dispatch(Intent.Select("alice"))
                assertEquals(setOf("alice"), vm.state.value.selectedIds)
            }
            assertTrue(fragment.requireArguments().keySet().none { it.contains("selected") || it.contains("members") })
            controller.pause().stop().destroy()
        }
    }

    @Test fun `add member rejections keep their specific messages and a role change confirms success`() {
        every { store.contacts() } returns listOf(row("new", GROUP_ROLE_MEMBER))
        mockkObject(com.difft.android.base.widget.ToastUtil)
        try {
            val shown = mutableListOf<String>()
            every { com.difft.android.base.widget.ToastUtil.show(any<String>()) } answers { shown += firstArg<String>() }
            val controller = Robolectric.buildActivity(GroupPageTestActivity::class.java).setup()
            val context = controller.get()
            val fragment = GroupSelectMemberFragment.newInstance("group", MemberAction.ADD)
            context.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
            val vm = ViewModelProvider(fragment)[GroupPageViewModel::class.java]
            pumpUntil("initial page load") { vm.state.value.loaded }
            coEvery { groups.addMembersWithBindings(any(), any(), any()) } returns
                com.difft.android.network.BaseResponse(1, 2, "server text", com.difft.android.network.group.CreateGroupResp("group", null))
            vm.dispatch(Intent.Select("new"))
            vm.dispatch(Intent.Submit)
            pumpUntil("moderator-only rejection shown") { shown.isNotEmpty() }
            assertEquals(context.getString(R.string.invite_only_moderators_can_add), shown.single())
            coEvery { groups.addMembersWithBindings(any(), any(), any()) } returns com.difft.android.network.BaseResponse(1, 10125, null,
                com.difft.android.network.group.CreateGroupResp("group", listOf(com.difft.android.network.group.Stranger("Zed", "zed"))))
            pumpUntil("page reloaded") { !vm.state.value.busy && vm.state.value.loaded }
            
            assertEquals(setOf("new"), vm.state.value.selectedIds)
            vm.dispatch(Intent.Submit)
            pumpUntil("stranger rejection shown") { shown.size == 2 }
            assertEquals(context.getString(R.string.group_not_your_friend, "Zed"), shown[1])
            controller.pause().stop().destroy()

            coEvery { groups.changeMemberRole(any(), any(), any()) } returns com.difft.android.network.BaseResponse<Any>(1, 0, null, null)
            coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns GroupModel().apply { gid = "group"; status = 0 }
            val roleController = Robolectric.buildActivity(GroupPageTestActivity::class.java).setup()
            val rolePage = GroupModeratorsFragment.newInstance("group", ModeratorAction.ADD)
            roleController.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, rolePage).commitNow()
            val roleVm = ViewModelProvider(rolePage)[GroupPageViewModel::class.java]
            pumpUntil("moderator page load") { roleVm.state.value.loaded }
            roleVm.dispatch(Intent.Select("alice"))
            roleVm.dispatch(Intent.Submit)
            pumpUntil("role change confirmed") { shown.size == 3 }
            assertEquals(roleController.get().getString(R.string.operation_successful), shown[2])
            roleController.pause().stop().destroy()
        } finally {
            unmockkObject(com.difft.android.base.widget.ToastUtil)
        }
    }

    @Test fun `management accepted value survives old store while refresh remains pending`() {
        coEvery { groups.changeGroupSettings(any(), any()) } returns
            com.difft.android.network.BaseResponse(1, 0, null, null)
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns null
        val controller = Robolectric.buildActivity(GroupPageTestActivity::class.java).setup()
        val fragment = GroupManagementFragment.newInstance("group")
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        val vm = ViewModelProvider(fragment)[GroupPageViewModel::class.java]
        pumpUntil("initial page load") { vm.state.value.loaded }
        vm.dispatch(Intent.ChangeSetting(Setting.INVITATION, true))
        pumpUntil("accepted setting retained") { !vm.state.value.busy && vm.state.value.pending != null }
        assertTrue(vm.state.value.invitation)
        val generation = vm.state.value.generation
        vm.dispatch(Intent.Load)
        pumpUntil("management reload started") { vm.state.value.generation > generation }
        assertTrue(vm.state.value.invitation)
        val toggle = fragment.requireView().findViewById<com.difft.android.base.widget.DifftSwitchView>(R.id.switch_can_add)
        pumpUntil("accepted setting rendered") { toggle.isChecked }
        assertTrue(toggle.isChecked)
        controller.pause().stop().destroy()
    }

    @Test fun `hidden transfer completion remains pending and replays after view recreation without another API call`() {
        val release = CompletableDeferred<Unit>()
        coEvery { groups.changeGroupSettings(any(), any()) } coAnswers {
            release.await()
            com.difft.android.network.BaseResponse(1, 0, null, null)
        }
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns GroupModel().apply { gid = "group"; status = 0 }
        val navigation = mockk<DetailNavigation>(relaxed = true)
        var completionPops = 0
        every { navigation.pop(any(), 2) } answers { completionPops++; true }
        mockkStatic("com.difft.android.base.utils.pane.DetailNavigationKt")
        every { any<Fragment>().detailNavigation() } returns navigation
        val controller = Robolectric.buildActivity(GroupPageTestActivity::class.java).setup()
        try {
            val fragment = GroupModeratorsFragment.newInstance("group", ModeratorAction.TRANSFER)
            controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
            val vm = ViewModelProvider(fragment)[GroupPageViewModel::class.java]
            pumpUntil("initial page load") { vm.state.value.loaded }
            vm.dispatch(Intent.Select("alice"))
            vm.dispatch(Intent.Submit)
            pumpUntil("transfer submission started") { vm.state.value.busy }
            controller.pause().stop()
            pumpUntil("hidden lifecycle collectors stopped") { true }
            release.complete(Unit)
            pumpUntil("hidden transfer completed") { vm.state.value.completionPopCount == 2 }
            verify(exactly = 0) { navigation.pop(any(), 2) }
            controller.restart().start().resume()
            pumpUntil("visible completion replayed") { completionPops == 1 }
            verify(exactly = 1) { navigation.pop(any(), 2) }
            controller.recreate()
            pumpUntil("recreated completion replayed") { completionPops == 2 }
            verify(exactly = 2) { navigation.pop(any(), 2) }
            coVerify(exactly = 1) { groups.changeGroupSettings(any(), any()) }
        } finally {
            controller.pause().stop().destroy()
            unmockkStatic("com.difft.android.base.utils.pane.DetailNavigationKt")
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `transfer confirmation closes on retained page hide or cover and rejects its late callback`() {
        var confirmation: (() -> Unit)? = null
        mockkObject(ComposeDialogManager)
        every {
            ComposeDialogManager.showMessageDialog(
                context = any(), title = any(), message = any(), confirmText = any(), cancelText = any(),
                showCancel = any(), cancelable = any(), autoDismiss = any(), confirmButtonColor = any(),
                cancelButtonColor = any(), onConfirm = any(), onCancel = any(), onDismiss = any(),
                onClosed = any(), content = any(),
            )
        } answers {
            confirmation = arg(10)
            callOriginal() 
        }
        try {
            for (covered in listOf(false, true)) {
                val controller = Robolectric.buildActivity(GroupPageTestActivity::class.java).setup().visible()
                try {
                    val host = GroupTransferVisibilityHost()
                    controller.get().supportFragmentManager.beginTransaction()
                        .replace(android.R.id.content, host).commitNow()
                    val fragment = GroupModeratorsFragment.newInstance("group", ModeratorAction.TRANSFER)
                    host.childFragmentManager.beginTransaction()
                        .replace(GroupTransferVisibilityHost.CONTENT, fragment).commitNow()
                    val vm = ViewModelProvider(fragment)[GroupPageViewModel::class.java]
                    pumpUntil("transfer page loaded") { vm.state.value.loaded }
                    vm.dispatch(Intent.Select("alice"))
                    val done = fragment.requireView().findViewById<android.widget.TextView>(R.id.done_button)
                    pumpUntil("transfer confirmation enabled") { done.isEnabled }
                    val previousDialog = ShadowDialog.getLatestDialog()
                    done.performClick()
                    pumpUntil("actual transfer dialog shown") {
                        ShadowDialog.getLatestDialog()?.let { it !== previousDialog && it.isShowing } == true
                    }
                    val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                    val lateConfirm = requireNotNull(confirmation)
                    val retainedView = fragment.requireView()
                    assertTrue(dialog.isShowing)
                    assertEquals(setOf("alice"), vm.state.value.selectedIds)

                    host.leaveVisibleTop(fragment, covered)
                    pumpUntil("retained page stopped being the visible top") {
                        !host.visibleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    }
                    assertSame(retainedView, fragment.requireView())
                    pumpUntil("transfer dialog dismissed when covered=$covered") { !dialog.isShowing }
                    assertFalse(dialog.isShowing, "Transfer dialog must close when covered=$covered")
                    
                    lateConfirm()
                    assertFalse(vm.state.value.busy)
                    assertEquals(setOf("alice"), vm.state.value.selectedIds)
                    pumpUntil("late confirmation main callbacks drained") { true }
                    coVerify(exactly = 0) { groups.changeGroupSettings(any(), any()) }
                } finally {
                    controller.pause().stop().destroy()
                    pumpUntil("transfer page destroyed") { true }
                }
            }
        } finally {
            unmockkObject(ComposeDialogManager)
        }
    }

    
    private fun pumpUntil(description: String, condition: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "UI pump must run on the SDK main thread" }
        val deadline = java.time.Clock.systemUTC().millis() + 5_000L
        do {
            (applicationScope as? kotlinx.coroutines.test.TestScope)?.testScheduler?.runCurrent()
            dispatcher.testDispatcher.scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).runOneTask()
            dispatcher.testDispatcher.scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(1) 
        } while (java.time.Clock.systemUTC().millis() < deadline)
        assertTrue(condition(), "Timed out after 5 seconds: $description")
    }

    private fun row(id: String, role: Int) = GroupMemberModel(id, id, null, null, id.take(1), role)
}


class GroupTransferVisibilityHost : Fragment(), DetailStackHost {
    companion object { const val CONTENT = 0x012304ff }
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    val visibleOwner: LifecycleOwner get() = owner
    override val detailNavigation: DetailNavigation = mockk(relaxed = true)
    override fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner = owner
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply {
            id = CONTENT
            owner.registry.currentState = Lifecycle.State.STARTED
        }

    fun leaveVisibleTop(page: Fragment, covered: Boolean) {
        owner.registry.currentState = Lifecycle.State.CREATED
        childFragmentManager.beginTransaction().apply {
            setMaxLifecycle(page, Lifecycle.State.STARTED)
            if (covered) add(CONTENT, Fragment(android.R.layout.simple_list_item_1)) else hide(page)
        }.commitNow()
    }

    override fun onDestroyView() {
        owner.registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroyView()
    }
}

package com.difft.android.dualpane

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.R
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusActionLauncher
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.recent.ChatListSource
import com.difft.android.chat.recent.ChatListSourceModule
import com.difft.android.chat.recent.ChatListSourceProvider
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.recent.FoldedGroupsFragment
import com.difft.android.chat.recent.ListPaneLayerOpener
import com.difft.android.chat.recent.RecentChatAdapter
import com.difft.android.chat.recent.RoomViewData
import com.difft.android.chat.ui.ConversationShells
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home-screen stand-in for the real folded-groups page: the real `activity_index` tree, a
 * navigator with plain conversation roots, the production list-pane layer with
 * [IndexDetailPages]' roots, the opener the chat list's folded row calls, and the router's home
 * callback recording what a row asked to open.
 */
@AndroidEntryPoint
class FoldedLayerWiringTestActivity :
    FragmentActivity(),
    PaneNavigator.Owner,
    ListPaneLayer.Owner,
    ConversationNavigationCallback,
    ListPaneLayerOpener {
    lateinit var navigator: PaneNavigator
        private set
    override val paneNavigator: PaneNavigator get() = navigator
    override lateinit var listLayer: ListPaneLayer
        private set
    val opened = mutableListOf<ConversationTarget>()

    override val isDualPaneMode: Boolean get() = navigator.isDualPane
    override val currentSelectedConversationId: String? = null
    override val detailCoversList: StateFlow<Boolean> = MutableStateFlow(false)
    override val homeTabBarObstructionPx: StateFlow<Int> = MutableStateFlow(0)
    override fun currentConversationOnScreen(): String? = null
    override fun onOneOnOneConversationSelected(contactId: String, jumpMessageTimestamp: Long?) = Unit
    override fun onGroupConversationSelected(groupId: String, jumpMessageTimestamp: Long?) = Unit
    override fun onContactDetailSelected(contactId: String) = Unit
    override fun showDetailFragment(fragment: Fragment, tag: String?) = Unit
    override fun openConversation(target: ConversationTarget) {
        opened += target
    }

    override fun openSearchLayer(): Boolean = false

    override fun openFoldedGroupsLayer(): Boolean {
        listLayer.open(ListPaneLayerRoot.FOLDED_GROUPS)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_index)
        navigator = PaneNavigator(
            this,
            resources.getBoolean(R.bool.dual_pane_layout_active),
            object : PaneNavigator.Pages {
                override fun conversationRoot(target: ConversationTarget): Fragment = Fragment()
                override fun usesSettingsBackground(page: Fragment): Boolean = false
                override fun conversationLayerBackground(context: Context, layer: View): Drawable = ColorDrawable(0)
            },
            object : PaneNavigator.Listener {
                override fun onDetailStateChanged() = Unit
                override fun onCurrentDetailEmptied() = Unit
                override fun onSelectTabRequested(tab: Int) = navigator.selectTab(tab)
            },
        )
        navigator.attach(savedInstanceState)
        listLayer = ListPaneLayer(this, navigator.chrome, IndexDetailPages, navigator.detailCoversList) {}
        listLayer.attach(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        navigator.saveState(outState)
        listLayer.saveState(outState)
    }
}

/**
 * The real [FoldedGroupsFragment] as the root of the list-pane layer (folded groups as a layer
 * §3.2, §5): the folded rows of the supplied chat-list source, a group tap routed to the home
 * screen, the two-pane selection on its row, and the self-close when focus stops folding, also
 * across a restore (R1-F1: the collector waits for STARTED; a not-yet-loaded focus is not judged).
 */
@HiltAndroidTest
@UninstallModules(ChatListSourceModule::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = com.difft.android.test.FoldableTestApplication_Application::class, sdk = [34], qualifiers = "w840dp-h900dp")
class FoldedGroupsLayerIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val globals = GlobalStaticMockRule()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()

    private val source = MutableStateFlow(ChatListSource())
    private val effective = MutableStateFlow(FOLDING)
    private var loaded = CompletableDeferred(Unit)

    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    @BindValue @JvmField val wcdb: WCDB = mockk(relaxed = true)
    @BindValue @JvmField val focusActionLauncher: FocusActionLauncher = mockk(relaxed = true)
    @BindValue @JvmField val focusRepository: GroupFocusRepository = mockk(relaxed = true)
    @BindValue @JvmField val chatListSourceProvider: ChatListSourceProvider = object : ChatListSourceProvider {
        override fun chatListSource(activity: FragmentActivity): Flow<ChatListSource> = source
    }

    private var controller: ActivityController<FoldedLayerWiringTestActivity>? = null
    private val activity: FoldedLayerWiringTestActivity get() = controller!!.get()
    private val layer: ListPaneLayer get() = activity.listLayer

    @Before
    fun setUp() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        mockkObject(AppStartup)
        every { AppStartup.onCriticalRenderEventStart() } just Runs
        every { AppStartup.onCriticalRenderEventEnd() } just Runs
        // The test Application is no ConversationOpener: hand the open to the production router.
        mockkObject(ConversationShells)
        every { ConversationShells.open(any(), any()) } answers { ConversationRouter.open(firstArg(), secondArg()) }
        every { focusRepository.effective } returns effective
        coEvery { focusRepository.ensureLoaded() } coAnswers { loaded.await() }
        hilt.inject()
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        unmockkObject(AppStartup, ConversationShells)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /** The rows are assembled off the main thread: idles until [done] or a timeout. */
    private fun idleUntil(done: () -> Boolean) {
        repeat(200) {
            idle()
            if (done()) return
            Thread.sleep(10)
        }
        idle()
    }

    private fun launch(saved: Bundle? = null) {
        val built = Robolectric.buildActivity(FoldedLayerWiringTestActivity::class.java)
        controller = (if (saved == null) built.setup() else built.setup(saved)).visible()
        idle()
    }

    private fun openLayer(): FoldedGroupsFragment {
        launch()
        assertTrue(activity.openFoldedGroupsLayer())
        idle()
        return assertIs<FoldedGroupsFragment>(layer.host!!.rootPage())
    }

    private fun recycler(page: Fragment): RecyclerView =
        assertNotNull(findRecycler(page.requireView()), "the folded list is composed")

    private fun findRecycler(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) findRecycler(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun adapter(page: Fragment): RecentChatAdapter = recycler(page).adapter as RecentChatAdapter

    private fun rowIds(page: Fragment): List<String> = adapter(page).currentList.mapNotNull { it.chatItemData()?.roomId }

    private fun room(id: String, sortTime: Long, group: Boolean = true) = RoomViewData(
        roomId = id,
        type = if (group) RoomViewData.Type.Group else RoomViewData.Type.OneOnOne,
        roomName = "name-$id",
        // A known member count keeps the group avatar off its WCDB count query.
        groupMembersNumber = 3,
        lastActiveTime = sortTime,
        sortTime = sortTime,
    )

    private fun supplyRooms() {
        source.value = ChatListSource(
            rooms = listOf(room("F", 50), room("a", 40), room("c", 30, group = false), room("b", 20)),
            focus = FOLDING,
            isLoaded = true,
        )
    }

    @Test
    fun `the page shows the folded groups of the supplied source`() {
        supplyRooms()
        val page = openLayer()
        idleUntil { rowIds(page).isNotEmpty() }

        assertEquals(listOf("a", "b"), rowIds(page))
        assertEquals(ListPaneLayerRoot.FOLDED_GROUPS, layer.root)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.list_layer).visibility)
    }

    @Test
    fun `a group tap opens on the home screen and the selection marks its row, the layer stays`() {
        supplyRooms()
        val page = openLayer()
        idleUntil { rowIds(page).isNotEmpty() }
        val list = recycler(page)
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
        list.layout(0, 0, list.measuredWidth, list.measuredHeight)

        val row = assertNotNull(list.findViewHolderForAdapterPosition(0), "first folded row laid out").itemView
        row.findViewById<View>(com.difft.android.chat.R.id.swipe_foreground).performClick()
        idle()

        assertEquals(listOf(ConversationTarget(ConversationTarget.Kind.GROUP, "a", null)), activity.opened)
        assertTrue(layer.active)

        layer.host!!.updateDualPaneSelection("a")
        assertEquals("a", adapter(page).selectedId)
    }

    @Test
    fun `focus ending while the page is open closes the layer`() {
        supplyRooms()
        openLayer()
        assertTrue(layer.active)

        effective.value = EffectiveFocus.INACTIVE
        idle()

        assertFalse(layer.active)
        assertNull(layer.host)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.list_layer).visibility)
    }

    @Test
    fun `a restore before the focus cache loads keeps the page`() {
        openLayer()
        val saved = Bundle()
        controller!!.pause().saveInstanceState(saved).stop().destroy()
        idle()
        controller = null
        // What a process restore sees: the INACTIVE placeholder until the cache loads.
        loaded = CompletableDeferred()
        effective.value = EffectiveFocus.INACTIVE

        launch(saved)
        assertTrue(layer.active)
        assertIs<FoldedGroupsFragment>(layer.host!!.rootPage())

        effective.value = FOLDING
        loaded.complete(Unit)
        idle()
        assertTrue(layer.active)
    }

    @Test
    fun `a restore with the focus already ended closes the layer after the start without failing`() {
        openLayer()
        val saved = Bundle()
        controller!!.pause().saveInstanceState(saved).stop().destroy()
        idle()
        controller = null
        // Loaded and inactive by the time the Activity is recreated.
        effective.value = EffectiveFocus.INACTIVE

        launch(saved)

        assertFalse(layer.active)
        assertNull(layer.host)
        assertEquals(0, activity.supportFragmentManager.fragments.count { it is ListPaneLayerHostFragment })
    }

    private companion object {
        val FOLDING = EffectiveFocus(featureEnabled = true, active = true, groupIds = setOf("F"), foldOthers = true)
    }
}

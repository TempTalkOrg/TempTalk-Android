package com.difft.android.chat.contacts.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.difft.android.base.ui.compose.AzIndexBar
import com.difft.android.base.ui.compose.TileStrip
import com.difft.android.base.ui.compose.safeTopInsets
import com.difft.android.base.ui.glass.GlassBackdrop
import com.difft.android.base.ui.glass.LocalGlassBackdrop
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.compose.ContactAvatar
import com.difft.android.chat.contacts.mvi.ContactsContract.Intent
import com.difft.android.chat.contacts.mvi.ContactsContract.State
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt






@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: State,
    onIntent: (Intent) -> Unit,
    modifier: Modifier = Modifier,
    collapseTitle: Boolean = false,
    showAddInHeader: Boolean = true,
    bandVisible: Boolean = true,
    controlsVisible: Boolean = true,
    bottomObstruction: Dp = DifftTheme.contacts.zero,
    onGlassSource: (com.difft.android.base.ui.glass.GlassBackdrop?) -> Unit = {},
) {
    val density = LocalDensity.current
    val tokens = DifftTheme.contacts
    
    val statusHeight = with(density) { safeTopInsets().getTop(this).toDp() }
    val header = remember { ContactsHeaderScrollState() }
    val connection = remember(header) { header.connection() }
    val fullHeaderPx = with(density) { tokens.homeHeaderHeight.toPx() }
    val titleRange = if (collapseTitle) fullHeaderPx else 0f
    var stripsHeightPx by remember { mutableIntStateOf(0) }
    val initialTabHeight = with(density) { (tokens.tabHeight + tokens.tabBandPadding * 2).roundToPx() }
    var tabHeightPx by remember { mutableIntStateOf(initialTabHeight) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val lists = DirectoryTab.entries.map { rememberLazyListState() }
    val pager = rememberPagerState(initialPage = state.selectedTab.ordinal) { DirectoryTab.entries.size }
    val list = lists[state.selectedTab.ordinal]
    val scope = rememberCoroutineScope()
    LaunchedEffect(titleRange, stripsHeightPx) {
        header.updateRange(titleRange + stripsHeightPx, list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0)
    }
    
    val titleCollapsed = header.offsetPx.coerceIn(0f, titleRange)
    val stripsCollapsed = (header.offsetPx - titleCollapsed).coerceIn(0f, stripsHeightPx.toFloat())
    val titleHeight = with(density) { (fullHeaderPx - titleCollapsed).toDp() }
    val chromeHeight = statusHeight + titleHeight
    val stripsTopPx = with(density) { chromeHeight.toPx() } - stripsCollapsed
    val tabTopPx = stripsTopPx + stripsHeightPx
    val listTop = with(density) { (tabTopPx + tabHeightPx).toDp() }
    val indexHeight = with(density) { (viewportHeight - tabTopPx - tabHeightPx).coerceAtLeast(0f).toDp() } - bottomObstruction
    val indexVisible = controlsVisible && indexHeight > tokens.zero && state.indexLetters.isNotEmpty()
    
    val rowEnd = if (indexVisible) maxOf(tokens.rowEndInset, tokens.indexWidth + tokens.gapMedium) else tokens.rowEndInset
    val listBottom = bottomObstruction + tokens.gapSection
    val largerText = state.textSize == TextSizeUtil.TEXT_SIZE_LAGER
    val refresh = rememberPullToRefreshState()
    
    val tabRequest = remember { mutableStateOf<TabRequest?>(null) }
    DirectoryPagerSync(state, onIntent, pager, lists, header, tabRequest)
    GlassBackdrop(
        modifier = modifier.fillMaxSize().clipToBounds()
            .background(DifftTheme.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .onSizeChanged { viewportHeight = it.height },
        content = {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { onIntent(Intent.PullRefresh) },
                modifier = Modifier.fillMaxSize(),
                state = refresh,
                indicator = {
                    PullToRefreshDefaults.Indicator(state = refresh, isRefreshing = state.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = chromeHeight))
                },
            ) {
                Box(Modifier.fillMaxSize().nestedScroll(connection)) {
                    HorizontalPager(pager, Modifier.fillMaxSize().testTag(CONTACTS_PAGER_TAG), key = { DirectoryTab.entries[it] }) { page ->
                        val tab = DirectoryTab.entries[page]
                        LazyColumn(
                            state = lists[page],
                            modifier = Modifier.fillMaxSize()
                                .testTag(if (tab == state.selectedTab) CONTACTS_LIST_TAG else CONTACTS_LIST_TAG + ":" + tab.name),
                            contentPadding = PaddingValues(top = listTop, bottom = listBottom),
                        ) {
                            
                            item(key = TOP_ANCHOR_KEY) { Spacer(Modifier.fillMaxWidth().height(with(density) { 1.toDp() })) }
                            directorySections(state, tab, largerText, rowEnd, onIntent)
                        }
                    }
                    
                    DirectoryStrips(state, onIntent, { stripsHeightPx = it },
                        Modifier.offset { IntOffset(0, stripsTopPx.roundToInt()) })
                }
            }
        },
        chrome = {
            val source = LocalGlassBackdrop.current
            val register by rememberUpdatedState(onGlassSource)
            DisposableEffect(source) {
                register(source)
                onDispose { register(null) }
            }
            
            val bandBelow = if (header.pinned) with(density) { tabHeightPx.toDp() } else tokens.zero
            ContactsTitleBar(statusHeight, titleHeight, titleCollapsed, bandVisible, controlsVisible,
                showAddInHeader, { onIntent(Intent.AddContact) }, bandBelow = bandBelow)
            
            Box(Modifier.offset { IntOffset(0, tabTopPx.roundToInt()) }) {
                if (controlsVisible) DirectoryTabBand(state.selectedTab, { tab ->
                    tabRequest.value = TabRequest(tab)
                    onIntent(Intent.SelectTab(tab))
                }, Modifier.onSizeChanged { tabHeightPx = it.height }.testTag(CONTACTS_TABS_TAG))
                else Box(Modifier.fillMaxWidth().height(with(density) { tabHeightPx.toDp() }))
            }
            if (indexVisible) {
                Box(Modifier.fillMaxSize().padding(top = listTop, bottom = bottomObstruction), contentAlignment = Alignment.CenterEnd) {
                    AzIndexBar(state.indexLetters, { letter ->
                        directorySectionIndices(state, state.selectedTab)[letter]?.let { target ->
                            
                            header.collapse()
                            scope.launch { list.scrollToItem(target) }
                        }
                    }, Modifier.heightIn(max = indexHeight).clipToBounds().testTag(CONTACTS_INDEX_TAG))
                }
            }
        },
    )
}


private class TabRequest(val tab: DirectoryTab)






@Composable
private fun DirectoryPagerSync(state: State, onIntent: (Intent) -> Unit, pager: PagerState, lists: List<LazyListState>,
    header: ContactsHeaderScrollState, tabRequest: MutableState<TabRequest?>) {
    val selected by rememberUpdatedState(state.selectedTab)
    LaunchedEffect(pager) {
        
        
        snapshotFlow { pager.settledPage to (tabRequest.value == null) }.collect { (page, free) ->
            val tab = DirectoryTab.entries[page]
            if (free && tab != selected) onIntent(Intent.SelectTab(tab))
        }
    }
    
    
    LaunchedEffect(tabRequest.value) {
        val request = tabRequest.value ?: return@LaunchedEffect
        try {
            snapshotFlow { pager.isScrollInProgress }.first { !it }
            val page = request.tab.ordinal
            if (pager.currentPage != page || pager.currentPageOffsetFraction != 0f) pager.animateScrollToPage(page)
        } finally {
            if (tabRequest.value === request) tabRequest.value = null
        }
    }
    LaunchedEffect(header) {
        snapshotFlow { header.pinned }.collect { pinned ->
            if (!pinned) lists.forEach { if (it.firstVisibleItemIndex != 0 || it.firstVisibleItemScrollOffset != 0) it.requestScrollToItem(0) }
        }
    }
    LaunchedEffect(state.selectedTab) {
        val page = state.selectedTab.ordinal
        snapshotFlow { pager.isScrollInProgress }.first { !it }
        
        if (tabRequest.value != null) return@LaunchedEffect
        
        if (pager.currentPage == page && pager.currentPageOffsetFraction == 0f) return@LaunchedEffect
        
        if (state.hasUserSelectedTab) pager.animateScrollToPage(page) else pager.scrollToPage(page)
    }
}

internal const val CONTACTS_LIST_TAG = "contacts_directory_list"
internal const val CONTACTS_PAGER_TAG = "contacts_directory_pager"
internal const val CONTACTS_TABS_TAG = "contacts_directory_tabs"
internal const val CONTACTS_STRIPS_TAG = "contacts_directory_strips"
internal const val CONTACTS_INDEX_TAG = "contacts_directory_index"

internal const val CONTACTS_SECTION_TAG_PREFIX = "contacts_section:"
private const val TOP_ANCHOR_KEY = "top_anchor"






@Composable
private fun DirectoryStrips(state: State, onIntent: (Intent) -> Unit, onHeight: (Int) -> Unit, modifier: Modifier) {
    val tokens = DifftTheme.contacts
    Column(modifier.fillMaxWidth()
        .wrapContentHeight(Alignment.Top, unbounded = true)
        .onSizeChanged { onHeight(it.height) }
        .background(DifftTheme.colors.background)
        .scrollable(rememberScrollableState { 0f }, Orientation.Vertical)
        .testTag(CONTACTS_STRIPS_TAG)) {
        if (state.pendingRemoval.isNotEmpty()) {
            TileStrip(stringResource(R.string.weak_contact_pending_group_title), state.pendingRemoval.size,
                Modifier.padding(vertical = tokens.gapSection)) {
                items(state.pendingRemoval, key = { it.row.id }) { tile ->
                    CountdownAvatarTile(
                        avatar = { ContactAvatar(tile.row.contact, tokens.tileAvatar, it) },
                        name = tile.row.displayName, days = tile.displayDays,
                        todayLabel = stringResource(R.string.contacts_today),
                        onClick = { onIntent(Intent.OpenContact(tile.row.id)) },
                        selected = tile.row.id == state.selectedId,
                    )
                }
            }
        }
        if (state.expiringGroups.isNotEmpty()) {
            TileStrip(stringResource(R.string.contacts_expiring_groups), state.expiringGroups.size,
                Modifier.padding(vertical = tokens.gapSection)) {
                items(state.expiringGroups, key = { it.row.id }) { tile ->
                    CountdownAvatarTile(
                        avatar = { GroupAvatarWithCount(tile.row.group, tokens.tileAvatar, showCount = false,
                            memberCount = tile.row.memberCount, modifier = it) },
                        name = tile.row.displayName, days = tile.daysLeft,
                        todayLabel = stringResource(R.string.contacts_today),
                        onClick = { onIntent(Intent.OpenGroup(tile.row.id)) },
                        selected = tile.row.id == state.selectedId,
                    )
                }
            }
        }
        if (state.pendingRemoval.isNotEmpty() || state.expiringGroups.isNotEmpty()) {
            HorizontalDivider(thickness = DifftTheme.hairline, color = DifftTheme.colors.divider)
        }
    }
}

private fun LazyListScope.directorySections(state: State, tab: DirectoryTab, largerText: Boolean, rowEnd: Dp, onIntent: (Intent) -> Unit) {
    if (tab == DirectoryTab.Contacts) state.contactSections.forEach { section ->
        item(key = "contact_section:${section.letter}") { DirectorySectionHeader(section.letter, Modifier.testTag(CONTACTS_SECTION_TAG_PREFIX + section.letter)) }
        itemsIndexed(section.rows, key = { _, row -> "contact:${row.id}" }) { index, row ->
            DirectoryRow(row.contact, row.displayName, { onIntent(Intent.OpenContact(row.id)) },
                messageExpirySeconds = row.messageExpirySeconds, official = row.isOfficial,
                self = row.isSelf, largerText = largerText,
                selected = row.id == state.selectedId, isLastInSection = index == section.rows.lastIndex, endInset = rowEnd)
        }
    } else state.groupSections.forEach { section ->
        item(key = "group_section:${section.letter}") { DirectorySectionHeader(section.letter, Modifier.testTag(CONTACTS_SECTION_TAG_PREFIX + section.letter)) }
        itemsIndexed(section.rows, key = { _, row -> "group:${row.id}" }) { index, row ->
            DirectoryRow(row.group, row.displayName, { onIntent(Intent.OpenGroup(row.id)) },
                memberCount = row.memberCount, messageExpirySeconds = row.messageExpirySeconds,
                largerText = largerText, selected = row.id == state.selectedId,
                isLastInSection = index == section.rows.lastIndex, endInset = rowEnd)
        }
    }
}

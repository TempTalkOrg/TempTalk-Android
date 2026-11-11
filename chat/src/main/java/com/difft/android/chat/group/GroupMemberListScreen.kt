package com.difft.android.chat.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.floatingPill
import com.difft.android.base.ui.compose.input.DifftSearchBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.common.compose.ContactAvatar
import com.difft.android.chat.group.GroupMemberListContract.Member
import com.difft.android.chat.group.GroupMemberListContract.State
import com.difft.android.base.R as BaseR





@Composable
fun GroupMemberListScreen(
    state: State,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    var searching by remember { mutableStateOf(false) }
    val searchGap = searchBarGap()
    val hits = state.members.matching(state.query)
    val moderators = hits.filter { it.isModerator }
    val participants = hits.filterNot { it.isModerator }
    Column(modifier.fillMaxSize().background(DifftTheme.colors.background)) {
        TitleBar(titleText = stringResource(R.string.group_member_list_title), onBackClick = onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().testTag(GROUP_MEMBER_LIST_TAG),
                contentPadding = PaddingValues(bottom = SEARCH_BAR_HEIGHT + searchGap + LIST_CLEARANCE)) {
                memberSection(R.string.group_member_list_moderators, moderators, state.query, onOpen)
                memberSection(R.string.group_member_list_participants, participants, state.query, onOpen)
            }
            if (!state.loading && hits.isEmpty() && state.query.isNotBlank()) {
                Text(stringResource(R.string.group_member_list_no_match, state.query.trim()),
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = DifftTheme.spacing.insetLarge),
                    style = DifftTheme.typography.p3, color = DifftTheme.colors.textTertiary)
            }
            
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = DifftTheme.spacing.insetLarge, end = DifftTheme.spacing.insetLarge, bottom = searchGap),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DifftTheme.spacing.inlineSmall)) {
                
                
                Box(Modifier.weight(1f).height(SEARCH_BAR_HEIGHT).floatingPill()
                    .clickable(interactionSource = null, indication = null) { searchFocus.requestFocus() }
                    .padding(start = SEARCH_BAR_START, end = SEARCH_BAR_END)
                    .testTag(GROUP_MEMBER_SEARCH_TAG),
                    contentAlignment = Alignment.CenterStart) {
                    DifftSearchBar(
                        query = state.query,
                        onQueryChange = onQueryChange,
                        onClear = { onQueryChange("") },
                        modifier = Modifier.fillMaxWidth().onFocusChanged { searching = it.isFocused },
                        containerColor = Color.Transparent,
                        focusRequester = searchFocus,
                    )
                }
                
                if (searching || state.query.isNotEmpty()) SearchCloseButton {
                    onQueryChange("")
                    focusManager.clearFocus()
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.memberSection(
    titleRes: Int,
    members: List<Member>,
    query: String,
    onOpen: (String) -> Unit,
) {
    if (members.isEmpty()) return
    item(key = "header:$titleRes") {
        Text("${stringResource(titleRes)} · ${members.size}",
            modifier = Modifier.fillMaxWidth().padding(start = DifftTheme.spacing.insetLarge, end = DifftTheme.spacing.insetLarge,
                top = DifftTheme.spacing.insetMedium, bottom = DifftTheme.spacing.insetXSmall),
            style = DifftTheme.typography.p4, color = DifftTheme.colors.textTertiary)
    }
    items(members, key = { "member:${it.id}" }) { member -> MemberRow(member, query, onOpen) }
}


@Composable
private fun SearchCloseButton(onClick: () -> Unit) {
    Box(Modifier.size(SEARCH_BAR_HEIGHT).floatingPill().clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(BaseR.drawable.base_ic_close), stringResource(BaseR.string.base_close),
            Modifier.size(SEARCH_CLOSE_ICON), tint = DifftTheme.colors.icon)
    }
}


@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun searchBarGap(): Dp {
    if (WindowInsets.isImeVisible) return SEARCH_KEYBOARD_GAP
    val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return maxOf(SEARCH_MIN_GAP, SEARCH_RESTING_GAP - navBottom)
}

@Composable
private fun MemberRow(member: Member, query: String, onOpen: (String) -> Unit) {
    val textStart = DifftTheme.spacing.insetLarge + MEMBER_AVATAR + DifftTheme.spacing.insetMedium
    Box(Modifier.fillMaxWidth().height(MEMBER_ROW_HEIGHT).clickable { onOpen(member.id) }) {
        Row(Modifier.fillMaxSize().padding(horizontal = DifftTheme.spacing.insetLarge), verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(member.contact, MEMBER_AVATAR)
            Spacer(Modifier.width(DifftTheme.spacing.insetMedium))
            Text(highlighted(member.name, query, DifftTheme.colors.textInfo), style = DifftTheme.typography.p2,
                color = DifftTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(Modifier.align(Alignment.BottomStart).padding(start = textStart),
            thickness = DifftTheme.hairline, color = DifftTheme.colors.divider)
    }
}


internal fun highlighted(text: String, query: String, color: Color): AnnotatedString {
    val key = query.trim()
    if (key.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var from = 0
        while (true) {
            val at = text.indexOf(key, from, ignoreCase = true)
            if (at < 0) break
            addStyle(SpanStyle(color = color), at, at + key.length)
            from = at + key.length
        }
    }
}

private val MEMBER_ROW_HEIGHT = 72.dp
private val MEMBER_AVATAR = 52.dp
private val SEARCH_BAR_HEIGHT = 48.dp
private val SEARCH_BAR_START = 14.dp
private val SEARCH_BAR_END = 4.dp
private val SEARCH_CLOSE_ICON = 24.dp
private val SEARCH_KEYBOARD_GAP = 10.dp
private val SEARCH_RESTING_GAP = 28.dp
private val SEARCH_MIN_GAP = 8.dp

private val LIST_CLEARANCE = 12.dp

internal const val GROUP_MEMBER_LIST_TAG = "group_member_list"
internal const val GROUP_MEMBER_SEARCH_TAG = "group_member_search"

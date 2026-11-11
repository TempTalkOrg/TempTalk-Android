package com.difft.android.chat.contacts.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.base.ui.compose.FloatingIconButton
import com.difft.android.base.ui.compose.cornerCutoutPadding
import com.difft.android.base.ui.glass.glassBand
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

internal fun contactsTitleCanCollapse(windowHeightDp: Int, multiWindow: Boolean): Boolean =
    !multiWindow && windowHeightDp < 700






@Stable
internal class ContactsHeaderScrollState {
    var offsetPx by mutableFloatStateOf(0f)
        private set
    var rangePx by mutableFloatStateOf(0f)
        private set
    val pinned: Boolean get() = offsetPx >= rangePx

    
    fun scroll(delta: Float): Float {
        val next = (offsetPx - delta).coerceIn(0f, rangePx)
        val consumed = offsetPx - next
        offsetPx = next
        return consumed
    }

    
    fun updateRange(range: Float, listAtTop: Boolean) {
        offsetPx = if (pinned && !listAtTop) range else offsetPx.coerceAtMost(range)
        rangePx = range
    }

    fun collapse() { offsetPx = rangePx }
}





internal fun ContactsHeaderScrollState.connection(): NestedScrollConnection =
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0) Offset(0f, scroll(available.y)) else Offset.Zero

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0) Offset(0f, scroll(available.y)) else Offset.Zero
    }


@Composable
internal fun contactsChromeVisibility(window: Lifecycle?, covered: StateFlow<Boolean>?): Pair<Boolean, Boolean> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lastFront by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var pageState by remember(lifecycle) { mutableStateOf(lifecycle.currentState) }
    var windowState by remember(window) { mutableStateOf(window?.currentState) }
    val hidden = covered?.collectAsStateWithLifecycle()?.value == true
    DisposableEffect(lifecycle, window) {
        val pageObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> lastFront = true
                Lifecycle.Event.ON_PAUSE -> if (window?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false) lastFront = false
                else -> Unit
            }
            pageState = lifecycle.currentState
        }
        val windowObserver = LifecycleEventObserver { _, _ -> windowState = window?.currentState }
        lifecycle.addObserver(pageObserver)
        window?.addObserver(windowObserver)
        onDispose { lifecycle.removeObserver(pageObserver); window?.removeObserver(windowObserver) }
    }
    val front = pageState.isAtLeast(Lifecycle.State.RESUMED) ||
        (lastFront && windowState?.isAtLeast(Lifecycle.State.RESUMED) == false && pageState.isAtLeast(Lifecycle.State.STARTED))
    return front to (front && !hidden)
}

@Composable
internal fun ContactsTitleBar(
    statusHeight: Dp,
    visibleHeight: Dp,
    collapsedPx: Float,
    bandVisible: Boolean,
    controlsVisible: Boolean,
    showAdd: Boolean,
    onAdd: () -> Unit,
    bandBelow: Dp = DifftTheme.contacts.zero,
) {
    val tokens = DifftTheme.contacts
    
    Box(Modifier.fillMaxWidth().height(statusHeight + visibleHeight + bandBelow)
        .then(if (bandVisible) Modifier.glassBand() else Modifier)) {
        Box(Modifier.padding(top = statusHeight).height(visibleHeight).clipToBounds()) {
            if (controlsVisible) Row(
                Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(tokens.homeHeaderHeight)
                    .offset { IntOffset(0, -collapsedPx.roundToInt()) }
                    .cornerCutoutPadding().padding(horizontal = DifftTheme.spacing.insetLarge).testTag(CONTACTS_TITLE_ROW_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.chat_contacts), style = DifftTheme.typography.h4, color = DifftTheme.colors.textPrimary)
                Spacer(Modifier.weight(1f))
                if (showAdd) FloatingIconButton(R.drawable.chat_tabler_add_contact,
                    stringResource(R.string.contact_add_contacts), onAdd, iconSize = DifftTheme.spacing.iconMedium)
            }
        }
    }
}

internal const val CONTACTS_TITLE_ROW_TAG = "contacts_title_row"

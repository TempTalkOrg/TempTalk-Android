package com.difft.android.home

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.ui.sidechrome.SideChromeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach






internal object HomeSideChrome {

    fun attach(
        owner: LifecycleOwner,
        chrome: SideChromeController,
        tabState: StateFlow<HomeTabCapsuleState>,
        onTabClick: (Int) -> Unit,
        onChatsDoubleTap: () -> Unit,
        onSearch: () -> Unit,
        onSearchFrom: (android.graphics.Rect) -> Unit,
        listCovered: StateFlow<Boolean>,
        bottomBar: View,
        bottomBarObstruction: MutableStateFlow<Int>,
        dualPaneMinWidthDp: () -> Float,
        onReserveChanged: () -> Unit,
    ) {
        chrome.dualPaneMinWidthDp = dualPaneMinWidthDp
        chrome.lower = {
            val state by tabState.collectAsStateWithLifecycle()
            HomeTabRail(state = state, onTabClick = onTabClick, onChatsDoubleTap = onChatsDoubleTap, onSearch = onSearch, onSearchFrom = onSearchFrom)
        }
        listCovered.onEach { covered ->
            chrome.lowerVisible = !covered
            
            chrome.registry.preferBase = !covered
        }.launchIn(owner.lifecycleScope)
        snapshotFlow { chrome.reservedPx }
            .distinctUntilChanged()
            .onEach { reserve ->
                
                bottomBar.visibility = if (reserve > 0) View.GONE else View.VISIBLE
                if (reserve > 0) bottomBarObstruction.value = 0
            }
            .drop(1)
            .onEach { bottomBar.post(onReserveChanged) }
            .launchIn(owner.lifecycleScope)
    }
}

package com.difft.android.chat.gif.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.gif.GifPanelContract.GifTab


private data class TabSpec(val tab: GifTab, val iconRes: Int, val enabled: Boolean)





@Composable
fun GifTabBar(
    selectedTab: GifTab,
    favoritesEnabled: Boolean,
    moodTabsEnabled: Boolean,
    onTabClick: (GifTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        TabSpec(GifTab.SEARCH, R.drawable.chat_ic_gif_tab_search, enabled = true),
        TabSpec(GifTab.FAVORITES, R.drawable.chat_ic_gif_tab_favorites, enabled = favoritesEnabled),
        TabSpec(GifTab.TRENDING, R.drawable.chat_ic_gif_tab_trending, enabled = true),
        TabSpec(GifTab.MOOD_HAPPY, R.drawable.chat_ic_gif_tab_mood_happy, enabled = moodTabsEnabled),
        TabSpec(GifTab.MOOD_SAD, R.drawable.chat_ic_gif_tab_mood_sad, enabled = moodTabsEnabled)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { spec ->
            GifTabItem(
                spec = spec,
                selected = spec.tab == selectedTab,
                onClick = { if (spec.enabled) onTabClick(spec.tab) }
            )
        }
    }
}

@Composable
private fun GifTabItem(
    spec: TabSpec,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint: Color = when {
        !spec.enabled -> DifftTheme.colors.textDisabled
        selected -> DifftTheme.colors.icon
        else -> DifftTheme.colors.textTertiary
    }
    val containerBg = if (selected) DifftTheme.colors.backgroundTertiary else Color.Transparent
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(DifftTheme.radius.largeShape)
            .background(containerBg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = spec.enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(spec.iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

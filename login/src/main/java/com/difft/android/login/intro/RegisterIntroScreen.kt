package com.difft.android.login.intro

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.login.R
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun RegisterIntroScreen(
    onFinishIntro: () -> Unit,
    onBackToSignUp: () -> Unit,
) {
    val pages = remember { registerIntroPages() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    var pagerTopYInRoot by remember { mutableIntStateOf(0) }
    var buttonBottomYInRoot by remember { mutableIntStateOf(0) }

    val onBack: () -> Unit = {
        val current = pagerState.currentPage
        if (current == 0) {
            onBackToSignUp()
        } else {
            scope.launch { pagerState.animateScrollToPage(current - 1) }
        }
    }

    BackHandler(enabled = true, onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DifftTheme.colors.bg)
            .systemBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                onBack = onBack,
                onSkip = onFinishIntro,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        pagerTopYInRoot = coords.positionInRoot().y.toInt()
                    }
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { pageIndex ->
                    RegisterIntroPage(
                        data = pages[pageIndex],
                        onNext = {
                            if (pageIndex == pages.lastIndex) {
                                onFinishIntro()
                            } else {
                                scope.launch { pagerState.animateScrollToPage(pageIndex + 1) }
                            }
                        },
                        // Indicator anchors to page 0's button only — content lengths differ
                        // across pages, so locking to one page keeps the indicator stationary.
                        onButtonBottomYInRoot = if (pageIndex == 0) {
                            { y -> buttonBottomYInRoot = y }
                        } else {
                            { }
                        },
                    )
                }
                // Anchored to the bottom of the centered Next button via measured layout
                // (rather than a fixed bottom padding) so the indicator sits a fixed gap
                // below the button regardless of screen size or content height.
                val indicatorOffsetY = (buttonBottomYInRoot - pagerTopYInRoot)
                    .coerceAtLeast(0)
                AnimatedPageIndicator(
                    pagerState = pagerState,
                    pageCount = pages.size,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(x = 0, y = indicatorOffsetY + 24.dp.roundToPx()) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnimatedPageIndicator(
    pagerState: PagerState,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    val activeColor = DifftTheme.colors.textPrimary
    val inactiveColor = DifftTheme.colors.textDisabled
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val scrollPos = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val activeFraction = (1f - abs(scrollPos - index)).coerceIn(0f, 1f)
            val width = lerp(6.dp, 16.dp, activeFraction)
            val color = lerp(inactiveColor, activeColor, activeFraction)
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = color,
                modifier = Modifier.size(width = width, height = 6.dp),
                content = {},
            )
        }
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = ImageVector.vectorResource(id = com.difft.android.base.R.drawable.chative_ic_back),
                contentDescription = null,
                tint = DifftTheme.colors.textPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        TextButton(onClick = onSkip) {
            Text(
                text = stringResource(id = R.string.register_intro_skip),
                style = DifftTheme.typography.bodyMedium,
                color = DifftTheme.colors.textSecondary,
            )
        }
    }
}

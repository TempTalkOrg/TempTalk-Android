package com.difft.android.call.ui.barrage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.EmojiBubbleMessage
import com.difft.android.call.data.TextBubbleMessage
import kotlinx.coroutines.launch

/**
 * 独立的气泡飘动层。
 *
 * 设计意图（关键）：
 *
 * 过去把 `BubbleAnimationLayer` 放在 `BarrageMessageView` 内部时，
 * 无论怎么在其父 Box 上做 frozen-size、Modifier.layout、onSizeChanged 等隔离，
 * 只要外层 `BarrageMessageView` 因为弹幕菜单展开或控制栏显隐而 recompose，
 * 同一颗子树里就会触发一次 measure/placement invalidation；
 * 叠加文本子像素舍入，就表现为"菜单/控制栏一出现，气泡瞬间向上跳 10~30dp"。
 *
 * 这个组件把气泡飘动完全挪到 `CallSurface` 的兄弟层：
 *   - 它不读 `showSimpleBarrageEnabled`、`showBottomToolBarViewEnabled` 等任何
 *     会随菜单/控制栏变化的 state；
 *   - 它独立 collect `callUiController.emojiBubbleMessage` /
 *     `textBubbleMessage` 两条 [kotlinx.coroutines.flow.SharedFlow]；
 *   - 它的 @Composable 子树与 [BarrageMessageView] 完全平行，任何
 *     [BarrageMessageView] 内部的 recomposition 都不再波及这里的气泡。
 *
 * 由此得到"气泡参考系"真正不可破坏：外层 Box 始终是 `fillMaxSize()`
 * 的 Surface 兄弟，大小只跟随 Activity 窗口，而窗口在菜单/控制栏切换
 * 时并不变化。
 */
@Composable
fun BubbleOverlayLayer(viewModel: LCallViewModel) {
    val callUiController = viewModel.callUiController
    val coroutineScope = rememberCoroutineScope()

    val isInPipMode by callUiController.isInPipMode.collectAsState(false)

    val emojiBubbles = remember { mutableStateListOf<EmojiBubbleMessage>() }
    val textBubbles = remember { mutableStateListOf<TextBubbleMessage>() }

    LaunchedEffect(Unit) {
        callUiController.emojiBubbleMessage.collect { bubble ->
            if (!callUiController.isInPipMode.value) emojiBubbles.add(bubble)
        }
    }

    LaunchedEffect(Unit) {
        callUiController.textBubbleMessage.collect { bubble ->
            if (!callUiController.isInPipMode.value) textBubbles.add(bubble)
        }
    }

    LaunchedEffect(isInPipMode) {
        if (isInPipMode) {
            emojiBubbles.clear()
            textBubbles.clear()
        }
    }

    if (isInPipMode) return

    Box(modifier = Modifier.fillMaxSize()) {
        emojiBubbles.forEach { bubble ->
            key(bubble.id) {
                EmojiBubbleView(
                    bubbleMessage = bubble,
                    onAnimationEnd = {
                        coroutineScope.launch {
                            kotlinx.coroutines.yield()
                            emojiBubbles.remove(bubble)
                        }
                    }
                )
            }
        }
        textBubbles.forEach { bubble ->
            key(bubble.id) {
                TextBubbleView(
                    bubbleMessage = bubble,
                    onAnimationEnd = {
                        coroutineScope.launch {
                            kotlinx.coroutines.yield()
                            textBubbles.remove(bubble)
                        }
                    }
                )
            }
        }
    }
}

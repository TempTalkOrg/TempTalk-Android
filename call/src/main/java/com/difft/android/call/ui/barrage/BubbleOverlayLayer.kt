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

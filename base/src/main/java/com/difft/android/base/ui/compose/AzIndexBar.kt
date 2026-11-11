package com.difft.android.base.ui.compose

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.sideBar.SideBarLayout
import com.difft.android.base.widget.sideBar.SideBarSlot






@Composable
fun AzIndexBar(letters: List<String>, onLetter: (String) -> Unit, modifier: Modifier = Modifier) {
    if (letters.isEmpty()) return
    val callback by rememberUpdatedState(onLetter)
    val tokens = DifftTheme.contacts
    val density = LocalDensity.current
    val view = LocalView.current
    val baseStyle = DifftTheme.typography.h7
    BoxWithConstraints(modifier.width(tokens.indexWidth)) {
        val availablePx = constraints.maxHeight.toFloat()
        val preferredPx = with(density) { tokens.indexLetterHeight.toPx() }
        val slots = remember(letters.size, availablePx) {
            SideBarLayout.slots(letters.size, availablePx, with(density) { MIN_SLOT_HEIGHT.toPx() })
        }
        if (slots.isEmpty()) return@BoxWithConstraints
        val slotPx = minOf(preferredPx, availablePx / slots.size)
        val fitted = with(density) { (slotPx * TEXT_TO_SLOT_RATIO).toSp() }
        val fontSize = if (fitted < baseStyle.fontSize) fitted else baseStyle.fontSize
        
        
        
        var chosen by remember { mutableStateOf<String?>(null) }
        
        
        val currentLetters by rememberUpdatedState(letters)
        val currentSlots by rememberUpdatedState(slots)
        val currentSlotPx by rememberUpdatedState(slotPx)
        Column(
            modifier = Modifier.width(tokens.indexWidth).height(with(density) { (slotPx * slots.size).toDp() })
                .pointerInput(Unit) {
                    awaitEachGesture {
                        fun select(y: Float) {
                            val index = SideBarLayout.letterIndexAt(currentSlots, currentSlotPx, y)
                            val letter = currentLetters.getOrNull(index) ?: return
                            if (letter != chosen) {
                                chosen = letter
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                callback(letter)
                            }
                        }
                        val down = awaitFirstDown()
                        select(down.position.y)
                        down.consume()
                        drag(down.id) { change -> select(change.position.y); change.consume() }
                        chosen = null
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            slots.forEach { slot ->
                val (text, selected, target) = when (slot) {
                    is SideBarSlot.Letter -> Triple(letters[slot.index], letters[slot.index] == chosen, slot.index)
                    is SideBarSlot.Dot -> Triple(DOT, chosen in letters.subList(slot.fromIndex, slot.toIndex + 1), slot.fromIndex)
                }
                Box(Modifier.height(with(density) { slotPx.toDp() }).width(tokens.indexWidth)
                    .semantics(mergeDescendants = true) { onClick { callback(letters[target]); true } },
                    contentAlignment = Alignment.Center) {
                    Text(text, modifier = Modifier.wrapContentHeight(Alignment.CenterVertically, unbounded = true),
                        
                        style = baseStyle.copy(fontSize = fontSize),
                        textAlign = TextAlign.Center,
                        color = if (selected) DifftTheme.colors.primary else DifftTheme.colors.textInfo,
                        maxLines = 1)
                }
            }
        }
    }
}

private val MIN_SLOT_HEIGHT = 12.dp
private const val TEXT_TO_SLOT_RATIO = 0.8f
private const val DOT = "•"

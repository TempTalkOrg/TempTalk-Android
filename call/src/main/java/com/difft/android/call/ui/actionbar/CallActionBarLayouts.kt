package com.difft.android.call.ui.actionbar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.R

/**
 * The controls a bar layout arranges. Each lambda renders exactly one round control (no
 * caption); the layouts decide placement, gaps and captions.
 */
class ActionBarSlots(
    val mic: @Composable () -> Unit,
    val video: @Composable () -> Unit,
    val speaker: @Composable () -> Unit,
    val invite: @Composable () -> Unit,
    val people: @Composable () -> Unit,
    val more: @Composable () -> Unit,
    val emoji: @Composable () -> Unit,
    val end: @Composable () -> Unit,
)

/** Captions for [TwoRowActionBar]. */
data class ActionBarLabels(
    val mic: String,
    val video: String,
    val speaker: String,
    val emoji: String,
    val more: String,
    val end: String,
)

/** Two-row 1v1 bar backplate: page ground at 72% (backdrop blur is not available in Compose). */
private const val TWO_ROW_PLATE_ALPHA = 0.72f

/**
 * 1v1 portrait phones: Mute / Video / Speaker over Emoji / More / End, captions under each,
 * three equal columns inside a 343dp-max container. [showPlate] adds the rounded ground
 * behind the rows while either side's camera is on, so captions stay legible over video.
 */
@Composable
fun TwoRowActionBar(
    slots: ActionBarSlots,
    labels: ActionBarLabels,
    showPlate: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CallActionBarPlanner.H_INSET_DP.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                // Cap first, then fill: the other order lets fillMaxWidth fix the constraints
                // before the cap is seen, which would stretch the rows past 343dp.
                .widthIn(max = CallActionBarPlanner.TWO_ROW_MAX_WIDTH_DP.dp)
                .fillMaxWidth()
                .testTag("call_action_bar_two_row")
                .then(
                    if (showPlate) {
                        Modifier
                            .background(
                                color = DifftTheme.colors.backgroundElevate.copy(alpha = TWO_ROW_PLATE_ALPHA),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .padding(vertical = CallActionBarPlanner.TWO_ROW_PLATE_PADDING_DP.dp)
                    } else {
                        Modifier
                    }
                ),
            verticalArrangement = Arrangement.spacedBy(CallActionBarPlanner.TWO_ROW_GAP_DP.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                LabeledActionSlot(labels.mic, Modifier.weight(1f)) { slots.mic() }
                LabeledActionSlot(labels.video, Modifier.weight(1f)) { slots.video() }
                LabeledActionSlot(labels.speaker, Modifier.weight(1f)) { slots.speaker() }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                LabeledActionSlot(labels.emoji, Modifier.weight(1f)) { slots.emoji() }
                LabeledActionSlot(labels.more, Modifier.weight(1f)) { slots.more() }
                LabeledActionSlot(labels.end, Modifier.weight(1f)) { slots.end() }
            }
        }
    }
}

/**
 * Wide windows: Emoji pinned to the start inset, End pinned to the end inset, the control
 * group (Mute Video Speaker Invite [People] More) centred on the window.
 */
@Composable
fun SplitActionBar(
    plan: ActionBarPlan,
    slots: ActionBarSlots,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(plan.buttonSizeDp.dp)
            .testTag("call_action_bar_split"),
    ) {
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = CallActionBarPlanner.H_INSET_DP.dp)) {
            slots.emoji()
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(plan.gapDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.mic()
            slots.video()
            slots.speaker()
            slots.invite()
            if (plan.showPeople) slots.people()
            slots.more()
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = CallActionBarPlanner.H_INSET_DP.dp)) {
            slots.end()
        }
    }
}

/**
 * One centred row. Emoji leads the row when [ActionBarPlan.emojiInline]; otherwise it lives in
 * [OutsideEmojiButton] above the row and the row starts at Mute.
 */
@Composable
fun SingleRowActionBar(
    plan: ActionBarPlan,
    slots: ActionBarSlots,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(plan.buttonSizeDp.dp)
            .testTag("call_action_bar_row"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(plan.gapDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (plan.emojiInline) slots.emoji()
            slots.mic()
            slots.video()
            slots.speaker()
            slots.more()
            slots.end()
        }
    }
}

/** Inner hairline that lifts the outside Emoji pill off a dark ground. */
private val OUTSIDE_EMOJI_RING = Color(0xE65E6673)
private const val OUTSIDE_EMOJI_BG_ALPHA = 0.9f

/**
 * Emoji entry for [ActionBarLayout.EMOJI_OUTSIDE] / [ActionBarLayout.COMPACT]: a 40dp pill at
 * the start inset, floating above the row.
 */
@Composable
fun OutsideEmojiButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(CallActionBarPlanner.OUTSIDE_EMOJI_SIZE_DP.dp)
            .actionButtonShadow()
            .background(
                color = DifftTheme.colors.backgroundSecondary.copy(alpha = OUTSIDE_EMOJI_BG_ALPHA),
                shape = CircleShape,
            )
            .border(width = 1.dp, color = OUTSIDE_EMOJI_RING, shape = CircleShape)
            .testTag("call_btn_emoji_outside")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.tabler_mood_smile),
            contentDescription = "emoji",
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(DifftTheme.colors.textPrimary),
            modifier = Modifier.size(CallActionBarPlanner.OUTSIDE_EMOJI_ICON_DP.dp),
        )
    }
}

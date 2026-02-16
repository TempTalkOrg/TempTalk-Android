package com.difft.android.call.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.util.StringUtil
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowCriticalAlertConfirmView(viewModel: LCallViewModel, onDismiss: () -> Unit, sendCriticalAlert: (String?) -> Unit = {}) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val isCriticalAlertEnable by viewModel.callUiController.showCriticalAlertConfirmViewEnabled.collectAsState(false)
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val isParticipantSharedScreen by viewModel.callUiController.isShareScreening.collectAsState(false)
    val callType by viewModel.callType.collectAsState()
    val gid = if(callType == CallType.GROUP.type) viewModel.conversationId else null
    val invitees by viewModel.participantManager.awaitingJoinInvitees.collectAsState(emptyList())
    val message = remember { mutableStateOf("") }
    val showTooltip = remember { mutableStateOf(false) }
    val tooltipAnchorBounds = remember { mutableStateOf<IntRect?>(null) }
    val tooltipSpacingPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val tooltipPositionProvider = remember(tooltipSpacingPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val bounds = tooltipAnchorBounds.value ?: return IntOffset.Zero
                val x = bounds.left + (bounds.width - popupContentSize.width) / 2
                val y = bounds.bottom + tooltipSpacingPx
                return IntOffset(x, y)
            }
        }
    }

    val contactorCacheManager = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance).contactorCacheManager
    }

    LaunchedEffect(invitees) {
        message.value = getCriticalAlertConfirmViewMessage(contactorCacheManager, callType, invitees)
    }

    if (isCriticalAlertEnable && !isInPipMode) {

        ModalBottomSheet (
            modifier = Modifier
                .then(if (isParticipantSharedScreen) Modifier.width(375.dp) else Modifier.fillMaxWidth())
                .wrapContentHeight(),
            sheetState = sheetState,
            containerColor = colorResource(id = com.difft.android.base.R.color.bg3_night),
            contentWindowInsets = { WindowInsets.navigationBars },
            onDismissRequest = {
                onDismiss()
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(color = colorResource(id = com.difft.android.base.R.color.bg3_night), shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 0.dp, bottomEnd = 0.dp))
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(start = 16.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Top),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .width(118.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ResUtils.getString(R.string.call_toolbar_bottom_critical_alert_text),
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                    fontFamily = FontFamily.Default,
                                    fontWeight = FontWeight(400),
                                    color = colorResource(id = com.difft.android.base.R.color.t_primary_night),
                                )
                            )

                            Image(
                                modifier = Modifier
                                    .padding(0.66667.dp)
                                    .width(16.dp)
                                    .height(16.dp)
                                    .onGloballyPositioned { coordinates ->
                                        val bounds = coordinates.boundsInWindow()
                                        tooltipAnchorBounds.value = IntRect(
                                            left = bounds.left.roundToInt(),
                                            top = bounds.top.roundToInt(),
                                            right = bounds.right.roundToInt(),
                                            bottom = bounds.bottom.roundToInt()
                                        )
                                    }
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        showTooltip.value = !showTooltip.value
                                    },
                                painter = painterResource(id = R.drawable.tabler_info_circle),
                                contentDescription = "image description",
                                contentScale = ContentScale.Fit
                            )
                        }

                        if (showTooltip.value && tooltipAnchorBounds.value != null) {
                            Popup(
                                popupPositionProvider = tooltipPositionProvider,
                                onDismissRequest = { showTooltip.value = false },
                                properties = PopupProperties(focusable = false),
                            ) {
                                TooltipBubble(
                                    text = ResUtils.getString(R.string.call_critical_alert_tooltip),
                                )
                            }
                        }

                        Text(
                            modifier = Modifier
                                .width(343.dp)
                                .wrapContentHeight(),
                            text = message.value,
                            style = TextStyle(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight(400),
                                color = colorResource(id = com.difft.android.base.R.color.t_secondary_night),
                                textAlign = TextAlign.Center,
                            )
                        )
                    }

                    Row(
                        modifier = Modifier
                            .width(343.dp)
                            .height(48.dp)
                            .background(color = colorResource(id = com.difft.android.base.R.color.primary), shape = RoundedCornerShape(size = 8.dp))
                            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
                            .clickable{
                                sendCriticalAlert(gid)
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ResUtils.getString(R.string.call_critical_alert_confirm_view_button_send),
                            style = TextStyle(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight(400),
                                color = colorResource(id = com.difft.android.base.R.color.t_primary_night),
                                textAlign = TextAlign.Center,
                            )
                        )
                    }
                }
            }
        }
    }

}

private const val INVITEE_NAME_LIMIT = 3
private val TOOLTIP_CORNER_RADIUS = 4.dp
private val TOOLTIP_TRIANGLE_WIDTH = 12.dp
private val TOOLTIP_TRIANGLE_HEIGHT = 6.dp

private suspend fun getCriticalAlertConfirmViewMessage(
    contactorCacheManager: ContactorCacheManager,
    callType: String,
    invitees: List<String>,
): String {
    val isGroupCall = callType == CallType.GROUP.type
    if (isGroupCall && invitees.isEmpty()) {
        return ResUtils.getString(R.string.call_critical_alert_confirm_view_message_group)
    }

    val templateRes = if (isGroupCall) {
        R.string.call_critical_alert_confirm_view_message_group_invitees
    } else {
        R.string.call_critical_alert_confirm_view_message_instant
    }

    val namesLimit = minOf(invitees.size, INVITEE_NAME_LIMIT)
    val firstPartNames = getInviteeNames(contactorCacheManager, invitees, namesLimit)
    val remainingCount = invitees.size - namesLimit

    val suffix = if (remainingCount > 0) " ${ResUtils.getString(R.string.call_critical_alert_confirm_view_message_invitees, remainingCount)}" else ""

    return ResUtils.getString(templateRes, firstPartNames + suffix)
}

private suspend fun getInviteeNames(
    contactorCacheManager: ContactorCacheManager,
    invitees: List<String>,
    limit: Int,
): String = withContext(Dispatchers.IO) {
    val names = mutableListOf<String>()
    for (id in invitees.take(limit)) {
        val name = contactorCacheManager.getDisplayNameById(id)
        name?.let {
            StringUtil.truncateWithEllipsis(name, 10).let { names.add(it) }
        }
    }
    names.joinToString(", ")
}

@Composable
private fun TooltipBubble(text: String) {
    val tooltipBackground = colorResource(id = com.difft.android.base.R.color.bg_tooltip)
    Column(
        modifier = Modifier
            .alpha(0.95f)
            .width(290.dp)
            .padding(start = 10.dp, top = 5.dp, end = 10.dp, bottom = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(TOOLTIP_TRIANGLE_WIDTH, TOOLTIP_TRIANGLE_HEIGHT)) {
            val path = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path = path, color = tooltipBackground)
        }
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight(400),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .alpha(0.95f)
                .width(290.dp)
                .background(
                    color = tooltipBackground,
                    shape = RoundedCornerShape(TOOLTIP_CORNER_RADIUS)
                )
                .padding(start = 10.dp, top = 5.dp, end = 10.dp, bottom = 5.dp)
        )
    }
}


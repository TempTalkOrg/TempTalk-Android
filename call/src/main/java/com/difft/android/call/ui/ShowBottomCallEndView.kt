package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.difft.android.call.BuildConfig
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.BottomButtonTextStyle
import com.difft.android.call.data.BottomCallEndAction
import com.difft.android.call.ui.HideNavigationBarEffect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowBottomCallEndView(viewModel: LCallViewModel, onDismiss: () -> Unit, onClickItem: (BottomCallEndAction) -> Unit) {
    val showBottomCallEndViewEnable by viewModel.callUiController.showBottomCallEndViewEnable.collectAsState(false)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val coroutineScope = rememberCoroutineScope()
    val isShareScreening by viewModel.callUiController.isShareScreening.collectAsState(false)

    val dismissSheet: () -> Unit = {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    LaunchedEffect(showBottomCallEndViewEnable) {
        if (showBottomCallEndViewEnable) {
            viewModel.callUiController.setShowBottomToolBarViewEnabled(false)
            sheetState.show()
        } else {
            sheetState.hide()
        }
    }


    if (showBottomCallEndViewEnable || sheetState.isVisible) {
        ModalBottomSheet (
            scrimColor = Color.Transparent,
            containerColor = Color.Transparent,
            dragHandle = null,
            shape = RoundedCornerShape(size = 12.dp),
            modifier = Modifier
                .background(color = Color.Transparent)
                .then(if (isShareScreening) Modifier.wrapContentWidth() else Modifier.fillMaxWidth())
                .wrapContentHeight(),
            sheetState = sheetState,
            onDismissRequest = {
                dismissSheet()
            },
        ){
            HideNavigationBarEffect()
            Column(
                modifier = Modifier
                    .semantics { testTagsAsResourceId = BuildConfig.DEBUG }
                    .testTag("call_end_sheet")
                    .padding(start = 8.dp, end = 8.dp, bottom = 32.dp)
                    .then(if (isShareScreening) Modifier.wrapContentWidth() else Modifier.fillMaxWidth())
            ) {
                if (isShareScreening) {
                    HorizontalScreenButtonView(onClickItem = onClickItem, onCancelClick = dismissSheet)
                } else {
                    VerticalScreenButtonView(onClickItem = onClickItem, onCancelClick = dismissSheet)
                }
            }
        }
    }
}

@Composable
fun HorizontalScreenButtonView(onClickItem: (BottomCallEndAction) -> Unit, onCancelClick: () -> Unit = { onClickItem(BottomCallEndAction.CANCEL) }) {
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .height(60.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .testTag("call_end_sheet_end_for_all")
                .padding(0.25.dp)
                .width(238.dp)
                .height(60.dp)
                .background(color = DifftTheme.colors.backgroundTertiary, shape = RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp, topStart = 12.dp, bottomStart = 12.dp))
                .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 18.dp)
                .clickable( interactionSource = remember { MutableInteractionSource() }, indication = null){
                    onClickItem(BottomCallEndAction.END_CALL)
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier
                    .height(24.dp),
                text = ResUtils.getString(R.string.call_button_group_alert_end_text),
                style = BottomButtonTextStyle.copy(color = DifftTheme.colors.textError)
            )
        }

        Row(
            modifier = Modifier
                .testTag("call_end_sheet_leave")
                .padding(0.25.dp)
                .width(238.dp)
                .height(60.dp)
                .background(color = DifftTheme.colors.backgroundTertiary, shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp, topStart = 0.dp, bottomStart = 0.dp))
                .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 18.dp)
                .clickable( interactionSource = remember { MutableInteractionSource() }, indication = null){
                    onClickItem(BottomCallEndAction.LEAVE_CALL)
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier
                    .height(24.dp),
                text = ResUtils.getString(R.string.call_button_group_leave_meeting),
                style = BottomButtonTextStyle.copy(color = DifftTheme.colors.textPrimary)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            modifier = Modifier
                .testTag("call_end_sheet_cancel")
                .padding(0.dp)
                .width(212.dp)
                .height(60.dp)
                .background(color = DifftTheme.colors.backgroundSecondary, shape = RoundedCornerShape(size = 12.dp))
                .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 18.dp)
                .clickable( interactionSource = remember { MutableInteractionSource() }, indication = null){
                    onCancelClick()
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier
                    .height(24.dp),
                text = ResUtils.getString(R.string.call_button_group_alert_cancel_text),
                style = BottomButtonTextStyle.copy(color = DifftTheme.colors.textPrimary)
            )
        }
    }


}

@Composable
fun VerticalScreenButtonView(onClickItem: (BottomCallEndAction) -> Unit, onCancelClick: () -> Unit = { onClickItem(BottomCallEndAction.CANCEL) }) {
    Column(
        modifier = Modifier
            .padding(0.dp)
            .fillMaxWidth()
            .wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Top),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(
                modifier = Modifier
                    .testTag("call_end_sheet_end_for_all")
                    .padding(0.25.dp)
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(color = DifftTheme.colors.backgroundTertiary, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomEnd = 0.dp, bottomStart = 0.dp))
                    .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 18.dp)
                    .clickable( interactionSource = remember { MutableInteractionSource() }, indication = null){
                        onClickItem(BottomCallEndAction.END_CALL)
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier
                        .width(327.dp)
                        .height(24.dp),
                    text = ResUtils.getString(R.string.call_button_group_alert_end_text),
                    style = BottomButtonTextStyle.copy(color = DifftTheme.colors.textError)
                )
            }

            Row(
                modifier = Modifier
                    .testTag("call_end_sheet_leave")
                    .padding(0.25.dp)
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(color = DifftTheme.colors.backgroundTertiary, shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomEnd = 12.dp, bottomStart = 12.dp))
                    .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 18.dp)
                    .clickable( interactionSource = remember { MutableInteractionSource() }, indication = null){
                        onClickItem(BottomCallEndAction.LEAVE_CALL)
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier
                        .width(327.dp)
                        .height(24.dp),
                    text = ResUtils.getString(R.string.call_button_group_leave_meeting),
                    style = BottomButtonTextStyle.copy(color = DifftTheme.colors.textPrimary)
                )
            }
        }

        Row(
            modifier = Modifier
                .testTag("call_end_sheet_cancel")
                .padding(0.dp)
                .fillMaxWidth()
                .height(60.dp)
                .background(color = DifftTheme.colors.backgroundSecondary, shape = RoundedCornerShape(size = 12.dp))
                .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 18.dp)
                .clickable( interactionSource = remember { MutableInteractionSource() }, indication = null){
                    onCancelClick()
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier
                    .width(327.dp)
                    .height(24.dp),
                text = ResUtils.getString(R.string.call_button_group_alert_cancel_text),
                style = BottomButtonTextStyle.copy(color = DifftTheme.colors.textPrimary)
            )
        }
    }
}


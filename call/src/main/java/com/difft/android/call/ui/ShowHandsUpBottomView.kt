package com.difft.android.call.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowHandsUpBottomView(viewModel: LCallViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )
    val showHandsUpEnabled by viewModel.callUiController.showHandsUpEnabled.collectAsState(false)
    val handsUpUserInfo by viewModel.handsUpUserInfo.collectAsState(emptyList())
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val isShareScreening by viewModel.callUiController.isShareScreening.collectAsState(false)

    if(showHandsUpEnabled && !isInPipMode && !isShareScreening) {
        ModalBottomSheet (
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            sheetState = sheetState,
            containerColor = colorResource(id = com.difft.android.base.R.color.bg3_night),
            onDismissRequest = { onDismiss() })
        {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
                horizontalAlignment = Alignment.Start,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Child views.
                    Image(
                        modifier = Modifier
                            .padding(0.66667.dp)
                            .width(16.dp)
                            .height(16.dp),
                        painter = painterResource(id = R.drawable.call_tabler_hand_stop),
                        contentDescription = "image description",
                        contentScale = ContentScale.None
                    )

                    Text(
                        text = "Raise hand (${handsUpUserInfo.size}) ",
                        // SF/H5
                        style = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight(510),
                            color = colorResource(id = com.difft.android.base.R.color.t_secondary_night),
                        )
                    )
                }

                LazyVerticalGrid(
                    modifier = Modifier.fillMaxHeight(),
                    columns = GridCells.Fixed(1),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ){
                    items(
                        count = handsUpUserInfo.size,
                        key = { index -> handsUpUserInfo[index].userId }
                    )
                    { index ->
                        ShowHandsUpBottomListView(viewModel, handsUpUserInfo[index],viewHeight = 44.dp, fontSize = 16, lineHeight = 24)
                    }
                }
            }

        }
    }


}
package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.SfProFont
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.data.BarrageMessageConfig


@Composable
fun SimpleBarrageMessage(
    modifier: Modifier,
    config: BarrageMessageConfig,
    onClickItem: (String) -> Unit
){
    Column(
        modifier = modifier
            .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
            .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
            .width(LCallUiConstants.SIMPLE_BARRAGE_UI_WIDTH.dp)
            .wrapContentHeight()
            .background(color = colorResource(id = com.difft.android.base.R.color.bg2_night), shape = RoundedCornerShape(size = 8.dp))
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
        horizontalAlignment = Alignment.Start,
    ) {
        val iterator = config.barrageTexts.listIterator()
        while (iterator.hasNext()) {
            val firstElement = iterator.next()
            if(firstElement.length <= LCallUiConstants.SIMPLE_BARRAGE_MAX_SINGLE_TEXT_LENGTH) { // 单个文本长度小于等于16，则尝试拼接下一个元素
                if (iterator.hasNext()) {
                    val secondElement = iterator.next()
                    DualTextItem(
                        firstText = firstElement,
                        secondText = secondElement,
                        onClickFirst = { onClickItem(firstElement) },
                        onClickSecond = { onClickItem(secondElement) }
                    )
                } else {
                    TextItem(
                        text = firstElement,
                        onClick = { onClickItem(firstElement) }
                    )
                }
            } else {
                TextItem(
                    text = firstElement,
                    onClick = { onClickItem(firstElement) }
                )
            }
        }
    }
}


@Composable
fun TextItem(
    text: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .alpha(0.9f)
            .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
            .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
            .height(LCallUiConstants.SIMPLE_BARRAGE_ITEM_HEIGHT.dp)
            .background(color = colorResource(id = com.difft.android.base.R.color.bg3_night), shape = RoundedCornerShape(size = 4.dp))
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.height(20.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.height(20.dp),
                text = text,
                style = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = SfProFont,
                    fontWeight = FontWeight.Normal,
                    color = colorResource(id = com.difft.android.base.R.color.t_primary_night)
                )
            )
        }
    }
}

@Composable
fun DualTextItem(
    firstText: String,
    secondText: String,
    onClickFirst: () -> Unit,
    onClickSecond: () -> Unit
) {
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .height(LCallUiConstants.SIMPLE_BARRAGE_ITEM_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        verticalAlignment = Alignment.Top
    ) {
        TextItem(text = firstText, onClick = onClickFirst)
        TextItem(text = secondText, onClick = onClickSecond)
    }
}


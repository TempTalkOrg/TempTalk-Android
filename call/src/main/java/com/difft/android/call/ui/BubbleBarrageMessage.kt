package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.BubbleMessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BubbleBarrageMessage(
    modifier: Modifier,
    config: BarrageMessageConfig,
    onClickItem: (String, BubbleMessageType) -> Unit
){
    Column(
        modifier = modifier
            .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
            .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
            .width(LCallUiConstants.SIMPLE_BARRAGE_UI_WIDTH.dp)
            .wrapContentHeight()
            .background(color = colorResource(id = com.difft.android.base.R.color.bg2_night), shape = RoundedCornerShape(size = 8.dp))
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        horizontalAlignment = Alignment.Start,
    ) {
        // Emoji 行 - 可水平滚动
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(
                items = config.emojiPresets,
                key = { emoji -> emoji }
            ) { emoji ->
                Text(
                    text = emoji,
                    modifier = Modifier
                        .clickable {
                            onClickItem(emoji, BubbleMessageType.EMOJI)
                        }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    style = TextStyle(
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Default,
                    )
                )
            }
        }

        // 分割线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(color = colorResource(id = com.difft.android.base.R.color.gray_700))
        )

        // TextPresets 行 - 按父控件宽度自动换行（Lazy 行渲染）
        TextPresetsFlowLazy(
            items = config.textPresets,
            onClick = { onClickItem(it, BubbleMessageType.TEXT) }
        )
    }
}

@Composable
private fun TextPresetsFlowLazy(
    items: List<String>,
    onClick: (String) -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var rows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var maxWidthPx by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .onSizeChanged { size -> maxWidthPx = size.width.toFloat() }
    ) {
        val horizontalSpacingPx = with(density) { 12.dp.toPx() }
        val itemHorizontalPaddingPx = with(density) { 8.dp.toPx() * 2 }

        LaunchedEffect(items, maxWidthPx) {
            if (maxWidthPx <= 0f) {
                rows = emptyList()
                return@LaunchedEffect
            }
            val itemWidths = items.map { text ->
                val widthPx = textMeasurer
                    .measure(AnnotatedString(text))
                    .size
                    .width
                    .toFloat() + itemHorizontalPaddingPx
                text to widthPx
            }

            rows = withContext(Dispatchers.Default) {
                val result = mutableListOf<MutableList<String>>()
                var currentRow = mutableListOf<String>()
                var currentWidth = 0f

                itemWidths.forEach { (text, widthPx) ->
                    val nextWidth = if (currentRow.isEmpty()) {
                        widthPx
                    } else {
                        currentWidth + horizontalSpacingPx + widthPx
                    }

                    if (nextWidth > maxWidthPx && currentRow.isNotEmpty()) {
                        result.add(currentRow)
                        currentRow = mutableListOf()
                        currentWidth = 0f
                    }

                    currentRow.add(text)
                    currentWidth = if (currentRow.size == 1) widthPx else nextWidth
                }

                if (currentRow.isNotEmpty()) {
                    result.add(currentRow)
                }

                result
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(rows.size) { index ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rows[index].forEach { text ->
                        TextItem(
                            text = text,
                            onClick = { onClick(text) }
                        )
                    }
                }
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
            .wrapContentWidth()
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
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    color = colorResource(id = com.difft.android.base.R.color.t_primary_night)
                )
            )
        }
    }
}


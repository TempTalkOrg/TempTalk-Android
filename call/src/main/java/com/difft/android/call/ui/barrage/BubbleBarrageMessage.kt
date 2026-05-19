package com.difft.android.call.ui.barrage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.BubbleMessageType

@Composable
fun BubbleBarrageMessage(
    modifier: Modifier,
    config: BarrageMessageConfig,
    widthDp: Int = LCallUiConstants.SIMPLE_BARRAGE_UI_WIDTH,
    onClickItem: (String, BubbleMessageType) -> Unit
){
    val baseEmojiSpacingDp = 8f
    val emojiCount = config.emojiPresets.size
    val extraWidthDp = (widthDp - LCallUiConstants.SIMPLE_BARRAGE_UI_WIDTH).coerceAtLeast(0)
    val emojiSpacing = if (emojiCount > 1) {
        (baseEmojiSpacingDp + extraWidthDp.toFloat() / (emojiCount - 1)).dp
    } else {
        baseEmojiSpacingDp.dp
    }

    Column(
        modifier = modifier
            .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
            .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
            .width(widthDp.dp)
            .wrapContentHeight()
            .background(color = colorResource(id = com.difft.android.base.R.color.bg2_night), shape = RoundedCornerShape(size = 8.dp))
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        horizontalAlignment = Alignment.Start,
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            horizontalArrangement = Arrangement.spacedBy(emojiSpacing),
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

        // TextPresets 行 - 按父控件宽度自动换行。
        //
        // 关键点：使用同步 [FlowRow]，菜单首次 measure 即得到最终尺寸。
        // 旧实现 (TextPresetsFlowLazy) 走 LaunchedEffect + withContext 的二阶段
        // 布局：先以"只有一行"的高度绘制，下一帧才补足真实高度，这会让
        // BarrageMessageView 的外层 Box 在同一帧内经历两次约束派发，导致
        // BubbleAnimationLayer 里飘动中的气泡 animateFloat target 被重复
        // 求值、align(BottomStart) 的 placement 产生 1px 舍入差，最终叠加
        // 成肉眼可见的气泡跳动。
        TextPresetsFlow(
            items = config.textPresets,
            onClick = { onClickItem(it, BubbleMessageType.TEXT) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextPresetsFlow(
    items: List<String>,
    onClick: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { text ->
            TextItem(
                text = text,
                onClick = { onClick(text) }
            )
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

package com.difft.android.call.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.text.BidiFormatter
import com.difft.android.call.util.StringUtil






@Composable
internal fun NameWithSuffix(
    name: String,
    suffix: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    suffixModifier: Modifier = Modifier,
) {
    val direction = if (BidiFormatter.getInstance().isRtl(suffix)) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f, fill = false).testTag(NAME_WITH_SUFFIX_NAME_TAG),
                text = StringUtil.truncateWithEllipsis(name, NAME_MEASURE_CAP),
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = suffix, modifier = suffixModifier, style = style, color = color, maxLines = 1)
        }
    }
}

internal const val NAME_WITH_SUFFIX_NAME_TAG = "call_name_with_suffix_name"

package com.difft.android.base.ui.compose.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftRadius
import com.difft.android.base.ui.theme.DifftTheme





object DifftSettingsDefaults {
    val GroupShape: Shape = DifftRadius.largeShape
    
    val separatorThickness: Dp
        @Composable
        @ReadOnlyComposable
        get() = DifftTheme.hairline
    val RowMinHeight: Dp = 52.dp
    val RowContentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

    
    val LeadingGap: Dp = 8.dp

    
    val LabelInfoGap: Dp = 4.dp

    
    val TrailingGap: Dp = 8.dp

    
    val LabelSpacing: Dp = 12.dp

    
    val ChevronSize: Dp = 16.dp
    val LabelInfoIconSize: Dp = 14.dp

    
    val CheckmarkSize: Dp = 24.dp

    
    val OptionDescriptionGap: Dp = 4.dp

    
    val LeadingDotSize: Dp = 8.dp

    
    val PageInset: Dp = 16.dp
    val GroupSpacing: Dp = 16.dp
    val FirstGroupSpacing: Dp = 8.dp

    
    val CaptionInset: Dp = 16.dp
    val TitleBottomGap: Dp = 8.dp
    val FooterTopGap: Dp = 8.dp

    
    val RowSurfaceColor: Color
        @Composable @ReadOnlyComposable
        get() = DifftTheme.colors.background

    
    val LabelTextStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = DifftTheme.typography.p2

    
    val CaptionTextStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = DifftTheme.typography.p4

    
    val TitleTextStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = DifftTheme.typography.p3

    
    val OptionDescriptionTextStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = DifftTheme.typography.bodyMedium

    
    const val ChevronTestTag = "difft_settings_chevron"

    
    const val CheckmarkTestTag = "difft_settings_checkmark"
}
































@Composable
fun DifftSettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        if (title != null || titleTrailing != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DifftSettingsDefaults.CaptionInset)
                    .padding(bottom = DifftSettingsDefaults.TitleBottomGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DifftSettingsCaption(
                    text = title.orEmpty(),
                    color = DifftTheme.colors.textSecondary,
                    style = DifftSettingsDefaults.TitleTextStyle,
                    modifier = Modifier.weight(1f),
                )
                titleTrailing?.invoke()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                
                .clip(DifftSettingsDefaults.GroupShape)
                .background(DifftTheme.colors.divider),
            verticalArrangement = Arrangement.spacedBy(DifftSettingsDefaults.separatorThickness),
            content = content,
        )
        if (footer != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DifftSettingsDefaults.CaptionInset)
                    .padding(top = DifftSettingsDefaults.FooterTopGap),
                content = footer,
            )
        }
    }
}






@Composable
fun DifftSettingsCaption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DifftTheme.colors.textTertiary,
    style: TextStyle = DifftSettingsDefaults.CaptionTextStyle,
) = Text(
    text = text,
    style = style,
    color = color,
    modifier = modifier,
)

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DifftSettingsGroupPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(
            Modifier
                .background(DifftTheme.colors.bg)
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            DifftSettingsGroup {
                DifftSettingsRow(label = "Version", value = "2.4.2")
                DifftSettingsRow(label = "Build time", value = "12:00 PM")
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
            DifftSettingsGroup(
                title = "Select server",
                footer = { DifftSettingsCaption("Explains what the group above does.") },
            ) {
                DifftSettingsRow(label = "Check for updates", onClick = {})
                DifftSettingsRow(label = "Disabled row", onClick = {}, enabled = false)
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }
}

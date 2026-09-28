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

/**
 * Figma settings-cell geometry (52dp cell, 16h/10v padding, r8 group). Single source for the kit
 * and for every page that composes rows into a group.
 */
object DifftSettingsDefaults {
    val GroupShape: Shape = DifftRadius.largeShape
    val SeparatorThickness: Dp = 1.dp
    val RowMinHeight: Dp = 52.dp
    val RowContentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

    /** leading slot -> label */
    val LeadingGap: Dp = 8.dp

    /** label -> info icon */
    val LabelInfoGap: Dp = 4.dp

    /** label/value -> chevron | switch | custom trailing */
    val TrailingGap: Dp = 8.dp

    /** label -> switch, used by [DifftSwitchRow] */
    val LabelSpacing: Dp = 12.dp

    /** Trailing chevron: the 16dp Figma icon box, shared with every XML settings row. */
    val ChevronSize: Dp = 16.dp
    val LabelInfoIconSize: Dp = 14.dp

    /** Trailing checkmark on a single-select option row: the 24dp Figma `tabler:check` box. */
    val CheckmarkSize: Dp = 24.dp

    /** label -> description line, inside an option row's label column. */
    val OptionDescriptionGap: Dp = 4.dp

    /** Status dot in a row's leading slot (server-node connection rows). */
    val LeadingDotSize: Dp = 8.dp

    // Page rhythm: groups sit at a 16dp page inset, 16dp apart, 8dp below the title bar.
    val PageInset: Dp = 16.dp
    val GroupSpacing: Dp = 16.dp
    val FirstGroupSpacing: Dp = 8.dp

    // Title/footer are inset a further 16dp so they align with the row label, not the card edge.
    val CaptionInset: Dp = 16.dp
    val TitleBottomGap: Dp = 8.dp
    val FooterTopGap: Dp = 8.dp

    /** Row fill. A group paints the divider colour, so every child must paint this over it. */
    val RowSurfaceColor: Color
        @Composable @ReadOnlyComposable
        get() = DifftTheme.colors.background

    /** Row label/value: SF/P2 (Regular 16/24, no letter spacing), as the Figma cell. */
    val LabelTextStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = DifftTheme.typography.p2

    /** Group footer: SF/P4 (12/16, no letter spacing), one step below the title. */
    val CaptionTextStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = DifftTheme.typography.p4

    /** Group title: SF/P3 (14/20), one step above the footer. */
    val TitleTextStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = DifftTheme.typography.p3

    /** Option-row description line: the explanation under a selectable option's label. */
    val OptionDescriptionTextStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = DifftTheme.typography.bodyMedium

    /** Test-only handle for the chevron; the glyph itself is decorative. */
    const val ChevronTestTag = "difft_settings_chevron"

    /** Test-only handle for the option checkmark; the glyph itself is decorative. */
    const val CheckmarkTestTag = "difft_settings_checkmark"
}

/**
 * Inset rounded settings group: an 8dp-radius card on the page colour, with an automatic 1dp
 * divider hairline between rows and optional title / footer captions.
 *
 * The hairlines are NOT dividers the caller inserts. The card paints `colors.divider` and lays its
 * children out with a 1dp gap; every row paints [DifftSettingsDefaults.RowSurfaceColor] over that,
 * so the gaps are exactly the Figma cell separator and a single-row group has none. A child that
 * forgets the fill shows a divider-coloured band — a loud failure, not a silent one.
 *
 * The card clips before it fills, so a row's ripple follows the card's corners (issue #1215).
 *
 * [title] / [titleTrailing] express a named partition with a partition-level action; pages that
 * only need explanatory text below a card use [footer] instead.
 *
 * Page usage:
 * ```
 * Box(Modifier.systemBarsPadding()) {
 *     DifftScreen(title = …, onNavigateBack = …, containerColor = DifftTheme.colors.bg) { padding ->
 *         Column(
 *             Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
 *                 .padding(horizontal = DifftSettingsDefaults.PageInset),
 *         ) {
 *             Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
 *             DifftSettingsGroup { … }
 *             Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
 *             DifftSettingsGroup(title = …, footer = { DifftSettingsCaption(…) }) { … }
 *         }
 *     }
 * }
 * ```
 */
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
                // clip before background so every row ripple follows the card's corners (#1215).
                .clip(DifftSettingsDefaults.GroupShape)
                .background(DifftTheme.colors.divider),
            verticalArrangement = Arrangement.spacedBy(DifftSettingsDefaults.SeparatorThickness),
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

/**
 * Group title / footer text. Defaults to the footer style; the title row passes
 * [DifftSettingsDefaults.TitleTextStyle]. A slot footer lets a caller pass a link-bearing
 * AnnotatedString.
 */
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

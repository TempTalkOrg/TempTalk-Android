package com.difft.android.base.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.core.graphics.drawable.toDrawable

/**
 * Main theme composable for Difft design system
 * Integrates Material Design 3 with custom Difft extensions
 *
 * This theme provides:
 * - Material3 ColorScheme, Typography, and Shapes for auto-styling MD3 components
 * - Extended colors, typography, and spacing for app-specific needs
 * - Unified accessor API via DifftTheme object
 *
 * @param darkTheme Whether to use dark theme. Defaults to system setting.
 * @param useFlatBackground If false (default), uses `colors.bg` — the settings idiom
 *   page background that the majority of screens want. If true, uses `colors.background`
 *   (= bg1, pure white in light / #181A20 in dark) for immersive content pages such as
 *   FilePreSendActivity that need a flat surface without elevated cards. Only selects which
 *   color feeds [backgroundColor] below (`colorScheme.background` vs `extendedColors.bg`) —
 *   it does not affect colorScheme, typography, or any other rendered content, and is
 *   independent of [applyWindowBackground] (the two parameters compose freely).
 * @param applyWindowBackground Whether this composition may write the computed background
 *   color onto the host Activity's `window` (affects the status/navigation bar tint on
 *   Android 15+). Defaults to `true`: a `DifftTheme` call is normally a one-time Activity-root
 *   mount and should own its window. Pass `false` when this instance does NOT own the host
 *   window — temporary overlays added directly onto an Activity's root view (popups, floating
 *   panels), independent floating windows whose Context still resolves to a host Activity, or
 *   content persistently embedded in a shared container it does not own. `false` is a pure
 *   opt-out: it does not snapshot or restore the window background, because the correct state
 *   for such content is to never have touched the window in the first place.
 * @param content The composable content to display within the theme.
 */
@Composable
fun DifftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useFlatBackground: Boolean = false,
    applyWindowBackground: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // remember(darkTheme): the factory functions are zero-parameter — darkTheme is the
    // complete key. ColorScheme has no equals/hashCode override (reference identity only);
    // without remember, every unrelated DifftTheme recomposition allocates a new instance
    // and forces every MaterialTheme.colorScheme.* consumer to recompose needlessly.
    val colorScheme = remember(darkTheme) {
        if (darkTheme) createDarkColorScheme() else createLightColorScheme()
    }
    val extendedColors = if (darkTheme) createDarkExtendedColors() else createLightExtendedColors()

    // Default = settings idiom (colors.bg). Immersive pages opt out with useFlatBackground = true.
    val backgroundColor = if (useFlatBackground) {
        colorScheme.background  // bg1 — flat surface for immersive content pages
    } else {
        extendedColors.bg       // settings idiom — page bg, cards use colors.bgElevated
    }

    // Set window background color to affect status bar on Android 15+.
    // applyWindowBackground = false: this composition does not own the host window
    // (temporary overlay / floating window / non-owning embedded subtree) — skip entirely,
    // no snapshot, no restore.
    if (applyWindowBackground) {
        SideEffect {
            val activity = context as? Activity
            activity?.window?.setBackgroundDrawable(
                backgroundColor.toArgb().toDrawable()
            )
        }
    }

    // Create Material3 Typography and Shapes
    val typography = createDifftTypography()
    val shapes = createDifftShapes()

    // Create extended typography and spacing
    val extendedTypography = DifftExtendedTypography()
    val spacing = DifftSpacing()

    // Provide all theme values via CompositionLocals
    CompositionLocalProvider(
        LocalDifftExtendedColors provides extendedColors,
        LocalDifftExtendedTypography provides extendedTypography,
        LocalDifftSpacing provides spacing
    ) {
        // Configure MaterialTheme with our color scheme, typography, and shapes
        // This enables Material3 components to auto-style themselves
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}

/**
 * Theme variant specifically for previews
 * Automatically detects dark theme from @Preview annotation's uiMode
 */
@Composable
fun DifftThemePreview(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    DifftTheme(
        darkTheme = darkTheme,
        content = content
    )
}

/**
 * Unified theme accessor object
 * Provides clean API for accessing all theme values
 *
 * Usage:
 * - Colors: DifftTheme.colors.primary
 * - Typography: DifftTheme.typography.bodyLarge
 * - Spacing: DifftTheme.spacing.insetMedium
 * - Shapes: DifftTheme.shapes.medium
 */
object DifftTheme {
    /**
     * Access unified color scheme
     * Combines Material3 ColorScheme with Difft extended colors
     */
    val colors: DifftColorAccessor
        @Composable
        @ReadOnlyComposable
        get() = DifftColorAccessor

    /**
     * Access spacing values
     */
    val spacing: DifftSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalDifftSpacing.current

    /**
     * Access unified typography
     * Combines Material3 Typography with Difft extended typography
     */
    val typography: DifftTypographyAccessor
        @Composable
        @ReadOnlyComposable
        get() = DifftTypographyAccessor

    /**
     * Access Material3 shapes
     */
    val shapes: Shapes
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes
}

/**
 * Unified color accessor that combines Material3 ColorScheme with extended colors
 * Provides single API for all color access
 */
object DifftColorAccessor {
    // ============== Material3 ColorScheme Colors ==============
    val primary: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary

    val onPrimary: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onPrimary

    val primaryContainer: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primaryContainer

    val onPrimaryContainer: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onPrimaryContainer

    val secondary: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.secondary

    val onSecondary: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSecondary

    val secondaryContainer: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.secondaryContainer

    val onSecondaryContainer: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSecondaryContainer

    val tertiary: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.tertiary

    val onTertiary: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onTertiary

    val tertiaryContainer: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.tertiaryContainer

    val onTertiaryContainer: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onTertiaryContainer

    val error: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.error

    val onError: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onError

    val errorContainer: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.errorContainer

    val onErrorContainer: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onErrorContainer

    val background: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.background

    val onBackground: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onBackground

    val surface: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surface

    val onSurface: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface

    val surfaceVariant: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceVariant

    val onSurfaceVariant: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurfaceVariant

    val outline: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.outline

    val outlineVariant: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.outlineVariant

    val scrim: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.scrim

    val inverseSurface: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.inverseSurface

    val inverseOnSurface: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.inverseOnSurface

    val inversePrimary: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.inversePrimary

    val surfaceTint: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceTint

    // ============== Extended Difft Colors ==============
    private val extended: DifftExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalDifftExtendedColors.current

    val success: Color
        @Composable @ReadOnlyComposable
        get() = extended.success

    val successDark: Color
        @Composable @ReadOnlyComposable
        get() = extended.successDark

    val warning: Color
        @Composable @ReadOnlyComposable
        get() = extended.warning

    val warningDark: Color
        @Composable @ReadOnlyComposable
        get() = extended.warningDark

    val info: Color
        @Composable @ReadOnlyComposable
        get() = extended.info

    val textPrimary: Color
        @Composable @ReadOnlyComposable
        get() = extended.textPrimary

    val textSecondary: Color
        @Composable @ReadOnlyComposable
        get() = extended.textSecondary

    val textTertiary: Color
        @Composable @ReadOnlyComposable
        get() = extended.textTertiary

    val textDisabled: Color
        @Composable @ReadOnlyComposable
        get() = extended.textDisabled

    val textOnPrimary: Color
        @Composable @ReadOnlyComposable
        get() = extended.textOnPrimary

    val textError: Color
        @Composable @ReadOnlyComposable
        get() = extended.textError

    val textWarning: Color
        @Composable @ReadOnlyComposable
        get() = extended.textWarning

    val textSuccess: Color
        @Composable @ReadOnlyComposable
        get() = extended.textSuccess

    val textInfo: Color
        @Composable @ReadOnlyComposable
        get() = extended.textInfo

    /**
     * Default page background (settings idiom).
     * Matches XML `@color/bg`. Use this for the page-level background on almost all screens —
     * `DifftTheme {}` already sets this as the window background by default.
     *
     * In light mode this is #FAFAFA (= `bg2` value); in dark mode #181A20 (= `bg1` value).
     * Pair with `colors.bgElevated` for cards/list items that visually sit on top of the page.
     *
     * NOT to be confused with `colors.background` (Material3 raw `colorScheme.background`,
     * which equals `bg1` — pure white in light / #181A20 in dark; used for immersive pages
     * via `DifftTheme(useFlatBackground = true)`).
     */
    val bg: Color
        @Composable @ReadOnlyComposable
        get() = extended.bg

    val backgroundSecondary: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundSecondary

    val backgroundTertiary: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundTertiary

    val backgroundQuaternary: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundQuaternary

    val backgroundActionPopup: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundActionPopup

    val backgroundBottomSheet: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundBottomSheet

    val backgroundDisabled: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundDisabled

    val backgroundModal: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundModal

    val backgroundPopup: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundPopup

    val backgroundTooltip: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundTooltip

    val backgroundBlue: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundBlue

    val backgroundElevate: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundElevate

    /**
     * Elevated/card background, sits one tier above `colors.bg`.
     * Matches XML `@color/bg.elevated`. Use for cards / list items / sections that
     * visually float on the page background.
     *
     * In light mode this is #FFFFFF (= `bg1` value, the page is the lighter gray `bg`);
     * in dark mode #1E2329 (= `bg2` value, the page is the darker `bg`). The relationship
     * between `bg` and `bgElevated` is **inverted** between light and dark — that's why
     * semantic tokens (`bg` / `bgElevated`) are separate from raw tier tokens (`bg1` / `bg2`).
     */
    val bgElevated: Color
        @Composable @ReadOnlyComposable
        get() = extended.bgElevated

    val divider: Color
        @Composable @ReadOnlyComposable
        get() = extended.divider

    val line: Color
        @Composable @ReadOnlyComposable
        get() = extended.line

    val border: Color
        @Composable @ReadOnlyComposable
        get() = extended.border

    val icon: Color
        @Composable @ReadOnlyComposable
        get() = extended.icon

    val iconOnPrimary: Color
        @Composable @ReadOnlyComposable
        get() = extended.iconOnPrimary

    val ripple: Color
        @Composable @ReadOnlyComposable
        get() = extended.ripple

    val overlay: Color
        @Composable @ReadOnlyComposable
        get() = extended.overlay

    val buttonPrimary: Color
        @Composable @ReadOnlyComposable
        get() = extended.buttonPrimary

    val buttonSecondary: Color
        @Composable @ReadOnlyComposable
        get() = extended.buttonSecondary

    val buttonDisabled: Color
        @Composable @ReadOnlyComposable
        get() = extended.buttonDisabled

    val statusBarColor: Color
        @Composable @ReadOnlyComposable
        get() = extended.statusBarColor

    val navigationBarColor: Color
        @Composable @ReadOnlyComposable
        get() = extended.navigationBarColor

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = extended.isDark

    // ============== Legacy Compatibility Aliases ==============
    val primaryVariant: Color
        @Composable @ReadOnlyComposable
        get() = primaryContainer

    val secondaryVariant: Color
        @Composable @ReadOnlyComposable
        get() = secondaryContainer
}

/**
 * Unified typography accessor that combines Material3 Typography with extended typography
 * Provides single API for all text style access
 */
object DifftTypographyAccessor {
    // ============== Material3 Typography Styles ==============
    val displayLarge: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.displayLarge

    val displayMedium: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.displayMedium

    val displaySmall: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.displaySmall

    val headlineLarge: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineLarge

    val headlineMedium: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineMedium

    val headlineSmall: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineSmall

    val titleLarge: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge

    val titleMedium: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleMedium

    val titleSmall: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleSmall

    val bodyLarge: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyLarge

    val bodyMedium: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyMedium

    val bodySmall: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodySmall

    val labelLarge: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.labelLarge

    val labelMedium: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.labelMedium

    val labelSmall: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.labelSmall

    // ============== Extended Difft Typography ==============
    private val extended: DifftExtendedTypography
        @Composable @ReadOnlyComposable
        get() = LocalDifftExtendedTypography.current

    val profileName: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.profileName

    val profileSubtitle: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.profileSubtitle

    val sectionTitle: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.sectionTitle

    val infoLabel: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.infoLabel

    val infoValue: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.infoValue

    val avatarInitials: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.avatarInitials

    val topBarTitle: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.topBarTitle

    val statusText: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.statusText

    val button: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.button

    val buttonSmall: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.buttonSmall

    val inputLabel: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.inputLabel

    val inputBody: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.inputBody

    val caption: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.caption

    val overline: TextStyle
        @Composable @ReadOnlyComposable
        get() = extended.overline

    // ============== Legacy Compatibility Aliases ==============
    val body: TextStyle
        @Composable @ReadOnlyComposable
        get() = bodyMedium
}
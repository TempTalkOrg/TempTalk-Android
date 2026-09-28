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
 *   page background (#F5F5F5 light / #0B0E11 dark) that the majority of screens want.
 *   If true, uses `colors.background` (= bg1, #FFFFFF light / #181A20 dark) for content
 *   pages such as FilePreSendActivity whose rows sit full-width on the page instead of on
 *   cards. Only selects which color feeds [backgroundColor] below (`colorScheme.background`
 *   vs `extendedColors.bg`) — it does not affect colorScheme, typography, or any other
 *   rendered content, and is independent of [applyWindowBackground] (the two parameters
 *   compose freely).
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

    // Default = settings idiom (colors.bg). Content pages opt out with useFlatBackground = true.
    val backgroundColor = if (useFlatBackground) {
        colorScheme.background  // bg1 — content pages (rows sit full-width on the page)
    } else {
        extendedColors.bg       // settings idiom — page bg, cards use colors.background (bg1)
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

    // Create the design type scale and spacing
    val typeScale = DifftTypeScale()
    val spacing = DifftSpacing()

    // Provide all theme values via CompositionLocals
    CompositionLocalProvider(
        LocalDifftExtendedColors provides extendedColors,
        LocalDifftTypeScale provides typeScale,
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
 * - Radius: DifftTheme.radius.largeShape
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
     * Combines the Figma type scale (h3…p4, footnote) with the Material3 Typography slots
     */
    val typography: DifftTypographyAccessor
        @Composable
        @ReadOnlyComposable
        get() = DifftTypographyAccessor

    /**
     * Access the design-library corner-radius tiers
     */
    val radius: DifftRadius
        get() = DifftRadius

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
    // Only the roles the app actually reads are exposed. Everything else is either an
    // extended token below or reachable via MaterialTheme.colorScheme for MD3 components.
    val primary: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary

    val error: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.error

    /**
     * Raw `colorScheme.background` (= bg1). The settings-card fill, and the page surface of
     * content pages opened with `DifftTheme(useFlatBackground = true)`; a settings page
     * itself wants [bg] instead.
     */
    val background: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.background

    val scrim: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.scrim

    // ============== Extended Difft Colors ==============
    private val extended: DifftExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalDifftExtendedColors.current

    val success: Color
        @Composable @ReadOnlyComposable
        get() = extended.success

    val warning: Color
        @Composable @ReadOnlyComposable
        get() = extended.warning

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

    /** Orange caution text: a pending action (not an error), e.g. "Enhance Encryption" on a plain group. */
    val textCaution: Color
        @Composable @ReadOnlyComposable
        get() = extended.textCaution

    /**
     * Default page background (settings idiom).
     * Matches XML `@color/bg`. Use this for the page-level background on almost all screens —
     * `DifftTheme {}` already sets this as the window background by default.
     *
     * In light mode this is #F5F5F5; in dark mode #0B0E11 (both = `bgpage-secondary` value).
     * Pair with `colors.background` (bg1) for the cards/rows that sit on top of the page.
     *
     * NOT to be confused with `colors.background` (Material3 raw `colorScheme.background`,
     * which equals `bg1` — #FFFFFF in light / #181A20 in dark; it is both the settings-card
     * fill and the page background of content pages opened with
     * `DifftTheme(useFlatBackground = true)`).
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

    val backgroundDisabled: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundDisabled

    /**
     * Library `bgpopup`. Overlays only — popups, bottom sheets, floating panels. Settings
     * cards and list items use `colors.background` (bg1) instead.
     *
     * In light mode this is #FFFFFF (= `bg1` value); in dark mode #1E2329 (= `bg2` value),
     * one tier lighter than the `bg` page (#0B0E11) so an overlay reads as floating. The
     * tier it maps to differs between light and dark — that's why semantic tokens are
     * separate from raw tier tokens (`bg1` / `bg2`).
     */
    val backgroundPopup: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundPopup

    /** Library `bgmodal`: dialogs, dropdown menus, notifications. */
    val backgroundModal: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundModal

    val backgroundTooltip: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundTooltip

    val backgroundBlue: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundBlue

    /** Library `bgspace`: the workspace / brand surface. */
    val backgroundSpace: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundSpace

    /** Library `bgpage-primary`: #FFFFFF light / #0B0E11 dark. */
    val backgroundPagePrimary: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundPagePrimary

    /** Library `bgpage-secondary`: #F5F5F5 light / #0B0E11 dark. */
    val backgroundPageSecondary: Color
        @Composable @ReadOnlyComposable
        get() = extended.backgroundPageSecondary

    /**
     * Matches XML `@color/bg5` — the call surface, which is forced dark regardless of the
     * system theme, so only the dark value (#181A20) is ever rendered.
     */
    val bg5: Color
        @Composable @ReadOnlyComposable
        get() = extended.bg5

    val line: Color
        @Composable @ReadOnlyComposable
        get() = extended.line

    /** Library `divider`: list/row separators. Distinct from [line], which is a border colour. */
    val divider: Color
        @Composable @ReadOnlyComposable
        get() = extended.divider

    val icon: Color
        @Composable @ReadOnlyComposable
        get() = extended.icon

    val iconOnPrimary: Color
        @Composable @ReadOnlyComposable
        get() = extended.iconOnPrimary

    val ripple: Color
        @Composable @ReadOnlyComposable
        get() = extended.ripple

    val statusBarColor: Color
        @Composable @ReadOnlyComposable
        get() = extended.statusBarColor

    val navigationBarColor: Color
        @Composable @ReadOnlyComposable
        get() = extended.navigationBarColor

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = extended.isDark
}

/**
 * Unified typography accessor: the Figma type scale ([DifftTypeScale]) plus the Material3 slots
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

    // ============== Figma type scale (SF/H*, SF/P*) — see DifftTypeScale ==============
    private val scale: DifftTypeScale
        @Composable @ReadOnlyComposable
        get() = LocalDifftTypeScale.current

    val h1: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.h1

    val h2: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.h2

    val h3: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.h3

    val h4: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.h4

    val h5: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.h5

    val h6: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.h6

    val h7: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.h7

    val p0: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.p0

    val p1: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.p1

    val p2: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.p2

    val p3: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.p3

    val p4: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.p4

    val p5: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.p5

    val footnote: TextStyle
        @Composable @ReadOnlyComposable
        get() = scale.footnote

    // ============== Legacy Compatibility Aliases ==============
    val body: TextStyle
        @Composable @ReadOnlyComposable
        get() = bodyMedium
}
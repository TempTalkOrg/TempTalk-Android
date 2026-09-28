package com.difft.android.base.ui.theme.tokens

import androidx.compose.ui.graphics.Color

/**
 * Semantic design tokens - single source of truth for every themed color.
 *
 * Values come from the "Wea Design system" library (TT UI color sheet); the trailing comment
 * on a line is the library's own token name. Wherever a value is a step of the library
 * palette it references [Palette] instead of repeating the hex.
 *
 * Semantic roles are mapped in DifftColorScheme and read via `DifftTheme.colors`.
 */
object ColorTokens {
    /**
     * Window dim for surfaces whose scrim is drawn by the platform instead of by composition
     * (Compose dialogs, View bottom sheets): the alpha of [Light.Scrim] / [Dark.Scrim].
     */
    const val ScrimDimAmount = 0.5f

    // ============== Brand Colors ==============
    // Not palette steps: the library defines `primary` as its own value (palette Blue400 is #328AFD).
    val Primary = Color(0xFF056FFA)
    val PrimaryVariant = Color(0xFF2558C1)

    // ============== Functional Colors ==============
    // The *Dark variants are the library's on-light text colours (tsuccess / twarning / …).
    val Success = Palette.Green500                        // success
    val SuccessDark = Palette.Green700                    // tsuccess (light)
    val Warning = Palette.Yellow300                       // warning
    val WarningDark = Palette.Yellow600                   // twarning (light)
    // Caution (orange): pending-action state, e.g. an un-encrypted group's "Enhance Encryption" entry.
    val Caution = Palette.Orange400                       // caution
    val CautionDark = Palette.Orange600                   // tcaution (light)
    val Error = Palette.Red400                            // error
    val ErrorDark = Palette.Red500                        // terror (light)
    val Info = Primary                                    // tinfo (light)
    val InfoLight = Palette.Blue200                       // tinfo (dark)

    // ============== Light Theme Colors ==============
    object Light {
        // Background hierarchy
        val Background = Palette.Gray0                    // bg1
        val Bg = Palette.Gray10                           // bg — default page background (= bgpage-secondary)
        val BackgroundSecondary = Palette.Gray5           // bg2
        val BackgroundPopup = Palette.Gray0               // bgpopup
        val BackgroundModal = Palette.Gray0               // bgmodal — dialogs, menus, notifications
        val BackgroundTertiary = Palette.Gray10           // bg3
        val BackgroundQuaternary = Palette.Gray50         // bg4
        val BackgroundDisabled = Palette.Gray50           // bgdisable
        val BackgroundBlue = Palette.Blue10               // bgsky
        val BackgroundSpace = Palette.Blue900             // bgspace
        val BackgroundPagePrimary = Palette.Gray0         // bgpage-primary
        val BackgroundPageSecondary = Palette.Gray10      // bgpage-secondary
        // bg5 is a permanently dark call surface; the light value is only reachable under forced dark.
        val Bg5 = Palette.Gray5

        // Text hierarchy
        val TextPrimary = Palette.Gray800
        val TextSecondary = Palette.Gray600
        val TextTertiary = Palette.Gray300
        val TextDisabled = Palette.Gray200
        val TextOnPrimary = Palette.Gray0

        // UI elements
        val Line = Palette.Gray50
        val Divider = Palette.Gray50
        val Icon = Palette.Gray600
        val IconOnPrimary = Palette.Gray0

        // Special effects (alpha composites, not palette steps)
        val Ripple = Color(0x1F056FFA)
        // Library modal scrim: black 50 % on both themes (dialogs, bottom sheets).
        val Scrim = Color(0x80000000)

        // Component specific
        // Library `bgtooltip` is Gray600; TempTalk keeps Gray500 (resolved toward the Elevation page).
        val Tooltip = Palette.Gray500
    }

    // ============== Dark Theme Colors ==============
    object Dark {
        // Background hierarchy
        val Background = Palette.Gray900                  // bg1
        val Bg = Palette.Gray1000                         // bg — default page background (= bgpage-secondary)
        val BackgroundSecondary = Palette.Gray800         // bg2
        val BackgroundPopup = Palette.Gray800             // bgpopup
        val BackgroundModal = Palette.Gray700             // bgmodal — dialogs, menus, notifications
        val BackgroundTertiary = Palette.Gray700          // bg3
        val BackgroundQuaternary = Palette.Gray600        // bg4
        val BackgroundDisabled = Palette.Gray600          // bgdisable
        val BackgroundBlue = Palette.LightBlue800         // bgsky
        // bgspace: the TT sheet overrides the CC value (#000000) with a literal, off-palette fill.
        val BackgroundSpace = Color(0xFF333941)
        val BackgroundPagePrimary = Palette.Gray1000      // bgpage-primary
        val BackgroundPageSecondary = Palette.Gray1000    // bgpage-secondary
        val Bg5 = Palette.Gray900                         // bg5 — permanently dark call surface

        // Text hierarchy
        val TextPrimary = Palette.Gray50
        val TextSecondary = Palette.Gray200
        val TextTertiary = Palette.Gray300
        val TextDisabled = Palette.Gray500
        val TextOnPrimary = Palette.Gray0

        // UI elements
        val Line = Palette.Gray600
        val Divider = Palette.Gray700
        val Icon = Palette.Gray200
        val IconOnPrimary = Palette.Gray0

        // Special effects (alpha composites, not palette steps)
        val Ripple = Color(0x1F82C1FC)
        // Library modal scrim: black 50 % on both themes (dialogs, bottom sheets).
        val Scrim = Color(0x80000000)

        // Component specific
        // Library `bgtooltip` is Gray600; TempTalk keeps Gray500 (resolved toward the Elevation page).
        val Tooltip = Palette.Gray500
    }
}

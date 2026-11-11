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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.core.graphics.drawable.toDrawable












@Composable
fun DifftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useFlatBackground: Boolean = false,
    applyWindowBackground: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    
    val colorScheme = remember(darkTheme) {
        if (darkTheme) createDarkColorScheme() else createLightColorScheme()
    }
    val extendedColors = if (darkTheme) createDarkExtendedColors() else createLightExtendedColors()

    
    val backgroundColor = if (useFlatBackground) {
        colorScheme.background  
    } else {
        extendedColors.bg       
    }

    if (applyWindowBackground) {
        SideEffect {
            val activity = context as? Activity
            activity?.window?.setBackgroundDrawable(
                backgroundColor.toArgb().toDrawable()
            )
        }
    }

    val typography = createDifftTypography()
    val shapes = createDifftShapes()

    val typeScale = DifftTypeScale()
    val spacing = DifftSpacing()

    CompositionLocalProvider(
        LocalDifftExtendedColors provides extendedColors,
        LocalDifftTypeScale provides typeScale,
        LocalDifftSpacing provides spacing
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}





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


object DifftTheme {
    val contacts: DifftContactsTokens
        get() = DifftContactsTokens

    
    val colors: DifftColorAccessor
        @Composable
        @ReadOnlyComposable
        get() = DifftColorAccessor

    val spacing: DifftSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalDifftSpacing.current

    



    val hairline: Dp
        @Composable
        @ReadOnlyComposable
        get() = with(LocalDensity.current) { 1f.toDp() }

    
    val typography: DifftTypographyAccessor
        @Composable
        @ReadOnlyComposable
        get() = DifftTypographyAccessor

    val radius: DifftRadius
        get() = DifftRadius

    val shapes: Shapes
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes
}





object DifftTypographyAccessor {
    
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

    
    val body: TextStyle
        @Composable @ReadOnlyComposable
        get() = bodyMedium
}

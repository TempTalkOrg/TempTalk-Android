package com.difft.android.base.ui.theme.tokens

import androidx.compose.ui.graphics.Color

/**
 * Primitive colour palette of the "Wea Design system" library (Illustration color page).
 *
 * These are raw steps, not semantic roles. UI code reaches for `DifftTheme.colors` first; a
 * [Palette] step is read directly only for a colour that has no semantic role (call-page
 * accents, gradients), which the `DifftColorTokenRequired` lint rule accepts.
 * Semantic tokens live in [ColorTokens] and are exposed through `DifftTheme.colors`.
 *
 * Step names match the library ("Gray 50" -> [Gray50], "Light Blue 800" -> [LightBlue800]).
 */
object Palette {
    // ============== Brand logo ==============
    val BrandLogoBlue = Color(0xFF328AFD)
    val BrandLogoTeal = Color(0xFF00BDB6)

    // ============== Gray ==============
    val Gray0 = Color(0xFFFFFFFF)
    val Gray5 = Color(0xFFFAFAFA)
    val Gray10 = Color(0xFFF5F5F5)
    val Gray50 = Color(0xFFEAECEF)
    val Gray100 = Color(0xFFD8DCE1)
    val Gray200 = Color(0xFFB7BDC6)
    val Gray250 = Color(0xFF929AA5)
    val Gray300 = Color(0xFF848E9C)
    val Gray400 = Color(0xFF707A8A)
    val Gray500 = Color(0xFF5E6673)
    val Gray600 = Color(0xFF474D57)
    val Gray700 = Color(0xFF2B3139)
    val Gray800 = Color(0xFF1E2329)
    val Gray900 = Color(0xFF181A20)
    val Gray1000 = Color(0xFF0B0E11)

    // ============== Blue ==============
    val Blue10 = Color(0xFFEBF7FF)
    val Blue50 = Color(0xFFD4EBFC)
    val Blue100 = Color(0xFFB1DCFD)
    val Blue200 = Color(0xFF82C1FC)
    val Blue300 = Color(0xFF4DA0FF)
    val Blue400 = Color(0xFF328AFD)
    val Blue500 = Color(0xFF0C6CE9)
    val Blue600 = Color(0xFF005AE0)
    val Blue700 = Color(0xFF0046B7)
    val Blue800 = Color(0xFF012C70)
    val Blue900 = Color(0xFF001C4E)

    // ============== Light Blue ==============
    val LightBlue10 = Color(0xFFEBFAFF)
    val LightBlue50 = Color(0xFFCCF3FF)
    val LightBlue100 = Color(0xFF9BE6FF)
    val LightBlue200 = Color(0xFF64D3FF)
    val LightBlue300 = Color(0xFF33BCFF)
    val LightBlue400 = Color(0xFF22ACFB)
    val LightBlue500 = Color(0xFF008DE8)
    val LightBlue600 = Color(0xFF016DC0)
    val LightBlue700 = Color(0xFF014C8F)
    val LightBlue800 = Color(0xFF003366)
    val LightBlue900 = Color(0xFF001C4E)

    // ============== Teal ==============
    val Teal10 = Color(0xFFEBFFFD)
    val Teal50 = Color(0xFFC0FCF7)
    val Teal100 = Color(0xFF81F5EB)
    val Teal200 = Color(0xFF4DE7DC)
    val Teal300 = Color(0xFF21D3C9)
    val Teal400 = Color(0xFF00BDB6)
    val Teal500 = Color(0xFF099996)
    val Teal600 = Color(0xFF127878)
    val Teal700 = Color(0xFF155556)
    val Teal800 = Color(0xFF174446)
    val Teal900 = Color(0xFF173536)

    // ============== Green ==============
    val Green10 = Color(0xFFF2FFF4)
    val Green50 = Color(0xFFDEFFE7)
    val Green100 = Color(0xFFB3F9C8)
    val Green200 = Color(0xFF78F1A3)
    val Green300 = Color(0xFF45E386)
    val Green400 = Color(0xFF1FD275)
    val Green500 = Color(0xFF01BC6A)
    val Green600 = Color(0xFF009659)
    val Green700 = Color(0xFF00764B)
    val Green800 = Color(0xFF00563A)
    val Green900 = Color(0xFF003C2C)

    // ============== Yellow ==============
    val Yellow10 = Color(0xFFFFFCE5)
    val Yellow50 = Color(0xFFFFF8C2)
    val Yellow100 = Color(0xFFFFEF87)
    val Yellow200 = Color(0xFFFFE24D)
    val Yellow300 = Color(0xFFFFC814)
    val Yellow400 = Color(0xFFF6B602)
    val Yellow500 = Color(0xFFDA9900)
    val Yellow600 = Color(0xFFB06D00)
    val Yellow700 = Color(0xFF8A5000)
    val Yellow800 = Color(0xFF663B00)
    val Yellow900 = Color(0xFF3E2400)

    // ============== Orange ==============
    val Orange10 = Color(0xFFFFF8EE)
    val Orange50 = Color(0xFFFFEBCC)
    val Orange100 = Color(0xFFFFD587)
    val Orange200 = Color(0xFFFFBE53)
    val Orange300 = Color(0xFFFFA425)
    val Orange400 = Color(0xFFFF8800)
    val Orange500 = Color(0xFFE16F00)
    val Orange600 = Color(0xFFAE5004)
    val Orange700 = Color(0xFF843908)
    val Orange800 = Color(0xFF612E10)
    val Orange900 = Color(0xFF411902)

    // ============== Red ==============
    val Red10 = Color(0xFFFFF5F4)
    val Red50 = Color(0xFFFFE3E0)
    val Red100 = Color(0xFFFFC1B8)
    val Red200 = Color(0xFFFF897C)
    val Red300 = Color(0xFFFE6756)
    val Red400 = Color(0xFFF84135)
    val Red500 = Color(0xFFD9271E)
    val Red600 = Color(0xFFA71914)
    val Red700 = Color(0xFF8B110F)
    val Red800 = Color(0xFF680909)
    val Red900 = Color(0xFF4A0101)

    // ============== Pink (starts at step 5, not 10) ==============
    val Pink5 = Color(0xFFFFF2F9)
    val Pink50 = Color(0xFFFFDCEC)
    val Pink100 = Color(0xFFFFADC6)
    val Pink200 = Color(0xFFFF85A7)
    val Pink300 = Color(0xFFFF628A)
    val Pink400 = Color(0xFFFF4274)
    val Pink500 = Color(0xFFEB2064)
    val Pink600 = Color(0xFFCE0857)
    val Pink700 = Color(0xFF99043B)
    val Pink800 = Color(0xFF700831)
    val Pink900 = Color(0xFF4D001F)

    // ============== Purple ==============
    val Purple10 = Color(0xFFF8F2FF)
    val Purple50 = Color(0xFFECDBFF)
    val Purple100 = Color(0xFFD3A8FF)
    val Purple200 = Color(0xFFC189FF)
    val Purple300 = Color(0xFFAA63FA)
    val Purple400 = Color(0xFF8E3DEB)
    val Purple500 = Color(0xFF6F24C7)
    val Purple600 = Color(0xFF58209E)
    val Purple700 = Color(0xFF461585)
    val Purple800 = Color(0xFF330B67)
    val Purple900 = Color(0xFF1F0048)

    // ============== Purple Blue ==============
    val PurpleBlue10 = Color(0xFFF7F2FF)
    val PurpleBlue50 = Color(0xFFE8DCFF)
    val PurpleBlue100 = Color(0xFFC6ADFF)
    val PurpleBlue200 = Color(0xFFAE91FF)
    val PurpleBlue300 = Color(0xFF8E6FFF)
    val PurpleBlue400 = Color(0xFF664EF9)
    val PurpleBlue500 = Color(0xFF4637D4)
    val PurpleBlue600 = Color(0xFF3B2DA7)
    val PurpleBlue700 = Color(0xFF2B218D)
    val PurpleBlue800 = Color(0xFF1C166E)
    val PurpleBlue900 = Color(0xFF08084E)
}

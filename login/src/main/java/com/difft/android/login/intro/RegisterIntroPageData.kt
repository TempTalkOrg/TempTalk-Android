package com.difft.android.login.intro

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.difft.android.login.R

internal data class RegisterIntroPageData(
    val illustration: @Composable (Modifier) -> Unit,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

internal fun registerIntroPages(): List<RegisterIntroPageData> = listOf(
    RegisterIntroPageData(
        illustration = { modifier -> Ill1Messages(modifier) },
        titleRes = R.string.register_intro_page1_title,
        bodyRes = R.string.register_intro_page1_body,
    ),
    RegisterIntroPageData(
        illustration = { modifier -> Ill2Lock(modifier) },
        titleRes = R.string.register_intro_page2_title,
        bodyRes = R.string.register_intro_page2_body,
    ),
)

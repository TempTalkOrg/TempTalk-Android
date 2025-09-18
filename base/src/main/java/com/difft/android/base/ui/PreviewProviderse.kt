package com.difft.android.base.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

class BooleanValuesProvider : PreviewParameterProvider<Boolean> {
    override val values: Sequence<Boolean> = sequenceOf(
        false, true
    )
}
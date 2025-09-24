package com.difft.android.chat.compose

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

internal class StateSelectMessageModelDataProvider :
    PreviewParameterProvider<SelectMessageState> {
    override val values: Sequence<SelectMessageState> = sequenceOf(
        SelectMessageState(
            editModel = true,
            selectedMessageIds = setOf("1", "2", "3"),
            totalMessageCount = 10
        ),
        SelectMessageState(
            editModel = false,
            selectedMessageIds = setOf("1"),
            totalMessageCount = 3
        ),
        SelectMessageState(
            editModel = false,
            selectedMessageIds = setOf(),
            totalMessageCount = 3
        )
    )
}

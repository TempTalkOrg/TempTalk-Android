package com.difft.android.chat.compose

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

internal class StateSelectMessageModelDataProvider :
    PreviewParameterProvider<SelectMessageState> {
    override val values: Sequence<SelectMessageState> = sequenceOf(
        // Preview with some recallable messages
        SelectMessageState(
            editModel = true,
            selectedMessageIds = setOf("1", "2", "3"),
            totalMessageCount = 10,
            recallableMessageIds = setOf("1", "2")  // 2 out of 3 selected can be recalled
        ),
        // Preview with no recallable messages (gray recall button)
        SelectMessageState(
            editModel = true,
            selectedMessageIds = setOf("1"),
            totalMessageCount = 3,
            recallableMessageIds = emptySet()  // None can be recalled
        ),
        // Preview with all selected messages recallable
        SelectMessageState(
            editModel = true,
            selectedMessageIds = setOf("1", "2", "3"),
            totalMessageCount = 5,
            recallableMessageIds = setOf("1", "2", "3")  // All can be recalled
        )
    )
}

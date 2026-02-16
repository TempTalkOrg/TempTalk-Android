package com.difft.android.setting

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R

@Composable
fun TestScreen(
    onNavigateBack: () -> Unit,
    onCreateGroups: (memberIds: String, count: Int) -> Unit,
    onDisbandGroups: () -> Unit,
    onSendMessageToAllGroups: () -> Unit,
    onSendMessageToSingleGroup: (count: Int) -> Unit,
    onCorruptDatabase: () -> Unit,
    onBackupDatabase: () -> Unit,
    onSendRecoveryEvent: () -> Unit,
    onDialogTest: () -> Unit
) {
    var memberIds by remember { mutableStateOf("") }
    var groupCount by remember { mutableStateOf("") }
    var singleGroupMessageCount by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding()
    ) {
        TitleBar(titleText = "Test", onBackClick = onNavigateBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DifftTheme.spacing.insetLarge)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DifftTheme.spacing.stackMedium)
        ) {
            // Section 1: Message Test
            TestSection(title = "Message Test") {
                // Member IDs Input
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DifftTheme.spacing.insetLarge)
                        .padding(vertical = DifftTheme.spacing.insetSmall)
                ) {
                    TestInputField(
                        value = memberIds,
                        onValueChange = { memberIds = it },
                        hint = "Enter member IDs starting with +, separated by commas",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(color = DifftTheme.colors.backgroundSecondary)

                // Create Groups
                TestRowItem(
                    title = "Create Test Groups",
                    onClick = {
                        val count = groupCount.toIntOrNull()?.coerceIn(1, 200) ?: 50
                        onCreateGroups(memberIds, count)
                    },
                    trailingContent = {
                        NumberInputField(
                            value = groupCount,
                            onValueChange = { groupCount = it },
                            hint = "50"
                        )
                    }
                )

                HorizontalDivider(color = DifftTheme.colors.backgroundSecondary)

                // Send Message to Single Group
                TestRowItem(
                    title = "Send Message to Single Group",
                    onClick = {
                        val count = singleGroupMessageCount.toIntOrNull()?.coerceIn(1, 200) ?: 50
                        onSendMessageToSingleGroup(count)
                    },
                    trailingContent = {
                        NumberInputField(
                            value = singleGroupMessageCount,
                            onValueChange = { singleGroupMessageCount = it },
                            hint = "50"
                        )
                    }
                )

                HorizontalDivider(color = DifftTheme.colors.backgroundSecondary)

                // Send Message to All Groups
                TestRowItem(
                    title = "Send Message to All Groups",
                    onClick = onSendMessageToAllGroups
                )

                HorizontalDivider(color = DifftTheme.colors.backgroundSecondary)

                // Disband Groups
                TestRowItem(
                    title = "Disband or Leave All Groups",
                    onClick = onDisbandGroups
                )
            }

            // Section 2: Database Recovery Test
            TestSection(title = "Database Recovery Test") {
                TestRowItem(
                    title = "Corrupt Database File",
                    onClick = onCorruptDatabase
                )

                HorizontalDivider(color = DifftTheme.colors.backgroundSecondary)

                TestRowItem(
                    title = "Manual Backup Database",
                    onClick = onBackupDatabase
                )

                HorizontalDivider(color = DifftTheme.colors.backgroundSecondary)

                TestRowItem(
                    title = "Send Recovery Event",
                    onClick = onSendRecoveryEvent
                )
            }

            // Section 3: Dialog Test
            TestSection(title = "Dialog Test") {
                TestRowItem(
                    title = "Dialog Test",
                    subtitle = "Unified API Test",
                    onClick = onDialogTest
                )
            }
        }
    }
}

@Composable
private fun TestSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = DifftTheme.typography.labelMedium,
            color = DifftTheme.colors.textSecondary,
            modifier = Modifier.padding(
                start = DifftTheme.spacing.insetLarge,
                bottom = DifftTheme.spacing.insetSmall
            )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DifftTheme.colors.backgroundSettingItem)
        ) {
            content()
        }
    }
}

@Composable
private fun TestRowItem(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = DifftTheme.spacing.insetLarge),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = DifftTheme.typography.bodyLarge,
            color = DifftTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.weight(1f))

        if (subtitle != null) {
            Text(
                text = subtitle,
                style = DifftTheme.typography.bodyMedium,
                color = DifftTheme.colors.textTertiary,
                modifier = Modifier.padding(end = DifftTheme.spacing.insetSmall)
            )
        }

        trailingContent?.invoke()

        Spacer(modifier = Modifier.width(DifftTheme.spacing.insetSmall))

        Icon(
            painter = painterResource(id = R.drawable.chat_ic_arrow_right),
            contentDescription = null,
            tint = DifftTheme.colors.textPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun NumberInputField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    val cursorColor = DifftTheme.colors.primary
    Box(
        modifier = modifier
            .width(60.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(DifftTheme.colors.background)
            .padding(horizontal = DifftTheme.spacing.insetSmall),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                if (newValue.all { it.isDigit() }) {
                    val numValue = newValue.toIntOrNull()
                    when {
                        newValue.isEmpty() -> onValueChange("")
                        numValue != null && numValue > 200 -> onValueChange("200")
                        else -> onValueChange(newValue)
                    }
                }
            },
            textStyle = DifftTheme.typography.bodyMedium.copy(
                color = DifftTheme.colors.textPrimary,
                textAlign = TextAlign.Center
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            cursorBrush = SolidColor(cursorColor),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            style = DifftTheme.typography.bodyMedium,
                            color = DifftTheme.colors.textTertiary,
                            textAlign = TextAlign.Center
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun TestInputField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = hint,
                style = DifftTheme.typography.bodyMedium,
                color = DifftTheme.colors.textTertiary
            )
        },
        textStyle = DifftTheme.typography.bodyMedium.copy(
            color = DifftTheme.colors.textPrimary
        ),
        singleLine = false,
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = DifftTheme.colors.background,
            unfocusedContainerColor = DifftTheme.colors.background,
            focusedBorderColor = DifftTheme.colors.divider,
            unfocusedBorderColor = DifftTheme.colors.divider
        ),
        modifier = modifier
    )
}

// ============== Preview Composables ==============

@Preview(name = "Light Theme", showBackground = true)
@Preview(
    name = "Dark Theme",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun TestScreenPreview() {
    DifftTheme {
        TestScreen(
            onNavigateBack = {},
            onCreateGroups = { _, _ -> },
            onDisbandGroups = {},
            onSendMessageToAllGroups = {},
            onSendMessageToSingleGroup = {},
            onCorruptDatabase = {},
            onBackupDatabase = {},
            onSendRecoveryEvent = {},
            onDialogTest = {}
        )
    }
}

@Preview(name = "Message Section - Light", showBackground = true)
@Preview(
    name = "Message Section - Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun TestSectionPreview() {
    DifftTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DifftTheme.colors.background)
                .padding(DifftTheme.spacing.insetLarge)
        ) {
            TestSection(title = "Message Test") {
                TestRowItem(
                    title = "Create Test Groups",
                    onClick = {},
                    trailingContent = {
                        NumberInputField(
                            value = "",
                            onValueChange = {},
                            hint = "50"
                        )
                    }
                )

                HorizontalDivider(color = DifftTheme.colors.backgroundSecondary)

                TestRowItem(
                    title = "Send Message to All Groups",
                    onClick = {}
                )
            }
        }
    }
}

@Preview(name = "Number Input - Light", showBackground = true)
@Preview(
    name = "Number Input - Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun NumberInputFieldPreview() {
    DifftTheme {
        Row(
            modifier = Modifier
                .background(DifftTheme.colors.backgroundSettingItem)
                .padding(DifftTheme.spacing.insetLarge),
            horizontalArrangement = Arrangement.spacedBy(DifftTheme.spacing.inlineMedium)
        ) {
            NumberInputField(
                value = "",
                onValueChange = {},
                hint = "50"
            )
            NumberInputField(
                value = "100",
                onValueChange = {},
                hint = "50"
            )
        }
    }
}
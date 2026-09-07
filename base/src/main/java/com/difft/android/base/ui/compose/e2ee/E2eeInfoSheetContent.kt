package com.difft.android.base.ui.compose.e2ee

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.R
import com.difft.android.base.ui.compose.DifftBottomSheetDefaults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.openExternalBrowser

/**
 * Inner content of the E2EE explainer sheet (icon/title/body/protected-card/caveat/actions).
 * No dialog chrome — callers ([E2eeInfoSheet.show] / [E2eeInfoSheetDialog]) supply the
 * ModalBottomSheet host and DifftTheme(darkTheme=) wrapping. [onDismissRequest] is invoked
 * by BOTH the primary button and the "Learn more" link (which also opens [learnMoreUrl]
 * in the external browser BEFORE requesting dismiss — close sheet, then browser).
 */
@Composable
fun E2eeInfoSheetContent(
    learnMoreUrl: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val openLearnMore: () -> Unit = {
        onDismissRequest()
        context.openExternalBrowser(learnMoreUrl)
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp

    Column(
        modifier = modifier
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            // Mock's 32dp sheet-top padding assumed the grabber floats OUTSIDE the sheet;
            // the shared drag handle already contributes its bottom padding below the pill, so only
            // the remainder is added here to keep the visual grabber→icon distance at 32dp.
            .padding(top = DifftTheme.spacing.insetXXLarge - DifftBottomSheetDefaults.DragHandleBottomPadding, bottom = 16.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    ImageVector.vectorResource(R.drawable.base_tabler_lock),
                    contentDescription = null,
                    tint = DifftTheme.colors.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    stringResource(R.string.e2ee_sheet_title),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = DifftTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.e2ee_sheet_body),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = DifftTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DifftTheme.colors.backgroundTertiary, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProtectedItemRow(R.drawable.base_tabler_message_circle, stringResource(R.string.e2ee_sheet_item_message))
                ProtectedItemRow(R.drawable.base_tabler_phone, stringResource(R.string.e2ee_sheet_item_call))
                ProtectedItemRow(R.drawable.base_tabler_paperclip, stringResource(R.string.e2ee_sheet_item_file))
                Text(
                    stringResource(R.string.e2ee_sheet_caveat),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = DifftTheme.colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawTopBorder(DifftTheme.colors.border)
                        .padding(top = 16.dp),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DifftTheme.colors.primary),
            ) {
                Text(
                    stringResource(R.string.e2ee_sheet_got_it),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = DifftTheme.colors.textOnPrimary,
                )
            }
            TextButton(
                onClick = openLearnMore,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.e2ee_learn_more),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = DifftTheme.colors.textInfo,
                )
            }
        }
    }
}

@Composable
private fun ProtectedItemRow(@DrawableRes icon: Int, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            ImageVector.vectorResource(icon),
            contentDescription = null,
            tint = DifftTheme.colors.icon,
            modifier = Modifier.size(20.dp),
        )
        Text(label, fontSize = 14.sp, lineHeight = 20.sp, color = DifftTheme.colors.textPrimary)
    }
}

/** 1dp hairline drawn along the top edge only — used to separate the caveat text from the
 * protected-content list above it inside the same card. Local to this file: the only sheet
 * that needs a top-only border, not promoted to a shared :base Modifier utility. */
private fun Modifier.drawTopBorder(color: Color): Modifier = drawBehind {
    drawLine(
        color = color,
        start = Offset(0f, 0f),
        end = Offset(size.width, 0f),
        strokeWidth = 1.dp.toPx(),
    )
}

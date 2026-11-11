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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.base.R
import com.difft.android.base.ui.compose.DifftBottomSheetDefaults
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.openExternalBrowser








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
    
    
    val containerHeight = LocalWindowInfo.current.containerSize.height
    val windowHeight = if (containerHeight > 0) {
        with(LocalDensity.current) { containerHeight.toDp() }
    } else {
        LocalConfiguration.current.screenHeightDp.dp
    }
    val maxHeight = windowHeight * 0.78f

    Column(
        modifier = modifier
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            
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
                    tint = DifftTheme.colors.icon,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    stringResource(R.string.e2ee_sheet_title),
                    style = DifftTheme.typography.h5,
                    color = DifftTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.e2ee_sheet_body),
                    style = DifftTheme.typography.p3,
                    color = DifftTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DifftTheme.colors.backgroundTertiary, DifftTheme.radius.largeShape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProtectedItemRow(R.drawable.base_tabler_message_circle, stringResource(R.string.e2ee_sheet_item_message))
                ProtectedItemRow(R.drawable.base_tabler_phone, stringResource(R.string.e2ee_sheet_item_call))
                ProtectedItemRow(R.drawable.base_tabler_paperclip, stringResource(R.string.e2ee_sheet_item_file))
                Text(
                    stringResource(R.string.e2ee_sheet_caveat),
                    style = DifftTheme.typography.p4,
                    color = DifftTheme.colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawTopBorder(DifftTheme.colors.divider)
                        .padding(top = 16.dp),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DifftButton(
                text = stringResource(R.string.e2ee_sheet_got_it),
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = openLearnMore,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.e2ee_learn_more),
                    
                    style = DifftTheme.typography.h6,
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
        Text(label, style = DifftTheme.typography.p3, color = DifftTheme.colors.textPrimary)
    }
}




private fun Modifier.drawTopBorder(color: Color): Modifier = drawBehind {
    
    
    drawLine(
        color = color,
        start = Offset(0f, HALF_PIXEL),
        end = Offset(size.width, HALF_PIXEL),
        strokeWidth = 1f,
    )
}

private const val HALF_PIXEL = 0.5f

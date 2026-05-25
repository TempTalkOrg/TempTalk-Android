package com.difft.android.login.intro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.login.R

@Composable
internal fun RegisterIntroPage(
    data: RegisterIntroPageData,
    onNext: () -> Unit,
    onButtonBottomYInRoot: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 15.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                .background(DifftTheme.colors.bgElevated)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            data.illustration(Modifier.size(120.dp))

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(id = data.titleRes),
                style = DifftTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = DifftTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(id = data.bodyRes),
                style = DifftTheme.typography.bodyMedium,
                color = DifftTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .onGloballyPositioned { coords ->
                        // Pages share identical layout, so any visible page reports the
                        // same Y; Screen uses this to anchor the floating indicator.
                        onButtonBottomYInRoot((coords.positionInRoot().y + coords.size.height).toInt())
                    },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DifftTheme.colors.primary,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = stringResource(id = R.string.register_intro_next),
                    style = DifftTheme.typography.labelLarge,
                )
            }
        }
    }
}

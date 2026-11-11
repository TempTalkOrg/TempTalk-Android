package com.difft.android.chat.group

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R










@Composable
fun GroupEncryptionBottomSheet(
    isUpgrade: Boolean,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
    canReset: Boolean = false,
    onReset: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(DifftTheme.colors.backgroundTertiary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.chat_ic_lock_cog),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = DifftTheme.colors.textSecondary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        
        Text(
            text = stringResource(
                if (isUpgrade) R.string.group_encrypted_title
                else R.string.group_encrypted_info_title
            ),
            style = DifftTheme.typography.p2,
            color = DifftTheme.colors.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        
        Text(
            text = stringResource(
                if (isUpgrade) R.string.group_upgrade_description
                else R.string.group_encrypted_info_description
            ),
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        
        if (isUpgrade) {
            TextButton(
                onClick = {
                    onUpgrade()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.group_upgrade_button),
                    
                    style = DifftTheme.typography.h5,
                    color = DifftTheme.colors.primary
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.group_upgrade_cancel),
                    
                    style = DifftTheme.typography.h5,
                    color = DifftTheme.colors.textPrimary
                )
            }
        } else {
            
            
            if (canReset) {
                TextButton(
                    onClick = {
                        onReset()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.group_crypto_reset_title),
                        
                        style = DifftTheme.typography.h5,
                        color = DifftTheme.colors.primary
                    )
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.group_encrypted_info_dismiss),
                    
                    
                    style = DifftTheme.typography.h5,
                    color = DifftTheme.colors.textPrimary
                )
            }
        }
    }
}

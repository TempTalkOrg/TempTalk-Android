package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.FullScreenBottomDialog
import com.difft.android.base.widget.BottomDialog
import com.difft.android.base.widget.WaitDialog
import com.difft.android.base.widget.MessageDialog
import dagger.hilt.android.AndroidEntryPoint

/**
 * Unified Dialog Test Page
 * Demonstrates two ways to call ComposeDialogManager:
 * 1. Direct API Call - Works with all Activities
 * 2. Composable Functions - Compose environment only
 */
@AndroidEntryPoint
class DialogTestActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, DialogTestActivity::class.java)
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DialogTestScreen()
        }
    }

    // === Direct API methods - Works with all Activities ===

    fun showMessageDialogDirect() {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = "Direct API Call",
            message = "This dialog is called directly via ComposeDialogManager.showMessageDialog()",
            showCancel = true,
            cancelable = true,
            onConfirm = {
                ComposeDialogManager.showTip(this, "User clicked OK")
            },
            onCancel = {
                ComposeDialogManager.showTip(this, "User clicked Cancel")
            },
            onDismiss = {
                ComposeDialogManager.showTip(this, "Dialog dismissed")
            }
        )
    }

    fun showWaitDialogDirect() {
        ComposeDialogManager.showWait(
            context = this,
            message = "",
            cancelable = false
        )
    }

    fun showBottomDialogDirect() {
        ComposeDialogManager.showBottomDialog(
            activity = this,
            onDismiss = {
                ComposeDialogManager.showTip(this, "Bottom dialog dismissed")
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Direct API Call",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "This bottom dialog is called directly via ComposeDialogManager.showBottomDialog()",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Button(
                    onClick = { /* Close logic handled in onDismiss */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }

    fun showBottomDialogWithViewDirect() {
        ComposeDialogManager.showBottomDialog(
            activity = this,
            layoutId = R.layout.test_custom_view_dialog,
            onDismiss = {
                ComposeDialogManager.showTip(this, "Custom view bottom dialog dismissed")
            }
        ) { view ->
            view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
                // Close logic handled in onDismiss
            }
        }
    }

    fun showFullScreenBottomDialogDirect() {
        ComposeDialogManager.showFullScreenBottomDialog(
            activity = this,
            onDismiss = {
                ComposeDialogManager.showTip(this, "Fullscreen bottom dialog dismissed")
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Fullscreen Bottom Dialog",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "This fullscreen bottom dialog is called directly via ComposeDialogManager.showFullScreenBottomDialog()",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Button(
                    onClick = { /* Close logic handled in onDismiss */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }

    fun showFullScreenBottomDialogWithViewDirect() {
        ComposeDialogManager.showFullScreenBottomDialog(
            activity = this,
            layoutId = R.layout.test_custom_view_dialog,
            onDismiss = {
                ComposeDialogManager.showTip(this, "Fullscreen custom view bottom dialog dismissed")
            }
        ) { view ->
            view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
                // Close logic handled in onDismiss
            }
        }
    }

    fun showNonDismissibleDialogDirect() {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = "Important Notice",
            message = "This dialog cannot be dismissed by clicking outside. You must use the buttons.",
            confirmText = "Got it",
            showCancel = true,
            cancelable = false,
            onConfirm = {
                ComposeDialogManager.showTip(this, "User confirmed")
            },
            onCancel = {
                ComposeDialogManager.showTip(this, "User cancelled")
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogTestScreen() {
    val context = LocalContext.current as DialogTestActivity

    // === Composable function state ===
    var showMessageDialogComposable by remember { mutableStateOf(false) }
    var showWaitDialogComposable by remember { mutableStateOf(false) }
    var showBottomDialogComposable by remember { mutableStateOf(false) }
    var showBottomDialogViewComposable by remember { mutableStateOf(false) }
    var showFullScreenDialogComposable by remember { mutableStateOf(false) }
    var showFullScreenDialogViewComposable by remember { mutableStateOf(false) }
    var showNonDismissibleDialogComposable by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(colorResource(id = com.difft.android.base.R.color.bg_setting))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Dialog Test Page",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colorResource(id = com.difft.android.base.R.color.t_primary),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                text = "Demonstrates two ways to call ComposeDialogManager",
                fontSize = 14.sp,
                color = colorResource(id = com.difft.android.base.R.color.t_primary),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // === Method 1: Direct API Call ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Method 1: Direct API Call",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "ComposeDialogManager.showXxx() - Works with all Activities",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Message Dialog
                    Button(
                        onClick = { context.showMessageDialogDirect() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Message Dialog (Direct)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Wait Dialog
                    Button(
                        onClick = { context.showWaitDialogDirect() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Wait Dialog (Direct)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bottom Dialog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { context.showBottomDialogDirect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Bottom Dialog", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { context.showBottomDialogWithViewDirect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Custom View", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Fullscreen Bottom Dialog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { context.showFullScreenBottomDialogDirect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Fullscreen", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { context.showFullScreenBottomDialogWithViewDirect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Fullscreen View", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Non-dismissible Dialog
                    Button(
                        onClick = { context.showNonDismissibleDialogDirect() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Non-dismissible Dialog (Direct)")
                    }
                }
            }
        }

        // === Method 2: Composable Functions ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Method 2: Composable Functions",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "@Composable fun XxxDialog() - Compose environment only",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Message Dialog
                    Button(
                        onClick = { showMessageDialogComposable = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Message Dialog (Composable)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Wait Dialog
                    Button(
                        onClick = { showWaitDialogComposable = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Wait Dialog (Composable)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bottom Dialog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showBottomDialogComposable = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Bottom Dialog", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showBottomDialogViewComposable = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Custom View", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Fullscreen Bottom Dialog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showFullScreenDialogComposable = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Fullscreen", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showFullScreenDialogViewComposable = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Fullscreen View", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Non-dismissible Dialog
                    Button(
                        onClick = { showNonDismissibleDialogComposable = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Non-dismissible Dialog (Composable)")
                    }
                }
            }
        }

        // === Snackbar Tips Test ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Snackbar Tips",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF9800),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                ComposeDialogManager.showPopTip(
                                    context,
                                    "Simple tip message"
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Simple", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                ComposeDialogManager.showTip(
                                    context,
                                    "Success message",
                                    ComposeDialogManager.DialogType.SUCCESS
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Success", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                ComposeDialogManager.showTip(
                                    context,
                                    "Error message",
                                    ComposeDialogManager.DialogType.ERROR
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Error", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                ComposeDialogManager.showTip(
                                    context,
                                    "Warning message",
                                    ComposeDialogManager.DialogType.WARNING,
                                    actionText = "View",
                                    onAction = {
                                        ComposeDialogManager.showTip(context, "Clicked View")
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Warning+Action", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // === Composable function Dialog calls ===

    // Message Dialog
    MessageDialog(
        isVisible = showMessageDialogComposable,
        title = "Composable Function",
        message = "This dialog is called via @Composable MessageDialog() function",
        showCancel = true,
        cancelable = true,
        onConfirm = {
            showMessageDialogComposable = false
            ComposeDialogManager.showTip(context, "User clicked OK")
        },
        onCancel = {
            showMessageDialogComposable = false
            ComposeDialogManager.showTip(context, "User clicked Cancel")
        },
        onDismiss = {
            showMessageDialogComposable = false
            ComposeDialogManager.showTip(context, "Dialog dismissed")
        }
    )

    // Wait Dialog
    WaitDialog(
        isVisible = showWaitDialogComposable,
        message = "Processing...",
        cancelable = true
    )

    // Bottom Dialog - Compose content
    BottomDialog(
        isVisible = showBottomDialogComposable,
        onDismiss = { 
            showBottomDialogComposable = false
            ComposeDialogManager.showTip(context, "Bottom dialog dismissed")
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Composable Function",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Text(
                text = "This bottom dialog is called via @Composable BottomDialog() function",
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showBottomDialogComposable = false },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                
                Button(
                    onClick = { showBottomDialogComposable = false },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("OK")
                }
            }
        }
    }

    // Bottom Dialog - Custom View
    BottomDialog(
        isVisible = showBottomDialogViewComposable,
        onDismiss = { 
            showBottomDialogViewComposable = false
            ComposeDialogManager.showTip(context, "Custom view bottom dialog dismissed")
        },
        layoutId = R.layout.test_custom_view_dialog
    ) { view ->
        view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
            showBottomDialogViewComposable = false
        }
    }

    // Fullscreen Bottom Dialog
    FullScreenBottomDialog(
        isVisible = showFullScreenDialogComposable,
        onDismiss = { 
            showFullScreenDialogComposable = false
            ComposeDialogManager.showTip(context, "Fullscreen bottom dialog dismissed")
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Fullscreen Bottom Dialog",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Text(
                text = "This fullscreen bottom dialog is called via @Composable FullScreenBottomDialog() function",
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Button(
                onClick = { showFullScreenDialogComposable = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }
        }
    }

    // Fullscreen Bottom Dialog - Custom View
    FullScreenBottomDialog(
        isVisible = showFullScreenDialogViewComposable,
        onDismiss = { 
            showFullScreenDialogViewComposable = false
            ComposeDialogManager.showTip(context, "Fullscreen custom view bottom dialog dismissed")
        },
        layoutId = R.layout.test_custom_view_dialog
    ) { view ->
        view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
            showFullScreenDialogViewComposable = false
        }
    }

    // Non-dismissible Dialog
    MessageDialog(
        isVisible = showNonDismissibleDialogComposable,
        title = "Important Notice",
        message = "This dialog cannot be dismissed by clicking outside. You must use the buttons.",
        confirmText = "Got it",
        showCancel = true,
        cancelable = false,
        onConfirm = { 
            showNonDismissibleDialogComposable = false
            ComposeDialogManager.showTip(context, "User confirmed")
        },
        onCancel = { 
            showNonDismissibleDialogComposable = false
            ComposeDialogManager.showTip(context, "User cancelled")
        }
    )
}

@Preview(showBackground = true, name = "Dialog Test Preview")
@Composable
fun DialogTestPreview() {
    // Preview version, no dependency on specific Activity
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = com.difft.android.base.R.color.bg_setting))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Dialog Test Page",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colorResource(id = com.difft.android.base.R.color.t_primary),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                text = "Demonstrates two ways to call ComposeDialogManager",
                fontSize = 14.sp,
                color = colorResource(id = com.difft.android.base.R.color.t_primary),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // === Method 1: Direct API Call ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Method 1: Direct API Call",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "ComposeDialogManager.showXxx() - Works with all Activities",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Sample buttons (not clickable in preview mode)
                    Button(
                        onClick = { /* Preview mode */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Message Dialog (Direct)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { /* Preview mode */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Wait Dialog (Direct)")
                    }
                }
            }
        }

        // === Method 2: Composable Functions ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Method 2: Composable Functions",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "@Composable fun XxxDialog() - Compose environment only",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Button(
                        onClick = { /* Preview mode */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Message Dialog (Composable)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { /* Preview mode */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Wait Dialog (Composable)")
                    }
                }
            }
        }
    }
}

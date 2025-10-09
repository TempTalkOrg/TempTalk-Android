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
 * 统一的Dialog测试页面
 * 展示ComposeDialogManager的两种调用方式：
 * 1. 直接调用API - 适用于所有Activity
 * 2. Composable函数 - 适用于Compose环境
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

    // === 直接调用API方法 - 可在任何Activity中使用 ===

    fun showMessageDialogDirect() {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = "直接调用API",
            message = "这是通过ComposeDialogManager.showMessageDialog()直接调用的对话框",
            showCancel = true,
            cancelable = true,
            onConfirm = {
                ComposeDialogManager.showTip(this, "用户点击了确定")
            },
            onCancel = {
                ComposeDialogManager.showTip(this, "用户点击了取消")
            },
            onDismiss = {
                ComposeDialogManager.showTip(this, "对话框已关闭")
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
                ComposeDialogManager.showTip(this, "底部Dialog已关闭")
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "直接调用API",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "这是通过ComposeDialogManager.showBottomDialog()直接调用的底部对话框",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Button(
                    onClick = { /* 关闭逻辑在onDismiss中处理 */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }

    fun showBottomDialogWithViewDirect() {
        ComposeDialogManager.showBottomDialog(
            activity = this,
            layoutId = R.layout.test_custom_view_dialog,
            onDismiss = {
                ComposeDialogManager.showTip(this, "自定义View底部Dialog已关闭")
            }
        ) { view ->
            view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
                // 关闭逻辑在onDismiss中处理
            }
        }
    }

    fun showFullScreenBottomDialogDirect() {
        ComposeDialogManager.showFullScreenBottomDialog(
            activity = this,
            onDismiss = {
                ComposeDialogManager.showTip(this, "全屏底部Dialog已关闭")
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "全屏底部Dialog",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "这是通过ComposeDialogManager.showFullScreenBottomDialog()直接调用的全屏底部对话框",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Button(
                    onClick = { /* 关闭逻辑在onDismiss中处理 */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }

    fun showFullScreenBottomDialogWithViewDirect() {
        ComposeDialogManager.showFullScreenBottomDialog(
            activity = this,
            layoutId = R.layout.test_custom_view_dialog,
            onDismiss = {
                ComposeDialogManager.showTip(this, "全屏自定义View底部Dialog已关闭")
            }
        ) { view ->
            view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
                // 关闭逻辑在onDismiss中处理
            }
        }
    }

    fun showNonDismissibleDialogDirect() {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = "重要提示",
            message = "这是一个不可通过点击外部取消的对话框，只能通过按钮操作。",
            confirmText = "我知道了",
            showCancel = true,
            cancelable = false,
            onConfirm = {
                ComposeDialogManager.showTip(this, "用户确认了")
            },
            onCancel = {
                ComposeDialogManager.showTip(this, "用户取消了")
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogTestScreen() {
    val context = LocalContext.current as DialogTestActivity

    // === Composable函数方式的状态 ===
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
            .background(colorResource(id = com.difft.android.base.R.color.bg_setting))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Dialog测试页面",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colorResource(id = com.difft.android.base.R.color.t_primary),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                text = "展示ComposeDialogManager的两种调用方式",
                fontSize = 14.sp,
                color = colorResource(id = com.difft.android.base.R.color.t_primary),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // === 方式一：直接调用API ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "方式一：直接调用API",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "ComposeDialogManager.showXxx() - 适用于所有Activity",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // 消息对话框
                    Button(
                        onClick = { context.showMessageDialogDirect() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("消息对话框 (直接调用)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 等待对话框
                    Button(
                        onClick = { context.showWaitDialogDirect() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("等待对话框 (直接调用)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 底部Dialog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { context.showBottomDialogDirect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("底部Dialog", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { context.showBottomDialogWithViewDirect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("自定义View", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 全屏底部Dialog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { context.showFullScreenBottomDialogDirect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("全屏Dialog", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { context.showFullScreenBottomDialogWithViewDirect() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("全屏View", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 不可取消对话框
                    Button(
                        onClick = { context.showNonDismissibleDialogDirect() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("不可取消对话框 (直接调用)")
                    }
                }
            }
        }

        // === 方式二：Composable函数 ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "方式二：Composable函数",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "@Composable fun XxxDialog() - 仅适用于Compose环境",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // 消息对话框
                    Button(
                        onClick = { showMessageDialogComposable = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("消息对话框 (Composable)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 等待对话框
                    Button(
                        onClick = { showWaitDialogComposable = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("等待对话框 (Composable)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 底部Dialog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showBottomDialogComposable = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("底部Dialog", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showBottomDialogViewComposable = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("自定义View", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 全屏底部Dialog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showFullScreenDialogComposable = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("全屏Dialog", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showFullScreenDialogViewComposable = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("全屏View", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 不可取消对话框
                    Button(
                        onClick = { showNonDismissibleDialogComposable = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("不可取消对话框 (Composable)")
                    }
                }
            }
        }

        // === 提示信息测试 ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "提示信息 (Snackbar)",
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
                                    "简单提示消息"
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("简单提示", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                ComposeDialogManager.showTip(
                                    context,
                                    "成功消息",
                                    ComposeDialogManager.DialogType.SUCCESS
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("成功提示", fontSize = 12.sp)
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
                                    "错误消息",
                                    ComposeDialogManager.DialogType.ERROR
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("错误提示", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                ComposeDialogManager.showTip(
                                    context,
                                    "警告消息",
                                    ComposeDialogManager.DialogType.WARNING,
                                    actionText = "查看",
                                    onAction = {
                                        ComposeDialogManager.showTip(context, "点击了查看")
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("警告+操作", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // === Composable函数方式的Dialog调用 ===

    // 消息对话框
    MessageDialog(
        isVisible = showMessageDialogComposable,
        title = "Composable函数",
        message = "这是通过@Composable MessageDialog()函数调用的对话框",
        showCancel = true,
        cancelable = true,
        onConfirm = {
            showMessageDialogComposable = false
            ComposeDialogManager.showTip(context, "用户点击了确定")
        },
        onCancel = {
            showMessageDialogComposable = false
            ComposeDialogManager.showTip(context, "用户点击了取消")
        },
        onDismiss = {
            showMessageDialogComposable = false
            ComposeDialogManager.showTip(context, "对话框已关闭")
        }
    )

    // 等待对话框
    WaitDialog(
        isVisible = showWaitDialogComposable,
        message = "正在处理中...",
        cancelable = true
    )

    // 底部Dialog - Compose内容
    BottomDialog(
        isVisible = showBottomDialogComposable,
        onDismiss = { 
            showBottomDialogComposable = false
            ComposeDialogManager.showTip(context, "底部Dialog已关闭")
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Composable函数",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Text(
                text = "这是通过@Composable BottomDialog()函数调用的底部对话框",
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
                    Text("取消")
                }
                
                Button(
                    onClick = { showBottomDialogComposable = false },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确定")
                }
            }
        }
    }

    // 底部Dialog - 自定义View
    BottomDialog(
        isVisible = showBottomDialogViewComposable,
        onDismiss = { 
            showBottomDialogViewComposable = false
            ComposeDialogManager.showTip(context, "自定义View底部Dialog已关闭")
        },
        layoutId = R.layout.test_custom_view_dialog
    ) { view ->
        view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
            showBottomDialogViewComposable = false
        }
    }

    // 全屏底部Dialog
    FullScreenBottomDialog(
        isVisible = showFullScreenDialogComposable,
        onDismiss = { 
            showFullScreenDialogComposable = false
            ComposeDialogManager.showTip(context, "全屏底部Dialog已关闭")
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "全屏底部Dialog",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Text(
                text = "这是通过@Composable FullScreenBottomDialog()函数调用的全屏底部对话框",
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Button(
                onClick = { showFullScreenDialogComposable = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("关闭")
            }
        }
    }

    // 全屏底部Dialog - 自定义View
    FullScreenBottomDialog(
        isVisible = showFullScreenDialogViewComposable,
        onDismiss = { 
            showFullScreenDialogViewComposable = false
            ComposeDialogManager.showTip(context, "全屏自定义View底部Dialog已关闭")
        },
        layoutId = R.layout.test_custom_view_dialog
    ) { view ->
        view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
            showFullScreenDialogViewComposable = false
        }
    }

    // 不可取消对话框
    MessageDialog(
        isVisible = showNonDismissibleDialogComposable,
        title = "重要提示",
        message = "这是一个不可通过点击外部取消的对话框，只能通过按钮操作。",
        confirmText = "我知道了",
        showCancel = true,
        cancelable = false,
        onConfirm = { 
            showNonDismissibleDialogComposable = false
            ComposeDialogManager.showTip(context, "用户确认了")
        },
        onCancel = { 
            showNonDismissibleDialogComposable = false
            ComposeDialogManager.showTip(context, "用户取消了")
        }
    )
}

@Preview(showBackground = true, name = "Dialog Test Preview")
@Composable
fun DialogTestPreview() {
    // 预览版本，不依赖特定Activity
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = com.difft.android.base.R.color.bg_setting))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Dialog测试页面",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colorResource(id = com.difft.android.base.R.color.t_primary),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                text = "展示ComposeDialogManager的两种调用方式",
                fontSize = 14.sp,
                color = colorResource(id = com.difft.android.base.R.color.t_primary),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // === 方式一：直接调用API ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "方式一：直接调用API",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "ComposeDialogManager.showXxx() - 适用于所有Activity",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // 示例按钮（预览模式不可点击）
                    Button(
                        onClick = { /* 预览模式 */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("消息对话框 (直接调用)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { /* 预览模式 */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("等待对话框 (直接调用)")
                    }
                }
            }
        }

        // === 方式二：Composable函数 ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "方式二：Composable函数",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "@Composable fun XxxDialog() - 仅适用于Compose环境",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Button(
                        onClick = { /* 预览模式 */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("消息对话框 (Composable)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { /* 预览模式 */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("等待对话框 (Composable)")
                    }
                }
            }
        }
    }
}

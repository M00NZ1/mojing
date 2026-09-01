package com.mojing.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import com.mojing.app.R
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mojing.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FloatingWindowService : Service() {

    private var floatingView: ComposeView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = 20
            y = 100
        }

        floatingView = ComposeView(this).apply {
            setContent {
                FloatingChatBubble(
                    onTapApp = {
                        val intent = Intent(this@FloatingWindowService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                    },
                    onDismiss = { stopSelf() }
                )
            }
        }

        windowManager.addView(floatingView, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        floatingView?.let {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeView(it)
        }
        super.onDestroy()
    }
}

data class ChatLine(val speaker: String, val content: String, val isNarrator: Boolean = false)

@Composable
fun FloatingChatBubble(
    onTapApp: () -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val chatLines = remember { mutableStateListOf<ChatLine>() }
    val listState = rememberLazyListState()

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (expanded) {
            Card(
                modifier = Modifier
                    .width(320.dp)
                    .heightIn(max = 480.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row {
                            IconButton(onClick = onTapApp, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Send, "打开App", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onDismiss() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    HorizontalDivider()

                    if (chatLines.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("点击悬浮球唤起此面板", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(chatLines.size) { index ->
                                val line = chatLines[index]
                                ChatBubble(line)
                            }
                        }
                        LaunchedEffect(chatLines.size) {
                            if (chatLines.isNotEmpty()) listState.animateScrollToItem(chatLines.size - 1)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f).height(48.dp),
                            placeholder = { Text("输入...", fontSize = 13.sp) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    chatLines.add(ChatLine("你", inputText))
                                    chatLines.add(ChatLine("AI", "请在App中打开以获取完整回复", false))
                                    inputText = ""
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Send, "发送", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Person, stringResource(R.string.app_name), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun ChatBubble(line: ChatLine) {
    val isSelf = line.speaker == "你"
    val bg = if (isSelf) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    else MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start
    ) {
        Text(line.speaker, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bg,
            modifier = Modifier.widthIn(max = 260.dp)
        ) {
            Text(
                line.content,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                fontSize = 13.sp,
                fontStyle = if (line.isNarrator) FontStyle.Italic else FontStyle.Normal
            )
        }
    }
}

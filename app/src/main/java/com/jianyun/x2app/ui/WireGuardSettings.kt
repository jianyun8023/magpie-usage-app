package com.jianyun.x2app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jianyun.x2app.vpn.VpnController
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun WireGuardSettings(busy: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { VpnController.get(context) }
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var confirmDelete by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(controller::importConfig)
    }
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                controller.refresh()
                delay(5_000)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("WireGuard", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
        Text(
            when {
                state.busy -> "正在处理…"
                !state.known -> "VPN 状态未知"
                state.up -> "隧道已启动"
                state.configured -> "已断开 · 配置已保存"
                else -> "未导入配置"
            },
            color = Ink.fg,
        )
        if (state.up) {
            Text(
                if (state.handshake > 0) "最近握手：${DateFormat.getDateTimeInstance().format(Date(state.handshake))}"
                else "尚未收到 peer 握手，请访问内网服务后检查。",
                color = Ink.fgSoft,
            )
            Text("接收 ${state.rx} B · 发送 ${state.tx} B", color = Ink.fgSoft)
        }
        state.error?.let { Text(it, color = Ink.danger) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton(
                if (state.configured) "替换配置" else "导入 .conf",
                { importer.launch(arrayOf("*/*")) },
                Modifier.weight(1f),
                enabled = !busy && !state.busy && state.known && !state.up,
            )
            InkButton(
                if (!state.known) "确认断开" else if (state.up) "断开" else "连接",
                {
                    if (state.up || !state.known) onDisconnect() else onConnect()
                },
                Modifier.weight(1f),
                selected = state.up,
                enabled = !busy && !state.busy && (state.configured || !state.known),
            )
        }
        if (state.configured || state.error != null) {
            InkButton("删除 VPN 配置", { confirmDelete = true }, enabled = !busy && !state.busy && state.known && !state.up)
        }
        Text("配置在本机加密保存，不参与备份。路由按 AllowedIPs 生效；隧道启动不代表内网可达。", fontSize = 14.sp, color = Ink.fgSoft)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 VPN 配置？") },
            text = { Text("删除后需要重新导入 .conf 文件。") },
            confirmButton = { InkButton("删除", { confirmDelete = false; controller.delete() }) },
            dismissButton = { InkButton("取消", { confirmDelete = false }) },
        )
    }
}

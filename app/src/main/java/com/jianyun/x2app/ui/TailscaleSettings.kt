package com.jianyun.x2app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianyun.x2app.tailscale.TailscaleState

internal enum class TailscalePrimaryAction { LOGIN, CONNECT, DISCONNECT, AUTHORIZE }

internal fun tailscalePrimaryAction(state: TailscaleState): TailscalePrimaryAction = when {
    state.serviceActive && state.authUrl != null -> TailscalePrimaryAction.AUTHORIZE
    state.serviceActive -> TailscalePrimaryAction.DISCONNECT
    state.backendState in listOf("NoState", "NeedsLogin", "InUseOtherUser") -> TailscalePrimaryAction.LOGIN
    else -> TailscalePrimaryAction.CONNECT
}

@Composable
fun TailscaleSettings(
    state: TailscaleState,
    busy: Boolean,
    onLogin: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onLogout: () -> Unit,
    onOpenAuthorization: () -> Unit,
    onSaveControlUrl: (String) -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var controlUrl by remember(state.controlUrl) { mutableStateOf(state.controlUrl) }
    var pendingControlUrl by remember { mutableStateOf<String?>(null) }
    val needsLogin = state.backendState in listOf("NoState", "NeedsLogin", "InUseOtherUser")
    val primaryAction = tailscalePrimaryAction(state)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tailscale", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
        Text(
            when {
                state.serviceActive && state.connected -> "已连接"
                !state.serviceActive && state.backendState in listOf("Starting", "Running") -> "已断开 · 可手动连接"
                state.backendState == "Running" -> "引擎运行中 · VPN 尚未建立"
                state.backendState == "Starting" -> "正在建立连接"
                state.backendState == "NeedsMachineAuth" -> "等待管理员批准设备"
                state.backendState == "NeedsLogin" -> "需要登录"
                state.backendState == "Stopped" -> "已断开"
                state.backendState == "InUseOtherUser" -> "引擎由其他用户占用"
                else -> "尚未就绪"
            },
            color = Ink.fg,
        )
        if (state.serviceActive && state.connected && state.addresses.isNotEmpty()) {
            Text(state.addresses.joinToString("\n"), color = Ink.fgSoft)
        }
        state.error?.let { Text(it, color = Ink.danger) }
        state.health.forEach { Text(it, color = Ink.fgSoft) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (primaryAction) {
                TailscalePrimaryAction.AUTHORIZE -> {
                    InkButton("继续登录", onOpenAuthorization, Modifier.weight(1f), enabled = !busy)
                    InkButton("取消登录", onDisconnect, Modifier.weight(1f), enabled = !busy)
                }
                TailscalePrimaryAction.DISCONNECT -> InkButton("断开", onDisconnect, Modifier.weight(1f), enabled = !busy)
                TailscalePrimaryAction.LOGIN -> InkButton("登录", onLogin, Modifier.weight(1f), enabled = !busy)
                TailscalePrimaryAction.CONNECT -> InkButton("连接", onConnect, Modifier.weight(1f), enabled = !busy)
            }
            if (!needsLogin && state.authUrl == null) {
                InkButton("退出账号", { confirmLogout = true }, Modifier.weight(1f), enabled = !busy)
            }
        }
        OutlinedTextField(
            value = controlUrl,
            onValueChange = { controlUrl = it },
            label = { Text("控制服务器（留空使用官方）") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        )
        InkButton("保存控制服务器", { pendingControlUrl = controlUrl }, enabled = !busy &&
            controlUrl.trim().trimEnd('/').ifBlank { TailscaleState().controlUrl } != state.controlUrl)
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出 Tailscale 账号？") },
            text = { Text("将断开 Tailscale，下次使用需要重新登录。") },
            confirmButton = { InkButton("退出", { confirmLogout = false; onLogout() }) },
            dismissButton = { InkButton("取消", { confirmLogout = false }) },
        )
    }
    pendingControlUrl?.let { requested ->
        AlertDialog(
            onDismissRequest = { pendingControlUrl = null },
            title = { Text("切换控制服务器？") },
            text = { Text("将先断开 Tailscale 并退出当前账号。保存后需要重新登录。" +
                if (requested.trim().startsWith("http://", true)) "\nHTTP 不加密，请仅用于可信测试网络。" else "") },
            confirmButton = { InkButton("断开并保存", { pendingControlUrl = null; onSaveControlUrl(requested) }, enabled = !busy) },
            dismissButton = { InkButton("取消", { pendingControlUrl = null }) },
        )
    }
}

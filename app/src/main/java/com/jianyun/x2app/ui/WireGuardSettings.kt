package com.jianyun.x2app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianyun.x2app.vpn.AppNetworkMode
import com.jianyun.x2app.vpn.WireGuardState

@Composable
fun WireGuardSettings(
    state: WireGuardState,
    busy: Boolean,
    mode: AppNetworkMode,
    onImport: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val selected = mode == AppNetworkMode.WIREGUARD

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("WireGuard", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
        Text(
            when {
                state.busy -> "正在处理…"
                !state.configured -> "未导入配置"
                state.up -> "隧道已启动 · 仅本应用流量使用该网络"
                selected -> "已选择 · 未连接"
                else -> "已断开 · 配置已保存"
            },
            color = Ink.fg,
        )
        state.error?.let { Text(it, color = Ink.danger) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton(
                if (state.configured) "替换配置" else "导入 .conf",
                onImport,
                Modifier.weight(1f),
                enabled = !busy && !state.up,
            )
            InkButton(
                if (state.up) "断开" else "连接",
                if (state.up) onDisconnect else onConnect,
                Modifier.weight(1f),
                selected = state.up,
                enabled = !busy && state.configured,
            )
        }
        if (state.configured || state.error != null) {
            InkButton("删除配置", { confirmDelete = true }, enabled = !busy && !state.up)
        }
        Text(
            "配置在本机加密保存，不参与备份；路由按 AllowedIPs 生效，隧道启动不代表内网可达。",
            fontSize = 14.sp,
            color = Ink.fgSoft,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 WireGuard 配置？") },
            text = { Text("删除后需要重新导入 .conf 文件。") },
            confirmButton = { InkButton("删除", { confirmDelete = false; onDelete() }) },
            dismissButton = { InkButton("取消", { confirmDelete = false }) },
        )
    }
}

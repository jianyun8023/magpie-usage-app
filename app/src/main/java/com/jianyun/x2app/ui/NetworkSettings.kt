package com.jianyun.x2app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.jianyun.x2app.vpn.AppNetworkEngine
import com.jianyun.x2app.vpn.AppNetworkMode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

@Composable
fun NetworkSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = remember { AppNetworkEngine.get(context) }
    val mode by engine.mode.collectAsStateWithLifecycle()
    val wgState by engine.wireGuard.collectAsStateWithLifecycle()
    val tsState by engine.tailscale.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var openLoginWhenReady by rememberSaveable { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    val busy = wgState.busy || tsState.busy

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(engine::importWireGuard)
    }

    fun run(reportFailure: Boolean = true, action: suspend () -> Result<Unit>) {
        scope.launch {
            action()
                .onSuccess { operationError = null }
                .onFailure {
                    if (reportFailure) operationError = it.message ?: "网络操作失败，请重试"
                }
        }
    }

    fun openAuthorization() {
        if (busy || tsState.authUrl == null) return
        openLoginWhenReady = false
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tsState.authUrl))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) {
            engine.reportTailscaleError("无法打开授权页面，请安装或启用浏览器后选择“继续登录”。")
        }
    }

    LaunchedEffect(tsState.authUrl, openLoginWhenReady, tsState.error, wgState.busy, tsState.busy) {
        if (openLoginWhenReady && tsState.authUrl != null && tsState.error == null && !busy) openAuthorization()
    }
    LaunchedEffect(lifecycle, engine) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            run { engine.refresh() }
            awaitCancellation()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("网络接入", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
            InkButton("刷新状态", { run { engine.refresh() } }, enabled = !busy)
        }
        Text(
            when (mode) {
                AppNetworkMode.DIRECT -> "直连 · 请求不经过应用内网络"
                AppNetworkMode.WIREGUARD ->
                    if (wgState.up) "WireGuard 隧道已启动" else "WireGuard 已选择 · 未连接"
                AppNetworkMode.TAILSCALE ->
                    if (tsState.connected) "Tailscale 已连接" else "Tailscale 已选择 · 未连接"
                AppNetworkMode.UNAVAILABLE -> "网络配置不可用，请重新选择网络"
            },
            fontSize = 14.sp,
            color = Ink.fgSoft,
        )
        Text("请求只会走当前选择的网络；切换或断开失败时不会回落直连。", fontSize = 14.sp, color = Ink.fgSoft)
        operationError?.let { Text(it, color = Ink.danger) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton("直连", { run { engine.useDirect() } }, Modifier.weight(1f),
                selected = mode == AppNetworkMode.DIRECT, enabled = !busy)
            InkButton("WireGuard", { run { engine.connectWireGuard() } }, Modifier.weight(1f),
                selected = mode == AppNetworkMode.WIREGUARD, enabled = !busy)
            InkButton("Tailscale", { run { engine.connectTailscale() } }, Modifier.weight(1f),
                selected = mode == AppNetworkMode.TAILSCALE, enabled = !busy)
        }

        WireGuardSettings(
            state = wgState,
            busy = busy,
            mode = mode,
            onImport = { importer.launch(arrayOf("*/*")) },
            onConnect = { run(reportFailure = false) { engine.connectWireGuard() } },
            onDisconnect = { run(reportFailure = false) { engine.disconnectWireGuard() } },
            onDelete = { engine.deleteWireGuard() },
        )
        HorizontalDivider(color = Ink.line)
        TailscaleSettings(
            state = tsState,
            busy = busy,
            onLogin = { openLoginWhenReady = true; run(reportFailure = false) { engine.loginTailscale() } },
            onConnect = { run(reportFailure = false) { engine.connectTailscale() } },
            onDisconnect = { openLoginWhenReady = false; run(reportFailure = false) { engine.disconnectTailscale() } },
            onLogout = { openLoginWhenReady = false; run(reportFailure = false) { engine.logoutTailscale() } },
            onOpenAuthorization = ::openAuthorization,
            onSaveControlUrl = { value ->
                openLoginWhenReady = false
                run(reportFailure = false) { engine.setControlUrl(value) }
            },
        )
    }
}

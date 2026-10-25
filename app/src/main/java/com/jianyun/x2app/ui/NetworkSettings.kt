package com.jianyun.x2app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jianyun.x2app.tailscale.TailscaleController
import com.jianyun.x2app.vpn.NetworkAction
import com.jianyun.x2app.vpn.NetworkController
import com.jianyun.x2app.vpn.VpnController
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

@Composable
fun NetworkSettings() {
    val context = LocalContext.current
    val network = remember { NetworkController.get(context) }
    val wg = remember { VpnController.get(context) }
    val ts = remember { TailscaleController.get(context) }
    val networkState by network.state.collectAsStateWithLifecycle()
    val wgState by wg.state.collectAsStateWithLifecycle()
    val tsState by ts.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    // Save only user intent across rotation, never the login URL/token.
    var openLoginWhenReady by rememberSaveable { mutableStateOf(false) }
    val busy = networkState.busy || wgState.busy || tsState.busy || pendingAction != null

    fun runPending() {
        val action = pendingAction?.let(NetworkAction::valueOf) ?: return
        pendingAction = null
        network.run(action)
    }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK && runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
        if (granted) runPending()
        else {
            pendingAction = null
            openLoginWhenReady = false
            network.reportError("未获得系统 VPN 授权，未切换网络。")
        }
    }
    fun prepareVpn() {
        try {
            val intent = if (pendingAction == NetworkAction.WG_CONNECT.name) VpnService.prepare(context) else ts.prepareVpn()
            if (intent == null) runPending() else authorization.launch(intent)
        } catch (_: Exception) {
            pendingAction = null
            openLoginWhenReady = false
            network.reportError("无法请求系统 VPN 授权，请检查系统设置。")
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification consent is optional; VPN consent is not.
        prepareVpn()
    }
    fun request(action: NetworkAction) {
        if (network.state.value.busy || wg.state.value.busy || ts.state.value.busy || pendingAction != null) return
        openLoginWhenReady = action == NetworkAction.TS_LOGIN
        pendingAction = action.name
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else prepareVpn()
    }
    fun openAuthorization() {
        if (network.state.value.busy || pendingAction != null || !wg.state.value.known || wg.state.value.up) return
        val url = ts.state.value.authUrl ?: return
        openLoginWhenReady = false
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) {
            network.reportError("无法打开授权页面，请安装或启用浏览器后选择“继续登录”。")
        }
    }
    LaunchedEffect(tsState.authUrl, openLoginWhenReady, networkState.busy, networkState.error, wgState.up, wgState.known, pendingAction) {
        if (openLoginWhenReady && tsState.authUrl != null && networkState.error == null) openAuthorization()
    }
    LaunchedEffect(lifecycle, ts) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Browser return only refreshes the engine; it never means authentication succeeded.
            ts.refresh()
            awaitCancellation()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("网络接入", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
            InkButton("刷新状态", { scope.launch { ts.refresh(); wg.refresh() } }, enabled = !busy)
        }
        Text("连接一种 VPN 时会先断开另一种。", fontSize = 14.sp, color = Ink.fgSoft)
        if (networkState.busy) Text("正在切换网络…", color = Ink.fg)
        networkState.error?.let { Text(it, color = Ink.danger) }
        WireGuardSettings(
            busy = busy,
            onConnect = { request(NetworkAction.WG_CONNECT) },
            onDisconnect = { network.run(NetworkAction.WG_DISCONNECT) },
        )
        HorizontalDivider(color = Ink.line)
        TailscaleSettings(
            state = tsState,
            busy = busy,
            onLogin = { request(NetworkAction.TS_LOGIN) },
            onConnect = { request(NetworkAction.TS_CONNECT) },
            onDisconnect = { openLoginWhenReady = false; network.run(NetworkAction.TS_DISCONNECT) },
            onLogout = { openLoginWhenReady = false; network.run(NetworkAction.TS_LOGOUT) },
            onOpenAuthorization = { request(NetworkAction.TS_LOGIN) },
            onSaveControlUrl = { value -> openLoginWhenReady = false; network.setControlUrl(value) },
        )
    }
}

package com.jianyun.x2app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianyun.x2app.BuildConfig
import com.jianyun.x2app.api.AppUpdate
import com.jianyun.x2app.api.GITHUB_PROJECT_URL
import com.jianyun.x2app.api.checkForAppUpdate
import com.jianyun.x2app.data.UpdateChannel
import com.jianyun.x2app.data.UpdatePreferences
import com.jianyun.x2app.data.UpdateMirror
import com.jianyun.x2app.data.acceleratedGitHubUrl
import com.jianyun.x2app.data.normalizedUpdateProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun UpdateSettings(preferences: UpdatePreferences, onSave: (UpdatePreferences) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var proxy by rememberSaveable { mutableStateOf(preferences.proxyPrefix) }
    var mirrorName by rememberSaveable { mutableStateOf(UpdateMirror.forPrefix(preferences.proxyPrefix).name) }
    val mirror = UpdateMirror.entries.firstOrNull { it.name == mirrorName } ?: UpdateMirror.OFFICIAL
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var checkedProxy by remember { mutableStateOf("") }
    val resultView = remember { BringIntoViewRequester() }

    LaunchedEffect(message, error, update) {
        if (message != null || error != null) {
            withFrameNanos { }
            resultView.bringIntoView()
        }
    }

    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) {
            error = "无法打开链接，请安装或启用浏览器"
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("当前版本 ${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = Ink.fgSoft)
        Text("更新检查使用系统网络，不影响网关连接。", fontSize = 14.sp, color = Ink.fgSoft)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UpdateChannel.entries.forEach { channel ->
                InkButton(channel.label, {
                    onSave(preferences.copy(channel = channel))
                    update = null
                    message = null
                    error = null
                }, Modifier.weight(1f), selected = preferences.channel == channel, enabled = !checking)
            }
        }
        if (preferences.channel == UpdateChannel.BETA) {
            Text("Beta 包含预发布版和 Snapshot。", fontSize = 14.sp, color = Ink.fgSoft)
        }
        Text("检查来源", fontSize = 14.sp, color = Ink.fgSoft)
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 2,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateMirror.entries.forEach { choice ->
                InkButton(choice.label, {
                    mirrorName = choice.name
                    choice.prefix?.let { prefix ->
                        proxy = prefix
                        onSave(preferences.copy(proxyPrefix = prefix))
                    }
                    update = null
                    message = null
                    error = null
                }, Modifier.weight(1f), selected = mirror == choice, enabled = !checking)
            }
        }
        if (mirror == UpdateMirror.CUSTOM) OutlinedTextField(
            value = proxy,
            onValueChange = { proxy = it; update = null; message = null; error = null },
            label = { Text("GitHub 加速前缀") },
            placeholder = { Text("https://proxy.example/") },
            supportingText = { Text("需支持 GitHub API 转发，检查时保存。") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !checking,
            isError = error != null,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
            shape = RectangleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ink.fg, unfocusedBorderColor = Ink.line,
                focusedLabelColor = Ink.fg, unfocusedLabelColor = Ink.fgSoft,
                focusedTextColor = Ink.fg, unfocusedTextColor = Ink.fg,
                cursorColor = Ink.fg, focusedContainerColor = Ink.bg, unfocusedContainerColor = Ink.bg,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton(if (checking) "正在检查" else "检查更新", {
                try {
                    require(mirror != UpdateMirror.CUSTOM || proxy.isNotBlank()) { "请填写自定义 GitHub 加速前缀" }
                    val selected = preferences.copy(proxyPrefix = normalizedUpdateProxy(proxy))
                    onSave(selected)
                    proxy = selected.proxyPrefix
                    checking = true
                    update = null
                    message = null
                    error = null
                    scope.launch {
                        try {
                            update = checkForAppUpdate(selected, BuildConfig.VERSION_NAME,
                                BuildConfig.GIT_REVISION, BuildConfig.GIT_REVISION_TIME)
                            checkedProxy = selected.proxyPrefix
                            message = update?.let { "发现新版本：${it.release.name}" } ?: "未发现所选通道可用的新版本"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = when (e) {
                                is SerializationException -> "更新源返回了无法识别的数据，请确认加速站支持 GitHub API"
                                is IllegalStateException -> e.message ?: "更新检查失败，请稍后重试"
                                else -> "更新检查失败，请检查系统网络或加速地址"
                            }
                        } finally {
                            checking = false
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            }, Modifier.weight(1f), selected = true, enabled = !checking)
            InkButton("GitHub 项目", { open(GITHUB_PROJECT_URL) }, Modifier.weight(1f))
        }
        if (message != null || error != null) {
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(resultView),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                message?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink.fg) }
                error?.let { Text(it, fontSize = 14.sp, color = Ink.danger) }
                update?.let { available ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InkButton("浏览器下载 APK", {
                            open(acceleratedGitHubUrl(available.apk.downloadUrl, checkedProxy))
                        }, Modifier.weight(1f), selected = true)
                        InkButton("发布说明", { open(available.pageUrl) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

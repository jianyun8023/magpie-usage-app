package com.jianyun.x2app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianyun.x2app.data.ServerConfig
import com.jianyun.x2app.data.normalizedServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    config: ServerConfig,
    onSave: (baseUrl: String, webKey: String) -> Unit,
    onTest: suspend (baseUrl: String, webKey: String) -> String,
) {
    var url by remember(config) { mutableStateOf(config.baseUrl) }
    var key by remember(config) { mutableStateOf(config.webKey) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Ink.fg,
        unfocusedBorderColor = Ink.line,
        focusedLabelColor = Ink.fg,
        unfocusedLabelColor = Ink.fgSoft,
        focusedTextColor = Ink.fg,
        unfocusedTextColor = Ink.fg,
        cursorColor = Ink.fg,
        focusedContainerColor = Ink.bg,
        unfocusedContainerColor = Ink.bg,
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("服务器设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
        Text(
            "填写运行 magpie web 的机器地址。服务需以 magpie web --lan 启动，并设置 MAGPIE_WEB_KEY。",
            fontSize = 15.sp,
            color = Ink.fgSoft,
            lineHeight = 22.sp,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; testResult = null; validationError = null },
            label = { Text("地址，如 192.0.2.10:3430") },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RectangleShape,
            colors = fieldColors,
            enabled = !testing,
            isError = validationError != null,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; testResult = null; validationError = null },
            label = { Text("MAGPIE_WEB_KEY") },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            shape = RectangleShape,
            colors = fieldColors,
            enabled = !testing,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton(
                if (testing) "正在测试" else "测试连接",
                {
                    scope.launch {
                        testing = true
                        try {
                            val normalized = normalizedServerConfig(url, key)
                            validationError = null
                            testResult = onTest(normalized.baseUrl, normalized.webKey)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IllegalArgumentException) {
                            testResult = null
                            validationError = e.message
                        } catch (_: Exception) {
                            testResult = "连接测试失败，请检查网络与服务器设置后重试"
                        } finally {
                            testing = false
                        }
                    }
                },
                Modifier.weight(1f),
                enabled = !testing,
            )
            InkButton(
                "保存",
                {
                    try {
                        val normalized = normalizedServerConfig(url, key)
                        validationError = null
                        onSave(normalized.baseUrl, normalized.webKey)
                    } catch (e: IllegalArgumentException) {
                        testResult = null
                        validationError = e.message
                    }
                },
                Modifier.weight(1f),
                selected = true,
                enabled = !testing,
            )
        }
        validationError?.let { Text(it, color = Ink.danger) }
        testResult?.let {
            Text(
                it,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Ink.fg,
                lineHeight = 22.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = Ink.fg)
        NetworkSettings()
        HorizontalDivider(color = Ink.fg)
        Text("关于", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
        Text(
            "Magpie 用量 · 查看剩余额度、请求记录与用量汇总。\n数据来自 magpie web API（/api/usage*）。",
            fontSize = 14.sp,
            color = Ink.fgSoft,
            lineHeight = 21.sp,
        )
    }
}

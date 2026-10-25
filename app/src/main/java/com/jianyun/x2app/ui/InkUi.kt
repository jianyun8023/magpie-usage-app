package com.jianyun.x2app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Ink {
    val fg = Color.Black
    val fgSoft = Color(0xFF444444)
    val bg = Color.White
    val line = Color(0xFF777777)
    val barBg = Color(0xFFDDDDDD)
    val accent = Color.Black
    val danger = Color.Black
}

@Composable
fun Modifier.inkClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    enabled = enabled,
    onClick = onClick,
)

@Composable
fun InkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .border(1.dp, if (enabled) Ink.fg else Ink.line)
            .background(if (selected && enabled) Ink.fg else Ink.bg)
            .semantics {
                role = Role.Button
                this.selected = selected
            }
            .inkClickable(enabled, onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (!enabled) Ink.line else if (selected) Ink.bg else Ink.fg,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("正在读取数据", color = Ink.fg, fontSize = 18.sp)
    }
}

@Composable
fun ErrorBox(msg: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("! 加载失败", color = Ink.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(msg, color = Ink.fgSoft, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        InkButton("重试", onRetry)
    }
}

@Composable
fun DataStatus(state: LoadState<*>) {
    val ready = state as? LoadState.Ready<*>
    val text = when {
        ready == null -> ""
        ready.refreshing -> "正在更新，当前数据仍可查看"
        ready.updateError != null -> "! 更新失败：${ready.updateError}"
        ready.updatedAt != null -> "更新于 ${clock(ready.updatedAt)}"
        else -> ""
    }
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 24.dp).padding(horizontal = 16.dp)) {
        if (text.isNotEmpty()) {
            Text(
                text,
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = if (ready?.updateError == null) Ink.fgSoft else Ink.fg,
                fontSize = 12.sp,
                maxLines = 2,
            )
        }
    }
}

@Composable
fun PageControls(
    page: Int,
    pageCount: Int,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        InkButton("上一页", { onPage(page - 1) }, enabled = page > 0)
        Text(
            "${(page + 1).coerceAtMost(pageCount)} / $pageCount 页",
            color = Ink.fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        InkButton("下一页", { onPage(page + 1) }, enabled = page + 1 < pageCount)
    }
}

fun clock(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

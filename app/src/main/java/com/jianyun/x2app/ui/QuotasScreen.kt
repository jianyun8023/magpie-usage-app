package com.jianyun.x2app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianyun.x2app.data.QuotaCard
import com.jianyun.x2app.data.QuotaWindow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun QuotasScreen(
    state: LoadState<List<QuotaCard>>,
    ignoredIds: Set<String>,
    onToggleIgnored: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    when (state) {
        is LoadState.Loading -> LoadingBox()
        is LoadState.Failed -> ErrorBox(state.message, onRefresh)
        is LoadState.Ready -> QuotasBody(state, ignoredIds, onToggleIgnored, onRefresh)
    }
}

@Composable
private fun QuotasBody(
    state: LoadState.Ready<List<QuotaCard>>,
    ignoredIds: Set<String>,
    onToggleIgnored: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(0) }
    var managing by rememberSaveable { mutableStateOf(false) }
    val sortedData = sortedQuotaCards(state.data)
    if (managing) {
        QuotaManager(sortedData, ignoredIds, onToggleIgnored) { managing = false }
        return
    }
    val visibleData = sortedData.filterNot { quotaId(it) in ignoredIds }
    val ignoredCount = sortedData.count { quotaId(it) in ignoredIds }
    Column(Modifier.fillMaxSize()) {
        DataStatus(state)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "显示 ${visibleData.size} 个上游",
                Modifier.weight(1f),
                color = Ink.fgSoft,
                fontSize = 14.sp,
            )
            InkButton(
                if (ignoredCount == 0) "管理上游" else "管理上游 · 已忽略 $ignoredCount",
                { managing = true },
            )
        }
        if (visibleData.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.data.isEmpty()) "暂无可显示的额度" else "所有上游均已忽略",
                    color = Ink.fgSoft,
                    fontSize = 17.sp,
                )
            }
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (maxWidth >= 700.dp) 2 else 1
            val rows = if (maxHeight >= 700.dp) 3 else 2
            val perPage = columns * rows
            val pageCount = ceil(visibleData.size.toDouble() / perPage).toInt().coerceAtLeast(1)
            val safePage = page.coerceIn(0, pageCount - 1)
            val visible = visibleData.drop(safePage * perPage).take(perPage)
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val rowData = visible.chunked(columns)
                    rowData.forEach { rowCards ->
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowCards.forEach { card ->
                                QuotaCardItem(
                                    card = card,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onRefresh = onRefresh,
                                    onHide = { onToggleIgnored(quotaId(card), true) },
                                )
                            }
                            repeat(columns - rowCards.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    repeat(rows - rowData.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (pageCount > 1) {
                    PageControls(page = safePage, pageCount = pageCount, onPage = { page = it })
                }
            }
        }
    }
}

@Composable
private fun QuotaManager(
    cards: List<QuotaCard>,
    ignoredIds: Set<String>,
    onToggleIgnored: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(0) }
    val perPage = 8
    val pageCount = ceil(cards.size.toDouble() / perPage).toInt().coerceAtLeast(1)
    val safePage = page.coerceIn(0, pageCount - 1)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("管理上游", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            InkButton("完成", onClose)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            cards.drop(safePage * perPage).take(perPage).forEach { card ->
                val id = quotaId(card)
                val ignored = id in ignoredIds
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, Ink.line).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            quotaDisplayTitle(card),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = listOfNotNull(card.from, card.user).joinToString(" · ")
                        if (subtitle.isNotBlank()) {
                            Text(subtitle, fontSize = 13.sp, color = Ink.fgSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    InkButton(if (ignored) "恢复" else "忽略", { onToggleIgnored(id, !ignored) }, selected = ignored)
                }
            }
        }
        if (pageCount > 1) PageControls(safePage, pageCount, { page = it })
    }
}

private fun sortedQuotaCards(cards: List<QuotaCard>): List<QuotaCard> = cards.sortedWith(
    compareBy<QuotaCard>(
        { !it.hasError },
        { card -> windowsShown(card).mapNotNull(::remainingPercent).minOrNull() ?: 101 },
        { quotaDisplayTitle(it).lowercase() },
    )
)

private fun quotaId(card: QuotaCard): String =
    listOf(card.provider, card.name, card.from.orEmpty(), card.user.orEmpty()).joinToString("\u001F")

private fun quotaDisplayTitle(card: QuotaCard): String =
    card.name.ifBlank { card.provider }.ifBlank {
        card.from?.ifBlank { null } ?: card.user?.ifBlank { null } ?: "未命名上游"
    }

fun windowsShown(card: QuotaCard): List<QuotaWindow> {
    val seen = LinkedHashMap<String, QuotaWindow>()
    for (window in card.windows) {
        val key = listOf(
            window.pool ?: window.name,
            window.used,
            window.remaining,
            window.startsAt,
            window.resetsAt,
            window.display,
            window.amount,
            window.limit,
            window.unlimited,
        ).joinToString("\u001F")
        if (key !in seen) seen[key] = window
    }
    return seen.values.toList()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuotaCardItem(
    card: QuotaCard,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onHide: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }
    Box(
        modifier
            .border(1.dp, Ink.fg)
            .background(Ink.bg)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { showActions = true },
            )
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    quotaDisplayTitle(card),
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    color = Ink.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = "刷新 ${quotaDisplayTitle(card)}" }
                        .inkClickable(onClick = onRefresh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↻", fontSize = 24.sp, color = Ink.fg)
                }
                Text(
                    when {
                        card.hasError -> "! 不可达"
                        card.isBalance -> "余额"
                        card.kind == "plan" -> "计划"
                        else -> "订阅"
                    },
                    fontSize = 13.sp,
                    fontWeight = if (card.hasError) FontWeight.Bold else FontWeight.Normal,
                    color = Ink.fg,
                )
            }
            val subtitle = listOfNotNull(
                card.from,
                card.plan,
                card.user?.takeIf { it.isNotBlank() && !card.name.contains(it) },
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 13.sp, color = Ink.fgSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when {
                card.hasError -> Text(card.error ?: "未知错误", fontSize = 14.sp, color = Ink.fg)
                card.isBalance -> {
                    Text("剩余", fontSize = 13.sp, color = Ink.fgSoft)
                    Text(card.balance ?: "—", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
                    if (card.balanceSplit.isNotEmpty()) {
                        Text(
                            card.balanceSplit.joinToString(" · ") { "${it.label} ${it.text}" },
                            fontSize = 13.sp,
                            color = Ink.fgSoft,
                        )
                    }
                }
                else -> {
                    val windows = windowsShown(card).sortedBy { remainingPercent(it) ?: 101 }
                    if (windows.isEmpty()) Text("暂无额度窗口", fontSize = 14.sp, color = Ink.fgSoft)
                    windows.forEach { WindowRow(it) }
                }
            }
            card.until?.let {
                Text("到期 ${fmtReset(it)}", fontSize = 12.sp, color = Ink.fgSoft)
            }
            card.readAt?.let {
                Text("读取 ${fmtReset(it)}", fontSize = 12.sp, color = Ink.fgSoft)
            }
        }
        if (showActions) {
            androidx.compose.ui.window.Popup(
                onDismissRequest = { showActions = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                Text(
                    "隐藏卡片",
                    Modifier.defaultMinSize(minWidth = 160.dp, minHeight = 48.dp)
                        .background(Ink.bg)
                        .border(1.dp, Ink.fg)
                        .inkClickable {
                            showActions = false
                            onHide()
                        }
                        .padding(16.dp),
                    color = Ink.fg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun WindowRow(window: QuotaWindow) {
    val remainingPct = remainingPercent(window)
    val low = remainingPct != null && remainingPct <= 10
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                window.pool ?: window.name,
                fontSize = 14.sp,
                color = Ink.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text(
                when {
                    window.unlimited == true -> "不限量"
                    remainingPct != null -> "剩 $remainingPct%" + if (low) " 偏低" else ""
                    else -> window.display ?: window.remaining?.let { "剩余 ${formatAmount(it)}" } ?: "—"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Ink.fg,
            )
        }
        if (window.unlimited != true && remainingPct != null) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).background(Ink.barBg).border(1.dp, Ink.fg)) {
                Box(
                    Modifier.fillMaxWidth(remainingPct / 100f).fillMaxHeight().background(Ink.fg)
                )
            }
        }
        val amountDetails = window.amount?.let { amount ->
            window.limit?.let { limit -> "${formatAmount(amount)} / ${formatAmount(limit)}" }
        }
        val details = listOfNotNull(
            window.display?.takeIf { it.isNotBlank() } ?: amountDetails,
            window.startsAt?.let { "开始 ${fmtReset(it)}" },
            window.resetsAt?.let { "重置 ${fmtReset(it)}" },
        ).joinToString(" · ")
        if (details.isNotEmpty()) {
            Text(details, fontSize = 12.sp, color = Ink.fgSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val holdsDetails = buildList {
            window.holds?.let { holds ->
                if (holds.tokens > 0) add("占用 ${formatAmount(holds.tokens)} Token")
                if (holds.cost > 0) add("占用费用 $%.2f".format(holds.cost))
            }
            window.holdsWhy?.takeIf { it.isNotBlank() }?.let { reason ->
                add(if (reason.equals("low", ignoreCase = true)) "额度偏低" else reason)
            }
        }.joinToString(" · ")
        if (holdsDetails.isNotEmpty()) {
            Text(holdsDetails, fontSize = 12.sp, color = Ink.fgSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun remainingPercent(window: QuotaWindow): Int? {
    window.used?.let { used -> return (100 - used).roundToInt().coerceIn(0, 100) }
    window.remaining?.let { remaining ->
        if (remaining in 0.0..100.0) return remaining.roundToInt().coerceIn(0, 100)
    }
    val amount = window.amount
    val limit = window.limit
    if (amount != null && limit != null && limit > 0) {
        return ((limit - amount) / limit * 100).roundToInt().coerceIn(0, 100)
    }
    return null
}

private fun formatAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

private val quotaTimeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")

fun fmtReset(iso: String): String {
    val zone = ZoneId.systemDefault()
    val parsed = runCatching { Instant.parse(iso).atZone(zone) }
        .recoverCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone) }
        .recoverCatching { java.time.LocalDateTime.parse(iso).atZone(zone) }
        .getOrNull() ?: return iso.take(16).replace("T", " ")
    return quotaTimeFormat.format(parsed)
}

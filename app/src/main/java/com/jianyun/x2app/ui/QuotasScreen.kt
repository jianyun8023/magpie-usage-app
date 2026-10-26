package com.jianyun.x2app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianyun.x2app.data.QuotaCard
import com.jianyun.x2app.data.QuotaWindow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun QuotasScreen(
    state: LoadState<List<QuotaCard>>,
    ignoredIds: Set<String>,
    onToggleIgnored: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRefreshCard: (QuotaCard) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DataStatus(state)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state) {
                is LoadState.Loading -> LoadingBox()
                is LoadState.Failed -> ErrorBox(state.message, onRefresh)
                is LoadState.Ready -> QuotasBody(state, ignoredIds, onToggleIgnored, onRefreshCard)
            }
        }
    }
}

@Composable
private fun QuotasBody(
    state: LoadState.Ready<List<QuotaCard>>,
    ignoredIds: Set<String>,
    onToggleIgnored: (String, Boolean) -> Unit,
    onRefreshCard: (QuotaCard) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(0) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    val sortedData = sortedQuotaCards(state.data)
    val opened = sortedData.firstOrNull { quotaId(it) == openedId }
    if (opened != null) {
        BackHandler { openedId = null }
        QuotaDetails(opened) { openedId = null }
        return
    }
    if (managing) {
        BackHandler { managing = false }
        QuotaManager(sortedData, ignoredIds, onToggleIgnored) { managing = false }
        return
    }
    val visibleData = sortedData.filterNot { quotaId(it) in ignoredIds }
    val ignoredCount = sortedData.count { quotaId(it) in ignoredIds }
    Column(Modifier.fillMaxSize()) {
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
            val columns = quotaColumns(maxWidth)
            val rows = quotaRows(maxHeight)
            val perPage = columns * rows
            val pageCount = ceil(visibleData.size.toDouble() / perPage).toInt().coerceAtLeast(1)
            val safePage = page.coerceIn(0, pageCount - 1)
            val visible = visibleData.drop(safePage * perPage).take(perPage)
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(remember(safePage) { ScrollState(0) })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val rowData = visible.chunked(columns)
                    rowData.forEach { rowCards ->
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowCards.forEach { card ->
                                QuotaCardItem(
                                    card = card,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onOpen = { openedId = quotaId(card) },
                                    onHide = { onToggleIgnored(quotaId(card), true) },
                                    onRefresh = { onRefreshCard(card) },
                                    refreshing = state.refreshing,
                                )
                            }
                            repeat(columns - rowCards.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (pageCount > 1) {
                    PageControls(page = safePage, pageCount = pageCount, onPage = { page = it })
                }
            }
        }
    }
}

internal fun quotaColumns(width: Dp): Int = when {
    width >= 1000.dp -> 4
    width >= 720.dp -> 3
    width >= 420.dp -> 2
    else -> 1
}

internal fun quotaRows(height: Dp): Int = when {
    height >= 700.dp -> 3
    height >= 420.dp -> 2
    else -> 1
}

@Composable
private fun QuotaManager(
    cards: List<QuotaCard>,
    ignoredIds: Set<String>,
    onToggleIgnored: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val managerMaxWidth = this@BoxWithConstraints.maxWidth
        val perPage = quotaManagerRows(this@BoxWithConstraints.maxHeight) * quotaManagerColumns(managerMaxWidth)
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
            val columns = quotaManagerColumns(managerMaxWidth)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cards.drop(safePage * perPage).take(perPage).chunked(columns).forEach { rowCards ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowCards.forEach { card ->
                            Box(Modifier.weight(1f)) {
                                ManagedQuotaCard(card, quotaId(card) in ignoredIds) {
                                    onToggleIgnored(quotaId(card), !(quotaId(card) in ignoredIds))
                                }
                            }
                        }
                        repeat(columns - rowCards.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (pageCount > 1) PageControls(safePage, pageCount, { page = it })
        }
    }
}

@Composable
private fun ManagedQuotaCard(
    card: QuotaCard,
    ignored: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, Ink.line).padding(12.dp),
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
        InkButton(if (ignored) "恢复" else "忽略", onToggle, selected = ignored)
    }
}

internal fun quotaManagerColumns(width: Dp): Int = when {
    width >= 900.dp -> 3
    width >= 560.dp -> 2
    else -> 1
}

internal fun quotaManagerRows(height: Dp): Int = when {
    height >= 620.dp -> 8
    height >= 420.dp -> 6
    else -> 4
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
    val seen = LinkedHashMap<List<Any?>, QuotaWindow>()
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
            window.holds,
            window.holdsWhy,
        )
        if (key !in seen) seen[key] = window
    }
    return seen.values.toList()
}

internal fun summaryWindows(card: QuotaCard): List<QuotaWindow> =
    windowsShown(card).sortedBy { if (it.unlimited == true) 101 else remainingPercent(it) ?: 101 }.take(2)

internal fun balanceSplitLabel(label: String): String = when (label.trim().lowercase()) {
    "granted" -> "赠送"
    "topped up", "topped-up" -> "充值"
    else -> label
}

internal fun quotaResetSummaryLines(card: QuotaCard): List<String> {
    if (card.hasError) return emptyList()
    val resets = card.resets ?: return emptyList()
    return buildList {
        if (resets.byWindow) {
            val counts = listOfNotNull(
                resets.fiveHour?.takeIf { it >= 0 }?.let { "5小时重置 $it 次" },
                resets.weekly?.takeIf { it >= 0 }?.let { "每周重置 $it 次" },
            )
            if (counts.isNotEmpty()) add(counts.joinToString(" / "))
        } else {
            resets.count?.takeIf { it >= 0 }?.let { add("可用重置 $it 次") }
        }
        resets.until?.takeIf { it.isNotBlank() }?.let { add("重置额度最早到期 ${fmtReset(it)}") }
    }
}

internal fun quotaResetEachLines(card: QuotaCard): List<String> {
    if (card.hasError) return emptyList()
    return card.resets?.each.orEmpty().mapNotNull { reset ->
        reset.until?.takeIf { it.isNotBlank() }?.let { until ->
            when (reset.window) {
                "fiveHour" -> "5小时重置额度到期 ${fmtReset(until)}"
                "weekly" -> "每周重置额度到期 ${fmtReset(until)}"
                else -> "重置额度到期 ${fmtReset(until)}"
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuotaCardItem(
    card: QuotaCard,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onHide: () -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
) {
    var showActions by remember { mutableStateOf(false) }
    Box(
        modifier
            .border(1.dp, Ink.fg)
            .background(Ink.bg)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "查看完整额度",
                onClick = onOpen,
                onLongClick = { showActions = true },
            )
    ) {
        Column(
            Modifier.fillMaxHeight().padding(12.dp),
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
                if (card.hasError || !card.isBalance) {
                    Text(
                        when {
                            card.hasError -> "! 不可达"
                            card.kind == "plan" -> "计划"
                            else -> "订阅"
                        },
                        fontSize = 13.sp,
                        fontWeight = if (card.hasError) FontWeight.Bold else FontWeight.Normal,
                        color = Ink.fg,
                    )
                }
                Spacer(Modifier.width(8.dp))
                InkButton("刷新", onRefresh, enabled = !refreshing)
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
                card.hasError -> Text(card.error ?: "未知错误", fontSize = 14.sp, color = Ink.fg,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                card.isBalance -> {
                    Text(card.balance ?: "—", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
                    if (card.balanceSplit.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            card.balanceSplit.forEach { split ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        balanceSplitLabel(split.label),
                                        Modifier.weight(1f).padding(end = 8.dp),
                                        fontSize = 13.sp,
                                        color = Ink.fgSoft,
                                    )
                                    Text(
                                        split.text,
                                        Modifier.weight(1f),
                                        fontSize = 13.sp,
                                        color = Ink.fg,
                                        textAlign = TextAlign.End,
                                    )
                                }
                            }
                        }
                    }
                    card.readAt?.takeIf { it.isNotBlank() }?.let {
                        Text("数据时间 ${fmtReset(it)}", fontSize = 12.sp, color = Ink.fgSoft)
                    }
                }
                else -> {
                    val windows = summaryWindows(card)
                    if (windows.isEmpty()) Text("暂无额度窗口", fontSize = 14.sp, color = Ink.fgSoft)
                    windows.forEach { window ->
                        WindowRow(window, summary = true)
                    }
                }
            }
            card.until?.let {
                Text("到期 ${fmtReset(it)}", fontSize = 12.sp, color = Ink.fgSoft)
            }
            val windowCount = windowsShown(card).size
            val lowCount = windowsShown(card).count {
                it.unlimited != true && (remainingPercent(it)?.let { percent -> percent <= 10 } == true)
            }
            if (lowCount > 2) Text("共 $lowCount 个窗口额度偏低", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            quotaResetSummaryLines(card).forEach { line ->
                Text(line, fontSize = 13.sp, color = Ink.fgSoft)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (windowCount > 0) "完整详情 · 共 $windowCount 个额度窗口 ›" else "查看完整额度 ›",
                Modifier.padding(top = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink.fg,
            )
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
private fun QuotaDetails(card: QuotaCard, onClose: () -> Unit) {
    var diagnosticsExpanded by rememberSaveable(quotaId(card)) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Ink.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InkButton("返回", onClose)
            Text(quotaDisplayTitle(card), Modifier.weight(1f), fontSize = 20.sp,
                fontWeight = FontWeight.Bold, color = Ink.fg)
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val overview = listOfNotNull(card.from, card.plan, card.user)
                        .filter { it.isNotBlank() && it != quotaDisplayTitle(card) }.distinct()
                    overview.forEach { Text(it, fontSize = 16.sp, color = Ink.fgSoft) }
                    card.error?.let {
                        Text(it, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
                    }
                    if (card.isBalance || card.balance != null) {
                        Text(card.balance ?: "—", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
                    }
                    card.balanceSplit.forEach {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                balanceSplitLabel(it.label),
                                Modifier.weight(1f).padding(end = 8.dp),
                                fontSize = 16.sp,
                                color = Ink.fgSoft,
                            )
                            Text(
                                it.text,
                                Modifier.weight(1f),
                                fontSize = 16.sp,
                                color = Ink.fg,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                    card.until?.let { Text("到期 ${fmtReset(it)}", fontSize = 14.sp, color = Ink.fgSoft) }
                    quotaResetSummaryLines(card).forEach { line ->
                        Text(line, fontSize = 14.sp, color = Ink.fgSoft)
                    }
                    quotaResetEachLines(card).forEach { line ->
                        Text(line, fontSize = 14.sp, color = Ink.fgSoft)
                    }
                    card.readAt?.takeIf { it.isNotBlank() }?.let {
                        Text("数据时间 ${fmtReset(it)}", fontSize = 14.sp, color = Ink.fgSoft)
                    }
                }
                val windows = windowsShown(card).sortedBy {
                    if (it.unlimited == true) 101 else remainingPercent(it) ?: 101
                }
                if (windows.isEmpty() && !card.isBalance) {
                    Text("暂无额度窗口", fontSize = 16.sp, color = Ink.fgSoft)
                }
                windows.forEach { window ->
                    Column(Modifier.fillMaxWidth().border(1.dp, Ink.line).padding(16.dp)) {
                        WindowRow(window)
                    }
                }
                InkButton(
                    if (diagnosticsExpanded) "收起原始数据" else "原始数据",
                    { diagnosticsExpanded = !diagnosticsExpanded },
                )
                if (diagnosticsExpanded) {
                    Text(quotaDetailText(card), fontSize = 14.sp, lineHeight = 22.sp, color = Ink.fgSoft)
                }
            }
        }
    }
}

// Keep original windows and timestamps here: aliases and precision can matter when copying details.
internal fun quotaDetailText(card: QuotaCard): String = buildList {
    add(quotaDisplayTitle(card))
    add("提供方：${card.provider}")
    add("名称：${card.name}")
    card.icon?.let { add("图标：$it") }
    card.kind?.let { add("类型：$it") }
    card.from?.let { add("来源：$it") }
    card.plan?.let { add("计划：$it") }
    card.user?.let { add("账户：$it") }
    card.error?.let { add("错误：$it") }
    card.balance?.let { add("余额：$it") }
    card.balanceSplit.forEach { add("${it.label}：${it.text}") }
    card.until?.let { add("到期：$it") }
    card.readAt?.let { add("读取：$it") }
    if (card.windows.isEmpty() && !card.isBalance) add("暂无额度窗口")
    card.windows.forEach { window ->
        add("")
        add("名称：${window.name}")
        window.pool?.let { add("额度池：$it") }
        window.unlimited?.let { add("不限量：${if (it) "是" else "否"}") }
        if (window.unlimited == true) add("不限量")
        else remainingPercent(window)?.let { add("剩余 $it%" + if (it <= 10) " · 额度偏低" else "") }
        window.display?.let { add(it) }
        window.used?.let { add("已用比例：$it%") }
        window.remaining?.let { add("剩余额度：$it") }
        window.amount?.let { add("已用额度：$it") }
        window.limit?.let { add("额度上限：$it") }
        window.startsAt?.let { add("开始：$it") }
        window.resetsAt?.let { add("重置：$it") }
        window.holds?.let {
            add("整窗容量估算 Token：${it.tokens}")
            add("整窗 API 标价估算原值：${it.cost}")
            add("API 标价估算可用：${if (it.priced) "是" else "否"}")
            add("估算依据已用比例：${it.used}%")
        }
        window.holdsWhy?.let { add("无法估算整窗容量：${capacityEstimateReason(it)}（$it）") }
    }
}.joinToString("\n")

@Composable
fun WindowRow(window: QuotaWindow, summary: Boolean = false) {
    val remainingPct = remainingPercent(window)
    val low = remainingPct != null && remainingPct <= 10
    val title = window.pool?.takeIf { it.isNotBlank() } ?: window.name.ifBlank { "未命名窗口" }
    val value = when {
        window.unlimited == true -> "不限量"
        remainingPct != null -> "剩 $remainingPct%" + if (low) " 偏低" else ""
        else -> window.remaining?.let { "剩余 ${formatAmount(it)}" } ?: window.display ?: "—"
    }
    Column(Modifier.fillMaxWidth()) {
        if (!summary) {
            Text(title, fontSize = 16.sp, color = Ink.fg, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 14.sp,
                    color = Ink.fg,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text(
                    value,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink.fg,
                )
            }
        }
        if (window.unlimited != true && remainingPct != null) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).background(Ink.barBg).border(1.dp, Ink.fg)) {
                Box(
                    Modifier.fillMaxWidth(remainingPct / 100f).fillMaxHeight().background(Ink.fg)
                )
            }
        }
        if (summary) {
            window.resetsAt?.let {
                Text("重置 ${fmtReset(it)}", fontSize = 12.sp, color = Ink.fgSoft)
            }
            return@Column
        }
        val details = (windowAmountDetails(window).filter { it != value } + listOfNotNull(
            window.startsAt?.let { "开始 ${fmtReset(it)}" },
            window.resetsAt?.let { "重置 ${fmtReset(it)}" },
        )).distinct().joinToString("\n")
        if (details.isNotEmpty()) {
            Text(details, fontSize = 14.sp, color = Ink.fgSoft)
        }
        val holdsDetails = capacityEstimateText(window)
        if (holdsDetails.isNotEmpty()) {
            Text(holdsDetails, fontSize = 14.sp, color = Ink.fgSoft)
        }
    }
}

internal fun windowAmountDetails(window: QuotaWindow): List<String> {
    val amountDetails = when {
        window.amount != null && window.limit != null -> "已用 ${formatAmount(window.amount)} / ${formatAmount(window.limit)}"
        window.amount != null -> "已用 ${formatAmount(window.amount)}"
        window.limit != null -> "上限 ${formatAmount(window.limit)}"
        else -> null
    }
    val display = window.display?.takeIf { it.isNotBlank() }
    val pair = display?.split('/')?.map { it.trim().replace(",", "").toDoubleOrNull() }
    val repeatsAmount = window.amount != null && window.limit != null &&
        pair?.size == 2 && pair[0] == window.amount && pair[1] == window.limit
    return listOfNotNull(display?.takeUnless { repeatsAmount }, amountDetails).distinct()
}

// provider/window_holds.go: holds estimates the whole allowance; low means <5% used.
internal fun capacityEstimateReason(reason: String): String = when (reason.lowercase()) {
    "low" -> "估算样本不足（已用比例不足 5%）"
    "clock" -> "窗口起止时间未知"
    "log" -> "请求日志未覆盖整个窗口"
    "none" -> "窗口内暂无经 Magpie 转发的调用"
    "credits" -> "部分调用可能使用额外余额支付"
    else -> reason
}

internal fun capacityEstimateText(window: QuotaWindow): String {
    window.holds?.let { holds ->
        return "整窗约 ${formatAmount(holds.tokens)} Token" +
            if (holds.priced) " · API 标价约 $${formatAmount(holds.cost)}" else ""
    }
    return window.holdsWhy?.takeIf { it.isNotBlank() && it != "-" }?.let {
        "整窗容量暂无法估算 · ${capacityEstimateReason(it)}"
    }.orEmpty()
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
    if (value % 1.0 == 0.0) String.format(java.util.Locale.US, "%,.0f", value)
    else String.format(java.util.Locale.US, "%,.2f", value)

private val quotaTimeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")

fun fmtReset(iso: String): String {
    val zone = ZoneId.systemDefault()
    val parsed = runCatching { Instant.parse(iso).atZone(zone) }
        .recoverCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone) }
        .recoverCatching { java.time.LocalDateTime.parse(iso).atZone(zone) }
        .getOrNull() ?: return iso.take(16).replace("T", " ")
    return quotaTimeFormat.format(parsed)
}

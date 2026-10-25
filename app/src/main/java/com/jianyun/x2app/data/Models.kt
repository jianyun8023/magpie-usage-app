package com.jianyun.x2app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// GET /api/usage/quotas —— 额度卡片
@Serializable
data class QuotaCard(
    val provider: String,
    val name: String = "",
    val icon: String? = null,
    val kind: String? = null,
    val plan: String? = null,
    val user: String? = null,
    val from: String? = null,
    val windows: List<QuotaWindow> = emptyList(),
    val balance: String? = null,
    @SerialName("balanceSplit") val balanceSplit: List<BalanceSplit> = emptyList(),
    val error: String? = null,
    @SerialName("readAt") val readAt: String? = null,
    val until: String? = null,
) {
    val isBalance: Boolean get() = kind == "balance"
    val hasError: Boolean get() = error != null
}

@Serializable
data class QuotaWindow(
    val name: String = "",
    val used: Double? = null,
    val remaining: Double? = null,
    @SerialName("resetsAt") val resetsAt: String? = null,
    @SerialName("startsAt") val startsAt: String? = null,
    val display: String? = null,
    val amount: Double? = null,
    val limit: Double? = null,
    val pool: String? = null,
    val unlimited: Boolean? = null,
    val holds: Holds? = null,
    @SerialName("holdsWhy") val holdsWhy: String? = null,
) {
    val usedPct: Int? get() = used?.toInt()?.coerceIn(0, 100)
}

@Serializable
data class Holds(
    val tokens: Double = 0.0,
    val cost: Double = 0.0,
    val priced: Boolean = false,
    val used: Double = 0.0,
)

@Serializable
data class BalanceSplit(
    val label: String = "",
    val text: String = "",
)

// GET /api/usage/requests —— 请求流水
@Serializable
data class Ledger(
    val rows: List<RequestRow> = emptyList(),
    val total: Int = 0,
    val period: String = "30d",
    val agents: List<Facet> = emptyList(),
    val providers: List<Facet> = emptyList(),
    val models: List<Facet> = emptyList(),
) {
    @Serializable
    data class Facet(val id: String, val name: String = "", val icon: String? = null)
}

@Serializable
data class RequestRow(
    @SerialName("route_id") val routeId: Long = 0,
    val t: String = "",
    val agent: String = "",
    @SerialName("agentName") val agentName: String? = null,
    val provider: String = "",
    @SerialName("providerName") val providerName: String? = null,
    val host: String? = null,
    @SerialName("providerAccount") val providerAccount: String? = null,
    val model: String = "",
    val req: String = "",
    val served: String? = null,
    val `in`: Long = 0,
    val out: Long = 0,
    @SerialName("cache_read") val cacheRead: Long = 0,
    @SerialName("cache_write") val cacheWrite: Long = 0,
    val reasoning: Long? = null,
    val effort: String? = null,
    val ms: Long = 0,
    @SerialName("ttft_ms") val ttftMs: Long? = null,
    val status: Int = 0,
    val err: String? = null,
    val cost: Double? = null,
    val priced: Boolean? = null,
    val ep: String? = null,
    val session: String? = null,
    val access: String? = null,
    val source: String? = null,
    val tries: Int? = null,
    @SerialName("handed_on") val handedOn: Boolean = false,
) {
    val failed: Boolean get() = status >= 400 || !err.isNullOrEmpty()
    val ok: Boolean get() = status in 200..299 && !failed
}

// GET /api/usage —— 用量汇总
@Serializable
data class UsageSummary(
    val period: String = "",
    val since: String? = null,
    val calls: Long = 0,
    val errors: Long = 0,
    val retried: Long = 0,
    val input: Long = 0,
    val output: Long = 0,
    @SerialName("cache_read") val cacheRead: Long = 0,
    @SerialName("cache_write") val cacheWrite: Long = 0,
    val reasoning: Long = 0,
    val cost: Double = 0.0,
    val unpriced: Long = 0,
    val agents: List<UsageGroup> = emptyList(),
    val models: List<UsageGroup> = emptyList(),
    val accounts: List<UsageGroup> = emptyList(),
    val series: List<DayPoint> = emptyList(),
) {
    @Serializable
    data class UsageGroup(
        val id: String = "",
        val name: String? = null,
        val sub: String? = null,
        val provider: String? = null,
        val model: String? = null,
        val host: String? = null,
        val calls: Long = 0,
        val errors: Long = 0,
        val input: Long = 0,
        val output: Long = 0,
        @SerialName("cache_read") val cacheRead: Long = 0,
        val cost: Double = 0.0,
    )

    @Serializable
    data class DayPoint(
        val label: String = "",
        val time: String = "",
        val calls: Long = 0,
        val errors: Long = 0,
        val input: Long = 0,
        val output: Long = 0,
        val cost: Double = 0.0,
    )
}

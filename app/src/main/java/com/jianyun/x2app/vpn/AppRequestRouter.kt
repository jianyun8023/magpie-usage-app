package com.jianyun.x2app.vpn

import com.jianyun.x2app.api.HttpResponse
import com.jianyun.x2app.api.HttpTransport

enum class AppNetworkMode(val label: String) {
    DIRECT("直连"), WIREGUARD("WireGuard"), TAILSCALE("Tailscale"), UNAVAILABLE("网络配置不可用");

    companion object {
        fun restored(value: String?): AppNetworkMode =
            if (value == null) DIRECT else entries.firstOrNull { it.name == value } ?: UNAVAILABLE
    }
}

internal data class AppRoute(
    val mode: AppNetworkMode,
    val ready: Boolean,
    val revision: Long = 0,
    val nativeSessionID: Long = 0,
)

/** A disconnected selection is still a selection: never retry an internal request over direct HTTP. */
internal class AppRequestRouter(
    initialMode: AppNetworkMode,
    private val direct: HttpTransport,
    private val internalGet: suspend (AppRoute, String, String) -> HttpResponse,
) : HttpTransport {
    @Volatile var route = AppRoute(initialMode, initialMode == AppNetworkMode.DIRECT)
        private set

    /** A null session id preserves it for readiness changes within the same native session. */
    @Synchronized fun update(mode: AppNetworkMode, ready: Boolean, nativeSessionID: Long? = null) {
        val session = nativeSessionID ?: if (route.mode == mode) route.nativeSessionID else 0
        if (route.mode != mode || route.ready != ready || route.nativeSessionID != session) {
            route = AppRoute(mode, ready, route.revision + 1, session)
        }
    }

    fun requireCurrent(expected: AppRoute) {
        check(route == expected) { "应用内网络已切换，请刷新重试" }
        check(expected.ready) { "${expected.mode.label} 未连接；请求已阻止，请连接或明确选择直连" }
    }

    override suspend fun get(url: String, cookie: String): HttpResponse {
        val selected = route
        requireCurrent(selected)
        val response = if (selected.mode == AppNetworkMode.DIRECT) direct.get(url, cookie)
            else internalGet(selected, url, cookie)
        requireCurrent(selected)
        return response
    }
}

# Tailscale Android 引擎接入契约

实现基于官方 tailscale-android 稳定版 `1.104.2-tc3430f0d2-gb01fa89d5` 的 libtailscale；不调用外部 Tailscale App。来源许可证见同目录 THIRD_PARTY_NOTICE.txt，打包时还需包含 native 构建产物的完整第三方许可证。

## Kotlin API

```kotlin
val tailscale = TailscaleController.get(context)
val state: StateFlow<TailscaleState> = tailscale.state
val authorization: Intent? = tailscale.prepareVpn()
// authorization 非空：ActivityResult 启动系统 VPN 授权；成功后重新检查 prepareVpn()。
val result: Result<Unit> = tailscale.login() // 已授权、前台用户操作中调用
// 或 tailscale.connect()：恢复已保存的会话
// tailscale.refresh()：读取引擎状态，首次调用初始化引擎
// tailscale.disconnect()：等待 native TUN 关闭且 Android Service 销毁
// tailscale.logout()：先断开，再调用官方 LocalAPI logout
```

所有 suspend 命令在 IO 线程执行并串行化，返回命令提交结果；登录、连接的最终结果必须观察 `state`。状态字段：`backendState`、`vpnActive`、`busy`、`authUrl`、`addresses`、`error`、`health`；`connected` 仅在后端 Running 且 VPN 建立时为真。`authUrl` 只接收 HTTPS URL，由 UI 使用系统浏览器/Custom Tabs 授权，保留重新打开入口；浏览器返回不能标记已登录。首次启动/回到页面可 `refresh()`；不要对 authUrl 轮询或写入磁盘。

自定义控制服务器扩展：`state.controlUrl` 默认 `https://controlplane.tailscale.com`；`suspend setControlUrl(value: String): Result<Unit>` 接收 HTTP/HTTPS 根地址，空白恢复官方。校验拒绝 credentials、路径、query、fragment 和无效端口。实际变更先 disconnect、官方 logout，再 PATCH `ControlURL`+`ControlURLSet=true` 并核对返回值，最后在现有 Keystore 加密/no-backup 存储保存选择。错误不得显示已保存；切换会退出旧服务器身份。同值保存无副作用。login 自动携带此配置，不需 UI 再传控制地址；connect 和系统恢复也校验控制服务器一致性。

上述 HTTPS 授权规则仅有一个显式例外：用户选择 HTTP 控制服务器时，额外允许完全同源（scheme/hostname/有效port 相等）的 HTTP 授权 URL；其他 HTTP 一律拒绝。例如配置 `http://headscale.example.test:8080` 仅允许该 origin 的 HTTP 注册地址，不能接受其他端口或相似域名。UI 的“继续登录”校验须接受 controller 已验证的该地址，不能自己全局放开 HTTP，也不要重复只允许 HTTPS 而误拦 Headscale。取消/logout会关闭授权意图门控，后续 refresh/status/notification 不会恢复后端旧AuthURL；只有新的 login/connect 重新打开门控。

连接命令不等待用户在浏览器完成认证，因此返回成功后 busy 会结束，但状态仍可能 NeedsLogin/NeedsMachineAuth/Starting。UI 必须按后端状态显示。应用内取消授权流程可调用 disconnect()。

`state.serviceActive` 表示当前真实 Android VPN Service 已附着，只有 attached/detached 生命周期更新。后端 Running 或已有设备 IP 不代表服务存在；force-stop 后 refresh 可恢复这些会话信息，但不自启服务。无 service 且无 vpnActive 时 UI 应提供“连接”，通过统一 TS_CONNECT 互斥恢复；有 service 正在建立连接时提供“断开”。connected 保留 Running&&vpnActive，detach 同时清除 serviceActive/vpnActive。

登录期间服务仅保持前台；只有真实后端状态进入 Starting/Running，才通过异步状态通知注册 requestVPN。NeedsLogin/NeedsMachineAuth 不建立 TUN。通知回调把工作交给 IO scope 并获取命令锁，避免同步重入 native；延迟工作会重新检查当前状态、服务归属和 closing。授权 URL 不代表旧 VPN 错误已经恢复，不会因此清除错误。

TailscaleLoginSmokeTest 的安全诊断只输出固定错误分类/白名单短语、后端状态、VPN标志、授权URL是否存在、IP数量；未识别的消息整体隐去，不打印URL、地址、token、key或异常堆栈。等待期间记录状态转换，首次error不会立即结束观察，但最终仍要求无未解决错误；无URL则超时失败。需显式 tailscaleLoginSmoke=true、新进程、系统VPN已授权且无TS账号。测试只获取授权地址，不打开网页或完成账号授权。

在 WG→Tailscale 切换前等待 WG 真实断开；Tailscale→WG 必须等待 `disconnect().isSuccess`。不要在 UI 直接启动此 Service 或自行维护一份“已连接”状态。授权必须在 login/connect 之前完成，以满足 Android 14 的 VPN 前台服务条件。

冷启动时 disconnect() 也会初始化官方引擎、加载持久会话并通过 LocalAPI 写入 WantRunning=false，检查返回的 Prefs 确认停止意图，再等待已附着服务销毁。初始化、写入或关闭失败均返回失败，集成方不能继续启动 WG。restore() 与 disconnect() 使用同一命令锁；稍后排队到达的 START_STICKY 恢复会重新读取 false 并关闭服务，不请求 VPN。授权撤销同样清除持久运行意图。

## 集成人负责的声明

```kotlin
implementation(files("libs/libtailscale.aar"))
```

现有 coroutines、serialization 和 core-ktx 足够。无需 Jetpack Security、外部 Tailscale 客户端、额外 HTTP 客户端。

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".tailscale.TailscaleVpnService"
    android:exported="false"
    android:foregroundServiceType="systemExempted"
    android:permission="android.permission.BIND_VPN_SERVICE">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
    <!-- 同包存在 WG service，未实现由系统选择协议的持久化策略，暂不宣称 always-on。 -->
    <meta-data android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON" android:value="false" />
</service>
```

Android 13+ 通知权限可在统一网络入口请求。用户拒绝通知权限不应被解释为 VPN 授权拒绝。保留通知的断开操作。

会话以 Android Keystore AES-GCM 加密，存入 `noBackupFilesDir/tailscale-state.enc`；native 数据目录为 `noBackupFilesDir/tailscale/`，均自动排除 Android Backup。解密失败会报错，绝不静默重新生成身份。进程重启读取原身份，连接后被系统回收的 START_STICKY 服务依据保存的 WantRunning 恢复。未实现开机自动连接或 always-on 协议选择；切换入口应保持这两个服务的互斥。

Go 持有 detach 后的 TUN FD；Kotlin 不重复关闭。关闭时连续两次官方 serviceDisconnect：该稳定版使用无缓冲 channel，第二次发送作为第一次 CloseTUNs 已完成的屏障。升级 native 版本需重新核对 `backend.go` 此行为。Android 11 route exclusion 使用官方 native 计算后的包含路由；API 33+ 才使用 Builder.excludeRoute。

NewBuilder 桥接没有 Go error 返回，因此只创建适配对象；Android Builder 懒初始化到带 error 返回的方法，closing 检查位于 Establish。login/connect 从提交服务启动到引擎命令完成均在取消清理范围内。每个显式 START 带请求代次，取消先使代次失效，再在 NonCancellable 中清除 WantRunning、主线程 stopService、等待已附着服务关闭。晚到的失效 START 不附着、不恢复；如果同一实例已由更新命令接管，只忽略旧 START。旧实例的销毁/状态通知不覆盖新实例状态。

上述竞态需设备回归（尚未执行）：
1. 在 shutdown 的 closing=true 后暂停 Go disconnect 消费，触发一次待处理路由更新；NewBuilder 必须返回对象，Establish 返回错误，进程不崩溃且最终 TUN 关闭。
2. 在 startForegroundService 已提交、Main→IO 返回前取消 login/connect 的 Job；恢复调度，确认通知消失、service 不驻留、WantRunning=false。
3. 在等待 attached 时取消/制造等待超时，然后放行延迟 onStartCommand；失效 START 必须停止且返回 START_NOT_STICKY，不调用 requestVPN。
4. 上述取消完成后立即重新连接，再投递旧代次 START；新连接及通知必须保留，旧请求不得关闭新服务。
5. 在当前服务刚 attached、初始化或 LocalAPI 操作期间取消；确认取消异常仍传给调用方，清理不随调用方取消而中断，随后切 WG 不被抢占。

## 验证

- 已使用 native 构建 worker 的真实 gomobile Java 输出（只读）检查接口；AAR 完成后再次用其 classes.jar + Android 34 SDK + Kotlin 1.9.24 编译全部适配 Kotlin 与状态测试；8 项 JUnit 状态测试通过。
- 没有运行 Gradle/ADB。新增 Android instrumentation `TailscaleStoreTest` 在独立 no-backup 测试目录验证加密、重读和删除；尚未运行。
- 需主任务在 AAR 完成后统一 assemble/test/lint，检查 APK 的目标 ABI 与 native 打包。
- Android 11 真机：拒绝/允许 VPN 授权；系统浏览器登录/取消/再次打开；账号审批等待；Running+TUN 后 IP；Tailnet IP 和 MagicDNS 访问 Magpie；失败时不得显示已连接。
- 真机网络：Wi-Fi↔蜂窝、断网恢复、DNS/网关变化、后台/锁屏和进程回收后恢复。
- 真机互斥：WG↔Tailscale 多次切换、系统撤销授权；停止返回后旧 TUN 必须释放，且旧引擎不得重新抢占。
- 冷启动竞态：先连接 TS 并保留 WantRunning=true，结束进程后直接进入 WG 切换路径，确认 disconnect 成功且随后 TS 的 START_STICKY 恢复不会抢占 WG；再重启确认 TS 仍处于停止状态。
- 真机退出：退出后重启仍需登录，不残留旧 IP；Keystore 损坏明确报错。Android 14+ 另验 foreground service 类型、通知拒绝与断开按钮。

没有真实账号授权，尚不能宣称登录、数据面连通或重启会话恢复已在设备跑通。

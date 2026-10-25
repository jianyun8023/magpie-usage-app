本次更新直接接入 Magpie 模型网关，并修复额度卡片展示和刷新。

- 用量、请求流水和额度改用 `/v1/magpie/...` 接口及网关 API Key，无需另开 `magpie web`。
- 恢复每张额度卡片的「刷新」按钮，按上游与账号单独刷新；顶部「刷新」更新全部额度。
- 修复 DeepSeek 返回值缺少类型时被误判为订阅的问题，显示总余额、赠送和充值明细。
- 直连、WireGuard、Tailscale 均支持 Bearer 鉴权和 POST 刷新；网络切换或断开后不会回落直连。
- 改善 Tailscale 子网访问、自动恢复连接，以及页面请求状态恢复。

升级后请在「服务器设置」中改填 Magpie 模型网关地址和网关 API Key，例如 `http://192.0.2.10:3425`，并开启服务端局域网共享。原来的 Web Key 不能作为网关 API Key 使用。受预算、模型或账号限制的 Key，数据读取范围由服务端权限决定。

支持 Android 11 及以上 ARM 设备（arm64-v8a、armeabi-v7a）。沿用 v0.1.0 发布签名，可直接覆盖升级，保留原有配置。

已知限制：网关 API Key 仍存于普通 SharedPreferences，未排除系统备份。HTTP 访问请通过可信网络、HTTPS 或 VPN。详细使用说明与第三方声明见 README。

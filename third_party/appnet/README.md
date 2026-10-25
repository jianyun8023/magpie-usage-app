# appnet 原生网络引擎

`app/libs/libappnet.aar` 提供 x2-app 进程内 HTTP 网络引擎，不创建系统 VPN/TUN，不监听 HTTP/SOCKS 代理，也不修改全局路由。应用 HTTP 只通过用户态 WireGuard 或 tsnet netstack。

## 固定来源

- Go：`go1.27.3`（Tailscale 官方 build，revision `7ef7b403648ac3a9481a6e88e8b894eff50b0167`）
- Tailscale：`tailscale.com v1.104.2`
- WireGuard 独立引擎：`golang.zx2c4.com/wireguard v0.0.0-20260522210424-ecfc5a8d5446`
- Tailscale 内部 wireguard-go：`github.com/tailscale/wireguard-go v0.0.0-20260928213032-417aef361226`
- gomobile/gobind：`golang.org/x/mobile v0.0.0-20240806205939-81131f6468ab`
- Android NDK：r23b / `23.1.7779620`
- ABI：`arm64-v8a`、`armeabi-v7a`；native API 26
- 构建标签：`ts_omit_logtail`（禁用 Tailscale 日志上传）
- Tailscale core commit：`e26db7a5797683ec99ad1516c40b628c7f2dfd71`（`tailscale.com v1.104.2` 模块缓存 Origin.Hash，不是 Android 壳仓库 commit）
- 版本 stamp：脚本读取 x2-app 当前 git HEAD，注入无 `v` 前缀的 `1.104.2-t<core9>-g<app9>` 与 `1.104.2`，以及 core/app 完整 commit，满足 Tailscale `tailscale_go` + Android 的 version checker 要求
- 链接参数包含 `-Wl,-z,max-page-size=16384`，并校验两个 ABI 的 ELF LOAD 段均满足 16 KB 页对齐

实际依赖版本以 `native/appnet/go.mod` 和 `go.sum` 为准，审计副本见 `upstream.go.mod`、`upstream.go.sum`。`MODULES.txt` 是在 Android arm64/arm + `ts_omit_logtail` 下用 `go list -deps` 得到的实际链接模块与固定版本；`THIRD_PARTY_NOTICES.txt` 汇总这些模块根目录 LICENSE/COPYING/NOTICE/PATENTS 及 Go runtime 许可证。

## strict userspace dial 补丁

- `patches/tailscale-logsdir-logtail.patch`：当 `ts_omit_logtail` 禁用日志上传时，跳过 `ipnlocal` 中 sockstat logger 初始化，避免无条件求值 `logpolicy.LogsDir` 在 Android 上 panic。日志功能启用时保持上游行为；patch 对 `v1.104.2` 源码用 `patch -p3` 应用。
- `patches/tailscale-strict-dial.patch`：只新增 strict userspace dial API，不修改上游 `UserDial` 行为：

- `tsnet.Server.DialStrict`：等待 tsnet Running 后调用 strict dial；
- `tsdial.Dialer.StrictUserDial`：只接受 Tailnet IP/MagicDNS 可解析地址，TCP/UDP 均强制调用 `NetstackDialTCP/UDP`。

未知域名在 `errUnresolved` 时立即失败，LAN IP 也必须经 netstack 判定；两者都不会回落系统 DNS、系统 socket 或默认直连。控制面原有 DNS/HTTP 出站保持 `UserDial` 路径，不影响 DERP 与 Headscale 连接。该补丁在临时复制的 Tailscale 源码上以 `patch -p3` 应用，缓存中的模块源码保持只读。

## 重建

先准备 `/private/tmp/x2-tailscale-tools` 中的 Go、NDK、模块缓存，并安装固定 gomobile/gobind 到 `TOOLS/bin`（见 `scripts/build-appnet.sh` 的提示）。然后在仓库根目录执行：

```sh
./scripts/build-appnet.sh
```

脚本复制 `native/appnet` 与固定 Tailscale 源码到临时目录，只在临时模块里加入 `replace` 指令，完成 gomobile bind、剥离调试信息、检查 16 KB ELF 对齐，并生成 `third_party/appnet/SHA256SUMS`（路径相对仓库根目录）。仓库模块的 `go.mod/go.sum` 不包含本地 replace；`vendor/` 由 `.gitignore` 排除，不交付。未剥离 AAR 保留在工具目录 `libappnet_unstripped.aar`。

`JNI-API.txt` 是对最终 AAR `classes.jar` 执行 `javap` 得到的接口记录。Go `int` 经 gobind 映射为 Java `long`。当前 SHA-256 见 [SHA256SUMS](SHA256SUMS)，包含两个 ARM ABI。

## 状态存储兼容

tsnet 状态继续使用旧官方引擎的 `statestore-<key>` 键名和 `base64.RawStdEncoding` 编码，已存在的 Headscale/Tailscale 身份可直接迁移，Logout 才会清除身份。`native/appnet/store_test.go` 覆盖读写、删除和损坏状态 fail-closed。

## Android 接口桥

Android SDK 30 起禁止应用 UID 调用 `net.Interfaces`，tsnet 需要通过
`netmon.RegisterInterfaceGetter` 从 Java 获取实时接口状态。AAR 导出：

```java
Appnet.setInterfaceProvider(InterfaceProvider provider)
```

`provider.InterfacesJSON()` 必须在每次调用时重新枚举 `NetworkInterface`，不能返回缓存快照。JSON 兼容旧官方 `getInterfacesAsJson` 结构：

```json
[{"name":"wlan0","index":3,"mtu":1500,"up":true,"broadcast":true,"loopback":false,"pointToPoint":false,"multicast":true,"addrs":[{"ip":"192.0.2.10","prefixLen":24}]}]
```

Native 侧逐次回调 provider、解析 JSON、构造 `netmon.Interface{Interface, AltAddrs}`；IPv6 地址允许携带接口 zone（例如 `fe80::1%wlan0`），native 侧会解析后去除 zone；prefix 长度按 IPv4/IPv6 位宽校验。接口只能安装一次，需在创建首个 Client 前完成，重复安装会返回错误。`native/appnet/interfaces_test.go` 覆盖合法结构、非法 JSON/IP/zone/prefix 与重复安装。

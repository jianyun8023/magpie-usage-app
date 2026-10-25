# Magpie 用量

面向 Android 墨水屏设备的 Magpie 客户端，查看额度、请求流水和用量汇总。使用 Kotlin / Jetpack Compose 构建，采用黑白界面，内置 WireGuard 与 Tailscale 网络接入，可连接自建 Headscale。

[项目介绍与演示](https://jianyun8023.github.io/magpie-usage-app/) · [GitHub 仓库](https://github.com/jianyun8023/magpie-usage-app)

![真实 App 界面演示，使用演示数据](docs/assets/overview.gif)

## 功能

- **额度**：查看上游额度与重置窗口，展开完整详情，手动刷新，隐藏不关心的上游。
- **流水**：按时间、Agent、上游及失败状态筛选，分页或跳页，查看请求详情。
- **用量**：查看今天、7 天、30 天的调用、费用与 Token 汇总，以及小时、每日和分组明细；按设备时区请求数据。
- **网络**：导入 WireGuard `.conf`，或登录 Tailscale / Headscale，连接、断开并查看状态。

| 额度 | 流水 | 用量 |
| --- | --- | --- |
| ![额度页面](docs/assets/quotas.png) | ![请求流水页面](docs/assets/ledger.png) | ![用量汇总页面](docs/assets/usage.png) |

图片展示真实 App 界面，使用演示数据与打码示例账号，非真实账户及历史记录。GIF 按额度、流水、用量轮播展示截图。

## 使用

需要 **Android 11（API 30）或以上的 ARM 设备**，支持 `arm64-v8a`、`armeabi-v7a`。当前构建不包含 x86 / x86_64 原生库。

在运行 Magpie 的机器上设置 Web Key 并启动可供设备访问的 Web 服务：

```sh
export MAGPIE_WEB_KEY='替换为你自己的随机密钥'
magpie web --lan
```

在 App 的「服务器设置」中填写地址和相同的 `MAGPIE_WEB_KEY`，先「测试连接」，再「保存」。例如 `http://192.0.2.10:3430` 或 `http://magpie.example.com:3430`；这些是示例地址，请替换为实际可达地址。端口以服务启动信息为准，当前建议显式使用 `3430` 等非默认端口。

客户端访问 `/api/usage`、`/api/usage/quotas` 和 `/api/usage/requests`。服务端须提供这些接口；出现 404 时检查地址和 Magpie 版本。Web Key 是服务访问凭据，不是模型提供商的 API Key。HTTP 不加密传输，远程访问应使用受信任的 HTTPS 或 VPN。

### 内置 VPN

在「服务器设置 → 网络接入」中选择一种方式，并接受 Android 的 VPN 授权：

- **WireGuard**：导入 `.conf` 后连接；访问范围由配置中的 `AllowedIPs` 等路由设置决定。
- **Tailscale**：控制服务器留空使用官方服务；连接 Headscale 时填写其控制服务器 URL，保存并按授权页面完成登录。更换控制服务器会断开并退出当前账号。

WireGuard 与 Tailscale 互斥，切换时会先断开另一种。内置引擎不需要另装对应 VPN 客户端，但仍需可用的服务端和授权。连接状态不等于 Magpie 服务可达，连接后仍需测试服务器地址。

## 构建与测试

推荐使用 JDK 17 或 21，并准备 Android SDK Platform 34、Build Tools 34.0.0 和 Platform Tools。项目使用 Gradle 8.7；通过 `ANDROID_HOME` 或未纳入版本控制的 `local.properties` 指定 SDK 目录。

```sh
git clone https://github.com/jianyun8023/magpie-usage-app.git
cd magpie-usage-app
./gradlew :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。连接开启 USB 调试的 ARM 设备后可安装：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

单元测试与静态检查：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

在已连接设备上运行配置重建回归测试：

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.jianyun.x2app.data.SettingsStoreRecreationTest
```

该测试使用本机回环 HTTP 服务，真实重建 Activity，确认保留的 ViewModel 使用更新后的地址和 Cookie，并在结束时恢复原有服务器与隐藏额度配置。

仓库包含 `app/libs/libtailscale.aar`，普通 APK 构建无需重编 Go 引擎。原生库来源、固定版本、构建脚本和第三方声明见 [third_party/tailscale](third_party/tailscale/README.md)；上游为 [tailscale-android](https://github.com/tailscale/tailscale-android) 和 [tailscale](https://github.com/tailscale/tailscale)。

## 验证与已知限制

目前通过 96 项单元测试与 1 项 Activity 重建回归测试。真机 UI 在 Note X2、Android 11、字体缩放 1.15 下检查；已验证 WireGuard 加密隧道访问、Headscale IP / MagicDNS 访问、两种 VPN 双向切换，以及 App 冷启动后手动重连。

- 官方 Tailscale 账号登录尚未实测；其他设备、ROM 和字体缩放仍需验证。未验证常驻 VPN、开机自动连接或系统杀进程后的自动恢复。
- **Magpie Web Key 目前存于普通 SharedPreferences，未加密，且未从系统备份中排除。** VPN 配置的加密存储不代表所有凭据都已加密。
- HTTP 默认端口 80 的 Cookie 名构造仍有已知问题；当前请使用显式非默认端口，例如 `3430`。
- 部分 ROM 会在崩溃后禁用测试包，造成测试关闭时的 `EmptyActivity` 异常。若确认测试包被禁用，可执行 `adb shell pm enable com.jianyun.x2app.test` 后重跑。

项目目前未声明仓库级开源许可证；第三方组件按各自许可证使用，其声明见 [Tailscale 第三方目录](third_party/tailscale/README.md)。

## CI 与发布

[下载最新 Release APK](https://github.com/jianyun8023/magpie-usage-app/releases/latest) · [构建状态](https://github.com/jianyun8023/magpie-usage-app/actions/workflows/android.yml)

推送 main 或提交 PR 时，GitHub Actions 自动构建 Debug APK、运行单元测试与 lint，并保留 APK 和检查报告 14 天。真机 instrumentation 测试仍需连接 ARM 设备运行。

版本标签（例如 `v0.1.0`）须与 `versionName` 一致，检查通过后构建签名 Release APK，发布到 GitHub Release，附带 SHA256 校验文件。签名使用仓库 Secrets `RELEASE_KEYSTORE_BASE64` 和 `RELEASE_KEYSTORE_PASSWORD`；后续版本必须沿用同一密钥。本地签名备份位于被 Git 忽略的 `.release-signing/`，不要提交或公开。

概览及 Agent、模型、账号分组的“总计 Token”均为输入 + 输出，与 Magpie 默认概览口径一致，缓存和推理 Token 不额外加入。正式 Release 签名与本地 Debug 签名不同，切换安装前应保存原有配置。

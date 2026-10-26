# 应用更新检查来源

更新源与 Release 数据模型改编自 [pavi2410/kmp-app-updater](https://github.com/pavi2410/kmp-app-updater)，固定上游提交为 [`902a04af0c9b065ced2f249f82a58b8ee61f3cd1`](https://github.com/pavi2410/kmp-app-updater/tree/902a04af0c9b065ced2f249f82a58b8ee61f3cd1)，许可证为 Apache-2.0。

采用的上游文件位于 `core/src/commonMain/kotlin/com/pavi2410/appupdater/`：

- `GitHubUpdateSource.kt`：稳定版使用 `/releases/latest`，预发布通道使用 Release 列表。
- `ReleaseInfo.kt`：GitHub Release / Asset 的序列化模型与转换。

本地文件保留 `com.pavi2410.appupdater` 包名，并注明改动。调整包括：将 Ktor 换成注入的请求函数；按通道过滤草稿与预发布；保留名称、预发布标记及附件更新时间；列表请求每页读取 30 个版本。

应用侧 `api/AppUpdates.kt` 将请求接入现有 `MagpieTransport`，只传空鉴权值，遵守当前选中的应用内网络，失败不回落直连。`data/UpdatePreferences.kt` 负责通道与 HTTPS 加速前缀配置。`ui/UpdateSettings.kt` 在设置页提供手动检查及浏览器跳转，不包含后台轮询、下载器或自动安装。

标准版本号使用 [Java SemVer 0.10.2](https://github.com/zafarkhaja/jsemver)（MIT）比较，依赖坐标为 `com.github.zafarkhaja:java-semver:0.10.2`。不使用上游按点分数字比较的实现，确保 `beta.2` / `beta.10` / 正式版的优先级符合 SemVer。

仓库现有滚动 `snapshot` 标签不是 SemVer。其适配由应用维护：从 `magpie-usage-snapshot-<revision>.apk` 读取提交号，选择更新时间最新的 APK，并与构建内记录的源码提交及提交时间比较；同一提交不重复提示，旧 Snapshot 不作为更新。没有 Git 提交信息的源码构建跳过 Snapshot 比较，仍可检查标准版本并打开项目页。

许可证随 APK 分发：

- `app/src/main/assets/licenses/app-updater/LICENSE`
- `app/src/main/assets/licenses/java-semver/LICENSE`

后续同步上游时应重新核对这两个源码文件及本地回归测试，不能直接覆盖现有网络路由适配。

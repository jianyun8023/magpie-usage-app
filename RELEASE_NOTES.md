首个公开版本，支持 Android 11 及以上的 ARM 设备（arm64-v8a、armeabi-v7a）。

- 查看额度、重置窗口、请求流水及周期用量。
- 概览及 Agent、模型、账号分组新增“总计 Token”，按输入 + 输出 + 缓存读取 + 缓存写入统计；推理已计入输出，不重复加入。
- 内置 WireGuard、Tailscale，支持自建 Headscale。
- GitHub Actions 自动构建、单元测试及 lint；版本标签通过检查后发布签名 APK 和 SHA256 校验文件。

下载下方 APK 安装，使用前配置自己的 Magpie 服务地址与 Web Key。该 APK 使用固定的发布签名；此前安装的本地 debug 版本签名不同，无法直接覆盖安装，卸载前请自行保存服务器及 VPN 配置。

已知限制：Web Key 目前使用普通 SharedPreferences 存储，未排除系统备份；HTTP 默认 80 端口的 Cookie 名构造存在问题，请显式使用 3430 等非默认端口。详细使用方式、验证范围与第三方声明见 README。

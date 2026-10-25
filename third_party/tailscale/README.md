# 内置 Tailscale 原生库

来源：官方 https://github.com/tailscale/tailscale-android ，稳定 release **1.104.2**（2026-10-21 发布，非 prerelease）。

- Android tag：`1.104.2-tc3430f0d2-gb01fa89d5`
- Android commit：`b01fa89d5ded31492c98c881207736c39c8f329e`
- 核心模块：`tailscale.com v1.104.2`
- Go 工具链：`go1.27.3`，官方 revision `7ef7b403648ac3a9481a6e88e8b894eff50b0167`
- gomobile/gobind：`golang.org/x/mobile v0.0.0-20240806205939-81131f6468ab`（使用上游 go.mod/go.sum）
- NDK：r23b / `23.1.7779620`
- ABI：`arm64-v8a`、`armeabi-v7a`；Android native API 26
- 构建 tags：`tailscale_go ts_omit_cachenetmap`
- 保留上游 `version-ldflags.sh` 的四个版本 stamp，以及 `-linkmode=external -extldflags=-Wl,-z,max-page-size=16384`

## 在 macOS Apple Silicon 重建

默认源码目录 `/private/tmp/x2-tailscale-src`，工具与缓存目录 `/private/tmp/x2-tailscale-tools`。支持用 `TAILSCALE_SRC`、`TAILSCALE_TOOLS` 覆盖；脚本不会覆盖已有不同 commit 或修改过的上游源码。Android SDK 使用 `ANDROID_HOME`，默认 `~/Library/Android/sdk`。需要可执行 Java/Javac（本次 Java 21），SDK 的已安装 Android platform（本次 android-34）。不需要构建整个官方 Android App，不依赖 CMake。

先准备官方固定工具包（下载后先校验，再解压）：

```sh
mkdir -p /private/tmp/x2-tailscale-tools/go
cd /private/tmp/x2-tailscale-tools
curl -fL --retry 3 https://github.com/tailscale/go/releases/download/build-7ef7b403648ac3a9481a6e88e8b894eff50b0167/darwin-arm64.tar.gz -o go.tar.gz
curl -fL --retry 3 https://dl.google.com/android/repository/android-ndk-r23b-darwin.zip -o ndk.zip
shasum -a 256 -c <<'SUMS'
c05e1fa39d20234c928b5df1300684e38b961060cb9fa461c7c08486e2271115  go.tar.gz
e067b7402fdae85bfbe8af1822afd573b8e73dce443a8292fdaeb2e8dc3aeb86  ndk.zip
SUMS
tar -xzf go.tar.gz --strip-components=1 -C go
unzip -q ndk.zip
```

然后在 x2-app 仓库执行：

```sh
./scripts/build-tailscale.sh
```

Go 包、编译缓存、gomobile 和临时文件全部位于隔离工具目录。脚本使用官方 gomobile bind 参数，只把目标 ABI 缩为两个 ARM 架构；对两个原生库去除调试信息，并校验 ELF LOAD 段对齐至少 16 KB。未裁剪功能、未使用 stub。未修改上游源码。未压缩符号版保留在工具目录 `libtailscale_unstripped.aar`。最终产物 `app/libs/libtailscale.aar` 包含真实 Go 引擎和 Java JNI 绑定。

## 许可证

`LICENSE.android`、`PATENTS.android` 来自固定 Android commit；`LICENSE.core`、`PATENTS.core` 来自 Tailscale v1.104.2。两者为 BSD-3-Clause；分发二进制需保留版权和许可证，不得暗示官方背书。`DEPENDENCIES.android.md` 是上游该版本提供的依赖索引，可能包含完整官方 App 的组件；实际 Go 依赖以锁定源码的 go.mod/go.sum 为准。

构建时由官方 mkversion 核实：`VERSION_TRACK=stable`、核心 commit `c3430f0d2b8a1d646ad3ae867e3470bac07f1fe0`、完整版本 `1.104.2-tc3430f0d2-gb01fa89d5`。mkversion 的核心 Git 历史缓存显式设为工具目录 `tailscale-oss`，不会写入用户默认缓存目录。

## 本次产物核验

- AAR：19,170,848 bytes（约 18.3 MiB）
- SHA-256：`7f43df0cd721685a53062655b1be51868a515e54ccf28b6a00299840e1c8547f`
- 两个 ABI 的 ELF LOAD 段均通过 16 KB 对齐检查；ZIP 完整性检查通过。
- `JNI-API.txt` 为从最终 AAR 的 classes.jar 执行 javap 得到的真实桥接接口（例如 Go int 映射为 Java long）。
- `MODULES.txt` 为 Android arm64 + 本次 build tags 的依赖模块列表；`THIRD_PARTY_NOTICES.txt` 收集这些模块的根目录 LICENSE/COPYING/NOTICE 和 Go runtime 许可证。上游依赖索引只作参考，实际锁定依赖见这份列表及 go.sum。
- 该脚本保证源码和工具版本锁定，不承诺 ZIP 时间戳导致的逐字节复现；重建后需更新本节产物哈希。
- 集成与真机验证范围见仓库README。

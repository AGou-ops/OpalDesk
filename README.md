# OpalDesk (`v0.0.1`)

> **免 Root Android 跨端桌面工作站引擎 · Liquid Glass × 类 DeX 自由多窗与平铺引擎 · 本机零拷贝桌面 / macOS 原生低延迟串流 / Type-C 物理外接屏三位一体**

**OpalDesk** 将 Android 手机或平板转化为完整的桌面级工作站：由一个轻量级 Shell 权限守护进程（`OpalDeskDaemon`）统一驱动 **Liquid Glass 自由多窗、多槽平铺布局与工作台侧边栏**，并无缝输出到三种显示载体：

1. **本机零拷贝桌面（LocalDeX）**：无需外接显示器或电脑，直接在平板/手机内屏以零拷贝 `SurfaceView` 全屏进入桌面工作环境，支持触控、手写笔、外接键鼠与桌面快捷方式一键直达。
2. **macOS 原生远程桌面（`OpalDesk.app`）**：基于 **Swift 6 + SwiftUI + VideoToolbox + AudioToolbox** 构建的原生 macOS 14+ 客户端，通过 **USB 抽象 Socket / Wi-Fi TLS 1.3 (TOFU) + Bonjour (`_opaldesk._tcp`)** 低延迟串流 Android 完整桌面或单应用独立窗口，支持 H.264 / HEVC 硬解、音频回传、UHID 虚拟键鼠、双向文字/图片剪贴板同步与文件传输。
3. **Type-C / HDMI 物理外接屏 & 增强镜像副屏**：支持物理显示器独立扩展桌面、EDID 宽屏/21:9 带鱼屏自适应、四向旋转、主屏一键黑屏省电，以及将原生镜像黑边改造为支持系统小组件与鼠标无缝跨屏的信息副屏。

---

## ✨ 核心特性 (`v0.0.1`)

### 1. Liquid Glass × 桌面窗口引擎
- **自由多窗与实时拖拽**：支持窗口标题栏实时拖动、边缘缩放、最大化/最小化/关闭、沉浸顶栏与快捷窗口切换器。
- **多拓扑平铺 & 分屏**：内置居中黄金比例单窗、左右/上下二分屏、1+2 主从三分屏、三列分屏及 2×2 四宫格，支持拖拽分割条实时调比。
- **底部任务栏与工作台侧边栏（Workbench Rail）**：自适应自动隐藏任务栏、快捷设置浮窗、应用抽屉、桌面小组件面板，以及支持实时缩略图快照（`TaskSnapshot`）切换的左侧/顶部工作台应用栏。
- **分应用显示规则 & 防小窗劫持**：为单个 App 定制默认 / 强制横屏全屏 / 竖屏居中兼容策略，自动规避 OEM 工作台模式强制套壳悬浮窗。

### 2. 三栖显示输出
| 模式 | 适用场景 | 核心能力 |
| :--- | :--- | :--- |
| **本机 LocalDeX** | 平板/折叠屏随时办公 | 零拷贝本地 `SurfaceView` 承载、独立 DPI 调节、多指触控/手写笔/外设直通、桌面图标长按快捷入口 |
| **macOS 原生串流** | 手机/平板协同 Mac 办公 | USB 有线即插即用 & 局域网 TLS 1.3 + 6 位 PIN 配对、VideoToolbox 硬解、AAC-LC 音频回传、UHID/系统注入双输入引擎、剪贴板文字/PNG 互传、拖拽传文件 |
| **Type-C 物理外接屏** | 直连便携屏/桌面显示器 | 4K / 21:9 带鱼屏自适应、0°/90°/180°/270° 旋转、增强原生镜像信息侧边栏（14 款预设卡片 + 系统 AppWidget）、外置鼠标无缝跨屏、一键主屏极暗熄屏 |

### 3. 单 APK 四通道免 Root 激活
- **内置无线调试（零电脑、零第三方依赖）**：APK 内置 `arm64-v8a` `libadb.so`，采用 **Tier 1 `adb push` 直传 → Tier 2 stdin 管道写入 → Tier 3 多用户分包 `pm path`** 三级释放管线，彻底兼容 Android 11–17（无旧版 Toybox `unzip -tq` 故障），支持通知栏直接回复 6 位配对码。
- **Shizuku / Sui 一键秒激活**：自动检测运行中的 Shizuku（ADB `uid=2000`）与 Sui（Root `uid=0` 自动降权），支持启动时自动静默激活。
- **Root 静默降权激活**：兼容 Magisk / KernelSU / APatch，仅用 Root 完成哈希校验后强制降权至 `uid=2000` (`u:r:shell:s0`) 运行。
- **电脑 ADB 一键激活**：跨 Bash / Zsh / PowerShell / CMD 通用单行命令。

---

## 🏗️ 项目架构

```text
├── bridge/                                       # Shell 权限后台守护进程（打包为 opaldesk.jar）
│   └── src/io/github/lengsukq/opaldesk/daemon/
│       ├── OpalDeskDaemon.java                   # 守护进程入口、生命周期调度与 --verify-helper / --stop
│       ├── display/                              # PhysicalDisplay / LocalDex / NativeMirror / CompatPolicy
│       ├── window/                               # DesktopWindowManager / TilingLayoutEngine / TaskWindowController
│       ├── stream/                               # OpalCastProtocol / RemoteStreamServer / UHID / Audio / Clipboard / FileTransfer
│       ├── security/                             # TLS 1.3 自签证书生成与 TOFU 信任库
│       ├── input/                                # InputInjector / LocalInputController / 快捷键匹配
│       └── ipc/                                  # Binder IPC (UID + 签名证书双校验) 与单实例 LocalSocket
├── helper/                                       # Android 客户端（io.github.lengsukq.opaldesk · 100% Kotlin + Compose + Miuix）
│   ├── src/io/github/lengsukq/opaldesk/
│   │   ├── MainActivity.kt / LocalDexActivity.kt / LauncherActivity.kt / TouchpadActivity.kt
│   │   ├── BridgeService.kt / BridgeClient.kt / AdbBootstrap.kt
│   │   ├── adb/                                  # 三级无线 ADB 投递、mDNS 发现与 ShizukuBootstrap
│   │   ├── desktop/                              # Liquid Glass 窗口标题栏、任务栏、工作台侧边栏与浮窗
│   │   ├── design/iosglass/                      # Liquid Glass 材质、Squircle 几何与跨窗模糊
│   │   └── ui/                                   # 响应式主控台、OpalTextField 与四通道激活抽屉
│   └── jniLibs/arm64-v8a/libadb.so               # 内置 arm64 adb 可执行程序（子进程调用）
├── macos/OpalDeskMac/                            # macOS 14+ 原生客户端（Swift 6 / SwiftPM）
│   ├── Sources/OpalDeskCore/                     # OpalCast 协议、TLS TOFU、Bonjour、VideoToolbox、AudioToolbox、ADB 启动器
│   ├── Sources/OpalDeskMac/                      # SwiftUI 界面、交互画布、菜单栏常驻与偏好设置
│   └── Tests/OpalDeskCoreTests/                  # 68 项 Swift Testing 单元测试
├── scripts/                                      # init-signing.sh / build-macos-app.sh / dev-deploy.sh / generate-macos-icon.swift
├── start_opaldesk.sh                             # 设备端手动激活守护进程脚本
└── docs/USER_GUIDE.md                            # 详细使用与排障指南
```

---

## 🚀 快速上手

更多操作细节请阅读 **[📖 docs/USER_GUIDE.md](docs/USER_GUIDE.md)**。

### Android 端激活
1. 安装 `OpalDesk_v0.0.1.apk`，首次打开按引导授予 **通知**、**悬浮窗** 与 **无障碍服务 (`OpalDesk 平板辅助手势`)** 权限。
2. 在激活弹窗中任选以下任一方式拉起守护进程：
   - **无线调试**（免电脑）：开启系统无线调试后在通知栏或弹窗填入 6 位配对码；
   - **Shizuku / Sui**：已启动 Shizuku 时一键授权秒激活；
   - **Root**：Magisk / KernelSU / APatch 一键授权并自动降权至 `shell` 身份；
   - **电脑 ADB 单行命令**（支持 Bash / Zsh / PowerShell / CMD）：
     ```bash
     adb shell "rm -f /data/local/tmp/opd_src.apk /data/local/tmp/opaldesk.jar.new && pm path io.github.lengsukq.opaldesk | grep '^package:' | cut -d: -f2 | head -n1 | xargs cat > /data/local/tmp/opd_src.apk && test -s /data/local/tmp/opd_src.apk && unzip -p /data/local/tmp/opd_src.apk assets/opaldesk.jar > /data/local/tmp/opaldesk.jar.new && rm -f /data/local/tmp/opd_src.apk && test -s /data/local/tmp/opaldesk.jar.new && chmod 644 /data/local/tmp/opaldesk.jar.new && timeout 6 env CLASSPATH=/data/local/tmp/opaldesk.jar.new app_process /data/local/tmp io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon --verify-helper >/dev/null 2>&1 && (timeout 8 env CLASSPATH=/data/local/tmp/opaldesk.jar.new app_process /data/local/tmp io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon --stop >/dev/null 2>&1 || true) && ((pgrep -f '^app_process.*OpalDeskDaemon' >/dev/null 2>&1 || pgrep -f '^/system/bin/app_process.*OpalDeskDaemon' >/dev/null 2>&1) && (pkill -f '^app_process.*OpalDeskDaemon' 2>/dev/null; pkill -f '^/system/bin/app_process.*OpalDeskDaemon' 2>/dev/null; true) && sleep 1 && (pkill -f -l KILL '^app_process.*OpalDeskDaemon' 2>/dev/null; pkill -f -l KILL '^/system/bin/app_process.*OpalDeskDaemon' 2>/dev/null; true) || true) && rm -f /data/local/tmp/opaldesk.jar && mv -f /data/local/tmp/opaldesk.jar.new /data/local/tmp/opaldesk.jar && test -s /data/local/tmp/opaldesk.jar && (appops set io.github.lengsukq.opaldesk SYSTEM_ALERT_WINDOW allow 2>/dev/null || true) && (appops set io.github.lengsukq.opaldesk RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true) && (appops set io.github.lengsukq.opaldesk 10021 allow 2>/dev/null || true) && (appops set io.github.lengsukq.opaldesk ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true) && (pm grant io.github.lengsukq.opaldesk android.permission.POST_NOTIFICATIONS 2>/dev/null || true) && (pm grant io.github.lengsukq.opaldesk android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || true) && (dumpsys deviceidle whitelist +io.github.lengsukq.opaldesk >/dev/null 2>&1 || true) && (CLASSPATH=/data/local/tmp/opaldesk.jar nohup app_process /data/local/tmp io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon >/data/local/tmp/opaldesk.log 2>&1 </dev/null &)"
     ```

### macOS 端连接
1. 运行 `OpalDesk.app`（macOS 14+，支持 Apple Silicon / Intel）。
2. **有线 USB**：用数据线连接已开启 USB 调试的 Android 设备，OpalDesk 会自动检测并按需拉起守护进程建立低延迟通道。
3. **局域网无线**：在 Android 端打开「Mac / 无线大屏」开关后，macOS 端通过 Bonjour（`_opaldesk._tcp`）自动发现设备，首次连接核对 TLS 证书指纹并输入 6 位 PIN 完成配对信任。

---

## 🛠️ 从源码编译

### Android（APK + Daemon JAR）
需要 **JDK 21** 与 **Android SDK**（`build-tools/37.0.0` + `platforms/android-37.0`）：

```bash
# 1. 首次本地编译前生成独立签名证书（会创建 opaldesk.p12，已被 .gitignore 忽略）
./scripts/init-signing.sh

# 2. 编译 Release APK（自动执行 :bridge:dexBridgeJar 并嵌入 opaldesk.jar）
./gradlew :helper:assembleRelease

# 无密钥验证构建（仅输出未签名 APK）
./gradlew :helper:assembleRelease -PallowUnsignedRelease=true

# 运行 Android 全量单元测试
./gradlew :bridge:test :helper:testDebugUnitTest
```

### macOS 客户端（`OpalDesk.app` / `.dmg`）
需要 **macOS 14+** 与 **Xcode 16+ / Swift 6**：

```bash
# 运行 Swift 单元测试
(cd macos/OpalDeskMac && swift test)

# 编译并打包 OpalDesk.app（追加 --dmg 可同步生成安装包）
./scripts/build-macos-app.sh --dmg
```

---

## 🛡️ 独立标识、签名验真与并行共存说明

- **官方发布仓库**：[`https://github.com/lengsukq/OpalDesk`](https://github.com/lengsukq/OpalDesk)（GitHub Releases）
- **Android 包名**：`io.github.lengsukq.opaldesk`（独立起始版本线 `v0.0.1`）
- **正式发布 APK 签名证书 SHA-256 指纹**：
  ```text
  46f8a5294387afa67269cd374589a25f8f228d15b9746a3438c8b8ca4a48467b
  ```
- **macOS Bundle ID**：`io.github.lengsukq.opaldesk.mac`
- **隔离的运行时命名空间**：独立守护进程类名 `OpalDeskDaemon`、独立 `/data/local/tmp/opaldesk.*` 配置/日志/证书文件、独立抽象 Socket（`opaldesk_stream` / `io.github.lengsukq.opaldesk.daemon.ctl`）及 Bonjour 服务（`_opaldesk._tcp`），**可与原版 ExtBridge 在同一台设备上完全并行安装与共存，互不冲突**。

---

## 🙏 致谢与来源声明（Acknowledgments）

- **OpalDesk** 最初源自并深度演化自开源项目 **[duppjuzo/ExtBridge](https://github.com/duppjuzo/ExtBridge)**（[MIT License](LICENSE)）。衷心感谢原作者 **duppjuzo** 在 Android 免 Root Type-C 外接屏守护进程、增强原生镜像信息副屏、免电脑无线 ADB 自激活与 Shizuku 激活实践方面的开创性工作与开源贡献！
- 由于本项目后续独立重写了 Liquid Glass × 类 DeX 桌面窗口引擎、本机 LocalDeX 模式、工作台侧边栏以及 macOS 原生低延迟串流客户端，现作为独立项目 **OpalDesk** 进行维护，使用独立包名、独立图标、独立签名与从 `v0.0.1` 起算的独立版本号，避免与上游 ExtBridge 主仓产生任何混淆或签名冲突。
- 其他内置可执行程序与第三方开源依赖清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 📄 开源协议

基于 [MIT License](LICENSE) 开源。

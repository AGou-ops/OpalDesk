# Changelog / 更新日志

本项目版本号与安装包后缀同步（见 `helper/AndroidManifest.xml` 及 GitHub Releases）。

---

## v3.2.3 (`versionCode: 8`) — 2026-11-17

### 🐛 关键问题修复（外接屏无法点击启动 / 输入注入静默失效）
- **绕过系统「后台弹出界面 (`MIUIOP 10021`)」对扩展屏桌面启动应用的静默拦截**
  - 在小米/HyperOS 等深度定制 ROM 上，应用处于 `VirtualDisplay` 时，从 Helper 进程自身调用 `startActivity()` 可能因缺少 `MIUIOP(10021)` 后台弹窗权限而被系统直接吞掉（无异常抛出，表现为点击图标有按下高亮动画却无任何软件打开）。
  - 现改为当 `LauncherActivity` 运行于扩展屏时，**优先通过 Shell 守护进程 (`BridgeClient.launchComponent` -> `ExtBridge.launchComponentOnVd`) 代理启动应用**，仅在守护未连接/返回失败时才回退本地 `startActivity()`；同时在 `ExtBridge` 启动、无线调试引导、`start_extbridge.sh` 与电脑 ADB 命令中自动授予 `appops set com.extbridge.helper 10021 allow`。
- **可感知化 `INJECT_EVENTS` 安全拦截状态 & 鼠标输入注入与光标层级修复**
  - 为 `ExtBridge` 统一封装 `invokeInjectInput()`，精确捕获 `SecurityException` 与输入注入返回值，并在状态接口 (`code=15`) 与鼠标注入返回 (`code=17`) 中透传 `sLastInjectStatus`（`1=正常` / `-1=INJECT_EVENTS 被拒绝`）。
  - 修正触摸板左/右键单击注入使用的未来时间戳序列为单调递增至当前时间（`now - 25` ~ `now`），防止部分设备 `InputDispatcher` 丢弃未来时间戳的 `ACTION_UP` 事件。
  - 修复 `BridgeService` 宿主模式下 `mDockRoot`（`10dp`）和快捷面板（`16dp`）海拔高于 `mCursorView`（`0dp`）导致 **鼠标准星被 Dock 栏挡在下层** 的层级 bug，统一提升鼠标指针至最高渲染层级（`elevation/translationZ = 48dp` + `bringToFront()`）。
  - 当系统未开启 MIUI/HyperOS「开发者选项 → USB 调试（安全设置）/ 允许模拟点击」导致触控板/鼠标准星只动画不生效时，控制台详情页与触控板顶部会立即弹出醒目红色提示与 Toast 指引开启。

- **首次启动全量权限初始化 & 进入开发者选项二次校验（含无障碍必需项）**
  - 将 **无障碍服务（`ExtBridge 平板辅助手势`）** 提升为与通知、悬浮窗并列的必需项；应用首次启动时自动顺序申请全部核心权限（Android 13+ 运行时 `POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW` 悬浮窗与无障碍服务），并提供 Android 13+「受限设置（灰色开关）」一键进应用详情解锁引导。
  - 若用户在首次启动时拒绝或跳过了任意权限（含无障碍/悬浮窗/通知），当其首次点击**「去开启无线调试 / 开发者选项」**时，会先拦截并要求补齐缺失权限；同时在 ADB/守护激活命令中自动授予 `ACCESS_RESTRICTED_SETTINGS` 解锁受限设置。

---

## v3.2.2 (`versionCode: 7`) — 2026-11-16

### 🎨 交互表达优化 & 项目定位明确
- **区分「桌面渲染分辨率」与「外接物理输出」，消除切换误解**
  - 控制台卡片明确标注上方预设与自定义宽高控制的是 **`VirtualDisplay` 真实渲染画布**（全机型 100% 生效：支持 `720p/900p` 降 GPU 负载稳帧、`4K` 超采样锐化、比例适配与竖屏旋转），状态栏同步区分显示 `虚拟渲染画布` 与 `外接物理输出 (系统/EDID协商)`。
  - 将 **目标刷新率预设与显示器硬件 EDID 模式列表** 统一收纳进默认折叠的 **「▸ 高级：外接物理信号与帧率尝试 (受设备固件限制)」** 面板，主界面只突出 **桌面渲染分辨率、四向旋转与缩放 DPI** 三项 100% 生效的核心设置；主应用按钮更名为 **「✓ 应用渲染分辨率与设置」**。
- **物理模式锁死诚实检测 (`sLastPhysResult = -3`)**
  - `ExtBridge` 在调用 `setUserPreferredDisplayMode` 后若检测到系统固件未实际切换物理档位（例如 Xiaomi HyperOS 4 下 `DisplayModeDirectorImpl` 限制 `displayId` 仅 0/1 且 `SurfaceFlinger` 拦截 `Attempt to set desired display modes for external display`），会返回明确状态 `-3`，控制台通过横幅与 Toast 告知用户“虚拟桌面已切换，当前系统固件锁定外接物理输出为 xx×yy@zzHz，已自动硬件缩放铺满”。
- **明确项目免 Root 定位与文档说明**
  - `README.md` 与激活弹窗补充说明：ExtBridge 专注 **免 Root / Shell 权限** 路线，不开发也不会维护用于改写系统框架或 `SurfaceFlinger` 的 Zygisk/LSPosed Root Hook 模块；已有的一键 Root 激活仅作省事注册与立即降权运行使用。

---

## v3.2.1 (`versionCode: 6`) — 2026-11-16

### 🐛 问题修复
- **修复部分 Android 13–16 设备物理外接分辨率与刷新率切换失效的问题**
  - 原 `DisplayManager.setUserPreferredDisplayMode(int, Display.Mode, boolean)` 三参数反射仅存在于 Android 17+，在 Android 15 / 16（如小米平板 8 Pro 等 ROM）上会静默 `NoSuchMethodException` 导致显示器始终停在默认 `1080P 60Hz`。
  - 现改为多签名级联探测并带 Shell 兜底：`DisplayManager`（3参/2参）→ `DisplayManagerGlobal`（2参/3参）→ `IDisplayManager`（2参/3参）→ `cmd display set-user-preferred-display-mode`。
  - 修复 `applyConfig()` 预设匹配逻辑（适配竖屏旋转宽高、刷新率无匹配挡位时不静默伪装成功，由控制台诚实提示 EDID 不支持该帧率或系统拒绝切换）；点击分辨率预设、自定义输入框或「对调宽高」时自动重置旧 EDID 物理模式 ID 锁定。
  - 修复 `tick()` 与异步切换时 `reassert()` 未重算缩放矩阵的问题：当 `sPhysW × sPhysH` 发生变化时自动重新执行 `applySurfaceTransform()` 与状态同步。
- **修复 App 划掉 / 强行停止后自动重启，并新增「停止服务并退出」功能**
  - 移除 `ExtBridge` 中 `sHelperProcToken.linkToDeath` 的 1.5 秒强行拉起死循环，`tick()` 仅在首次接入外接物理屏时同步唤醒 Helper；`notifyHelperSync()` 在 Helper 未存活时不再发 `STATE_SYNC` 广播触发唤醒；`BridgeService` 改为 `START_NOT_STICKY`。
  - 在控制台顶部操作区与待机通知栏新增 **「⏻ 停止服务并退出」**（调用 `BridgeClient.shutdownDaemon()` 触发 `code == 99` + `SHUTDOWN` 广播，先恢复外接屏并退出守护进程，再销毁前台通知与 App 任务），不再使用外接屏时可一键彻底关闭。

---

## v3.2.0 (`versionCode: 5`) — 2026-11-16

### ✨ 新增功能
- **四向输出画面旋转与竖屏桌面 (`0° / 90° / 180° / 270°`)**
  - 在 `ExtBridge` 图层合成管线（`applySurfaceTransform`）引入 `Matrix` + `RectF` 硬件旋转与居中矩阵变换，支持将物理横屏输出的 Type-C 显示器/便携屏竖放后点对点满屏竖屏显示，或倒置 `180°` 显示。
  - 切换 `90° / 270°` 时通过 `liveResizeOrRebuild()` 对 `VirtualDisplay` 与 `BLASTBufferQueue` 执行无感热重建与宽高对调，**不中断已打开的后台应用**；旋转角度持久化保存于 `/data/local/tmp/extbridge.conf`。
- **控制台与扩展屏快捷面板同步接入**
  - `MainActivity` 新增 **「输出桌面旋转方向」**（`0° 正常横屏` / `90° 顺时针竖屏` / `180° 倒置横屏` / `270° 逆时针竖屏`）与 **`⇅ 对调宽高`**（支持在横屏显示器上保持 `0°` 居中投射竖屏桌面）。
  - 扩展屏右下角快捷面板（`BridgeService`）新增 **`↻ 旋转`** 按钮，可直接在扩展屏侧循环切换输出方向。
- **竖屏排版与边界适配**
  - 放宽分辨率校验 `isValidDims()`，完整支持 `1440×2560`、`1080×1920`、`2160×3840` 等竖屏尺寸；`computeOverlayDpi()` 改为按短边限幅以优化竖屏 Dock 缩放；`calcFreeformRect()` 适配竖屏自由小窗宽高比例。

---

## v3.1.0 (`versionCode: 4`) — 2026-11-16

### ✨ 新增与改进
- **新增 Root 一键激活模式（免 ADB / 免开发者选项）**
  - 在激活弹窗新增 **「👑 Root 激活 (免 ADB 权限)」**，兼容 Magisk / KernelSU / APatch；支持勾选 **「启动软件时自动 Root 激活」**，重启设备打开 App 即可静默恢复后台守护进程。
  - `ExtBridge` 守护进程以 Root 拉起后自动通过 `Os.setgid(2000)` / `Os.setuid(2000)` 降权至 `shell` 身份运行，降低高权限驻留风险。
- **首次插屏跨分辨率自动适配**
  - 在用户尚未保存自定义配置（`/data/local/tmp/extbridge.conf` 不存在）时，自动按接入外接显示器的原生 `physW × physH @ physFps` 创建虚拟桌面，并按短边比例自动计算桌面 DPI（限幅 `160 ~ 320`）。
- **主屏手势隔离加固 & 工程化整理**
  - 在 `applyDisplayFullscreenPolicy()` 中通过反射补充 `IWindowManager.setShouldShowSystemDecors(vdId, false)`，加强对不同系统 ROM 主屏全面屏手势的兼容隔离。
  - `MainActivity` 标题改为动态读取安装包 `versionName`；补充 GitHub Actions 自动编译工作流 `.github/workflows/build.yml`。

---

## v3.0.0 (`versionCode: 3`)

### 🎉 核心基线功能
- 基于 `VirtualDisplay` + `SurfaceControl` 的真·双屏独立扩展桌面，支持一键切换原生镜像模式。
- 手机端内建 `arm64` `adb` 与 `extbridge.jar`，支持本机无线调试 mDNS 自动配对自激活（自动屏蔽 VPN 虚拟网卡干扰）。
- 独立桌面启动器（同步系统壁纸 + 高斯毛玻璃、中文/拼音首字母搜索）、底部 Dock 任务栏与 Win11 风格快捷控制中心。
- 手机端全高大触摸板与软键盘文字转发、硬件鼠标映射热切。
- 按应用粒度管理的四种显示兼容模式（跟随系统默认 / 强制横屏全屏 / 竖屏居中兼容 / 自由小窗模式），以及可选无障碍手势兜底服务。

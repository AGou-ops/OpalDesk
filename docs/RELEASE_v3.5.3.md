# ExtBridge `v3.5.3` (`versionCode: 25`) 更新文档

## 核心更新概览：Xiaomi HyperOS 原生风格全面重构（`Compose + Miuix`）、窗口舞台过渡动画与全链路安全稳定性加固

`v3.5.3` 合并了 `v3.5.x` 主线全部界面重构与实机深度修复：采用 **`Jetpack Compose + Miuix 0.9.4`** 对主控台与虚拟触摸板进行彻底重构，同步对 **扩展桌面启动器、DeX 底部 Dock、快捷控制中心浮窗、自由分屏菜单与镜像融合边栏卡片** 完成全链路 HyperOS 2.0 视觉规范统一；新增 **HyperOS 2.0 级窗口最小化/恢复与分屏过渡动效引擎**；并完成了覆盖 `SurfaceFlinger` 图层合成、跨屏输入焦点、多窗口生命周期与跨进程 IPC 鉴权的全库级恶性隐患治理。

---

## 1. 主控台全面重构（`MainActivity.kt` · `Miuix 0.9.4`）

- **手机 / 平板自适应双形态导航骨架**：
  - **横屏平板工作台 (`>= 760dp`)**：左侧配备原生澎湃风格 `NavigationRail` 侧边导航栏，首页采用**左状态与快捷磁贴 + 右当前输出模式参数直调**双栏并排卡片布局，无需频繁跨页即可一屏掌控全局。
  - **竖屏手机与紧凑窗口 (`< 760dp`)**：顶部配备可折叠大标题 `TopAppBar` + 底部四栏 `NavigationBar`（`控制台`、`显示与边栏`、`应用规则`、`设置`），单手拇指操作顺手直观。
- **信息分层与动态按需呈现（告别超长杂乱列表）**：
  - **控制台**：顶部精简状态主卡（支持点击展开 **连接与渲染管线诊断抽屉**）+ `DeX 扩展桌面 / 原生镜像投屏` 分段胶囊切换器 + **2×2 澎湃控制中心磁贴**（`键鼠路由`、`大触摸板`、`一键黑屏`、`扩展桌面 / 副屏直控`）；运行权限健康横幅仅在缺权限时浮现，授权齐全后自动隐藏零干扰。
  - **显示与边栏**：根据当前输出模式智能切换专属配置面板——DeX 模式下集中呈现分辨率下拉预设、宽高对调弹窗、旋转方向、带关键刻度吸附的 **DPI 滑块（实时换算 `swDp` 平板阈值）** 及 **EDID 硬件档位抽屉**；原生镜像模式下采用 **2×2 可视化微缩显示器布局示意图卡片**（`纯黑居中`、`主屏靠左`、`主屏靠右`、`主屏居中`），辅以系统 `AppWidget` 双槽位绑定器与 14 款边栏信息卡排序管理抽屉。
  - **分应用显示规则（独立一级页面）**：脱离旧版局促弹窗，升级为带实时搜索框、应用高清图标与澎湃下拉选择器的独立页面，已自定义规则的应用自动置顶并支持一键全局重置。
  - **支持跟随系统 / 澎湃浅色 / 澎湃深色三态主题无缝切换**。

---

## 2. 全周边界面视觉联动与舞台过渡动画（零 Emoji · 线性矢量图标统一）

- **大触摸板与远程输入法 (`TouchpadActivity.kt`)**：
  - 升级为与主控台同源的 `Compose + Miuix` 架构：顶部紧凑悬浮状态胶囊（实时坐标、一键切屏路由、键盘开关）、圆角点阵触控区与右侧单指滚轮滑轨、底部拟真触感左键/拖拽锁定/右键三键、线性矢量工具条（含**一键黑屏**与**副屏直控**直达）及可折叠快捷键与输入法栏；保留竖屏手机**单手拇指热区模式**。
  - **虚拟触摸板「右键 / 返回」与双指轻触智能分发修复**：修复主屏触摸板抢占系统顶层焦点（`mTopFocusedDisplayId=0`）及鼠标 `BUTTON_SECONDARY` 不经过 `InputReader` 导致外接屏右键无法触发返回的问题；现支持智能场景识别——当光标位于 DeX 桌面图标或底部 Dock 任务图标上时触发右键上下文菜单，当控制中心/分屏浮窗打开时优先收起浮窗，在其余所有外接屏应用内点击「右键 / 返回」或**双指轻触**均稳定执行跨屏 `KEYCODE_BACK` 返回，同时保护扩展桌面根页面不被误关。
- **DeX 底部 Dock、澎湃控制中心浮窗与舞台动画引擎 (`BridgeService.java` + `HyperUi.java` + `StageTransitionView.java`)**：
  - 新增轻量级抗锯齿线性矢量图标引擎 `HyperUi`，全面移除 Dock、控制中心浮窗、分屏比例浮窗、任务右键气泡、黑屏提示及通知栏按钮中的 Emoji 字符；控制中心浮窗升级为 **HyperOS 2.0 深色毛玻璃大圆角面板 (`22dp`)**，激活磁贴统一采用澎湃标志蓝 (`#0D84FF`)。
  - **新增 HyperOS 2.0 级窗口舞台过渡动画引擎 (`StageTransitionView`)**：针对 Android 虚拟显示器（`VirtualDisplay`）上 `resizeTask` / `startActivityFromRecents` 原生无过渡动画导致画面硬切的问题，新增守护进程跨进程零拷贝 GPU `TaskSnapshot (HardwareBuffer)` 快照通道（适配 Android 17 `ITaskSnapshotManager` 并附带 `FLAG_SECURE`/冷启动毛玻璃应用卡自动兜底）与全链路动效编排：
    - **Dock 图标退回后台（窗口 → 图标形变）**：点击前台应用图标或「桌面」按钮时，实时窗口平滑圆角收缩并沿微弧轨迹坍缩形变为对应 Dock 图标，落地瞬间触发 Dock 图标弹性缓冲回弹与扩展桌面景深回落；
    - **Dock 图标后台唤起**：从对应 Dock 图标位置以澎湃阻尼曲线弹出展开至全屏或对应分屏槽位，待底层窗口绘制就绪后 `85ms` 无缝叠化交接；
    - **自由分屏全流程动效**：进入分屏/放入左右屏滑入展开、分割条胶丸弹性弹出（`Overshoot`）、分屏比例预设切换（`3:7 ~ 7:3`）双窗同步形变、**左右/上下互换立体前后交叉位移**、退出分屏平滑铺满全屏，以及分屏控制菜单与任务气泡缩放渐隐微交互。
- **扩展桌面启动器 (`LauncherActivity.java`) & 副屏直控 (`SideControlActivity.java`) & 镜像融合边栏**：
  - 扩展桌面顶栏搜索胶囊、`应用规则`、`同步壁纸` 与分屏提示条统一换装 `HyperUi` 线性矢量图标；镜像融合边栏全部 14 款预设信息卡片去除 Emoji 杂讯并与系统 `AppWidget` 视觉融为一体。

---

## 3. 全链路恶性隐患治理、镜像副屏防串屏与 IPC 安全加固（`v3.5.1` ~ `v3.5.3` 汇总）

- **分屏退出半屏黑屏卡死与多窗口残留根治 (`ExtBridge.java`)**：
  - 修复 Android 15~17 / HyperOS 2.0 下 `WINDOWING_MODE_MULTI_WINDOW (6)` 直接切回 `FULLSCREEN (1)` 时 `SurfaceFlinger` Task Leash 残留分屏裁剪/偏移坐标（如 `7:3` 退出后 `x=1796` 导致左侧 70% 黑屏且遮挡点击）的底层缺陷；全路径统一通过先在多窗口态 `resizeTask(fullBounds)` 铺满全屏再切换 `FULLSCREEN (1)` 并恢复应用专属兼容策略彻底消除残留。
- **镜像模式副屏偶发渲染串屏（显示应用界面一部分）根治 (`ExtBridge.java` + `BridgeService.java`)**：
  - **物理像素双向硬裁剪隔离**：为内置主屏镜像层新增硬裁剪父容器 `ExtMir0Clip`（配纯黑底衬 `ExtMir0Bg`），锁死主屏镜像不越界；单侧副屏模式下将 `ExtMirrorSide` 提升至 `layer 2` 并通过 `Transaction.setCrop` 精确裁剪至边栏区域（MTK HWC 直通 `DEVICE` 硬件层合成），切换「换边」（`Mode 1/2/3`）原地无损更新裁剪无需重建虚拟屏；
  - **无空窗期原子换窗与 `PixelFormat.OPAQUE` 实心背景**：副屏刷新改为先构建并挂载新窗口再移除旧视图，保持 `AppWidgetHost` 持续监听不断流，消除 `0 Layers` 空窗闪烁与越界绘制；
  - **主屏横竖屏无缓存追踪与副屏误入任务瞬时迁回**：直连 `IDisplayManager.getDisplayInfo(0)` 禁用进程内尺寸缓存（`<240ms` 响应旋转），并在物理鼠标、副屏直控、虚拟触摸板与 `tick()` 全路径执行 `35ms / 110ms / 280ms` 自动巡检，将小组件在副屏拉起的全屏页面立即无感迁回主屏。
- **镜像副屏点击第三方小组件（如网易云音乐）指针跳出副屏与原点偏移修复 (`SideMouseCaptureActivity.java` + `BridgeService.java` + `ExtBridge.java`)**：
  - 修复点击小组件播放按钮拉起瞬态透明中转页 `AppwidgetPlayerRedirectActivity` 抢走窗口/显示器焦点导致 `PointerCapture` 中断、鼠标被强制钳制跳出副屏并回退旧入口原点的缺陷；现通过 `0x1040B`（`STEAL_TOP_FOCUS_DISABLED`）、`mTopFocusedDisplayId=0` 焦点回正、WMS 焦点脉冲无感重获 `PointerCapture` 及非越界释放零强制位移彻底解决；同时支持主屏手指触摸（`ACTION_OUTSIDE` / `SOURCE_TOUCHSCREEN`）或 `ESC`/`返回` 毫秒级释放。
- **守护进程、无线调试、IPC 鉴权与构建供应链双轮深度安全加固（Codex + Claude 联合安全审计全闭环）**：
  - **控制广播纯签名级权限保护与内部分离 (`ControlReceiver` + `AndroidManifest.xml`)**：对外导出的 `ControlReceiver` 绑定纯 `signature` 级系统权限 `android.permission.INJECT_EVENTS`（`com.android.shell` 默认持有，彻底阻断普通第三方应用及 `pm grant` 开发级权限绕过，且绝不放行 `INVALID_UID`）；应用内通知栏快捷按钮独立走非导出 `ControlReceiver$Internal`，非必要导出的 `TouchpadActivity` / `SideControlActivity` 收敛为 `exported="false"` 并开启 `filterTouchesWhenObscured`，以显式 `<queries>` 替代 `QUERY_ALL_PACKAGES`；
  - **Binder 接口令牌、官方证书 SHA-256 强绑定与高危动作白名单 (`ExtBridge` + `BridgeProvider` + `BridgeClient`)**：移除 `BridgeService.onStartCommand` 与导出 `LauncherActivity` 的未鉴权 `cb` 注入路径，仅经 `BridgeProvider.call("setBinder")` 传递句柄并强制校验 `com.extbridge.daemon.IBridgeControl` `InterfaceToken`；守护进程动态校验 `com.extbridge.helper` 的官方 X.509 签名证书 SHA-256 指纹（信任边界严格限定为 `uid 0 / 1000 / 2000` 系统/Shell 进程及通过官方证书校验的 `com.extbridge.helper`，防卸载后同包名冒牌应用接管），并对组件启动、任务移屏/关闭/截图、系统设置跳转及 `am compat`（逐条独立进程调用、上限 512 项）实施严格包名与外接屏任务归属校验；
  - **强制降权闭环、Root 暂存防符号链接劫持与前置哈希/证书校验 (`AdbBootstrap` + `start_extbridge.sh`)**：守护进程启动时清空附加组并执行 `setgid(2000)` + `setuid(2000)` 完整读回校验，任何一步失败立即 `System.exit(1)` 拒绝以 Root 运行；Root 自举与 `start_extbridge.sh` 先校验 `extbridge.jar` SHA-256 / 官方 APK 证书、使用 `mktemp` (`O_EXCL`) 拒绝符号链接并先行切换至 `u:r:shell:s0` (`uid=2000`) 后再停机替换与写日志；电脑端 `buildPcAdbCommand`、`README.md` 与 `USER_GUIDE.md` 三处激活命令完全统一为零 `$` 跨平台兼容管道；
  - **内置无线调试私有 Unix Socket 隔离、本机 IP 校验与可选自动关闭 (`AdbBootstrap.java`)**：废弃 TCP `127.0.0.1:15037`，改用应用私有 `0700` 目录下的 `localfilesystem:` Unix 域套接字并在全路径 `finally` 执行 `disconnect` + `kill-server` 与套接字清理；配对与连接严格校验本机网卡 IP 并隐藏通知明文配对码，新增「无线激活后自动关闭无线调试」开关；
  - **单实例锁服务端身份校验、异构后台线程池与异常退出自愈 (`ExtBridge.java`)**：`@com.extbridge.daemon.ctl` 客户端与 `--stop` 双向校验 `SO_PEERCRED` 服务端 UID（防恶意抢注双实例）；`NEED_BINDER` 防抖与跨进程 IPC 移出主 Looper（消除光标卡顿与阻塞）；每次启动前无条件恢复残留外接键鼠关联 (`unbindInput`) 与崩溃遗留亮度备份，退出清理重排为「亮度原子写盘与读回校验还原 → 解绑输入 → 释放 `SurfaceControl/VirtualDisplay` → 还原分屏与兼容策略」，`--stop` 预留充足优雅停机窗口；
  - **CI 最小权限、Action SHA 锁定、强制官方 V2/V3 签名与第三方合规声明 (`build.gradle.kts` + `build.yml` + `THIRD_PARTY_NOTICES.md`)**：GitHub Actions 默认只读权限 (`contents: read`)、全量 Action 按 40 位 Commit SHA 固定、签名口令仅注入构建单步并拆出独立 `release` 发布作业（防覆盖已有 Tag）；`helper/build.gradle.kts` 增加 `preBuild` `assets/extbridge.jar` 非空门禁，移除 V1 签名与 Debug 签名回退，锁定官方 V3 证书 SHA-256 指纹；新增 `THIRD_PARTY_NOTICES.md` 完整披露 `libadb.so`（`termux/android-tools` `34.0.0`，SHA-256 `47ea035f...`）及全部依赖许可证。

---

## 4. 构建工具链与体积优化

- 构建工程升级至 **Gradle `9.7.1` + AGP `9.4.1` + Kotlin `2.4.20` + JDK `21` + Android SDK `37.0`**，保留 `build_v3.ps1` 一键全自动编译流程与 GitHub Actions 自动构建发布工作流。
- 启用 **R8 代码缩减与资源压缩**（保留 `com.extbridge.**` 守护进程与 IPC 符号及 API 30~37 图形兼容路径），完整内置 `arm64-v8a` `libadb.so`、`extbridge.jar`、Jetpack Compose 及 Miuix 组件库，整包体积约 **`5.29 MB`**，保持相同 V3 签名支持免卸载直接覆盖升级。

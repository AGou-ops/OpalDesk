# ExtBridge v3.4.1 (`versionCode: 20`) 版本更新文档

> 发布日期：2026-11-17  
> 适用范围：Android 11+（ARM64，建议 Android 14–16）支持 DP Alt Mode 的手机与平板设备  
> 完整使用指引请参考：[使用说明书 (USER_GUIDE.md)](USER_GUIDE.md) · [项目主页 (README.md)](../README.md)

---

## 一、版本亮点总览

`v3.4.1` 围绕**「增强原生镜像信息副屏」**、**「主屏一键黑屏最低亮度省电」**与**「全量底层代码审计加固」**三大主线进行了深度完善：

1. **增强原生镜像信息副屏（14 种预设组件 + 6 套主题 + 系统 AppWidget）**：
   - 将原生镜像外接屏左右空白黑边改造成实时可交互扩展工作台，支持 **左对齐合并**、**右对齐合并**、**左右双翼对称** 与 **关闭** 4 种布局，自动适配高/低分辨率及多屏宽高比。
   - 内置 **14 款可任意替换排序的信息卡片**（大数字时钟、电池温压、Wi-Fi 速率信号、蓝牙外设、音频路由通道+音量点按控制、媒体播放+切歌、日历日程、内存系统负载、实时网速、存储亮度、25 分钟番茄钟、便签、镜像快捷台、`主屏最低亮度` 卡片），全面加大高对比字体。
   - 支持 **6 款视觉主题 + 自定义底色**，以及系统桌面小组件（AppWidget）接入与 **5 档防拉伸比例约束**（`自动原比例 / 方形 1:1 / 竖屏 3:4 / 宽屏 16:9 / 铺满槽位`）。
2. **外置鼠标外屏显示、无缝跨屏与「副屏直控」**：
   - 修复镜像模式下外置鼠标不在外接屏显示的问题，实现实体鼠标**滑过镜像区边缘直接跨入黑边侧边栏**点击/滚动/右键操控小组件，并解决边缘碰触误弹出 ExtBridge 主界面的问题。
   - 提供 **5 档光标灵敏度倍率**（`0.9x ~ 3.4x`）及 DPI 自适应。
   - 未接实体键鼠时，支持在主屏打开 **`副屏直控` (`SideControlActivity`)** 或使用增强 **虚拟触摸板** 直接手指点按操控副屏组件，同时保护主屏原桌面布局不被破坏。
3. **主屏一键黑屏 & 最低亮度省电闭环**：
   - DeX 底部 Dock 快捷控制浮窗新增 **「🌙 一键黑屏 (平板最低亮度)」**，镜像侧边栏同步内置 **「🌙 主屏最低亮度」** 卡片：一键压低主屏背光至硬件最小值 `1` 并盖上纯黑防误触遮罩（支持 `NavAssistService` 无障碍叠加层兜底），双击主屏或停止退出服务时精确还原原始亮度。
4. **全量稳定性、并发安全、能耗与 IPC 加固**：
   - 修复 Daemon 端多处 `VirtualDisplay` 循环重建抖动、补充 4 把并发互斥锁、缓存 `SurfaceControl` 反射并改用自适应轮询退避；
   - 将 Helper 端高频 `MemoryInfo` / `TrafficStats` / `StatFs` / 亮度采集移出主线程，强化生命周期清理、监听器注销、`BridgeProvider`/`BridgeBinder` UID ACL 鉴权与 `ControlReceiver` `goAsync()` 异步响应；
   - 补全 `AndroidManifest.xml` 中全部 11 项控制广播 Action。

---

## 二、全模块更新与修复清单

### 1. 守护进程 (`ExtBridge.java`)
- 新增并收口 `sInputLock`、`sCursorLock`、`sSplitLock`、`sScreenDimLock` 并发互斥锁，消除多线程竞态。
- 缓存 `SurfaceControl` 与 `Transaction` 反射句柄（`initSurfaceReflection` / `newSurfaceTx` / `closeSurfaceTx`），避免高频反射查找与泄漏；统一边距最小阈值 `EDGE_MARGIN_MIN_PX = 48`。
- 修复原生镜像（`NATIVE_MIRROR_STOCK`）、侧屏互斥开关（`sSideVdAttempted`）及物理分辨率未变更场景下的 `1.5s` 重复重建。
- `startMirrorCursorPoller` 升级为自适应退避轮询（`16ms → 50ms → 150ms`）与基于时间戳的边缘离开防抖；`handleCapturedMirrorMouse` 修复右/中键（`actionType 6/7`）与 `code 42` 重入捕获保护。
- 增强 `BridgeBinder` 调用方 UID ACL 校验（`isTrustedCaller`），限制 `runCmdOut` 256KB 输出与 3 秒超时；确保 `cleanupDisplay` 幂等还原系统亮度。

### 2. Helper 客户端与服务 (`BridgeService.java` 等)
- `BridgeService` 补充 `mDestroyed`、`mPollSeq` 序号校验、`safeBgExecute` / `safePost`；切层清理统一使用 `detachExtOverlays(boolean keepBlackout)`，防止切换窗口意外撤掉黑屏。
- 主线程刷新解耦：内存、网速、存储与亮度采集在后台线程完成，番茄钟计时仅在运行状态触发；图标 `LruCache` 通过 `cloneDrawable()` 隔离状态，`MiniIconView` / `MiniBatteryView` 预分配几何对象，`QuickTile` 复用 `GradientDrawable`。
- 安全与生命周期加固：`BridgeProvider` 校验调用方 UID 白名单（`0 / 1000 / 2000 / myUid`）；`BridgeClient` 改用 `CopyOnWriteArraySet` 与 `linkToDeath`/`pingBinder()`；`MainActivity`、`TouchpadActivity` 与 `LauncherActivity` 在 `onPause` / `onDestroy` 注销监听器；`TouchpadActivity` 移动事件在后台线程节流合并。
- `ControlReceiver` 切换为 `goAsync()` 后台执行，并在 `AndroidManifest.xml` 补齐 `SET_MIRROR_SIDE_MODE`、`SPLIT_ACTION`、`SET_ROTATION`、`SET_DOCK_AUTO_HIDE` 等完整 Action 声明。

### 3. 图文文档与验证成果
- 新增 11 张真实外接显示器实测截图（[docs/images/](images/)）与完整图文指南（[docs/USER_GUIDE.md](USER_GUIDE.md)），覆盖 DeX 桌面、Dock 快捷浮窗、一键黑屏、强制横屏全屏、50/50 自由分屏、统一/双翼镜像侧边栏、主屏副屏直控及虚拟触摸板。
- 在 **REDMI K Pad (`25079RPDCC`) + `2560×1440` 显示器** 上完成快速连续切模式、分屏调比与互换、四向旋转、输入开关切换及黑屏亮度还原（`19 → 1 → 22`）压测，全程零崩溃、零 `BadTokenException`、零 `ANR`。

---

## 三、发布产物清单与升级注意事项

- **`ExtBridge_v3.4.1.apk`**：直接覆盖安装（`adb install -r`），升级后建议重启一次守护进程或按弹窗重新一键激活以加载最新 `extbridge.jar`。
- **`ExtBridge_v3.4.1_src_github.zip`**：完整源码 + 图文文档归档（已按 `.gitignore` 过滤构建缓存与私有签名密钥）。
- **兼容与权限提醒**：若外接触控仅见光标动画无点击效果，请在系统 **开发者选项** 开启 **「USB 调试（安全设置）/ 允许模拟点击」**；黑屏叠加层推荐配合授予 **无障碍服务（`ExtBridge 平板辅助手势`）** 以获得最高兼容层级保障。

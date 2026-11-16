# Changelog / 更新日志

本项目版本号与安装包后缀同步（见 `helper/AndroidManifest.xml` 及 GitHub Releases）。

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

/* ==========================================================================
   OpalDesk 2D Interactive Showcase Controller
   - Live Draggable 120fps Multi-Window Compositor Simulator
   - Real-Device Xiaomi Pad 8 Pro & Xiaomi 14 Pro Interactive Gallery + Lightbox
   - Architecture Blueprint Inspector · 4-Channel Activation Studio
   - Shared Dual-Theme Circular Iris Transition & ZH/EN Bilingual Sync
   ========================================================================== */
(() => {
  'use strict';

  const THEME_KEY = 'opaldesk-showcase-theme-v1';
  const LANG_KEY = 'opaldesk-showcase-lang-v1';

  function safeGet(k, fb = null) {
    try { const v = window.localStorage.getItem(k); return v !== null ? v : fb; } catch (_) { return fb; }
  }
  function safeSet(k, v) { try { window.localStorage.setItem(k, v); } catch (_) {} }

  const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const savedTheme = safeGet(THEME_KEY);
  const state = {
    theme: (savedTheme === 'pure-frost' || savedTheme === 'prism-pulse')
      ? savedTheme
      : (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'prism-pulse' : 'pure-frost'),
    lang: safeGet(LANG_KEY) === 'en' ? 'en' : 'zh',
    layout: 'split2',
    filter: 'all',
    archId: 'daemon',
    codeTab: 'wireless',
    lbIndex: 0
  };

  /* Real-Device Screenshot Catalog (All 9 Verified Hardware Captures) */
  const SHOTS = [
    {
      id: 'dex-light',
      cat: 'dex',
      portrait: false,
      src: './assets/screenshots/dex-workstation-light.jpg',
      device: 'Xiaomi Pad 8 Pro · 3040×1904 · Android 17',
      zhTitle: '本机 120fps 液态毛玻璃多窗工作站（晴空浅色）',
      enTitle: '120fps Liquid Glass Multi-Window Desk (Light Frost)',
      zhDesc: '世界时钟与科学计算器 50/50 左右分屏运行在各自独立 VirtualDisplay 中，单 RenderNode 合成红黄绿 macOS 顶栏与外扩散阴影。',
      enDesc: 'World Clock & Scientific Calculator split 50/50 inside dedicated VirtualDisplays with unified single-RenderNode macOS glass chrome.'
    },
    {
      id: 'dex-ctrl-dark',
      cat: 'dex',
      portrait: false,
      src: './assets/screenshots/dex-control-dark.jpg',
      device: 'Xiaomi Pad 8 Pro · 曜石暗色主题',
      zhTitle: '曜石深色主题 + 实时桌面控制中心',
      enTitle: 'Obsidian Dark Theme & Live Control Center',
      zhDesc: '支持 60–120Hz 帧率实时生效、窗口内容 DPI 热刷新、浅色/跟随/深色毛玻璃一键切换、7 套分屏模板与自定义相册壁纸。',
      enDesc: 'Live 60–120Hz refresh control, hot-reload window DPI, Light/Dark glass themes, 7 one-tap split templates, and custom wallpapers.'
    },
    {
      id: 'dex-dark',
      cat: 'dex',
      portrait: false,
      src: './assets/screenshots/dex-workstation-dark.jpg',
      device: 'Xiaomi Pad 8 Pro · 曜石暗色主题',
      zhTitle: '曜石深色液态毛玻璃双窗分屏',
      enTitle: 'Obsidian Dark Dual-Window Split Workstation',
      zhDesc: '深色毛玻璃顶栏、底部浮动 8 席程序坞与深海壁纸和谐融合，纯拖拽仅改客户端 GPU 矩阵偏移，零 IPC 通信。',
      enDesc: 'Deep obsidian frosted window headers & floating glass dock over dark wallpaper with zero-IPC GPU-matrix dragging.'
    },
    {
      id: 'cast-desktop',
      cat: 'cast',
      portrait: false,
      src: './assets/screenshots/cast-desktop-phone-to-tablet.jpg',
      device: 'Xiaomi 14 Pro → Xiaomi Pad 8 Pro · 2560×1600 @ 120Hz',
      zhTitle: '手机 → 平板无线 Wi-Fi TLS 投屏桌面',
      enTitle: 'Phone → Tablet Wireless Wi-Fi TLS Desktop Cast',
      zhDesc: '免 PIN 授权后通过 TLS 1.3 直连串流，手机应用以独立竖屏形态（560dpi 宿主超采样）零黑边运行在平板 120Hz 桌面中。',
      enDesc: 'PIN-free TLS 1.3 streaming runs phone apps in portrait 560dpi host-supersampled mode on the tablet at 2560×1600 @ 120Hz.'
    },
    {
      id: 'cast-floating',
      cat: 'cast',
      portrait: false,
      src: './assets/screenshots/cast-floating-app.jpg',
      device: 'Xiaomi 14 Pro → Xiaomi Pad 8 Pro · 独立悬浮小窗',
      zhTitle: '跨设备单应用全局悬浮小窗（1:1 宿主 DPI）',
      enTitle: 'Cross-Device Standalone Floating App Window',
      zhDesc: '无需切桌面，将手机端应用直接以 macOS 液态毛玻璃小窗悬浮在平板任意界面之上，支持对角缩放、远端输入法自动转发与零阴影闪烁。',
      enDesc: 'Float any phone app directly over tablet apps with macOS Liquid Glass chrome, corner resize, remote IME forwarding, and zero flicker.'
    },
    {
      id: 'phone-trackpad',
      cat: 'cast',
      portrait: true,
      src: './assets/screenshots/phone-trackpad.jpg',
      device: 'Xiaomi 14 Pro · 1440×3200 · 投屏触控板',
      zhTitle: '手机端新版液态毛玻璃精密触控板 + 一键息屏',
      enTitle: 'Redesigned Phone Liquid Glass Trackpad & Screen-Off',
      zhDesc: '120Hz FLAG_ONEWAY 光标直通，顶部支持外接键鼠一键切本机/投屏端，底部 50/50 硬件按键条搭配「拖拽锁定 | 显示桌面 | 一键息屏」。',
      enDesc: '120Hz pointer stream with mouse target switcher, 50/50 hardware-style L/R buttons, and Drag Lock / Desktop / Screen-Off pills.'
    },
    {
      id: 'phone-casting',
      cat: 'console',
      portrait: true,
      src: './assets/screenshots/phone-casting-console.jpg',
      device: 'Xiaomi 14 Pro · 投屏态主控台',
      zhTitle: '手机端投屏态主控台与永久信任设备管理',
      enTitle: 'Phone Active-Cast Console & Trust Management',
      zhDesc: '投屏期间自动锁定本机 DeX 避免双端冲突，支持一键打开触控板、物理息屏省电、TLS 短指纹核验与随时撤销永久信任设备。',
      enDesc: 'Automatically locks local DeX during cast, exposes one-tap Trackpad & physical Screen-Off, plus TLS fingerprint & trust revocation.'
    },
    {
      id: 'tablet-console',
      cat: 'console',
      portrait: false,
      src: './assets/screenshots/tablet-console.jpg',
      device: 'Xiaomi Pad 8 Pro · Apple HIG 主控台',
      zhTitle: '平板端极简 Apple HIG 三卡片主控台',
      enTitle: 'Clean Apple HIG Three-Card Tablet Console',
      zhDesc: '收敛为工作站 Hero、局域网 mDNS 自动发现互联卡片（一键投屏桌面 / 应用小窗）与 USB-C/HDMI 外接显示器开关，零噪点。',
      enDesc: 'Focused three-card layout: Workstation Hero, mDNS Peer Discovery (Desktop / Floating App), and USB-C / HDMI Auto-Workstation.'
    },
    {
      id: 'tablet-settings',
      cat: 'console',
      portrait: false,
      src: './assets/screenshots/tablet-settings.jpg',
      device: 'Xiaomi Pad 8 Pro · 设置与安全更新',
      zhTitle: '设置页：常用排序、应用内安全更新与虚拟屏池配额',
      enTitle: 'Settings: Usage Sort, GitHub OTA & VirtualDisplay Pool',
      zhDesc: '支持启动器按打开次数排序、GitHub Releases 直连 SHA-256 校验更新、IInputManager / /dev/uhid 输入路由选择与 3–8 实时屏配额调节。',
      enDesc: 'Usage-frequency launcher sorting, SHA-256 verified GitHub Releases in-app updater, InputManager / uhid router, and 3–8 live VD quota.'
    }
  ];

  /* Featured Tablet / Phone Quick Switch Maps */
  const TABLET_VIEWS = {
    light: { src: './assets/screenshots/dex-workstation-light.jpg', shotIdx: 0, zh: SHOTS[0].zhDesc, en: SHOTS[0].enDesc },
    dark: { src: './assets/screenshots/dex-workstation-dark.jpg', shotIdx: 2, zh: SHOTS[2].zhDesc, en: SHOTS[2].enDesc },
    ctrl: { src: './assets/screenshots/dex-control-dark.jpg', shotIdx: 1, zh: SHOTS[1].zhDesc, en: SHOTS[1].enDesc },
    cast: { src: './assets/screenshots/cast-desktop-phone-to-tablet.jpg', shotIdx: 3, zh: SHOTS[3].zhDesc, en: SHOTS[3].enDesc },
    float: { src: './assets/screenshots/cast-floating-app.jpg', shotIdx: 4, zh: SHOTS[4].zhDesc, en: SHOTS[4].enDesc }
  };
  const PHONE_VIEWS = {
    trackpad: { src: './assets/screenshots/phone-trackpad.jpg', shotIdx: 5, zh: SHOTS[5].zhDesc, en: SHOTS[5].enDesc },
    casting: { src: './assets/screenshots/phone-casting-console.jpg', shotIdx: 6, zh: SHOTS[6].zhDesc, en: SHOTS[6].enDesc }
  };

  /* Architecture Blueprint Inspector Details */
  const ARCH_DETAILS = {
    daemon: {
      path: 'bridge/src/io/github/lengsukq/opaldesk/daemon/OpalDeskDaemon.java',
      zhTitle: 'OpalDeskDaemon · 免 Root 降权守护进程',
      enTitle: 'OpalDeskDaemon · Rootless uid=2000 Shell Daemon',
      zhDesc: '纯 Java 11 编译为 165 KB 的 assets/opaldesk.jar。即使通过 Root 启动，也会在打开控制 Socket 或注册 Binder 前立即执行 dropRootFailClosed() 降权至 uid=2000 (u:r:shell:s0)。',
      enDesc: 'Pure Java 11 daemon packaged as a 165 KB DEX jar. Even when launched via su, it immediately drops privileges fail-closed to uid=2000 (u:r:shell:s0) before opening any socket or Binder service.',
      zhBullets: ['抽象命名空间单实例控制 Socket 防多开冲突', 'Binder 服务端严格校验调用方 UID 与 APK 签名证书 SHA-256', '可选 --root-enhance 仅在降权前写入标准 AOSP 多窗开关'],
      enBullets: ['Single-instance abstract control socket prevents orphan daemons', 'Fail-closed Binder caller UID + APK signing SHA-256 verification', 'Optional --root-enhance sets standard AOSP resizable flags before drop']
    },
    pool: {
      path: 'bridge/src/io/github/lengsukq/opaldesk/daemon/compositor/VirtualDisplayPool.java',
      zhTitle: 'VirtualDisplayPool · LRU 虚拟屏池与快照休眠',
      enTitle: 'VirtualDisplayPool · LRU Lease Pool & Snapshot Hibernation',
      zhDesc: '绕过 Android 14+ 对单 UID 并发 VirtualDisplay 的配额上限，并动态自适应各 OEM ROM 实际可用槽位。',
      enDesc: 'Bypasses Android 14+ per-UID VirtualDisplayAdapter quotas while dynamically adapting to stricter OEM ceilings.',
      zhBullets: ['前台活跃窗持有实时 VirtualDisplay（3–8 席可调）', '旧后台窗自动捕获 HardwareBuffer JPEG TaskSnapshot 并释放槽位', '点击休眠卡片 150ms 无缝挂载虚拟屏原地续跑'],
      enBullets: ['Foreground windows hold live VirtualDisplay leases (3–8 configurable)', 'Older background windows capture HardwareBuffer TaskSnapshots and release slots', 'Clicking any hibernated card re-leases and wakes seamlessly in ~150ms']
    },
    input: {
      path: 'bridge/src/io/github/lengsukq/opaldesk/daemon/input/PerDisplayInputRouter.java',
      zhTitle: 'PerDisplayInputRouter · 双通道跨屏键鼠路由',
      enTitle: 'PerDisplayInputRouter · Dual-Channel Input & /dev/uhid',
      zhDesc: '负责按 displayId 精准直通触摸、压感笔、鼠标滚轮与物理键盘，并提供内核级自动回退与全局物理鼠标路由。',
      enDesc: 'Routes 10-finger touch, stylus, scroll wheel, and keyboard per displayId with automatic Linux kernel /dev/uhid fallback.',
      zhBullets: ['主通道 IInputManager.injectInputEvent 零延迟按 displayId 分发', '被定制 ROM 拦截时自动创建 Linux /dev/uhid 虚拟绝对指针与键盘', 'ExternalMouseRouter 支持蓝牙/USB 鼠标一键切本机或投屏端'],
      enBullets: ['Primary IInputManager injection targeted per displayId', 'Automatic Linux /dev/uhid virtual pointer + keyboard fallback on blocked ROMs', 'ExternalMouseRouter switches physical mouse between local & cast displays']
    },
    compositor: {
      path: 'helper/src/io/github/lengsukq/opaldesk/compositor/OpalCompositorWorkspace.kt',
      zhTitle: 'OpalCompositorWorkspace · 120fps 单 RenderNode 合成器',
      enTitle: 'OpalCompositorWorkspace · 120fps Single-RenderNode GPU UI',
      zhDesc: '基于 Jetpack Compose Foundation + OpalPrimitives 零厂商耦合构建，把标题栏、外扩散阴影、8 向缩放与实时 TextureView 合成于同一 RenderNode。',
      enDesc: 'Built purely on Compose Foundation with zero vendor dependencies: chrome, spread shadows, resize grips, and live TextureView compose in one RenderNode.',
      zhBullets: ['拖拽窗口纯改 GPU 矩阵偏移：0 次 Binder IPC、0 次虚拟屏重绘', 'ClipOp.Difference 内部挖空多层外扩散阴影，彻底杜绝半透明灰斑', '支持 7 套一键分屏模板、Exposé 全景与自定义相册壁纸'],
      enBullets: ['Window translation mutates GPU matrix only: 0 Binder IPC, 0 VirtualDisplay resize', 'ClipOp.Difference exterior shadows keep frosted glass crystal clear', '7 one-tap split templates, Mission Control Exposé & custom wallpapers']
    },
    cast: {
      path: 'helper/src/io/github/lengsukq/opaldesk/cast/PeerFloatingAppService.kt',
      zhTitle: 'OpalCast · 无线互投、独立小窗与远端输入法',
      enTitle: 'OpalCast · Wireless Desktop, Floating App & Remote IME',
      zhDesc: '基于 mDNS 自动发现、免 PIN 弹窗授权与 TLS 1.3 加密构建跨设备桌面投屏与单应用全局悬浮小窗。',
      enDesc: 'mDNS peer discovery, PIN-free consent modal, and TLS 1.3 streaming powering Desktop Cast and Standalone Floating App windows.',
      zhBullets: ['OpalPeerCastMutex 引用计数单向防环锁，杜绝双端互投递归死循环', 'TYPE_IME_REQUEST = 0x16 实现发送端输入框聚焦后接收端自动弹键盘', '宿主 DPI 超采样算法保证微信、拼多多等应用小窗 1:1 清晰排版'],
      enBullets: ['Reference-counted OpalPeerCastMutex prevents A↔B recursive cast loops', 'TYPE_IME_REQUEST = 0x16 automatically pops local IME on the receiver', 'Host-DPI supersampling keeps floating apps crisp at 1:1 native density']
    },
    updater: {
      path: 'helper/src/io/github/lengsukq/opaldesk/update/AppUpdateManager.kt',
      zhTitle: 'AppUpdateManager · GitHub Releases 安全应用内更新',
      enTitle: 'AppUpdateManager · Verified GitHub Releases In-App OTA',
      zhDesc: '内置安全更新模块，支持启动静默检查（每 6 小时节流）与手动检查，全程 fail-closed 校验。',
      enDesc: 'Built-in in-app updater supporting 6-hour throttled silent checks and manual checks directly against official GitHub Releases.',
      zhBullets: ['严格限制官方 github.com / *.githubusercontent.com HTTPS 域名白名单', '流式下载实时进度与取消，完成后自动核验 SHA-256 摘要与 APK 包名', '兼容 GitHub API 403 限流自动回退网页重定向与浏览器直达'],
      enBullets: ['Strict HTTPS allowlist restricted to official GitHub release hosts', 'Streaming download with cancel, SHA-256 digest verification & package check', 'Graceful 403 rate-limit fallback via /releases/latest redirect']
    },
    macos: {
      path: 'macos/OpalDeskMac/',
      zhTitle: 'OpalDeskMac · 原生 Swift 6 macOS 伴侣接收端',
      enTitle: 'OpalDeskMac · Native Swift 6 macOS Companion App',
      zhDesc: '专为 Apple Silicon / macOS 14+ 打造的轻量级纯原生接收端（仅 2.4 MB），无 Electron 套壳。',
      enDesc: 'Lightweight 2.4 MB native Swift 6 companion app for Apple Silicon / macOS 14+ with zero Electron overhead.',
      zhBullets: ['支持 USB ADB 端口转发与局域网 Wi-Fi TLS + TOFU 指纹校验', '硬件加速 HEVC / H.264 视频解码 + AAC 低延迟音频串流', '双向剪贴板图文同步、文件互传与 macOS Command→Ctrl 快捷键映射'],
      enBullets: ['Supports USB ADB forwarding and LAN Wi-Fi TLS + TOFU verification', 'Hardware-accelerated HEVC/H.264 decode with low-latency AAC audio', 'Two-way clipboard sync, file transfer, and Command→Ctrl key mapping']
    }
  };

  /* 4-Channel Activation & Build Code Studio Snippets */
  const CODE_SNIPPETS = {
    wireless: {
      zh: `# ① 推荐：内置无线调试（无需电脑、免 Root）
1. 在手机/平板安装最新版 OpalDesk APK 并打开 App
2. 进入「系统设置 → 开发者选项 → 开启无线调试 → 使用配对码配对设备」
3. 无需切出设置：在顶部通知栏直接输入 6 位配对码即可完成本机自激活
4. APK 内置 libadb.so 自动提取 assets/opaldesk.jar 并以 uid=2000 拉起守护进程`,
      en: `# 1. Recommended: Built-in Wireless Debugging (No PC, Rootless)
1. Install and launch the latest OpalDesk APK on Android 11–17
2. Open Developer Options -> Wireless Debugging -> Pair device with pairing code
3. Enter the 6-digit pairing code directly in OpalDesk's notification prompt
4. Built-in libadb.so extracts assets/opaldesk.jar and starts uid=2000 daemon`
    },
    shizuku: {
      zh: `# ② Shizuku / Sui 一键激活（免电脑）
1. 确保设备已启动 Shizuku（无线调试模式或 Sui）
2. 打开 OpalDesk → 点击激活引导卡片中的「Shizuku 一键激活」
3. 授予 Shizuku 授权后自动校验签名并启动 OpalDeskDaemon（uid=2000）`,
      en: `# 2. Shizuku / Sui One-Tap Activation (No PC)
1. Ensure Shizuku (Wireless ADB or Sui) is running on the device
2. Open OpalDesk -> tap "Activate via Shizuku" in the activation modal
3. Grant Shizuku permission to verify signature and start OpalDeskDaemon (uid=2000)`
    },
    adb: {
      zh: `adb shell "rm -f /data/local/tmp/opd_src.apk /data/local/tmp/opaldesk.jar.new && pm path io.github.lengsukq.opaldesk | grep '^package:' | cut -d: -f2 | head -n1 | xargs cat > /data/local/tmp/opd_src.apk && unzip -p /data/local/tmp/opd_src.apk assets/opaldesk.jar > /data/local/tmp/opaldesk.jar.new && rm -f /data/local/tmp/opd_src.apk && chmod 644 /data/local/tmp/opaldesk.jar.new && mv -f /data/local/tmp/opaldesk.jar.new /data/local/tmp/opaldesk.jar && (CLASSPATH=/data/local/tmp/opaldesk.jar nohup app_process /data/local/tmp io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon >/data/local/tmp/opaldesk.log 2>&1 </dev/null &)"`,
      en: `adb shell "rm -f /data/local/tmp/opd_src.apk /data/local/tmp/opaldesk.jar.new && pm path io.github.lengsukq.opaldesk | grep '^package:' | cut -d: -f2 | head -n1 | xargs cat > /data/local/tmp/opd_src.apk && unzip -p /data/local/tmp/opd_src.apk assets/opaldesk.jar > /data/local/tmp/opaldesk.jar.new && rm -f /data/local/tmp/opd_src.apk && chmod 644 /data/local/tmp/opaldesk.jar.new && mv -f /data/local/tmp/opaldesk.jar.new /data/local/tmp/opaldesk.jar && (CLASSPATH=/data/local/tmp/opaldesk.jar nohup app_process /data/local/tmp io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon >/data/local/tmp/opaldesk.log 2>&1 </dev/null &)"`
    },
    build: {
      zh: `# ④ 本地从源码编译、运行全量单元测试与部署
./scripts/init-signing.sh                         # 首次生成本地 PKCS12 签名证书
./gradlew :bridge:test :helper:testDebugUnitTest  # 全量纯 JVM 单元测试
./gradlew :helper:assembleRelease                 # 自动打包 assets/opaldesk.jar 并签名
./scripts/dev-deploy.sh --logs                    # 一键安装并拉起守护进程

# 编译原生 Swift 6 macOS 伴侣端
cd macos/OpalDeskMac && swift test && swift build -c release && ../../scripts/build-macos-app.sh --skip-build`,
      en: `# 4. Build, Test & Deploy from Source
./scripts/init-signing.sh                         # Initialize local PKCS12 key once
./gradlew :bridge:test :helper:testDebugUnitTest  # Run full JVM unit test suite
./gradlew :helper:assembleRelease                 # Auto-embeds assets/opaldesk.jar
./scripts/dev-deploy.sh --logs                    # Install APK & launch daemon

# Build native Swift 6 macOS Companion
cd macos/OpalDeskMac && swift test && swift build -c release && ../../scripts/build-macos-app.sh --skip-build`
    }
  };

  /* Bilingual Dictionary */
  const I18N = {
    zh: {
      nav_sim: '多窗模拟', nav_gallery: '真机实拍', nav_compare: '路线对比', nav_features: '核心特性', nav_arch: '三端架构', nav_dl: '下载激活',
      nav_3d: '3D 舞台', theme_light: '晴霜浅色', theme_dark: '曜石暗色', nav_get: '下载 APK',
      hero_badge: 'ANDROID 11–17 · 95%+ 免 ROOT · 120FPS GPU 合成 · MIT 开源',
      hero_t1: '一窗一独立虚拟屏，', hero_t2: '全品牌安卓通用桌面工作站。',
      hero_sub: '彻底告别厂商私有 Freeform 小窗与撕裂悬浮标题栏。120fps 单 RenderNode 皮肉合一液态毛玻璃桌面、LRU 快照休眠、外接 2K/4K 满屏与手机↔平板↔Mac 无线互联。',
      cta_dl: '获取最新 APK / Mac 伴侣端', cta_gallery: '浏览双机真机实拍', cta_3d: '前往 3D 交互舞台 →',
      sim_label: '在线试玩多窗合成器：', lay_split2: '左右二分', lay_master: '主副 2:1', lay_cols3: '三列分屏', lay_grid4: '四宫格', lay_cascade: '阶梯自由层叠', lay_hib: '模拟 LRU 快照休眠 / 唤醒', sim_reset: '重置排布',
      gal_eyebrow: 'REAL HARDWARE CAPTURES · ZERO MOCKUPS', gal_title: '双机真机实拍，所见即所得',
      gal_desc: '以下 9 组画面全部直采自 <strong>Xiaomi Pad 8 Pro（Android 17）</strong> 与 <strong>Xiaomi 14 Pro（Android 16）</strong> 运行 OpalDesk 的真实屏幕，点击任意截图可放大细看像素级液态毛玻璃细节。',
      hw_tab_title: '本机 120fps 液态毛玻璃多窗工作站', hw_tab_light: '晴空浅色', hw_tab_dark: '曜石深色', hw_tab_ctrl: '控制中心', hw_tab_cast: '无线投屏', hw_tab_float: '独立小窗',
      hw_ph_title: '手机端精密触控板 & 投屏中枢', hw_ph_tp: '精密触控板', hw_ph_cast: '投屏态主控',
      flt_all: '全部实拍 (9)', flt_dex: '本机多窗桌面 (3)', flt_cast: '跨设备互投 & 小窗 (3)', flt_console: '主控台 & 设置 (3)',
      cmp_eyebrow: 'ARCHITECTURE COMPARISON · 告别路线妥协', cmp_title: '为什么彻底抛弃 Freeform + 悬浮壳？',
      cmp_desc: '从早期实战踩坑中彻底重构为 Wayland 式独立虚拟屏合成器，从根源消灭碎片化、拖拽撕裂、外接屏失活与高权限依赖。',
      cmp_h_dim: '对比维度', cmp_h_opal: 'OpalDesk（Wayland 式通用合成器）', cmp_h_old: '传统 Freeform + 悬浮壳方案', cmp_h_oem: '厂商私有桌面模式（DeX / 电脑模式）',
      cmp_r1_d: '窗口运行模式', cmp_r1_o: '<strong>100% 标准 AOSP FULLSCREEN (1)</strong>，一窗一独立 VirtualDisplay', cmp_r1_old: '魔改 Freeform (5) / ITaskOrganizer，升级即易失效', cmp_r1_oem: '深度绑定特定厂商框架，换品牌即不可用',
      cmp_r2_d: '画面与标题栏合成', cmp_r2_o: '<strong>单 RenderNode 皮肉合一</strong>：纯拖拽 0 IPC、0 虚拟屏重绘', cmp_r2_old: '悬浮窗外挂标题栏，拖拽撕裂、掉帧、热区错位', cmp_r2_oem: '系统自带，但外观/阴影/分屏模板难以自由定制',
      cmp_r3_d: '多窗并发上限', cmp_r3_o: '<strong>VirtualDisplayPool LRU 休眠</strong>：突破 Android 14+ 配额，点击 150ms 唤醒', cmp_r3_old: '受系统小窗数硬上限约束，容易互相挤掉', cmp_r3_oem: '多为固定 3–5 窗，不支持自定义快照池容量',
      cmp_r4_d: '跨设备无线互联', cmp_r4_o: '<strong>手机↔平板桌面/独立小窗 + 原生 Swift 6 Mac 伴侣端</strong> + 远端输入法自动转发', cmp_r4_old: '几乎无原生跨端协议，依赖第三方投屏拼凑', cmp_r4_oem: '仅限同生态全家桶，跨品牌安卓与 Mac 受限',
      cmp_r5_d: '外接屏与物理键鼠', cmp_r5_o: '<strong>2K/4K 100Hz+ 满屏不中断</strong> + 鼠标一键切本机/投屏端 + <code>/dev/uhid</code> 回退', cmp_r5_old: '切出 App 外接屏即黑屏，跨屏鼠标常被 ROM 拦截', cmp_r5_oem: '部分机型仅支持 60Hz 镜像或缺少硬件鼠标切换',
      cmp_r6_d: '权限与安全模型', cmp_r6_o: '<strong>零无障碍 · 95%+ 免 Root</strong>，守护进程强制降权 <code>uid=2000</code> + APK SHA-256 校验', cmp_r6_old: '强依赖敏感无障碍服务与常驻悬浮窗权限', cmp_r6_oem: '闭源系统内置，不开放架构与源码审计',
      feat_eyebrow: 'UNIVERSAL COMPOSITOR ARCHITECTURE', feat_title: '六大核心能力，稳如原生系统',
      feat_desc: '每一项特性均围绕真实高频痛点迭代，配套纯 JVM 确定性单元测试与多机型实机回归验证。',
      f1_t: '宿主 DPI 超采样 · 小窗清晰零裁切', f1_d: '新增 <code>resolveWindowBufferSpec</code> 与 <code>resolveStandaloneStreamSpec</code>，在保持逻辑 dp 不变的前提下按比例向宿主机密度超采样，彻底解决微信等兼容应用字体 2× 爆炸与拼多多小窗极小字/闪动问题。',
      f2_t: 'VirtualDisplayPool LRU 智能休眠', f2_d: '动态探测严苛 ROM 单 UID 虚拟屏上限，前台窗口持有实时屏幕，后台旧窗自动捕获 <code>HardwareBuffer</code> 高清快照并释放槽位；点击休眠卡片 150ms 原地挂载无缝续跑。',
      f3_t: '跨设备无线投屏 & 单应用全局悬浮小窗', f3_d: '免 PIN 交互弹窗授权 + TLS 1.3 直连与 <code>OpalPeerCastMutex</code> 单向防环锁；手机↔平板支持完整桌面投屏或任选单个应用以 macOS 液态毛玻璃小窗悬浮运行，自带 <code>TYPE_IME_REQUEST</code> 远端输入法自动转发。',
      f4_t: '液态毛玻璃精密触控板 + 一键物理息屏', f4_d: '无线或 USB-C 投屏时本机即刻化为 120Hz 精密触控板，配备 50/50「左键·按住拖拽 | 右键·返回」硬件按键条与「拖拽锁定 | 显示桌面 | 一键息屏」胶囊，支持 <code>SurfaceControl</code> 物理灭屏省电与双击亮屏。',
      f5_t: '全局物理鼠标跨屏路由 & /dev/uhid 内核回退', f5_d: '蓝牙/USB 物理鼠标一键自由切换归属本机或投屏端，0.50×–3.00× 无级变速并自动隐藏虚拟光标；遇 OEM 封锁跨屏触摸注入时自动创建 Linux <code>/dev/uhid</code> 内核虚拟外设穿透限制。',
      f6_t: '95%+ 免 Root 降权 + GitHub 直连安全更新', f6_d: '内置无线调试（<code>libadb.so</code>）免电脑激活，守护进程 fail-closed 降权至 <code>uid=2000</code> 并严格校验 APK 签名；内置应用更新直连 GitHub Releases，自动执行 SHA-256 摘要与包名双重校验。',
      arch_eyebrow: 'CLEAN MODULAR ARCHITECTURE', arch_title: '三端分层架构蓝图（点击模块查看详情）',
      arch_desc: '核心几何、虚拟屏池、排序器与防环锁完全剥离 Android 单例依赖，保证高确定性纯 JVM 单元测试覆盖。',
      arch_bus_ipc: '↕ 签名 SHA-256 双向鉴权 Binder IPC · GL_TEXTURE_EXTERNAL_OES 零拷贝 ↕',
      arch_bus_stream: '↕ HEVC/H.264 + AAC · Wi-Fi TLS 1.3 / USB ADB 端口转发 ↕',
      dl_eyebrow: 'FREE · MIT OPEN SOURCE · ZERO SUBSCRIPTION', dl_title: '下载 OpalDesk 最新版，30 秒免 Root 激活',
      dl_desc: '支持 Android 11–17 全品牌手机与平板（内置无线调试免电脑激活）以及 Apple Silicon macOS 14+ 原生伴侣端。',
      dl_android_t: 'OpalDesk Android 安装包 (APK)',
      dl_android_d: '已内嵌同签名守护进程 <code>opaldesk.jar</code>，支持手机/平板本机多窗、外接显示器、跨设备互投及应用内 GitHub 直连自动校验更新。',
      dl_mac_t: 'OpalDesk macOS 伴侣端 (ZIP)',
      dl_mac_d: '纯原生 Swift 6 macOS 接收伴侣端，支持 USB / Wi-Fi TLS 低延迟 HEVC 视频与 AAC 音频串流、双向剪贴板与快捷映射。',
      dl_android_btn: '前往 Releases 下载最新 APK', dl_mac_btn: '前往 Releases 下载 macOS 伴侣端',
      tab_wireless: '① 内置无线调试（推荐）', tab_shizuku: '② Shizuku / Sui', tab_adb: '③ 电脑单行 ADB', tab_build: '④ 源码编译测试',
      btn_copy: '复制命令', ft_3d: '3D 舞台主页'
    },
    en: {
      nav_sim: 'Simulator', nav_gallery: 'Real Shots', nav_compare: 'Compare', nav_features: 'Features', nav_arch: 'Architecture', nav_dl: 'Download',
      nav_3d: '3D Stage', theme_light: 'Light Frost', theme_dark: 'Obsidian', nav_get: 'Get APK',
      hero_badge: 'ANDROID 11–17 · 95%+ ROOTLESS · 120FPS GPU COMPOSITOR · MIT',
      hero_t1: 'One VirtualDisplay per window.', hero_t2: 'Universal Android desktop workstation.',
      hero_sub: 'Zero OEM Freeform hacks and zero split-brain overlay chrome. Unified 120fps Liquid Glass compositing, LRU snapshot hibernation, 2K/4K external displays, and Phone ↔ Tablet ↔ Mac wireless casting.',
      cta_dl: 'Download Latest APK / Mac App', cta_gallery: 'Browse Real Hardware Shots', cta_3d: 'Enter 3D WebGL Stage →',
      sim_label: 'Interactive Compositor Demo:', lay_split2: '50/50 Split', lay_master: 'Master 2:1', lay_cols3: '3 Columns', lay_grid4: '2×2 Grid', lay_cascade: 'Free Cascade', lay_hib: 'Toggle LRU Snapshot Wake', sim_reset: 'Reset',
      gal_eyebrow: 'REAL HARDWARE CAPTURES · ZERO MOCKUPS', gal_title: 'Captured live on real devices.',
      gal_desc: 'All 9 screenshots were captured directly from <strong>Xiaomi Pad 8 Pro (Android 17)</strong> and <strong>Xiaomi 14 Pro (Android 16)</strong> running OpalDesk. Click any image to inspect full-resolution Liquid Glass details.',
      hw_tab_title: 'Local 120fps Liquid Glass Multi-Window Workstation', hw_tab_light: 'Light Frost', hw_tab_dark: 'Obsidian Dark', hw_tab_ctrl: 'Control Center', hw_tab_cast: 'Desktop Cast', hw_tab_float: 'Floating App',
      hw_ph_title: 'Phone Precision Trackpad & Cast Hub', hw_ph_tp: 'Trackpad', hw_ph_cast: 'Casting State',
      flt_all: 'All Captures (9)', flt_dex: 'Multi-Window Desk (3)', flt_cast: 'Cross-Device & Float (3)', flt_console: 'Console & Settings (3)',
      cmp_eyebrow: 'ARCHITECTURE COMPARISON · ZERO COMPROMISE', cmp_title: 'Why abandon Freeform + Overlay hacks?',
      cmp_desc: 'Rewritten from the ground up into a Wayland-style VirtualDisplay compositor that eliminates OEM fragmentation, drag tearing, and accessibility permissions.',
      cmp_h_dim: 'Dimension', cmp_h_opal: 'OpalDesk (Wayland Compositor)', cmp_h_old: 'Legacy Freeform + Overlay', cmp_h_oem: 'OEM Proprietary Desktop',
      cmp_r1_d: 'Windowing Mode', cmp_r1_o: '<strong>100% Standard AOSP FULLSCREEN (1)</strong>, dedicated VirtualDisplay per window', cmp_r1_old: 'Hacked Freeform (5) / ITaskOrganizer, fragile across updates', cmp_r1_oem: 'Locked to one vendor ROM; unavailable on other brands',
      cmp_r2_d: 'Chrome Composition', cmp_r2_o: '<strong>Unified Single RenderNode</strong>: 0 IPC & 0 VirtualDisplay resize on drag', cmp_r2_old: 'Floating overlay headers cause drag tearing & hit-test drift', cmp_r2_oem: 'Native system chrome, limited visual & layout customization',
      cmp_r3_d: 'Multi-Window Limit', cmp_r3_o: '<strong>VirtualDisplayPool LRU Hibernation</strong>: beats Android 14+ quotas, 150ms wake', cmp_r3_old: 'Hard-capped by OEM freeform task limits', cmp_r3_oem: 'Typically capped at 3–5 windows with no snapshot pooling',
      cmp_r4_d: 'Cross-Device Cast', cmp_r4_o: '<strong>Phone ↔ Tablet Desktop / Floating App + Swift 6 Mac App</strong> + Remote IME', cmp_r4_old: 'No native cross-device protocol; relies on third-party tools', cmp_r4_oem: 'Walled garden limited to same-brand ecosystem devices',
      cmp_r5_d: 'External & Mouse', cmp_r5_o: '<strong>Uninterrupted 2K/4K 100Hz+</strong> + Mouse Local/Cast Toggle + <code>/dev/uhid</code>', cmp_r5_old: 'Blacks out when switching local apps; cross-display input blocked', cmp_r5_oem: 'Often capped at 60Hz mirror or lacks hardware mouse routing',
      cmp_r6_d: 'Security & Perms', cmp_r6_o: '<strong>Zero Accessibility · 95%+ Rootless</strong>, forced drop to <code>uid=2000</code> + SHA-256 auth', cmp_r6_old: 'Requires intrusive Accessibility & permanent overlay permissions', cmp_r6_oem: 'Closed-source system binary, not auditable',
      feat_eyebrow: 'UNIVERSAL COMPOSITOR ARCHITECTURE', feat_title: 'Six core pillars, built to feel native.',
      feat_desc: 'Every capability ships with deterministic pure-JVM unit tests and real multi-device regression verification.',
      f1_t: 'Host-DPI Supersampling · Razor-Sharp Small Windows', f1_d: 'Pure-JVM <code>resolveWindowBufferSpec</code> & <code>resolveStandaloneStreamSpec</code> scale virtual buffers toward host density while preserving logical dp, curing 2× oversized compat fonts & tiny floating-window text.',
      f2_t: 'VirtualDisplayPool LRU Snapshot Hibernation', f2_d: 'Dynamically adapts to strict OEM VirtualDisplay quotas. Foreground apps keep live displays while older windows save <code>HardwareBuffer</code> snapshots and release slots — waking in ~150ms on click.',
      f3_t: 'Wireless Desktop Cast & Standalone Floating Apps', f3_d: 'PIN-free interactive approval + TLS 1.3 + <code>OpalPeerCastMutex</code> anti-loop guard. Stream full desktop or float any phone app on your tablet with automatic <code>TYPE_IME_REQUEST</code> remote keyboard forwarding.',
      f4_t: 'Liquid Glass Precision Trackpad & One-Tap Screen-Off', f4_d: 'Turns your phone into a 120Hz trackpad with a 50/50 hardware-style L/R click bar and Drag Lock / Show Desktop / Screen-Off pills backed by AOSP <code>SurfaceControl</code> power-off + double-tap wake.',
      f5_t: 'Global Physical Mouse Router & /dev/uhid Fallback', f5_d: 'Route Bluetooth/USB mouse freely between local and cast screens (0.50×–3.00× speed) with automatic virtual-cursor hiding and Linux <code>/dev/uhid</code> kernel fallback when OEMs block injection.',
      f6_t: '95%+ Rootless uid=2000 & Verified GitHub OTA', f6_d: 'Built-in Wireless Debugging (<code>libadb.so</code>) activates without a PC. Daemon drops privileges fail-closed to <code>uid=2000</code>, with SHA-256 + package-verified GitHub Releases in-app updates.',
      arch_eyebrow: 'CLEAN MODULAR ARCHITECTURE', arch_title: 'Three-Tier Architecture Blueprint (Click any module)',
      arch_desc: 'Geometry, display pooling, launcher sorting, and anti-loop locks are decoupled from Android Binder singletons for 100% deterministic JVM testing.',
      arch_bus_ipc: '↕ SHA-256 Verified Binder IPC · Zero-Copy GL_TEXTURE_EXTERNAL_OES ↕',
      arch_bus_stream: '↕ HEVC/H.264 + AAC · Wi-Fi TLS 1.3 / USB ADB Forwarding ↕',
      dl_eyebrow: 'FREE · MIT OPEN SOURCE · ZERO SUBSCRIPTION', dl_title: 'Get Latest OpalDesk — Rootless in 30s',
      dl_desc: 'Runs on any Android 11–17 phone or tablet (PC-free Wireless Debugging built in) plus native Swift 6 Apple Silicon macOS 14+ companion.',
      dl_android_t: 'OpalDesk Android Package (APK)',
      dl_android_d: 'Embeds matching signed <code>opaldesk.jar</code> daemon. Supports local multi-window workstation, external monitors, cross-device casting, and verified GitHub in-app updates.',
      dl_mac_t: 'OpalDesk macOS Companion (ZIP)',
      dl_mac_d: 'Pure native Swift 6 macOS companion app featuring USB & Wi-Fi TLS low-latency HEVC video, AAC audio, clipboard sync, and Command→Ctrl mapping.',
      dl_android_btn: 'Download Latest APK on Releases', dl_mac_btn: 'Download macOS App on Releases',
      tab_wireless: '① Wireless ADB (No PC)', tab_shizuku: '② Shizuku / Sui', tab_adb: '③ One-Line PC ADB', tab_build: '④ Build from Source',
      btn_copy: 'Copy', ft_3d: '3D Interactive Stage'
    }
  };

  /* Toast Helper */
  const toastEl = document.getElementById('glassToastIsland');
  const toastText = document.getElementById('glassToastText');
  let toastTimer = 0;
  function showToast(msg) {
    if (!toastEl || !toastText) return;
    toastText.textContent = msg;
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('show'), 2200);
  }

  /* ========================================================================
     1. Interactive Multi-Window Compositor Simulator
     ======================================================================== */
  const simCanvas = document.getElementById('simCanvas');
  const simFocusedApp = document.getElementById('simFocusedApp');
  const simTelemetry = document.getElementById('simTelemetry');
  const PROFILES = ['桌面', '竖屏', '平板 4:3'];

  const SIM_WINDOWS = [
    { id: 'clock', zh: '世界时钟', en: 'World Clock', kpi: '14:51:31', subZh: '伦敦 06:51 · 纽约 01:51', subEn: 'London 06:51 · New York 01:51', profileIdx: 0, hibernated: false, z: 2 },
    { id: 'calc', zh: '科学计算器', en: 'Calculator', kpi: '120 × 100 = 12,000', subZh: '1:1 宿主 DPI 超采样 · 零裁切', subEn: '1:1 Host-DPI Supersampling', profileIdx: 0, hibernated: false, z: 3 },
    { id: 'browser', zh: 'OpalDesk GitHub', en: 'OpalDesk Repo', kpi: 'MIT Open Source', subZh: 'Wayland 式独立虚拟屏合成器', subEn: 'Universal Wayland Compositor', profileIdx: 0, hibernated: false, z: 1 },
    { id: 'phone', zh: '手机悬浮小窗', en: 'Phone Float App', kpi: '23116PN5BC', subZh: 'Wi-Fi TLS 1.3 · 560dpi 竖屏', subEn: 'Wi-Fi TLS 1.3 · 560dpi Portrait', profileIdx: 1, hibernated: true, z: 4 }
  ];

  let topZ = 5;
  const winEls = new Map();

  function computeLayoutFrames(mode) {
    const pad = 16;
    const w = simCanvas.clientWidth || 1000;
    const h = simCanvas.clientHeight || 440;
    const usableW = w - pad * 2;
    const usableH = h - pad * 2;
    const g = 12;

    if (mode === 'split2') {
      const half = (usableW - g) / 2;
      return {
        clock: { x: pad, y: pad, w: half, h: usableH, show: true },
        calc: { x: pad + half + g, y: pad, w: half, h: usableH, show: true },
        browser: { x: pad + 40, y: pad + 40, w: half * 0.85, h: usableH * 0.8, show: false },
        phone: { x: w - 260, y: pad + 18, w: 230, h: usableH - 36, show: false }
      };
    }
    if (mode === 'master21') {
      const left = (usableW - g) * 0.64;
      const right = usableW - g - left;
      const rh = (usableH - g) / 2;
      return {
        browser: { x: pad, y: pad, w: left, h: usableH, show: true },
        clock: { x: pad + left + g, y: pad, w: right, h: rh, show: true },
        calc: { x: pad + left + g, y: pad + rh + g, w: right, h: rh, show: true },
        phone: { x: w - 250, y: pad + 20, w: 220, h: usableH - 40, show: false }
      };
    }
    if (mode === 'cols3') {
      const col = (usableW - g * 2) / 3;
      return {
        clock: { x: pad, y: pad, w: col, h: usableH, show: true },
        calc: { x: pad + col + g, y: pad, w: col, h: usableH, show: true },
        browser: { x: pad + (col + g) * 2, y: pad, w: col, h: usableH, show: true },
        phone: { x: pad + 20, y: pad + 20, w: 220, h: usableH - 40, show: false }
      };
    }
    if (mode === 'grid4') {
      const cw = (usableW - g) / 2;
      const ch = (usableH - g) / 2;
      return {
        clock: { x: pad, y: pad, w: cw, h: ch, show: true },
        calc: { x: pad + cw + g, y: pad, w: cw, h: ch, show: true },
        browser: { x: pad, y: pad + ch + g, w: cw, h: ch, show: true },
        phone: { x: pad + cw + g, y: pad + ch + g, w: cw, h: ch, show: true }
      };
    }
    // cascade
    return {
      browser: { x: pad + 12, y: pad + 10, w: Math.min(480, usableW * 0.56), h: usableH * 0.78, show: true },
      clock: { x: pad + 130, y: pad + 42, w: Math.min(430, usableW * 0.48), h: usableH * 0.76, show: true },
      calc: { x: pad + 270, y: pad + 72, w: Math.min(400, usableW * 0.44), h: usableH * 0.74, show: true },
      phone: { x: Math.max(pad + 30, usableW - 230), y: pad + 24, w: 224, h: usableH * 0.84, show: true }
    };
  }

  function updateSimTelemetry() {
    const visible = SIM_WINDOWS.filter((w) => {
      const el = winEls.get(w.id);
      return el && el.style.display !== 'none';
    });
    const live = visible.filter((w) => !w.hibernated).length;
    const hib = visible.filter((w) => w.hibernated).length;
    if (simTelemetry) {
      simTelemetry.textContent = `RenderNode: 120fps · Drag IPC: 0 · Active VD: ${live}/6 · Snapshot: ${hib}`;
    }
  }

  function renderSimWindows() {
    if (!simCanvas) return;
    simCanvas.innerHTML = '';
    winEls.clear();

    SIM_WINDOWS.forEach((win) => {
      const el = document.createElement('div');
      el.className = `sim-win${win.hibernated ? ' hibernated' : ''}`;
      el.style.zIndex = String(win.z);
      el.innerHTML = `
        <div class="sim-win-bar">
          <span class="win-lights"><span></span><span></span><span></span></span>
          <span class="sim-win-title">${state.lang === 'en' ? win.en : win.zh}</span>
          <button type="button" class="win-profile-btn mono">${PROFILES[win.profileIdx]}</button>
        </div>
        <div class="sim-win-body">
          <div>
            <div class="sim-win-kpi mono">${win.kpi}</div>
            <div class="sim-win-sub">${state.lang === 'en' ? win.subEn : win.subZh}</div>
          </div>
          <span class="sim-win-badge mono">VirtualDisplay · FULLSCREEN (1)</span>
          ${win.hibernated ? `
            <div class="sim-hib-overlay">
              <span class="sim-hib-pill mono">TaskSnapshot 休眠中 (0 VD)</span>
              <span class="sim-win-sub">${state.lang === 'en' ? 'Click for 150ms instant wake' : '点击 150ms 无缝唤醒'}</span>
            </div>` : ''}
        </div>
      `;

      // Profile toggle button
      const profBtn = el.querySelector('.win-profile-btn');
      profBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        win.profileIdx = (win.profileIdx + 1) % PROFILES.length;
        profBtn.textContent = PROFILES[win.profileIdx];
        if (window.OpalSound) window.OpalSound.playClick('pill');
        showToast(`${state.lang === 'en' ? win.en : win.zh} → ${PROFILES[win.profileIdx]}`);
      });

      // Wake hibernated window on click
      const overlay = el.querySelector('.sim-hib-overlay');
      if (overlay) {
        overlay.addEventListener('click', () => {
          win.hibernated = false;
          win.z = ++topZ;
          if (window.OpalSound) window.OpalSound.playPulse(2, 0);
          showToast(state.lang === 'en' ? `${win.en} woke from TaskSnapshot in 150ms` : `${win.zh} 已从 TaskSnapshot 150ms 唤醒`);
          renderSimWindows();
          applySimLayout(state.layout);
        });
      }

      // Focus & GPU-matrix drag on titlebar
      el.addEventListener('pointerdown', () => {
        win.z = ++topZ;
        el.style.zIndex = String(win.z);
        if (simFocusedApp) simFocusedApp.textContent = state.lang === 'en' ? win.en : win.zh;
      });

      const bar = el.querySelector('.sim-win-bar');
      bar.addEventListener('pointerdown', (e) => {
        if (e.target.closest('button')) return;
        e.preventDefault();
        el.classList.add('dragging');
        const startX = e.clientX;
        const startY = e.clientY;
        const origL = parseFloat(el.style.left) || 0;
        const origT = parseFloat(el.style.top) || 0;
        const onMove = (ev) => {
          const maxL = Math.max(0, simCanvas.clientWidth - el.offsetWidth);
          const maxT = Math.max(0, simCanvas.clientHeight - el.offsetHeight);
          const nl = Math.max(0, Math.min(maxL, origL + (ev.clientX - startX)));
          const nt = Math.max(0, Math.min(maxT, origT + (ev.clientY - startY)));
          el.style.left = `${nl}px`;
          el.style.top = `${nt}px`;
        };
        const onUp = () => {
          el.classList.remove('dragging');
          window.removeEventListener('pointermove', onMove);
          window.removeEventListener('pointerup', onUp);
        };
        window.addEventListener('pointermove', onMove);
        window.addEventListener('pointerup', onUp);
      });

      simCanvas.appendChild(el);
      winEls.set(win.id, el);
    });
  }

  function applySimLayout(mode) {
    state.layout = mode;
    const frames = computeLayoutFrames(mode);
    SIM_WINDOWS.forEach((win) => {
      const el = winEls.get(win.id);
      const f = frames[win.id];
      if (!el || !f) return;
      el.style.display = f.show ? 'flex' : 'none';
      el.style.left = `${f.x}px`;
      el.style.top = `${f.y}px`;
      el.style.width = `${f.w}px`;
      el.style.height = `${f.h}px`;
    });
    document.querySelectorAll('.preset-chip[data-layout]').forEach((btn) => {
      btn.classList.toggle('active', btn.getAttribute('data-layout') === mode);
    });
    updateSimTelemetry();
  }

  document.querySelectorAll('.preset-chip[data-layout]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const lay = btn.getAttribute('data-layout');
      if (window.OpalSound) window.OpalSound.playClick('pill');
      applySimLayout(lay);
    });
  });

  const simHibernateBtn = document.getElementById('simHibernateBtn');
  if (simHibernateBtn) {
    simHibernateBtn.addEventListener('click', () => {
      const target = SIM_WINDOWS[2]; // browser
      target.hibernated = !target.hibernated;
      if (state.layout === 'split2') state.layout = 'cols3';
      if (window.OpalSound) window.OpalSound.playPulse(2, 0.2);
      renderSimWindows();
      applySimLayout(state.layout);
      showToast(target.hibernated
        ? (state.lang === 'en' ? 'Background window hibernated to TaskSnapshot (0 VD)' : '后台窗口已冻结为 TaskSnapshot 快照（释放 1 个虚拟屏槽位）')
        : (state.lang === 'en' ? 'Woke VirtualDisplay in 150ms' : '已 150ms 热唤醒实时虚拟屏'));
    });
  }

  const simResetBtn = document.getElementById('simResetBtn');
  if (simResetBtn) {
    simResetBtn.addEventListener('click', () => {
      SIM_WINDOWS.forEach((w, i) => { w.hibernated = i === 3; });
      renderSimWindows();
      applySimLayout(state.layout);
      if (window.OpalSound) window.OpalSound.playClick('pill');
    });
  }

  document.querySelectorAll('[data-dock-win]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const id = btn.getAttribute('data-dock-win');
      const win = SIM_WINDOWS.find((w) => w.id === id);
      const el = winEls.get(id);
      if (!win || !el) return;
      if (el.style.display === 'none') applySimLayout('cascade');
      if (win.hibernated) {
        win.hibernated = false;
        renderSimWindows();
        applySimLayout(state.layout);
      }
      const nextEl = winEls.get(id);
      if (nextEl) {
        win.z = ++topZ;
        nextEl.style.zIndex = String(win.z);
      }
      if (window.OpalSound) window.OpalSound.playClick('pill');
    });
  });

  window.addEventListener('resize', () => applySimLayout(state.layout));

  /* ========================================================================
     2. Featured Hardware Frames & Real-Device Gallery + Lightbox
     ======================================================================== */
  const featuredTabletImg = document.getElementById('featuredTabletImg');
  const featuredTabletCaption = document.getElementById('featuredTabletCaption');
  const featuredPhoneImg = document.getElementById('featuredPhoneImg');
  const featuredPhoneCaption = document.getElementById('featuredPhoneCaption');
  let curTabletView = 'light';
  let curPhoneView = 'trackpad';

  function setTabletView(key) {
    curTabletView = key;
    const v = TABLET_VIEWS[key] || TABLET_VIEWS.light;
    if (featuredTabletImg) featuredTabletImg.src = v.src;
    if (featuredTabletCaption) featuredTabletCaption.textContent = state.lang === 'en' ? v.en : v.zh;
    document.querySelectorAll('[data-hw-tablet]').forEach((b) => b.classList.toggle('active', b.getAttribute('data-hw-tablet') === key));
  }
  function setPhoneView(key) {
    curPhoneView = key;
    const v = PHONE_VIEWS[key] || PHONE_VIEWS.trackpad;
    if (featuredPhoneImg) featuredPhoneImg.src = v.src;
    if (featuredPhoneCaption) featuredPhoneCaption.textContent = state.lang === 'en' ? v.en : v.zh;
    document.querySelectorAll('[data-hw-phone]').forEach((b) => b.classList.toggle('active', b.getAttribute('data-hw-phone') === key));
  }
  document.querySelectorAll('[data-hw-tablet]').forEach((b) => b.addEventListener('click', () => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    setTabletView(b.getAttribute('data-hw-tablet'));
  }));
  document.querySelectorAll('[data-hw-phone]').forEach((b) => b.addEventListener('click', () => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    setPhoneView(b.getAttribute('data-hw-phone'));
  }));

  document.getElementById('featuredTabletWrap')?.addEventListener('click', () => openLightbox(TABLET_VIEWS[curTabletView].shotIdx));
  document.getElementById('featuredPhoneWrap')?.addEventListener('click', () => openLightbox(PHONE_VIEWS[curPhoneView].shotIdx));

  const shotGrid = document.getElementById('shotGrid');
  function renderShotGrid() {
    if (!shotGrid) return;
    shotGrid.innerHTML = '';
    SHOTS.forEach((shot, idx) => {
      if (state.filter !== 'all' && shot.cat !== state.filter) return;
      const card = document.createElement('article');
      card.className = 'shot-card';
      card.innerHTML = `
        <div class="shot-thumb${shot.portrait ? ' portrait-thumb' : ''}">
          <img src="${shot.src}" alt="${state.lang === 'en' ? shot.enTitle : shot.zhTitle}" loading="lazy" />
        </div>
        <div class="shot-meta">
          <span class="shot-device-inline mono">${shot.device}</span>
          <h4>${state.lang === 'en' ? shot.enTitle : shot.zhTitle}</h4>
          <p>${state.lang === 'en' ? shot.enDesc : shot.zhDesc}</p>
        </div>
      `;
      card.addEventListener('click', () => openLightbox(idx));
      shotGrid.appendChild(card);
    });
  }

  document.querySelectorAll('.filter-pill').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.filter = btn.getAttribute('data-filter') || 'all';
      document.querySelectorAll('.filter-pill').forEach((b) => b.classList.toggle('active', b === btn));
      if (window.OpalSound) window.OpalSound.playClick('pill');
      renderShotGrid();
    });
  });

  /* Lightbox */
  const lightbox = document.getElementById('lightbox');
  const lbImg = document.getElementById('lbImg');
  const lbTitle = document.getElementById('lbTitle');
  const lbDevice = document.getElementById('lbDevice');
  const lbCaption = document.getElementById('lbCaption');

  function updateLightbox() {
    const s = SHOTS[state.lbIndex] || SHOTS[0];
    lbImg.src = s.src;
    lbImg.alt = state.lang === 'en' ? s.enTitle : s.zhTitle;
    lbTitle.textContent = state.lang === 'en' ? s.enTitle : s.zhTitle;
    lbDevice.textContent = s.device;
    lbCaption.textContent = state.lang === 'en' ? s.enDesc : s.zhDesc;
  }
  function openLightbox(idx) {
    state.lbIndex = (idx + SHOTS.length) % SHOTS.length;
    updateLightbox();
    lightbox.hidden = false;
    document.body.style.overflow = 'hidden';
    if (window.OpalSound) window.OpalSound.playClick('pill');
  }
  function closeLightbox() {
    lightbox.hidden = true;
    document.body.style.overflow = '';
  }
  document.getElementById('lbClose')?.addEventListener('click', closeLightbox);
  document.getElementById('lbPrev')?.addEventListener('click', () => { state.lbIndex = (state.lbIndex - 1 + SHOTS.length) % SHOTS.length; updateLightbox(); });
  document.getElementById('lbNext')?.addEventListener('click', () => { state.lbIndex = (state.lbIndex + 1) % SHOTS.length; updateLightbox(); });
  lightbox?.addEventListener('click', (e) => { if (e.target === lightbox) closeLightbox(); });
  window.addEventListener('keydown', (e) => {
    if (lightbox.hidden) return;
    if (e.key === 'Escape') closeLightbox();
    if (e.key === 'ArrowLeft') { state.lbIndex = (state.lbIndex - 1 + SHOTS.length) % SHOTS.length; updateLightbox(); }
    if (e.key === 'ArrowRight') { state.lbIndex = (state.lbIndex + 1) % SHOTS.length; updateLightbox(); }
  });

  /* ========================================================================
     3. Architecture Blueprint Inspector & Code Studio
     ======================================================================== */
  function renderArchInspector() {
    const info = ARCH_DETAILS[state.archId] || ARCH_DETAILS.daemon;
    document.getElementById('archPath').textContent = info.path;
    document.getElementById('archTitle').textContent = state.lang === 'en' ? info.enTitle : info.zhTitle;
    document.getElementById('archDesc').textContent = state.lang === 'en' ? info.enDesc : info.zhDesc;
    const ul = document.getElementById('archBullets');
    ul.innerHTML = '';
    (state.lang === 'en' ? info.enBullets : info.zhBullets).forEach((item) => {
      const li = document.createElement('li');
      li.textContent = item;
      ul.appendChild(li);
    });
    document.querySelectorAll('.arch-node').forEach((n) => n.classList.toggle('active', n.getAttribute('data-arch') === state.archId));
  }
  document.querySelectorAll('.arch-node').forEach((n) => {
    n.addEventListener('click', () => {
      state.archId = n.getAttribute('data-arch') || 'daemon';
      if (window.OpalSound) window.OpalSound.playClick('pill');
      renderArchInspector();
    });
  });

  function renderCodeStudio() {
    const block = document.getElementById('studioCodeBlock');
    const snip = CODE_SNIPPETS[state.codeTab] || CODE_SNIPPETS.wireless;
    if (block) block.textContent = state.lang === 'en' ? snip.en : snip.zh;
    document.querySelectorAll('.studio-tab').forEach((t) => t.classList.toggle('active', t.getAttribute('data-code') === state.codeTab));
  }
  document.querySelectorAll('.studio-tab').forEach((t) => {
    t.addEventListener('click', () => {
      state.codeTab = t.getAttribute('data-code') || 'wireless';
      if (window.OpalSound) window.OpalSound.playClick('pill');
      renderCodeStudio();
    });
  });
  document.getElementById('copyCodeBtn')?.addEventListener('click', () => {
    const text = document.getElementById('studioCodeBlock')?.textContent || '';
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => showToast(state.lang === 'en' ? 'Copied to clipboard' : '已复制到剪贴板'));
    } else {
      showToast(state.lang === 'en' ? 'Ready to copy' : '已选中命令');
    }
    if (window.OpalSound) window.OpalSound.playClick('primary');
  });

  /* ========================================================================
     4. Theme, Bilingual, Nav Glider & Scroll Progress
     ======================================================================== */
  const themeBtn = document.getElementById('themeToggleBtn');
  const themeLabel = document.getElementById('themeToggleLabel');
  const langBtn = document.getElementById('langToggleBtn');

  function applyTheme(next, evt) {
    state.theme = next;
    safeSet(THEME_KEY, next);
    const commit = () => {
      document.documentElement.setAttribute('data-theme', next);
      if (themeLabel) themeLabel.textContent = next === 'pure-frost' ? I18N[state.lang].theme_light : I18N[state.lang].theme_dark;
    };
    if (!reducedMotion && evt && typeof document.startViewTransition === 'function') {
      const x = evt.clientX || window.innerWidth * 0.8;
      const y = evt.clientY || 32;
      const maxR = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y)) + 90;
      document.documentElement.style.setProperty('--vt-x', `${x}px`);
      document.documentElement.style.setProperty('--vt-y', `${y}px`);
      document.documentElement.style.setProperty('--vt-max-r', `${maxR}px`);
      document.startViewTransition(commit);
    } else {
      commit();
    }
  }
  themeBtn?.addEventListener('click', (e) => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    applyTheme(state.theme === 'pure-frost' ? 'prism-pulse' : 'pure-frost', e);
  });

  function applyLang(next) {
    state.lang = next;
    safeSet(LANG_KEY, next);
    document.documentElement.lang = next === 'en' ? 'en' : 'zh-CN';
    if (langBtn) langBtn.textContent = next === 'en' ? '中文' : 'EN';
    const dict = I18N[next];
    document.querySelectorAll('[data-i18n]').forEach((el) => {
      const k = el.getAttribute('data-i18n');
      if (dict[k] !== undefined) el.innerHTML = dict[k];
    });
    if (themeLabel) themeLabel.textContent = state.theme === 'pure-frost' ? dict.theme_light : dict.theme_dark;
    renderSimWindows();
    applySimLayout(state.layout);
    setTabletView(curTabletView);
    setPhoneView(curPhoneView);
    renderShotGrid();
    renderArchInspector();
    renderCodeStudio();
    requestAnimationFrame(moveNavGlider);
  }
  langBtn?.addEventListener('click', () => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    applyLang(state.lang === 'zh' ? 'en' : 'zh');
  });

  /* Nav Glider & Scroll Spy */
  const navLinks = Array.from(document.querySelectorAll('#navLinks a'));
  const mobileLinks = Array.from(document.querySelectorAll('.mobile-section-bar a'));
  const navGlider = document.getElementById('navGlider');
  const scrollBeam = document.getElementById('scrollBeam');
  const sections = navLinks.map((a) => document.querySelector(a.getAttribute('href'))).filter(Boolean);

  function moveNavGlider() {
    if (!navGlider) return;
    const active = document.querySelector('#navLinks a.active') || navLinks[0];
    if (!active || active.offsetWidth === 0) return;
    navGlider.style.width = `${active.offsetWidth}px`;
    navGlider.style.transform = `translateX(${active.offsetLeft - 3}px)`;
  }

  function onScroll() {
    const maxS = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
    if (scrollBeam) scrollBeam.style.width = `${Math.min(100, (window.scrollY / maxS) * 100)}%`;

    let activeIdx = 0;
    const probe = window.scrollY + 180;
    sections.forEach((sec, i) => {
      if (sec.offsetTop <= probe) activeIdx = i;
    });
    navLinks.forEach((a, i) => a.classList.toggle('active', i === activeIdx));
    mobileLinks.forEach((a, i) => a.classList.toggle('active', i === activeIdx));
    moveNavGlider();
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', moveNavGlider);

  /* Boot */
  applyLang(state.lang);
  onScroll();
})();

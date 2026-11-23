/* ==========================================================================
   OpalDesk — Signature 3D Spatial Hardware & Wayland Compositor Stage
   - Precision Chamfered 3D Tablet (Xiaomi Pad 8 Pro) & Phone (Xiaomi 14 Pro)
   - Real Captured OpalDesk Screen Textures (Auto-Synced Light / Dark!)
   - Exploded Z-Depth Wayland Multi-Window RenderNode Layers
   - Interactive VirtualDisplayPool Spatial Window Carousel (Live vs Snapshot)
   - Live Multi-Device Casting Desk with Optical Ribbon & Floating App Flight
   - Four-Wafer Isometric Silicon-Style Architecture Stack
   - Procedural Studio Softbox Environment Map + Interactive Orbit Inspect
   ========================================================================== */
(() => {
  'use strict';

  const THEME_KEY = 'opaldesk-showcase-theme-v1';
  const LANG_KEY = 'opaldesk-showcase-lang-v1';

  const ESM_CANDIDATES = [
    'https://registry.npmmirror.com/three/0.170.0/files/build/three.module.js',
    'https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.module.js'
  ];
  const UMD_CANDIDATES = [
    'https://registry.npmmirror.com/three/0.158.0/files/build/three.min.js',
    'https://cdn.bootcdn.net/ajax/libs/three.js/0.158.0/three.min.js',
    'https://cdnjs.cloudflare.com/ajax/libs/three.js/0.158.0/three.min.js'
  ];

  function safeGet(k, fb = null) {
    try { const v = window.localStorage.getItem(k); return v !== null ? v : fb; } catch (_) { return fb; }
  }
  function safeSet(k, v) { try { window.localStorage.setItem(k, v); } catch (_) {} }

  const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const savedTheme = safeGet(THEME_KEY);
  const initialTheme = (savedTheme === 'pure-frost' || savedTheme === 'prism-pulse')
    ? savedTheme
    : (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'prism-pulse' : 'pure-frost');

  /* Stage 02: VirtualDisplayPool Live / Hibernated Window Cards (Full 16:10 Unstretched Frames) */
  const POOL_SLOTS = [
    { id: 'win-1', zh: '双窗浅色 · LIVE', en: 'Light Desk · LIVE', titleZh: '晴空浅色工作站', titleEn: 'Light Workstation', sub: '3040×1904 · 120Hz', live: true, accent: '#0071E3', shot: 'dexLight' },
    { id: 'win-2', zh: '双窗深色 · LIVE', en: 'Dark Desk · LIVE', titleZh: '曜石深色工作站', titleEn: 'Dark Workstation', sub: '3040×1904 · 120Hz', live: true, accent: '#22B8CF', shot: 'dexDark' },
    { id: 'win-3', zh: '控制中心 · LIVE', en: 'Control · LIVE', titleZh: '实时桌面控制中心', titleEn: 'Control Center', sub: '120Hz · DPI Hot', live: true, accent: '#5E7CE2', shot: 'dexCtrlDark' },
    { id: 'win-4', zh: '无线投屏 · LIVE', en: 'Wire Cast · LIVE', titleZh: '手机→平板 120Hz 投屏', titleEn: 'Wireless Desktop Cast', sub: '2560×1600 · TLS 1.3', live: true, accent: '#2997FF', shot: 'castDesktop' },
    { id: 'win-5', zh: '主控台 · 快照', en: 'Console · Snap', titleZh: '工作站主控台', titleEn: 'Console', sub: 'TaskSnapshot · 0 VD', live: false, accent: '#30D158', shot: 'console' },
    { id: 'win-6', zh: '设置中心 · 快照', en: 'Settings · Snap', titleZh: '输入与配额设置', titleEn: 'Settings', sub: 'TaskSnapshot · 0 VD', live: false, accent: '#34D399', shot: 'settings' }
  ];

  const TOPO_PRESETS = {
    cast: {
      zhQuote: '“平板全屏 2560×1600 @ 120Hz 接收手机桌面，手机端同步化为液态毛玻璃触控板并支持一键物理息屏省电。”',
      enQuote: '"Tablet receives a full 2560×1600 @ 120Hz desktop from Phone while Phone turns into a frosted precision trackpad with one-tap screen-off."',
      zhMeta: 'Wi-Fi TLS 1.3 · TOFU 指纹 · OpalPeerCastMutex 单向防环锁',
      enMeta: 'Wi-Fi TLS 1.3 · TOFU Fingerprint · OpalPeerCastMutex Anti-Loop Lock'
    },
    float: {
      zhQuote: '“无需切换桌面，手机端应用直接以 macOS 液态毛玻璃小窗飞跃悬浮于平板之上，1:1 宿主 DPI 超采样清晰锐利。”',
      enQuote: '"Any phone app lifts into a macOS Liquid Glass floating window hovering directly over your tablet at razor-sharp 1:1 host DPI."',
      zhMeta: 'PeerFloatingAppService · TYPE_IME_REQUEST 跨端输入法自动转发',
      enMeta: 'PeerFloatingAppService · TYPE_IME_REQUEST Remote IME Forwarding'
    },
    mac: {
      zhQuote: '“接入 USB-C / HDMI 便携屏即刻满屏 2K/4K 100Hz+；也可通过原生 Swift 6 Mac 伴侣端或免安装 Web 浏览器（WebCodecs + WSS）低延迟接收桌面与声音。”',
      enQuote: '"Plug into USB-C / HDMI for instant 2K/4K 100Hz+, stream to native Swift 6 OpalDeskMac, or open the zero-install Web Cast Receiver (WebCodecs + WSS) in any Chrome/Edge browser."',
      zhMeta: 'ExternalMonitor · OpalDeskMac Swift 6 · GitHub Pages WebCodecs 接收端',
      enMeta: 'ExternalMonitor · OpalDeskMac Swift 6 · GitHub Pages WebCodecs Receiver'
    }
  };

  const WAFER_SPECS = [
    {
      id: 'wafer-mac',
      y: 1.56,
      z: 0.30,
      accent: '#D99B43',
      zh: 'LAYER 04 · OpalDeskMac (Swift 6) / Web 浏览器端 & 2K/4K 外接屏',
      en: 'LAYER 04 · OpalDeskMac (Swift 6) / Web Receiver & 2K/4K Monitor',
      tag: '04 · OUTPUT',
      title: 'Mac · Web 接收端 · 2K/4K 外接屏',
      sub: 'Swift 6 原生端 · GitHub Pages WebCodecs + WSS · 双向剪贴板',
      pill: 'HEVC · WSS'
    },
    {
      id: 'wafer-helper',
      y: 0.52,
      z: 0.10,
      accent: '#2997FF',
      zh: 'LAYER 03 · :helper 客户端 120fps 单 RenderNode GPU 合成器',
      en: 'LAYER 03 · :helper 120fps Single-RenderNode GPU Compositor',
      tag: '03 · CLIENT',
      title: 'OpalCompositorWorkspace',
      sub: '120fps 单 RenderNode 皮肉合一 · 拖拽 0 IPC · GitHub OTA',
      pill: '120fps GPU'
    },
    {
      id: 'wafer-pool',
      y: -0.52,
      z: -0.10,
      accent: '#30D158',
      zh: 'LAYER 02 · VirtualDisplayPool LRU 休眠池 + InputManager / uhid',
      en: 'LAYER 02 · VirtualDisplayPool LRU + InputManager & /dev/uhid',
      tag: '02 · ENGINE',
      title: 'VirtualDisplayPool & InputRouter',
      sub: '标准 FULLSCREEN (1) · TaskSnapshot 150ms 唤醒 · /dev/uhid 回退',
      pill: 'LRU · /dev/uhid'
    },
    {
      id: 'wafer-daemon',
      y: -1.56,
      z: -0.30,
      accent: '#5E7CE2',
      zh: 'LAYER 01 · :bridge Shell 守护进程（强制降权 uid=2000 · SHA-256 鉴权）',
      en: 'LAYER 01 · :bridge Shell Daemon (uid=2000 · SHA-256 Verified)',
      tag: '01 · DAEMON',
      title: 'OpalDeskDaemon · uid=2000',
      sub: '165 KB Java 11 DEX · fail-closed 降权 shell · APK 签名双向鉴权',
      pill: 'SHA-256 IPC'
    }
  ];

  const STAGE_TAGS = {
    zh: [
      '00 · 双机实拍 · 120fps 工作站',
      '01 · Wayland 虚拟屏 Z 轴分层',
      '02 · LRU 快照休眠窗口廊',
      '03 · 手机↔平板↔Mac 空间互联',
      '04 · 四层立式免 Root 架构',
      '05 · 下载最新版与开源共建'
    ],
    en: [
      '00 · REAL HARDWARE · 120FPS DESK',
      '01 · WAYLAND Z-DEPTH LAYERS',
      '02 · LRU SNAPSHOT CAROUSEL',
      '03 · PHONE ↔ TABLET ↔ MAC CAST',
      '04 · 4-TIER ROOTLESS ARCHITECTURE',
      '05 · DOWNLOAD LATEST & OPEN SOURCE'
    ]
  };

  const I18N = {
    zh: {
      ch_0: '序章 · Wayland 式 120fps 多窗工作站',
      ch_1: '一窗一独立虚拟屏 · Z 轴分层拆解',
      ch_2: 'VirtualDisplayPool LRU 快照窗口廊',
      ch_3: '跨设备无线投屏 · 独立小窗与触控板',
      ch_4: '四层立式架构 · 免 Root 降权守护',
      ch_5: '获取最新 APK / Mac 伴侣端',
      nav_overview: '2D 详解',
      nav_web: 'Web 投屏端',
      theme_light: '晴霜浅色',
      theme_dark: '曜石暗色',
      sound_on: '音效',
      sound_off: '静音',
      btn_play: '自动导览',
      btn_pause: '暂停导览',
      nav_get: '下载 APK',
      rail_0: '00 · 双机工作站',
      rail_1: '01 · Z 轴分层拆解',
      rail_2: '02 · 虚拟屏池窗口廊',
      rail_3: '03 · 跨设备空间互投',
      rail_4: '04 · 四层架构晶圆',
      rail_5: '05 · 下载与开源',
      s0_eyebrow: 'ANDROID 11–17 · 免 ROOT 优先 · 120FPS · MIT 开源',
      s0_title: '<span class="title-clause">一窗一独立虚拟屏，</span><span class="title-clause">重塑安卓桌面工作站。</span>',
      s0_desc: '<span class="prose-clause">彻底抛弃厂商私有 Freeform 小窗与撕裂悬浮壳。</span><span class="prose-clause">支持本机 120fps 多窗、手机↔平板↔Mac↔Web 浏览器无线互投，以及扫码局域网免线激活。</span>',
      s0_trigger: '切换 3D 分层爆炸视图',
      s0_web_cta: '打开 Web 投屏端 / 局域网激活',
      s0_overview_cta: '查看 2D 真机画廊与模拟器',
      s0_scroll: '向下滚动或拖动背景旋转机身 · 支持 ↑ / ↓ 与 1–6 快捷键',
      s1_eyebrow: '01 / 05 · EXPLODED WAYLAND COMPOSITOR',
      s1_title: '告别厂商小窗碎片，<br/>一窗一虚拟屏 Z 轴分层。',
      s1_desc: '右侧 3D 平板实时拆解 OpalDesk 渲染管线：每个应用以标准 AOSP FULLSCREEN (1) 运行在专属 VirtualDisplay 中，标题栏、阴影、实时窗口与 Dock 在单一 RenderNode 零撕裂合成。',
      s1_m1: '拖拽仅改 GPU 矩阵，0 Binder IPC',
      s1_m2: '宿主 DPI 超采样，小窗 1:1 清晰',
      s1_m3: '浅 / 深双主题屏幕实时同步切换',
      s1_link: '前往 2D 页面在线试玩多窗分屏模拟器',
      s2_eyebrow: '02 / 05 · VIRTUALDISPLAYPOOL LRU',
      s2_title: '突破系统配额上限，<br/>后台快照休眠 150ms 唤醒。',
      s2_desc: '左侧 3D 弧形窗口廊实时演示 VirtualDisplayPool：蓝框为前台活跃实时虚拟屏，翠绿磨砂为已释放槽位的 TaskSnapshot 快照卡。点击下方任意窗口卡片观察 3D 热唤醒：',
      s2_link: '浏览小米平板 8 Pro & 小米 14 Pro 全套真机实拍',
      s3_eyebrow: '03 / 05 · OPALCAST · MAC · WEB',
      s3_title: '手机、平板、Mac 与浏览器，<br/>全平台零摩擦无线协同。',
      s3_desc: '小米 14 Pro 化作液态毛玻璃精密触控板，支持 2560×1600 @ 120Hz 无线桌面、独立应用悬浮小窗、Mac 原生端以及免安装 GitHub Pages WebCodecs 浏览器被投屏端：',
      t_cast: '手机 → 平板无线桌面 + 手机精密触控板',
      t_float: '跨设备单应用悬浮小窗从手机飞跃至平板',
      t_mac: 'USB-C 2K/4K 外接满屏 + Mac 原生端 & Web 浏览器端',
      s3_web_link: '立即打开 Web 被投屏端（免安装浏览器直连）',
      s3_link: '查看真机投屏、独立小窗与触控板实拍画廊',
      s4_eyebrow: '04 / 05 · FOUR-WAFER ROOTLESS STACK',
      s4_title: '四层解耦架构，<br/>95%+ 免 Root 降权守护。',
      s4_desc: '从底层 uid=2000 降权 Shell 守护进程，到 VirtualDisplayPool 与 /dev/uhid 双通道输入，再到客户端 120fps Compose 合成、Mac 伴侣端与免安装 Web 端，悬停左侧任意晶圆层可查看职责。',
      s4_m1: 'u:r:shell:s0 降权运行，零无障碍',
      s4_m2: 'Linux /dev/uhid 内核级键鼠回退',
      s4_m3: 'APK SHA-256 鉴权 + GitHub OTA',
      s4_link: '查阅四端模块源码与架构检视台',
      s5_eyebrow: 'OPALDESK · ANDROID 11–17 · MACOS 14+ · WEB · MIT',
      s5_title: '<span class="title-clause">全品牌安卓，</span><span class="title-clause">天生桌面级生产力。</span>',
      s5_desc: 'MIT 永久开源免费，内置无线调试与 Web 局域网扫码免线激活，应用内直连 GitHub Releases 自动校验升级。',
      s5_cta_apk: '下载最新 APK & Mac 伴侣端',
      s5_cta_web: '打开 Web 投屏端 & 局域网激活 →',
      s5_cta_home: '进入 2D 实拍画廊与多窗模拟器 →',
      p_web: 'Web 被投屏端 & 局域网激活',
      p_sim: '120fps 多窗模拟器',
      p_gallery: '双机真机实拍画廊',
      p_compare: '架构路线对比',
      p_features: '核心特性总览',
      p_arch: '四端架构蓝图',
      p_dl: '多通道激活指南'
    },
    en: {
      ch_0: 'Prologue · Real Hardware 120fps Workstation',
      ch_1: 'Exploded Wayland Z-Depth Compositor Stack',
      ch_2: 'VirtualDisplayPool LRU Snapshot Carousel',
      ch_3: 'Spatial Phone ↔ Tablet ↔ Mac ↔ Web Casting',
      ch_4: '4-Tier Rootless uid=2000 Architecture',
      ch_5: 'Download Latest APK, Mac App & Web Cast',
      nav_overview: '2D View',
      nav_web: 'Web Cast',
      theme_light: 'Light Frost',
      theme_dark: 'Obsidian',
      sound_on: 'Sound',
      sound_off: 'Muted',
      btn_play: 'Auto Tour',
      btn_pause: 'Pause Tour',
      nav_get: 'Get APK',
      rail_0: '00 · Dual Hardware',
      rail_1: '01 · Z-Depth Stack',
      rail_2: '02 · LRU Carousel',
      rail_3: '03 · Spatial Cast',
      rail_4: '04 · 4-Wafer Arch',
      rail_5: '05 · Download',
      s0_eyebrow: 'ANDROID 11–17 · ROOTLESS-FIRST · 120FPS · MIT OPEN SOURCE',
      s0_title: '<span class="title-clause">One VirtualDisplay per window.</span><span class="title-clause">Android desktop, reborn.</span>',
      s0_desc: '<span class="prose-clause">Zero OEM Freeform hacks, zero split-brain overlay chrome.</span><span class="prose-clause">120fps multi-window desk, Phone ↔ Tablet ↔ Mac ↔ zero-install Web Cast &amp; browser LAN activation.</span>',
      s0_trigger: 'Toggle 3D Exploded Layer View',
      s0_web_cta: 'Open Web Cast & LAN Activation',
      s0_overview_cta: 'Open 2D Gallery & Simulator',
      s0_scroll: 'Scroll or drag stage to inspect hardware · Keys ↑ / ↓ & 1–6',
      s1_eyebrow: '01 / 05 · EXPLODED WAYLAND COMPOSITOR',
      s1_title: 'Zero OEM fragmentation.<br/>Exploded Z-Depth Compositor.',
      s1_desc: 'Watch the 3D tablet unpack OpalDesk’s live pipeline: every app runs in standard AOSP FULLSCREEN (1) inside its own VirtualDisplay, composed with glass chrome, shadows & Dock in one 120fps RenderNode.',
      s1_m1: 'GPU-only matrix drag · 0 Binder IPC',
      s1_m2: 'Host-DPI supersampling · 1:1 sharp',
      s1_m3: 'Live Light / Dark hardware texture sync',
      s1_link: 'Try the interactive 2D Multi-Window Simulator',
      s2_eyebrow: '02 / 05 · VIRTUALDISPLAYPOOL LRU',
      s2_title: 'Beat display quotas.<br/>150ms Snapshot Carousel.',
      s2_desc: 'The 3D spatial window arc visualizes VirtualDisplayPool live: sapphire cards hold active VirtualDisplays while frosted emerald cards hibernate as zero-VD TaskSnapshots. Click any slot to wake:',
      s2_link: 'Browse all 9 real Xiaomi Pad 8 Pro & Xiaomi 14 Pro captures',
      s3_eyebrow: '03 / 05 · OPALCAST · MAC · WEB',
      s3_title: 'Phone, Tablet, Mac & Browser.<br/>Zero-friction synergy.',
      s3_desc: 'Xiaomi 14 Pro becomes a frosted 120Hz trackpad for 2560×1600 @ 120Hz wireless desktop casting, floating apps, native Mac companion, and zero-install GitHub Pages WebCodecs browser casting:',
      t_cast: 'Phone → Tablet Wireless Desktop + Precision Trackpad',
      t_float: 'Standalone Floating App Lifting from Phone to Tablet',
      t_mac: 'USB-C 2K/4K Monitor + Native Mac App & Web Browser Cast',
      s3_web_link: 'Launch Web Cast Receiver (Zero-Install Browser)',
      s3_link: 'Inspect live casting, floating app & trackpad captures',
      s4_eyebrow: '04 / 05 · FOUR-WAFER ROOTLESS STACK',
      s4_title: 'Four decoupled tiers.<br/>95%+ Rootless uid=2000.',
      s4_desc: 'From the fail-closed uid=2000 shell daemon and LRU pool + /dev/uhid input router to the 120fps Compose compositor, Swift 6 Mac app, and WebCodecs browser receiver — hover any wafer on the left.',
      s4_m1: 'Drops to u:r:shell:s0 · 0 Accessibility',
      s4_m2: 'Linux /dev/uhid kernel fallback router',
      s4_m3: 'SHA-256 Binder auth + GitHub OTA',
      s4_link: 'Open Multi-Tier Module Inspector on 2D Page',
      s5_eyebrow: 'OPALDESK · ANDROID 11–17 · MACOS 14+ · WEB · MIT',
      s5_title: '<span class="title-clause">Every Android device,</span><span class="title-clause">a desktop workstation.</span>',
      s5_desc: 'Free MIT open source. Activate with built-in Wireless Debugging or QR-guided Web LAN Activation, with SHA-256 verified GitHub Releases OTA.',
      s5_cta_apk: 'Download Latest APK & macOS App',
      s5_cta_web: 'Open Web Cast & LAN Activation →',
      s5_cta_home: 'Open 2D Real Gallery & Simulator →',
      p_web: 'Web Cast & LAN Activation',
      p_sim: '120fps Simulator',
      p_gallery: 'Real Device Gallery',
      p_compare: 'Architecture Compare',
      p_features: 'Core Capabilities',
      p_arch: 'System Blueprint',
      p_dl: 'Activation Guide'
    }
  };

  const state = {
    theme: initialTheme,
    lang: safeGet(LANG_KEY) === 'en' ? 'en' : 'zh',
    topo: 'cast',
    activeScene: 0,
    scrollNorm: 0,
    morph: 0,
    explodeToggle: false,
    explodePulse: 0,
    castPulse: 0,
    autoPlaying: false,
    pointerX: 0,
    pointerY: 0,
    pointerOverUI: false,
    orbitYaw: 0,
    orbitPitch: 0,
    dragging: false
  };

  /* DOM Refs */
  const container = document.getElementById('canvasContainer');
  const bootVeil = document.getElementById('bootVeil');
  const bootBarFill = document.getElementById('bootBarFill');
  const stageMorphBtn = document.getElementById('stageMorphBtn');
  const stageMorphTag = document.getElementById('stageMorphTag');
  const chapterPopover = document.getElementById('chapterPopover');
  const themeToggleBtn = document.getElementById('themeToggleBtn');
  const themeToggleLabel = document.getElementById('themeToggleLabel');
  const soundToggleBtn = document.getElementById('soundToggleBtn');
  const soundToggleLabel = document.getElementById('soundToggleLabel');
  const filmPlayBtn = document.getElementById('filmPlayBtn');
  const filmPlayLabel = document.getElementById('filmPlayLabel');
  const langToggleBtn = document.getElementById('langToggleBtn');
  const timecodeIndex = document.getElementById('timecodeIndex');
  const timecodeFill = document.getElementById('timecodeFill');
  const railDroplet = document.getElementById('railDroplet');
  const cursorAura = document.getElementById('cursorAura');
  const cursorNodeLabel = document.getElementById('cursorNodeLabel');
  const poolSlotList = document.getElementById('poolSlotList');
  const poolSummaryLine = document.getElementById('poolSummaryLine');
  const topoDetailQuote = document.getElementById('topoDetailQuote');
  const topoDetailMeta = document.getElementById('topoDetailMeta');
  const scenes = Array.from(document.querySelectorAll('.film-scene'));
  const tcDots = Array.from(document.querySelectorAll('.tc-dot'));
  const menuItems = Array.from(document.querySelectorAll('.chapter-menu-item'));

  let onThemeChangeCb = null;
  let onLayoutChangeCb = null;
  function applyTheme(nextTheme, evt) {
    state.theme = nextTheme;
    safeSet(THEME_KEY, nextTheme);
    const commit = () => {
      document.documentElement.setAttribute('data-theme', nextTheme);
      updateThemeLabel();
      if (onThemeChangeCb) onThemeChangeCb(nextTheme);
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

  function updateThemeLabel() {
    const dict = I18N[state.lang];
    themeToggleLabel.textContent = state.theme === 'pure-frost' ? dict.theme_light : dict.theme_dark;
  }

  function applyLang(nextLang) {
    state.lang = nextLang;
    safeSet(LANG_KEY, nextLang);
    document.documentElement.lang = nextLang === 'en' ? 'en' : 'zh-CN';
    langToggleBtn.textContent = nextLang === 'en' ? '中文' : 'EN';
    const dict = I18N[nextLang];
    document.querySelectorAll('[data-i18n]').forEach((el) => {
      const key = el.getAttribute('data-i18n');
      if (dict[key] !== undefined) el.innerHTML = dict[key];
    });
    updateThemeLabel();
    updateStageLabels(state.activeScene);
    renderPoolSlots();
    renderTopoDetails();
    updateSoundUI();
    filmPlayLabel.textContent = state.autoPlaying ? dict.btn_pause : dict.btn_play;
    if (onLayoutChangeCb) requestAnimationFrame(onLayoutChangeCb);
  }

  function updateStageLabels(idx) {
    const tags = STAGE_TAGS[state.lang] || STAGE_TAGS.zh;
    stageMorphTag.textContent = tags[idx] || tags[0];
    timecodeIndex.textContent = `0${idx}`;
    tcDots.forEach((d, i) => {
      d.classList.toggle('active', i === idx);
      if (i === idx) d.setAttribute('aria-current', 'step');
      else d.removeAttribute('aria-current');
    });
    menuItems.forEach((m, i) => m.classList.toggle('active', i === idx));
    if (railDroplet) railDroplet.style.transform = `translateY(${idx * 26}px)`;
  }

  function renderPoolSlots() {
    if (!poolSlotList) return;
    poolSlotList.innerHTML = '';
    let liveCount = 0;
    POOL_SLOTS.forEach((slot, idx) => {
      if (slot.live) liveCount++;
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `pool-chip ${slot.live ? 'live' : 'hibernated'}`;
      btn.innerHTML = `<span class="chip-dot"></span><span>${state.lang === 'en' ? slot.en : slot.zh}</span>`;
      btn.addEventListener('click', () => {
        slot.live = !slot.live;
        state.explodePulse = 1.0;
        if (window.OpalSound) window.OpalSound.playPulse(2, (idx / 5) * 2 - 1);
        renderPoolSlots();
      });
      poolSlotList.appendChild(btn);
    });
    const hibernated = POOL_SLOTS.length - liveCount;
    poolSummaryLine.textContent = state.lang === 'en'
      ? `Active VirtualDisplays: ${liveCount} / 6 · TaskSnapshot Hibernated: ${hibernated} (0 VD cost, 150ms wake)`
      : `当前活跃实时虚拟屏：${liveCount} / 6 上限 · TaskSnapshot 快照休眠：${hibernated} 窗（0 虚拟屏配额 · 150ms 唤醒）`;
  }

  function renderTopoDetails() {
    const preset = TOPO_PRESETS[state.topo] || TOPO_PRESETS.cast;
    topoDetailQuote.textContent = state.lang === 'en' ? preset.enQuote : preset.zhQuote;
    topoDetailMeta.textContent = state.lang === 'en' ? preset.enMeta : preset.zhMeta;
    document.querySelectorAll('.topo-option').forEach((btn) => {
      const active = btn.getAttribute('data-topo') === state.topo;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-checked', active ? 'true' : 'false');
    });
  }

  document.querySelectorAll('.topo-option').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.topo = btn.getAttribute('data-topo') || 'cast';
      state.castPulse = 1.25;
      if (window.OpalSound) window.OpalSound.playPulse(3, 0.2);
      renderTopoDetails();
    });
  });

  function updateSoundUI() {
    if (!window.OpalSound || !soundToggleBtn) return;
    const s = window.OpalSound.getStatus();
    soundToggleBtn.setAttribute('aria-pressed', s.enabled ? 'true' : 'false');
    soundToggleBtn.classList.toggle('sound-live', s.enabled);
    soundToggleLabel.textContent = s.enabled ? I18N[state.lang].sound_on : I18N[state.lang].sound_off;
  }
  if (window.OpalSound) {
    window.OpalSound.subscribe(updateSoundUI);
    soundToggleBtn.addEventListener('click', () => window.OpalSound.setEnabled(!window.OpalSound.getStatus().enabled));
  }

  themeToggleBtn.addEventListener('click', (e) => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    applyTheme(state.theme === 'pure-frost' ? 'prism-pulse' : 'pure-frost', e);
  });
  langToggleBtn.addEventListener('click', () => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    applyLang(state.lang === 'zh' ? 'en' : 'zh');
  });

  function setPopoverOpen(open) {
    chapterPopover.hidden = !open;
    stageMorphBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
  }
  stageMorphBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (window.OpalSound) window.OpalSound.playClick('pill');
    setPopoverOpen(chapterPopover.hidden);
  });
  document.addEventListener('click', () => setPopoverOpen(false));

  function jumpToScene(idx) {
    const safeIdx = Math.max(0, Math.min(scenes.length - 1, idx));
    const target = scenes[safeIdx];
    if (!target) return;
    setPopoverOpen(false);
    const destY = safeIdx === 0 ? 0 : target.offsetTop;
    window.scrollTo({ top: destY, behavior: reducedMotion ? 'auto' : 'smooth' });
    if (window.OpalSound) window.OpalSound.playClick('pill');
  }
  document.querySelectorAll('[data-jump]').forEach((el) => {
    el.addEventListener('click', () => jumpToScene(Number(el.getAttribute('data-jump'))));
  });

  let autoRaf = 0;
  function stopAutoPlay() {
    state.autoPlaying = false;
    filmPlayBtn.classList.remove('playing');
    filmPlayLabel.textContent = I18N[state.lang].btn_play;
    if (autoRaf) cancelAnimationFrame(autoRaf);
  }
  filmPlayBtn.addEventListener('click', () => {
    if (window.OpalSound) window.OpalSound.playClick('pill');
    if (state.autoPlaying) { stopAutoPlay(); return; }
    state.autoPlaying = true;
    filmPlayBtn.classList.add('playing');
    filmPlayLabel.textContent = I18N[state.lang].btn_pause;
    const maxS = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
    if (window.scrollY >= maxS - 24) window.scrollTo({ top: 0, behavior: 'auto' });
    const step = () => {
      if (!state.autoPlaying) return;
      const ms = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
      if (window.scrollY >= ms - 4) { stopAutoPlay(); return; }
      window.scrollBy(0, 1.5);
      autoRaf = requestAnimationFrame(step);
    };
    autoRaf = requestAnimationFrame(step);
  });
  ['wheel', 'touchstart'].forEach((ev) => window.addEventListener(ev, () => { if (state.autoPlaying) stopAutoPlay(); }, { passive: true }));

  document.getElementById('heroPulseBtn')?.addEventListener('click', () => {
    state.explodeToggle = !state.explodeToggle;
    state.explodePulse = 1.2;
    if (window.OpalSound) window.OpalSound.playPulse(state.explodeToggle ? 1 : 0, 0);
  });
  document.getElementById('igniteCastBtn')?.addEventListener('click', () => {
    state.castPulse = 1.45;
    if (window.OpalSound) window.OpalSound.playPulse(3, 0);
  });

  document.querySelectorAll('[data-glass-card]').forEach((card) => {
    card.addEventListener('pointermove', (e) => {
      const r = card.getBoundingClientRect();
      card.style.setProperty('--mx', `${((e.clientX - r.left) / r.width) * 100}%`);
      card.style.setProperty('--my', `${((e.clientY - r.top) / r.height) * 100}%`);
    }, { passive: true });
  });

  function updateScrollState() {
    const maxScroll = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
    const norm = Math.max(0, Math.min(1, window.scrollY / maxScroll));
    state.scrollNorm = norm;
    state.morph = norm * 5.0;
    if (timecodeFill) timecodeFill.style.height = `${(norm * 100).toFixed(1)}%`;

    const nextIdx = Math.max(0, Math.min(5, Math.round(state.morph)));
    scenes.forEach((sec, i) => sec.classList.toggle('is-active', i === nextIdx));
    if (nextIdx !== state.activeScene) {
      state.activeScene = nextIdx;
      updateStageLabels(nextIdx);
      if (window.OpalSound) window.OpalSound.playSceneShift(nextIdx);
    }
  }
  window.addEventListener('scroll', updateScrollState, { passive: true });
  window.addEventListener('keydown', (e) => {
    if (e.target && ['INPUT', 'TEXTAREA'].includes(e.target.tagName)) return;
    if (e.key === 'ArrowDown') { e.preventDefault(); jumpToScene(state.activeScene + 1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); jumpToScene(state.activeScene - 1); }
    else if (e.key >= '1' && e.key <= '6') { jumpToScene(Number(e.key) - 1); }
  });

  /* Interactive Stage Pointer & Orbit Drag */
  let dragStartX = 0;
  let dragStartY = 0;
  window.addEventListener('pointerdown', (e) => {
    if (e.target.closest('.glass-surface, .header-capsule, .timecode-rail, a, button')) return;
    state.dragging = true;
    dragStartX = e.clientX;
    dragStartY = e.clientY;
    if (state.autoPlaying) stopAutoPlay();
  });
  window.addEventListener('pointermove', (e) => {
    state.pointerX = (e.clientX / window.innerWidth) * 2 - 1;
    state.pointerY = -(e.clientY / window.innerHeight) * 2 + 1;
    state.pointerOverUI = Boolean(e.target && e.target.closest && e.target.closest('.glass-surface, .header-capsule, .timecode-rail, .chapter-popover'));
    if (cursorAura) {
      cursorAura.style.transform = `translate3d(${e.clientX}px, ${e.clientY}px, 0)`;
      cursorAura.classList.toggle('flip-left', e.clientX > window.innerWidth - 310);
    }
    if (state.dragging) {
      const dx = (e.clientX - dragStartX) * 0.0055;
      const dy = (e.clientY - dragStartY) * 0.0045;
      dragStartX = e.clientX;
      dragStartY = e.clientY;
      state.orbitYaw = Math.max(-0.95, Math.min(0.95, state.orbitYaw + dx));
      state.orbitPitch = Math.max(-0.45, Math.min(0.45, state.orbitPitch + dy));
    }
  }, { passive: true });
  window.addEventListener('pointerup', () => { state.dragging = false; });

  /* ========================================================================
     Three.js Multi-CDN Loader
     ======================================================================== */
  function loadScript(src, timeoutMs = 1500) {
    return new Promise((resolve, reject) => {
      if (window.THREE && window.THREE.WebGLRenderer) return resolve(window.THREE);
      const s = document.createElement('script');
      const timer = setTimeout(() => { s.remove(); reject(new Error('timeout')); }, timeoutMs);
      s.src = src;
      s.async = true;
      s.onload = () => {
        clearTimeout(timer);
        if (window.THREE && window.THREE.WebGLRenderer) resolve(window.THREE);
        else reject(new Error('no THREE'));
      };
      s.onerror = () => { clearTimeout(timer); s.remove(); reject(new Error('err')); };
      document.head.appendChild(s);
    });
  }

  async function loadThree() {
    if (window.THREE && window.THREE.WebGLRenderer) return window.THREE;
    for (const url of ESM_CANDIDATES) {
      try {
        const mod = await Promise.race([
          import(url),
          new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), 1600))
        ]);
        if (mod && mod.WebGLRenderer) return mod;
      } catch (_) {}
    }
    for (const url of UMD_CANDIDATES) {
      try {
        const t = await loadScript(url, 1700);
        if (t && t.WebGLRenderer) return t;
      } catch (_) {}
    }
    return null;
  }

  /* ========================================================================
     Procedural Helpers (Rounded Shapes, Studio EnvMap, High-DPI Canvas Cards)
     ======================================================================== */
  function roundedRectShape(THREE, w, h, r) {
    const s = new THREE.Shape();
    const x = -w / 2;
    const y = -h / 2;
    s.moveTo(x + r, y);
    s.lineTo(x + w - r, y);
    s.quadraticCurveTo(x + w, y, x + w, y + r);
    s.lineTo(x + w, y + h - r);
    s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    s.lineTo(x + r, y + h);
    s.quadraticCurveTo(x, y + h, x, y + h - r);
    s.lineTo(x, y + r);
    s.quadraticCurveTo(x, y, x + r, y);
    return s;
  }

  function makeRoundedPlaneGeo(THREE, w, h, r) {
    const shape = roundedRectShape(THREE, w, h, r);
    const geo = new THREE.ShapeGeometry(shape, 16);
    const pos = geo.attributes.position;
    const uvs = new Float32Array(pos.count * 2);
    for (let i = 0; i < pos.count; i++) {
      uvs[i * 2] = (pos.getX(i) + w / 2) / w;
      uvs[i * 2 + 1] = (pos.getY(i) + h / 2) / h;
    }
    geo.setAttribute('uv', new THREE.BufferAttribute(uvs, 2));
    return geo;
  }

  function makeStudioEnvTexture(THREE, renderer) {
    const c = document.createElement('canvas');
    c.width = 512;
    c.height = 256;
    const g = c.getContext('2d');
    const bg = g.createLinearGradient(0, 0, 0, 256);
    bg.addColorStop(0, '#eef5ff');
    bg.addColorStop(0.48, '#a6c0e4');
    bg.addColorStop(0.52, '#243044');
    bg.addColorStop(1, '#0c111b');
    g.fillStyle = bg;
    g.fillRect(0, 0, 512, 256);

    // Crisp studio softbox strips for realistic anodized metal speculars
    g.fillStyle = '#ffffff';
    g.fillRect(46, 22, 160, 44);
    g.fillStyle = 'rgba(180, 228, 255, 0.92)';
    g.fillRect(280, 34, 175, 36);
    g.fillStyle = 'rgba(125, 211, 252, 0.55)';
    g.fillRect(110, 150, 290, 20);

    const tex = new THREE.CanvasTexture(c);
    tex.mapping = THREE.EquirectangularReflectionMapping;
    if ('colorSpace' in tex && THREE.SRGBColorSpace) tex.colorSpace = THREE.SRGBColorSpace;
    try {
      const pmrem = new THREE.PMREMGenerator(renderer);
      const envRT = pmrem.fromEquirectangular(tex);
      pmrem.dispose();
      tex.dispose();
      return envRT.texture;
    } catch (_) {
      return tex;
    }
  }

  function makeSoftShadowTexture(THREE) {
    const c = document.createElement('canvas');
    c.width = 256;
    c.height = 256;
    const g = c.getContext('2d');
    const grad = g.createRadialGradient(128, 128, 12, 128, 128, 122);
    grad.addColorStop(0, 'rgba(8, 18, 42, 0.42)');
    grad.addColorStop(0.45, 'rgba(8, 18, 42, 0.16)');
    grad.addColorStop(1, 'rgba(8, 18, 42, 0)');
    g.fillStyle = grad;
    g.fillRect(0, 0, 256, 256);
    return new THREE.CanvasTexture(c);
  }

  /* High-DPI Front-Facing Architectural Glass Slab Texture (100% Legible at Default Angle) */
  function makeWaferTexture(THREE, spec, isLight) {
    const c = document.createElement('canvas');
    c.width = 1600;
    c.height = 320;
    const g = c.getContext('2d');

    // Crisp frosted glass surface gradient
    const bg = g.createLinearGradient(0, 0, 1600, 320);
    if (isLight) {
      bg.addColorStop(0, 'rgba(255, 255, 255, 0.96)');
      bg.addColorStop(1, 'rgba(241, 246, 255, 0.92)');
    } else {
      bg.addColorStop(0, 'rgba(26, 33, 48, 0.96)');
      bg.addColorStop(1, 'rgba(18, 23, 35, 0.94)');
    }
    g.fillStyle = bg;
    g.fillRect(0, 0, 1600, 320);

    // Subtle hairline inner rim
    g.strokeStyle = isLight ? 'rgba(15, 28, 52, 0.08)' : 'rgba(255, 255, 255, 0.14)';
    g.lineWidth = 4;
    g.strokeRect(2, 2, 1596, 316);

    // Left luminous accent bar
    g.fillStyle = spec.accent;
    g.beginPath();
    g.roundRect(26, 36, 14, 248, 7);
    g.fill();

    // Layer index tag pill
    g.fillStyle = isLight ? 'rgba(15, 28, 52, 0.06)' : 'rgba(255, 255, 255, 0.09)';
    g.beginPath();
    g.roundRect(68, 42, 210, 44, 22);
    g.fill();
    g.fillStyle = spec.accent;
    g.font = '700 24px "SF Mono", ui-monospace, monospace';
    g.fillText(spec.tag, 90, 72);

    // Right spec pill
    g.font = '600 26px "SF Mono", ui-monospace, monospace';
    const pillW = Math.max(230, g.measureText(spec.pill).width + 54);
    const pillX = 1554 - pillW;
    g.fillStyle = isLight ? 'rgba(0, 113, 227, 0.09)' : 'rgba(77, 163, 255, 0.16)';
    g.strokeStyle = spec.accent;
    g.lineWidth = 2.5;
    g.beginPath();
    g.roundRect(pillX, 44, pillW, 52, 26);
    g.fill();
    g.stroke();
    g.fillStyle = isLight ? '#16181D' : '#F4F7FB';
    g.fillText(spec.pill, pillX + 27, 79);

    // Crisp bold title
    g.fillStyle = isLight ? '#11151E' : '#F8FAFC';
    g.font = '700 54px -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", sans-serif';
    g.fillText(spec.title, 68, 178);

    // Clear subtitle
    g.fillStyle = isLight ? '#475569' : '#B4C0D3';
    g.font = '500 32px -apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", sans-serif';
    g.fillText(spec.sub, 68, 252);

    const tex = new THREE.CanvasTexture(c);
    if ('colorSpace' in tex && THREE.SRGBColorSpace) tex.colorSpace = THREE.SRGBColorSpace;
    tex.anisotropy = 8;
    return tex;
  }

  /* High-DPI Floating Glass Dock Texture (Stage 01 Exploded Layer) */
  function makeDockTexture(THREE, isLight) {
    const c = document.createElement('canvas');
    c.width = 768;
    c.height = 144;
    const g = c.getContext('2d');
    g.clearRect(0, 0, 768, 144);

    g.fillStyle = isLight ? 'rgba(255,255,255,0.88)' : 'rgba(24,30,44,0.88)';
    g.strokeStyle = isLight ? 'rgba(255,255,255,0.96)' : 'rgba(255,255,255,0.24)';
    g.lineWidth = 4;
    g.beginPath();
    g.roundRect(12, 14, 744, 116, 58);
    g.fill();
    g.stroke();

    const colors = ['#0071E3', '#22B8CF', '#5E7CE2', '#30D158', '#FF9F0A', '#EC4899'];
    colors.forEach((col, i) => {
      const x = 78 + i * 106;
      g.fillStyle = col;
      g.beginPath();
      g.roundRect(x, 34, 76, 76, 22);
      g.fill();
      // specular highlight
      g.fillStyle = 'rgba(255,255,255,0.28)';
      g.beginPath();
      g.roundRect(x + 6, 40, 64, 24, 12);
      g.fill();
    });

    const tex = new THREE.CanvasTexture(c);
    if ('colorSpace' in tex && THREE.SRGBColorSpace) tex.colorSpace = THREE.SRGBColorSpace;
    return tex;
  }

  /* ========================================================================
     Primary WebGL2 Signature OpalDesk 3D Hardware & Compositor Engine
     ======================================================================== */
  function mountSignature3D(THREE) {
    const renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance', alpha: false });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setSize(window.innerWidth, window.innerHeight);
    if ('toneMapping' in renderer) {
      renderer.toneMapping = THREE.ACESFilmicToneMapping;
      renderer.toneMappingExposure = 1.08;
    }
    container.appendChild(renderer.domElement);

    const scene = new THREE.Scene();
    const envTex = makeStudioEnvTexture(THREE, renderer);
    scene.environment = envTex;

    const camera = new THREE.PerspectiveCamera(35, window.innerWidth / window.innerHeight, 0.1, 100);
    camera.position.set(0, 0, 11.4);

    let targetBlend = state.theme === 'pure-frost' ? 1.0 : 0.0;
    let themeBlend = targetBlend;

    const darkBg = new THREE.Color(0x090c12);
    const lightBg = new THREE.Color(0xedf2fa);
    const liveBg = darkBg.clone().lerp(lightBg, themeBlend);
    scene.background = liveBg;

    /* Studio Lighting Rig */
    const hemi = new THREE.HemisphereLight(0xf0f7ff, 0x1e293b, 1.35);
    scene.add(hemi);
    const keyLight = new THREE.DirectionalLight(0xffffff, 2.2);
    keyLight.position.set(5.5, 8.5, 7.5);
    scene.add(keyLight);
    const fillLight = new THREE.DirectionalLight(0x7dd3fc, 0.95);
    fillLight.position.set(-6.5, 2.5, 4.0);
    scene.add(fillLight);
    const rimLight = new THREE.DirectionalLight(0x6366f1, 1.15);
    rimLight.position.set(2.0, -4.0, -6.5);
    scene.add(rimLight);

    /* Load Real Captured OpalDesk Screenshots as Crisp WebGL Textures */
    const loader = new THREE.TextureLoader();
    const maxAniso = renderer.capabilities.getMaxAnisotropy ? Math.min(8, renderer.capabilities.getMaxAnisotropy()) : 4;
    function loadShot(file) {
      const tex = loader.load(`./assets/screenshots/${file}`);
      if ('colorSpace' in tex && THREE.SRGBColorSpace) tex.colorSpace = THREE.SRGBColorSpace;
      tex.anisotropy = maxAniso;
      return tex;
    }
    const SHOTS = {
      dexLight: loadShot('dex-workstation-light.jpg'),
      dexDark: loadShot('dex-workstation-dark.jpg'),
      dexCtrlDark: loadShot('dex-control-dark.jpg'),
      castDesktop: loadShot('cast-desktop-phone-to-tablet.jpg'),
      castFloat: loadShot('cast-floating-app.jpg'),
      phoneTrackpad: loadShot('phone-trackpad.jpg'),
      phoneCasting: loadShot('phone-casting-console.jpg'),
      console: loadShot('tablet-console.jpg'),
      settings: loadShot('tablet-settings.jpg')
    };

    /* Soft Atmospheric Opal Studio Cyclorama Halo */
    const haloUniforms = { uMorph: { value: 0 }, uLight: { value: themeBlend }, uPulse: { value: 0 } };
    const haloMat = new THREE.ShaderMaterial({
      uniforms: haloUniforms,
      transparent: true,
      depthWrite: false,
      depthTest: false,
      vertexShader: `varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
      fragmentShader: `
        uniform float uMorph;
        uniform float uLight;
        uniform float uPulse;
        varying vec2 vUv;
        void main() {
          vec2 p = (vUv - 0.5) * 2.0;
          float r = length(p);
          if (r > 0.98) discard;
          float g1 = exp(-dot(p - vec2(-0.28, 0.18), p - vec2(-0.28, 0.18)) * 3.0);
          float g2 = exp(-dot(p - vec2(0.32, -0.14), p - vec2(0.32, -0.14)) * 2.8);
          vec3 cyan = vec3(0.16, 0.66, 0.98);
          vec3 indigo = vec3(0.46, 0.44, 0.94);
          vec3 pearl = vec3(0.92, 0.54, 0.78);
          vec3 col = cyan * g1 + mix(indigo, pearl, clamp(uMorph * 0.18, 0.0, 0.7)) * g2;
          float fade = smoothstep(0.98, 0.25, r);
          float a = (g1 * 0.24 + g2 * 0.20) * fade * (1.0 + uPulse * 0.35) * mix(0.85, 0.38, uLight);
          gl_FragColor = vec4(col, a);
        }
      `
    });
    const haloPlane = new THREE.Mesh(new THREE.PlaneGeometry(24, 16), haloMat);
    haloPlane.position.set(0, 0.5, -5.0);
    haloPlane.renderOrder = -10;
    scene.add(haloPlane);

    const root = new THREE.Group();
    scene.add(root);

    const raycastTargets = [];
    const shadowTex = makeSoftShadowTexture(THREE);

    /* ======================================================================
       BUILD 1: PRECISION CHAMFERED XIAOMI PAD 8 PRO (EXACT 3040:1904 = 1.5966)
       ====================================================================== */
    const tabletRig = new THREE.Group();
    root.add(tabletRig);

    const TAB_IMG_ASPECT = 3040 / 1904; // 1.596638655
    const scrH = 2.72;
    const scrW = scrH * TAB_IMG_ASPECT; // 4.34286 -> EXACT 1:1 aspect!
    const tabW = scrW + 0.24;
    const tabH = scrH + 0.24;
    const tabDepth = 0.14;
    const bodyShape = roundedRectShape(THREE, tabW, tabH, 0.24);
    const bodyGeo = new THREE.ExtrudeGeometry(bodyShape, {
      depth: tabDepth,
      bevelEnabled: true,
      bevelThickness: 0.032,
      bevelSize: 0.028,
      bevelSegments: 5,
      curveSegments: 20
    });
    bodyGeo.center();

    const metalChassisMat = new THREE.MeshStandardMaterial({
      color: 0xcbd5e1,
      metalness: 0.86,
      roughness: 0.22,
      envMapIntensity: 1.35
    });
    const tabletBody = new THREE.Mesh(bodyGeo, metalChassisMat);
    tabletBody.userData = { label: 'Xiaomi Pad 8 Pro · 3040×1904 @ 120Hz' };
    raycastTargets.push(tabletBody);
    tabletRig.add(tabletBody);

    // Obsidian Front Glass Bezel
    const bezelGeo = makeRoundedPlaneGeo(THREE, tabW - 0.07, tabH - 0.07, 0.20);
    const bezelMat = new THREE.MeshPhysicalMaterial({
      color: 0x0b0e14,
      roughness: 0.08,
      metalness: 0.1,
      clearcoat: 1.0,
      clearcoatRoughness: 0.06
    });
    const tabletBezel = new THREE.Mesh(bezelGeo, bezelMat);
    tabletBezel.position.z = tabDepth * 0.5 + 0.034;
    tabletRig.add(tabletBezel);

    // Live Crisp Tablet Display Screen (EXACT 1:1 3040:1904 aspect)
    const tabletScreenGeo = makeRoundedPlaneGeo(THREE, scrW, scrH, 0.13);
    const tabletScreenMat = new THREE.MeshBasicMaterial({ map: state.theme === 'pure-frost' ? SHOTS.dexLight : SHOTS.dexDark });
    const tabletScreen = new THREE.Mesh(tabletScreenGeo, tabletScreenMat);
    tabletScreen.position.z = tabDepth * 0.5 + 0.039;
    tabletScreen.userData = { label: 'OpalDesk · 单 RenderNode 120fps 合成画面' };
    raycastTargets.push(tabletScreen);
    tabletRig.add(tabletScreen);

    // Subtle Studio Floor Shadow under Tablet
    const tabShadow = new THREE.Mesh(
      new THREE.PlaneGeometry(5.8, 2.2),
      new THREE.MeshBasicMaterial({ map: shadowTex, transparent: true, opacity: 0.42, depthWrite: false })
    );
    tabShadow.rotation.x = -Math.PI / 2;
    tabShadow.position.set(0, -1.94, 0);
    tabletRig.add(tabShadow);

    /* EXPLODED WAYLAND Z-DEPTH LAYERS (Pixel-Exact 1:1 Crop Dimensions!) */
    function makeCroppedWindowPlane(w, h, r, tex, u0, v0, u1, v1, label) {
      const geo = makeRoundedPlaneGeo(THREE, w, h, r);
      const uv = geo.attributes.uv;
      for (let i = 0; i < uv.count; i++) {
        const u = uv.getX(i);
        const v = uv.getY(i);
        uv.setXY(i, u0 + u * (u1 - u0), v0 + v * (v1 - v0));
      }
      const grp = new THREE.Group();

      // Thin physical glass backing slab so cards feel architectural, not paper-thin
      const slabGeo = new THREE.ExtrudeGeometry(roundedRectShape(THREE, w + 0.03, h + 0.03, r + 0.012), {
        depth: 0.028,
        bevelEnabled: true,
        bevelThickness: 0.008,
        bevelSize: 0.008,
        bevelSegments: 2
      });
      slabGeo.center();
      const backMat = new THREE.MeshPhysicalMaterial({
        color: 0xe2e8f0,
        roughness: 0.14,
        metalness: 0.35,
        clearcoat: 1.0,
        transparent: true,
        opacity: 0
      });
      const backSlab = new THREE.Mesh(slabGeo, backMat);
      backSlab.position.z = -0.024;
      grp.add(backSlab);

      const screenMat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, opacity: 0 });
      const plane = new THREE.Mesh(geo, screenMat);
      plane.userData = { label };
      raycastTargets.push(plane);
      grp.add(plane);

      // Luminous glass rim around each floating window layer
      const rimPts = roundedRectShape(THREE, w + 0.022, h + 0.022, r + 0.01).getPoints(44);
      const rimGeo = new THREE.BufferGeometry().setFromPoints(rimPts.map((p) => new THREE.Vector3(p.x, p.y, 0.008)));
      const rimMat = new THREE.LineBasicMaterial({ color: 0x38bdf8, transparent: true, opacity: 0 });
      grp.add(new THREE.LineLoop(rimGeo, rimMat));

      return { grp, plane, screenMat, rimMat, backMat };
    }

    // Exact 1:1 pixel proportions: w = scrW * (u1 - u0), h = scrH * (v1 - v0)
    const layerClock = makeCroppedWindowPlane(
      scrW * (0.495 - 0.006), scrH * (0.955 - 0.10), 0.095, SHOTS.dexLight,
      0.006, 0.10, 0.495, 0.955,
      'OpalWin-1 · 世界时钟 · 独立 VirtualDisplay #1'
    );
    layerClock.grp.position.set(-scrW * 0.248, 0.06, 0.08);
    tabletRig.add(layerClock.grp);

    const layerCalc = makeCroppedWindowPlane(
      scrW * (0.994 - 0.504), scrH * (0.955 - 0.10), 0.095, SHOTS.dexLight,
      0.504, 0.10, 0.994, 0.955,
      'OpalWin-2 · 科学计算器 · 独立 VirtualDisplay #2'
    );
    layerCalc.grp.position.set(scrW * 0.248, 0.06, 0.08);
    tabletRig.add(layerCalc.grp);

    const layerCtrl = makeCroppedWindowPlane(
      scrW * (0.992 - 0.652), scrH * (0.96 - 0.03), 0.105, SHOTS.dexCtrlDark,
      0.652, 0.03, 0.992, 0.96,
      'Control Center · 120Hz / DPI 热刷新 / 7 套分屏'
    );
    layerCtrl.grp.position.set(scrW * 0.31, 0.01, 0.12);
    tabletRig.add(layerCtrl.grp);

    const dockMat = new THREE.MeshBasicMaterial({ map: makeDockTexture(THREE, state.theme === 'pure-frost'), transparent: true, opacity: 0 });
    const layerDock = new THREE.Mesh(new THREE.PlaneGeometry(2.15, 0.40), dockMat);
    layerDock.position.set(0, -scrH * 0.44, 0.14);
    layerDock.userData = { label: 'Floating Liquid Glass Dock · ClipOp.Difference 外扩散阴影' };
    raycastTargets.push(layerDock);
    tabletRig.add(layerDock);

    /* ======================================================================
       BUILD 2: PRECISION TITANIUM XIAOMI 14 PRO (EXACT 1440:3200 = 0.450)
       ====================================================================== */
    const phoneRig = new THREE.Group();
    root.add(phoneRig);

    const PH_IMG_ASPECT = 1440 / 3200; // 0.45 exact!
    const phScrH = 2.80;
    const phScrW = phScrH * PH_IMG_ASPECT; // 1.26 exact!
    const phW = phScrW + 0.14;
    const phH = phScrH + 0.14;
    const phDepth = 0.13;
    const phGeo = new THREE.ExtrudeGeometry(roundedRectShape(THREE, phW, phH, 0.20), {
      depth: phDepth,
      bevelEnabled: true,
      bevelThickness: 0.028,
      bevelSize: 0.024,
      bevelSegments: 5,
      curveSegments: 18
    });
    phGeo.center();

    const titaniumMat = new THREE.MeshStandardMaterial({
      color: 0x94a3b8,
      metalness: 0.90,
      roughness: 0.20,
      envMapIntensity: 1.4
    });
    const phoneBody = new THREE.Mesh(phGeo, titaniumMat);
    phoneBody.userData = { label: 'Xiaomi 14 Pro · 1440×3200 · 精密触控板与投屏源' };
    raycastTargets.push(phoneBody);
    phoneRig.add(phoneBody);

    const phBezel = new THREE.Mesh(makeRoundedPlaneGeo(THREE, phW - 0.05, phH - 0.05, 0.17), bezelMat);
    phBezel.position.z = phDepth * 0.5 + 0.030;
    phoneRig.add(phBezel);

    const phScreenMat = new THREE.MeshBasicMaterial({ map: SHOTS.phoneTrackpad });
    const phScreen = new THREE.Mesh(makeRoundedPlaneGeo(THREE, phScrW, phScrH, 0.13), phScreenMat);
    phScreen.position.z = phDepth * 0.5 + 0.034;
    phScreen.userData = { label: 'Xiaomi 14 Pro · 120Hz 液态毛玻璃精密触控板' };
    raycastTargets.push(phScreen);
    phoneRig.add(phScreen);

    // Floating App Window Card (EXACT 1:1 aspect from castFloat crop!)
    const fltU0 = 0.525, fltV0 = 0.04, fltU1 = 0.885, fltV1 = 0.855;
    const fltAspect = TAB_IMG_ASPECT * (fltU1 - fltU0) / (fltV1 - fltV0); // 0.7052 exact!
    const fltH = 2.02;
    const fltW = fltH * fltAspect;
    const floatAppCard = makeCroppedWindowPlane(
      fltW, fltH, 0.10, SHOTS.castFloat,
      fltU0, fltV0, fltU1, fltV1,
      '独立应用悬浮小窗 · 来自 Xiaomi 14 Pro · 1:1 宿主 DPI'
    );
    root.add(floatAppCard.grp);

    /* ======================================================================
       BUILD 3: SLEEK EXTERNAL 2K / OPALDESKMAC MONITOR (EXACT 1.5966 ASPECT)
       ====================================================================== */
    const macRig = new THREE.Group();
    root.add(macRig);
    const macScrH = 2.28;
    const macScrW = macScrH * TAB_IMG_ASPECT; // 3.640 exact!
    const macFrameGeo = new THREE.ExtrudeGeometry(roundedRectShape(THREE, macScrW + 0.24, macScrH + 0.24, 0.16), {
      depth: 0.09,
      bevelEnabled: true,
      bevelThickness: 0.02,
      bevelSize: 0.02,
      bevelSegments: 4
    });
    macFrameGeo.center();
    const macFrameMat = new THREE.MeshStandardMaterial({ color: 0xe2e8f0, metalness: 0.85, roughness: 0.24, transparent: true, opacity: 0 });
    const macBody = new THREE.Mesh(macFrameGeo, macFrameMat);
    macRig.add(macBody);
    const macScreenMat = new THREE.MeshBasicMaterial({ map: SHOTS.dexDark, transparent: true, opacity: 0 });
    const macScreen = new THREE.Mesh(makeRoundedPlaneGeo(THREE, macScrW, macScrH, 0.10), macScreenMat);
    macScreen.position.z = 0.072;
    macScreen.userData = { label: 'OpalDeskMac (Swift 6) · 2K/4K 100Hz+ 独立外接工作站' };
    raycastTargets.push(macScreen);
    macRig.add(macScreen);
    const standMat = new THREE.MeshStandardMaterial({ color: 0xcbd5e1, metalness: 0.88, roughness: 0.22, transparent: true, opacity: 0 });
    const standNeck = new THREE.Mesh(new THREE.BoxGeometry(0.46, 0.72, 0.06), standMat);
    standNeck.position.set(0, -1.52, -0.06);
    const standBase = new THREE.Mesh(new THREE.BoxGeometry(1.38, 0.045, 0.56), standMat);
    standBase.position.set(0, -1.86, 0.06);
    macRig.add(standNeck, standBase);
    macRig.position.set(0.6, 0.25, -1.4);

    /* ======================================================================
       BUILD 4: STAGE 02 VIRTUALDISPLAYPOOL STAGE-MANAGER STACK (EXACT 16:10!)
       ====================================================================== */
    const poolCarousel = new THREE.Group();
    root.add(poolCarousel);
    const poolCardH = 1.34;
    const poolCardW = poolCardH * TAB_IMG_ASPECT; // 2.1395 -> EXACT 1:1 zero stretch!
    const poolCards = POOL_SLOTS.map((slot, idx) => {
      const tex = SHOTS[slot.shot] || SHOTS.dexLight;
      const card = makeCroppedWindowPlane(poolCardW, poolCardH, 0.10, tex, 0, 0, 1, 1, slot.zh);
      card.plane.userData = { kind: 'pool', slot, index: idx };

      // Frosted emerald hibernation veil overlay (only visible when !slot.live!)
      const frostMat = new THREE.MeshBasicMaterial({ color: 0x10b981, transparent: true, opacity: 0 });
      const frostPlane = new THREE.Mesh(makeRoundedPlaneGeo(THREE, poolCardW, poolCardH, 0.10), frostMat);
      frostPlane.position.z = 0.006;
      card.grp.add(frostPlane);

      poolCarousel.add(card.grp);
      return { ...card, frostMat, slot, idx };
    });

    /* ======================================================================
       BUILD 5: STAGE 04 UPRIGHT 3D ARCHITECTURAL GLASS SLABS (100% LEGIBLE!)
       ====================================================================== */
    const waferGroup = new THREE.Group();
    root.add(waferGroup);
    const slabW = 4.20;
    const slabH = 0.84; // Exact 5:1 matching 1600x320 faceplate texture (0% stretch!)
    const slabDepth = 0.18;
    const waferGeo = new THREE.ExtrudeGeometry(roundedRectShape(THREE, slabW, slabH, 0.14), {
      depth: slabDepth,
      bevelEnabled: true,
      bevelThickness: 0.022,
      bevelSize: 0.020,
      bevelSegments: 4
    });
    waferGeo.center();

    const waferRecords = WAFER_SPECS.map((spec, idx) => {
      const grp = new THREE.Group();
      const slabMat = new THREE.MeshPhysicalMaterial({
        color: new THREE.Color(spec.accent),
        roughness: 0.18,
        metalness: 0.32,
        clearcoat: 1.0,
        transparent: true,
        opacity: 0
      });
      const slab = new THREE.Mesh(waferGeo, slabMat);
      grp.add(slab);

      const topTexLight = makeWaferTexture(THREE, spec, true);
      const topTexDark = makeWaferTexture(THREE, spec, false);
      const topMat = new THREE.MeshBasicMaterial({
        map: state.theme === 'pure-frost' ? topTexLight : topTexDark,
        transparent: true,
        opacity: 0
      });
      const frontPlane = new THREE.Mesh(makeRoundedPlaneGeo(THREE, slabW - 0.06, slabH - 0.06, 0.11), topMat);
      frontPlane.position.z = slabDepth * 0.5 + 0.025;
      frontPlane.userData = { kind: 'wafer', spec, index: idx };
      raycastTargets.push(frontPlane);
      grp.add(frontPlane);

      const rimPts = roundedRectShape(THREE, slabW + 0.01, slabH + 0.01, 0.145).getPoints(44);
      const rimMat = new THREE.LineBasicMaterial({ color: new THREE.Color(spec.accent), transparent: true, opacity: 0 });
      const rim = new THREE.LineLoop(
        new THREE.BufferGeometry().setFromPoints(rimPts.map((p) => new THREE.Vector3(p.x, p.y, slabDepth * 0.5 + 0.028))),
        rimMat
      );
      grp.add(rim);

      grp.position.set(0, spec.y, spec.z);
      waferGroup.add(grp);
      return { grp, slabMat, topMat, rimMat, topTexLight, topTexDark, spec, idx };
    });

    // Vertical luminous Binder / UHID interconnect conduits + live pulse beads strictly behind all 4 slabs
    const pillarMat = new THREE.MeshBasicMaterial({ color: 0x38bdf8, transparent: true, opacity: 0 });
    const archBeads = [];
    [-1.45, 0, 1.45].forEach((px, pIdx) => {
      const p = new THREE.Mesh(new THREE.CylinderGeometry(0.015, 0.015, 3.25, 10), pillarMat);
      p.position.set(px, 0, -0.46);
      waferGroup.add(p);
      const bm = new THREE.MeshBasicMaterial({ color: 0x38bdf8, transparent: true, opacity: 0 });
      const bead = new THREE.Mesh(new THREE.SphereGeometry(0.052, 12, 12), bm);
      waferGroup.add(bead);
      archBeads.push({ mesh: bead, mat: bm, x: px, phase: pIdx * 0.33 });
    });

    /* Theme Switch Sync (Live Texture Swap on Hardware & Wafers!) */
    onThemeChangeCb = (nextTheme) => {
      targetBlend = nextTheme === 'pure-frost' ? 1.0 : 0.0;
      const isLight = nextTheme === 'pure-frost';
      dockMat.map = makeDockTexture(THREE, isLight);
      dockMat.needsUpdate = true;
      waferRecords.forEach((w) => {
        w.topMat.map = isLight ? w.topTexLight : w.topTexDark;
        w.topMat.needsUpdate = true;
      });
    };

    /* Raycaster for Glass Hover Tooltips */
    const raycaster = new THREE.Raycaster();
    const ptrVec = new THREE.Vector2();
    let lastHitUuid = null;

    function updateHover() {
      if (state.pointerOverUI) {
        lastHitUuid = null;
        cursorAura.classList.remove('hovering-node');
        return;
      }
      ptrVec.set(state.pointerX, state.pointerY);
      raycaster.setFromCamera(ptrVec, camera);
      const hits = raycaster.intersectObjects(raycastTargets, false).filter((h) => {
        let obj = h.object;
        while (obj) {
          if (!obj.visible) return false;
          obj = obj.parent;
        }
        const m = h.object.material;
        return !m.transparent || m.opacity > 0.28;
      });
      if (hits.length > 0) {
        const obj = hits[0].object;
        const ud = obj.userData || {};
        let text = ud.label || '';
        if (ud.kind === 'pool') text = state.lang === 'en' ? `${ud.slot.titleEn} · ${ud.slot.sub}` : `${ud.slot.titleZh} · ${ud.slot.sub}`;
        if (ud.kind === 'wafer') text = state.lang === 'en' ? ud.spec.en : ud.spec.zh;
        if (text) {
          cursorNodeLabel.textContent = text;
          cursorAura.classList.add('hovering-node');
          if (lastHitUuid !== obj.uuid) {
            lastHitUuid = obj.uuid;
            if (window.OpalSound) window.OpalSound.playHover(ud.index || 0);
          }
          return;
        }
      }
      lastHitUuid = null;
      cursorAura.classList.remove('hovering-node');
    }

    /* ======================================================================
       DOM-AWARE SAFE-BOX PROJECTION (PIXEL-EXACT ACROSS EVERY SCREEN SIZE)
       Measures real header, rail & glass cards -> computes exact free world rect
       ====================================================================== */
    let safeBoxes = null;
    function measureSafeBoxes() {
      const W = Math.max(320, window.innerWidth);
      const H = Math.max(400, window.innerHeight);
      const isSplit = W > 960;
      const halfH0 = Math.tan(THREE.MathUtils.degToRad(camera.fov * 0.5)) * camera.position.z;
      const halfW0 = halfH0 * (W / H);

      const headerEl = document.querySelector('.header-capsule');
      const headerBottom = headerEl ? Math.max(54, headerEl.getBoundingClientRect().bottom) : 64;
      const topSafe = headerBottom + 14;

      const railEl = document.querySelector('.timecode-rail');
      const railLeft = (isSplit && railEl && railEl.offsetWidth > 0)
        ? Math.min(W - 56, railEl.getBoundingClientRect().left - 14)
        : W - 16;

      function toWorldBox(pxL, pxR, pxT, pxB) {
        const l = Math.max(12, pxL);
        const r = Math.max(l + 120, Math.min(W - 12, pxR));
        const t = Math.max(topSafe, pxT);
        const b = Math.max(t + 120, Math.min(H - 12, pxB));
        const ndcX = ((l + r) * 0.5 / W) * 2 - 1;
        const ndcY = 1 - ((t + b) * 0.5 / H) * 2;
        return {
          cx: ndcX * halfW0,
          cy: ndcY * halfH0,
          availW: ((r - l) / W) * (2 * halfW0),
          availH: ((b - t) / H) * (2 * halfH0)
        };
      }

      function measureSceneBox(secIdx, side) {
        const sec = scenes[secIdx];
        const copy = sec ? sec.querySelector('.scene-copy') : null;
        const cs = sec ? window.getComputedStyle(sec) : null;
        const padL = cs ? parseFloat(cs.paddingLeft) || 24 : 24;
        const padR = cs ? parseFloat(cs.paddingRight) || 24 : 24;
        const padB = cs ? parseFloat(cs.paddingBottom) || 24 : 24;
        const cardW = copy ? copy.offsetWidth : Math.min(460, W * 0.4);
        const cardH = copy ? copy.offsetHeight : 250;

        if (side === 'bottom' || !isSplit) {
          const bottomLimit = Math.max(topSafe + 140, H - padB - cardH - 14);
          return toWorldBox(16, isSplit ? railLeft : W - 16, topSafe, bottomLimit);
        }
        if (side === 'right') {
          const leftLimit = padL + cardW + 24;
          return toWorldBox(leftLimit, railLeft, topSafe + 8, H - 24);
        }
        const rightLimit = W - padR - cardW - 24;
        return toWorldBox(24, rightLimit, topSafe + 8, H - 24);
      }

      safeBoxes = {
        isSplit,
        b0: measureSceneBox(0, 'bottom'),
        b1: measureSceneBox(1, 'right'),
        b2: measureSceneBox(2, 'left'),
        b3: measureSceneBox(3, 'right'),
        b4: measureSceneBox(4, 'left'),
        b5: measureSceneBox(5, 'bottom')
      };
    }

    onLayoutChangeCb = measureSafeBoxes;
    measureSafeBoxes();
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(measureSafeBoxes).catch(() => {});
    }

    window.addEventListener('resize', () => {
      camera.aspect = window.innerWidth / window.innerHeight;
      camera.updateProjectionMatrix();
      renderer.setSize(window.innerWidth, window.innerHeight);
      measureSafeBoxes();
    });

    /* Smooth Choreographed Render Loop */
    const clock = new THREE.Clock();
    let smoothMorph = 0;
    let smoothExplode = 0;

    function lerp(a, b, k) { return a + (b - a) * k; }
    function clamp01(v) { return Math.max(0, Math.min(1, v)); }
    function smoothstep(a, b, x) { const t = clamp01((x - a) / (b - a)); return t * t * (3 - 2 * t); }

    function animate() {
      requestAnimationFrame(animate);
      const dt = Math.min(clock.getDelta(), 0.05);
      const t = clock.elapsedTime;

      themeBlend = lerp(themeBlend, targetBlend, Math.min(1, dt * 6));
      liveBg.copy(darkBg).lerp(lightBg, themeBlend);
      scene.background = liveBg;

      // Gently spring orbit drag back when released
      if (!state.dragging) {
        state.orbitYaw = lerp(state.orbitYaw, 0, Math.min(1, dt * 2.2));
        state.orbitPitch = lerp(state.orbitPitch, 0, Math.min(1, dt * 2.2));
      }

      smoothMorph = lerp(smoothMorph, state.morph, Math.min(1, dt * 6.0));
      state.explodePulse = Math.max(0, state.explodePulse - dt * 1.5);
      state.castPulse = Math.max(0, state.castPulse - dt * 1.1);

      haloUniforms.uMorph.value = smoothMorph;
      haloUniforms.uLight.value = themeBlend;
      haloUniforms.uPulse.value = state.explodePulse + state.castPulse * 0.5;

      if (!safeBoxes) measureSafeBoxes();
      const { b0, b1, b2, b3, b4, b5 } = safeBoxes;

      // Exact unit scales computed from real viewport safe boxes (0% clip on any aspect ratio!)
      const s0 = Math.max(0.26, Math.min(0.95, b0.availW / 6.65, b0.availH / 3.35));
      const s1 = Math.max(0.26, Math.min(0.95, b1.availW / 5.75, b1.availH / 3.85));
      const s2 = Math.max(0.26, Math.min(0.98, b2.availW / 4.95, b2.availH / 4.15));
      const s3 = Math.max(0.26, Math.min(0.95, b3.availW / 5.95, b3.availH / 3.85));
      const s4 = Math.max(0.26, Math.min(0.98, b4.availW / 4.75, b4.availH / 4.35));
      const s5 = Math.max(0.26, Math.min(0.95, b5.availW / 6.65, b5.availH / 3.35));

      // Stage visibility weights
      const w0 = 1 - smoothstep(0.15, 0.85, smoothMorph);
      const w1 = smoothstep(0.25, 0.95, smoothMorph) * (1 - smoothstep(1.25, 1.9, smoothMorph));
      const w2 = smoothstep(1.3, 1.95, smoothMorph) * (1 - smoothstep(2.25, 2.9, smoothMorph));
      const w3 = smoothstep(2.3, 2.95, smoothMorph) * (1 - smoothstep(3.25, 3.9, smoothMorph));
      const w4 = smoothstep(3.3, 3.95, smoothMorph) * (1 - smoothstep(4.25, 4.9, smoothMorph));
      const w5 = smoothstep(4.35, 4.95, smoothMorph);

      /* --- 1. TABLET RIG CHOREOGRAPHY (Stages 00, 01, 03, 05) --- */
      const tabSum = w0 + w1 + w3 + w5;
      const tabVis = clamp01(tabSum);
      tabletRig.visible = tabVis > 0.02;
      if (tabletRig.visible) {
        let desiredTex = state.theme === 'pure-frost' ? SHOTS.dexLight : SHOTS.dexDark;
        if (w3 > 0.45) {
          desiredTex = state.topo === 'float' ? SHOTS.castFloat : SHOTS.castDesktop;
        } else if (w1 > 0.45 && state.theme === 'prism-pulse') {
          desiredTex = SHOTS.dexDark;
        }
        if (tabletScreenMat.map !== desiredTex) {
          tabletScreenMat.map = desiredTex;
          tabletScreenMat.needsUpdate = true;
        }
        const winTex = state.theme === 'pure-frost' ? SHOTS.dexLight : SHOTS.dexDark;
        if (layerClock.screenMat.map !== winTex) {
          layerClock.screenMat.map = winTex;
          layerCalc.screenMat.map = winTex;
        }

        const n0 = w0 / tabSum, n1 = w1 / tabSum, n3 = w3 / tabSum, n5 = w5 / tabSum;
        const isFlt = state.topo === 'float';
        const tx0 = b0.cx - 0.82 * s0, ty0 = b0.cy + 0.02 * s0, tz0 = -0.35, sc0 = 0.86 * s0;
        const tx1 = b1.cx - 0.14 * s1, ty1 = b1.cy, tz1 = -0.38, sc1 = 0.90 * s1;
        const tx3 = b3.cx + (isFlt ? 0.30 : 0.50) * s3, ty3 = b3.cy + 0.16 * s3, tz3 = isFlt ? -0.55 : -0.42, sc3 = (isFlt ? 0.84 : 0.86) * s3;
        const tx5 = b5.cx - 0.82 * s5, ty5 = b5.cy + 0.02 * s5, tz5 = -0.35, sc5 = 0.86 * s5;

        const tx = n0 * tx0 + n1 * tx1 + n3 * tx3 + n5 * tx5;
        const ty = n0 * ty0 + n1 * ty1 + n3 * ty3 + n5 * ty5 + Math.sin(t * 1.1) * 0.035;
        const tz = n0 * tz0 + n1 * tz1 + n3 * tz3 + n5 * tz5;

        const ry = (n0 * 0.15) + (n1 * -0.30) + (n3 * -0.18) + (n5 * 0.15) + state.pointerX * 0.12 + state.orbitYaw;
        const rx = (n0 * -0.05) + (n1 * 0.12) + (n3 * 0.05) + (n5 * -0.05) - state.pointerY * 0.07 + state.orbitPitch;
        const sc = (n0 * sc0 + n1 * sc1 + n3 * sc3 + n5 * sc5) * (0.65 + 0.35 * tabVis);

        tabletRig.position.set(tx, ty, tz);
        tabletRig.rotation.set(rx, ry, 0);
        tabletRig.scale.setScalar(sc);

        // Exploded Wayland Z-Depth Separation (active in Stage 01 OR toggled in Stage 00!)
        const targetExp = clamp01(w1 + (w0 > 0.5 && state.explodeToggle ? 0.95 : 0) + state.explodePulse * 0.25);
        smoothExplode = lerp(smoothExplode, targetExp, Math.min(1, dt * 6.5));

        const showExp = smoothExplode > 0.04;
        layerClock.grp.visible = showExp;
        layerCalc.grp.visible = showExp;
        layerCtrl.grp.visible = showExp;
        layerDock.visible = showExp;

        layerClock.grp.position.z = 0.09 + smoothExplode * 0.56;
        layerClock.grp.position.x = -scrW * 0.248 - smoothExplode * 0.10;
        layerClock.screenMat.opacity = smoothExplode * 0.96;
        layerClock.backMat.opacity = smoothExplode * 0.72;
        layerClock.rimMat.opacity = smoothExplode * 0.78;

        layerCalc.grp.position.z = 0.09 + smoothExplode * 0.94;
        layerCalc.grp.position.x = scrW * 0.248 + smoothExplode * 0.08;
        layerCalc.screenMat.opacity = smoothExplode * 0.96;
        layerCalc.backMat.opacity = smoothExplode * 0.72;
        layerCalc.rimMat.opacity = smoothExplode * 0.78;

        layerCtrl.grp.position.z = 0.12 + smoothExplode * 1.36;
        layerCtrl.grp.position.x = scrW * 0.29 + smoothExplode * 0.12;
        layerCtrl.screenMat.opacity = smoothExplode * 0.94;
        layerCtrl.backMat.opacity = smoothExplode * 0.78;
        layerCtrl.rimMat.opacity = smoothExplode * 0.68;

        layerDock.position.z = 0.14 + smoothExplode * 1.66;
        dockMat.opacity = smoothExplode * 0.96;
      }

      /* --- 2. PHONE RIG CHOREOGRAPHY (Stages 00, 03, 05) --- */
      const phSum = w0 + w3 + w5;
      const phVis = clamp01(phSum);
      phoneRig.visible = phVis > 0.02;
      if (phoneRig.visible) {
        const desiredPh = (w3 > 0.4 && state.topo === 'float') ? SHOTS.phoneCasting : SHOTS.phoneTrackpad;
        if (phScreenMat.map !== desiredPh) {
          phScreenMat.map = desiredPh;
          phScreenMat.needsUpdate = true;
        }
        const p0 = w0 / phSum, p3 = w3 / phSum, p5 = w5 / phSum;
        const px0 = b0.cx + 2.08 * s0, py0 = b0.cy - 0.04 * s0, pz0 = 0.35, psc0 = 0.76 * s0;
        const px3 = b3.cx - 1.62 * s3, py3 = b3.cy - 0.46 * s3, pz3 = 0.72, psc3 = 0.74 * s3;
        const px5 = b5.cx + 2.08 * s5, py5 = b5.cy - 0.04 * s5, pz5 = 0.35, psc5 = 0.76 * s5;

        const px = p0 * px0 + p3 * px3 + p5 * px5;
        const py = p0 * py0 + p3 * py3 + p5 * py5 + Math.cos(t * 1.25) * 0.035;
        const pz = p0 * pz0 + p3 * pz3 + p5 * pz5;

        const prx = (p0 * -0.05) + (p3 * -0.40) + (p5 * -0.05) - state.pointerY * 0.07 + state.orbitPitch;
        const pry = (p0 * -0.22) + (p3 * 0.24) + (p5 * -0.22) + state.pointerX * 0.14 + state.orbitYaw;
        const psc = (p0 * psc0 + p3 * psc3 + p5 * psc5) * (0.65 + 0.35 * phVis);

        phoneRig.position.set(px, py, pz);
        phoneRig.rotation.set(prx, pry, p3 * -0.06);
        phoneRig.scale.setScalar(psc);
      }

      /* --- 3. STAGE 03 FLOATING APP FLIGHT & EXTERNAL MAC DISPLAY --- */
      const fltW = w3 * (state.topo === 'float' ? 1.0 : 0.0);
      floatAppCard.grp.visible = fltW > 0.03;
      if (floatAppCard.grp.visible) {
        floatAppCard.screenMat.opacity = fltW * 0.98;
        floatAppCard.backMat.opacity = fltW * 0.85;
        floatAppCard.rimMat.opacity = fltW * 0.90;
        floatAppCard.grp.scale.setScalar(0.82 * s3);
        floatAppCard.grp.position.set(
          b3.cx + 0.82 * s3 + Math.sin(t * 1.4) * 0.04,
          b3.cy + 0.16 * s3 + Math.cos(t * 1.6) * 0.04,
          0.90
        );
        floatAppCard.grp.rotation.set(0.05 - state.pointerY * 0.07, -0.20 + state.pointerX * 0.11 + state.orbitYaw, 0);
      }

      const macW = w3 * (state.topo === 'mac' ? 1.0 : 0.0);
      macRig.visible = macW > 0.03;
      if (macRig.visible) {
        macFrameMat.opacity = macW * 0.95;
        macScreenMat.opacity = macW * 0.98;
        standMat.opacity = macW * 0.95;
        macRig.scale.setScalar(0.90 * s3);
        macRig.position.set(b3.cx + 0.44 * s3, b3.cy + 0.22 * s3 + Math.sin(t) * 0.035, -0.35);
        macRig.rotation.set(0.05 - state.pointerY * 0.06, -0.18 + state.pointerX * 0.11 + state.orbitYaw, 0);
        if (state.topo === 'mac') tabletRig.visible = false;
      }

      /* --- 4. STAGE 02 VIRTUALDISPLAYPOOL STAGE-MANAGER 3D FAN (0% STRETCH, 0% CLIP) --- */
      poolCarousel.visible = w2 > 0.03;
      if (poolCarousel.visible) {
        poolCarousel.position.set(b2.cx, b2.cy, 0.0);
        poolCarousel.scale.setScalar(s2);
        poolCarousel.rotation.y = 0.26 + state.pointerX * 0.12 + state.orbitYaw;
        poolCarousel.rotation.x = -0.04 - state.pointerY * 0.06 + state.orbitPitch;

        let liveRank = 0;
        let hibRank = 0;
        const totalLive = poolCards.filter((x) => x.slot.live).length;
        const totalHib = poolCards.length - totalLive;

        poolCards.forEach((c) => {
          const isLive = c.slot.live;
          let targetX, targetY, targetZ, targetRotY;
          if (isLive) {
            const centerShift = (liveRank - (totalLive - 1) * 0.5);
            targetX = centerShift * 0.52 + 0.32;
            targetY = centerShift * -0.34 + 0.34 + Math.sin(t * 1.1 + c.idx) * 0.03;
            targetZ = 0.88 - liveRank * 0.34;
            targetRotY = -0.14 + centerShift * 0.05;
            liveRank++;
          } else {
            const centerShift = (hibRank - (totalHib - 1) * 0.5);
            targetX = -0.82 + centerShift * 0.50;
            targetY = -1.06 + centerShift * -0.14 + Math.cos(t * 1.0 + c.idx) * 0.025;
            targetZ = -0.72 - hibRank * 0.32;
            targetRotY = 0.20;
            hibRank++;
          }

          c.grp.position.x = lerp(c.grp.position.x, targetX, Math.min(1, dt * 7));
          c.grp.position.y = lerp(c.grp.position.y, targetY, Math.min(1, dt * 7));
          c.grp.position.z = lerp(c.grp.position.z, targetZ, Math.min(1, dt * 7));
          c.grp.rotation.y = lerp(c.grp.rotation.y, targetRotY, Math.min(1, dt * 7));
          c.grp.scale.setScalar(lerp(c.grp.scale.x, isLive ? 1.0 : 0.78, Math.min(1, dt * 7)));

          c.screenMat.opacity = w2 * (isLive ? 0.98 : 0.68);
          c.backMat.opacity = w2 * (isLive ? 0.78 : 0.42);
          c.frostMat.opacity = w2 * (isLive ? 0.0 : 0.22);
          c.rimMat.color.setHex(isLive ? 0x2997ff : 0x30d158);
          c.rimMat.opacity = w2 * (isLive ? 0.94 : 0.80);
        });
      }

      /* --- 5. STAGE 04 UPRIGHT 3D ARCHITECTURAL GLASS SLABS (CRISP & LEGIBLE) --- */
      waferGroup.visible = w4 > 0.03;
      if (waferGroup.visible) {
        waferGroup.position.set(b4.cx, b4.cy, 0.15);
        waferGroup.scale.setScalar(s4);
        waferGroup.rotation.set(
          0.04 - state.pointerY * 0.06 + state.orbitPitch,
          0.20 + state.pointerX * 0.13 + Math.sin(t * 0.35) * 0.035 + state.orbitYaw,
          0
        );
        pillarMat.opacity = w4 * 0.48;
        archBeads.forEach((b) => {
          b.mat.opacity = w4 * 0.90;
          const u = ((t * 0.42 + b.phase) % 1.0);
          b.mesh.position.set(b.x, -1.56 + u * 3.12, -0.46);
        });
        waferRecords.forEach((w, i) => {
          w.slabMat.opacity = w4 * (themeBlend > 0.5 ? 0.34 : 0.46);
          w.topMat.opacity = w4 * 0.98;
          w.rimMat.opacity = w4 * 0.65;
          w.grp.position.y = w.spec.y + Math.sin(t * 1.05 + i * 0.7) * 0.03;
          w.grp.position.z = w.spec.z;
        });
      }

      updateHover();
      renderer.render(scene, camera);
    }
    animate();
  }

  /* Graceful Canvas 2D Fallback (Also Uses Real Hardware Screenshots!) */
  function mountCanvasFallback() {
    const canvas = document.createElement('canvas');
    container.appendChild(canvas);
    const ctx = canvas.getContext('2d');
    const imgLight = new Image();
    imgLight.src = './assets/screenshots/dex-workstation-light.jpg';
    const imgDark = new Image();
    imgDark.src = './assets/screenshots/dex-workstation-dark.jpg';
    const imgPhone = new Image();
    imgPhone.src = './assets/screenshots/phone-trackpad.jpg';

    function resize() {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = window.innerWidth * dpr;
      canvas.height = window.innerHeight * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    resize();
    window.addEventListener('resize', resize);

    function draw(now) {
      requestAnimationFrame(draw);
      const t = now * 0.001;
      const w = window.innerWidth;
      const h = window.innerHeight;
      const light = state.theme === 'pure-frost';
      ctx.fillStyle = light ? '#EDF2FA' : '#090C12';
      ctx.fillRect(0, 0, w, h);

      const cx = w * 0.5 + state.pointerX * 18;
      const cy = h * 0.40 + Math.sin(t) * 6;
      const tw = Math.min(640, w * 0.62);
      const th = tw * 0.625;
      const tabImg = light ? imgLight : imgDark;

      if (tabImg.complete && tabImg.naturalWidth > 0) {
        ctx.save();
        ctx.shadowColor = 'rgba(0,0,0,0.28)';
        ctx.shadowBlur = 42;
        ctx.shadowOffsetY = 22;
        ctx.fillStyle = '#14171F';
        ctx.beginPath();
        ctx.roundRect(cx - tw / 2 - 10, cy - th / 2 - 10, tw + 20, th + 20, 22);
        ctx.fill();
        ctx.restore();

        ctx.save();
        ctx.beginPath();
        ctx.roundRect(cx - tw / 2, cy - th / 2, tw, th, 14);
        ctx.clip();
        ctx.drawImage(tabImg, cx - tw / 2, cy - th / 2, tw, th);
        ctx.restore();
      }
      if (imgPhone.complete && imgPhone.naturalWidth > 0 && w > 640) {
        const pw = 148;
        const ph = pw * 2.15;
        const px = cx + tw * 0.42;
        const py = cy + th * 0.08;
        ctx.save();
        ctx.shadowColor = 'rgba(0,0,0,0.34)';
        ctx.shadowBlur = 32;
        ctx.fillStyle = '#14171F';
        ctx.beginPath();
        ctx.roundRect(px - 7, py - 7, pw + 14, ph + 14, 24);
        ctx.fill();
        ctx.beginPath();
        ctx.roundRect(px, py, pw, ph, 18);
        ctx.clip();
        ctx.drawImage(imgPhone, px, py, pw, ph);
        ctx.restore();
      }
    }
    requestAnimationFrame(draw);
  }

  /* Boot */
  applyLang(state.lang);
  updateScrollState();
  if (bootBarFill) bootBarFill.style.width = '72%';

  loadThree()
    .then((THREE) => {
      if (THREE) {
        try { mountSignature3D(THREE); }
        catch (_) { mountCanvasFallback(); }
      } else {
        mountCanvasFallback();
      }
    })
    .finally(() => {
      if (bootBarFill) bootBarFill.style.width = '100%';
      setTimeout(() => { if (bootVeil) bootVeil.classList.add('ready'); }, 220);
    });
})();

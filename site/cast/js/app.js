/**
 * OpalDesk Web Cast Receiver & LAN Activation Controller.
 * Orchestrates OpalCast v1.1 over WSS/WS, WebCodecs video/audio, input/IME, file transfer,
 * remote app launcher (+ Document PiP standalone windows), and Helper LAN activation handover.
 */

import { buildTrustPageUrl, LanActivationClient, probeHttpsHealth } from './activation_client.js';
import { OpalAudioPlayer } from './audio_player.js';
import { OpalInputController } from './input_mapper.js';
import {
  decodeAppList,
  decodeAuthResult,
  decodeClipboardPush,
  decodeDeviceState,
  decodeFileTransferAck,
  decodeFrame,
  decodeImeRequest,
  decodePingPong,
  decodeVideoConfig,
  decodeAudioConfig,
  DISPLAY_MODE_DEX,
  encodeFileTransferChunk,
  encodeFrame,
  encodeHello,
  encodeImageClipboardEvent,
  encodeKeyEvent,
  encodeMouseEvent,
  encodePingPong,
  encodeResizeConfig,
  encodeSystemAction,
  encodeTextEvent,
  encodeTouchEvent,
  encodeWindowAction,
  FILE_ACK_OK,
  FILE_FLAG_DATA,
  FILE_FLAG_END,
  FILE_FLAG_START,
  FLAG_CLIPBOARD_IMAGE_PNG,
  SESSION_ROLE_DESKTOP,
  SESSION_ROLE_STANDALONE_APP,
  SYS_BACK,
  SYS_DISCONNECT,
  SYS_HOME,
  SYS_SET_AUDIO_ENABLED,
  TEXT_MODE_COMMIT_PASTE,
  TYPE_APP_LIST,
  TYPE_AUDIO_CONFIG,
  TYPE_AUDIO_FRAME,
  TYPE_AUTH_RESULT,
  TYPE_CLIPBOARD_PUSH,
  TYPE_DEVICE_STATE,
  TYPE_FILE_TRANSFER_ACK,
  TYPE_FILE_TRANSFER_CHUNK,
  TYPE_HELLO,
  TYPE_IME_REQUEST,
  TYPE_INPUT_KEY,
  TYPE_INPUT_MOUSE,
  TYPE_INPUT_TEXT,
  TYPE_INPUT_TOUCH,
  TYPE_LIST_APPS,
  TYPE_PING,
  TYPE_PONG,
  TYPE_REQUEST_IDR,
  TYPE_RESIZE_CONFIG,
  TYPE_SYSTEM_ACTION,
  TYPE_VIDEO_CONFIG,
  TYPE_VIDEO_FRAME,
  TYPE_WINDOW_ACTION,
} from './protocol.js';
import { OpalVideoDecoder, probeVideoCodecs } from './video_decoder.js';

const STORAGE_CLIENT_ID = 'opaldesk-web-client-id-v1';
const STORAGE_DEVICES = 'opaldesk-web-devices-v1';
const STORAGE_THEME = 'opaldesk-showcase-theme-v1';
const FILE_CHUNK_SIZE = 64 * 1024;

const $ = (id) => document.getElementById(id);

function getOrCreateClientId() {
  try {
    let cid = localStorage.getItem(STORAGE_CLIENT_ID);
    if (cid && cid.length >= 12) return cid;
    const arr = new Uint8Array(12);
    crypto.getRandomValues(arr);
    cid = 'web-' + Array.from(arr, (b) => b.toString(16).padStart(2, '0')).join('');
    localStorage.setItem(STORAGE_CLIENT_ID, cid);
    return cid;
  } catch {
    return 'web-' + Math.random().toString(16).slice(2, 14);
  }
}

function loadSavedDevices() {
  try {
    const raw = localStorage.getItem(STORAGE_DEVICES);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveDeviceRecord(rec) {
  try {
    const list = loadSavedDevices().filter((d) => !(d.host === rec.host && d.port === rec.port));
    list.unshift({ ...rec, updatedAt: Date.now() });
    localStorage.setItem(STORAGE_DEVICES, JSON.stringify(list.slice(0, 8)));
  } catch {
    // Ignore storage quota errors
  }
}

function getSavedToken(host, port) {
  const found = loadSavedDevices().find((d) => d.host === host && Number(d.port) === Number(port));
  return found?.authToken || '';
}

function detectBrowserName() {
  const ua = navigator.userAgent || '';
  if (ua.includes('Edg/')) return 'OpalDesk Web (Edge)';
  if (ua.includes('Chrome/')) return 'OpalDesk Web (Chrome)';
  if (ua.includes('Firefox/')) return 'OpalDesk Web (Firefox)';
  if (ua.includes('Safari/')) return 'OpalDesk Web (Safari)';
  return 'OpalDesk Web';
}

function isSafariBrowser() {
  const ua = navigator.userAgent || '';
  return ua.includes('Safari/') && !ua.includes('Chrome/') && !ua.includes('Chromium/') && !ua.includes('Edg/');
}

class OpalCastWebApp {
  constructor() {
    this.clientId = getOrCreateClientId();
    this.transportMode = 'wifi'; // 'wifi' | 'usb'
    this.sessionRole = SESSION_ROLE_DESKTOP;
    this.standaloneAppPkg = '';
    this.standaloneAppCls = '';

    this.ws = null;
    this.connected = false;
    this.authenticated = false;
    this.remoteWidth = 1920;
    this.remoteHeight = 1080;
    this.remoteFps = 60;
    this.lastRttMs = 0;
    this.pingIdSeq = 1n;
    this.pingSentMap = new Map();
    this.pingTimer = null;
    this.toolbarFadeTimer = null;
    this.resizeDebounceTimer = null;
    this.textSeq = 1;
    this.transferSeq = 1;
    this.currentApps = [];
    this.resizeMode = 'auto';
    this.activeHost = '';
    this.activePort = 19878;

    this.toastTimer = null;
    this.toastActionFn = null;

    this.video = new OpalVideoDecoder($('castCanvas'), {
      onResize: (w, h, fps) => {
        this.remoteWidth = w;
        this.remoteHeight = h;
        if (fps > 0) this.remoteFps = Math.round(fps);
        this.updateTelemetry();
      },
      onRequestIdr: () => this.sendOpalFrame(TYPE_REQUEST_IDR),
      onStats: (st) => {
        this.remoteFps = st.fps;
        this.updateTelemetry();
      },
      onError: (msg) => this.showToast(msg),
    });

    this.audio = new OpalAudioPlayer({
      onStateChange: (st) => this.syncAudioUi(st),
    });

    this.input = new OpalInputController($('castCanvas'), $('imeBridge'), {
      getRemoteSize: () => ({ width: this.remoteWidth, height: this.remoteHeight }),
      onMouse: (action, x, y, buttons, actBtn, hScroll, vScroll) => {
        this.sendOpalFrame(TYPE_INPUT_MOUSE, 0, encodeMouseEvent(action, x, y, buttons, actBtn, hScroll, vScroll));
      },
      onTouch: (action, idx, pointers) => {
        this.sendOpalFrame(TYPE_INPUT_TOUCH, 0, encodeTouchEvent(action, idx, pointers));
      },
      onKey: (action, code, meta, repeat) => {
        this.sendOpalFrame(TYPE_INPUT_KEY, 0, encodeKeyEvent(action, code, meta, repeat));
      },
      onText: (text, commit) => {
        const mode = commit ? TEXT_MODE_COMMIT_PASTE : 1;
        this.sendOpalFrame(TYPE_INPUT_TEXT, 0, encodeTextEvent(mode, this.textSeq++, text));
      },
      onImageClipboard: (pngBytes) => {
        this.sendOpalFrame(
          TYPE_INPUT_TEXT,
          FLAG_CLIPBOARD_IMAGE_PNG,
          encodeImageClipboardEvent(TEXT_MODE_COMMIT_PASTE, this.textSeq++, pngBytes)
        );
        this.showToast('已粘贴图片到手机端');
      },
      onFileDrop: (files) => this.sendDroppedFiles(files),
      onUserGesture: () => this.audio.resumeFromGesture(),
    });

    this.activation = new LanActivationClient({
      onStatus: (_st, msg) => this.appendActLog(msg),
      onAuthResult: (res) => {
        if (res.ok) {
          this.setActError('');
          $('actTrustBox').hidden = true;
          $('actWorkspace').hidden = false;
          $('disconnectActBtn').hidden = false;
          this.appendActLog('✓ 访问码认证通过，等待手机端无线调试端口信息…');
        } else {
          this.setActError(res.message || '6 位访问码不正确');
          this.appendActLog(`✗ 认证失败: ${res.message || ''}`);
        }
      },
      onState: (state) => this.renderActivationState(state),
      onProgress: (msg) => this.appendActLog(`▸ ${msg}`),
      onResult: (res) => {
        this.appendActLog(`${res.ok ? '✓' : '✗'} ${res.message || ''}`);
        this.setActBusy(false);
        if (!res.ok) this.setActError(res.message || '激活未成功');
      },
      onReadyForCast: (info) => {
        this.appendActLog(`★ ${info.message || '已就绪'}`);
        if (info.ok && info.host) {
          this.showToast('引擎激活成功，正在切入 Web 投屏…');
          this.activation.disconnect();
          $('castHostInput').value = info.host;
          $('castPortInput').value = String(info.castPort || 19878);
          this.selectLobbyTab('cast');
          this.selectTransportMode('wifi');
          setTimeout(() => this.startCastConnection(), 450);
        }
      },
      onError: (msg) => {
        this.setActError(msg);
        this.appendActLog(`! ${msg}`);
      },
      onClose: (reason) => {
        this.appendActLog(`连接关闭: ${reason}`);
        $('disconnectActBtn').hidden = true;
      },
    });

    this.init();
  }

  async init() {
    this.bindUiEvents();
    this.input.attach();
    this.renderRecentDevices();
    this.installTrustPopupListener();
    this.installResizeObserver();

    const caps = await probeVideoCodecs();
    const badge = $('codecBadge');
    if (!caps.supported) {
      badge.textContent = 'WebCodecs 不可用';
      badge.classList.add('warn');
      this.showBrowserWarn(
        '当前浏览器不支持 WebCodecs 硬件视频解码（或处于非安全上下文）。请通过 GitHub Pages HTTPS 在最新版 Chrome / Edge / Firefox 中打开。'
      );
    } else {
      badge.textContent = caps.hevc ? 'H.264 + HEVC 硬解就绪' : 'H.264 硬解就绪';
      badge.classList.add('ok');
      if (!caps.hevc) {
        const hevcOpt = $('castCodecSelect').querySelector('option[value="hevc"]');
        if (hevcOpt) hevcOpt.textContent = 'HEVC / H.265 (当前浏览器未检出硬解)';
      }
    }

    if (isSafariBrowser()) {
      this.showBrowserWarn(
        '检测到 Safari 浏览器：Safari 对公网页面跨源连接局域网自签名 WSS 限制较严，若放行证书后仍无法握手，建议使用 Chrome 或 Edge 浏览器。'
      );
    }

    this.applyUrlParams();
  }

  applyUrlParams() {
    const url = new URL(window.location.href);
    const params = url.searchParams;
    const hashParams = new URLSearchParams(url.hash.replace(/^#/, ''));
    const mode = params.get('mode') || '';
    const host = params.get('host') || '';
    const port = params.get('port') || params.get('castPort') || '';
    const actPort = params.get('actPort') || '';
    const transport = params.get('transport') || '';
    const role = params.get('role') || '';
    const pkg = params.get('pkg') || '';
    const cls = params.get('cls') || '';
    const sessionCode = hashParams.get('code') || '';

    // Erase sensitive one-time session code from URL bar immediately after reading
    if (sessionCode) {
      try {
        url.hash = '';
        window.history.replaceState(null, '', url.toString());
      } catch {
        // Ignore history restriction
      }
    }

    if (host) {
      $('castHostInput').value = host;
      $('actHostInput').value = host;
    } else {
      const recent = loadSavedDevices()[0];
      if (recent) {
        $('castHostInput').value = recent.host;
        $('castPortInput').value = String(recent.port || 19878);
        $('actHostInput').value = recent.host;
      }
    }
    if (port) $('castPortInput').value = port;
    if (actPort) $('actPortInput').value = actPort;
    if (sessionCode) $('actCodeInput').value = sessionCode;
    if (transport === 'usb') this.selectTransportMode('usb');

    if (role === SESSION_ROLE_STANDALONE_APP && pkg) {
      this.sessionRole = SESSION_ROLE_STANDALONE_APP;
      this.standaloneAppPkg = pkg;
      this.standaloneAppCls = cls;
      if (host) {
        setTimeout(() => this.startCastConnection(), 200);
        return;
      }
    }

    if (mode === 'activate') {
      this.selectLobbyTab('activate');
      if (host && sessionCode) {
        setTimeout(() => this.startActivationConnection(), 250);
      }
    }
  }

  bindUiEvents() {
    $('themeToggleBtn').addEventListener('click', () => {
      const cur = document.documentElement.getAttribute('data-theme');
      const next = cur === 'prism-pulse' ? 'pure-frost' : 'prism-pulse';
      document.documentElement.setAttribute('data-theme', next);
      try { localStorage.setItem(STORAGE_THEME, next); } catch {}
    });

    $('tabCastBtn').addEventListener('click', () => this.selectLobbyTab('cast'));
    $('tabActivateBtn').addEventListener('click', () => this.selectLobbyTab('activate'));
    $('modeWifiBtn').addEventListener('click', () => this.selectTransportMode('wifi'));
    $('modeUsbBtn').addEventListener('click', () => this.selectTransportMode('usb'));

    $('copyAdbForwardBtn').addEventListener('click', () => {
      this.copyText('adb forward tcp:19878 localabstract:opaldesk_stream', '已复制 USB 转发命令');
    });
    $('connectCastBtn').addEventListener('click', () => this.startCastConnection());
    $('openCastTrustBtn').addEventListener('click', () => {
      const host = $('castHostInput').value.trim();
      const port = Number($('castPortInput').value) || 19878;
      this.openTrustPopup(host, port);
    });
    $('clearRecentBtn').addEventListener('click', () => {
      try { localStorage.removeItem(STORAGE_DEVICES); } catch {}
      this.renderRecentDevices();
    });

    // Activation events
    $('connectActBtn').addEventListener('click', () => this.startActivationConnection());
    $('disconnectActBtn').addEventListener('click', () => {
      this.activation.disconnect();
      $('actWorkspace').hidden = true;
    });
    $('openActTrustBtn').addEventListener('click', () => {
      const host = $('actHostInput').value.trim();
      const port = Number($('actPortInput').value) || 19879;
      this.openTrustPopup(host, port);
    });
    $('actPairStartBtn').addEventListener('click', () => {
      const pairPort = Number($('adbPairPortInput').value) || 0;
      const pairCode = $('adbPairCodeInput').value.trim();
      const connectPort = Number($('adbConnPortInput').value) || 0;
      if (!pairPort || pairCode.length !== 6) {
        this.setActError('请填写配对端口和 6 位无线调试配对码');
        return;
      }
      this.setActError('');
      this.setActBusy(true);
      this.activation.submitPairAndStart({
        pairPort,
        pairCode,
        connectPort,
        autoEnableCast: $('actAutoCastCheck').checked,
      });
    });
    $('actDirectConnBtn').addEventListener('click', () => {
      const connectPort = Number($('adbConnPortInput').value) || 0;
      if (!connectPort) {
        this.setActError('请先填写无线调试主页显示的连接端口');
        return;
      }
      this.setActError('');
      this.setActBusy(true);
      this.activation.submitDirectConnect({
        connectPort,
        autoEnableCast: $('actAutoCastCheck').checked,
      });
    });
    $('actShizukuBtn').addEventListener('click', () => {
      this.setActError('');
      this.setActBusy(true);
      this.activation.submitShizukuActivate({ autoEnableCast: $('actAutoCastCheck').checked });
    });
    $('actRefreshStateBtn').addEventListener('click', () => this.activation.requestStateRefresh());
    $('copyActFallbackBtn').addEventListener('click', () => {
      this.copyText($('actFallbackCmd').textContent || '', '已复制无线 ADB 兜底命令');
    });

    // Stage toolbar actions
    $('cancelConnectBtn').addEventListener('click', () => this.disconnectCast('已取消连接'));
    $('tbDisconnectBtn').addEventListener('click', () => this.disconnectCast('已断开投屏'));
    $('tbBackBtn').addEventListener('click', () => {
      this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_BACK));
      this.input.focusIme();
    });
    $('tbHomeBtn').addEventListener('click', () => {
      this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_HOME));
      this.input.focusIme();
    });
    $('tbInputModeBtn').addEventListener('click', () => {
      const nextTouch = !this.input.primaryLeftAsTouch;
      this.input.setPrimaryLeftAsTouch(nextTouch);
      const btn = $('tbInputModeBtn');
      btn.textContent = nextTouch ? '触控' : '鼠标';
      btn.classList.toggle('active', nextTouch);
      this.showToast(nextTouch ? '左键拖拽已切换为「触控滑动」模式（支持列表拖拽滚动）' : '左键拖拽已切换为「鼠标指针」模式（支持文本框选）');
      this.input.focusIme();
    });
    $('tbAudioBtn').addEventListener('click', async () => {
      await this.audio.resumeFromGesture();
      const nextMuted = !this.audio.muted;
      this.audio.setMuted(nextMuted);
      this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_SET_AUDIO_ENABLED, nextMuted ? 0 : 1));
      this.input.focusIme();
    });
    $('tbFullscreenBtn').addEventListener('click', () => {
      this.toggleFullscreen();
      this.input.focusIme();
    });

    // Quality popover
    $('tbQualityBtn').addEventListener('click', () => {
      $('appsPopover').hidden = true;
      $('qualityPopover').hidden = !$('qualityPopover').hidden;
      if ($('qualityPopover').hidden) this.input.focusIme();
    });
    $('closeQualityPopBtn').addEventListener('click', () => {
      $('qualityPopover').hidden = true;
      this.input.focusIme();
    });
    $('qualityPopover').querySelectorAll('[data-preset]').forEach((btn) => {
      btn.addEventListener('click', () => {
        this.resizeMode = btn.dataset.preset;
        $('qualityPopover').querySelectorAll('[data-preset]').forEach((b) => b.classList.toggle('active', b === btn));
      });
    });
    $('applyQualityBtn').addEventListener('click', () => {
      this.applyLiveResizeConfig();
      $('qualityPopover').hidden = true;
      this.input.focusIme();
    });

    // App launcher popover
    $('tbAppsBtn').addEventListener('click', () => {
      $('qualityPopover').hidden = true;
      const opening = $('appsPopover').hidden;
      $('appsPopover').hidden = !opening;
      if (opening) {
        this.sendOpalFrame(TYPE_LIST_APPS);
        $('appSearchInput').focus();
      } else {
        this.input.focusIme();
      }
    });
    $('closeAppsPopBtn').addEventListener('click', () => {
      $('appsPopover').hidden = true;
      this.input.focusIme();
    });
    $('appSearchInput').addEventListener('input', () => this.renderAppList());

    // Toast action button
    $('toastActionBtn').addEventListener('click', () => {
      if (this.toastActionFn) this.toastActionFn();
      $('toastIsland').hidden = true;
    });

    // Stage toolbar auto-hide + reveal via top grab handle or top-edge hover
    $('stageRevealHandle').addEventListener('pointerenter', () => this.bumpToolbarVisibility());
    $('stageRevealHandle').addEventListener('click', () => this.bumpToolbarVisibility());
    $('stageToolbar').addEventListener('pointerleave', () => this.bumpToolbarVisibility());
    $('stageView').addEventListener('pointermove', (e) => {
      if (e.clientY <= 18) this.bumpToolbarVisibility();
    });

    // Drag-drop visual cue
    let dragDepth = 0;
    $('canvasWrap').addEventListener('dragenter', (e) => {
      if (!this.authenticated) return;
      e.preventDefault();
      dragDepth++;
      $('dropOverlay').hidden = false;
    });
    $('canvasWrap').addEventListener('dragleave', () => {
      dragDepth = Math.max(0, dragDepth - 1);
      if (dragDepth === 0) $('dropOverlay').hidden = true;
    });
    $('canvasWrap').addEventListener('drop', () => {
      dragDepth = 0;
      $('dropOverlay').hidden = true;
    });
  }

  installTrustPopupListener() {
    window.addEventListener('message', (ev) => {
      const d = ev.data;
      if (!d || d.type !== 'opaldesk-tls-trusted') return;
      if (d.role === 'activate') {
        $('actTrustBox').hidden = true;
        this.showToast('激活服务证书已信任，正在自动连接…');
        this.startActivationConnection();
      } else if (d.role === 'cast') {
        $('castTrustBox').hidden = true;
        this.showToast('设备加密证书已信任，正在自动进入投屏…');
        this.startCastConnection();
      }
    });
  }

  installResizeObserver() {
    const ro = new ResizeObserver(() => {
      if (!this.authenticated || this.resizeMode !== 'auto') return;
      clearTimeout(this.resizeDebounceTimer);
      this.resizeDebounceTimer = setTimeout(() => this.applyLiveResizeConfig(), 380);
    });
    ro.observe($('canvasWrap'));
  }

  openTrustPopup(host, port) {
    if (!host) return;
    const trustUrl = buildTrustPageUrl(host, port, window.location.href);
    const pop = window.open(trustUrl, 'opaldesk_tls_trust', 'width=520,height=640');
    if (!pop) {
      window.location.href = trustUrl;
    }
  }

  selectLobbyTab(tab) {
    const isCast = tab === 'cast';
    $('tabCastBtn').classList.toggle('active', isCast);
    $('tabActivateBtn').classList.toggle('active', !isCast);
    $('tabCastBtn').setAttribute('aria-selected', String(isCast));
    $('tabActivateBtn').setAttribute('aria-selected', String(!isCast));
    $('paneCast').hidden = !isCast;
    $('paneActivate').hidden = isCast;
  }

  selectTransportMode(mode) {
    this.transportMode = mode === 'usb' ? 'usb' : 'wifi';
    const isUsb = this.transportMode === 'usb';
    $('modeWifiBtn').classList.toggle('active', !isUsb);
    $('modeUsbBtn').classList.toggle('active', isUsb);
    $('usbHelperBox').hidden = !isUsb;
    if (isUsb) {
      $('castHostInput').value = '127.0.0.1';
      $('castPortInput').value = '19878';
      $('castTrustBox').hidden = true;
    }
  }

  // ==================== CAST CONNECTION ====================

  async startCastConnection() {
    this.setCastError('');
    $('castTrustBox').hidden = true;

    const isUsb = this.transportMode === 'usb';
    const host = (isUsb ? '127.0.0.1' : $('castHostInput').value.trim());
    const port = Number($('castPortInput').value) || 19878;
    if (!host) {
      this.setCastError('请填写手机局域网 IP 地址');
      return;
    }

    $('connectCastBtn').disabled = true;
    $('connectCastBtn').textContent = '正在探测设备…';

    if (!isUsb) {
      const probe = await probeHttpsHealth(host, port);
      if (!probe.ok) {
        $('connectCastBtn').disabled = false;
        $('connectCastBtn').textContent = '开始 Web 投屏';
        if (probe.needsTrust) {
          $('castTrustBox').hidden = false;
          $('castTrustMsg').textContent = `无法直接验证 https://${host}:${port} 的本地加密证书。请先点击右侧按钮放行证书（或在 Chrome 本地网络弹窗点「允许」）。`;
        } else {
          this.setCastError(`无法连接到手机 ${host}:${port}（${probe.error}）。请确认手机已开启「跨设备互联 → 共享本机」且在同一局域网。`);
        }
        return;
      }
    }

    this.activeHost = host;
    this.activePort = port;
    const wsUrl = isUsb ? `ws://${host}:${port}/ws` : `wss://${host}:${port}/ws`;
    this.openCastWebSocket(wsUrl, host, port, isUsb);
  }

  openCastWebSocket(wsUrl, host, port, isUsb) {
    this.teardownCastSocket();
    const ws = new WebSocket(wsUrl);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;

    const reqSpec = this.computeInitialStreamSpec();
    const savedToken = isUsb ? '' : getSavedToken(host, port);

    this.enterStageView(host, !savedToken && !isUsb);

    ws.onopen = () => {
      this.connected = true;
      const helloPayload = encodeHello({
        clientName: detectBrowserName(),
        clientId: this.clientId,
        preferredCodec: $('castCodecSelect').value || 'h264',
        width: reqSpec.width,
        height: reqSpec.height,
        dpi: reqSpec.dpi,
        fps: reqSpec.fps,
        bitrateKbps: reqSpec.bitrateKbps,
        displayMode: DISPLAY_MODE_DEX,
        audioEnabled: this.sessionRole === SESSION_ROLE_DESKTOP && $('castAudioCheck').checked,
        authToken: savedToken,
        transportHint: isUsb ? 'usb' : 'wifi_tls',
        sessionRole: this.sessionRole,
        appPackage: this.standaloneAppPkg,
        appActivity: this.standaloneAppCls,
      });
      this.sendOpalFrame(TYPE_HELLO, 0, helloPayload);
    };

    ws.onmessage = (ev) => {
      if (!(ev.data instanceof ArrayBuffer)) return;
      let frame;
      try {
        frame = decodeFrame(ev.data);
      } catch {
        return;
      }
      this.handleDownlinkFrame(frame);
    };

    ws.onerror = () => {
      if (!this.authenticated) {
        this.disconnectCast('WebSocket 握手失败：请检查证书是否已放行或手机端无线共享是否开启');
        if (!isUsb) $('castTrustBox').hidden = false;
      }
    };

    ws.onclose = (ev) => {
      const wasAuth = this.authenticated;
      const reason = ev.reason || (wasAuth ? '投屏连接已断开' : '未能建立投屏会话');
      this.disconnectCast(reason);
    };
  }

  handleDownlinkFrame(frame) {
    switch (frame.type) {
      case TYPE_AUTH_RESULT: {
        const auth = decodeAuthResult(frame.payload);
        if (!auth.ok) {
          this.disconnectCast(auth.message || '手机端拒绝了此次投屏请求');
          return;
        }
        this.authenticated = true;
        $('approvalOverlay').hidden = true;
        $('stagePeerName').textContent = auth.deviceName || this.activeHost;
        this.input.setEnabled(true);
        this.startHeartbeat();
        this.bumpToolbarVisibility();

        if (this.transportMode !== 'usb') {
          const prevToken = getSavedToken(this.activeHost, this.activePort);
          saveDeviceRecord({
            host: this.activeHost,
            port: this.activePort,
            deviceName: auth.deviceName || this.activeHost,
            authToken: auth.authToken || prevToken,
            permanent: auth.permanent || Boolean(prevToken),
          });
          this.renderRecentDevices();
        }
        this.showToast(`已连接到 ${auth.deviceName || this.activeHost}`);
        break;
      }

      case TYPE_VIDEO_CONFIG: {
        const cfg = decodeVideoConfig(frame.payload);
        this.video.configure(cfg);
        break;
      }

      case TYPE_VIDEO_FRAME: {
        this.video.decodeFrame(frame.payload, frame.flags, frame.timestampUs);
        break;
      }

      case TYPE_AUDIO_CONFIG: {
        const acfg = decodeAudioConfig(frame.payload);
        this.audio.configure(acfg);
        break;
      }

      case TYPE_AUDIO_FRAME: {
        this.audio.decodeFrame(frame.payload, frame.timestampUs);
        break;
      }

      case TYPE_DEVICE_STATE: {
        try {
          const st = decodeDeviceState(frame.payload);
          if (st.deviceName) $('stagePeerName').textContent = st.deviceName;
        } catch {
          // Ignore malformed telemetry
        }
        break;
      }

      case TYPE_IME_REQUEST: {
        try {
          const req = decodeImeRequest(frame.payload);
          this.input.handleRemoteImeRequest(req.show);
        } catch {
          // Ignore
        }
        break;
      }

      case TYPE_CLIPBOARD_PUSH: {
        try {
          const clip = decodeClipboardPush(frame.payload, frame.flags);
          if (!clip.isImage && clip.text) {
            this.handleRemoteClipboardText(clip.text);
          }
        } catch {
          // Ignore
        }
        break;
      }

      case TYPE_APP_LIST: {
        try {
          this.currentApps = decodeAppList(frame.payload);
          this.renderAppList();
        } catch {
          // Ignore
        }
        break;
      }

      case TYPE_FILE_TRANSFER_ACK: {
        try {
          const ack = decodeFileTransferAck(frame.payload);
          if (ack.status === FILE_ACK_OK) {
            this.showToast(`文件已送达手机: ${ack.pathOrError}`);
          } else {
            this.showToast(`文件传输失败: ${ack.pathOrError}`);
          }
        } catch {
          // Ignore
        }
        break;
      }

      case TYPE_PONG: {
        try {
          const id = decodePingPong(frame.payload);
          const sentAt = this.pingSentMap.get(id);
          if (sentAt !== undefined) {
            this.pingSentMap.delete(id);
            this.lastRttMs = Math.max(1, Math.round(performance.now() - sentAt));
            this.updateTelemetry();
          }
        } catch {
          // Ignore
        }
        break;
      }
      default:
        break;
    }
  }

  sendOpalFrame(type, flags = 0, payload = undefined) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const raw = encodeFrame(type, flags, undefined, payload);
    this.ws.send(raw);
  }

  computeInitialStreamSpec() {
    const isStandalone = this.sessionRole === SESSION_ROLE_STANDALONE_APP;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const vw = Math.max(640, Math.round(window.innerWidth * dpr));
    const vh = Math.max(480, Math.round(window.innerHeight * dpr));
    const width = isStandalone ? 1080 : Math.min(2560, vw & ~7);
    const height = isStandalone ? 2280 : Math.min(1600, vh & ~7);
    const dpi = isStandalone ? 380 : Math.max(160, Math.min(320, Math.round(160 * dpr)));
    const fps = Number($('castFpsSelect').value) || 60;
    const bitrateKbps = Number($('castBitrateSelect').value) || 14000;
    return { width, height, dpi, fps, bitrateKbps };
  }

  applyLiveResizeConfig() {
    if (!this.authenticated) return;
    let w;
    let h;
    if (this.resizeMode === 'auto') {
      const rect = $('canvasWrap').getBoundingClientRect();
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const isStandalone = this.sessionRole === SESSION_ROLE_STANDALONE_APP;
      const minW = isStandalone ? 480 : 640;
      const maxH = isStandalone ? 2560 : 1600;
      w = Math.min(2560, Math.max(minW, Math.round(rect.width * dpr)));
      h = Math.min(maxH, Math.max(480, Math.round(rect.height * dpr)));
    } else {
      const [pw, ph] = this.resizeMode.split('x').map(Number);
      w = pw || 1920;
      h = ph || 1080;
    }
    const fps = Number($('liveFpsSelect').value) || Number($('castFpsSelect').value) || 60;
    const bitrateKbps = Number($('liveBitrateSelect').value) || Number($('castBitrateSelect').value) || 14000;
    this.sendOpalFrame(TYPE_RESIZE_CONFIG, 0, encodeResizeConfig({ width: w, height: h, dpi: 0, fps, bitrateKbps }));
  }

  async sendDroppedFiles(files) {
    for (const file of files) {
      const transferId = this.transferSeq++;
      this.showToast(`正在发送 ${file.name} (${(file.size / 1024 / 1024).toFixed(2)} MB)…`);
      const buf = new Uint8Array(await file.arrayBuffer());
      let offset = 0;
      let isFirst = true;
      while (offset < buf.byteLength || isFirst) {
        const end = Math.min(buf.byteLength, offset + FILE_CHUNK_SIZE);
        const slice = buf.subarray(offset, end);
        let flags = FILE_FLAG_DATA;
        if (isFirst) flags |= FILE_FLAG_START;
        if (end >= buf.byteLength) flags |= FILE_FLAG_END;
        this.sendOpalFrame(
          TYPE_FILE_TRANSFER_CHUNK,
          0,
          encodeFileTransferChunk(transferId, flags, {
            fileName: file.name,
            totalSize: buf.byteLength,
            data: slice,
          })
        );
        isFirst = false;
        offset = end;
        if (this.ws && this.ws.bufferedAmount > 2 * 1024 * 1024) {
          await new Promise((r) => setTimeout(r, 30));
        }
      }
    }
  }

  handleRemoteClipboardText(text) {
    if (document.hasFocus() && navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        () => this.showToast('已同步手机剪贴板内容'),
        () => this.showToast('收到手机剪贴板文本', '点此复制', () => this.copyText(text, '已复制手机剪贴板'))
      );
    } else {
      this.showToast('收到手机剪贴板文本', '点此复制', () => this.copyText(text, '已复制手机剪贴板'));
    }
  }

  renderAppList() {
    const q = ($('appSearchInput').value || '').trim().toLowerCase();
    const listEl = $('appsList');
    listEl.innerHTML = '';
    const filtered = this.currentApps.filter(
      (a) => !q || (a.label || '').toLowerCase().includes(q) || (a.pkg || '').toLowerCase().includes(q)
    );
    if (!filtered.length) {
      listEl.innerHTML = '<div class="hint-text" style="padding:10px;text-align:center">未找到匹配应用</div>';
      return;
    }
    for (const app of filtered.slice(0, 80)) {
      const row = document.createElement('div');
      row.className = 'app-row';

      const info = document.createElement('div');
      info.className = 'app-info';
      if (app.icon) {
        const img = document.createElement('img');
        img.src = `data:image/png;base64,${app.icon}`;
        img.alt = '';
        info.appendChild(img);
      }
      const lbl = document.createElement('span');
      lbl.className = 'app-label';
      lbl.textContent = app.label || app.pkg;
      info.appendChild(lbl);

      const btns = document.createElement('div');
      btns.className = 'app-btns';

      const openBtn = document.createElement('button');
      openBtn.type = 'button';
      openBtn.className = 'mini-btn';
      openBtn.textContent = '打开';
      openBtn.addEventListener('click', () => {
        this.sendOpalFrame(TYPE_WINDOW_ACTION, 0, encodeWindowAction({ action: 'launch', pkg: app.pkg, cls: app.cls || '' }));
        $('appsPopover').hidden = true;
      });
      btns.appendChild(openBtn);

      const popBtn = document.createElement('button');
      popBtn.type = 'button';
      popBtn.className = 'mini-btn';
      popBtn.textContent = '独立小窗';
      popBtn.addEventListener('click', () => this.openStandaloneAppWindow(app));
      btns.appendChild(popBtn);

      row.append(info, btns);
      listEl.appendChild(row);
    }
  }

  openStandaloneAppWindow(app) {
    const u = new URL(window.location.href);
    u.search = '';
    u.hash = '';
    u.searchParams.set('host', this.activeHost);
    u.searchParams.set('port', String(this.activePort));
    if (this.transportMode === 'usb') u.searchParams.set('transport', 'usb');
    u.searchParams.set('role', SESSION_ROLE_STANDALONE_APP);
    u.searchParams.set('pkg', app.pkg);
    if (app.cls) u.searchParams.set('cls', app.cls);
    window.open(u.toString(), `opal_app_${app.pkg}`, 'width=440,height=880');
    $('appsPopover').hidden = true;
  }

  enterStageView(peerLabel, showApprovalWait) {
    const isStandalone = this.sessionRole === SESSION_ROLE_STANDALONE_APP;
    $('lobbyView').hidden = true;
    $('stageView').hidden = false;
    $('stagePeerName').textContent = peerLabel;
    $('approvalOverlay').hidden = !showApprovalWait;
    $('tbHomeBtn').hidden = isStandalone;
    $('tbAppsBtn').hidden = isStandalone;
    $('tbAudioBtn').hidden = isStandalone;
    $('liveFpsSelect').value = $('castFpsSelect').value;
    $('liveBitrateSelect').value = $('castBitrateSelect').value;
    $('connectCastBtn').disabled = false;
    $('connectCastBtn').textContent = '开始 Web 投屏';
  }

  disconnectCast(reasonMessage = '') {
    if (this.ws && this.ws.readyState === WebSocket.OPEN && this.authenticated) {
      try {
        this.sendOpalFrame(TYPE_SYSTEM_ACTION, 0, encodeSystemAction(SYS_DISCONNECT));
      } catch {
        // Ignore send race
      }
    }
    this.teardownCastSocket();
    this.input.setEnabled(false);
    this.video.reset();
    this.audio.reset();
    $('stageView').hidden = true;
    $('lobbyView').hidden = false;
    $('approvalOverlay').hidden = true;
    $('qualityPopover').hidden = true;
    $('appsPopover').hidden = true;
    $('connectCastBtn').disabled = false;
    $('connectCastBtn').textContent = '开始 Web 投屏';
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
    }
    if (reasonMessage) {
      this.setCastError(reasonMessage);
    }
  }

  teardownCastSocket() {
    clearInterval(this.pingTimer);
    this.pingTimer = null;
    clearTimeout(this.toolbarFadeTimer);
    clearTimeout(this.resizeDebounceTimer);
    this.pingSentMap.clear();
    this.connected = false;
    this.authenticated = false;
    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onerror = null;
      this.ws.onclose = null;
      try { this.ws.close(1000, 'bye'); } catch {}
      this.ws = null;
    }
  }

  startHeartbeat() {
    clearInterval(this.pingTimer);
    this.pingTimer = setInterval(() => {
      if (!this.authenticated) return;
      const id = this.pingIdSeq++;
      this.pingSentMap.set(id, performance.now());
      if (this.pingSentMap.size > 16) {
        const firstKey = this.pingSentMap.keys().next().value;
        this.pingSentMap.delete(firstKey);
      }
      this.sendOpalFrame(TYPE_PING, 0, encodePingPong(id));
    }, 3000);
  }

  updateTelemetry() {
    const parts = [`${this.remoteWidth}×${this.remoteHeight}`];
    if (this.remoteFps > 0) parts.push(`${this.remoteFps}fps`);
    if (this.lastRttMs > 0) parts.push(`${this.lastRttMs}ms`);
    $('stageTelemetry').textContent = parts.join(' · ');
  }

  syncAudioUi(st) {
    const btn = $('tbAudioBtn');
    if (st.needsGesture) {
      btn.textContent = '点此出声';
      btn.classList.add('active');
      this.showToast('浏览器需要手势以解锁音频', '开启声音', () => this.audio.resumeFromGesture());
    } else if (st.muted) {
      btn.textContent = '已静音';
      btn.classList.remove('active');
    } else {
      btn.textContent = '声音';
      btn.classList.remove('active');
    }
  }

  bumpToolbarVisibility() {
    const tb = $('stageToolbar');
    tb.classList.remove('dimmed');
    clearTimeout(this.toolbarFadeTimer);
    this.toolbarFadeTimer = setTimeout(() => {
      if ($('qualityPopover').hidden && $('appsPopover').hidden && !tb.matches(':hover')) {
        tb.classList.add('dimmed');
      }
    }, 2600);
  }

  toggleFullscreen() {
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
    } else {
      $('stageView').requestFullscreen().catch(() => {});
    }
  }

  // ==================== LAN ACTIVATION ====================

  async startActivationConnection() {
    this.setActError('');
    $('actTrustBox').hidden = true;

    const host = $('actHostInput').value.trim();
    const port = Number($('actPortInput').value) || 19879;
    const code = $('actCodeInput').value.trim();

    if (!host) {
      this.setActError('请填写手机局域网 IP');
      return;
    }
    if (!/^\d{6}$/.test(code)) {
      this.setActError('请输入手机屏幕上显示的 6 位会话访问码');
      return;
    }

    $('connectActBtn').disabled = true;
    $('connectActBtn').textContent = '正在探测激活服务…';
    const probe = await probeHttpsHealth(host, port);
    $('connectActBtn').disabled = false;
    $('connectActBtn').textContent = '连接手机激活服务';

    if (!probe.ok) {
      if (probe.needsTrust) {
        $('actTrustBox').hidden = false;
      } else {
        this.setActError(`无法连接手机激活服务 ${host}:${port}（${probe.error}）。请确认手机端已开启「Web激活」服务且在同一 Wi-Fi。`);
      }
      return;
    }

    $('actLogOutput').textContent = '';
    this.activation.connect(host, port, code);
  }

  renderActivationState(st) {
    $('actDeviceTitle').textContent = `${st.deviceName || 'Android'} (${st.lanIp || ''})`;
    const pPort = st.discovery?.pairingPort || 0;
    const cPort = st.discovery?.connectPort || 0;
    $('actDiscoverySummary').textContent = `mDNS 配对端口: ${pPort || '等待手机打开配对弹窗'} · 连接端口: ${cPort || '未发现'}`;

    if (pPort > 0 && !$('adbPairPortInput').matches(':focus')) {
      $('adbPairPortInput').value = String(pPort);
    }
    if (cPort > 0 && !$('adbConnPortInput').matches(':focus')) {
      $('adbConnPortInput').value = String(cPort);
    }

    const badge = $('actDaemonBadge');
    badge.textContent = st.daemonConnected ? '引擎已激活' : '未激活';
    badge.className = `status-pill ${st.daemonConnected ? 'ok' : 'warn'}`;
    $('actShizukuBtn').hidden = !st.shizukuGranted;
    this.setActBusy(Boolean(st.busy));

    const ip = st.lanIp || $('actHostInput').value.trim();
    const fallback = cPort > 0
      ? `adb connect ${ip}:${cPort} && ${st.pcAdbCmd || ''}`
      : (st.pcAdbCmd || '');
    $('actFallbackCmd').textContent = fallback;
  }

  setActBusy(busy) {
    $('actPairStartBtn').disabled = busy;
    $('actDirectConnBtn').disabled = busy;
    $('actShizukuBtn').disabled = busy;
  }

  appendActLog(line) {
    if (!line) return;
    const el = $('actLogOutput');
    const ts = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    el.textContent += `[${ts}] ${line}\n`;
    el.scrollTop = el.scrollHeight;
  }

  // ==================== HELPERS ====================

  renderRecentDevices() {
    const list = loadSavedDevices();
    const wrap = $('recentDevicesWrap');
    const container = $('recentDevicesList');
    if (!list.length) {
      wrap.hidden = true;
      return;
    }
    wrap.hidden = false;
    container.innerHTML = '';
    for (const dev of list) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'recent-chip';
      btn.textContent = `${dev.deviceName || dev.host} (${dev.host}:${dev.port})`;
      btn.addEventListener('click', () => {
        this.selectTransportMode('wifi');
        $('castHostInput').value = dev.host;
        $('castPortInput').value = String(dev.port || 19878);
        this.startCastConnection();
      });
      container.appendChild(btn);
    }
  }

  setCastError(msg) {
    const box = $('castErrorBox');
    box.hidden = !msg;
    box.textContent = msg || '';
  }

  setActError(msg) {
    const box = $('actErrorBox');
    box.hidden = !msg;
    box.textContent = msg || '';
  }

  showBrowserWarn(msg) {
    const el = $('browserWarnBanner');
    el.hidden = false;
    el.textContent = msg;
  }

  showToast(text, actionLabel = '', actionFn = null) {
    $('toastText').textContent = text;
    const btn = $('toastActionBtn');
    this.toastActionFn = actionFn;
    if (actionLabel && actionFn) {
      btn.textContent = actionLabel;
      btn.hidden = false;
    } else {
      btn.hidden = true;
    }
    $('toastIsland').hidden = false;
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => {
      $('toastIsland').hidden = true;
    }, actionLabel ? 6500 : 3200);
  }

  copyText(text, successMsg) {
    if (!text) return;
    navigator.clipboard?.writeText(text).then(
      () => this.showToast(successMsg),
      () => this.showToast('剪贴板写入受限，请手动选择复制')
    );
  }
}

window.addEventListener('DOMContentLoaded', () => {
  new OpalCastWebApp();
});

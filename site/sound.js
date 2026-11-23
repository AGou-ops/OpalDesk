/* ==========================================================================
   OpalDesk — Procedural Web Audio Engine (Zero External Assets)
   Glassy Compositor Chimes · VirtualDisplay Wake Pulses · Tactile UI Clicks
   ========================================================================== */
(() => {
  'use strict';

  const STORAGE_KEY = 'opaldesk-showcase-sound-v1';

  function readPref() {
    try {
      return window.localStorage.getItem(STORAGE_KEY) !== 'off';
    } catch (_) {
      return true;
    }
  }

  function writePref(enabled) {
    try {
      window.localStorage.setItem(STORAGE_KEY, enabled ? 'on' : 'off');
    } catch (_) {}
  }

  const STAGE_SCALES = [
    [392.0, 440.0, 523.25, 587.33, 659.25, 783.99, 880.0],
    [523.25, 587.33, 659.25, 783.99, 987.77, 1046.5, 1174.66],
    [440.0, 523.25, 659.25, 783.99, 880.0, 1046.5, 1318.51],
    [329.63, 392.0, 493.88, 587.33, 659.25, 783.99, 987.77],
    [587.33, 659.25, 783.99, 880.0, 1046.5, 1174.66, 1318.51],
    [261.63, 329.63, 392.0, 493.88, 523.25, 659.25, 783.99]
  ];

  const state = {
    enabled: readPref(),
    unlocked: false,
    scene: 0,
    listeners: new Set()
  };

  let ctx = null;
  let master = null;
  let dry = null;
  let wet = null;
  let lastHoverAt = 0;

  function status() {
    return { enabled: state.enabled, unlocked: state.unlocked, active: state.enabled && state.unlocked };
  }

  function notify() {
    const snap = status();
    state.listeners.forEach((fn) => {
      try { fn(snap); } catch (_) {}
    });
  }

  function ensureGraph() {
    if (ctx) return true;
    const AudioCtor = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtor) return false;
    ctx = new AudioCtor();

    master = ctx.createGain();
    master.gain.value = 0.0001;

    dry = ctx.createGain();
    dry.gain.value = 0.82;

    wet = ctx.createGain();
    wet.gain.value = 0.36;

    const delayL = ctx.createDelay(1.0);
    const delayR = ctx.createDelay(1.0);
    delayL.delayTime.value = 0.17;
    delayR.delayTime.value = 0.25;

    const fb = ctx.createGain();
    fb.gain.value = 0.34;

    const damp = ctx.createBiquadFilter();
    damp.type = 'lowpass';
    damp.frequency.value = 4200;

    const merger = ctx.createChannelMerger(2);

    dry.connect(master);
    wet.connect(delayL);
    wet.connect(delayR);
    delayL.connect(fb);
    fb.connect(damp);
    damp.connect(delayR);
    delayL.connect(merger, 0, 0);
    delayR.connect(merger, 0, 1);
    merger.connect(master);
    master.connect(ctx.destination);
    return true;
  }

  function syncGain() {
    if (!ctx || !master) return;
    const now = ctx.currentTime;
    const active = state.enabled && state.unlocked && !document.hidden;
    master.gain.cancelScheduledValues(now);
    master.gain.setTargetAtTime(active ? 0.65 : 0.0001, now, 0.04);
  }

  async function unlock() {
    if (!state.enabled) return false;
    if (!ensureGraph()) return false;
    if (ctx.state === 'suspended') {
      try { await ctx.resume(); } catch (_) { return false; }
    }
    if (ctx.state === 'running') {
      state.unlocked = true;
      syncGain();
      notify();
      return true;
    }
    return false;
  }

  function ready() {
    return Boolean(ctx && state.enabled && state.unlocked && ctx.state === 'running' && !document.hidden);
  }

  function withAudio(fn) {
    if (!state.enabled || document.hidden) return;
    if (ready()) fn();
    else unlock().then((ok) => { if (ok && ready()) fn(); });
  }

  function tone({ freq, freqEnd = null, type = 'sine', gain = 0.09, attack = 0.004, decay = 0.36, delay = 0, reverb = 0.45, pan = 0 }) {
    if (!ready()) return;
    const t0 = ctx.currentTime + delay;
    const osc = ctx.createOscillator();
    osc.type = type;
    osc.frequency.setValueAtTime(Math.max(40, freq), t0);
    if (freqEnd !== null) {
      osc.frequency.exponentialRampToValueAtTime(Math.max(40, freqEnd), t0 + attack + decay * 0.85);
    }

    const env = ctx.createGain();
    env.gain.setValueAtTime(0.0001, t0);
    env.gain.linearRampToValueAtTime(gain, t0 + attack);
    env.gain.exponentialRampToValueAtTime(0.0001, t0 + attack + decay);

    let out = env;
    if (Math.abs(pan) > 0.02 && typeof ctx.createStereoPanner === 'function') {
      const p = ctx.createStereoPanner();
      p.pan.value = Math.max(-0.8, Math.min(0.8, pan));
      env.connect(p);
      out = p;
    }

    osc.connect(env);
    out.connect(dry);
    if (reverb > 0) {
      const send = ctx.createGain();
      send.gain.value = reverb;
      out.connect(send);
      send.connect(wet);
    }

    osc.start(t0);
    osc.stop(t0 + attack + decay + 0.06);
  }

  function playClick(kind = 'pill') {
    withAudio(() => {
      if (kind === 'primary') {
        tone({ freq: 587.33, freqEnd: 880, type: 'sine', gain: 0.085, attack: 0.003, decay: 0.19, reverb: 0.5 });
        tone({ freq: 1174.66, type: 'sine', gain: 0.032, attack: 0.003, decay: 0.14, delay: 0.018, reverb: 0.4 });
      } else {
        tone({ freq: 720, freqEnd: 620, type: 'sine', gain: 0.052, attack: 0.002, decay: 0.085, reverb: 0.25 });
      }
    });
  }

  function playPulse(sceneIdx = state.scene, nx = 0) {
    withAudio(() => {
      const scale = STAGE_SCALES[Math.max(0, Math.min(5, sceneIdx))] || STAGE_SCALES[0];
      const base = scale[2];
      const fifth = scale[4];
      const top = scale[6];
      const pan = Math.max(-0.65, Math.min(0.65, nx * 0.65));
      tone({ freq: base, type: 'sine', gain: 0.09, attack: 0.005, decay: 0.42, reverb: 0.6, pan });
      tone({ freq: fifth, type: 'sine', gain: 0.062, attack: 0.006, decay: 0.52, delay: 0.04, reverb: 0.68, pan: -pan * 0.4 });
      tone({ freq: top, type: 'triangle', gain: 0.025, attack: 0.008, decay: 0.36, delay: 0.08, reverb: 0.72, pan });
    });
  }

  function playHover(idx = 0) {
    const now = performance.now();
    if (now - lastHoverAt < 90 || !ready()) return;
    lastHoverAt = now;
    const scale = STAGE_SCALES[state.scene] || STAGE_SCALES[0];
    const freq = scale[Math.abs(idx) % scale.length];
    tone({ freq: freq * 1.5, type: 'sine', gain: 0.024, attack: 0.002, decay: 0.11, reverb: 0.35 });
  }

  function playSceneShift(nextScene) {
    state.scene = nextScene;
    if (!ready()) return;
    const scale = STAGE_SCALES[nextScene] || STAGE_SCALES[0];
    tone({ freq: scale[0], freqEnd: scale[2], type: 'sine', gain: 0.055, attack: 0.015, decay: 0.38, reverb: 0.65 });
  }

  function setEnabled(next) {
    state.enabled = Boolean(next);
    writePref(state.enabled);
    if (state.enabled) unlock().then(() => playClick('pill'));
    else syncGain();
    notify();
  }

  document.addEventListener('visibilitychange', syncGain);

  window.OpalSound = {
    unlock,
    setEnabled,
    getStatus: status,
    subscribe(fn) { state.listeners.add(fn); fn(status()); },
    playClick,
    playPulse,
    playHover,
    playSceneShift
  };
})();

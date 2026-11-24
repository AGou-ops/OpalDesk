import test from 'node:test';
import assert from 'node:assert/strict';

import {
  clampImeBridgeCoords,
  isImeCompositionOrNonAsciiKey,
  isImeToggleShortcut,
  isModifierDomCode,
  isVirtualKeyboardDevice,
  OpalInputController,
  resolveWebInputMode,
  shouldPreventDefaultForKeyEvent,
} from '../js/input_mapper.js';

test('Modifier codes are recognized so Shift/CapsLock/Ctrl/Alt are never blocked by preventDefault()', () => {
  for (const code of [
    'ShiftLeft', 'ShiftRight', 'ControlLeft', 'ControlRight',
    'AltLeft', 'AltRight', 'MetaLeft', 'MetaRight', 'CapsLock',
  ]) {
    assert.equal(isModifierDomCode(code), true, `expected ${code} to be a modifier`);
    assert.equal(
      shouldPreventDefaultForKeyEvent({ code, key: 'Shift' }, { imeFocused: true, isModifier: true }),
      false
    );
  }
  assert.equal(isModifierDomCode('KeyA'), false);
  assert.equal(isModifierDomCode('Space'), false);
});

test('IME mode-toggle shortcuts are recognized and never swallowed or prevented', () => {
  // Ctrl+Space (macOS/Linux/Windows input source switch)
  const ctrlSpace = { code: 'Space', key: ' ', ctrlKey: true, metaKey: false, altKey: false, shiftKey: false };
  assert.equal(isImeToggleShortcut(ctrlSpace), true);
  assert.equal(shouldPreventDefaultForKeyEvent(ctrlSpace, { imeFocused: true, isModifier: false }), false);

  // Ctrl+Option+Space (macOS next input source)
  const ctrlAltSpace = { code: 'Space', key: ' ', ctrlKey: true, metaKey: false, altKey: true, shiftKey: false };
  assert.equal(isImeToggleShortcut(ctrlAltSpace), true);

  // Win+Space / Cmd+Space
  const metaSpace = { code: 'Space', key: ' ', ctrlKey: false, metaKey: true, altKey: false, shiftKey: false };
  assert.equal(isImeToggleShortcut(metaSpace), true);

  // Ctrl+. (Chinese / English punctuation toggle)
  const ctrlDot = { code: 'Period', key: '.', ctrlKey: true, metaKey: false, altKey: false, shiftKey: false };
  assert.equal(isImeToggleShortcut(ctrlDot), true);
  assert.equal(shouldPreventDefaultForKeyEvent(ctrlDot, { imeFocused: true, isModifier: false }), false);

  // Plain Space and Ctrl+C are NOT IME toggles
  assert.equal(isImeToggleShortcut({ code: 'Space', key: ' ', ctrlKey: false, metaKey: false, altKey: false }), false);
  assert.equal(isImeToggleShortcut({ code: 'KeyC', key: 'c', ctrlKey: true, metaKey: false, altKey: false }), false);
});

test('Composition, Process/229, dead keys, and full-width Chinese punctuation route through IME bridge', () => {
  assert.equal(isImeCompositionOrNonAsciiKey({ isComposing: true, key: 'n', keyCode: 78 }), true);
  assert.equal(isImeCompositionOrNonAsciiKey({ isComposing: false, key: 'Process', keyCode: 229 }), true);
  assert.equal(isImeCompositionOrNonAsciiKey({ isComposing: false, key: 'Dead', keyCode: 229 }), true);
  assert.equal(isImeCompositionOrNonAsciiKey({ isComposing: false, key: '，', code: 'Comma', keyCode: 188 }), true);
  assert.equal(isImeCompositionOrNonAsciiKey({ isComposing: false, key: '。', code: 'Period', keyCode: 190 }), true);
  assert.equal(isImeCompositionOrNonAsciiKey({ isComposing: false, key: '？', code: 'Slash', keyCode: 191 }), true);

  // Plain ASCII is not treated as CJK composition
  assert.equal(isImeCompositionOrNonAsciiKey({ isComposing: false, key: 'a', code: 'KeyA', keyCode: 65 }), false);
  assert.equal(isImeCompositionOrNonAsciiKey({ isComposing: false, key: ',', code: 'Comma', keyCode: 188 }), false);
  assert.equal(isImeCompositionOrNonAsciiKey({ isComposing: false, key: 'Enter', code: 'Enter', keyCode: 13 }), false);
});

test('shouldPreventDefaultForKeyEvent preserves browser IME on printable chars while preventing navigation keys', () => {
  // Single printable char inside focused imeInput -> do NOT preventDefault
  assert.equal(
    shouldPreventDefaultForKeyEvent(
      { code: 'KeyN', key: 'n', ctrlKey: false, metaKey: false, altKey: false },
      { imeFocused: true, isModifier: false }
    ),
    false
  );

  // Navigation / editing / shortcut keys -> DO preventDefault so Tab/Arrows/Backspace/Ctrl+A stay in canvas
  for (const ev of [
    { code: 'Tab', key: 'Tab', ctrlKey: false, metaKey: false, altKey: false },
    { code: 'Backspace', key: 'Backspace', ctrlKey: false, metaKey: false, altKey: false },
    { code: 'ArrowDown', key: 'ArrowDown', ctrlKey: false, metaKey: false, altKey: false },
    { code: 'KeyA', key: 'a', ctrlKey: true, metaKey: false, altKey: false },
  ]) {
    assert.equal(shouldPreventDefaultForKeyEvent(ev, { imeFocused: true, isModifier: false }), true);
  }
});

test('clampImeBridgeCoords keeps IME candidate anchor inside visible viewport bounds', () => {
  assert.deepEqual(clampImeBridgeCoords(450, 320, 1280, 720), { left: 450, top: 320 });
  assert.deepEqual(clampImeBridgeCoords(-20, 5, 1280, 720), { left: 16, top: 16 });
  assert.deepEqual(clampImeBridgeCoords(1400, 900, 1280, 720), { left: 1264, top: 704 });
});

test('isVirtualKeyboardDevice distinguishes Android/iPadOS tablets from desktop PC browsers', () => {
  // Android tablet Chrome
  assert.equal(
    isVirtualKeyboardDevice({
      userAgent: 'Mozilla/5.0 (Linux; Android 15; Xiaomi Pad 6S Pro) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36',
      platform: 'Linux armv81',
      maxTouchPoints: 10,
      coarsePointer: true,
    }),
    true
  );

  // iPadOS 17+ desktop-class Safari (reports MacIntel + maxTouchPoints=5)
  assert.equal(
    isVirtualKeyboardDevice({
      userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.4 Safari/605.1.15',
      platform: 'MacIntel',
      maxTouchPoints: 5,
      coarsePointer: true,
    }),
    true
  );

  // Real macOS desktop browser
  assert.equal(
    isVirtualKeyboardDevice({
      userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36',
      platform: 'MacIntel',
      maxTouchPoints: 0,
      coarsePointer: false,
    }),
    false
  );

  // Windows touchscreen laptop using fine trackpad/mouse (coarsePointer=false)
  assert.equal(
    isVirtualKeyboardDevice({
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36',
      platform: 'Win32',
      maxTouchPoints: 10,
      coarsePointer: false,
    }),
    false
  );

  // Android / HarmonyOS 10"+ tablet in Chrome "Request Desktop Site" mode (spoofs X11 Linux x86_64
  // even with external mouse/trackpad attached where coarsePointer=false, while maxTouchPoints=5..10)
  assert.equal(
    isVirtualKeyboardDevice({
      userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36',
      platform: 'Linux x86_64',
      maxTouchPoints: 10,
      coarsePointer: false,
      anyCoarsePointer: true,
    }),
    true
  );
  assert.equal(
    isVirtualKeyboardDevice({
      userAgent: 'Mozilla/5.0 (Phone; OpenHarmony 5.0) AppleWebKit/537.36 ArkWeb/4.1.6.1',
      platform: 'Linux aarch64',
      maxTouchPoints: 5,
      coarsePointer: true,
    }),
    true
  );
});

test('OpalInputController suppresses passive soft-keyboard popups on tablets until IME_REQUEST or explicit toggle', () => {
  let focusCalls = 0;
  let blurCalls = 0;
  let currentAttrMode = '';
  const kbStates = [];
  const imeStub = {
    value: '',
    inputMode: '',
    style: {},
    setAttribute(k, v) {
      if (k === 'inputmode') currentAttrMode = v;
    },
    focus() {
      focusCalls++;
      globalThis.document = { activeElement: imeStub };
    },
    blur() {
      blurCalls++;
      globalThis.document = { activeElement: null };
    },
    addEventListener() {},
    removeEventListener() {},
  };
  const canvasStub = {
    addEventListener() {},
    removeEventListener() {},
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 1280, height: 800 }),
  };

  globalThis.document = { activeElement: null };
  try {
    const ctrl = new OpalInputController(canvasStub, imeStub, {
      getRemoteSize: () => ({ width: 1920, height: 1080 }),
      onMouse: () => {},
      onTouch: () => {},
      onKey: () => {},
      onText: () => {},
      onSoftKeyboardStateChange: (open) => kbStates.push(open),
      virtualKeyboardDevice: true,
    });

    assert.equal(currentAttrMode, 'none');

    // Enabling session and passive focusIme() calls (canvas taps / toolbar clicks) MUST NOT focus or pop keyboard!
    ctrl.setEnabled(true);
    ctrl.focusIme();
    assert.equal(focusCalls, 0);
    assert.equal(currentAttrMode, 'none');

    // Remote Android EditText sends TYPE_IME_REQUEST(show=true, TYPE_CLASS_NUMBER=2) -> opens numeric keyboard!
    ctrl.handleRemoteImeRequest(true, 0x02);
    assert.equal(focusCalls, 1);
    assert.equal(currentAttrMode, 'numeric');
    assert.equal(ctrl.softKeyboardOpen, true);

    // Remote Android hides IME -> resets inputmode='none' and blurs
    ctrl.handleRemoteImeRequest(false, 0);
    assert.equal(blurCalls, 1);
    assert.equal(currentAttrMode, 'none');
    assert.equal(ctrl.softKeyboardOpen, false);

    // Manual toolbar "键盘" button toggles open and closed cleanly
    assert.equal(ctrl.toggleSoftKeyboard(), true);
    assert.equal(currentAttrMode, 'text');
    assert.equal(focusCalls, 2);
    assert.equal(ctrl.toggleSoftKeyboard(), false);
    assert.equal(currentAttrMode, 'none');
    assert.equal(blurCalls, 2);
    assert.deepEqual(kbStates, [true, false, true, false]);

    // Input type mapping sanity checks
    assert.equal(resolveWebInputMode(0x2002), 'decimal');
    assert.equal(resolveWebInputMode(0x03), 'tel');
    assert.equal(resolveWebInputMode(0x21), 'email');
    assert.equal(resolveWebInputMode(0x11), 'url');
  } finally {
    delete globalThis.document;
  }
});

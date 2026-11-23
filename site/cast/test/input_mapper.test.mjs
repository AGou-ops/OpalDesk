import test from 'node:test';
import assert from 'node:assert/strict';

import {
  clampImeBridgeCoords,
  isImeCompositionOrNonAsciiKey,
  isImeToggleShortcut,
  isModifierDomCode,
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

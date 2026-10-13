"use strict";
// Browser input → the SDL3 emulation in the game image (platform/web/.../sdl/WebSdl.java).
// The image allocates a block in its linear memory ("nmem") and calls attachInput();
// we keep the canvas size there and append events to its ring buffer. Layout:
//   +4 write index (ours)  +8 read index (game)  +12 slot count
//   +16 css w  +20 css h  +24 px w  +28 px h  +32 dpr*1000  +36 focused
//   +40 mouse x (f32)  +44 mouse y (f32)  +48 buttons  +52 pointer locked  +56 caps lock
//   +64 slots of 32 bytes: i32 type, a, b, c; f32 x, y, dx, dy
(() => {
  const SLOT = 32;
  const EV = {
    KEY_DOWN: 768, KEY_UP: 769, TEXT_INPUT: 771,
    MOUSE_MOTION: 1024, MOUSE_DOWN: 1025, MOUSE_UP: 1026, MOUSE_WHEEL: 1027,
    WIN_RESIZED: 518, WIN_PIXEL_SIZE: 519, WIN_ENTER: 524, WIN_LEAVE: 525,
    WIN_FOCUS_GAINED: 526, WIN_FOCUS_LOST: 527, WIN_CLOSE: 528,
  };

  // KeyboardEvent.code → SDL scancode (USB HID usage ids).
  const SCANCODES = {};
  for (let i = 0; i < 26; i++) SCANCODES["Key" + String.fromCharCode(65 + i)] = 4 + i;
  for (let i = 1; i <= 9; i++) SCANCODES["Digit" + i] = 29 + i;
  SCANCODES.Digit0 = 39;
  Object.assign(SCANCODES, {
    Enter: 40, Escape: 41, Backspace: 42, Tab: 43, Space: 44, Minus: 45, Equal: 46,
    BracketLeft: 47, BracketRight: 48, Backslash: 49, Semicolon: 51, Quote: 52, Backquote: 53,
    Comma: 54, Period: 55, Slash: 56, CapsLock: 57,
    PrintScreen: 70, ScrollLock: 71, Pause: 72, Insert: 73, Home: 74, PageUp: 75, Delete: 76,
    End: 77, PageDown: 78, ArrowRight: 79, ArrowLeft: 80, ArrowDown: 81, ArrowUp: 82,
    NumLock: 83, NumpadDivide: 84, NumpadMultiply: 85, NumpadSubtract: 86, NumpadAdd: 87,
    NumpadEnter: 88, Numpad0: 98, NumpadDecimal: 99, IntlBackslash: 100, ContextMenu: 101,
    ControlLeft: 224, ShiftLeft: 225, AltLeft: 226, MetaLeft: 227,
    ControlRight: 228, ShiftRight: 229, AltRight: 230, MetaRight: 231,
  });
  for (let i = 1; i <= 12; i++) SCANCODES["F" + i] = 57 + i;
  for (let i = 13; i <= 24; i++) SCANCODES["F" + i] = 91 + i;
  for (let i = 1; i <= 9; i++) SCANCODES["Numpad" + i] = 88 + i;

  const SCANCODE_MASK = 1 << 30;
  function keycode(e, scancode) {
    // Layout-aware like SDL: printable keys use their (unshifted) character.
    if (e.key && e.key.length === 1 && !(scancode >= 89 && scancode <= 99)) {
      const ch = e.key.toLowerCase();
      if (ch.codePointAt(0) >= 32) return ch.codePointAt(0);
    }
    switch (scancode) {
      case 40: return 13;
      case 41: return 27;
      case 42: return 8;
      case 43: return 9;
      case 44: return 32;
      case 76: return 127;
      default: return scancode | SCANCODE_MASK;
    }
  }

  let mem = null; // WebAssembly.Memory
  let block = 0;
  let slots = 0;
  let keyState = 0;
  const canvas = () => globalThis.mcweb.canvas;

  function view() {
    return new DataView(mem.buffer); // re-create: memory.grow detaches old buffers
  }

  function push(type, a = 0, b = 0, c = 0, x = 0, y = 0, dx = 0, dy = 0) {
    if (!mem) return false;
    const dv = view();
    const write = dv.getInt32(block + 4, true);
    const read = dv.getInt32(block + 8, true);
    if (write - read >= slots - 4) return false; // full: drop
    const at = block + 64 + (write % slots) * SLOT;
    dv.setInt32(at, type, true);
    dv.setInt32(at + 4, a, true);
    dv.setInt32(at + 8, b, true);
    dv.setInt32(at + 12, c, true);
    dv.setFloat32(at + 16, x, true);
    dv.setFloat32(at + 20, y, true);
    dv.setFloat32(at + 24, dx, true);
    dv.setFloat32(at + 28, dy, true);
    dv.setInt32(block + 4, write + 1, true);
    return true;
  }

  function pushText(text) {
    const bytes = new TextEncoder().encode(text);
    const extra = Math.ceil(bytes.length / SLOT);
    const dv = view();
    const write = dv.getInt32(block + 4, true);
    const read = dv.getInt32(block + 8, true);
    if (write + 1 + extra - read >= slots - 4) return;
    const at = block + 64 + (write % slots) * SLOT;
    dv.setInt32(at, EV.TEXT_INPUT, true);
    dv.setInt32(at + 4, bytes.length, true);
    const u8 = new Uint8Array(mem.buffer);
    for (let i = 0; i < bytes.length; i++) {
      const s = (write + 1 + Math.floor(i / SLOT)) % slots;
      u8[block + 64 + s * SLOT + (i % SLOT)] = bytes[i];
    }
    dv.setInt32(block + 4, write + 1 + extra, true);
  }

  function setState(off, value, float = false) {
    if (!mem) return;
    const dv = view();
    if (float) dv.setFloat32(block + off, value, true);
    else dv.setInt32(block + off, value, true);
  }

  // ---- size ----
  let lastSize = "";
  function syncSize() {
    const c = canvas();
    const rect = c.getBoundingClientRect();
    const dpr = globalThis.devicePixelRatio || 1;
    const cssW = Math.max(1, Math.round(rect.width));
    const cssH = Math.max(1, Math.round(rect.height));
    const pxW = Math.max(1, Math.round(rect.width * dpr));
    const pxH = Math.max(1, Math.round(rect.height * dpr));
    if (c.width !== pxW || c.height !== pxH) {
      c.width = pxW;
      c.height = pxH;
    }
    const key = `${cssW}x${cssH}@${pxW}x${pxH}`;
    if (key === lastSize) return;
    lastSize = key;
    setState(16, cssW);
    setState(20, cssH);
    setState(24, pxW);
    setState(28, pxH);
    setState(32, Math.round(dpr * 1000));
    push(EV.WIN_RESIZED, cssW, cssH);
    push(EV.WIN_PIXEL_SIZE, pxW, pxH);
  }

  // ---- keyboard ----
  function setKey(scancode, down) {
    if (!mem || scancode <= 0 || scancode >= 512) return;
    new Uint8Array(mem.buffer)[keyState + scancode] = down ? 1 : 0;
  }

  function onKey(e) {
    if (!mem) return;
    const scancode = SCANCODES[e.code] ?? 0;
    const down = e.type === "keydown";
    if (scancode) {
      setKey(scancode, down);
      setState(56, e.getModifierState && e.getModifierState("CapsLock") ? 1 : 0);
      push(down ? EV.KEY_DOWN : EV.KEY_UP, scancode, keycode(e, scancode), e.repeat ? 1 : 0);
    }
    // Text: printable keys without Ctrl/Meta become text input (like SDL on desktop).
    if (down && e.key && e.key.length === 1 && !e.ctrlKey && !e.metaKey) pushText(e.key);
    // Keep browser shortcuts (Tab focus, F-keys, Ctrl+W...) from firing inside the game,
    // but let Ctrl/Meta+V through so the paste event can fill our clipboard cache.
    const pasting = (e.ctrlKey || e.metaKey) && e.code === "KeyV";
    if (!pasting) e.preventDefault();
  }

  // ---- mouse ----
  let lastX = 0;
  let lastY = 0;
  function mousePos(e) {
    const rect = canvas().getBoundingClientRect();
    return [e.clientX - rect.left, e.clientY - rect.top];
  }

  function onMove(e) {
    const locked = document.pointerLockElement === canvas();
    const [x, y] = locked ? [lastX, lastY] : mousePos(e);
    lastX = x;
    lastY = y;
    setState(40, x, true);
    setState(44, y, true);
    push(EV.MOUSE_MOTION, buttonMask, 0, 0, x, y, e.movementX || 0, e.movementY || 0);
  }

  let buttonMask = 0;
  const SDL_BUTTON = [1, 2, 3, 4, 5]; // browser 0 left,1 middle,2 right,3 back,4 forward
  function onButton(e) {
    const button = SDL_BUTTON[e.button] ?? 1;
    const down = e.type === "mousedown";
    if (down) buttonMask |= 1 << (button - 1);
    else buttonMask &= ~(1 << (button - 1));
    setState(48, buttonMask);
    const [x, y] = document.pointerLockElement === canvas() ? [lastX, lastY] : mousePos(e);
    push(down ? EV.MOUSE_DOWN : EV.MOUSE_UP, button, e.detail || 1, 0, x, y);
    if (down) canvas().focus();
    e.preventDefault();
  }

  function onWheel(e) {
    // SDL: +y scrolls away from the user. Browser: +deltaY scrolls down.
    const scale = e.deltaMode === 1 ? 1 : e.deltaMode === 2 ? 3 : 1 / 100;
    const dx = -e.deltaX * scale;
    const dy = -e.deltaY * scale;
    push(EV.MOUSE_WHEEL, 0, 0, 0, lastX, lastY, dx, dy);
    e.preventDefault();
  }

  // ---- host actions requested by the game ----
  let wantLock = false;
  const CURSORS = ["default", "text", "wait", "crosshair", "progress", "nwse-resize", "nesw-resize",
    "ew-resize", "ns-resize", "move", "not-allowed", "pointer"];
  let clipboardCache = null;

  Object.assign(globalThis.mcweb, {
    attachInput(memory, blockAddr, slotCount, keyStateAddr) {
      mem = memory;
      block = blockAddr;
      slots = slotCount;
      keyState = keyStateAddr;
      lastSize = "";
      syncSize();
      setState(36, document.hasFocus() ? 1 : 0);
    },
    setPointerLock(on) {
      wantLock = on;
      const c = canvas();
      if (on && document.pointerLockElement !== c) {
        c.requestPointerLock?.()?.catch?.(() => {});
      } else if (!on && document.pointerLockElement === c) {
        document.exitPointerLock();
      }
    },
    setCursor(id) {
      canvas().style.cursor = CURSORS[id] ?? "default";
    },
    openUrl(url) {
      if (/^https?:\/\//i.test(url)) globalThis.open(url, "_blank", "noopener");
    },
    setTextInput() {},
    setClipboard(text) {
      clipboardCache = text;
      navigator.clipboard?.writeText(text).catch(() => {});
    },
    getClipboard() {
      return clipboardCache;
    },
    showMessage(title, message) {
      globalThis.mcweb.onLog?.(`[message] ${title}: ${message}`);
    },
  });

  addEventListener("DOMContentLoaded", () => {}, { once: true });
  const c = canvas();
  c.addEventListener("keydown", onKey);
  c.addEventListener("keyup", onKey);
  c.addEventListener("mousemove", onMove);
  c.addEventListener("mousedown", onButton);
  addEventListener("mouseup", onButton);
  c.addEventListener("wheel", onWheel, { passive: false });
  c.addEventListener("contextmenu", (e) => e.preventDefault());
  c.addEventListener("mouseenter", () => push(EV.WIN_ENTER));
  c.addEventListener("mouseleave", () => push(EV.WIN_LEAVE));
  addEventListener("focus", () => { setState(36, 1); push(EV.WIN_FOCUS_GAINED); });
  addEventListener("blur", () => {
    setState(36, 0);
    push(EV.WIN_FOCUS_LOST);
    if (mem) new Uint8Array(mem.buffer).fill(0, keyState, keyState + 512); // release held keys
  });
  addEventListener("paste", (e) => { clipboardCache = e.clipboardData?.getData("text") ?? clipboardCache; });
  document.addEventListener("pointerlockchange", () => {
    const locked = document.pointerLockElement === c;
    setState(52, locked ? 1 : 0);
    if (!locked && wantLock) {
      // The user pressed Esc (the browser releases the lock itself): tell the game,
      // which opens its pause menu in response to losing focus.
      wantLock = false;
      push(EV.WIN_FOCUS_LOST);
      push(EV.WIN_FOCUS_GAINED);
    }
  });
  new ResizeObserver(syncSize).observe(c);
  matchMedia(`(resolution: ${globalThis.devicePixelRatio || 1}dppx)`).addEventListener?.("change", syncSize);
})();

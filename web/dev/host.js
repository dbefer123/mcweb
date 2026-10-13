"use strict";
// Development host for the game image: log panel, stage display and the
// requestAnimationFrame-driven frame callback that the Java side registers.
(() => {
  const logEl = document.getElementById("log");
  const stageEl = document.getElementById("stage");
  const fpsEl = document.getElementById("fps");
  const follow = document.getElementById("follow");
  const MAX_LINES = 4000;
  let lines = 0;

  function append(msg) {
    const text = String(msg);
    const span = document.createElement("span");
    if (/\/(ERROR|FATAL)\]|\[fail\]|Exception|Error:/.test(text)) span.className = "error";
    else if (/\/WARN\]/.test(text)) span.className = "warn";
    span.textContent = text + "\n";
    logEl.appendChild(span);
    if (++lines > MAX_LINES) {
      logEl.removeChild(logEl.firstChild);
      lines--;
    }
    if (follow.checked) logEl.scrollTop = logEl.scrollHeight;
  }

  let frameCallback = null;
  let frames = 0;
  let lastFpsAt = performance.now();
  function loop(now) {
    if (frameCallback) {
      frameCallback();
      frames++;
      if (now - lastFpsAt >= 1000) {
        fpsEl.textContent = `${Math.round((frames * 1000) / (now - lastFpsAt))} fps`;
        frames = 0;
        lastFpsAt = now;
      }
    }
    requestAnimationFrame(loop);
  }

  globalThis.mcweb = {
    canvas: document.getElementById("game"),
    onLog: append,
    onStage(stage) {
      stageEl.textContent = stage;
      append(`[host] stage: ${stage}`);
    },
    onExit(code) {
      frameCallback = null;
      stageEl.textContent = `exited (${code})`;
      append(`[host] game exited with code ${code}`);
    },
    setFrameCallback(cb) {
      frameCallback = cb;
    },
  };
  // Java stdout/stderr also land in console.log; mirror errors raised outside Java.
  addEventListener("error", (e) => append(`[host] ${e.message}`));
  addEventListener("unhandledrejection", (e) => append(`[host] unhandled: ${e.reason}`));

  requestAnimationFrame(loop);
  const script = document.createElement("script");
  script.src = "image/minecraft.js";
  script.onerror = () => append("[host] could not load image/minecraft.js (build it with tools/build-image.mjs)");
  document.body.appendChild(script);
})();

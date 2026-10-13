"use strict";
// Development host for the game image: log panel, stage display and the
// requestAnimationFrame-driven frame callback that the Java side registers.
// Java stack traces are built from JS stacks; V8 keeps only 10 frames by default.
Error.stackTraceLimit = 200;
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
    onLog() {}, // WebJs.log also writes to console.log, which is mirrored below
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
  // Java System.out/err go to console.log/error: mirror them into the panel too.
  for (const level of ["log", "warn", "error"]) {
    const original = console[level].bind(console);
    console[level] = (...args) => {
      original(...args);
      append(args.map(String).join(" "));
    };
  }
  // Mirror errors raised outside Java.
  addEventListener("error", (e) => append(`[host] ${e.message}`));
  addEventListener("unhandledrejection", (e) => append(`[host] unhandled: ${e.reason}`));

  requestAnimationFrame(loop);

  // The game creates its renderer synchronously, so WebGPU and the GLSL compiler
  // (glslang.wasm, see tools/glslang-wasm) are set up before the image starts.
  async function initGraphics() {
    const [{ initWebGpu, createGpuHost }, { createGlslang }] = await Promise.all([import("./webgpu.js"), import("./glslang.js")]);
    const { adapter, device } = await initWebGpu();
    globalThis.mcweb.gpu = createGpuHost({ device, adapter, canvas: globalThis.mcweb.canvas, log: append });
    append(`[host] WebGPU: ${adapter.info?.vendor ?? "?"} ${adapter.info?.architecture ?? ""} (${[...device.features].join(", ")})`);
    const wasm = await (await fetch("glslang/glslang.wasm")).arrayBuffer();
    const glslang = await createGlslang(wasm, (line) => append(`[glslang] ${line}`));
    let last = null;
    globalThis.mcweb.glsl = {
      compile(stage, source, preamble) {
        last = glslang.compile(stage, source, preamble);
        return last.ok ? last.spirv.length : -1;
      },
      take(nmem, addr) {
        new Uint32Array(nmem.buffer, addr, last.spirv.length).set(last.spirv);
      },
      lastLog() {
        return last?.log ?? "";
      },
    };
  }

  // Development: asset objects the game reads from disk (vanilla pack metadata, title
  // panorama, unifont, icons, bundled resource packs) come from the local Prism assets
  // folder (dev server mount /assets). Sounds and extra languages are left out for now.
  async function loadAssets() {
    const index = await (await fetch("assets/indexes/34.json")).json();
    const wanted = Object.entries(index.objects).filter(([name]) =>
      !name.startsWith("minecraft/sounds/") && !name.startsWith("minecraft/music/")
      && !(name.startsWith("minecraft/lang/") || name.startsWith("realms/lang/")) || name.endsWith("/en_us.json"));
    const files = [];
    let bytes = 0;
    const queue = [...wanted];
    await Promise.all(Array.from({ length: 16 }, async () => {
      while (queue.length) {
        const [, { hash }] = queue.shift();
        const path = `assets/objects/${hash.slice(0, 2)}/${hash}`;
        const res = await fetch(path);
        if (!res.ok) continue;
        const data = new Uint8Array(await res.arrayBuffer());
        bytes += data.length;
        files.push({ path, data });
      }
    }));
    globalThis.mcweb.files = files;
    append(`[host] staged ${files.length} asset objects (${(bytes / 1048576).toFixed(1)} MiB)`);
  }

  Promise.all([
    initGraphics().catch((e) => append(`[host] graphics init failed: ${e.message}`)),
    loadAssets().catch((e) => append(`[host] asset staging failed: ${e.message}`)),
  ])
    .finally(() => {
      const script = document.createElement("script");
      script.src = "image/minecraft.js";
      script.onerror = () => append("[host] could not load image/minecraft.js (build it with tools/build-image.mjs)");
      document.body.appendChild(script);
    });
})();

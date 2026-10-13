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

  // Messages are added to the panel in batches: touching the DOM (and scrolling, which
  // forces a layout) once per message cost hundreds of ms per frame during loading.
  let pending = [];
  let flushTimer = 0;
  function append(msg) {
    pending.push(String(msg));
    if (!flushTimer) flushTimer = setTimeout(flushLog, 100);
  }
  function flushLog() {
    flushTimer = 0;
    const batch = pending;
    pending = [];
    const fragment = document.createDocumentFragment();
    for (const text of batch.slice(-MAX_LINES)) {
      const span = document.createElement("span");
      if (/\/(ERROR|FATAL)\]|\[fail\]|Exception|Error:/.test(text)) span.className = "error";
      else if (/\/WARN\]/.test(text)) span.className = "warn";
      span.textContent = text + "\n";
      fragment.appendChild(span);
    }
    lines += fragment.childNodes.length;
    logEl.appendChild(fragment);
    while (lines > MAX_LINES) {
      logEl.removeChild(logEl.firstChild);
      lines--;
    }
    if (follow.checked) logEl.scrollTop = logEl.scrollHeight;
  }

  // Frame timing: `gap` is the time between animation frames, `cb` the time spent in the
  // game's frame callback. mcweb.frameStats() returns the recent frames.
  const FRAME_HISTORY = 240;
  const frameLog = [];
  let frameCallback = null;
  let frames = 0;
  let cbTotal = 0;
  let lastFpsAt = performance.now();
  let lastFrameAt = 0;
  function loop(now) {
    requestAnimationFrame(loop);
    runFrame(now);
  }

  // A hidden page gets no animation frames, which would freeze loading and let server
  // connections time out. While hidden, a worker timer (not throttled like the page's
  // timers) drives the game at a lower rate instead.
  const HIDDEN_FRAME_MS = 33;
  const ticker = new Worker(URL.createObjectURL(new Blob(
    [`setInterval(() => postMessage(0), ${HIDDEN_FRAME_MS});`], { type: "text/javascript" })));
  let lastFrameEnd = 0;
  ticker.onmessage = () => {
    // Ticks that queued up behind a long frame are dropped, leaving the thread some air.
    const now = performance.now();
    if (document.hidden && now - lastFrameEnd >= HIDDEN_FRAME_MS / 4) {
      runFrame(now);
      lastFrameEnd = performance.now();
    }
  };

  function runFrame(now) {
    if (frameCallback) {
      const start = performance.now();
      try {
        frameCallback();
      } finally {
        const cb = performance.now() - start;
        frameLog.push({ at: Math.round(now), gap: lastFrameAt ? Math.round(now - lastFrameAt) : 0, cb: Math.round(cb) });
        if (frameLog.length > FRAME_HISTORY) frameLog.shift();
        lastFrameAt = now;
        cbTotal += cb;
        frames++;
        if (now - lastFpsAt >= 1000) {
          fpsEl.textContent = `${Math.round((frames * 1000) / (now - lastFpsAt))} fps, ${Math.round(cbTotal / frames)} ms/frame in game`;
          frames = 0;
          cbTotal = 0;
          lastFpsAt = now;
        }
      }
    }
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
    frameStats() {
      return frameLog.slice();
    },
  };
  // Java System.out/err go to console.log/error: mirror them into the panel too.
  for (const level of ["log", "warn", "error"]) {
    const original = console[level].bind(console);
    console[level] = (...args) => {
      original(...args);
      append(args.map((a) => (a instanceof Error ? a.stack || String(a) : String(a))).join(" "));
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
    const gpu = createGpuHost({ device, adapter, canvas: globalThis.mcweb.canvas, log: append });
    // Development: keep a handle on the image's linear memory for inspection from the console.
    const attach = gpu.attach;
    gpu.attach = (memory) => {
      globalThis.mcweb.nmem = memory;
      attach(memory);
    };
    globalThis.mcweb.gpu = gpu;
    append(`[host] WebGPU: ${adapter.info?.vendor ?? "?"} ${adapter.info?.architecture ?? ""} (${[...device.features].join(", ")})`);
    const wasm = await (await fetch("glslang/glslang.wasm")).arrayBuffer();
    const glslang = await createGlslang(wasm, (line) => append(`[glslang] ${line}`));
    let last = null;
    let debugShaders = 0;
    globalThis.mcweb.glsl = {
      compile(stage, source, preamble) {
        last = glslang.compile(stage, source, preamble);
        if (!last.ok && debugShaders++ < 3) {
          // Show the offending lines of the first few failures.
          const m = /ERROR: \d+:(\d+):/.exec(last.log);
          const lines = source.split("\n");
          const at = m ? Number(m[1]) : 1;
          append(`[glslang] failed source lines ${Math.max(1, at - 3)}-${at + 2} (preamble: ${JSON.stringify(preamble)}):\n`
            + lines.slice(Math.max(0, at - 4), at + 2).map((l, i) => `${Math.max(1, at - 3) + i}: ${l}`).join("\n"));
        }
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
    // Vanilla resources (the game jar's assets/ and data/), built by tools/build-image.mjs.
    const res = await fetch("bundle/vanilla-resources.zip");
    if (res.ok) {
      const data = new Uint8Array(await res.arrayBuffer());
      bytes += data.length;
      files.push({ path: "resources/vanilla.zip", data });
    } else {
      append("[host] bundle/vanilla-resources.zip missing (run tools/build-image.mjs)");
    }
    globalThis.mcweb.files = files;
    append(`[host] staged ${files.length} files (${(bytes / 1048576).toFixed(1)} MiB)`);
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

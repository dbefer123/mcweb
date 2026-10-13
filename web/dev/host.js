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

  // ---- Green threads (JSPI; platform/web/.../exec/CoThreads.java) ----
  // Every Java thread runs on its own WebAssembly stack. The build makes main a promising
  // call, and patchImports below wraps the image's CoThreads.suspend* imports in
  // WebAssembly.Suspending: they return promises here, and the calling Java thread waits
  // for them while the browser (and other Java threads) run.
  const parked = new Map(); // thread key -> { resolve, timer }
  const yieldQueue = [];
  const yieldChannel = new MessageChannel(); // a yield resumes after pending tasks, unthrottled
  yieldChannel.port1.onmessage = () => yieldQueue.shift()?.(0);
  const frameWaiters = [];

  const co = {
    /** Runs runnable.run() on a new stack (promising call, see build-image.mjs). */
    start(runnable) {
      globalThis.__mcwebPromisingCall = true;
      let done;
      try {
        done = runnable.run();
      } finally {
        globalThis.__mcwebPromisingCall = false;
      }
      Promise.resolve(done).catch((e) => append(`[co] thread failed: ${e?.stack ?? e}`));
    },
    /** LockSupport.park / Object.wait: until wake(key) (→ 1) or the timeout (→ 0; ms < 0: none). */
    park(key, ms) {
      return new Promise((resolve) => {
        const waiter = { resolve, timer: 0 };
        parked.set(key, waiter);
        if (ms >= 0) {
          waiter.timer = setTimeout(() => {
            if (parked.get(key) === waiter) {
              parked.delete(key);
              resolve(0);
            }
          }, ms);
        }
      });
    },
    wake(key) {
      const waiter = parked.get(key);
      if (!waiter) return;
      parked.delete(key);
      clearTimeout(waiter.timer);
      waiter.resolve(1);
    },
    sleep(ms) {
      return new Promise((resolve) => setTimeout(() => resolve(0), ms));
    },
    yieldNow() {
      return new Promise((resolve) => {
        yieldQueue.push(resolve);
        yieldChannel.port2.postMessage(0);
      });
    },
    /** The game loop's frame boundary: resolves at the next animation frame. */
    frame() {
      if (frameStart) gameMs = performance.now() - frameStart;
      return new Promise((resolve) => frameWaiters.push(resolve));
    },
  };

  // Frame delivery and timing: `gap` is the time between frames, `game` the time from a
  // frame's start until the game loop waited for the next one (other Java threads that ran
  // in between included). mcweb.frameStats() returns the recent frames.
  const FRAME_HISTORY = 240;
  const frameLog = [];
  let frames = 0;
  let gameTotal = 0;
  let gameMs = 0;
  let frameStart = 0;
  let lastFpsAt = performance.now();
  let lastFrameAt = 0;
  function deliverFrame(now) {
    if (!frameWaiters.length) return;
    frameLog.push({ at: Math.round(now), gap: lastFrameAt ? Math.round(now - lastFrameAt) : 0, game: Math.round(gameMs) });
    if (frameLog.length > FRAME_HISTORY) frameLog.shift();
    lastFrameAt = now;
    gameTotal += gameMs;
    frames++;
    if (now - lastFpsAt >= 1000) {
      fpsEl.textContent = `${Math.round((frames * 1000) / (now - lastFpsAt))} fps, ${Math.round(gameTotal / frames)} ms/frame in game`;
      frames = 0;
      gameTotal = 0;
      lastFpsAt = now;
    }
    frameStart = performance.now();
    for (const resolve of frameWaiters.splice(0)) resolve(0);
  }
  let lastAnimationFrameAt = 0;
  function loop(now) {
    lastAnimationFrameAt = performance.now();
    requestAnimationFrame(loop);
    deliverFrame(now);
  }

  // Hidden pages, and visible ones in a minimized or occluded window, get no (or very few)
  // animation frames, which would freeze the game loop and let server connections time
  // out. When animation frames stop, a worker timer (not throttled like the page's
  // timers) delivers frames at a lower rate instead.
  const FALLBACK_FRAME_MS = 33;
  const ticker = new Worker(URL.createObjectURL(new Blob(
    [`setInterval(() => postMessage(0), ${FALLBACK_FRAME_MS});`], { type: "text/javascript" })));
  ticker.onmessage = () => {
    const now = performance.now();
    if (document.hidden || now - lastAnimationFrameAt > 100) deliverFrame(now);
  };

  globalThis.mcweb = {
    canvas: document.getElementById("game"),
    co,
    onLog() {}, // WebJs.log also writes to console.log, which is mirrored below
    onStage(stage) {
      stageEl.textContent = stage;
      append(`[host] stage: ${stage}`);
    },
    onExit(code) {
      stageEl.textContent = `exited (${code})`;
      append(`[host] game exited with code ${code}`);
    },
    /** Called by the glue before instantiation (build-image.mjs patch). */
    patchImports(imports) {
      let n = 0;
      for (const [name, fn] of Object.entries(imports.jsbody)) {
        if (name.startsWith("_CoThreads.suspend")) {
          imports.jsbody[name] = new WebAssembly.Suspending(fn);
          n++;
        }
      }
      if (n !== 4) throw new Error(`expected 4 CoThreads.suspend* imports, found ${n}`);
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
      if (typeof WebAssembly.Suspending !== "function" || typeof WebAssembly.promising !== "function") {
        append("[host] this browser lacks WebAssembly JSPI (needed for the game's threads): use Chrome or Edge 137 or newer");
        return;
      }
      const script = document.createElement("script");
      script.src = "image/minecraft.js";
      script.onerror = () => append("[host] could not load image/minecraft.js (build it with tools/build-image.mjs)");
      document.body.appendChild(script);
    });
})();

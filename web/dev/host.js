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

  // The previous page load's log (its last lines) survives a reload for diagnosis:
  // mcweb.previousLog() in the console. Best effort: storage may be unavailable.
  const PREVIOUS_LOG_KEY = "mcweb.previousLog";
  const PREVIOUS_LOG_CHARS = 200_000;
  let previousLog = "";
  try {
    previousLog = localStorage.getItem(PREVIOUS_LOG_KEY) ?? "";
  } catch {
    // no storage
  }
  addEventListener("pagehide", () => {
    try {
      flushLog();
      localStorage.setItem(PREVIOUS_LOG_KEY, logEl.textContent.slice(-PREVIOUS_LOG_CHARS));
    } catch {
      // quota or no storage
    }
  });

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
  // The ticker also notices when the page was blocked: a Java thread that runs without
  // yielding stops everything else (timers, input, the other threads) until it suspends.
  const BLOCKED_REPORT_MS = 2000;
  let lastTickAt = performance.now();
  ticker.onmessage = () => {
    const now = performance.now();
    if (now - lastTickAt > BLOCKED_REPORT_MS) {
      append(`[host] the page was blocked for ${Math.round(now - lastTickAt)} ms (a Java thread ran without yielding)`);
    }
    lastTickAt = now;
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
    /**
     * Called by the glue before instantiation (build-image.mjs patch): every @JS method
     * named suspend* (CoThreads, WebSockets) may return a promise the calling Java thread
     * waits for.
     */
    patchImports(imports) {
      const names = [];
      for (const [name, fn] of Object.entries(imports.jsbody)) {
        if (/\.suspend[A-Z]/.test(name)) {
          imports.jsbody[name] = new WebAssembly.Suspending(fn);
          names.push(name.split("___")[0]);
        }
      }
      if (!names.some((n) => n.startsWith("_CoThreads."))) throw new Error("CoThreads.suspend* imports not found");
      append(`[host] suspending imports: ${names.join(", ")}`);
    },
    net: createNet(),
    http: createHttp(),
    frameStats() {
      return frameLog.slice();
    },
    previousLog() {
      return previousLog;
    },
    persist: createPersist(),
  };

  // ---- Multiplayer sockets (platform/web/.../net/WebSocketChannel.java) ----
  // One WebSocket per server connection, to the relay (relay/relay.mjs), which opens the
  // TCP connection. awaitOpen/awaitMessage may return promises: the Java reader thread
  // suspends on them. Relay: ?relay=ws://host:port in the page URL, else 127.0.0.1:25580.
  function createNet() {
    const relay = new URLSearchParams(location.search).get("relay") ?? "ws://127.0.0.1:25580";
    const sockets = new Map();
    let nextId = 1;

    function finish(s, value) {
      const waiter = s.waiter;
      s.waiter = null;
      waiter?.(value);
    }

    return {
      open(host, port) {
        const id = nextId++;
        const s = { ws: null, state: "connecting", reason: "", queue: [], waiter: null, openWaiter: null };
        sockets.set(id, s);
        try {
          s.ws = new WebSocket(`${relay}/connect?host=${encodeURIComponent(host)}&port=${port}`);
        } catch (e) {
          s.state = "closed";
          s.reason = `Bad relay address ${relay}: ${e.message}`;
          return id;
        }
        s.ws.binaryType = "arraybuffer";
        s.ws.onopen = () => {
          s.state = "open";
          s.openWaiter?.(1);
          s.openWaiter = null;
        };
        s.ws.onmessage = (e) => {
          s.queue.push(new Uint8Array(e.data));
          if (s.queue.length === 1) finish(s, s.queue[0].length);
        };
        s.ws.onerror = () => {
          if (!s.reason) s.reason = `Can't reach the relay at ${relay} (start it with: node relay/relay.mjs)`;
        };
        s.ws.onclose = (e) => {
          if (e.reason) s.reason = e.reason;
          else if (!s.reason) s.reason = `Connection closed (${e.code})`;
          s.state = "closed";
          s.openWaiter?.(0);
          s.openWaiter = null;
          if (!s.queue.length) finish(s, -1);
        };
        return id;
      },
      awaitOpen(id) {
        const s = sockets.get(id);
        if (!s || s.state === "closed") return 0;
        if (s.state === "open") return 1;
        return new Promise((resolve) => (s.openWaiter = resolve));
      },
      reason(id) {
        return sockets.get(id)?.reason || "Connection failed";
      },
      awaitMessage(id) {
        const s = sockets.get(id);
        if (!s) return -1;
        if (s.queue.length) return s.queue[0].length;
        if (s.state === "closed") return -1;
        return new Promise((resolve) => (s.waiter = resolve));
      },
      take(id, nmem, addr) {
        const s = sockets.get(id);
        const data = s?.queue.shift();
        if (data?.length) new Uint8Array(nmem.buffer, addr, data.length).set(data);
      },
      send(id, data) {
        const s = sockets.get(id);
        if (s?.state === "open") s.ws.send(data);
      },
      close(id) {
        const s = sockets.get(id);
        if (!s) return;
        sockets.delete(id);
        s.state = "closed";
        s.queue.length = 0;
        try {
          s.ws?.close();
        } catch {
          // already closing
        }
        s.openWaiter?.(0);
        s.openWaiter = null;
        finish(s, -1);
      },
    };
  }

  // ---- HTTP (platform/web/.../net/WebUrlHandlers.java) ----
  // The game's HttpURLConnections are fetch() requests; the Java thread suspends in
  // await(id) until the response (or the failure) is in. Hosts that don't send CORS headers
  // fail like an unreachable host.
  function createHttp() {
    const requests = new Map();
    let nextId = 1;
    // Crash reporting must never leave the page (EntityCulling bundles Sentry; it is also
    // replaced by a no-op class in the image).
    const BLOCKED_HOSTS = /(^|\.)sentry\.io$|(^|\.)sentry\./i;

    return {
      start(url, method, headerLines, body, timeoutMs) {
        const id = nextId++;
        const r = { done: false, status: -1, statusText: "", headers: "", body: null, error: "", waiter: null };
        requests.set(id, r);
        const settle = () => {
          r.done = true;
          r.waiter?.(r.status);
          r.waiter = null;
        };
        let host = "";
        try {
          host = new URL(url).hostname;
        } catch {
          r.error = `TypeError: bad URL ${url}`;
          settle();
          return id;
        }
        if (BLOCKED_HOSTS.test(host)) {
          r.error = `Blocked: requests to ${host} are not allowed`;
          settle();
          return id;
        }
        const headers = new Headers();
        for (const line of headerLines.split("\n")) {
          const i = line.indexOf(":");
          if (i <= 0) continue;
          try {
            headers.append(line.slice(0, i).trim(), line.slice(i + 1).trim());
          } catch {
            // a header name or value the browser won't send
          }
        }
        const init = { method, headers, credentials: "omit", cache: "no-store", redirect: "follow" };
        if (body && method !== "GET" && method !== "HEAD") init.body = body;
        if (timeoutMs > 0) init.signal = AbortSignal.timeout(timeoutMs);
        fetch(url, init)
          .then(async (res) => {
            r.body = new Uint8Array(await res.arrayBuffer());
            r.status = res.status;
            r.statusText = res.statusText;
            r.headers = [...res.headers].map(([k, v]) => `${k}: ${v}`).join("\n");
          })
          .catch((e) => {
            r.status = -1;
            r.error = `${e?.name ?? "Error"}: ${e?.message ?? e}`;
          })
          .finally(settle);
        return id;
      },
      await(id) {
        const r = requests.get(id);
        if (!r) return -1;
        if (r.done) return r.status;
        return new Promise((resolve) => (r.waiter = resolve));
      },
      error: (id) => requests.get(id)?.error ?? "unknown request",
      statusText: (id) => requests.get(id)?.statusText ?? "",
      headers: (id) => requests.get(id)?.headers ?? "",
      bodyLength: (id) => requests.get(id)?.body?.length ?? 0,
      takeBody(id, nmem, addr) {
        const body = requests.get(id)?.body;
        if (body?.length) new Uint8Array(nmem.buffer, addr, body.length).set(body);
      },
      release(id) {
        requests.delete(id);
      },
    };
  }

  // ---- Persistent game directory (platform/web/.../storage/PersistentStorage.java) ----
  // /root/game (saves, options, configs, server list) is mirrored to the origin-private
  // file system under mcweb/game/: restored before the game starts, updated as it changes.
  function createPersist() {
    const available = typeof navigator.storage?.getDirectory === "function";
    const rootPromise = available
      ? navigator.storage.getDirectory().then((root) => root.getDirectoryHandle("mcweb", { create: true }))
      : null;
    // Pending operations by path: a newer write or a removal replaces an older one.
    const pending = new Map();
    let running = false;

    async function parentOf(path, create) {
      const parts = path.split("/");
      let dir = await rootPromise;
      for (const part of parts.slice(0, -1)) dir = await dir.getDirectoryHandle(part, { create });
      return [dir, parts[parts.length - 1]];
    }

    async function drain() {
      if (running) return;
      running = true;
      try {
        while (pending.size) {
          const [path, data] = pending.entries().next().value;
          pending.delete(path);
          try {
            if (data) {
              const [dir, name] = await parentOf(`game/${path}`, true);
              const writable = await (await dir.getFileHandle(name, { create: true })).createWritable();
              await writable.write(data);
              await writable.close();
              markInUse();
            } else {
              const [dir, name] = await parentOf(`game/${path}`, false);
              await dir.removeEntry(name);
            }
          } catch (e) {
            if (data || e.name !== "NotFoundError") append(`[storage] ${data ? "write" : "remove"} ${path} failed: ${e.message}`);
          }
        }
      } finally {
        running = false;
      }
    }

    // Browsers may clear best-effort storage (low disk space; in-memory profiles under
    // memory pressure). A flag in localStorage, which is kept separately, tells a cleared
    // store from a first visit so the loss can be reported instead of passing silently.
    const IN_USE_KEY = "mcweb.storageInUse";
    let markedInUse = false;
    function markInUse() {
      if (markedInUse) return;
      markedInUse = true;
      try {
        localStorage.setItem(IN_USE_KEY, new Date().toISOString());
      } catch {
        // no localStorage: no loss detection
      }
    }

    async function collect(dir, prefix, out) {
      for await (const [name, handle] of dir.entries()) {
        const path = `${prefix}${name}`;
        if (handle.kind === "directory") await collect(handle, `${path}/`, out);
        else out.push({ path, data: new Uint8Array(await (await handle.getFile()).arrayBuffer()) });
      }
    }

    return {
      available,
      write(path, data) {
        pending.set(path, data);
        drain();
      },
      remove(path) {
        pending.set(path, null);
        drain();
      },
      /** Stored files as { path: "game/...", data } for the image's file import. */
      async loadAll() {
        if (!available) return [];
        // Ask the browser not to evict it; browsers grant this at their discretion.
        navigator.storage.persist?.().then((granted) => {
          if (!granted) append("[host] browser storage is best-effort: the browser may clear it when space runs low (export worlds you want to keep)");
        }).catch(() => {});
        const out = [];
        const root = await rootPromise;
        const game = await root.getDirectoryHandle("game", { create: true });
        await collect(game, "game/", out);
        let inUseSince = null;
        try {
          inUseSince = localStorage.getItem(IN_USE_KEY);
        } catch {
          // no localStorage
        }
        if (!out.length && inUseSince) {
          append(`[host] WARNING: browser storage was cleared by the browser since the last visit ` +
            `(in use since ${inUseSince}): worlds and settings saved before are gone`);
        }
        return out;
      },
    };
  }
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
    // The game directory as last stored (worlds, options, configs).
    try {
      const stored = await globalThis.mcweb.persist.loadAll();
      for (const f of stored) bytes += f.data.length;
      files.push(...stored);
      append(`[host] restored ${stored.length} game files from browser storage`);
    } catch (e) {
      append(`[host] could not read browser storage: ${e.message}`);
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

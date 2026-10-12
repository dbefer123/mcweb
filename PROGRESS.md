# Progress

Plan: `C:\Users\Cade N\.claude\plans\all-right-so-here-wise-meerkat.md` (approved 2026-10-12).
Goal: Minecraft 26.3 + the Prism instance's Fabric mods, compiled to WebAssembly (GraalVM Web Image),
rendered with WebGPU, singleplayer in a Worker, multiplayer through a WebSocket↔TCP relay.

## Status

| Phase | State |
|---|---|
| 0. Toolchain spike | **Done: go** (2026-10-12) |
| 1. Inputs + Fabric baker + desktop checkpoint | Next |
| 2. Headless image | — |
| 3. Rendering + input | — |
| 4. Singleplayer | — |
| 5. Multiplayer | — |
| 6. Microsoft sign-in | — |
| 7. Completeness | — |
| 8. Performance | — |
| 9. Hosting | — |

## Environment

- Oracle GraalVM 25.2.4 (JDK 25.0.4, Web Image) at `D:\Claude\toolchains\graalvm-25.2.4+7.1`
  (sha256 2b41fffc…983a verified). Binaryen 131 at `D:\Claude\toolchains\binaryen-version_131`.
- MSVC 14.51 (Visual Studio Community 2026) + Windows SDK 10.0.26100 are installed; `tools/ni.cmd`
  runs `vcvars64.bat` (needs the VS Installer dir on PATH for `vswhere`) and sets TEMP to
  `D:\gradle-tmp\tmp` (no spaces).
- Node 26.7. Prism caches `minecraft-26.3-client.jar`; assets at `%APPDATA%\PrismLauncher\assets`.

## Phase 0 findings (spike in `spike/webimage`)

1. **Backend: WasmGC only.** `-H:Backend=WASM` (linear memory) can be forced with
   `-Dcom.oracle.graalvm.iswebimage=true` via `tools/ni-direct.mjs`, but it can't lower
   `synchronized` (MonitorEnter) and silently drops every `@JS` method. Dead end.
2. **CPU speed vs HotSpot** (cold runs): int 2.2×, long 1.4×, small-alloc 4.3×, HashMap 1.1–1.6×,
   trig 3.8× (Math.sin/cos are JS imports), interface dispatch 2.5× slower.
3. **Data path problem + fix.** A `byte[]` passed to JS is a Proxy with per-element reads
   (~3.5 MB/s); NIO "direct" buffers are heap buffers; `ByteBuffer.putFloat` is 30× slower than
   HotSpot. **Fix: `tools/watpatch`.** `-H:WasmAsPath` points at a hook that streams the WAT,
   injects an exported linear memory `nmem`, and replaces bodies of `@NeverInline` methods in
   `WasmIntrinsics` with raw loads/stores, `memory.copy/fill`, and array↔memory copies.
   Results: 4M `putFloat` = 13 ms (HotSpot ByteBuffer 8 ms), 16 MiB byte[]→nmem = 12 ms, and JS
   reads `nmem.buffer` zero-copy (WebGPU `queue.writeBuffer(buf, 0, nmem.buffer, addr, len)`).
4. **WAT naming:** `$_<SimpleClass>_<method>___<arg>_<arg>_<ret>` with `J I F D B S Z C V`,
   `$B` = byte[], `String` etc. Functions are `  (func $name …)` … `  )` at 2-space indent.
   byte[] = `$struct.array.byte { hub, identityHashCode, $field.inner: (ref $array.byte) }`.
5. **Threads:** single-threaded. `Thread.start()` **silently never runs** the thread; `join`
   returns at once. `CompletableFuture.supplyAsync` runs inline. `Object.wait(ms)` returns.
   → must substitute Thread/executors explicitly (cooperative scheduler).
6. **Reflection:** `Unsafe.class.getDeclaredField("theUnsafe")` fails (StackWalker unsupported).
   Netty/LWJGL Unsafe users need substitutions.
7. **Stack traces:** with `-H:+DebugNames`, `printStackTrace` shows mangled Java frames from the
   JS backtrace — good enough for debugging. Without it, depth 1.
8. **Interop:** `@JS` imports are all-externref (boxing per call) → fine for coarse calls,
   avoid in hot loops. JS→Java callbacks: pass a `Runnable`; JS calls `cb.run()` (120 calls ≈ 5 ms
   incl. logging). `@JS` code can call runtime-internal `getExport(name)`.
9. **Browser:** the built-in browser pane has WebGPU (NVIDIA Ampere adapter). Triangle rendered
   from Java-written `nmem` data.
10. Spike build time ~30 s, peak builder RSS 1.6 GB, wasm 8–10 MB.

## Next (Phase 1)

- `tools/stage-inputs.mjs`: copy 26.3 jar/libraries/assets from Prism, mods + config.
- `tools/fabric-baker`: Knot headless → transform all classes → baked jar + BakedFabricData.
- Desktop checkpoint: baked jar runs on plain Java 25 without Fabric's transformer.

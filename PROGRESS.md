# Progress

Plan: `C:\Users\Cade N\.claude\plans\all-right-so-here-wise-meerkat.md` (approved 2026-10-12).
Goal: Minecraft 26.3 + the Prism instance's Fabric mods, compiled to WebAssembly (GraalVM Web Image),
rendered with WebGPU, singleplayer in a Worker, multiplayer through a WebSocket↔TCP relay.

## Status

| Phase | State |
|---|---|
| 0. Toolchain spike | **Done: go** (2026-10-12) |
| 1. Inputs + Fabric baker + desktop checkpoint | **Done** (2026-10-12) — local-server join deferred to Phase 5 |
| 2. Headless image | Next |
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

## Phase 1 results

Commands (in order): `node tools/stage-inputs.mjs` → `node tools/bake.mjs` →
`node tools/run-baked.mjs --selftest [--trace]`.

- **stage-inputs** reads `mmc-pack.json` + Prism meta → `inputs/manifest.json` (74 libraries incl.
  LWJGL 3.4.3 Windows natives, Fabric Loader 0.19.5, Mixin 0.17.4/0.8.7, ASM 9.10.1), copies the 7
  mods, 9 config files and `options.txt` to `inputs/instance/`. Asset index 34 is read in place.
- **bake** runs the real Knot launcher with a build-only mod (`tools/fabric-baker`, preLaunch) and a
  capture agent. Output `build/baked/`:
  - `minecraft-baked.jar` 11,383 classes + 21,492 resources (entrypoint patch, class tweakers,
    package widening, mixins; 572 classes changed by mixins)
  - `mods-baked.jar` 4,078 classes + mod resources (no `fabric.mod.json`, so never seen as a mod)
  - `libs-baked.jar` mixin targets in libraries: DFU `TaggedChoice(+$TaggedChoiceType)`,
    Brigadier `ContextChain`
  - 1,046 mixin classes dropped; 13 Mixin-generated `$Anonymous$<hash>` classes; 6 MixinExtras
    `LocalRef*Impl` classes defined via `Lookup.defineClass` (captured by the agent);
    30 unresolved refs are optional integrations (ModMenu, Controlify) and annotations. 0 failures.
  - Gotchas: `getClassByteArray(name, true)` returns *pre*-mixin bytes; call
    `IMixinTransformer.transformClassBytes` yourself (null bytes → generate synthetic class).
    `Mixins.getConfigs()` is only the *pending* set, so library targets come from `@Mixin`
    annotations on the skipped mixin classes.
- **BakedLauncher** (`platform/fabric`) mirrors `Knot.init` without class loader/Mixin: game provider
  locate/initialize (no remap: 26.3 intermediary is an empty identity file), `load`, `freeze`,
  `loadClassTweakers`, preLaunch entrypoints, then `MinecraftGameProvider.launch`.
- **Self-test** (`platform/selftest`, `-Dmcweb.selftest=true`): title → screenshot → fixed-seed
  creative world → terrain → screenshot → `PASS` → quit. Desktop run: PASS, ~60–75 FPS, both
  screenshots correct (Sodium renderer, 57 mods). 26.3 API notes: screen/overlay are on `Gui`
  (`mc.gui.screen()`), render target is `mc.gameRenderer.mainRenderTarget()`.
- **Trace:** `build/trace-config/reachability-metadata.json` (170 KB) from a traced self-test run.
- 26.3 uses SDL3 (not GLFW), and LWJGL shaderc/spvc/spng/freetype/jemalloc/vma natives → all need
  browser replacements. Vanilla has an MDI chunk path (`prepareChunkRendersIndirect`).

## Next (Phase 2)

- Spike: Inflater/Deflater, zipfs, `resource:` NIO FS, jimfs behaviour in Web Image.
- First full image attempt: baked jars + libs, logging → simple SLF4J provider, LWJGL natives,
  threads/executors (inline first), main loop driven by requestAnimationFrame.

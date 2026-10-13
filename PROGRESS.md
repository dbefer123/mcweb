# Progress

Plan: `C:\Users\Cade N\.claude\plans\all-right-so-here-wise-meerkat.md` (approved 2026-10-12).
Goal: Minecraft 26.3 + the Prism instance's Fabric mods, compiled to WebAssembly (GraalVM Web Image),
rendered with WebGPU, singleplayer (integrated server as a green thread, see Phase 4),
multiplayer through a WebSocket↔TCP relay.

## Status

| Phase | State |
|---|---|
| 0. Toolchain spike | **Done: go** (2026-10-12) |
| 1. Inputs + Fabric baker + desktop checkpoint | **Done** (2026-10-12) — local-server join deferred to Phase 5 |
| 2. Headless image | **Done** (2026-10-13) |
| 3. Rendering + input | **Done** (2026-10-13): title screen, menus, options, Sodium video settings; clicks, keys, text |
| 4. Singleplayer | **Core done** (2026-10-13): integrated server as a green thread, worlds play and persist in OPFS across reloads. World zip import/export done. Open: perf (Phase 8) |
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

## Phase 2 (in progress)

Build: `node tools/build-image.mjs [--xmx=10g] [--parallelism=4]` → `build/image/out/minecraft.js(.wasm)`.
Dev page: `.claude/launch.json` "dev" → `http://127.0.0.1:4173` (`web/dev/`, `/image/` → build output).

Web Image probe (spike2): NIO default FS is in-memory (`/root`), FileChannel/RandomAccessFile and
embedded resources (`resource:` FS) work. Broken out of the box: zlib (`Inflater`/`Deflater` are
UOE stubs), `FileInputStream.length0`, `LockSupport.park` (throws), `Thread.start` (silently
drops the thread).

Runtime layer (`platform/web`), all via GraalVM substitutions (Web Image's own are disabled with
`-H:DisableSubstitution`):
- `jdk/`: JZlib-backed `Inflater`/`Deflater`, pure-Java CRC32/Adler32, `ZipUtils.loadLibrary` no-op,
  cooperative `Unsafe.park`, `Thread.start0` → `WebThreads` (one-shot threads run as tasks; known
  looping threads are not started), `FileInputStream.length0/position0` = 0.
- `exec/`: `WebScheduler` (task queue pumped per frame and inside park; deadlock detector after 20 s
  idle parking), `WebExecutorService`.
- `mc/`: `Util.makeExecutor/makeIoExecutor` → cooperative; `Minecraft.run()` → `FramePump`
  (rAF-driven `pollEvents` + `runTick`, then throws `MainLoopDetached`; fires Fabric API's
  `CLIENT_STARTED` itself because the baked `run()` had that injection);
  `PreferredGraphicsApi.getBackendsToTry` → `WebGpuBackend` only; Vulkan availability check → null.
- `fabric/`: `WebLauncher` + `WebGameProvider` (no class path; mods read from VFS jars),
  `UrlUtil.getCodeSource` → VFS copy of the loader jar.
- `log/`: SLF4J console provider; `LogUtils` log4j-core methods substituted; log4j-core and
  log4j-slf4j2-impl excluded from the image.
- `mem/`: `Nmem` (WAT intrinsics on exported memory `nmem`), `NativeHeap` (size-class slabs +
  best-fit coalescing large blocks), `WasmMemoryBackend` (LWJGL 3.4 `MemoryBackend`),
  `ScopedMemoryAccess` substitution (null base → nmem, else JDK internals) so real
  `DirectByteBuffer`s live in nmem; LWJGL `Library.loadSystem`/`MemoryAccessJNI` stubs.

Findings:
- 26.3 rendering is the new `com.mojang.renderpearl` library: `GpuBackend` → `GpuDeviceBackend`,
  `GpuSurfaceBackend`, `CommandEncoderBackend`, `RenderPassBackend`, shaders as `SpvModule`.
  Device = `new FrontendGpuDevice(backend)`. GL/Vulkan backends become unreachable.
- Minecraft uses 83 distinct SDL3 functions (≈65 once GL/Vulkan ones drop) → plan: emulate the
  LWJGL SDL binding methods rather than rewriting `Window`/`SDLEventHandler`.
- Builder needs: argfile (cmd.exe 8191-char limit), filtered JOML jar (Java 1.2 package-info),
  build-time init for our SLF4J classes (Netty creates loggers at build time), `-O1` and
  `-H:ImageHeapObjectsPerFunction=10000` (V8 per-function size limit), low `--parallelism`
  (analysis OOMed at 9 GB with default threads).
- EntityCulling bundles shaded Sentry (`dev.tr7zw.lib.sentry`); make sure it can't report.

Later Phase 2 findings (the image builds in ~5 min, ~122 MiB wasm, peak ~5.5 GiB):
- **Boxing caches.** The WasmGC backend lowers boxing with an "AllocatingBox" that skips the JLS
  caches, so `Integer.valueOf(0) != Integer.valueOf(0)`. Minecraft compares block-state property
  values with `==` (FerriteCore re-boxes them): FireBlock/RedstoneWireBlock got null shapes and
  Lithium NPE'd. Fix: `jdk/BoxCache` (`@NeverInline` helpers returning the JDK cache arrays) +
  WAT patches routing `$func.box.*` to them. `tools/watpatch` `@require` fails the build if a
  patched function is missing. `BoxCache.selfCheck()` must use opaque arguments, otherwise the
  analysis folds the helpers to "return one constant".
- **Concurrency.** `ForkJoinPool.submit`/`ForkJoinTask.fork` → `quietlyInvoke` (Fabric mod
  discovery waits on them); `ThreadPoolExecutor.execute` and `ScheduledThreadPoolExecutor` →
  `WebScheduler` (DataFixers optimize pool). Float/double `VarHandle` CAS can't be lowered by the
  WasmGC backend → UOE substitutions.
- **pointsto patch** (`tools/pointsto-patch`): retargets one branch in `TypeFlowSimplifier` that
  asserts "@Delete methods should have a single callee" (Minecraft code hits it). Applied with
  `-J--patch-module=org.graalvm.nativeimage.pointsto=…`.
- **Reflection metadata** is filtered from the desktop trace (game/mod packages only, no array
  types — raw array entries caused `Feature[] cannot be cast to Enum[]`), plus jopt-simple's
  `valueOf` constructors and `net.fabricmc.loader.Messages`.
- **Logging.** `LogUtils.getLogger()` uses `StackWalker.getCallerClass` → names loggers from the
  JS stack instead (`CallerNames`).
- **Fabric entrypoints** (`Class::method`) are built with `MethodHandleProxies` = runtime class
  definition → unsupported. The baker now generates `build/baked/gen-src/.../BakedEntrypoints.java`
  (`EntrypointTable`, ASM-checked access) and `DefaultLanguageAdapter.create` reads it (40/40 baked).
- **Mod threads.** EntityCulling's `CullThread` loops forever with `sleep` → `CullTask.run`
  substituted with one pass, re-run every `sleepDelay` ms by `WebThreads`. Sodium's chunk builder
  starts worker threads when a world loads → still to do (Phase 4). Transition has an April-1-only
  "godmode" code with a looping thread (harmless).
- **Other substitutions:** EntityCulling's Sentry (`SentryInstance` shadow class, no-op);
  `SystemReport` hardware/software via OSHI → static text; `ManagementFactory` GC/memory/runtime
  beans (Web Image has none); `NativeLibrariesBootstrap` no-op; `ClientBootstrap` skips
  `VulkanFeatureSets` (reflective probes); LWJGL callbacks without libffi (`Upcalls` replaced by a
  handle registry, `LibFFI` type/ABI getters stubbed, `apiCreateCIF` → null).
- Vanilla resources resolve through Web Image's `resource:` file system (warning only).
- Remaining noise: Cp1252 `native.encoding`, OSHI `LinuxOperatingSystem` (only from crash reports),
  "Log4j API could not find a logging provider" (some mod uses log4j-api directly).

## Phase 3 (in progress)

Shader path (desktop renderpearl: shaderc for GLSL→SPIR-V, SPIRV-Cross for reflection):
- **glslang → wasm:** `node tools/glslang-wasm/build.mjs` builds Khronos glslang 16.6.0 with
  wasi-sdk 34 (both pinned + sha256-checked, in `D:\Claude\toolchains`) into
  `build/glslang-wasm/glslang.wasm` (2.7 MiB). Entry points in `tools/glslang-wasm/glslang_web.c`
  (Vulkan 1.2 / SPIR-V 1.5, Vulkan rules, auto-mapped bindings = shaderc's settings; includes and
  defines are expanded on the Java side). Loader: `web/dev/glslang.js` (minimal WASI shim).
  Gotcha: needs an 8 MiB stack (`-z stack-size`), the bison parser overflows wasm-ld's 64 KiB.
- **SPIR-V side (pure Java, `platform/web/.../gpu/spirv`)**: `SpirvModule` parser,
  `SpirvReflection` (renderpearl's `SpvModule.Reflection` from the SPIR-V: same resource kinds,
  order and names as spvc; setters patch Location/Binding/DescriptorSet words in place),
  `WgslTranslator` (structured control flow incl. loops/continuing/switch/phis, GLSL.std.450,
  images; combined image samplers → texture `@binding(2b)` + sampler `2b+1`; texel buffers →
  storage arrays with format-aware loads; push constants → `@group(1) @binding(0)` storage;
  stage IO through private globals and a generated entry point; vertex Y negated so framebuffers
  match Vulkan's layout; `diagnostic(off, derivative_uniformity)`).
- **Test corpus:** `node tools/run-baked.mjs --selftest --shader-corpus` records every pipeline the
  desktop game compiles (GLSL inputs + exactly what the backend receives: patched SPIR-V and the
  pipeline description) into `build/shader-corpus` (393 pipelines incl. Sodium's 3 terrain ones).
  `node tools/shadertest/run.mjs` translates them all (393/393), and
  `http://localhost:4173/wgsl-check.html` compiles them with WebGPU and builds render pipelines
  (auto layout): 392/393 OK, the remaining one was the page passing `writeMask = -1` (Sodium uses
  -1 for "all"; the backend must mask with 15).
- Facts for the backend: default depth test is `GREATER_THAN_OR_EQUAL` (reversed Z); topologies
  QUADS (319), TRIANGLES, LINES, TRIANGLE_FAN (12, no WebGPU equivalent), TRIANGLE_STRIP, POINTS;
  WIREFRAME pipelines are skipped when the device reports no `wireframeFillMode`; color targets
  include RGBA16_FLOAT and R32_FLOAT (OIT, MAX/ADD blending → `float32-blendable`); Sodium terrain:
  20-byte push constants, R32_SINT texel buffer, attribute formats RG32_UINT/RGBA8_UNORM/RG16_UINT/
  RGBA8_UINT. Sodium 0.9 has `GLDrawContext` and `VKDrawContext` (backend-specific draw paths).

**WebGPU backend** (`platform/web/.../gpu/`, host `web/dev/webgpu.js`):
- Command stream: Java appends int32 commands to a 4 MiB stream in nmem (`WebGpu`), JS decodes
  them per flush; object ids are allocated in Java. Strings (WGSL, labels) go through direct
  `@JS` calls. Flushes happen at submit, when full, and right after commands that read caller
  memory (multi-draw parameter arrays, temp uploads).
- Buffers: mappable buffers keep an nmem shadow; write mappings upload the mapped range on close
  (host-visible semantics); read mappings return the shadow, filled by readbacks
  (`copyTextureToBuffer` → padded staging → `mapAsync` → unpadded into the shadow → callback run
  from the frame pump). Fences complete immediately (queue order).
- Transient memory: renderpearl's `TransientBlockAllocator` with GPU blocks + nmem shadows;
  written ranges uploaded right before submit; blocks reused immediately (queue order).
- Uploads stay in command order: staging + encoder copies; `writeToTexture` repacks rows to the
  256-byte pitch in Java; `copyBufferToTexture` with unaligned pitch → per-row copies.
- Pipelines: SPIR-V → WGSL at `compilePipeline`; GPU pipelines created lazily per variant
  (attachment formats + mask of bound textures that are unfilterable, which become
  `unfilterable-float` + a nearest twin sampler). Front face `cw` (framebuffer layout = Vulkan).
  Bind groups cached by resources and dropped when a resource is destroyed or unused for long.
  Push constants: 256-byte slots in a storage ring, dynamic offsets (group 1).
- QUADS/LINES/TRIANGLES → triangle-list, fans → shared fan index buffer, multi-draw variants and
  indirect draws expanded by the host. Region clears use an internal pipeline; full clears use
  load-op passes. Present = blit into the canvas texture with a Y flip (as Vulkan's blit).
- Device features reported: multi-draw (both forms), indirect, no wireframe, no shader draw
  parameters, no persistent mapping. Sodium sees a non-Vulkan device → its GL draw path
  (`multiDrawIndexed` with separate arrays), which the host expands.
- `GlslCompiler.compileToSpv` substituted: includes expanded like shaderc's resolver, defines as a
  preamble, glslang wasm via `mcweb.glsl`; returns `WebSpvModule` (Java reflection).
- Images: `NativeImage` uses stb_image/stb_image_write/stb_image_resize natives → substituted
  with a Java PNG codec (`media/PngCodec`, matches stb on all 3,965 game PNGs incl. 1/4-bit gray;
  ImageIO differs only by gamma on gray) + bilinear resize.
- Audio stubbed (OpenAL absent → "Turning off sounds & music") until Phase 7;
  `FramerateLimiter` → frame pump skips animation frames instead of sleeping.
- Dev assets: the dev server mounts Prism's `assets/`; the page stages non-sound, non-language
  asset objects (pack.mcmeta, panorama, unifont, icons, bundled packs) and `WebRuntime` copies
  page-provided files (`mcweb.files`) into the VFS at boot.
- Build: Mixin's ServiceLoader services excluded (`MixinServiceLaunchWrapper` broke compilation).

First run with the backend: device + window created, then OpenAL's `dlopen` (fixed by the audio
stub). Further fixes on the way to the title screen:
- Rendering code is now reachable → image 177 MiB wasm, WAT 3.2 GB → `-H:WasmComments=NONE`,
  builder `--xmx=12g` (peak RSS ~12 GiB; close the game tab while building).
- Web Image miscompiles a `MethodHandle.invokeExact` result (missing cast → invalid Wasm) in
  Sodium's `SimpleFrustum` → class substituted with plain field access (`@Alias` JOML planes).
- Off-heap `Unsafe` (Web Image traps it): JOML → `-Djoml.nounsafe=true` (NIO path),
  Netty → `io.netty.noUnsafe`, Sodium `MemoryIntrinsics` → nmem, Lithium `ChunkConstants`
  (`allocateInstance` via reflective `theUnsafe`) → reflection metadata for `Unsafe.theUnsafe`
  + `unsafeAllocated` for `LevelChunk` (a class-level substitution with a static initializer
  doesn't work: substitution classes are initialized at build time). A scan of all image jars found no other raw-address users (Guava/lz4 are on-heap).
- `LibSTB.setupMalloc` (STB's native allocator hook) → no-op.
- Command stream bug: a lazily created object (attachment view, push-constant layout) emitted
  its create command *inside* another command → resolve ids before `WebGpu.begin`.
- Vanilla resources: Web Image's `resource:` paths (`/8!/assets/...`) only resolve the probe file
  → the bundle now has `build/bundle/vanilla-resources.zip` (game jar `assets/` + `data/`,
  `tools/bundle/ResourceZip.java`), the page provides it as `/root/resources/vanilla.zip`, and
  `VanillaPackResourcesBuilder.pushJarResources` mounts it with zipfs. Vanilla `assets/minecraft`
  and `data/minecraft` are no longer embedded in the image.
- Dev page: errors print with stacks; `webgpu.js` names the failing opcode and stream words.

- **First frames:** the Mojang loading screen renders in the browser through the WebGPU backend
  (2026-10-13). The frame pump runs; shaders compile through glslang wasm (needs
  `GL_GOOGLE_cpp_style_line_directive` after `#version`, as shaderc enables it implicitly).
- Include text corruption ("character literals not supported", garbage after an include): our
  include expansion read the text with `ShadercIncludeResult.contentString()`, which reads up to
  a NUL; Minecraft stores the text without one (`memUTF8(s, false)`), so the read ran on into
  stale memory (left-over zip bytes from the page's file import). LWJGL's `content()` buffer is
  NUL-terminated too (`memByteBufferNT1`), so the fix reads pointer + `content_length`
  directly, as shaderc does. (An earlier theory, use-after-free of the include memory,
  was wrong: a scan of nmem found both include structs intact with the right lengths.)
  `NativeHeap` still detects double/invalid frees (slot bitmaps), logs them and ignores them.
- Relative `config/...` paths (EntityCulling): Web Image fixes the Jimfs working directory from
  `user.dir`, which isn't the game dir → `<cwd>/config` is a symlink to `/root/game/config`.
- Build-time `native.encoding=Cp1252` leaked from Windows → encodings forced to UTF-8 at boot.
- Hidden pages get no animation frames (or ~1/s), which froze loading at "1 fps" whenever the
  browser pane/tab was hidden, and would let server connections time out in a background tab.
  The host now drives frames from a worker timer (~30 Hz) while `document.hidden`: loading
  runs at ~20-30 fps hidden, ~17 ms per frame in the game.
- All 393 pipelines compile in the browser (no glslang errors) after the include fix.
- HTTP: Web Image's http/https handler is unusable for the game (plain `URLConnection`, rejects
  every proxy incl. `NO_PROXY`, refuses the main thread); the failed Realms check escaped as an
  unchecked `RealmsHttpException` and stopped the game. `net/WebUrlHandlers` is installed as the
  `URLStreamHandlerFactory` at boot: `HttpURLConnection`s that fail at connect with
  `ConnectException` (= offline on desktop). A real transport comes with Phase 6.
- ICU4J data (`langInfo.res` etc., for likely subtags/bidi) is now in the image: the 10 files
  the desktop trace read (~1 MB).
- With those, the whole resource reload completes (all atlases built, Realms check fails
  gracefully) and the first screen starts drawing. Then:
  - `Unsafe.copySwapMemory0` is missing in Web Image even heap-to-heap (ICU bidi data read
    big-endian into a `char[]`): `ScopedMemoryAccess.copySwapMemory` now always swaps through
    a `byte[]`.
  - WebGPU "buffer used in submit while destroyed": Minecraft closes buffers/textures right
    after recording commands that use them (fine in GL/Vulkan); the WebGPU destroy is now
    deferred until after the next submit.
  - Sodium generates a `VertexSerializer` class per vertex-format pair at run time
    (`Lookup.defineClass`), impossible in the image → `createSerializer` substituted with an
    interpreted serializer over Sodium's own copy list (bulk copy when formats match). A scan
    of all baked classes found no other runtime class definition (MixinExtras' LocalRef
    generator only runs at mixin-apply time; its generated impls are in the bake).
- Dev page: `mcweb.nmem` is the image's linear memory, for inspection from the console;
  `mcweb.frameStats()` lists recent frames (gap between frames, ms in the game callback); the
  log panel is updated in batches (per-message DOM appends + scroll forced ~230 ms of layout
  per frame while loading).
- `log4j-api` users get its SimpleProvider (console) instead of no provider.

- **Title screen** renders in the browser (2026-10-13): panorama, logo, splash, buttons,
  "Minecraft 26.3 (Modded)". Idle cost ~1 ms/frame in the game.
- Input: clicks work. A press can arrive without a preceding mousemove (taps, synthetic
  clicks) and Minecraft clicks at the last motion position → input.js reports a motion
  first when the press position differs.
- Options screen: the menu blur's uniform buffer is 12 bytes; WebGPU binds uniforms in
  16-byte steps → GPU buffers are rounded up to 16 bytes and uniform bindings padded to the
  pipeline's minimum.

- Options screen (with blurred panorama) and Sodium 0.9.2 video settings (Reese's Sodium
  Options 2.2.5) render and work; keys (Escape) and typed text (search box) work.
  TODO (Phase 7): text that arrives without keydown events (IME composition, mobile
  keyboards, `insertText`) needs a hidden input element / `beforeinput` handling.

## Phase 4: singleplayer (in progress)

- Create World screen opens (datapacks/world presets load: "Loaded 1866 advancements").
  "Create New World" froze the page: `Minecraft.doWorldLoad` spins in
  `while (!server.isReady())` rendering frames, and the integrated server's own loop
  (`runServer`, `prepareLevels`, `waitUntilNextTick`) blocks the same way. Every blocking
  wait in Minecraft (and Netty's `syncUninterruptibly`) would need rewriting for a
  run-to-completion runtime.
- **Decision: green threads with JSPI** (WebAssembly.Suspending/promising; Chrome/Edge 137+,
  this browser is Chromium 152). Spike (`spike/webimage/src/spike/Spike3.java`,
  `spike/webimage/build-spike3.mjs`, `spike/webimage/web/spike3.html`): Web Image (WasmGC)
  code suspends on a JS promise and resumes; a second stack started from Java via a
  promising proxy call interleaves with main (`c0m0c1m1c2m2`); exceptions across suspension
  work; the page stays responsive.
- Design (`exec/CoThreads`): every Thread is a coroutine on its own stack; `park`/`unpark`,
  `Thread.sleep`, `yield`, `Object.wait/notify`, `isAlive`/`join` suspend/resume;
  `Thread.currentThread()` is SVM's current-thread slot (`PlatformThreads.currentThread`),
  set on every resume, so `isSameThread`, chunk-cache thread checks and ThreadLocals behave
  as on the desktop. Executor tasks run on worker coroutines that yield every ~6 ms (a new
  worker only when all are blocked). Minecraft's own `run()` loop runs on the main stack;
  `RenderSystem.pollEvents` (top of each iteration) waits for the next animation frame.
  The integrated server and Netty's local event loops run unmodified. Monitors stay no-ops.
  Glue patches in build-image.mjs: imports hook, promising `main`, promising proxy calls.
- First green-thread runs: the image needs `--add-exports` for SVM's thread-local package
  (CoThreads sets the current-thread slot). Then startup stalled after the sound engine:
  a worker blocked inside a task (RetryableFetch's URL supplier joins another fetch queued
  behind it) and no other worker took the queue → workers that block (park/wait/sleep)
  now hand the queue on, and only non-blocked workers count as available. A watchdog thread
  logs threads stuck >10 s in an untimed park/wait with the park site; setting
  `mcweb.co.dumpRequested = true` in the console dumps all threads.
- Frames: a visible page in a minimized/occluded window also gets no animation frames
  (the main loop sat in awaitFrame for 40 s) → the ticker delivers a frame whenever none
  arrived for 100 ms, hidden or not.
- **Integrated server runs** (2026-10-13): Create New World → `[Server thread]` starts,
  selects the spawn, prepares the spawn area and saves chunks, while the page keeps
  rendering at ~30 fps. The client then failed to open the in-memory connection: Netty
  creates `LocalServerChannel` reflectively → reflection metadata for the Netty channel
  constructors.
- With the metadata, the in-memory connection opens (`Netty Local IO` green threads) and the
  client's first packet fails to encode: Netty's adaptive allocator wants a direct buffer,
  its build-time-chosen CleanerJava25 allocates through an FFM arena → Unsafe.allocateMemory
  → Web Image has no unmanaged malloc. Fixes: Netty on heap buffers only (unpooled
  allocator, `noPreferDirect`, `directBufferPreferred`/`canReliabilyFreeDirectBuffers`
  substituted to false), and Unsafe's allocate/free/realloc/setMemory/copyMemory on nmem
  (NativeHeap) as a safety net for any `ByteBuffer.allocateDirect`.
  Pitfall: an `@Alias` of a native that SVM substitutes elsewhere (here `copyMemory0`)
  binds to the bare native, not to the substitution → "unsupported native method" and a
  black screen. Heap cases are done in our code instead (`ArrayCopies.heapToHeap/fillHeap`).
- **Player joins the world** (2026-10-13): spawn preparation (48 chunks) takes ~10 s, then
  `Player logged in with entity id 19`, view distance 16, Sodium's chunk builder starts.
  The client crashed on its first world frame: IncompatibleClassChangeError in Sodium's
  section culling. Cause: the class-level `SimpleFrustum` substitution didn't declare
  `implements Frustum` (a class-level substitution replaces the declaration too).
- **The world renders and the player can walk** (2026-10-13): first singleplayer world in
  the browser (terrain via Sodium, sky, hand, hotbar, HUD), ~30 fps at ~3 ms/frame in the
  game loop (pane size), no WebGPU errors at first; holding W walks.
  Then fixed: Sodium's arena compaction copies within one buffer (allowed in GL, rejected
  by WebGPU → invalid command buffers) → same-buffer copies bounce through a scratch
  buffer; the world-icon screenshot needs `stbi_write_png_compression_level` /
  `force_png_filter` (STB globals) → nmem int slots.
- Persistence (`storage/PersistentStorage`, host.js `mcweb.persist`): /root/game (saves,
  options.txt, configs, servers.dat) is mirrored to OPFS `mcweb/game/`. The page restores it
  with the other staged files before the image starts; a "Storage sync" green thread
  compares mtime+size every 5 s and hands changed files / deletions to the page, which
  writes them to OPFS in the background (coalesced per path). Excludes logs/ and debug/.
  Note: don't run `build-image.mjs --compile-only` while an image build runs (it rewrites
  build/image/classes under the builder).
- `Thread.sleep` was broken: `Thread.sleepNanos0` is an unsupported native in the image
  (SVM core's substitution isn't active in Web Image; Web Image's no-op is disabled), so the
  first user of Thread.sleep (the storage sync loop, which caught the error and retried)
  spun forever and hung the page. Now substituted → CoThreads.sleep. A green thread that
  never suspends starves everything, so loops sleep outside their try blocks.
- Spin loops (green threads only switch when one suspends): "Save and Quit" hung the page.
  The client renders in `while (!server.isShutdown()) { pumpEvents; renderFrame }` (paced by
  the swap on the desktop) → `RenderSystem.pumpEvents` now waits for the next frame, like
  pollEvents. The server's stop loop waits "until the next tick" (1 ms ahead) after work
  that took longer, so `managedBlock` never parked → `BlockableEventLoop.managedBlock` yields
  (maybeYield) at entry and between tasks; `Thread.onSpinWait` yields too.
- **Worlds persist** (2026-10-13): create world → Save and Quit (server stops and saves
  cleanly) → reload the page (same tab) → 91 files restored from OPFS → the world is listed
  and loads at the same spot. Phase 4's core check passes. (Each new tab of the dev browser
  pane gets fresh storage; a reload of the same tab keeps it.)
  Then fixed: STBImageWrite's static initializer takes the address of four stb globals
  (`nstbi_zlib_compress`, `..._tga_with_rle` were missing) → every PNG write failed (0-byte
  world icon and screenshots) → all four are nmem slots. `.fabric/` (extracted nested mod
  jars, ~10 MB) is no longer persisted.
- Screenshots (F2) save real PNGs; world zip export/import: dev page "Worlds in browser
  storage" panel (web/dev/worlds.js): export zips a world from OPFS (stored entries + CRC),
  import reads stored/deflated zips (DecompressionStream; Windows "\\" names normalized),
  finds the folder with level.dat and stores it under a free name; it shows up in the game
  after a reload (verified: export → import → "New World (2)" listed).
- **Deviation from the plan:** the integrated server runs as a green thread next to the
  client, not in a Worker. IntegratedServer is tied to the client's `Minecraft` object, and
  WasmGC heaps can't be shared between Workers (a Worker server would be a second image
  instance with serialized packets). Revisit as a Phase 8 performance option.

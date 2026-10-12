# Minecraft Web Client (work in progress)

A browser client for Minecraft Java Edition 26.3 with Fabric mods baked in, built by compiling
the real game to WebAssembly (Oracle GraalVM Web Image) and rendering with WebGPU.

**Status:** early development. See [PROGRESS.md](PROGRESS.md).

## Important: what this repository contains

This repository holds only original platform code (build tools, browser runtime, relay, API).
It contains **no Minecraft code or assets**. The game bundle is built locally from your own
licensed copy of Minecraft and must not be redistributed or hosted publicly; Mojang's EULA
forbids that. The `.gitignore` excludes every Mojang-derived input and output.

## Layout

| Path | Purpose |
|---|---|
| `tools/ni.cmd` | Runs `native-image` with MSVC and space-free temp dirs (Windows) |
| `tools/watpatch/` | Assembler hook that adds a linear memory and raw-memory intrinsics to the WasmGC image |
| `tools/ni-direct.mjs` | Re-runs a captured builder command with extra hosted options (experiments) |
| `tools/static-server.mjs` | Loopback static server for local testing |
| `spike/webimage/` | Phase 0 toolchain spike (interop, memory, threads, WebGPU) |

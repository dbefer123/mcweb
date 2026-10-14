# mcweb: Minecraft Java Edition in the browser (work in progress)

A browser client for Minecraft Java Edition 26.3, with the player's Fabric mods baked in. The
real game is compiled to WebAssembly with Oracle GraalVM Web Image and renders with WebGPU. It
plays singleplayer worlds in the page and joins normal multiplayer servers through a small
WebSocket-to-TCP relay.

**Status:** early development. See [PROGRESS.md](PROGRESS.md) for the detailed log.

| Phase | State |
|---|---|
| Toolchain, Fabric mod baking, headless image | done |
| Rendering (WebGPU backend for Blaze3D) and input | done: menus, options, Sodium's settings |
| Singleplayer | done: worlds play and persist in browser storage; zip import/export |
| Multiplayer | done: server list ping, joining an offline-mode server (chat, movement, blocks) |
| Microsoft account sign-in | next (needs an approved Azure app ID) |
| Audio, skins, resource packs, performance, hosting | planned |

## What this repository contains, and what it never will

This repository holds **only original code**: the build tools, the browser runtime (WebGPU
host, input, storage, networking), the relay, and the Java platform layer that adapts the game
to the browser. It contains **no Minecraft code, assets or game files**, and none are ever
uploaded or hosted.

Each player builds the game image locally from **their own licensed copy** of Minecraft (as
installed by their launcher). That image stays on their machine; Mojang's EULA forbids
redistributing the game, and the project's tooling and `.gitignore` keep every Mojang-derived
input and output out of the repository. A public deployment will serve only the page and its
runtime; a deploy check will reject any build output that contains game files.

## How it works

```
Browser tab
 ├─ page: loader, WebGPU canvas, input, storage (OPFS), Microsoft sign-in (planned)
 ├─ game image (WebAssembly, built locally): Minecraft 26.3 + baked Fabric mods + platform layer
 │    Blaze3D GpuDevice → WebGPU (GLSL → SPIR-V → WGSL)
 │    Java threads → green threads on JS promise integration (JSPI)
 └─ WebSocket → relay (local Node process) → TCP → Minecraft server
```

- **Fabric mods** are applied at build time (mixins can't run inside an ahead-of-time
  compiled image): a baker runs Fabric Loader offline and writes the transformed classes.
- **Networking:** browsers can't open TCP sockets, so `relay/relay.mjs` carries the game's own
  protocol over a WebSocket. It accepts only the page's origin, refuses private and loopback
  destinations unless explicitly allowed, and is not an open proxy.

## Microsoft sign-in (planned) and account safety

Joining online-mode servers needs the player's Microsoft account. The plan:

- Sign-in runs **in the page** with Microsoft's OAuth 2.0 authorization-code flow with PKCE
  (public client, no client secret), using **this project's own Azure app registration**.
  No other launcher's client ID is used.
- The page exchanges the Microsoft token for Xbox Live and Minecraft tokens and uses them
  only for the requests the game itself makes (profile, session join, chat-signing
  certificates).
- Minecraft services don't allow requests from web pages (CORS), so those calls go through a
  minimal pass-through endpoint limited to the specific Mojang/Microsoft URLs the game needs.
  It forwards requests unchanged, never stores or logs tokens, and is not an open proxy.
- Tokens stay in the player's browser. Nothing is sent to any other service; crash reporting
  in the bundled mods is disabled.

## Building (developers)

Requirements: Windows, Node.js, Oracle GraalVM 25 (Web Image), Binaryen, and a Prism Launcher
instance of Minecraft 26.3 with Fabric. Paths are configured in `tools/config.mjs`.

```
node tools/stage-inputs.mjs        # read the game and mods from your own Prism instance
node tools/bake.mjs                # apply the Fabric mods
node tools/build-image.mjs         # compile the WebAssembly image (~10 min, ~12 GB RAM)
node relay/relay.mjs               # multiplayer relay on ws://127.0.0.1:25580
node tools/static-server.mjs web/dev 4173 --mount /image=build/image/out ...
```

A browser with WebGPU and JSPI (current Chrome or Edge) is required.

## Not affiliated with Mojang or Microsoft

This is an independent project. Minecraft is a trademark of Mojang Synergies AB.

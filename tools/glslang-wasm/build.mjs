// Builds glslang (Khronos reference GLSL compiler) into build/glslang-wasm/glslang.wasm
// with wasi-sdk, plus the entry points in glslang_web.c. The browser uses it where
// desktop renderpearl uses shaderc's native library.
//
// Toolchain and sources (pinned; downloaded once into D:\Claude\toolchains when missing):
//   wasi-sdk 34   https://github.com/WebAssembly/wasi-sdk/releases/tag/wasi-sdk-34
//   glslang 16.6.0 https://github.com/KhronosGroup/glslang/releases/tag/16.6.0
//
// Usage: node tools/glslang-wasm/build.mjs [--clean]
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync, renameSync, createWriteStream } from "node:fs";
import { cpus } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { BUILD } from "../config.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const TOOLCHAINS = process.env.MCWEB_TOOLCHAINS || "D:\\Claude\\toolchains";
const WASI_SDK = process.env.WASI_SDK_PATH || join(TOOLCHAINS, "wasi-sdk-34.0-x86_64-windows");
const GLSLANG_VERSION = "16.6.0";
const GLSLANG_SRC = join(TOOLCHAINS, `glslang-${GLSLANG_VERSION}`);
const GLSLANG_TARBALL = {
  url: `https://github.com/KhronosGroup/glslang/archive/refs/tags/${GLSLANG_VERSION}.tar.gz`,
  sha256: "9c09b901149c729df745057dafa815278aaa101b84d2b6e14f16a42de52f97f2",
};
const WASI_SDK_TARBALL = {
  url: "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-34/wasi-sdk-34.0-x86_64-windows.tar.gz",
  sha256: "cccb5c323a9b34f0349a9b09e8804a0a7632c68c3310f4b5f437ed57d7e71d8f",
};
const OUT = join(BUILD, "glslang-wasm");
const OBJ = join(OUT, "obj");
const GEN = join(OUT, "gen");

const SOURCES = [
  "glslang/GenericCodeGen/CodeGen.cpp",
  "glslang/GenericCodeGen/Link.cpp",
  ...[
    "glslang_tab.cpp", "attribute.cpp", "Constant.cpp", "iomapper.cpp", "InfoSink.cpp", "Initialize.cpp",
    "IntermTraverse.cpp", "Intermediate.cpp", "ParseContextBase.cpp", "ParseHelper.cpp", "PoolAlloc.cpp",
    "RemoveTree.cpp", "Scan.cpp", "ShaderLang.cpp", "SpirvIntrinsics.cpp", "SymbolTable.cpp", "Versions.cpp",
    "intermOut.cpp", "limits.cpp", "linkValidate.cpp", "parseConst.cpp", "reflection.cpp",
    "preprocessor/Pp.cpp", "preprocessor/PpAtom.cpp", "preprocessor/PpContext.cpp",
    "preprocessor/PpScanner.cpp", "preprocessor/PpTokens.cpp", "propagateNoContraction.cpp",
  ].map((f) => `glslang/MachineIndependent/${f}`),
  "glslang/CInterface/glslang_c_interface.cpp",
  "glslang/ResourceLimits/ResourceLimits.cpp",
  "glslang/ResourceLimits/resource_limits_c.cpp",
  ...[
    "GlslangToSpv.cpp", "InReadableOrder.cpp", "Logger.cpp", "SpvBuilder.cpp", "SpvPostProcess.cpp",
    "doc.cpp", "SpvTools.cpp", "disassemble.cpp", "CInterface/spirv_c_interface.cpp",
  ].map((f) => `SPIRV/${f}`),
];

const EXPORTS = ["gw_alloc", "gw_free", "gw_compile", "gw_result_free"];

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

async function download(url, dest, expected) {
  if (!existsSync(dest)) {
    console.log(`downloading ${url}`);
    const res = await fetch(url);
    if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
    const part = dest + ".part";
    const out = createWriteStream(part);
    for await (const chunk of res.body) out.write(chunk);
    await new Promise((r) => out.end(r));
    renameSync(part, dest);
  }
  const actual = sha256(dest);
  if (actual !== expected) throw new Error(`${dest}: sha256 ${actual} != ${expected}`);
}

function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const p = spawn(cmd, args, { stdio: ["ignore", "inherit", "inherit"], ...opts });
    p.on("error", reject);
    p.on("exit", (code) => (code === 0 ? resolve() : reject(new Error(`${cmd} exited ${code}`))));
  });
}

async function ensureInputs() {
  const dl = join(TOOLCHAINS, "dl-glslang");
  if (!existsSync(join(GLSLANG_SRC, "glslang"))) {
    mkdirSync(dl, { recursive: true });
    const tgz = join(dl, `glslang-${GLSLANG_VERSION}.tar.gz`);
    await download(GLSLANG_TARBALL.url, tgz, GLSLANG_TARBALL.sha256);
    await run("tar", ["-xzf", tgz, "-C", TOOLCHAINS]);
  }
  if (!existsSync(join(WASI_SDK, "bin", "clang++.exe")) && !existsSync(join(WASI_SDK, "bin", "clang++"))) {
    const dlw = join(TOOLCHAINS, "dl-wasi");
    mkdirSync(dlw, { recursive: true });
    const tgz = join(dlw, "wasi-sdk-34.0-x86_64-windows.tar.gz");
    await download(WASI_SDK_TARBALL.url, tgz, WASI_SDK_TARBALL.sha256);
    await run("tar", ["-xzf", tgz, "-C", TOOLCHAINS]);
  }
}

function writeBuildInfo() {
  const [major, minor, patch] = GLSLANG_VERSION.split(".");
  const dir = join(GEN, "glslang");
  mkdirSync(dir, { recursive: true });
  writeFileSync(
    join(dir, "build_info.h"),
    `#ifndef GLSLANG_BUILD_INFO\n#define GLSLANG_BUILD_INFO\n` +
      `#define GLSLANG_VERSION_MAJOR ${major}\n#define GLSLANG_VERSION_MINOR ${minor}\n` +
      `#define GLSLANG_VERSION_PATCH ${patch}\n#define GLSLANG_VERSION_FLAVOR ""\n` +
      `#define GLSLANG_VERSION_GREATER_THAN(major, minor, patch) ((GLSLANG_VERSION_MAJOR) > (major) || ((major) == GLSLANG_VERSION_MAJOR && ((GLSLANG_VERSION_MINOR) > (minor) || ((minor) == GLSLANG_VERSION_MINOR && (GLSLANG_VERSION_PATCH) > (patch)))))\n` +
      `#define GLSLANG_VERSION_GREATER_OR_EQUAL_TO(major, minor, patch) ((GLSLANG_VERSION_MAJOR) > (major) || ((major) == GLSLANG_VERSION_MAJOR && ((GLSLANG_VERSION_MINOR) > (minor) || ((minor) == GLSLANG_VERSION_MINOR && (GLSLANG_VERSION_PATCH >= (patch))))))\n` +
      `#define GLSLANG_VERSION_LESS_THAN(major, minor, patch) ((GLSLANG_VERSION_MAJOR) < (major) || ((major) == GLSLANG_VERSION_MAJOR && ((GLSLANG_VERSION_MINOR) < (minor) || ((minor) == GLSLANG_VERSION_MINOR && (GLSLANG_VERSION_PATCH) < (patch)))))\n` +
      `#define GLSLANG_VERSION_LESS_OR_EQUAL_TO(major, minor, patch) ((GLSLANG_VERSION_MAJOR) < (major) || ((major) == GLSLANG_VERSION_MAJOR && ((GLSLANG_VERSION_MINOR) < (minor) || ((minor) == GLSLANG_VERSION_MINOR && (GLSLANG_VERSION_PATCH <= (patch))))))\n` +
      `#endif\n`,
  );
}

const exe = (name) => join(WASI_SDK, "bin", process.platform === "win32" ? `${name}.exe` : name);
const COMMON = [
  "--target=wasm32-wasip1",
  `--sysroot=${join(WASI_SDK, "share", "wasi-sysroot")}`,
  "-O2",
  "-DENABLE_SPIRV=1",
  "-DENABLE_OPT=0",
  "-DNDEBUG",
  `-I${GLSLANG_SRC}`,
  `-I${GEN}`,
];
const CXXFLAGS = [...COMMON, "-std=c++17", "-fno-exceptions", "-fno-rtti", `-include${join(here, "wasi_shim.h")}`];

async function compileAll() {
  mkdirSync(OBJ, { recursive: true });
  const jobs = [...SOURCES.map((s) => ({ src: join(GLSLANG_SRC, s), cxx: true })), { src: join(here, "ossource_wasi.cpp"), cxx: true }, { src: join(here, "glslang_web.c"), cxx: false }];
  const objects = [];
  const queue = [...jobs];
  const workers = Math.max(1, Math.min(cpus().length, 8));
  await Promise.all(
    Array.from({ length: workers }, async () => {
      while (queue.length) {
        const job = queue.shift();
        const obj = join(OBJ, job.src.replace(/^.*?(glslang-[\d.]+[\\/]|glslang-wasm[\\/])/, "").replace(/[\\/:]/g, "_") + ".o");
        objects.push(obj);
        if (existsSync(obj) && statSync(obj).mtimeMs > statSync(job.src).mtimeMs) continue;
        const flags = job.cxx ? CXXFLAGS : [...COMMON, "-std=c11"];
        await run(exe(job.cxx ? "clang++" : "clang"), [...flags, "-c", job.src, "-o", obj]);
      }
    }),
  );
  return objects;
}

async function link(objects) {
  const out = join(OUT, "glslang.wasm");
  await run(exe("clang++"), [
    "--target=wasm32-wasip1",
    `--sysroot=${join(WASI_SDK, "share", "wasi-sysroot")}`,
    "-mexec-model=reactor",
    "-O2",
    "-Wl,--strip-debug",
    // glslang's bison parser and builtin symbol-table setup recurse deeply; the
    // wasm-ld default stack (64 KiB) overflows into the heap.
    "-Wl,-z,stack-size=8388608",
    ...EXPORTS.map((e) => `-Wl,--export=${e}`),
    "-o",
    out,
    ...objects,
  ]);
  return out;
}

if (process.argv.includes("--clean")) rmSync(OUT, { recursive: true, force: true });
const t0 = Date.now();
await ensureInputs();
writeBuildInfo();
const objects = await compileAll();
const wasm = await link(objects);
console.log(`${wasm}: ${(statSync(wasm).size / 1024).toFixed(0)} KiB in ${((Date.now() - t0) / 1000).toFixed(1)} s`);

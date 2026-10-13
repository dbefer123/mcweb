// Builds the WebAssembly game image (Phase 2+).
//
//   1. fetch pinned third-party deps (JZlib);
//   2. stage the embedded virtual file system (options, configs, mod jars, Fabric
//      Loader jar, asset index) under build/image/resources/webfs;
//   3. compile platform/web against the baked game, mods and libraries;
//   4. run native-image --tool:svm-wasm with the WAT intrinsics hook.
//
// Output: build/image/out/minecraft.js + minecraft.js.wasm (never publish these:
// they contain Mojang's code).
//
// Usage: node tools/build-image.mjs [--compile-only] [--xmx=10g] [--parallelism=4]
import { readFileSync, mkdirSync, copyFileSync, readdirSync, rmSync, writeFileSync, existsSync, cpSync, statSync } from "node:fs";
import { join, delimiter, basename } from "node:path";
import { spawnSync } from "node:child_process";
import { ROOT, BUILD, INPUTS, JAVAC, GRAALVM_HOME, TMP } from "./config.mjs";
import { fetchDeps } from "./fetch-deps.mjs";

const argv = process.argv.slice(2);
const compileOnly = argv.includes("--compile-only");
const xmx = (argv.find((a) => a.startsWith("--xmx=")) || "--xmx=11g").slice(6);
const manifest = JSON.parse(readFileSync(join(INPUTS, "manifest.json"), "utf8"));
const IMG = join(BUILD, "image");

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { stdio: "inherit", ...opts });
  if (r.status !== 0) {
    console.error(`command failed (${r.status}): ${basename(cmd)}`);
    process.exit(r.status ?? 1);
  }
}

function listFiles(dir, ext) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...listFiles(p, ext));
    else if (!ext || e.name.endsWith(ext)) out.push(p);
  }
  return out;
}

// Libraries that must stay out of the image: native-only jars, log4j-core (replaced by
// an SLF4J console provider), and the log4j SLF4J binding.
const EXCLUDE = [/-natives-/, /:log4j-core:/, /:log4j-slf4j2-impl:/, /jtracy-natives/, /java-objc-bridge/];
let libraries = manifest.libraries.filter((l) => !EXCLUDE.some((re) => re.test(l.name))).map((l) => l.path);

// Some jars (e.g. JOML) ship Java 1.2-era package-info classes the builder can't load;
// use filtered copies of those.
{
  const r = spawnSync(join(GRAALVM_HOME, "bin", "java.exe"),
    [join(ROOT, "tools", "jarfilter", "JarFilter.java"), join(IMG, "libs"), ...libraries], { encoding: "utf8" });
  if (r.status !== 0) {
    console.error(r.stderr);
    process.exit(1);
  }
  const replaced = new Map(r.stdout.trim().split(/\r?\n/).filter(Boolean).map((l) => l.split("\t")));
  libraries = libraries.map((p) => replaced.get(p) ?? p);
  for (const [from] of replaced) console.log(`filtered old package-info from ${basename(from)}`);
}
const baked = ["minecraft-baked.jar", "mods-baked.jar", "libs-baked.jar"].map((j) => join(BUILD, "baked", j));
const deps = await fetchDeps();

// 2. Embedded virtual file system (Phase 2 stand-in for the streamed game bundle).
const res = join(IMG, "resources");
const webfs = join(res, "webfs");
rmSync(res, { recursive: true, force: true });
mkdirSync(webfs, { recursive: true });
const index = [];
function stage(src, rel) {
  mkdirSync(join(webfs, rel, ".."), { recursive: true });
  copyFileSync(src, join(webfs, rel));
  index.push(rel);
}
const inst = manifest.instance;
if (existsSync(join(inst, "options.txt"))) stage(join(inst, "options.txt"), "game/options.txt");
for (const f of readdirSync(join(inst, "config"))) stage(join(inst, "config", f), `game/config/${f}`);
for (const m of manifest.mods) stage(join(inst, "mods", m.file), `game/mods/${m.file}`);
const loaderJar = manifest.libraries.find((l) => l.name.startsWith("net.fabricmc:fabric-loader:")).path;
stage(loaderJar, `loader/${basename(loaderJar)}`);
const assetIndexId = manifest.assetIndex.id;
stage(join(manifest.assetsDir, "indexes", `${assetIndexId}.json`), `assets/indexes/${assetIndexId}.json`);
writeFileSync(join(webfs, "index.txt"), index.join("\n") + "\n");
cpSync(join(ROOT, "platform", "web", "resources"), res, { recursive: true });
console.log(`staged ${index.length} embedded files`);

// 3. Compile the web platform layer.
const classes = join(IMG, "classes");
rmSync(classes, { recursive: true, force: true });
mkdirSync(classes, { recursive: true });
// svm.jar provides builder-recognized annotations such as @NeverInline (compile time only).
const compileCp = [...baked, ...libraries, ...deps, join(GRAALVM_HOME, "lib", "svm", "builder", "svm.jar")].join(delimiter);
const sources = [...listFiles(join(ROOT, "platform", "web", "src"), ".java"), ...listFiles(join(ROOT, "platform", "selftest", "src"), ".java")];
run(JAVAC, ["-nowarn", "-Xlint:none", "-parameters", "--add-exports", "java.base/jdk.internal.foreign=ALL-UNNAMED",
  "--add-modules", "org.graalvm.nativeimage,org.graalvm.webimage.api",
  "-d", classes, "-cp", compileCp, ...sources]);
console.log(`compiled ${sources.length} platform sources`);
if (compileOnly) process.exit(0);

// 4. native-image.
const out = join(IMG, "out");
mkdirSync(out, { recursive: true });
const imageCp = [classes, res, ...baked, ...libraries, ...deps].join(delimiter);
const disable = [
  // Replaced by our pure-Java zlib (platform/web/.../jdk/ZipSubstitutions.java).
  "com.oracle.svm.webimage.substitute.system.Target_java_util_zip_Inflater_Web",
  "com.oracle.svm.webimage.substitute.system.Target_java_util_zip_Deflater_Web",
  "com.oracle.svm.webimage.substitute.system.Target_java_util_zip_CRC32_Web",
  // Replaced by cooperative versions (ConcurrencySubstitutions.java).
  "com.oracle.svm.webimage.substitute.system.Target_sun_misc_Unsafe_Web.park",
  "com.oracle.svm.webimage.substitute.system.Target_sun_misc_Unsafe_Web.unpark",
  "com.oracle.svm.webimage.substitute.system.Target_java_lang_Thread_Web.start0",
  "com.oracle.svm.webimage.substitute.system.Target_java_io_FileInputStream_Web.length0",
  "com.oracle.svm.webimage.substitute.system.Target_java_io_FileInputStream_Web.position0",
];
const resources = [
  "webfs/.*",
  "assets/.*", "data/.*", "pack\\.mcmeta", "pack\\.png", "version\\.json", "flightrecorder-config\\.jfc",
  "META-INF/services/.*",
];
const parallelism = (argv.find((a) => a.startsWith("--parallelism=")) || "--parallelism=4").slice(14);
const niArgs = [
  "-H:+UnlockExperimentalVMOptions",
  // -O1 is load-bearing: -O2 and -Ob emit functions past V8's 7,654,321-byte
  // per-function limit, and the browser refuses to instantiate the module.
  "-O1",
  // Split image-heap initialization across functions for the same reason.
  "-H:ImageHeapObjectsPerFunction=10000",
  // Analysis memory scales with threads (~1.5 GB each); fewer threads fits 16 GB hosts.
  `--parallelism=${parallelism}`,
  "-J-XX:GCTimeRatio=19",
  "--add-exports=java.base/jdk.internal.foreign=ALL-UNNAMED",
  "-H:+DebugNames",
  "-H:+ReportExceptionStackTraces",
  `-H:WasmAsPath=${join(ROOT, "tools", "watpatch", "wasm-as.cmd")}`,
  ...disable.map((d) => `-H:DisableSubstitution=${d}`),
  ...resources.map((r) => `-H:IncludeResources=${r}`),
  `-H:ConfigurationFileDirectories=${join(BUILD, "trace-config")}`,
  // Netty's bundled config creates SLF4J loggers at build time, so our logger types
  // end up in the image heap; they hold no runtime state, so initialize them early too.
  "--initialize-at-build-time=dev.mcwebclient.web.log.WebLogger,dev.mcwebclient.web.log.WebSlf4jProvider,dev.mcwebclient.web.log.WebSlf4jProvider$Factory",
  `-J-Xmx${xmx}`,
  "-cp", imageCp,
  "-o", join(out, "minecraft"),
  "dev.mcwebclient.web.WebMain",
];
// The class path alone exceeds cmd.exe's 8191-character limit, so everything except
// the tool selector (which must come first) goes through a Java-style @argfile.
const argfile = join(IMG, "native-image.args");
writeFileSync(argfile, niArgs.map((a) => `"${a.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`).join("\n"));
const t0 = Date.now();
run("cmd", ["/c", join(ROOT, "tools", "ni.cmd"), "--tool:svm-wasm", `@${argfile}`]);
console.log(`image built in ${((Date.now() - t0) / 60000).toFixed(1)} min`);
for (const f of readdirSync(out)) console.log(`  ${f}  ${(statSync(join(out, f)).size / 1048576).toFixed(1)} MiB`);

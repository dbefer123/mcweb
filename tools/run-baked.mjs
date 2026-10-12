// Desktop checkpoint (Phase 1): runs Minecraft from the baked jars on plain Java,
// with no Knot class loader, no Mixin transformer and no original client jar.
//
// Usage: node tools/run-baked.mjs [--compile-only] [extra game args...]
import { readFileSync, mkdirSync, copyFileSync, readdirSync, rmSync, cpSync, existsSync } from "node:fs";
import { join, delimiter } from "node:path";
import { spawnSync } from "node:child_process";
import { ROOT, BUILD, INPUTS, JAVA, JAVAC, JAR, TMP } from "./config.mjs";

const manifest = JSON.parse(readFileSync(join(INPUTS, "manifest.json"), "utf8"));
const args = process.argv.slice(2);
const compileOnly = args.includes("--compile-only");
const gameArgs = args.filter((a) => a !== "--compile-only");
const fabricLibs = manifest.libraries.filter((l) => l.role === "fabric").map((l) => l.path);

function run(cmd, argv, opts = {}) {
  const r = spawnSync(cmd, argv, { stdio: "inherit", ...opts });
  if (r.status !== 0) process.exit(r.status ?? 1);
}

function listJava(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...listJava(p));
    else if (e.name.endsWith(".java")) out.push(p);
  }
  return out;
}

// Compile the Fabric runtime (BakedLauncher).
const rtOut = join(BUILD, "runtime", "fabric");
rmSync(rtOut, { recursive: true, force: true });
mkdirSync(rtOut, { recursive: true });
run(JAVAC, ["-nowarn", "--release", "25", "-d", rtOut, "-cp", fabricLibs.join(delimiter), ...listJava(join(ROOT, "platform", "fabric", "src"))]);
const rtJar = join(BUILD, "runtime", "fabric-runtime.jar");
run(JAR, ["--create", "--file", rtJar, "-C", rtOut, "."]);

// Compile the self-test driver against the baked game + mods.
const baked = join(BUILD, "baked");
const bakedJars = ["minecraft-baked.jar", "mods-baked.jar", "libs-baked.jar"].map((j) => join(baked, j));
const stOut = join(BUILD, "runtime", "selftest");
rmSync(stOut, { recursive: true, force: true });
mkdirSync(stOut, { recursive: true });
run(JAVAC, ["-nowarn", "--release", "25", "-d", stOut, "-cp", [...bakedJars, ...manifest.libraries.map((l) => l.path)].join(delimiter),
  ...listJava(join(ROOT, "platform", "selftest", "src"))]);
const stJar = join(BUILD, "runtime", "selftest.jar");
run(JAR, ["--create", "--file", stJar, "-C", stOut, "."]);
if (compileOnly) process.exit(0);
const selftest = gameArgs.includes("--selftest");
// --trace records reflection/resource/proxy use for native-image (GraalVM tracing agent).
const trace = gameArgs.includes("--trace");
const passArgs = gameArgs.filter((a) => a !== "--selftest" && a !== "--trace");
const traceDir = join(BUILD, "trace-config");
const agentArgs = trace ? [`-agentlib:native-image-agent=config-merge-dir=${traceDir}`] : [];

// Game dir with the real mods (for metadata/resources) and configs.
const gameDir = join(BUILD, "desktop-run");
mkdirSync(join(gameDir, "mods"), { recursive: true });
for (const f of readdirSync(join(gameDir, "mods"))) rmSync(join(gameDir, "mods", f));
for (const m of manifest.mods) copyFileSync(join(manifest.instance, "mods", m.file), join(gameDir, "mods", m.file));
if (!existsSync(join(gameDir, "config"))) cpSync(join(manifest.instance, "config"), join(gameDir, "config"), { recursive: true });
if (!existsSync(join(gameDir, "options.txt")) && existsSync(join(manifest.instance, "options.txt"))) {
  copyFileSync(join(manifest.instance, "options.txt"), join(gameDir, "options.txt"));
}

const classpath = [
  ...bakedJars,
  rtJar,
  stJar,
  ...manifest.libraries.map((l) => l.path),
].join(delimiter);

run(JAVA, [
  "-Xmx4g",
  "-XX:StackShadowPages=32",
  ...agentArgs,
  `-Djava.io.tmpdir=${TMP}`,
  `-Dmcweb.selftest=${selftest}`,
  "-cp", classpath,
  "dev.mcwebclient.fabric.BakedLauncher",
  "--username", "Player",
  "--version", manifest.versions["net.minecraft"],
  "--gameDir", gameDir,
  "--assetsDir", manifest.assetsDir,
  "--assetIndex", manifest.assetIndex.id,
  "--uuid", "00000000000000000000000000000000",
  "--accessToken", "0",
  "--versionType", "release",
  ...passArgs,
], { cwd: gameDir });

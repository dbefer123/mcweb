// Bakes Fabric mods into static jars (Phase 1).
//
//   1. compiles the baker mod (tools/fabric-baker) against the staged Fabric Loader/Mixin/ASM;
//   2. builds a throwaway game dir (build/bake-run) with the staged mods + baker + configs;
//   3. launches the real Knot (KnotClient); the baker's preLaunch entrypoint writes
//      build/baked/{minecraft-baked.jar, mods-baked.jar, bake-report.json} and halts the JVM.
//
// Requires: node tools/stage-inputs.mjs
import { readFileSync, mkdirSync, copyFileSync, readdirSync, existsSync, rmSync, writeFileSync, cpSync } from "node:fs";
import { join, delimiter } from "node:path";
import { spawnSync } from "node:child_process";
import { ROOT, BUILD, INPUTS, JAVA, JAVAC, JAR, TMP } from "./config.mjs";

const manifest = JSON.parse(readFileSync(join(INPUTS, "manifest.json"), "utf8"));
const fabricLibs = manifest.libraries.filter((l) => l.role === "fabric").map((l) => l.path);
mkdirSync(TMP, { recursive: true });

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { stdio: "inherit", ...opts });
  if (r.status !== 0) {
    console.error(`command failed (${r.status}): ${cmd} ${args.slice(0, 6).join(" ")} ...`);
    process.exit(r.status ?? 1);
  }
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

// 1. Compile the baker mod.
const bakerSrc = join(ROOT, "tools", "fabric-baker");
const bakerOut = join(BUILD, "baker");
rmSync(join(bakerOut, "classes"), { recursive: true, force: true });
mkdirSync(join(bakerOut, "classes"), { recursive: true });
run(JAVAC, ["-nowarn", "--release", "21", "-d", join(bakerOut, "classes"), "-cp", fabricLibs.join(delimiter), ...listJava(join(bakerSrc, "src"))]);
copyFileSync(join(bakerSrc, "resources", "fabric.mod.json"), join(bakerOut, "classes", "fabric.mod.json"));
const bakerJar = join(bakerOut, "mcweb-baker.jar");
run(JAR, ["--create", "--file", bakerJar, "-C", join(bakerOut, "classes"), "."]);

// 1b. Compile the capture agent (records classes defined at runtime, e.g. MixinExtras refs).
const agentOut = join(bakerOut, "agent-classes");
rmSync(agentOut, { recursive: true, force: true });
mkdirSync(agentOut, { recursive: true });
run(JAVAC, ["-nowarn", "--release", "21", "-d", agentOut, ...listJava(join(bakerSrc, "agent"))]);
writeFileSync(join(bakerOut, "agent-manifest.txt"), "Premain-Class: dev.mcwebclient.bakeragent.CaptureAgent\n");
const agentJar = join(bakerOut, "mcweb-bake-agent.jar");
run(JAR, ["--create", "--file", agentJar, "--manifest", join(bakerOut, "agent-manifest.txt"), "-C", agentOut, "."]);
const capturePrefixes = ["com/llamalad7/mixinextras/"];

// 2. Throwaway game dir.
const runDir = join(BUILD, "bake-run");
rmSync(runDir, { recursive: true, force: true });
mkdirSync(join(runDir, "mods"), { recursive: true });
for (const m of manifest.mods) copyFileSync(join(manifest.instance, "mods", m.file), join(runDir, "mods", m.file));
copyFileSync(bakerJar, join(runDir, "mods", "mcweb-baker.jar"));
cpSync(join(manifest.instance, "config"), join(runDir, "config"), { recursive: true });

// 3. Launch Knot.
const outDir = join(BUILD, "baked");
mkdirSync(outDir, { recursive: true });
const classpath = [...manifest.libraries.map((l) => l.path), manifest.gameJar.path].join(delimiter);
const t0 = Date.now();
run(JAVA, [
  "-Xmx4g",
  `-javaagent:${agentJar}=${capturePrefixes.join(",")}`,
  `-Djava.io.tmpdir=${TMP}`,
  `-Dmcweb.bake.out=${outDir}`,
  "-Dfabric.log.level=info",
  "-cp", classpath,
  manifest.fabricMainClass,
  "--username", "Baker",
  "--version", manifest.versions["net.minecraft"],
  "--gameDir", runDir,
  "--assetsDir", manifest.assetsDir,
  "--assetIndex", manifest.assetIndex.id,
  "--uuid", "00000000000000000000000000000000",
  "--accessToken", "0",
  "--versionType", "release",
], { cwd: runDir });

const report = JSON.parse(readFileSync(join(outDir, "bake-report.json"), "utf8"));
console.log(`\nbake finished in ${((Date.now() - t0) / 1000).toFixed(1)} s`);
console.log(`  minecraft classes: ${report.minecraftClasses}, resources: ${report.minecraftResources}, mod classes: ${report.modClasses}`);
console.log(`  classes changed by transforms per mod:`, report.changedPerMod);
console.log(`  skipped mixin classes: ${report.skippedMixinClasses.length}, generated: ${report.generatedClasses.length}`);
console.log(`  unresolved references: ${report.unresolvedReferences.length}, failures: ${Object.keys(report.failures).length}`);
for (const [k, v] of Object.entries(report.failures).slice(0, 20)) console.log(`    FAIL ${k}: ${v.slice(0, 300)}`);

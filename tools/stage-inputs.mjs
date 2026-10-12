// Resolves the exact game inputs from the Prism instance and writes inputs/manifest.json.
//
// Reads the instance's mmc-pack.json for component versions, then Prism's cached
// metadata for each component to collect library coordinates. Libraries are
// referenced in place in Prism's libraries folder (nothing is downloaded).
// Mods and configs are copied into inputs/instance so later steps never touch
// the live Prism instance.
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, copyFileSync, statSync } from "node:fs";
import { join, basename } from "node:path";
import { createHash } from "node:crypto";
import { PRISM_DIR, INSTANCE_DIR, GAME_DIR, INPUTS } from "./config.mjs";

const readJson = (p) => JSON.parse(readFileSync(p, "utf8"));
const LIBS = join(PRISM_DIR, "libraries");

function mavenPath(name) {
  const [group, artifact, version, classifier] = name.split(":");
  const file = `${artifact}-${version}${classifier ? "-" + classifier : ""}.jar`;
  return join(LIBS, ...group.split("."), artifact, version, file);
}

// Prism/Mojang library rules: allow/disallow per OS. We stage for this machine (Windows x64).
function rulesAllow(rules) {
  if (!rules) return true;
  let allowed = false;
  for (const rule of rules) {
    const osName = rule.os?.name;
    const matches = !osName || osName === "windows" || osName === "windows-x86_64";
    if (matches) allowed = rule.action === "allow";
  }
  return allowed;
}

function collect(metaFile, role) {
  const meta = readJson(metaFile);
  const out = [];
  for (const lib of meta.libraries || []) {
    if (!rulesAllow(lib.rules)) continue;
    // Skip 32-bit and ARM Windows natives; this host is x64.
    if (/natives-windows-(x86|arm64)/.test(lib.name)) continue;
    const path = mavenPath(lib.name);
    out.push({ name: lib.name, path, role, exists: existsSync(path) });
  }
  if (meta.mainJar) {
    out.push({ name: meta.mainJar.name, path: mavenPath(meta.mainJar.name), role: "game", exists: existsSync(mavenPath(meta.mainJar.name)) });
  }
  return { meta, libs: out };
}

const pack = readJson(join(INSTANCE_DIR, "mmc-pack.json"));
const versions = Object.fromEntries(pack.components.map((c) => [c.uid, c.version]));
const metaFile = (uid) => join(PRISM_DIR, "meta", uid, `${versions[uid]}.json`);

const mc = collect(metaFile("net.minecraft"), "minecraft");
const lwjgl = collect(metaFile("org.lwjgl3"), "lwjgl");
const loader = collect(metaFile("net.fabricmc.fabric-loader"), "fabric");
const intermediary = collect(metaFile("net.fabricmc.intermediary"), "fabric");

const all = [...mc.libs, ...lwjgl.libs, ...loader.libs, ...intermediary.libs];
const missing = all.filter((l) => !l.exists);
if (missing.length) {
  console.error("Missing libraries (launch the instance once in Prism to download them):");
  for (const m of missing) console.error("  " + m.name + " -> " + m.path);
  process.exit(1);
}

// Copy mods and configs into a private staging copy.
const stageDir = join(INPUTS, "instance");
for (const sub of ["mods", "config"]) mkdirSync(join(stageDir, sub), { recursive: true });
const sha1 = (p) => createHash("sha1").update(readFileSync(p)).digest("hex");
const mods = [];
for (const f of readdirSync(join(GAME_DIR, "mods"))) {
  if (!f.endsWith(".jar")) continue;
  const src = join(GAME_DIR, "mods", f);
  copyFileSync(src, join(stageDir, "mods", f));
  mods.push({ file: f, sha1: sha1(src), size: statSync(src).size });
}
const configs = [];
for (const f of readdirSync(join(GAME_DIR, "config"))) {
  const src = join(GAME_DIR, "config", f);
  if (!statSync(src).isFile()) continue;
  copyFileSync(src, join(stageDir, "config", f));
  configs.push(f);
}

// The player's options (keybinds, video settings, onboarding already done).
const hasOptions = existsSync(join(GAME_DIR, "options.txt"));
if (hasOptions) copyFileSync(join(GAME_DIR, "options.txt"), join(stageDir, "options.txt"));

const gameJar = all.find((l) => l.role === "game");
const manifest = {
  generated: new Date().toISOString(),
  versions,
  mainClass: mc.meta.mainClass,
  fabricMainClass: loader.meta.mainClass,
  assetIndex: mc.meta.assetIndex,
  assetsDir: join(PRISM_DIR, "assets"),
  gameJar: { path: gameJar.path, sha1: sha1(gameJar.path) },
  libraries: all.filter((l) => l.role !== "game").map(({ name, path, role }) => ({ name, path, role })),
  instance: stageDir,
  mods,
  configs,
};
mkdirSync(INPUTS, { recursive: true });
writeFileSync(join(INPUTS, "manifest.json"), JSON.stringify(manifest, null, 2));
console.log(`Minecraft ${versions["net.minecraft"]}, Fabric Loader ${versions["net.fabricmc.fabric-loader"]}, LWJGL ${versions["org.lwjgl3"]}`);
console.log(`${manifest.libraries.length} libraries, ${mods.length} mods (${mods.map((m) => basename(m.file)).join(", ")}), ${configs.length} config files`);
console.log(`game jar sha1 ${manifest.gameJar.sha1}`);
console.log(`wrote ${join(INPUTS, "manifest.json")}`);

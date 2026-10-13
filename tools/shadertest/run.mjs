// Compiles the WGSL translator (platform/web/.../gpu/spirv) plus tools/shadertest on the
// desktop JVM and runs it over build/shader-corpus. See ShaderTest.java.
//
// Usage: node tools/shadertest/run.mjs
import { readFileSync, readdirSync, mkdirSync, rmSync } from "node:fs";
import { join, delimiter } from "node:path";
import { spawnSync } from "node:child_process";
import { ROOT, BUILD, INPUTS, JAVA, JAVAC } from "../config.mjs";

const manifest = JSON.parse(readFileSync(join(INPUTS, "manifest.json"), "utf8"));
const cp = [
  ...["minecraft-baked.jar", "mods-baked.jar", "libs-baked.jar"].map((j) => join(BUILD, "baked", j)),
  ...manifest.libraries.map((l) => l.path),
];
const spirvDir = join(ROOT, "platform", "web", "src", "dev", "mcwebclient", "web", "gpu", "spirv");
const sources = [
  ...readdirSync(spirvDir).filter((f) => f.endsWith(".java")).map((f) => join(spirvDir, f)),
  join(ROOT, "tools", "shadertest", "ShaderTest.java"),
];
const out = join(BUILD, "shadertest", "classes");
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
let r = spawnSync(JAVAC, ["-nowarn", "--release", "25", "-d", out, "-cp", cp.join(delimiter), ...sources], { stdio: "inherit" });
if (r.status !== 0) process.exit(r.status ?? 1);
r = spawnSync(JAVA, ["-cp", [out, ...cp].join(delimiter), "ShaderTest", join(BUILD, "shader-corpus")], { stdio: "inherit" });
process.exit(r.status ?? 1);

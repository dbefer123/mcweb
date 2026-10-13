// JSPI probe build: compiles spike.Spike3 with Web Image, then patches the JS glue so
// that (a) the page can wrap imports in WebAssembly.Suspending before instantiation,
// (b) main runs through WebAssembly.promising, and (c) a Java proxy call can be made
// through WebAssembly.promising (a coroutine on its own stack).
// Usage: node spike/webimage/build-spike3.mjs   (output: spike/webimage/web/s3/)
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..", "..");
const GRAALVM_HOME = process.env.GRAALVM_HOME || "D:\\Claude\\toolchains\\graalvm-25.2.4+7.1";
const classes = join(HERE, "out", "s3", "classes");
const out = join(HERE, "web", "s3");

function run(cmd, args) {
  const r = spawnSync(cmd, args, { stdio: "inherit" });
  if (r.status !== 0) throw new Error(`${cmd} exited with ${r.status}`);
}

rmSync(classes, { recursive: true, force: true });
mkdirSync(classes, { recursive: true });
mkdirSync(out, { recursive: true });
run(join(GRAALVM_HOME, "bin", "javac.exe"), ["--add-modules", "org.graalvm.webimage.api", "-d", classes,
  join(HERE, "src", "spike", "Spike3.java")]);
run("cmd", ["/c", join(ROOT, "tools", "ni.cmd"), "--tool:svm-wasm", "-H:+UnlockExperimentalVMOptions",
  "-H:+DebugNames", "-cp", classes, "-o", join(out, "spike3"), "spike.Spike3"]);

// Glue patches (exact-text replacements; fail loudly if Web Image's glue changes).
const gluePath = join(out, "spike3.js");
let glue = readFileSync(gluePath, "utf8");
function patch(from, to) {
  if (!glue.includes(from)) throw new Error(`glue patch target not found: ${from}`);
  glue = glue.replace(from, to);
}
patch("const result = await WebAssembly.instantiate(file, wasmImports);",
  "globalThis.mcwebPatchImports?.(wasmImports);\n    const result = await WebAssembly.instantiate(file, wasmImports);");
patch('getExport("main")(toJavaStringArray(args));',
  'WebAssembly.promising(getExport("main"))(toJavaStringArray(args)).catch((e) => { console.log("main failed:"); console.log(e); });');
patch("javaResult = metadata.method.call(null, javaThis, ...javaArgs);",
  "if (globalThis.__mcwebPromisingCall) { globalThis.__mcwebPromisingCall = false; return WebAssembly.promising(metadata.method)(javaThis, ...javaArgs); }\n" +
  "                        javaResult = metadata.method.call(null, javaThis, ...javaArgs);");
writeFileSync(gluePath, glue);
console.log("spike3 built and patched:", gluePath);

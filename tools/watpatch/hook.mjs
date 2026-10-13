// Stand-in for Binaryen's wasm-as, used via native-image -H:WasmAsPath=.
//
// Web Image writes the whole module as WAT text and then assembles it with
// wasm-as. Before assembling, this hook streams the WAT and:
//   1. replaces the bodies of marked Java methods (e.g. WasmIntrinsics.putFloat)
//      with hand-written WAT (linear-memory loads/stores, bulk copies);
//   2. injects extra module fields (the "nmem" linear memory and its export).
// The spec file is named by WATPATCH_SPEC (default: intrinsics.wat next to
// this script). Functions named by @require must exist in the WAT, or the
// build fails (exit 3): a patch that silently found nothing would leave the
// image subtly wrong (e.g. JLS boxing caches bypassed).
//
// Spec format:
//   ;; @replace-body $_WasmIntrinsics_putFloat___JF_V
//   (f32.store $nmem (i32.wrap_i64 (local.get $p0)) (local.get $p1))
//   ;; @end
//   ;; @module
//   (memory $nmem 16 65536)
//   ;; @end
//   ;; @require $func.box.Int
import { createReadStream, createWriteStream, readFileSync, appendFileSync, existsSync } from "node:fs";
import { createInterface } from "node:readline";
import { spawnSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const logFile = process.env.WATPATCH_LOG;
const log = (msg) => {
  if (logFile) appendFileSync(logFile, msg + "\n");
};
log(`[watpatch] argv: ${JSON.stringify(args)}`);

function realWasmAs() {
  const home = process.env.BINARYEN_HOME || "D:\\Claude\\toolchains\\binaryen-version_131";
  return join(home, "bin", process.platform === "win32" ? "wasm-as.exe" : "wasm-as");
}

function parseSpec(path) {
  const replace = new Map();
  const moduleFields = [];
  const required = new Set();
  let mode = null;
  let name = null;
  let buf = [];
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const directive = line.match(/^;;\s*@(replace-body|module|require|end)\s*(\S*)/);
    if (directive) {
      const [, kind, arg] = directive;
      if (kind === "require") {
        if (mode) throw new Error(`watpatch: @require inside @${mode} block in ${path}`);
        required.add(arg);
      } else if (kind === "end") {
        if (mode === "replace-body") replace.set(name, buf.join("\n"));
        else if (mode === "module") moduleFields.push(buf.join("\n"));
        mode = null;
        buf = [];
      } else {
        mode = kind;
        name = arg;
        buf = [];
      }
      continue;
    }
    if (mode) buf.push(line);
  }
  if (mode) throw new Error(`watpatch: unterminated @${mode} block in ${path}`);
  return { replace, moduleFields, required };
}

async function patch(input, output, spec) {
  const out = createWriteStream(output);
  const write = (s) => {
    if (!out.write(s + "\n")) return new Promise((r) => out.once("drain", r));
    return null;
  };
  const found = new Set();
  const requiredFound = new Set();
  const rl = createInterface({ input: createReadStream(input), crlfDelay: Infinity });
  let skipping = false;
  let held = null;
  for await (const line of rl) {
    if (skipping) {
      if (line === "  )") {
        skipping = false;
        const p = write(line);
        if (p) await p;
      }
      continue;
    }
    if (line.startsWith("  (func $")) {
      const fname = line.slice(8).split(/[\s(]/, 1)[0];
      if (spec.required.has(fname)) requiredFound.add(fname);
      const body = spec.replace.get(fname);
      if (body !== undefined) {
        found.add(fname);
        skipping = true;
        if (held !== null) {
          const p0 = write(held);
          if (p0) await p0;
          held = null;
        }
        const p = write(line + "\n" + body);
        if (p) await p;
        continue;
      }
    }
    if (held !== null) {
      const p = write(held);
      if (p) await p;
    }
    held = line;
  }
  // `held` is the module's closing paren: inject extra fields before it.
  for (const field of spec.moduleFields) await (write(field) ?? Promise.resolve());
  if (held !== null) await (write(held) ?? Promise.resolve());
  await new Promise((r) => out.end(r));
  // An intrinsic class that isn't reachable is compiled away entirely; that's fine.
  // But if some of a class's intrinsics exist and others don't, one was inlined or
  // renamed and would silently run its placeholder body.
  const owner = (fn) => fn.slice(0, fn.indexOf("_", 2));
  const usedOwners = new Set([...found].map(owner));
  const missing = [...spec.replace.keys()].filter((k) => !found.has(k) && usedOwners.has(owner(k)));
  const missingRequired = [...spec.required].filter((k) => !requiredFound.has(k));
  return { found: found.size, missing, missingRequired };
}

const watIndex = args.findIndex((a) => a.endsWith(".wat"));
let forwarded = args;
if (watIndex >= 0) {
  const specPath = resolve(process.env.WATPATCH_SPEC || join(here, "intrinsics.wat"));
  if (!existsSync(specPath)) throw new Error(`watpatch: spec not found: ${specPath}`);
  const spec = parseSpec(specPath);
  const input = args[watIndex];
  const output = input.replace(/\.wat$/, ".patched.wat");
  const t0 = Date.now();
  const { found, missing, missingRequired } = await patch(input, output, spec);
  if (missingRequired.length) {
    const msg = `watpatch: required functions missing from WAT: ${missingRequired.join(", ")}`;
    console.error(msg);
    process.exit(3);
  }
  log(`[watpatch] ${input}: replaced ${found} bodies, injected ${spec.moduleFields.length} fields in ${Date.now() - t0} ms`);
  if (missing.length) {
    // Usually just unreachable (never called, so never compiled). An intrinsic that was
    // inlined instead would run its placeholder body; Nmem.selfCheck() at boot catches that.
    const msg = `watpatch: note: intrinsics not present in WAT (unreachable?): ${missing.join(", ")}`;
    log(msg);
    console.error(msg);
  }
  forwarded = [...args];
  forwarded[watIndex] = output;
}
const result = spawnSync(realWasmAs(), forwarded, { stdio: "inherit" });
process.exit(result.status ?? 1);

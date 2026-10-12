// Re-runs a native-image builder command captured with `--dry-run --verbose`,
// appending extra hosted options the driver would otherwise reject
// (e.g. -H:Backend=WASM, which svm-wasm does not list as user-settable).
//
// Usage: node tools/ni-direct.mjs <dryrun.txt> [extra -H options...]
import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";

const [dryRunPath, ...extra] = process.argv.slice(2);
const text = readFileSync(dryRunPath, "utf8").replace(/^\uFEFF/, "");
const lines = text.split(/\r?\n/);
const start = lines.findIndex((l) => l.startsWith("Executing ["));
const end = lines.findIndex((l, i) => i > start && l.trim() === "]");
if (start < 0 || end < 0) throw new Error("no 'Executing [' block in dry-run output");

function unquote(token) {
  token = token.replace(/\s*\\$/, "").trim();
  if (token.startsWith("'") && token.endsWith("'")) token = token.slice(1, -1).replace(/'\\''/g, "'");
  return token;
}

const env = { ...process.env };
const args = [];
let java = null;
for (const raw of lines.slice(start + 1, end)) {
  const token = unquote(raw);
  if (!token) continue;
  if (!java) {
    const eq = token.indexOf("=");
    if (eq > 0 && /^[A-Za-z_][A-Za-z0-9_]*$/.test(token.slice(0, eq))) {
      env[token.slice(0, eq)] = token.slice(eq + 1);
      continue;
    }
    java = token;
    continue;
  }
  args.push(token);
}
// The driver's keep-alive file is gone once the dry run exits; the builder
// would quit immediately if pointed at it.
const keepalive = args.indexOf("-keepalive");
if (keepalive >= 0) args.splice(keepalive, 2);
// "--jvm=<flag>" extras are builder JVM flags and must precede --module-path.
const jvmFlags = extra.filter((e) => e.startsWith("--jvm=")).map((e) => e.slice(6));
const hosted = extra.filter((e) => !e.startsWith("--jvm="));
args.splice(args.indexOf("--module-path"), 0, ...jvmFlags);
args.push(...hosted);

// Java @argfiles treat backslash as an escape inside quotes, so double them.
const argfile = join(dirname(dryRunPath), "builder.args");
writeFileSync(argfile, args.map((a) => `"${a.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`).join("\n"));
console.log(`java: ${java}\nargs: ${args.length} (+${extra.length} extra) -> ${argfile}`);
const result = spawnSync(java, [`@${argfile}`], { env, stdio: "inherit" });
process.exit(result.status ?? 1);

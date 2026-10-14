// Minimal static file server for local testing (loopback only).
// Usage: node tools/static-server.mjs <root> [port] [--mount /prefix=dir ...]
// Mount directories may use environment variables: --mount /assets=%APPDATA%/PrismLauncher/assets
import { createServer } from "node:http";
import { createReadStream, statSync } from "node:fs";
import { extname, join, normalize, resolve, sep } from "node:path";

const args = process.argv.slice(2);
const positional = args.filter((a, i) => !a.startsWith("--") && !(args[i - 1] === "--mount"));
const root = resolve(positional[0] || ".");
const port = Number(positional[1] || process.env.PORT || 4173);
const mounts = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--mount") {
    const [prefix, dir] = args[++i].split("=");
    const expanded = dir.replace(/%([A-Za-z_][A-Za-z0-9_]*)%/g, (m, name) => process.env[name] ?? m);
    mounts.push({ prefix: prefix.replace(/\/$/, "") + "/", dir: resolve(expanded) });
  }
}
const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".wasm": "application/wasm",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".txt": "text/plain; charset=utf-8",
};

function resolvePath(pathname) {
  for (const m of mounts) {
    if (pathname.startsWith(m.prefix)) return { base: m.dir, rel: pathname.slice(m.prefix.length) };
  }
  return { base: root, rel: pathname };
}

createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  const { base, rel } = resolvePath(decodeURIComponent(url.pathname));
  let path = normalize(join(base, rel));
  if (path !== base && !path.startsWith(base + sep)) {
    res.writeHead(403).end();
    return;
  }
  try {
    if (statSync(path).isDirectory()) path = join(path, "index.html");
    const size = statSync(path).size;
    res.writeHead(200, {
      "content-type": types[extname(path)] || "application/octet-stream",
      "content-length": size,
      "cache-control": "no-store",
      // Cross-origin isolation enables SharedArrayBuffer and precise timers.
      "cross-origin-opener-policy": "same-origin",
      "cross-origin-embedder-policy": "require-corp",
    });
    createReadStream(path).pipe(res);
  } catch {
    res.writeHead(404).end("not found");
  }
}).listen(port, "127.0.0.1", () => {
  console.log(`serving ${root} on http://127.0.0.1:${port}`);
  for (const m of mounts) console.log(`  ${m.prefix} -> ${m.dir}`);
});

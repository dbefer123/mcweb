// Downloads pinned third-party jars (Maven Central) used by the web runtime into .cache/deps.
// Each download is verified against Maven's published SHA-1 before it is kept.
import { mkdirSync, existsSync, writeFileSync, readFileSync, renameSync } from "node:fs";
import { createHash } from "node:crypto";
import { join } from "node:path";
import { ROOT } from "./config.mjs";

export const DEPS = [
  // Pure-Java zlib (BSD-3-Clause); backs java.util.zip.Inflater/Deflater in the WasmGC image.
  { group: "com.jcraft", artifact: "jzlib", version: "1.1.3" },
];

export const DEPS_DIR = join(ROOT, ".cache", "deps");

export function depPath(dep) {
  return join(DEPS_DIR, `${dep.artifact}-${dep.version}.jar`);
}

async function fetchBuffer(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  return Buffer.from(await res.arrayBuffer());
}

export async function fetchDeps() {
  mkdirSync(DEPS_DIR, { recursive: true });
  for (const dep of DEPS) {
    const out = depPath(dep);
    if (existsSync(out)) continue;
    const base = `https://repo1.maven.org/maven2/${dep.group.replace(/\./g, "/")}/${dep.artifact}/${dep.version}/${dep.artifact}-${dep.version}.jar`;
    const [jar, sha1] = await Promise.all([fetchBuffer(base), fetchBuffer(base + ".sha1")]);
    const expected = sha1.toString("utf8").trim().split(/\s+/)[0];
    const actual = createHash("sha1").update(jar).digest("hex");
    if (actual !== expected) throw new Error(`${dep.artifact}: sha1 mismatch ${actual} != ${expected}`);
    writeFileSync(out + ".part", jar);
    renameSync(out + ".part", out);
    console.log(`fetched ${dep.group}:${dep.artifact}:${dep.version} (${jar.length} bytes, sha1 ok)`);
  }
  return DEPS.map(depPath);
}

if (import.meta.url === `file:///${process.argv[1].replace(/\\/g, "/")}`) {
  const paths = await fetchDeps();
  console.log(paths.join("\n"));
}

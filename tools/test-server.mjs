// Local Minecraft server for multiplayer tests (Phase 5): Mojang's dedicated server in
// offline mode on localhost:25565, which the relay allows by default.
//
//   node tools/test-server.mjs [--version 26.3] [--port 25565] [--accept-eula]
//
// Downloads server.jar from Mojang (hash-checked against the version manifest) into
// build/test-server/<version>/ and writes server.properties. Starting the server requires
// accepting Mojang's EULA (https://aka.ms/MinecraftEULA): pass --accept-eula yourself to
// record that you accept it; the script never accepts it for you.
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { spawn } from "node:child_process";
import { BUILD, JAVA, TMP } from "./config.mjs";

const argv = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};
const version = opt("--version", "26.3");
const port = Number(opt("--port", "25565"));
const acceptEula = argv.includes("--accept-eula");

const dir = join(BUILD, "test-server", version);
mkdirSync(dir, { recursive: true });
const sha1 = (buf) => createHash("sha1").update(buf).digest("hex");

async function get(url, what) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${what}: HTTP ${res.status} from ${url}`);
  return Buffer.from(await res.arrayBuffer());
}

// 1. server.jar, verified against Mojang's manifest.
const jar = join(dir, "server.jar");
const manifest = JSON.parse(await get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", "version manifest"));
const entry = manifest.versions.find((v) => v.id === version);
if (!entry) throw new Error(`version ${version} is not in Mojang's version manifest`);
const versionJson = await get(entry.url, `version ${version}`);
if (sha1(versionJson) !== entry.sha1) throw new Error(`version ${version} metadata failed its hash check`);
const server = JSON.parse(versionJson).downloads?.server;
if (!server) throw new Error(`version ${version} has no server download`);
if (!existsSync(jar) || sha1(readFileSync(jar)) !== server.sha1) {
  console.log(`downloading server.jar ${version} (${(server.size / 1048576).toFixed(1)} MiB) from ${server.url}`);
  const data = await get(server.url, "server.jar");
  if (data.length !== server.size || sha1(data) !== server.sha1) throw new Error("server.jar failed its hash check");
  writeFileSync(jar, data);
}
console.log(`server.jar ${version} ok (sha1 ${server.sha1})`);

// 2. Offline mode: the web client has no Microsoft sign-in yet (Phase 6). Keep existing
// properties the server added; force the ones the test depends on.
const propsPath = join(dir, "server.properties");
const props = new Map();
if (existsSync(propsPath)) {
  for (const line of readFileSync(propsPath, "utf8").split(/\r?\n/)) {
    const m = /^([^#=]+)=(.*)$/.exec(line);
    if (m) props.set(m[1], m[2]);
  }
}
for (const [k, v] of Object.entries({
  "online-mode": "false",
  "enforce-secure-profile": "false",
  "server-ip": "127.0.0.1",
  "server-port": String(port),
  "motd": "Minecraft Web test server",
  "view-distance": "8",
  "simulation-distance": "6",
  "spawn-protection": "0",
  // Loopback-only test server: anyone who can reach it is on this machine.
  "white-list": "false",
  "enforce-whitelist": "false",
})) props.set(k, v);
writeFileSync(propsPath, [...props].map(([k, v]) => `${k}=${v}`).join("\n") + "\n");

// 3. EULA: only on the user's explicit flag.
const eulaPath = join(dir, "eula.txt");
const eulaAccepted = existsSync(eulaPath) && /^eula=true$/m.test(readFileSync(eulaPath, "utf8"));
if (!eulaAccepted) {
  if (!acceptEula) {
    console.log("\nThe server needs Mojang's EULA accepted: https://aka.ms/MinecraftEULA");
    console.log("If you accept it, run again with --accept-eula.");
    process.exit(2);
  }
  writeFileSync(eulaPath, `# Accepted by the user with --accept-eula on ${new Date().toISOString()}\n` +
    "# https://aka.ms/MinecraftEULA\neula=true\n");
}

// 4. Run it (Ctrl+C or "stop" to quit). Don't run it during an image build: memory.
console.log(`starting the server on 127.0.0.1:${port} (offline mode) in ${dir}`);
// A temp dir without spaces: Java's Windows selector makes a Unix-domain socket there, and
// the default (under "C:\Users\<name with space>") fails with "Invalid argument: connect".
mkdirSync(TMP, { recursive: true });
const child = spawn(JAVA, ["-Xms1G", "-Xmx2G", `-Djava.io.tmpdir=${TMP}`, "-jar", "server.jar", "nogui"], {
  cwd: dir,
  stdio: "inherit",
  env: { ...process.env, TEMP: TMP, TMP },
});
child.on("exit", (code) => process.exit(code ?? 0));

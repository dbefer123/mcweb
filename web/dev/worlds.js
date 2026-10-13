// World export/import for the browser storage (OPFS mcweb/game/saves, kept in sync with
// the game by platform/web/.../storage/PersistentStorage.java). Worlds are exchanged as
// ordinary zip files: the same layout as a desktop .minecraft/saves folder.

const SAVES = ["mcweb", "game", "saves"];

async function savesDir() {
  let dir = await navigator.storage.getDirectory();
  for (const name of SAVES) dir = await dir.getDirectoryHandle(name, { create: true });
  return dir;
}

/** Names of the worlds in browser storage. */
export async function listWorlds() {
  const names = [];
  for await (const [name, handle] of (await savesDir()).entries()) {
    if (handle.kind === "directory") names.push(name);
  }
  return names.sort((a, b) => a.localeCompare(b));
}

async function collect(dir, prefix, out) {
  for await (const [name, handle] of dir.entries()) {
    const path = `${prefix}${name}`;
    if (handle.kind === "directory") await collect(handle, `${path}/`, out);
    else if (name !== "session.lock") out.push({ name: path, data: new Uint8Array(await (await handle.getFile()).arrayBuffer()) });
  }
}

/** Downloads a world as <name>.zip. Export from the title screen for a complete save. */
export async function exportWorld(name) {
  const files = [];
  await collect(await (await savesDir()).getDirectoryHandle(name), `${name}/`, files);
  const blob = zip(files);
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `${name}.zip`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
  return { files: files.length, bytes: blob.size };
}

/**
 * Stores the world in a zip file under a free name. The world folder is the one holding
 * level.dat (at the top of the zip or one level down). The game sees it after a reload.
 */
export async function importWorld(file) {
  const entries = await unzip(new Uint8Array(await file.arrayBuffer()));
  const levelDat = entries.map((e) => e.name).filter((n) => /(^|\/)level\.dat$/.test(n))
    .sort((a, b) => a.split("/").length - b.split("/").length)[0];
  if (!levelDat) throw new Error("no level.dat in this zip: not a Minecraft world");
  const root = levelDat.slice(0, -"level.dat".length); // "" or "Folder/"
  const base = root ? root.slice(0, -1).split("/").pop() : file.name.replace(/\.zip$/i, "");
  const existing = new Set(await listWorlds());
  let name = base.replace(/[\\/:*?"<>|]/g, "_") || "Imported World";
  for (let i = 2; existing.has(name); i++) name = `${base} (${i})`;

  const saves = await savesDir();
  const world = await saves.getDirectoryHandle(name, { create: true });
  let count = 0;
  for (const entry of entries) {
    if (!entry.name.startsWith(root) || entry.name.endsWith("/")) continue;
    const parts = entry.name.slice(root.length).split("/");
    if (parts.some((p) => p === ".." || p === "")) continue;
    let dir = world;
    for (const part of parts.slice(0, -1)) dir = await dir.getDirectoryHandle(part, { create: true });
    const writable = await (await dir.getFileHandle(parts[parts.length - 1], { create: true })).createWritable();
    await writable.write(await entry.data());
    await writable.close();
    count++;
  }
  return { name, files: count };
}

/** Wires the dev page's Worlds panel (index.html). */
export function attachWorldsPanel() {
  const list = document.getElementById("world-list");
  const status = document.getElementById("world-status");
  const exportButton = document.getElementById("world-export");
  const input = document.getElementById("world-import");
  const say = (text) => (status.textContent = text);

  async function refresh() {
    const names = await listWorlds();
    list.replaceChildren(...names.map((n) => new Option(n, n)));
    exportButton.disabled = names.length === 0;
    if (!names.length) say("No worlds in browser storage yet.");
  }

  document.getElementById("world-refresh").addEventListener("click", () => refresh().catch((e) => say(e.message)));
  exportButton.addEventListener("click", async () => {
    try {
      say(`Exporting ${list.value}…`);
      const { files, bytes } = await exportWorld(list.value);
      say(`Exported ${list.value}: ${files} files, ${(bytes / 1048576).toFixed(1)} MiB.`);
    } catch (e) {
      say(`Export failed: ${e.message}`);
    }
  });
  input.addEventListener("change", async () => {
    const file = input.files[0];
    input.value = "";
    if (!file) return;
    try {
      say(`Importing ${file.name}…`);
      const { name, files } = await importWorld(file);
      await refresh();
      list.value = name;
      say(`Imported "${name}" (${files} files). Reload the page to play it.`);
    } catch (e) {
      say(`Import failed: ${e.message}`);
    }
  });
  refresh().catch((e) => say(`Browser storage unavailable: ${e.message}`));
}

// ---- zip (stored entries; region files are compressed already) ----

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(data) {
  let c = 0xffffffff;
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

export function zip(files) {
  const encoder = new TextEncoder();
  const parts = [];
  const central = [];
  let offset = 0;
  for (const { name, data } of files) {
    const nameBytes = encoder.encode(name);
    const crc = crc32(data);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(4, 20, true);
    local.setUint16(6, 0x0800, true); // UTF-8 names
    local.setUint16(8, 0, true); // stored
    local.setUint32(14, crc, true);
    local.setUint32(18, data.length, true);
    local.setUint32(22, data.length, true);
    local.setUint16(26, nameBytes.length, true);
    parts.push(local, nameBytes, data);

    const entry = new DataView(new ArrayBuffer(46));
    entry.setUint32(0, 0x02014b50, true);
    entry.setUint16(4, 20, true);
    entry.setUint16(6, 20, true);
    entry.setUint16(8, 0x0800, true);
    entry.setUint32(16, crc, true);
    entry.setUint32(20, data.length, true);
    entry.setUint32(24, data.length, true);
    entry.setUint16(28, nameBytes.length, true);
    entry.setUint32(42, offset, true);
    central.push(entry, nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }
  const centralSize = central.reduce((n, p) => n + p.byteLength, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, files.length, true);
  end.setUint16(10, files.length, true);
  end.setUint32(12, centralSize, true);
  end.setUint32(16, offset, true);
  if (offset > 0xffffffff || files.length > 0xffff) throw new Error("world too large for a zip without ZIP64");
  return new Blob([...parts, ...central, end], { type: "application/zip" });
}

/** Entries of a zip as { name, data(): Promise<Uint8Array> } (stored or deflated). */
export async function unzip(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 65535); i--) {
    if (view.getUint32(i, true) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error("not a zip file");
  const count = view.getUint16(eocd + 10, true);
  let p = view.getUint32(eocd + 16, true);
  if (p === 0xffffffff) throw new Error("ZIP64 archives are not supported");
  const decoder = new TextDecoder();
  const entries = [];
  for (let i = 0; i < count; i++) {
    if (view.getUint32(p, true) !== 0x02014b50) throw new Error("corrupt zip directory");
    const method = view.getUint16(p + 10, true);
    const compressed = view.getUint32(p + 20, true);
    const nameLength = view.getUint16(p + 28, true);
    const extraLength = view.getUint16(p + 30, true);
    const commentLength = view.getUint16(p + 32, true);
    const local = view.getUint32(p + 42, true);
    // Windows tools (Compress-Archive) store "\" separators.
    const name = decoder.decode(bytes.subarray(p + 46, p + 46 + nameLength)).replace(/\\/g, "/");
    p += 46 + nameLength + extraLength + commentLength;
    const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
    const raw = bytes.subarray(start, start + compressed);
    entries.push({
      name,
      async data() {
        if (method === 0) return raw;
        if (method !== 8) throw new Error(`unsupported zip compression method ${method} (${name})`);
        const stream = new Blob([raw]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
        return new Uint8Array(await new Response(stream).arrayBuffer());
      },
    });
  }
  return entries;
}

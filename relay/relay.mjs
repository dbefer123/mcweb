// WebSocket → TCP relay for the browser client: the page can't open TCP sockets, so each
// Minecraft connection is a WebSocket to this relay, which opens the TCP connection and
// passes bytes both ways (binary frames carry the raw stream).
//
//   node relay/relay.mjs [--port 25580] [--origin http://localhost:4173]... [--allow host:port]... [--allow-lan]
//
// Not an open proxy:
//   - listens on 127.0.0.1 only;
//   - accepts WebSockets only from the listed page origins (browsers always send Origin);
//   - refuses destinations in private, loopback and link-local ranges unless allowed
//     (--allow host:port, default localhost:25565; --allow-lan allows all private ranges).
// A target is ws://127.0.0.1:<port>/connect?host=<host>&port=<port>. Like the game, port
// 25565 first looks for a _minecraft._tcp SRV record.

import { createServer } from "node:http";
import { connect } from "node:net";
import { createHash } from "node:crypto";
import { promises as dns } from "node:dns";
import { isIP } from "node:net";

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const options = (name) => args.flatMap((a, i) => (a === name ? [args[i + 1]] : []));

const PORT = Number(option("--port", 25580));
const ORIGINS = new Set(options("--origin").length ? options("--origin") : ["http://localhost:4173", "http://127.0.0.1:4173"]);
const ALLOWED = new Set((options("--allow").length ? options("--allow") : ["localhost:25565"]).map((s) => s.toLowerCase()));
const ALLOW_LAN = args.includes("--allow-lan");
const GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const MAX_FRAME = 4 * 1024 * 1024;

function log(...parts) {
  console.log(new Date().toISOString().slice(11, 19), ...parts);
}

// ---- destination policy ----

function isPrivate(address) {
  if (isIP(address) === 4) {
    const [a, b] = address.split(".").map(Number);
    return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31)
      || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224;
  }
  const v6 = address.toLowerCase();
  if (v6.startsWith("::ffff:")) return isPrivate(v6.slice(7));
  return v6 === "::1" || v6 === "::" || v6.startsWith("fc") || v6.startsWith("fd") || v6.startsWith("fe8")
    || v6.startsWith("fe9") || v6.startsWith("fea") || v6.startsWith("feb") || v6.startsWith("ff");
}

async function resolveTarget(host, port) {
  if (port === 25565 && !isIP(host)) {
    try {
      const [srv] = (await dns.resolveSrv(`_minecraft._tcp.${host}`)).sort((a, b) => a.priority - b.priority || b.weight - a.weight);
      if (srv) {
        host = srv.name;
        port = srv.port;
      }
    } catch {
      // no SRV record: connect directly
    }
  }
  // Every address: "localhost" is ::1 first on Windows, while a server may listen on
  // 127.0.0.1 only.
  const addresses = (await dns.lookup(host, { all: true })).map((a) => a.address);
  return { host, port, addresses };
}

function permitted(requested, target, address) {
  if (!isPrivate(address)) return true;
  if (ALLOW_LAN) return true;
  const keys = [`${requested.host}:${requested.port}`, `${target.host}:${target.port}`, `${address}:${target.port}`];
  return keys.some((k) => ALLOWED.has(k.toLowerCase()));
}

/** Connects to the first address that accepts; rejects with the last error. */
function connectFirst(addresses, port) {
  return new Promise((resolve, reject) => {
    const attempt = (i, lastError) => {
      if (i >= addresses.length) return reject(lastError);
      const tcp = connect({ host: addresses[i], port });
      const onError = (e) => attempt(i + 1, e);
      tcp.once("error", onError);
      tcp.once("connect", () => {
        tcp.off("error", onError);
        resolve({ tcp, address: addresses[i] });
      });
    };
    attempt(0, new Error("no address"));
  });
}

// ---- WebSocket framing (RFC 6455, server side) ----

function frame(opcode, payload) {
  const length = payload.length;
  const header = length < 126 ? Buffer.alloc(2) : length < 65536 ? Buffer.alloc(4) : Buffer.alloc(10);
  header[0] = 0x80 | opcode;
  if (length < 126) {
    header[1] = length;
  } else if (length < 65536) {
    header[1] = 126;
    header.writeUInt16BE(length, 2);
  } else {
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(length), 2);
  }
  return Buffer.concat([header, payload]);
}

function closeFrame(code, reason) {
  const text = Buffer.from(reason).subarray(0, 120);
  const payload = Buffer.alloc(2 + text.length);
  payload.writeUInt16BE(code, 0);
  text.copy(payload, 2);
  return frame(0x8, payload);
}

/** Parses client frames from a growing buffer; calls onFrame(opcode, payload, fin). */
function createParser(onFrame, onError) {
  let buffered = Buffer.alloc(0);
  return (chunk) => {
    buffered = buffered.length ? Buffer.concat([buffered, chunk]) : chunk;
    while (buffered.length >= 2) {
      const fin = (buffered[0] & 0x80) !== 0;
      const opcode = buffered[0] & 0x0f;
      const masked = (buffered[1] & 0x80) !== 0;
      let length = buffered[1] & 0x7f;
      let offset = 2;
      if (length === 126) {
        if (buffered.length < 4) return;
        length = buffered.readUInt16BE(2);
        offset = 4;
      } else if (length === 127) {
        if (buffered.length < 10) return;
        const big = buffered.readBigUInt64BE(2);
        if (big > BigInt(MAX_FRAME)) return onError("frame too large");
        length = Number(big);
        offset = 10;
      }
      if (!masked) return onError("client frames must be masked");
      if (length > MAX_FRAME) return onError("frame too large");
      if (buffered.length < offset + 4 + length) return;
      const mask = buffered.subarray(offset, offset + 4);
      const payload = Buffer.from(buffered.subarray(offset + 4, offset + 4 + length));
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      buffered = buffered.subarray(offset + 4 + length);
      onFrame(opcode, payload, fin);
    }
  };
}

// ---- server ----

const server = createServer((req, res) => {
  res.writeHead(426, { "content-type": "text/plain" });
  res.end("Minecraft WebSocket relay: connect with a WebSocket to /connect?host=...&port=...\n");
});

server.on("upgrade", async (req, socket) => {
  const fail = (status, text) => {
    socket.end(`HTTP/1.1 ${status}\r\ncontent-type: text/plain\r\nconnection: close\r\n\r\n${text}\n`);
    log("refused:", text);
  };
  const url = new URL(req.url, "http://relay");
  const origin = req.headers.origin;
  if (!origin || !ORIGINS.has(origin)) return fail("403 Forbidden", `origin not allowed: ${origin ?? "(none)"}`);
  if (url.pathname !== "/connect" || req.headers.upgrade?.toLowerCase() !== "websocket") return fail("400 Bad Request", "expected a WebSocket to /connect");
  const key = req.headers["sec-websocket-key"];
  if (!key) return fail("400 Bad Request", "missing Sec-WebSocket-Key");

  const host = (url.searchParams.get("host") ?? "").trim();
  const port = Number(url.searchParams.get("port") ?? 25565);
  if (!/^[a-z0-9.\-_:[\]]{1,253}$/i.test(host) || !Number.isInteger(port) || port < 1 || port > 65535) {
    return fail("400 Bad Request", "invalid host or port");
  }

  // An error while resolving/connecting below must not crash the relay (no listener yet).
  socket.on("error", () => {});
  const accept = createHash("sha1").update(key + GUID).digest("base64");
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nupgrade: websocket\r\nconnection: Upgrade\r\nsec-websocket-accept: ${accept}\r\n\r\n`);
  socket.setNoDelay(true);

  const closeWith = (code, reason) => {
    if (!socket.destroyed) socket.end(closeFrame(code, reason));
  };

  let target;
  try {
    target = await resolveTarget(host, port);
  } catch (e) {
    log(`${host}:${port}: unknown host`);
    return closeWith(4004, `Unknown host ${host}`);
  }
  // Only addresses that pass the private-range check are dialed (each one is checked, so a
  // name can't mix a public address with a private one to get through).
  const candidates = target.addresses.filter((a) => permitted({ host, port }, target, a));
  if (!candidates.length) {
    log(`${host}:${port} → ${target.addresses.join(", ")}: private address not allowed`);
    return closeWith(4003, `${host} is a private address; start the relay with --allow ${host}:${port} or --allow-lan`);
  }

  // The browser's data waits in the socket (no 'data' listener yet) until this connects.
  let tcp;
  try {
    let address;
    ({ tcp, address } = await connectFirst(candidates, target.port));
    log(`${host}:${port} → ${target.host}:${target.port} (${address}) connected`);
  } catch (e) {
    log(`${host}:${port}: ${e.code ?? e.message}`);
    return closeWith(4000, `Connection failed: ${e.code ?? e.message}`);
  }
  if (socket.destroyed) return tcp.destroy();
  tcp.setNoDelay(true);
  tcp.on("data", (data) => {
    if (!socket.write(frame(0x2, data))) {
      tcp.pause();
      socket.once("drain", () => tcp.resume());
    }
  });
  tcp.on("error", (e) => {
    log(`${host}:${port}: ${e.code ?? e.message}`);
    closeWith(4000, `Connection failed: ${e.code ?? e.message}`);
  });
  tcp.on("close", () => closeWith(1000, "server closed the connection"));

  let message = [];
  const parse = createParser((opcode, payload, fin) => {
    if (opcode === 0x8) {
      tcp.end();
      if (!socket.destroyed) socket.end(frame(0x8, Buffer.alloc(0)));
    } else if (opcode === 0x9) {
      socket.write(frame(0xa, payload));
    } else if (opcode === 0x2 || opcode === 0x0) {
      message.push(payload);
      if (!fin) return;
      const data = message.length === 1 ? message[0] : Buffer.concat(message);
      message = [];
      if (!tcp.write(data)) {
        socket.pause();
        tcp.once("drain", () => socket.resume());
      }
    }
  }, (reason) => {
    log(`${host}:${port}: protocol error: ${reason}`);
    closeWith(1002, reason);
    tcp.destroy();
  });
  socket.on("data", parse);
  socket.on("close", () => tcp.destroy());
  socket.on("error", () => tcp.destroy());
});

server.listen(PORT, "127.0.0.1", () => {
  log(`relay on ws://127.0.0.1:${PORT}/connect; origins: ${[...ORIGINS].join(", ")}; private targets allowed: ${ALLOW_LAN ? "all (--allow-lan)" : [...ALLOWED].join(", ")}`);
});

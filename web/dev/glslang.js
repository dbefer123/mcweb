// Loader for build/glslang-wasm/glslang.wasm (tools/glslang-wasm): GLSL -> SPIR-V.
// Works in the browser and in Node. The module is a WASI "reactor"; glslang only
// needs stdio-ish imports, so a minimal shim stands in for a WASI runtime.

const ERRNO_SUCCESS = 0;
const ERRNO_BADF = 8;
const ERRNO_NOSYS = 52;

class WasiExit extends Error {
  constructor(code) {
    super(`glslang called proc_exit(${code})`);
    this.code = code;
  }
}

function wasiShim(getMemory, log) {
  const view = () => new DataView(getMemory().buffer);
  let pending = "";
  return {
    environ_sizes_get(countPtr, sizePtr) {
      view().setUint32(countPtr, 0, true);
      view().setUint32(sizePtr, 0, true);
      return ERRNO_SUCCESS;
    },
    environ_get() {
      return ERRNO_SUCCESS;
    },
    clock_time_get(_id, _precision, outPtr) {
      view().setBigUint64(outPtr, BigInt(Math.round(performance.now() * 1e6)), true);
      return ERRNO_SUCCESS;
    },
    fd_write(fd, iovs, iovsLen, nwrittenPtr) {
      const dv = view();
      const bytes = new Uint8Array(getMemory().buffer);
      let written = 0;
      for (let i = 0; i < iovsLen; i++) {
        const ptr = dv.getUint32(iovs + i * 8, true);
        const len = dv.getUint32(iovs + i * 8 + 4, true);
        if (fd === 1 || fd === 2) pending += new TextDecoder().decode(bytes.subarray(ptr, ptr + len));
        written += len;
      }
      let nl;
      while ((nl = pending.indexOf("\n")) >= 0) {
        log(pending.slice(0, nl));
        pending = pending.slice(nl + 1);
      }
      dv.setUint32(nwrittenPtr, written, true);
      return ERRNO_SUCCESS;
    },
    fd_close: () => ERRNO_SUCCESS,
    fd_fdstat_get: (fd) => (fd <= 2 ? ERRNO_SUCCESS : ERRNO_BADF),
    fd_fdstat_set_flags: () => ERRNO_NOSYS,
    fd_prestat_get: () => ERRNO_BADF,
    fd_prestat_dir_name: () => ERRNO_BADF,
    fd_read: () => ERRNO_NOSYS,
    fd_seek: () => ERRNO_NOSYS,
    path_open: () => ERRNO_NOSYS,
    proc_exit(code) {
      throw new WasiExit(code);
    },
  };
}

export const STAGE_VERTEX = 0;
export const STAGE_FRAGMENT = 4;
export const STAGE_COMPUTE = 5;

/**
 * @param {BufferSource | WebAssembly.Module} wasm glslang.wasm bytes or a compiled module
 * @returns {Promise<{compile(stage: number, source: string, preamble?: string): {ok: boolean, spirv: Uint32Array | null, log: string}}>}
 */
export async function createGlslang(wasm, log = (line) => console.log(`[glslang] ${line}`)) {
  let memory;
  const imports = { wasi_snapshot_preview1: wasiShim(() => memory, log) };
  const module = wasm instanceof WebAssembly.Module ? wasm : await WebAssembly.compile(wasm);
  const instance = await WebAssembly.instantiate(module, imports);
  const ex = instance.exports;
  memory = ex.memory;
  ex._initialize();
  const encoder = new TextEncoder();
  const decoder = new TextDecoder();

  const putString = (s) => {
    const bytes = encoder.encode(s);
    const ptr = ex.gw_alloc(bytes.length + 1);
    const heap = new Uint8Array(memory.buffer);
    heap.set(bytes, ptr);
    heap[ptr + bytes.length] = 0;
    return ptr;
  };
  const getCString = (ptr) => {
    if (!ptr) return "";
    const heap = new Uint8Array(memory.buffer);
    let end = ptr;
    while (heap[end]) end++;
    return decoder.decode(heap.subarray(ptr, end));
  };

  // shaderc enables these implicitly; renderpearl's sources rely on `#line N "file"`. They
  // must follow #version (glslang puts the preamble before it), keeping line numbers.
  const SHADERC_EXTENSIONS = "#extension GL_GOOGLE_cpp_style_line_directive : enable\n#extension GL_GOOGLE_include_directive : enable\n";
  function withExtensions(source) {
    const m = /^[ \t]*#[ \t]*version[^\n]*\n/m.exec(source);
    if (!m) return SHADERC_EXTENSIONS + source;
    const end = m.index + m[0].length;
    const line = source.slice(0, end).split("\n").length; // number of the line after #version
    return source.slice(0, end) + SHADERC_EXTENSIONS + `#line ${line}\n` + source.slice(end);
  }

  return {
    compile(stage, source, preamble = "") {
      const src = putString(withExtensions(source));
      const pre = putString(preamble);
      try {
        const r = ex.gw_compile(stage, src, pre);
        if (!r) return { ok: false, spirv: null, log: "glslang: out of memory" };
        try {
          const dv = new DataView(memory.buffer);
          const ok = dv.getInt32(r, true) === 1;
          const words = dv.getUint32(r + 4, true);
          const count = dv.getInt32(r + 8, true);
          const logText = getCString(dv.getUint32(r + 12, true));
          const spirv = ok ? new Uint32Array(memory.buffer.slice(words, words + count * 4)) : null;
          return { ok, spirv, log: logText };
        } finally {
          ex.gw_result_free(r);
        }
      } finally {
        ex.gw_free(src);
        ex.gw_free(pre);
      }
    },
  };
}

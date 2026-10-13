// WebGPU host for the game's renderpearl backend (platform/web/.../gpu/WebGpu.java).
//
// Java appends commands to an Int32 stream in the shared `nmem` memory and calls
// exec(addr, count) once per batch; object ids are chosen by Java and index `objs`.
// Opcodes and encodings must match WebGpu.java.

// GpuFormat ordinals (com.mojang.renderpearl.api.GpuFormat) -> WebGPU texture formats.
const TEXTURE_FORMATS = [
  "r8unorm", "r8snorm", "rg8unorm", "rg8snorm", null, null, "rgba8unorm", "rgba8snorm",
  "r16unorm", "r16snorm", "rg16unorm", "rg16snorm", null, null, "rgba16unorm", "rgba16snorm",
  "r8uint", "r8sint", "rg8uint", "rg8sint", null, null, "rgba8uint", "rgba8sint",
  "r16uint", "r16sint", "rg16uint", "rg16sint", null, null, "rgba16uint", "rgba16sint",
  "r32uint", "r32sint", "rg32uint", "rg32sint", null, null, "rgba32uint", "rgba32sint",
  "r16float", "rg16float", null, "rgba16float", "r32float", "rg32float", null, "rgba32float",
  "rgb10a2unorm", "rgb10a2uint", "rg11b10ufloat",
  "depth32float", "depth32float-stencil8", "depth24plus-stencil8", "depth16unorm", "stencil8",
];
// GpuFormat ordinals -> WebGPU vertex formats.
const VERTEX_FORMATS = [
  "unorm8", "snorm8", "unorm8x2", "snorm8x2", null, null, "unorm8x4", "snorm8x4",
  "unorm16", "snorm16", "unorm16x2", "snorm16x2", null, null, "unorm16x4", "snorm16x4",
  "uint8", "sint8", "uint8x2", "sint8x2", null, null, "uint8x4", "sint8x4",
  "uint16", "sint16", "uint16x2", "sint16x2", null, null, "uint16x4", "sint16x4",
  "uint32", "sint32", "uint32x2", "sint32x2", "uint32x3", "sint32x3", "uint32x4", "sint32x4",
  "float16", "float16x2", null, "float16x4", "float32", "float32x2", "float32x3", "float32x4",
  "unorm10-10-10-2", null, null, null, null, null, null, null,
];
const TOPOLOGY = ["point-list", "line-list", "line-strip", "triangle-list", "triangle-strip"];
const COMPARE = ["never", "less", "equal", "less-equal", "greater", "not-equal", "greater-equal", "always"];
const BLEND_FACTOR = ["zero", "one", "src", "one-minus-src", "dst", "one-minus-dst", "src-alpha", "one-minus-src-alpha",
  "dst-alpha", "one-minus-dst-alpha", "constant", "one-minus-constant", "src-alpha-saturated"];
const BLEND_OP = ["add", "subtract", "reverse-subtract", "min", "max"];
const ADDRESS = ["clamp-to-edge", "repeat", "mirror-repeat"];
const FILTER = ["nearest", "linear"];
const VIEW_DIM = ["2d", "2d-array", "cube", "cube-array", "3d"];
const SAMPLE_TYPE = ["float", "unfilterable-float", "depth", "sint", "uint"];
const SAMPLER_TYPE = ["filtering", "non-filtering", "comparison"];
const ASPECT = ["all", "depth-only", "stencil-only"];

const OP = {
  CREATE_BUFFER: 1, DESTROY: 2, WRITE_BUFFER: 3, CREATE_TEXTURE: 4, CREATE_VIEW: 5, CREATE_SAMPLER: 6,
  CREATE_BGL: 7, CREATE_PIPELINE_LAYOUT: 8, CREATE_RENDER_PIPELINE: 9, CREATE_BIND_GROUP: 10,
  BEGIN_PASS: 11, END_PASS: 12, SET_PIPELINE: 13, SET_BIND_GROUP: 14, SET_VERTEX_BUFFER: 15,
  SET_INDEX_BUFFER: 16, SET_SCISSOR: 17, DRAW: 18, DRAW_INDEXED: 19, DRAW_INDEXED_INDIRECT: 20,
  DRAW_INDIRECT: 21, MULTI_DRAW_SEPARATE: 22, MULTI_DRAW_INTERLEAVED: 23, COPY_B2B: 24, COPY_B2T: 25,
  READBACK_TEXTURE: 26, COPY_T2T: 27, SUBMIT: 28, BLIT_CANVAS: 29, READBACK_BUFFER: 30, CLEAR_REGION: 31,
  PUSH_DEBUG_GROUP: 32, POP_DEBUG_GROUP: 33,
};

/** Creates the host; `nmem` is the image's exported WebAssembly.Memory. */
export function createGpuHost({ device, adapter, canvas, log }) {
  let nmem = null;
  const objs = [];
  const labels = [];
  let encoder = null;
  let pass = null;
  let indexFormat = "uint32";
  let indexSize = 4;
  const pendingReadbacks = []; // waiting for the next submit
  const completed = [];        // request ids whose data is in nmem
  const context = canvas.getContext("webgpu");
  const canvasFormat = navigator.gpu.getPreferredCanvasFormat();
  let canvasConfigured = false;

  device.addEventListener("uncapturederror", (e) => log(`[webgpu] ${e.error.message}`));
  device.lost.then((info) => log(`[webgpu] device lost: ${info.reason} ${info.message}`));

  function enc() {
    if (!encoder) encoder = device.createCommandEncoder();
    return encoder;
  }

  // ---- internal pipelines: present blit (with Y flip) and region clear ----
  const blitModule = device.createShaderModule({
    label: "mcweb blit",
    code: `
@group(0) @binding(0) var src: texture_2d<f32>;
@group(0) @binding(1) var samp: sampler;
struct V { @builtin(position) pos: vec4<f32>, @location(0) uv: vec2<f32> }
@vertex fn vs(@builtin(vertex_index) i: u32) -> V {
  let p = vec2<f32>(f32((i << 1u) & 2u), f32(i & 2u));
  var v: V;
  v.pos = vec4<f32>(p * 2.0 - 1.0, 0.0, 1.0);
  v.uv = vec2<f32>(p.x, p.y); // framebuffer rows are stored bottom-up (Vulkan layout): flip
  return v;
}
@fragment fn fs(v: V) -> @location(0) vec4<f32> {
  return vec4<f32>(textureSampleLevel(src, samp, v.uv, 0.0).rgb, 1.0);
}`,
  });
  const blitPipelines = new Map();
  const blitSampler = device.createSampler({ magFilter: "nearest", minFilter: "nearest" });
  function blitPipeline(format) {
    let p = blitPipelines.get(format);
    if (!p) {
      p = device.createRenderPipeline({
        label: "mcweb blit",
        layout: "auto",
        vertex: { module: blitModule, entryPoint: "vs" },
        fragment: { module: blitModule, entryPoint: "fs", targets: [{ format }] },
        primitive: { topology: "triangle-list" },
      });
      blitPipelines.set(format, p);
    }
    return p;
  }

  const clearModule = device.createShaderModule({
    label: "mcweb clear",
    code: `
struct C { color: vec4<f32>, depth: vec4<f32> }
@group(0) @binding(0) var<uniform> c: C;
@vertex fn vs(@builtin(vertex_index) i: u32) -> @builtin(position) vec4<f32> {
  let p = vec2<f32>(f32((i << 1u) & 2u), f32(i & 2u));
  return vec4<f32>(p * 2.0 - 1.0, c.depth.x, 1.0);
}
@fragment fn fs() -> @location(0) vec4<f32> { return c.color; }
@fragment fn fsDepth() { }`,
  });
  const clearPipelines = new Map();
  const clearUniforms = device.createBuffer({ size: 256 * 64, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST });
  let clearSlot = 0;
  function clearPipeline(colorFormat, depthFormat) {
    const key = `${colorFormat}|${depthFormat}`;
    let p = clearPipelines.get(key);
    if (!p) {
      p = device.createRenderPipeline({
        label: "mcweb clear",
        layout: "auto",
        vertex: { module: clearModule, entryPoint: "vs" },
        fragment: colorFormat
          ? { module: clearModule, entryPoint: "fs", targets: [{ format: colorFormat }] }
          : { module: clearModule, entryPoint: "fsDepth", targets: [] },
        primitive: { topology: "triangle-list" },
        ...(depthFormat ? { depthStencil: { format: depthFormat, depthWriteEnabled: true, depthCompare: "always" } } : {}),
      });
      clearPipelines.set(key, p);
    }
    return p;
  }

  function destroy(id) {
    const o = objs[id];
    if (o && typeof o.destroy === "function" && !(o instanceof GPUTextureView)) o.destroy();
    objs[id] = undefined;
    labels[id] = undefined;
  }

  function exec(addr, count) {
    const buf = nmem.buffer;
    const w = new Int32Array(buf, addr, count);
    const f = new Float32Array(buf, addr, count);
    let p = 0;
    while (p < count) {
      const op = w[p++];
      switch (op) {
        case OP.CREATE_BUFFER: {
          const id = w[p++], size = w[p++], usage = w[p++];
          objs[id] = device.createBuffer({ size, usage, label: labels[id] });
          break;
        }
        case OP.DESTROY:
          destroy(w[p++]);
          break;
        case OP.WRITE_BUFFER: {
          const id = w[p++], dst = w[p++], src = w[p++] >>> 0, size = w[p++];
          device.queue.writeBuffer(objs[id], dst, nmem.buffer, src, size);
          break;
        }
        case OP.CREATE_TEXTURE: {
          const id = w[p++], format = TEXTURE_FORMATS[w[p++]], dim = w[p++] === 1 ? "3d" : "2d";
          const width = w[p++], height = w[p++], depth = w[p++], mips = w[p++], usage = w[p++];
          objs[id] = device.createTexture({ size: [width, height, depth], format, dimension: dim, mipLevelCount: mips, usage, label: labels[id] });
          break;
        }
        case OP.CREATE_VIEW: {
          const id = w[p++], tex = objs[w[p++]], dimension = VIEW_DIM[w[p++]];
          const baseMipLevel = w[p++], mipLevelCount = w[p++], baseArrayLayer = w[p++], arrayLayerCount = w[p++];
          const aspect = ASPECT[w[p++]];
          objs[id] = tex.createView({ dimension, baseMipLevel, mipLevelCount, baseArrayLayer, arrayLayerCount, aspect });
          break;
        }
        case OP.CREATE_SAMPLER: {
          const id = w[p++];
          const desc = {
            addressModeU: ADDRESS[w[p++]], addressModeV: ADDRESS[w[p++]],
            minFilter: FILTER[w[p++]], magFilter: FILTER[w[p++]], mipmapFilter: FILTER[w[p++]],
            maxAnisotropy: w[p++], lodMaxClamp: f[p++],
          };
          const cmp = w[p++];
          if (cmp >= 0) desc.compare = COMPARE[cmp];
          if (desc.maxAnisotropy > 1 && (desc.minFilter !== "linear" || desc.magFilter !== "linear" || desc.mipmapFilter !== "linear")) {
            desc.maxAnisotropy = 1; // WebGPU requires all-linear filtering for anisotropy
          }
          objs[id] = device.createSampler(desc);
          break;
        }
        case OP.CREATE_BGL: {
          const id = w[p++], n = w[p++];
          const entries = [];
          for (let i = 0; i < n; i++) {
            const binding = w[p++], visibility = w[p++], kind = w[p++], a = w[p++], b = w[p++];
            const e = { binding, visibility };
            if (kind === 0) e.buffer = { type: "uniform", hasDynamicOffset: a === 1 };
            else if (kind === 1) e.buffer = { type: "read-only-storage", hasDynamicOffset: a === 1 };
            else if (kind === 2) e.texture = { sampleType: SAMPLE_TYPE[a], viewDimension: VIEW_DIM[b] };
            else e.sampler = { type: SAMPLER_TYPE[a] };
            entries.push(e);
          }
          objs[id] = device.createBindGroupLayout({ entries, label: labels[id] });
          break;
        }
        case OP.CREATE_PIPELINE_LAYOUT: {
          const id = w[p++], n = w[p++];
          const bindGroupLayouts = [];
          for (let i = 0; i < n; i++) bindGroupLayouts.push(objs[w[p++]]);
          objs[id] = device.createPipelineLayout({ bindGroupLayouts });
          break;
        }
        case OP.CREATE_RENDER_PIPELINE: {
          const id = w[p++], layout = objs[w[p++]], vs = objs[w[p++]], fs = objs[w[p++]];
          const topology = TOPOLOGY[w[p++]], cullMode = w[p++] ? "back" : "none", frontFace = w[p++] ? "cw" : "ccw";
          const depthFormat = w[p++];
          const depthWrite = w[p++] === 1, depthCompare = COMPARE[w[p++]], depthBias = w[p++];
          const depthBiasSlopeScale = f[p++];
          const nvb = w[p++];
          const buffers = [];
          for (let i = 0; i < nvb; i++) {
            const stride = w[p++], step = w[p++], na = w[p++];
            if (stride < 0) {
              buffers.push(null);
              p += na * 3;
              continue;
            }
            const attributes = [];
            for (let j = 0; j < na; j++) {
              const format = VERTEX_FORMATS[w[p++]], offset = w[p++], shaderLocation = w[p++];
              attributes.push({ format, offset, shaderLocation });
            }
            buffers.push({ arrayStride: stride, stepMode: step ? "instance" : "vertex", attributes });
          }
          const nt = w[p++];
          const targets = [];
          for (let i = 0; i < nt; i++) {
            const fmt = w[p++], writeMask = w[p++], blend = w[p++];
            const cs = w[p++], cd = w[p++], co = w[p++], as = w[p++], ad = w[p++], ao = w[p++];
            if (fmt < 0) {
              targets.push(null);
              continue;
            }
            const t = { format: TEXTURE_FORMATS[fmt], writeMask };
            if (blend) {
              t.blend = {
                color: { srcFactor: BLEND_FACTOR[cs], dstFactor: BLEND_FACTOR[cd], operation: BLEND_OP[co] },
                alpha: { srcFactor: BLEND_FACTOR[as], dstFactor: BLEND_FACTOR[ad], operation: BLEND_OP[ao] },
              };
            }
            targets.push(t);
          }
          const desc = {
            label: labels[id],
            layout,
            vertex: { module: vs, entryPoint: "main", buffers },
            fragment: { module: fs, entryPoint: "main", targets },
            primitive: { topology, cullMode, frontFace },
          };
          if (depthFormat >= 0) {
            desc.depthStencil = {
              format: TEXTURE_FORMATS[depthFormat], depthWriteEnabled: depthWrite, depthCompare,
              depthBias, depthBiasSlopeScale,
            };
          }
          device.pushErrorScope("validation");
          objs[id] = device.createRenderPipeline(desc);
          device.popErrorScope().then((e) => e && log(`[webgpu] pipeline ${labels[id]}: ${e.message}`));
          break;
        }
        case OP.CREATE_BIND_GROUP: {
          const id = w[p++], layout = objs[w[p++]], n = w[p++];
          const entries = [];
          for (let i = 0; i < n; i++) {
            const binding = w[p++], kind = w[p++], res = objs[w[p++]], offset = w[p++], size = w[p++];
            if (kind === 0) entries.push({ binding, resource: { buffer: res, offset, size: size < 0 ? undefined : size } });
            else entries.push({ binding, resource: res });
          }
          objs[id] = device.createBindGroup({ layout, entries });
          break;
        }
        case OP.BEGIN_PASS: {
          const nc = w[p++];
          const colorAttachments = [];
          let width = 0, height = 0;
          for (let i = 0; i < nc; i++) {
            const view = w[p++], load = w[p++];
            const r = f[p++], g = f[p++], b = f[p++], a = f[p++];
            if (view < 0) {
              colorAttachments.push(null);
              continue;
            }
            colorAttachments.push({ view: objs[view], loadOp: load ? "clear" : "load", storeOp: "store", clearValue: { r, g, b, a } });
          }
          const depthView = w[p++], depthLoad = w[p++], depthClear = f[p++], hasStencil = w[p++];
          const vw = w[p++], vh = w[p++], sx = w[p++], sy = w[p++], sw = w[p++], sh = w[p++];
          const desc = { colorAttachments };
          if (depthView >= 0) {
            desc.depthStencilAttachment = {
              view: objs[depthView], depthLoadOp: depthLoad ? "clear" : "load", depthStoreOp: "store", depthClearValue: depthClear,
              ...(hasStencil ? { stencilLoadOp: "load", stencilStoreOp: "store" } : {}),
            };
          }
          pass = enc().beginRenderPass(desc);
          pass.setViewport(0, 0, vw, vh, 0, 1);
          pass.setScissorRect(sx, sy, sw, sh);
          break;
        }
        case OP.END_PASS:
          pass.end();
          pass = null;
          break;
        case OP.SET_PIPELINE:
          pass.setPipeline(objs[w[p++]]);
          break;
        case OP.SET_BIND_GROUP: {
          const index = w[p++], bg = objs[w[p++]], n = w[p++];
          if (n === 0) pass.setBindGroup(index, bg);
          else {
            const offsets = [];
            for (let i = 0; i < n; i++) offsets.push(w[p++]);
            pass.setBindGroup(index, bg, offsets);
          }
          break;
        }
        case OP.SET_VERTEX_BUFFER: {
          const slot = w[p++], b = objs[w[p++]], offset = w[p++], size = w[p++];
          pass.setVertexBuffer(slot, b, offset, size < 0 ? undefined : size);
          break;
        }
        case OP.SET_INDEX_BUFFER: {
          const b = objs[w[p++]], fmt = w[p++], offset = w[p++], size = w[p++];
          indexFormat = fmt ? "uint32" : "uint16";
          indexSize = fmt ? 4 : 2;
          pass.setIndexBuffer(b, indexFormat, offset, size < 0 ? undefined : size);
          break;
        }
        case OP.SET_SCISSOR:
          pass.setScissorRect(w[p++], w[p++], w[p++], w[p++]);
          break;
        case OP.DRAW:
          pass.draw(w[p++], w[p++], w[p++], w[p++]);
          break;
        case OP.DRAW_INDEXED:
          pass.drawIndexed(w[p++], w[p++], w[p++], w[p++], w[p++]);
          break;
        case OP.DRAW_INDEXED_INDIRECT: {
          const b = objs[w[p++]], offset = w[p++], n = w[p++], stride = w[p++];
          for (let i = 0; i < n; i++) pass.drawIndexedIndirect(b, offset + i * stride);
          break;
        }
        case OP.DRAW_INDIRECT: {
          const b = objs[w[p++]], offset = w[p++], n = w[p++], stride = w[p++];
          for (let i = 0; i < n; i++) pass.drawIndirect(b, offset + i * stride);
          break;
        }
        case OP.MULTI_DRAW_SEPARATE: {
          // GL-style glMultiDrawElementsBaseVertex: byte offsets (int64), counts, base vertices.
          const n = w[p++], offs = w[p++] >>> 0, counts = w[p++] >>> 0, bases = w[p++] >>> 0;
          const mem = nmem.buffer;
          const o64 = new BigInt64Array(mem, offs, n);
          const c32 = new Int32Array(mem, counts, n);
          const b32 = new Int32Array(mem, bases, n);
          for (let i = 0; i < n; i++) {
            if (c32[i] > 0) pass.drawIndexed(c32[i], 1, Number(o64[i]) / indexSize, b32[i], 0);
          }
          break;
        }
        case OP.MULTI_DRAW_INTERLEAVED: {
          const n = w[p++], params = w[p++] >>> 0, instances = w[p++], firstInstance = w[p++], indexed = w[p++];
          const d = new Int32Array(nmem.buffer, params, n * (indexed ? 3 : 2));
          if (indexed) {
            for (let i = 0; i < n; i++) pass.drawIndexed(d[i * 3 + 1], instances, d[i * 3], d[i * 3 + 2], firstInstance);
          } else {
            for (let i = 0; i < n; i++) pass.draw(d[i * 2 + 1], instances, d[i * 2], firstInstance);
          }
          break;
        }
        case OP.COPY_B2B: {
          const src = objs[w[p++]], so = w[p++], dst = objs[w[p++]], dof = w[p++], size = w[p++];
          enc().copyBufferToBuffer(src, so, dst, dof, size);
          break;
        }
        case OP.COPY_B2T: {
          const b = objs[w[p++]], offset = w[p++], bytesPerRow = w[p++], rowsPerImage = w[p++];
          const t = objs[w[p++]], mipLevel = w[p++], x = w[p++], y = w[p++], z = w[p++];
          const width = w[p++], height = w[p++], depth = w[p++];
          enc().copyBufferToTexture({ buffer: b, offset, bytesPerRow, rowsPerImage },
            { texture: t, mipLevel, origin: [x, y, z] }, [width, height, depth]);
          break;
        }
        case OP.READBACK_TEXTURE: {
          // Copy a texture region into a padded staging buffer; after the next submit the
          // rows are unpadded into nmem at dstAddr and the request is completed.
          const request = w[p++], t = objs[w[p++]], mipLevel = w[p++], x = w[p++], y = w[p++];
          const width = w[p++], height = w[p++], texelBytes = w[p++], dstAddr = w[p++] >>> 0, aspect = ASPECT[w[p++]];
          const rowBytes = width * texelBytes;
          const padded = Math.ceil(rowBytes / 256) * 256;
          const staging = device.createBuffer({ size: padded * height, usage: GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST });
          enc().copyTextureToBuffer({ texture: t, mipLevel, origin: [x, y, 0], aspect },
            { buffer: staging, bytesPerRow: padded, rowsPerImage: height }, [width, height, 1]);
          pendingReadbacks.push({ request, staging, rowBytes, padded, rows: height, dstAddr });
          break;
        }
        case OP.READBACK_BUFFER: {
          const request = w[p++], b = objs[w[p++]], offset = w[p++], size = w[p++], dstAddr = w[p++] >>> 0;
          const staging = device.createBuffer({ size: Math.ceil(size / 4) * 4, usage: GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST });
          enc().copyBufferToBuffer(b, offset, staging, 0, Math.ceil(size / 4) * 4);
          pendingReadbacks.push({ request, staging, rowBytes: size, padded: size, rows: 1, dstAddr });
          break;
        }
        case OP.COPY_T2T: {
          const src = objs[w[p++]], dst = objs[w[p++]], mipLevel = w[p++];
          const sx = w[p++], sy = w[p++], dx = w[p++], dy = w[p++], width = w[p++], height = w[p++];
          enc().copyTextureToTexture({ texture: src, mipLevel, origin: [sx, sy, 0] }, { texture: dst, mipLevel, origin: [dx, dy, 0] }, [width, height, 1]);
          break;
        }
        case OP.SUBMIT:
          submit();
          break;
        case OP.BLIT_CANVAS: {
          const view = objs[w[p++]];
          if (!canvasConfigured) break;
          const target = context.getCurrentTexture();
          const pl = blitPipeline(canvasFormat);
          const bg = device.createBindGroup({ layout: pl.getBindGroupLayout(0), entries: [{ binding: 0, resource: view }, { binding: 1, resource: blitSampler }] });
          const rp = enc().beginRenderPass({ colorAttachments: [{ view: target.createView(), loadOp: "clear", storeOp: "store", clearValue: [0, 0, 0, 1] }] });
          rp.setPipeline(pl);
          rp.setBindGroup(0, bg);
          rp.draw(3);
          rp.end();
          break;
        }
        case OP.CLEAR_REGION: {
          const colorView = w[p++], colorFormat = w[p++], depthView = w[p++], depthFormat = w[p++];
          const x = w[p++], y = w[p++], width = w[p++], height = w[p++], vw = w[p++], vh = w[p++];
          const r = f[p++], g = f[p++], b = f[p++], a = f[p++], depth = f[p++];
          const cf = colorView >= 0 ? TEXTURE_FORMATS[colorFormat] : null;
          const df = depthView >= 0 ? TEXTURE_FORMATS[depthFormat] : null;
          const slot = (clearSlot++ % 64) * 256;
          device.queue.writeBuffer(clearUniforms, slot, new Float32Array([r, g, b, a, depth, 0, 0, 0]));
          const pl = clearPipeline(cf, df);
          const bg = device.createBindGroup({ layout: pl.getBindGroupLayout(0), entries: [{ binding: 0, resource: { buffer: clearUniforms, offset: slot, size: 32 } }] });
          const desc = { colorAttachments: cf ? [{ view: objs[colorView], loadOp: "load", storeOp: "store" }] : [] };
          if (df) desc.depthStencilAttachment = { view: objs[depthView], depthLoadOp: "load", depthStoreOp: "store" };
          const rp = enc().beginRenderPass(desc);
          rp.setViewport(0, 0, vw, vh, 0, 1);
          rp.setScissorRect(x, y, width, height);
          rp.setPipeline(pl);
          rp.setBindGroup(0, bg);
          rp.draw(3);
          rp.end();
          break;
        }
        case OP.PUSH_DEBUG_GROUP:
          p++;
          break;
        case OP.POP_DEBUG_GROUP:
          break;
        default:
          throw new Error(`webgpu: unknown opcode ${op} at ${p - 1}`);
      }
    }
  }

  function submit() {
    if (pass) throw new Error("webgpu: submit inside a render pass");
    device.queue.submit([enc().finish()]);
    encoder = null;
    const readbacks = pendingReadbacks.splice(0);
    for (const rb of readbacks) {
      rb.staging.mapAsync(GPUMapMode.READ).then(() => {
        const src = new Uint8Array(rb.staging.getMappedRange());
        const dst = new Uint8Array(nmem.buffer);
        for (let row = 0; row < rb.rows; row++) {
          dst.set(src.subarray(row * rb.padded, row * rb.padded + rb.rowBytes), rb.dstAddr + row * rb.rowBytes);
        }
        rb.staging.unmap();
        rb.staging.destroy();
        completed.push(rb.request);
      }, (e) => {
        log(`[webgpu] readback ${rb.request} failed: ${e.message}`);
        completed.push(rb.request);
      });
    }
  }

  return {
    canvasFormat,
    attach(memory) {
      nmem = memory;
    },
    exec,
    setLabel(id, label) {
      labels[id] = label;
    },
    createShaderModule(id, code, label) {
      const module = device.createShaderModule({ code, label });
      objs[id] = module;
      module.getCompilationInfo().then((info) => {
        for (const m of info.messages) {
          if (m.type === "error") log(`[webgpu] ${label}:${m.lineNum}:${m.linePos} ${m.message}`);
        }
      });
    },
    configureCanvas(width, height) {
      if (canvas.width !== width) canvas.width = width;
      if (canvas.height !== height) canvas.height = height;
      context.configure({ device, format: canvasFormat, alphaMode: "opaque" });
      canvasConfigured = true;
    },
    /** Next readback request id whose data has landed in nmem, or -1. */
    nextCompleted() {
      return completed.length ? completed.shift() : -1;
    },
    info(key) {
      if (key === "browser") return navigator.userAgentData?.brands?.map((b) => `${b.brand} ${b.version}`).join(", ") ?? navigator.userAgent;
      return String(adapter.info?.[key] ?? "");
    },
    limit(key) {
      return Number(device.limits[key] ?? 0);
    },
    hasFeature(name) {
      return device.features.has(name);
    },
  };
}

/** Requests the adapter and device with the optional features the backend can use. */
export async function initWebGpu() {
  if (!navigator.gpu) throw new Error("This browser has no WebGPU (use a current Chrome or Edge)");
  const adapter = await navigator.gpu.requestAdapter({ powerPreference: "high-performance" });
  if (!adapter) throw new Error("No WebGPU adapter available");
  const optional = ["float32-blendable", "float32-filterable", "indirect-first-instance", "depth32float-stencil8",
    "texture-formats-tier1", "rg11b10ufloat-renderable"];
  const requiredFeatures = optional.filter((f) => adapter.features.has(f));
  const l = adapter.limits;
  const requiredLimits = {
    maxTextureDimension2D: l.maxTextureDimension2D,
    maxBufferSize: l.maxBufferSize,
    maxStorageBufferBindingSize: l.maxStorageBufferBindingSize,
    maxUniformBufferBindingSize: l.maxUniformBufferBindingSize,
    maxStorageBuffersPerShaderStage: l.maxStorageBuffersPerShaderStage,
    maxSampledTexturesPerShaderStage: l.maxSampledTexturesPerShaderStage,
    maxSamplersPerShaderStage: l.maxSamplersPerShaderStage,
    maxBindingsPerBindGroup: l.maxBindingsPerBindGroup,
    maxColorAttachmentBytesPerSample: l.maxColorAttachmentBytesPerSample,
    maxVertexAttributes: l.maxVertexAttributes,
    maxInterStageShaderVariables: l.maxInterStageShaderVariables,
  };
  const device = await adapter.requestDevice({ requiredFeatures, requiredLimits });
  return { adapter, device };
}

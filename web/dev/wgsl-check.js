// Compiles every WGSL pair in build/shader-corpus (written by tools/shadertest) with WebGPU
// and builds a render pipeline from the recorded pipeline description, so translation
// errors and stage/vertex-interface mismatches show up without running the game.
// Results: window.__wgslCheck = { ok, failed, failures: [{ dir, messages }] }.

const VERTEX_FORMATS = {
  R32_FLOAT: "float32", RG32_FLOAT: "float32x2", RGB32_FLOAT: "float32x3", RGBA32_FLOAT: "float32x4",
  R32_SINT: "sint32", RG32_SINT: "sint32x2", RGB32_SINT: "sint32x3", RGBA32_SINT: "sint32x4",
  R32_UINT: "uint32", RG32_UINT: "uint32x2", RGB32_UINT: "uint32x3", RGBA32_UINT: "uint32x4",
  RG16_SINT: "sint16x2", RGBA16_SINT: "sint16x4", RG16_UINT: "uint16x2", RGBA16_UINT: "uint16x4",
  RG16_UNORM: "unorm16x2", RGBA16_UNORM: "unorm16x4", RG16_SNORM: "snorm16x2", RGBA16_SNORM: "snorm16x4",
  RG16_FLOAT: "float16x2", RGBA16_FLOAT: "float16x4",
  R8_SINT: "sint8", R8_UINT: "uint8", R8_UNORM: "unorm8", R8_SNORM: "snorm8",
  RG8_SINT: "sint8x2", RG8_UINT: "uint8x2", RG8_UNORM: "unorm8x2", RG8_SNORM: "snorm8x2",
  RGBA8_SINT: "sint8x4", RGBA8_UINT: "uint8x4", RGBA8_UNORM: "unorm8x4", RGBA8_SNORM: "snorm8x4",
};
const TEXTURE_FORMATS = {
  RGBA8_UNORM: "rgba8unorm", RGBA16_FLOAT: "rgba16float", RGBA32_FLOAT: "rgba32float", R32_FLOAT: "r32float",
  RG32_FLOAT: "rg32float", R16_FLOAT: "r16float", RG16_FLOAT: "rg16float", R8_UNORM: "r8unorm",
};
const TOPOLOGY = { TRIANGLES: "triangle-list", QUADS: "triangle-list", TRIANGLE_FAN: "triangle-list",
  TRIANGLE_STRIP: "triangle-strip", LINES: "line-list", LINE_STRIP: "line-strip", POINTS: "point-list" };
const BLEND_FACTOR = { ZERO: "zero", ONE: "one", SRC_COLOR: "src", ONE_MINUS_SRC_COLOR: "one-minus-src",
  DST_COLOR: "dst", ONE_MINUS_DST_COLOR: "one-minus-dst", SRC_ALPHA: "src-alpha", ONE_MINUS_SRC_ALPHA: "one-minus-src-alpha",
  DST_ALPHA: "dst-alpha", ONE_MINUS_DST_ALPHA: "one-minus-dst-alpha", CONSTANT_COLOR: "constant",
  ONE_MINUS_CONSTANT_COLOR: "one-minus-constant", SRC_ALPHA_SATURATE: "src-alpha-saturated" };
const BLEND_OP = { ADD: "add", SUBTRACT: "subtract", REVERSE_SUBTRACT: "reverse-subtract", MIN: "min", MAX: "max" };
const COMPARE = { ALWAYS_PASS: "always", NEVER_PASS: "never", LESS_THAN: "less", LESS_THAN_OR_EQUAL: "less-equal",
  EQUAL: "equal", NOT_EQUAL: "not-equal", GREATER_THAN: "greater", GREATER_THAN_OR_EQUAL: "greater-equal" };

function parseBlend(s) {
  // "BlendFunction[color=BlendEquation[sourceFactor=X, destFactor=Y, op=Z], alpha=BlendEquation[...]]"
  const eqs = [...s.matchAll(/sourceFactor=(\w+), destFactor=(\w+), op=(\w+)/g)];
  const toComp = (m) => ({ srcFactor: BLEND_FACTOR[m[1]], dstFactor: BLEND_FACTOR[m[2]], operation: BLEND_OP[m[3]] });
  return { color: toComp(eqs[0]), alpha: toComp(eqs[1]) };
}

const results = document.getElementById("results");
const summary = document.getElementById("summary");
const report = { ok: 0, failed: 0, failures: [] };
window.__wgslCheck = report;

function fail(dir, messages) {
  report.failed++;
  report.failures.push({ dir, messages });
  const div = document.createElement("div");
  div.innerHTML = `<div class="bad">${dir}</div><pre></pre>`;
  div.querySelector("pre").textContent = messages.join("\n");
  results.appendChild(div);
}

async function main() {
  const adapter = await navigator.gpu?.requestAdapter();
  if (!adapter) {
    summary.textContent = "WebGPU unavailable";
    return;
  }
  const wanted = ["float32-blendable", "float32-filterable"].filter((f) => adapter.features.has(f));
  const device = await adapter.requestDevice({ requiredFeatures: wanted });
  const index = await (await fetch("corpus/index.json")).json();
  for (const entry of index) {
    const p = entry.pipeline;
    const messages = [];
    const modules = {};
    for (const stage of ["vertex", "fragment"]) {
      if (!entry[stage]) continue;
      const code = await (await fetch(`corpus/${entry.dir}/${entry[stage]}`)).text();
      const module = device.createShaderModule({ code, label: `${p.name} ${stage}` });
      const info = await module.getCompilationInfo();
      for (const msg of info.messages) {
        if (msg.type === "error") messages.push(`${stage}:${msg.lineNum}:${msg.linePos} ${msg.message}`);
      }
      modules[stage] = module;
    }
    if (messages.length === 0 && p.polygonMode !== "WIREFRAME") {
      const buffers = p.vertexBuffers.map((vb) => ({
        arrayStride: vb.stride,
        stepMode: vb.stepRate > 0 ? "instance" : "vertex",
        attributes: p.attribs.filter((a) => a.slot === vb.slot)
          .map((a) => ({ shaderLocation: a.location, offset: a.offset, format: VERTEX_FORMATS[a.format] ?? `?${a.format}` })),
      }));
      const targets = p.colorTargets.map((c) => c && {
        format: TEXTURE_FORMATS[c.format] ?? `?${c.format}`,
        writeMask: c.writeMask & 15,
        ...(c.blend ? { blend: parseBlend(c.blend) } : {}),
      });
      const desc = {
        layout: "auto",
        vertex: { module: modules.vertex, entryPoint: "main", buffers },
        fragment: { module: modules.fragment, entryPoint: "main", targets },
        primitive: { topology: TOPOLOGY[p.topology], cullMode: p.cull ? "back" : "none", frontFace: "ccw" },
        ...(p.depthStencil ? { depthStencil: { format: "depth32float", depthWriteEnabled: p.depthStencil.writeDepth,
          depthCompare: COMPARE[p.depthStencil.depthTest] } } : {}),
      };
      device.pushErrorScope("validation");
      try {
        device.createRenderPipeline(desc);
      } catch (e) {
        messages.push(`pipeline: ${e.message}`);
      }
      const err = await device.popErrorScope();
      if (err) messages.push(`pipeline: ${err.message}`);
    }
    if (messages.length) fail(entry.dir, messages);
    else report.ok++;
    summary.textContent = `${report.ok} ok, ${report.failed} failed (of ${index.length})`;
  }
  summary.className = report.failed ? "bad" : "good";
  summary.textContent = `Done: ${report.ok} ok, ${report.failed} failed (of ${index.length})`;
  report.done = true;
}

main().catch((e) => {
  summary.textContent = `Error: ${e.message}`;
  report.error = String(e);
  report.done = true;
});

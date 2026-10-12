"use strict";
// Spike host: collects log lines, sets up WebGPU, then loads the Web Image build.
// The Java side writes triangle vertices into the image's linear memory ("nmem")
// and calls spikeGpu(memory, addr, len); we upload straight from memory.buffer.
const out = document.getElementById("out");
const lines = [];
globalThis.spikeOut = (msg) => {
  lines.push(msg);
  out.textContent = lines.join("\n");
};

let gpu = null;
async function initGpu() {
  if (!navigator.gpu) return "navigator.gpu missing";
  const adapter = await navigator.gpu.requestAdapter();
  if (!adapter) return "no adapter";
  const device = await adapter.requestDevice();
  const canvas = document.getElementById("gpu");
  const context = canvas.getContext("webgpu");
  const format = navigator.gpu.getPreferredCanvasFormat();
  context.configure({ device, format, alphaMode: "opaque" });
  const module = device.createShaderModule({
    code: `
      struct Out { @builtin(position) pos: vec4f, @location(0) color: vec3f };
      @vertex fn vs(@location(0) p: vec2f, @location(1) c: vec3f) -> Out {
        var o: Out; o.pos = vec4f(p, 0.0, 1.0); o.color = c; return o;
      }
      @fragment fn fs(i: Out) -> @location(0) vec4f { return vec4f(i.color, 1.0); }`,
  });
  const pipeline = device.createRenderPipeline({
    layout: "auto",
    vertex: {
      module, entryPoint: "vs",
      buffers: [{ arrayStride: 20, attributes: [
        { shaderLocation: 0, offset: 0, format: "float32x2" },
        { shaderLocation: 1, offset: 8, format: "float32x3" },
      ] }],
    },
    fragment: { module, entryPoint: "fs", targets: [{ format }] },
  });
  const vbuf = device.createBuffer({ size: 256, usage: GPUBufferUsage.VERTEX | GPUBufferUsage.COPY_DST });
  gpu = { device, context, pipeline, vbuf, info: adapter.info ? `${adapter.info.vendor} ${adapter.info.architecture}` : "adapter" };
  return `ok (${gpu.info})`;
}

globalThis.spikeGpu = (memory, addr, len) => {
  if (!gpu) {
    spikeOut("[gpu] skipped: no device");
    return;
  }
  const { device, context, pipeline, vbuf } = gpu;
  device.queue.writeBuffer(vbuf, 0, memory.buffer, addr, len);
  const enc = device.createCommandEncoder();
  const pass = enc.beginRenderPass({ colorAttachments: [{
    view: context.getCurrentTexture().createView(),
    clearValue: { r: 0.08, g: 0.08, b: 0.1, a: 1 }, loadOp: "clear", storeOp: "store",
  }] });
  pass.setPipeline(pipeline);
  pass.setVertexBuffer(0, vbuf);
  pass.draw(3);
  pass.end();
  device.queue.submit([enc.finish()]);
  spikeOut(`[gpu] drew triangle from nmem @${addr} (${len} bytes)`);
};

(async () => {
  spikeOut("[gpu] init: " + (await initGpu().catch((e) => "error " + e)));
  const s = document.createElement("script");
  s.src = "spike.js";
  s.onload = () => {
    const pump = () => {
      if (typeof globalThis.spikeTick === "function") globalThis.spikeTick();
      requestAnimationFrame(pump);
    };
    requestAnimationFrame(pump);
  };
  document.body.appendChild(s);
})();

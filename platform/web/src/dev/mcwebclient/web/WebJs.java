package dev.mcwebclient.web;

import org.graalvm.webimage.api.JS;

/**
 * Coarse-grained calls into the page's host runtime (globalThis.mcweb). Each call
 * crosses the Wasm/JS boundary with boxing, so nothing here belongs in a hot loop.
 */
public final class WebJs {
    private WebJs() {
    }

    @JS.Coerce
    @JS(value = "console.log(msg); globalThis.mcweb?.onLog?.(msg);", args = {"msg"})
    public static native void log(String msg);

    @JS.Coerce
    @JS(value = "globalThis.mcweb?.onStage?.(stage);", args = {"stage"})
    public static native void stage(String stage);

    /** Registers the per-frame callback; the host calls cb.run() from requestAnimationFrame. */
    @JS(value = "globalThis.mcweb.setFrameCallback(() => cb.run());", args = {"cb"})
    public static native void setFrameCallback(Runnable cb);

    @JS.Coerce
    @JS(value = "globalThis.mcweb?.onExit?.(code);", args = {"code"})
    public static native void exit(int code);
}

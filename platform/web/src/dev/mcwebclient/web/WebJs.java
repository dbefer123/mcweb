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

    @JS.Coerce
    @JS(value = "globalThis.mcweb?.onExit?.(code);", args = {"code"})
    public static native void exit(int code);
}

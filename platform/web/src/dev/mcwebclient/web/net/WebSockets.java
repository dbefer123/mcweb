package dev.mcwebclient.web.net;

import dev.mcwebclient.web.exec.CoThreads;
import dev.mcwebclient.web.mem.NativeHeap;
import dev.mcwebclient.web.mem.Nmem;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import org.graalvm.webimage.api.JS;

/**
 * Browser WebSockets for {@link WebSocketChannel} (JS side: web/dev/host.js, mcweb.net).
 * Each socket gets a reader green thread that suspends until the socket opens and then
 * until each message arrives (the suspend* imports are WebAssembly.Suspending).
 */
final class WebSockets {
    private WebSockets() {
    }

    static int open(String host, int port) {
        return hostOpen(host, port);
    }

    static void startReader(WebSocketChannel channel, int socket) {
        Thread reader = new Thread(() -> read(channel, socket), "WebSocket reader #" + socket);
        reader.setDaemon(true);
        reader.start();
    }

    private static void read(WebSocketChannel channel, int socket) {
        EventLoop loop = channel.eventLoop();
        Thread me = CoThreads.beginIoWait();
        int opened = suspendOpen(socket);
        CoThreads.endIoWait(me);
        if (opened != 1) {
            String reason = hostReason(socket);
            loop.execute(() -> channel.onConnectFailed(reason));
            return;
        }
        loop.execute(channel::onOpen);
        while (true) {
            CoThreads.beginIoWait();
            int length = suspendMessage(socket);
            CoThreads.endIoWait(me);
            if (length < 0) {
                loop.execute(channel::onClosed);
                return;
            }
            byte[] data = new byte[length];
            if (length > 0) {
                long tmp = NativeHeap.malloc(length);
                try {
                    hostTake(socket, (int) tmp);
                    Nmem.copyToBytes(tmp, data, 0, length);
                } finally {
                    NativeHeap.free(tmp);
                }
            } else {
                hostTake(socket, 0);
                continue;
            }
            ByteBuf buf = Unpooled.wrappedBuffer(data);
            loop.execute(() -> channel.onMessage(buf));
        }
    }

    static void send(int socket, ByteBuf buf) {
        int length = buf.readableBytes();
        long tmp = NativeHeap.malloc(length);
        try {
            if (buf.hasArray()) {
                Nmem.copyFromBytes(buf.array(), buf.arrayOffset() + buf.readerIndex(), tmp, length);
            } else {
                byte[] bytes = new byte[length];
                buf.getBytes(buf.readerIndex(), bytes);
                Nmem.copyFromBytes(bytes, 0, tmp, length);
            }
            hostSend(socket, (int) tmp, length);
        } finally {
            NativeHeap.free(tmp);
        }
    }

    static void close(int socket) {
        hostClose(socket);
    }

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.net.open(host, port);", args = {"host", "port"})
    private static native int hostOpen(String host, int port);

    /** 1 once open, 0 if it failed (suspends while connecting). */
    @JS.Coerce
    @JS(value = "return globalThis.mcweb.net.awaitOpen(socket);", args = {"socket"})
    private static native int suspendOpen(int socket);

    /** Length of the next message, or -1 once closed (suspends until one arrives). */
    @JS.Coerce
    @JS(value = "return globalThis.mcweb.net.awaitMessage(socket);", args = {"socket"})
    private static native int suspendMessage(int socket);

    /** Copies the next message into nmem and drops it from the queue. */
    @JS.Coerce
    @JS(value = "globalThis.mcweb.net.take(socket, getExport('nmem'), addr >>> 0);", args = {"socket", "addr"})
    private static native void hostTake(int socket, int addr);

    @JS.Coerce
    @JS(value = "globalThis.mcweb.net.send(socket, new Uint8Array(getExport('nmem').buffer, addr >>> 0, len).slice());",
            args = {"socket", "addr", "len"})
    private static native void hostSend(int socket, int addr, int len);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.net.reason(socket);", args = {"socket"})
    private static native String hostReason(int socket);

    @JS.Coerce
    @JS(value = "globalThis.mcweb.net.close(socket);", args = {"socket"})
    private static native void hostClose(int socket);
}

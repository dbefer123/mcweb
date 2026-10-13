package dev.mcwebclient.web.net;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ConnectionPendingException;

import dev.mcwebclient.web.exec.CoThreads;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * A Netty client channel whose transport is a WebSocket to the relay (relay/relay.mjs),
 * which holds the TCP connection to the Minecraft server. Minecraft's pipeline (framing,
 * compression, encryption, packets) runs on top unchanged.
 *
 * <p>The browser side is {@link WebSockets}: connect() opens the socket and starts a green
 * thread that waits for it to open and then for each message, handing results to this
 * channel's event loop. Writes go straight to the socket.
 */
public final class WebSocketChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static EventLoopGroup group;

    private final ChannelConfig config = new DefaultChannelConfig(this);
    private int socket;
    private boolean open = true;
    private boolean connected;
    private InetSocketAddress remote;
    private ChannelPromise connectPromise;
    private boolean sentAny;

    public WebSocketChannel() {
        super(null);
    }

    /** The event loop group for WebSocket channels (plain task loops; no I/O to poll). */
    public static synchronized EventLoopGroup group() {
        if (group == null) {
            group = new DefaultEventLoopGroup(1, new DefaultThreadFactory("Netty WebSocket IO", true) {
                @Override
                protected Thread newThread(Runnable r, String name) {
                    Thread t = super.newThread(r, name);
                    CoThreads.expectIdleWaits(t); // the loop waits for tasks between connections
                    return t;
                }
            });
        }
        return group;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new WebSocketUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return null;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remote;
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        throw new UnsupportedOperationException("WebSocket channels can't bind");
    }

    @Override
    protected void doDisconnect() {
        doClose();
    }

    @Override
    protected void doClose() {
        open = false;
        connected = false;
        if (socket != 0) {
            WebSockets.close(socket);
            socket = 0;
        }
        if (connectPromise != null) {
            connectPromise.tryFailure(new ConnectException("closed before connecting"));
        }
    }

    @Override
    protected void doBeginRead() {
        // The reader thread delivers data as it arrives.
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {
        while (true) {
            Object msg = in.current();
            if (msg == null) {
                return;
            }
            if (msg instanceof ByteBuf buf) {
                if (buf.isReadable() && socket != 0) {
                    if (!sentAny) {
                        sentAny = true;
                        dev.mcwebclient.web.WebJs.log("[net] sending to " + remote.getHostString() + ":" + remote.getPort());
                    }
                    WebSockets.send(socket, buf);
                }
                in.remove();
            } else {
                in.remove(new UnsupportedOperationException("unsupported message type " + msg.getClass().getName()));
            }
        }
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isActive() {
        return open && connected;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    // ---- called on the event loop by the reader thread (WebSockets) ----

    void onOpen() {
        dev.mcwebclient.web.WebJs.log("[net] connected to " + remote.getHostString() + ":" + remote.getPort()
                + " through the relay (on " + Thread.currentThread().getName() + ")");
        if (!open || connectPromise == null) {
            return;
        }
        connected = true;
        ChannelPromise promise = connectPromise;
        connectPromise = null;
        if (promise.trySuccess()) {
            pipeline().fireChannelActive();
        }
    }

    void onConnectFailed(String reason) {
        if (connectPromise != null) {
            ChannelPromise promise = connectPromise;
            connectPromise = null;
            promise.tryFailure(new ConnectException(reason));
        }
        unsafe().close(unsafe().voidPromise());
    }

    void onMessage(ByteBuf data) {
        if (!open) {
            data.release();
            return;
        }
        pipeline().fireChannelRead(data);
        pipeline().fireChannelReadComplete();
    }

    void onClosed() {
        if (open) {
            unsafe().close(unsafe().voidPromise());
        }
    }

    private final class WebSocketUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }
            if (connectPromise != null) {
                promise.setFailure(new ConnectionPendingException());
                return;
            }
            InetSocketAddress target = (InetSocketAddress) remoteAddress;
            remote = target;
            connectPromise = promise;
            dev.mcwebclient.web.WebJs.log("[net] connecting to " + target.getHostString() + ":" + target.getPort()
                    + " on " + Thread.currentThread().getName());
            socket = WebSockets.open(target.getHostString(), target.getPort());
            WebSockets.startReader(WebSocketChannel.this, socket);
        }
    }
}

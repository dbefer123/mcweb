package dev.mcwebclient.web.net;

import java.net.InetSocketAddress;
import java.util.Optional;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.resolver.NoopAddressResolverGroup;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * Multiplayer connections through the WebSocket relay. The page can't resolve names or
 * open TCP sockets, so a server address stays a hostname (the relay resolves it, including
 * the _minecraft._tcp SRV lookup the game does for port 25565) and the channel is a
 * {@link WebSocketChannel}. The handshake still carries the hostname the player typed.
 */
final class NetworkSubstitutions {
    private NetworkSubstitutions() {
    }

    /** Throws a checked exception from a method that doesn't declare it. */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    /** An unresolved address: the relay resolves it. */
    static ResolvedServerAddress unresolved(String host, int port) {
        InetSocketAddress address = InetSocketAddress.createUnresolved(host, port);
        return new ResolvedServerAddress() {
            @Override
            public String getHostName() {
                return host;
            }

            @Override
            public String getHostIp() {
                return host;
            }

            @Override
            public int getPort() {
                return port;
            }

            @Override
            public InetSocketAddress asInetSocketAddress() {
                return address;
            }
        };
    }
}

/**
 * JDK sockets (LAN world discovery's multicast socket, anything else) fail the way an
 * unavailable network does: a SocketException their callers handle, not the LinkageError of
 * an unsupported native (which escaped JoinMultiplayerScreen.init's catch (Exception)).
 */
@TargetClass(className = "sun.nio.ch.Net")
final class Target_sun_nio_ch_Net {
    @Substitute
    private static int socket0(boolean preferIPv6, boolean stream, boolean reuse, boolean fastLoopback) {
        throw NetworkSubstitutions.<RuntimeException>sneaky(new java.net.SocketException("Sockets are not available in the browser"));
    }
}

/** SRV records are looked up by the relay; JNDI DNS doesn't exist here (it only logged an error). */
@TargetClass(net.minecraft.client.multiplayer.resolver.ServerRedirectHandler.class)
final class Target_net_minecraft_client_multiplayer_resolver_ServerRedirectHandler {
    @Substitute
    static net.minecraft.client.multiplayer.resolver.ServerRedirectHandler createDnsSrvRedirectHandler() {
        return net.minecraft.client.multiplayer.resolver.ServerRedirectHandler.EMPTY;
    }
}

/**
 * The pre-1.7 ping (tried after a failed modern ping) through the relay as well: the game's
 * version builds a NIO channel, whose selector doesn't exist here (a fatal LinkageError).
 */
@TargetClass(net.minecraft.client.multiplayer.ServerStatusPinger.class)
final class Target_net_minecraft_client_multiplayer_ServerStatusPinger {
    @Substitute
    private void pingLegacyServer(InetSocketAddress resolvedAddress, ServerAddress rawAddress,
                                  net.minecraft.client.multiplayer.ServerData data, EventLoopGroupHolder eventLoopGroupHolder) {
        new Bootstrap().group(WebSocketChannel.group())
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .channelFactory((io.netty.channel.ChannelFactory<WebSocketChannel>) WebSocketChannel::new)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new net.minecraft.client.multiplayer.LegacyServerPinger(rawAddress,
                                (protocolVersion, gameVersion, motd, players, maxPlayers) -> {
                                    data.setState(net.minecraft.client.multiplayer.ServerData.State.INCOMPATIBLE);
                                    data.version = net.minecraft.network.chat.Component.literal(gameVersion);
                                    data.motd = net.minecraft.network.chat.Component.literal(motd);
                                    data.status = net.minecraft.client.multiplayer.ServerStatusPinger.formatPlayerCount(players, maxPlayers);
                                    data.players = new net.minecraft.network.protocol.status.ServerStatus.Players(maxPlayers, players, java.util.List.of());
                                }));
                    }
                })
                .connect(resolvedAddress);
    }
}

/** No DNS (or SRV lookup, or Mojang's blocked-server check, which needs the network) here. */
@TargetClass(net.minecraft.client.multiplayer.resolver.ServerNameResolver.class)
final class Target_net_minecraft_client_multiplayer_resolver_ServerNameResolver {
    @Substitute
    public Optional<ResolvedServerAddress> resolveAddress(ServerAddress address) {
        return Optional.of(NetworkSubstitutions.unresolved(address.getHost(), address.getPort()));
    }
}

/**
 * Remote connections on a WebSocketChannel; the pipeline is the game's (the baked Fabric
 * networking mixins live in configureSerialization, which is still called).
 */
@TargetClass(Connection.class)
final class Target_net_minecraft_network_Connection {
    @Alias private BandwidthDebugMonitor bandwidthDebugMonitor;

    @Substitute
    public static ChannelFuture connect(InetSocketAddress address, EventLoopGroupHolder eventLoopGroupHolder, Connection connection) {
        BandwidthDebugMonitor monitor = ((Target_net_minecraft_network_Connection) (Object) connection).bandwidthDebugMonitor;
        return new Bootstrap().group(WebSocketChannel.group())
                // The hostname goes to the relay as is: no DNS lookup here.
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .channelFactory((io.netty.channel.ChannelFactory<WebSocketChannel>) WebSocketChannel::new)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        ChannelPipeline pipeline = channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
                        Connection.configureSerialization(pipeline, PacketFlow.CLIENTBOUND, false, monitor);
                        connection.configurePacketHandler(pipeline);
                    }
                })
                .connect(address);
    }
}

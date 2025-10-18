package org.apache.hc.client5.http.websocket.example;


import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public final class H2WebSocketEchoServerNetty {

    private static final char SETTINGS_ENABLE_CONNECT_PROTOCOL = (char) 0x8;

    public static void main(final String[] args) throws Exception {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;

        final EventLoopGroup boss = new NioEventLoopGroup(1);
        final EventLoopGroup worker = new NioEventLoopGroup();

        try {
            // Server HTTP/2 settings: advertise RFC 8441 support
            final Http2Settings settings = new Http2Settings();
            settings.put(SETTINGS_ENABLE_CONNECT_PROTOCOL, Long.valueOf(1L));

            final Http2FrameCodec http2Codec =
                    Http2FrameCodecBuilder.forServer()
                            .initialSettings(settings)
                            .validateHeaders(false) // allow :protocol
                            .build();

            final Http2WebSocketServerHandler h2ws =
                    Http2WebSocketServerBuilder.create()
                            .acceptor((ctx, path, subprotocols, req, resp) -> {
                                if ("/echo".equals(path)) {
                                    return ctx.executor().newSucceededFuture(new EchoHandler());
                                }
                                return ctx.executor().newFailedFuture(
                                        new WebSocketHandshakeException("websocket rejected, path=" + path));
                            })
                            .build();

            final ServerBootstrap b = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel ch) {
                            // Accept BOTH: h2c prior-knowledge and HTTP/1.1 Upgrade
                            final HttpServerCodec http1 = new HttpServerCodec();

                            final HttpServerUpgradeHandler.UpgradeCodecFactory upgradeFactory = protocol -> {
                                if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                                    return new Http2ServerUpgradeCodec(http2Codec, h2ws);
                                }
                                return null;
                            };

                            final ChannelHandler priorKnowledgeHandler = new ChannelInitializer<Channel>() {
                                @Override
                                protected void initChannel(final Channel ch) {
                                    final ChannelPipeline p = ch.pipeline();
                                    p.addLast("h2", http2Codec);
                                    p.addLast("h2ws", h2ws);
                                }
                            };

                            final HttpServerUpgradeHandler upgrade = new HttpServerUpgradeHandler(http1, upgradeFactory);
                            final CleartextHttp2ServerUpgradeHandler h2cBoth =
                                    new CleartextHttp2ServerUpgradeHandler(http1, upgrade, priorKnowledgeHandler);

                            final ChannelPipeline p = ch.pipeline();
                            p.addLast("h2c-both", h2cBoth);
                        }
                    });

            final Channel ch = b.bind(port).sync().channel();
            System.out.println("[H2 WS-Server] h2c up at ws://localhost:" + port + "/echo");
            ch.closeFuture().sync();
        } finally {
            final GenericFutureListener<Future<Object>> ignore = f -> {
            };
            boss.shutdownGracefully().addListener(ignore);
            worker.shutdownGracefully().addListener(ignore);
        }
    }

    /**
     * Echo text/binary; ping->pong; close on Close frame.
     */
    static final class EchoHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame) {
                ctx.writeAndFlush(new TextWebSocketFrame(((TextWebSocketFrame) frame).text()));
            } else if (frame instanceof BinaryWebSocketFrame) {
                ctx.writeAndFlush(new BinaryWebSocketFrame(frame.content().retain()));
            } else if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            } else if (frame instanceof CloseWebSocketFrame) {
                ctx.writeAndFlush(frame.retainedDuplicate())
                        .addListener(ChannelFutureListener.CLOSE);
            } else {
                frame.release();
            }
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
package ru.hse.networks.hw5.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

public final class ChatServerInitializer extends ChannelInitializer<SocketChannel> {
    private static final int MAX_FRAME_SIZE   = 2 * 1024 * 1024;
    private static final int MAX_HTTP_CONTENT = 65_536;

    private final ChatRoom room;

    public ChatServerInitializer(ChatRoom room) {
        this.room = room;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT));
        p.addLast(new ChunkedWriteHandler());
        p.addLast(new WebSocketServerCompressionHandler());
        p.addLast(new HttpStaticFileHandler("client"));
        p.addLast(new WebSocketServerProtocolHandler("/ws", null, true, MAX_FRAME_SIZE));
        p.addLast(new ChatWebSocketHandler(room));
    }
}

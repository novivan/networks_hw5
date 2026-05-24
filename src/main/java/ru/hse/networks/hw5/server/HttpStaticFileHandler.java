package ru.hse.networks.hw5.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;

public final class HttpStaticFileHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(HttpStaticFileHandler.class);

    private final String rootDir;

    public HttpStaticFileHandler(String rootDir) {
        this.rootDir = rootDir;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!req.decoderResult().isSuccess()) {
            send(ctx, HttpResponseStatus.BAD_REQUEST, "Bad request", "text/plain");
            return;
        }
        if (req.method() != HttpMethod.GET) {
            send(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed", "text/plain");
            return;
        }
        String uri = req.uri();
        int q = uri.indexOf('?');
        if (q >= 0) uri = uri.substring(0, q);
        if (uri.equals("/")) uri = "/index.html";
        if (uri.equals("/ws")) {
            ctx.fireChannelRead(req.retain());
            return;
        }

        if (uri.contains("..")) {
            send(ctx, HttpResponseStatus.FORBIDDEN, "Forbidden", "text/plain");
            return;
        }

        byte[] body = readResource(uri);
        if (body == null) {
            send(ctx, HttpResponseStatus.NOT_FOUND, "Not found: " + uri, "text/plain");
            return;
        }
        send(ctx, HttpResponseStatus.OK, body, mime(uri));
    }

    private byte[] readResource(String uri) {
        Path local = Paths.get(rootDir, uri.substring(1));
        try {
            if (Files.isRegularFile(local)) return Files.readAllBytes(local);
        } catch (IOException ignored) { }

        String cp = "/client" + uri;
        try (InputStream is = HttpStaticFileHandler.class.getResourceAsStream(cp)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (IOException e) {
            log.warn("Failed to read {}", cp, e);
            return null;
        }
    }

    private static String mime(String uri) {
        if (uri.endsWith(".html")) return "text/html; charset=UTF-8";
        if (uri.endsWith(".js"))   return "application/javascript; charset=UTF-8";
        if (uri.endsWith(".css"))  return "text/css; charset=UTF-8";
        if (uri.endsWith(".proto")) return "text/plain; charset=UTF-8";
        if (uri.endsWith(".png"))  return "image/png";
        if (uri.endsWith(".jpg") || uri.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static void send(ChannelHandlerContext ctx, HttpResponseStatus status, String body, String type) {
        send(ctx, status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), type);
    }

    private static void send(ChannelHandlerContext ctx, HttpResponseStatus status, byte[] body, String type) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }
}

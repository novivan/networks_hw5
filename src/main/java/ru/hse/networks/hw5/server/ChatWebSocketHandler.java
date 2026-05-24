package ru.hse.networks.hw5.server;

import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.networks.hw5.proto.ChatProto.*;

public final class ChatWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatRoom room;

    public ChatWebSocketHandler(ChatRoom room) {
        this.room = room;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (!(frame instanceof BinaryWebSocketFrame)) {
            sendError(ctx.channel(), ErrorResponse.Code.INVALID_REQUEST, "Binary frames only", 0);
            return;
        }
        ByteBuf buf = frame.content();
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);

        ClientEnvelope env;
        try {
            env = ClientEnvelope.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            sendError(ctx.channel(), ErrorResponse.Code.INVALID_REQUEST, "Malformed protobuf", 0);
            return;
        }

        long rid = env.getRequestId();
        switch (env.getPayloadCase()) {
            case JOIN    -> handleJoin(ctx.channel(), env.getJoin(), rid);
            case MESSAGE -> handleMessage(ctx.channel(), env.getMessage(), rid);
            case LEAVE   -> ctx.channel().close();
            case PAYLOAD_NOT_SET -> sendError(ctx.channel(),
                    ErrorResponse.Code.INVALID_REQUEST, "Empty payload", rid);
        }
    }

    private void handleJoin(Channel ch, JoinRequest req, long rid) {
        if (room.nameOf(ch) != null) {
            sendError(ch, ErrorResponse.Code.INVALID_REQUEST, "Already joined", rid);
            return;
        }
        String name = req.getName().trim();
        if (name.isEmpty() || name.length() > 32) {
            sendError(ch, ErrorResponse.Code.INVALID_REQUEST, "Invalid name", rid);
            return;
        }

        Attachment icon = null;
        if (req.hasIcon() && req.getIcon().getData().size() > 0) {
            if (req.getIcon().getData().size() > ChatRoom.MAX_IMAGE_BYTES) {
                sendError(ch, ErrorResponse.Code.IMAGE_TOO_LARGE, "Icon > 1MB", rid);
                return;
            }
            icon = req.getIcon();
        }

        if (!room.tryJoin(name, ch, icon)) {
            JoinResponse resp = JoinResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("Name is already taken or chat is full")
                    .build();
            send(ch, ServerEnvelope.newBuilder().setJoinResponse(resp).setRequestId(rid).build());
            return;
        }

        JoinResponse.Builder resp = JoinResponse.newBuilder()
                .setSuccess(true)
                .addAllHistory(room.snapshotHistory())
                .addAllUserIcons(room.allUserIcons());

        Attachment last = room.lastImage();
        if (last != null) resp.setLastImage(last);

        send(ch, ServerEnvelope.newBuilder().setJoinResponse(resp).setRequestId(rid).build());

        room.broadcastEnvelope(
                ChatRoom.buildPresence(PresenceUpdate.Kind.JOINED, name, icon), ch);

        log.info("'{}' joined, online={}", name, room.size());
    }

    private void handleMessage(Channel ch, ChatMessageRequest req, long rid) {
        String sender = room.nameOf(ch);
        if (sender == null) {
            sendError(ch, ErrorResponse.Code.NOT_JOINED, "Join first", rid);
            return;
        }

        String text = req.getText();
        if (text.length() > ChatRoom.MAX_TEXT_LEN) {
            sendError(ch, ErrorResponse.Code.MESSAGE_TOO_LONG,
                    "Max " + ChatRoom.MAX_TEXT_LEN + " chars", rid);
            return;
        }

        Attachment attachment = null;
        if (req.hasAttachment() && req.getAttachment().getData().size() > 0) {
            if (req.getAttachment().getData().size() > ChatRoom.MAX_IMAGE_BYTES) {
                sendError(ch, ErrorResponse.Code.IMAGE_TOO_LARGE, "Image > 1MB", rid);
                return;
            }
            attachment = req.getAttachment();
        }

        String recipient = req.getRecipient();
        boolean isPrivate = recipient != null && !recipient.isEmpty();

        ChatEntry.Builder entry = ChatEntry.newBuilder()
                .setSender(sender)
                .setText(text)
                .setTimestamp(System.currentTimeMillis())
                .setIsPrivate(isPrivate)
                .setRecipient(isPrivate ? recipient : "");
        if (attachment != null) entry.setAttachment(attachment);

        ChatEntry e = entry.build();
        ServerEnvelope env = ServerEnvelope.newBuilder()
                .setBroadcast(ChatBroadcast.newBuilder().setEntry(e))
                .build();

        if (isPrivate) {
            if (!room.sendTo(recipient, env)) {
                sendError(ch, ErrorResponse.Code.RECIPIENT_NOT_FOUND,
                        "No user '" + recipient + "'", rid);
                return;
            }
            if (!sender.equals(recipient)) {
                room.sendTo(sender, env);
            }
        } else {
            room.appendHistory(e);
            room.broadcastAll(env);
        }
    }

    private static void send(Channel ch, ServerEnvelope env) {
        ch.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(env.toByteArray())));
    }

    private static void sendError(Channel ch, ErrorResponse.Code code, String msg, long rid) {
        ServerEnvelope env = ServerEnvelope.newBuilder()
                .setError(ErrorResponse.newBuilder().setCode(code).setMessage(msg))
                .setRequestId(rid)
                .build();
        send(ch, env);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        room.leave(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Exception in WS handler", cause);
        ctx.close();
    }
}

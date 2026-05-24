package ru.hse.networks.hw5.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.buffer.Unpooled;
import ru.hse.networks.hw5.proto.ChatProto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatRoom {
    private static final Logger log = LoggerFactory.getLogger(ChatRoom.class);

    public static final int MAX_USERS         = 100;
    public static final int MAX_HISTORY_LINES = 50;
    public static final int MAX_HISTORY_IMAGES = 50;
    public static final int MAX_TEXT_LEN      = 25;
    public static final int MAX_IMAGE_BYTES   = 1024 * 1024;

    private final Map<String, Channel> usersByName = new ConcurrentHashMap<>();
    private final Map<Channel, String> namesByChannel = new ConcurrentHashMap<>();
    private final Map<String, Attachment> icons = new ConcurrentHashMap<>();

    private final Deque<ChatEntry> history = new ArrayDeque<>();
    private final Deque<ChatEntry> imageHistory = new ArrayDeque<>();
    private final Object historyLock = new Object();

    public int size() {
        return usersByName.size();
    }

    public synchronized boolean tryJoin(String name, Channel ch, Attachment icon) {
        if (size() >= MAX_USERS) return false;
        if (name == null || name.isBlank()) return false;
        if (usersByName.putIfAbsent(name, ch) != null) return false;
        namesByChannel.put(ch, name);
        if (icon != null && icon.getData().size() > 0) {
            icons.put(name, icon);
        }
        return true;
    }

    public String nameOf(Channel ch) {
        return namesByChannel.get(ch);
    }

    public Channel channelOf(String name) {
        return usersByName.get(name);
    }

    public Attachment iconOf(String name) {
        return icons.get(name);
    }

    public Collection<UserIcon> allUserIcons() {
        List<UserIcon> out = new ArrayList<>();
        for (Map.Entry<String, Attachment> e : icons.entrySet()) {
            out.add(UserIcon.newBuilder().setName(e.getKey()).setIcon(e.getValue()).build());
        }
        return out;
    }

    public List<ChatEntry> snapshotHistory() {
        synchronized (historyLock) {
            return new ArrayList<>(history);
        }
    }

    public Attachment lastImage() {
        synchronized (historyLock) {
            ChatEntry e = imageHistory.peekLast();
            return e == null ? null : e.getAttachment();
        }
    }

    public void appendHistory(ChatEntry entry) {
        if (entry.getIsPrivate()) return;
        synchronized (historyLock) {
            history.addLast(entry);
            while (history.size() > MAX_HISTORY_LINES) history.removeFirst();
            if (entry.hasAttachment() && entry.getAttachment().getData().size() > 0) {
                imageHistory.addLast(entry);
                while (imageHistory.size() > MAX_HISTORY_IMAGES) imageHistory.removeFirst();
            }
        }
    }

    public void leave(Channel ch) {
        String name = namesByChannel.remove(ch);
        if (name != null) {
            usersByName.remove(name, ch);
            icons.remove(name);
            log.info("User '{}' left, online={}", name, size());
            broadcastEnvelope(buildPresence(PresenceUpdate.Kind.LEFT, name, null), ch);
        }
    }

    public void broadcastEnvelope(ServerEnvelope env, Channel except) {
        byte[] bytes = env.toByteArray();
        for (Channel ch : usersByName.values()) {
            if (ch == except) continue;
            if (ch.isActive()) {
                ch.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)));
            }
        }
    }

    public void broadcastAll(ServerEnvelope env) {
        broadcastEnvelope(env, null);
    }

    public boolean sendTo(String name, ServerEnvelope env) {
        Channel ch = usersByName.get(name);
        if (ch == null || !ch.isActive()) return false;
        ch.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(env.toByteArray())));
        return true;
    }

    public static ServerEnvelope buildPresence(PresenceUpdate.Kind kind, String name, Attachment icon) {
        PresenceUpdate.Builder p = PresenceUpdate.newBuilder().setKind(kind).setName(name);
        if (icon != null) p.setIcon(icon);
        return ServerEnvelope.newBuilder().setPresence(p).build();
    }
}

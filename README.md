# HW5 — WebSocket + Protocol Buffers Chat (Java/Netty + JS)

Chat application built for the "Computer Networks" course (HW5).
Server: **Java 21 + Netty 4.1**, Client: **pure JavaScript** (no frameworks).
Wire protocol: **WebSocket BINARY mode** carrying **Protocol Buffers (proto3)** messages
defined in [`chat.proto`](src/main/proto/chat.proto:1).

Scope: extended task (up to 10 points) — supports `name`, "Join" flow,
private `@name` messages, optional user icons, error codes and history replay.

## Project layout

```
networks_hw5/
├── build.gradle
├── settings.gradle
├── src/main/proto/chat.proto              # business protocol
├── src/main/java/ru/hse/networks/hw5/
│   └── server/                            # Netty server
│       ├── Main.java
│       ├── ChatServer.java
│       ├── ChatServerInitializer.java
│       ├── ChatRoom.java
│       ├── ChatWebSocketHandler.java
│       └── HttpStaticFileHandler.java
├── src/main/resources/logback.xml
├── client/
│   ├── index.html                         # chat UI
│   └── app.js                             # JS client (protobufjs via CDN)
├── REPORT.md
└── video_link.txt
```

## Build & run

Requires **JDK 21** and Gradle (wrapper-less; install gradle 8.x).

```bash
gradle run                # starts server on port 8080
# or:
gradle run --args='9090'  # custom port
```

On startup the server:

1. Generates Java sources from [`chat.proto`](src/main/proto/chat.proto:1) via the `protobuf` plugin.
2. Binds to `0.0.0.0:8080` (Netty NIO event loop).
3. Serves static files from `client/` over HTTP at `/` and the WebSocket endpoint at `/ws`.

Open <http://localhost:8080/> in **several browser tabs / windows** to simulate multiple clients.
The client loads [`protobuf.min.js`](https://cdn.jsdelivr.net/npm/protobufjs@7.4.0/dist/protobuf.min.js)
from a CDN at runtime.

## Protocol summary

Defined in [`chat.proto`](src/main/proto/chat.proto:1) (proto3).

- `ClientEnvelope` / `ServerEnvelope` use `oneof` to carry one of several request/response types.
- Nested messages: `Attachment` (with `ImageFormat` enum), `ClientInfo`, `ChatEntry`, `UserIcon`.
- `repeated` fields: `JoinResponse.history`, `JoinResponse.user_icons`.
- `optional` field: `Attachment.file_name` (proto3 `optional` for explicit presence).
- Multiple scalar types: `string`, `bytes`, `bool`, `int32`, `int64`, enums.
- Server checks presence of every optional sub-message (icon / attachment / recipient).

## Feature matrix

Base requirements (≤ 8 points):

- [x] Single HTML page + JS, multiple tabs supported.
- [x] WebSocket binary mode, Protobuf envelope per direction.
- [x] 25-char input, 10-row readonly log textarea, image area, Send & Attach buttons.
- [x] Auto-connect on page load.
- [x] On failure → retry after 10 s with live countdown; inputs disabled.
- [x] Up to 100 concurrent clients; history (50 lines, 50 images) replayed on connect.
- [x] Last image delivered on connect.
- [x] JPEG/PNG ≤ 1 MB enforced both client- and server-side.

Extended requirements (9–10 points):

- [x] `name` field + Join button; connection is established first, then chat is joined via request.
- [x] Server returns history on success or `ErrorResponse{NAME_TAKEN}` otherwise.
- [x] `@name <text>` syntax sends a private message; `ErrorResponse{RECIPIENT_NOT_FOUND}` if absent.
- [x] Optional user icon attached on join, redistributed via `PresenceUpdate` and `JoinResponse.user_icons`.

## Notes on Netty pipeline

`HttpServerCodec` → `HttpObjectAggregator` → `ChunkedWriteHandler` →
`WebSocketServerCompressionHandler` → `HttpStaticFileHandler` →
`WebSocketServerProtocolHandler("/ws")` → `ChatWebSocketHandler`.

The static-file handler short-circuits non-`/ws` HTTP requests; the WebSocket
handler upgrades `/ws` and forwards binary frames to the application handler.

(function () {
    "use strict";

    const RECONNECT_SECONDS = 10;
    const MAX_TEXT          = 25;
    const MAX_IMAGE_BYTES   = 1024 * 1024;
    const WS_URL            = (location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws";

    const $ = (id) => document.getElementById(id);
    const statusEl = $("status");
    const joinBox  = $("joinBox");
    const chatBox  = $("chatBox");
    const joinBtn  = $("joinBtn");
    const nameIn   = $("nameInput");
    const iconIn   = $("iconInput");
    const joinErr  = $("joinError");
    const logEl    = $("log");
    const userListEl = $("userList");
    const msgIn    = $("msgInput");
    const fileIn   = $("fileInput");
    const sendBtn  = $("sendBtn");
    const previewEl = $("preview");
    const incomingEl = $("incoming");

    let proto = null;
    let ws = null;
    let reconnectTimer = null;
    let countdownTimer = null;
    let secondsLeft = 0;
    let joined = false;
    let myName = "";
    let nextRequestId = 1;
    const userIcons = new Map();
    let pendingFile = null;
    let pendingIcon = null;

    const PROTO_DEF = `
syntax = "proto3";
package chat;

message Attachment {
  enum ImageFormat { UNKNOWN = 0; JPEG = 1; PNG = 2; }
  ImageFormat format    = 1;
  bytes       data      = 2;
  optional string file_name = 3;
  int32       width_px  = 4;
  int32       height_px = 5;
}

message ClientInfo {
  string protocol_version = 1;
  bool   supports_images  = 2;
  int32  client_build     = 3;
}

message JoinRequest {
  string     name        = 1;
  ClientInfo client_info = 2;
  Attachment icon        = 3;
}

message ChatMessageRequest {
  string     text       = 1;
  Attachment attachment = 2;
  string     recipient  = 3;
}

message LeaveRequest { string reason = 1; }

message ClientEnvelope {
  oneof payload {
    JoinRequest        join    = 1;
    ChatMessageRequest message = 2;
    LeaveRequest       leave   = 3;
  }
  int64 request_id = 16;
}

message ChatEntry {
  string     sender     = 1;
  string     text       = 2;
  int64      timestamp  = 3;
  Attachment attachment = 4;
  bool       is_private = 5;
  string     recipient  = 6;
}

message UserIcon { string name = 1; Attachment icon = 2; }

message JoinResponse {
  bool       success       = 1;
  string     error_message = 2;
  repeated ChatEntry history = 3;
  Attachment last_image = 4;
  repeated UserIcon user_icons = 5;
}

message ErrorResponse {
  enum Code {
    UNKNOWN = 0; NAME_TAKEN = 1; NOT_JOINED = 2; RECIPIENT_NOT_FOUND = 3;
    MESSAGE_TOO_LONG = 4; IMAGE_TOO_LARGE = 5; INVALID_REQUEST = 6; INTERNAL = 7;
  }
  Code code = 1;
  string message = 2;
}

message ChatBroadcast { ChatEntry entry = 1; }

message PresenceUpdate {
  enum Kind { JOINED = 0; LEFT = 1; }
  Kind   kind = 1;
  string name = 2;
  Attachment icon = 3;
}

message ServerEnvelope {
  oneof payload {
    JoinResponse   join_response = 1;
    ChatBroadcast  broadcast     = 2;
    ErrorResponse  error         = 3;
    PresenceUpdate presence      = 4;
  }
  int64 request_id = 16;
}
`;

    function loadProto() {
        proto = protobuf.parse(PROTO_DEF, { keepCase: false }).root;
    }

    function setStatus(text, cls) {
        statusEl.textContent = text;
        statusEl.className = cls || "";
    }

    function appendLog(line, cls) {
        const prefix = cls === "msg-priv" ? "[PRIV] " : "";
        logEl.value += (logEl.value ? "\n" : "") + prefix + line;
        logEl.scrollTop = logEl.scrollHeight;
    }

    function attachmentToDataUrl(att) {
        if (!att || !att.data || att.data.length === 0) return null;
        const fmt = att.format;
        const mime = fmt === 2 ? "image/png" : "image/jpeg";
        const bytes = att.data instanceof Uint8Array ? att.data : new Uint8Array(att.data);
        let bin = "";
        for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
        return "data:" + mime + ";base64," + btoa(bin);
    }

    function readFileAsAttachment(file) {
        return new Promise((resolve, reject) => {
            if (!file) { resolve(null); return; }
            if (file.size > MAX_IMAGE_BYTES) {
                reject(new Error("Image must be <= 1 MB"));
                return;
            }
            if (file.type !== "image/png" && file.type !== "image/jpeg") {
                reject(new Error("Only PNG/JPEG are allowed"));
                return;
            }
            const r = new FileReader();
            r.onload = () => {
                const arr = new Uint8Array(r.result);
                resolve({
                    format: file.type === "image/png" ? 2 : 1,
                    data: arr,
                    fileName: file.name
                });
            };
            r.onerror = () => reject(r.error);
            r.readAsArrayBuffer(file);
        });
    }

    function renderUserList() {
        userListEl.innerHTML = "";
        if (userIcons.size === 0) {
            userListEl.textContent = "(only you)";
            return;
        }
        userIcons.forEach((info, name) => {
            const span = document.createElement("span");
            span.className = "uitem";
            if (info.dataUrl) {
                const img = document.createElement("img");
                img.src = info.dataUrl;
                span.appendChild(img);
            }
            const txt = document.createElement("span");
            txt.textContent = name;
            span.appendChild(txt);
            userListEl.appendChild(span);
        });
    }

    function showImage(container, dataUrl, caption) {
        container.innerHTML = "";
        if (!dataUrl) return;
        const wrap = document.createElement("div");
        if (caption) {
            const c = document.createElement("div");
            c.textContent = caption;
            c.style.fontSize = "12px";
            wrap.appendChild(c);
        }
        const img = document.createElement("img");
        img.src = dataUrl;
        wrap.appendChild(img);
        container.appendChild(wrap);
    }

    function clearTimers() {
        if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
        if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null; }
    }

    function scheduleReconnect() {
        clearTimers();
        secondsLeft = RECONNECT_SECONDS;
        setStatus("Disconnected. Reconnecting in " + secondsLeft + "s…", "err");
        joinBtn.disabled = true;
        nameIn.disabled = true;
        iconIn.disabled = true;
        countdownTimer = setInterval(() => {
            secondsLeft--;
            if (secondsLeft > 0) {
                setStatus("Disconnected. Reconnecting in " + secondsLeft + "s…", "err");
            }
        }, 1000);
        reconnectTimer = setTimeout(connect, RECONNECT_SECONDS * 1000);
    }

    function connect() {
        clearTimers();
        setStatus("Connecting to " + WS_URL + "…");
        try {
            ws = new WebSocket(WS_URL);
        } catch (e) {
            scheduleReconnect();
            return;
        }
        ws.binaryType = "arraybuffer";

        ws.onopen = () => {
            setStatus("Connected. Please enter a name and join.", "ok");
            joinBtn.disabled = false;
            nameIn.disabled = false;
            iconIn.disabled = false;
        };
        ws.onclose = () => {
            joined = false;
            chatBox.disabled = true;
            joinBox.disabled = false;
            scheduleReconnect();
        };
        ws.onerror = () => {};
        ws.onmessage = onWsMessage;
    }

    function send(clientEnvelopeObj) {
        if (!ws || ws.readyState !== WebSocket.OPEN) return false;
        const ClientEnvelope = proto.lookupType("chat.ClientEnvelope");
        const errMsg = ClientEnvelope.verify(clientEnvelopeObj);
        if (errMsg) {
            console.error("verify:", errMsg);
            return false;
        }
        const msg = ClientEnvelope.create(clientEnvelopeObj);
        const buf = ClientEnvelope.encode(msg).finish();
        ws.send(buf);
        return true;
    }

    function onWsMessage(evt) {
        if (!(evt.data instanceof ArrayBuffer)) return;
        const ServerEnvelope = proto.lookupType("chat.ServerEnvelope");
        let env;
        try {
            env = ServerEnvelope.decode(new Uint8Array(evt.data));
        } catch (e) {
            console.error("decode error", e);
            return;
        }
        if (env.joinResponse) handleJoinResponse(env.joinResponse);
        else if (env.broadcast) handleBroadcast(env.broadcast);
        else if (env.error) handleError(env.error);
        else if (env.presence) handlePresence(env.presence);
    }

    function handleJoinResponse(jr) {
        if (!jr.success) {
            joinErr.textContent = jr.errorMessage || "Failed to join";
            return;
        }
        joinErr.textContent = "";
        joined = true;
        joinBox.disabled = true;
        chatBox.disabled = false;
        setStatus("Joined as '" + myName + "'", "ok");

        userIcons.clear();
        (jr.userIcons || []).forEach(u => {
            userIcons.set(u.name, { dataUrl: attachmentToDataUrl(u.icon) });
        });
        renderUserList();

        logEl.value = "";
        (jr.history || []).forEach(e => renderEntry(e, true));

        if (jr.lastImage && jr.lastImage.data && jr.lastImage.data.length) {
            showImage(incomingEl, attachmentToDataUrl(jr.lastImage), "(last image in chat)");
        }
    }

    function handleBroadcast(bc) {
        if (bc && bc.entry) renderEntry(bc.entry, false);
    }

    function renderEntry(entry, isHistory) {
        const ts = entry.timestamp ? new Date(Number(entry.timestamp)).toLocaleTimeString() : "";
        const priv = entry.isPrivate;
        let line;
        if (priv) {
            if (entry.sender === myName) {
                line = `${ts} you → @${entry.recipient}: ${entry.text}`;
            } else {
                line = `${ts} ${entry.sender} → you (private): ${entry.text}`;
            }
        } else {
            line = `${ts} ${entry.sender}: ${entry.text}`;
        }
        appendLog(line, priv ? "msg-priv" : "");

        if (entry.attachment && entry.attachment.data && entry.attachment.data.length) {
            const url = attachmentToDataUrl(entry.attachment);
            const cap = (isHistory ? "[history] " : "") + entry.sender + (priv ? " (private)" : "");
            showImage(incomingEl, url, cap);
        }
    }

    function handleError(err) {
        const code = err.code;
        const msg  = err.message || "Server error";
        if (!joined) {
            joinErr.textContent = msg;
        } else {
            appendLog("[server error] " + msg, "err");
        }
        console.warn("ErrorResponse:", code, msg);
    }

    function handlePresence(p) {
        if (p.kind === 1) {
            userIcons.delete(p.name);
            appendLog("* " + p.name + " left the chat");
        } else {
            userIcons.set(p.name, { dataUrl: attachmentToDataUrl(p.icon) });
            appendLog("* " + p.name + " joined the chat");
        }
        renderUserList();
    }

    iconIn.addEventListener("change", async () => {
        joinErr.textContent = "";
        const f = iconIn.files && iconIn.files[0];
        if (!f) { pendingIcon = null; return; }
        try {
            pendingIcon = await readFileAsAttachment(f);
        } catch (e) {
            joinErr.textContent = e.message;
            pendingIcon = null;
            iconIn.value = "";
        }
    });

    joinBtn.addEventListener("click", () => {
        const name = nameIn.value.trim();
        joinErr.textContent = "";
        if (!name) { joinErr.textContent = "Name is required"; return; }
        if (name.length > 32) { joinErr.textContent = "Name too long"; return; }
        myName = name;
        userIcons.set(name, { dataUrl: pendingIcon ? attachmentToDataUrl(pendingIcon) : null });
        const join = {
            name: name,
            clientInfo: {
                protocolVersion: "1.0",
                supportsImages: true,
                clientBuild: 1
            }
        };
        if (pendingIcon) join.icon = pendingIcon;
        send({ join: join, requestId: nextRequestId++ });
    });

    fileIn.addEventListener("change", async () => {
        const f = fileIn.files && fileIn.files[0];
        previewEl.innerHTML = "";
        pendingFile = null;
        if (!f) return;
        try {
            pendingFile = await readFileAsAttachment(f);
            showImage(previewEl, attachmentToDataUrl(pendingFile), "(to be sent)");
        } catch (e) {
            appendLog("[error] " + e.message, "err");
            fileIn.value = "";
        }
    });

    async function doSend() {
        if (!joined) return;
        let text = msgIn.value;
        if (text.length === 0 && !pendingFile) return;
        if (text.length > MAX_TEXT) {
            appendLog("[error] message exceeds " + MAX_TEXT + " chars", "err");
            return;
        }
        let recipient = "";
        const m = text.match(/^@(\S+)\s+(.*)$/);
        if (m) { recipient = m[1]; text = m[2]; }
        const msg = { text: text, recipient: recipient };
        if (pendingFile) msg.attachment = pendingFile;
        if (!send({ message: msg, requestId: nextRequestId++ })) {
            appendLog("[error] not connected", "err");
            return;
        }
        msgIn.value = "";
        fileIn.value = "";
        pendingFile = null;
        previewEl.innerHTML = "";
    }

    sendBtn.addEventListener("click", doSend);
    msgIn.addEventListener("keydown", (e) => {
        if (e.key === "Enter") { e.preventDefault(); doSend(); }
    });

    try {
        loadProto();
    } catch (e) {
        setStatus("Failed to load protocol definition: " + e.message, "err");
        return;
    }
    connect();
})();

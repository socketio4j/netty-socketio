/*
 * Copyright (c) 2025 The Socketio4j Project
 * Parent project : Copyright (c) 2012-2025 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
function failUnhandled(kind, error) {
    console.error(`${kind}:`, error && error.stack ? error.stack : error);
    process.exit(1);
}

process.on("uncaughtException", error => failUnhandled("Uncaught exception", error));
process.on("unhandledRejection", reason => failUnhandled("Unhandled rejection", reason));

const parseArgs = () => {
    const args = {};
    process.argv.slice(2).forEach(arg => {
        const [key, value] = arg.split("=");
        args[key.replace(/^--/, "")] = value;
    });
    return args;
};

const args = parseArgs();

const version = args.version;
const port = args.port;
const transport = args.transport;
const scenario = args.scenario;
const namespace = args.namespace || "";

let io;
try {
    ({ io } = require("./client-loader").loadSocketIoClient(version));
} catch (e) {
    console.error(e.message || e);
    process.exit(1);
}

// A Socket.IO client's local "disconnect" event can precede the final polling
// write. Retain the process for this bounded period so the server observes the
// disconnect before Java begins the next isolated parameterized case.
const DISCONNECT_SETTLE_DELAY_MS = 100;
const DISCONNECT_FLUSH_DELAY_MS = 250;
const activeSockets = new Set();
let completed = false;

function createSocket(namespace = "", forceNew = true) {
    const socket = io(`http://localhost:${port}${namespace}`, {
        transports: [transport],
        reconnection: false,
        forceNew,
        upgrade: false
    });

    activeSockets.add(socket);
    socket.once("disconnect", () => activeSockets.delete(socket));
    return socket;
}

function handleConnectError(socket) {
    socket.on("connect_error", err =>
        fail(err && err.message ? err.message : err));
}

function awaitConnect(sockets, callback) {
    let connected = 0;
    let finished = false;

    function onConnect() {
        if (finished) {
            return;
        }

        if (++connected !== sockets.length) {
            return;
        }

        finished = true;
        callback();
    }

    sockets.forEach(socket => {
        socket.once("connect", onConnect);
        handleConnectError(socket);
    });
}

function disconnectAll(...sockets) {
    setTimeout(() => {
        sockets.forEach(socket => {
            if (socket) {
                socket.disconnect();
            }
        });
    }, DISCONNECT_SETTLE_DELAY_MS);
}
const timeout = setTimeout(() => {
    fail("Test timed out");
}, 10000);

function success(message) {
    if (completed) {
        return;
    }

    completed = true;
    clearTimeout(timeout);
    disconnectAll(...activeSockets);
    console.log(message);
    setTimeout(() => process.exit(0),
        DISCONNECT_SETTLE_DELAY_MS + DISCONNECT_FLUSH_DELAY_MS);
}

function fail(message) {
    if (completed) {
        return;
    }

    completed = true;
    clearTimeout(timeout);
    disconnectAll(...activeSockets);
    console.error(message);
    setTimeout(() => process.exit(1),
        DISCONNECT_SETTLE_DELAY_MS + DISCONNECT_FLUSH_DELAY_MS);
}
function getErrorMessage(err) {
    if (typeof err === "string") {
        return err;
    }

    if (err && typeof err.message === "string") {
        return err.message;
    }

    if (err && err.message != null) {
        return String(err.message);
    }

    return String(err);
}

switch (scenario) {

    //
    // NS-001
    //
    case "namespace_connect": {

        const socket = createSocket(namespace);

        socket.on("connect", () => {
            socket.emit("helloEvent", "Hello from JS");
        });

        socket.on("helloResponse", msg => {
            socket.emit("clientNsReceived", "helloResponse", msg);
            setTimeout(() => {
                disconnectAll(socket);
                success("NS-001 PASSED");
            }, 100);
        });

        handleConnectError(socket);

        break;
    }

    //
    // NS-002
    //
    case "namespace_reject": {

        const socket = createSocket(namespace);

        socket.on("connect", () => {
            fail("Should not connect");
        });

        socket.on("connect_error", err => {

            const message = getErrorMessage(err);

            if (message !== "Invalid namespace") {
                fail(`Unexpected error: ${message}`);
                return;
            }

            disconnectAll(socket);
            success("NS-002 PASSED");
        });

        socket.on("error", err => {

            // Socket.IO v1/v2

            const message = getErrorMessage(err);

            if (message !== "Invalid namespace") {
                fail(`Unexpected error: ${message}`);
                return;
            }

            disconnectAll(socket);
            success("NS-002 PASSED");
        });

        break;
    }

    case "namespace_isolation": {

        const socket = createSocket("/chat", false);

        socket.on("connect", () => {
            socket.emit("helloEvent", "Isolation Test");
        });

        socket.on("helloResponse", msg => {
            socket.emit("clientNsReceived", "helloResponse", msg);
            setTimeout(() => {
                disconnectAll(socket);
                success("NS-003 PASSED");
            }, 100);
        });

        handleConnectError(socket);

        break;
    }

    case "namespace_multiple": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        awaitConnect([defaultSocket, chatSocket], () => {
            disconnectAll(defaultSocket, chatSocket);
            success("NS-004 PASSED");
        });

        break;
    }
    case "namespace_force_new": {

        const socket1 = createSocket("", true);
        const socket2 = createSocket("", true);

        awaitConnect([socket1, socket2], () => {
            disconnectAll(socket1, socket2);
            success("NS-005 PASSED");
        });

        break;
    }
    case "namespace_client_disconnect": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        awaitConnect([defaultSocket, chatSocket], () => {
            chatSocket.disconnect();
        });

        chatSocket.on("disconnect", () => {

            defaultSocket.emit("defaultPing", "", ack => {

                if (ack !== "ALIVE") {
                    fail("Unexpected ACK: " + ack);
                    return;
                }

                disconnectAll(defaultSocket);
                success("NS-006A PASSED");
            });

        });

        break;
    }
    case "namespace_server_disconnect": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        awaitConnect([defaultSocket, chatSocket], () => {
            chatSocket.emit("leaveNamespace", "");
        });

        chatSocket.on("prepareDisconnect", () => {
            chatSocket.emit("confirmDisconnect", "");
        });

        chatSocket.on("disconnect", () => {

            defaultSocket.emit("defaultPing", "", ack => {

                if (ack !== "ALIVE") {
                    fail("Unexpected ACK: " + ack);
                    return;
                }

                disconnectAll(defaultSocket);
                success("NS-006B PASSED");
            });

        });

        break;
    }

    case "namespace_event_isolation": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        let defaultReceived = false;
        let chatReceived = false;

        function finish() {
            if (!defaultReceived || !chatReceived) {
                return;
            }

            disconnectAll(defaultSocket, chatSocket);
            success("NS-007 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {
            defaultSocket.emit("fireDefault", "");
            chatSocket.emit("fireChat", "");
        });

        defaultSocket.on("defaultMessage", () => {
            defaultReceived = true;
            finish();
        });

        chatSocket.on("chatMessage", () => {
            chatReceived = true;
            finish();
        });

        //
        // Isolation checks
        //
        defaultSocket.on("chatMessage", () => {
            fail("Default namespace received chatMessage");
        });

        chatSocket.on("defaultMessage", () => {
            fail("Chat namespace received defaultMessage");
        });

        break;
    }
    case "namespace_ack_isolation": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        let defaultAck = false;
        let chatAck = false;

        function finish() {
            if (!defaultAck || !chatAck) {
                return;
            }

            disconnectAll(defaultSocket, chatSocket);
            success("NS-008 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {

            defaultSocket.emit("defaultAck", "", ack => {

                if (ack !== "DEFAULT") {
                    fail("Unexpected default ACK: " + ack);
                    return;
                }

                defaultAck = true;
                finish();
            });

            chatSocket.emit("chatAck", "", ack => {

                if (ack !== "CHAT") {
                    fail("Unexpected chat ACK: " + ack);
                    return;
                }

                chatAck = true;
                finish();
            });

        });

        break;
    }
    case "namespace_binary_isolation": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        const defaultPayload = Buffer.from([1, 2, 3, 4]);
        const chatPayload = Buffer.from([5, 6, 7, 8]);

        let defaultReceived = false;
        let chatReceived = false;

        function finish() {
            if (!defaultReceived || !chatReceived) {
                return;
            }

            disconnectAll(defaultSocket, chatSocket);
            success("NS-010 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {
            defaultSocket.emit("fireDefaultBinary", defaultPayload);
        });

        defaultSocket.on("defaultBinary", data => {

            if (!Buffer.from(data).equals(defaultPayload)) {
                fail("Unexpected default binary payload");
                return;
            }

            defaultReceived = true;

            //
            // Send the second binary event only after the first
            // one has completed.
            //
            chatSocket.emit("fireChatBinary", chatPayload);

            finish();
        });

        chatSocket.on("chatBinary", data => {

            if (!Buffer.from(data).equals(chatPayload)) {
                fail("Unexpected chat binary payload");
                return;
            }

            chatReceived = true;
            finish();
        });

        //
        // Isolation checks
        //

        defaultSocket.on("chatBinary", () => {
            fail("Default namespace received chatBinary");
        });

        chatSocket.on("defaultBinary", () => {
            fail("Chat namespace received defaultBinary");
        });

        break;
    }

    case "namespace_concurrent_binary": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        const defaultPayload = Buffer.from([1, 2, 3, 4]);
        const chatPayload = Buffer.from([5, 6, 7, 8]);

        let defaultReceived = false;
        let chatReceived = false;

        function finish() {
            if (!defaultReceived || !chatReceived) {
                return;
            }

            disconnectAll(defaultSocket, chatSocket);
            success("NS-011 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {

            //
            // Fire both binary events immediately.
            //
            defaultSocket.emit("fireDefaultBinary", defaultPayload);
            chatSocket.emit("fireChatBinary", chatPayload);

        });

        defaultSocket.on("defaultBinary", data => {

            if (!Buffer.from(data).equals(defaultPayload)) {
                fail("Unexpected default binary payload");
                return;
            }

            defaultReceived = true;
            finish();
        });

        chatSocket.on("chatBinary", data => {

            if (!Buffer.from(data).equals(chatPayload)) {
                fail("Unexpected chat binary payload");
                return;
            }

            chatReceived = true;
            finish();
        });

        //
        // Isolation
        //

        defaultSocket.on("chatBinary", () => {
            fail("Default namespace received chatBinary");
        });

        chatSocket.on("defaultBinary", () => {
            fail("Chat namespace received defaultBinary");
        });

        break;
    }

    case "namespace_event_ordering": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        let completed = false;

        function finish() {
            if (completed) {
                return;
            }

            completed = true;

            disconnectAll(defaultSocket, chatSocket);
            success("NS-012 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {

            defaultSocket.emit("sequence", 1);
            chatSocket.emit("sequence", 2);
            defaultSocket.emit("sequence", 3);
            chatSocket.emit("sequence", 4);
            defaultSocket.emit("sequence", 5);

        });

        defaultSocket.on("orderingComplete", finish);
        chatSocket.on("orderingComplete", finish);

        break;
    }

    case "namespace_room_isolation": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        let defaultReceived = false;
        let chatReceived = false;

        function finish() {
            if (!defaultReceived || !chatReceived) {
                return;
            }

            disconnectAll(defaultSocket, chatSocket);
            success("NS-013 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {

            //
            // Same room name in different namespaces
            //
            defaultSocket.emit("joinDefaultRoom", "room1");
            chatSocket.emit("joinChatRoom", "room1");

        });

        defaultSocket.on("defaultRoomMessage", () => {
            defaultReceived = true;
            finish();
        });

        chatSocket.on("chatRoomMessage", () => {
            chatReceived = true;
            finish();
        });

        //
        // Must NEVER happen
        //
        defaultSocket.on("chatRoomMessage", () => {
            fail("Default namespace received chat room broadcast");
        });

        chatSocket.on("defaultRoomMessage", () => {
            fail("Chat namespace received default room broadcast");
        });

        break;
    }

    case "namespace_room_join_leave_isolation": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        let chatReceived = false;

        awaitConnect([defaultSocket, chatSocket], () => {

            defaultSocket.emit("joinDefaultRoom", "room1");
            chatSocket.emit("joinChatRoom", "room1");

            setTimeout(() => {
                defaultSocket.emit("leaveDefaultRoom", "room1");
            }, 50);

        });

        //
        // Default namespace must NOT receive anything after leaving.
        //
        defaultSocket.on("defaultRoomMessage", () => {
            fail("Default namespace received room broadcast after leaving");
        });

        //
        // Chat namespace must still receive its room broadcast.
        //
        chatSocket.on("chatRoomMessage", () => {

            if (chatReceived) {
                return;
            }

            chatReceived = true;

            disconnectAll(defaultSocket, chatSocket);
            success("NS-014 PASSED");
        });

        break;
    }

    case "namespace_broadcast_exclude_sender": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        let defaultReceived = false;
        let chatReceived = false;

        function finish() {
            if (!defaultReceived || !chatReceived) {
                return;
            }

            disconnectAll(defaultSocket, chatSocket);
            success("NS-015 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {

            defaultSocket.emit("broadcastDefault", "");
            chatSocket.emit("broadcastChat", "");

        });

        defaultSocket.on("defaultBroadcast", () => {
            defaultReceived = true;
            finish();
        });

        chatSocket.on("chatBroadcast", () => {
            chatReceived = true;
            finish();
        });

        //
        // Must NEVER happen
        //
        defaultSocket.on("chatBroadcast", () => {
            fail("Default namespace received chat broadcast");
        });

        chatSocket.on("defaultBroadcast", () => {
            fail("Chat namespace received default broadcast");
        });

        break;
    }

    case "namespace_reconnect_isolation": {

        const defaultSocket = createSocket("", false);
        let chatSocket = createSocket("/chat", false);

        let defaultConnected = false;
        let chatConnected = false;
        let reconnected = false;

        function ready() {

            if (!defaultConnected || !chatConnected) {
                return;
            }

            chatSocket.emit("reconnectNamespace", "");
        }

        defaultSocket.on("connect", () => {
            defaultConnected = true;
            ready();
        });

        chatSocket.on("connect", () => {
            chatConnected = true;
            ready();
        });

        chatSocket.on("disconnect", () => {

            chatSocket = createSocket("/chat", false);

            chatSocket.on("connect", () => {

                reconnected = true;

                defaultSocket.emit("defaultPing", "", ack => {

                    if (ack !== "ALIVE") {
                        fail("Unexpected ACK: " + ack);
                        return;
                    }

                    disconnectAll(defaultSocket, chatSocket);
                    success("NS-016 PASSED");
                });

            });

            handleConnectError(chatSocket);
        });

        handleConnectError(defaultSocket);

        break;
    }
    case "namespace_mixed_packets": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        const payload = Buffer.from([1,2,3,4]);

        let textDone = false;
        let binaryDone = false;
        let ackDone = false;

        function finish() {

            if (!textDone || !binaryDone || !ackDone) {
                return;
            }

            disconnectAll(defaultSocket, chatSocket);
            success("NS-017 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {

            defaultSocket.emit("textEvent", "hello");

            chatSocket.emit("binaryEvent", payload);

            defaultSocket.emit("ackEvent", "ping", ack => {

                if (ack !== "ACK_OK") {
                    fail("Unexpected ACK: " + ack);
                    return;
                }

                ackDone = true;
                finish();
            });

        });

        defaultSocket.on("textResponse", msg => {

            if (msg !== "hello") {
                fail("Unexpected text response");
                return;
            }

            textDone = true;
            finish();
        });

        chatSocket.on("binaryResponse", data => {

            if (!Buffer.from(data).equals(payload)) {
                fail("Unexpected binary response");
                return;
            }

            binaryDone = true;
            finish();
        });

        //
        // Isolation checks
        //

        defaultSocket.on("binaryResponse", () =>
            fail("Default namespace received binary response"));

        chatSocket.on("textResponse", () =>
            fail("Chat namespace received text response"));

        break;
    }
    case "namespace_volatile_isolation": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        let defaultReceived = false;
        let chatReceived = false;

        function finish() {

            if (!defaultReceived || !chatReceived) {
                return;
            }

            disconnectAll(defaultSocket, chatSocket);
            success("NS-018 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {

            defaultSocket.emit("fireDefaultVolatile", "");
            chatSocket.emit("fireChatVolatile", "");

        });

        defaultSocket.on("defaultVolatile", () => {
            defaultReceived = true;
            finish();
        });

        chatSocket.on("chatVolatile", () => {
            chatReceived = true;
            finish();
        });

        //
        // Isolation checks
        //
        defaultSocket.on("chatVolatile", () => {
            fail("Default namespace received chat volatile event");
        });

        chatSocket.on("defaultVolatile", () => {
            fail("Chat namespace received default volatile event");
        });

        break;
    }
    case "namespace_mixed_multiplexing": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        const payload = Buffer.from([1, 2, 3, 4]);

        let ackDone = false;
        let binaryDone = false;
        let broadcastDone = false;

        function finish() {

            if (!ackDone || !binaryDone || !broadcastDone) {
                return;
            }

            disconnectAll(defaultSocket, chatSocket);
            success("NS-019 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {

            defaultSocket.emit("ackEvent", "ping", ack => {

                if (ack !== "ACK_OK") {
                    fail("Unexpected ACK: " + ack);
                    return;
                }

                ackDone = true;
                finish();
            });

            chatSocket.emit("binaryEvent", payload);

            defaultSocket.emit("broadcastEvent", "");

        });

        chatSocket.on("binaryResponse", data => {

            if (!Buffer.from(data).equals(payload)) {
                fail("Unexpected binary payload");
                return;
            }

            binaryDone = true;
            finish();
        });

        defaultSocket.on("broadcastResponse", () => {
            broadcastDone = true;
            finish();
        });

        //
        // Isolation checks
        //

        defaultSocket.on("binaryResponse", () =>
            fail("Default namespace received binary response"));

        chatSocket.on("broadcastResponse", () =>
            fail("Chat namespace received default broadcast"));

        break;
    }
    case "namespace_stress_multiplexing": {

        const defaultSocket = createSocket("", false);
        const chatSocket = createSocket("/chat", false);

        let textResponses = 0;
        let binaryResponses = 0;
        let ackResponses = 0;

        const payload = Buffer.from([1,2,3,4]);

        function finish() {

            if (textResponses !== 10 ||
                binaryResponses !== 10 ||
                ackResponses !== 10) {
                return;
            }

            disconnectAll(defaultSocket, chatSocket);
            success("NS-020 PASSED");
        }

        awaitConnect([defaultSocket, chatSocket], () => {

            for (let i = 0; i < 10; i++) {

                defaultSocket.emit("text", "msg-" + i);

                chatSocket.emit("binary", payload);

                defaultSocket.emit("ack", "ack-" + i, ack => {

                    if (ack !== "ACK") {
                        fail("Unexpected ACK: " + ack);
                        return;
                    }

                    ackResponses++;
                    finish();
                });
            }

        });

        defaultSocket.on("textResponse", () => {
            textResponses++;
            finish();
        });

        chatSocket.on("binaryResponse", data => {

            if (!Buffer.from(data).equals(payload)) {
                fail("Unexpected binary payload");
                return;
            }

            binaryResponses++;
            finish();
        });

        break;
    }

    case "namespace_polling_server_disconnect": {

        const socket = createSocket("/chat", false);

        socket.on("disconnect", reason => {

            if (reason !== "io server disconnect") {
                fail("Unexpected disconnect reason: " + reason);
                return;
            }

            success("NS-021 PASSED");
        });

        handleConnectError(socket);

        break;
    }

    default:
        fail(`Unknown scenario: ${scenario}`);
}

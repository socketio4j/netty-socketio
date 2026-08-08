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
const params = new URLSearchParams(location.search);

const transport = params.get("transport") || "websocket";

const HOST = params.get("host") || "http://127.0.0.1:9092";

const TEXT = "Hello SocketIO Browser";

const BINARY = new Uint8Array([
    0, 1, 2, 3, 4, 5, 10, 20, 30, 40,
    50, 60, 70, 80, 90, 100,
    0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
]);

const MIXED = {
    text: TEXT,
    binary: BINARY.slice().buffer,
    number: 42
};

// A Socket.IO client emits its local "disconnect" callback before a browser
// context necessarily finishes writing the namespace disconnect packet. Keep
// the page alive just long enough for that write to leave the browser; Java
// still requires the server to observe every disconnect before this case can
// pass.
const DISCONNECT_FLUSH_DELAY_MS = 100;

console.log("interop.js loaded");

function toUint8Array(data) {

    if (data instanceof Uint8Array) {
        return data;
    }

    if (data instanceof ArrayBuffer) {
        return new Uint8Array(data);
    }

    if (Array.isArray(data)) {
        return Uint8Array.from(data);
    }

    return new Uint8Array(data);
}

function arrayEquals(a, b) {

    a = toUint8Array(a);
    b = toUint8Array(b);

    if (a.length !== b.length) {
        return false;
    }

    for (let i = 0; i < a.length; i++) {
        if (a[i] !== b[i]) {
            return false;
        }
    }

    return true;
}

async function runInterop() {

    console.log("runInterop()");

    try {

        const root = await connect("/");
        const chat = await connect("/chat");

        await testNamespace(root, "/");
        await testNamespace(chat, "/chat");

        await closeSocket(chat);
        await closeSocket(root);

        success();

    } catch (e) {

        console.error(e);

        fail(e.message || String(e));
    }
}

async function testNamespace(socket, namespace) {

    log("");
    log("===============================");
    log(namespace === "/" ? "DEFAULT" : namespace);
    log("===============================");

    await testText(socket);
    await testTextAck(socket);
    await testBinary(socket);
    await testBinaryAck(socket);
    await testMixed(socket);
    await testMixedAck(socket);
}

function connect(namespace) {

    return new Promise((resolve, reject) => {

        const socket = io(HOST + namespace, {
            transports: [transport],
            upgrade: false,
            rememberUpgrade: false,
            reconnection: false
        });

        socket.on("connect", () => {

            log("CONNECTED " + namespace);

            const actual =
                socket.io &&
                socket.io.engine &&
                socket.io.engine.transport
                    ? socket.io.engine.transport.name
                    : transport;

            log("TRANSPORT = " + actual);

            if (actual !== transport) {
                reject(new Error(
                    "Expected transport " +
                    transport +
                    " but got " +
                    actual));
                return;
            }

            resolve(socket);
        });

        socket.on("upgrade", () => {
            reject(new Error("Unexpected websocket upgrade"));
        });

        socket.on("connect_error", reject);
        socket.on("error", reject);

    });
}

function closeSocket(socket) {

    return new Promise((resolve, reject) => {

        let completed = false;
        const timeout = setTimeout(() => {
            if (!completed) {
                completed = true;
                reject(new Error("Timed out waiting for Socket.IO disconnect"));
            }
        }, 1000);

        function finish() {

            if (completed) {
                return;
            }

            completed = true;
            clearTimeout(timeout);
            setTimeout(resolve, DISCONNECT_FLUSH_DELAY_MS);
        }

        socket.once("disconnect", reason => {

            log("DISCONNECTED (" + reason + ")");
            finish();
        });

        socket.close();
    });
}

function testText(socket) {

    log("Running text");

    return new Promise((resolve, reject) => {

        socket.once("textReply", reply => {

            try {

                if (reply !== TEXT) {
                    throw new Error("text mismatch");
                }

                pass("text");
                resolve();

            } catch (e) {
                reject(e);
            }
        });

        socket.emit("text", TEXT);
    });
}

function testTextAck(socket) {

    log("Running textAck");

    return new Promise((resolve, reject) => {

        socket.emit("textAck", TEXT, reply => {

            try {

                if (reply !== TEXT) {
                    throw new Error("textAck mismatch");
                }

                pass("textAck");
                resolve();

            } catch (e) {
                reject(e);
            }
        });
    });
}

function testBinary(socket) {

    log("Running binary");

    return new Promise((resolve, reject) => {

        socket.once("binaryReply", reply => {

            try {

                if (!arrayEquals(reply, BINARY)) {
                    throw new Error("binary mismatch");
                }

                pass("binary");
                resolve();

            } catch (e) {
                reject(e);
            }
        });

        socket.emit("binary", BINARY.slice().buffer);
    });
}

function testBinaryAck(socket) {

    log("Running binaryAck");

    return new Promise((resolve, reject) => {

        socket.emit("binaryAck", BINARY.slice().buffer, reply => {

            try {

                if (!arrayEquals(reply, BINARY)) {
                    throw new Error("binaryAck mismatch");
                }

                pass("binaryAck");
                resolve();

            } catch (e) {
                reject(e);
            }
        });
    });
}

function testMixed(socket) {

    log("Running mixed");

    return new Promise((resolve, reject) => {

        socket.once("mixedReply", reply => {

            try {

                if (reply.text !== TEXT) {
                    throw new Error("mixed text mismatch");
                }

                if (reply.number !== 42) {
                    throw new Error("mixed number mismatch");
                }

                if (!arrayEquals(reply.binary, BINARY)) {
                    throw new Error("mixed binary mismatch");
                }

                pass("mixed");
                resolve();

            } catch (e) {
                reject(e);
            }
        });

        socket.emit("mixed", MIXED);
    });
}

function testMixedAck(socket) {

    log("Running mixedAck");

    return new Promise((resolve, reject) => {

        socket.emit("mixedAck", MIXED, reply => {

            try {

                if (reply.text !== TEXT) {
                    throw new Error("mixedAck text mismatch");
                }

                if (reply.number !== 42) {
                    throw new Error("mixedAck number mismatch");
                }

                if (!arrayEquals(reply.binary, BINARY)) {
                    throw new Error("mixedAck binary mismatch");
                }

                pass("mixedAck");
                resolve();

            } catch (e) {
                reject(e);
            }
        });
    });
}

if (typeof io === "undefined") {

    fail("Socket.IO client failed to load");

} else {

    runInterop();
}

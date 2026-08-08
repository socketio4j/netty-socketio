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

const minimist = require("minimist");

function failUnhandled(kind, error) {
    console.error(`${kind}:`, error && error.stack ? error.stack : error);
    process.exit(1);
}

process.on("uncaughtException", error => failUnhandled("Uncaught exception", error));
process.on("unhandledRejection", reason => failUnhandled("Unhandled rejection", reason));

const args = minimist(process.argv.slice(2), {
    string: ["version", "port", "scenario"]
});

const version = String(args.version);
const port = Number(args.port);
const scenario = args.scenario;

if (!version || !port || !scenario) {
    console.error(
        "Usage: node test-clients-transport.js " +
        "--version=<exact Socket.IO client version> " +
        "--port=<port> " +
        "--scenario=<scenario>"
    );
    process.exit(1);
}

function loadSocketIoClient(version) {
    return require("./client-loader").loadSocketIoClient(version).io;
}

const io = loadSocketIoClient(version);

const TEST_TIMEOUT_MS = 15_000;
let completed = false;
let testTimeout;
let lastObservedTransport = "not connected";

function activeTransport(socket) {
    const engine = socket && socket.io && socket.io.engine;
    const transport = engine && engine.transport;
    return transport && transport.name ? transport.name : "unknown";
}

function finish(exitCode, message) {
    if (completed) {
        return;
    }

    completed = true;
    clearTimeout(testTimeout);

    if (message) {
        console.error(message);
    }

    process.exit(exitCode);
}

function fail(message) {
    finish(1, message + " (last transport: " + lastObservedTransport + ")");
}

function success(socket) {
    // The Java test independently requires the server-side disconnect event.
    // Do not wait indefinitely for a legacy client's local disconnect callback:
    // Socket.IO 1.x/2.x can close the transport without delivering that callback.
    clearTimeout(testTimeout);
    socket.disconnect();
    setTimeout(() => finish(0), 250);
}

function attachCommonHandlers(socket) {

    socket.on("disconnect", reason => {

        if (reason !== "io client disconnect") {
            fail("Unexpected disconnect: " + reason);
        }

        finish(0);
    });

    socket.on("connect_error", err => {
        fail("Connect error: " + err.message);
    });

    socket.on("error", err => {
        fail("Socket error: " + err);
    });
}

function createSocket() {
    return io(`http://127.0.0.1:${port}`, {
        transports: ["polling", "websocket"],
        upgrade: true,
        rememberUpgrade: false
    });
}

function waitForUpgrade(socket, callback) {

    let attempts = 0;
    const maxAttempts = 100;

    function check() {

        socket.emit("whoAreYou", "", transport => {

            lastObservedTransport = transport || activeTransport(socket);

            if (transport === "websocket") {
                callback();
                return;
            }

            if (++attempts >= maxAttempts) {
                fail("Transport never upgraded");
            }

            setTimeout(check, 50);
        });
    }

    check();
}

/**
 * UPGRADE-001
 */
function runTransportUpgrade() {

    const socket = createSocket();

    attachCommonHandlers(socket);

    socket.on("connect", () => {

        lastObservedTransport = activeTransport(socket);

        waitForUpgrade(socket, () => {
            success(socket);
        });

    });
}
testTimeout = setTimeout(() => {
    fail("Transport upgrade test timed out");
}, TEST_TIMEOUT_MS);

switch (scenario) {

    case "transport_upgrade":
        runTransportUpgrade();
        break;

    default:
        fail("Unknown scenario: " + scenario);
}

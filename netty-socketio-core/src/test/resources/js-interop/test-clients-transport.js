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

function fail(message) {
    console.error(message);
    process.exit(1);
}

function success(socket) {
    socket.close();
}

function attachCommonHandlers(socket) {

    socket.on("disconnect", reason => {

        if (reason !== "io client disconnect") {
            fail("Unexpected disconnect: " + reason);
        }

        process.exit(0);
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

        waitForUpgrade(socket, () => {
            success(socket);
        });

    });
}



switch (scenario) {

    case "transport_upgrade":
        runTransportUpgrade();
        break;

    default:
        fail("Unknown scenario: " + scenario);
}

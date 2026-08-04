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
const clientCount = parseInt(args.clients || "2", 10);

let io;

switch (version) {
    case "1":
        io = require("socket.io-client-v1");
        break;
    case "2":
        io = require("socket.io-client-v2");
        break;
    case "3":
        io = require("socket.io-client-v3");
        break;
    case "4":
        io = require("socket.io-client-v4");
        break;
    default:
        console.error("Unsupported version:", version);
        process.exit(1);
}

const url = `http://localhost:${port}`;

const options = {
    transports: [transport],
    reconnection: false,
    forceNew: true
};

const timeout = setTimeout(() => {
    console.error("Test timed out");
    disconnectAll();
    process.exit(1);
}, 10000);

const clients = [];

for (let i = 0; i < clientCount; i++) {
    clients.push({
        id: i,
        socket: io(url, options),
        connected: false
    });
}

function disconnectAll(exitCode, message, isError) {
    let remaining = clients.length;

    if (remaining === 0) {
        if (isError) {
            console.error(message);
        } else {
            console.log(message);
        }
        process.exit(exitCode);
        return;
    }

    clients.forEach(client => {
        const finish = () => {
            if (--remaining === 0) {
                if (isError) {
                    console.error(message);
                } else {
                    console.log(message);
                }
                process.exit(exitCode);
            }
        };

        if (client.socket.connected) {
            client.socket.once("disconnect", finish);
            client.socket.disconnect();
        } else {
            finish();
        }
    });
}

function success(message) {
    clearTimeout(timeout);
    disconnectAll(0, message, false);
}

function fail(message) {
    clearTimeout(timeout);
    disconnectAll(1, message, true);
}


Promise.all(
    clients.map(client =>
        new Promise((resolve, reject) => {

            client.socket.on("connect", () => {
                client.connected = true;
                console.log(`Client ${client.id} connected`);
                resolve();
            });

            client.socket.on("connect_error", reject);

        })
    )
).then(() => {

    console.log("All clients connected");

    switch (args.scenario) {

        case "broadcast_all": {

            const received = new Array(clients.length).fill(0);

            clients.forEach((client, index) => {

                client.socket.on("broadcastMessage", msg => {

                    if (msg !== "hello_everyone") {
                        fail(`Unexpected message for client ${index}`);
                    }

                    received[index]++;

                    if (received[index] > 1) {
                        fail(`Duplicate delivery for client ${index}`);
                    }

                    if (received.every(c => c === 1)) {
                        success("BCAST-001 PASSED");
                    }
                });

            });

            clients.forEach(client => {
                client.socket.emit("start", "");
            });

            break;
        }

        case "broadcast_exclude_client": {

            const received = new Array(clients.length).fill(0);

            clients.forEach((client, index) => {

                client.socket.on("broadcastMessage", msg => {

                    if (msg !== "hello_everyone") {
                        fail(`Unexpected message for client ${index}`);
                    }

                    received[index]++;

                    if (received[index] > 1) {
                        fail(`Duplicate delivery for client ${index}`);
                    }

                });

            });

            // Client 0 initiates the broadcast and will be excluded.
            setTimeout(() => {
                clients[0].socket.emit("start", "");
            }, 100);

            setTimeout(() => {

                if (received[0] !== 0) {
                    fail("Excluded client should not receive the broadcast");
                }

                if (received[1] !== 1) {
                    fail("Client 1 should receive the broadcast");
                }

                if (received[2] !== 1) {
                    fail("Client 2 should receive the broadcast");
                }

                success("BCAST-002 PASSED");

            }, 500);

            break;
        }
        case "broadcast_exclude_predicate": {

            const received = new Array(clients.length).fill(0);

            clients.forEach((client, index) => {

                client.socket.on("broadcastMessage", msg => {

                    if (msg !== "hello_everyone") {
                        fail(`Unexpected message for client ${index}`);
                    }

                    received[index]++;

                    if (received[index] > 1) {
                        fail(`Duplicate delivery for client ${index}`);
                    }

                });

            });

            // Client 0 is excluded by the predicate.
            setTimeout(() => {
                clients[0].socket.emit("start", "");
            }, 100);

            setTimeout(() => {

                if (received[0] !== 0) {
                    fail("Predicate-excluded client should not receive the broadcast");
                }

                if (received[1] !== 1) {
                    fail("Client 1 should receive the broadcast");
                }

                if (received[2] !== 1) {
                    fail("Client 2 should receive the broadcast");
                }

                success("BCAST-003 PASSED");

            }, 500);

            break;
        }
        case "broadcast_room": {

            const received = new Array(clients.length).fill(0);

            clients.forEach((client, index) => {

                client.socket.on("roomMessage", msg => {

                    if (msg !== "hello_room") {
                        fail(`Unexpected message for client ${index}`);
                    }

                    received[index]++;

                    if (received[index] > 1) {
                        fail(`Duplicate delivery for client ${index}`);
                    }

                });

            });

            setTimeout(() => {

                // Client0 joins roomA
                clients[0].socket.emit("start", "roomA");

                // Client1 joins roomA
                clients[1].socket.emit("start", "roomA");

                // Client2 joins nothing
                clients[2].socket.emit("start", "");

            }, 100);

            setTimeout(() => {

                if (received[0] !== 1) {
                    fail("Client0 should receive room broadcast");
                }

                if (received[1] !== 1) {
                    fail("Client1 should receive room broadcast");
                }

                if (received[2] !== 0) {
                    fail("Client2 should not receive room broadcast");
                }

                success("BCAST-004 PASSED");

            }, 500);

            break;
        }

        case "broadcast_empty_room": {

            let received = false;

            clients.forEach((client, index) => {

                client.socket.on("roomMessage", msg => {
                    console.error(`Client ${index} unexpectedly received: ${msg}`);
                    received = true;
                });

            });

            setTimeout(() => {

                clients.forEach(client => {
                    client.socket.emit("start", "");
                });

            }, 100);

            setTimeout(() => {

                if (received) {
                    fail("Broadcast to empty room should not be delivered");
                }

                success("BCAST-005 PASSED");

            }, 500);

            break;
        }

        case "broadcast_nonexistent_room": {

            let received = false;

            clients.forEach((client, index) => {

                client.socket.on("roomMessage", msg => {
                    console.error(`Client ${index} unexpectedly received: ${msg}`);
                    received = true;
                });

            });

            setTimeout(() => {

                clients.forEach(client => {
                    client.socket.emit("start", "");
                });

            }, 100);

            setTimeout(() => {

                if (received) {
                    fail("Broadcast to non-existent room should not be delivered");
                }

                success("BCAST-006 PASSED");

            }, 500);

            break;
        }

        default:
            fail(`Unknown scenario: ${args.scenario}`);
    }

}).catch(fail);
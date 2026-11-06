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
const clientCount = parseInt(args.clients || "2", 10);

let io;
try {
    ({ io } = require("./client-loader").loadSocketIoClient(version));
} catch (e) {
    console.error(e.message || e);
    process.exit(1);
}

const url = `http://localhost:${port}`;

const options = {
    transports: [transport],
    reconnection: false,
    forceNew: true
};

// A local Socket.IO "disconnect" event is not proof that a polling client has
// sent its disconnect packet. Give the final poll a bounded chance to flush so
// the Java test barrier can prove server-side cleanup before the next case.
const DISCONNECT_FLUSH_DELAY_MS = 250;

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
                setTimeout(() => process.exit(exitCode), DISCONNECT_FLUSH_DELAY_MS);
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
            clients.forEach((client, index) => {
                client.socket.on("broadcastMessage", msg => {
                    client.socket.emit("clientReceivedBroadcast", client.id, msg);
                });
            });

            clients.forEach(client => {
                client.socket.emit("start", "");
            });

            setTimeout(() => {
                success("BCAST-001 PASSED");
            }, 500);

            break;
        }

        case "broadcast_exclude_client": {
            clients.forEach((client, index) => {
                client.socket.on("broadcastMessage", msg => {
                    client.socket.emit("clientReceivedBroadcast", client.id, msg);
                });
            });

            // Client 0 initiates the broadcast and will be excluded.
            setTimeout(() => {
                clients[0].socket.emit("start", "");
            }, 100);

            setTimeout(() => {
                success("BCAST-002 PASSED");
            }, 500);

            break;
        }
        case "broadcast_exclude_predicate": {
            clients.forEach((client, index) => {
                client.socket.on("broadcastMessage", msg => {
                    client.socket.emit("clientReceivedBroadcast", client.id, msg);
                });
            });

            // Client 0 is excluded by the predicate.
            setTimeout(() => {
                clients[0].socket.emit("start", "");
            }, 100);

            setTimeout(() => {
                success("BCAST-003 PASSED");
            }, 500);

            break;
        }
        case "broadcast_room": {
            clients.forEach((client, index) => {
                client.socket.on("roomMessage", msg => {
                    client.socket.emit("clientReceivedRoomMessage", client.id, msg);
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
                success("BCAST-004 PASSED");
            }, 500);

            break;
        }

        case "broadcast_empty_room": {
            clients.forEach((client, index) => {
                client.socket.on("roomMessage", msg => {
                    client.socket.emit("clientReceivedRoomMessage", client.id, msg);
                });
            });

            setTimeout(() => {
                clients.forEach(client => {
                    client.socket.emit("start", "");
                });
            }, 100);

            setTimeout(() => {
                success("BCAST-005 PASSED");
            }, 500);

            break;
        }

        case "broadcast_nonexistent_room": {
            clients.forEach((client, index) => {
                client.socket.on("roomMessage", msg => {
                    client.socket.emit("clientReceivedRoomMessage", client.id, msg);
                });
            });

            setTimeout(() => {
                clients.forEach(client => {
                    client.socket.emit("start", "");
                });
            }, 100);

            setTimeout(() => {
                success("BCAST-006 PASSED");
            }, 500);

            break;
        }

        default:
            fail(`Unknown scenario: ${args.scenario}`);
    }

}).catch(fail);

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

function failFast(reason) {
    console.error("Critical test setup failure:", reason);
    process.exit(1);
}

const parseArgs = () => {
    const args = {};
    process.argv.slice(2).forEach(arg => {
        const [key, value] = arg.split('=');
        args[key.replace(/^--/, '')] = value;
    });
    return args;
};

const args = parseArgs();
const version = args.version;
if (!version) {
    failFast("Missing required --version argument");
}
const port = args.port || '8080';
const transport = args.transport;
if (!transport) {
    failFast("Missing required --transport argument");
}
const scenario = args.scenario;
if (!scenario) {
    failFast("Missing required --scenario argument");
}

let connected = false;
const exitProcess = process.exit.bind(process);
process.exit = code => {
    if ((code === undefined || code === 0) && !connected) {
        console.error("Refusing success before Socket.IO connection is established");
        exitProcess(1);
        return;
    }
    exitProcess(code);
};

console.log(`Running JS Client Interop Test: version=v${version}, port=${port}, transport=${transport}, scenario=${scenario}`);

let io;
let clientPackage;
let pkg;
try {
    ({ io, clientPackage, packageMetadata: pkg } =
        require("./client-loader").loadSocketIoClient(version));
} catch (e) {
    failFast(e.message || e);
}

console.log("====================================");
console.log("Requested client :", version);
console.log("Package name     :", pkg.name);
console.log("Package version  :", pkg.version);
console.log("====================================");
const url = `http://localhost:${port}`;
const options = {
    transports: [transport],
    reconnection: false,
    forceNew: true
};

const socket = io(url, options);

const timeout = setTimeout(() => {
    console.error('Test timed out');
    socket.disconnect();
    process.exit(1);
}, 10000);

// A polling client queues its Socket.IO disconnect in the next poll request. Keep
// Node alive long enough for that request to reach the server before reporting
// success. The local "disconnect" event is not proof that an older polling client
// has flushed that request.
const DISCONNECT_SETTLE_DELAY_MS = 100;
const DISCONNECT_FLUSH_DELAY_MS = 250;
let completed = false;

function success(message) {
    if (completed) {
        return;
    }

    completed = true;
    clearTimeout(timeout);
    console.log(message);
    setTimeout(() => {
        socket.disconnect();
        setTimeout(() => process.exit(0), DISCONNECT_FLUSH_DELAY_MS);
    }, DISCONNECT_SETTLE_DELAY_MS);
}

socket.on('connect', () => {
    connected = true;
    console.log(`[v${version} JS Client] Connected successfully via ${transport}`);
    console.log("Socket.IO package :", pkg.version);

    if (socket.io && socket.io.engine) {
        console.log("Transport        :", socket.io.engine.transport.name);

        try {
            const eio = require(`${clientPackage}/node_modules/engine.io-client/package.json`);
            console.log("Engine.IO client:", eio.version);
        } catch (e) {
            console.log("Engine.IO package not directly accessible");
        }
    }
    const transportObj = socket.io.engine.transport;

    console.log("Transport:", transportObj.name);

    if (transportObj && transportObj.query) {
        console.log("EIO:", transportObj.query.EIO);
    }

    console.log("Transport object:", transportObj);
    if (scenario === 'connect') {
        // Socket.IO 1.x over polling can deliver its namespace CONNECT and a
        // following DISCONNECT in separate requests. Do not make them race:
        // first let the confirmed connection settle on the server, then flush
        // the disconnect through the normal success path.
        setTimeout(() => success('Connect scenario PASSED'), 100);
    }

    if (scenario === 'text') {
        socket.emit('testText', 'hello from js client v' + version);
    }

    if (scenario === 'ack') {
        socket.emit('testAck', 'ping_ack_data', (response) => {
            console.log(`[v${version} JS Client] Received ack response:`, response);
            socket.emit('clientAckResponse', response);
            setTimeout(() => {
                success('Ack scenario PASSED');
            }, 100);
        });
    }

    if (scenario === 'ack_binary') {
        socket.emit('testAckBinary', 'ping_ack_binary_data', (response) => {
            console.log(`[v${version} JS Client] Received ack_binary response:`, response);
            socket.emit('clientAckBinaryResponse', response);
            setTimeout(() => {
                success('Ack binary scenario PASSED');
            }, 100);
        });
    }

    if (scenario === 'binary') {
        const buf = Buffer.from([10, 20, 30, 40, 50]);
        socket.emit('testBinary', buf);
    }

    if (scenario === 'multi_binary') {
        const buf1 = Buffer.from([1, 2, 3]);
        const buf2 = Buffer.from([4, 5, 6]);
        socket.emit('testMultiBinary', buf1, buf2);
    }

    if (scenario === 'object') {
        // Test untyped/Map object deserialization
        socket.emit('testObject', { name: 'hello', value: 42 });
    }

    if (scenario === 'pojo') {
        // Test typed POJO deserialization
        socket.emit('testPojo', { name: 'hello', value: 42 });
    }

    if (scenario === 'complex_pojo') {
        // Test multi-level nested real-life complex POJO deserialization
        const complexOrder = {
            orderId: 'ORD-98765',
            totalAmount: 149.98,
            customer: {
                customerId: 'CUST-001',
                email: 'alice@example.com',
                vipStatus: true
            },
            items: [
                { sku: 'ITEM-A', quantity: 2, unitPrice: 49.99 },
                { sku: 'ITEM-B', quantity: 1, unitPrice: 50.00 }
            ],
            metadata: {
                source: 'mobile_app',
                env: 'production'
            }
        };
        socket.emit('testComplexPojo', complexOrder);
    }

    if (scenario === 'mixed') {
        // Test heterogeneous args: String + Binary together (MultiTypeEventListener)
        const buf = Buffer.from([7, 8, 9]);
        socket.emit('testMixed', 'hello_text', buf);
    }
});

socket.on('textResponse', (data) => {
    console.log(`[v${version} JS Client] Received textResponse:`, data);
    socket.emit('clientTextResponse', data);
    setTimeout(() => {
        success('Text scenario PASSED');
    }, 100);
});

socket.on('binaryResponse', (data) => {
    console.log(`[v${version} JS Client] Received binaryResponse:`, data);
    socket.emit('clientBinaryResponse', data);
    setTimeout(() => {
        success('Binary scenario PASSED');
    }, 100);
});

socket.on('objectResponse', (data) => {
    console.log(`[v${version} JS Client] Received objectResponse:`, data);
    socket.emit('clientObjectResponse', data);
    setTimeout(() => {
        success('Object scenario PASSED');
    }, 100);
});

socket.on('pojoResponse', (data) => {
    console.log(`[v${version} JS Client] Received pojoResponse:`, data);
    socket.emit('clientPojoResponse', data);
    setTimeout(() => {
        success('POJO scenario PASSED');
    }, 100);
});

socket.on('complexPojoResponse', (data) => {
    console.log(`[v${version} JS Client] Received complexPojoResponse:`, data);
    socket.emit('clientComplexPojoResponse', data);
    setTimeout(() => {
        success('Complex POJO scenario PASSED');
    }, 100);
});

socket.on('mixedResponse', (text, binData) => {
    console.log(`[v${version} JS Client] Received mixedResponse:`, text, binData);
    socket.emit('clientMixedResponse', text, binData);
    setTimeout(() => {
        success('Mixed scenario PASSED');
    }, 100);
});

if (scenario === 'server_ack_text') {
    socket.on('serverReqAckText', (data, callback) => {
        console.log(`[v${version} JS Client] Received serverReqAckText:`, data);
        if (data === 'hello_from_server' && typeof callback === 'function') {
            callback('js_ack_text_reply');
            setTimeout(() => {
                success('Server req ACK text scenario PASSED');
            }, 500);
        } else {
            console.error('serverReqAckText mismatch or missing callback:', data, typeof callback);
            process.exit(1);
        }
    });
}

if (scenario === 'server_ack_binary') {
    socket.on('serverReqAckBinary', (data, callback) => {
        console.log(`[v${version} JS Client] Received serverReqAckBinary:`, data);
        if (data === 'hello_for_binary_ack' && typeof callback === 'function') {
            callback(Buffer.from([55, 66, 77]));
            setTimeout(() => {
                success('Server req ACK binary scenario PASSED');
            }, 500);
        } else {
            console.error('serverReqAckBinary mismatch or missing callback:', data, typeof callback);
            process.exit(1);
        }
    });
}

if (scenario === 'server_ack_void') {
    socket.on('serverReqVoidAck', (data, callback) => {
        console.log(`[v${version} JS Client] Received serverReqVoidAck:`, data);
        if (data === 'hello_void' && typeof callback === 'function') {
            callback(); // no arguments (Void ACK)
            setTimeout(() => {
                success('Server req Void ACK scenario PASSED');
            }, 500);
        } else {
            console.error('serverReqVoidAck mismatch or missing callback:', data, typeof callback);
            process.exit(1);
        }
    });
}

if (scenario === 'server_ack_multi') {
    socket.on('serverReqMultiAck', (data, callback) => {
        console.log(`[v${version} JS Client] Received serverReqMultiAck:`, data);
        if (data === 'hello_multi' && typeof callback === 'function') {
            callback('reply_string', Buffer.from([88, 99])); // Heterogeneous multi-type ACK (String + Buffer)
            setTimeout(() => {
                success('Server req MultiType ACK scenario PASSED');
            }, 500);
        } else {
            console.error('serverReqMultiAck mismatch or missing callback:', data, typeof callback);
            process.exit(1);
        }
    });
}

socket.on('connect_error', (err) => {
    console.error('Connection error:', err);
    clearTimeout(timeout);
    process.exit(1);
});
if (scenario === "join_room") {

    socket.emit("joinRoom", "room1");

    socket.on("roomMessage", (msg) => {

        console.log("Received:", msg);

        if (msg === "hello room") {
            success("Join room scenario PASSED");
            return;
        }

        process.exit(1);
    });
}
if (scenario === "leave_room") {

    socket.emit("joinLeaveRoom", "room1");

    socket.on("roomMessage", (msg) => {
        console.error("Received unexpected room message:", msg);
        process.exit(1);
    });

    socket.on("done", () => {
        success("Leave room scenario PASSED");
    });
}
if (scenario === "join_same_room_twice") {

    let received = 0;

    socket.emit("joinSameRoomTwice", "room1");

    socket.on("roomMessage", (msg) => {

        received++;

        if (received > 1) {
            console.error("Duplicate room delivery");
            process.exit(1);
        }

        if (msg !== "hello room") {
            console.error("Unexpected message:", msg);
            process.exit(1);
        }

        setTimeout(() => {

            if (received !== 1) {
                console.error("Expected exactly one room message, got", received);
                process.exit(1);
            }

            success("Join same room twice PASSED");

        }, 300);
    });
}
if (scenario === "leave_unknown_room") {

    socket.emit("leaveUnknownRoom", "roomB");

    socket.on("roomMessage", (msg) => {

        if (msg !== "hello_roomA") {
            console.error("Unexpected message:", msg);
            process.exit(1);
        }

        success("ROOM-004 PASSED");
    });
}

if (scenario === "join_multiple_rooms") {

    let roomAReceived = false;
    let roomBReceived = false;

    socket.emit("joinMultipleRooms", "");

    socket.on("roomAMessage", (msg) => {
        if (msg !== "hello_roomA") {
            process.exit(1);
        }

        roomAReceived = true;

        if (roomAReceived && roomBReceived) {
            success("ROOM-005 PASSED");
        }
    });

    socket.on("roomBMessage", (msg) => {
        if (msg !== "hello_roomB") {
            process.exit(1);
        }

        roomBReceived = true;

        if (roomAReceived && roomBReceived) {
            success("ROOM-005 PASSED");
        }
    });
}
if (scenario === "leave_one_room") {

    let roomAReceived = false;
    let roomBReceived = false;

    socket.emit("leaveOneRoom", "");

    socket.on("roomAMessage", () => {
        roomAReceived = true;
    });

    socket.on("roomBMessage", (msg) => {

        if (msg !== "hello_roomB") {
            process.exit(1);
        }

        roomBReceived = true;

        setTimeout(() => {

            if (roomAReceived) {
                console.error("Received roomA message after leaving roomA");
                process.exit(1);
            }

            if (!roomBReceived) {
                console.error("Did not receive roomB message");
                process.exit(1);
            }

            success("ROOM-006 PASSED");

        }, 300);
    });
}
if (scenario === "leave_all_rooms") {

    socket.emit("leaveAllRooms", "");

    let received = false;

    socket.on("roomAMessage", () => received = true);
    socket.on("roomBMessage", () => received = true);
    socket.on("roomCMessage", () => received = true);

    // Wait a little to ensure no messages arrive.
    setTimeout(() => {

        if (received) {
            console.error("Received room message after leaving all rooms");
            process.exit(1);
        }

        success("ROOM-007 PASSED");

    }, 500);
}

if (scenario === "disconnect_rooms") {

    socket.emit("joinAndDisconnect", "");

    socket.on("disconnectNow", () => {
        socket.disconnect();
    });

    socket.on("roomAMessage", () => {
        console.error("Received roomA message after disconnect");
        process.exit(1);
    });

    socket.on("roomBMessage", () => {
        console.error("Received roomB message after disconnect");
        process.exit(1);
    });

    socket.on("disconnect", () => {
        setTimeout(() => {
            success("ROOM-008 PASSED");
        }, 300);
    });
}
if (scenario === "server_batch_text_binary_text") {

    const received = [];

    socket.on("batchText1", (msg) => {

        if (msg !== "TEXT1") {
            console.error("batchText1 mismatch:", msg);
            process.exit(1);
        }

        received.push("TEXT1");
        checkDone();
    });

    socket.on("batchBinary", (data) => {

        const buf = Buffer.from(data);

        if (buf.length !== 5
            || buf[0] !== 1
            || buf[1] !== 2
            || buf[2] !== 3
            || buf[3] !== 4
            || buf[4] !== 5) {

            console.error("Binary payload mismatch:", buf);
            process.exit(1);
        }

        received.push("BIN");
        checkDone();
    });

    socket.on("batchText2", (msg) => {

        if (msg !== "TEXT2") {
            console.error("batchText2 mismatch:", msg);
            process.exit(1);
        }

        received.push("TEXT2");
        checkDone();
    });

    function checkDone() {

        if (received.length !== 3) {
            return;
        }

        if (received[0] !== "TEXT1"
            || received[1] !== "BIN"
            || received[2] !== "TEXT2") {

            console.error("Packet ordering incorrect:", received);
            process.exit(1);
        }

        socket.emit("clientBatchDone", received.join(","));
        setTimeout(() => {
            success("Server batch text/binary/text PASSED");
        }, 100);
    }
}

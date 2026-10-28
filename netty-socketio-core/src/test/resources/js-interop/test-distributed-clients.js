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
        const [key, value] = arg.split('=');
        args[key.replace(/^--/, '')] = value;
    });
    return args;
};

const args = parseArgs();

const clientName = args.clientName || 'client1';

function failFast(reason, details = null) {
    console.error(`[${clientName || "client"} CRITICAL FAILURE] ${reason}`,
        details ? JSON.stringify(details) : "");
    process.exit(1);
}

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
const targetRoom = args.room;
if (!targetRoom) {
    failFast("Missing required --room argument");
}
const customNamespace = args.namespace || '';

process.on('uncaughtException', (err) => failFast('Uncaught Exception', err.stack || err));
process.on('unhandledRejection', (reason) => failFast('Unhandled Rejection', reason));

let io;
try {
    io = require("./client-loader").loadSocketIoClient(version).io;
} catch (e) {
    failFast(`Failed to load Socket.IO client ${version}`, e.message);
}

const url = `http://localhost:${port}${customNamespace}`;
const socket = io(url, {
    transports: [transport],
    reconnection: false,
    forceNew: true
});

const receivedEvents = [];
const timeoutMs = args.timeout ? parseInt(args.timeout, 10) : 35000;

const timeout = setTimeout(() => {
    failFast(`Test timed out after ${timeoutMs}ms. Received ${receivedEvents.length} events:`, receivedEvents);
}, timeoutMs);

let joinedRoomOk = false;
let leftRoomOk = false;

// The distributed matrix terminates many polling clients concurrently. Allow a
// full scheduling turn for their final POST/poll exchange before process exit;
// the Java suite still proves server-side removal rather than trusting this.
const DISCONNECT_FLUSH_DELAY_MS = 1000;
let closing = false;

const exitGracefully = (code = 0, delayMs = 300) => {
    clearTimeout(timeout);
    setTimeout(() => {
        closing = true;
        // Close the shared Manager once. Calling socket.disconnect() first
        // removes the server session, then manager.close() can race with a
        // final polling request and receive a spurious 400 for that SID.
        if (socket.io && typeof socket.io.close === "function") {
            socket.io.close();
        } else if (socket.io && socket.io.engine
                && typeof socket.io.engine.close === "function") {
            socket.io.engine.close();
        } else {
            socket.disconnect();
        }
        setTimeout(() => process.exit(code), DISCONNECT_FLUSH_DELAY_MS);
    }, delayMs);
};

// --- LIFECYCLE & TRANSPORT ERROR HANDLERS ---
socket.on('connect_error', (err) => failFast('Connection Error', err.message || err));
socket.on('error', (err) => {
    if (!closing) {
        failFast('Socket Error', err);
    }
});
socket.on('disconnect', (reason) => {
    if (!closing && (reason === 'io server disconnect' || reason === 'transport close') && !process.exitCode) {
        failFast('Unexpected Disconnect', reason);
    }
});

socket.on('connect', () => {
    console.log(`[${clientName} v${version}] Connected to ${url} via ${transport}, joining room: ${targetRoom}`);
    if (!joinedRoomOk) {
        socket.emit('join-room', targetRoom);
    }
});

socket.on('join-ok', (roomName) => {
    if (!joinedRoomOk) {
        joinedRoomOk = true;
        console.log(`[${clientName}] Received join-ok for room: ${roomName}`);
        socket.emit('client-ready', clientName);
    }
});

socket.on('leave-command', (roomName) => {
    console.log(`[${clientName}] Leaving room: ${roomName}`);
    socket.emit('leave-room', roomName);
});

socket.on('leave-ok', (roomName) => {
    console.log(`[${clientName}] Received leave-ok for room: ${roomName}`);
    leftRoomOk = true;
    socket.emit('client-left-room', clientName);
});

// --- SCENARIO: GLOBAL BROADCAST ---
socket.on('global-event', (data) => {
    console.log(`[${clientName}] Received global-event:`, data);
    receivedEvents.push(data);

    if (scenario === 'dist_global_broadcast') {
        const expectedNonce = args.globalNonce;
        if (data === expectedNonce) {
            console.log(`[${clientName}] Global broadcast verified with exact nonce: ${expectedNonce}`);
            exitGracefully(0);
        } else {
            failFast(`Global broadcast nonce mismatch! Expected '${expectedNonce}', got:`, data);
        }
    }
});

// --- SCENARIO: DIRECT SESSIONID ROUTING ---
socket.on('direct-event', (data) => {
    console.log(`[${clientName}] Received direct-event:`, data);
    if (scenario === 'dist_direct_session') {
        const expectedNonce = args.directNonce;
        if (data === expectedNonce) {
            console.log(`[${clientName}] Direct session message verified with exact nonce`);
            socket.emit('direct-confirmed', clientName);
        } else {
            failFast(`Direct session nonce mismatch! Expected '${expectedNonce}', got:`, data);
        }
    }
});

// --- SCENARIO: CUSTOM NAMESPACE (/admin) ---
socket.on('admin-event', (data) => {
    console.log(`[${clientName}] Received admin-event on /admin:`, data);
    if (scenario === 'dist_custom_namespace') {
        const expectedNonce = args.adminNonce;
        if (data === expectedNonce) {
            console.log(`[${clientName}] Custom namespace broadcast verified cleanly`);
            exitGracefully(0);
        } else {
            failFast(`Custom namespace nonce mismatch! Expected '${expectedNonce}', got:`, data);
        }
    }
});

// --- SCENARIO: CLIENT-INITIATED ACK TRIGGER ---
socket.on('trigger-client-ack', () => {
    if (scenario === 'dist_client_ack') {
        const challengeNonce = "CLIENT_CHALLENGE_" + clientName;
        console.log(`[${clientName}] Emitting client-ack-req...`);
        socket.emit('client-ack-req', challengeNonce, (reply) => {
            if (reply === "SERVER_ACK_REPLY_" + challengeNonce) {
                console.log(`[${clientName}] Received valid server ACK reply. Confirming...`);
                socket.emit('client-ack-confirmed', clientName);
            } else {
                failFast(`Client ACK reply mismatch! Expected 'SERVER_ACK_REPLY_${challengeNonce}', got:`, reply);
            }
        });
    }
});

// --- SCENARIO: SERVER-TRIGGERED P2P SENDER EMISSION ---
socket.on('trigger-p2p-send', (targetSender) => {
    if (scenario === 'dist_client_to_client' && clientName === targetSender) {
        console.log(`[${clientName}] Triggered by server to emit client-p2p-send...`);
        socket.emit('client-p2p-send', {
            sender: clientName,
            room: targetRoom,
            nonce: args.p2pNonce
        });
    }
});

// --- SCENARIO: P2P RELAY RECEIVER & CONFIRMATION ---
socket.on('client-p2p-receive', (payload) => {
    console.log(`[${clientName}] Received client-p2p-receive payload:`, payload);
    receivedEvents.push(payload);

    if (scenario === 'dist_client_to_client') {
        if (payload && payload.sender === args.p2pSender && payload.nonce === args.p2pNonce) {
            console.log(`[${clientName}] P2P payload verified cleanly. Confirming back to server...`);
            socket.emit('client-p2p-confirmed', clientName);
        } else {
            failFast(`P2P Relay payload mismatch! Expected sender '${args.p2pSender}', nonce '${args.p2pNonce}', got:`, payload);
        }
    }
});

// --- MAIN DISTRIBUTED ROOM EVENT HANDLER ---
socket.on('dist-event', (...eventArgs) => {
    const data = eventArgs[0];
    console.log(`[${clientName}] Received dist-event:`, eventArgs);
    receivedEvents.push(eventArgs);

    if (scenario === 'dist_room_leave_negative' && leftRoomOk) {
        failFast(`FAILURE: Received forbidden dist-event '${data}' after leaving room!`);
    }

    if (scenario === 'dist_room_isolation_negative') {
        const expectedNonce = args.expectedNonce;
        if (data !== expectedNonce) {
            failFast(`ROOM ISOLATION BREACH! Expected '${expectedNonce}', received:`, data);
        }
        socket.emit('room-isolation-confirmed', clientName);
    }

    if (scenario === 'dist_client_exclusion') {
        const expectedNonce = args.exclusionNonce;
        const targetExcludedName = args.excludedClientName;

        if (clientName === targetExcludedName) {
            failFast(`EXCLUSION FAILURE! Excluded client '${clientName}' received forbidden broadcast event!`);
        } else if (data === expectedNonce) {
            console.log(`[${clientName}] Non-excluded client received event. Confirming...`);
            socket.emit('exclusion-confirmed', clientName);
        } else {
            failFast(`Exclusion test nonce mismatch! Expected '${expectedNonce}', got:`, data);
        }
    }

    if (scenario === 'dist_binary') {
        const isBuf = Buffer.isBuffer(data) || data instanceof Uint8Array || (data && (data.buffer || data.type === 'Buffer'));
        if (!isBuf) failFast('Expected binary Buffer/Uint8Array, got:', typeof data);

        const rawBuf = Buffer.isBuffer(data) ? data : Buffer.from(data.buffer || data);
        const expectedLength = parseInt(args.byteLength, 10);
        const expectedCheckSum = parseInt(args.checkSum, 10);

        if (rawBuf.length !== expectedLength) failFast(`Binary length mismatch! Expected ${expectedLength}, got ${rawBuf.length}`);

        let actualCheckSum = 0;
        for (let i = 0; i < rawBuf.length; i++) actualCheckSum += rawBuf[i];

        if (actualCheckSum !== expectedCheckSum) failFast(`Binary Checksum mismatch! Expected ${expectedCheckSum}, got ${actualCheckSum}`);
    } else if (scenario === 'dist_object') {
        if (!data || data.name !== args.expectedName || data.value !== parseInt(args.expectedValue, 10)) {
            failFast(`Object POJO Nonce Mismatch! Expected name '${args.expectedName}', value ${args.expectedValue}, got:`, data);
        }
    } else if (scenario === 'dist_complex_object') {
        if (!data || data.orderId !== args.orderId || !data.customer || data.customer.customerId !== args.customerId) {
            failFast(`Complex object dynamic nonces mismatch! Expected orderId '${args.orderId}', customerId '${args.customerId}', got:`, data);
        }
    } else if (scenario === 'dist_mixed') {
        const [text, buf, obj] = eventArgs;
        const isBuf = Buffer.isBuffer(buf) || buf instanceof Uint8Array || (buf && (buf.buffer || buf.type === 'Buffer'));
        if (text !== args.textNonce || !isBuf || !obj || obj.nonce !== args.mapNonce || obj.value !== parseInt(args.mapVal, 10)) {
            failFast(`Mixed payload exact verification failed! Expected text '${args.textNonce}', mapNonce '${args.mapNonce}', mapVal ${args.mapVal}, got:`, eventArgs);
        }
    }

    if (scenario === 'dist_room_broadcast') {
        const hasNonce1 = receivedEvents.some(a => a[0] === args.nonce1);
        const hasNonce2 = receivedEvents.some(a => a[0] === args.nonce2);

        if (hasNonce1 && hasNonce2) {
            console.log(`[${clientName}] Received both unique node nonces cleanly - SUCCESS`);
            exitGracefully(0);
        }
    } else if (['dist_binary', 'dist_object', 'dist_complex_object', 'dist_mixed'].includes(scenario) && receivedEvents.length >= 1) {
        console.log(`[${clientName}] Verified nonced payload event - SUCCESS`);
        exitGracefully(0);
    }
});

// --- DONE SIGNALS FOR SCENARIO COMPLETION ---
socket.on('dist-test-done', (checkType) => {
    console.log(`[${clientName}] Received dist-test-done signal: checkType=${checkType}`);

    if (scenario === 'dist_room_isolation_negative') {
        const hasExpected = receivedEvents.some(a => a[0] === args.expectedNonce);
        const hasUnexpected = receivedEvents.some(a => a[0] !== args.expectedNonce);

        if (hasExpected && !hasUnexpected && receivedEvents.length === 1) {
            console.log(`[${clientName}] Room isolation test PASSED cleanly`);
            exitGracefully(0);
        } else {
            failFast(`Room isolation failed. hasExpected=${hasExpected}, hasUnexpected=${hasUnexpected}, totalEvents=${receivedEvents.length}`);
        }
    }

    if (scenario === 'dist_room_leave_negative') {
        if (leftRoomOk && receivedEvents.length === 0) {
            console.log(`[${clientName}] Room leave test PASSED cleanly`);
            exitGracefully(0);
        } else {
            failFast(`Room leave test failed. leftRoomOk=${leftRoomOk}, receivedEvents=${receivedEvents.length}`);
        }
    }

    if (scenario === 'dist_client_exclusion') {
        const targetExcludedName = args.excludedClientName;
        if (clientName === targetExcludedName) {
            if (receivedEvents.length === 0) {
                console.log(`[${clientName}] Excluded client correctly received 0 events - PASSED`);
                exitGracefully(0);
            } else {
                failFast(`Excluded client received ${receivedEvents.length} forbidden events!`);
            }
        } else {
            console.log(`[${clientName}] Non-excluded client finished scenario - PASSED`);
            exitGracefully(0);
        }
    }

    if (['dist_ack_text', 'dist_ack_binary', 'dist_client_to_client', 'dist_direct_session', 'dist_client_ack', 'dist_custom_namespace', 'dist_abrupt_disconnect'].includes(scenario)) {
        console.log(`[${clientName}] Scenario '${scenario}' confirmed complete by server signal`);
        exitGracefully(0);
    }
});

// --- SERVER-INITIATED ACK CALLBACK HANDLERS ---
socket.on('distAckTextReq', (challengeNonce, callback) => {
    if (typeof callback === 'function') {
        callback(`ACK_VERIFIED_${challengeNonce}`);
    } else {
        failFast('Missing ACK callback in distAckTextReq');
    }
});

socket.on('distAckBinaryReq', (tokenBuffer, callback) => {
    if (typeof callback === 'function' && tokenBuffer) {
        const rawBuf = Buffer.isBuffer(tokenBuffer) ? tokenBuffer : Buffer.from(tokenBuffer.buffer || tokenBuffer);
        callback(Buffer.concat([rawBuf, Buffer.from([0xBE, 0xEF])]));
    } else {
        failFast('Missing ACK callback or buffer in distAckBinaryReq');
    }
});

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
const version = args.version || '4';
const port = args.port || '8080';
const transport = args.transport || 'websocket';
const scenario = args.scenario || 'dist_room_broadcast';
const targetRoom = args.room || 'RoomAlpha';

console.log(`Running Distributed JS Client: name=${clientName}, version=v${version}, port=${port}, transport=${transport}, scenario=${scenario}, room=${targetRoom}`);

let io;
if (version === '1') {
    io = require('socket.io-client-v1');
} else if (version === '2') {
    io = require('socket.io-client-v2');
} else if (version === '3') {
    io = require('socket.io-client-v3');
} else if (version === '4') {
    io = require('socket.io-client-v4');
} else {
    console.error(`Unsupported client version: ${version}`);
    process.exit(1);
}

const url = `http://localhost:${port}`;
const options = {
    transports: [transport],
    reconnection: false,
    forceNew: true
};

const socket = io(url, options);

const receivedEvents = [];

const timeoutMs = args.timeout ? parseInt(args.timeout, 10) : 35000;

const timeout = setTimeout(() => {
    console.error(`[${clientName}] Test timed out after ${timeoutMs}ms. Received ${receivedEvents.length} events:`, JSON.stringify(receivedEvents));
    socket.disconnect();
    process.exit(1);
}, timeoutMs);

let joinedRoomOk = false;
let leftRoomOk = false;

socket.on('connect', () => {
    console.log(`[${clientName} v${version}] Connected to server on port ${port} via ${transport}, joining room: ${targetRoom}`);
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

socket.on('dist-event', (...args) => {
    const data = args[0];
    console.log(`[${clientName}] Received dist-event:`, args);
    receivedEvents.push(args);

    if (scenario === 'dist_room_leave_negative') {
        if (leftRoomOk) {
            console.error(`[${clientName}] FAILURE: Received dist-event after leaving room! Data:`, data);
            clearTimeout(timeout);
            socket.disconnect();
            process.exit(1);
        }
    }

    if (scenario === 'dist_room_isolation_negative') {
        const expectedData = clientName.includes('red') ? 'red_only_message' : 'blue_only_message';
        if (data !== expectedData) {
            console.error(`[${clientName}] ROOM ISOLATION FAILURE: Expected '${expectedData}', got unexpected event data:`, data);
            clearTimeout(timeout);
            socket.disconnect();
            process.exit(1);
        }
    }

    if (scenario === 'dist_binary') {
        const isBuf = Buffer.isBuffer(data) || data instanceof Uint8Array || (data && (data.buffer || data.type === 'Buffer'));
        if (!isBuf) {
            console.error(`[${clientName}] Expected binary Buffer/Uint8Array, got:`, typeof data, data);
            clearTimeout(timeout);
            socket.disconnect();
            process.exit(1);
        }
    } else if (scenario === 'dist_object') {
        if (!data || data.name !== 'cluster_pojo' || data.value !== 42) {
            console.error(`[${clientName}] Expected object {name: 'cluster_pojo', value: 42}, got:`, data);
            clearTimeout(timeout);
            socket.disconnect();
            process.exit(1);
        }
    } else if (scenario === 'dist_complex_object') {
        if (!data || data.orderId !== 'ORD-CLUSTER-12345' || data.totalAmount !== 299.99 ||
            !data.customer || data.customer.customerId !== 'CUST-VIP-777' || data.customer.vipStatus !== true ||
            !data.items || data.items.length !== 2 || data.items[0].sku !== 'SKU-CLUSTER-A' ||
            !data.metadata || data.metadata.region !== 'us-east-1') {
            console.error(`[${clientName}] Complex object mismatch, got:`, JSON.stringify(data));
            clearTimeout(timeout);
            socket.disconnect();
            process.exit(1);
        }
    } else if (scenario === 'dist_mixed') {
        const text = args[0];
        const buf = args[1];
        const obj = args[2];
        const isBuf = Buffer.isBuffer(buf) || buf instanceof Uint8Array || (buf && (buf.buffer || buf.type === 'Buffer'));
        if (text !== 'hello_cluster' || !isBuf || !obj || obj.value !== 99) {
            console.error(`[${clientName}] Expected mixed args ['hello_cluster', Buffer, {value: 99}], got:`, args);
            clearTimeout(timeout);
            socket.disconnect();
            process.exit(1);
        }
    }

    if (scenario === 'dist_room_broadcast') {
        const hasMsg1 = receivedEvents.some(a => a[0] === 'msg_from_server1');
        const hasMsg2 = receivedEvents.some(a => a[0] === 'msg_from_server2');
        if (hasMsg1 && hasMsg2) {
            console.log(`[${clientName}] Received both server1 and server2 room broadcast events - SUCCESS`);
            clearTimeout(timeout);
            setTimeout(() => {
                socket.disconnect();
                process.exit(0);
            }, 200);
        }
    } else if ((scenario === 'dist_single_event' || scenario === 'dist_binary' || scenario === 'dist_object' || scenario === 'dist_complex_object' || scenario === 'dist_mixed') && receivedEvents.length >= 1) {
        console.log(`[${clientName}] Received all ${receivedEvents.length} expected room broadcast events - SUCCESS`);
        clearTimeout(timeout);
        setTimeout(() => {
            socket.disconnect();
            process.exit(0);
        }, 200);
    }
});

socket.on('dist-test-done', (checkType) => {
    console.log(`[${clientName}] Received dist-test-done signal from server: checkType=${checkType}`);

    if (scenario === 'dist_room_isolation_negative') {
        const expectedData = clientName.includes('red') ? 'red_only_message' : 'blue_only_message';
        const hasExpected = receivedEvents.some(a => a[0] === expectedData);
        const hasUnexpected = receivedEvents.some(a => a[0] !== expectedData);
        if (hasExpected && !hasUnexpected) {
            console.log(`[${clientName}] Room isolation test PASSED cleanly (received expected event, 0 unexpected)`);
            clearTimeout(timeout);
            socket.disconnect();
            process.exit(0);
        } else {
            console.error(`[${clientName}] Room isolation check failed. hasExpected=${hasExpected}, hasUnexpected=${hasUnexpected}`);
            clearTimeout(timeout);
            socket.disconnect();
            process.exit(1);
        }
    }

    if (scenario === 'dist_room_leave_negative') {
        if (leftRoomOk && receivedEvents.length === 0) {
            console.log(`[${clientName}] Room leave test PASSED cleanly (left room, 0 post-leave events received)`);
            clearTimeout(timeout);
            socket.disconnect();
            process.exit(0);
        } else {
            console.error(`[${clientName}] Room leave test failed. leftRoomOk=${leftRoomOk}, receivedEvents=${receivedEvents.length}`);
            clearTimeout(timeout);
            socket.disconnect();
            process.exit(1);
        }
    }
});

socket.on('global-event', (data) => {
    console.log(`[${clientName}] Received global-event:`, data);
    receivedEvents.push(data);
    socket.emit('global-event-received', { client: clientName, data: data });

    if (scenario === 'dist_global_broadcast') {
        console.log(`[${clientName}] Received expected global cluster event - SUCCESS`);
        clearTimeout(timeout);
        setTimeout(() => {
            socket.disconnect();
            process.exit(0);
        }, 200);
    }
});

socket.on('distAckTextReq', (data, callback) => {
    console.log(`[${clientName}] Received distAckTextReq:`, data);
    if (typeof callback === 'function') {
        callback(`ack_reply_${clientName}`);
        console.log(`[${clientName}] Executed text ACK callback - SUCCESS`);
        clearTimeout(timeout);
        setTimeout(() => {
            socket.disconnect();
            process.exit(0);
        }, 200);
    } else {
        console.error(`[${clientName}] Missing callback in distAckTextReq`);
        clearTimeout(timeout);
        socket.disconnect();
        process.exit(1);
    }
});

socket.on('distAckBinaryReq', (data, callback) => {
    console.log(`[${clientName}] Received distAckBinaryReq:`, data);
    if (typeof callback === 'function') {
        callback(Buffer.from([10, 20, 30]));
        console.log(`[${clientName}] Executed binary ACK callback - SUCCESS`);
        clearTimeout(timeout);
        setTimeout(() => {
            socket.disconnect();
            process.exit(0);
        }, 200);
    } else {
        console.error(`[${clientName}] Missing callback in distAckBinaryReq`);
        clearTimeout(timeout);
        socket.disconnect();
        process.exit(1);
    }
});

socket.on('connect_error', (err) => {
    console.error(`[${clientName}] Connection error:`, err);
    clearTimeout(timeout);
    process.exit(1);
});

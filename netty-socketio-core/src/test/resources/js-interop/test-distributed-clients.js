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

const timeoutMs = (scenario === 'dist_negative_isolation' || scenario === 'dist_room_leave_negative') ? 3500 : 15000;

const timeout = setTimeout(() => {
    if (scenario === 'dist_negative_isolation' || scenario === 'dist_room_leave_negative') {
        console.log(`[${clientName}] Negative assertion passed (no spurious events received within timeout)`);
        socket.disconnect();
        process.exit(0);
    }
    console.error(`[${clientName}] Test timed out. Received events:`, receivedEvents);
    socket.disconnect();
    process.exit(1);
}, timeoutMs);

socket.on('connect', () => {
    console.log(`[${clientName} v${version}] Connected to server on port ${port} via ${transport}, joining room: ${targetRoom}`);
    socket.emit('join-room', targetRoom);
});

socket.on('join-ok', (roomName) => {
    console.log(`[${clientName}] Received join-ok for room: ${roomName}`);
    socket.emit('client-ready', clientName);
});

socket.on('leave-command', (roomName) => {
    console.log(`[${clientName}] Leaving room: ${roomName}`);
    socket.emit('leave-room', roomName);
});

socket.on('dist-event', (...args) => {
    const data = args[0];
    console.log(`[${clientName}] Received dist-event:`, args);
    receivedEvents.push(args);

    if (scenario === 'dist_negative_isolation' || scenario === 'dist_room_leave_negative') {
        console.error(`[${clientName}] FAILURE: Received event in negative/isolated scenario! Data:`, data);
        clearTimeout(timeout);
        socket.disconnect();
        process.exit(1);
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

    if ((scenario === 'dist_room_broadcast' && receivedEvents.length >= 2) ||
        ((scenario === 'dist_single_event' || scenario === 'dist_binary' || scenario === 'dist_object' || scenario === 'dist_mixed') && receivedEvents.length >= 1)) {
        console.log(`[${clientName}] Received all ${receivedEvents.length} expected room broadcast events - SUCCESS`);
        clearTimeout(timeout);
        setTimeout(() => {
            socket.disconnect();
            process.exit(0);
        }, 200);
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

socket.on('connect_error', (err) => {
    console.error(`[${clientName}] Connection error:`, err);
    clearTimeout(timeout);
    process.exit(1);
});

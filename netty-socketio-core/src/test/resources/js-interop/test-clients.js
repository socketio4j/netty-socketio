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

console.log(`Running JS Client Interop Test: version=v${version}, port=${port}, transport=${transport}, scenario=${scenario}`);

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

const timeout = setTimeout(() => {
    console.error('Test timed out');
    socket.disconnect();
    process.exit(1);
}, 10000);

socket.on('connect', () => {
    console.log(`[v${version} JS Client] Connected successfully via ${transport}`);

    if (scenario === 'connect') {
        clearTimeout(timeout);
        socket.disconnect();
        console.log('Connect scenario PASSED');
        process.exit(0);
    }

    if (scenario === 'text') {
        socket.emit('testText', 'hello from js client v' + version);
    }

    if (scenario === 'ack') {
        socket.emit('testAck', 'ping_ack_data', (response) => {
            console.log(`[v${version} JS Client] Received ack response:`, response);
            if (response === 'ack_reply_ping_ack_data') {
                clearTimeout(timeout);
                socket.disconnect();
                console.log('Ack scenario PASSED');
                process.exit(0);
            } else {
                console.error('Ack response mismatch:', response);
                process.exit(1);
            }
        });
    }

    if (scenario === 'ack_binary') {
        socket.emit('testAckBinary', 'ping_ack_binary_data', (response) => {
            console.log(`[v${version} JS Client] Received ack_binary response:`, response);
            const buf = Buffer.from(response);
            if (buf.length === 3 && buf[0] === 50 && buf[1] === 51 && buf[2] === 52) {
                clearTimeout(timeout);
                socket.disconnect();
                console.log('Ack binary scenario PASSED');
                process.exit(0);
            } else {
                console.error('Ack binary response mismatch:', buf);
                process.exit(1);
            }
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
    if (data === 'hello from server') {
        clearTimeout(timeout);
        socket.disconnect();
        console.log('Text scenario PASSED');
        process.exit(0);
    } else {
        console.error('Text response mismatch:', data);
        process.exit(1);
    }
});

socket.on('binaryResponse', (data) => {
    console.log(`[v${version} JS Client] Received binaryResponse:`, data);
    const buf = Buffer.from(data);
    if (buf.length === 3 && buf[0] === 100 && buf[1] === 101 && buf[2] === 102) {
        clearTimeout(timeout);
        socket.disconnect();
        console.log('Binary scenario PASSED');
        process.exit(0);
    } else {
        console.error('Binary data mismatch:', buf);
        process.exit(1);
    }
});

socket.on('objectResponse', (data) => {
    console.log(`[v${version} JS Client] Received objectResponse:`, data);
    if (data && data.echo === 'hello' && data.doubled === 84) {
        clearTimeout(timeout);
        socket.disconnect();
        console.log('Object scenario PASSED');
        process.exit(0);
    } else {
        console.error('Object response mismatch:', data);
        process.exit(1);
    }
});

socket.on('pojoResponse', (data) => {
    console.log(`[v${version} JS Client] Received pojoResponse:`, data);
    if (data && data.echo === 'hello' && data.doubled === 84) {
        clearTimeout(timeout);
        socket.disconnect();
        console.log('POJO scenario PASSED');
        process.exit(0);
    } else {
        console.error('POJO response mismatch:', data);
        process.exit(1);
    }
});

socket.on('complexPojoResponse', (data) => {
    console.log(`[v${version} JS Client] Received complexPojoResponse:`, data);
    if (data && data.orderId === 'ORD-98765' && data.status === 'PROCESSED' && data.processedItemCount === 2 && data.customerEmail === 'alice@example.com') {
        clearTimeout(timeout);
        socket.disconnect();
        console.log('Complex POJO scenario PASSED');
        process.exit(0);
    } else {
        console.error('Complex POJO response mismatch:', data);
        process.exit(1);
    }
});

socket.on('mixedResponse', (text, binData) => {
    console.log(`[v${version} JS Client] Received mixedResponse:`, text, binData);
    const buf = Buffer.from(binData);
    if (text === 'hello_text_reply' && buf.length === 3 && buf[0] === 7 && buf[1] === 8 && buf[2] === 9) {
        clearTimeout(timeout);
        socket.disconnect();
        console.log('Mixed scenario PASSED');
        process.exit(0);
    } else {
        console.error('Mixed response mismatch - text:', text, 'buf:', buf);
        process.exit(1);
    }
});

if (scenario === 'server_ack_text') {
    socket.on('serverReqAckText', (data, callback) => {
        console.log(`[v${version} JS Client] Received serverReqAckText:`, data);
        if (data === 'hello_from_server' && typeof callback === 'function') {
            callback('js_ack_text_reply');
            setTimeout(() => {
                clearTimeout(timeout);
                socket.disconnect();
                console.log('Server req ACK text scenario PASSED');
                process.exit(0);
            }, 300);
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
                clearTimeout(timeout);
                socket.disconnect();
                console.log('Server req ACK binary scenario PASSED');
                process.exit(0);
            }, 300);
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
                clearTimeout(timeout);
                socket.disconnect();
                console.log('Server req Void ACK scenario PASSED');
                process.exit(0);
            }, 300);
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
                clearTimeout(timeout);
                socket.disconnect();
                console.log('Server req MultiType ACK scenario PASSED');
                process.exit(0);
            }, 300);
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

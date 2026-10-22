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
const CLIENT_PACKAGES = Object.freeze({
    "1.7.3": "socket.io-client-v1-7-3",
    "2.1.1": "socket.io-client-v2-1-1",
    "2.3.0": "socket.io-client-v2-3-0",
    "2.4.0": "socket.io-client-v2-4-0",
    "2.5.0": "socket.io-client-v2",
    "3.1.3": "socket.io-client-v3",
    "4.0.0": "socket.io-client-v4-0-0",
    "4.7.0": "socket.io-client-v4-7-0",
    "4.7.2": "socket.io-client-v4-7-2",
    "4.7.5": "socket.io-client-v4-7-5",
    "4.8.1": "socket.io-client-v4-8-1",
    "4.8.3": "socket.io-client-v4"
});

function loadSocketIoClient(version) {
    const clientPackage = CLIENT_PACKAGES[version];
    if (!clientPackage) {
        throw new Error(`Unsupported Socket.IO client version: ${version}`);
    }

    const packageMetadata = require(`${clientPackage}/package.json`);
    if (packageMetadata.version !== version) {
        throw new Error(
            `Client alias ${clientPackage} resolved ${packageMetadata.version}, expected ${version}`
        );
    }

    return {
        io: require(clientPackage),
        clientPackage,
        packageMetadata
    };
}

module.exports = { CLIENT_PACKAGES, loadSocketIoClient };

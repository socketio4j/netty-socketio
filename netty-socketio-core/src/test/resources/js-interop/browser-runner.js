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
const { chromium, firefox, webkit } = require("playwright");

const HTTP_PORT = process.env.HTTP_PORT || "8080";
const SOCKETIO_PORT = process.env.SOCKETIO_PORT || "9092";

const BASE = `http://127.0.0.1:${HTTP_PORT}/interop.html`;

const browsers = [
    { name: "Chromium", type: chromium },
    { name: "Firefox", type: firefox },
    { name: "WebKit", type: webkit }
];

const ALL_VERSIONS = [
    "1.7.3",
    "2.1.1",
    "2.3.0",
    "2.4.0",
    "2.5.0",
    "3.1.3",
    "4.0.0",
    "4.7.0",
    "4.7.2",
    "4.7.5",
    "4.8.1",
    "4.8.3"
];

function resolveVersions() {
    const configured = process.env.SOCKETIO_INTEROP_VERSIONS;
    if (!configured) {
        return ALL_VERSIONS;
    }

    const versions = configured.split(",").map(version => version.trim());
    if (versions.length === 0 || versions.some(version => !version)) {
        throw new Error("SOCKETIO_INTEROP_VERSIONS must contain one or more versions");
    }
    for (const version of versions) {
        if (!ALL_VERSIONS.includes(version)) {
            throw new Error(`Unsupported Socket.IO client version: ${version}`);
        }
    }
    if (new Set(versions).size !== versions.length) {
        throw new Error("SOCKETIO_INTEROP_VERSIONS must not contain duplicate versions");
    }
    return versions;
}

const versions = resolveVersions();

const transports = [
    "polling",
    "websocket"
];

async function runCase(browser, browserInfo, version, transport) {

    console.log();
    console.log("====================================");
    console.log(browserInfo.name);
    console.log(version);
    console.log(transport);
    console.log("====================================");

    // A fresh context preserves the old one-browser-per-case isolation while
    // allowing each browser family to reuse its expensive browser process.
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors = [];
    const requestFailures = [];

    page.on("console", msg => {
        console.log(msg.text());
    });

    page.on("pageerror", err => {
        pageErrors.push(err);
        console.error(err);
    });

    page.on("requestfailed", req => {
        const failure = req.failure();
        const detail = `${req.method()} ${req.url()} ${failure ? failure.errorText : "unknown failure"}`;
        requestFailures.push(detail);
        console.error(detail);
    });

    try {

        await page.goto(
            BASE +
            "?client=" + version +
            "&transport=" + transport +
            "&host=" + encodeURIComponent("http://127.0.0.1:" + SOCKETIO_PORT),
            {
                waitUntil: "load"
            });

        await page.waitForFunction(
            () => window.TEST_RESULT !== undefined,
            {
                timeout: 30000
            });

        const result = await page.evaluate(
            () => window.TEST_RESULT
        );

        if (result === "PASS" && pageErrors.length === 0 && requestFailures.length === 0) {

            console.log("PASS");
            return 0;
        }

        console.error("FAIL", result, pageErrors, requestFailures);
        return 1;

    } catch (e) {

        console.error(e);
        return 1;

    } finally {

        // Context closure is awaited so no next case can inherit open pages,
        // WebSockets, cookies, or local storage from this case.
        await context.close();
    }
}

async function runBrowser(browserInfo) {

    let failures = 0;
    const browser = await browserInfo.type.launch({
        headless: true
    });

    try {
        for (const version of versions) {
            for (const transport of transports) {
                failures += await runCase(browser, browserInfo, version, transport);
            }
        }
    } finally {
        await browser.close();
    }

    return failures;
}

(async () => {

    // Browser families are independent. Run them concurrently to keep this
    // exact matrix fast, while runBrowser keeps each family's cases ordered.
    const failures = (await Promise.all(browsers.map(runBrowser)))
        .reduce((total, browserFailures) => total + browserFailures, 0);

    console.log();
    console.log("=======================");
    console.log("Failures : " + failures);
    console.log("=======================");

    process.exit(failures === 0 ? 0 : 1);

})().catch(error => {
    console.error("Browser interop runner crashed", error);
    process.exit(1);
});

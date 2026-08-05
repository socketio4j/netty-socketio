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


const BASE = "http://127.0.0.1:8080/interop.html";

const browsers = [
    { name: "Chromium", type: chromium },
    { name: "Firefox", type: firefox },
    { name: "WebKit", type: webkit }
];

const versions = [
    "v1",
    "v2",
    "v3",
    "v4"
];

const transports = [
    "polling",
    "websocket"
];

(async () => {

    let failures = 0;

    for (const browserInfo of browsers) {

        for (const version of versions) {

            for (const transport of transports) {

                console.log();
                console.log("====================================");
                console.log(browserInfo.name);
                console.log(version);
                console.log(transport);
                console.log("====================================");

                const browser = await browserInfo.type.launch({
                    headless: true
                });

                const page = await browser.newPage();

                page.on("console", msg => {
                    console.log(msg.text());
                });

                page.on("pageerror", err => {
                    console.error(err);
                });

                page.on("requestfailed", req => {
                    console.error(req.url(), req.failure());
                });

                try {

                    await page.goto(
                        BASE +
                        "?client=" + version +
                        "&transport=" + transport,
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

                    if (result === "PASS") {

                        console.log("PASS");

                    } else {

                        failures++;

                        console.error("FAIL");
                    }

                } catch (e) {

                    failures++;

                    console.error(e);

                } finally {

                    await browser.close();

                }
            }
        }
    }

    console.log();
    console.log("=======================");
    console.log("Failures : " + failures);
    console.log("=======================");

    process.exit(failures === 0 ? 0 : 1);

})();
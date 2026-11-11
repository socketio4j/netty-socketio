#!/usr/bin/env bash
# Shared prerequisite check for the full reactor test gates.
set -eu

JS_INTEROP_DIR="netty-socketio-core/src/test/resources/js-interop"
if ! command -v node >/dev/null 2>&1; then
    echo "JavaScript interop tests require Node.js and npm. Install them, then run: npm ci --prefix $JS_INTEROP_DIR" >&2
    exit 1
fi

if ! (cd "$JS_INTEROP_DIR" && node -e '
    const dependencies = require("./package.json").dependencies;
    let missing = false;
    for (const name of Object.keys(dependencies)) {
        try {
            require(name);
        } catch (error) {
            console.error(`Cannot load required test dependency: ${name}`);
            missing = true;
        }
    }
    process.exitCode = missing ? 1 : 0;
'); then
    echo "JavaScript interop dependencies are unavailable. Run: npm ci --prefix $JS_INTEROP_DIR" >&2
    exit 1
fi

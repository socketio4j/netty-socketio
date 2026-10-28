#!/usr/bin/env bash
# Shared local/CI event-loop safety gate for netty-socketio. Run from any directory.
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

# Keep evidence separate between runs; CI supplies RUNNER_TEMP for artifact upload.
AUDIT_DIR=${RUNNER_TEMP:-$(mktemp -d "${TMPDIR:-/tmp}/socketio-lock-audit.XXXXXX")}
mkdir -p "$AUDIT_DIR"
echo "Event-loop safety evidence: $AUDIT_DIR"

# Copy git files to a clean worktree so build outputs or IDE state do not contaminate
AUDIT_WORKTREE=$(mktemp -d "$AUDIT_DIR/worktree.XXXXXX")
git ls-files -z --cached --others --exclude-standard | \
    while IFS= read -r -d '' file; do
        if [ -e "$file" ] || [ -L "$file" ]; then
            printf '%s\0' "$file"
        fi
    done | rsync -a --from0 --files-from=- ./ "$AUDIT_WORKTREE/"
cd "$AUDIT_WORKTREE"

# Build lock-audit tool
mvn -B -ntp -f verification/locking/pom.xml clean package

# Compile netty-socketio-core production and test classes
mvn -B -ntp -DskipTests clean test-compile -pl netty-socketio-core
mvn -B -ntp dependency:build-classpath -pl netty-socketio-core "-Dmdep.outputFile=$AUDIT_DIR/lock-classpath"

# 1. Run static bytecode audit against production classes
java -jar verification/locking/target/lock-audit.jar netty-socketio-core/target/classes

# 2. Run negative static control (proves that bypassed and caught guards fail)
java -cp verification/locking/target/lock-audit.jar com.socketio4j.socketio.verification.lockaudit.AuditNegativeControls

# 3. Run negative static fixture (proves unsafe code fails audit)
if java -jar verification/locking/target/lock-audit.jar verification/locking/target/classes/com/socketio4j/socketio/verification/fixtures > "$AUDIT_DIR/static-negative.log" 2>&1; then
    echo 'Unsafe fixture incorrectly passed' >&2
    exit 1
fi
grep -q 'unguarded' "$AUDIT_DIR/static-negative.log"

# 4. Run negative runtime control with agent (proves event-loop lock is caught and recorded)
java "-javaagent:$PWD/verification/locking/target/lock-audit.jar=$AUDIT_DIR/negative-count" \
    -cp "verification/locking/target/lock-audit.jar:netty-socketio-core/target/classes:$(cat "$AUDIT_DIR/lock-classpath")" \
    com.socketio4j.socketio.verification.fixtures.BlockingCallback
test "$(cat "$AUDIT_DIR/negative-count")" = 1

# 5. Run runtime test suite with lock probe agent attached
mvn -B -ntp -Pverify-eventloop-locks \
    "-Dsocketio.lockagent=$PWD/verification/locking/target/lock-audit.jar" \
    "-Dsocketio.lockreport=$AUDIT_DIR/lock-count" \
    -Dtest=EventLoopSafetyTest,SocketSslConfigEventLoopSafetyTest test -pl netty-socketio-core

test "$(cat "$AUDIT_DIR/lock-count")" = 0
test "$(cat "$AUDIT_DIR/lock-count.classes")" -gt 0
echo 'Event-loop safety gate passed.'

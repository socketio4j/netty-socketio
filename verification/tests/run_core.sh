#!/usr/bin/env bash
# Run every core partition even if one fails, then validate their combined evidence.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
docker info >/dev/null
node --version >/dev/null
python3 verification/tests/check_evidence_test.py
mkdir -p netty-socketio-core/target
EVIDENCE=$(mktemp -d "$ROOT/netty-socketio-core/target/test-evidence.XXXXXX")
echo "Test evidence: $EVIDENCE"
VERSIONS=${SOCKETIO_INTEROP_VERSIONS:-full}
COMMON=(-f netty-socketio-core/pom.xml --batch-mode --no-transfer-progress -Pcoverage
  "-Dsocketio.coverage.dataFile=$EVIDENCE/jacoco.exec"
  "-Dsocketio.coverage.reportDirectory=$EVIDENCE/coverage"
  "-Dsocketio.interop.versions=$VERSIONS" -Dsocketio.skip.docker=false)
mvn "${COMMON[@]}" initialize test-compile > "$EVIDENCE/compile.log" 2>&1
STATUS=0
for EXECUTION in default-test external-service-integration shared-single-node-js-interop; do
  echo "Running $EXECUTION"
  if mvn "${COMMON[@]}" "-Dsocketio.test.reportsDirectory=$EVIDENCE/reports/$EXECUTION" \
       initialize "surefire:test@$EXECUTION" > "$EVIDENCE/$EXECUTION.log" 2>&1; then
    echo "$EXECUTION passed"
  else
    echo "$EXECUTION FAILED; see $EVIDENCE/$EXECUTION.log"
    STATUS=1
  fi
done
if ! mvn "${COMMON[@]}" jacoco:report > "$EVIDENCE/coverage.log" 2>&1; then STATUS=1; fi
if ! python3 verification/tests/check_evidence.py --reports "$EVIDENCE/reports" \
    --source netty-socketio-core/src/test/java --coverage "$EVIDENCE/coverage/jacoco.xml" \
    --minimum-branch 0.80 --output "$EVIDENCE/summary.json"; then STATUS=1; fi
printf '%s\n' "$EVIDENCE" > netty-socketio-core/target/latest-test-evidence.txt
exit "$STATUS"

#!/usr/bin/env python3
"""Prove critical test assertions fail when their stimulus or behavior is broken.

Every mutation runs in a temporary source copy. A compilation failure, missing
report, skipped test, infrastructure error, or successful mutant is a failure of
this gate, never evidence that an assertion detected the defect.
"""
import argparse
import json
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST = "netty-socketio-core/src/test/java/com/socketio4j/socketio/"
MAIN = "netty-socketio-core/src/main/java/com/socketio4j/socketio/"
CONTROLS = [
    ("recorded-leak-signal", "ByteBufLeakTest", TEST + "leak/ByteBufLeakTest.java",
     "public void testEncoderDecoderCyclesZeroLeaks() throws IOException {",
     "public void testEncoderDecoderCyclesZeroLeaks() throws IOException { leakDetected.set(true);"),
    ("chaos-without-stimulus", "ProtocolChaosBoundaryTest", TEST + "integration/resilience/ProtocolChaosBoundaryTest.java",
     "bad.send(malformed);", "// Deliberately omit the malformed stimulus."),
    ("heartbeat-peer-still-responds", "ClientHeartbeatTimeoutReapTest", TEST + "integration/resilience/ClientHeartbeatTimeoutReapTest.java",
     "new RawWebSocketClient(config.getPort(), false)", "new RawWebSocketClient(config.getPort(), true)"),
    ("corrupt-binary-ack", "BinaryDataTest", TEST + "integration/protocol/BinaryDataTest.java",
     "ack.sendAckData(data);", "ack.sendAckData(new byte[]{99});"),
    ("ack-timeout-retains-callback", "AckManagerMemoryLeakTest", MAIN + "ack/AckManager.java",
     "return ackEntry.removeCallback(index);", "return ackEntry.getAckCallback(index);"),
    ("slow-consumer-retains-queue", "ClientHeadTest", MAIN + "handler/ClientHead.java",
     "transportState.getPacketsQueue().clear();", "// Deliberately retain the disconnected consumer queue."),
    ("accept-missing-attachment-count", "PacketDecoderFuzzingTest", MAIN + "protocol/PacketDecoder.java",
     'throw new IllegalArgumentException("Missing binary attachment count");',
     "// Deliberately accept an empty attachment count."),
]


def run_case(work, selection, log_path):
    reports = work / "netty-socketio-core/target/surefire-reports"
    shutil.rmtree(reports, ignore_errors=True)
    command = ["mvn", "--batch-mode", "--no-transfer-progress", "-f",
               str(work / "netty-socketio-core/pom.xml"), "-Dtest=" + selection,
               "test-compile", "surefire:test@default-test"]
    with log_path.open("w") as log:
        try:
            result = subprocess.run(command, cwd=work, stdout=log, stderr=subprocess.STDOUT, timeout=240)
        except subprocess.TimeoutExpired:
            return {"status": "inconclusive", "reason": "execution timeout"}
    cases = []
    for report in sorted(reports.glob("TEST-*.xml")):
        for case in ET.parse(report).getroot().findall("testcase"):
            cases.append(case)
    failures = [case for case in cases if case.find("failure") is not None]
    errors = [case for case in cases if case.find("error") is not None]
    skipped = [case for case in cases if case.find("skipped") is not None]
    assertion_failures = all("Assertion" in case.find("failure").get("type", "")
                             for case in failures)
    return {"exit_code": result.returncode, "cases": len(cases), "failures": len(failures),
            "errors": len(errors), "skipped": len(skipped), "assertion_failures": assertion_failures,
            "failed_cases": [case.get("name") for case in failures]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    results = {}
    with tempfile.TemporaryDirectory(prefix="socketio-assertion-controls-") as temporary:
        work = Path(temporary)
        shutil.copy2(ROOT / "pom.xml", work / "pom.xml")
        core = work / "netty-socketio-core"
        core.mkdir()
        shutil.copy2(ROOT / "netty-socketio-core/pom.xml", core / "pom.xml")
        shutil.copytree(ROOT / "netty-socketio-core/src", core / "src",
                        ignore=shutil.ignore_patterns("node_modules"))
        selection = ",".join(dict.fromkeys(control[1] for control in CONTROLS))
        baseline = run_case(work, selection, output / "baseline.log")
        results["baseline"] = baseline
        if (baseline.get("exit_code") != 0 or not baseline.get("cases")
                or baseline.get("failures") or baseline.get("errors") or baseline.get("skipped")):
            (output / "results.json").write_text(json.dumps(results, indent=2))
            raise SystemExit("Baseline did not pass; no mutations can be certified.")
        for name, test, relative, before, after in CONTROLS:
            source = work / relative
            original = source.read_text()
            if original.count(before) != 1:
                raise SystemExit("Mutation anchor must match exactly once: " + name)
            try:
                source.write_text(original.replace(before, after))
                result = run_case(work, test, output / (name + ".log"))
                result["status"] = ("detected" if result.get("exit_code") != 0
                                    and result.get("failures", 0) > 0
                                    and result.get("errors") == 0 and result.get("skipped") == 0
                                    and result.get("assertion_failures") else "NOT_PROVED")
                results[name] = result
                print(name + ": " + result["status"], flush=True)
            finally:
                source.write_text(original)
            (output / "results.json").write_text(json.dumps(results, indent=2))
        if any(result.get("status") != "detected" for name, result in results.items() if name != "baseline"):
            raise SystemExit("One or more broken behaviors were not proved detectable.")


if __name__ == "__main__":
    main()

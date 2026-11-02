#!/usr/bin/env python3
"""Reject incomplete, repeated, skipped, flaky or failed JUnit evidence."""
import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def inspect_reports(directory):
    files = sorted(directory.rglob("TEST-*.xml"))
    if not files:
        raise ValueError("No JUnit reports: " + str(directory))
    cases = set()
    owners = set()
    count = 0
    for path in files:
        root = ET.parse(path).getroot()
        entries = root.findall("testcase")
        if int(root.get("tests", "-1")) != len(entries):
            raise ValueError("JUnit count disagrees with case entries: " + str(path))
        owner = path.name[len("TEST-"):-len(".xml")].split("$")[0]
        for case in entries:
            for forbidden in ("failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError"):
                if case.find(forbidden) is not None:
                    raise ValueError(forbidden + ": " + owner + " / " + case.get("name", ""))
            identity = (owner, case.get("classname"), case.get("name"))
            if identity in cases:
                raise ValueError("Repeated case identity: " + str(identity))
            cases.add(identity)
            owners.add(owner)
            count += 1
    if not count:
        raise ValueError("Reports contain no executed test cases")
    return count, owners


def expected_core_classes(source):
    expected = set()
    for path in source.rglob("*Test.java"):
        text = path.read_text()
        if re.search(r"\babstract\s+class\b", text):
            continue
        if not re.search(r"@(?:Test|ParameterizedTest|Nested)\b|extends\s+\w*(?:Test|Support)\b", text):
            continue
        package = re.search(r"^package\s+([^;]+);", text, re.MULTILINE)
        if package:
            expected.add(package.group(1) + "." + path.stem)
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--coverage", type=Path)
    parser.add_argument("--minimum-branch", type=float, default=0.80)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        count, owners = inspect_reports(args.reports)
        if args.source:
            missing = expected_core_classes(args.source) - owners
            if missing:
                raise ValueError("Required concrete test classes not executed: " + ", ".join(sorted(missing)))
        summary = {"cases": count, "test_classes": len(owners), "outcome": "passed"}
        if args.coverage:
            report = ET.parse(args.coverage).getroot()
            branch = next((item for item in report.findall("counter") if item.get("type") == "BRANCH"), None)
            if branch is None:
                raise ValueError("No branch counter in coverage report")
            covered, missed = int(branch.get("covered")), int(branch.get("missed"))
            if covered + missed == 0:
                raise ValueError("Coverage report has zero instrumented branches")
            ratio = covered / (covered + missed)
            summary.update(branch_covered=covered, branch_missed=missed, branch_ratio=ratio)
            if ratio < args.minimum_branch:
                raise ValueError("Branch coverage {:.2%} below required {:.2%}".format(ratio, args.minimum_branch))
        if args.output:
            args.output.write_text(json.dumps(summary, indent=2) + "\n")
        print(json.dumps(summary, indent=2))
    except (ValueError, ET.ParseError, OSError) as error:
        raise SystemExit("EVIDENCE REJECTED: " + str(error))


if __name__ == "__main__":
    main()

"""Negative fixtures for the evidence gate; no external services needed."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from check_evidence import expected_core_classes, inspect_reports

SCRIPT = Path(__file__).resolve().parent / "check_evidence.py"


class EvidenceGateTest(unittest.TestCase):
    """Unit and CLI negative-control tests for check_evidence.py."""

    def inspect(self, xml, second=None):
        """Write one or two synthetic JUnit XML reports and run inspect_reports on them."""
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "TEST-example.Test.xml").write_text(xml)
            if second:
                nested = directory / "other-partition"
                nested.mkdir()
                (nested / "TEST-example.Test.xml").write_text(second)
            return inspect_reports(directory)

    def run_cli(self, *args):
        """Invoke check_evidence.py as a subprocess with the provided CLI arguments."""
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            capture_output=True,
            text=True,
        )

    def test_expected_classes_only_excludes_top_level_abstract_classes(self):
        """Verify expected_core_classes ignores top-level abstract classes but keeps concrete ones."""
        fixtures = {
            "AbstractTest": "public abstract class AbstractTest { @Test void test() {} }",
            "MultilineTest": "abstract\npublic class MultilineTest { @Test void test() {} }",
            "NestedTest": "public class NestedTest { abstract class Helper {} @Test void test() {} }",
            "CommentTest": "/** abstract class Example {} */ public class CommentTest { @Test void test() {} }",
            "SiblingTest": "abstract class Helper {} public class SiblingTest { @Test void test() {} }",
            "LiteralTest": 'public class LiteralTest { String s = "abstract class Example {"; @Test void test() {} }',
        }
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name, body in fixtures.items():
                (directory / (name + ".java")).write_text("package example;\n" + body)
            self.assertEqual({"example.NestedTest", "example.CommentTest", "example.LiteralTest", "example.SiblingTest"},
                             expected_core_classes(directory))

    def test_accepts_nonempty_consistent_success(self):
        """Verify inspect_reports accepts a valid passing JUnit XML report."""
        self.assertEqual(1, self.inspect('<testsuite tests="1"><testcase name="case" classname="Test"/></testsuite>')[0])

    def test_rejects_each_nonpass_outcome(self):
        """Verify inspect_reports rejects failure, error, skipped, flaky, and rerun test elements."""
        for outcome in ["failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError"]:
            with self.subTest(outcome=outcome), self.assertRaisesRegex(ValueError, r"^" + outcome + r":"):
                self.inspect('<testsuite tests="1"><testcase name="case"><' + outcome + '/></testcase></testsuite>')

    def test_rejects_count_mismatch(self):
        """Verify inspect_reports rejects reports whose declared test count disagrees with testcase entries."""
        with self.assertRaises(ValueError):
            self.inspect('<testsuite tests="2"><testcase name="case"/></testsuite>')

    def test_rejects_missing_or_invalid_declared_count(self):
        """Verify inspect_reports rejects missing, negative, or non-integer tests attributes."""
        for count in ('', ' tests="-1"', ' tests="-2"', ' tests="invalid"'):
            for classname in ('Test', 'Test$Nested'):
                with self.subTest(count=count, classname=classname), self.assertRaisesRegex(
                        ValueError, r"JUnit test count is missing or negative: .*TEST-example\.Test\.xml"):
                    self.inspect('<testsuite' + count + '><testcase name="case" classname="'
                                 + classname + '"/></testsuite>')

    def test_accepts_nested_container_report_counts(self):
        """Verify inspect_reports allows nested @Nested container test counts."""
        self.assertEqual(1, self.inspect('<testsuite tests="0"><testcase name="case" classname="Test$Nested"/></testsuite>')[0])

    def test_rejects_repeated_case_across_partitions(self):
        """Verify inspect_reports rejects duplicate testcase executions across partitions."""
        xml = '<testsuite tests="1"><testcase name="case" classname="Test"/></testsuite>'
        with self.assertRaises(ValueError):
            self.inspect(xml, xml)

    def test_rejects_empty_execution(self):
        """Verify inspect_reports rejects test suites with zero executed testcases."""
        with self.assertRaises(ValueError):
            self.inspect('<testsuite tests="0"/>')

    def test_main_rejects_missing_expected_class(self):
        """Verify the CLI exits non-zero when a concrete test class in --source lacks a report."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reports = root / "reports"
            source = root / "src"
            reports.mkdir()
            source.mkdir()
            (reports / "TEST-example.ExecutedTest.xml").write_text(
                '<testsuite tests="1"><testcase name="case" classname="example.ExecutedTest"/></testsuite>'
            )
            (source / "ExecutedTest.java").write_text("package example;\npublic class ExecutedTest { @Test void t() {} }")
            (source / "MissingTest.java").write_text("package example;\npublic class MissingTest { @Test void t() {} }")
            result = self.run_cli("--reports", str(reports), "--source", str(source))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Required concrete test classes not executed: example.MissingTest", result.stderr)

    def test_main_rejects_zero_instrumented_branches(self):
        """Verify the CLI exits non-zero when the JaCoCo report has zero total branches."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reports = root / "reports"
            reports.mkdir()
            (reports / "TEST-example.Test.xml").write_text(
                '<testsuite tests="1"><testcase name="case" classname="example.Test"/></testsuite>'
            )
            coverage = root / "jacoco.xml"
            coverage.write_text('<report><counter type="BRANCH" covered="0" missed="0"/></report>')
            result = self.run_cli("--reports", str(reports), "--coverage", str(coverage))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Coverage report has zero instrumented branches", result.stderr)

    def test_main_rejects_branch_ratio_below_minimum(self):
        """Verify the CLI exits non-zero when branch coverage is below --minimum-branch."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reports = root / "reports"
            reports.mkdir()
            (reports / "TEST-example.Test.xml").write_text(
                '<testsuite tests="1"><testcase name="case" classname="example.Test"/></testsuite>'
            )
            coverage = root / "jacoco.xml"
            coverage.write_text('<report><counter type="BRANCH" covered="60" missed="40"/></report>')
            result = self.run_cli(
                "--reports", str(reports), "--coverage", str(coverage), "--minimum-branch", "0.65"
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Branch coverage 60.00% below required 65.00%", result.stderr)

    def test_main_rejects_missing_coverage_file(self):
        """Verify the CLI exits non-zero with EVIDENCE REJECTED when --coverage points to a missing file."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reports = root / "reports"
            reports.mkdir()
            (reports / "TEST-example.Test.xml").write_text(
                '<testsuite tests="1"><testcase name="case" classname="example.Test"/></testsuite>'
            )
            missing_coverage = root / "nonexistent-jacoco.xml"
            result = self.run_cli("--reports", str(reports), "--coverage", str(missing_coverage))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("EVIDENCE REJECTED:", result.stderr)


if __name__ == "__main__":
    unittest.main()

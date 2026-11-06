"""Negative fixtures for the evidence gate; no external services needed."""
import tempfile
import unittest
from pathlib import Path

from check_evidence import inspect_reports


class EvidenceGateTest(unittest.TestCase):
    def inspect(self, xml, second=None):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "TEST-example.Test.xml").write_text(xml)
            if second:
                nested = directory / "other-partition"
                nested.mkdir()
                (nested / "TEST-example.Test.xml").write_text(second)
            return inspect_reports(directory)

    def test_accepts_nonempty_consistent_success(self):
        self.assertEqual(1, self.inspect('<testsuite tests="1"><testcase name="case" classname="Test"/></testsuite>')[0])

    def test_rejects_each_nonpass_outcome(self):
        for outcome in ["failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError"]:
            with self.subTest(outcome=outcome), self.assertRaises(ValueError):
                self.inspect('<testsuite tests="1"><testcase name="case"><' + outcome + '/></testcase></testsuite>')

    def test_rejects_count_mismatch(self):
        with self.assertRaises(ValueError):
            self.inspect('<testsuite tests="2"><testcase name="case"/></testsuite>')

    def test_rejects_missing_or_invalid_declared_count(self):
        for count in ('', ' tests="-1"', ' tests="-2"', ' tests="invalid"'):
            for classname in ('Test', 'Test$Nested'):
                with self.subTest(count=count, classname=classname), self.assertRaises(ValueError):
                    self.inspect('<testsuite' + count + '><testcase name="case" classname="'
                                 + classname + '"/></testsuite>')

    def test_accepts_nested_container_report_counts(self):
        self.assertEqual(1, self.inspect('<testsuite tests="0"><testcase name="case" classname="Test$Nested"/></testsuite>')[0])

    def test_rejects_repeated_case_across_partitions(self):
        xml = '<testsuite tests="1"><testcase name="case" classname="Test"/></testsuite>'
        with self.assertRaises(ValueError):
            self.inspect(xml, xml)

    def test_rejects_empty_execution(self):
        with self.assertRaises(ValueError):
            self.inspect('<testsuite tests="0"/>')


if __name__ == "__main__":
    unittest.main()

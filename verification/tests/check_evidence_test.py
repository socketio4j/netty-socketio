"""Negative fixtures for the evidence gate; no external services needed."""
import tempfile
import unittest
from pathlib import Path

from check_evidence import expected_core_classes, inspect_reports


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

    def test_expected_classes_only_excludes_top_level_abstract_classes(self):
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

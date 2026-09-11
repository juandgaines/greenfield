#!/usr/bin/env python3
"""Tests for lib/ktlint_report.py — the per-edit gate's parsing half."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))
import ktlint_report  # noqa: E402

PLAIN = (
    "src/main/java/Foo.kt:3:1: Wildcard import (standard:no-wildcard-imports)\n"
    "src/main/java/Foo.kt:12:5: Unexpected indentation (standard:indent)\n"
)


class EditedPathTests(unittest.TestCase):
    def test_reads_the_file_path_from_the_event(self):
        event = {"tool_name": "Write", "tool_input": {"file_path": "/repo/A.kt"}}
        self.assertEqual(ktlint_report.edited_path(event), "/repo/A.kt")

    def test_event_without_tool_input(self):
        self.assertIsNone(ktlint_report.edited_path({"tool_name": "Bash"}))

    def test_event_with_no_file_path(self):
        self.assertIsNone(ktlint_report.edited_path({"tool_input": {"command": "ls"}}))

    def test_empty_file_path_is_not_a_path(self):
        self.assertIsNone(ktlint_report.edited_path({"tool_input": {"file_path": ""}}))

    def test_non_string_file_path_is_rejected(self):
        self.assertIsNone(ktlint_report.edited_path({"tool_input": {"file_path": 7}}))


class KotlinSuffixTests(unittest.TestCase):
    def test_kt_and_kts_are_covered(self):
        self.assertTrue(ktlint_report.is_kotlin("/repo/A.kt"))
        self.assertTrue(ktlint_report.is_kotlin("/repo/build.gradle.kts"))

    def test_everything_else_is_not(self):
        for path in ("/repo/A.java", "/repo/strings.xml", "/repo/README.md",
                     "/repo/libs.versions.toml", "/repo/Kt", "/repo/a.kt.bak"):
            self.assertFalse(ktlint_report.is_kotlin(path), path)


class ParseTests(unittest.TestCase):
    def test_parses_every_field(self):
        first = ktlint_report.parse(PLAIN)[0]
        self.assertEqual(first["path"], "src/main/java/Foo.kt")
        self.assertEqual(first["line"], 3)
        self.assertEqual(first["column"], 1)
        self.assertEqual(first["message"], "Wildcard import")
        self.assertEqual(first["rule"], "standard:no-wildcard-imports")

    def test_ignores_non_violation_lines(self):
        noisy = "Loading rules\n" + PLAIN + "Summary error count: 2\n"
        self.assertEqual(len(ktlint_report.parse(noisy)), 2)

    def test_clean_output_has_no_violations(self):
        self.assertEqual(ktlint_report.parse(""), [])

    def test_message_containing_parentheses_keeps_the_trailing_rule(self):
        line = "A.kt:1:1: Function name (see docs) is wrong (standard:function-naming)"
        parsed = ktlint_report.parse(line)[0]
        self.assertEqual(parsed["rule"], "standard:function-naming")
        self.assertEqual(parsed["message"], "Function name (see docs) is wrong")

    def test_a_syntax_error_has_an_empty_rule_and_still_parses(self):
        # ktlint reports a file that is not valid Kotlin with an EMPTY rule
        # group; requiring a non-empty id made the most broken possible edit
        # the one edit that produced no report.
        line = "Broken.kt:3:12: Not a valid Kotlin file (3:12 expecting ')') ()"
        parsed = ktlint_report.parse(line)
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]["rule"], "")
        self.assertIn("Not a valid Kotlin file", parsed[0]["message"])

    def test_format_mode_duplicates_are_collapsed(self):
        # --format lints before and after its correction pass, printing each
        # residual violation once per pass.
        doubled = PLAIN + PLAIN
        self.assertEqual(len(ktlint_report.parse(doubled)), 2)


class ForFileTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = os.path.realpath(self.tmp.name)
        self.target = os.path.join(self.root, "src", "main", "java", "Foo.kt")
        os.makedirs(os.path.dirname(self.target))
        open(self.target, "w").close()
        other = os.path.join(self.root, "src", "main", "java", "Bar.kt")
        open(other, "w").close()

    def tearDown(self):
        self.tmp.cleanup()

    def test_keeps_violations_in_the_edited_file(self):
        self.assertEqual(len(ktlint_report.for_file(PLAIN, self.target, self.root)), 2)

    def test_drops_violations_in_another_file(self):
        other = PLAIN + "src/main/java/Bar.kt:1:1: Wildcard import (standard:no-wildcard-imports)\n"
        kept = ktlint_report.for_file(other, self.target, self.root)
        self.assertEqual(len(kept), 2)
        self.assertTrue(all(v["path"].endswith("Foo.kt") for v in kept))

    def test_absolute_paths_in_the_output_still_match(self):
        absolute = "%s:9:1: Wildcard import (standard:no-wildcard-imports)\n" % self.target
        self.assertEqual(len(ktlint_report.for_file(absolute, self.target, self.root)), 1)


class RenderTests(unittest.TestCase):
    def test_nothing_to_report_renders_empty(self):
        self.assertEqual(ktlint_report.render([], "A.kt"), "")

    def test_report_names_file_line_column_and_rule(self):
        body = ktlint_report.render(ktlint_report.parse(PLAIN), "src/main/java/Foo.kt")
        self.assertIn("src/main/java/Foo.kt:3:1:", body)
        self.assertIn("(standard:no-wildcard-imports)", body)
        self.assertIn("2 violations", body)

    def test_single_violation_is_not_pluralised(self):
        body = ktlint_report.render(ktlint_report.parse(PLAIN)[:1], "Foo.kt")
        self.assertIn("1 violation ", body)
        self.assertNotIn("1 violations", body)

    def test_an_empty_rule_renders_without_dangling_parens(self):
        line = "Broken.kt:3:12: Not a valid Kotlin file (3:12 expecting ')') ()"
        body = ktlint_report.render(ktlint_report.parse(line), "Broken.kt")
        self.assertIn("Broken.kt:3:12: Not a valid Kotlin file", body)
        self.assertNotIn(" ()", body)


if __name__ == "__main__":
    unittest.main(verbosity=1)

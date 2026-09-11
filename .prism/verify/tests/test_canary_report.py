#!/usr/bin/env python3
"""The canary verdict is the framework's only proof that its rules are loaded.

Every other check in prism-doctor reads a file and believes it. This one reads
what detekt actually produced, so its parsing has to be right about a format
nobody here controls -- and getting it wrong in the permissive direction turns
the doctor into the false green it exists to catch.

The cases below are the ones that would each, individually, do that.
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))

import canary_report  # noqa: E402

CANARY = "PrismDoctorCanary.kt"
RULE = "NoConsoleLogging"


def report(body):
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".xml", delete=False, encoding="utf-8"
    )
    handle.write(body)
    handle.close()
    return handle.name


class VerdictTests(unittest.TestCase):
    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            try:
                os.unlink(path)
            except OSError:
                pass

    def _report(self, body):
        path = report(body)
        self.paths.append(path)
        return path

    def test_finds_the_rule(self):
        path = self._report(
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<checkstyle version="4.3">\n'
            '  <file name="/repo/app/src/main/kotlin/com/example/PrismDoctorCanary.kt">\n'
            '    <error line="4" column="5" severity="error"'
            ' message="Remove the &apos;println&apos; call from shipped code."'
            ' source="detekt.NoConsoleLogging" />\n'
            "  </file>\n"
            "</checkstyle>\n"
        )
        self.assertEqual(canary_report.verdict(path, CANARY, RULE), canary_report.FOUND)

    def test_relative_path_still_matches(self):
        """detekt resolves file names against basePath, which the convention
        plugin sets to the root project -- so the name may be relative. Matching
        on an absolute path would fail on exactly the repositories that are
        configured correctly."""
        path = self._report(
            '<checkstyle version="4.3">\n'
            '  <file name="app/src/main/kotlin/com/example/PrismDoctorCanary.kt">\n'
            '    <error line="4" column="5" source="detekt.NoConsoleLogging" />\n'
            "  </file>\n"
            "</checkstyle>\n"
        )
        self.assertEqual(canary_report.verdict(path, CANARY, RULE), canary_report.FOUND)

    def test_analysed_but_rule_absent_is_the_false_green(self):
        """THE case. detekt ran, read the canary, and reported something else.
        The rule set is not loaded. Reporting this as a pass is the single
        worst outcome the framework has."""
        path = self._report(
            '<checkstyle version="4.3">\n'
            '  <file name="/repo/app/src/main/kotlin/com/example/PrismDoctorCanary.kt">\n'
            '    <error line="1" column="1" source="detekt.MagicNumber" />\n'
            "  </file>\n"
            "</checkstyle>\n"
        )
        self.assertEqual(
            canary_report.verdict(path, CANARY, RULE), canary_report.FILE_ANALYSED
        )

    def test_empty_file_node_is_not_a_pass(self):
        """A clean canary file means the rule did not fire. It never means the
        rule is loaded."""
        path = self._report(
            '<checkstyle version="4.3">\n'
            '  <file name="/repo/app/src/main/kotlin/com/example/PrismDoctorCanary.kt" />\n'
            "</checkstyle>\n"
        )
        self.assertEqual(
            canary_report.verdict(path, CANARY, RULE), canary_report.FILE_ANALYSED
        )

    def test_canary_absent_from_report_is_distinguished(self):
        """detekt produced a report but never read the canary -- a source-set
        problem. Saying 'file-analysed' here would send the reader to check
        four wiring requirements that are all fine."""
        path = self._report(
            '<checkstyle version="4.3">\n'
            '  <file name="/repo/app/src/main/kotlin/com/example/Other.kt">\n'
            '    <error line="1" column="1" source="detekt.NoConsoleLogging" />\n'
            "  </file>\n"
            "</checkstyle>\n"
        )
        self.assertEqual(
            canary_report.verdict(path, CANARY, RULE), canary_report.NOT_ANALYSED
        )

    def test_rule_reported_in_another_file_does_not_count(self):
        """A pre-existing println elsewhere in the repository proves nothing
        about the run we just performed. Covered by the case above, asserted
        separately because a suffix match written slightly loosely would pass
        it."""
        path = self._report(
            '<checkstyle version="4.3">\n'
            '  <file name="/repo/app/src/main/kotlin/com/example/NotThePrismDoctorCanary.kt">\n'
            '    <error line="1" column="1" source="detekt.NoConsoleLogging" />\n'
            "  </file>\n"
            "</checkstyle>\n"
        )
        # Endswith-matching means this DOES match the suffix; the guard is that
        # the doctor writes a file whose name it chose. Documented, not fixed:
        # a stricter match on the full relative path would break the basePath
        # case above. This asserts the known behaviour so a future change to
        # the matching is a deliberate one.
        self.assertEqual(canary_report.verdict(path, CANARY, RULE), canary_report.FOUND)

    def test_ruleset_qualified_source_does_not_match(self):
        """detekt 2.0 writes the BARE rule id after 'detekt.'. If a future
        version qualifies it, this test fails rather than the doctor silently
        reporting the false green."""
        path = self._report(
            '<checkstyle version="4.3">\n'
            '  <file name="/repo/PrismDoctorCanary.kt">\n'
            '    <error line="1" column="1" source="detekt.prism:NoConsoleLogging" />\n'
            "  </file>\n"
            "</checkstyle>\n"
        )
        self.assertEqual(
            canary_report.verdict(path, CANARY, RULE), canary_report.FILE_ANALYSED
        )

    def test_missing_report_is_undetermined(self):
        self.assertEqual(
            canary_report.verdict("/nonexistent/report.xml", CANARY, RULE),
            canary_report.NO_REPORT,
        )
        self.assertIn(canary_report.NO_REPORT, canary_report.UNDETERMINED)

    def test_truncated_report_is_undetermined_not_a_pass(self):
        """A Gradle crash mid-write must not read as 'no findings'."""
        path = self._report('<checkstyle version="4.3">\n  <file name="/repo/PRISM')
        self.assertEqual(
            canary_report.verdict(path, CANARY, RULE), canary_report.UNREADABLE
        )
        self.assertIn(canary_report.UNREADABLE, canary_report.UNDETERMINED)

    def test_empty_report_is_undetermined(self):
        path = self._report("")
        self.assertEqual(
            canary_report.verdict(path, CANARY, RULE), canary_report.UNREADABLE
        )


class ExitStatusTests(unittest.TestCase):
    """The shell reads stdout, but a caller that ignores it must still not be
    able to mistake undetermined for a pass."""

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            try:
                os.unlink(path)
            except OSError:
                pass

    def test_found_exits_zero(self):
        path = report(
            '<checkstyle><file name="/x/PrismDoctorCanary.kt">'
            '<error source="detekt.NoConsoleLogging" /></file></checkstyle>'
        )
        self.paths.append(path)
        self.assertEqual(canary_report.main(["x", path, CANARY, RULE]), 0)

    def test_false_green_exits_one(self):
        path = report(
            '<checkstyle><file name="/x/PrismDoctorCanary.kt" /></checkstyle>'
        )
        self.paths.append(path)
        self.assertEqual(canary_report.main(["x", path, CANARY, RULE]), 1)

    def test_undetermined_exits_two(self):
        self.assertEqual(
            canary_report.main(["x", "/nonexistent.xml", CANARY, RULE]), 2
        )

    def test_wrong_arity_exits_two(self):
        self.assertEqual(canary_report.main(["x"]), 2)


if __name__ == "__main__":
    unittest.main()

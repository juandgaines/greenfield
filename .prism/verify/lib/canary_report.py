#!/usr/bin/env python3
"""Decide, from a detekt report, whether the PRISM rule set actually ran.

This is the one question in the whole framework that cannot be answered by
reading configuration, because the failure it exists to catch looks exactly
like success: a rules module that was copied in but never put on the
`detektPlugins` configuration produces a detekt run that loads zero rules and
reports BUILD SUCCESSFUL over unexamined code.

`prism-doctor` plants a file that violates a *PRISM-owned* rule and runs detekt
over it. This module reads what came back. A built-in detekt rule would fire
even with the rule set entirely absent, which is why the canary rule must be
one of ours.

FOUR VERDICTS, and the distinction between the last three is the entire value:

    found                the rule fired. The rule set is live.
    file-analysed        detekt read the canary and did not report the rule.
                         THE FALSE GREEN. detekt is running; our rules are not
                         loaded.
    not-analysed         a report exists but never mentions the canary file, so
                         detekt did not read it. A source-set problem, not a
                         rule-loading problem, and saying so saves the reader
                         from checking the wrong four things.
    no-report /          nothing to read. UNDETERMINED, never a pass: an
    unreadable           inability to ask the question is not an answer of no.

Report format. detekt 2.0 emits `checkstyle` XML — the type was renamed from
`xml`, and `DetektReports` has no `xml` member at all. Each finding is an
`<error>` whose `source` attribute is the literal `detekt.` followed by the
bare rule id, NOT the ruleset-qualified one:

    <checkstyle version="4.3">
      <file name="/abs/path/to/PrismDoctorCanary.kt">
        <error line="4" column="5" severity="error"
               message="Remove the 'println' call from shipped code."
               source="detekt.NoConsoleLogging" />
      </file>
    </checkstyle>

The `name` attribute is resolved against detekt's `basePath`, which the
convention plugin sets to the root project. It may therefore be absolute or
relative depending on configuration, so files are matched on SUFFIX. Matching
an absolute path would fail on exactly the correctly-configured repositories.
"""

import os
import sys
import xml.etree.ElementTree as ElementTree

FOUND = "found"
FILE_ANALYSED = "file-analysed"
NOT_ANALYSED = "not-analysed"
NO_REPORT = "no-report"
UNREADABLE = "unreadable"

#: Verdicts that mean the question could not be answered. A caller MUST treat
#: these as non-zero, never as an absence of findings.
UNDETERMINED = (NO_REPORT, UNREADABLE)


def verdict(report_path, canary_filename, rule_id):
    """Return one of the five verdicts above.

    `canary_filename` is matched as a suffix of each `<file name=...>`;
    `rule_id` is the bare detekt rule id, without the `detekt.` prefix and
    without a ruleset qualifier.
    """
    if not report_path or not os.path.isfile(report_path):
        return NO_REPORT
    try:
        root = ElementTree.parse(report_path).getroot()
    except Exception:
        # Deliberately broad: a truncated, empty or non-XML file is the same
        # answer as a malformed one -- we cannot tell, so we do not guess.
        return UNREADABLE

    wanted_source = "detekt." + rule_id
    saw_canary = False
    for file_node in root.iter("file"):
        name = file_node.get("name") or ""
        if not name.endswith(canary_filename):
            continue
        saw_canary = True
        for error in file_node.iter("error"):
            if error.get("source") == wanted_source:
                return FOUND
    return FILE_ANALYSED if saw_canary else NOT_ANALYSED


def main(argv):
    if len(argv) != 4:
        sys.stderr.write(
            "usage: canary_report.py <report.xml> <CanaryFile.kt> <RuleId>\n"
        )
        return 2
    result = verdict(argv[1], argv[2], argv[3])
    sys.stdout.write(result + "\n")
    # The shell reads the verdict from stdout; the status is a convenience so a
    # caller that ignores stdout still cannot mistake undetermined for a pass.
    if result == FOUND:
        return 0
    if result in UNDETERMINED:
        return 2
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))

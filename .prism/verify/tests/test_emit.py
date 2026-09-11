#!/usr/bin/env python3
"""The outbound half of the seam: does the gate's verdict survive translation?

`test_normalise.py` covers the inbound half. This is its mirror, and it exists
because until it did there was NO test of the dispatcher's output at all -- the
gates were tested, the inbound translation was tested, and the one place the
verdict is turned back into the harness's own vocabulary was not. That is how a
Cursor adapter shipped in which every ALLOW printed nothing, which under the
`failClosed: true` the same adapter correctly sets is a failed hook, which
blocks. The gates were fine. The answer never arrived.

The property under test throughout: an allow must be as legible to the harness
as a deny is.
"""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "adapters", "lib"))

import emit  # noqa: E402


def run(harness, gate, status, message=""):
    exit_status, doc = emit.emit(harness, gate, status, message)
    return exit_status, doc


class ExitCodeHarnesses(unittest.TestCase):
    """claude-code, codex and copilot read the process status. Do not touch it."""

    def test_status_passes_through_verbatim(self):
        for harness in emit.EXIT_CODE_HARNESSES:
            for gate in emit.GATES:
                for status in (0, 2):
                    got, doc = run(harness, gate, status, "some reason")
                    self.assertEqual(got, status, (harness, gate, status))
                    self.assertIsNone(doc, "must stay passthrough")

    def test_a_crash_is_not_a_denial(self):
        # 127 is "the gate could not run", which the harness distinguishes from
        # a refusal. Normalising it to 2 would report a clean tree as blocked.
        got, doc = run("claude-code", "push", 127, "boom")
        self.assertEqual(got, 127)
        self.assertIsNone(doc)


class CursorPush(unittest.TestCase):
    """beforeShellExecution -- the only Cursor event that can block."""

    def test_allow_is_an_explicit_permission(self):
        status, doc = run("cursor", "push", 0)
        self.assertEqual(status, 0)
        self.assertEqual(doc["permission"], "allow")

    def test_allow_is_never_an_empty_document(self):
        # THE REGRESSION GUARD. An empty stdout is a failed hook on Cursor, and
        # with failClosed set a failed hook blocks. This is the defect.
        status, doc = run("cursor", "push", 0)
        self.assertTrue(doc, "an empty allow document blocks every command")
        self.assertTrue(json.dumps(doc).strip())

    def test_deny_says_so_in_both_channels(self):
        status, doc = run("cursor", "push", 2, "denied: unverified tree")
        self.assertEqual(status, 2, "exit 2 is kept so the deny probe still passes")
        self.assertEqual(doc["permission"], "deny")

    def test_user_message_is_one_line_agent_message_is_all_of_it(self):
        reason = "first line\nsecond line\nthird line"
        _, doc = run("cursor", "push", 2, reason)
        self.assertEqual(doc["user_message"], "first line")
        self.assertEqual(doc["agent_message"], reason)

    def test_deny_with_no_reason_still_carries_one(self):
        _, doc = run("cursor", "push", 2, "")
        self.assertTrue(doc["agent_message"])
        self.assertTrue(doc["user_message"])


class CursorCannotBlock(unittest.TestCase):
    """ktlint, stop and subagent are events Cursor cannot refuse."""

    def test_ktlint_never_returns_a_blocking_status(self):
        # afterFileEdit has no verdict to give. Returning the gate's 2 would
        # make every formatting finding a failed hook.
        for status in (0, 2):
            got, doc = run("cursor", "ktlint", status, "needs formatting")
            self.assertEqual(got, 0)
            self.assertIsNotNone(doc)

    def test_ktlint_carries_the_report_as_an_advisory(self):
        _, doc = run("cursor", "ktlint", 2, "Wildcard import")
        self.assertEqual(doc["agent_message"], "Wildcard import")

    def test_turn_end_deny_becomes_a_continuation(self):
        # This is `turn_end: "continue"` -- advertised in the manifest, and
        # until now implemented nowhere.
        for gate in ("stop", "subagent"):
            status, doc = run("cursor", gate, 2, "staticAnalysis failed in :app")
            self.assertEqual(status, 0, "Cursor cannot deny at turn end")
            self.assertEqual(doc["followup_message"], "staticAnalysis failed in :app")
            self.assertNotIn("permission", doc)

    def test_turn_end_allow_is_quiet(self):
        # The give-up notice rides on stderr: re-prompting about a loop we have
        # just conceded would restart the loop we gave up on.
        for gate in ("stop", "subagent"):
            status, doc = run("cursor", gate, 0, "giving up; NOT verified")
            self.assertEqual(status, 0)
            self.assertEqual(doc, {})


class Antigravity(unittest.TestCase):
    def test_push_allow_is_explicit(self):
        # Was an empty stdout -- latent only because this harness is tier 2.
        status, doc = run("antigravity", "push", 0)
        self.assertEqual(status, 0)
        self.assertEqual(doc["decision"], "allow")

    def test_push_deny_keeps_its_vocabulary(self):
        status, doc = run("antigravity", "push", 2, "why not")
        self.assertEqual(status, 0, "a non-zero exit is not a denial here")
        self.assertEqual(doc["decision"], "deny")
        self.assertEqual(doc["reason"], "why not")

    def test_stop_continues_rather_than_denying(self):
        # Stop re-enters the loop on `continue` and CANNOT deny. Shipping a
        # denial here was a verdict the harness had no way to act on.
        status, doc = run("antigravity", "stop", 2, "not verified")
        self.assertEqual(status, 0)
        self.assertEqual(doc["decision"], "continue")
        self.assertEqual(doc["reason"], "not verified")

    def test_stop_never_denies(self):
        for status_in in (0, 2):
            _, doc = run("antigravity", "stop", status_in, "x")
            self.assertNotEqual(doc.get("decision"), "deny")


class EveryDocumentHarnessSpeaks(unittest.TestCase):
    """The invariant the defect violated, asserted across the whole table."""

    DOCUMENT_HARNESSES = ("cursor", "antigravity")

    def test_no_document_harness_ever_answers_with_nothing(self):
        for harness in self.DOCUMENT_HARNESSES:
            for gate in emit.GATES:
                for status in (0, 2):
                    _, doc = run(harness, gate, status, "reason text")
                    self.assertIsNotNone(
                        doc, "%s/%s/%s produced no document" % (harness, gate, status))

    def test_output_is_always_serialisable(self):
        for harness in emit.HARNESSES:
            for gate in emit.GATES:
                _, doc = run(harness, gate, 2, 'quotes " and \n newlines')
                if doc is not None:
                    json.dumps(doc)


class UnknownPairings(unittest.TestCase):
    def test_unknown_harness_gets_its_status_back_untouched(self):
        status, doc = run("not-a-harness", "push", 2, "x")
        self.assertEqual(status, 2)
        self.assertIsNone(doc)

    def test_unknown_gate_gets_its_status_back_untouched(self):
        status, doc = run("cursor", "not-a-gate", 2, "x")
        self.assertEqual(status, 2)
        self.assertIsNone(doc)


if __name__ == "__main__":
    unittest.main()

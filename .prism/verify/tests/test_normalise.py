"""Payload translation: five harnesses, four gates, one canonical shape.

The gates read one document shape and five harnesses emit five others. Every
case here is taken from `docs/harness-capability-matrix.md`'s per-harness
contract -- from a payload captured by an executed probe where one exists.

The cases that matter most are the negative ones. A translation that quietly
returned an empty command for an event it did not understand would allow every
push through that harness, ungated and silently, which is the exact failure
push-gate.sh's own header is written against.
"""

import importlib.util
import json
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PAYLOAD = os.path.dirname(os.path.dirname(HERE))
NORMALISE = os.path.join(PAYLOAD, "adapters", "lib", "normalise.py")
DISPATCHER = os.path.join(PAYLOAD, "adapters", "lib", "prism-hook")
MANIFEST = os.path.join(PAYLOAD, "adapters", "harnesses.json")

_spec = importlib.util.spec_from_file_location("prism_normalise", NORMALISE)
n = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(n)


class TranslatesEachHarnessToTheCanonicalShape(unittest.TestCase):
    """Axis A -- the shell command, which is what the push gate reads."""

    def test_claude_code_is_the_identity(self):
        status, out = n.normalise(
            "claude-code", "push", {"tool_input": {"command": "git push origin main"}}
        )
        self.assertEqual(status, n.OK)
        self.assertEqual(out["tool_input"]["command"], "git push origin main")

    def test_cursor_puts_the_command_at_the_top_level(self):
        status, out = n.normalise(
            "cursor", "push", {"command": "git push", "cwd": "/r", "sandbox": False}
        )
        self.assertEqual(status, n.OK)
        self.assertEqual(out["tool_input"]["command"], "git push")

    def test_copilot_puts_the_command_under_toolArgs(self):
        status, out = n.normalise(
            "copilot",
            "push",
            {"toolName": "bash", "toolArgs": {"command": "git push", "description": "d"}},
        )
        self.assertEqual(status, n.OK)
        self.assertEqual(out["tool_input"]["command"], "git push")

    def test_antigravity_nests_it_under_pascal_case_keys(self):
        status, out = n.normalise(
            "antigravity",
            "push",
            {"toolCall": {"args": {"CommandLine": "git push --force"}}},
        )
        self.assertEqual(status, n.OK)
        self.assertEqual(out["tool_input"]["command"], "git push --force")

    def test_codex_uses_the_same_names_as_claude_code(self):
        status, out = n.normalise(
            "codex", "push", {"tool_name": "Bash", "tool_input": {"command": "git push"}}
        )
        self.assertEqual(status, n.OK)
        self.assertEqual(out["tool_input"]["command"], "git push")


class DistinguishesNothingToDoFromCannotTell(unittest.TestCase):
    """The distinction the whole file exists for."""

    def test_an_event_with_no_command_is_no_event_not_an_empty_command(self):
        # A Read event reaching the push gate. There is genuinely nothing to
        # gate, and allowing is correct.
        for harness, event in (
            ("claude-code", {"tool_input": {"file_path": "/a.kt"}}),
            ("cursor", {"file_path": "/a.kt"}),
            ("copilot", {"toolName": "read", "toolArgs": {"path": "/a.kt"}}),
            ("antigravity", {"toolCall": {"args": {"AbsolutePath": "/a.kt"}}}),
        ):
            with self.subTest(harness=harness):
                status, out = n.normalise(harness, "push", event)
                self.assertEqual(status, n.NO_EVENT)
                self.assertEqual(out, {})

    def test_an_unknown_harness_is_unknown_not_empty(self):
        status, out = n.normalise("some-new-cli", "push", {"command": "git push"})
        self.assertEqual(status, n.UNKNOWN)

    def test_a_non_string_command_is_not_accepted_as_one(self):
        # A number or a dict where a command belongs is not "no command"; it is
        # a payload shape we do not recognise.
        status, _ = n.normalise("cursor", "push", {"command": {"argv": ["git", "push"]}})
        self.assertEqual(status, n.NO_EVENT)

    def test_a_non_dict_event_is_unknown(self):
        self.assertEqual(n.normalise("cursor", "push", ["git", "push"])[0], n.UNKNOWN)

    def test_every_declared_harness_has_a_translator(self):
        # A registration file can be added without a translation. That would
        # produce `unknown` for every event -- which denies on push, loudly,
        # rather than allowing. This asserts the pair stays complete anyway.
        for harness in n.HARNESSES:
            with self.subTest(harness=harness):
                self.assertIn(harness, n.TRANSLATORS)


class TurnEndAndSubagentFields(unittest.TestCase):
    def test_cursor_derives_the_loop_guard_from_loop_count(self):
        # Cursor has no stop_hook_active. loop_count carries the same meaning
        # the gate wants: a previous pass already intervened.
        self.assertFalse(n.normalise("cursor", "stop", {"loop_count": 0})[1]["stop_hook_active"])
        self.assertTrue(n.normalise("cursor", "stop", {"loop_count": 2})[1]["stop_hook_active"])

    def test_antigravity_derives_it_from_executionNum(self):
        self.assertFalse(
            n.normalise("antigravity", "stop", {"executionNum": 1})[1]["stop_hook_active"]
        )
        self.assertTrue(
            n.normalise("antigravity", "stop", {"executionNum": 3})[1]["stop_hook_active"]
        )

    def test_copilot_carries_stop_hook_active_verbatim(self):
        out = n.normalise("copilot", "stop", {"stop_hook_active": True})[1]
        self.assertTrue(out["stop_hook_active"])

    def test_antigravity_has_no_subagent_event_and_says_so(self):
        # PostInvocation is a model-invocation boundary, not a subagent one.
        # Translating it into something subagent-shaped would be a lie.
        self.assertEqual(n.normalise("antigravity", "subagent", {})[0], n.UNKNOWN)

    def test_copilot_subagent_fields_are_camel_case(self):
        out = n.normalise(
            "copilot", "subagent", {"agentId": "s-1", "agentType": "reviewer", "cwd": "/r"}
        )[1]
        self.assertEqual((out["agent_id"], out["agent_type"], out["cwd"]), ("s-1", "reviewer", "/r"))

    def test_a_harness_without_cwd_yields_empty_not_root(self):
        # Cursor supplies no cwd on any event. Empty makes the gate take its
        # documented default-tree path; "/" would send it at the filesystem.
        out = n.normalise("cursor", "subagent", {"subagent_type": "reviewer"})[1]
        self.assertEqual(out["cwd"], "")


class TheDispatcherFailsClosedOnThePushGate(unittest.TestCase):
    """The dispatcher is where a translation failure becomes an allow or a deny."""

    def _run(self, harness, gate, payload):
        return subprocess.run(
            ["sh", DISPATCHER, "--harness", harness, "--gate", gate],
            input=payload, capture_output=True, text=True,
        )

    def test_an_unreadable_push_event_denies(self):
        r = self._run("claude-code", "push", "not json at all")
        self.assertEqual(r.returncode, 2)
        self.assertIn("denying", r.stderr)

    def test_an_unknown_harness_denies_on_push(self):
        r = self._run("some-new-cli", "push", '{"command":"git push"}')
        self.assertEqual(r.returncode, 2)

    def test_an_event_with_nothing_to_gate_allows(self):
        r = self._run("claude-code", "push", '{"tool_input":{"file_path":"/a.kt"}}')
        self.assertEqual(r.returncode, 0)

    def test_an_unreadable_ktlint_event_allows(self):
        # ktlint reports and never blocks, so it has nothing useful to do
        # without a path -- and denying an edit over a formatting check would
        # be wildly out of proportion.
        self.assertEqual(self._run("cursor", "ktlint", "garbage").returncode, 0)

    def test_a_missing_engine_denies_on_push(self):
        env = dict(os.environ, PRISM_ENGINE_DIR="/nonexistent/prism/engine")
        r = subprocess.run(
            ["sh", DISPATCHER, "--harness", "claude-code", "--gate", "push"],
            input='{"tool_input":{"command":"git push"}}',
            capture_output=True, text=True, env=env,
        )
        self.assertEqual(r.returncode, 2)
        self.assertIn("engine is not at", r.stderr)


class TheManifestMatchesWhatShips(unittest.TestCase):
    """The manifest is what SETUP.md and prism-doctor both read. If it drifts
    from the files on disk, the doctor verifies a registration nobody wrote."""

    def setUp(self):
        with open(MANIFEST) as fh:
            self.rows = json.load(fh)["harnesses"]

    def test_every_harness_names_a_source_file_that_exists(self):
        for name, row in self.rows.items():
            with self.subTest(harness=name):
                self.assertTrue(
                    os.path.isfile(os.path.join(PAYLOAD, "adapters", row["source"])),
                    "%s names a source file that does not ship" % name,
                )

    def test_every_registration_calls_the_dispatcher_with_its_own_name(self):
        for name, row in self.rows.items():
            with self.subTest(harness=name):
                with open(os.path.join(PAYLOAD, "adapters", row["source"])) as fh:
                    text = fh.read()
                self.assertIn("--harness %s" % name, text)

    def test_cursor_sets_failClosed_on_every_gate_hook(self):
        # Measured: an identical crashing hook allows the command without this
        # key and blocks it with the key set. One word between a gate and the
        # appearance of one.
        row = self.rows["cursor"]
        self.assertEqual(row["fail_closed_key"], "failClosed")
        with open(os.path.join(PAYLOAD, "adapters", row["source"])) as fh:
            doc = json.load(fh)
        for event, entries in doc["hooks"].items():
            for entry in entries:
                with self.subTest(event=event):
                    self.assertIs(entry.get("failClosed"), True)

    def test_tier_2_harnesses_are_not_reported_as_enforcing(self):
        for name, row in self.rows.items():
            with self.subTest(harness=name):
                if row["tier"] == 2:
                    self.assertFalse(row["gate_active"])
                    self.assertEqual(row["gates"], [])
                    self.assertIn("re_test_trigger", row)

    def test_no_promise_claims_more_than_the_row_allows(self):
        # A Tier 2 row must not describe turn-end verification; Cursor cannot
        # deny at turn end and must not claim it either.
        for name, row in self.rows.items():
            with self.subTest(harness=name):
                promise = row["promise"].lower()
                if not row["gate_active"]:
                    self.assertNotIn("turn", promise)
                    self.assertIn("push", promise)
        self.assertNotIn("verifies the work before", self.rows["cursor"]["promise"])

    def test_every_shipped_harness_is_known_to_the_translator(self):
        self.assertEqual(set(self.rows), set(n.HARNESSES))


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Tests for the base-branch configuration guard.

Every test builds a real git repository, commits a baseline configuration, then
weakens or strengthens it in the working tree. That is deliberate: the guard's
whole job is a comparison against `git show`, and a fake would test the
comparison logic while skipping the part most likely to be wrong.

Two properties, and the second is the one that keeps this usable:

  * every weakening is caught, INCLUDING the ones an agent would actually
    reach for first;
  * every strengthening passes silently. A guard that complains about promotion
    is a guard people turn off.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))

import guard  # noqa: E402


class GuardTestCase(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="prism-guard-")
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "t")
        os.makedirs(os.path.join(self.root, ".prism", "verify"))
        # PRISM must already be ON the base branch for any of these tests to
        # mean anything. The guard now skips a tree whose base branch carries no
        # install -- correct, because the install is not a weakening of what was
        # not there. Without this line every test below would pass vacuously,
        # which is the same silent-green the guard exists to prevent.
        self.write(".prism/prism.json", '{"baseBranch": "main"}\n')

    def git(self, *args):
        subprocess.run(("git",) + args, cwd=self.root, check=True,
                       capture_output=True)

    def write(self, path, text):
        full = os.path.join(self.root, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as handle:
            handle.write(text)

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "baseline")

    def findings(self):
        return guard.run(self.root, "main")

    DETEKT = (
        "prism:\n"
        "  NoConsoleLogging:\n"
        "    active: true\n"
        "    excludes: ['**/test/**']\n"
        "  NoNotNullAssertion:\n"
        "    active: true\n"
    )

    # --- the bypass an agent reaches for first ---------------------------
    def test_a_rule_switched_off_is_caught(self):
        self.write("detekt.yml", self.DETEKT)
        self.commit()
        self.write("detekt.yml", self.DETEKT.replace(
            "  NoNotNullAssertion:\n    active: true",
            "  NoNotNullAssertion:\n    active: false"))
        found = self.findings()
        self.assertEqual(1, len(found), found)
        self.assertIn("NoNotNullAssertion", found[0])

    def test_a_widened_exclusion_is_caught(self):
        self.write("detekt.yml", self.DETEKT)
        self.commit()
        self.write("detekt.yml", self.DETEKT.replace(
            "excludes: ['**/test/**']", "excludes: ['**/test/**', '**/MyFile.kt']"))
        found = self.findings()
        self.assertEqual(1, len(found), found)
        self.assertIn("gained exclusions", found[0])

    def test_a_deleted_rule_is_caught(self):
        self.write("detekt.yml", self.DETEKT)
        self.commit()
        self.write("detekt.yml", "prism:\n  NoConsoleLogging:\n    active: true\n"
                                 "    excludes: ['**/test/**']\n")
        self.assertIn("REMOVED", " ".join(self.findings()))

    # --- ktlint's real property syntax has hyphens in it ------------------
    #
    # A character class of [A-Za-z0-9_] matched no real ktlint rule name, so
    # the guard silently passed the exact edit it exists to catch. Found by
    # trying the bypass, not by reading the regex.
    def test_a_disabled_ktlint_rule_is_caught_hyphens_and_all(self):
        self.write(".editorconfig", "[*.kt]\nktlint_standard_no-wildcard-imports = enabled\n")
        self.commit()
        self.write(".editorconfig", "[*.kt]\nktlint_standard_no-wildcard-imports = enabled\n"
                                    "ktlint_standard_function-naming = disabled\n")
        found = self.findings()
        self.assertEqual(1, len(found), found)
        self.assertIn("function-naming", found[0])

    # --- coverage ---------------------------------------------------------
    def test_a_lowered_coverage_floor_is_caught(self):
        self.write(".prism/verify/thresholds.json",
                   json.dumps({"default": 80, "overrides": {":core:crypto": 100}}))
        self.commit()
        self.write(".prism/verify/thresholds.json",
                   json.dumps({"default": 40, "overrides": {":core:crypto": 60}}))
        found = " ".join(self.findings())
        self.assertIn("default lowered", found)
        self.assertIn("lowered 100 -> 60", found)

    def test_a_removed_override_is_caught(self):
        self.write(".prism/verify/thresholds.json",
                   json.dumps({"default": 80, "overrides": {":core:crypto": 100}}))
        self.commit()
        self.write(".prism/verify/thresholds.json",
                   json.dumps({"default": 80, "overrides": {}}))
        self.assertIn("was removed", " ".join(self.findings()))

    # --- scope ------------------------------------------------------------
    def scope_file(self, default, modules=None):
        self.write(".prism/scope.json",
                   json.dumps({"default": default, "modules": modules or {}}))

    def test_a_demoted_module_is_caught(self):
        self.scope_file({"detekt": "enforce", "ktlint": "enforce",
                         "konsist": "enforce", "coverage": "enforce"},
                        {":feature:x": {"detekt": "enforce"}})
        self.commit()
        self.scope_file({"detekt": "enforce", "ktlint": "enforce",
                         "konsist": "enforce", "coverage": "enforce"},
                        {":feature:x": {"detekt": "observe"}})
        found = " ".join(self.findings())
        self.assertIn(":feature:x", found)
        self.assertIn("enforce -> observe", found)

    def test_a_demoted_default_is_caught(self):
        self.scope_file(dict.fromkeys(guard.scope.ENGINES, "enforce"))
        self.commit()
        self.scope_file({"detekt": "observe", "ktlint": "enforce",
                         "konsist": "enforce", "coverage": "enforce"})
        found = " ".join(self.findings())
        self.assertIn("default.detekt", found)

    # A scope file APPEARING is itself a weakening: the uniform build has none,
    # and adding one can only reduce what blocks.
    def test_a_scope_file_appearing_is_caught(self):
        self.write("detekt.yml", self.DETEKT)
        self.commit()
        self.scope_file({"detekt": "observe"})
        self.assertIn("appeared", " ".join(self.findings()))

    # --- baselines --------------------------------------------------------
    def test_a_grown_baseline_is_caught(self):
        self.write("detekt.baseline.xml",
                   "<SmellBaseline><CurrentIssues><ID>A</ID></CurrentIssues></SmellBaseline>")
        self.commit()
        self.write("detekt.baseline.xml",
                   "<SmellBaseline><CurrentIssues><ID>A</ID><ID>B</ID>"
                   "</CurrentIssues></SmellBaseline>")
        self.assertIn("grew from", " ".join(self.findings()))

    def test_a_grown_detekt_baseline_is_caught_in_any_module(self):
        """0.6.0: one per module, so the guard walks for them.

        Before this the detekt baseline was a single literal path at the root, so
        the only file the guard could watch was that one. A per-module baseline
        that grew was invisible to it.
        """
        self.write("feature/login/detekt.baseline.xml",
                   "<SmellBaseline><CurrentIssues><ID>A</ID></CurrentIssues></SmellBaseline>")
        self.commit()
        self.write("feature/login/detekt.baseline.xml",
                   "<SmellBaseline><CurrentIssues><ID>A</ID><ID>B</ID>"
                   "</CurrentIssues></SmellBaseline>")
        self.assertIn("grew from", " ".join(self.findings()))

    def test_a_detekt_baseline_that_appears_after_the_install_is_caught(self):
        """Recording NEW suppressions is the weakening this exists to catch.

        The install commit itself is exempt -- installed_at_base() returns early
        when the base branch has no .prism/prism.json -- so a baseline appearing
        later is somebody absorbing findings written since.
        """
        self.write("src/Main.kt", "fun main() {}\n")
        self.commit()
        self.write("feature/login/detekt.baseline.xml",
                   "<SmellBaseline><CurrentIssues><ID>A</ID></CurrentIssues></SmellBaseline>")
        self.assertIn("grew from", " ".join(self.findings()))

    def test_a_grown_ktlint_baseline_is_caught_wherever_it_lives(self):
        self.write("feature/login/ktlint.baseline.xml",
                   "<baseline><file name='a'></file></baseline>")
        self.commit()
        self.write("feature/login/ktlint.baseline.xml",
                   "<baseline><file name='a'></file><file name='b'></file></baseline>")
        self.assertIn("grew from", " ".join(self.findings()))

    # --- the half that keeps it usable ------------------------------------
    #
    # A guard that complains about promotion is a guard people turn off.
    def test_every_strengthening_passes_silently(self):
        self.write("detekt.yml", self.DETEKT.replace(
            "  NoNotNullAssertion:\n    active: true",
            "  NoNotNullAssertion:\n    active: false"))
        self.write(".editorconfig", "[*.kt]\nktlint_standard_function-naming = disabled\n")
        self.write(".prism/verify/thresholds.json", json.dumps({"default": 60, "overrides": {}}))
        self.scope_file({"detekt": "observe", "ktlint": "observe",
                         "konsist": "observe", "coverage": "observe"})
        self.write("detekt.baseline.xml",
                   "<SmellBaseline><CurrentIssues><ID>A</ID><ID>B</ID></CurrentIssues></SmellBaseline>")
        self.commit()

        # now strengthen every one of them
        self.write("detekt.yml", self.DETEKT)
        self.write(".editorconfig", "[*.kt]\n")
        self.write(".prism/verify/thresholds.json",
                   json.dumps({"default": 80, "overrides": {":core:crypto": 100}}))
        self.scope_file(dict.fromkeys(guard.scope.ENGINES, "enforce"))
        self.write("detekt.baseline.xml",
                   "<SmellBaseline><CurrentIssues><ID>A</ID></CurrentIssues></SmellBaseline>")
        self.assertEqual([], self.findings())

    def test_an_untouched_tree_is_silent(self):
        self.write("detekt.yml", self.DETEKT)
        self.write(".editorconfig", "[*.kt]\n")
        self.commit()
        self.assertEqual([], self.findings())

    # --- the install is not a weakening ----------------------------------
    # Measured in round 3: a fresh install was refused with exit 2 and seven
    # complaints, every one of them the payload's OWN shipped files -- the
    # scope template, the four ktlint rules PRISM's .editorconfig disables on
    # purpose, and the two baselines the install is instructed to create. The
    # advice it printed ("land it on the base branch first") required the very
    # push it had just denied.

    def install_tree(self):
        """Everything an install lands, none of it on the base branch."""
        self.write("detekt.yml", self.DETEKT)
        self.write(".editorconfig",
                   "[*.kt]\nktlint_standard_filename = disabled\n"
                   "ktlint_standard_no-blank-line-in-list = disabled\n")
        self.scope_file(dict.fromkeys(guard.scope.ENGINES, "observe"))
        self.write(".prism/verify/thresholds.json", json.dumps({"default": 80}))
        self.write("detekt.baseline.xml",
                   "<SmellBaseline><CurrentIssues><ID>A</ID><ID>B</ID>"
                   "</CurrentIssues></SmellBaseline>")

    def test_the_install_itself_is_not_refused(self):
        os.remove(os.path.join(self.root, ".prism", "prism.json"))
        self.write("src/Main.kt", "fun main() {}\n")
        self.commit()                       # base branch: no PRISM at all
        self.install_tree()
        self.write(".prism/prism.json", '{"baseBranch": "main"}\n')
        self.assertEqual([], self.findings())
        self.assertEqual(0, guard.main(["--root", self.root, "--base", "main"]))

    def test_the_skip_is_keyed_on_prism_json_and_nothing_else(self):
        """The negative control for the test above.

        The same weakening must be CAUGHT the moment the base branch carries an
        install. Without this, 'skip when PRISM is absent' could quietly widen
        into 'skip', and every test in this file would still pass.
        """
        self.write("detekt.yml", self.DETEKT)
        self.commit()                       # base branch: prism.json IS present
        self.write("detekt.yml", self.DETEKT.replace(
            "  NoNotNullAssertion:\n    active: true",
            "  NoNotNullAssertion:\n    active: false"))
        found = self.findings()
        self.assertEqual(1, len(found), found)
        self.assertIn("NoNotNullAssertion", found[0])


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Tests for per-module, per-engine posture resolution.

The asymmetry these are built around: this file decides what BLOCKS, so every
way of misreading it must land on the strict side. An absent file means
everything enforces. An unparseable one raises rather than defaulting. An
engine name nobody recognises is refused rather than ignored -- a typo silently
dropped reads in the file as though it were in force, which is the exact shape
of the silent green the framework exists to prevent.
"""

import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))

import scope  # noqa: E402


class ScopeTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="prism-scope-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        os.makedirs(os.path.join(self.tmp, ".prism"))

    def write(self, obj):
        path = os.path.join(self.tmp, ".prism", "scope.json")
        with open(path, "w", encoding="utf-8") as handle:
            if isinstance(obj, str):
                handle.write(obj)
            else:
                json.dump(obj, handle)
        return path

    def settings(self, *modules):
        with open(os.path.join(self.tmp, "settings.gradle.kts"), "w") as handle:
            for module in modules:
                handle.write('include("%s")\n' % module)

    # --- the uniform artifact ---------------------------------------------
    #
    # No file at all. This is not "unconfigured", it is the strict reading,
    # and it is the entire difference between the two builds.
    def test_absent_file_enforces_everything(self):
        default, modules = scope.load(self.tmp)
        self.assertEqual(scope.ENFORCE_ALL, default)
        self.assertEqual({}, modules)
        self.assertEqual("enforce", scope.posture_for(":app", "ktlint", self.tmp))
        self.assertEqual(list(scope.ENGINES), scope.enforcing(":app", self.tmp))

    # --- partial overrides ------------------------------------------------
    def test_module_entry_is_partial(self):
        self.write({"default": {"detekt": "observe", "ktlint": "observe",
                                "konsist": "observe", "coverage": "observe"},
                    "modules": {":feature:login": {"detekt": "enforce"}}})
        self.assertEqual("enforce", scope.posture_for(":feature:login", "detekt", self.tmp))
        self.assertEqual("observe", scope.posture_for(":feature:login", "ktlint", self.tmp))
        self.assertEqual("observe", scope.posture_for(":app", "detekt", self.tmp))
        self.assertEqual(["detekt"], scope.enforcing(":feature:login", self.tmp))

    def test_default_is_partial_too(self):
        # An unnamed engine falls back to enforce, not to observe: the default
        # for the default is the strict one.
        self.write({"default": {"detekt": "observe"}, "modules": {}})
        self.assertEqual("observe", scope.posture_for(":app", "detekt", self.tmp))
        self.assertEqual("enforce", scope.posture_for(":app", "ktlint", self.tmp))

    # --- fail closed ------------------------------------------------------
    def test_unparseable_file_raises_rather_than_defaults(self):
        self.write("{ this is not json")
        with self.assertRaises(scope.ScopeError) as caught:
            scope.load(self.tmp)
        self.assertIn("cannot be read", str(caught.exception))

    def test_unknown_engine_is_refused_not_ignored(self):
        self.write({"default": {}, "modules": {":app": {"detket": "observe"}}})
        with self.assertRaises(scope.ScopeError) as caught:
            scope.load(self.tmp)
        self.assertIn("detket", str(caught.exception))
        self.assertIn("known engines", str(caught.exception))

    def test_unknown_posture_is_refused(self):
        self.write({"default": {"detekt": "warn"}, "modules": {}})
        with self.assertRaises(scope.ScopeError) as caught:
            scope.load(self.tmp)
        self.assertIn("enforce", str(caught.exception))

    def test_module_must_be_a_gradle_path(self):
        self.write({"default": {}, "modules": {"app": {"detekt": "observe"}}})
        with self.assertRaises(scope.ScopeError) as caught:
            scope.load(self.tmp)
        self.assertIn("starting with ':'", str(caught.exception))

    # --- reporting --------------------------------------------------------
    def test_prisms_own_modules_are_not_the_consumers(self):
        self.settings(":app", ":tooling:prism-rules", ":tooling:konsist")
        self.assertEqual([":app"], scope._modules_from_settings(self.tmp))

    def test_matrix_covers_every_engine(self):
        self.write({"default": {"detekt": "observe"}, "modules": {}})
        grid = scope.matrix([":app"], self.tmp)
        self.assertEqual(set(scope.ENGINES), set(grid[":app"]))

    def test_cli_reports_a_dead_module_as_a_note_not_a_failure(self):
        self.settings(":app")
        self.write({"default": {}, "modules": {":gone": {"detekt": "observe"}}})
        self.assertEqual(0, scope.main(["check", "--root", self.tmp]))

    def test_cli_refuses_an_invalid_file(self):
        self.write("{ nope")
        self.assertEqual(2, scope.main(["show", "--root", self.tmp]))


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Tests for placement that refuses to overwrite the consumer's work.

The property under test is what a SECOND run does. Placing a file into an empty
repository is the easy half and a mechanism that only did that would still lose
work -- which is exactly what happened: SETUP.md promised `<file>.prism-new`
since before 0.5.0, nothing implemented it, and re-running setup restored the
all-`observe` scope template over a repository that had spent weeks promoting
modules.

So the assertions below are mostly about NOT writing: theirs survives, ours goes
beside it, a difference is reported rather than resolved, and appending a
fragment twice appends it once.
"""

import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))

import place  # noqa: E402


class PlaceTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="prism-place-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def path(self, name):
        return os.path.join(self.tmp, name)

    def write(self, name, text):
        target = self.path(name)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(text)
        return target

    def read(self, name):
        with open(self.path(name), encoding="utf-8") as handle:
            return handle.read()


class TestSingleFile(PlaceTestCase):
    def test_absent_destination_is_placed(self):
        src = self.write("src/scope.json", '{"default": {}}\n')
        self.assertEqual(place.PLACED, place.place_file(src, self.path("out/scope.json")))
        self.assertEqual('{"default": {}}\n', self.read("out/scope.json"))

    def test_identical_destination_is_unchanged_and_leaves_no_prism_new(self):
        src = self.write("src/a", "same\n")
        self.write("out/a", "same\n")
        self.assertEqual(place.UNCHANGED, place.place_file(src, self.path("out/a")))
        self.assertFalse(os.path.exists(self.path("out/a.prism-new")))

    def test_consumer_edit_survives_and_ours_lands_beside_it(self):
        """THE REGRESSION. This is the promotion that used to be reverted."""
        src = self.write("src/scope.json", '{"default": {"detekt": "observe"}}\n')
        self.write("out/scope.json", '{"default": {"detekt": "enforce"}}\n')
        self.assertEqual(place.KEPT, place.place_file(src, self.path("out/scope.json")))
        self.assertEqual(
            '{"default": {"detekt": "enforce"}}\n',
            self.read("out/scope.json"),
            "the consumer's promotion must survive a re-run",
        )
        self.assertEqual(
            '{"default": {"detekt": "observe"}}\n',
            self.read("out/scope.json.prism-new"),
            "and ours must be readable beside it rather than lost",
        )

    def test_a_same_size_edit_is_still_detected(self):
        """Size and mtime agreeing is not the bytes agreeing.

        A shallow compare would call this UNCHANGED and overwrite it, and an edit
        that preserves the length is the likeliest kind in a config file: one
        posture word swapped for another of equal length.
        """
        src = self.write("src/a", "observe\n")
        self.write("out/a", "enforce\n")
        self.assertEqual(place.KEPT, place.place_file(src, self.path("out/a")))
        self.assertEqual("enforce\n", self.read("out/a"))

    def test_a_directory_where_a_file_belongs_is_refused(self):
        src = self.write("src/a", "x\n")
        os.makedirs(self.path("out/a"))
        with self.assertRaises(place.PlaceError):
            place.place_file(src, self.path("out/a"))

    def test_a_missing_source_is_an_operation_failure(self):
        with self.assertRaises(place.PlaceError):
            place.place_file(self.path("src/nope"), self.path("out/nope"))


class TestTree(PlaceTestCase):
    def test_every_file_is_reported_and_only_edited_ones_are_kept(self):
        self.write("src/verify/one", "1\n")
        self.write("src/verify/lib/two", "2\n")
        self.write("src/verify/lib/three", "3\n")
        place.place_tree(self.path("src/verify"), self.path("out/verify"))

        self.write("out/verify/lib/two", "EDITED BY THEM\n")
        results = dict(place.place_tree(self.path("src/verify"), self.path("out/verify")))

        self.assertEqual(place.UNCHANGED, results["one"])
        self.assertEqual(place.UNCHANGED, results[os.path.join("lib", "three")])
        self.assertEqual(place.KEPT, results[os.path.join("lib", "two")])
        self.assertEqual("EDITED BY THEM\n", self.read("out/verify/lib/two"))
        self.assertEqual("2\n", self.read("out/verify/lib/two.prism-new"))

    def test_a_file_the_consumer_added_is_not_deleted(self):
        """This is a placement, not a sync. Their extra file is theirs."""
        self.write("src/verify/one", "1\n")
        self.write("out/verify/theirs.kt", "class Theirs\n")
        place.place_tree(self.path("src/verify"), self.path("out/verify"))
        self.assertTrue(os.path.exists(self.path("out/verify/theirs.kt")))


class TestAppendAbsentLines(PlaceTestCase):
    FRAGMENT = "# prism\n**/build/\n.prism/verify/state/\n"

    def test_running_it_twice_appends_once(self):
        src = self.write("src/gitignore.fragment", self.FRAGMENT)
        self.write("out/.gitignore", "/local.properties\n")

        outcome, count = place.append_absent_lines(src, self.path("out/.gitignore"))
        self.assertEqual((place.PLACED, 3), (outcome, count))

        outcome, count = place.append_absent_lines(src, self.path("out/.gitignore"))
        self.assertEqual(
            (place.UNCHANGED, 0), (outcome, count),
            "a second setup run must add nothing to .gitignore")
        self.assertEqual(1, self.read("out/.gitignore").count("**/build/"))

    def test_only_the_absent_lines_are_appended(self):
        src = self.write("src/gitignore.fragment", self.FRAGMENT)
        self.write("out/.gitignore", "**/build/\n")
        outcome, count = place.append_absent_lines(src, self.path("out/.gitignore"))
        self.assertEqual((place.PLACED, 2), (outcome, count))
        self.assertEqual(1, self.read("out/.gitignore").count("**/build/"))

    def test_a_destination_without_a_trailing_newline_does_not_join_lines(self):
        src = self.write("src/gitignore.fragment", "**/build/\n")
        self.write("out/.gitignore", "/local.properties")
        place.append_absent_lines(src, self.path("out/.gitignore"))
        self.assertEqual("/local.properties\n**/build/\n", self.read("out/.gitignore"))

    def test_an_absent_destination_is_created(self):
        src = self.write("src/gitignore.fragment", "**/build/\n")
        outcome, count = place.append_absent_lines(src, self.path("out/.gitignore"))
        self.assertEqual((place.PLACED, 1), (outcome, count))
        self.assertEqual("**/build/\n", self.read("out/.gitignore"))


class TestExitStatus(PlaceTestCase):
    """A difference is reported, never fatal.

    If KEPT exited non-zero, the agent's next move would be the one recovery
    that loses the work: delete theirs and run setup again.
    """

    def test_kept_still_exits_zero(self):
        src = self.write("src/a", "ours\n")
        self.write("out/a", "theirs\n")
        self.assertEqual(0, place.main(
            ["file", "--src", src, "--dest", self.path("out/a")]))

    def test_a_real_failure_exits_one(self):
        self.assertEqual(1, place.main(
            ["file", "--src", self.path("src/nope"), "--dest", self.path("out/nope")]))


if __name__ == "__main__":
    unittest.main(verbosity=0)

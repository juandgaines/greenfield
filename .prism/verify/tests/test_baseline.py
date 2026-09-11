#!/usr/bin/env python3
"""The baseline decides what day one looks like, so its edges are load-bearing.

Two failures would each be silent in exactly the repositories where nobody
would look. `collect` losing a module's fragment leaves those findings
un-suppressed and the build red for a reason nobody chose; `collect`
overwriting an existing baseline absorbs every violation written since the
install, permanently, with nothing reporting that it happened.

The cases below are those two and the smaller ones around them.
"""

import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))

import baseline  # noqa: E402

FRAGMENT_DIR = os.path.join("build", "prism", "baseline")


def fragment(root, module, task, ids, suppressed=()):
    directory = os.path.join(root, module, FRAGMENT_DIR)
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, task + ".xml")
    with open(path, "w") as out:
        out.write('<?xml version="1.0" ?>\n<SmellBaseline>\n')
        out.write("  <ManuallySuppressedIssues>\n")
        for i in suppressed:
            out.write("    <ID>%s</ID>\n" % i)
        out.write("  </ManuallySuppressedIssues>\n  <CurrentIssues>\n")
        for i in ids:
            out.write("    <ID>%s</ID>\n" % i)
        out.write("  </CurrentIssues>\n</SmellBaseline>\n")
    return path


class BaselineTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.out = os.path.join(self.root, "detekt.baseline.xml")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def collect(self, *extra):
        return baseline.main(["collect", "--root", self.root, "--out", self.out] + list(extra))

    # --- the union -------------------------------------------------------
    #
    # The whole reason this file exists. detekt's own baseline task REPLACES
    # currentIssues, so if collect took the last fragment rather than the union,
    # fourteen of fifteen modules would be silently un-baselined.
    def test_collect_unions_every_fragment(self):
        fragment(self.root, "app", "detektBaseline", ["RuleA:app.kt$x"])
        fragment(self.root, "app", "detektBaselineDebug", ["RuleB:app.kt$y"])
        fragment(self.root, "core", "detektBaseline", ["RuleA:core.kt$z"])
        self.assertEqual(baseline.EXIT_OK, self.collect())

        current, suppressed = baseline.read(self.out)
        self.assertEqual(
            ["RuleA:app.kt$x", "RuleA:core.kt$z", "RuleB:app.kt$y"], sorted(current)
        )
        self.assertEqual([], suppressed)

    def test_collect_deduplicates(self):
        fragment(self.root, "app", "detektBaseline", ["RuleA:app.kt$x"])
        fragment(self.root, "app", "detektBaselineDebug", ["RuleA:app.kt$x"])
        self.collect()
        current, _ = baseline.read(self.out)
        self.assertEqual(["RuleA:app.kt$x"], current)

    def test_collect_preserves_manual_suppressions(self):
        fragment(self.root, "app", "detektBaseline", ["RuleA:a"], suppressed=["RuleZ:kept"])
        self.collect()
        _, suppressed = baseline.read(self.out)
        self.assertEqual(["RuleZ:kept"], suppressed)

    # --- the refusal -----------------------------------------------------
    #
    # Rerunning collect over a live baseline is how a team silently absorbs
    # every finding written since the install. It has to ask.
    def test_collect_refuses_over_an_existing_baseline(self):
        fragment(self.root, "app", "detektBaseline", ["RuleA:a"])
        self.collect()
        fragment(self.root, "app", "detektBaseline", ["RuleA:a", "RuleNew:written_since"])
        self.assertEqual(baseline.EXIT_ERROR, self.collect())

        current, _ = baseline.read(self.out)
        self.assertNotIn("RuleNew:written_since", current)

    def test_force_overwrites_deliberately(self):
        fragment(self.root, "app", "detektBaseline", ["RuleA:a"])
        self.collect()
        fragment(self.root, "app", "detektBaseline", ["RuleNew:written_since"])
        self.assertEqual(baseline.EXIT_OK, self.collect("--force"))
        current, _ = baseline.read(self.out)
        self.assertEqual(["RuleNew:written_since"], current)

    def test_collect_with_no_fragments_fails_loudly(self):
        self.assertEqual(baseline.EXIT_ERROR, self.collect())
        self.assertFalse(os.path.exists(self.out))

    # --- reading ---------------------------------------------------------
    def test_absent_baseline_is_its_own_exit_code(self):
        # Absent is not an error and not zero findings: the caller has to be
        # able to tell "nothing is suppressed" from "there is no such file".
        self.assertEqual(baseline.EXIT_ABSENT, baseline.main(["count", "--path", self.out]))

    def test_unparseable_baseline_is_an_error_not_an_empty_one(self):
        # detekt fails every module's analysis on an invalid baseline. Reporting
        # zero findings here would contradict the build until somebody looked.
        with open(self.out, "w") as handle:
            handle.write("<SmellBaseline><CurrentIssues>")
        self.assertEqual(baseline.EXIT_ERROR, baseline.main(["count", "--path", self.out]))

    def test_group_counts_by_rule(self):
        fragment(self.root, "app", "detektBaseline", ["RuleA:1", "RuleA:2", "RuleB:3"])
        self.collect()
        self.assertEqual(baseline.EXIT_OK, baseline.main(["group", "--path", self.out]))

    # --- subtraction, the only direction that edits -----------------------
    def test_drop_removes_one_rule_and_keeps_the_rest(self):
        fragment(self.root, "app", "detektBaseline", ["RuleA:1", "RuleA:2", "RuleB:3"])
        self.collect()
        self.assertEqual(
            baseline.EXIT_OK, baseline.main(["drop", "--rule", "RuleA", "--path", self.out])
        )
        current, _ = baseline.read(self.out)
        self.assertEqual(["RuleB:3"], current)

    def test_drop_keeps_manual_suppressions(self):
        fragment(self.root, "app", "detektBaseline", ["RuleA:1"], suppressed=["RuleZ:kept"])
        self.collect()
        baseline.main(["drop", "--rule", "RuleA", "--path", self.out])
        _, suppressed = baseline.read(self.out)
        self.assertEqual(["RuleZ:kept"], suppressed)

    def test_drop_of_an_unrecorded_rule_fails_rather_than_no_ops(self):
        # A silent success here reads as "that rule is now enforced" when
        # nothing changed and the name was simply wrong.
        fragment(self.root, "app", "detektBaseline", ["RuleA:1"])
        self.collect()
        self.assertEqual(
            baseline.EXIT_ERROR, baseline.main(["drop", "--rule", "Typo", "--path", self.out])
        )

    def test_rule_id_splits_on_the_first_colon_only(self):
        # Signatures contain colons. Splitting on the last one, or on all of
        # them, invents rule names that match nothing.
        self.assertEqual("RuleA", baseline.rule_of("RuleA:File.kt$fun foo(x: Int)"))
        self.assertEqual("File.kt$fun foo(x: Int)", baseline.signature_of("RuleA:File.kt$fun foo(x: Int)"))


class DropModuleTest(unittest.TestCase):
    """`drop --module` is what `./prism promote :module` runs.

    IT HAS BEEN WRONG TWICE, in opposite directions, and both were silent.

    First it filtered baseline ids by a `feature/login/` path prefix, believing
    detekt embeds the source path. detekt embeds the BASENAME, so the filter
    matched nothing in every repository, retired zero entries, and reported "no
    baselined findings in :app" -- which reads as "this module is clean".
    Measured on a real install: 330 entries before, 330 after.

    Then it attributed by basename against the modules holding a file of that
    name, and correctly REFUSED when two modules shared one. That refusal was
    right and it was still a false green: the entries it kept were suppressing
    six real findings in the module being promoted, and `staticAnalysis` would
    have reported that module clean.

    0.6.0 removes the question. One baseline per module means dropping a
    module's suppressions is a file operation, so the cases below assert on
    FILES rather than on inference.
    """

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)

    def module(self, path, *files):
        directory = os.path.join(self.root, *path.strip(":").split(":"))
        source = os.path.join(directory, "src", "main", "kotlin")
        os.makedirs(source, exist_ok=True)
        open(os.path.join(directory, "build.gradle.kts"), "w").close()
        for name in files:
            open(os.path.join(source, name), "w").close()
        return directory

    def baseline_in(self, path, *ids, **kwargs):
        directory = os.path.join(self.root, *path.strip(":").split(":"))
        os.makedirs(directory, exist_ok=True)
        target = os.path.join(directory, baseline.BASELINE_NAME)
        baseline.write(target, list(ids), list(kwargs.get("manual", ())))
        return target

    def drop(self, module):
        return baseline.main(["drop", "--module", module, "--root", self.root])

    def current(self, path):
        target = os.path.join(self.root, *path.strip(":").split(":"),
                              baseline.BASELINE_NAME)
        if not os.path.exists(target):
            return None
        return sorted(baseline.read(target)[0])

    def test_a_modules_suppressions_are_dropped_with_its_file(self):
        self.module(":app", "MainActivity.kt")
        self.module(":core:data", "Repo.kt")
        self.baseline_in(":app", "NoConsoleLogging:MainActivity.kt:MainActivity$onCreate")
        self.baseline_in(":core:data", "LongMethod:Repo.kt:Repo$fetch")
        self.assertEqual(baseline.EXIT_OK, self.drop(":app"))
        self.assertIsNone(self.current(":app"),
                          "a module that suppresses nothing keeps no baseline")
        self.assertEqual(["LongMethod:Repo.kt:Repo$fetch"], self.current(":core:data"),
                         "and no other module is touched")

    def test_a_sibling_module_is_not_taken_along(self):
        """`:core` must not swallow `:core:data` -- the old prefix bug, inverted."""
        self.module(":core", "Base.kt")
        self.module(":core:data", "Repo.kt")
        self.baseline_in(":core", "LongMethod:Base.kt:Base$a")
        self.baseline_in(":core:data", "LongMethod:Repo.kt:Repo$b")
        self.assertEqual(baseline.EXIT_OK, self.drop(":core"))
        self.assertEqual(["LongMethod:Repo.kt:Repo$b"], self.current(":core:data"))

    def test_a_shared_basename_is_no_longer_a_question(self):
        """THE REGRESSION, from the other side.

        Two modules, one `Theme.kt`, so detekt writes the SAME id for both --
        `MagicNumber:Theme.kt:Theme$colors`. With one baseline at the root that
        was a single entry and dropping it for either module was a guess. With a
        file per module each one holds its own copy, and promoting one leaves
        the other exactly as it was.
        """
        self.module(":feature:login", "Theme.kt")
        self.module(":feature:home", "Theme.kt")
        self.baseline_in(":feature:login", "MagicNumber:Theme.kt:Theme$colors")
        self.baseline_in(":feature:home", "MagicNumber:Theme.kt:Theme$colors")
        self.assertEqual(baseline.EXIT_OK, self.drop(":feature:login"))
        self.assertIsNone(self.current(":feature:login"),
                          "the promoted module now suppresses nothing")
        self.assertEqual(["MagicNumber:Theme.kt:Theme$colors"],
                         self.current(":feature:home"),
                         "and the identical entry in the sibling survives")

    def test_a_module_with_nothing_suppressed_says_so_rather_than_failing(self):
        """It must never read as 'clean', and it must not read as an error either.

        `./prism promote` runs this before it flips the posture. A module with no
        baseline is the ordinary case on a repository installed as `enforce`, and
        a non-zero exit there would stop a promotion that has nothing wrong with it.
        """
        self.module(":app", "MainActivity.kt")
        self.baseline_in(":core:data", "LongMethod:Repo.kt:Repo$fetch")
        self.assertEqual(baseline.EXIT_OK, self.drop(":app"))
        self.assertEqual(["LongMethod:Repo.kt:Repo$fetch"], self.current(":core:data"))

    def test_a_module_that_is_not_in_the_tree_is_an_error(self):
        self.module(":app", "MainActivity.kt")
        self.assertEqual(baseline.EXIT_ERROR, self.drop(":nope"))

    def test_a_bare_module_name_is_accepted(self):
        self.module(":app", "MainActivity.kt")
        self.baseline_in(":app", "NoConsoleLogging:MainActivity.kt:MainActivity$onCreate")
        self.assertEqual(baseline.EXIT_OK, self.drop("app"))
        self.assertIsNone(self.current(":app"))

    def test_hand_written_suppressions_go_with_the_module(self):
        """<ManuallySuppressedIssues> is still a suppression.

        Promoting a module means it suppresses nothing; leaving the hand-written
        block behind would leave the module blocking on everything EXCEPT the
        entries somebody added by hand, which is the state hardest to notice.
        """
        self.module(":app", "MainActivity.kt")
        self.baseline_in(":app", "LongMethod:MainActivity.kt:MainActivity$a",
                         manual=("MagicNumber:MainActivity.kt:MainActivity$b",))
        self.assertEqual(baseline.EXIT_OK, self.drop(":app"))
        self.assertIsNone(self.current(":app"))

    def test_file_of_reads_the_basename_detekt_actually_writes(self):
        self.assertEqual(
            "AgendaBindsModule.kt",
            baseline.file_of(
                "AbstractClassCanBeInterface:AgendaBindsModule.kt:"
                "AgendaBindsModule$AgendaBindsModule"))


class PerModuleTest(unittest.TestCase):
    """One baseline per module -- the shape 0.6.0 changed, asserted directly."""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)

    def collect(self, *extra):
        return baseline.main(["collect", "--root", self.root] + list(extra))

    def path(self, module):
        return os.path.join(self.root, *module.strip(":").split(":"),
                            baseline.BASELINE_NAME)

    def test_collect_writes_one_baseline_beside_each_module(self):
        fragment(self.root, os.path.join("run", "domain"), "detektBaselineMain",
                 ["MagicNumber:RunningTracker.kt:RunningTracker$1"])
        fragment(self.root, os.path.join("core", "data"), "detektBaselineMain",
                 ["LongMethod:Repo.kt:Repo$fetch"])
        self.assertEqual(baseline.EXIT_OK, self.collect())
        self.assertTrue(os.path.isfile(self.path(":run:domain")))
        self.assertTrue(os.path.isfile(self.path(":core:data")))
        self.assertFalse(os.path.exists(os.path.join(self.root, baseline.BASELINE_NAME)),
                         "and nothing at the root, which is what used to collide")

    def test_two_modules_sharing_a_filename_get_an_entry_each(self):
        """THE CRITICAL ONE. Measured: 193 ids, 36 file names appearing twice.

        detekt writes the same id for a same-named file in two modules, and the
        union deduplicates, so one root file recorded TWO findings as ONE entry.
        """
        same = "MagicNumber:RunningTracker.kt:RunningTracker$1"
        fragment(self.root, os.path.join("run", "domain"), "detektBaselineMain", [same])
        fragment(self.root, os.path.join("wear", "run", "domain"),
                 "detektBaselineMain", [same])
        self.assertEqual(baseline.EXIT_OK, self.collect())
        self.assertEqual([same], sorted(baseline.read(self.path(":run:domain"))[0]))
        self.assertEqual([same], sorted(baseline.read(self.path(":wear:run:domain"))[0]))

    def test_count_is_the_whole_tree(self):
        fragment(self.root, "app", "detektBaselineDebug", ["RuleA:a.kt$x"])
        fragment(self.root, os.path.join("core", "data"), "detektBaselineMain",
                 ["RuleB:b.kt$y", "RuleC:c.kt$z"])
        self.collect()
        self.assertEqual(baseline.EXIT_OK,
                         baseline.main(["count", "--root", self.root]))

    def test_collect_refuses_when_any_module_already_has_one(self):
        """All or nothing: half this run and half an older one is unreadable."""
        fragment(self.root, "app", "detektBaselineDebug", ["RuleA:a.kt$x"])
        fragment(self.root, os.path.join("core", "data"), "detektBaselineMain",
                 ["RuleB:b.kt$y"])
        self.assertEqual(baseline.EXIT_OK, self.collect())
        os.remove(self.path(":app"))
        self.assertEqual(baseline.EXIT_ERROR, self.collect(),
                         "core:data still has one, so nothing is rewritten")
        self.assertFalse(os.path.exists(self.path(":app")))

    def test_a_renamed_file_leaves_an_entry_that_matches_nothing(self):
        """M2. Renaming detaches the entry, and `count` is where that shows."""
        module = os.path.join(self.root, "core", "data", "src", "main")
        os.makedirs(module)
        open(os.path.join(module, "Kept.kt"), "w").close()
        entries = ["RuleA:Kept.kt:Kept$x", "RuleA:Renamed.kt:Renamed$y"]
        baseline.write(self.path(":core:data"), entries, [])
        self.assertEqual(
            ["Renamed.kt"],
            baseline.stale_entries(self.path(":core:data"), entries))

    def test_an_id_this_cannot_read_is_never_called_stale(self):
        """A false positive sends somebody hunting for a rename that never was."""
        module = os.path.join(self.root, "core", "data", "src", "main")
        os.makedirs(module)
        entries = ["RuleA:something-that-is-not-a-file"]
        baseline.write(self.path(":core:data"), entries, [])
        self.assertEqual(
            [], baseline.stale_entries(self.path(":core:data"), entries))


if __name__ == "__main__":
    unittest.main()

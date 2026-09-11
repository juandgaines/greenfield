#!/usr/bin/env python3
"""Tests for lib/deps.py — the reverse module dependency closure."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))
import deps  # noqa: E402


def _write(root, relative, text):
    path = os.path.join(root, relative)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


class AccessorTests(unittest.TestCase):
    def test_plain_segment_is_unchanged(self):
        self.assertEqual(deps._accessor_for(":core:domain"), "core.domain")

    def test_kebab_case_becomes_camel_case(self):
        self.assertEqual(deps._accessor_for(":core:design-system"), "core.designSystem")
        self.assertEqual(deps._accessor_for(":core:files-db"), "core.filesDb")

    def test_nested_module(self):
        self.assertEqual(
            deps._accessor_for(":feature:auth:presentation"),
            "feature.auth.presentation")

    def test_single_segment(self):
        self.assertEqual(deps._accessor_for(":app"), "app")


class GraphTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        _write(self.root, "settings.gradle.kts",
               'include(":app")\n'
               'include(":core:domain")\n'
               'include(":core:design-system")\n'
               'include(":feature:auth:presentation")\n')
        _write(self.root, "core/domain/build.gradle.kts", "plugins { }\n")
        _write(self.root, "core/design-system/build.gradle.kts",
               "dependencies { implementation(projects.core.domain) }\n")
        _write(self.root, "feature/auth/presentation/build.gradle.kts",
               "dependencies {\n"
               "    implementation(projects.core.domain)\n"
               "    implementation(projects.core.designSystem)\n"
               "}\n")
        _write(self.root, "app/build.gradle.kts",
               "dependencies { implementation(projects.feature.auth.presentation) }\n")

    def tearDown(self):
        self.tmp.cleanup()

    def test_all_modules_reads_settings(self):
        self.assertEqual(
            deps.all_modules(self.root),
            [":app", ":core:domain", ":core:design-system",
             ":feature:auth:presentation"])

    def test_kebab_accessor_resolves_to_its_module(self):
        graph = deps.dependency_graph(self.root)
        self.assertIn(":core:design-system", graph[":feature:auth:presentation"])

    def test_closure_is_transitive(self):
        # :app reaches :core:domain only through :feature:auth:presentation.
        self.assertEqual(
            deps.with_dependents([":core:domain"], self.root),
            [":app", ":core:design-system", ":core:domain",
             ":feature:auth:presentation"])

    def test_leaf_has_no_dependents(self):
        self.assertEqual(deps.with_dependents([":app"], self.root), [":app"])

    def test_seed_is_always_included(self):
        self.assertIn(":core:design-system",
                      deps.with_dependents([":core:design-system"], self.root))

    def test_unknown_module_passes_through(self):
        self.assertEqual(
            deps.with_dependents([":no:such:module"], self.root),
            [":no:such:module"])

    def test_a_module_never_depends_on_itself(self):
        graph = deps.dependency_graph(self.root)
        for module, dependencies in graph.items():
            self.assertNotIn(module, dependencies)

    def test_missing_build_file_is_not_fatal(self):
        _write(self.root, "settings.gradle.kts",
               'include(":core:domain")\ninclude(":ghost")\n')
        graph = deps.dependency_graph(self.root)
        self.assertEqual(graph[":ghost"], set())

    def test_unreadable_settings_yields_no_modules(self):
        with tempfile.TemporaryDirectory() as empty:
            self.assertEqual(deps.all_modules(empty), [])


class TestSourceSetEdgeTests(unittest.TestCase):
    """androidTest-only edges are real: a module whose tests no longer
    compile is not a module that passed gate 2."""

    def test_android_test_implementation_counts(self):
        with tempfile.TemporaryDirectory() as root:
            _write(root, "settings.gradle.kts",
                   'include(":core:files-db")\ninclude(":feature:files:data")\n')
            _write(root, "core/files-db/build.gradle.kts", "plugins { }\n")
            _write(root, "feature/files/data/build.gradle.kts",
                   "dependencies { androidTestImplementation(projects.core.filesDb) }\n")
            self.assertEqual(
                deps.with_dependents([":core:files-db"], root),
                [":core:files-db", ":feature:files:data"])


if __name__ == "__main__":
    unittest.main(verbosity=0)

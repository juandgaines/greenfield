"""Tests for lib/coverage.py. Run: python3 tests/test_coverage.py"""
import io
import json
import re
import os
import sys
import shutil
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
import coverage  # noqa: E402

CONFIG = {"default": 80, "overrides": {":core:crypto": 100, ":core:design-system": 0}}

JACOCO_XML = """<?xml version="1.0" encoding="UTF-8"?>
<report name="m">
  <counter type="INSTRUCTION" missed="10" covered="90"/>
  <counter type="LINE" missed="25" covered="75"/>
  <counter type="BRANCH" missed="5" covered="5"/>
</report>
"""

JACOCO_XML_EMPTY = """<?xml version="1.0" encoding="UTF-8"?>
<report name="m">
  <counter type="LINE" missed="0" covered="0"/>
</report>
"""


class ThresholdTests(unittest.TestCase):
    def test_default_applies_when_no_override(self):
        self.assertEqual(80, coverage.threshold_for(":feature:files:data", CONFIG))

    def test_override_wins(self):
        self.assertEqual(100, coverage.threshold_for(":core:crypto", CONFIG))

    def test_zero_override_is_honoured_not_treated_as_missing(self):
        self.assertEqual(0, coverage.threshold_for(":core:design-system", CONFIG))

    def test_goals_block_does_not_affect_threshold(self):
        config = {"default": 80, "goals": {":core:crypto": 100},
                  "overrides": {":core:crypto": 96}}
        self.assertEqual(96, coverage.threshold_for(":core:crypto", config))

    def test_unlisted_module_gets_default(self):
        config = {"default": 80, "overrides": {":core:crypto": 96}}
        self.assertEqual(80, coverage.threshold_for(":feature:new", config))


class ThresholdNormalisationTests(unittest.TestCase):
    """A floor is a decision. A measurement is not, and it got pasted in as one.

    `thresholds.json` tells you to set a starting floor to "a number you have
    already measured", so somebody set one to 98.30508474576271. Seventeen
    significant figures then went through every report that prints the required
    column -- eight characters wide -- and the table stopped lining up.
    """

    def threshold(self, value):
        return coverage.threshold_for(":m", {"default": value})

    def test_a_pasted_measurement_becomes_one_decimal_place(self):
        self.assertEqual(98.3, self.threshold(98.30508474576271))

    def test_it_only_ever_rounds_DOWN(self):
        """Rounding up would deny the very push that measured the number."""
        self.assertEqual(98.3, self.threshold(98.39999))
        self.assertEqual(79.9, self.threshold(79.999))

    def test_an_integral_floor_stays_an_int(self):
        """So it renders as 80, not 80.0, in a column people read."""
        self.assertEqual(80, self.threshold(80))
        self.assertIsInstance(self.threshold(80.0), int)
        self.assertEqual(100, self.threshold(100.0))

    def test_a_deliberate_half_point_survives(self):
        self.assertEqual(99.5, self.threshold(99.5))

    def test_an_override_is_normalised_too(self):
        self.assertEqual(
            91.2,
            coverage.threshold_for(":m", {"default": 80,
                                          "overrides": {":m": 91.23456}}))

    def test_a_value_that_is_not_a_number_is_left_alone(self):
        """The reporting path substitutes '?' and must still be able to."""
        self.assertEqual("?", self.threshold("?"))


class LineCoverageTests(unittest.TestCase):
    def _write(self, body):
        handle = tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False)
        handle.write(body)
        handle.close()
        return handle.name

    def test_reads_line_counter(self):
        self.assertAlmostEqual(75.0, coverage.line_coverage(self._write(JACOCO_XML)))

    def test_no_lines_is_full_coverage_not_a_crash(self):
        self.assertEqual(100.0, coverage.line_coverage(self._write(JACOCO_XML_EMPTY)))

    def test_missing_file_raises(self):
        with self.assertRaises(FileNotFoundError):
            coverage.line_coverage("/nope/coverage.xml")


class ReportKindTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()

    def _module(self, name, jacoco, unit, instrumentation):
        base = os.path.join(self.root, name)
        os.makedirs(base, exist_ok=True)
        with open(os.path.join(base, "build.gradle.kts"), "w") as handle:
            handle.write('id("prism.jacoco")\n' if jacoco else 'id("acme.android.library")\n')
        if unit:
            os.makedirs(os.path.join(base, "src", "test"), exist_ok=True)
        if instrumentation:
            os.makedirs(os.path.join(base, "src", "androidTest"), exist_ok=True)
        return name

    def test_both_suites_is_combined(self):
        name = self._module("both", True, True, True)
        self.assertEqual("combined", coverage.report_kind(name, self.root))

    def test_unit_only_is_jvm(self):
        name = self._module("unitonly", True, True, False)
        self.assertEqual("jvm", coverage.report_kind(name, self.root))

    def test_instrumentation_only(self):
        name = self._module("instonly", True, False, True)
        self.assertEqual("instrumentation", coverage.report_kind(name, self.root))

    def test_no_suites_is_none(self):
        name = self._module("empty", True, False, False)
        self.assertEqual("none", coverage.report_kind(name, self.root))

    def test_jacoco_detection(self):
        yes = self._module("withjacoco", True, True, True)
        no = self._module("nojacoco", False, True, True)
        self.assertTrue(coverage.has_jacoco(yes, self.root))
        self.assertFalse(coverage.has_jacoco(no, self.root))

    def test_has_sources_true_when_src_main_has_kotlin(self):
        name = self._module("withmain", True, False, False)
        base = os.path.join(self.root, name, "src", "main", "java")
        os.makedirs(base, exist_ok=True)
        with open(os.path.join(base, "Thing.kt"), "w") as handle:
            handle.write("class Thing\n")
        self.assertTrue(coverage.has_sources(name, self.root))

    def test_has_sources_false_when_no_src_main(self):
        name = self._module("nomain", True, False, False)
        self.assertFalse(coverage.has_sources(name, self.root))


class CLIErrorHandlingTests(unittest.TestCase):
    def _run_assert_cli(self, module, xml_path, config_path):
        """Run assert CLI and return (exit_code, stdout)."""
        old_argv = sys.argv
        old_stdout = sys.stdout
        try:
            sys.argv = ["coverage.py", "assert", "--module", module, "--xml", xml_path, "--config", config_path]
            sys.stdout = io.StringIO()
            exit_code = coverage.main()
            output = sys.stdout.getvalue()
            return exit_code, output
        finally:
            sys.argv = old_argv
            sys.stdout = old_stdout

    def _write(self, body):
        handle = tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False)
        handle.write(body)
        handle.close()
        return handle.name

    def test_missing_xml_prints_error_and_exits_1(self):
        config_file = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        json.dump({"default": 80, "overrides": {}}, config_file)
        config_file.close()

        exit_code, output = self._run_assert_cli(":test:module", "/nope/coverage.xml", config_file.name)

        self.assertEqual(1, exit_code)
        self.assertTrue(output.startswith("ERROR"), f"Expected output to start with 'ERROR', got: {output}")

    def test_malformed_xml_prints_error_and_exits_1(self):
        config_file = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        json.dump({"default": 80, "overrides": {}}, config_file)
        config_file.close()

        xml_file = tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False)
        xml_file.write("not xml at all")
        xml_file.close()

        exit_code, output = self._run_assert_cli(":test:module", xml_file.name, config_file.name)

        self.assertEqual(1, exit_code)
        self.assertTrue(output.startswith("ERROR"), f"Expected output to start with 'ERROR', got: {output}")

    def test_zero_total_line_data_prints_error_and_exits_1(self):
        """An empty/misconfigured JaCoCo report must not read as 100%."""
        config_file = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        json.dump({"default": 80, "overrides": {}}, config_file)
        config_file.close()

        exit_code, output = self._run_assert_cli(
            ":test:module", self._write(JACOCO_XML_EMPTY), config_file.name)

        self.assertEqual(1, exit_code)
        self.assertTrue(output.startswith("ERROR"), f"Expected output to start with 'ERROR', got: {output}")
        self.assertIn("no line data", output)

    def test_missing_line_counter_prints_error_and_exits_1(self):
        """A report with no LINE counter at all is also an error, not 100%."""
        config_file = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        json.dump({"default": 80, "overrides": {}}, config_file)
        config_file.close()

        no_line_counter_xml = """<?xml version="1.0" encoding="UTF-8"?>
<report name="m">
  <counter type="INSTRUCTION" missed="0" covered="0"/>
</report>
"""
        exit_code, output = self._run_assert_cli(
            ":test:module", self._write(no_line_counter_xml), config_file.name)

        self.assertEqual(1, exit_code)
        self.assertTrue(output.startswith("ERROR"), f"Expected output to start with 'ERROR', got: {output}")

    def test_string_threshold_prints_error_and_exits_1(self):
        config_file = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        json.dump({"default": 80, "overrides": {":test:module": "80"}}, config_file)
        config_file.close()

        xml_file = tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False)
        xml_file.write("""<?xml version="1.0" encoding="UTF-8"?>
<report name="m">
  <counter type="LINE" missed="20" covered="80"/>
</report>
""")
        xml_file.close()

        exit_code, output = self._run_assert_cli(":test:module", xml_file.name, config_file.name)

        self.assertEqual(1, exit_code)
        self.assertTrue(output.startswith("ERROR"), f"Expected output to start with 'ERROR', got: {output}")


class InheritedJacocoTests(unittest.TestCase):
    """Coverage configuration is applied by the convention plugins.

    No module declares `id("prism.jacoco")` any more, so a detector that only
    grepped the module's own build file would report every module in the
    project as unconfigured — which is the single failure that would make the
    push gate deny every push again. These assertions pin the inheritance
    resolution that replaced it, including the transitive hop through
    `acme.android.library.compose`.
    """

    def setUp(self):
        self.root = tempfile.mkdtemp()
        kotlin_dir = os.path.join(self.root, "build-logic", "src", "main", "kotlin")
        os.makedirs(kotlin_dir)
        self._register([
            ("prism.jacoco", "JacocoConventionPlugin"),
            ("acme.android.library", "AndroidLibraryConventionPlugin"),
            ("acme.android.library.compose", "AndroidLibraryComposeConventionPlugin"),
            ("acme.kotlin.ktor", "KotlinKtorConventionPlugin"),
        ])
        self._plugin_source("JacocoConventionPlugin", ['pluginManager.apply("jacoco")'])
        self._plugin_source("AndroidLibraryConventionPlugin", [
            'pluginManager.apply("com.android.library")',
            'pluginManager.apply("prism.jacoco")',
        ])
        self._plugin_source("AndroidLibraryComposeConventionPlugin", [
            'apply("acme.android.library")',
        ])
        self._plugin_source("KotlinKtorConventionPlugin", ['apply("io.ktor.plugin")'])

    def _register(self, pairs):
        lines = ["gradlePlugin {", "    plugins {"]
        for index, (plugin_id, class_name) in enumerate(pairs):
            lines += [
                '        register("p%d") {' % index,
                '            id = "%s"' % plugin_id,
                '            implementationClass = "%s"' % class_name,
                "        }",
            ]
        lines += ["    }", "}"]
        path = os.path.join(self.root, "build-logic", "build.gradle.kts")
        with open(path, "w") as handle:
            handle.write("\n".join(lines) + "\n")

    def _plugin_source(self, class_name, body_lines):
        path = os.path.join(
            self.root, "build-logic", "src", "main", "kotlin", class_name + ".kt"
        )
        with open(path, "w") as handle:
            handle.write("class %s {\n" % class_name)
            for line in body_lines:
                handle.write("    " + line + "\n")
            handle.write("}\n")

    def _module(self, name, plugin_ids):
        base = os.path.join(self.root, name)
        os.makedirs(base, exist_ok=True)
        with open(os.path.join(base, "build.gradle.kts"), "w") as handle:
            handle.write("plugins {\n")
            for plugin_id in plugin_ids:
                handle.write('    id("%s")\n' % plugin_id)
            handle.write("}\n")
        return name

    def test_direct_application_still_counts(self):
        name = self._module("direct", ["prism.jacoco"])
        self.assertTrue(coverage.has_jacoco(name, self.root))

    def test_base_android_library_inherits(self):
        name = self._module("androidlib", ["acme.android.library"])
        self.assertTrue(coverage.has_jacoco(name, self.root))

    def test_compose_library_inherits_transitively(self):
        name = self._module("composelib", ["acme.android.library.compose"])
        self.assertTrue(coverage.has_jacoco(name, self.root))

    def test_an_unrelated_plugin_alone_does_not_count(self):
        name = self._module("ktoronly", ["acme.kotlin.ktor"])
        self.assertFalse(coverage.has_jacoco(name, self.root))

    def test_a_module_with_no_prism_plugins_does_not_count(self):
        name = self._module("plain", [])
        self.assertFalse(coverage.has_jacoco(name, self.root))

    def test_a_missing_build_file_does_not_count(self):
        self.assertFalse(coverage.has_jacoco("no-such-module", self.root))

    def test_removing_the_apply_from_the_base_plugin_stops_counting(self):
        """The detector must follow build-logic, not assume it.

        If someone deletes the apply line from AndroidLibraryConventionPlugin,
        modules using it really are unconfigured again, and the gate must say
        so rather than keep vouching for them from a hardcoded list.
        """
        name = self._module("androidlib", ["acme.android.library"])
        self._plugin_source("AndroidLibraryConventionPlugin", [
            'pluginManager.apply("com.android.library")',
        ])
        self.assertFalse(coverage.has_jacoco(name, self.root))

    def test_providers_include_the_transitive_closure(self):
        providers = coverage._plugins_providing_jacoco(self.root)
        self.assertEqual(
            {
                "prism.jacoco",
                "acme.android.library",
                "acme.android.library.compose",
            },
            providers,
        )

    def test_the_real_repository_configures_every_module(self):
        """The guarantee, asserted against the actual build.

        Every module in settings.gradle.kts must resolve to configured. This is
        the assertion that fails the moment someone adds a module that applies
        no base convention plugin, which is the only remaining way the
        `no-jacoco` denial could come back.
        """
        repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
        settings = os.path.join(repo_root, "settings.gradle.kts")
        if not os.path.exists(settings):
            self.skipTest("not running inside the repository")
        with open(settings) as handle:
            modules = re.findall(r'^include\("([^"]+)"\)', handle.read(), re.MULTILINE)
        self.assertGreater(len(modules), 0)
        unconfigured = [
            module
            for module in modules
            if not coverage.has_jacoco(module.lstrip(":").replace(":", "/"), repo_root)
        ]
        self.assertEqual([], unconfigured)


class ShippedThresholdsTests(unittest.TestCase):
    """The shipped thresholds.json must BE Principle IV, not a ratchet.

    It used to record each module's MEASURED coverage as its floor —
    :core:data at 6, :core:presentation at 24, :feature:auth:presentation at
    27 — so gate 3 passed modules nowhere near the standard while its denial
    text still read 'below the required N%'. An override may now only raise
    a module above the 80 default, never lower one.
    """

    def setUp(self):
        here = os.path.dirname(os.path.abspath(__file__))
        self.root = os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.dirname(here))))
        with open(os.path.join(here, "..", "thresholds.json")) as handle:
            self.config = json.load(handle)

    def test_default_is_the_principle_iv_standard(self):
        self.assertEqual(self.config["default"], 80)

    def test_no_override_is_below_the_standard(self):
        below = {module: value
                 for module, value in self.config.get("overrides", {}).items()
                 if value < 80}
        self.assertEqual(below, {}, "overrides may only raise a module above 80")

    def test_the_example_override_is_deletable(self):
        """An example you cannot delete is not an example.

        This assertion used to read
        `threshold_for(":core:crypto", config) == 100`, pinning the shipped
        override by name. `:core:crypto` is the ORIGIN project's module; the
        entry survives as a worked example of a security-critical floor, and
        `$example` in the file says so. But a consumer who does the obvious
        thing -- replace it with their own module, or drop the object -- would
        have broken `.prism/verify/tests/run-tests.sh` in their own repository,
        which is a fine way to teach people not to touch the file.

        What actually matters is the SHAPE, and it is asserted here and in
        test_no_override_is_below_the_standard: an override may only raise.
        """
        for overrides in ({}, {":core:crypto": 100}, {":feature:payments": 95}):
            config = {"default": 80, "overrides": overrides}
            for module, expected in overrides.items():
                self.assertEqual(coverage.threshold_for(module, config), expected)
            self.assertEqual(coverage.threshold_for(":anything:else", config), 80)

    def test_an_override_that_is_present_raises_rather_than_lowers(self):
        for module, value in self.config.get("overrides", {}).items():
            self.assertGreaterEqual(
                coverage.threshold_for(module, self.config), self.config["default"],
                "%s is held below the default" % module,
            )

    def test_every_settings_module_requires_at_least_eighty(self):
        settings = os.path.join(self.root, "settings.gradle.kts")
        if not os.path.exists(settings):
            self.skipTest("settings.gradle.kts not found from this location")
        with open(settings) as handle:
            modules = re.findall(r'^include\("([^"]+)"\)', handle.read(), re.MULTILINE)
        self.assertTrue(modules, "expected to find modules in settings.gradle.kts")
        for module in modules:
            self.assertGreaterEqual(
                coverage.threshold_for(module, self.config), 80,
                "%s is held below the Principle IV standard" % module)



class FreshnessTests(unittest.TestCase):
    """The push gate CHECKS a coverage result instead of producing one, so
    "was this report made from the code on disk now" is the whole gate."""

    XML = ('<?xml version="1.0"?><report name="m">'
           '<counter type="LINE" missed="10" covered="90"/></report>')

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.module_dir = "mod"
        src = os.path.join(self.root, "mod", "src", "main", "java")
        os.makedirs(src)
        self.source = os.path.join(src, "Main.kt")
        with open(self.source, "w") as handle:
            handle.write("fun main() {}\n")
        reports = os.path.join(self.root, "mod", "build", "reports", "cov")
        os.makedirs(reports)
        self.xml = os.path.join(reports, "coverage.xml")

    def tearDown(self):
        self.tmp.cleanup()

    def _write_report(self, offset):
        """Write the XML with an mtime `offset` seconds after the source."""
        with open(self.xml, "w") as handle:
            handle.write(self.XML)
        stamp = os.path.getmtime(self.source) + offset
        os.utime(self.xml, (stamp, stamp))

    def _write_execution_data(self, offset):
        ec_dir = os.path.join(self.root, "mod", "build", "outputs",
                              "code_coverage", "debugAndroidTest", "connected")
        os.makedirs(ec_dir, exist_ok=True)
        path = os.path.join(ec_dir, "coverage.ec")
        with open(path, "w") as handle:
            handle.write("x")
        stamp = os.path.getmtime(self.source) + offset
        os.utime(path, (stamp, stamp))

    def test_missing_report_is_not_fresh(self):
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm"),
            "no-report")

    def test_report_newer_than_sources_is_fresh(self):
        self._write_report(offset=10)
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm"),
            "fresh")

    def test_report_older_than_sources_is_stale(self):
        self._write_report(offset=-10)
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm"),
            "stale")

    def test_editing_a_source_after_the_report_makes_it_stale(self):
        self._write_report(offset=10)
        later = os.path.getmtime(self.xml) + 60
        os.utime(self.source, (later, later))
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm"),
            "stale")

    def test_combined_report_without_execution_data_is_rejected(self):
        # prismCombinedCoverage dependsOn testDebugUnitTest only and merges
        # .ec through a TOLERANT fileTree, so it happily writes a fresh
        # report containing unit coverage alone. Freshness of the XML is
        # therefore NOT evidence the instrumentation suite ran.
        self._write_report(offset=10)
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "combined"),
            "no-device-run")

    def _write_device_run_evidence(self, offset):
        """AGP's connected-run output, WITHOUT any .ec — a killed run."""
        results = os.path.join(self.root, "mod", "build", "outputs",
                               "androidTest-results", "connected")
        os.makedirs(results, exist_ok=True)
        path = os.path.join(results, "TEST-emulator.xml")
        with open(path, "w") as handle:
            handle.write("<testsuite/>")
        stamp = os.path.getmtime(self.source) + offset
        os.utime(path, (stamp, stamp))
        os.utime(results, (stamp, stamp))

    def test_a_killed_device_run_is_not_reported_as_never_having_run(self):
        # Two connectedDebugAndroidTest runs for the same test package on one
        # emulator kill each other. The loser dies with "Instrumentation run
        # failed due to Process crashed" having produced result output but no
        # execution data — and the gate then said `no-device-run`, which reads
        # as "you never ran it" and sent the reader off to repeat the one
        # thing they had already done.
        self._write_device_run_evidence(offset=5)
        self._write_report(offset=10)
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "combined"),
            "device-run-no-data")

    def test_evidence_older_than_the_sources_is_still_never_ran(self):
        # A connected run from before the current code is not a run of this
        # code, so the action is still "go run it".
        self._write_device_run_evidence(offset=-30)
        self._write_report(offset=10)
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "combined"),
            "no-device-run")

    def test_execution_data_present_wins_over_the_evidence_split(self):
        # Evidence is only consulted when there is no .ec at all; a real run
        # that brought data back is judged on the data.
        self._write_device_run_evidence(offset=5)
        self._write_execution_data(offset=5)
        self._write_report(offset=10)
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "combined"),
            "fresh")

    def test_combined_report_with_fresh_execution_data_is_fresh(self):
        self._write_execution_data(offset=5)
        self._write_report(offset=10)
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "combined"),
            "fresh")

    def test_execution_data_older_than_sources_is_rejected(self):
        self._write_execution_data(offset=-10)
        self._write_report(offset=10)
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "combined"),
            "stale-device-run")

    def test_device_run_after_the_report_is_rejected(self):
        # The report cannot contain a run that happened after it was written.
        self._write_report(offset=5)
        self._write_execution_data(offset=60)
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "combined"),
            "stale-device-run")

    # --- 7.6: mtime is a cheap pre-check, content is the answer ----------
    def _touch_source_later(self):
        """Move the source mtime past the report without changing a byte —
        what a checkout, a stash pop or a no-op editor save does."""
        later = os.path.getmtime(self.xml) + 60
        os.utime(self.source, (later, later))

    def test_an_mtime_only_change_is_not_stale_once_recorded(self):
        self._write_report(offset=10)
        # A gate pass records the fingerprint.
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml,
                                      "jvm", record=True),
            "fresh")
        self._touch_source_later()
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm"),
            "fresh")

    def test_a_real_content_change_is_still_stale_with_a_record(self):
        self._write_report(offset=10)
        coverage.report_freshness(self.module_dir, self.root, self.xml,
                                  "jvm", record=True)
        with open(self.source, "a") as handle:
            handle.write("fun added() {}\n")
        self._touch_source_later()
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm"),
            "stale")

    def test_without_a_record_it_falls_back_to_mtime(self):
        self._write_report(offset=10)
        self._touch_source_later()
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm"),
            "stale")

    def test_nothing_is_recorded_unless_asked(self):
        self._write_report(offset=10)
        coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm")
        self.assertFalse(os.path.exists(
            os.path.join(os.path.dirname(self.xml), coverage.FINGERPRINT_NAME)))

    def test_nothing_is_recorded_for_a_report_that_is_not_fresh(self):
        self._write_report(offset=-10)
        coverage.report_freshness(self.module_dir, self.root, self.xml,
                                  "jvm", record=True)
        self.assertFalse(os.path.exists(
            os.path.join(os.path.dirname(self.xml), coverage.FINGERPRINT_NAME)))

    def test_an_mtime_only_change_also_rescues_the_device_verdict(self):
        self._write_execution_data(offset=5)
        self._write_report(offset=10)
        coverage.report_freshness(self.module_dir, self.root, self.xml,
                                  "combined", record=True)
        self._touch_source_later()
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "combined"),
            "fresh")

    def test_a_missing_artifact_is_never_rescued_by_the_fingerprint(self):
        # An unchanged source set does not make an absent .ec present.
        self._write_report(offset=10)
        coverage.report_freshness(self.module_dir, self.root, self.xml,
                                  "jvm", record=True)
        self._touch_source_later()
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "combined"),
            "no-device-run")

    def test_the_fingerprint_covers_the_build_file(self):
        self._write_report(offset=10)
        coverage.report_freshness(self.module_dir, self.root, self.xml,
                                  "jvm", record=True)
        build_file = os.path.join(self.root, "mod", "build.gradle.kts")
        with open(build_file, "w") as handle:
            handle.write("plugins { id(\"x\") }\n")
        later = os.path.getmtime(self.xml) + 60
        os.utime(build_file, (later, later))
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm"),
            "stale")

    def test_jvm_kind_ignores_execution_data_entirely(self):
        self._write_report(offset=10)
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm"),
            "fresh")

    def test_build_file_edit_invalidates_the_report(self):
        self._write_report(offset=10)
        build_file = os.path.join(self.root, "mod", "build.gradle.kts")
        with open(build_file, "w") as handle:
            handle.write("plugins { }\n")
        later = os.path.getmtime(self.xml) + 60
        os.utime(build_file, (later, later))
        self.assertEqual(
            coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm"),
            "stale")


class CanaryFingerprintTests(unittest.TestCase):
    """Proving a gate is live must not cost the measurement it just took.

    THE PROCEDURE THIS PROTECTS: promoting a module tells you to plant a
    deliberate violation, watch the gate deny it, and revert. Reverting rewrites
    the file, so its mtime moves; gate 3 compares the coverage report against the
    code on disk and the measurement taken minutes earlier became `stale`. On a
    connected suite retaking it is an emulator and several minutes, so the honest
    reading of 0.5.0 was: verify the gate, or keep your coverage result.

    The rescue existed and was unreachable. report_freshness() has always
    accepted a recorded source fingerprint as proof that unchanged CONTENT sits
    under a moved timestamp -- but the only caller passing --record was the push
    gate, so anybody who measured and did not immediately push had nothing
    recorded. `record` is that write, offered to whoever produced the report.
    """

    XML = ('<?xml version="1.0"?><report name="m">'
           '<counter type="LINE" missed="10" covered="90"/></report>')

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = self.tmp.name
        self.module_dir = "mod"
        src = os.path.join(self.root, "mod", "src", "main", "java")
        os.makedirs(src)
        self.source = os.path.join(src, "Main.kt")
        self.original = "fun main() {}\n"
        with open(self.source, "w") as handle:
            handle.write(self.original)
        reports = os.path.join(self.root, "mod", "build", "reports", "cov")
        os.makedirs(reports)
        self.xml = os.path.join(reports, "coverage.xml")
        with open(self.xml, "w") as handle:
            handle.write(self.XML)
        stamp = os.path.getmtime(self.source) + 10
        os.utime(self.xml, (stamp, stamp))

    def record(self):
        argv = ["coverage.py", "record", "--module", ":mod", "--root", self.root,
                "--xml", self.xml, "--kind", "jvm"]
        saved, sys.argv = sys.argv, argv
        out = io.StringIO()
        saved_out, sys.stdout = sys.stdout, out
        try:
            return coverage.main(), out.getvalue().strip()
        finally:
            sys.argv, sys.stdout = saved, saved_out

    def freshness(self):
        return coverage.report_freshness(self.module_dir, self.root, self.xml, "jvm")

    def plant_and_revert(self):
        """The canary, exactly as the guide describes it: append, then restore."""
        with open(self.source, "a") as handle:
            handle.write('fun prismCanary() { println("canary") }\n')
        later = os.path.getmtime(self.xml) + 60
        os.utime(self.source, (later, later))
        with open(self.source, "w") as handle:
            handle.write(self.original)
        later += 60
        os.utime(self.source, (later, later))

    def test_a_canary_cycle_used_to_cost_the_measurement(self):
        """The state without a record, so the fix below is measured against it."""
        self.assertEqual("fresh", self.freshness())
        self.plant_and_revert()
        self.assertEqual("stale", self.freshness(),
                         "nothing recorded, so an mtime is all there is to go on")

    def test_a_recorded_report_survives_the_canary(self):
        """THE FIX. Same cycle, with the fingerprint written when it was true."""
        self.assertEqual((0, "recorded"), self.record())
        self.plant_and_revert()
        self.assertEqual("fresh", self.freshness(),
                         "the content is identical, and that was recorded")

    def test_a_real_edit_is_still_stale_after_recording(self):
        """The property that makes the rescue safe: CONTENT, not a timestamp."""
        self.assertEqual((0, "recorded"), self.record())
        with open(self.source, "a") as handle:
            handle.write("fun addedForReal() = 1\n")
        later = os.path.getmtime(self.xml) + 60
        os.utime(self.source, (later, later))
        self.assertEqual("stale", self.freshness())

    def test_record_refuses_a_report_the_timestamps_call_stale(self):
        """It must never be a way to declare a stale number current.

        This is the most attractive escape hatch the framework could grow: one
        command that makes gate 3 accept whatever coverage was last measured. It
        can only ever write down something the mtime check already agrees with.
        """
        later = os.path.getmtime(self.xml) + 60
        os.utime(self.source, (later, later))
        code, output = self.record()
        self.assertEqual(1, code)
        self.assertEqual("stale", output)
        self.assertFalse(
            os.path.exists(coverage._fingerprint_path(self.xml)),
            "and it writes nothing while refusing")

    def test_record_without_a_report_says_so(self):
        os.remove(self.xml)
        self.assertEqual((1, "no-report"), self.record())


class ObservedModuleTests(unittest.TestCase):
    """Coverage in a module set to `coverage: observe`.

    The asymmetry worth stating: observe short-circuits the WHOLE verdict, not
    just the pass/fail comparison. A module nobody has promoted yet has no
    obligation to have a fresh coverage report, or any report -- and treating a
    missing one as a denial would make observe block on exactly the modules it
    exists to unblock. It still prints what it knows, because a gap nobody can
    see is a gap nobody closes.
    """

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        os.makedirs(os.path.join(self.root, ".prism"))
        self.config = os.path.join(self.root, "thresholds.json")
        with open(self.config, "w") as handle:
            json.dump({"default": 80, "overrides": {}}, handle)
        # PRODUCTION SOURCE, on purpose. Without it :app has nothing to cover,
        # and since 0.6.0 that is its own verdict -- so these cases would pass by
        # taking a branch that has nothing to do with posture. See NoSourcesTests.
        source = os.path.join(self.root, "app", "src", "main", "kotlin")
        os.makedirs(source)
        with open(os.path.join(source, "App.kt"), "w") as handle:
            handle.write("class App\n")

    def scope(self, posture):
        with open(os.path.join(self.root, ".prism", "scope.json"), "w") as handle:
            json.dump({"default": {"coverage": posture}, "modules": {}}, handle)

    def verdict(self, xml="does-not-exist.xml"):
        argv = ["coverage.py", "verdict", "--module", ":app", "--root", self.root,
                "--xml", os.path.join(self.root, xml), "--kind", "jvm",
                "--config", self.config]
        saved, sys.argv = sys.argv, argv
        out = io.StringIO()
        saved_out, sys.stdout = sys.stdout, out
        try:
            return coverage.main(), out.getvalue().strip()
        finally:
            sys.argv, sys.stdout = saved, saved_out

    def test_observed_module_does_not_deny_on_a_missing_report(self):
        self.scope("observe")
        code, output = self.verdict()
        self.assertEqual(0, code)
        self.assertTrue(output.startswith("observe"), output)
        self.assertIn("no-report", output)

    def test_enforced_module_still_denies_on_a_missing_report(self):
        self.scope("enforce")
        code, _ = self.verdict()
        self.assertEqual(1, code)

    def test_absent_scope_file_enforces(self):
        code, _ = self.verdict()
        self.assertEqual(1, code)

    def test_unreadable_scope_file_denies_rather_than_guesses(self):
        with open(os.path.join(self.root, ".prism", "scope.json"), "w") as handle:
            handle.write("{ not json")
        code, output = self.verdict()
        self.assertEqual(1, code)
        self.assertIn("scope-unreadable", output)


class NoSourcesTests(unittest.TestCase):
    """A module with tests and NO production code must not deny a push.

    `:tooling:konsist` is exactly that shape, and PRISM's own installer places
    it: a src/test full of konsist rules, no src/main at all. Its report_kind is
    `jvm`, so it never reached the `none` arm that consults has_sources(); JaCoCo
    produced a report with zero lines, the verdict printed `no-line-data`, and
    GATE 3 DENIED THE PUSH. The framework failed a repository on code the
    framework had just written into it.

    has_sources() existed the whole time. This is the arm that calls it.
    """

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        os.makedirs(os.path.join(self.root, ".prism"))
        self.config = os.path.join(self.root, "thresholds.json")
        with open(self.config, "w") as handle:
            json.dump({"default": 80, "overrides": {}}, handle)

    def module(self, gradle_path, *source_sets):
        directory = os.path.join(self.root, *gradle_path.strip(":").split(":"))
        for source_set in source_sets:
            path = os.path.join(directory, "src", source_set, "kotlin")
            os.makedirs(path, exist_ok=True)
            with open(os.path.join(path, "Thing.kt"), "w") as handle:
                handle.write("class Thing\n")
        return directory

    def empty_report(self, gradle_path):
        directory = os.path.join(self.root, *gradle_path.strip(":").split(":"),
                                 "build", "reports", "prism-coverage")
        os.makedirs(directory, exist_ok=True)
        path = os.path.join(directory, "coverage.xml")
        with open(path, "w") as handle:
            handle.write('<report name="x"><counter type="LINE" missed="0" '
                         'covered="0"/></report>\n')
        return path

    def verdict(self, gradle_path, xml):
        argv = ["coverage.py", "verdict", "--module", gradle_path,
                "--root", self.root, "--xml", xml, "--kind", "jvm",
                "--config", self.config]
        saved, sys.argv = sys.argv, argv
        out = io.StringIO()
        saved_out, sys.stdout = sys.stdout, out
        try:
            return coverage.main(), out.getvalue().strip()
        finally:
            sys.argv, sys.stdout = saved, saved_out

    def test_a_test_only_module_does_not_deny(self):
        """THE REGRESSION. Tests, no production code, a report of zero lines."""
        self.module(":tooling:konsist", "test")
        xml = self.empty_report(":tooling:konsist")
        code, output = self.verdict(":tooling:konsist", xml)
        self.assertEqual(0, code, "a module with nothing to cover cannot be under-covered")
        self.assertEqual("no-sources", output)

    def test_it_does_not_even_need_a_report(self):
        """There is nothing to measure, so a missing report is not staleness."""
        self.module(":tooling:konsist", "test")
        code, output = self.verdict(":tooling:konsist",
                                    os.path.join(self.root, "nope.xml"))
        self.assertEqual(0, code)
        self.assertEqual("no-sources", output)

    def test_a_module_WITH_production_code_and_no_lines_still_denies(self):
        """The other direction, which must not be softened by the fix above.

        Production code plus a report that measured nothing is a real gap: either
        the report is for the wrong thing or the module is entirely untested.
        """
        self.module(":app", "main", "test")
        xml = self.empty_report(":app")
        code, output = self.verdict(":app", xml)
        self.assertEqual(1, code)
        self.assertIn("no-line-data", output)


if __name__ == "__main__":
    unittest.main()

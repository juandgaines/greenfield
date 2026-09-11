#!/usr/bin/env python3
"""Tests for the substitution mechanism.

Every test here asserts a REFUSAL. That is deliberate: rendering a fully
resolved template correctly is the easy half, and a mechanism that only did
that would still ship broken installs. What earns its keep is refusing to write
a file it cannot finish, refusing a required parameter that carries a default,
and refusing a placeholder nobody declared -- because each of those, left
alone, produces an install that reports success while verifying nothing.
"""

import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))

import render  # noqa: E402


class RenderTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="prism-render-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def path(self, name):
        return os.path.join(self.tmp, name)

    def write(self, name, text):
        target = self.path(name)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(text)
        return target

    def registry(self, parameters):
        return self.write("parameters.json", json.dumps({"parameters": parameters}))


class TestFailClosedRendering(RenderTestCase):
    """An unresolved placeholder must stop the render and write nothing."""

    def test_unresolved_placeholder_raises_and_names_it(self):
        with self.assertRaises(render.RenderError) as caught:
            render.substitute("package @PACKAGE_ROOT@", {}, "ProjectScope.kt.in")
        message = str(caught.exception)
        self.assertIn("@PACKAGE_ROOT@", message)
        self.assertIn("ProjectScope.kt.in", message)
        self.assertIn("Nothing was written", message)

    def test_every_unresolved_placeholder_is_named_not_just_the_first(self):
        with self.assertRaises(render.RenderError) as caught:
            render.substitute("@A_ONE@ @B_TWO@", {}, "t.in")
        self.assertIn("@A_ONE@", str(caught.exception))
        self.assertIn("@B_TWO@", str(caught.exception))

    def test_no_output_file_is_written_when_a_value_is_missing(self):
        registry = self.registry(
            {"PACKAGE_ROOT": {"tier": "required", "description": "d", "templates": []}}
        )
        template = self.write("t.in", "package @PACKAGE_ROOT@")
        output = self.path("out.kt")
        code = render.main(
            ["--parameters", registry, "--template", template, "--output", output]
        )
        self.assertEqual(code, 1)
        self.assertFalse(os.path.exists(output), "a file was written despite the refusal")

    def test_an_existing_output_survives_a_failed_render(self):
        registry = self.registry(
            {"PACKAGE_ROOT": {"tier": "required", "description": "d", "templates": []}}
        )
        template = self.write("t.in", "package @PACKAGE_ROOT@")
        output = self.write("out.kt", "the previous, working content")
        render.main(["--parameters", registry, "--template", template, "--output", output])
        with open(output, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "the previous, working content")

    def test_a_resolved_template_renders(self):
        registry = self.registry(
            {"PACKAGE_ROOT": {"tier": "required", "description": "d", "templates": []}}
        )
        template = self.write("t.in", "package @PACKAGE_ROOT@.konsist")
        values = self.write("v.json", json.dumps({"PACKAGE_ROOT": "com.acme.app"}))
        output = self.path("out.kt")
        code = render.main(
            ["--parameters", registry, "--template", template,
             "--output", output, "--values", values]
        )
        self.assertEqual(code, 0)
        with open(output, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "package com.acme.app.konsist")


class TestConsume(RenderTestCase):
    """--consume removes the template, and only after a successful write.

    This replaced `render ... && rm -f "$K/$f.kt.in"` in the install recipe,
    which stalled 5 of 5 measured installs on an agent's dangerous-command guard
    -- a variable path in an `rm` is what that guard exists to stop. The guard
    was right: a piped variant, where the pipeline's status replaced render's,
    deleted three templates having rendered nothing.

    So the ordering below is the whole point, and the second test is the one
    that matters.
    """

    def _registry(self):
        return self.registry(
            {"PACKAGE_ROOT": {"tier": "required", "description": "d", "templates": []}}
        )

    def test_the_template_is_removed_after_a_successful_render(self):
        registry = self._registry()
        template = self.write("T.kt.in", "package @PACKAGE_ROOT@")
        values = self.write("v.json", json.dumps({"PACKAGE_ROOT": "com.example"}))
        output = self.path("T.kt")
        code = render.main(["--parameters", registry, "--values", values,
                            "--consume", "--template", template, "--output", output])
        self.assertEqual(code, 0)
        self.assertTrue(os.path.exists(output), "nothing was rendered")
        self.assertFalse(os.path.exists(template), "the template survived")

    def test_a_refused_render_leaves_the_template_alone(self):
        registry = self._registry()
        template = self.write("T.kt.in", "package @PACKAGE_ROOT@")
        output = self.path("T.kt")
        code = render.main(["--parameters", registry, "--consume",
                            "--template", template, "--output", output])
        self.assertEqual(code, 1)
        self.assertFalse(os.path.exists(output), "a file was written despite the refusal")
        self.assertTrue(
            os.path.exists(template),
            "THE TEMPLATE WAS DESTROYED BY A FAILED RENDER -- this is the exact "
            "loss the shell recipe caused, and recovering it means unpacking the "
            "artifact again")

    def test_without_consume_the_template_stays(self):
        registry = self._registry()
        template = self.write("T.kt.in", "package @PACKAGE_ROOT@")
        values = self.write("v.json", json.dumps({"PACKAGE_ROOT": "com.example"}))
        output = self.path("T.kt")
        render.main(["--parameters", registry, "--values", values,
                     "--template", template, "--output", output])
        self.assertTrue(os.path.exists(template), "--consume is not the default")


class TestPlaceholderSyntax(RenderTestCase):
    """@NAME@ must not disturb the languages the templates are written in."""

    def test_kotlin_string_templates_are_left_alone(self):
        source = 'val s = "${greeting}, @PACKAGE_ROOT@"'
        rendered = render.substitute(source, {"PACKAGE_ROOT": "com.acme"}, "t.in")
        self.assertEqual(rendered, 'val s = "${greeting}, com.acme"')

    def test_shell_expansion_is_left_alone(self):
        source = 'root="${PRISM_ROOT:-.}"; pkg=@PACKAGE_ROOT@'
        rendered = render.substitute(source, {"PACKAGE_ROOT": "com.acme"}, "t.in")
        self.assertEqual(rendered, 'root="${PRISM_ROOT:-.}"; pkg=com.acme')

    def test_an_email_address_is_not_a_placeholder(self):
        source = "author: someone@example.com"
        self.assertEqual(render.substitute(source, {}, "t.in"), source)

    def test_a_kotlin_annotation_is_not_a_placeholder(self):
        source = "@Composable fun X() {}"
        self.assertEqual(render.substitute(source, {}, "t.in"), source)


class TestParameterTiers(RenderTestCase):
    """The three tiers, and the rule that keeps the bottom one honest."""

    def test_a_defaulted_parameter_renders_with_no_values_supplied(self):
        registry = self.registry(
            {"BASE_BRANCH": {"tier": "defaulted", "default": "main", "templates": []}}
        )
        template = self.write("t.in", "base=@BASE_BRANCH@")
        output = self.path("out.json")
        self.assertEqual(
            render.main(["--parameters", registry, "--template", template,
                         "--output", output]),
            0,
        )
        with open(output, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "base=main")

    def test_a_supplied_value_overrides_a_default(self):
        registry = self.registry(
            {"BASE_BRANCH": {"tier": "defaulted", "default": "main", "templates": []}}
        )
        template = self.write("t.in", "base=@BASE_BRANCH@")
        values = self.write("v.json", json.dumps({"BASE_BRANCH": "trunk"}))
        output = self.path("out.json")
        render.main(["--parameters", registry, "--template", template,
                     "--output", output, "--values", values])
        with open(output, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "base=trunk")

    def test_a_required_parameter_may_not_ship_a_default(self):
        # The tempting default is the origin repository's own value. It renders
        # cleanly, installs without complaint, and points the analysis at a
        # tree the consumer does not have.
        registry = self.registry(
            {"PACKAGE_ROOT": {"tier": "required", "default": "com.example.origin"}}
        )
        with self.assertRaises(render.RenderError) as caught:
            render.load_parameters(registry)
        message = str(caught.exception)
        self.assertIn("PACKAGE_ROOT", message)
        self.assertIn("no honest default", message)

    def test_a_defaulted_parameter_without_a_default_is_rejected(self):
        registry = self.registry({"BASE_BRANCH": {"tier": "defaulted"}})
        with self.assertRaises(render.RenderError):
            render.load_parameters(registry)

    def test_an_unknown_tier_is_rejected(self):
        registry = self.registry({"X_Y": {"tier": "probably-fine"}})
        with self.assertRaises(render.RenderError):
            render.load_parameters(registry)

    def test_a_value_for_an_undeclared_parameter_is_refused(self):
        registry = self.registry(
            {"BASE_BRANCH": {"tier": "defaulted", "default": "main", "templates": []}}
        )
        template = self.write("t.in", "base=@BASE_BRANCH@")
        values = self.write("v.json", json.dumps({"NOT_DECLARED": "x"}))
        output = self.path("out.json")
        self.assertEqual(
            render.main(["--parameters", registry, "--template", template,
                         "--output", output, "--values", values]),
            1,
        )
        self.assertFalse(os.path.exists(output))


class TestTargetedReRender(RenderTestCase):
    """Re-rendering ONE file must not demand the whole registry.

    Re-rendering a single generated file is the documented update path -- a
    consumer adds a module, the package map regenerates. If that asked for
    every required parameter in the registry, including ones the file does not
    reference, the update path would be unusable outside a full install.
    """

    def two_required(self):
        return self.registry(
            {
                "PACKAGE_ROOT": {"tier": "required", "description": "d", "templates": []},
                "OTHER_THING": {"tier": "required", "description": "d", "templates": []},
            }
        )

    def test_only_the_placeholders_the_template_uses_are_required(self):
        registry = self.two_required()
        template = self.write("t.in", "package @PACKAGE_ROOT@")
        values = self.write("v.json", json.dumps({"PACKAGE_ROOT": "com.acme"}))
        output = self.path("out.kt")
        self.assertEqual(
            render.main(["--parameters", registry, "--template", template,
                         "--output", output, "--values", values]),
            0,
        )
        with open(output, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "package com.acme")

    def test_a_placeholder_the_template_does_use_is_still_required(self):
        registry = self.two_required()
        template = self.write("t.in", "@PACKAGE_ROOT@ @OTHER_THING@")
        values = self.write("v.json", json.dumps({"PACKAGE_ROOT": "com.acme"}))
        output = self.path("out.kt")
        self.assertEqual(
            render.main(["--parameters", registry, "--template", template,
                         "--output", output, "--values", values]),
            1,
        )
        self.assertFalse(os.path.exists(output))

    def test_the_manifest_records_only_what_the_template_used(self):
        registry = self.two_required()
        template = self.write("t.in", "package @PACKAGE_ROOT@")
        values = self.write("v.json", json.dumps({"PACKAGE_ROOT": "com.acme"}))
        output = self.path("out.kt")
        manifest = self.path("manifest.json")
        render.main(["--parameters", registry, "--template", template,
                     "--output", output, "--values", values, "--manifest", manifest])
        with open(manifest, encoding="utf-8") as handle:
            entry = json.load(handle)["files"][output]
        self.assertEqual(entry["values"], {"PACKAGE_ROOT": "com.acme"})


class TestEnumerableSet(RenderTestCase):
    """The required set must be closed, and checkable without a target repo."""

    def test_check_fails_on_a_placeholder_no_registry_declares(self):
        registry = self.registry(
            {"BASE_BRANCH": {"tier": "defaulted", "default": "main", "templates": []}}
        )
        os.makedirs(self.path("templates"), exist_ok=True)
        self.write("templates/x.in", "@BASE_BRANCH@ and @UNDECLARED_ONE@")
        self.assertEqual(
            render.main(["--parameters", registry, "--check",
                         "--templates-dir", self.path("templates")]),
            1,
        )

    def test_check_passes_when_every_placeholder_is_declared(self):
        registry = self.registry(
            {"BASE_BRANCH": {"tier": "defaulted", "default": "main", "templates": []}}
        )
        os.makedirs(self.path("templates"), exist_ok=True)
        self.write("templates/x.in", "@BASE_BRANCH@")
        self.assertEqual(
            render.main(["--parameters", registry, "--check",
                         "--templates-dir", self.path("templates")]),
            0,
        )

    def test_list_required_needs_no_target_repository(self):
        registry = self.registry(
            {
                "PACKAGE_ROOT": {"tier": "required", "description": "d",
                                 "templates": ["a.in"]},
                "BASE_BRANCH": {"tier": "defaulted", "default": "main", "templates": []},
            }
        )
        self.assertEqual(render.main(["--parameters", registry, "--list-required"]), 0)


class TestProvenance(RenderTestCase):
    """The manifest must record enough to tell drift from a hand edit."""

    def test_manifest_records_template_values_and_hash(self):
        registry = self.registry(
            {"PACKAGE_ROOT": {"tier": "required", "description": "d", "templates": []}}
        )
        template = self.write("t.in", "package @PACKAGE_ROOT@")
        values = self.write("v.json", json.dumps({"PACKAGE_ROOT": "com.acme"}))
        output = self.path("out.kt")
        manifest = self.path("manifest.json")
        render.main(["--parameters", registry, "--template", template, "--output", output,
                     "--values", values, "--manifest", manifest])
        with open(manifest, encoding="utf-8") as handle:
            entry = json.load(handle)["files"][output]
        self.assertEqual(entry["template"], template)
        self.assertEqual(entry["values"], {"PACKAGE_ROOT": "com.acme"})
        self.assertEqual(entry["sha256"], render.sha256_of("package com.acme"))

    def test_re_rendering_unchanged_inputs_is_idempotent(self):
        registry = self.registry(
            {"PACKAGE_ROOT": {"tier": "required", "description": "d", "templates": []}}
        )
        template = self.write("t.in", "package @PACKAGE_ROOT@")
        values = self.write("v.json", json.dumps({"PACKAGE_ROOT": "com.acme"}))
        output = self.path("out.kt")
        manifest = self.path("manifest.json")
        argv = ["--parameters", registry, "--template", template, "--output", output,
                "--values", values, "--manifest", manifest]
        render.main(argv)
        with open(manifest, encoding="utf-8") as handle:
            first = handle.read()
        render.main(argv)
        with open(manifest, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), first)

    def test_a_changed_input_updates_the_recorded_hash(self):
        registry = self.registry(
            {"PACKAGE_ROOT": {"tier": "required", "description": "d", "templates": []}}
        )
        template = self.write("t.in", "package @PACKAGE_ROOT@")
        output = self.path("out.kt")
        manifest = self.path("manifest.json")
        for package in ("com.acme", "com.other"):
            values = self.write("v.json", json.dumps({"PACKAGE_ROOT": package}))
            render.main(["--parameters", registry, "--template", template,
                         "--output", output, "--values", values, "--manifest", manifest])
        with open(manifest, encoding="utf-8") as handle:
            entry = json.load(handle)["files"][output]
        self.assertEqual(entry["sha256"], render.sha256_of("package com.other"))
        self.assertEqual(entry["values"], {"PACKAGE_ROOT": "com.other"})


class TestWorksInBothLayouts(RenderTestCase):
    """render.py must find its registry in the payload AND in an install.

    THE BUG THIS CAUGHT, found by running a command the guide tells consumers
    to run. This file lives at `<payload>/core/verify/lib/render.py` while the
    framework is being built and at `<repo>/.prism/verify/lib/render.py` once
    installed. The default registry path was computed as "four directories up,
    then core/templates" -- correct in the payload, and pointing at
    `<repo>/core/templates/` in an install, which is a directory the consumer
    does not have.

    So `--list-all`, `--list-required` and every render that omitted
    `--parameters` failed in the only place a consumer would ever run them,
    and nothing noticed: the payload's own tests all run in the payload layout.
    """

    def _tree(self, prism_dir):
        lib = os.path.join(self.tmp, prism_dir, "verify", "lib")
        templates = os.path.join(self.tmp, prism_dir, "templates")
        os.makedirs(lib)
        os.makedirs(templates)
        here = os.path.dirname(os.path.abspath(__file__))
        shutil.copy(os.path.join(here, "..", "lib", "render.py"),
                    os.path.join(lib, "render.py"))
        with open(os.path.join(templates, "parameters.json"), "w",
                  encoding="utf-8") as handle:
            json.dump({"parameters": {
                "A_NAME": {"tier": "required", "description": "d", "templates": []}
            }}, handle)
        return os.path.join(lib, "render.py")

    def _list_required(self, script):
        import subprocess
        return subprocess.run([sys.executable, script, "--list-required"],
                              capture_output=True, text=True)

    def test_the_installed_layout_finds_its_registry(self):
        result = self._list_required(self._tree(".prism"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("A_NAME", result.stdout)

    def test_the_payload_layout_still_finds_its_registry(self):
        result = self._list_required(self._tree("core"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("A_NAME", result.stdout)

    def test_the_shipped_default_resolves_from_this_checkout(self):
        self.assertTrue(
            os.path.exists(render.DEFAULT_PARAMETERS),
            "DEFAULT_PARAMETERS does not exist: %s" % render.DEFAULT_PARAMETERS,
        )


class TestGuardedNameInvariant(RenderTestCase):
    """A group switch and the name it guards must agree, or nothing is written.

    THE FAILURE THIS EXISTS FOR, MEASURED RATHER THAN IMAGINED. An install
    shipped `paletteObject: 'none'` on two rules that were `active: true`. The
    rules compared a receiver against the literal string "none", never matched,
    and reported success over five files that violated them. Every check in the
    system passed: the placeholder resolved, the YAML was valid, the rule name
    was declared, the config validated.

    'none' is PRISM's own convention for an inert name, and until this it had
    no implementation anywhere -- the rules guarded with isNotBlank(), and
    'none' is not blank. Two documents promised it would "fail loudly". It did
    not.
    """

    SWITCH = {
        "tier": "defaulted",
        "default": "true",
        "description": "d",
        "guards": ["NAME_OBJECT"],
        "templates": [],
    }
    NAME = {"tier": "required", "description": "d", "templates": []}

    def _render(self, values, template="a: @GROUP_ACTIVE@\nn: '@NAME_OBJECT@'\n"):
        registry = self.registry({"GROUP_ACTIVE": self.SWITCH, "NAME_OBJECT": self.NAME})
        tpl = self.write("t.in", template)
        vals = self.write("v.json", json.dumps(values))
        output = self.path("out.yml")
        code = render.main(
            ["--parameters", registry, "--template", tpl,
             "--output", output, "--values", vals]
        )
        return code, output

    def test_sentinel_beside_an_active_rule_is_refused(self):
        code, output = self._render({"GROUP_ACTIVE": "true", "NAME_OBJECT": "none"})
        self.assertEqual(code, 1)
        self.assertFalse(
            os.path.exists(output),
            "a file was written for a rule that would report success over "
            "everything it was pointed at",
        )

    def test_the_refusal_names_the_parameter_the_switch_and_both_answers(self):
        registry = self.registry({"GROUP_ACTIVE": self.SWITCH, "NAME_OBJECT": self.NAME})
        params = render.load_parameters(registry)
        with self.assertRaises(render.RenderError) as caught:
            render.validate_invariants(
                params, {"GROUP_ACTIVE": "true"}, {"NAME_OBJECT": "none"}
            )
        message = str(caught.exception)
        self.assertIn("NAME_OBJECT", message)
        self.assertIn("GROUP_ACTIVE", message)
        self.assertIn("real name", message)
        self.assertIn("false", message)

    def test_every_spelling_of_the_sentinel_is_refused(self):
        # Case and punctuation are how a sentinel slips through a set-membership
        # check. The value is lower-cased and stripped before comparison.
        for spelling in ("none", "None", "NONE", "  none  ", "n/a", "N/A",
                         "TODO", "tbd", "unknown", ""):
            code, output = self._render(
                {"GROUP_ACTIVE": "true", "NAME_OBJECT": spelling}
            )
            self.assertEqual(code, 1, "%r was accepted beside an active rule" % spelling)
            self.assertFalse(os.path.exists(output), "%r wrote a file" % spelling)

    def test_sentinel_with_the_switch_off_is_the_correct_pairing(self):
        code, output = self._render({"GROUP_ACTIVE": "false", "NAME_OBJECT": "none"})
        self.assertEqual(code, 0)
        self.assertTrue(os.path.exists(output))

    def test_a_real_name_with_the_switch_on_is_the_other_correct_pairing(self):
        code, output = self._render({"GROUP_ACTIVE": "true", "NAME_OBJECT": "AppColors"})
        self.assertEqual(code, 0)
        self.assertTrue(os.path.exists(output))

    def test_a_real_name_beside_an_inactive_group_is_refused_too(self):
        # Harmless at runtime -- the rules never run. Refused because it means
        # somebody answered the name and then switched the group off, and one of
        # those two was not what they meant.
        code, _ = self._render({"GROUP_ACTIVE": "false", "NAME_OBJECT": "AppColors"})
        self.assertEqual(code, 1)

    def test_the_switch_is_checked_even_when_the_template_does_not_use_it(self):
        # `resolve` is scoped to one template's placeholders. A file that used
        # the NAME without the SWITCH would otherwise skip the check entirely,
        # and single-file re-rendering is a documented path.
        code, output = self._render(
            {"GROUP_ACTIVE": "true", "NAME_OBJECT": "none"},
            template="n: '@NAME_OBJECT@'\n",
        )
        self.assertEqual(code, 1)
        self.assertFalse(os.path.exists(output))

    def test_the_switch_default_applies_when_nobody_supplied_it(self):
        # The registry default is `true`, so an unsupplied switch is ACTIVE and
        # a sentinel beside it is still refused. A default nobody typed is the
        # likeliest way this arrives.
        code, output = self._render({"NAME_OBJECT": "none"})
        self.assertEqual(code, 1)
        self.assertFalse(os.path.exists(output))


class TestGuardsRegistryIntegrity(RenderTestCase):
    """`guards` is the switch-to-name link, so the registry must keep it honest."""

    def test_guarding_a_parameter_that_does_not_exist_is_refused(self):
        registry = self.registry(
            {"GROUP_ACTIVE": {"tier": "defaulted", "default": "true",
                              "description": "d", "guards": ["GHOST"],
                              "templates": []}}
        )
        with self.assertRaises(render.RenderError) as caught:
            render.load_parameters(registry)
        self.assertIn("GHOST", str(caught.exception))

    def test_only_a_defaulted_switch_may_guard(self):
        registry = self.registry(
            {"NAME_OBJECT": {"tier": "required", "description": "d",
                             "guards": ["OTHER"], "templates": []},
             "OTHER": {"tier": "required", "description": "d", "templates": []}}
        )
        with self.assertRaises(render.RenderError) as caught:
            render.load_parameters(registry)
        self.assertIn("guards", str(caught.exception))

    def test_the_shipped_registry_guards_every_consumer_named_parameter(self):
        """Every `required` name in a detekt template must sit behind a switch.

        A required name with no switch is a name with no honest answer for a
        repository that lacks the shape -- which is how 'none' beside an active
        rule got written in the first place.
        """
        here = os.path.dirname(os.path.abspath(__file__))
        registry = os.path.join(here, "..", "..", "templates", "parameters.json")
        params = render.load_parameters(registry)
        guarded = {n for spec in params.values() for n in spec.get("guards", [])}
        unguarded = [
            name
            for name, spec in params.items()
            if spec.get("tier") == render.TIER_REQUIRED
            and any("detekt.yml.in" in t for t in spec.get("templates", []))
            and name not in guarded
        ]
        self.assertEqual([], unguarded)


class TestRegistryIntegrity(RenderTestCase):
    def test_a_missing_registry_is_an_error_not_an_empty_set(self):
        with self.assertRaises(render.RenderError):
            render.load_parameters(self.path("absent.json"))

    def test_a_malformed_registry_is_an_error(self):
        broken = self.write("parameters.json", "{not json")
        with self.assertRaises(render.RenderError):
            render.load_parameters(broken)


if __name__ == "__main__":
    unittest.main(verbosity=0)

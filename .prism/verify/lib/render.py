#!/usr/bin/env python3
"""Render a PRISM template by substituting @NAME@ placeholders.

The framework ships files that cannot be finished until they are in a
consumer's repository: the package root konsist analyses, the module-to-package
map, the coverage thresholds a team chose. This renders those.

Three properties, and each exists because its absence is a silent failure
rather than a loud one:

  1. @NAME@, not ${NAME}. Templates are written in Kotlin, Gradle Kotlin DSL,
     POSIX shell, JSON and Markdown, and ${...} is live syntax in the first
     three. A renderer using it cannot tell a placeholder from the file's own
     content, and would corrupt exactly the files that most need templating.

  2. An unresolved placeholder is an ERROR, and nothing is written. A
     partially-substituted file is syntactically plausible, installs without
     complaint, and encodes a literal "@PACKAGE_ROOT@" where a package root
     belongs. It fails by succeeding -- the same class of failure detekt's
     `config.validation: true` and DetektConventionPlugin's explicit
     source.setFrom(...) exist to prevent.

  3. Three parameter tiers, because deferring a value is not free. A value the
     framework OWNS is baked in and is not a parameter at all. A value the
     CONSUMER owns but for which an honest default exists is defaulted, so it
     renders unattended. Only a value the consumer owns AND for which no honest
     default exists is required. Every parameter pushed up that list is one
     fewer thing the setup agent can get wrong.

The tier that must never be fudged is the third. Shipping the origin
repository's own value as a "sensible default" for a required parameter
produces an install that renders cleanly, reports success, and analyses a tree
the consumer does not have.

Python 3 standard library only. This adds no prerequisite: the engine already
denies a push outright when python3 cannot be run (push-gate.sh), on the
argument that a missing interpreter must never be read as "nothing to check".
"""

import argparse
import json
import os
import re
import sys

# The payload root, derived from this file rather than from the working
# directory. render.py is invoked from the CONSUMER's repository root during
# setup -- `python3 prism-setup/core/verify/lib/render.py --list-required` --
# where a cwd-relative "core/templates/parameters.json" resolves to the
# consumer's own core/ modules, if it resolves at all. Both defaults below are
# anchored here so the documented commands work from anywhere.
#
# AND IT MUST WORK IN BOTH LAYOUTS, which an earlier version did not. This file
# lives at `<payload>/core/verify/lib/render.py` while the framework is being
# built, and at `<repo>/.prism/verify/lib/render.py` once it is installed. The
# old anchor walked up four directories and appended "core", which is right in
# the payload and points at `<repo>/core/templates/` -- a directory a consumer
# does not have -- in an install. Every documented invocation that omits
# `--parameters`, `--list-all` and `--list-required` among them, failed in the
# only place a consumer would ever run them.
#
# Two levels up is `core/` or `.prism/` depending on which tree this is, and
# `templates/` sits directly inside either. One rule, both layouts.
PRISM_ROOT = os.path.dirname(  # <payload>/core   OR   <repo>/.prism
    os.path.dirname(os.path.abspath(os.path.dirname(os.path.abspath(__file__))))
)
DEFAULT_PARAMETERS = os.path.join(PRISM_ROOT, "templates", "parameters.json")
DEFAULT_TEMPLATES_DIR = PRISM_ROOT

# A placeholder is @NAME@ where NAME is upper snake case. Deliberately narrow:
# it must not match an email address, a Kotlin annotation, or a decorator.
PLACEHOLDER = re.compile(r"@([A-Z][A-Z0-9_]*)@")

TIER_LITERAL = "literal"
TIER_DEFAULTED = "defaulted"
TIER_REQUIRED = "required"
TIERS = (TIER_LITERAL, TIER_DEFAULTED, TIER_REQUIRED)

# PRISM'S OWN CONVENTION, AND UNTIL NOW AN UNIMPLEMENTED ONE. A parameter that
# names a declaration in the consumer's repository has no honest value when the
# repository has no such declaration, so the group switch goes false and the
# name takes an inert placeholder. `parameters.json` and `SETUP.md` have said
# for several releases that turning the rule back on without a real name "fails
# loudly with the rule's own message" -- and it did not. The rules guarded with
# `isNotBlank()`, and 'none' is not blank.
#
# A measured install shipped `paletteObject: 'none'` with `active: true`. Both
# rules compared a receiver against the literal string "none", never matched,
# and reported success over five files that violated them. Nothing refused it:
# the switch and the name are separate placeholders, and substitution has no
# opinion about whether they agree.
#
# So the pairing is enforced here, at the only moment both values are in one
# dict, and the rules enforce it again at runtime for hand edits render never
# sees. Lower-cased before comparison: 'None' and 'NONE' are the same mistake.
SENTINELS = frozenset({"", "none", "n/a", "na", "todo", "tbd", "unknown"})


class RenderError(Exception):
    """Raised for every refusal. The message is user-facing."""


def load_parameters(path):
    """Read the parameter registry -- the enumerable set of what must be resolved.

    Setup is an agent following an instruction file, and that instruction can
    only be written against a closed set. A set discovered by grepping the
    payload for placeholders is not a specification: a parameter added later
    without being registered is one the setup skill will not know to resolve.
    """
    try:
        with open(path, encoding="utf-8") as handle:
            raw = json.load(handle)
    except FileNotFoundError:
        raise RenderError(
            "parameter registry not found: %s\n"
            "Every parameter must be declared before it can be rendered." % path
        )
    except ValueError as exc:
        raise RenderError("parameter registry is not valid JSON: %s\n  %s" % (path, exc))

    params = raw.get("parameters")
    if not isinstance(params, dict):
        raise RenderError("parameter registry %s has no 'parameters' object." % path)

    for name, spec in params.items():
        if not PLACEHOLDER.fullmatch("@%s@" % name):
            raise RenderError(
                "parameter name %r is not upper snake case, so no placeholder "
                "can reference it." % name
            )
        tier = spec.get("tier")
        if tier not in TIERS:
            raise RenderError(
                "parameter %s has tier %r; expected one of %s."
                % (name, tier, ", ".join(TIERS))
            )
        if tier == TIER_REQUIRED and "default" in spec:
            # The whole point of the required tier is that no honest default
            # exists. One that carries a value is either mis-tiered or is the
            # origin repository's value in disguise.
            raise RenderError(
                "parameter %s is tier 'required' but ships a default (%r).\n"
                "A required parameter is one for which no honest default "
                "exists. If a default is honest, tier it 'defaulted'."
                % (name, spec["default"])
            )
        if tier == TIER_DEFAULTED and "default" not in spec:
            raise RenderError(
                "parameter %s is tier 'defaulted' but ships no default." % name
            )

    # `guards` is the machine-readable link from a group switch to the names it
    # makes inert. It exists so validate_invariants() can check the pairing
    # without hard-coding which switch owns which parameter -- the registry is
    # the authority, and a switch added without its guards would otherwise be a
    # switch nothing checks.
    for name, spec in params.items():
        guarded = spec.get("guards")
        if guarded is None:
            continue
        if not isinstance(guarded, list) or not all(
            isinstance(item, str) for item in guarded
        ):
            raise RenderError(
                "parameter %s declares 'guards' that is not a list of names." % name
            )
        if spec.get("tier") != TIER_DEFAULTED:
            raise RenderError(
                "parameter %s declares 'guards' but is not tier 'defaulted'.\n"
                "Only a group switch guards a name." % name
            )
        for target in guarded:
            if target not in params:
                raise RenderError(
                    "parameter %s guards %s, which the registry does not declare."
                    % (name, target)
                )
    return params


def switch_value(name, spec, supplied):
    """The value a group switch holds, whether or not this template needs it.

    `resolve` is scoped to one template's placeholders, so a file that uses a
    guarded NAME without also using its SWITCH would leave the pair
    unvalidated. The switch is defaulted by construction, so its value is
    always knowable: what was supplied, else the registry default.
    """
    return str(supplied.get(name, spec.get("default", ""))).strip().lower()


def validate_invariants(params, supplied, values):
    """Refuse a switch and the names it guards that disagree.

    Two directions, and only one of them is dangerous:

        switch TRUE + sentinel name  -- the rule runs, matches nothing, and
                                        reports SUCCESS over the code it was
                                        pointed at. This is the false green.

        switch FALSE + real name     -- the rule does not run. Harmless today,
                                        refused anyway: it means somebody
                                        answered the name and then switched the
                                        group off, and one of those two was not
                                        what they meant.

    Raises rather than warns, and is called before anything is written, so a
    disagreement leaves no file behind to be found later and trusted.
    """
    problems = []
    for switch, spec in params.items():
        guarded = spec.get("guards")
        if not guarded:
            continue
        active = switch_value(switch, spec, supplied) == "true"
        for target in guarded:
            if target not in values:
                continue  # this template does not reference the name
            given = str(values[target]).strip()
            inert = given.lower() in SENTINELS
            if active and inert:
                problems.append(
                    "  %s = %r while %s is true.\n"
                    "    %s is the name of a declaration in YOUR repository. A\n"
                    "    placeholder passes every check the rule can make, so the\n"
                    "    rule would run, match nothing, and report success over the\n"
                    "    code it was pointed at.\n"
                    "    Either give %s a real name, or set %s to false."
                    % (target, given, switch, target, target, switch)
                )
            elif not active and not inert:
                problems.append(
                    "  %s = %r while %s is false.\n"
                    "    The rules that read %s never run, so this name has no\n"
                    "    effect. One of the two answers is not what was meant.\n"
                    "    Either set %s to true, or set %s to 'none'."
                    % (target, given, switch, target, switch, target)
                )
    if problems:
        raise RenderError(
            "a group switch and the name it guards disagree:\n\n%s\n\n"
            "Nothing was written. Every parameter is listed by\n"
            "`python3 .prism/verify/lib/render.py --list-all`."
            % "\n\n".join(problems)
        )


def resolve(params, supplied, needed=None):
    """Build the value map, or report precisely which values are missing.

    `needed` scopes the check to the placeholders one template actually uses.
    Without it, re-rendering a single file demands every required parameter in
    the registry -- so a consumer who added a module and wanted their package
    map regenerated would be asked for values that file does not reference.
    Re-rendering one generated file is the documented update path, and it has
    to work without re-running setup.

    THE THIRD RETURN VALUE IS WHICH NAMES FELL BACK TO A DEFAULT. Without it,
    "the installer considered this switch and affirmed true" and "nobody ever
    read the question" produce byte-identical output and an identical
    provenance manifest. Ten installs of one repository produced four different
    rule sets, and nothing anywhere recorded which of the five group switches
    had actually been decided. A default is a legitimate answer; a default
    nobody knows was taken is not an answer at all.
    """
    values = {}
    missing = []
    defaulted = []
    for name, spec in params.items():
        if needed is not None and name not in needed:
            continue
        if name in supplied:
            values[name] = supplied[name]
        elif spec.get("tier") == TIER_DEFAULTED:
            values[name] = spec["default"]
            defaulted.append(name)
        elif spec.get("tier") == TIER_LITERAL:
            continue
        else:
            missing.append(name)
    return values, missing, defaulted


def substitute(text, values, template_name):
    """Substitute every placeholder, or refuse and name what was unresolved.

    Refusing is the point. Returning the text with one placeholder left in it
    would produce a file that looks finished and is not.
    """
    unresolved = []

    def replace(match):
        name = match.group(1)
        if name in values:
            return str(values[name])
        unresolved.append(name)
        return match.group(0)

    rendered = PLACEHOLDER.sub(replace, text)
    if unresolved:
        ordered = sorted(set(unresolved))
        raise RenderError(
            "unresolved placeholder%s in %s: %s\n"
            "Nothing was written. Supply %s, or correct the template."
            % (
                "" if len(ordered) == 1 else "s",
                template_name,
                ", ".join("@%s@" % n for n in ordered),
                "a value for it" if len(ordered) == 1 else "values for them",
            )
        )
    return rendered


def sha256_of(text):
    import hashlib

    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def write_atomically(path, text):
    """Write via a temporary file in the same directory, then replace.

    A render that dies midway must leave the previous file intact rather than a
    truncated one. A half-written gate script or a half-written package map is
    worse than no file at all, because it looks like a file.
    """
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    temporary = os.path.join(directory, ".%s.prism-tmp" % os.path.basename(path))
    try:
        with open(temporary, "w", encoding="utf-8") as handle:
            handle.write(text)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)


def record_provenance(manifest_path, output_path, template_path, values, digest):
    """Record what a rendered file was made from.

    Without the template and the values, "did the consumer edit this generated
    file, or did the generator's input change?" is unanswerable, and the update
    path can only guess. Those two need opposite responses: one is regenerate,
    the other is stop and report. The hash alone distinguishes changed from
    unchanged; it takes the template and the values to distinguish WHY.
    """
    manifest = {"files": {}}
    if os.path.exists(manifest_path):
        try:
            with open(manifest_path, encoding="utf-8") as handle:
                manifest = json.load(handle)
        except ValueError as exc:
            raise RenderError(
                "manifest is not valid JSON: %s\n  %s" % (manifest_path, exc)
            )
    manifest.setdefault("files", {})
    manifest["files"][output_path] = {
        "template": template_path,
        "values": values,
        "sha256": digest,
    }
    write_atomically(manifest_path, json.dumps(manifest, indent=2, sort_keys=True) + "\n")


def command_render(args, params):
    supplied = {}
    if args.values:
        try:
            with open(args.values, encoding="utf-8") as handle:
                supplied = json.load(handle)
        except FileNotFoundError:
            raise RenderError("values file not found: %s" % args.values)
        except ValueError as exc:
            raise RenderError(
                "values file is not valid JSON: %s\n  %s" % (args.values, exc)
            )
        if not isinstance(supplied, dict):
            raise RenderError("values file must contain a JSON object: %s" % args.values)

    unknown = sorted(set(supplied) - set(params))
    if unknown:
        raise RenderError(
            "values file supplies %s that the registry does not declare: %s\n"
            "Declare it in %s, or remove it."
            % (
                "a parameter" if len(unknown) == 1 else "parameters",
                ", ".join(unknown),
                args.parameters,
            )
        )

    try:
        with open(args.template, encoding="utf-8") as handle:
            text = handle.read()
    except FileNotFoundError:
        raise RenderError("template not found: %s" % args.template)

    needed = set(PLACEHOLDER.findall(text))
    values, missing, defaulted = resolve(params, supplied, needed)
    if missing:
        ordered = sorted(missing)
        raise RenderError(
            "required parameter%s not resolved: %s\n"
            "%s no honest default, so %s must be discovered against the target\n"
            "repository before this template can be rendered. Nothing was written."
            % (
                "" if len(ordered) == 1 else "s",
                ", ".join(ordered),
                "It has" if len(ordered) == 1 else "They have",
                "it" if len(ordered) == 1 else "they",
            )
        )

    validate_invariants(params, supplied, values)

    rendered = substitute(text, values, args.template)
    used = {name: values[name] for name in sorted(set(PLACEHOLDER.findall(text)))}
    write_atomically(args.output, rendered)

    # Reported on stderr rather than refused. Refusing would break the
    # documented single-file re-render, and most defaults are right most of the
    # time. What must not happen is the value passing unremarked: this is the
    # only moment anybody learns that a switch deciding eleven rules was never
    # actually answered.
    if defaulted:
        sys.stderr.write(
            "render: %d value%s not supplied, took the registry default:\n"
            % (len(defaulted), "" if len(defaulted) == 1 else "s")
        )
        for name in sorted(defaulted):
            sys.stderr.write("  %s = %s\n" % (name, values[name]))
        sys.stderr.write(
            "  Supply them in --values to put the decision on the record.\n"
        )
    if args.manifest:
        record_provenance(
            args.manifest, args.output, args.template, used, sha256_of(rendered)
        )

    # AFTER the write, and only here. The install used to do this in the shell,
    # as `render ... && rm -f "$K/$f.kt.in"` inside a loop, and it had two
    # problems. It stalled 5 of 5 measured installs, because a variable path in
    # an `rm` is exactly what an agent's dangerous-command guard is built to
    # stop -- and the guard was RIGHT: a piped variant of that same line, where
    # the pipeline's status replaced render's, deleted three templates having
    # rendered nothing. Here there is no shell, no pipeline and no variable: the
    # code that knows the render succeeded is the code that removes the input.
    if args.consume:
        try:
            os.unlink(args.template)
        except OSError as error:
            sys.stderr.write("render: rendered %s but could not remove %s: %s\n"
                             % (args.output, args.template, error))
            return 1
    return 0


def _print_param(name, spec, show_default=False):
    print("  @%s@" % name)
    if show_default:
        print("    default:   %s" % spec.get("default"))
    print("    means:     %s" % spec.get("description", "(undocumented)"))
    consumers = spec.get("templates") or []
    print("    templates: %s" % (", ".join(consumers) if consumers else "(none declared)"))
    print("")


def command_list_required(params):
    """Print the required tier without a target repository present."""
    required = {n: s for n, s in params.items() if s.get("tier") == TIER_REQUIRED}
    if not required:
        print("No required parameters.")
        return 0
    print("Required parameters -- these MUST be resolved against the target repository:\n")
    for name in sorted(required):
        _print_param(name, required[name])
    print("There are also DEFAULTED parameters, which render silently if you never")
    print("consider them. See:  render.py --list-all")
    return 0


def command_list_all(params):
    """Print every parameter, defaulted ones included.

    --list-required is the surface SETUP.md tells the installer to consult, and
    it prints the required tier alone. The five rule-group switches are
    defaulted, so they were invisible to the one tool an installer would run to
    discover what to answer -- and SETUP.md had to carry a table warning that
    they exist. A question nobody is shown is a question nobody answers.
    """
    required = {n: s for n, s in params.items() if s.get("tier") == TIER_REQUIRED}
    defaulted = {n: s for n, s in params.items() if s.get("tier") == TIER_DEFAULTED}

    print("Required -- no honest default exists; setup MUST resolve these:\n")
    for name in sorted(required):
        _print_param(name, required[name])

    print("Defaulted -- these render WITHOUT being asked. The default is a real")
    print("answer, but only when somebody chose it:\n")
    for name in sorted(defaulted):
        _print_param(name, defaulted[name], show_default=True)
    return 0


def command_check(args, params):
    """Fail when a template uses a placeholder the registry does not declare.

    This keeps the enumerable set honest. A placeholder added to a template
    without a registry entry renders fine here and is invisible to the setup
    skill, which resolves the set it was given.
    """
    problems = []
    for root, _dirs, files in os.walk(args.templates_dir):
        for name in sorted(files):
            if not name.endswith(".in"):
                continue
            path = os.path.join(root, name)
            with open(path, encoding="utf-8") as handle:
                found = set(PLACEHOLDER.findall(handle.read()))
            for placeholder in sorted(found - set(params)):
                problems.append((path, placeholder))
    if problems:
        for path, placeholder in problems:
            sys.stderr.write("undeclared placeholder @%s@ in %s\n" % (placeholder, path))
        sys.stderr.write(
            "\n%d undeclared placeholder%s. Every parameter must appear in the\n"
            "registry so the setup skill knows it has to be resolved.\n"
            % (len(problems), "" if len(problems) == 1 else "s")
        )
        return 1
    print("All placeholders are declared.")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--parameters",
        default=DEFAULT_PARAMETERS,
        help="the parameter registry (default: the payload's own "
             "core/templates/parameters.json)",
    )
    parser.add_argument("--template", help="template file to render")
    parser.add_argument("--output", help="where to write the rendered file")
    parser.add_argument("--values", help="JSON file of resolved parameter values")
    parser.add_argument("--manifest", help="manifest to record provenance into")
    parser.add_argument(
        "--consume",
        action="store_true",
        help="delete --template after a successful render. For the konsist "
             "templates, which live beside the sources they become and would "
             "otherwise be copied into the consumer's tooling/ as .kt.in files.",
    )
    parser.add_argument(
        "--templates-dir",
        default=DEFAULT_TEMPLATES_DIR,
        help="directory --check walks (default: the payload's own core/). "
             "The whole payload, "
             "not just core/templates: a template lives beside the file it "
             "renders, so ModulePackageRoots.kt.in sits in the konsist source "
             "tree rather than in a templates directory of its own.",
    )
    parser.add_argument(
        "--list-required",
        action="store_true",
        help="print every required parameter and exit; needs no target repository",
    )
    parser.add_argument(
        "--list-all",
        action="store_true",
        help="print every parameter including the defaulted ones, and their defaults",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if any template uses a placeholder the registry does not declare",
    )
    args = parser.parse_args(argv)

    try:
        params = load_parameters(args.parameters)
        if args.list_required:
            return command_list_required(params)
        if args.list_all:
            return command_list_all(params)
        if args.check:
            return command_check(args, params)
        if not args.template or not args.output:
            parser.error("--template and --output are required to render")
        return command_render(args, params)
    except RenderError as exc:
        sys.stderr.write("prism render: %s\n" % exc)
        return 1


if __name__ == "__main__":
    sys.exit(main())

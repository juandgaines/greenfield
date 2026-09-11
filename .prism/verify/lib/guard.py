#!/usr/bin/env python3
"""A push may not both need a weakening and contain it.

WHAT THIS IS FOR. PRISM's gates are configured by files in the repository the
agent is editing. Before this, an agent blocked by a rule could set
`active: false` in detekt.yml and NOTHING NOTICED -- no gate compared any
configuration against anything. That hole predates per-module posture and is
wider than it: R7 forbids a config switch that softens a gate, and then R7's
own permissive half grants rule-disabling as ordinary detekt mechanics.

So this compares the configuration in the working tree against the same
configuration on the BASE BRANCH, and denies when it got weaker. Promotion is
never checked; only weakening is.

WHAT IT IS NOT. Tamper-EVIDENT, not tamper-PROOF, and the documentation must
say so. Every PRISM gate lives in a file the agent can write, including this
one. What the guard buys is that weakening now costs a separate commit that a
human sees, with a one-line diff whose entire purpose is visible -- instead of
one line buried in a 450-line YAML file inside an unrelated change. Nothing
local can do better than that; only CI evaluating config it checks out itself
can, and that is deliberately out of PRISM's scope.

DEMOTION IS STILL ALLOWED. It just cannot ride along with the work that needed
it. Make it its own commit, merge it to the base branch, and every later push
agrees.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scope  # noqa: E402

DETEKT_YML = "detekt.yml"
EDITORCONFIG = ".editorconfig"
THRESHOLDS = ".prism/verify/thresholds.json"
SCOPE = ".prism/scope.json"
PRISM_JSON = ".prism/prism.json"
DETEKT_BASELINE = "detekt.baseline.xml"
KTLINT_BASELINE = "ktlint.baseline.xml"


def at_base(root, base, path):
    """The file's content on the base branch, or None when it was absent."""
    try:
        return subprocess.run(
            ["git", "show", "%s:%s" % (base, path)],
            cwd=root, capture_output=True, check=True,
        ).stdout.decode("utf-8", "replace")
    except (subprocess.CalledProcessError, OSError):
        return None


def in_tree(root, path):
    full = os.path.join(root, path)
    if not os.path.exists(full):
        return None
    with open(full, encoding="utf-8", errors="replace") as handle:
        return handle.read()


# --- detekt.yml -----------------------------------------------------------
# Parsed by shape rather than with a YAML library: the payload is stdlib-only,
# and what is needed is two flat facts per rule -- is it active, and what does
# it exclude.
RULE = re.compile(r"^  ([A-Z][A-Za-z0-9]*):\s*$")
ACTIVE = re.compile(r"^    active:\s*(\S+)\s*$")
EXCLUDES = re.compile(r"^    excludes:\s*(.+?)\s*$")


def detekt_rules(text):
    rules, current = {}, None
    for line in (text or "").split("\n"):
        found = RULE.match(line)
        if found:
            current = found.group(1)
            rules.setdefault(current, {"active": None, "excludes": ""})
            continue
        if current is None:
            continue
        found = ACTIVE.match(line)
        if found:
            rules[current]["active"] = found.group(1)
        found = EXCLUDES.match(line)
        if found:
            rules[current]["excludes"] = found.group(1)
    return rules


def check_detekt(before, after):
    findings = []
    old, new = detekt_rules(before), detekt_rules(after)
    for rule, was in old.items():
        now = new.get(rule)
        if now is None:
            findings.append("detekt.yml: rule %s was REMOVED" % rule)
            continue
        if was["active"] == "true" and now["active"] == "false":
            findings.append("detekt.yml: %s was switched active: true -> false" % rule)
        if now["excludes"] != was["excludes"] and len(now["excludes"]) > len(was["excludes"]):
            findings.append(
                "detekt.yml: %s gained exclusions\n      was: %s\n      now: %s"
                % (rule, was["excludes"] or "(none)", now["excludes"]))
    return findings


# --- .editorconfig --------------------------------------------------------
# HYPHENS. Real ktlint properties are `ktlint_standard_function-naming`, and
# an earlier character class of [A-Za-z0-9_] matched none of them -- the guard
# silently passed the exact edit it exists to catch. Found by trying the
# bypass rather than by reading the regex.
DISABLED = re.compile(r"^\s*(ktlint_[A-Za-z0-9_-]+)\s*=\s*(disabled|false)\s*$")


def disabled_rules(text):
    return {m.group(1) for m in
            (DISABLED.match(line) for line in (text or "").split("\n")) if m}


def check_editorconfig(before, after):
    gained = disabled_rules(after) - disabled_rules(before)
    return ["%s: %s was set to disabled" % (EDITORCONFIG, name)
            for name in sorted(gained)]


# --- thresholds.json ------------------------------------------------------
def check_thresholds(before, after):
    findings = []
    try:
        old = json.loads(before) if before else {}
        new = json.loads(after) if after else {}
    except ValueError:
        return ["%s: cannot be parsed" % THRESHOLDS]
    if new.get("default", 80) < old.get("default", 80):
        findings.append("%s: default lowered %s -> %s"
                        % (THRESHOLDS, old.get("default"), new.get("default")))
    old_over, new_over = old.get("overrides", {}), new.get("overrides", {})
    for module, was in old_over.items():
        now = new_over.get(module)
        if now is None:
            findings.append("%s: override for %s was removed (was %s)"
                            % (THRESHOLDS, module, was))
        elif now < was:
            findings.append("%s: %s lowered %s -> %s" % (THRESHOLDS, module, was, now))
    return findings


# --- scope.json -----------------------------------------------------------
def check_scope(before, after):
    findings = []
    if before is None and after is not None:
        return ["%s: appeared. No scope file means every engine enforces "
                "everywhere, so adding one can only reduce what blocks.\n"
                "      If this is deliberate, land it on the base branch "
                "first." % SCOPE]
    if before is None:
        return findings
    try:
        old_default, old_modules = _parse_scope(before)
        new_default, new_modules = _parse_scope(after) if after is not None else (
            dict(scope.ENFORCE_ALL), {})
    except scope.ScopeError as error:
        return ["%s: %s" % (SCOPE, error)]

    modules = set(old_modules) | set(new_modules)
    for engine in scope.ENGINES:
        if old_default.get(engine) == "enforce" and new_default.get(engine) == "observe":
            findings.append("%s: default.%s was demoted enforce -> observe"
                            % (SCOPE, engine))
        for module in sorted(modules):
            was = old_modules.get(module, {}).get(engine, old_default[engine])
            now = new_modules.get(module, {}).get(engine, new_default[engine])
            if was == "enforce" and now == "observe":
                findings.append("%s: %s %s was demoted enforce -> observe"
                                % (SCOPE, module, engine))
    return findings


def _parse_scope(text):
    raw = json.loads(text)
    default = dict(scope.ENFORCE_ALL)
    default.update(scope._postures(raw.get("default", {}), "default"))
    modules = {k: scope._postures(v, k) for k, v in raw.get("modules", {}).items()}
    return default, modules


# --- baselines ------------------------------------------------------------
def baseline_size(text):
    if not text:
        return 0
    try:
        return len(list(ET.fromstring(text).iter())) - 1
    except ET.ParseError:
        return 0


def check_baseline(path, before, after):
    was, now = baseline_size(before), baseline_size(after)
    if now > was:
        return ["%s: grew from %d suppressed entries to %d.\n"
                "      A baseline records what existed at install. Growing it "
                "absorbs work written since." % (path, was, now)]
    return []


def baseline_paths(root):
    """Every baseline in the tree, both engines, as repository-relative paths.

    The detekt baseline used to be ONE file at the root and was named as a
    literal here. Since 0.6.0 it is one per module, like ktlint's has always
    been -- an id carries the bare filename, so a single root file could not
    tell two modules with a same-named file apart and recorded their two
    findings as one entry. Walking for both names means a module that grows a
    baseline is watched from the moment it has one, with nothing to keep in step.
    """
    found = []
    for directory, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle")]
        for name in (DETEKT_BASELINE, KTLINT_BASELINE):
            if name in names:
                found.append(os.path.relpath(os.path.join(directory, name), root))
    return sorted(found)


def installed_at_base(root, base):
    """Is PRISM on the base branch at all?

    Everything below asks "did this push make an EXISTING protection weaker".
    That question has no meaning before there is an existing protection. On the
    branch that installs PRISM, the base branch has no scope file, no ktlint
    properties and no baselines, so every single check fires -- on the payload's
    own shipped files -- and the first push after any install is refused, with
    advice ("land it on the base branch first") that needs the push it just
    denied. The install is not a weakening of anything; it is the arrival of the
    thing later pushes are measured against.
    """
    return at_base(root, base, PRISM_JSON) is not None


def run(root, base):
    if not installed_at_base(root, base):
        return []
    findings = []
    findings += check_scope(at_base(root, base, SCOPE), in_tree(root, SCOPE))
    findings += check_detekt(at_base(root, base, DETEKT_YML), in_tree(root, DETEKT_YML))
    findings += check_editorconfig(at_base(root, base, EDITORCONFIG),
                                   in_tree(root, EDITORCONFIG))
    findings += check_thresholds(at_base(root, base, THRESHOLDS),
                                 in_tree(root, THRESHOLDS))
    for path in baseline_paths(root):
        findings += check_baseline(path, at_base(root, base, path), in_tree(root, path))
    return findings


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--base", required=True)
    args = parser.parse_args(argv)

    if not installed_at_base(args.root, args.base):
        print("prism: %s does not carry a PRISM install yet, so there is no "
              "configuration to weaken." % args.base)
        print("       This push is the install. Later pushes are compared "
              "against what it lands.")
        return 0

    findings = run(args.root, args.base)
    if not findings:
        return 0
    sys.stderr.write(
        "prism: this push weakens PRISM's own configuration relative to %s.\n\n"
        % args.base)
    for finding in findings:
        sys.stderr.write("  - %s\n" % finding)
    sys.stderr.write(
        "\n  A push may not both need a weakening and contain it.\n"
        "  If the change is deliberate, land it on %s as its own commit first,\n"
        "  where the diff says plainly what protection is being given up.\n"
        % args.base)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

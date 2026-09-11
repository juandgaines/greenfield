#!/usr/bin/env python3
"""Which engines block, in which modules.

THE FILE IS OPTIONAL AND ITS ABSENCE IS NOT NEUTRAL. No `.prism/scope.json`
means every engine enforces in every module. That is the state a repository
REACHES: work the baselines to zero, delete the file, and every engine blocks
everywhere. So a missing file is the STRICT reading, never the permissive one,
and a scope file that fails to load must not quietly become "nothing blocks".

  {
    "default": { "detekt": "observe", "ktlint": "observe",
                 "konsist": "observe", "coverage": "observe" },
    "modules": { ":core:domain": { "detekt": "enforce" } }
  }

A module entry is a PARTIAL override -- engines it does not name fall back to
`default`. That keeps promotion to one line, which matters, because a promotion
somebody has to hand-write four times is a promotion they batch up and stop
doing.

WHAT `observe` MEANS, EXACTLY. The engine runs, produces its reports, and does
not fail the build. It does not mean the rules are off, it does not mean the
findings are hidden, and it is not a place to leave things forever: the point
of recording it per module is that `prism status` can say how long it has been
there.

Fails closed, on the same terms as lib/config.sh: an unreadable or invalid
scope file makes the caller DENY and say why, rather than fall back to a
built-in. A file nobody can parse is the one case where guessing is worst.
"""

import argparse
import json
import os
import sys

ENGINES = ("detekt", "ktlint", "konsist", "coverage")
POSTURES = ("enforce", "observe")
ENFORCE_ALL = dict.fromkeys(ENGINES, "enforce")

SCOPE_FILE = os.path.join(".prism", "scope.json")


class ScopeError(Exception):
    """Raised for a scope file that exists and cannot be trusted."""


def load(root="."):
    """Return (default, modules). Absent file == everything enforces."""
    path = os.path.join(root, SCOPE_FILE)
    if not os.path.exists(path):
        return dict(ENFORCE_ALL), {}
    try:
        with open(path, encoding="utf-8") as handle:
            raw = json.load(handle)
    except (OSError, ValueError) as error:
        raise ScopeError("%s cannot be read: %s" % (SCOPE_FILE, error))
    if not isinstance(raw, dict):
        raise ScopeError("%s must be a JSON object" % SCOPE_FILE)

    default = dict(ENFORCE_ALL)
    default.update(_postures(raw.get("default", {}), "default"))

    modules = {}
    supplied = raw.get("modules", {})
    if not isinstance(supplied, dict):
        raise ScopeError("%s: 'modules' must be an object" % SCOPE_FILE)
    for module, entry in supplied.items():
        if not module.startswith(":"):
            raise ScopeError(
                "%s: module %r must be a Gradle path starting with ':'"
                % (SCOPE_FILE, module))
        modules[module] = _postures(entry, module)
    return default, modules


def _postures(entry, where):
    """Validate one {engine: posture} object. Unknown keys are refused.

    Refused rather than ignored: a typo'd engine name that is silently dropped
    reads in the file as though it were in force, which is the exact shape of
    every silent green this framework exists to prevent.
    """
    if not isinstance(entry, dict):
        raise ScopeError("%s: %s must be an object" % (SCOPE_FILE, where))
    out = {}
    for engine, posture in entry.items():
        if engine not in ENGINES:
            raise ScopeError(
                "%s: %s names engine %r -- known engines are %s"
                % (SCOPE_FILE, where, engine, ", ".join(ENGINES)))
        if posture not in POSTURES:
            raise ScopeError(
                "%s: %s.%s is %r -- must be 'enforce' or 'observe'"
                % (SCOPE_FILE, where, engine, posture))
        out[engine] = posture
    return out


def posture_for(module, engine, root="."):
    default, modules = load(root)
    return modules.get(module, {}).get(engine, default[engine])


def matrix(modules_wanted, root="."):
    """Resolved posture for every (module, engine). The reporting shape."""
    default, modules = load(root)
    return {
        module: {
            engine: modules.get(module, {}).get(engine, default[engine])
            for engine in ENGINES
        }
        for module in modules_wanted
    }


def enforcing(module, root="."):
    default, modules = load(root)
    return [engine for engine in ENGINES
            if modules.get(module, {}).get(engine, default[engine]) == "enforce"]


CREATED_COMMENT = [
    "Which engines BLOCK, per module. Read live on every run; nothing is",
    "regenerated from it. Edit it directly.",
    "",
    "ITS ABSENCE IS NOT NEUTRAL. With no scope file every engine enforces in",
    "every module, so DELETING this file is how you finish adopting, not how",
    "you opt out. The push guard refuses to let it reappear.",
    "",
    "  observe   the engine runs, writes its reports, and does not fail the",
    "            build. The rules are NOT off and the findings are NOT hidden.",
    "  enforce   findings fail the build.",
    "",
    "AN ENGINE THIS FILE DOES NOT NAME ENFORCES. Every key here is therefore a",
    "deliberate exception, and the shortest honest file is the best one. That is",
    "why step 8b writes only the engines that cannot enforce yet: a repository",
    "whose findings can be recorded in a baseline starts BLOCKING on day one,",
    "with today's findings quiet and a new violation refused.",
    "",
    "Written by verify/lib/scope.py. `./prism status` reports what it resolves.",
]


def _write(root, default, modules, comment):
    """Atomic write, so an interrupted set cannot leave a half-parsed gate."""
    path = os.path.join(root, SCOPE_FILE)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    payload = {}
    if comment:
        payload["$comment"] = comment
    payload["default"] = default
    payload["modules"] = modules
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")
    os.replace(temp, path)


def set_posture(root, engine, posture, module=None):
    """Set one engine's posture, for one module or for `default`.

    Returns one of PLACED / UPDATED / UNCHANGED, in place.py's vocabulary,
    because an installer that cannot tell those apart cannot report what it did.

    Only the engine named is written. Engines this file never mentions resolve
    to enforce through load()'s ENFORCE_ALL seed, so setting `konsist observe`
    on a repository with no scope file leaves the other three BLOCKING rather
    than silently demoting them -- the opposite of copying an all-observe
    template, which is what 0.6.0 did and why no new project could enforce.
    """
    path = os.path.join(root, SCOPE_FILE)
    existed = os.path.exists(path)
    raw = {}
    if existed:
        try:
            with open(path, encoding="utf-8") as handle:
                raw = json.load(handle)
        except (OSError, ValueError) as error:
            raise ScopeError("%s cannot be read: %s" % (SCOPE_FILE, error))
        if not isinstance(raw, dict):
            raise ScopeError("%s must be a JSON object" % SCOPE_FILE)

    # Validate what is already there before adding to it: writing into a file
    # that does not parse would turn one bad key into two.
    default = raw.get("default", {})
    modules_now = raw.get("modules", {})
    if not isinstance(modules_now, dict):
        raise ScopeError("%s: 'modules' must be an object" % SCOPE_FILE)
    _postures(default, "default")
    for name, entry in modules_now.items():
        _postures(entry, name)

    if module is None:
        target = default
        where = "default"
    else:
        if not module.startswith(":"):
            raise ScopeError(
                "module %r must be a Gradle path starting with ':'" % module)
        target = modules_now.setdefault(module, {})
        where = module

    before = target.get(engine)
    if before == posture:
        return "UNCHANGED  %s %s is already %s" % (where, engine, posture)
    target[engine] = posture

    comment = raw.get("$comment", CREATED_COMMENT if not existed else None)
    _write(root, default, modules_now, comment)
    if not existed:
        return "PLACED     %s (%s %s = %s); every engine it does not name enforces" % (
            SCOPE_FILE, where, engine, posture)
    return "UPDATED    %s %s: %s -> %s" % (
        where, engine, before or "enforce (unnamed)", posture)


def init(root, observe):
    """Step 8b's whole write, in one idempotent call.

    `observe` is the engines that CANNOT enforce yet. Everything else is left
    unnamed, and unnamed means enforce.

    IDEMPOTENT BY CONSTRUCTION, and that is the point rather than a nicety. The
    file it would write is the record of every promotion anybody has made, so a
    second install run that rewrote it would demote the repository silently --
    the exact loss phase 2 was built to stop, which happened because step 8b
    used to `cp` a template. Here an existing file is KEPT and nothing is
    touched. One call, so there is no window in which half the engines are set.

    Empty `observe` writes NO FILE: absence is how enforce is installed, and
    writing an all-enforce file instead would be a file the guard then refuses
    to let anyone delete.
    """
    # Validate BEFORE the existence check. A typo'd engine name is a defect in
    # the caller either way, and reporting KEPT over it would hide the typo on
    # every re-install -- the one path where nobody looks closely.
    unknown = [e for e in observe if e not in ENGINES]
    if unknown:
        raise ScopeError("not a PRISM engine: %s (known: %s)"
                         % (", ".join(unknown), ", ".join(ENGINES)))

    path = os.path.join(root, SCOPE_FILE)
    if os.path.exists(path):
        return "KEPT       %s already exists and is yours -- what blocks is unchanged" % SCOPE_FILE

    if not observe:
        return ("NONE       no %s written -- every engine enforces in every "
                "module, which is what a clean repository installs as" % SCOPE_FILE)

    ordered = [e for e in ENGINES if e in observe]
    _write(root, dict.fromkeys(ordered, "observe"), {}, CREATED_COMMENT)
    enforcing = [e for e in ENGINES if e not in observe]
    return "PLACED     %s -- observe: %s | enforce: %s" % (
        SCOPE_FILE, " ".join(ordered),
        " ".join(enforcing) if enforcing else "(none)")


def _modules_from_settings(root):
    """The consumer's modules, minus PRISM's own two."""
    path = os.path.join(root, "settings.gradle.kts")
    found = []
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if not line.startswith("include("):
                    continue
                start = line.find('"')
                end = line.find('"', start + 1)
                if start != -1 and end != -1:
                    found.append(line[start + 1:end])
    except OSError:
        return []
    return [m for m in found if m not in (":tooling:prism-rules", ":tooling:konsist")]


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",
                        choices=["show", "get", "check", "modules", "set", "init"])
    parser.add_argument("--root", default=".")
    parser.add_argument("--module")
    parser.add_argument("--engine", choices=ENGINES)
    parser.add_argument("--posture", choices=POSTURES)
    parser.add_argument("--default", action="store_true",
                        help="set the repository-wide default rather than a module")
    parser.add_argument("--observe", default="",
                        help="init: comma-separated engines that cannot enforce yet")
    args = parser.parse_args(argv)

    # `init` is step 8b's write. Like `set`, it must work with no file present,
    # so it runs before load().
    if args.command == "init":
        wanted = [e.strip() for e in args.observe.split(",") if e.strip()]
        try:
            print(init(args.root, wanted))
        except ScopeError as error:
            sys.stderr.write("prism scope: %s\n" % error)
            return 2
        return 0

    # `set` comes first because it is the one command that must work when the
    # file does not exist yet, and load() is not what decides that.
    if args.command == "set":
        if not args.engine or not args.posture:
            sys.stderr.write("set needs --engine and --posture\n")
            return 2
        if bool(args.module) == bool(args.default):
            sys.stderr.write("set needs exactly one of --module or --default\n")
            return 2
        try:
            print(set_posture(args.root, args.engine, args.posture,
                              None if args.default else args.module))
        except ScopeError as error:
            sys.stderr.write("prism scope: %s\n" % error)
            return 2
        return 0

    try:
        default, modules = load(args.root)
    except ScopeError as error:
        sys.stderr.write("prism scope: %s\n" % error)
        return 2

    if args.command == "check":
        declared = set(_modules_from_settings(args.root))
        dead = sorted(set(modules) - declared) if declared else []
        for module in dead:
            sys.stderr.write(
                "note: %s names %s, which settings.gradle.kts does not declare\n"
                % (SCOPE_FILE, module))
        print("scope ok -- %d module override(s), %d dead" % (len(modules), len(dead)))
        return 0

    if args.command == "get":
        if not args.module or not args.engine:
            sys.stderr.write("get needs --module and --engine\n")
            return 2
        print(modules.get(args.module, {}).get(args.engine, default[args.engine]))
        return 0

    if args.command == "modules":
        for module in _modules_from_settings(args.root):
            print(module)
        return 0

    wanted = _modules_from_settings(args.root) or sorted(modules)
    resolved = matrix(wanted, args.root)
    if not os.path.exists(os.path.join(args.root, SCOPE_FILE)):
        print("no %s -- every engine enforces in every module\n" % SCOPE_FILE)
    print("%-34s %s" % ("MODULE", "  ".join("%-8s" % e for e in ENGINES)))
    for module in wanted:
        row = resolved[module]
        print("%-34s %s" % (module, "  ".join("%-8s" % row[e] for e in ENGINES)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

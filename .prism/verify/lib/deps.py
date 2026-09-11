#!/usr/bin/env python3
"""Reverse module dependency closure, read from the Gradle build files.

Both gates used to verify only the modules whose files changed. Gradle
builds a module's DEPENDENCIES, never its dependents, so changing a
signature in `:core:domain` left `:feature:auth:presentation` broken while
`:core:domain:build` passed and the gate went green. That is the opposite
of Principle VII gate 2 ("all affected modules build"), which is about the
modules a change REACHES, not the one that was edited.

The graph is derived, never hardcoded: `settings.gradle.kts` says which
modules exist, and each module's `build.gradle.kts` says which others it
consumes via type-safe project accessors (`implementation(projects.core.
designSystem)`). The accessor spelling is camelCase per segment, so
`:core:design-system` is written `projects.core.designSystem` and
`:core:files-db` is `projects.core.filesDb`; `_accessor_for` reproduces
that transformation so the mapping back to a Gradle path is exact rather
than guessed.

androidTest/test-only dependencies count. A change to `:core:files-db`
breaks `:feature:files:data`'s androidTest compilation just as surely as
its main source set, and a module that no longer compiles its tests is not
a module that passed gate 2.

Unknown modules (a path not in settings.gradle.kts, e.g. the test suite's
`:no:such:module`) pass through unchanged rather than raising: the caller
is asking "what else does this reach", and the honest answer for something
outside the graph is "nothing".
"""
import argparse
import os
import re
import sys

_INCLUDE_RE = re.compile(r'^include\("([^"]+)"\)', re.MULTILINE)
_ACCESSOR_RE = re.compile(r"\bprojects\.([A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*)")


def _module_dir(gradle_path):
    return gradle_path.lstrip(":").replace(":", "/")


def _camel(segment):
    """`design-system` -> `designSystem`, matching Gradle's accessor naming."""
    head, *rest = segment.split("-")
    return head + "".join(part[:1].upper() + part[1:] for part in rest)


def _accessor_for(gradle_path):
    """`:core:design-system` -> `core.designSystem` (the part after `projects.`)."""
    return ".".join(_camel(segment) for segment in gradle_path.lstrip(":").split(":"))


def all_modules(root):
    """Every Gradle path in settings.gradle.kts, in declaration order."""
    try:
        with open(os.path.join(root, "settings.gradle.kts"), encoding="utf-8") as handle:
            text = handle.read()
    except OSError:
        return []
    return _INCLUDE_RE.findall(text)


def dependency_graph(root):
    """{module: set(modules it depends on)} for every module in settings."""
    modules = all_modules(root)
    by_accessor = {_accessor_for(module): module for module in modules}

    graph = {}
    for module in modules:
        build_file = os.path.join(root, _module_dir(module), "build.gradle.kts")
        try:
            with open(build_file, encoding="utf-8") as handle:
                body = handle.read()
        except OSError:
            graph[module] = set()
            continue
        found = set()
        for match in _ACCESSOR_RE.finditer(body):
            target = by_accessor.get(match.group(1))
            if target is not None and target != module:
                found.add(target)
        graph[module] = found
    return graph


def with_dependents(seeds, root):
    """`seeds` plus every module that reaches one of them, transitively.

    Walks the reversed edges to a fixed point, so `:core:domain` pulls in
    `:core:data` directly and `:app` through it.
    """
    graph = dependency_graph(root)
    reverse = {module: set() for module in graph}
    for module, dependencies in graph.items():
        for dependency in dependencies:
            reverse.setdefault(dependency, set()).add(module)

    closure = set(seeds)
    pending = list(seeds)
    while pending:
        current = pending.pop()
        for dependent in reverse.get(current, ()):
            if dependent not in closure:
                closure.add(dependent)
                pending.append(dependent)
    return sorted(closure)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    dependents = sub.add_parser("dependents")
    dependents.add_argument("--root", required=True)
    dependents.add_argument("--module", action="append", default=[])
    # Seeds on stdin, whitespace-separated. The shell callers cannot build
    # one --module per seed portably: zsh does not word-split an unquoted
    # `$modules`, so the whole newline-joined list arrived as ONE --module
    # value that the pass-through contract for unknown modules then echoed
    # back — the seeds came back with no dependents and no error. Reading
    # stdin and splitting HERE makes every shell hand over the same list.
    dependents.add_argument("--stdin", action="store_true")

    listing = sub.add_parser("all")
    listing.add_argument("--root", required=True)

    args = parser.parse_args()
    if args.cmd == "dependents":
        seeds = list(args.module)
        if args.stdin:
            seeds.extend(token for line in sys.stdin for token in line.split())
        for module in with_dependents(seeds, args.root):
            print(module)
    elif args.cmd == "all":
        for module in sorted(all_modules(args.root)):
            print(module)
    return 0


if __name__ == "__main__":
    sys.exit(main())

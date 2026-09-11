"""Stale compiled-class directories left behind by an AGP major upgrade.

WHY THIS EXISTS. Coverage is measured by handing JaCoCo the directory a module
compiled its classes into. AGP moved that directory in 9.x: it writes
build/intermediates/built_in_kotlinc/<variant>/<task>/classes, where 8.x wrote
build/tmp/kotlin-classes/<variant>. Upgrading does not remove the old tree --
nothing does, short of `./gradlew clean` -- so a repository that has been
through the upgrade carries two directories holding the same class NAMES
compiled by two different compilers.

Measured on a 26-module repository upgraded from AGP 8.x to 9.1.0: every
build/tmp/kotlin-classes was ten months old, and no AGP 9 build had written
there. Handed both, JaCoCo refuses -- "Can't add different class with same
name" -- and coverage could not be measured, and so could not be enforced, on
20 of the 26 modules.

The init scripts no longer read these paths: they ask the compile task where it
actually wrote. This module exists for the residue itself, which is worth
removing once at install and worth naming if it turns up later.

`sweep` only ever removes a directory that has a NEWER sibling holding the same
kind of output. A lone directory is this build's real output whatever it is
called, and is never touched.
"""

import argparse
import os
import shutil
import sys
import time

# (what supersedes it, what it supersedes). Newest layout first.
SUPERSEDED = [
    ("build/intermediates/built_in_kotlinc", "build/tmp/kotlin-classes"),
]


def _module_dirs(root):
    """Every directory under root that has a build.gradle.kts and a build/."""
    found = []
    for current, dirs, _files in os.walk(root):
        dirs[:] = [d for d in dirs
                   if d not in (".git", "build", ".gradle", ".idea", "node_modules")]
        if os.path.isfile(os.path.join(current, "build.gradle.kts")) \
                or os.path.isfile(os.path.join(current, "build.gradle")):
            if os.path.isdir(os.path.join(current, "build")):
                found.append(current)
    return sorted(found)


def _populated(path):
    """True when the tree holds at least one .class file."""
    for _current, _dirs, files in os.walk(path):
        for name in files:
            if name.endswith(".class"):
                return True
    return False


def stale(root="."):
    """[(module_dir, kept, kept_mtime, removed_candidate, its_mtime)]

    Only pairs where BOTH are populated. One alone is whatever this build
    produced, and is not stale by any definition available here.
    """
    out = []
    for module in _module_dirs(root):
        for newer, older in SUPERSEDED:
            new_path = os.path.join(module, newer)
            old_path = os.path.join(module, older)
            if not (os.path.isdir(new_path) and os.path.isdir(old_path)):
                continue
            if not (_populated(new_path) and _populated(old_path)):
                continue
            out.append((os.path.relpath(module, root),
                        os.path.relpath(new_path, root),
                        os.path.getmtime(new_path),
                        os.path.relpath(old_path, root),
                        os.path.getmtime(old_path)))
    return out


def _stamp(mtime):
    return time.strftime("%Y-%m-%d", time.localtime(mtime))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["report", "sweep"])
    parser.add_argument("--root", default=".")
    args = parser.parse_args(argv)

    found = stale(args.root)
    if not found:
        print("no superseded class-output directories")
        return 0

    for module, kept, kept_at, old, old_at in found:
        print("%s\n  keeping  %s  (%s)\n  stale    %s  (%s)"
              % (module, kept, _stamp(kept_at), old, _stamp(old_at)))
        if args.command == "sweep":
            try:
                shutil.rmtree(os.path.join(args.root, old))
                print("  removed  %s" % old)
            except OSError as error:
                # A build directory that cannot be written is not a reason to
                # fail an install. The init scripts do not read these paths.
                print("  could NOT remove %s: %s" % (old, error))

    if args.command == "report":
        print("\n%d module(s) carry a superseded class-output directory."
              % len(found))
        print("Left behind by an AGP upgrade; nothing but `clean` removes it.")
        print("Coverage does not read these paths, so this is housekeeping:")
        print("  python3 .prism/verify/lib/classdirs.py sweep")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Put a payload file where it belongs WITHOUT overwriting the consumer's work.

SETUP.md has always promised this and nothing implemented it:

    Setup is idempotent. Running it again reports what differs and writes only
    what the consumer has not edited; anything they changed is left alone with
    the new version beside it as `<file>.prism-new`.

Until 0.6.0 that paragraph described no code. Every placement in step 5 was a
plain `cp`, so a second run overwrote whatever the consumer had edited since the
first, silently, and the `.prism-new` file the sentence named did not exist
anywhere in the repository. The concrete loss was `.prism/scope.json`: re-running
setup restored the all-`observe` template over a repository that had spent weeks
promoting modules, and `./prism status` was the only place it showed.

THE THREE OUTCOMES, and they are reported rather than guessed at:

  PLACED     nothing was there. The file is now ours.
  UNCHANGED  byte-identical to what we ship. Nothing to do, nothing to say.
  KEPT       it differs, so THEIRS STAYS. Ours lands beside it as
             <dest>.prism-new and the summary says so.

KEPT IS NOT AN ERROR AND MUST NOT BE TREATED AS ONE. A consumer editing a rule
set, a threshold or a scope file is the framework working as intended -- step 5
hands them `tooling/` precisely so they can. The failure mode this guards is the
opposite one: an installer that treats its own copy as the truth and quietly
reverts a decision somebody made on purpose.

The exit status is about whether the *operation* worked, never about whether
files differed: 0 for a completed run, 1 for a real failure (unreadable source,
undeletable temp file). A caller that wants to know what differed reads the
summary, which names every KEPT path. Making a difference fatal would push the
agent toward the one recovery that loses the work -- delete theirs and re-run.

Deliberately NOT a merge. Two files that differ are not reconciled here, because
nothing in this process can know which half of a conflict matters; `lines` is the
one exception, and only because appending an absent line to a `.gitignore` is
mechanical rather than a judgement.
"""

import argparse
import filecmp
import os
import shutil
import sys

try:
    from render import write_atomically
except ImportError:  # pragma: no cover - only when run from another directory
    write_atomically = None

PLACED, UNCHANGED, KEPT = "PLACED", "UNCHANGED", "KEPT"
SUFFIX = ".prism-new"


class PlaceError(Exception):
    """A failure of the operation, never a difference between two files."""


def _copy(src, dest):
    """Copy atomically, so a run that dies leaves the old file whole.

    A half-written gate script is worse than no file at all, because it looks
    like a file. render.write_atomically is the same argument and the same
    implementation; it is imported when available and reproduced when this
    module is run from somewhere its sibling is not importable.
    """
    directory = os.path.dirname(os.path.abspath(dest))
    os.makedirs(directory, exist_ok=True)
    if write_atomically is not None:
        try:
            with open(src, encoding="utf-8") as handle:
                write_atomically(dest, handle.read())
            shutil.copymode(src, dest)
            return
        except UnicodeDecodeError:
            pass  # binary: fall through to the byte-wise path
    temporary = os.path.join(directory, ".%s.prism-tmp" % os.path.basename(dest))
    try:
        shutil.copy2(src, temporary)
        os.replace(temporary, dest)
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)


def place_file(src, dest):
    """Return one of PLACED / UNCHANGED / KEPT. Never raises on a difference."""
    if not os.path.isfile(src):
        raise PlaceError("%s is not a file" % src)
    if not os.path.exists(dest):
        _copy(src, dest)
        return PLACED
    if os.path.isdir(dest):
        raise PlaceError("%s is a directory; a file cannot replace it" % dest)
    # shallow=False: mtime and size agreeing is not the same as the bytes
    # agreeing, and a consumer edit that happens to preserve the size is exactly
    # the case a shallow compare would call UNCHANGED and overwrite.
    if filecmp.cmp(src, dest, shallow=False):
        return UNCHANGED
    _copy(src, dest + SUFFIX)
    return KEPT


def place_tree(src, dest):
    """Place every file under src, reporting per file. Directories are implicit.

    Whole-tree placement is offered because step 5 moves nine directories, and a
    rule the agent has to remember thirty times is a rule that gets applied
    twenty-nine times. Nothing is deleted from dest: a file the consumer added
    that we do not ship is theirs, and this is not a sync.
    """
    if not os.path.isdir(src):
        raise PlaceError("%s is not a directory" % src)
    results = []
    for directory, dirnames, filenames in os.walk(src):
        dirnames[:] = [d for d in sorted(dirnames) if d not in ("__pycache__",)]
        for name in sorted(filenames):
            if name.endswith(".pyc"):
                continue
            source = os.path.join(directory, name)
            relative = os.path.relpath(source, src)
            results.append((relative, place_file(source, os.path.join(dest, relative))))
    return results


def _stripped_lines(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as handle:
        return [line.rstrip("\n").strip() for line in handle]


def append_absent_lines(src, dest):
    """Append only the fragment lines that are not already in dest, verbatim.

    The `.gitignore` case. SETUP.md's `cat fragment >> .gitignore` is the
    empty-file case and the prose beside it says so -- but prose is what an agent
    skips, and running it twice writes the fragment twice.

    LITERAL matching, and the limit is deliberate. `**/build/` and an existing
    `build/` mean the same thing to git and are different strings here, so this
    will append the first over the second. Gitignore semantics are not
    reimplemented for the sake of one file; what this guarantees is that running
    setup again adds NOTHING, which is the property re-runs need. The semantic
    tidy-up stays a judgement in SETUP.md, where it is one paragraph a human can
    check rather than a pattern matcher nobody can.
    """
    if not os.path.isfile(src):
        raise PlaceError("%s is not a file" % src)
    with open(src, encoding="utf-8") as handle:
        fragment = [line.rstrip("\n") for line in handle]
    present = set(_stripped_lines(dest))
    absent = [line for line in fragment if line.strip() and line.strip() not in present]
    if not absent:
        return UNCHANGED, 0
    existing = ""
    if os.path.exists(dest):
        with open(dest, encoding="utf-8") as handle:
            existing = handle.read()
    parts = [existing]
    if existing and not existing.endswith("\n"):
        parts.append("\n")
    parts.append("\n".join(absent))
    parts.append("\n")
    if write_atomically is not None:
        write_atomically(dest, "".join(parts))
    else:  # pragma: no cover
        with open(dest, "w", encoding="utf-8") as handle:
            handle.write("".join(parts))
    return PLACED, len(absent)


def _summarise(results):
    """One line per file, then the count. KEPT paths are named individually.

    A summary that only counted would leave the agent's report saying "3 kept"
    with no way to tell the human WHICH of their files it declined to touch.
    """
    kept = []
    for relative, outcome in results:
        print("  %-9s %s" % (outcome, relative))
        if outcome == KEPT:
            kept.append(relative)
    counts = {PLACED: 0, UNCHANGED: 0, KEPT: 0}
    for _, outcome in results:
        counts[outcome] += 1
    print(
        "\n%d placed, %d unchanged, %d kept"
        % (counts[PLACED], counts[UNCHANGED], counts[KEPT])
    )
    if kept:
        print(
            "\nTHEIRS WAS KEPT in %d file(s). Ours is beside each one as "
            "<file>%s." % (len(kept), SUFFIX)
        )
        print("Report these to the human by name. Do not delete them to "
              "'finish' the install:")
        for relative in kept:
            print("  %s" % relative)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="place.py",
        description="Place payload files without overwriting consumer edits.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    one = sub.add_parser("file", help="place a single file")
    one.add_argument("--src", required=True)
    one.add_argument("--dest", required=True)

    tree = sub.add_parser("tree", help="place every file under a directory")
    tree.add_argument("--src", required=True)
    tree.add_argument("--dest", required=True)

    lines = sub.add_parser(
        "lines", help="append only the fragment lines that are absent")
    lines.add_argument("--src", required=True)
    lines.add_argument("--dest", required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "file":
            outcome = place_file(args.src, args.dest)
            return _summarise([(args.dest, outcome)])
        if args.command == "tree":
            return _summarise(place_tree(args.src, args.dest))
        outcome, count = append_absent_lines(args.src, args.dest)
        if outcome == UNCHANGED:
            print("  UNCHANGED %s (every line already present)" % args.dest)
        else:
            print("  APPENDED  %s (%d line(s))" % (args.dest, count))
        return 0
    except PlaceError as error:
        sys.stderr.write("prism place: %s\n" % error)
        return 1
    except OSError as error:
        sys.stderr.write("prism place: %s\n" % error)
        return 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Finish the install: verify it, remove the installer, say what the human has.

Three things happen here that used to happen nowhere, or happen as prose.

VERIFY FIRST, AND REMOVE NOTHING IF IT FAILS. "Remove the installer once
everything is ready" has a load-bearing second half. A repository whose doctor
does not pass still needs the installer, so a failing verdict removes nothing at
all and says so. This is the one ordering rule in the file.

REMOVE BY PROVEN IDENTITY, NEVER BY NAME. Until 0.6.2 nothing removed the
artifact: `prism-verify` stayed in the repository root forever as a stale,
versioned copy of the whole framework, and `prism-setup/` was removed by a bare
`rm -rf` that an agent typed from a document. Deleting a path because of what it
is called is how a recipe like that destroys something it did not mean to -- a
piped variant of one such line destroyed three konsist templates during 0.6.0's
phase 7. So every candidate here must PROVE it is the installer before it is
touched: the payload directory by carrying SETUP.md and a VERSION equal to the
installed one, the artifacts by being archives that contain SETUP.md. Anything
that fails its test is left alone and named in the report. `.prism/` and
`./prism` are never candidates.

SAY WHAT THE REPOSITORY NOW HAS, MECHANICALLY. The install used to end with
`Launching …` and then whatever the agent chose to write afterwards, so the one
sentence every human needs -- ./prism is the command now, .prism/ is where it
lives -- was improvised prose that a distracted session could simply omit. It is
a printed banner now.

The banner LISTS the commands and says what each does. It does not tell the
reader which one to run.
"""

import argparse
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scope  # noqa: E402  (sibling module; the path above is what makes it importable)

PAYLOAD = "prism-setup"
ARTIFACT = "prism-verify"

REMOVED = "REMOVED"
KEPT = "KEPT"
ABSENT = "ABSENT"


class HandoffError(Exception):
    pass


def installed_version(root):
    path = os.path.join(root, ".prism", "VERSION")
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        raise HandoffError(
            ".prism/VERSION is not readable, so this is not a finished install")


def _archive_is_installer(path):
    """True only for a zip that carries this installer's payload."""
    try:
        if not zipfile.is_zipfile(path):
            return False
        with zipfile.ZipFile(path) as archive:
            return "%s/setup/SETUP.md" % PAYLOAD in archive.namelist()
    except (OSError, zipfile.BadZipFile):
        return False


def _payload_is_ours(path, version):
    """True only for an unpacked payload of the version that is installed."""
    if not os.path.isfile(os.path.join(path, "setup", "SETUP.md")):
        return False
    try:
        with open(os.path.join(path, "VERSION"), encoding="utf-8") as handle:
            return handle.read().strip() == version
    except OSError:
        return False


def candidates(root):
    """Everything the installer could have left behind, in report order."""
    found = [os.path.join(root, PAYLOAD), os.path.join(root, ARTIFACT)]
    try:
        for name in sorted(os.listdir(root)):
            if name.startswith(ARTIFACT + "-") and name.endswith(".zip"):
                found.append(os.path.join(root, name))
    except OSError:
        pass
    return found


def judge(path, version):
    """(outcome, why) for one candidate. Nothing is removed here."""
    name = os.path.basename(path)
    if not os.path.exists(path):
        return ABSENT, ""
    if os.path.isdir(path):
        if name != PAYLOAD:
            return KEPT, "a directory this installer did not create"
        if not _payload_is_ours(path, version):
            return KEPT, ("not this install's payload -- no setup/SETUP.md, or a "
                          "VERSION that is not %s" % version)
        return REMOVED, "the unpacked payload"
    if not _archive_is_installer(path):
        return KEPT, "not a PRISM installer archive"
    return REMOVED, "the installer"


def remove(path):
    if os.path.isdir(path):
        import shutil
        shutil.rmtree(path)
    else:
        os.unlink(path)


def postures(root):
    """engine -> posture, resolved the way every other reader resolves it."""
    default, _modules = scope.load(root)
    return [(engine, default.get(engine, "enforce")) for engine in scope.ENGINES]


def banner(root, version, results):
    out = []
    add = out.append
    add("")
    add("PRISM %s is installed, and the doctor passed." % version)
    add("")
    add("  Added    ./prism      runs every PRISM command in this repository")
    add("           .prism/      the gates, the rule sets and the guides")
    removed = [(p, why) for p, outcome, why in results if outcome == REMOVED]
    kept = [(p, why) for p, outcome, why in results if outcome == KEPT]
    # One column width across both lists. A fixed width is fine until a
    # consumer's artifact is called prism-verify-0.6.2.zip, and then the
    # reasons no longer line up with each other.
    names = [os.path.basename(p) for p, _ in removed + kept]
    width = max([len(n) for n in names] + [12])
    if removed:
        add("")
        label = "  Removed  "
        for path, why in removed:
            add("%s%-*s  %s" % (label, width, os.path.basename(path), why))
            label = "           "
    for path, why in kept:
        add("  Kept     %-*s  %s" % (width, os.path.basename(path), why))
    add("")
    add("  ./prism status                 what blocks, per module and engine")
    add("  ./prism promote :module        make one module block on every engine")
    add("  ./prism promote --engine <e>   make one engine block in every module")
    add("  ./prism promote --all          make everything block")
    add("  ./prism doctor                 re-check this install")
    add("")
    add("  " + " · ".join("%s %s" % (e, p) for e, p in postures(root)))
    add("  .prism/INSTALL-GREENFIELD.md and .prism/INSTALL-BROWNFIELD.md")
    add("  describe how those change.")
    add("")
    return "\n".join(out)


def finish(root, doctor_passed, dry_run=False):
    version = installed_version(root)
    if not doctor_passed:
        raise HandoffError(
            "the doctor did not pass, so nothing was removed -- "
            "a repository that is not verified still needs its installer")
    results = []
    for path in candidates(root):
        outcome, why = judge(path, version)
        if outcome == REMOVED and not dry_run:
            remove(path)
        if outcome != ABSENT:
            results.append((path, outcome, why))
    return version, results


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="handoff.py",
        description="Verify the install, remove the installer, print the hand-off.")
    parser.add_argument("--root", default=".")
    parser.add_argument(
        "--doctor-passed", action="store_true",
        help="the caller ran prism-doctor and it exited 0. Without this nothing "
             "is removed, because an unverified install still needs its installer.")
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would be removed, and remove nothing")
    args = parser.parse_args(argv)
    try:
        version, results = finish(args.root, args.doctor_passed, args.dry_run)
    except (HandoffError, scope.ScopeError) as error:
        sys.stderr.write("prism handoff: %s\n" % error)
        return 1
    except OSError as error:
        sys.stderr.write("prism handoff: %s\n" % error)
        return 1
    if args.dry_run:
        for path, outcome, why in results:
            print("  %-8s %s%s" % (outcome, os.path.basename(path),
                                   " -- " + why if why else ""))
        return 0
    print(banner(args.root, version, results))
    return 0


if __name__ == "__main__":
    sys.exit(main())

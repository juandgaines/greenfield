#!/usr/bin/env python3
"""Read and edit the detekt baseline. Standard library only.

WHY THIS FILE EXISTS AT ALL. `detektBaseline` REPLACES the baseline it is
pointed at -- it keeps only <ManuallySuppressedIssues> from the file it finds
and writes this run's findings as the whole <CurrentIssues> set. So several
tasks pointed at one file leave the LAST one's findings and nothing else: a
baseline that looks complete, suppresses one task's sources, and silently
un-suppresses the rest the next time anything runs. The convention plugin
therefore sends each creation task to its own fragment under
build/prism/baseline/, and `collect` unions them here.

ONE BASELINE PER MODULE, AND THAT IS 0.6.0's CORRECTION. Until then there was a
single file at the repository root, justified in DetektConventionPlugin by a
claim that turned out to be false: that "baseline ids embed the file path
relative to basePath, so entries from fifteen modules cannot collide". They do
not embed the path. A real id is

    AbstractClassCanBeInterface:AgendaBindsModule.kt:AgendaBindsModule$...

-- a BARE FILENAME -- and `basePath` does not change it. Measured on one
26-module repository's own baseline: 193 ids, 0 containing a path separator, 84
distinct file components, 36 of them appearing more than once. `RunningTracker.kt`
existed in both `:run:domain` and `:wear:run:domain`, so their entries were
indistinguishable, and `write()` deduplicates -- TWO findings were recorded as
ONE entry.

The consequence was a FALSE GREEN, which is worse than a false denial. Promoting
`:run:domain` could not tell which entries were that module's, correctly refused
to guess, and kept them -- and the kept entries were suppressing six real
findings in the module being promoted. `staticAnalysis` would have reported green
over code that was not clean.

A per-module file cannot do that: an entry in `:run:domain/detekt.baseline.xml`
is only ever read while analysing `:run:domain`. It also makes attribution exact
rather than inferred -- `drop --module` is now a file, not a guess at which
module a bare filename belonged to -- and it matches ktlint, which has always
kept one baseline per module. The residual limit is honest and much smaller: two
same-named files INSIDE one module still share an entry, and nothing here can
see that, because two tasks reporting the same finding look identical to it.

WHAT THE BASELINE IS FOR, AND WHAT IT IS NOT FOR. It records findings that
existed the day PRISM was installed so that day one is not a red build. Every
rule stays live: a NEW violation of a baselined rule still fails. It is not a
way to switch a rule off -- that is `active: false` in detekt.yml, and the two
must not be confused, because a rule that does not apply here should never come
back and a finding that was baselined should.

THE ONE DIRECTION THIS TOOL MOVES. `drop` removes suppression; `collect`
refuses to write over an existing baseline. Regenerating over a baseline
absorbs every violation written since the install -- quietly, and in exactly
the repositories where nobody would notice. A seam may over-block, never allow,
so the workday operation subtracts and the additive one asks first.
"""

import argparse
import collections
import os
import sys
import tempfile
import xml.etree.ElementTree as ET

CURRENT = "CurrentIssues"
SUPPRESSED = "ManuallySuppressedIssues"
# A per-module filename now, not a path from the root. Every command below
# operates on the TREE of these unless --path names one.
BASELINE_NAME = "detekt.baseline.xml"
DEFAULT_BASELINE = BASELINE_NAME
FRAGMENT_GLOB = os.path.join("build", "prism", "baseline")
PRUNE = (".git", "build", ".gradle", ".idea", "node_modules")

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_ABSENT = 3


def die(message, *detail):
    print("baseline: %s" % message, file=sys.stderr)
    for line in detail:
        print("  %s" % line, file=sys.stderr)
    return EXIT_ERROR


def read(path):
    """Return (current, suppressed) as lists of id strings.

    A baseline that does not parse is an ERROR, never an empty one. detekt
    itself fails every module's analysis on an invalid baseline, so reporting
    zero findings here would contradict the build for as long as nobody looked.
    """
    tree = ET.parse(path)
    root = tree.getroot()

    def ids(tag):
        node = root.find(tag)
        if node is None:
            return []
        return [e.text for e in node.findall("ID") if e.text]

    return ids(CURRENT), ids(SUPPRESSED)


def write(path, current, suppressed):
    """Write the baseline atomically, ids sorted so a diff is readable."""
    root = ET.Element("SmellBaseline")
    for tag, values in ((SUPPRESSED, suppressed), (CURRENT, current)):
        node = ET.SubElement(root, tag)
        for value in sorted(set(values)):
            ET.SubElement(node, "ID").text = value
    body = ET.tostring(root, encoding="unicode")

    directory = os.path.dirname(os.path.abspath(path)) or "."
    handle, temporary = tempfile.mkstemp(dir=directory, prefix=".baseline-")
    try:
        with os.fdopen(handle, "w") as out:
            out.write('<?xml version="1.0" ?>\n')
            out.write(body)
            out.write("\n")
        os.replace(temporary, path)
    except BaseException:
        if os.path.exists(temporary):
            os.unlink(temporary)
        raise


def rule_of(baseline_id):
    """detekt writes '<RuleId>:<signature>'. The rule is what people act on."""
    return baseline_id.split(":", 1)[0]


def signature_of(baseline_id):
    parts = baseline_id.split(":", 1)
    return parts[1] if len(parts) > 1 else ""


def load(path):
    """(current, suppressed, exit_code). exit_code is None when the file was read."""
    if not os.path.isfile(path):
        return [], [], EXIT_ABSENT
    try:
        current, suppressed = read(path)
    except ET.ParseError as error:
        return [], [], die(
            "%s does not parse: %s" % (path, error),
            "detekt fails every module's analysis on an invalid baseline, so this",
            "is a broken build rather than an empty baseline. Fix or delete the file.",
        )
    return current, suppressed, None


def module_dir_of_fragment(fragment):
    """The module directory a fragment belongs to.

    Fragments land at <module>/build/prism/baseline/<task>.xml, so the module is
    whatever sits above `build`. THIS IS WHERE THE MODULE IDENTITY USED TO BE
    THROWN AWAY: the union read every fragment in the tree into one list and the
    only thing that knew which module a finding came from was the path of the
    file being read.
    """
    parts = os.path.abspath(fragment).split(os.sep)
    for index in range(len(parts) - 1, 0, -1):
        if parts[index] == "build":
            return os.sep.join(parts[:index])
    return None


def fragments_by_module(root):
    """module directory -> its fragment files, sorted."""
    grouped = collections.defaultdict(list)
    for directory, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in (".git", ".gradle", ".idea")]
        if not directory.endswith(FRAGMENT_GLOB):
            continue
        for name in sorted(files):
            if not name.endswith(".xml"):
                continue
            fragment = os.path.join(directory, name)
            module = module_dir_of_fragment(fragment)
            if module:
                grouped[module].append(fragment)
    return grouped


def baseline_files(root):
    """Every module baseline in the tree, sorted. Build output is never one."""
    found = []
    for directory, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in PRUNE]
        if BASELINE_NAME in names:
            found.append(os.path.join(directory, BASELINE_NAME))
    return sorted(found)


def label_of(root, path):
    """How to name a baseline file to a human: its module's Gradle path."""
    relative = os.path.relpath(os.path.dirname(os.path.abspath(path)),
                               os.path.abspath(root))
    if relative in (".", ""):
        return ":"
    return ":" + relative.replace(os.sep, ":")


def cmd_collect(args):
    """Union each module's fragments into that module's own baseline.

    `--out` still writes ONE file, because a caller who names an output file
    means it; without it this walks the tree and writes one baseline per module
    that produced fragments.
    """
    grouped = fragments_by_module(args.root)
    if not grouped:
        return die(
            "no baseline fragments under %s" % args.root,
            "Run './gradlew prismBaseline' first. It writes one fragment per detekt",
            "task; this command unions them per module, because detekt's own",
            "baseline task REPLACES rather than merges and would leave the findings",
            "of whichever task ran last.",
        )

    if args.out:
        targets = {os.path.abspath(args.out): sorted(
            f for files in grouped.values() for f in files)}
    else:
        targets = {os.path.join(module, BASELINE_NAME): sorted(files)
                   for module, files in grouped.items()}

    # ALL OR NOTHING. Writing the modules that have no baseline yet and skipping
    # the ones that do would leave a tree half-recorded by this run and half by
    # an older one, with nothing saying which was which.
    existing = sorted(path for path in targets if os.path.exists(path))
    if existing and not args.force:
        return die(
            "%d module baseline(s) already exist" % len(existing),
            *([("  %s" % os.path.relpath(path, args.root)) for path in existing[:8]]
              + [
                  "Regenerating a baseline ABSORBS every violation written since the",
                  "last one -- the findings you were about to be told about become",
                  "permanently suppressed, and nothing reports that it happened. To",
                  "retire entries as you fix them, use:",
                  "  baseline.py drop --rule <RuleId>",
                  "Pass --force only if you mean to discard the existing record.",
              ])
        )

    seen = collections.defaultdict(set)
    total = 0
    for path in sorted(targets):
        current, suppressed = [], []
        for fragment in targets[path]:
            try:
                found, kept = read(fragment)
            except ET.ParseError as error:
                return die("%s does not parse: %s" % (fragment, error))
            current.extend(found)
            suppressed.extend(kept)
        write(path, current, suppressed)
        label = label_of(args.root, path)
        for entry in set(current):
            seen[entry].add(label)
        total += len(set(current))
        print("%-34s %d findings from %d fragment(s)"
              % (label, len(set(current)), len(targets[path])))

    print("\n%d findings recorded across %d module baseline(s)."
          % (total, len(targets)))

    # The mechanism, stated on real data rather than asserted in a comment. Two
    # modules holding a same-named file produce the SAME id, and until 0.6.0 that
    # was one shared entry in one root file.
    shared = {entry: mods for entry, mods in seen.items() if len(mods) > 1}
    if shared:
        print(
            "\n%d id(s) occur in more than one module. A detekt baseline id carries"
            % len(shared))
        print("the BARE FILENAME, not the path, so those entries are only")
        print("distinguishable because each module now has its own file:")
        for entry in sorted(shared)[:5]:
            print("  %-40s %s" % (file_of(entry), " ".join(sorted(shared[entry]))))
    return EXIT_OK


def selected(args):
    """(files, explicit) -- the baselines a command acts on.

    `--path` names ONE file and keeps exactly the single-file semantics every
    caller had before 0.6.0, which is what the tests drive and what a caller
    inspecting a fragment wants. Without it the subject is the whole tree,
    because the baseline is per module now and a repository has as many as it
    has modules with findings.
    """
    if getattr(args, "path", None):
        return [args.path], True
    return baseline_files(getattr(args, "root", ".") or "."), False


def load_selected(args):
    """[(path, current, suppressed)] or an exit code. Never a partial read.

    A file that does not parse stops everything: detekt fails EVERY module's
    analysis on an invalid baseline, so reporting the other modules' numbers as
    though the tree were fine would contradict the build for as long as nobody
    looked.
    """
    files, explicit = selected(args)
    loaded = []
    for path in files:
        current, suppressed, code = load(path)
        if code == EXIT_ABSENT:
            if explicit:
                return EXIT_ABSENT, explicit
            continue
        if code:
            return code, explicit
        loaded.append((path, current, suppressed))
    return loaded, explicit


def module_sources(module_dir):
    """The basenames of the Kotlin files that module holds today."""
    names = set()
    for directory, dirs, files in os.walk(module_dir):
        dirs[:] = [d for d in dirs if d not in PRUNE]
        for name in files:
            if name.endswith(".kt"):
                names.add(name)
    return names


def stale_entries(path, current):
    """Entries naming a file the module no longer has, so they suppress nothing.

    THIS IS THE OTHER HALF OF THE BARE-FILENAME PROBLEM, and it cost a real run a
    red build in a step that had nothing to do with detekt: five ktlint
    `standard:filename` renames (`Routes.kt` -> `Destination.kt` and four more)
    detached six baselined detekt findings at once. The entry stays in the file,
    matches nothing, and the finding it used to suppress is live again. Nothing
    warned about it, in a guide that keeps the two engines in separate chapters.

    A rename is not an error and this never fails anything -- it is a rot count,
    and knowing it is the difference between "detekt regressed" and "we renamed
    five files".

    ONLY NAMES THIS CAN ACTUALLY READ are considered. An id whose file component
    does not look like a source file is skipped rather than guessed at: a false
    positive here sends somebody hunting for a rename that never happened, which
    is worse than saying nothing, and the whole value of this number is that it
    is trustworthy enough to act on.
    """
    have = module_sources(os.path.dirname(os.path.abspath(path)))
    names = set()
    for entry in current:
        name = file_of(entry)
        if not name.endswith((".kt", ".kts", ".java")):
            continue
        if name not in have:
            names.add(name)
    return sorted(names)


def cmd_count(args):
    """How much is suppressed, in words that mean what they say.

    Until 0.6.0 this printed `current=330 suppressed=0`, which is detekt's two
    XML tag names presented as English -- and read as the exact inverse of the
    truth. Those 330 are the SUPPRESSED ones: <CurrentIssues> is what detekt
    recorded when the baseline was written, and every entry in it stops blocking.
    The `suppressed=0` beside it was <ManuallySuppressedIssues>, the block a
    human adds by hand, which is usually empty. A reader saw "suppressed=0" next
    to 330 and concluded that nothing was suppressed and 330 findings were live,
    which is the one number a promotion decision turns on.

    So the first field is now the TOTAL that does not block, and the split
    follows it. The FIRST LINE IS A CONTRACT: prism-doctor and `./prism status`
    both read `suppressed=` from it as the number to report, and fields are only
    ever appended to it.
    """
    loaded, explicit = load_selected(args)
    if loaded == EXIT_ABSENT or not loaded:
        print("suppressed=0 baselined=0 manual=0 stale=0 (no baseline)")
        return EXIT_ABSENT
    if isinstance(loaded, int):
        return loaded

    baselined = sum(len(current) for _, current, _ in loaded)
    manual = sum(len(suppressed) for _, _, suppressed in loaded)
    stale = {}
    for path, current, _ in loaded:
        names = stale_entries(path, current)
        if names:
            stale[path] = names
    print("suppressed=%d baselined=%d manual=%d stale=%d"
          % (baselined + manual, baselined, manual,
             sum(len(names) for names in stale.values())))

    if not explicit and len(loaded) > 1:
        root = getattr(args, "root", ".") or "."
        for path, current, suppressed in loaded:
            print("  %-34s %d" % (label_of(root, path), len(current) + len(suppressed)))

    if stale:
        root = getattr(args, "root", ".") or "."
        print("\nBASELINED FILE NAMES THAT ARE NO LONGER IN THEIR MODULE.")
        print("A detekt baseline id carries the bare filename, so renaming a file")
        print("detaches its entries and those findings block again:")
        for path, names in sorted(stale.items()):
            print("  %-34s %s" % (label_of(root, path), ", ".join(names[:5])))
    return EXIT_OK


def cmd_group(args):
    loaded, _ = load_selected(args)
    if loaded == EXIT_ABSENT or not loaded:
        return EXIT_ABSENT
    if isinstance(loaded, int):
        return loaded
    counts = collections.Counter(
        rule_of(entry) for _, current, _ in loaded for entry in current)
    # Highest first: the top line is the one worth a decision, because one
    # answer about one rule retires the most entries.
    for rule, count in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])):
        print("%d %s" % (count, rule))
    return EXIT_OK


def cmd_list(args):
    loaded, explicit = load_selected(args)
    if loaded == EXIT_ABSENT or not loaded:
        return EXIT_ABSENT
    if isinstance(loaded, int):
        return loaded
    root = getattr(args, "root", ".") or "."
    for path, current, _ in loaded:
        for baseline_id in sorted(current):
            if rule_of(baseline_id) != args.rule:
                continue
            # The module is printed because the same signature can legitimately
            # appear in two of them -- that is the bare-filename mechanism, and
            # a list that hid it would send somebody to the wrong file.
            if explicit:
                print(signature_of(baseline_id))
            else:
                print("%-24s %s" % (label_of(root, path), signature_of(baseline_id)))
    return EXIT_OK


def file_of(baseline_id):
    """The source file a baseline id points at -- a BASENAME, not a path.

    This is the correction the rest of this file is built on. An earlier version
    assumed detekt ids embed the path relative to basePath and filtered modules
    with a `feature/login/` prefix. They do not: a real id reads

        AbstractClassCanBeInterface:AgendaBindsModule.kt:AgendaBindsModule$...

    so the prefix matched nothing, in every repository, and `drop --module`
    silently kept every entry while reporting that the module was clean. A
    filter that matches nothing is the failure mode this whole framework exists
    to catch; it does not get to live in the framework.

    Two shapes are handled because both occur. detekt writes three parts --
    `Rule:File.kt:Signature` -- and the `$` that begins the element signature
    sometimes lands in the second part instead, `Rule:File.kt$Class$member`. The
    name ends at the first `:` or the first `$`, whichever comes first.
    """
    name = signature_of(baseline_id).split(":", 1)[0]
    return name.split("$", 1)[0]


def module_dir_of(root, module):
    """A Gradle path -> the directory holding that module's baseline."""
    if module in (":", ""):
        return os.path.abspath(root)
    return os.path.join(os.path.abspath(root),
                        *[part for part in module.strip(":").split(":") if part])


def _remove_when_empty(path, current, suppressed):
    """A baseline with nothing in it is deleted, not written empty.

    The doctor calls an absent baseline and an empty one the SAME verdict, and
    says why: somebody who worked their findings to zero is in the good state.
    Leaving a file with two empty elements behind makes that state look like an
    install artefact nobody dared delete.
    """
    if current or suppressed:
        write(path, current, suppressed)
        return False
    os.remove(path)
    return True


def cmd_drop(args):
    if args.module:
        return drop_module(args)
    return drop_rule(args)


def drop_module(args):
    """Everything one module suppresses, retired -- by FILE, not by inference.

    This is what `./prism promote :module` runs, and before 0.6.0 it was the
    weakest part of the framework: with one baseline at the root it had to guess
    which entries belonged to the module from a bare filename, and when a sibling
    held a file of the same name it correctly refused and KEPT them. Those kept
    entries then suppressed real findings in the module being promoted, and
    `staticAnalysis` reported green over code that was not clean. A per-module
    file turns the whole question into `os.path.isfile`.
    """
    target = args.module if args.module.startswith(":") else ":" + args.module
    directory = module_dir_of(args.root, target)
    if not os.path.isdir(directory):
        return die("%s is not a directory in this tree" % os.path.relpath(directory, args.root),
                   "Give the module as a Gradle path, e.g. :feature:login.")
    path = os.path.join(directory, BASELINE_NAME)
    current, suppressed, code = load(path)
    if code == EXIT_ABSENT:
        print("Nothing is suppressed in %s -- it has no %s." % (target, BASELINE_NAME))
        print("Every finding there already blocks where its engine enforces.")
        return EXIT_OK
    if code:
        return code
    os.remove(path)
    print("Dropped %d suppression(s) in %s; removed %s."
          % (len(current) + len(suppressed), target,
             os.path.relpath(path, args.root)))
    if suppressed:
        print("  %d of them were hand-written <ManuallySuppressedIssues>; they are"
              % len(suppressed))
        print("  gone too, because promoting a module means it suppresses nothing.")
    print("Run './gradlew staticAnalysis' -- if it is green they were genuinely fixed.")
    return EXIT_OK


def drop_rule(args):
    loaded, _ = load_selected(args)
    if loaded == EXIT_ABSENT or not loaded:
        return die("no baseline to drop from",
                   "Nothing in this tree suppresses anything.")
    if isinstance(loaded, int):
        return loaded

    root = getattr(args, "root", ".") or "."
    gone, remain, touched, removed = 0, 0, [], []
    for path, current, suppressed in loaded:
        kept = [entry for entry in current if rule_of(entry) != args.rule]
        dropped = len(current) - len(kept)
        remain += len(kept)
        if dropped:
            gone += dropped
            touched.append((label_of(root, path), dropped))
            if _remove_when_empty(path, kept, suppressed):
                removed.append(label_of(root, path))
    if not gone:
        return die("dropped nothing for %s" % args.rule,
                   "Run 'baseline.py group' for the rules that are actually recorded.")
    for label, dropped in touched:
        print("  %-34s %d dropped" % (label, dropped))
    print("Dropped %d findings for %s across %d module(s); %d remain. Run "
          "'./gradlew staticAnalysis' -- if it is green they were genuinely fixed."
          % (gone, args.rule, len(touched), remain))
    for label in removed:
        print("  %s has nothing suppressed now, so its baseline was removed." % label)
    return EXIT_OK


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command")

    # EVERY subcommand takes --root, and --path is the escape hatch rather than
    # the default. The baseline is per module, so "which file" is the wrong
    # question to make a caller answer: the right subject is the repository, and
    # a caller who genuinely means one file says so.
    def with_subject(p):
        p.add_argument("--root", default=".",
                       help="repository root; every module baseline under it is the subject")
        p.add_argument("--path", default=None,
                       help="act on ONE baseline file instead of the tree")
        return p

    collect = sub.add_parser("collect", help="union each module's fragments into its baseline")
    collect.add_argument("--root", default=".")
    collect.add_argument("--out", help="write ONE baseline here instead of one per module")
    collect.add_argument("--force", action="store_true")
    collect.set_defaults(func=cmd_collect)

    for name, func, helptext in (
        ("count", cmd_count, "how many findings are suppressed, and how many entries have rotted"),
        ("group", cmd_group, "counts by rule, highest first"),
    ):
        with_subject(sub.add_parser(name, help=helptext)).set_defaults(func=func)

    listing = with_subject(sub.add_parser("list", help="the signatures baselined for one rule"))
    listing.add_argument("--rule", required=True)
    listing.set_defaults(func=cmd_list)

    # Exactly one of --rule or --module. Both together would read as "this rule
    # in that module", which is not what it does; --module retires everything the
    # module suppresses, which is what promoting one means.
    drop = with_subject(sub.add_parser(
        "drop", help="stop suppressing one rule's findings, or one module's"))
    drop_what = drop.add_mutually_exclusive_group(required=True)
    drop_what.add_argument("--rule")
    drop_what.add_argument("--module", help="a Gradle path, e.g. the one you are promoting")
    drop.set_defaults(func=cmd_drop)

    args = parser.parse_args(argv)
    if not getattr(args, "func", None):
        parser.print_help()
        return EXIT_ERROR
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

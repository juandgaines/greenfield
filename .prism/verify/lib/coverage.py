#!/usr/bin/env python3
"""JaCoCo configuration checks and coverage-threshold comparison.

Used by push-gate.sh. Importable for tests; also has a small CLI so the
shell hook can call it without inlining Python.
"""
import argparse
import hashlib
import json
import math
import re
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scope  # noqa: E402


def threshold_for(module, config):
    """Required line-coverage percent for a Gradle module path.

    NORMALISED, because a floor is a decision and a measurement is not. The
    documented way to set a starting floor is "a number you have already
    measured", and a measurement looks like 98.30508474576271 -- so that is what
    got pasted in. Seventeen significant figures then travelled through every
    report that prints the required column, where the field is eight characters
    wide, and the table stopped lining up. Worse than the formatting: it reads as
    a decision made to a ten-billionth of a percent, on a number that moves with
    the next commit.

    ONE DECIMAL PLACE, AND ALWAYS DOWNWARD. Flooring can only ever make the
    requirement weaker by less than 0.1pp, which is invisible; rounding UP would
    deny a push for a fraction nobody chose, and the first push it denied would be
    the one that measured the number. An integral value stays an int so it renders
    as `80` rather than `80.0`.
    """
    overrides = config.get("overrides", {})
    value = overrides[module] if module in overrides else config.get("default", 80)
    return _normalise_threshold(value)


def _normalise_threshold(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return value
    if isinstance(value, int):
        return value
    if float(value).is_integer():
        return int(value)
    return math.floor(value * 10) / 10.0


def _observed_detail(module, root, xml, kind, config_path):
    """What an observed module would have scored, for reporting only.

    Never raises and never denies. Every failure here is a reporting gap in a
    module that by definition does not block, so it degrades to a word rather
    than to an exit status.
    """
    try:
        with open(config_path) as handle:
            required = threshold_for(module, json.load(handle))
    except (OSError, ValueError, TypeError):
        required = "?"
    try:
        counter = _line_counter(xml)
    except (OSError, ET.ParseError):
        return "no-report %s" % required
    if counter is None or (counter[0] + counter[1]) == 0:
        return "no-line-data %s" % required
    covered, missed = counter
    return "%.1f %s" % (100.0 * covered / (covered + missed), required)


def _line_counter(xml_path):
    """(covered, missed) from the JaCoCo XML's top-level LINE counter, or
    None when the report has no LINE counter at all."""
    if not os.path.exists(xml_path):
        raise FileNotFoundError(xml_path)
    tree = ET.parse(xml_path)
    for counter in tree.getroot().findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered", 0)), int(counter.get("missed", 0))
    return None


def line_coverage(xml_path):
    """Percent of lines covered in a JaCoCo XML report, 0-100.

    A module with no lines at all counts as fully covered rather than as a
    zero: there is nothing to test, so it cannot be under-tested. Callers
    that need to tell "nothing to test" apart from "the report is empty or
    missing" should use _line_counter directly (see the `assert` CLI path).
    """
    counter = _line_counter(xml_path)
    if counter is None:
        return 100.0
    covered, missed = counter
    total = covered + missed
    if total == 0:
        return 100.0
    return 100.0 * covered / total


def has_sources(module_dir, root):
    """True when the module's src/main contains any Kotlin file.

    Used to tell a module that genuinely has no source (pure config, e.g.
    version catalog wiring) apart from one with production code but zero
    tests of any kind — the latter must deny the push, not pass silently.
    """
    base = os.path.join(root, module_dir, "src", "main")
    for _current, _dirs, filenames in os.walk(base):
        for filename in filenames:
            if filename.endswith(".kt"):
                return True
    return False


JACOCO_PLUGIN_ID = "prism.jacoco"

#: AGP's own plugin ids. Not PRISM's -- PRISM ships no Android convention
#: plugin, so these are the only names that mean "this is an Android module"
#: in an arbitrary consumer repository.
ANDROID_PLUGIN_IDS = ("com.android.application", "com.android.library")

_PLUGIN_REGISTRATION_RE = re.compile(
    r'id\s*=\s*"(?P<id>[^"]+)"\s*\n\s*implementationClass\s*=\s*"(?P<cls>[^"]+)"'
)
# ANY plugin id, deliberately — not `prism.` anything.
#
# Both of these were anchored to `prism\.` until 2026-08-28, which meant the
# plugin graph could only see plugins PRISM itself had named. A consumer whose
# modules apply their OWN convention plugin — `acme.android.library`, say —
# was invisible: has_jacoco returned False for every module, and the push gate
# denies a module it believes is unconfigured for coverage. Every push, in a
# repository that was correctly set up.
#
# It never surfaced because the fixtures used `prism.*` names too, so the tests
# and the defect shared an assumption. The fixtures now model a consumer's own
# conventions, which is what caught it.
_PLUGIN_APPLY_RE = re.compile(r'\bapply\(\s*"(?P<id>[A-Za-z][\w.-]*)"\s*\)')
_MODULE_PLUGIN_RE = re.compile(r'\bid\(\s*"(?P<id>[A-Za-z][\w.-]*)"\s*\)')


def _convention_plugin_graph(root):
    """Maps each convention plugin id to the prism plugin ids it applies.

    Read from build-logic rather than hardcoded. Coverage is now applied from
    inside the base convention plugins, so a module's own build file no longer
    mentions it at all — the question "is this module configured for coverage"
    can only be answered by following what its plugins apply. Deriving the
    graph keeps that answer correct when a convention plugin is added or
    rewired, which a hardcoded list of four ids would not.
    """
    build_logic = os.path.join(root, "build-logic")
    registrations = os.path.join(build_logic, "build.gradle.kts")
    try:
        with open(registrations) as handle:
            text = handle.read()
    except OSError:
        return {}

    graph = {}
    for match in _PLUGIN_REGISTRATION_RE.finditer(text):
        plugin_id, class_name = match.group("id"), match.group("cls")
        source = os.path.join(build_logic, "src", "main", "kotlin", class_name + ".kt")
        try:
            with open(source) as handle:
                body = handle.read()
        except OSError:
            graph[plugin_id] = set()
            continue
        graph[plugin_id] = {m.group("id") for m in _PLUGIN_APPLY_RE.finditer(body)}
    return graph


def _plugins_providing(root, *targets):
    """Every plugin id whose application brings one of `targets` with it.

    The transitive closure over the consumer's own convention plugins, so a
    module applying `acme.android.library.compose` is recognised through
    `acme.android.library` without either name being known in advance. That
    generality is the point: PRISM ships four verification plugins and no
    opinion about how a consumer configures Kotlin or Android, so every
    question about what a module IS has to be answered against the real
    upstream plugin ids rather than against names PRISM chose.
    """
    graph = _convention_plugin_graph(root)
    providers = set(targets)
    changed = True
    while changed:
        changed = False
        for plugin_id, applied in graph.items():
            if plugin_id not in providers and applied & providers:
                providers.add(plugin_id)
                changed = True
    return providers


def _plugins_providing_jacoco(root):
    """Every plugin id whose application brings coverage configuration."""
    return _plugins_providing(root, JACOCO_PLUGIN_ID)


def is_android(module_dir, root):
    """True when the module applies the Android Gradle Plugin, however indirectly.

    Asked by the scope calculation to choose between `assemble`/`test` and
    `assembleDebug`/`testDebugUnitTest`. AGP's own ids are the signal because
    they are the only names guaranteed to mean the same thing in every
    repository.
    """
    build_file = os.path.join(root, module_dir, "build.gradle.kts")
    try:
        with open(build_file) as handle:
            text = handle.read()
    except OSError:
        return False
    declared = {m.group("id") for m in _MODULE_PLUGIN_RE.finditer(text)}
    if declared & set(ANDROID_PLUGIN_IDS):
        return True
    return bool(declared & _plugins_providing(root, *ANDROID_PLUGIN_IDS))


def has_jacoco(module_dir, root):
    """True when the module gets coverage configuration, directly or inherited.

    A module used to declare `id("prism.jacoco")` itself. It no longer does:
    the base convention plugins apply it, so that coverage is a property of the
    build rather than of thirteen individual decisions someone has to remember.
    This therefore resolves the plugin the module DOES declare and asks whether
    coverage arrives through it.
    """
    build_file = os.path.join(root, module_dir, "build.gradle.kts")
    try:
        with open(build_file) as handle:
            text = handle.read()
    except OSError:
        return False

    providers = _plugins_providing_jacoco(root)
    declared = {m.group("id") for m in _MODULE_PLUGIN_RE.finditer(text)}
    return bool(declared & providers)


def report_kind(module_dir, root):
    """Which coverage report suits this module's source sets."""
    base = os.path.join(root, module_dir, "src")
    unit = os.path.isdir(os.path.join(base, "test"))
    instrumentation = os.path.isdir(os.path.join(base, "androidTest"))
    if unit and instrumentation:
        return "combined"
    if unit:
        return "jvm"
    if instrumentation:
        return "instrumentation"
    return "none"


def newest_source_mtime(module_dir, root):
    """Latest mtime across every Kotlin source in the module, or None.

    Covers src/main, src/test and src/androidTest plus the module's build
    file: any of them changing means a previously-produced coverage report
    describes code that no longer exists.
    """
    latest = None
    base = os.path.join(root, module_dir, "src")
    candidates = []
    for current, _dirs, filenames in os.walk(base):
        for filename in filenames:
            if filename.endswith((".kt", ".kts")):
                candidates.append(os.path.join(current, filename))
    build_file = os.path.join(root, module_dir, "build.gradle.kts")
    if os.path.exists(build_file):
        candidates.append(build_file)
    for path in candidates:
        try:
            stamp = os.path.getmtime(path)
        except OSError:
            continue
        if latest is None or stamp > latest:
            latest = stamp
    return latest


def _newest_execution_data(module_dir, root):
    """Latest mtime among the on-device .ec files, or None if there are none.

    AGP pulls these back off the emulator during connectedDebugAndroidTest.
    They are the ONLY evidence that the instrumentation suite actually ran:
    prismCombinedCoverage dependsOn testDebugUnitTest alone and merges the
    .ec files through a tolerant fileTree, so it happily produces a report
    with no .ec present at all — a unit-test-only number wearing a combined
    report's name.
    """
    latest = None
    base = os.path.join(root, module_dir, "build", "outputs", "code_coverage")
    for current, _dirs, filenames in os.walk(base):
        for filename in filenames:
            if not filename.endswith(".ec"):
                continue
            try:
                stamp = os.path.getmtime(os.path.join(current, filename))
            except OSError:
                continue
            if latest is None or stamp > latest:
                latest = stamp
    return latest


def _device_run_evidence(module_dir, root):
    """Latest mtime of anything AGP writes when a connected run HAPPENS, or None.

    Distinct from the .ec files, which are what a connected run brings BACK.
    The two come apart: when two `connectedDebugAndroidTest` runs for the same
    test package share one emulator they kill each other, and the loser dies
    with "Instrumentation run failed due to Process crashed" having produced
    result output but no execution data. Reporting that as "you never ran it"
    sent the reader off to do the one thing they had already done.
    """
    latest = None
    module_build = os.path.join(root, module_dir, "build")
    for base in (
        os.path.join(module_build, "outputs", "androidTest-results", "connected"),
        os.path.join(module_build, "outputs", "code_coverage"),
        os.path.join(module_build, "reports", "androidTests", "connected"),
    ):
        if not os.path.exists(base):
            continue
        candidates = [base]
        for current, _dirs, filenames in os.walk(base):
            candidates.extend(os.path.join(current, name) for name in filenames)
        for path in candidates:
            try:
                stamp = os.path.getmtime(path)
            except OSError:
                continue
            if latest is None or stamp > latest:
                latest = stamp
    return latest


FINGERPRINT_NAME = "prism-source-fingerprint"


def source_fingerprint(module_dir, root):
    """SHA-256 over "<repo-relative path>\0<sha256 of contents>" for every
    Kotlin source in the module plus its build file.

    Content, not timestamps. `newest_source_mtime` answers a cheaper question
    and gets it wrong in one direction: a `git checkout`, `stash pop`, branch
    switch, or an editor re-saving identical bytes all move a file's mtime
    without changing a byte, and the report that describes that file is then
    declared stale. Measured 2026-08-19 — all five of those operations move
    the mtime of an unchanged file, and one instance was live in this repo at
    the time (`core/domain/build.gradle.kts`, two hours newer than the report,
    byte-identical to HEAD).
    """
    digest = hashlib.sha256()
    paths = []
    base = os.path.join(root, module_dir, "src")
    for current, _dirs, filenames in os.walk(base):
        for filename in filenames:
            if filename.endswith((".kt", ".kts")):
                paths.append(os.path.join(current, filename))
    build_file = os.path.join(root, module_dir, "build.gradle.kts")
    if os.path.exists(build_file):
        paths.append(build_file)
    for path in sorted(paths):
        digest.update(os.path.relpath(path, root).encode("utf-8"))
        digest.update(b"\0")
        try:
            with open(path, "rb") as handle:
                digest.update(hashlib.sha256(handle.read()).hexdigest().encode())
        except OSError:
            digest.update(b"?")
        digest.update(b"\n")
    return digest.hexdigest()


def _fingerprint_path(xml_path):
    return os.path.join(os.path.dirname(xml_path), FINGERPRINT_NAME)


def _read_fingerprint(xml_path):
    try:
        with open(_fingerprint_path(xml_path)) as handle:
            return handle.read().strip() or None
    except OSError:
        return None


def _mtime_freshness(module_dir, root, xml_path, kind, report_stamp, source_stamp):
    """The original, purely-mtime verdict. Kept whole and unchanged: it is the
    cheap pre-check, and every verdict that is about a MISSING artifact rather
    than about a timestamp has to come from here and stay final."""
    if source_stamp is not None and source_stamp > report_stamp:
        return "stale"

    if kind in ("combined", "instrumentation"):
        device_stamp = _newest_execution_data(module_dir, root)
        if device_stamp is None:
            # "Never ran" and "ran and lost its data" need different words
            # because they need different actions from the reader: the first
            # is "go run it", the second is "something took the device from
            # you — serialize the run and try again".
            evidence = _device_run_evidence(module_dir, root)
            if evidence is not None and (source_stamp is None
                                         or evidence >= source_stamp):
                return "device-run-no-data"
            return "no-device-run"
        if source_stamp is not None and source_stamp > device_stamp:
            return "stale-device-run"
        if device_stamp > report_stamp:
            # The device suite ran AFTER the report was written, so the
            # report cannot contain it.
            return "stale-device-run"

    return "fresh"


def report_freshness(module_dir, root, xml_path, kind, record=False):
    """"fresh" | "no-report" | "stale" | "no-device-run" | "device-run-no-data"
    | "stale-device-run".

    Answers "was this report produced from the code that is on disk now",
    which is what lets the push hook CHECK a coverage result instead of
    spending an hour PRODUCING one inside a hook that silently allows the
    push if it times out.
    """
    if not os.path.exists(xml_path):
        return "no-report"

    report_stamp = os.path.getmtime(xml_path)
    source_stamp = newest_source_mtime(module_dir, root)
    verdict = _mtime_freshness(module_dir, root, xml_path, kind,
                               report_stamp, source_stamp)

    # The fingerprint rescues ONLY the two verdicts that are a claim about
    # timestamps. "no-report", "no-device-run" and "device-run-no-data" are
    # claims about MISSING ARTIFACTS, and no amount of unchanged source makes
    # a missing .ec file present.
    if verdict in ("stale", "stale-device-run"):
        recorded = _read_fingerprint(xml_path)
        if recorded is not None and recorded == source_fingerprint(module_dir, root):
            # Re-run with the source timestamps DISREGARDED rather than
            # jumping straight to "fresh": they are the one thing just proved
            # wrong, and every check that does not depend on them still
            # applies. Skipping that let an unchanged source set paper over a
            # missing .ec file.
            verdict = _mtime_freshness(module_dir, root, xml_path, kind,
                                       report_stamp, None)

    if verdict == "fresh" and record:
        # Written only when the mtime path itself said fresh at some point,
        # so what is recorded is provably the content the report was produced
        # from. Skipped when the record is already current, which keeps the
        # steady-state common case a stat rather than a walk of every source.
        fingerprint_file = _fingerprint_path(xml_path)
        try:
            needs_write = (not os.path.exists(fingerprint_file)
                           or os.path.getmtime(fingerprint_file) < report_stamp)
            if needs_write:
                with open(fingerprint_file, "w") as handle:
                    handle.write(source_fingerprint(module_dir, root) + "\n")
        except OSError:
            pass  # a read-only build dir must not turn a verdict into a crash

    return verdict


def _module_dir(gradle_path):
    return gradle_path.lstrip(":").replace(":", "/")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    check = sub.add_parser("check")
    check.add_argument("--module", required=True)
    check.add_argument("--root", required=True)

    has_sources_cmd = sub.add_parser("has-sources")
    has_sources_cmd.add_argument("--module", required=True)
    has_sources_cmd.add_argument("--root", required=True)

    is_android_cmd = sub.add_parser("is-android")
    is_android_cmd.add_argument("--module", required=True)
    is_android_cmd.add_argument("--root", required=True)

    verdict_cmd = sub.add_parser("verdict")
    verdict_cmd.add_argument("--module", required=True)
    verdict_cmd.add_argument("--root", required=True)
    verdict_cmd.add_argument("--xml", required=True)
    verdict_cmd.add_argument("--kind", required=True)
    verdict_cmd.add_argument("--config", required=True)
    verdict_cmd.add_argument(
        "--record", action="store_true",
        help="record the source fingerprint beside a fresh report, so a later "
             "mtime-only change (a checkout, a stash pop, a no-op save) does "
             "not read as staleness. The gates pass this; status.sh does not, "
             "because reporting must not write.",
    )

    threshold_cmd = sub.add_parser("threshold")
    threshold_cmd.add_argument("--module", required=True)
    threshold_cmd.add_argument("--config", required=True)

    record_cmd = sub.add_parser("record")
    record_cmd.add_argument("--module", required=True)
    record_cmd.add_argument("--root", required=True)
    record_cmd.add_argument("--xml", required=True)
    record_cmd.add_argument("--kind", required=True)

    assert_cmd = sub.add_parser("assert")
    assert_cmd.add_argument("--module", required=True)
    assert_cmd.add_argument("--xml", required=True)
    assert_cmd.add_argument("--config", required=True)

    args = parser.parse_args()

    if args.cmd == "check":
        module_dir = _module_dir(args.module)
        if not has_jacoco(module_dir, args.root):
            print("no-jacoco")
            return 0
        print(report_kind(module_dir, args.root))
        return 0

    if args.cmd == "has-sources":
        module_dir = _module_dir(args.module)
        print("yes" if has_sources(module_dir, args.root) else "no")
        return 0

    if args.cmd == "is-android":
        # The EXIT STATUS is the answer -- affected.sh branches on it directly.
        # 0 = Android, 1 = not. Nothing is printed: a caller that mistook
        # stdout for the verdict would read an empty string either way.
        module_dir = args.module if "/" in args.module else _module_dir(args.module)
        return 0 if is_android(module_dir, args.root) else 1

    if args.cmd == "threshold":
        # status.sh needs the required percentage even for a module whose
        # verdict is "stale", where no measurement exists to carry it. Same
        # threshold_for the gate uses, so the number reported and the number
        # enforced are the same number.
        try:
            with open(args.config) as handle:
                config = json.load(handle)
        except (OSError, ValueError):
            print("?")
            return 1
        print(threshold_for(args.module, config))
        return 0

    if args.cmd == "record":
        # STAMP A REPORT WITH THE CONTENT IT WAS PRODUCED FROM, at the moment it
        # was produced. The rescue in report_freshness() has always existed and
        # was reachable only from the push gate, which passes --record; anybody
        # who measured coverage and did not push had no fingerprint on disk, so
        # the first thing that moved an mtime without changing a byte turned a
        # good measurement into `stale`.
        #
        # The differential canary is exactly that: promoting a module tells you
        # to plant a violation, prove the gate fires, and revert it -- and
        # reverting rewrites the file, so PROVING THE GATE IS LIVE COST THE
        # MEASUREMENT. Coverage then had to be produced again, which on a
        # connected suite is minutes and an emulator.
        #
        # IT CAN ONLY EVER RECORD WHAT IS ALREADY TRUE. If the timestamps do not
        # already say `fresh`, this refuses and prints why: a command that could
        # stamp a stale report as current would be a way to declare any coverage
        # number acceptable, which is the escape hatch R7 forbids -- and it would
        # be the most attractive one in the framework.
        module_dir = _module_dir(args.module)
        if not os.path.exists(args.xml):
            print("no-report")
            return 1
        report_stamp = os.path.getmtime(args.xml)
        source_stamp = newest_source_mtime(module_dir, args.root)
        verdict = _mtime_freshness(module_dir, args.root, args.xml, args.kind,
                                   report_stamp, source_stamp)
        if verdict != "fresh":
            print(verdict)
            return 1
        fingerprint_file = _fingerprint_path(args.xml)
        try:
            with open(fingerprint_file, "w") as handle:
                handle.write(source_fingerprint(module_dir, args.root) + "\n")
        except OSError as error:
            print("unwritable %s" % error)
            return 1
        print("recorded")
        return 0

    if args.cmd == "verdict":
        # OBSERVE SHORT-CIRCUITS THE WHOLE VERDICT, not just the comparison.
        # A module nobody has promoted yet has no obligation to have a fresh
        # report, or a report at all -- treating a missing one as a denial
        # there would make `observe` block on exactly the modules it exists to
        # unblock. It still PRINTS what it knows, so status.sh can show the gap
        # somebody is meant to be closing.
        try:
            observed = scope.posture_for(args.module, "coverage", args.root) == "observe"
        except scope.ScopeError as error:
            # Fails closed, like every other reader of this file: a scope file
            # that exists and cannot be parsed denies rather than guesses.
            print("scope-unreadable %s" % error)
            return 1
        if observed:
            print("observe %s" % _observed_detail(args.module, args.root,
                                                  args.xml, args.kind, args.config))
            return 0

        module_dir = _module_dir(args.module)

        # NOTHING TO COVER IS NOT A FAILURE TO COVER, and this is the check that
        # was missing. has_sources() has existed since the first version and the
        # `none` KIND consults it -- but a module with a src/test and no src/main
        # never reaches that arm: its kind is `jvm`, JaCoCo produces a report with
        # zero lines, and the measurement below printed `no-line-data` and DENIED.
        #
        # `:tooling:konsist` is exactly that module, and PRISM's own installer
        # places it. The framework denied pushes on a repository because of code
        # the framework had just written into it. It is also excluded from the
        # gate's module list now (lib/affected.sh), and both fixes stay: the list
        # is about whose code it is, this is about what a report of zero lines
        # means for anybody's module.
        if not has_sources(module_dir, args.root):
            print("no-sources")
            return 0

        freshness = report_freshness(module_dir, args.root, args.xml, args.kind,
                                     record=args.record)
        if freshness != "fresh":
            print(freshness)
            return 1
        try:
            with open(args.config) as handle:
                config = json.load(handle)
            required = threshold_for(args.module, config)
            counter = _line_counter(args.xml)
            if counter is None or (counter[0] + counter[1]) == 0:
                print("no-line-data")
                return 1
            covered, missed = counter
            actual = 100.0 * covered / (covered + missed)
        except (OSError, ValueError, TypeError, ET.ParseError) as error:
            print("unreadable %s" % error)
            return 1
        if actual >= required:
            print("pass %.1f %s" % (actual, required))
            return 0
        print("low %.1f %s" % (actual, required))
        return 1

    try:
        with open(args.config) as handle:
            config = json.load(handle)
        required = threshold_for(args.module, config)
        counter = _line_counter(args.xml)
        if counter is None or (counter[0] + counter[1]) == 0:
            print("ERROR coverage report contains no line data: %s" % args.xml)
            return 1
        covered, missed = counter
        actual = 100.0 * covered / (covered + missed)
        result = 0 if actual >= required else 1
        print("%.1f %s" % (actual, required))
        return result
    except FileNotFoundError as error:
        print("ERROR no coverage report at %s" % error)
        return 1
    except (ValueError, ET.ParseError) as error:
        print("ERROR unreadable coverage data: %s" % error)
        return 1
    except TypeError as error:
        print("ERROR bad threshold configuration: %s" % error)
        return 1


if __name__ == "__main__":
    sys.exit(main())

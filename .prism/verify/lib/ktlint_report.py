#!/usr/bin/env python3
"""ktlint plain-reporter parsing for the per-edit hook.

Two jobs, both kept out of ktlint.sh so they can be tested without a JVM:

  * read the edited file path out of a PostToolUse event, and decide whether
    it is a Kotlin path this gate covers at all;
  * turn ktlint's plain output into the report the agent sees, keeping only
    violations in the file that was actually edited.

The second filter is defensive rather than load-bearing — the hook passes
ktlint one path — but a report that names a file the agent did not touch is
worse than no report, so it is asserted here rather than assumed.
"""

import argparse
import json
import os
import re
import sys

KOTLIN_SUFFIXES = (".kt", ".kts")

# ktlint's plain reporter: `path:line:col: message (rule-id)`. The rule id is
# the last parenthesised group on the line, and messages contain parentheses
# of their own, so the rule is anchored to the end rather than searched for.
# The rule group is `*`, not `+`: a file that is not valid Kotlin at all is
# reported with an EMPTY rule — `... Not a valid Kotlin file (...) ()` — and
# requiring a non-empty id made the most broken possible edit the one edit
# that produced no report.
VIOLATION = re.compile(
    r"^(?P<path>.+?):(?P<line>\d+):(?P<column>\d+): "
    r"(?P<message>.*) \((?P<rule>[A-Za-z0-9_.:-]*)\)$"
)


def edited_path(event):
    """The file path an Edit/Write PostToolUse event wrote, or None."""
    tool_input = event.get("tool_input")
    if not isinstance(tool_input, dict):
        return None
    path = tool_input.get("file_path")
    if not isinstance(path, str) or not path:
        return None
    return path


def is_kotlin(path):
    return path.endswith(KOTLIN_SUFFIXES)


def parse(output):
    """Every distinct violation line in ktlint plain output, in order.

    Deduplicated because `--format` lints twice (before and after the
    autocorrect pass) and prints each residual violation once per pass.
    """
    violations = []
    seen = set()
    for raw in output.splitlines():
        match = VIOLATION.match(raw.strip())
        if not match:
            continue
        key = match.groups()
        if key in seen:
            continue
        seen.add(key)
        violations.append(
            {
                "path": match.group("path"),
                "line": int(match.group("line")),
                "column": int(match.group("column")),
                "message": match.group("message"),
                "rule": match.group("rule"),
            }
        )
    return violations


def _same_file(violation_path, target, root):
    """ktlint reports relative to its working directory; the hook knows the
    absolute path. Compare both ends resolved against that directory."""
    if os.path.isabs(violation_path):
        candidate = violation_path
    else:
        candidate = os.path.join(root, violation_path)
    return os.path.realpath(candidate) == os.path.realpath(target)


def for_file(output, target, root):
    return [v for v in parse(output) if _same_file(v["path"], target, root)]


def render(violations, display_path):
    """The report body written to stderr. Empty when nothing remains."""
    if not violations:
        return ""
    lines = [
        "STATIC ANALYSIS — %d violation%s ktlint could not fix automatically."
        % (len(violations), "" if len(violations) == 1 else "s"),
        "",
    ]
    for violation in violations:
        rendered = "%s:%d:%d: %s" % (
            display_path,
            violation["line"],
            violation["column"],
            violation["message"],
        )
        if violation["rule"]:
            rendered += " (%s)" % violation["rule"]
        lines.append(rendered)
    lines.append("")
    lines.append(
        "Formatting was already corrected in place; these need an actual edit."
    )
    return "\n".join(lines) + "\n"


def _cmd_path(args):
    """Print the edited Kotlin path, or nothing when the event is not one."""
    try:
        event = json.load(sys.stdin)
    except (ValueError, TypeError):
        return 1
    if not isinstance(event, dict):
        return 1
    path = edited_path(event)
    if path is None or not is_kotlin(path):
        return 1
    sys.stdout.write(path)
    return 0


def _cmd_report(args):
    """Print the report for `--file`, exiting 2 when there is one to show.

    A nonzero ktlint exit that produced output but NO parseable violation is
    ktlint failing to run at all (a missing java, an evicted classpath jar),
    and looking clean because the gate did not run is the one failure a gate
    must never have — so it is reported just as loudly as a violation.
    """
    output = sys.stdin.read()
    everything = parse(output)
    body = render(
        [v for v in everything if _same_file(v["path"], args.file, args.root)],
        args.display or args.file,
    )
    if body:
        sys.stderr.write(body)
        return 2
    if args.ktlint_exit != 0 and not everything and output.strip():
        sys.stderr.write(
            "prism-verify: gate 0 did not run — ktlint exited %d without "
            "reporting a violation.\nThis edit was NOT checked. ktlint said:\n\n"
            % args.ktlint_exit
        )
        tail = output.strip().splitlines()[:20]
        sys.stderr.write("\n".join(tail) + "\n")
        return 2
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("path", help="read the edited Kotlin path from a hook event")

    report = sub.add_parser("report", help="render ktlint output for one file")
    report.add_argument("--file", required=True, help="absolute path of the edited file")
    report.add_argument("--root", required=True, help="ktlint's working directory")
    report.add_argument("--display", help="path to show in the report")
    report.add_argument(
        "--ktlint-exit", type=int, default=0,
        help="ktlint's own exit status, to tell 'clean' from 'did not run'",
    )

    args = parser.parse_args(argv)
    return {"path": _cmd_path, "report": _cmd_report}[args.command](args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

#!/usr/bin/env python3
"""Find the three consumer names the design-system rules need, mechanically.

WHY THIS EXISTS. Ten installs of one unchanged repository once produced four
different rule sets, all passing prism-doctor, because six group switches were
decided by an agent reading prose -- and the same harness reached opposite
answers on identical code forty minutes apart. Four of those switches are now
deleted. The two that survive guard a NAME, and a name is not a judgment: a
repository either has a declaration holding its raw colours or it does not.
This probe answers that question the same way every time.

WHAT IT WILL NOT DO. It never guesses between candidates. PRISM's failure mode
is the silent green -- a rule pointed at a name that matches nothing reports
success over the code it was aimed at -- so an ambiguous answer exits 3 and
names every candidate it found. A human picks; the probe records the choice
and the evidence. Being undecided out loud is the supported outcome; being
confidently wrong is the one this file exists to prevent.

Output is JSON on stdout (--json) or a report on stderr-shaped prose. The
values are the ones setup feeds to render.py verbatim, and both the values and
the evidence belong in .prism/INSTALL-RECORD.md.
"""

import argparse
import json
import os
import re
import sys

SKIP_DIRS = {
    ".git", ".gradle", ".kotlin", ".idea", "build", "generated",
    ".prism", ".claude", "prism-setup", "node_modules",
}
# PRISM's own modules ship with the framework; their colours are not the
# consumer's. Matched on path segments so a nested layout still drops out.
PRISM_OWNED = ("tooling/prism-rules", "tooling/konsist")

DECL = re.compile(
    r"^\s*(?:(?:public|internal|private|abstract|sealed|open)\s+)*"
    r"(?:data\s+|value\s+)?(object|class|interface)\s+(\w+)"
)
COMPOSABLE_FUN = re.compile(r"^\s*(?:(?:public|internal|private)\s+)?fun\s+(\w*Theme)\s*[(<]")
COLOR_LITERAL = re.compile(r"\bColor\s*\(\s*0[xX]")
TOP_LEVEL_COLOR = re.compile(r"^(?:(?:public|internal|private)\s+)?val\s+\w+[^=]*=\s*Color\s*\(\s*0[xX]")
LOCAL_OF = re.compile(r"(?:static)?[cC]ompositionLocalOf\s*<\s*([\w.]+)")
COLOR_PROP = re.compile(r"^\s*(?:va[lr])\s+\w+\s*:\s*Color\b")


def kotlin_files(root):
    """Every consumer-owned .kt file under root, newest-layout agnostic."""
    for directory, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        rel = os.path.relpath(directory, root).replace(os.sep, "/")
        if any(seg in rel for seg in PRISM_OWNED):
            continue
        for name in names:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(directory, name)
            relpath = os.path.relpath(path, root).replace(os.sep, "/")
            if "/src/test/" in "/" + relpath or "/src/androidTest/" in "/" + relpath:
                continue
            try:
                with open(path, encoding="utf-8", errors="replace") as handle:
                    yield relpath, handle.read().split("\n")
            except OSError:
                continue


def declaration_bodies(lines):
    """Yield (kind, name, start_line, body_lines) for each declaration.

    Brace-counted rather than parsed. A Kotlin parser is not available to the
    payload -- it is stdlib-only by construction -- and brace counting is wrong
    only for braces inside strings or comments, which a colour palette does not
    contain.

    THE CONSTRUCTOR LIST IS PART OF THE BODY. `data class ExtendedColors(val
    success: Color, val warning: Color)` declares its properties in the
    parentheses and may have no braces at all. An earlier version of this
    scanned forward for the next `{` when a declaration had none, which
    silently attributed an UNRELATED later declaration's contents to this one.
    A wrong name is the one outcome this file exists to prevent, so the scan
    now stops where the declaration stops.
    """
    index = 0
    while index < len(lines):
        match = DECL.match(lines[index])
        if not match:
            index += 1
            continue
        kind, name = match.group(1), match.group(2)
        body, cursor = [], index
        parens = 0
        # 1. the declaration head, including a multi-line constructor list.
        while cursor < len(lines):
            line = lines[cursor]
            body.append(line)
            parens += line.count("(") - line.count(")")
            cursor += 1
            if parens <= 0:
                break
        # 2. a body, but only if it opens where the declaration ends.
        opener = cursor - 1
        if "{" not in body[-1]:
            probe_at = cursor
            while probe_at < len(lines) and not lines[probe_at].strip():
                probe_at += 1
            if probe_at < len(lines) and lines[probe_at].strip().startswith("{"):
                body.append(lines[probe_at])
                cursor = probe_at + 1
                opener = probe_at
        if "{" in lines[opener]:
            depth = sum(line.count("{") - line.count("}") for line in body)
            while cursor < len(lines) and depth > 0:
                body.append(lines[cursor])
                depth += lines[cursor].count("{") - lines[cursor].count("}")
                cursor += 1
        yield kind, name, index + 1, body
        index = max(cursor, index + 1)


def probe(root):
    palettes, holders, themes, loose_colours = [], [], [], []
    local_types = set()

    for relpath, lines in kotlin_files(root):
        for number, line in enumerate(lines, 1):
            found = LOCAL_OF.search(line)
            if found:
                local_types.add(found.group(1).rsplit(".", 1)[-1])
            if TOP_LEVEL_COLOR.match(line):
                loose_colours.append("%s:%d" % (relpath, number))
            theme = COMPOSABLE_FUN.match(line)
            if theme and any("@Composable" in lines[back]
                             for back in range(max(0, number - 6), number)):
                themes.append((theme.group(1), "%s:%d" % (relpath, number)))

        for kind, name, start, body in declaration_bodies(lines):
            if kind == "interface":
                continue
            colours = sum(1 for line in body if COLOR_LITERAL.search(line))
            props = sum(1 for line in body if COLOR_PROP.match(line))
            where = "%s:%d" % (relpath, start)
            # A palette HOLDS literals. A token holder DECLARES Color-typed
            # properties and is carried by a CompositionLocal. The two are told
            # apart by that, not by their names -- naming conventions are
            # exactly what does not travel between repositories.
            if colours >= 3:
                palettes.append((name, where, colours))
            if props >= 2:
                holders.append((name, where, props))

    holders = [h for h in holders if h[0] in local_types] or holders
    return {
        "palettes": sorted(palettes, key=lambda item: -item[2]),
        "holders": sorted(holders, key=lambda item: -item[2]),
        "themes": themes,
        "loose_colours": loose_colours,
    }


def resolve(found):
    """Turn findings into the five values, or refuse to choose."""
    values, evidence, ambiguous = {}, {}, []

    def one(key, candidates, absent_note):
        if not candidates:
            values[key] = "none"
            evidence[key] = absent_note
            return False
        names = sorted({item[0] for item in candidates})
        if len(names) > 1:
            ambiguous.append("%s: %s" % (key, ", ".join(
                "%s (%s)" % (item[0], item[1]) for item in candidates)))
            values[key] = "none"
            evidence[key] = "AMBIGUOUS -- %d candidates" % len(names)
            return False
        values[key] = candidates[0][0]
        evidence[key] = candidates[0][1]
        return True

    has_palette = one("PALETTE_OBJECT", found["palettes"],
                      "no declaration holds three or more raw Color literals")
    has_holder = one("EXTENDED_TOKEN_HOLDER", found["holders"],
                     "no class declares two or more Color-typed properties")
    has_theme = one("THEME_OBJECT", found["themes"],
                    "no @Composable function named *Theme")

    # PALETTE guards TWO names, so it is true only when both resolved --
    # render.py refuses a true switch beside a sentinel name, and it is right
    # to. A repository with a palette but no token holder is a real shape and
    # its honest answer is false, recorded with the reason.
    values["PALETTE_RULES_ACTIVE"] = "true" if (has_palette and has_holder) else "false"
    values["THEME_RULES_ACTIVE"] = "true" if has_theme else "false"

    # A name found but not usable is reported as found-and-shelved, never as
    # simply absent. "We looked and there is nothing" and "we found one but the
    # group it belongs to is off" are different facts, and the second is the
    # one a reader needs to reopen the decision later.
    def shelve(key, switch):
        if values[key] != "none":
            evidence[key] = "found at %s, unused: %s=false" % (evidence[key], switch)
        values[key] = "none"

    if values["PALETTE_RULES_ACTIVE"] == "false":
        shelve("PALETTE_OBJECT", "PALETTE_RULES_ACTIVE")
        shelve("EXTENDED_TOKEN_HOLDER", "PALETTE_RULES_ACTIVE")
    if values["THEME_RULES_ACTIVE"] == "false":
        shelve("THEME_OBJECT", "THEME_RULES_ACTIVE")

    return values, evidence, ambiguous


def report(found, values, evidence, ambiguous, stream):
    write = stream.write
    write("PRISM design-system probe\n\n")
    for key in ("PALETTE_OBJECT", "EXTENDED_TOKEN_HOLDER", "THEME_OBJECT",
                "PALETTE_RULES_ACTIVE", "THEME_RULES_ACTIVE"):
        write("  %-22s %-24s %s\n" % (key, values[key], evidence.get(key, "")))
    if found["loose_colours"] and values["PALETTE_OBJECT"] == "none":
        write("\n  %d raw Color literals sit at top level, in files including:\n"
              % len(found["loose_colours"]))
        for where in found["loose_colours"][:5]:
            write("    %s\n" % where)
        write("  There is no declaration to name. PALETTE_RULES_ACTIVE=false is\n"
              "  the correct answer, and those colours are checked by no rule.\n"
              "  Record that in .prism/INSTALL-RECORD.md -- it is a gap you chose.\n")
    if ambiguous:
        write("\nAMBIGUOUS -- a human must pick, and say why:\n")
        for line in ambiguous:
            write("  %s\n" % line)
    write("\n")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--json", action="store_true",
                        help="print only the values, for --values")
    args = parser.parse_args(argv)

    found = probe(args.root)
    values, evidence, ambiguous = resolve(found)

    if args.json:
        json.dump(values, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
    else:
        report(found, values, evidence, ambiguous, sys.stdout)
    return 3 if ambiguous else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

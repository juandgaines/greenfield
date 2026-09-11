# Tests for ./prism — the three verbs, and the two collisions they resolved.
# Sourced by run-tests.sh.
#
# THE INTERFACE IS THE FEATURE HERE. The first person to install PRISM from
# outside this repository reported the CLI as "way too complicated ... it would
# be intuitive to just have a setup command and that's it", and the count backed
# him up: 41 invocable things, 17 of them pure reporting. So the assertions below
# are unusually interested in what the help text says, because a shipped command
# nobody can find is the same defect as one that does not work.
#
# Two collisions are pinned:
#
#   1. `enforce` MEANT TWO DIFFERENT THINGS. `./prism enforce :mod` rewrote one
#      module's scope entry; `prism-verify enforce` installed the floor, removed
#      scope.json and wrote the record. prism-doctor recommended the second by
#      name, from inside a repository where the first was what you had. The
#      action has one name now, and one implementation.
#   2. THERE WERE TWO `status` COMMANDS, each holding half the truth, and
#      ./prism's own help apologised for it in writing: "./prism status shows
#      posture and baselines, not coverage."
#
# And M3: the suppressed count printed detekt's XML tag names as though they were
# English, so the screen said `suppressed=0` beside 330 entries that were all
# suppressed. That inverts the one number a promotion decision turns on, which is
# why tp_status asserts on the reading and not merely on the number.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

TP_PRISM="$HOOK_DIR/../prism"

# An installed repository: the fixture, plus the two things an install places
# that the fixtures do not -- ./prism at the root and the git adapter.
tp_repo() {
    repo=$(prism_fixture_repo)
    cp "$TP_PRISM" "$repo/prism"
    chmod +x "$repo/prism"
    mkdir -p "$repo/.prism/adapters/git"
    cp "$HOOK_DIR/../adapters/git/install.sh" \
       "$HOOK_DIR/../adapters/git/pre-push" "$repo/.prism/adapters/git/"
    printf '%s' "$repo"
}

tp_config() {
    # $1 repo, $2 posture
    python3 -c '
import json, sys
path = sys.argv[1]
with open(path) as handle:
    config = json.load(handle)
config["posture"] = sys.argv[2]
config["harness"] = "none"
json.dump(config, open(path, "w"), indent=2)
' "$1/.prism/prism.json" "$2"
}

tp_scope() {
    # $1 repo, $2 posture for all four engines
    python3 -c '
import json, sys
E = ("detekt", "ktlint", "konsist", "coverage")
json.dump({"default": dict.fromkeys(E, sys.argv[2]), "modules": {}},
          open(sys.argv[1], "w"), indent=2)
' "$1/.prism/scope.json" "$2"
}

tp_baseline() {
    # $1 the directory the baseline belongs to -- a MODULE directory since 0.6.0,
    # because one root file could not tell two modules with a same-named file
    # apart. Remaining args = suppressed ids.
    repo=$1
    shift
    mkdir -p "$repo"
    {
        printf '<?xml version="1.0" ?>\n<SmellBaseline>\n'
        printf '  <ManuallySuppressedIssues/>\n  <CurrentIssues>\n'
        for id in "$@"; do printf '    <ID>%s</ID>\n' "$id"; done
        printf '  </CurrentIssues>\n</SmellBaseline>\n'
    } >"$repo/detekt.baseline.xml"
}

# Sets TP_OUT and TP_CODE rather than printing. `x=$(tp_run ...)` would run the
# function in a SUBSHELL, so every exit status it recorded would be discarded at
# the closing paren -- and an assertion on a status that is always the previous
# one is an assertion that passes for the wrong reason.
tp_run() {
    # $1 repo, rest = argv
    repo=$1
    shift
    TP_OUT=$( cd "$repo" && sh ./prism "$@" 2>&1 )
    TP_CODE=$?
}

tp_hook() {
    printf '%s' "$1/$(git -C "$1" rev-parse --git-path hooks)/pre-push"
}

tp_posture() {
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("posture","-"))' \
        "$1/.prism/prism.json"
}

# The per-engine posture, resolved the way every reader resolves it: an engine
# .prism/scope.json does not name ENFORCES, so an absent key is not "unset".
tp_engine() {
    python3 -c '
import os, sys
sys.path.insert(0, os.environ["HOOK_DIR"] + "/lib")
import scope
default, _ = scope.load(sys.argv[1])
print(default.get(sys.argv[2], "?"))
' "$1" "$2"
}

# --- the help text IS the interface --------------------------------------
tp1=$(tp_repo)
tp_run "$tp1" --help
tp1_out=$TP_OUT
assert_contains "$tp1_out" "./prism status" "the help names status"
assert_contains "$tp1_out" "./prism promote" "the help names promote"
assert_contains "$tp1_out" "./prism promote --engine" \
    "the help names the engine axis, so the guides can stop naming scope.py"
assert_contains "$tp1_out" "./prism doctor" "the help names doctor"
assert_not_contains "$tp1_out" "./prism setup" \
    "and NOT setup: the installer removes itself, so that verb could only mislead"
assert_not_contains "$tp1_out" "./prism guard" \
    "and does NOT tell a human to type the internals"
# 0.6.3: `baseline` IS the interface now. The five subcommands underneath it
# still are not -- the help names the verb, not `baseline drop --rule`.
assert_contains "$tp1_out" "./prism baseline" "the help names baseline"
assert_contains "$tp1_out" "./prism coverage" "the help names coverage"
assert_contains "$tp1_out" "./prism probe" "the help names probe"
assert_not_contains "$tp1_out" "./prism baseline drop" \
    "but not its five subcommands"
assert_not_contains "$tp1_out" "./prism enforce" \
    "the old name is accepted but never advertised"

# --- the internals still work, which is why removing them was never the fix ---
#
# `report` was a verb of its own and is now part of `status`; the rest are what
# the gates and the agent skills call. A refactor that shrank the help text by
# breaking them would break the hooks.
tp2=$(tp_repo)
tp_run "$tp2" version
tp2_out=$TP_OUT
assert_ne "0" "$TP_CODE" "version with no .prism/VERSION still reports the reason"
printf '9.9.9\n' > "$tp2/.prism/VERSION"
tp_run "$tp2" version
tp2_out=$TP_OUT
assert_eq "9.9.9" "$tp2_out" "version still reads .prism/VERSION"
tp_run "$tp2" baseline count
tp2_out=$TP_OUT
assert_contains "$tp2_out" "suppressed=" "baseline is still reachable for skills"

# --- promote refuses to guess at scope -----------------------------------
#
# A bare `promote` used to be the whole-repository form. Promoting everything is
# not something anybody should reach by leaving a word off.
tp3=$(tp_repo)
tp_config "$tp3" "observe"
tp_scope "$tp3" "observe"
tp_run "$tp3" promote
tp3_out=$TP_OUT
assert_eq "2" "$TP_CODE" "bare promote exits 2"
assert_contains "$tp3_out" ":a:module" "and names the one-module form"
assert_contains "$tp3_out" "--all" "and names the whole-repository form"
assert_eq "observe" "$(python3 -c '
import json,sys; print(json.load(open(sys.argv[1]))["default"]["detekt"])' \
    "$tp3/.prism/scope.json")" "and changes nothing"

# --- promote :module ------------------------------------------------------
tp4=$(tp_repo)
tp_config "$tp4" "observe"
tp_scope "$tp4" "observe"
# The same id in two modules: detekt writes the bare filename, so `Shared.kt` in
# :core:domain and :core:other produce one identical entry each.
tp_baseline "$tp4/core/domain" "MagicNumber:Shared.kt:Shared\$1"
tp_baseline "$tp4/core/other" "MagicNumber:Shared.kt:Shared\$1"
mkdir -p "$tp4/core/domain"
printf '<baseline/>\n' > "$tp4/core/domain/ktlint.baseline.xml"
tp_run "$tp4" promote :core:domain
tp4_out=$TP_OUT
assert_contains "$tp4_out" "now enforces every engine" "promote :module reports the flip"
assert_eq "enforce" "$(python3 -c '
import json,sys; print(json.load(open(sys.argv[1]))["modules"][":core:domain"]["ktlint"])' \
    "$tp4/.prism/scope.json")" "and writes all four engines for that module"
assert_eq "observe" "$(python3 -c '
import json,sys; print(json.load(open(sys.argv[1]))["default"]["detekt"])' \
    "$tp4/.prism/scope.json")" "and leaves every other module alone"
[ -f "$tp4/core/domain/ktlint.baseline.xml" ] && tp4_kt=present || tp4_kt=gone
assert_eq "gone" "$tp4_kt" "and retires that module's ktlint baseline"
[ -f "$tp4/core/domain/detekt.baseline.xml" ] && tp4_dt=present || tp4_dt=gone
assert_eq "gone" "$tp4_dt" "and its detekt baseline, which is a file now rather than a search"
assert_eq "1" "$(python3 -c "
import xml.etree.ElementTree as ET, sys
root = ET.parse(sys.argv[1]).getroot()
print(len(root.find('CurrentIssues').findall('ID')))" "$tp4/core/other/detekt.baseline.xml")" \
    "while the sibling module's identical entry survives — the false green M6 caused"

# --- baseline: one command for a two-step contract -------------------------
#
# The two steps cannot be collapsed, only wrapped. detekt's creation task
# REPLACES the file it is pointed at, a module runs two or three of them, so
# each writes a per-task fragment and baseline.py unions them. Run the union
# first and there is nothing to union.
#
# Until 0.6.3 `./prism baseline` was a bare passthrough that ran no Gradle at
# all, so the shipped documents had to spell both halves -- and it did not
# inject --root, so `count` silently depended on the caller's cwd.
tpb=$(tp_repo)
prism_fixture_gradlew "$tpb"
# What ./gradlew prismBaseline would have written: one fragment per detekt task,
# under the module's build/. The stub cannot produce them, so the test does --
# and that is the point of the ordering being asserted below.
mkdir -p "$tpb/core/domain/build/prism/baseline"
cat > "$tpb/core/domain/build/prism/baseline/detekt.xml" <<'FRAG'
<?xml version="1.0" ?>
<SmellBaseline>
  <ManuallySuppressedIssues/>
  <CurrentIssues>
    <ID>NoConsoleLogging:Thing.kt$println("x")</ID>
  </CurrentIssues>
</SmellBaseline>
FRAG
tp_run "$tpb" baseline
assert_eq "0" "$TP_CODE" "bare baseline succeeds"
assert_contains "$(cat "$tpb/.fixture-gradle-tasks" 2>/dev/null)" "prismBaseline" \
    "it runs ./gradlew prismBaseline FIRST -- without the fragments there is nothing to union"
assert_path_exists "$tpb/core/domain/detekt.baseline.xml" \
    "and then unions the fragments into the module's baseline"

# --force is reachable through the library and never passed for you: it absorbs
# every violation written since the last baseline, permanently and silently.
tp_run "$tpb" baseline --force
assert_ne "0" "$TP_CODE" "--force is refused"
assert_contains "$TP_OUT" "ABSORBS every violation" "and says what it would cost"
assert_contains "$TP_OUT" "./prism baseline drop --rule" "and names the honest alternative"

# The subcommands still work, because the gates and the burndown skill call
# them -- now with --root supplied rather than inherited from the cwd.
tp_run "$tpb" baseline count
assert_contains "$TP_OUT" "suppressed=" "baseline count still reports, for the skills"

# --- coverage: derive every argument from the module name -------------------
#
# Until 0.6.3 the shipped documents asked a human to type four values that are
# all derivable from one:
#   coverage.py record --module :app --root . --kind jvm --xml app/build/...
tpc=$(tp_repo)
prism_fixture_gradlew "$tpc"
tp_run "$tpc" coverage :core:domain
assert_eq "0" "$TP_CODE" "coverage runs"
assert_contains "$TP_OUT" ":core:domain" "and names the module it looked at"

# NO SOURCING OF affected.sh MAY USE THE ASSIGNMENT-PREFIX FORM. On macOS
# /bin/sh is bash 3.2 in POSIX mode, where `VAR=x . file` inside a function
# loses VAR after the builtin returns -- reproduced directly:
#
#   f() { V=1 . /dev/null; echo "${V-UNSET}"; }   -> UNSET
#   f() { V=1 : ;          echo "${V-UNSET}"; }   -> 1
#
# It is specific to `.`, and it is STRICTLY WORSE than writing no prefix at all,
# because affected.sh's own `PRISM_ROOT="${PRISM_ROOT:-$(git rev-parse ...)}"`
# fallback would have covered the bare case. The prefix defeats its own safety
# net. Three sites carried this shape; one of them shipped broken.
tp_src=$(cat "$TP_PRISM")
assert_not_contains "$tp_src" 'PRISM_ROOT="$PRISM_HOME" . ' \
    "no sourcing of affected.sh uses the assignment-prefix form"

# AND THE REASON MUST REACH THE READER. `2>/dev/null` on prism_all_modules turned
# "PRISM_ROOT: unbound variable" into "no modules found -- is this the repository
# root?", which blames the consumer's settings.gradle.kts for a bug in ours.
assert_not_contains "$tp_src" 'prism_all_modules 2>/dev/null' \
    "the module listing does not swallow the reason it failed"

# WITH NO ARGUMENT IT MUST FIND THE MODULES ITSELF. This failed on a real
# install with "no modules found": `VAR=x . file` does not reliably carry VAR
# into the sourced file under /bin/sh on macOS, and prism_all_modules reads
# PRISM_ROOT. The pattern had been in this file since 0.6.0 and got away with it
# only because the function it borrowed, prism_own_modules, prints two literals
# and never reads the variable.
tp_run "$tpc" coverage
assert_not_contains "$TP_OUT" "no modules found" \
    "bare ./prism coverage resolves the module list"
assert_contains "$TP_OUT" ":core:" "and names the modules it found"

# THE CONNECTED RUN GOES THROUGH THE LOCK OR NOT AT ALL. Two connected runs of
# the same test package on one emulator kill each other -- measured on
# :feature:files:presentation, where the killed run could not pull back its .ec
# and coverage then read `no-device-run`, which looks exactly like "you never
# ran it". Until 0.6.3 the lock was something status.sh PRINTED and a human was
# trusted to paste; now the verb runs it.
assert_contains "$(cat "$TP_PRISM")" 'device_lock.py' \
    "./prism coverage routes the connected run through the device lock"
assert_contains "$(cat "$TP_PRISM")" 'select_emulator.py' \
    "and finds a running emulator rather than booting one"
assert_not_contains "$(cat "$TP_PRISM")" 'android emulator start "' \
    "PRISM never starts a virtual machine on somebody's computer"

# A module with no test sources has nothing to measure, and says so rather
# than failing.
tp_run "$tpc" coverage :nope:nothing
assert_contains "$TP_OUT" "nothing to measure" "a module with no suite is reported, not failed"

# --- promote --engine: the OTHER axis, and the reason it exists ------------
#
# A module moves all four engines; an engine moves every module. The greenfield
# install ends on the second one -- konsist and coverage observe out of the box,
# because a JUnit suite has no baseline and nothing has measured coverage yet --
# and until 0.6.2 there was no verb for it. The shipped guide told a human to
# type `python3 .prism/verify/lib/scope.py set --default --engine konsist
# --posture enforce` to finish a normal install.
tpe=$(tp_repo)
tp_scope "$tpe" observe
tp_run "$tpe" promote --engine konsist
assert_eq "0" "$TP_CODE" "promote --engine succeeds"
assert_eq "enforce" "$(tp_engine "$tpe" konsist)" "the named engine now enforces"
assert_eq "observe" "$(tp_engine "$tpe" detekt)" "and no other engine moved"
assert_eq "observe" "$(tp_engine "$tpe" coverage)" "including the other observing one"

# It only ever strengthens, which is what lets it share promote's no-weakening
# rule. There is deliberately no --posture here to pass "observe" to.
tp_run "$tpe" promote --engine konsist
assert_eq "0" "$TP_CODE" "running it twice is not an error"
assert_eq "enforce" "$(tp_engine "$tpe" konsist)" "and leaves the engine enforcing"

tp_run "$tpe" promote --engine kotlint
assert_ne "0" "$TP_CODE" "a misspelled engine is refused"
assert_contains "$TP_OUT" "is not a PRISM engine" "by name, with the four listed"
assert_contains "$TP_OUT" "detekt ktlint konsist coverage" "so the typo can be fixed"

tp_run "$tpe" promote --engine
assert_ne "0" "$TP_CODE" "--engine requires an engine"
tp_run "$tpe" promote --engine konsist detekt
assert_ne "0" "$TP_CODE" "and exactly one -- each needs its own green build"

# --- promote --all: the floor, the scope file, then the record ------------
#
# THE ORDER IS THE PROPERTY, not the outcome. It came from prism-verify enforce
# and it survives the move: an aborted promotion must leave the record HONEST,
# because a posture of "enforce" over a repository with no floor makes the doctor
# report a correct install as broken with no way to tell which half is wrong.
tp5=$(tp_repo)
tp_config "$tp5" "observe"
tp_scope "$tp5" "observe"
tp_run "$tp5" promote --all
tp5_out=$TP_OUT
assert_contains "$tp5_out" "Removed   .prism/scope.json" "promote --all removes the scope file"
[ -f "$tp5/.prism/scope.json" ] && tp5_scope=present || tp5_scope=gone
assert_eq "gone" "$tp5_scope" "which is what actually enforces"
assert_eq "enforce" "$(tp_posture "$tp5")" "and the record follows"
tp5_hook=$(tp_hook "$tp5")
[ -x "$tp5_hook" ] && tp5_floor=installed || tp5_floor=missing
assert_eq "installed" "$tp5_floor" "and the pre-push floor is installed and executable"
assert_contains "$tp5_out" "prism-doctor" "and the verdict is left to the doctor"

# --- and it is idempotent -------------------------------------------------
tp_run "$tp5" promote --all
tp5b_out=$TP_OUT
assert_contains "$tp5b_out" "already enforces everywhere" \
    "a second promote --all reports the state rather than failing"
assert_eq "enforce" "$(tp_posture "$tp5")" "and the record is unchanged"

# --- a floor that cannot be installed leaves the record alone ------------
#
# Somebody else's pre-push hook is the realistic case: install.sh refuses to
# overwrite a hook it did not write, on purpose. The record must not advance past
# what is installed.
tp6=$(tp_repo)
tp_config "$tp6" "observe"
tp_scope "$tp6" "observe"
tp6_hook=$(tp_hook "$tp6")
mkdir -p "$(dirname "$tp6_hook")"
printf '#!/bin/sh\n# somebody else s secret scanner\nexit 0\n' > "$tp6_hook"
chmod +x "$tp6_hook"
tp_run "$tp6" promote --all
tp6_out=$TP_OUT
assert_ne "0" "$TP_CODE" "promote --all fails when the floor cannot be installed"
[ -f "$tp6/.prism/scope.json" ] && tp6_scope=present || tp6_scope=gone
assert_eq "present" "$tp6_scope" "the scope file is untouched"
assert_eq "observe" "$(tp_posture "$tp6")" "and the record still says observe"
assert_contains "$(cat "$tp6_hook")" "secret scanner" "and their hook is still theirs"

# --- the old name still works, and says what it is now -------------------
tp7=$(tp_repo)
tp_config "$tp7" "observe"
tp_scope "$tp7" "observe"
tp_run "$tp7" enforce :core:domain
tp7_out=$TP_OUT
assert_contains "$tp7_out" "is \`promote\` now" "enforce explains the rename"
assert_contains "$tp7_out" "now enforces every engine" "and does the work anyway"

# --- status: one screen, and the suppressed count reads forwards ---------
tp8=$(tp_repo)
tp_config "$tp8" "observe"
tp_scope "$tp8" "observe"
# Two modules, one of them holding a file with the same name as the other's --
# the shape that used to be recorded as a single entry.
tp_baseline "$tp8/core/domain" "MagicNumber:A.kt:A\$1" "MagicNumber:Shared.kt:Shared\$2"
tp_baseline "$tp8/core/other" "MagicNumber:Shared.kt:Shared\$2"
tp_run "$tp8" status
tp8_out=$TP_OUT
assert_contains "$tp8_out" "POSTURE" "status shows posture"
assert_contains "$tp8_out" "SUPPRESSED" "status shows what is suppressed"
assert_contains "$tp8_out" "COVERAGE" "status shows coverage — it used to be a separate script"
assert_contains "$tp8_out" "3 findings suppressed" "and says how many, in that direction"
assert_contains "$tp8_out" ":core:domain" \
    "and which module each baseline belongs to — there is one per module now"
assert_contains "$tp8_out" ":core:other" \
    "including the sibling holding an identical entry, which used to be the same one"
assert_not_contains "$tp8_out" "suppressed=0" \
    "M3: the screen must never say suppressed=0 beside three suppressed findings"
assert_not_contains "$tp8_out" "current=" \
    "detekt's XML tag names are not English and are not printed as if they were"
# The contract between two files: ./prism reads the FIRST field of what
# baseline.py prints. If either side renames it, this repository ends up telling
# people their baseline is corrupt -- which is a worse lie than the one M3 was.
assert_not_contains "$tp8_out" "does not parse" \
    "and a baseline that parses is never reported as corrupt"
assert_not_contains "$tp8_out" "not coverage" \
    "and the help text stops apologising for showing only half the truth"

# --- setup repairs a hook git would skip in silence ----------------------
#
# git skips a non-executable hook with no warning, no output and exit 0. Under
# EITHER posture that is an install which looks wired and enforces nothing, so
# this is the one repair that does not consult the record.
tp9=$(tp_repo)
tp_config "$tp9" "observe"
tp9_hook=$(tp_hook "$tp9")
mkdir -p "$(dirname "$tp9_hook")"
printf '#!/bin/sh\n# PRISM_ADAPTER_MARKER: prism-verify/adapters/git/pre-push\nexit 0\n' \
    > "$tp9_hook"
chmod -x "$tp9_hook"
tp_run "$tp9" setup
tp9_out=$TP_OUT
assert_contains "$tp9_out" "chmod +x" "setup fixes a non-executable hook"
[ -x "$tp9_hook" ] && tp9_x=yes || tp9_x=no
assert_eq "yes" "$tp9_x" "and the hook is executable afterwards"
assert_contains "$tp9_out" "VERDICT" "and hands the verdict to the doctor"

# --- setup will NOT install enforcement somebody declined ---------------
tp10=$(tp_repo)
tp_config "$tp10" "observe"
tp_run "$tp10" setup
tp10_out=$TP_OUT
tp10_hook=$(tp_hook "$tp10")
[ -e "$tp10_hook" ] && tp10_floor=installed || tp10_floor=absent
assert_eq "absent" "$tp10_floor" \
    "an observe install declined the floor; a 'repair' that installs it is not a repair"
assert_contains "$tp10_out" "nothing to repair" "and setup says so plainly"

# --- but a missing floor under an enforcing record IS a gap -------------
tp11=$(tp_repo)
tp_config "$tp11" "enforce"
tp_run "$tp11" setup
tp11_out=$TP_OUT
tp11_hook=$(tp_hook "$tp11")
[ -x "$tp11_hook" ] && tp11_floor=installed || tp11_floor=absent
assert_eq "installed" "$tp11_floor" \
    "the record says this repository enforces, so the floor is reinstalled"
assert_contains "$tp11_out" "MISSING" "and setup names what it found"

# --- setup is not the installer -----------------------------------------
tp12=$(tp_repo)
tp_run "$tp12" doctor somewhere
tp12_out=$TP_OUT
assert_eq "2" "$TP_CODE" "doctor takes no arguments"
assert_contains "$tp12_out" "./prism-verify" \
    "and points at the installer for a repository with no install yet"
assert_not_contains "$tp12_out" "prism-verify setup" \
    "naming a subcommand the installer no longer has"

# `setup` still reaches the same code, because shell history and 0.6.1's
# documents say it. It announces the rename rather than pretending.
tp_run "$tp12" setup
assert_contains "$TP_OUT" "\`setup\` is \`doctor\` now" \
    "the old verb is accepted and says so"

# --- the status screen shows the consumer's modules, not PRISM's ---------
#
# MEASURED ON AN ACCEPTANCE INSTALL. `./gradlew prismBaseline` writes a
# ktlint.baseline.xml beside every module that applies prism.ktlint, and the two
# PRISM ships are two of them. Both came out EMPTY -- PRISM's own source is
# clean -- so nothing was hidden; what was wrong is that one screen excluded
# those two modules from the posture table (scope.py) and from the coverage
# table (affected.sh) and then listed them under SUPPRESSED, above the
# consumer's only real module. A report of what YOUR repository defers should
# not require knowing which two directories belong to the framework.
tp13=$(tp_repo)
tp_config "$tp13" "observe"
tp_scope "$tp13" "observe"
printf 'include(":tooling:prism-rules")\ninclude(":tooling:konsist")\n' \
    >> "$tp13/settings.gradle.kts"
for d in core/domain tooling/prism-rules tooling/konsist; do
    mkdir -p "$tp13/$d"
    printf '<?xml version="1.0"?>\n<baseline><file name="X.kt"/></baseline>\n' \
        > "$tp13/$d/ktlint.baseline.xml"
done
tp_run "$tp13" status
tp13_out=$TP_OUT
assert_contains "$tp13_out" "core/domain" \
    "the consumer's module is listed under SUPPRESSED"
assert_not_contains "$tp13_out" "tooling/prism-rules " \
    "the rule set's own baseline is not"
assert_not_contains "$tp13_out" "tooling/konsist " \
    "and neither is the konsist module's"
assert_contains "$tp13_out" "1 module baseline(s)" \
    "and the COUNT is the consumer's one, not all three"

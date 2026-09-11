# Tests for prism-doctor's posture checks — D6, D10, D11. Sourced by run-tests.sh.
#
# `observe` exists so that a repository which did not grow up under 72 rules is
# not red on day one. Getting that wrong has a specific shape, and it is not
# "the gate lets something through": it is A DOCTOR THAT FAILS ON A SUPPORTED
# CONFIGURATION. That was the recorded reason for rejecting this design once
# already -- a doctor people learn to ignore stops catching the installs that
# are genuinely broken, and the framework loses its only mechanical judge.
#
# So the cases below are mostly about the report rather than the exit code, and
# they pin both directions: an observe install with no floor must PASS and say
# it was chosen, while an enforcing one with no floor must still FAIL.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

DP_DOCTOR="$HOOK_DIR/prism-doctor"

# A repository just complete enough to reach D6/D10/D11. Checks before them
# fail here and that is fine -- the assertions read only the lines under test,
# which keeps this fast and free of any Gradle build.
dp_repo() {
    repo=$(prism_fixture_repo)
    mkdir -p "$repo/.prism/verify/lib"
    cp "$HOOK_DIR/lib/baseline.py" "$repo/.prism/verify/lib/baseline.py"
    printf '%s' "$repo"
}

dp_config() {
    # $1 repo, $2 posture value (omit the key entirely when empty)
    python3 -c '
import json, sys
path = sys.argv[1]
config = {"baseBranch": "main", "harness": "none"}
if len(sys.argv) > 2 and sys.argv[2]:
    config["posture"] = sys.argv[2]
json.dump(config, open(path, "w"), indent=2)
' "$1/.prism/prism.json" "$2"
}

dp_install_floor() {
    hooks=$(git -C "$1" rev-parse --git-path hooks)
    mkdir -p "$1/$hooks"
    printf '#!/bin/sh\n# PRISM_ADAPTER_MARKER: prism-verify/adapters/git/pre-push\nexit 0\n' \
        >"$1/$hooks/pre-push"
    chmod +x "$1/$hooks/pre-push"
}

dp_baseline() {
    # $1 the directory the baseline belongs to (a repo root, or a module dir),
    # remaining args = baseline ids. One baseline per MODULE since 0.6.0.
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

dp_run() {
    ( cd "$1" && PRISM_ROOT="$1" sh "$DP_DOCTOR" 2>&1 )
}

dp_scope() {
    # $1 repo, $2 posture for all four engines in `default`,
    # $3 (optional) one module promoted to enforce for detekt
    python3 -c '
import json, sys
E = ("detekt", "ktlint", "konsist", "coverage")
doc = {"default": dict.fromkeys(E, sys.argv[2]), "modules": {}}
if len(sys.argv) > 3 and sys.argv[3]:
    doc["modules"][sys.argv[3]] = {"detekt": "enforce"}
json.dump(doc, open(sys.argv[1], "w"), indent=2)
' "$1/.prism/scope.json" "$2" "${3:-}"
}

# --- the acceptance criterion for the whole design -----------------------
#
# observe + no floor is a SUPPORTED configuration. If the doctor fails it, the
# posture is not usable, because the first thing a consumer does after an
# install is run the doctor.
dp1=$(dp_repo)
dp_config "$dp1" "observe"
dp1_out=$(dp_run "$dp1")
assert_contains "$dp1_out" "posture is 'observe' and it was declined" \
    "an observe install with no floor reports a chosen state"
assert_not_contains "$dp1_out" "FAIL  no pre-push hook" \
    "and does not fail on it"
assert_contains "$dp1_out" "./prism promote --all" \
    "and names the command that installs it later"

# --- and the other direction still fails ---------------------------------
dp2=$(dp_repo)
dp_config "$dp2" "enforce"
dp2_out=$(dp_run "$dp2")
assert_contains "$dp2_out" "FAIL  no pre-push hook" \
    "an enforcing install with no floor still fails"
assert_contains "$dp2_out" "install.sh" \
    "and still names the installer"

# --- drift, in the direction the framework tolerates ---------------------
#
# Blocking MORE than the record claims is not a failure anywhere else here, so
# it is not one here: it is reported so the record can be corrected.
dp3=$(dp_repo)
dp_config "$dp3" "observe"
dp_install_floor "$dp3"
dp3_out=$(dp_run "$dp3")
assert_contains "$dp3_out" "blocks more than its record claims" \
    "a floor installed under observe is reported as drift"
assert_not_contains "$dp3_out" "FAIL  the pre-push floor" \
    "and not as a failure — over-blocking never is"

# --- a typo in the record must not be read as a posture ------------------
#
# Every sentence D6 prints about the floor depends on this value, so a value
# nobody recognises has to be loud rather than silently treated as enforce.
dp4=$(dp_repo)
dp_config "$dp4" "reporting"
dp4_out=$(dp_run "$dp4")
assert_contains "$dp4_out" "'reporting' is not a posture" \
    "an unrecognised posture is named"
assert_contains "$dp4_out" "observe" \
    "and the report says what the valid values are"

# --- an install from before the key existed ------------------------------
dp5=$(dp_repo)
dp_config "$dp5" ""
dp5_out=$(dp_run "$dp5")
assert_contains "$dp5_out" "no posture recorded" \
    "an install predating the key is read as enforce"
assert_contains "$dp5_out" "reading this install as 'enforce'" \
    "and says which way it was read rather than assuming silently"

# --- the baseline count is the readiness metric --------------------------
dp6=$(dp_repo)
dp_config "$dp6" "observe"
dp_baseline "$dp6" "RuleA:a.kt\$x" "RuleA:b.kt\$y" "RuleB:c.kt\$z"
dp6_out=$(dp_run "$dp6")
assert_contains "$dp6_out" "3 findings suppressed" \
    "the doctor counts what the baseline still holds"
assert_contains "$dp6_out" "./prism baseline group" \
    "and points at the breakdown by rule, as a verb rather than a library path"

# --- empty is the signal to enforce --------------------------------------
dp7=$(dp_repo)
dp_config "$dp7" "observe"
dp_baseline "$dp7"
dp7_out=$(dp_run "$dp7")
assert_contains "$dp7_out" "nothing left to burn down" \
    "an empty baseline under observe says the work is done"
assert_contains "$dp7_out" "./prism promote --all" \
    "and names the command that turns enforcement on"

# --- a corrupt baseline is a broken build, not an empty one --------------
#
# detekt fails EVERY module's analysis on an invalid baseline. Reporting zero
# findings would contradict the build for as long as nobody looked.
dp8=$(dp_repo)
dp_config "$dp8" "observe"
printf '<SmellBaseline><CurrentIssues>\n' >"$dp8/detekt.baseline.xml"
assert_contains "$(dp_run "$dp8")" "does not parse" \
    "an unparseable baseline fails rather than counting as empty"

# --- no baseline at all is the good state, not a missing one -------------
dp9=$(dp_repo)
dp_config "$dp9" "enforce"
assert_contains "$(dp_run "$dp9")" "nothing suppressed" \
    "an enforcing install with no baseline reports every rule live"

# --- one baseline per module, and the count is the whole tree ------------
#
# 0.6.0. The baseline used to be one file at the root, and D11 stat-ed that
# path: a repository whose modules each carry their own would have reported
# "no baseline" over any number of suppressed findings.
dp9b=$(dp_repo)
dp_config "$dp9b" "observe"
mkdir -p "$dp9b/core/domain" "$dp9b/feature/login"
dp_baseline "$dp9b/core/domain" "RuleA:a.kt\$x" "RuleA:b.kt\$y"
dp_baseline "$dp9b/feature/login" "RuleB:c.kt\$z"
dp9b_out=$(dp_run "$dp9b")
assert_contains "$dp9b_out" "3 findings suppressed" \
    "the doctor counts every module's baseline, not a file at the root"
assert_not_contains "$dp9b_out" "nothing suppressed" \
    "and does not report a tree full of suppressions as clean"

# --- a renamed file detaches its entries, and that is REPORTED -----------
#
# M2, measured on a real run: five ktlint `standard:filename` renames detached
# six baselined detekt findings and turned a green staticAnalysis red, in a step
# that had nothing to do with detekt. The id carries the bare filename.
dp9c=$(dp_repo)
dp_config "$dp9c" "observe"
mkdir -p "$dp9c/core/domain/src/main"
printf 'class Kept\n' > "$dp9c/core/domain/src/main/Kept.kt"
# Real detekt id shape -- Rule:File.kt:Signature. The two-part shape the older
# fixtures use here would make BOTH entries unreadable to the stale check, and
# this case would then pass without proving anything.
dp_baseline "$dp9c/core/domain" "RuleA:Kept.kt:Kept\$x" "RuleA:Renamed.kt:Renamed\$y"
dp9c_out=$(dp_run "$dp9c")
assert_contains "$dp9c_out" "no longer in their module" \
    "an entry naming a file the module does not have is reported"
assert_not_contains "$dp9c_out" "FAIL  1 baselined" \
    "but never failed — a rename is not a defect, it is a rot count"


# --- THE REGRESSION. The record says enforce; nothing enforces. ----------
#
# This is the install a real consumer reported: they answered `enforce`, and
# every engine observed. 0.5.0's doctor read only the posture KEY and printed
# "posture: enforce -- every gate blocks" over exactly this tree, which is how
# the install could be wrong and verified at the same time. The doctor's job
# here is to open the file the engines open.
dp10=$(dp_repo)
dp_config "$dp10" "enforce"
dp_scope "$dp10" "observe"
dp10_out=$(dp_run "$dp10")
assert_contains "$dp10_out" "FAIL  posture: enforce" \
    "a record of enforce over an all-observe scope file FAILS"
assert_contains "$dp10_out" "scope.json resolves to 'observe'" \
    "and names what actually happens instead"
assert_contains "$dp10_out" "REMOVING .prism/scope.json" \
    "and says how enforce is actually installed"

# --- the same tree, honestly recorded, is fine ---------------------------
#
# Nothing about what blocks changed between dp10 and dp11. Only the record did,
# which is the point: the doctor is judging the AGREEMENT, not the posture.
dp11=$(dp_repo)
dp_config "$dp11" "observe"
dp_scope "$dp11" "observe"
assert_contains "$(dp_run "$dp11")" "PASS  posture: observe" \
    "an observe record over an all-observe scope file passes"

# --- mid-adoption is the healthy state, not a disagreement ---------------
#
# One module promoted while the rest still observe is what the on-ramp looks
# like every day it is being used. It must not read as a fault.
dp12=$(dp_repo)
dp_config "$dp12" "observe"
dp_scope "$dp12" "observe" ":core:domain"
dp12_out=$(dp_run "$dp12")
assert_contains "$dp12_out" "PASS  posture: observe" \
    "a partly promoted repository passes"
assert_contains "$dp12_out" "partially promoted" \
    "and is described as in progress rather than inconsistent"

# --- enforce, installed the way enforce is actually installed ------------
dp13=$(dp_repo)
dp_config "$dp13" "enforce"
assert_contains "$(dp_run "$dp13")" "PASS  posture: enforce" \
    "an enforce record with no scope file at all passes"

# --- a scope file nobody can parse is not an answer of no ----------------
#
# Every engine reads this file to decide whether to fail the build. Unreadable
# means the posture cannot be stated, which is ???? rather than PASS or FAIL.
dp14=$(dp_repo)
dp_config "$dp14" "enforce"
printf 'not json at all\n' >"$dp14/.prism/scope.json"
dp14_out=$(dp_run "$dp14")
assert_contains "$dp14_out" "????  .prism/scope.json exists and cannot be read" \
    "an unparseable scope file is undetermined, not passed"
assert_contains "$dp14_out" "not an answer of no" \
    "and says why that is not the same as failing"

# --- dp15-dp17: `mixed`, which is what a DERIVED install now records -----
#
# 0.6.1 decides posture per engine, so the ordinary outcome is some engines
# blocking and some not. 0.6.0 had no word for that: the key accepted only
# observe|enforce, so a correctly installed repository read as
# "'mixed' is not a posture" and the doctor FAILED it. These three pin the
# comparison to STRENGTH -- enforce > mixed > observe -- rather than to a list
# of named pairs, because that is what stops a fourth value reopening the gap.

# A greenfield as 8b now installs it: detekt and ktlint block with baselines,
# konsist and coverage observe. Record and reality agree.
dp15=$(dp_repo)
dp_config "$dp15" "mixed"
dp_install_floor "$dp15"
python3 -c '
import json, sys
json.dump({"default": {"konsist": "observe", "coverage": "observe"}, "modules": {}},
          open(sys.argv[1], "w"), indent=2)
' "$dp15/.prism/scope.json"
dp15_out=$(dp_run "$dp15")
assert_contains "$dp15_out" "posture: mixed" \
    "'mixed' is a posture the doctor can read"
assert_not_contains "$dp15_out" "is not a posture" \
    "and it is not rejected as invalid, which 0.6.0 did"
assert_contains "$dp15_out" "scope.json agrees" \
    "a mixed record over a mixed scope file AGREES"

# The dangerous direction, now reachable from `mixed`: the record claims some
# engines block, the file says none do.
dp16=$(dp_repo)
dp_config "$dp16" "mixed"
dp_install_floor "$dp16"
dp_scope "$dp16" "observe"
dp16_out=$(dp_run "$dp16")
assert_contains "$dp16_out" "blocks less" \
    "a mixed record over an all-observe file FAILS -- it over-reports"
assert_contains "$dp16_out" "FAIL" "and it is a failure, not a note"

# The safe direction: the file blocks more than the record claims.
dp17=$(dp_repo)
dp_config "$dp17" "mixed"
dp_install_floor "$dp17"
rm -f "$dp17/.prism/scope.json"
dp17_out=$(dp_run "$dp17")
assert_contains "$dp17_out" "blocks MORE" \
    "a mixed record over a removed scope file passes, blocking more"
assert_not_contains "$dp17_out" "baselines were worked down" \
    "and it does NOT claim the baselines were worked down -- it never checks that"

prism_fixture_cleanup "$dp15"
prism_fixture_cleanup "$dp16"
prism_fixture_cleanup "$dp17"
prism_fixture_cleanup "$dp1"
prism_fixture_cleanup "$dp2"
prism_fixture_cleanup "$dp3"
prism_fixture_cleanup "$dp4"
prism_fixture_cleanup "$dp5"
prism_fixture_cleanup "$dp6"
prism_fixture_cleanup "$dp7"
prism_fixture_cleanup "$dp8"
prism_fixture_cleanup "$dp9"
prism_fixture_cleanup "$dp10"
prism_fixture_cleanup "$dp11"
prism_fixture_cleanup "$dp12"
prism_fixture_cleanup "$dp13"
prism_fixture_cleanup "$dp14"

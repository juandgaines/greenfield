# Tests for status.sh. Sourced by run-tests.sh.
#
# Against a fixture with one passing, one stale and one below-threshold
# module, because those three are the states the report exists to tell apart.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

status_repo=$(prism_fixture_repo)
STATUS="$status_repo/.prism/verify/status.sh"

# A third module, so all three states are present at once.
mkdir -p "$status_repo/core/low/src/main/java" "$status_repo/core/low/src/test/java"
printf 'include(":core:domain")\ninclude(":core:other")\ninclude(":core:low")\n' \
    > "$status_repo/settings.gradle.kts"
printf 'plugins {\n    id("acme.kotlin.java.library")\n}\n' \
    > "$status_repo/core/low/build.gradle.kts"
printf 'class Low\n' > "$status_repo/core/low/src/main/java/Low.kt"
printf 'class LowTest\n' > "$status_repo/core/low/src/test/java/LowTest.kt"

status_report() {
    # status_report <module-dir> <covered> <missed>
    dir="$status_repo/$1/build/reports/prism-coverage"
    mkdir -p "$dir"
    printf '<report name="x"><counter type="LINE" missed="%s" covered="%s"/></report>\n' \
        "$3" "$2" > "$dir/coverage.xml"
    touch "$dir/coverage.xml"
}

# :core:domain — a current report well above the default 80.
status_report core/domain 100 0
# :core:low — a current report well below it.
status_report core/low 10 90
# :core:other — no report at all. Its src/main has code but no src/test, so it
# reports as a module with no tests rather than as a stale one.

out=$(PRISM_ROOT="$status_repo" sh "$STATUS" 2>&1)
assert_eq "0" "$?" "status.sh exits 0 with a passing module present"

assert_contains "$out" ":core:domain" "every module in settings.gradle.kts is listed"
assert_contains "$out" ":core:other" "including the one with no report"
assert_contains "$out" ":core:low" "including the one below threshold"
assert_contains "$out" "100.0" "the passing module's measured coverage is shown"
assert_contains "$out" "10.0" "the failing module's measured coverage is shown"
assert_contains "$out" "would DENY (low)" "a below-threshold module is called out as denying"
assert_contains "$out" "80" "the required threshold is shown beside the measurement"

# The refresh commands must be the SAME ones the gate prints, or the report
# sends people to run something that does not clear the denial. Asserted by
# construction: both render through prism_refresh_commands.
rm -rf "$status_repo/core/domain/build/reports"
out=$(PRISM_ROOT="$status_repo" sh "$STATUS" 2>&1)
assert_eq "0" "$?" "status.sh exits 0 with a stale module present"
assert_contains "$out" "no current result" "a module with no report is counted as such"
assert_contains "$out" "prismCoverage" "the refresh command for a JVM module is printed"
assert_contains "$out" ":core:domain:prismCoverage" "and it names the module"

gate_render=$(cd "$status_repo" && PRISM_ROOT="$status_repo" sh -c \
    '. "$0/lib/affected.sh"; prism_refresh_commands ":core:domain" jvm' \
    "$status_repo/.prism/verify")
assert_contains "$out" "$(printf '%s' "$gate_render" | head -1 | sed 's/^ *//')" \
    "the report renders the gate's own refresh command, not its own copy of it"

# An instrumentation module's refresh must route through the device lock, the
# same way the gate's denial does.
mkdir -p "$status_repo/core/other/src/androidTest/java"
printf 'class OtherAndroidTest\n' \
    > "$status_repo/core/other/src/androidTest/java/OtherAndroidTest.kt"
out=$(PRISM_ROOT="$status_repo" sh "$STATUS" 2>&1)
assert_contains "$out" "device_lock.py" \
    "an on-device refresh goes through the device lock"
assert_contains "$out" "select_emulator.py" "and says how to get an emulator"

# Every remediation command names the framework's own asset directory. The
# assets used to be carried inside the harness's skill directories, and two of
# the five supported harnesses receive no directory in the consumer's
# repository at all -- so a refresh command naming one pointed those consumers
# at a file they do not have. Per design D9 the location is the framework's.
assert_contains "$out" ".prism/assets/select_emulator.py" \
    "the emulator hint names the framework's asset directory"
assert_contains "$out" ".prism/assets/device_lock.py" \
    "and so does the device lock"
assert_eq "0" "$(printf '%s' "$out" | grep -c 'skills/')" \
    "no refresh command names a harness skill directory"

# --- read-only by construction -------------------------------------------
assert_eq "0" "$(grep -c 'gradlew' "$STATUS")" \
    "status.sh runs no gradle task"
assert_eq "0" "$(grep -cE '^[[:space:]]*exit [1-9]' "$STATUS")" \
    "status.sh has no non-zero exit anywhere"

# An unusable root is reported, not blocked on.
empty=$(mktemp -d)
out=$(PRISM_ROOT="$empty" sh "$STATUS" 2>&1)
assert_eq "0" "$?" "an unusable root still exits 0"
assert_contains "$out" "no usable repository root" "and says why it has nothing to report"
rm -rf "$empty"

# --- NO HOOK MAY CALL IT -------------------------------------------------
# A gate that consults a reporting script is a gate whose verdict can be
# changed by editing the reporting script.
# Comment lines are excluded: push-gate.sh names status.sh in a comment
# explaining that the two share one refresh renderer, which is the opposite of
# a dependency. What must not exist is an INVOCATION.
for gate in stop-gate.sh push-gate.sh subagent-gate.sh; do
    assert_eq "0" "$(grep -v '^[[:space:]]*#' "$HOOK_DIR/$gate" | grep -c 'status\.sh')" \
        "$gate does not call status.sh"
done
# The registration half of the same guarantee. This used to read
# "$HOOK_DIR/../../settings.json", which resolved to the harness's own config
# while the engine was nested inside it. The engine is harness-neutral now, so
# the question becomes "does ANY registration the payload ships invoke
# status.sh" — and the payload ships its registrations under adapters/.
#
# Phase 1 ships one adapter, the git pre-push floor. Phase 4 adds the harness
# ones, and they land under the same directory, so this assertion widens to
# cover them without being rewritten.
# One dirname. The adapters sit BESIDE the engine inside the framework's own
# directory -- core/adapters here, .prism/adapters in an install -- exactly as
# assets/, templates/ and review/ do. Two dirnames pointed at the repository
# root, where nothing the payload ships has ever lived; it went unnoticed
# because the guard below treats a missing directory as a pass, and until the
# floor landed there was no directory to find.
prism_adapters_dir="$HOOK_DIR/../adapters"
if [ -d "$prism_adapters_dir" ]; then
    assert_eq "0" \
        "$(grep -rl 'status\.sh' "$prism_adapters_dir" 2>/dev/null | wc -l | tr -d ' ')" \
        "no shipped adapter registers or invokes status.sh"
else
    # Absence is the stronger form of the same claim: nothing registers it
    # because there is nothing to register it with.
    assert_eq "0" "0" "no shipped adapter registers or invokes status.sh"
fi

# --- an OBSERVED module shows its number ---------------------------------
# 0.5.0. `observe` is the state every module is in while somebody is preparing
# to promote it, and coverage.py prints the measurement it made. status.sh's
# case had arms for `pass` and `low` only, so observe fell to the catch-all:
# a hardcoded dash, counted as having no current result, plus a refresh
# command that changes nothing. The documented loop -- measure, read the
# number, write tests, repeat, then promote -- could not be run from the tool
# for any module not already promoted.
status_report core/domain 100 0
status_report core/low 10 90
cat > "$status_repo/.prism/scope.json" <<'SCOPEJSON'
{
  "default": {
    "detekt": "observe",
    "ktlint": "observe",
    "konsist": "observe",
    "coverage": "observe"
  },
  "modules": {}
}
SCOPEJSON

out=$(PRISM_ROOT="$status_repo" sh "$STATUS" 2>&1)
assert_eq "0" "$?" "status.sh exits 0 with every module observed"
assert_contains "$out" "100.0" \
    "an observed module still shows the coverage it measured"
assert_contains "$out" "10.0" \
    "including one that would not clear its floor"
assert_contains "$out" "observe (clears)" \
    "and says whether it would clear the floor if promoted"
assert_contains "$out" "observe (short)" \
    "or that it would fall short"
assert_not_contains "$out" "would DENY" \
    "an observed module never claims it would deny -- that is what observe means"

# The number must not be laundered into the stale bucket, or the refresh hint
# tells people to re-run a task that already produced the number on screen.
assert_contains "$out" "2 observed" "observed modules are counted separately"
assert_not_contains "$out" ":core:domain:prismCoverage" \
    "and a module with a current report is not sent to refresh it"

rm -f "$status_repo/.prism/scope.json"

rm -rf "$status_repo"

# --- the STRUCTURAL arms honour observe too ------------------------------
# 0.6.0. The two arms above the measurement -- no jacoco wiring, and sources
# with no tests -- printed "would DENY" without asking scope.json anything, so
# an all-observe install (which is what every install starts as) was told two
# modules would be denied by a gate that observes them. The arms above this one
# were fixed in 0.5.0 for the measured case; these two were missed because they
# never reach coverage.py's verdict, which is where the posture was consulted.
#
# Its own fixture on purpose: the shared one accumulates reports as the cases
# above run, and the assertion needs a module that reaches the structural arm at
# the moment it is checked. The observe assertion further up passes today for
# exactly that reason -- by then :core:other has a report and never gets there.
struct_repo=$(prism_fixture_repo)
cat > "$struct_repo/.prism/scope.json" <<'SCOPEJSON'
{
  "default": {
    "detekt": "observe",
    "ktlint": "observe",
    "konsist": "observe",
    "coverage": "observe"
  },
  "modules": {}
}
SCOPEJSON
# :core:other has src/main and no src/test, so it lands in the no-tests arm.
struct_out=$(PRISM_ROOT="$struct_repo" sh "$struct_repo/.prism/verify/status.sh" 2>&1)
assert_contains "$struct_out" "blocks if promoted"     "a module with no tests that coverage OBSERVES says what it would do, not what it does"
assert_not_contains "$struct_out" "would DENY"     "because nothing there denies a push while coverage observes it"
assert_contains "$struct_out" "0 structurally blocked"     "and it is not counted among what blocks today"
assert_contains "$struct_out" "would be structurally blocked if promoted"     "the count is still reported -- it is the work promotion implies"

# The same tree with the scope file gone means enforce everywhere, and then the
# original verdict is the true one.
rm -f "$struct_repo/.prism/scope.json"
struct_out=$(PRISM_ROOT="$struct_repo" sh "$struct_repo/.prism/verify/status.sh" 2>&1)
assert_contains "$struct_out" "would DENY"     "with no scope file every engine enforces, so the same module would deny"
assert_contains "$struct_out" "1 structurally blocked" "and it is counted"

rm -rf "$struct_repo"

# --- a floor pasted in from a measurement still renders as a table --------
# M10. `thresholds.json` says to set a starting floor to a number you have
# already measured, so somebody set one to 98.30508474576271 — and the REQUIRED
# column is eight characters wide.
float_repo=$(prism_fixture_repo)
python3 - "$float_repo/.prism/verify/thresholds.json" <<'FLOORS'
import json, sys
json.dump({"default": 80, "overrides": {":core:domain": 98.30508474576271}},
          open(sys.argv[1], "w"), indent=2)
FLOORS
float_out=$(PRISM_ROOT="$float_repo" sh "$float_repo/.prism/verify/status.sh" :core:domain 2>&1)
assert_contains "$float_out" "98.3" \
    "a floor pasted in from a measurement is reported to one decimal place"
assert_not_contains "$float_out" "98.30508474576271" \
    "and not to seventeen significant figures, which no column can hold"
rm -rf "$float_repo"

# Tests for the Stop gate's sign-off short-circuit. Sourced by run-tests.sh.
#
# What is under test: the gate must skip a stop that left the tree exactly
# where the gate last rendered a verdict, and must NOT skip anything else.
# Every "skip" assertion is paired with a converse, because a short-circuit
# that fires unconditionally passes every skip test while verifying nothing.
#
# The sign-off is written by the STOP GATE ITSELF, never by a prompt hook.
# The predecessor design snapshotted the tree on every UserPromptSubmit, so
# the prompt after an Esc-interrupt absorbed never-gated broken Kotlin into
# the baseline and the next doc-only stop skipped it silently. The regression
# case for that laundering is below.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

so_repo=$(prism_fixture_repo)
# A branch whose diff against master already contains Kotlin — the situation
# the short-circuit exists for. On a clean master every assertion below would
# exit 0 through the no-Kotlin short-circuit instead, and pass while proving
# nothing.
prism_fixture_branch_commit "$so_repo"
prism_fixture_gradlew "$so_repo"
so_state=$(mktemp -d)
SO_GATE="$so_repo/.prism/verify/stop-gate.sh"

so_stop() {
    printf '{"stop_hook_active":%s}' "${1:-false}" \
        | PRISM_ROOT="$so_repo" PRISM_STATE_DIR="$so_state" sh "$SO_GATE" 2>&1
}

# --- no sign-off means "cannot tell", and cannot-tell verifies -------------
rm -f "$so_repo/.fixture-gradle-tasks"
out=$(so_stop)
assert_eq "0" "$?" "a passing verification exits 0"
tasks=$(tr '\n' ' ' < "$so_repo/.fixture-gradle-tasks")
assert_contains "$tasks" "staticAnalysis" "with no sign-off, gate 0 runs"
assert_contains "$tasks" ":core:domain:assemble" "with no sign-off, gate 1 runs"
assert_contains "$tasks" ":core:domain:test" "with no sign-off, gate 2 runs"
assert_contains "$tasks" ":core:other:assemble" "dependents are widened into scope"
assert_eq "0" "$([ -f "$so_state/stop-signoff" ] && echo 0 || echo 1)" \
    "a verified pass signs the tree off"

# --- a doc-only stop after a sign-off costs nothing ------------------------
rm -f "$so_repo/.fixture-gradle-tasks"
printf '# notes\n' > "$so_repo/NOTES.md"
out=$(so_stop)
assert_eq "0" "$?" "a doc-only stop after a sign-off exits 0"
assert_eq "" "$out" "and prints nothing"
assert_eq "1" "$([ -f "$so_repo/.fixture-gradle-tasks" ] && echo 0 || echo 1)" \
    "and runs no gradle task at all"

# --- converse: a Kotlin edit after the sign-off IS gated -------------------
signed_before=$(cat "$so_state/stop-signoff")
printf 'class Broken\n' > "$so_repo/core/domain/src/main/java/Broken.kt"
printf '1' > "$so_repo/.fixture-gradle-exit"
out=$(so_stop)
assert_eq "2" "$?" "a Kotlin edit after the sign-off reaches the gates"
assert_contains "$out" "VERIFICATION GATE 0 FAILED" \
    "gate 0 runs first and its failure is named"
assert_eq "$signed_before" "$(cat "$so_state/stop-signoff")" \
    "a blocked stop does not move the sign-off"

# --- the laundering regression: unverified Kotlin survives a doc turn ------
# The broken Kotlin above was never signed off. Under the prompt-hook design
# a new user prompt would have re-baselined the tree WITH it, and this
# doc-only stop would have exited 0 silently. It must keep being gated until
# a verdict lands.
printf '# more notes\n' > "$so_repo/NOTES2.md"
out=$(so_stop)
assert_eq "2" "$?" \
    "never-gated Kotlin is still gated even when the latest edit is doc-only"

# --- a pass moves the sign-off forward -------------------------------------
printf '0' > "$so_repo/.fixture-gradle-exit"
out=$(so_stop)
assert_eq "0" "$?" "the fixed tree verifies"
rm -f "$so_repo/.fixture-gradle-tasks"
out=$(so_stop)
assert_eq "0" "$?" "the very next stop is free again"
assert_eq "1" "$([ -f "$so_repo/.fixture-gradle-tasks" ] && echo 0 || echo 1)" \
    "and runs no gradle task"

# --- committing mid-turn must not empty the delta --------------------------
# `git diff HEAD` goes empty on a commit, so a fingerprint built from the
# diff alone would read a mid-turn commit as "nothing happened". HEAD is
# folded into the fingerprint for exactly this case: an edit that is
# committed in the same turn still reaches the gates.
printf 'class Seed { val evenMore = 3 }\n' \
    > "$so_repo/core/domain/src/main/java/Seed.kt"
git -C "$so_repo" -c user.email=fixture@example.com -c user.name=fixture \
    add -A >/dev/null 2>&1
git -C "$so_repo" -c user.email=fixture@example.com -c user.name=fixture \
    -c commit.gpgsign=false commit -qm 'mid-turn commit'
rm -f "$so_repo/.fixture-gradle-tasks"
out=$(so_stop)
assert_eq "0" "$?" "the committed tree verifies"
assert_eq "0" "$([ -f "$so_repo/.fixture-gradle-tasks" ] && echo 0 || echo 1)" \
    "Kotlin committed mid-turn is still gated, not read as nothing happened"

# --- the give-up path signs off what it REPORTED ---------------------------
# Ending a turn unverified after the block budget is a loud, documented
# verdict; later doc-only turns must not re-pay for it. The push gate's own
# staticAnalysis run is what keeps that state off the remote.
printf 'class StillBroken\n' > "$so_repo/core/domain/src/main/java/StillBroken.kt"
printf '1' > "$so_repo/.fixture-gradle-exit"
printf '3' > "$so_state/stop-block-count"
out=$(so_stop true)
assert_eq "0" "$?" "an exhausted budget gives up rather than blocking forever"
assert_contains "$out" "UNVERIFIED" "and says plainly that nothing passed"
rm -f "$so_repo/.fixture-gradle-tasks"
out=$(so_stop)
assert_eq "0" "$?" "the reported state is not re-gated"
assert_eq "1" "$([ -f "$so_repo/.fixture-gradle-tasks" ] && echo 0 || echo 1)" \
    "a doc-only turn after a give-up runs no gradle task"

# --- Kotlin written WHILE the gates run is not signed off ------------------
# The gates take minutes; a parallel session (or the user in Android Studio)
# can land Kotlin in the tree mid-run. The sign-off fingerprint is captured
# BEFORE the gates, so that code stays unsigned and the next stop verifies
# it — fingerprinting at the end absorbed it unverified.
printf '0' > "$so_repo/.fixture-gradle-exit"
printf 'class Precursor { val x = 2 }\n' \
    > "$so_repo/core/domain/src/main/java/Precursor.kt"
cat > "$so_repo/gradlew" <<'GRADLEW'
#!/bin/sh
root=$(dirname "$0")
for arg in "$@"; do
    case "$arg" in
        :*|staticAnalysis) printf '%s\n' "$arg" >> "$root/.fixture-gradle-tasks" ;;
    esac
done
printf 'class MidRun\n' > "$root/core/domain/src/main/java/MidRun.kt"
exit 0
GRADLEW
chmod +x "$so_repo/gradlew"
out=$(so_stop)
assert_eq "0" "$?" "the gated stop still passes while an edit lands mid-run"
rm -f "$so_repo/.fixture-gradle-tasks"
out=$(so_stop)
assert_eq "0" "$?" "the follow-up stop over the mid-run Kotlin passes too"
assert_eq "0" "$([ -f "$so_repo/.fixture-gradle-tasks" ] && echo 0 || echo 1)" \
    "Kotlin written during the gate run is VERIFIED by the next stop, not signed off"

# --- a forced-scope pass must not sign off the whole tree ------------------
# PRISM_STOP_FORCE_MODULES verifies only what it names; a repo-wide
# sign-off from that pass would launder every other module's unverified work
# past the next natural stop's short-circuit.
prism_fixture_gradlew "$so_repo"
signed_before=$(cat "$so_state/stop-signoff")
printf 'class Forced { val x = 3 }\n' \
    > "$so_repo/core/domain/src/main/java/Forced.kt"
out=$(printf '{"stop_hook_active":false}' \
    | PRISM_ROOT="$so_repo" PRISM_STATE_DIR="$so_state" \
      PRISM_STOP_FORCE_MODULES=":core:domain" sh "$SO_GATE" 2>&1)
assert_eq "0" "$?" "a forced-scope pass exits 0"
assert_eq "$signed_before" "$(cat "$so_state/stop-signoff")" \
    "a forced-scope pass does not move the sign-off"

rm -rf "$so_state"
prism_fixture_cleanup "$so_repo"

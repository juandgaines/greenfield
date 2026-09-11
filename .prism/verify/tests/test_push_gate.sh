# Tests for push-gate.sh wiring. Sourced by run-tests.sh.
#
# Scope note: this file asserts WIRING — that a detected push resolves a
# range, derives modules, and reaches gate 3's pre-check with the right
# verdict. It does NOT assert command detection form by form. Those
# assertions used to live here and drove the whole hook with
# PRISM_SKIP_GATE3=1, which is the only reason that switch existed in the
# shipped code path. They now live in tests/test_push_detect.py's
# MigratedFromTheGateSuiteTests, which asks the detector the same questions
# directly. The two false-positive cases below stay here because they assert
# the HOOK exits 0 on a non-push, which is a wiring claim, not a detection one.
#
# The only environment variables used here are PRISM_DRY_RUN,
# PRISM_PUSH_FORCE_MODULES, and PRISM_PUSH_RANGE_OVERRIDE. None of them
# skips a gate's verdict: the first two stop before any gate runs, and the
# third only substitutes a range so the diff-failure path can be reached.
GATE="$HOOK_DIR/push-gate.sh"
# The verification half. push-gate.sh is now the event parser and hands over to
# this the moment it has classified a command as a push, so every claim below
# about what the gate DOES -- rather than about what it makes of an event --
# reads this file.
VERIFY="$HOOK_DIR/push-verify.sh"

event() {
    # event <command> — emits a PreToolUse Bash event as JSON
    printf '{"tool_name":"Bash","tool_input":{"command":"%s"}}' "$1"
}

event_command() {
    # event_command <command> — like event(), but JSON-escapes via python3
    # so a multi-line command (a heredoc) or one with embedded quotes still
    # produces valid JSON, which plain printf substitution cannot do.
    python3 -c '
import json, sys
print(json.dumps({"tool_name": "Bash", "tool_input": {"command": sys.argv[1]}}))
' "$1"
}

# A module path that is deliberately not in settings.gradle.kts. Used
# wherever a test needs "a module the coverage pre-check cannot configure":
# naming a real module here would make the assertion evaporate the moment
# that module gains coverage configuration, which is exactly what the rest of
# this change does to all thirteen of them.
UNCONFIGURED_MODULE=":no:such:module"

# 7.5.2 — every assertion below that needs a RESOLVABLE PUSH RANGE runs
# against a fixture repository rather than against whatever the developer has
# checked out. Those cases silently depended on this checkout having a master
# ref, an upstream, and module changes between them; when the branch stopped
# having module changes in its range the hook exited 0 for the entirely
# correct reason that there was nothing to verify, and an assertion about dry
# runs failed while claiming a push had been ALLOWED.
#
# The fixture is a repo with a master commit and a feature branch that edits
# :core:domain, so "the range contains a module change" is true by
# construction. Assertions about THIS project's own configuration (that
# :core:crypto has both suites, that the shipped hook never runs
# connectedDebugAndroidTest) deliberately keep using the real repo — that is
# what they are about.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

wiring_repo=$(prism_fixture_repo)
prism_fixture_branch_commit "$wiring_repo"
WIRING_GATE="$wiring_repo/.prism/verify/push-gate.sh"

# Run under dry-run so a FALSE POSITIVE is observable: a wrongly-detected
# command reaches the dry-run block and prints "push range:". Without this
# the assertions pass against a broken matcher too, because a false positive
# on a clean tree would exit 0 silently.
for cmd in "ls -la" "git status" "./gradlew build" "npm run pushish" \
           "echo about to git push later" "git pushx --weird" \
           "cat notes-git-push.txt"; do
    out=$(event "$cmd" | PRISM_DRY_RUN=1 sh "$GATE" 2>&1)
    assert_eq "0" "$?" "non-push command [$cmd] exits 0"
    assert_not_contains "$out" "push range:" \
        "non-push command [$cmd] must not be detected as a push"
done

# --- unparseable input must not block ---
printf 'not json' | sh "$GATE" >/dev/null 2>&1
assert_eq "0" "$?" "unparseable stdin exits 0 rather than blocking"

# --- FIX 7: a heredoc writing prose that merely mentions a push must not
# gate — the hook itself must exit 0, not merely the detector say "no". ---
heredoc_cmd=$(cat <<'CMDEOF'
cat >> notes.md <<'EOF'
Remember: after review (git push) to the remote once approved.
EOF
echo done
CMDEOF
)
out=$(event_command "$heredoc_cmd" | PRISM_DRY_RUN=1 sh "$GATE" 2>&1)
assert_eq "0" "$?" "a heredoc writing push-mentioning prose exits 0"
assert_not_contains "$out" "push range:" \
    "a heredoc writing push-mentioning prose must not be detected as a push"

# --- FIX 7: a parenthesised mention inside a quoted argument (e.g. a
# commit message) must not read as a command-boundary "(" ---
commit_msg_cmd='git notarealcommit -m "please (git push) after merge"'
out=$(event_command "$commit_msg_cmd" | PRISM_DRY_RUN=1 sh "$GATE" 2>&1)
assert_eq "0" "$?" "a quoted message mentioning a push exits 0"
assert_not_contains "$out" "push range:" \
    "a quoted message mentioning a push must not be detected as a push"

# --- N3: push detection itself failing must deny, not fall through to
# allow. Simulated with a python3 stub that behaves normally for the
# initial JSON extraction but fails specifically when asked to run
# push_detect.py, so this exercises the detector-failure path in isolation
# from "python3 missing entirely" (which the earlier command_line
# extraction already handles separately). ---
real_python3=$(command -v python3)
stub_dir=$(mktemp -d)
cat > "$stub_dir/python3" <<STUB
#!/bin/sh
case "\$*" in
    *push_detect.py*) echo "simulated crash" >&2; exit 1 ;;
    *) exec "$real_python3" "\$@" ;;
esac
STUB
chmod +x "$stub_dir/python3"
out=$(event "git push" | PATH="$stub_dir:$PATH" sh "$GATE" 2>&1)
assert_eq "2" "$?" "a failing push detector denies rather than allowing"
assert_contains "$out" "push detection could not run" \
    "the denial says push detection itself failed"
# A non-push command must ALSO be denied when the detector cannot even be
# asked the question — the hook cannot prove it is safe to allow.
out=$(event "ls -la" | PATH="$stub_dir:$PATH" sh "$GATE" 2>&1)
assert_eq "2" "$?" "a non-push command is also denied when the detector fails"
rm -rf "$stub_dir"

# --- N5: an unresolvable changed-file range must deny, not read as "nothing
# changed" (previously piped straight into `sort -u`, which exits 0 on
# empty input regardless of whether `git diff` itself failed). Forced via
# the test-only PRISM_PUSH_RANGE_OVERRIDE, read only after the real
# PRISM_BASE_BRANCH resolution already succeeded. ---
out=$(event "git push" | PRISM_ROOT="$wiring_repo" \
    PRISM_PUSH_RANGE_OVERRIDE="no-such-ref..HEAD" sh "$WIRING_GATE" 2>&1)
assert_eq "2" "$?" "an unresolvable changed-file range denies the push"
assert_contains "$out" "could not compute the changed-file diff" \
    "the denial explains the range could not be diffed"

# --- the range falls back to the base merge-base with no upstream ---
# The range is printed before any gate runs, so a plain dry run is enough;
# whatever gate 3 goes on to decide about the real working tree cannot
# affect this assertion.
out=$(event "git push" | PRISM_ROOT="$wiring_repo" PRISM_DRY_RUN=1 \
    sh "$WIRING_GATE" 2>&1)
assert_contains "$out" "..HEAD" "the resolved range ends at HEAD"

# --- gate 3: the jacoco pre-check must fire before any gradle task ---
out=$(event "git push" | PRISM_ROOT="$wiring_repo" \
    PRISM_PUSH_FORCE_MODULES="$UNCONFIGURED_MODULE" sh "$WIRING_GATE" 2>&1)
assert_eq "2" "$?" "a module without prism.jacoco denies the push"
assert_contains "$out" "android-jacoco-setup" \
    "the denial names the skill that fixes it"
assert_contains "$out" "$UNCONFIGURED_MODULE" "the denial names the offending module"

# --- REGRESSION: the deleted gate-3 skip switch has no effect ------------
# Setting it must change nothing at all. Asserted two ways: the removed name
# does not appear in the shipped hook, and the same event produces byte-
# identical output and an identical exit status with and without it.
assert_eq "0" "$(cat "$GATE" "$VERIFY" | grep -c PRISM_SKIP_GATE3)" \
    "the removed skip switch does not appear in either half of the push gate"

with_var=$(event "git push" | PRISM_ROOT="$wiring_repo" \
    PRISM_SKIP_GATE3=1 PRISM_PUSH_FORCE_MODULES="$UNCONFIGURED_MODULE" \
    sh "$WIRING_GATE" 2>&1)
with_var_status=$?
without_var=$(event "git push" | PRISM_ROOT="$wiring_repo" \
    PRISM_PUSH_FORCE_MODULES="$UNCONFIGURED_MODULE" sh "$WIRING_GATE" 2>&1)
without_var_status=$?
assert_eq "$without_var_status" "$with_var_status" \
    "setting the removed skip switch does not change the exit status"
assert_eq "$without_var" "$with_var" \
    "setting the removed skip switch does not change the output"
assert_eq "2" "$with_var_status" \
    "an unconfigured module still denies with the removed switch set"
assert_contains "$with_var" "$UNCONFIGURED_MODULE" \
    "the denial still names the unconfigured module with the removed switch set"

# --- a configured module reaches the dry-run report without gradle ---
#
# Against a FIXTURE module graph. Which report kind a module gets — jvm,
# instrumentation or combined — is derived from the source sets it declares, so
# the assertion needs a repository that declares them. It read them off the
# ambient checkout until the framework was extracted into a product repository
# that has no such modules.
kind_repo=$(prism_fixture_graph_repo)
KIND_GATE="$kind_repo/.prism/verify/push-gate.sh"

out=$(event "git push" \
    | PRISM_ROOT="$kind_repo" PRISM_DRY_RUN=1 \
      PRISM_PUSH_FORCE_MODULES=":core:crypto" sh "$KIND_GATE" 2>&1)
assert_eq "2" "$?" "a dry run over a configured module denies rather than allowing"
assert_contains "$out" "combined" \
    ":core:crypto has both suites so it gets a combined report"
assert_contains "$out" "nothing was verified" \
    "a dry run says plainly that nothing was verified"

# --- REGRESSION: a broken python3 must DENY, not allow --------------------
# The command_line extraction used to print "" both when python3 said "this
# event has no command" and when python3 could not run at all, and an empty
# result exited 0. A missing or broken interpreter therefore allowed EVERY
# push through, ungated and silently — on the line directly above the guard
# written to stop exactly that for the detector. Stubbed to fail for every
# invocation, unlike the detector-only stub above.
stub_dir=$(mktemp -d)
printf '#!/bin/sh\nexit 127\n' > "$stub_dir/python3"
chmod +x "$stub_dir/python3"

out=$(event "git push" | PATH="$stub_dir:$PATH" sh "$GATE" 2>&1)
assert_eq "2" "$?" "a totally broken python3 denies the push rather than allowing it"
assert_contains "$out" "could not read the Bash event" \
    "the denial says the event itself could not be read"
assert_contains "$out" "NOT verified as safe" \
    "the denial is explicit that nothing was verified"

# A non-push is denied too: the hook cannot prove it is safe to allow.
out=$(event "ls -la" | PATH="$stub_dir:$PATH" sh "$GATE" 2>&1)
assert_eq "2" "$?" "a non-push is also denied when the event cannot be read"
rm -rf "$stub_dir"

# A genuinely unparseable event is still ALLOWED — that verdict was reached
# by running python3 successfully, so it is knowledge, not an outage.
printf 'not json' | sh "$GATE" >/dev/null 2>&1
assert_eq "0" "$?" "a parseable-but-not-JSON event still exits 0"

# --- REGRESSION: PRISM_DRY_RUN must never produce an ALLOW ---------------
# It is a test seam, so it may stop early — but a leaked environment
# variable must be able to over-block only, never to wave a push through
# with a message that reaches nothing but the debug log.
#
# 7.5.2: on a fixture, not this checkout. Run against the real repo this
# asserted nothing whenever the current branch happened to have no module
# changes in its push range — the hook then exits 0 for the entirely correct
# reason that there is nothing to verify, and the assertion failed while
# claiming a dry run had ALLOWED a push. The precondition it needs is "the
# range contains a module change", so the fixture supplies one.

out=$(event "git push" | PRISM_ROOT="$wiring_repo" PRISM_DRY_RUN=1 \
    sh "$WIRING_GATE" 2>&1)
assert_eq "2" "$?" "a dry run on a real push denies rather than allowing"
assert_contains "$out" ":core:domain" \
    "the dry run really did derive the changed module (not a vacuous pass)"
assert_contains "$out" "nothing was verified" \
    "the dry run says plainly that nothing was verified"
assert_not_contains "$out" "would be ALLOWED" \
    "no dry-run path describes itself as allowing a push"

# --- global build files pull EVERY module into scope ----------------------
# build-logic, the version catalog and the root build files belong to no
# module, so they used to derive an empty module list and exit 0 having
# verified nothing — on the edit with the widest blast radius of all.
for global_file in "build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt" \
                   "gradle/libs.versions.toml" \
                   "settings.gradle.kts" \
                   "gradle.properties"; do
    if printf '%s\n' "$global_file" | (
        . "$HOOK_DIR/lib/affected.sh"; prism_has_global_build_change
    ); then
        assert_eq "yes" "yes" "[$global_file] is recognised as a global build file"
    else
        assert_eq "yes" "no" "[$global_file] is recognised as a global build file"
    fi
done

if printf '%s\n' "core/domain/src/main/java/A.kt" | (
    . "$HOOK_DIR/lib/affected.sh"; prism_has_global_build_change
); then
    assert_eq "no" "yes" "an ordinary module source is NOT a global build file"
else
    assert_eq "no" "no" "an ordinary module source is NOT a global build file"
fi

# --- REGRESSION: an unusable root must DENY, not silently allow -----------
# PRISM_ROOT is assigned from `git rev-parse`, so a failure leaves it EMPTY
# rather than unset and `set -u` never fires. The module table then found no
# settings.gradle.kts, derived no modules, and the gate exited 0 having
# verified nothing at all.
empty_root=$(mktemp -d)
out=$(event "git push" | PRISM_ROOT="$empty_root" sh "$GATE" 2>&1)
assert_eq "2" "$?" "a root with no settings.gradle.kts denies the push"
assert_contains "$out" "no usable repository root" "the denial names the cause"
rm -rf "$empty_root"

# The worktree case: the hook lives under one checkout, the tree resolves to
# another. Verifying the wrong checkout is worse than not verifying, because
# it reports success.
other_tree=$(mktemp -d)
touch "$other_tree/settings.gradle.kts"
out=$(event "git push" | PRISM_ROOT="$other_tree" sh "$GATE" 2>&1)
assert_eq "2" "$?" "a tree that disagrees with the hook's own location denies"
assert_contains "$out" "disagree" "the denial explains the mismatch"
rm -rf "$other_tree"

# --- refspec awareness ----------------------------------------------------
# These ship no new commits from this branch, so an hour of instrumentation
# would verify nothing relevant to what is actually being sent.
for cmd in "git push --delete origin old-branch" "git push --tags" \
           "git push --dry-run"; do
    out=$(event "$cmd" | PRISM_DRY_RUN=1 sh "$GATE" 2>&1)
    assert_eq "0" "$?" "[$cmd] is allowed without running the pipeline"
    assert_not_contains "$out" "push range:" \
        "[$cmd] does not even resolve a range"
done

# An ordinary push still runs the pipeline.
out=$(event "git push origin master" | PRISM_ROOT="$wiring_repo" \
    PRISM_DRY_RUN=1 sh "$WIRING_GATE" 2>&1)
assert_contains "$out" "push range:" "an ordinary push still resolves a range"

# --- gate 3 CHECKS a result rather than producing one ---------------------
# The hook must not invoke connectedDebugAndroidTest itself: that is an hour
# of work behind a timeout that does not block when it expires.
# Distinguish EXECUTING from INSTRUCTING: the hook executes via
# "$PRISM_ROOT/gradlew", while the instructions it prints say "./gradlew".
assert_eq "0" "$(grep -c 'PRISM_ROOT/gradlew".*connectedDebugAndroidTest' "$VERIFY")" \
    "the hook never RUNS connectedDebugAndroidTest, it only instructs"
assert_eq "0" "$(grep -cE '^[[:space:]]*android emulator start' "$VERIFY")" \
    "the hook never boots an emulator itself"
# It must still TELL you how, or the denial is not actionable.
assert_contains "$(cat "$VERIFY")" "connectedDebugAndroidTest" \
    "the hook still names the command you have to run"

# --- 7.1: a worktree of the SAME repo is not a lockout --------------------
# prism_require_root ran BEFORE the event was read, let alone classified, so
# every Bash call from a git worktree exited 2 — `echo hello` included. Six
# agents dispatched into worktrees could not run `pwd`. The gate only ever
# wanted to gate pushes, and a worktree of the same repo runs the same hook
# code against the same project, so neither case has a reason to deny.
#
# Fixtures, not this checkout: the hook's implied root comes from $0, so
# proving "same repo" needs a repo whose hook tree the test controls.
. "$HOOK_DIR/tests/fixtures.sh"

wt_repo=$(prism_fixture_repo)
wt_tree=$(prism_fixture_worktree "$wt_repo")
WT_GATE="$wt_repo/.prism/verify/push-gate.sh"

out=$(event "echo hello" | PRISM_ROOT="$wt_tree" sh "$WT_GATE" 2>&1)
assert_eq "0" "$?" "a non-push command from a same-repo worktree is allowed"
assert_not_contains "$out" "disagree" \
    "a non-push command from a worktree is not answered with a root opinion"

out=$(event "git push" | PRISM_ROOT="$wt_tree" PRISM_DRY_RUN=1 \
    sh "$WT_GATE" 2>&1)
assert_contains "$out" "push range:" \
    "a real push from a same-repo worktree is still EVALUATED"
assert_not_contains "$out" "disagree" \
    "a same-repo worktree is not treated as a foreign checkout"

# --- 7.1.4: the protection the guard exists for still fires ---------------
# Same-repo worktree allowed is only correct if a genuinely different
# repository is still denied. Asserted in both directions so the fix cannot
# be mistaken for "the check was removed".
foreign_repo=$(prism_fixture_repo)
out=$(event "git push" | PRISM_ROOT="$foreign_repo" sh "$WT_GATE" 2>&1)
assert_eq "2" "$?" "a push against a DIFFERENT repository still denies"
assert_contains "$out" "disagree" "the foreign-checkout denial names the mismatch"

no_settings=$(mktemp -d)
out=$(event "git push" | PRISM_ROOT="$no_settings" sh "$WT_GATE" 2>&1)
assert_eq "2" "$?" "a root with no settings.gradle.kts still denies"
assert_contains "$out" "no usable repository root" \
    "the unusable-root denial names the cause"
rm -rf "$no_settings"

# Classification must stay fail-closed even though it now runs first: an
# interpreter that cannot be asked the question denies, it does not allow.
stub_dir=$(mktemp -d)
printf '#!/bin/sh\nexit 127\n' > "$stub_dir/python3"
chmod +x "$stub_dir/python3"
out=$(event "echo hello" | PRISM_ROOT="$wt_tree" PATH="$stub_dir:$PATH" \
    sh "$WT_GATE" 2>&1)
assert_eq "2" "$?" \
    "classification running first is still fail-closed when python3 is broken"
rm -rf "$stub_dir"

prism_fixture_cleanup "$wt_repo" "$wt_tree"
rm -rf "$foreign_repo"

# --- 7.3.3: a killed device run is denied in its OWN words ----------------
# The loser of two concurrent connected runs produces result output but no
# .ec, and the gate used to call that `no-device-run` — "you never ran it" —
# which sends the reader off to repeat the exact thing they just did.
device_repo=$(prism_fixture_repo)
prism_fixture_branch_commit "$device_repo"
DEVICE_GATE="$device_repo/.prism/verify/push-gate.sh"
# Gate 2 (compile + unit tests) runs ahead of gate 3, so the fixture needs a
# gradlew that succeeds — this case is about what gate 3 SAYS, not about the
# build.
prism_fixture_gradlew "$device_repo"

# src/androidTest makes the module's report kind `combined`, which is the only
# kind for which execution data is even consulted.
mkdir -p "$device_repo/core/domain/src/androidTest/java"
printf 'class SeedAndroidTest\n' \
    > "$device_repo/core/domain/src/androidTest/java/SeedAndroidTest.kt"

# AGP's connected-run output, with NO .ec beside it: a run that happened and
# lost its data.
mkdir -p "$device_repo/core/domain/build/outputs/androidTest-results/connected"
printf '<testsuite/>' \
    > "$device_repo/core/domain/build/outputs/androidTest-results/connected/TEST-emulator.xml"

# A report that is otherwise current, so freshness is the only thing wrong.
mkdir -p "$device_repo/core/domain/build/reports/prism-combined-coverage"
printf '<report name="x"><counter type="LINE" missed="1" covered="99"/></report>\n' \
    > "$device_repo/core/domain/build/reports/prism-combined-coverage/coverage.xml"
sleep 1
touch "$device_repo/core/domain/build/outputs/androidTest-results/connected/TEST-emulator.xml" \
      "$device_repo/core/domain/build/reports/prism-combined-coverage/coverage.xml"

out=$(event "git push" | PRISM_ROOT="$device_repo" \
    PRISM_PUSH_FORCE_MODULES=":core:domain" sh "$DEVICE_GATE" 2>&1)
assert_eq "2" "$?" "a device run that brought back no execution data denies"
assert_contains "$out" "device-run-no-data" \
    "the denial names the verdict rather than folding it into no-device-run"
assert_contains "$out" "brought back no" \
    "the denial says the suite RAN and lost its data"
assert_contains "$out" "device_lock.py" \
    "the denial tells you to re-run through the device lock"
assert_not_contains "$out" "(no-device-run)" \
    "it is NOT reported as never having run"

# The converse: with no evidence at all it IS never-ran, and says so.
rm -rf "$device_repo/core/domain/build/outputs/androidTest-results"
out=$(event "git push" | PRISM_ROOT="$device_repo" \
    PRISM_PUSH_FORCE_MODULES=":core:domain" sh "$DEVICE_GATE" 2>&1)
assert_eq "2" "$?" "a module that never ran on a device still denies"
assert_contains "$out" "no-device-run" "and it is reported as never having run"
assert_not_contains "$out" "brought back no" \
    "the never-ran denial does not claim a run happened"
rm -rf "$device_repo"

# --- gate C runs even when the push touches no module --------------------
# 0.5.0. A commit touching only .prism/scope.json belongs to no module, so the
# affected-module list is empty -- and the empty-list short-circuit used to sit
# ABOVE gate C and exit 0 there, having verified nothing. The guard's own
# denial text advises landing a weakening as its own commit first, which is
# precisely the shape that slipped through: the documented remedy was the
# exploit. Gate C now runs before the short-circuit.
guard_repo=$(prism_fixture_repo)
cat > "$guard_repo/.prism/scope.json" <<'SCOPEJSON'
{
  "default": {
    "detekt": "enforce",
    "ktlint": "enforce",
    "konsist": "enforce",
    "coverage": "enforce"
  },
  "modules": {}
}
SCOPEJSON
git -C "$guard_repo" -c user.email=fixture@example.com -c user.name=fixture add -A
git -C "$guard_repo" -c user.email=fixture@example.com -c user.name=fixture \
    -c commit.gpgsign=false commit -qm 'scope: enforce everywhere'
git -C "$guard_repo" checkout -q -b weaken-only
cat > "$guard_repo/.prism/scope.json" <<'SCOPEJSON'
{
  "default": {
    "detekt": "observe",
    "ktlint": "enforce",
    "konsist": "enforce",
    "coverage": "enforce"
  },
  "modules": {}
}
SCOPEJSON
git -C "$guard_repo" -c user.email=fixture@example.com -c user.name=fixture add -A
git -C "$guard_repo" -c user.email=fixture@example.com -c user.name=fixture \
    -c commit.gpgsign=false commit -qm 'demote detekt'
GUARD_GATE="$guard_repo/.prism/verify/push-gate.sh"

out=$(event "git push" | PRISM_ROOT="$guard_repo" sh "$GUARD_GATE" 2>&1)
assert_eq "2" "$?" "a push whose ONLY change is a weakening is denied"
assert_contains "$out" "CONFIGURATION GUARD" \
    "and it is gate C that denies it, not a later gate"
assert_contains "$out" "demoted enforce -> observe" \
    "the denial names the demotion it found"

# --- gate C may not fail open when the guard itself is gone --------------
# guard.py is on the packaging manifest, so its absence is a partial install
# or a deleted file -- the exact tamper case this gate exists for. Skipping
# the check because the checker is missing would be invisible: a hook that
# exits 0 routes its stderr to the debug log only.
mv "$guard_repo/.prism/verify/lib/guard.py" "$guard_repo/guard.py.away"
out=$(event "git push" | PRISM_ROOT="$guard_repo" sh "$GUARD_GATE" 2>&1)
assert_eq "2" "$?" "a missing guard.py denies rather than skipping the gate"
assert_contains "$out" "The guard itself is missing" \
    "and it says the checker is gone, not that the configuration is fine"
mv "$guard_repo/guard.py.away" "$guard_repo/.prism/verify/lib/guard.py"
rm -rf "$guard_repo"

# --- gate 3's pre-check reads posture ------------------------------------
# 0.5.0. The pre-check denies a module with production Kotlin and no test
# source set. It ran BEFORE posture was consulted, so `observe` blocked on
# exactly the modules it exists to unblock -- which is the state every
# adopting repository starts in. coverage.py's verdict documents the opposite
# intent in words; this makes the pre-check agree with it.
posture_repo=$(prism_fixture_repo)
prism_fixture_branch_commit "$posture_repo"
POSTURE_GATE="$posture_repo/.prism/verify/push-gate.sh"

# :core:other has src/main and no src/test at all -- the untested case.
out=$(event "git push" | PRISM_ROOT="$posture_repo" \
    PRISM_PUSH_FORCE_MODULES=":core:other" sh "$POSTURE_GATE" 2>&1)
assert_eq "2" "$?" "an ENFORCING module with no tests still denies"
assert_contains "$out" "no tests at all" "and says why"

cat > "$posture_repo/.prism/scope.json" <<'SCOPEJSON'
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
out=$(event "git push" | PRISM_ROOT="$posture_repo" PRISM_DRY_RUN=1 \
    PRISM_PUSH_FORCE_MODULES=":core:other" sh "$POSTURE_GATE" 2>&1)
assert_not_contains "$out" "no tests at all" \
    "the same module OBSERVED is not denied by the pre-check"
rm -rf "$posture_repo"

rm -rf "$wiring_repo"

rm -rf "$kind_repo"

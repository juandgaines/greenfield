# Tests for stop-gate.sh short-circuits. Sourced by run-tests.sh.
GATE="$HOOK_DIR/stop-gate.sh"

# The pass-cache is a real artifact of real verified runs, and a warm one
# silently short-circuits these assertions. Point the gate at a throwaway
# state dir so the suite never depends on, or damages, developer state.
PRISM_STATE_DIR=$(mktemp -d)
export PRISM_STATE_DIR

# --- "nothing changed" is a property of a FIXTURE, not of this checkout ---
# 7.5.1. Every assertion below needs a tree with nothing in it to verify.
# They used to get that by pointing the real repo at PRISM_BASE_BRANCH=HEAD,
# which empties the branch diff but says nothing about uncommitted work — so
# they passed on a clean tree and failed mid-session with work in progress,
# with no change to either the gate or the test. Observed exactly that during
# section 6: failing before a commit, passing after it.
#
# A throwaway repo with one commit and a clean tree makes the precondition
# true by construction, for whoever runs the suite and whatever they have
# open.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

clean_repo=$(prism_fixture_repo)
CLEAN_GATE="$clean_repo/.prism/verify/stop-gate.sh"
clean_state=$(mktemp -d)

# --- loop guard ---
# On the fixture too. This drove the REAL gate against the ambient checkout
# with no base branch pinned, so it ran a live Gradle build over every module
# differing from master and asserted the result was 0. That passes wherever
# the build happens to be warm and fails wherever it is not — it failed the
# moment it was run from a fresh worktree. Same coupling as everything else
# in this section; it was missed on the first pass because the main checkout
# hid it.
out=$(printf '{"stop_hook_active": true, "hook_event_name": "Stop"}' \
    | PRISM_ROOT="$clean_repo" PRISM_STATE_DIR="$clean_state" sh "$CLEAN_GATE" 2>&1)
assert_eq "0" "$?" "a continuation over a tree with nothing to do exits 0"
assert_eq "" "$out" "loop guard produces no output"

# --- malformed JSON must not block ---
out=$(printf 'not json at all' \
    | PRISM_ROOT="$clean_repo" PRISM_STATE_DIR="$clean_state" sh "$CLEAN_GATE" 2>&1)
assert_eq "0" "$?" "unparseable stdin does not block when nothing changed"
assert_contains "$out" "could not read the Stop event" "a parse failure is reported, not silent"
assert_contains "$out" "loop guard is unavailable" "the lost loop guard is called out"

# --- python3 unavailable must not block silently ---
stub_dir=$(mktemp -d)
printf '#!/bin/sh\nexit 127\n' > "$stub_dir/python3"
chmod +x "$stub_dir/python3"
out=$(printf '{"stop_hook_active": true}' \
    | PATH="$stub_dir:$PATH" PRISM_ROOT="$clean_repo" \
      PRISM_STATE_DIR="$clean_state" sh "$CLEAN_GATE" 2>&1)
assert_eq "0" "$?" "a broken python3 does not block when nothing changed"
assert_contains "$out" "loop guard is unavailable" \
    "a broken python3 is reported, not silent"
rm -rf "$stub_dir"

# --- nothing changed at all: no modules, so no gates ---
out=$(printf '{"stop_hook_active": false}' \
    | PRISM_ROOT="$clean_repo" PRISM_STATE_DIR="$clean_state" sh "$CLEAN_GATE" 2>&1)
assert_eq "0" "$?" "no changed Kotlin files exits 0"
assert_eq "" "$out" "no-change path produces no output"

# The converse, so the three cases above cannot pass by being vacuous: an
# uncommitted Kotlin edit in the SAME fixture is picked up and gated.
printf 'class Added\n' > "$clean_repo/core/domain/src/main/java/Added.kt"
out=$(printf '{"stop_hook_active": false}' \
    | PRISM_ROOT="$clean_repo" PRISM_STATE_DIR="$clean_state" PRISM_DRY_RUN=1 \
      sh "$CLEAN_GATE" 2>&1)
assert_eq "2" "$?" "an uncommitted Kotlin edit in the fixture IS gated"
assert_contains "$out" ":core:domain" "the edited module is derived from the fixture"
rm -f "$clean_repo/core/domain/src/main/java/Added.kt"

rm -rf "$clean_state"
rm -rf "$clean_repo"

# --- dry run reports the plan and does not invoke gradle ---
#
# Against a FIXTURE module graph, not the ambient checkout. Which Gradle task a
# module gets — assemble versus assembleDebug, test versus testDebugUnitTest —
# is derived from that module's build file, so the assertion needs a repository
# that actually declares the modules it names. Reading them off whichever
# checkout is open worked only while this repository was itself the Android
# project under test.
task_repo=$(prism_fixture_graph_repo)
TASK_GATE="$task_repo/.prism/verify/stop-gate.sh"
task_state=$(mktemp -d)

out=$(printf '{"stop_hook_active": false}' \
    | PRISM_ROOT="$task_repo" PRISM_STATE_DIR="$task_state" \
      PRISM_DRY_RUN=1 PRISM_STOP_FORCE_MODULES=":core:domain" \
      sh "$TASK_GATE" 2>&1)
# A dry run DENIES. It reports the plan and runs no gate, so it has no
# verdict; exiting 0 would make PRISM_DRY_RUN=1 in the environment a silent
# bypass of both gates, announced only into a log nobody reads.
assert_eq "2" "$?" "dry run denies rather than allowing"
assert_contains "$out" "nothing was verified" \
    "the dry run says plainly that nothing was verified"
assert_contains "$out" ":core:domain:assemble" \
    "JVM-only module uses the assemble task, not assembleDebug"
assert_not_contains "$out" ":core:domain:build" \
    "JVM-only module does NOT use :build — it runs the tests under the compile label"
assert_contains "$out" ":core:domain:test" \
    "JVM-only module uses the test task, not testDebugUnitTest"

out=$(printf '{"stop_hook_active": false}' \
    | PRISM_ROOT="$task_repo" PRISM_STATE_DIR="$task_state" \
      PRISM_DRY_RUN=1 PRISM_STOP_FORCE_MODULES=":core:crypto" \
      sh "$TASK_GATE" 2>&1)
assert_contains "$out" ":core:crypto:assembleDebug" \
    "android module uses assembleDebug"
assert_contains "$out" ":core:crypto:testDebugUnitTest" \
    "android module uses testDebugUnitTest"
assert_not_contains "$out" "connectedDebugAndroidTest" \
    "the stop gate never runs instrumentation tests"

# --- a module with no src/test skips the unit-test task ---
out=$(printf '{"stop_hook_active": false}' \
    | PRISM_ROOT="$task_repo" PRISM_STATE_DIR="$task_state" \
      PRISM_DRY_RUN=1 PRISM_STOP_FORCE_MODULES=":core:design-system" \
      sh "$TASK_GATE" 2>&1)
assert_contains "$out" ":core:design-system:assembleDebug" \
    "design-system still gets assembled"
assert_not_contains "$out" ":core:design-system:testDebugUnitTest" \
    "design-system has no src/test so the test task is skipped"

rm -rf "$PRISM_STATE_DIR"
unset PRISM_STATE_DIR

# --- REGRESSION: the loop guard must not surrender after ONE block --------
# stop_hook_active is a BOOLEAN. Treating it as "give up immediately" meant
# the gate blocked once, Claude claimed a fix, and the very next Stop was
# allowed unconditionally — the turn that mattered most was never verified.
probe_state=$(mktemp -d)

for count in 0 1 2; do
    printf '%s' "$count" > "$probe_state/stop-block-count"
    printf '{"stop_hook_active": true}' \
        | PRISM_STATE_DIR="$probe_state" PRISM_DRY_RUN=1 \
          PRISM_STOP_FORCE_MODULES=":core:domain" sh "$GATE" >/dev/null 2>&1
    assert_eq "2" "$?" "a continuation with $count prior blocks still verifies"
done

printf '3' > "$probe_state/stop-block-count"
out=$(printf '{"stop_hook_active": true}' \
    | PRISM_STATE_DIR="$probe_state" PRISM_DRY_RUN=1 \
      PRISM_STOP_FORCE_MODULES=":core:domain" sh "$GATE" 2>&1)
assert_eq "0" "$?" "the budget is bounded, so it eventually gives up"
assert_contains "$out" "giving up" "giving up says so rather than passing silently"
# stderr from a hook that exits 0 goes to the debug log only and Claude never
# sees it, so this notice used to be printed where nobody would ever read it —
# the one moment the gate concedes a turn is ending unverified.
assert_contains "$out" "systemMessage" \
    "the give-up notice goes out on the one channel an exit-0 hook has"
assert_contains "$out" "UNVERIFIED" \
    "and it says plainly that the gates never passed"

# Every deny increments the budget, or it is not bounded at all.
printf '0' > "$probe_state/stop-block-count"
printf '{"stop_hook_active": true}' \
    | PRISM_STATE_DIR="$probe_state" PRISM_DRY_RUN=1 \
      PRISM_STOP_FORCE_MODULES=":core:domain" sh "$GATE" >/dev/null 2>&1
assert_eq "1" "$(cat "$probe_state/stop-block-count")" "a deny increments the budget"

# A fresh (non-continuation) stop starts a new turn-chain, so a stale count
# cannot eat the next real failure's budget. Driven through a clean fixture
# rather than the ambient checkout: the reset happens before any scope is
# derived, and a dirty developer tree would otherwise send this assertion
# into a real Gradle run.
reset_repo=$(prism_fixture_repo)
RESET_GATE="$reset_repo/.prism/verify/stop-gate.sh"
printf '2' > "$probe_state/stop-block-count"
printf '{"stop_hook_active": false}' \
    | PRISM_ROOT="$reset_repo" PRISM_STATE_DIR="$probe_state" \
      sh "$RESET_GATE" >/dev/null 2>&1
assert_eq "0" "$(cat "$probe_state/stop-block-count")" "a fresh stop resets the budget"
rm -rf "$reset_repo"

rm -rf "$probe_state"

# --- stop_block must terminate, not recurse ------------------------------
# A careless edit once defined stop_block's body to call stop_block, which
# hung the hook until its timeout — and a timed-out hook renders no decision.
assert_eq "0" "$(awk '/^stop_block\(\) \{/,/^\}/' "$GATE" | grep -c 'stop_block$')" \
    "stop_block does not call itself"

# --- 7.1: a worktree of the SAME repo must not block every turn ----------
# prism_require_root compared the hook's implied root against the working
# tree by STRING, so an agent working in a worktree had its Stop gate deny
# unconditionally — not because anything failed, but because the two paths
# differ by construction. A worktree of the same repo runs the same hook code
# against the same project; it is safe by construction, which is exactly what
# a path comparison cannot express.

sg_repo=$(prism_fixture_repo)
sg_tree=$(prism_fixture_worktree "$sg_repo")
SG_GATE="$sg_repo/.prism/verify/stop-gate.sh"
sg_state=$(mktemp -d)

out=$(printf '{"stop_hook_active": false}' \
    | PRISM_ROOT="$sg_tree" PRISM_STATE_DIR="$sg_state" PRISM_DRY_RUN=1 \
      PRISM_STOP_FORCE_MODULES=":core:domain" sh "$SG_GATE" 2>&1)
assert_not_contains "$out" "no usable repository root" \
    "a same-repo worktree is not an unusable root"
assert_contains "$out" "would run: :core:domain:assemble" \
    "a Stop from a same-repo worktree reaches the gates"

# The protection still fires: a genuinely different repository is denied.
sg_foreign=$(prism_fixture_repo)
out=$(printf '{"stop_hook_active": false}' \
    | PRISM_ROOT="$sg_foreign" PRISM_STATE_DIR="$sg_state" \
      PRISM_STOP_FORCE_MODULES=":core:domain" sh "$SG_GATE" 2>&1)
assert_eq "2" "$?" "a Stop against a DIFFERENT repository still blocks"
assert_contains "$out" "no usable repository root" \
    "the foreign-checkout block names the cause"
rm -rf "$sg_foreign"

rm -rf "$sg_state"
prism_fixture_cleanup "$sg_repo" "$sg_tree"

# --- the repo build lock: concurrent gate builds never interleave ----------
# Parallel sessions sharing one checkout each run gate builds, and Gradle
# does not serialize whole builds across daemons; the lock does. Three
# properties matter: a held lock makes the gate refuse to build (loudly), a
# lock whose holder is dead is broken rather than waited on, and a verified
# pass leaves no lock behind.

lk_repo=$(prism_fixture_repo)
LK_GATE="$lk_repo/.prism/verify/stop-gate.sh"
lk_state=$(mktemp -d)
prism_fixture_gradlew "$lk_repo"
printf 'class Locked\n' > "$lk_repo/core/domain/src/main/java/Locked.kt"

# Held by a LIVE process (this test runner): the gate must not start a build
# under it. PRISM_BUILD_LOCK_WAIT=0 skips the wait, not the refusal.
mkdir -p "$lk_repo/.gradle/prism-verify-build.lock"
printf '%s' "$$" > "$lk_repo/.gradle/prism-verify-build.lock/pid"
out=$(printf '{"stop_hook_active": false}' \
    | PRISM_ROOT="$lk_repo" PRISM_STATE_DIR="$lk_state" \
      PRISM_BUILD_LOCK_WAIT=0 sh "$LK_GATE" 2>&1)
assert_eq "2" "$?" "a live-held build lock blocks the stop"
assert_contains "$out" "build lock" "the lock refusal names the lock"
if [ -f "$lk_repo/.fixture-gradle-tasks" ]; then
    PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
    printf 'FAIL the gate ran gradle under a held lock\n'
else
    PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + 1))
fi

# Held by a DEAD process: broken and verified through, and the pass releases
# the lock. The dead PID is real — a shell that has already exited.
dead_pid=$(sh -c 'echo $$')
printf '%s' "$dead_pid" > "$lk_repo/.gradle/prism-verify-build.lock/pid"
out=$(printf '{"stop_hook_active": false}' \
    | PRISM_ROOT="$lk_repo" PRISM_STATE_DIR="$lk_state" \
      PRISM_BUILD_LOCK_WAIT=0 sh "$LK_GATE" 2>&1)
assert_eq "0" "$?" "a dead holder's lock is broken, not waited on"
assert_contains "$(cat "$lk_repo/.fixture-gradle-tasks" 2>/dev/null)" \
    "staticAnalysis" "the gate built once past the stale lock"
if [ -d "$lk_repo/.gradle/prism-verify-build.lock" ]; then
    PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
    printf 'FAIL a verified pass left the build lock behind\n'
else
    PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + 1))
fi

rm -rf "$lk_state"
prism_fixture_cleanup "$lk_repo"

rm -rf "$task_repo" "$task_state"

# --- the failure headline names the failure it actually found -------------
# 0.5.0. This gate requests four task names, but Gradle reports the task that
# actually failed -- almost always a dependency of the one requested. The
# catch-all read "unit tests failed", so an ordinary Kotlin syntax error
# arriving as `:core:domain:compileDebugKotlin` was announced as a test
# failure, in a module that need not have any tests at all. The body always
# printed the real stack; the headline is what a reader acts on first.
hd_repo=$(prism_fixture_repo)
HD_GATE="$hd_repo/.prism/verify/stop-gate.sh"
hd_state=$(mktemp -d)
prism_fixture_gradlew "$hd_repo"

hd_run() {
    printf '%s' "$1" > "$hd_repo/.fixture-gradle-failed-task"
    rm -rf "$hd_state"
    hd_state=$(mktemp -d)
    printf 'class Headline%s\n' "$2" \
        > "$hd_repo/core/domain/src/main/java/Headline.kt"
    printf '{"stop_hook_active": false}' \
        | PRISM_ROOT="$hd_repo" PRISM_STATE_DIR="$hd_state" \
          PRISM_BUILD_LOCK_WAIT=0 sh "$HD_GATE" 2>&1
}

out=$(hd_run ":core:domain:compileDebugKotlin" A)
assert_contains "$out" "does not compile" \
    "a compile failure is reported as a compile failure"
assert_not_contains "$out" "unit tests failed" \
    "a compile failure is NOT reported as a test failure"

out=$(hd_run ":core:domain:kspDebugKotlin" B)
assert_not_contains "$out" "unit tests failed" \
    "a ksp failure is not reported as a test failure either"

out=$(hd_run ":core:domain:minifyReleaseWithR8" C)
assert_contains "$out" "failed to build at minifyReleaseWithR8" \
    "a shrinker failure names the task and claims neither compile nor test"
assert_not_contains "$out" "unit tests failed" \
    "and it is not reported as a test failure"

out=$(hd_run ":core:domain:test" D)
assert_contains "$out" "unit tests failed in :core:domain" \
    "a real test task IS reported as a test failure"

out=$(hd_run ":core:domain:testDebugUnitTest" E)
assert_contains "$out" "unit tests failed in :core:domain" \
    "and so is the Android unit-test task"

rm -rf "$hd_state"
prism_fixture_cleanup "$hd_repo"

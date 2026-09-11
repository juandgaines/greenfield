# Tests for subagent-gate.sh. Sourced by run-tests.sh.
#
# Everything here runs against a fixture repository with a stub gradlew.
# Nothing about this gate's WIRING — which modules it derives, whether a
# failure denies, how many times it denies before reporting — needs a real
# Android build, and asserting it against the developer's checkout is the
# coupling section 7.5 exists to remove.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

sub_repo=$(prism_fixture_repo)
prism_fixture_gradlew "$sub_repo"
SUB_GATE="$sub_repo/.prism/verify/subagent-gate.sh"
sub_state=$(mktemp -d)

sub_event() {
    # sub_event <cwd> [<agent_id>] [<agent_type>] — a SubagentStop event as JSON
    python3 -c '
import json, sys
print(json.dumps({
    "hook_event_name": "SubagentStop",
    "session_id": "test-session",
    "cwd": sys.argv[1],
    "agent_id": sys.argv[2],
    "agent_type": sys.argv[3],
}))
' "$1" "${2:-agent-1}" "${3:-general-purpose}"
}

sub_run() {
    # sub_run <cwd> <agent_id> [extra env assignments are the caller's job]
    sub_event "$1" "$2" | PRISM_ROOT="$sub_repo" PRISM_STATE_DIR="$sub_state" \
        sh "$SUB_GATE" 2>&1
}

# --- a clean tree has nothing to verify ----------------------------------
out=$(sub_run "$sub_repo" agent-clean)
assert_eq "0" "$?" "a subagent that changed nothing is allowed to stop"
assert_eq "" "$out" "the no-change path is silent"

# --- a green module is not denied ----------------------------------------
printf 'class Seed { val added = 1 }\n' > "$sub_repo/core/domain/src/main/java/Seed.kt"
printf '0' > "$sub_repo/.fixture-gradle-exit"
rm -f "$sub_repo/.fixture-gradle-tasks"
out=$(sub_run "$sub_repo" agent-green)
assert_eq "0" "$?" "a subagent that leaves a GREEN module may stop"
assert_eq "" "$out" "a green subagent stop is silent"
tasks=$(tr '\n' ' ' < "$sub_repo/.fixture-gradle-tasks")
assert_contains "$tasks" ":core:domain:assemble" "the touched module is compiled"
assert_contains "$tasks" ":core:domain:test" "the touched module's unit tests run"

# --- scope: the modules TOUCHED, not their dependents ---------------------
# :core:other consumes :core:domain in this fixture. The Stop gate widens to
# dependents on purpose; this one must not, or ten subagents each drag the
# whole graph through Gradle.
assert_not_contains "$tasks" ":core:other" \
    "the subagent gate does NOT widen to dependents"

# --- scope: derived from the TREE, not from the agent's own claim ---------
# The event carries agent_type and cwd and nothing about scope, and the gate
# asks git rather than the subagent. A module edited outside the subagent's
# brief is exactly the case worth catching, so a second edited module must
# appear without anyone declaring it.
printf 'class Other { val added = 1 }\n' > "$sub_repo/core/other/src/main/java/Other.kt"
rm -f "$sub_repo/.fixture-gradle-tasks"
out=$(sub_run "$sub_repo" agent-two-modules)
tasks=$(tr '\n' ' ' < "$sub_repo/.fixture-gradle-tasks")
assert_contains "$tasks" ":core:domain:assemble" "an edit in the brief is picked up"
assert_contains "$tasks" ":core:other:assemble" \
    "an edit OUTSIDE the brief is picked up too, from the tree"
git -C "$sub_repo" checkout -q -- core/other/src/main/java/Other.kt

# --- a global build file does NOT pull in every module --------------------
# The Stop gate widens to all thirteen there, and must; this gate would turn
# that into ten concurrent whole-project builds.
printf 'rootProject.name = "fixture"\n' >> "$sub_repo/settings.gradle.kts"
out=$(sub_event "$sub_repo" agent-global | PRISM_ROOT="$sub_repo" \
    PRISM_STATE_DIR="$sub_state" PRISM_DRY_RUN=1 sh "$SUB_GATE" 2>&1)
assert_not_contains "$out" ":core:other" \
    "a settings.gradle.kts edit does not drag in every module"
git -C "$sub_repo" checkout -q -- settings.gradle.kts

# --- a module that applies detekt runs its own detekt task -----------------
# `assemble` does not ride `check` the way `build` did, so per-module detekt
# silently vanished from this gate when the task pair changed — and this gate
# runs no repo-wide staticAnalysis to compensate, deliberately. The detekt
# task comes back module-scoped, and ONLY for modules that apply the plugin.
printf 'plugins {\n    id("acme.kotlin.java.library")\n    id("prism.detekt")\n}\n' \
    > "$sub_repo/core/domain/build.gradle.kts"
rm -rf "$sub_state/subagent-pass"
rm -f "$sub_repo/.fixture-gradle-tasks"
out=$(sub_run "$sub_repo" agent-detekt)
assert_eq "0" "$?" "a green module applying detekt may stop"
tasks=$(tr '\n' ' ' < "$sub_repo/.fixture-gradle-tasks")
assert_contains "$tasks" ":core:domain:detekt" \
    "a module applying prism.detekt runs its detekt task at the boundary"
assert_not_contains "$tasks" ":core:other:detekt" \
    "a module without the plugin does not get a detekt task it does not have"
git -C "$sub_repo" checkout -q -- core/domain/build.gradle.kts

# --- a broken module IS denied -------------------------------------------
# The source has to change too: the pass marker above is keyed on content, so
# leaving the tree identical would (correctly) short-circuit and this case
# would assert nothing.
printf 'class Seed { val broken = }\n' > "$sub_repo/core/domain/src/main/java/Seed.kt"
printf '1' > "$sub_repo/.fixture-gradle-exit"
rm -f "$sub_state/subagent-block-agent-broken"
out=$(sub_run "$sub_repo" agent-broken)
assert_eq "2" "$?" "a subagent that leaves a NON-COMPILING module is denied"
assert_contains "$out" "SUBAGENT VERIFICATION GATE 1 FAILED" \
    "the denial names the gate that failed"
assert_contains "$out" ":core:domain" "the denial names the broken module"
assert_contains "$out" "FIXTURE BUILD FAILURE" "the denial carries the build output"
assert_eq "1" "$(cat "$sub_state/subagent-block-agent-broken")" \
    "a denial increments that subagent's block budget"

# --- the budget is bounded: deny once, then report -----------------------
# A subagent often cannot fix what it broke, and blocking it forever holds up
# the whole wave. The second stop is allowed — loudly, and only because the
# Stop gate still refuses to end the turn on the same breakage.
out=$(sub_run "$sub_repo" agent-broken)
assert_eq "0" "$?" "the second stop is allowed rather than blocking forever"
assert_contains "$out" "STILL BROKEN" "giving up says the work is still broken"
assert_contains "$out" "./gradlew :core:domain:assemble" \
    "the report names the exact failing task"
assert_contains "$out" "systemMessage" \
    "the report goes out as systemMessage, the only channel an exit-0 hook has"
assert_contains "$out" "Stop gate will refuse" \
    "the report names what still enforces it"

# A DIFFERENT subagent gets its own budget: one agent's failure must not
# spend another's.
rm -f "$sub_state/subagent-block-agent-second"
out=$(sub_run "$sub_repo" agent-second)
assert_eq "2" "$?" "a different subagent still gets its own first denial"

# --- a pass clears the budget --------------------------------------------
printf '0' > "$sub_repo/.fixture-gradle-exit"
rm -rf "$sub_state/subagent-pass"
out=$(sub_run "$sub_repo" agent-broken)
assert_eq "0" "$?" "a fixed subagent is allowed to stop"
if [ -f "$sub_state/subagent-block-agent-broken" ]; then
    assert_eq "no" "yes" "a pass clears that subagent's block budget"
else
    assert_eq "no" "no" "a pass clears that subagent's block budget"
fi

# --- the pass marker is content-keyed, so concurrent subagents share it ---
# The situation this gate exists for is several subagents stopping at once.
# One shared "last pass" slot would thrash between their scopes and every one
# of them would rebuild.
rm -f "$sub_repo/.fixture-gradle-tasks"
out=$(sub_run "$sub_repo" agent-cache-hit)
assert_eq "0" "$?" "a repeat stop over identical sources is allowed"
if [ -f "$sub_repo/.fixture-gradle-tasks" ]; then
    assert_eq "no" "yes" "an unchanged tree does not rebuild"
else
    assert_eq "no" "no" "an unchanged tree does not rebuild"
fi

# --- dry run DENIES, like the other two gates ----------------------------
out=$(sub_event "$sub_repo" agent-dry | PRISM_ROOT="$sub_repo" \
    PRISM_STATE_DIR="$sub_state" PRISM_DRY_RUN=1 sh "$SUB_GATE" 2>&1)
assert_eq "2" "$?" "a dry run denies rather than allowing"
assert_contains "$out" "nothing was verified" \
    "the dry run says plainly that nothing was verified"

# --- a READ-ONLY subagent is not gated at all ----------------------------
# Subagents share the main checkout, so what this gate sees when a search or
# review agent stops is the MAIN agent's uncommitted work. Denying that agent
# asks it to fix a module it has no tool to edit.
mkdir -p "$sub_repo/.claude/agents"
cat > "$sub_repo/.claude/agents/fixture-reader.md" <<'AGENT'
---
name: fixture-reader
description: read-only fixture agent
tools: Read, Grep, Glob, Bash
---
AGENT
cat > "$sub_repo/.claude/agents/fixture-writer.md" <<'AGENT'
---
name: fixture-writer
description: fixture agent that can edit
tools: Read, Grep, Glob, Bash, Edit
---
AGENT

printf 'class Seed { val readOnlyProbe = }\n' > "$sub_repo/core/domain/src/main/java/Seed.kt"
printf '1' > "$sub_repo/.fixture-gradle-exit"
rm -rf "$sub_state/subagent-pass"
rm -f "$sub_repo/.fixture-gradle-tasks" "$sub_state/subagent-block-agent-reader"

out=$(sub_event "$sub_repo" agent-reader fixture-reader | PRISM_ROOT="$sub_repo" \
    PRISM_STATE_DIR="$sub_state" sh "$SUB_GATE" 2>&1)
assert_eq "0" "$?" "a read-only subagent is allowed to stop"
if [ -f "$sub_repo/.fixture-gradle-tasks" ]; then
    assert_eq "no" "yes" "a read-only subagent does not pay for a Gradle build"
else
    assert_eq "no" "no" "a read-only subagent does not pay for a Gradle build"
fi

# The SAME broken tree, an agent that can edit: still denied. Without this the
# case above would prove nothing but a warm cache.
rm -f "$sub_state/subagent-block-agent-writer"
out=$(sub_event "$sub_repo" agent-writer fixture-writer | PRISM_ROOT="$sub_repo" \
    PRISM_STATE_DIR="$sub_state" sh "$SUB_GATE" 2>&1)
assert_eq "2" "$?" "an editing subagent is still denied on that same tree"

# The built-in read-only agents ship no definition file here, so they are the
# only names the gate hardcodes.
rm -f "$sub_repo/.fixture-gradle-tasks"
out=$(sub_event "$sub_repo" agent-explore Explore | PRISM_ROOT="$sub_repo" \
    PRISM_STATE_DIR="$sub_state" sh "$SUB_GATE" 2>&1)
assert_eq "0" "$?" "the built-in Explore agent is not gated either"

# Unknown means GATE, not skip.
rm -f "$sub_state/subagent-block-agent-unknown"
out=$(sub_event "$sub_repo" agent-unknown no-such-agent-definition \
    | PRISM_ROOT="$sub_repo" PRISM_STATE_DIR="$sub_state" sh "$SUB_GATE" 2>&1)
assert_eq "2" "$?" "an agent type with no definition is verified, not skipped"

# An agent type is event data, so it must never become a path segment
# unchecked.
rm -f "$sub_state/subagent-block-agent-traversal"
out=$(sub_event "$sub_repo" agent-traversal '../../../etc/passwd' \
    | PRISM_ROOT="$sub_repo" PRISM_STATE_DIR="$sub_state" sh "$SUB_GATE" 2>&1)
assert_eq "2" "$?" "a path-traversing agent type is verified, not skipped"

rm -rf "$sub_repo/.claude/agents"
git -C "$sub_repo" checkout -q -- core/domain/src/main/java/Seed.kt
printf '0' > "$sub_repo/.fixture-gradle-exit"

# --- a subagent working in a WORKTREE is verified, not skipped -----------
# This is the case 7.1 unblocked, and the reason this gate has to read the
# tree from the event rather than from its own working directory.
sub_tree=$(prism_fixture_worktree "$sub_repo")
cp "$sub_repo/gradlew" "$sub_tree/gradlew"
printf '0' > "$sub_tree/.fixture-gradle-exit"
printf 'class Seed { val inWorktree = 1 }\n' > "$sub_tree/core/domain/src/main/java/Seed.kt"
out=$(sub_event "$sub_tree" agent-worktree | PRISM_STATE_DIR="$sub_state" \
    sh "$SUB_GATE" 2>&1)
assert_eq "0" "$?" "a subagent stopping in a same-repo worktree is verified"
tasks=$(tr '\n' ' ' < "$sub_tree/.fixture-gradle-tasks")
assert_contains "$tasks" ":core:domain:assemble" \
    "the worktree's OWN changes are what get built"

# --- an unverifiable tree reports, it does not block the wave ------------
foreign=$(mktemp -d)
out=$(sub_event "$foreign" agent-foreign | PRISM_ROOT="$foreign" \
    PRISM_STATE_DIR="$sub_state" sh "$SUB_GATE" 2>&1)
assert_eq "0" "$?" "an unverifiable tree does not block the subagent"
assert_contains "$out" "NOT checked" "it says plainly that nothing was checked"
assert_contains "$out" "systemMessage" "and it says so where someone can see it"
rm -rf "$foreign"

# --- no new bypass ------------------------------------------------------
# Section 7's standing rule. The only env vars this gate reads are the two
# test seams that DENY (dry run) or narrow scope for a fixture, plus the
# state-dir and block-budget overrides. None of them can produce an allow
# that skips a verdict.
assert_eq "0" "$(grep -cE 'PRISM_(SKIP|DISABLE|OFF)' "$SUB_GATE")" \
    "the subagent gate has no skip switch"
assert_eq "0" "$(grep -c 'FORCE_MODULES' "$SUB_GATE")" \
    "the subagent gate has no force-modules seam either"

git -C "$sub_repo" checkout -q -- core/domain/src/main/java/Seed.kt
rm -rf "$sub_state"
prism_fixture_cleanup "$sub_repo" "$sub_tree"

#!/bin/sh
# Stop hook — Principle VII gates 0, 1 and 2 for affected modules.
#
#   gate 0: static analysis passes across the repository
#   gate 1: the module compiles
#   gate 2: the module's JVM unit tests pass
#
# Instrumentation tests deliberately run at push time instead; see
# docs/superpowers/specs/2026-08-15-verification-gate-hooks-design.md.
#
# Exit 0 = let Claude stop. Exit 2 = block, reason on stderr.
set -u

HOOK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$HOOK_DIR/lib/affected.sh"

# Overridable so the test suite never collides with real developer state
# (the pass-cache is a real artifact of real verified runs).
STATE_DIR="${PRISM_STATE_DIR:-$HOOK_DIR/state}"
PASS_FILE="$STATE_DIR/stop-last-pass"

# The fingerprint of the tree as it stood the last time this gate exited 0 —
# a verified pass, a legitimate short-circuit, or the loud give-up. Written
# ONLY by this gate, at the moment it renders that verdict.
#
# It used to be written by a UserPromptSubmit hook instead ("what did the
# tree look like when this turn started"), and that was the hole: the
# baseline was rewritten on EVERY prompt, including the prompt after an
# Esc-interrupt, so Kotlin a turn had half-written when it was interrupted —
# code no gate had ever seen — was absorbed into the baseline and the next
# doc-only stop skipped it silently. A baseline only this gate writes cannot
# absorb anything this gate has not at least reported on.
SIGNOFF_FILE="$STATE_DIR/stop-signoff"

BLOCK_COUNT_FILE="$STATE_DIR/stop-block-count"
PRISM_MAX_BLOCKS="${PRISM_MAX_BLOCKS:-3}"

prism_block_count() {
    count=0
    if [ -f "$BLOCK_COUNT_FILE" ]; then
        count=$(cat "$BLOCK_COUNT_FILE" 2>/dev/null || printf '0')
        case "$count" in ''|*[!0-9]*) count=0 ;; esac
    fi
    printf '%s' "$count"
}

# Every deny goes through here so the budget is always accounted for.
stop_block() {
    mkdir -p "$STATE_DIR"
    printf '%s' "$(( $(prism_block_count) + 1 ))" > "$BLOCK_COUNT_FILE"
    exit 2
}

# Record the tree as signed off. The verified-pass path passes the
# fingerprint it captured BEFORE the gates ran: the gates take minutes, and
# Kotlin written into the tree during that window (a parallel session, the
# user in Android Studio) was otherwise absorbed into a fingerprint computed
# at the end — signed off without any gate having seen it. The short-circuit
# paths call this with no argument and fingerprint now, because they run no
# gates and leave no such window.
#
# Best-effort by design: a fingerprint that cannot be computed just means the
# next stop verifies instead of skipping, and this only ever runs on a path
# that is already exiting 0.
stop_signoff() {
    signoff_now="${1:-}"
    if [ -z "$signoff_now" ]; then
        prism_require_root || return 0
        signoff_now=$(prism_turn_fingerprint) || return 0
    fi
    mkdir -p "$STATE_DIR"
    printf '%s' "$signoff_now" > "$SIGNOFF_FILE"
}

input=$(cat)

# --- short-circuit 1: the loop guard --------------------------------------
# Set once Claude is already continuing because this hook blocked. Without
# it, a failure the agent cannot fix becomes an infinite block cycle.
active=$(printf '%s' "$input" | python3 -c '
import sys, json
try:
    event = json.load(sys.stdin)
except Exception:
    print("unparseable")
else:
    print("yes" if event.get("stop_hook_active") else "no")
' 2>/dev/null)

if [ "$active" != "yes" ] && [ "$active" != "no" ]; then
    prism_report 'prism-verify: could not read the Stop event (unparseable JSON, or python3 unavailable); verifying anyway.
prism-verify: the stop_hook_active loop guard is unavailable this run.
'
fi
# stop_hook_active is a BOOLEAN — it says a block happened, not how many.
# Treating it as "give up immediately" meant the gate blocked once, Claude
# claimed a fix, and the very next Stop was allowed unconditionally: the turn
# that mattered most was the one turn never verified. A bounded counter keeps
# the anti-infinite-loop property while still checking the fix.
# A stop that is NOT a continuation is a fresh user turn, and the budget is
# per turn-chain. Resetting here rather than on the verified-pass path alone
# means a short-circuit (nothing changed, cache hit) cannot leave a stale
# count behind to eat the next real failure's budget.
if [ "$active" = "no" ]; then
    mkdir -p "$STATE_DIR"
    printf '0' > "$BLOCK_COUNT_FILE"
fi

# The budget check runs BEFORE any deny path — the root check included. A
# deny that fires ahead of it is a deny outside the budget, and an unusable
# root used to be exactly that: every Stop blocked, forever, with the loop
# guard sitting unreached below it.
if [ "$active" = "yes" ] && [ "$(prism_block_count)" -ge "$PRISM_MAX_BLOCKS" ]; then
    prism_report "$(printf 'prism-verify: giving up after %s blocked attempts. Gates 0, 1 and 2 never
passed, so this turn is ending UNVERIFIED and the work is still broken.
Run the failing task yourself to see what it says.\n' "$PRISM_MAX_BLOCKS")"
    # The give-up is a verdict too: the state was REPORTED, so later turns
    # that leave the tree exactly here are not re-gated. The push gate's own
    # staticAnalysis run is the backstop that keeps it off the remote.
    # Never under a forced scope, though — the report covered the named
    # modules, and a repo-wide sign-off would launder everything else.
    if [ -z "${PRISM_STOP_FORCE_MODULES:-}" ]; then
        stop_signoff
    fi
    exit 0
fi

prism_require_root
root_status=$?
if [ "$root_status" -ne 0 ]; then
    printf 'VERIFICATION BLOCKED — no usable repository root.\n\n' >&2
    printf 'PRISM_ROOT resolved to [%s]. Either it has no settings.gradle.kts, or\n' \
        "$PRISM_ROOT" >&2
    printf 'the hook lives under a different checkout than the working tree (a git\n' >&2
    printf 'worktree). Either way no module can be derived, so nothing would be\n' >&2
    printf 'verified — and verifying the wrong tree is worse than not verifying.\n' >&2
    stop_block
fi

# --- short-circuit 2: nothing moved since the last sign-off ----------------
# The gate's question is "did anything gate-relevant change since this gate
# last rendered a verdict". A branch-scope check could never skip (any real
# feature branch contains Kotlin from its first commit), so a turn spent
# drafting Markdown paid a full staticAnalysis + assemble + unit-test run —
# or was blocked by a Kotlin failure another session had left in the tree.
#
# A missing or unreadable sign-off means "cannot tell", and cannot-tell
# verifies. That is also why an interrupted turn's Kotlin cannot slip
# through here: nothing signed it off, so the fingerprints differ and the
# gates run.
#
# Skipped under PRISM_STOP_FORCE_MODULES: that seam names the scope
# outright, so the tree delta has nothing to say about it.
if [ -z "${PRISM_STOP_FORCE_MODULES:-}" ] &&
        [ -f "$SIGNOFF_FILE" ] &&
        turn_now=$(prism_turn_fingerprint) &&
        [ "$turn_now" = "$(cat "$SIGNOFF_FILE" 2>/dev/null)" ]; then
    exit 0
fi

# --- work out what changed -------------------------------------------------
if [ -n "${PRISM_STOP_FORCE_MODULES:-}" ]; then
    modules="$PRISM_STOP_FORCE_MODULES"
else
    # In this shell, not inside the command substitution below: see the note at
    # the same call in push-gate.sh.
    if ! prism_require_base_branch; then
        printf 'VERIFICATION BLOCKED — no base branch is configured.\n\n' >&2
        printf 'The reason is above. The gate computes the turn'"'"'s scope against\n' >&2
        printf 'the base branch, and it will not guess which branch that is.\n' >&2
        stop_block
    fi

    if ! changed=$(prism_changed_files_stop); then
        printf 'VERIFICATION BLOCKED — could not resolve the base branch.\n\n' >&2
        printf 'Tried PRISM_BASE_BRANCH=%s and origin/%s; neither ref exists in this repo\n' \
            "$PRISM_BASE_BRANCH" "$PRISM_BASE_BRANCH" >&2
        printf '(and the branch has no upstream to fall back to).\n\n' >&2
        printf 'Set PRISM_BASE_BRANCH to a ref that exists, or fetch it locally, then retry.\n' >&2
        stop_block
    fi

    # A convention plugin, the version catalog, a rule set, or a root build
    # file can change how EVERY module builds while belonging to none of
    # them. Those paths used to map to no module, so this gate derived an
    # empty list and exited 0 having verified nothing — on precisely the
    # edit with the widest blast radius. Checked BEFORE the Kotlin filter
    # because libs.versions.toml and detekt.yml are not Kotlin files.
    if printf '%s\n' "$changed" | prism_has_global_build_change; then
        modules=$(prism_all_modules)
    else
        # --- short-circuit 3: nothing Kotlin on the BRANCH -----------------
        # Module scope is still derived from Kotlin files only, but resources
        # are NOT invisible to this gate: they are in the turn fingerprint,
        # so a res or manifest edit after a sign-off re-runs the branch-scope
        # gates, and assembleDebug compiles the resources of every module in
        # that scope (a deleted string breaks consumers of R.string.x). Only
        # a branch with no Kotlin at all skips here; the push gate scopes
        # resources in without an extension filter.
        kotlin_files=$(printf '%s\n' "$changed" | grep -E '\.(kt|kts)$' || true)
        if [ -z "$kotlin_files" ]; then
            stop_signoff
            exit 0
        fi
        modules=$(printf '%s\n' "$kotlin_files" | prism_modules_for_files)
        if [ -z "$modules" ]; then
            stop_signoff
            exit 0
        fi
    fi
fi

# --- widen to everything the change reaches -------------------------------
# Gradle builds a module's dependencies, never its dependents, so building
# only the edited module is not gate 2. An unreadable graph is a hard error:
# silently verifying the narrower scope is the exact failure this closes.
# shellcheck disable=SC2086
if ! modules=$(prism_with_dependents $modules); then
    printf 'VERIFICATION BLOCKED — could not read the module dependency graph.\n\n' >&2
    printf 'lib/deps.py could not derive which modules depend on the changed ones,\n' >&2
    printf 'so this gate cannot tell which modules the change reaches. Fix python3\n' >&2
    printf 'or lib/deps.py, then retry.\n' >&2
    stop_block
fi

# --- dry run: report the plan, then DENY ----------------------------------
# Evaluated BEFORE the pass-cache: a dry run that short-circuits on a warm
# cache prints nothing at all, which is useless for inspecting the plan.
#
# It DENIES rather than allowing. It runs no gate, so it has no verdict — and
# a hook that exits 0 has its stderr routed to the debug log only, never to
# the transcript. Exiting 0 here would make PRISM_DRY_RUN=1 in the
# environment a silent bypass of both gates: exactly the production bypass
# PRISM_SKIP_GATE3 was deleted for. Denying means a leaked variable can only
# ever over-block, loudly.
if [ "${PRISM_DRY_RUN:-}" = "1" ]; then
    for module in $modules; do
        for task in $(prism_build_tasks_for "$module"); do
            printf 'would run: %s\n' "$task" >&2
        done
    done
    printf '\nVERIFICATION BLOCKED — DRY RUN\n\n' >&2
    printf 'PRISM_DRY_RUN=1 is set, so no gate ran: nothing was verified.\n' >&2
    printf 'This is a test-suite seam, not a bypass: unset it to verify.\n' >&2
    stop_block
fi

# --- short-circuit 4: this exact source already passed --------------------
# shellcheck disable=SC2086
current_hash=$(prism_source_hash $modules)
if [ -f "$PASS_FILE" ] && [ "$(cat "$PASS_FILE")" = "$current_hash" ]; then
    stop_signoff
    exit 0
fi

# The sign-off fingerprint is captured HERE, before any gate runs. The gates
# below take minutes; an edit landing in the tree during that window must not
# be covered by the verdict, so the verdict describes the tree the gates
# actually saw. If the fingerprint cannot be computed, no sign-off is written
# and the next stop verifies — never a fingerprint taken after the run.
pre_gate_fingerprint=$(prism_turn_fingerprint) || pre_gate_fingerprint=""

# --- gate 0, ahead of everything ------------------------------------------
# Repository-wide rather than per-module: staticAnalysis costs seconds where
# the compile gate costs minutes, so scoping it would save nothing and would
# miss a violation in a module this change did not touch. A failure here
# short-circuits — gates 1 and 2 never run.
#
# ktlintToolingClasspath rides along to keep the per-edit hook's CLI
# classpath warm: resolving it cold inside ktlint.sh's own 120s PostToolUse
# budget is the one path where that hook can be killed without reporting.
# The task only writes a file Gradle has already resolved, so it cannot turn
# a clean staticAnalysis run red on its own.
if ! output=$(prism_locked_gradlew staticAnalysis ktlintToolingClasspath 2>&1); then
    printf 'VERIFICATION GATE 0 FAILED — static analysis reported violations.\n\n' >&2
    printf '%s\n' "$output" | tail -n 120 >&2
    printf '\nFix these before finishing. Re-run with:\n  ./gradlew staticAnalysis\n' >&2
    printf 'Formatting-only violations are fixed by:\n  ./gradlew ktlintFormat\n' >&2
    stop_block
fi

# --- gates 1 and 2, one invocation ----------------------------------------
# One gradlew launch for the whole scope, not one per task per module: a
# global build change widens scope to every module, and ~30 serial launches
# paid Gradle's configuration cost ~30 times on the hook that ends every
# coding turn. Gradle still stops at the first failure, and its own output
# names the failing task, so the gate-1/gate-2 attribution survives.
gate_tasks=""
for module in $modules; do
    gate_tasks="$gate_tasks $(prism_build_tasks_for "$module" | tr '\n' ' ')"
done

# shellcheck disable=SC2086
if [ -n "${gate_tasks# }" ] && \
        ! output=$(prism_locked_gradlew $gate_tasks 2>&1); then
    failed_task=$(printf '%s\n' "$output" \
        | sed -n "s/.*Execution failed for task '\(:[^']*\)'.*/\1/p" | head -n 1)
    # CLASSIFY ON THE TASK'S OWN NAME, NOT ON WHAT WE ASKED FOR. This gate
    # requests four task names (assemble, assembleDebug, test,
    # testDebugUnitTest) but Gradle reports the task that ACTUALLY failed,
    # which is almost always a dependency of the one requested. The catch-all
    # used to read "unit tests failed", so an ordinary Kotlin syntax error
    # arrived as `:core:data:compileDebugKotlin` and was announced as a test
    # failure -- in a module that may have no src/test at all, where no test
    # task was ever requested. Same for kspDebugKotlin, kaptDebugKotlin,
    # mergeDebugResources, dexBuilderDebug, minifyReleaseWithR8 and
    # lintVitalRelease. The body printed the real stack all along; the
    # headline is what a reader acts on first, and it sent them to the tests.
    failed_name=${failed_task##*:}
    case "$failed_task" in
        '')
            printf 'VERIFICATION GATES 1/2 FAILED — a module in scope does not compile,\nor its unit tests fail.\n\n' >&2
            ;;
        *)
            case "$failed_name" in
                test|test*)
                    printf 'VERIFICATION GATE 2 FAILED — unit tests failed in %s.\n\n' \
                        "${failed_task%:*}" >&2
                    ;;
                assemble*|compile*|ksp*|kapt*)
                    printf 'VERIFICATION GATE 1 FAILED — %s does not compile.\n\n' \
                        "${failed_task%:*}" >&2
                    ;;
                *)
                    # Packaging, shrinking, resource merging, lint. Not a
                    # compile and not a test, so it claims to be neither.
                    printf 'VERIFICATION GATE 1 FAILED — %s failed to build at %s.\n\n' \
                        "${failed_task%:*}" "$failed_name" >&2
                    ;;
            esac
            ;;
    esac
    printf '%s\n' "$output" | tail -n 120 >&2
    printf '\nFix this before finishing. Re-run with:\n  ./gradlew %s\n' \
        "${failed_task:-$(printf '%s' "$gate_tasks" | sed 's/^ *//')}" >&2
    stop_block
fi

mkdir -p "$STATE_DIR"
printf '%s' "$current_hash" > "$PASS_FILE"
printf '0' > "$BLOCK_COUNT_FILE"
# A forced-scope pass verified only what the seam named; a repo-wide sign-off
# from it would launder every OTHER module's unverified work past the next
# natural stop's short-circuit, so the seam never signs off.
if [ -z "${PRISM_STOP_FORCE_MODULES:-}" ] && [ -n "$pre_gate_fingerprint" ]; then
    stop_signoff "$pre_gate_fingerprint"
fi
exit 0

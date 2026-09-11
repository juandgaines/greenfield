#!/bin/sh
# SubagentStop hook — Principle VII gates at the SUBAGENT boundary.
#
#   gate 0 (module-scoped): detekt passes in every module the subagent
#                           touched that applies it
#   gate 1: every module the subagent touched compiles
#   gate 2: those modules' JVM unit tests pass
#
# Why this exists: .claude/settings.json wired Stop and PreToolUse(Bash) only,
# so a subagent was gated by nothing at all. Ten of them wrote several
# thousand lines here with zero gate coverage, and a :core:data that did not
# compile sat in the tree until the MAIN agent's Stop hook caught it — one
# whole turn later, after nine other subagents had built on top of it.
#
# Deliberately NARROWER than stop-gate.sh, and the differences are the point:
#
#   * scope is the modules with uncommitted work in the subagent's own tree,
#     never the whole project. Ten concurrent `./gradlew build` runs would
#     cost more than the hole this closes.
#   * it does NOT widen to dependents, and it does NOT widen to every module
#     when a global build file changes. Both of those are the Stop gate's
#     job, which still runs afterwards over the whole turn's work. This gate
#     is a fast fail at the boundary, not a second full verification.
#   * it skips subagents that cannot edit files at all. See
#     prism_agent_is_read_only below.
#
# Exit 0 = let the subagent stop. Exit 2 = block, reason on stderr.
set -u

HOOK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

input=$(cat)

# The subagent may be working in a git worktree of its own, so the tree to
# verify is the one the EVENT names, not whatever directory this hook was
# spawned in. Read before sourcing affected.sh, which resolves PRISM_ROOT.
#
# Tab-separated so an empty field stays a field. A failure here leaves the
# defaults in place rather than denying: unlike the push gate, this hook is
# not the last line of defence — stop-gate.sh re-verifies the same work with
# a wider scope — so an unreadable event is reported and verified anyway.
event=$(printf '%s' "$input" | python3 -c '
import sys, json
try:
    data = json.load(sys.stdin)
except Exception:
    print("\t\t")
else:
    print("\t".join([
        str(data.get("cwd") or ""),
        str(data.get("agent_id") or ""),
        str(data.get("agent_type") or ""),
    ]))
' 2>/dev/null) || event=""

if [ -z "$event" ]; then
    printf 'prism-verify: could not read the SubagentStop event; verifying the default tree.\n' >&2
    event="$(printf '\t\t')"
fi

event_cwd=$(printf '%s' "$event" | cut -f1)
agent_id=$(printf '%s' "$event" | cut -f2)
agent_type=$(printf '%s' "$event" | cut -f3)
[ -n "$agent_id" ] || agent_id="unknown"
[ -n "$agent_type" ] || agent_type="subagent"

# --- can this subagent fix a build failure at all? ------------------------
# Blocking a READ-ONLY subagent is nonsense. It has no Edit or Write tool, so
# the failing module is not something it can act on — and the tree it would
# be denied over is not one it wrote. Subagents share the main checkout
# unless they were dispatched into a worktree, so what this gate sees when a
# search or review agent stops is the MAIN agent's uncommitted work. Every
# such agent was paying for a Gradle build and then being refused its own
# stop over code it could not open a file in.
#
# The answer is read from the agent's OWN definition rather than from a list
# that goes stale: .claude/agents/<type>.md declares its tools, and an agent
# whose tools include neither Edit nor Write cannot change the tree. An agent
# with no definition file is VERIFIED — unknown means gate, not skip. The
# only names hardcoded are Claude Code's built-in read-only agents, which
# ship no definition file here to read.
#
# This narrows the gate, so it is worth being exact about what still covers
# the skipped case: everything. The Stop gate rebuilds the same modules, with
# dependents included, before the turn can end. Nothing reaches a commit or a
# push on the strength of this skip.
prism_agent_is_read_only() {
    # The type comes off an event, so it never becomes a path segment
    # unchecked.
    case "$1" in
        ''|*[!A-Za-z0-9._:-]*) return 1 ;;
    esac
    # Two dirnames to the repository root, then an EXPLICIT .claude/agents.
    # It used to be two dirnames and a bare `agents`, which worked only because
    # the engine itself lived at <root>/.claude/hooks/prism-verify, so `..`/`..`
    # landed inside the harness directory. The engine is harness-neutral now:
    # the same arithmetic pointed at <root>/agents, every definition silently
    # stopped being found, and every read-only subagent went back to paying for
    # a Gradle build — fail-closed, so nothing unsafe, but the exact regression
    # this function's comment above records as fixed.
    #
    # Resolved from HOOK_DIR rather than PRISM_ROOT deliberately: this runs
    # BEFORE PRISM_ROOT is derived from the event's cwd below, and it must keep
    # working for a subagent that stopped in a worktree of the same repository.
    #
    # The PATH is corrected here. The Claude Code SPECIFICITY is not, and is
    # not a path problem: the location and the `tools:` frontmatter format are
    # both this harness's. Antigravity has no subagent boundary at all, and
    # Cursor and Codex describe agents differently. Translating that is the
    # shim's job in Phase 4; this line only stops the gate being wrong for the
    # harness it does support.
    prism_agent_definition="$(dirname "$(dirname "$HOOK_DIR")")/.claude/agents/$1.md"
    if [ -f "$prism_agent_definition" ]; then
        prism_agent_tools=$(sed -n '1,20s/^tools:[[:space:]]*//p' \
            "$prism_agent_definition")
        # No `tools:` line means the agent inherits every tool, Edit included.
        [ -n "$prism_agent_tools" ] || return 1
        case "$prism_agent_tools" in
            *Edit*|*Write*|\*) return 1 ;;
        esac
        return 0
    fi
    case "$1" in
        Explore|Plan) return 0 ;;
    esac
    return 1
}

if prism_agent_is_read_only "$agent_type"; then
    exit 0
fi

if [ -z "${PRISM_ROOT:-}" ] && [ -n "$event_cwd" ] && [ -d "$event_cwd" ]; then
    PRISM_ROOT=$(git -C "$event_cwd" rev-parse --show-toplevel 2>/dev/null)
    export PRISM_ROOT
fi

# shellcheck disable=SC1091
. "$HOOK_DIR/lib/affected.sh"

STATE_DIR="${PRISM_STATE_DIR:-$HOOK_DIR/state}"
PASS_DIR="$STATE_DIR/subagent-pass"
BLOCK_COUNT_FILE="$STATE_DIR/subagent-block-$agent_id"

# One block, then a report. A subagent frequently CANNOT fix what it broke —
# the fix may be outside its brief, or in another subagent's module — and a
# gate that blocks it forever turns a compile error into a hung wave. So the
# budget is deliberately smaller than the Stop gate's three: block once with
# the failing output, and if it comes back still broken, let it stop and say
# so loudly instead.
#
# Letting it stop is NOT letting it through. The main agent's Stop gate
# rebuilds the same modules with dependents included before the turn can end,
# so broken work still cannot survive the turn — it just stops costing the
# wave its remaining subagents. The report exists so nobody has to wait for
# that to find out.
PRISM_MAX_SUBAGENT_BLOCKS="${PRISM_MAX_SUBAGENT_BLOCKS:-1}"

prism_subagent_block_count() {
    count=0
    if [ -f "$BLOCK_COUNT_FILE" ]; then
        count=$(cat "$BLOCK_COUNT_FILE" 2>/dev/null || printf '0')
        case "$count" in ''|*[!0-9]*) count=0 ;; esac
    fi
    printf '%s' "$count"
}

# Exit 0 routes stderr to the debug log only, so a report printed there would
# reach nobody. lib/affected.sh's prism_report puts the same text on
# systemMessage, which is the one channel that surfaces from an allow path.
prism_subagent_report() {
    prism_report "$1"
    exit 0
}

prism_require_root
root_status=$?
if [ "$root_status" -ne 0 ]; then
    message="prism-verify: the subagent [$agent_type] stopped in a tree this gate cannot verify
(PRISM_ROOT=[$PRISM_ROOT], event cwd=[$event_cwd]). Its work was NOT checked
at the subagent boundary. The Stop gate still covers it before the turn ends."
    prism_subagent_report "$message"
fi

# --- what did THIS subagent touch? ----------------------------------------
# From the working tree, never from the subagent's own account of its scope.
# A subagent that edited outside its brief is precisely the case worth
# catching, and it is also the one least likely to be reported accurately.
#
# Uncommitted work only — not the branch diff. In a worktree that is exactly
# what the subagent did; in a shared tree it is a superset of it, which errs
# toward verifying more rather than less.
# There is deliberately no force-modules seam here. The other two gates have
# one for their test suites, and every one of them is a place where scope can
# be narrowed from the environment. This gate's tests drive it through a
# fixture repository's real working tree instead, so the shipped code has no
# such variable to leak.
if ! changed=$(prism_changed_files_worktree); then
    message="prism-verify: could not read the working tree of [$PRISM_ROOT], so the
subagent [$agent_type]'s work was NOT checked at the subagent boundary.
The Stop gate still covers it before the turn ends."
    prism_subagent_report "$message"
fi
kotlin_files=$(printf '%s\n' "$changed" | grep -E '\.(kt|kts)$' || true)
if [ -z "$kotlin_files" ]; then
    exit 0
fi
modules=$(printf '%s\n' "$kotlin_files" | prism_modules_for_files)

if [ -z "$modules" ]; then
    exit 0
fi

# assemble+test plus, where the module applies it, its own detekt task. The
# task pair used to be `build`, whose `check` lifecycle ran detekt per
# module; swapping in `assemble` silently dropped it, and unlike the other
# two gates this one runs no repo-wide staticAnalysis — deliberately, since
# a violation in an untouched module (another subagent's, say) must not
# block this one. Module-scoped detekt restores the coverage without that
# blast radius. ktlint needs no equivalent: it never rode `check` here, and
# the per-edit hook covers it.
subagent_tasks_for() {
    prism_build_tasks_for "$1"
    if prism_module_has_detekt "$1"; then
        printf '%s:detekt\n' "$1"
    fi
}

# --- dry run: report the plan, then DENY ----------------------------------
# Same rule as the other two gates. It runs no gate, so it has no verdict,
# and exiting 0 would make a leaked PRISM_DRY_RUN=1 a silent bypass.
if [ "${PRISM_DRY_RUN:-}" = "1" ]; then
    for module in $modules; do
        for task in $(subagent_tasks_for "$module"); do
            printf 'would run: %s\n' "$task" >&2
        done
    done
    printf '\nSUBAGENT BLOCKED — DRY RUN\n\n' >&2
    printf 'PRISM_DRY_RUN=1 is set, so gates 1 and 2 did not run: nothing was verified.\n' >&2
    printf 'This is a test-suite seam, not a bypass: unset it to verify.\n' >&2
    exit 2
fi

# --- this exact source already passed -------------------------------------
# Keyed by content hash rather than by a single "last pass" slot, because the
# situation this gate exists for is SEVERAL subagents stopping at once: one
# shared slot would thrash between their differing scopes and every one of
# them would rebuild. A hash-named marker lets identical scopes share a pass.
# shellcheck disable=SC2086
current_hash=$(prism_source_hash $modules)
if [ -f "$PASS_DIR/$current_hash" ]; then
    exit 0
fi

# --- gates 1 and 2, fail-fast ---------------------------------------------
for module in $modules; do
    for task in $(subagent_tasks_for "$module"); do
        if ! output=$(prism_locked_gradlew "$task" 2>&1); then
            if [ "$(prism_subagent_block_count)" -ge "$PRISM_MAX_SUBAGENT_BLOCKS" ]; then
                message="prism-verify: the subagent [$agent_type] left $module broken and did not
fix it when blocked. Letting it stop so the wave is not held up — the work is
STILL BROKEN and the failing task is:

  ./gradlew $task

The Stop gate will refuse to end the turn until it passes."
                prism_subagent_report "$message"
            fi
            mkdir -p "$STATE_DIR"
            printf '%s' "$(( $(prism_subagent_block_count) + 1 ))" > "$BLOCK_COUNT_FILE"
            case "$task" in
                *assembleDebug|*:assemble)
                    printf 'SUBAGENT VERIFICATION GATE 1 FAILED — %s does not compile.\n\n' \
                        "$module" >&2
                    ;;
                *:detekt)
                    printf 'SUBAGENT VERIFICATION GATE 0 FAILED — detekt reported violations in %s.\n\n' \
                        "$module" >&2
                    ;;
                *)
                    printf 'SUBAGENT VERIFICATION GATE 2 FAILED — unit tests failed in %s.\n\n' \
                        "$module" >&2
                    ;;
            esac
            printf '%s\n' "$output" | tail -n 120 >&2
            printf '\nFix this before finishing. Re-run with:\n  ./gradlew %s\n' "$task" >&2
            exit 2
        fi
    done
done

mkdir -p "$PASS_DIR"
: > "$PASS_DIR/$current_hash"
rm -f "$BLOCK_COUNT_FILE"
# The markers are tiny and content-keyed, so they accumulate one per distinct
# tree state — and the block counters one per agent id. Age both out rather
# than letting the directory grow forever.
find "$PASS_DIR" -type f -mtime +7 -delete 2>/dev/null
find "$STATE_DIR" -maxdepth 1 -name 'subagent-block-*' -mtime +7 -delete \
    2>/dev/null
exit 0

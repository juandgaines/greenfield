#!/bin/sh
# The verification half of the push gate: Principle VII gates 0, 2 and 3, run
# against a repository state. Exit 0 = allow, exit 2 = deny with the reason on
# stderr.
#
#   gate 0: static analysis passes across the repository
#   gate 2: everything the change reaches compiles and its unit tests pass
#   gate 3: instrumentation + unit tests pass, and coverage clears thresholds
#
# Usage: sh push-verify.sh [<source-ref>]
#
# It takes a ref, not an event. That is the whole point of the split.
#
# All of this used to sit below push-gate.sh's event parser, reachable only by
# feeding a harness's JSON in on stdin — so the framework's central promise, that
# unverified work does not reach a remote, was available exactly where a harness
# chose to offer a PreToolUse hook and nowhere else. Per the capability matrix
# that is not a portability nicety: Copilot CLI documents hook timeouts as
# "always fail-open, even for preToolUse", at a 30 second default, and
# Antigravity defaults to 30 with no documented maximum. This gate runs static
# analysis, a build and a coverage pass. For those two harnesses a hook is not a
# weaker enforcement path, it is one that can be timed out of existence — and
# .git/hooks/pre-push is the primary enforcement rather than a fallback.
#
# The seam was already marked in the source it was cut from. Everything above it
# answers "is this command a push, and which ref is it sending"; git knows both
# without being asked, so a git hook needs none of it and constructs no synthetic
# event to pretend otherwise.
set -u

HOOK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$HOOK_DIR/lib/affected.sh"

# The ref being sent, when the caller knows it. `git push origin master` from a
# feature branch names the source ref in its refspec; git's own pre-push hook
# reads the same thing off stdin. Empty means HEAD.
push_ref="${1:-}"

# --- only NOW does the checkout matter ------------------------------------
# This ran FIRST for as long as the hook existed, before the event had even
# been read, so a command the gate had no opinion about — `echo hello` from a
# git worktree — exited 2 along with everything else. A command that is not a
# push never needed a root opinion: the gate only ever wanted to gate pushes.
# Classification stays fail-closed in the caller (an interpreter that cannot be
# asked the question denies), so moving this down widens where the gate works
# without adding anywhere it can be turned off.
prism_require_root
root_status=$?
if [ "$root_status" -eq 1 ]; then
    printf 'PUSH DENIED — no usable repository root.\n\n' >&2
    printf 'PRISM_ROOT resolved to [%s], which has no settings.gradle.kts. This\n' \
        "$PRISM_ROOT" >&2
    printf 'hook cannot derive any module, so nothing would be verified. It denies\n' >&2
    printf 'rather than allowing an unverified push in silence.\n' >&2
    exit 2
fi
if [ "$root_status" -eq 2 ]; then
    printf 'PUSH DENIED — the hook and the tree it would verify disagree.\n\n' >&2
    printf 'The hook belongs to a different REPOSITORY than the tree being pushed:\n' >&2
    printf '  hook implies : %s\n' "$(dirname "$(dirname "$HOOK_DIR")")" >&2
    printf '  tree resolved: %s\n\n' "$PRISM_ROOT" >&2
    printf 'A worktree of the SAME repository is fine — same hook code, same\n' >&2
    printf 'project — and is allowed. This is not that: verifying an unrelated\n' >&2
    printf 'checkout is worse than not verifying, because it reports success.\n' >&2
    printf 'Push from the right checkout, or set PRISM_ROOT to the tree you are\n' >&2
    printf 'actually pushing.\n' >&2
    exit 2
fi

# `git push origin master` from a feature branch used to verify the FEATURE
# branch's commits. When the refspec names a source ref, that is what is
# being sent, so that is what gets verified.
if [ -n "$push_ref" ] && [ "$push_ref" != "HEAD" ] && \
        git -C "$PRISM_ROOT" rev-parse --verify --quiet "$push_ref" >/dev/null 2>&1; then
    PRISM_PUSH_HEAD="$push_ref"
else
    PRISM_PUSH_HEAD="HEAD"
fi

# Resolved here, in this shell, rather than left to prism_push_range: every
# caller of that runs it in a command substitution, so a value it resolves is
# thrown away with the subshell and the denial below would report an empty ref
# whatever the actual cause was. It also splits the two failures apart, which
# need different fixes -- nothing configured, versus a configured ref this
# repository does not have.
if ! prism_require_base_branch; then
    printf 'PUSH DENIED — no base branch is configured.\n\n' >&2
    printf 'The reason is above. The gate computes what a push changes against\n' >&2
    printf 'the base branch, and it will not guess which branch that is: guessing\n' >&2
    printf 'wrong verifies a scope that is not the change, and says nothing.\n' >&2
    exit 2
fi

if ! range=$(prism_push_range); then
    printf 'PUSH DENIED — could not resolve the base branch for verification.\n\n' >&2
    printf 'Tried PRISM_BASE_BRANCH=%s and origin/%s; neither ref exists in this repo\n' \
        "$PRISM_BASE_BRANCH" "$PRISM_BASE_BRANCH" >&2
    printf '(and the branch has no upstream to fall back to).\n\n' >&2
    printf 'Set PRISM_BASE_BRANCH to a ref that exists, or fetch it locally, then push again.\n' >&2
    exit 2
fi

# Test-only override: lets the test suite exercise the prism_changed_files_range
# failure path below deterministically, without needing to construct a real
# stale-ref repo. Read after PRISM_BASE_BRANCH resolution succeeds, exactly
# like PRISM_PUSH_FORCE_MODULES below is read after the range is computed.
if [ -n "${PRISM_PUSH_RANGE_OVERRIDE:-}" ]; then
    range="$PRISM_PUSH_RANGE_OVERRIDE"
fi

if ! changed_files=$(prism_changed_files_range "$range"); then
    printf 'PUSH DENIED — could not compute the changed-file diff for range %s.\n\n' "$range" >&2
    printf 'This usually means the range is no longer valid — a stale @{upstream}\n' >&2
    printf 'pointing at a deleted ref, or a base commit that is gone. Re-fetch, or\n' >&2
    printf 'fix PRISM_BASE_BRANCH / the branch'"'"'s upstream, then push again.\n' >&2
    exit 2
fi
# A convention plugin, the version catalog, or a root build file belongs to
# no module but can change how every module builds, so it used to derive an
# empty list and allow the push having verified nothing.
if printf '%s\n' "$changed_files" | prism_has_global_build_change; then
    modules=$(prism_all_modules)
else
    modules=$(printf '%s\n' "$changed_files" | prism_modules_for_files)
fi

# The override exists for the test suite and must be read before the
# empty-range short-circuit, or forcing modules on a clean range is a no-op.
if [ -n "${PRISM_PUSH_FORCE_MODULES:-}" ]; then
    printf 'prism-verify: module list OVERRIDDEN by PRISM_PUSH_FORCE_MODULES=%s\n' \
        "$PRISM_PUSH_FORCE_MODULES" >&2
    modules="$PRISM_PUSH_FORCE_MODULES"
fi

# PRISM_DRY_RUN is the seam that makes range resolution and module
# derivation testable without paying for a Gradle build. It stops BEFORE any
# gate runs and says so; it never lets a gate reach a verdict and then
# discard it.
#
# It is NOT a warning mechanism, and nothing here may be written as if it
# were. A hook that exits 0 has its stderr routed to the debug log ONLY — it
# never reaches the transcript, so neither Claude nor the user ever sees a
# message printed on an allow path. That is precisely why the old
# gate-3 skip switch was deleted rather than made louder: an
# environment variable that turned the coverage gate off announced itself
# into a log nobody reads.
if [ "${PRISM_DRY_RUN:-}" = "1" ]; then
    printf 'push range: %s\n' "$range" >&2
    printf 'affected modules: %s\n' "$(printf '%s' "$modules" | tr '\n' ' ')" >&2
    printf 'verifying ref: %s\n' "$PRISM_PUSH_HEAD" >&2
    # Deliberately does not exit — gate 3 adds its own dry-run report below.
fi

# --- gate C: the configuration was not weakened to get here ---------------
# Runs BEFORE gate 0, and the order is the whole point. Every gate below is
# configured by files in the repository being pushed, so a run that checked the
# code first would be reading rules the same push had just relaxed.
#
# It compares detekt.yml, .editorconfig, thresholds.json, .prism/scope.json and
# every baseline against the BASE BRANCH, and denies when they got weaker.
# Promotion is never checked.
#
# This closes a hole that predates per-module posture and is wider than it: an
# agent blocked by a rule could set `active: false` in detekt.yml and nothing
# anywhere noticed. It is tamper-EVIDENT, not tamper-proof -- this script is
# itself a file the agent can write. What it buys is that weakening now costs a
# separate commit somebody sees.
#
# IT MAY NOT FAIL OPEN. guard.py is on the packaging manifest, so its absence
# is never innocent -- it is a partial install, or the one file an agent would
# delete to get past this gate. Skipping the check because the checker is gone
# is the failure this gate exists to prevent, and on an allow path nobody would
# ever see it: a hook that exits 0 has its stderr routed to the debug log only.
if [ ! -f "$PRISM_LIB_DIR/guard.py" ]; then
    printf 'PUSH DENIED — CONFIGURATION GUARD\n\n' >&2
    printf 'The guard itself is missing:\n\n  %s\n\n' "$PRISM_LIB_DIR/guard.py" >&2
    printf 'It ships with PRISM and nothing removes it in normal use, so this is\n' >&2
    printf 'either a partial install or a deleted file. Restore it -- reinstall,\n' >&2
    printf 'or `git checkout -- .prism/verify/lib/guard.py` -- then push again.\n' >&2
    exit 2
fi
if ! python3 "$PRISM_LIB_DIR/guard.py" --root "$PRISM_ROOT" --base "$PRISM_BASE_BRANCH"; then
    printf '\nPUSH DENIED — CONFIGURATION GUARD\n' >&2
    exit 2
fi

# A push that changes ONLY configuration belongs to no module, so the module
# list is empty and there is nothing to compile, test or measure. That is the
# correct reason to stop -- but it must come AFTER gate C, never before. A
# commit touching only .prism/scope.json used to derive an empty list and exit
# 0 here, having run no gate at all, and the guard's own denial text tells the
# reader to land a weakening as its own commit first: exactly the shape that
# slipped through.
#
# Hoisting gate C rather than sinking this is deliberate. Gate C needs nothing
# this block computes -- PRISM_ROOT, PRISM_LIB_DIR and PRISM_BASE_BRANCH are
# all resolved at the top -- while sinking the short-circuit would drag the
# empty-module case past the dry-run denial below and turn this exit 0 into a
# spurious DRY RUN denial with an empty coverage plan.
if [ -z "$modules" ]; then
    exit 0
fi

THRESHOLDS="$HOOK_DIR/thresholds.json"

# --- gate 3, step 1: configuration pre-check (no gradle) ------------------
# Cheapest possible failure, so it runs before anything expensive.
unconfigured=""
untested=""
kinds=""
for module in $modules; do
    # POSTURE IS READ HERE, not only in the verdict further down. The verdict
    # short-circuits on `observe` and says why in its own comment: "treating a
    # missing report as a denial there would make `observe` block on exactly
    # the modules it exists to unblock". This pre-check ran first and denied
    # first, so it contradicted that -- an observed module with production
    # Kotlin and no test source set was refused before posture was ever
    # consulted, which is the state every adopting repository starts in.
    #
    # Fails closed: an unreadable scope file yields no posture and the module
    # is treated as enforcing, like every other reader of that file.
    posture=$(python3 "$HOOK_DIR/lib/scope.py" get --module "$module" \
        --engine coverage --root "$PRISM_ROOT" 2>/dev/null) || posture="enforce"
    if [ "$posture" = "observe" ]; then
        continue
    fi

    kind=$(python3 "$HOOK_DIR/lib/coverage.py" check \
        --module "$module" --root "$PRISM_ROOT")
    case "$kind" in
        no-jacoco) unconfigured="$unconfigured $module" ;;
        none)
            has_sources=$(python3 "$HOOK_DIR/lib/coverage.py" has-sources \
                --module "$module" --root "$PRISM_ROOT")
            if [ "$has_sources" = "yes" ]; then
                untested="$untested $module"
            fi
            ;;
        *) kinds="$kinds $module=$kind" ;;
    esac
done

if [ -n "$unconfigured" ]; then
    printf 'PUSH DENIED — VERIFICATION GATE 3\n\n' >&2
    printf 'These modules are in the push but are not configured for coverage:\n' >&2
    for module in $unconfigured; do
        printf '  %s\n' "$module" >&2
    done
    printf '\nRun the android-jacoco-setup skill for each of them, then push again.\n' >&2
    exit 2
fi

if [ -n "$untested" ]; then
    printf 'PUSH DENIED — VERIFICATION GATE 3\n\n' >&2
    printf 'These modules have production code but no tests at all (no src/test\n' >&2
    printf 'and no src/androidTest), so no coverage could be measured:\n' >&2
    for module in $untested; do
        printf '  %s\n' "$module" >&2
    done
    printf '\nAdd unit or instrumentation tests for them, then push again.\n' >&2
    exit 2
fi

if [ "${PRISM_DRY_RUN:-}" = "1" ]; then
    printf 'PUSH DENIED — DRY RUN\n\n' >&2
    printf 'coverage plan:%s\n' "$kinds" >&2
    printf 'PRISM_DRY_RUN=1 is set, so gates 0, 2 and 3 did not run: nothing was verified.\n' >&2
    printf 'This is a test-suite seam, not a bypass: unset it to verify.\n' >&2
    exit 2
fi

# --- gate 0: static analysis over the whole repository --------------------
# The Stop gate's sign-off short-circuit means a violation a turn gave up on
# is deliberately not re-gated by later turns — and lint violations do not
# fail compilation, so without this run the build and coverage gates below
# wave that violation straight onto the remote. This is the backstop the
# Stop gate's give-up path relies on. Repo-wide for the same reason the Stop
# gate's is: it costs seconds where the builds below cost minutes.
#
# ktlintToolingClasspath rides along to keep the per-edit hook's CLI warm,
# same as on the Stop gate: a cold resolution inside ktlint.sh's own 120s
# PostToolUse budget is the one path where that hook dies unreported.
if ! output=$(prism_locked_gradlew staticAnalysis ktlintToolingClasspath 2>&1); then
    printf 'PUSH DENIED — VERIFICATION GATE 0\n\n' >&2
    printf 'Static analysis reported violations somewhere in the repository.\n\n' >&2
    printf '%s\n' "$output" | tail -n 120 >&2
    printf '\nFix these, then push again. Re-run with:\n  ./gradlew staticAnalysis\n' >&2
    printf 'Formatting-only violations are fixed by:\n  ./gradlew ktlintFormat\n' >&2
    exit 2
fi

# --- gate 2: everything the change REACHES compiles and unit-tests --------
# Deviceless, and almost entirely up-to-date from the Stop gate, so it sits
# ahead of the emulator and the coverage runs per Principle VII's
# cheap-to-expensive ordering.
#
# The coverage scope above is the modules that CHANGED, which is right for a
# coverage floor — a module's own number is a property of its own code. But
# gate 2 is about reach: Gradle builds a module's dependencies, never its
# dependents, so a signature change in :core:domain left every consumer
# broken while :core:domain's own tasks passed.
# shellcheck disable=SC2086
if ! build_scope=$(prism_with_dependents $modules); then
    printf 'PUSH DENIED — could not read the module dependency graph.\n\n' >&2
    printf 'lib/deps.py could not derive which modules depend on the pushed ones,\n' >&2
    printf 'so this hook cannot tell which modules the change reaches. Fix python3\n' >&2
    printf 'or lib/deps.py, then push again.\n' >&2
    exit 2
fi

build_tasks=""
for module in $build_scope; do
    build_tasks="$build_tasks $(prism_build_tasks_for "$module" | tr '\n' ' ')"
done

if [ -n "$build_tasks" ]; then
    # shellcheck disable=SC2086
    if ! output=$(prism_locked_gradlew $build_tasks 2>&1); then
        printf 'PUSH DENIED — VERIFICATION GATE 2\n\n' >&2
        printf 'A module the change reaches does not compile, or its unit tests fail.\n' >&2
        printf 'Scope was:%s\n\n' "$(printf '%s' "$build_scope" | tr '\n' ' ')" >&2
        printf '%s\n' "$output" | tail -n 120 >&2
        exit 2
    fi
fi

# --- gate 3: CHECK a recorded coverage result, never PRODUCE one ----------
# This hook used to boot an emulator, run connectedDebugAndroidTest across
# every module, and merge coverage — inline, inside the hook. That is an hour
# of work behind a hook timeout, and a timed-out hook does NOT block:
#
#   "A timed-out command, http, or mcp_tool hook doesn't block the tool call.
#    The call continues through the normal permission flow, so don't count on
#    a stalled hook to act as a gate."   — code.claude.com/docs/en/hooks
#
# So the gate was weakest exactly when it mattered most: the bigger the
# change, the longer the run, the likelier it silently allowed the push.
#
# Deciding and performing have opposite requirements. Deciding must be
# instant and reliable; performing must be long, visible and resumable. So
# the hook now only DECIDES, by reading artifacts Gradle already produced —
# milliseconds, and it still computes the number itself rather than trusting
# anyone's claim. When those artifacts are missing or stale it denies with
# the exact commands, and Claude runs them as ordinary Bash calls where no
# hook timeout applies and the output is visible. Nothing became manual: the
# push stays blocked until the work is done.
stale=""
low=""
for entry in $kinds; do
    module=$(printf '%s' "$entry" | cut -d= -f1)
    kind=$(printf '%s' "$entry" | cut -d= -f2)
    dir=$(prism_module_dir "$module")

    xml=$(prism_coverage_xml_for "$module" "$kind")

    # --record: on a report the mtime check itself calls fresh, remember the
    # content it was produced from, so a later `git checkout`, `stash pop` or
    # no-op save that moves an mtime without changing a byte does not read as
    # staleness. status.sh deliberately omits it — reporting must not write.
    verdict=$(python3 "$HOOK_DIR/lib/coverage.py" verdict --module "$module" \
        --root "$PRISM_ROOT" --xml "$xml" --kind "$kind" --config "$THRESHOLDS" \
        --record)
    if [ "$?" -eq 0 ]; then
        continue
    fi

    # Only the first word: "unreadable <error>" carries spaces, and these
    # entries are carried through a space-separated list.
    why=$(printf '%s' "$verdict" | awk '{print $1}')
    case "$verdict" in
        low\ *) low="$low $module" ;;
        *)      stale="$stale $module=$kind=$why" ;;
    esac
done

# Stale first: "we do not know yet" is a different answer from "we know and
# it is too low", and it is the cheaper one to act on.
if [ -n "$stale" ]; then
    printf 'PUSH DENIED — VERIFICATION GATE 3 (no current coverage result)\n\n' >&2
    printf 'These modules have no coverage report matching the code on disk, so\n' >&2
    printf 'this hook cannot tell whether they pass. Run the commands below, then\n' >&2
    printf 'push again — the check itself takes milliseconds.\n\n' >&2
    needs_device="no"
    for entry in $stale; do
        module=$(printf '%s' "$entry" | cut -d= -f1)
        kind=$(printf '%s' "$entry" | cut -d= -f2)
        why=$(printf '%s' "$entry" | cut -d= -f3)
        printf '  %s (%s)\n' "$module" "$why" >&2
        # "It never ran" and "it ran and lost its data" need different words,
        # because they need different actions. The second one used to be
        # reported as the first, which told the reader to go and do the exact
        # thing they had just done.
        if [ "$why" = "device-run-no-data" ]; then
            printf '      The instrumentation suite RAN for this code but brought back no\n' >&2
            printf '      .ec execution data. That is what a killed connected run looks\n' >&2
            printf '      like: two runs for the same test package on one emulator destroy\n' >&2
            printf '      each other, and the loser dies with "Process crashed" having\n' >&2
            printf '      produced results but no coverage. Re-run it THROUGH THE DEVICE\n' >&2
            printf '      LOCK — the commands below do exactly that.\n' >&2
        fi
        # Rendered by lib/affected.sh, which status.sh reads too — a status
        # view that prints different refresh commands than the gate does is
        # worse than none, because it sends you to run something that does
        # not clear the denial.
        prism_refresh_commands "$module" "$kind" | sed 's/^/      /' >&2
        if prism_kind_needs_device "$kind"; then
            needs_device="yes"
        fi
    done
    if [ "$needs_device" = "yes" ]; then
        printf '\nGo through device_lock.py for every connected run. Two of them for the\n' >&2
        printf 'same test package on one emulator kill each other, and the loser loses\n' >&2
        printf 'its coverage data rather than its tests — which is invisible until this\n' >&2
        printf 'gate reports the module as never having run.\n' >&2
        printf '\nThe connectedDebugAndroidTest runs need a booted emulator (minSdk 26):\n' >&2
        printf '  python3 %s --min-sdk 26\n' \
            "$PRISM_ASSETS_DIR/select_emulator.py" >&2
        printf '  android emulator start <avd>\n' >&2
        printf '\nRun connectedDebugAndroidTest BEFORE the coverage task, as its own\n' >&2
        printf 'invocation: prismCombinedCoverage dependsOn testDebugUnitTest only, so\n' >&2
        printf 'asking for both at once lets Gradle write the report first and merge\n' >&2
        printf 'whatever .ec files happen to be on disk — a unit-test-only number\n' >&2
        printf 'wearing a combined report'"'"'s name.\n' >&2
    fi
    exit 2
fi

if [ -n "$low" ]; then
    printf 'PUSH DENIED — VERIFICATION GATE 3 (coverage below threshold)\n\n' >&2
    for module in $low; do
        dir=$(prism_module_dir "$module")
        kind=$(printf '%s' "$kinds" | tr ' ' '\n' | grep "^$module=" | cut -d= -f2)
        xml=$(prism_coverage_xml_for "$module" "$kind")
        verdict=$(python3 "$HOOK_DIR/lib/coverage.py" verdict --module "$module" \
            --root "$PRISM_ROOT" --xml "$xml" --kind "$kind" --config "$THRESHOLDS")
        actual=$(printf '%s' "$verdict" | awk '{print $2}')
        required=$(printf '%s' "$verdict" | awk '{print $3}')
        printf '%s line coverage is %s%%, below the required %s%%.\n\n' \
            "$module" "$actual" "$required" >&2
        python3 "$PRISM_ASSETS_DIR/render_coverage.py" \
            "$xml" --module "$module" >&2
        printf '\n' >&2
    done
    printf 'Add tests for the weakest classes above, then push again.\n' >&2
    exit 2
fi

# --- done -----------------------------------------------------------------
# Figma parity is not verified anywhere in the pipeline: constitution 3.0.0
# removed it from the Definition of Done. android-preview-figma-verify
# remains available as an on-demand skill for deliberate design reviews.
exit 0

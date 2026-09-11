#!/bin/sh
# Shared helpers for the prism-verify hooks.
# Sourced, never executed: defines functions and two variables, runs nothing.

PRISM_ROOT="${PRISM_ROOT:-$(git rev-parse --show-toplevel)}"

# Where lib/*.py live. Every caller defines HOOK_DIR before sourcing this
# file, but the fallback keeps the helpers usable from a bare shell too.
PRISM_LIB_DIR="${PRISM_LIB_DIR:-${HOOK_DIR:+$HOOK_DIR/lib}}"
if [ -z "$PRISM_LIB_DIR" ]; then
    PRISM_LIB_DIR="$PRISM_ROOT/.prism/verify/lib"
fi

# The runtime-configuration loader. Sourced rather than re-implemented: there is
# one place that decides what "cannot read the configuration" means, and it is
# not each gate's own guess.
. "$PRISM_LIB_DIR/config.sh"

# Where the coverage assets live: two Gradle init scripts, the device lock and
# the report renderer.
#
# They used to be carried inside the harness's skill directories, duplicated
# two and three times over, and every remediation command the gates print named
# one of those copies. Per design D9 that is not survivable: two of the five
# supported harnesses cannot be given a directory inside the consumer's
# repository at all, and an install with no harness adapter is a supported
# outcome. A gate that denies a push and then prints the fix as a path to a
# file the consumer does not have has not told them anything.
#
# So the assets belong to the framework, beside the engine, and this default
# needs no value from anyone. PRISM_ASSETS_DIR overrides it -- the tests use
# that, and a consumer who moves them keeps one place to say so.
PRISM_ASSETS_DIR="${PRISM_ASSETS_DIR:-$PRISM_ROOT/.prism/assets}"

# Print a message where a human will actually see it, then return.
#
# A hook that exits 0 has its stderr routed to the debug log ONLY — never the
# transcript, and Claude never sees it. systemMessage on stdout is the one
# channel an allow path has, so both are emitted: the JSON for the user, the
# stderr copy for the debug log a later investigation reads.
#
# Shared because the Stop gate learned this the hard way. Its "giving up
# after N blocked attempts; NOT verified" notice was printed to stderr on an
# exit-0 path, so the single moment the gate concedes that a turn is ending
# unverified was also the one moment nobody was told about.
prism_report() {
    printf '%s' "$1" >&2
    python3 -c '
import json, sys
print(json.dumps({"systemMessage": sys.argv[1]}))
' "$1" 2>/dev/null || true
}

# Both gates MUST call this before deriving anything.
#
#   0 = usable, 1 = no repo root, 2 = the hook and the tree disagree.
#
# PRISM_ROOT is assigned from `git rev-parse`, so a failure leaves it EMPTY
# rather than unset and `set -u` never fires. The module table then grepped
# "/settings.gradle.kts", found nothing, derived no modules, and both gates
# exited 0 having verified nothing — a total silent bypass.
#
# The mismatch that matters is a hook resolving against a DIFFERENT
# repository: it would verify a checkout other than the one being pushed,
# which is worse than not verifying at all because it reports success.
#
# This used to be a string comparison of the two paths, and that is not the
# same question. A git worktree of this repo differs from the main checkout
# on every path while running the same hook code against the same project, so
# the comparison denied it — and because both gates called this before they
# had even read their event, EVERY Bash call from a worktree exited 2,
# `echo hello` included. Agents dispatched into worktrees could not run `pwd`.
#
# So ask the question directly: `--git-common-dir` is the shared .git
# directory, identical for a repo and all of its worktrees and different for
# any unrelated checkout. Verified 2026-08-19 on git 2.44: two worktrees of
# this repo both report /…/the-project/.git while --show-toplevel differs.
#
# Fail closed: if git cannot answer for either side, the answer is "cannot
# prove they are the same repository", which denies.
prism_require_root() {
    if [ -z "$PRISM_ROOT" ] || [ ! -f "$PRISM_ROOT/settings.gradle.kts" ]; then
        return 1
    fi
    if [ -n "${HOOK_DIR:-}" ]; then
    # TWO dirnames, not three. The engine installs to <root>/.prism/verify and
    # lives at <root>/core/verify in the product repo: both are two levels
    # below the repository root. It was three when the engine sat at
    # <root>/.claude/hooks/<the old engine directory>, and this line carried no
    # string, so the rename pass walked straight past it -- the relocation
    # surfaced it only because the fixtures moved in the same commit and the
    # whole suite went red. Anchor any future move here.
        prism_implied_root=$(dirname "$(dirname "$HOOK_DIR")")
        if [ "$prism_implied_root" = "$PRISM_ROOT" ]; then
            return 0
        fi
        prism_tree_repo=$(git -C "$PRISM_ROOT" rev-parse \
            --path-format=absolute --git-common-dir 2>/dev/null)
        prism_hook_repo=$(git -C "$prism_implied_root" rev-parse \
            --path-format=absolute --git-common-dir 2>/dev/null)
        if [ -z "$prism_tree_repo" ] || [ -z "$prism_hook_repo" ] || \
                [ "$prism_tree_repo" != "$prism_hook_repo" ]; then
            return 2
        fi
    fi
    return 0
}

# Serialize gate builds across SESSIONS. Parallel Claude sessions share this
# checkout, and Gradle does not serialize whole builds across daemons — a busy
# daemon just makes the client spawn another one, and the two builds then
# write the same build/ directories. Most collisions hide behind UP-TO-DATE
# checks; :tooling:konsist:test cannot (it re-executes on every run by
# design), so two overlapping gate runs rewrite the same test-results
# directory and one deletes the other's in-progress result mid-write
# (NoSuchFileException on in-progress-results-*.bin — observed 2026-08-24,
# two Stop gates one second apart).
#
# mkdir is the atomic primitive. The holder's PID is recorded so a lock left
# behind by a KILLED hook is broken rather than waited on; a holder that
# cannot be proven dead is waited for, then the caller denies loudly —
# over-blocking is recoverable, interleaved builds corrupting each other's
# outputs is not.
PRISM_BUILD_LOCK_WAIT="${PRISM_BUILD_LOCK_WAIT:-600}"

prism_build_lock_path() {
    printf '%s/.gradle/prism-verify-build.lock' "$PRISM_ROOT"
}

# Only ever removes a lock THIS process took: the exit trap below fires on
# every exit path, including ones where another session has since acquired
# the lock, and deleting theirs would reopen the exact race the lock closes.
prism_build_lock_release() {
    prism_lock=$(prism_build_lock_path)
    if [ "$(cat "$prism_lock/pid" 2>/dev/null)" = "$$" ]; then
        rm -rf "$prism_lock"
    fi
}

prism_build_lock_acquire() {
    prism_lock=$(prism_build_lock_path)
    mkdir -p "$PRISM_ROOT/.gradle" 2>/dev/null
    prism_lock_waited=0
    while ! mkdir "$prism_lock" 2>/dev/null; do
        prism_lock_pid=$(cat "$prism_lock/pid" 2>/dev/null)
        if [ -n "$prism_lock_pid" ] && \
                ! kill -0 "$prism_lock_pid" 2>/dev/null; then
            rm -rf "$prism_lock"
            continue
        fi
        if [ "$prism_lock_waited" -ge "$PRISM_BUILD_LOCK_WAIT" ]; then
            return 1
        fi
        sleep 2
        prism_lock_waited=$((prism_lock_waited + 2))
    done
    printf '%s' "$$" > "$prism_lock/pid"
    # The gates exit from many places (stop_block, prism_report, a plain
    # exit 2); a trap beats releasing on each of them and also covers a
    # failure between two locked invocations.
    trap 'prism_build_lock_release' EXIT
    return 0
}

# gradlew under the repo lock. A lock timeout prints its explanation on
# stdout and returns 1, so existing callers surface it exactly like a build
# failure — loudly, with the reason in the captured output.
prism_locked_gradlew() {
    if ! prism_build_lock_acquire; then
        printf 'prism-verify: another verification build has held the repo build lock\n'
        printf 'for over %ss (%s —\n' "$PRISM_BUILD_LOCK_WAIT" "$(prism_build_lock_path)"
        printf 'a parallel session, most likely). Nothing was run: concurrent gate builds\n'
        printf 'corrupt each other, so this one refused to start. Retry when the other\n'
        printf 'run finishes, or delete the lock directory if it is stale.\n'
        return 1
    fi
    "$PRISM_ROOT/gradlew" -p "$PRISM_ROOT" "$@"
    prism_locked_status=$?
    prism_build_lock_release
    return "$prism_locked_status"
}

# One "<dir> <gradlePath>" pair per line, longest dir first so that a
# prefix match finds the most specific module. settings.gradle.kts is the
# authority on which modules exist.
# PRISM'S OWN MODULES, WHICH ARE NOT THE CONSUMER'S CODE.
#
# The install declares two Gradle modules in the consumer's settings.gradle.kts:
# the detekt rule set and the konsist suite. They are the framework, shipped and
# tested here; a gate that measured them would be PRISM grading itself inside
# somebody else's repository, and it failed real installs for doing it.
# `:tooling:konsist` has a src/test and NO src/main, so the coverage gate
# measured a report with zero lines, called it `no-line-data`, and DENIED THE
# PUSH -- on a module the installer had just placed.
#
# They are still verified, and by the check that suits them: gate 0's
# `staticAnalysis` aggregate runs `:tooling:konsist:test`, which is exactly the
# konsist suite, and the rule set has its own 460-test suite here. What they are
# out of is the per-module iteration below -- coverage, and the build/test gate.
#
# lib/scope.py holds the same list for the posture table; tests/test_affected.sh
# asserts the two agree, because this file's own history says a definition kept
# in two places drifted apart twice in one branch.
prism_own_modules() {
    printf '%s\n' ':tooling:prism-rules' ':tooling:konsist'
}

prism_module_table() {
    # One newline-separated -F pattern list, from the function above rather than
    # a second copy of the paths: POSIX grep treats each line of an -F pattern as
    # its own pattern, so this stays one definition.
    prism_table_own=$(prism_own_modules)
    grep -oE '^include\("[^"]+"\)' "$PRISM_ROOT/settings.gradle.kts" \
        | sed -E 's/^include\("//; s/"\)$//' \
        | grep -vxF "$prism_table_own" \
        | while IFS= read -r gradle_path; do
              dir=$(printf '%s' "$gradle_path" | sed 's|^:||; s|:|/|g')
              printf '%s %s\n' "$dir" "$gradle_path"
          done \
        | awk '{ print length($1), $0 }' \
        | sort -rn \
        | cut -d' ' -f2-
}

# <gradlePath> -> directory form.
#
# The trailing newline matters: a caller that loops over modules and feeds the
# results to a command as separate arguments needs one line per module.
# Without it the paths concatenate into ONE nonexistent argument, which is how
# the deleted gate 4 silently degraded into a vacuous check.
prism_module_dir() {
    printf '%s\n' "$1" | sed 's|^:||; s|:|/|g'
}

# <path> -> the gradle path of the module owning it, or empty.
prism_module_for_path() {
    prism_module_table | awk -v target="$1" '
        { if (index(target, $1 "/") == 1) { print $2; exit } }'
}

# Paths that belong to NO module but can change how EVERY module builds:
# the convention plugins in build-logic, the version catalog, the Gradle
# wrapper, and the root/settings build files.
#
# One entry per line, relative to PRISM_ROOT; a directory entry covers
# everything under it. This list (plus the basename list below) is THE
# definition: the prefix matcher, the pass-cache file list, and the turn
# fingerprint below all derive from it, because when they were three
# hand-maintained copies they drifted apart twice in one branch (detekt.yml
# missing everywhere, gradle/wrapper missing from the fingerprint).
prism_global_build_paths() {
    printf '%s\n' \
        'build-logic' \
        'gradle/libs.versions.toml' \
        'gradle/wrapper' \
        'settings.gradle.kts' \
        'build.gradle.kts' \
        'gradle.properties'
}

# The static-analysis rule sets, matched by BASENAME anywhere in the tree.
# ktlint resolves .editorconfig hierarchically, so a module-local copy can
# relax rules for everything under its directory — and relaxing a rule must
# never be the one edit that skips the gate it relaxes. When only the ROOT
# copies were on the list, adding core/data/.editorconfig matched no module,
# no global path, and no fingerprint pathspec: the one edit that changes what
# gate 0 means slid past every gate until push.
prism_global_build_basenames() {
    printf '%s\n' \
        '.editorconfig' \
        'detekt.yml'
}

# Without this, global build files mapped to no module at all, so a change to
# a convention plugin — the one edit that can break all thirteen modules at
# once — made both gates derive an empty module list and exit 0 having
# verified nothing.
prism_is_global_build_file() {
    for prism_gbf_entry in $(prism_global_build_paths); do
        case "$1" in
            "$prism_gbf_entry" | "$prism_gbf_entry"/*) return 0 ;;
        esac
    done
    for prism_gbf_entry in $(prism_global_build_basenames); do
        case "$1" in
            "$prism_gbf_entry" | */"$prism_gbf_entry") return 0 ;;
        esac
    done
    return 1
}

# Reads paths on stdin. Exit 0 when any of them is a global build file.
prism_has_global_build_change() {
    while IFS= read -r file; do
        [ -n "$file" ] || continue
        if prism_is_global_build_file "$file"; then
            return 0
        fi
    done
    return 1
}

# The absolute path of every global build file that exists, one per line.
# Folded into prism_source_hash so the Stop gate's pass-cache cannot
# survive a convention-plugin, rule-set, or version-catalog edit. Derived
# from prism_global_build_paths; directory entries exclude their own build
# outputs, which are neither sources nor stable.
prism_global_build_file_list() {
    for prism_gbf_entry in $(prism_global_build_paths); do
        if [ -f "$PRISM_ROOT/$prism_gbf_entry" ]; then
            printf '%s\n' "$PRISM_ROOT/$prism_gbf_entry"
        elif [ -d "$PRISM_ROOT/$prism_gbf_entry" ]; then
            find "$PRISM_ROOT/$prism_gbf_entry" -type f \
                -not -path '*/build/*' -not -path '*/.gradle/*' 2>/dev/null
        fi
    done
    for prism_gbf_entry in $(prism_global_build_basenames); do
        find "$PRISM_ROOT" -name "$prism_gbf_entry" -type f \
            -not -path '*/build/*' -not -path '*/.gradle/*' \
            -not -path '*/.git/*' 2>/dev/null
    done
    return 0
}

# Every gradle path in settings.gradle.kts.
prism_all_modules() {
    prism_module_table | awk '{ print $2 }' | sort -u
}

# The given modules PLUS every module that depends on one of them.
#
# Gradle builds a module's dependencies, never its dependents, so verifying
# only the edited module is not Principle VII gate 2. Returns 1 and prints
# nothing when the graph cannot be read — callers MUST treat that as a hard
# error, exactly like an unresolvable base branch, rather than silently
# falling back to the narrower (and wrong) scope.
prism_with_dependents() {
    if [ "$#" -eq 0 ]; then
        return 0
    fi
    # Seeds travel over stdin, one per line, never as --module arguments. A
    # zsh caller does not word-split `$modules`, so the whole newline-joined
    # list arrives here as ONE argument — and a positional --module built
    # from that blob was a single well-formed value that deps.py passed
    # through unchanged, silently returning the seeds with no dependents.
    # printf '%s\n' splits that same blob back into lines, so both shells
    # hand deps.py the identical list.
    prism_dep_out=$(printf '%s\n' "$@" \
        | python3 "$PRISM_LIB_DIR/deps.py" dependents \
            --root "$PRISM_ROOT" --stdin 2>&1) || return 1
    printf '%s\n' "$prism_dep_out"
}

# Where the coverage report a given module's kind produces actually lands.
# Shared so the gate, which DENIES on it, and status.sh, which REPORTS on it,
# can never look at two different files.
prism_coverage_xml_for() {
    dir=$(prism_module_dir "$1")
    case "$2" in
        combined|instrumentation)
            printf '%s/%s/build/reports/prism-combined-coverage/coverage.xml\n' \
                "$PRISM_ROOT" "$dir"
            ;;
        *)
            printf '%s/%s/build/reports/prism-coverage/coverage.xml\n' \
                "$PRISM_ROOT" "$dir"
            ;;
    esac
}

# The exact commands that make one module's coverage report current, one per
# logical step, unindented.
#
# Shared by push-gate.sh's denial and status.sh's report on purpose. A status
# view whose refresh commands differ from the ones the gate prints is worse
# than no status view: it sends people to run something that does not clear
# the denial. There is one renderer, so there is nothing to keep in sync.
#
# Every connected run goes through device_lock.py — two of them for the same
# test package on one emulator destroy each other's execution data.
prism_refresh_commands() {
    prism_refresh_module="$1"
    prism_refresh_kind="$2"
    # ONE COMMAND, because since 0.6.3 there is one. This used to print the
    # init-script Gradle invocation, the device_lock.py wrapper and a
    # coverage.py record line with four arguments -- every one of which
    # ./prism coverage now derives from the module name, out of this very
    # function. A gate that denies a push should hand back something a person
    # can retype, not a library path.
    case "$prism_refresh_kind" in
        jvm)
            printf './prism coverage %s\n' "$prism_refresh_module"
            ;;
        *)
            printf './prism coverage %s      # needs a booted emulator\n' \
                "$prism_refresh_module"
            ;;
    esac
    # AND STAMP IT WITH WHAT IT MEASURED, in the same block, because whoever
    # runs the line above is the only one in a position to know that the report
    # and the source agree. It refuses unless the timestamps already say so, so
    # it cannot launder a stale report.
    #
    # Without this the fingerprint was written only by the push gate, so anybody
    # who measured coverage and did not immediately push had none -- and the next
    # thing to move an mtime without changing a byte turned the measurement into
    # `stale`. The differential canary does exactly that: reverting it rewrites
    # the file, so proving the gate was live cost the number, which on a
    # connected suite is minutes and an emulator.
}

# Exit 0 when a module's kind needs a booted device to refresh.
prism_kind_needs_device() {
    case "$1" in
        combined|instrumentation) return 0 ;;
    esac
    return 1
}

# The gradle tasks that prove one module compiles and its unit tests pass.
# Deviceless by construction: instrumentation belongs to the push gate.
#
# The compile task is `assemble`, never `build`: `build` runs `check`, which
# runs the tests, so a failing unit test surfaced under the compile task and
# was reported as "does not compile" — and the explicit test task after it
# was pure duplication. assemble/test mirrors the Android pair exactly, so
# the gate-1/gate-2 split holds for both module kinds.
prism_build_tasks_for() {
    gradle_path="$1"
    dir=$(prism_module_dir "$gradle_path")

    if prism_is_jvm_only "$gradle_path"; then
        printf '%s:assemble\n' "$gradle_path"
        if [ -d "$PRISM_ROOT/$dir/src/test" ]; then
            printf '%s:test\n' "$gradle_path"
        fi
    else
        printf '%s:assembleDebug\n' "$gradle_path"
        if [ -d "$PRISM_ROOT/$dir/src/test" ]; then
            printf '%s:testDebugUnitTest\n' "$gradle_path"
        fi
    fi
}

# Exit 0 when the module applies the detekt convention plugin itself.
#
# `assemble` deliberately does not ride `check` the way `build` did, so the
# per-module detekt task fell out of the task pair when `build` was replaced.
# The Stop and push gates compensate with a repo-wide staticAnalysis run; the
# SubagentStop gate must not (one subagent's violation elsewhere would block
# every other subagent in the wave), so it asks this per module instead. The
# match is the literal plugins-block line, not a bare grep for the id — the
# tooling modules mention "prism.detekt" in comments explaining why they
# deliberately do NOT apply it.
prism_module_has_detekt() {
    dir=$(prism_module_dir "$1")
    grep -q 'id("prism\.detekt")' "$PRISM_ROOT/$dir/build.gradle.kts" 2>/dev/null
}

# Reads paths on stdin, writes unique gradle paths on stdout.
prism_modules_for_files() {
    {
        prism_module_table
        echo "__END_TABLE__"
        cat
    } | awk '
        BEGIN { in_table = 1 }
        in_table && /__END_TABLE__/ {
            in_table = 0
            next
        }
        in_table {
            split($0, parts, " ")
            dir[++rowcount] = parts[1]
            gradle[rowcount] = parts[2]
            next
        }
        {
            for (i = 1; i <= rowcount; i++) {
                if (index($0, dir[i] "/") == 1) { print gradle[i]; break }
            }
        }
    ' | sort -u
}

# Resolves the base branch every gate computes its change scope against, into
# PRISM_BASE_BRANCH. Returns 1, having said why on stderr, when it cannot.
#
# The first of the nine configuration knobs to travel the whole route: declared
# in parameters.json, rendered into prism.json by render.py, read back here
# through config.sh. It is the proof that the mechanism works end to end, which
# is why exactly one knob makes the trip in this phase and the other eight wait
# for Phase 2 (design D6).
#
# The `:-master` fallback it replaces was the shape this framework keeps having
# to unlearn. A consumer whose trunk is `main` got a gate that resolved no merge
# base, or worse resolved one against a stale `master` and verified a scope that
# was not the change — and nothing said so, because a default that is present is
# indistinguishable from a value that was chosen. Per config.sh's contract an
# inability to ask the question is not an answer, so an unreadable configuration
# denies here rather than substituting PRISM's guess about someone else's
# repository.
#
# The environment still wins, and only as an override: scope.sh's `--base=` and
# the test suite both set PRISM_BASE_BRANCH directly. It can redirect the value,
# never disable the requirement for one.
prism_require_base_branch() {
    if [ -n "${PRISM_BASE_BRANCH:-}" ]; then
        return 0
    fi
    PRISM_BASE_BRANCH=$(prism_config_get baseBranch) || return 1
    if [ -z "$PRISM_BASE_BRANCH" ]; then
        printf 'prism: baseBranch is empty in %s\n' "$(prism_config_path)" >&2
        printf 'The gate cannot compute a change scope against an empty ref.\n' >&2
        return 1
    fi
    return 0
}

# Resolves the merge base, trying the local ref then the remote-tracking one.
# Prints nothing and returns 1 when the base cannot be determined — callers
# MUST treat that as a hard error, never as "nothing changed".
prism_merge_base() {
    prism_require_base_branch || return 1
    for ref in "$PRISM_BASE_BRANCH" "origin/$PRISM_BASE_BRANCH"; do
        base=$(git -C "$PRISM_ROOT" merge-base "$ref" HEAD 2>/dev/null)
        if [ -n "$base" ]; then
            printf '%s\n' "$base"
            return 0
        fi
    done
    return 1
}

# Everything on this branch that is not in the base branch, plus unstaged
# and untracked work. Committing therefore does not shrink the scope.
# Returns 1 and prints nothing when the base cannot be resolved — the
# caller MUST treat that as a hard error, not as an empty change set.
prism_changed_files_stop() {
    base=$(prism_merge_base) || return 1
    {
        git -C "$PRISM_ROOT" diff --name-only "$base" HEAD
        git -C "$PRISM_ROOT" diff --name-only HEAD
        git -C "$PRISM_ROOT" ls-files --others --exclude-standard
    } 2>/dev/null | sort -u
}

# Uncommitted work only: tracked edits against HEAD plus untracked files.
#
# This is the SubagentStop gate's scope. It deliberately does not reach back
# to the base branch the way prism_changed_files_stop does — a subagent is
# answerable for what it left in the tree, not for the whole branch, and in a
# worktree started from a commit the two are the same set anyway.
#
# Returns 1 and prints nothing when git itself fails (an empty repo has no
# HEAD to diff against) — the caller MUST treat that as "cannot tell", never
# as an empty change set.
prism_changed_files_worktree() {
    tracked=$(git -C "$PRISM_ROOT" diff --name-only HEAD 2>/dev/null) || return 1
    untracked=$(git -C "$PRISM_ROOT" ls-files --others --exclude-standard \
        2>/dev/null) || return 1
    printf '%s\n%s\n' "$tracked" "$untracked" | sed '/^$/d' | sort -u
}

# The range a push would send. Falls back to the base-branch merge-base
# when the branch has no upstream, which is the case for a first push.
# Returns 1 and prints nothing when neither an upstream nor the base can
# be resolved — the caller MUST treat that as a hard error.
prism_push_range() {
    head="${PRISM_PUSH_HEAD:-HEAD}"
    upstream=$(git -C "$PRISM_ROOT" rev-parse --abbrev-ref \
        --symbolic-full-name "$head@{upstream}" 2>/dev/null)
    if [ -n "$upstream" ]; then
        printf '%s..%s\n' "$upstream" "$head"
        return 0
    fi
    base=$(prism_merge_base) || return 1
    printf '%s..%s\n' "$base" "$head"
}

# Returns 1 and prints nothing when `git diff` itself fails on the given
# range (a stale @{upstream} pointing at a deleted ref, a range whose base
# commit is gone, ...) — the caller MUST treat that as a hard error, not as
# an empty diff. Piping straight into `sort -u` the way this used to work
# hid a `git diff` failure completely: `sort` on empty stdin still exits 0.
prism_changed_files_range() {
    output=$(git -C "$PRISM_ROOT" diff --name-only "$1" 2>/dev/null) || return 1
    printf '%s\n' "$output" | sort -u
}

# Exit 0 when the module is a pure Kotlin/JVM library, which has no
# assembleDebug or testDebugUnitTest task.
#
# Detected by the ABSENCE of the Android Gradle Plugin rather than the presence
# of one specific JVM plugin id. An earlier grep for prism.kotlin.java.library
# routed any module with a NEW JVM plugin id to assembleDebug — a task it does
# not have — and Gradle's "task not found" surfaced as a bogus "does not
# compile".
#
# The signal is AGP's OWN ids, `com.android.application` and
# `com.android.library`, resolved through the consumer's convention plugins the
# same way coverage.py resolves jacoco. It used to grep for `prism.android`,
# which only ever worked in a repository that applied PRISM's own Android
# convention plugins — and PRISM no longer ships any. Those plugins configured
# compileSdk, minSdk and jvmTarget: they were the origin project's build setup,
# not verification, and a framework that rewrites the build it is checking is
# the wrong shape. Asking about AGP asks the question that was always meant.
#
# The failure direction is deliberate and safe: misreading an Android module as
# JVM merely runs its slower all-variant assemble and test tasks, which is still
# a correct verification. Misreading JVM as Android would name a task that does
# not exist, which is not.
prism_is_jvm_only() {
    dir=$(prism_module_dir "$1")
    build_file="$PRISM_ROOT/$dir/build.gradle.kts"
    [ -f "$build_file" ] || return 0

    # Applied directly — the common case, and true however the consumer's own
    # convention plugins are named.
    if grep -q 'com\.android\.\(application\|library\)' "$build_file" 2>/dev/null; then
        return 1
    fi

    # Applied through a convention plugin of theirs. Same transitive resolution
    # coverage.py performs for jacoco, so a module saying id("acme.android.lib")
    # is recognised as Android without PRISM knowing that name in advance.
    if [ -n "${PRISM_LIB_DIR:-}" ] && [ -f "$PRISM_LIB_DIR/coverage.py" ]; then
        if python3 "$PRISM_LIB_DIR/coverage.py" is-android \
            --root "$PRISM_ROOT" --module "$dir" 2>/dev/null; then
            return 1
        fi
    fi
    return 0
}

# SHA-256 over "<repo-relative path>:<git blob hash>" for every Kotlin file
# under src/ (production, src/test, AND src/androidTest) plus the module's
# build file, in the given modules. Hashes the working tree, not the index,
# so uncommitted edits change the result.
#
# Used by the Stop gate's pass-cache: that gate RUNS the tests, so a change
# to a test file changes what was actually verified and must invalidate a
# previous pass. Do NOT scope this one to src/main — that was tried and was
# wrong.
#
# The global build files are folded in unconditionally. They belong to no
# module, so a convention-plugin or version-catalog edit changed nothing in
# this hash and a stale pass-cache entry stayed valid across an edit that
# can alter every module's build.
prism_source_hash() {
    {
        for gradle_path in "$@"; do
            dir=$(prism_module_dir "$gradle_path")
            find "$PRISM_ROOT/$dir/src" -type f \( -name '*.kt' -o -name '*.kts' \) 2>/dev/null
            if [ -f "$PRISM_ROOT/$dir/build.gradle.kts" ]; then
                printf '%s\n' "$PRISM_ROOT/$dir/build.gradle.kts"
            fi
        done
        prism_global_build_file_list
    } | sort | while IFS= read -r file; do
        printf '%s:%s\n' "${file#"$PRISM_ROOT"/}" \
            "$(git -C "$PRISM_ROOT" hash-object "$file" 2>/dev/null)"
    done | shasum -a 256 | cut -d' ' -f1
}

# A cheap, content-aware fingerprint of everything that can change the Stop
# gate's verdict: the Kotlin sources, the global build files, and the commit
# they sit on. Returns 1 and prints nothing when git cannot answer — callers
# MUST treat that as "cannot tell", never as "nothing changed".
#
# Deliberately NOT prism_source_hash. That one shells out to `git
# hash-object` once per Kotlin file in every module (2.3s in this repo)
# because it answers a different question: "is this exact source the source
# that passed the gates", scoped to a module list. This answers "did anything
# gate-relevant move since the Stop gate last signed off", which one
# `git diff` already computes (11ms). It runs on every stop, so the
# difference matters.
#
# HEAD is folded in because committing during a turn empties `git diff HEAD`
# without the work having gone anywhere.
prism_turn_fingerprint() {
    prism_fp_head=$(git -C "$PRISM_ROOT" rev-parse HEAD 2>/dev/null) || return 1
    # The pathspecs are derived from prism_global_build_paths rather than
    # spelled out again: a hand-copied list here is how a wrapper bump and a
    # detekt.yml edit each became invisible to the fingerprint while still
    # widening the gate's scope to every module.
    #
    # '*/src/*' folds every module source into the fingerprint, not just the
    # Kotlin: deleting a string resource breaks every consumer of R.string.x
    # at assembleDebug, so a res or manifest edit landing after a sign-off
    # must re-run the gate rather than ride the previous verdict.
    set -- '*.kt' '*.kts' '*/src/*'
    for prism_fp_entry in $(prism_global_build_paths); do
        set -- "$@" "$prism_fp_entry"
    done
    # Basename entries become '*<name>' pathspecs: git's default pathspec
    # wildcards cross directory boundaries, so '*.editorconfig' matches the
    # root copy and every module-local one.
    for prism_fp_entry in $(prism_global_build_basenames); do
        set -- "$@" "*$prism_fp_entry"
    done
    prism_fp_tracked=$(git -C "$PRISM_ROOT" diff HEAD -- "$@" 2>/dev/null) \
        || return 1
    prism_fp_untracked=$(git -C "$PRISM_ROOT" ls-files --others \
        --exclude-standard -- "$@" 2>/dev/null) || return 1
    {
        printf '%s\n' "$prism_fp_head"
        printf '%s\n' "$prism_fp_tracked"
        printf '%s\n' "$prism_fp_untracked" | while IFS= read -r file; do
            [ -n "$file" ] || continue
            printf '%s:%s\n' "$file" \
                "$(git -C "$PRISM_ROOT" hash-object "$file" 2>/dev/null)"
        done
    } | shasum -a 256 | cut -d' ' -f1
}


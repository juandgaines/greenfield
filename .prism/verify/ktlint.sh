#!/bin/sh
# PostToolUse hook — Principle VII gate 0 for a single edited file.
#
# Runs ktlint on the file that was just written, autocorrecting first and
# reporting only what is left. Formatting drift is fixed silently and never
# reaches the transcript; naming, imports and file layout — the violations
# that need a decision — are reported at file:line:col.
#
# Exit 0 = nothing to say. Exit 2 = report on stderr, which is the only exit
# code that puts the report in front of Claude; stderr from an exit-0 hook
# goes to the debug log alone.
set -u

HOOK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$HOOK_DIR/lib/affected.sh"

REPORT_PY="$PRISM_LIB_DIR/ktlint_report.py"
KTLINT_MAIN="com.pinterest.ktlint.Main"

prism_require_root || exit 0

# --- what was edited -------------------------------------------------------
# A non-Kotlin path, an event this hook cannot read, or a file that no longer
# exists are all "nothing to check", not "check failed".
file=$(python3 "$REPORT_PY" path 2>/dev/null) || exit 0
[ -n "$file" ] || exit 0
[ -f "$file" ] || exit 0

# --- which checkout owns the edit ------------------------------------------
# An edit can land in a git WORKTREE of this repository — a subagent
# dispatched into one, say — and those paths used to exit here silently, so
# a whole worktree wave went unformatted until a later Stop gate failed
# repo-wide staticAnalysis in a turn that did not cause it. The gate follows
# the file instead: `--git-common-dir` is identical for a repo and all of its
# worktrees (the same test prism_require_root uses), so a same-repo worktree
# is verified against ITS OWN root — its .editorconfig, its build files — and
# a file in an unrelated checkout stays out of scope as before.
seed_root=""
case "$file" in
    "$PRISM_ROOT"/*) ;;
    *)
        file_root=$(git -C "$(dirname -- "$file")" rev-parse \
            --show-toplevel 2>/dev/null)
        [ -n "$file_root" ] || exit 0
        session_repo=$(git -C "$PRISM_ROOT" rev-parse \
            --path-format=absolute --git-common-dir 2>/dev/null)
        file_repo=$(git -C "$file_root" rev-parse \
            --path-format=absolute --git-common-dir 2>/dev/null)
        if [ -z "$session_repo" ] || [ -z "$file_repo" ] || \
                [ "$session_repo" != "$file_repo" ]; then
            exit 0
        fi
        [ -f "$file_root/settings.gradle.kts" ] || exit 0
        seed_root="$PRISM_ROOT"
        PRISM_ROOT="$file_root"
        ;;
esac

CLASSPATH_FILE="$PRISM_ROOT/build/ktlint/cli-classpath.txt"

# --- the CLI ---------------------------------------------------------------
# The version lives in libs.versions.toml, but the dependency WIRING — the
# ktlint configuration, its SHADOWED bundling attribute, any future ruleset
# artifact — lives in the root build.gradle.kts, so BOTH are staleness
# inputs. Watching only the catalog left the hook formatting with the OLD
# ktlint after a build-file edit, indefinitely, while gate 0 resolved live:
# the hook then "corrected" files into a shape the Stop gate rejected.
# Gradle resolves the classpath once into this file and the hook reads it
# from there, paying the daemon cost on the first edit of a checkout rather
# than on every edit. An entry that no longer exists on disk is a Gradle
# cache eviction that would otherwise fail silently below — every entry is
# checked, not just the first, so the check survives the day the classpath
# grows a second jar.
classpath_is_stale() {
    stale_file="$1"
    stale_root="$2"
    [ -f "$stale_file" ] || return 0
    [ -s "$stale_file" ] || return 0
    for dep in "$stale_root/gradle/libs.versions.toml" \
               "$stale_root/build.gradle.kts"; do
        if [ "$dep" -nt "$stale_file" ]; then
            return 0
        fi
    done
    if ! tr ':' '\n' < "$stale_file" 2>/dev/null | {
            while IFS= read -r entry; do
                [ -n "$entry" ] || continue
                [ -f "$entry" ] || exit 1
            done
        }; then
        return 0
    fi
    return 1
}

# A worktree starts with an empty build/, so its first edit paid the cold
# Gradle resolve — once per worktree, multiplied across a wave. The resolved
# classpath is a list of jars in the shared ~/.gradle cache, valid for any
# tree whose version and wiring inputs are the same, so when the session
# root's copy is fresh and those inputs match byte-for-byte (or are absent on
# both sides), it is copied instead of re-resolved. Any doubt falls through
# to the real resolve.
seed_classpath() {
    [ -n "$seed_root" ] || return 1
    seed_file="$seed_root/build/ktlint/cli-classpath.txt"
    if classpath_is_stale "$seed_file" "$seed_root"; then
        return 1
    fi
    for dep in gradle/libs.versions.toml build.gradle.kts; do
        if [ -f "$PRISM_ROOT/$dep" ] || [ -f "$seed_root/$dep" ]; then
            cmp -s "$PRISM_ROOT/$dep" "$seed_root/$dep" || return 1
        fi
    done
    mkdir -p "${CLASSPATH_FILE%/*}" 2>/dev/null || return 1
    cp "$seed_file" "$CLASSPATH_FILE" 2>/dev/null
}

# A cold resolution is a full Gradle invocation — daemon start, build-logic
# compile, a jar download — and this hook runs under a 120s PostToolUse
# timeout. A timed-out hook is KILLED, and a killed hook reports nothing, so
# the resolution gets a budget under that timeout: exceeding it kills gradlew
# instead, and the hook lives to say the edit went unchecked. The Stop and
# push gates warm this file on their own gradlew runs, so the cold path is
# rare — a fresh checkout's first edit before any gate has run.
RESOLVE_BUDGET="${PRISM_KTLINT_RESOLVE_BUDGET:-90}"

resolve_classpath() {
    "$PRISM_ROOT/gradlew" -p "$PRISM_ROOT" -q ktlintToolingClasspath \
        >/dev/null 2>&1 &
    resolve_pid=$!
    # A watchdog rather than a kill -0 poll: the shell does not reap a
    # background child until `wait`, so a fast-exiting gradlew stays visible
    # to kill -0 as a zombie and a poll would misread it as running.
    ( sleep "$RESOLVE_BUDGET" && kill "$resolve_pid" ) >/dev/null 2>&1 &
    watchdog_pid=$!
    wait "$resolve_pid"
    resolve_status=$?
    kill "$watchdog_pid" >/dev/null 2>&1
    return "$resolve_status"
}

if classpath_is_stale "$CLASSPATH_FILE" "$PRISM_ROOT" && ! seed_classpath; then
    rm -f "$CLASSPATH_FILE"
    resolve_classpath
    resolve_status=$?
    if [ "$resolve_status" -gt 128 ]; then
        prism_report "prism-verify: gate 0 did not run — resolving the ktlint CLI classpath
exceeded ${RESOLVE_BUDGET}s and was stopped so this report could reach you at all.
This edit was NOT checked. Run ./gradlew ktlintToolingClasspath once to warm
it; later edits are then checked from the cached classpath.
"
        exit 0
    fi
    if [ "$resolve_status" -ne 0 ] || [ ! -f "$CLASSPATH_FILE" ]; then
        prism_report 'prism-verify: gate 0 did not run — ktlintToolingClasspath
could not produce build/ktlint/cli-classpath.txt, so ktlint has no CLI to run.
This edit was NOT checked. Run ./gradlew ktlintToolingClasspath to see why.
'
        exit 0
    fi
fi

# The file can vanish or be truncated BETWEEN the staleness check and this
# read — two concurrent hook invocations, one of them mid-re-resolve. Every
# other could-not-run path here reports loudly; this one must too, or a
# parallel wave's unluckiest edit reads as checked-and-clean.
classpath=$(cat "$CLASSPATH_FILE" 2>/dev/null)
if [ -z "$classpath" ]; then
    prism_report 'prism-verify: gate 0 did not run — build/ktlint/cli-classpath.txt
was empty or gone when read (a concurrent edit re-resolving it, most likely).
This edit was NOT checked. It resolves itself on the next edit; or run
./gradlew ktlintToolingClasspath and retry.
'
    exit 0
fi

ktlint() {
    (cd "$PRISM_ROOT" && java -cp "$classpath" "$KTLINT_MAIN" "$@" 2>&1)
}

relative=${file#"$PRISM_ROOT"/}

# --- correct, and report what correcting could not reach -------------------
# One launch, not two: the format pass's own plain output already lists
# exactly the violations it could not fix (corrected drift stays silent), so
# a second check-only JVM launch recomputed a list this one already printed.
# The exit status rides along so the reporter can tell "clean" apart from
# "ktlint itself crashed" — the two used to be indistinguishable, and a
# broken java or classpath passed every edit silently.
ktlint_output=$(ktlint --format --relative --reporter=plain --log-level=none "$relative")
ktlint_status=$?

printf '%s\n' "$ktlint_output" | python3 "$REPORT_PY" report \
    --file "$file" --root "$PRISM_ROOT" --display "$relative" \
    --ktlint-exit "$ktlint_status"
exit $?

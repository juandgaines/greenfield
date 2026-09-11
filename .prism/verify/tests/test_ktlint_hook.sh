# Tests for ktlint.sh — the per-edit static-analysis gate. Sourced by run-tests.sh.
#
# These run the REAL ktlint CLI against a fixture repo, because the behaviour
# under test is "what does the agent actually see after an edit", and a stubbed
# ktlint would only assert that the shell plumbing calls something. The rule set
# comes from the project's own .editorconfig, copied into the fixture, so the
# fixture and the Gradle task agree by construction.

# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

ktlint_real_root=$(dirname "$(dirname "$HOOK_DIR")")
ktlint_classpath_file="$ktlint_real_root/build/ktlint/cli-classpath.txt"

# One-time, and only when a checkout has never resolved the CLI. Everything
# below needs a real ktlint; producing it here beats every case silently
# degrading to "the hook could not run, so it said nothing".
if [ ! -f "$ktlint_classpath_file" ]; then
    "$ktlint_real_root/gradlew" -p "$ktlint_real_root" -q ktlintToolingClasspath \
        >/dev/null 2>&1
fi

# A payload extracted from the distribution ZIP is not a Gradle project: it
# carries build-logic as SOURCE and has no wrapper of its own, so there is
# nothing here that could resolve a CLI. That is a different fact from "this
# repository has Gradle and the resolution failed", and only the second is a
# defect. Conflating them made `build-zip.sh`'s check of the extracted archive
# fail on a payload that was completely intact.
#
# Reported loudly and skipped, never passed: a silent skip in the one suite that
# proves the formatting hook works would be the same false green the hook itself
# exists to prevent.
if [ ! -f "$ktlint_classpath_file" ] && [ ! -f "$ktlint_real_root/gradlew" ]; then
    printf 'SKIP ktlint.sh suite — no Gradle wrapper at %s\n' "$ktlint_real_root"
    printf '     This is the payload as shipped, not a built repository, so no\n'
    printf '     ktlint CLI can be resolved here. Run this suite from a checkout.\n'
elif [ ! -f "$ktlint_classpath_file" ]; then
    PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
    printf 'FAIL ktlint.sh suite could not resolve the ktlint CLI\n'
    printf '  run ./gradlew ktlintToolingClasspath and retry\n'
else

ktlint_repo=$(prism_fixture_repo)
KTLINT_HOOK="$ktlint_repo/.prism/verify/ktlint.sh"
cp "$ktlint_real_root/.editorconfig" "$ktlint_repo/.editorconfig"
mkdir -p "$ktlint_repo/build/ktlint"
cp "$ktlint_classpath_file" "$ktlint_repo/build/ktlint/cli-classpath.txt"

ktlint_event() {
    printf '{"hook_event_name":"PostToolUse","tool_name":"Edit","tool_input":{"file_path":"%s"}}' "$1"
}

ktlint_run() {
    ktlint_event "$1" | PRISM_ROOT="$ktlint_repo" sh "$KTLINT_HOOK" 2>&1
}

# --- a non-Kotlin path is not this gate's business ------------------------
printf 'plain text\n' > "$ktlint_repo/notes.txt"
out=$(ktlint_run "$ktlint_repo/notes.txt")
status=$?
assert_eq "0" "$status" "a .txt edit exits 0"
assert_eq "" "$out" "a .txt edit produces no output"

# An .xml layout and the version catalog are the two non-Kotlin files most
# likely to be edited in this project; neither may reach ktlint.
printf 'agp = "9.1.0"\n' > "$ktlint_repo/libs.versions.toml"
out=$(ktlint_run "$ktlint_repo/libs.versions.toml")
assert_eq "0" "$?" "a .toml edit exits 0"
assert_eq "" "$out" "a .toml edit produces no output"

# --- a file that no longer exists is nothing to check ---------------------
out=$(ktlint_run "$ktlint_repo/core/domain/src/main/java/Gone.kt")
assert_eq "0" "$?" "an edit to a since-deleted file exits 0"
assert_eq "" "$out" "an edit to a since-deleted file produces no output"

# --- autocorrectable drift is fixed in place and never reported -----------
drifted="$ktlint_repo/core/domain/src/main/java/Drifted.kt"
cat > "$drifted" <<'DRIFTED'
package seed

internal fun add(a:Int,b:Int):Int{
        return a+b
}
DRIFTED
before=$(cat "$drifted")
out=$(ktlint_run "$drifted")
status=$?
after=$(cat "$drifted")
assert_eq "0" "$status" "a purely autocorrectable file exits 0"
assert_eq "" "$out" "autocorrected formatting never reaches the transcript"
if [ "$before" = "$after" ]; then
    PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
    printf 'FAIL the hook did not correct the file in place\n  content: [%s]\n' "$after"
else
    PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + 1))
fi
assert_contains "$after" "fun add(a: Int, b: Int): Int" "the correction is ktlint's, not a rewrite"

# --- a violation with no autocorrect reaches the agent --------------------
wildcard="$ktlint_repo/core/domain/src/main/java/Wildcard.kt"
cat > "$wildcard" <<'WILDCARD'
package seed

import java.util.*

internal val id = UUID.randomUUID()
WILDCARD
out=$(ktlint_run "$wildcard")
status=$?
assert_eq "2" "$status" "a residual violation exits 2, the code that reaches Claude"
assert_contains "$out" "core/domain/src/main/java/Wildcard.kt:3:1:" \
    "the report names the file, line and column"
assert_contains "$out" "(standard:no-wildcard-imports)" "the report names the rule id"
assert_contains "$out" "Wildcard import" "the report says what is wrong"

# --- only the edited file is reported on ----------------------------------
# Wildcard.kt above is still on disk and still violating; editing a DIFFERENT
# file must not surface it. This is the property that keeps a per-edit report
# about the edit rather than about the repository.
other="$ktlint_repo/core/domain/src/main/java/Other2.kt"
cat > "$other" <<'OTHER'
package seed

import java.io.*

internal val separator = File.separator
OTHER
out=$(ktlint_run "$other")
assert_eq "2" "$?" "the second file's own violation still exits 2"
assert_contains "$out" "Other2.kt:3:1:" "the edited file is reported"
assert_not_contains "$out" "Wildcard.kt" "a violation in another file is not reported"

# --- a clean Kotlin file says nothing -------------------------------------
clean="$ktlint_repo/core/domain/src/main/java/Clean.kt"
printf 'package seed\n\ninternal val answer = 42\n' > "$clean"
out=$(ktlint_run "$clean")
assert_eq "0" "$?" "a clean Kotlin file exits 0"
assert_eq "" "$out" "a clean Kotlin file produces no output"

# --- a file outside the repository is not analysed ------------------------
outside=$(mktemp -d)
printf 'package seed\n\nimport java.util.*\n' > "$outside/Stray.kt"
out=$(ktlint_run "$outside/Stray.kt")
assert_eq "0" "$?" "a path outside PRISM_ROOT exits 0"
assert_eq "" "$out" "a path outside PRISM_ROOT produces no output"
rm -rf "$outside"

# --- a file in an UNRELATED git repo is not analysed either ----------------
# Same-repo detection is by --git-common-dir, so a different repository that
# happens to look like a Gradle project must still be out of scope: this hook
# formats the project it belongs to, not every checkout on the machine.
foreign=$(mktemp -d)
git -C "$foreign" init -q -b master
printf 'include(":m")\n' > "$foreign/settings.gradle.kts"
printf 'package seed\n\nimport java.util.*\n' > "$foreign/Stray.kt"
out=$(ktlint_run "$foreign/Stray.kt")
assert_eq "0" "$?" "a path in a foreign repo exits 0"
assert_eq "" "$out" "a path in a foreign repo produces no output"
rm -rf "$foreign"

# --- a worktree of the same repo IS analysed, against its own root ---------
# This used to exit 0 silently, so a subagent dispatched into a worktree got
# no per-edit formatting at all and the violations surfaced one turn later as
# a repo-wide staticAnalysis failure. The classpath is seeded from the session
# root rather than cold-resolved: the fixture has no working gradlew, so a
# report here proves the seed path, not just the adoption.
ktlint_worktree=$(prism_fixture_worktree "$ktlint_repo")
# The .editorconfig was copied into the fixture AFTER its seed commit, so the
# worktree checkout does not contain it; copied again so both trees lint
# under the project's real rule set rather than ktlint's defaults.
cp "$ktlint_real_root/.editorconfig" "$ktlint_worktree/.editorconfig"
wt_violation="$ktlint_worktree/core/domain/src/main/java/WtWildcard.kt"
cat > "$wt_violation" <<'WTWILDCARD'
package seed

import java.util.*

internal val id = UUID.randomUUID()
WTWILDCARD
out=$(ktlint_run "$wt_violation")
assert_eq "2" "$?" "a worktree violation exits 2, the code that reaches Claude"
assert_contains "$out" "core/domain/src/main/java/WtWildcard.kt:3:1:" \
    "the worktree report is relative to the worktree root"
assert_contains "$out" "(standard:no-wildcard-imports)" \
    "the worktree report names the rule id"
if [ -f "$ktlint_worktree/build/ktlint/cli-classpath.txt" ]; then
    PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + 1))
else
    PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
    printf 'FAIL the worktree classpath was not seeded from the session root\n'
fi

# Autocorrectable drift in the worktree is fixed in place, silently — the
# same contract as the session root, now from the seeded classpath.
wt_drifted="$ktlint_worktree/core/domain/src/main/java/WtDrifted.kt"
cat > "$wt_drifted" <<'WTDRIFTED'
package seed

internal fun add(a:Int,b:Int):Int{
        return a+b
}
WTDRIFTED
out=$(ktlint_run "$wt_drifted")
status=$?
after=$(cat "$wt_drifted")
assert_eq "0" "$status" "a purely autocorrectable worktree file exits 0"
assert_eq "" "$out" "worktree autocorrection never reaches the transcript"
assert_contains "$after" "fun add(a: Int, b: Int): Int" \
    "the worktree file was corrected in place"

# --- an unresolvable CLI is reported, never silently passed ---------------
# The dangerous failure for any gate is looking clean because it did not run.
# With no classpath file and no working gradlew the hook must say so.
mv "$ktlint_repo/build/ktlint/cli-classpath.txt" "$ktlint_repo/build/ktlint/saved.txt"
printf '#!/bin/sh\nexit 1\n' > "$ktlint_repo/gradlew"
chmod +x "$ktlint_repo/gradlew"
out=$(ktlint_run "$wildcard")
assert_eq "0" "$?" "an unresolvable CLI does not block the edit"
assert_contains "$out" "gate 0 did not run" "an unresolvable CLI is announced"
assert_contains "$out" "NOT checked" "the report says the edit went unchecked"
mv "$ktlint_repo/build/ktlint/saved.txt" "$ktlint_repo/build/ktlint/cli-classpath.txt"

# --- a file that is not valid Kotlin still produces a report ---------------
# ktlint reports syntax errors with an EMPTY rule id, which the old parser
# rejected — the one edit guaranteed to fail gate 0 at the next stop was the
# one edit that produced no per-edit feedback.
broken="$ktlint_repo/core/domain/src/main/java/BrokenSyntax.kt"
printf 'package seed\n\nfun broken( {\n' > "$broken"
out=$(ktlint_run "$broken")
assert_eq "2" "$?" "a syntax error exits 2, the code that reaches Claude"
assert_contains "$out" "BrokenSyntax.kt" "the syntax-error report names the file"

# --- a classpath whose jars are gone is repaired, or announced --------------
# Gradle's cache GC can evict the jar the classpath file points at. The file
# still EXISTS, so an existence check alone kept trusting it while every
# ktlint launch crashed — and an unchecked exit status turned every crash
# into "clean". Case one: the jar is gone and re-resolving fails too.
printf '/nonexistent/ktlint-cli.jar\n' > "$ktlint_repo/build/ktlint/cli-classpath.txt"
printf '#!/bin/sh\nexit 1\n' > "$ktlint_repo/gradlew"
chmod +x "$ktlint_repo/gradlew"
out=$(ktlint_run "$wildcard")
assert_eq "0" "$?" "an evicted jar with no working gradlew does not block the edit"
assert_contains "$out" "gate 0 did not run" "the dead classpath is announced"
assert_contains "$out" "NOT checked" "and the edit is reported as unchecked"

# Case two: the classpath entry exists on disk but is not a runnable ktlint,
# so staleness cannot see it and the launch itself crashes. The crash must be
# reported, never read as a clean file.
bogus="$ktlint_repo/build/ktlint/bogus.jar"
: > "$bogus"
printf '%s\n' "$bogus" > "$ktlint_repo/build/ktlint/cli-classpath.txt"
out=$(ktlint_run "$wildcard")
assert_eq "2" "$?" "a crashing ktlint launch exits 2 rather than passing silently"
assert_contains "$out" "gate 0 did not run" "the crash is announced"
assert_contains "$out" "NOT checked" "and the edit is reported as unchecked"

# --- an EMPTY classpath file is stale, and a failed re-resolve reports ------
# The empty file used to fall through to a bare `exit 0` with no report at
# all — an unchecked edit that read as checked-and-clean, the hook's own
# named worst failure.
: > "$ktlint_repo/build/ktlint/cli-classpath.txt"
out=$(ktlint_run "$wildcard")
assert_eq "0" "$?" "an empty classpath file does not block the edit"
assert_contains "$out" "gate 0 did not run" "an empty classpath is announced"
assert_contains "$out" "NOT checked" "and the edit is reported as unchecked"

# --- the root build.gradle.kts is a staleness input -------------------------
# The ktlint dependency WIRING lives there, not in the catalog; an edit to it
# used to leave the hook running the OLD ktlint indefinitely while gate 0
# resolved live.
cp "$ktlint_classpath_file" "$ktlint_repo/build/ktlint/cli-classpath.txt"
# Backdated so the build file is newer by more than filesystem mtime
# granularity — a same-second pair makes -nt answer "not newer".
touch -t 202001010000 "$ktlint_repo/build/ktlint/cli-classpath.txt"
printf '// root build\n' > "$ktlint_repo/build.gradle.kts"
out=$(ktlint_run "$wildcard")
assert_eq "0" "$?" "a build-file-stale classpath with no working gradlew does not block"
assert_contains "$out" "gate 0 did not run" \
    "a root build.gradle.kts edit re-resolves the CLI rather than trusting the old one"

# --- no bypass exists -----------------------------------------------------
# Asserted as a property of the source rather than trusted: a skip switch
# added later would make every case above pass while checking nothing.
hook_source=$(cat "$HOOK_DIR/ktlint.sh")
assert_not_contains "$hook_source" "--baseline" "the hook configures no baseline"
assert_not_contains "$hook_source" "SKIP" "the hook has no skip switch"

prism_fixture_cleanup "$ktlint_repo" "$ktlint_worktree"

fi

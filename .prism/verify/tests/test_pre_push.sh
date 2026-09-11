# Tests for adapters/git/pre-push — the enforcement floor. Sourced by run-tests.sh.
#
# This file is the phase's real portability check, and the reason is worth
# stating plainly: every other gate test in this suite constructs a Claude Code
# event and feeds it in on stdin. A green suite therefore could not tell
# "portable" from "renamed but still Claude-only" — the oracle had a blind spot
# shaped exactly like the thing the phase claims to have fixed.
#
# The cases below are the first drivers in this project's history that supply no
# harness event at all.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

# Resolved, not merely joined: the installer prints the adapter's absolute path
# and an assertion comparing that against a string still carrying `/../` fails
# for a reason that has nothing to do with the installer.
PP_ADAPTER_DIR=$(CDPATH= cd -- "$HOOK_DIR/../adapters/git" && pwd -P)
PP_ADAPTER="$PP_ADAPTER_DIR/pre-push"
PP_INSTALL="$PP_ADAPTER_DIR/install.sh"

pp_event() {
    printf '{"tool_name":"Bash","tool_input":{"command":"git push"}}'
}

# --- 6.4: a failing push is denied through pre-push, with nothing on stdin ---
pp_repo=$(prism_fixture_repo)
prism_fixture_branch_commit "$pp_repo"
prism_fixture_gradlew "$pp_repo"
sh "$PP_INSTALL" "$pp_repo" >/dev/null 2>&1
assert_eq "0" "$?" "the installer exits 0 on a repository with no pre-push hook"
assert_eq "yes" \
    "$([ -x "$pp_repo/.git/hooks/pre-push" ] && echo yes || echo no)" \
    "and leaves an executable hook where git will look for it"

printf '1' > "$pp_repo/.fixture-gradle-exit"

# No PRISM_ROOT, no event, and stdin explicitly closed. The hook has to derive
# everything it needs from the repository it is standing in, which is the whole
# claim being tested.
pp_out=$(cd "$pp_repo" && sh .git/hooks/pre-push origin \
    "$pp_repo" </dev/null 2>&1)
pp_status=$?
assert_eq "2" "$pp_status" "a failing push is denied through pre-push"
assert_contains "$pp_out" "PUSH DENIED" "and says so"
assert_not_contains "$pp_out" "could not read the Bash event" \
    "the floor never reaches the event parser it does not need"

# The converse: the floor gets out of the way when there is nothing to verify.
# Without it the denial above would pass just as well against a hook that
# refused every push.
#
# A separate fixture, because "nothing to verify" is a property of the range: a
# commit touching no module derives an empty module list and the gate allows
# before running anything. Making the same fixture ALLOW after it has denied
# would mean producing a current coverage report for it, which is a test about
# gate 3 and not about the floor.
pp_allow_repo=$(prism_fixture_repo)
sh "$PP_INSTALL" "$pp_allow_repo" >/dev/null 2>&1
git -C "$pp_allow_repo" checkout -q -b pp-docs-only
mkdir -p "$pp_allow_repo/docs"
printf 'notes\n' > "$pp_allow_repo/docs/notes.md"
git -C "$pp_allow_repo" -c user.email=fixture@example.com -c user.name=fixture \
    add -A
git -C "$pp_allow_repo" -c user.email=fixture@example.com -c user.name=fixture \
    -c commit.gpgsign=false commit -qm 'docs only'
prism_fixture_gradlew "$pp_allow_repo"
printf '1' > "$pp_allow_repo/.fixture-gradle-exit"
(cd "$pp_allow_repo" && sh .git/hooks/pre-push origin "$pp_allow_repo" \
    </dev/null >/dev/null 2>&1)
assert_eq "0" "$?" \
    "a push touching no module is allowed through pre-push, running no gate"

# A deletion ships no commits, so there is nothing to gate — the same call the
# harness path makes when push_detect classifies `--delete` as skip. Asserted
# with the gates set to FAIL, so an exit 0 can only mean they were never run.
printf '1' > "$pp_repo/.fixture-gradle-exit"
(cd "$pp_repo" && sh .git/hooks/pre-push origin "$pp_repo" \
    <<'REFS' >/dev/null 2>&1
(delete) 0000000000000000000000000000000000000000 refs/heads/gone 1111111111111111111111111111111111111111
REFS
)
assert_eq "0" "$?" "a push that only deletes a ref runs no gate"

# An engine that is not there is a denial, not a silent pass. A hook that
# cannot find its gates has verified nothing, and that is the one thing it
# exists to say.
mv "$pp_repo/.prism/verify" "$pp_repo/.prism/verify-moved"
pp_out=$(cd "$pp_repo" && sh .git/hooks/pre-push origin "$pp_repo" \
    </dev/null 2>&1)
assert_eq "2" "$?" "a missing engine denies the push"
assert_contains "$pp_out" "engine" "and the denial says what is missing"
mv "$pp_repo/.prism/verify-moved" "$pp_repo/.prism/verify"

# --- 6.5: the floor and the harness gate agree on the same repository -------
#
# Two entry points to one verdict is two chances to disagree, and a floor that
# denied what the harness allowed (or worse, the reverse) would be a second
# policy wearing the first one's name. Both are driven against the SAME fixture
# in the same state, back to back.
PP_HARNESS="$pp_repo/.prism/verify/push-gate.sh"

for pp_exit in 1 0; do
    printf '%s' "$pp_exit" > "$pp_repo/.fixture-gradle-exit"

    pp_out=$(cd "$pp_repo" && sh .git/hooks/pre-push origin "$pp_repo" \
        </dev/null 2>&1)
    pp_floor_status=$?

    pp_harness_out=$(cd "$pp_repo" && pp_event | sh "$PP_HARNESS" 2>&1)
    pp_harness_status=$?

    assert_eq "$pp_harness_status" "$pp_floor_status" \
        "floor and harness reach the same verdict with gradle exiting $pp_exit"
    assert_eq "$pp_harness_out" "$pp_out" \
        "and say the same thing about it with gradle exiting $pp_exit"
done

# The same equivalence on the allowing state. Agreeing to deny is half the
# claim; two entry points that agree only when the answer is no would still be
# two policies.
pp_out=$(cd "$pp_allow_repo" && sh .git/hooks/pre-push origin \
    "$pp_allow_repo" </dev/null 2>&1)
pp_floor_status=$?
pp_harness_out=$(cd "$pp_allow_repo" && pp_event | \
    sh "$pp_allow_repo/.prism/verify/push-gate.sh" 2>&1)
pp_harness_status=$?
assert_eq "$pp_harness_status" "$pp_floor_status" \
    "floor and harness agree when there is nothing to verify"
assert_eq "0" "$pp_floor_status" "and both allow"

prism_fixture_cleanup "$pp_repo"
rm -rf "$pp_repo" "$pp_allow_repo"

# --- 6.6: installation is non-clobbering and idempotent ---------------------
pp_install_repo=$(prism_fixture_repo)

sh "$PP_INSTALL" "$pp_install_repo" >/dev/null 2>&1
assert_eq "0" "$?" "the first install succeeds"

pp_out=$(sh "$PP_INSTALL" "$pp_install_repo" 2>&1)
assert_eq "0" "$?" "installing twice succeeds"
assert_contains "$pp_out" "no changes" \
    "and reports no changes rather than reinstalling"

# Somebody else's hook. A pre-push hook is somebody else's enforcement — a
# secret scanner, a message check — and overwriting it would make prism-verify
# the reason their protection stopped running.
printf '#!/bin/sh\necho not ours\n' > "$pp_install_repo/.git/hooks/pre-push"
pp_before=$(cat "$pp_install_repo/.git/hooks/pre-push")
pp_out=$(sh "$PP_INSTALL" "$pp_install_repo" 2>&1)
assert_eq "1" "$?" "a foreign pre-push hook makes the install fail"
assert_contains "$pp_out" "NOT replaced" "and the collision is reported"
assert_contains "$pp_out" "$PP_ADAPTER" \
    "and the report names the hook to chain from"
assert_eq "$pp_before" "$(cat "$pp_install_repo/.git/hooks/pre-push")" \
    "the foreign hook is left byte-for-byte alone"

# A stale copy of OUR hook is replaced, not treated as a collision: the marker
# is what distinguishes "somebody else's" from "an older version of this".
printf '#!/bin/sh\n# PRISM_ADAPTER_MARKER: prism-verify/adapters/git/pre-push\nexit 0\n' \
    > "$pp_install_repo/.git/hooks/pre-push"
pp_out=$(sh "$PP_INSTALL" "$pp_install_repo" 2>&1)
assert_eq "0" "$?" "an outdated prism hook is upgraded rather than refused"
assert_contains "$pp_out" "updated" "and the upgrade says so"
assert_eq "0" "$(cmp -s "$PP_ADAPTER" "$pp_install_repo/.git/hooks/pre-push" \
    && echo 0 || echo 1)" \
    "the installed hook matches the shipped one"

rm -rf "$pp_install_repo"
